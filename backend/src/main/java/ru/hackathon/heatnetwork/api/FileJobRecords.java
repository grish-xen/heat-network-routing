package ru.hackathon.heatnetwork.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Development storage without a database: {@code <id>.json} and {@code <id>.variants.json}. */
final class FileJobRecords implements JobRecords {
    private final Path directory;
    private final ObjectMapper mapper;

    FileJobRecords(Path directory, ObjectMapper mapper) {
        this.directory = directory;
        this.mapper = mapper;
    }

    private Path path(String id, String extension) {
        if (!FileJobStore.validId(id)) throw new IllegalArgumentException("Invalid server job ID");
        return directory.resolve(id + extension);
    }

    @Override public void save(JobView job) throws IOException {
        Path temporary = path(job.jobId, ".json.tmp");
        try {
            FileJobStore.StoredJob stored = new FileJobStore.StoredJob();
            stored.job = job;
            stored.updatedAt = Instant.now();
            mapper.writeValue(temporary.toFile(), stored);
            FilePublication.publish(temporary, path(job.jobId, ".json"));
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override public Optional<FileJobStore.StoredJob> find(String id) throws IOException {
        FileJobStore.StoredJob stored;
        try (InputStream input = Files.newInputStream(path(id, ".json"))) {
            stored = mapper.readValue(input, FileJobStore.StoredJob.class);
        } catch (NoSuchFileException absent) {
            return Optional.empty();
        }
        return Optional.of(stored);
    }

    @Override public void saveVariants(String id, List<VariantSummaryView> views) throws IOException {
        Path temporary = path(id, ".variants.json.tmp");
        try {
            mapper.writeValue(temporary.toFile(), views);
            FilePublication.publish(temporary, path(id, ".variants.json"));
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override public Optional<List<VariantSummaryView>> variants(String id) throws IOException {
        try (InputStream input = Files.newInputStream(path(id, ".variants.json"))) {
            return Optional.of(Arrays.asList(mapper.readValue(input, VariantSummaryView[].class)));
        } catch (NoSuchFileException absent) {
            return Optional.empty();
        }
    }

    @Override public void deleteVariants(String id) throws IOException {
        Files.deleteIfExists(path(id, ".variants.json.tmp"));
        Files.deleteIfExists(path(id, ".variants.json"));
    }

    @Override public void delete(String id) throws IOException {
        deleteVariants(id);
        Files.deleteIfExists(path(id, ".json.tmp"));
        Files.deleteIfExists(path(id, ".json"));
    }

    @Override public List<String> ids() throws IOException {
        List<String> result = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.json")) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                String id = name.substring(0, name.length() - 5);
                if (FileJobStore.validId(id)) result.add(id);
            }
        }
        return result;
    }
}
