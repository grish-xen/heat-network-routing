package ru.hackathon.heatnetwork.api;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.Arrays;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.hackathon.heatnetwork.model.Dataset;
import ru.hackathon.heatnetwork.model.Model.CalculatedVariant;
import ru.hackathon.heatnetwork.output.ResultExporter;

/** Small durable status files, one at a time; no in-memory registry of completed jobs. */
final class FileJobStore implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(FileJobStore.class);
    private static final Pattern ID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final List<String> MAP_FILES = List.of(".map-input.data", ".map-input.index", ".map-result.data", ".map-result.index");
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

    /** Export is private until both files exist and the caller persists SUCCEEDED. */
    void writeResult(String id, Dataset dataset, List<CalculatedVariant> variants, ResultExporter exporter) throws IOException {
        Path temporary = path(id, ".result.geojson.tmp");
        Path summaries = path(id, ".variants.json.tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW)) {
                exporter.write(dataset, variants, output);
            }
            CalculationCoordinator.interrupted();
            List<VariantSummaryView> views = ResultSummaries.read(temporary, mapper);
            if (views.size() != variants.size()) throw new IOException("Missing exported summary");
            mapper.writeValue(summaries.toFile(), views);
            CalculationCoordinator.interrupted();
            publish(temporary, path(id, ".result.geojson"));
            publish(summaries, path(id, ".variants.json"));
        } finally {
            Files.deleteIfExists(temporary);
            Files.deleteIfExists(summaries);
        }
    }

    private static void publish(Path temporary, Path target) throws IOException {
        FilePublication.publish(temporary, target);
    }

    void writeMaps(String id) throws IOException {
        List<VariantSummaryView> views;
        try (InputStream input = Files.newInputStream(path(id, ".variants.json"))) {
            views = Arrays.asList(mapper.readValue(input, VariantSummaryView[].class));
        }
        List<String> variants = new java.util.ArrayList<>();
        for (VariantSummaryView view : views) variants.add(view.variantId.value().textValue());
        try {
            MapArchive.build(input(id), path(id, ".map-input.data.tmp"), path(id, ".map-input.index.tmp"), null, mapper);
            MapArchive.build(path(id, ".result.geojson"), path(id, ".map-result.data.tmp"), path(id, ".map-result.index.tmp"), variants, mapper);
            CalculationCoordinator.interrupted();
            for (String suffix : MAP_FILES) publish(path(id, suffix + ".tmp"), path(id, suffix));
        } finally {
            for (String suffix : MAP_FILES) Files.deleteIfExists(path(id, suffix + ".tmp"));
        }
    }

    // Acquire the file handles before retention cleanup can delete them; scanning does not hold this monitor.
    synchronized MapArchive.Reader openMap(String id, MapQuery query) throws IOException {
        return openMap(id, query.layer);
    }

    synchronized MapArchive.Reader openMap(String id, String layer) throws IOException {
        requireResult(id);
        if (!"input".equals(layer) && !"result".equals(layer)) throw new IllegalArgumentException("Invalid map layer");
        String prefix = ".map-" + layer;
        if (!Files.isRegularFile(path(id, prefix + ".data")) || !Files.isRegularFile(path(id, prefix + ".index"))) {
            throw new ApiException(409, "MAP_DATA_UNAVAILABLE", "Данные карты не сохранены для этой задачи. Загрузите исходный файл повторно.");
        }
        return new MapArchive.Reader(path(id, prefix + ".data"), path(id, prefix + ".index"));
    }

    synchronized int mapVariant(String id, MapQuery query) throws IOException {
        requireResult(id);
        if ("input".equals(query.layer)) return 0;
        return mapVariant(id, query.variantId);
    }

    synchronized int mapVariant(String id, String variantId) throws IOException {
        List<VariantSummaryView> views = variants(id);
        for (int i = 0; i < views.size(); i++) {
            if (variantId.equals(views.get(i).variantId.value().textValue())) return i + 1;
        }
        throw new ApiException(404, "VARIANT_NOT_FOUND", "Вариант не найден в этой задаче.");
    }

    synchronized List<VariantSummaryView> variants(String id) throws IOException {
        requireResult(id);
        try (InputStream input = Files.newInputStream(path(id, ".variants.json"))) {
            return Arrays.asList(mapper.readValue(input, VariantSummaryView[].class));
        }
    }

    synchronized Download download(String id) throws IOException {
        requireResult(id);
        Path result = path(id, ".result.geojson");
        long size = Files.size(result);
        return new Download(Files.newInputStream(result), size);
    }

    private void requireResult(String id) throws IOException {
        if (!validId(id) || !Files.isRegularFile(path(id, ".json"))) {
            throw new ApiException(404, "JOB_NOT_FOUND", "Задача не найдена или срок её хранения истёк.");
        }
        if (get(id).status != JobView.Status.SUCCEEDED) {
            throw new ApiException(409, "RESULT_NOT_READY", "Результат доступен только после успешного завершения задачи.");
        }
    }

    static final class Download {
        final InputStream stream;
        final long length;
        Download(InputStream stream, long length) { this.stream = stream; this.length = length; }
    }

    void removeResult(String id) throws IOException {
        for (String suffix : List.of(".result.geojson.tmp", ".variants.json.tmp", ".result.geojson", ".variants.json")) {
            Files.deleteIfExists(path(id, suffix));
        }
        for (String suffix : MAP_FILES) {
            Files.deleteIfExists(path(id, suffix + ".tmp"));
            Files.deleteIfExists(path(id, suffix));
        }
    }

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
            publish(temporary, path(job.jobId, ".json"));
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    void removeInput(String id) throws IOException { Files.deleteIfExists(input(id)); }

    synchronized void delete(String id) throws IOException {
        removeInput(id);
        removeResult(id);
        Files.deleteIfExists(path(id, ".json.tmp"));
        Files.deleteIfExists(path(id, ".json"));
    }

    synchronized void recover() throws IOException {
        forEachStatus(id -> {
            try {
                JobView job = get(id);
                if (!job.terminal()) {
                    job = job.failed(List.of(new ApiError("SERVER_RESTARTED",
                            "Сервер был перезапущен до завершения задачи. Загрузите файл повторно.")));
                    save(job);
                }
                if (job.status == JobView.Status.SUCCEEDED
                        && (!Files.isRegularFile(path(id, ".result.geojson")) || !Files.isRegularFile(path(id, ".variants.json")))) {
                    job = job.failed(List.of(new ApiError("RESULT_UNAVAILABLE", "Сохранённый результат утрачен. Загрузите файл повторно.")));
                    save(job);
                }
                if (job.status != JobView.Status.SUCCEEDED) removeResult(id);
                removeInput(id);
            } catch (IOException exception) { LOG.error("Cannot recover job {}", id, exception); }
        });
        // Only exact server UUID filenames are eligible; unrelated files are untouched.
        for (String suffix : List.of(".geojson", ".json.tmp", ".result.geojson.tmp", ".variants.json.tmp",
                ".map-input.data.tmp", ".map-input.index.tmp", ".map-result.data.tmp", ".map-result.index.tmp")) {
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
