package ru.hackathon.heatnetwork.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Job metadata in PostgreSQL (schema {@code db/schema.sql}). Status and diagnostics are columns of
 * {@code heat_jobs}; each exported variant summary is a row of {@code heat_job_variants} with the official
 * values as exact JSON plus queryable score, cost, length and unconnected count.
 */
final class JdbcJobRecords implements JobRecords {
    private static final TypeReference<List<ApiError>> ERRORS = new TypeReference<List<ApiError>>() { };

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;

    JdbcJobRecords(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.mapper = mapper.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
    }

    @Override public void save(JobView job) throws IOException {
        String diagnostics = mapper.writeValueAsString(job.diagnostics);
        Timestamp now = Timestamp.from(Instant.now());
        transactions.executeWithoutResult(status -> {
            int updated = jdbc.update("UPDATE heat_jobs SET status = ?, stage = ?, mode = ?, diagnostics = ?, updated_at = ?"
                    + " WHERE job_id = ?", job.status.name(), job.stage.name(), job.mode, diagnostics, now, job.jobId);
            if (updated == 0) {
                jdbc.update("INSERT INTO heat_jobs (job_id, status, stage, mode, diagnostics, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)", job.jobId, job.status.name(), job.stage.name(), job.mode,
                        diagnostics, now, now);
            }
        });
    }

    @Override public Optional<FileJobStore.StoredJob> find(String id) throws IOException {
        try {
            return jdbc.query("SELECT status, stage, mode, diagnostics, updated_at FROM heat_jobs WHERE job_id = ?",
                    (row, index) -> {
                        FileJobStore.StoredJob stored = new FileJobStore.StoredJob();
                        stored.job = new JobView(id, JobView.Status.valueOf(row.getString("status")),
                                JobView.Stage.valueOf(row.getString("stage")), row.getString("mode"),
                                read(row.getString("diagnostics"), ERRORS));
                        stored.updatedAt = row.getTimestamp("updated_at").toInstant();
                        return stored;
                    }, id).stream().findFirst();
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
    }

    @Override public void saveVariants(String id, List<VariantSummaryView> views) throws IOException {
        String[] summaries = new String[views.size()];
        for (int i = 0; i < views.size(); i++) summaries[i] = mapper.writeValueAsString(views.get(i));
        transactions.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM heat_job_variants WHERE job_id = ?", id);
            for (int i = 0; i < views.size(); i++) {
                VariantSummaryView view = views.get(i);
                jdbc.update("INSERT INTO heat_job_variants (job_id, variant_rank, variant_id, score, calculated_cost,"
                        + " new_network_length, unconnected_count, summary) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        id, view.rank, view.variantId.value().asText(), view.score, view.calculatedCost,
                        view.newNetworkLength, view.unconnectedOksIds == null ? 0 : view.unconnectedOksIds.size(),
                        summaries[i]);
            }
        });
    }

    @Override public Optional<List<VariantSummaryView>> variants(String id) throws IOException {
        try {
            List<VariantSummaryView> views = jdbc.query(
                    "SELECT summary FROM heat_job_variants WHERE job_id = ? ORDER BY variant_rank",
                    (row, index) -> read(row.getString("summary"), VariantSummaryView.class), id);
            return views.isEmpty() ? Optional.empty() : Optional.of(views);
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
    }

    @Override public void deleteVariants(String id) {
        jdbc.update("DELETE FROM heat_job_variants WHERE job_id = ?", id);
    }

    @Override public void delete(String id) {
        transactions.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM heat_job_variants WHERE job_id = ?", id);
            jdbc.update("DELETE FROM heat_jobs WHERE job_id = ?", id);
        });
    }

    @Override public List<String> ids() {
        return jdbc.queryForList("SELECT job_id FROM heat_jobs", String.class);
    }

    private <T> T read(String json, Class<T> type) {
        try { return mapper.readValue(json, type); }
        catch (IOException exception) { throw new UncheckedIOException(exception); }
    }

    private <T> T read(String json, TypeReference<T> type) {
        try { return mapper.readValue(json, type); }
        catch (IOException exception) { throw new UncheckedIOException(exception); }
    }
}
