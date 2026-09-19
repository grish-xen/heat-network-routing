package ru.hackathon.heatnetwork.api;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Small durable status files, one at a time; no in-memory registry of completed jobs. */
final class FileJobStore implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(FileJobStore.class);
    private static final Pattern ID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private final Path directory;
    private final ObjectMapper mapper;
    private final FileChannel lockChannel;
    private final FileLock lock;

    FileJobStore(Path directory, ObjectMapper mapper) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        this.mapper = mapper.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
        Files.createDirectories(this.directory);
        lockChannel = FileChannel.open(this.directory.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            lock = lockChannel.tryLock();
            if (lock == null) throw new IOException("Job storage is already used by another server");
        } catch (IOException | OverlappingFileLockException exception) {
            lockChannel.close();
            throw new IOException("Cannot exclusively open job storage", exception);
        }
    }

    static boolean validId(String id) { return id != null && ID.matcher(id).matches(); }

    private Path path(String id, String extension) {
        if (!validId(id)) throw new IllegalArgumentException("Invalid server job ID");
        return directory.resolve(id + extension);
    }

    Path input(String id) { return path(id, ".geojson"); }

    // Windows cannot always replace a file while another thread is reading it. Status I/O
    // is small and serialized; large uploads and Dataset work do not take this monitor.
    synchronized JobView get(String id) throws IOException { return read(id).job; }

    private StoredJob read(String id) throws IOException {
        StoredJob stored;
        try (InputStream input = Files.newInputStream(path(id, ".json"))) {
            stored = mapper.readValue(input, StoredJob.class);
        }
        if (stored.job == null || !id.equals(stored.job.jobId) || stored.updatedAt == null
                || stored.job.status == null || stored.job.stage == null) throw new IOException("Invalid stored job");
        return stored;
    }

    synchronized void save(JobView job) throws IOException {
        Path temporary = path(job.jobId, ".json.tmp");
        try {
            StoredJob stored = new StoredJob();
            stored.job = job;
            stored.updatedAt = Instant.now();
            mapper.writeValue(temporary.toFile(), stored);
            try {
                Files.move(temporary, path(job.jobId, ".json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path(job.jobId, ".json"), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    void removeInput(String id) throws IOException { Files.deleteIfExists(input(id)); }

    synchronized void delete(String id) throws IOException {
        removeInput(id);
        Files.deleteIfExists(path(id, ".json.tmp"));
        Files.deleteIfExists(path(id, ".json"));
    }

    synchronized void recover() throws IOException {
        forEachStatus(id -> {
            try {
                JobView job = get(id);
                if (!job.terminal()) save(job.failed(List.of(new ApiError("SERVER_RESTARTED",
                        "Сервер был перезапущен до завершения задачи. Загрузите файл повторно."))));
                removeInput(id);
            } catch (IOException exception) { LOG.error("Cannot recover job {}", id, exception); }
        });
        // Only exact server UUID filenames are eligible; unrelated files are untouched.
        for (String suffix : List.of(".geojson", ".json.tmp")) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*" + suffix)) {
                for (Path file : files) {
                    String name = file.getFileName().toString();
                    if (validId(name.substring(0, name.length() - suffix.length()))) Files.deleteIfExists(file);
                }
            }
        }
    }

    synchronized void expire(Instant cutoff, Set<String> active) throws IOException {
        forEachStatus(id -> {
            if (active.contains(id)) return;
            try {
                StoredJob stored = read(id);
                if (stored.job.terminal() && stored.updatedAt.isBefore(cutoff)) delete(id);
            } catch (IOException exception) { LOG.error("Cannot expire job {}", id, exception); }
        });
    }

    private void forEachStatus(Consumer<String> action) throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.json")) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                String id = name.substring(0, name.length() - 5);
                if (validId(id)) action.accept(id);
            }
        }
    }

    @Override public void close() throws IOException {
        try { lock.release(); } finally { lockChannel.close(); }
    }

    public static final class StoredJob {
        public JobView job;
        public Instant updatedAt;
    }
}
