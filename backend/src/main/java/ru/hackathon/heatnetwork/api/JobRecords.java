package ru.hackathon.heatnetwork.api;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Durable job metadata: status snapshots and exported variant summaries. PostgreSQL in deployment
 * ({@link JdbcJobRecords}); small JSON files when no database is configured ({@link FileJobRecords}).
 * Large files (uploads, result GeoJSON, map archives) stay in the job storage directory.
 */
interface JobRecords {
    /** Inserts or replaces the status and stamps the update time. */
    void save(JobView job) throws IOException;

    Optional<FileJobStore.StoredJob> find(String id) throws IOException;

    /** Replaces the summaries of a job atomically; called once the result file is published. */
    void saveVariants(String id, List<VariantSummaryView> views) throws IOException;

    /** Empty when the job has no stored summaries. */
    Optional<List<VariantSummaryView>> variants(String id) throws IOException;

    void deleteVariants(String id) throws IOException;

    /** Removes the status and the summaries. */
    void delete(String id) throws IOException;

    List<String> ids() throws IOException;
}
