package ru.hackathon.heatnetwork.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import ru.hackathon.heatnetwork.model.ObjectId;

/** Job metadata in the database schema (H2 in PostgreSQL mode; Compose CI runs PostgreSQL 16). */
class JdbcJobRecordsTest {
    @TempDir Path temporary;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private JdbcTemplate jdbc;
    private JdbcJobRecords records;

    @BeforeEach void database() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/schema.sql")).execute(dataSource);
        new ResourceDatabasePopulator(new ClassPathResource("db/schema.sql")).execute(dataSource); // idempotent
        jdbc = new JdbcTemplate(dataSource);
        records = new JdbcJobRecords(jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)), mapper);
    }

    private static String id() { return UUID.randomUUID().toString(); }

    private static VariantSummaryView summary(int rank, String score) {
        VariantSummaryView view = new VariantSummaryView();
        view.id = new ObjectId(TextNode.valueOf("hnr-" + rank));
        view.objectType = "variant_summary";
        view.variantId = new ObjectId(TextNode.valueOf("variant-" + rank));
        view.rank = rank;
        view.constructionCost = new BigDecimal("26391400.00");
        view.chamberConstructionCost = new BigDecimal("3000000");
        view.existingChamberTieInCount = 1;
        view.existingChamberTieInCost = new BigDecimal("5000000");
        view.unconnectedPenalty = new BigDecimal("105000000.00");
        view.calculatedCost = new BigDecimal("131391400.00");
        view.newNetworkLength = 200.0;
        view.score = new BigDecimal(score);
        view.unconnectedOksIds = List.of(new ObjectId(IntNode.valueOf(1)), new ObjectId(TextNode.valueOf("1")));
        return view;
    }

    @Test void statusIsInsertedThenUpdated() throws Exception {
        String id = id();
        records.save(new JobView(id, JobView.Status.QUEUED, JobView.Stage.QUEUED, "depth", List.of()));
        FileJobStore.StoredJob queued = records.find(id).orElseThrow();
        assertEquals(JobView.Status.QUEUED, queued.job.status);
        assertEquals("depth", queued.job.mode);
        Thread.sleep(5);
        records.save(queued.job.failed(List.of(new ApiError("INVALID_INPUT", "Ошибка входа"))));
        FileJobStore.StoredJob failed = records.find(id).orElseThrow();
        assertEquals(JobView.Stage.FAILED, failed.job.stage);
        assertEquals("Ошибка входа", failed.job.diagnostics.get(0).message);
        assertTrue(failed.updatedAt.isAfter(queued.updatedAt));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM heat_jobs", Integer.class));
        assertEquals(List.of(id), records.ids());
    }

    @Test void summariesKeepExactNumbersAndIdTypes() throws Exception {
        String id = id();
        records.save(new JobView(id, JobView.Status.RUNNING, JobView.Stage.EXPORTING, "2d", List.of()));
        records.saveVariants(id, List.of(summary(1, "1.3389592000"), summary(2, "6.5389592001")));
        List<VariantSummaryView> read = records.variants(id).orElseThrow();
        assertEquals(2, read.size());
        assertEquals(0, new BigDecimal("1.3389592000").compareTo(read.get(0).score));
        assertEquals(0, new BigDecimal("131391400.00").compareTo(read.get(0).calculatedCost));
        assertTrue(read.get(0).unconnectedOksIds.get(0).value().isNumber());
        assertTrue(read.get(0).unconnectedOksIds.get(1).value().isTextual());
        assertEquals("variant-2", read.get(1).variantId.value().asText());
        assertEquals(0, new BigDecimal("6.5389592001").compareTo(jdbc.queryForObject(
                "SELECT score FROM heat_job_variants WHERE job_id = ? AND variant_rank = 2", BigDecimal.class, id)));
        assertEquals(2, jdbc.queryForObject(
                "SELECT unconnected_count FROM heat_job_variants WHERE job_id = ? AND variant_rank = 1", Integer.class, id));

        records.saveVariants(id, List.of(summary(1, "2.0")));
        assertEquals(1, records.variants(id).orElseThrow().size(), "replaced, not appended");
        records.deleteVariants(id);
        assertTrue(records.variants(id).isEmpty());
        records.delete(id);
        assertTrue(records.find(id).isEmpty());
    }

    @Test void storeKeepsMetadataInTheDatabaseAndRecoversAfterRestart() throws Exception {
        String running = id();
        String done = id();
        try (FileJobStore store = new FileJobStore(temporary, mapper, records)) {
            store.save(new JobView(running, JobView.Status.RUNNING, JobView.Stage.ROUTING, "2d", List.of()));
            store.save(new JobView(done, JobView.Status.SUCCEEDED, JobView.Stage.DONE, "2d", List.of()));
        }
        try (var files = Files.list(temporary)) {
            assertFalse(files.anyMatch(file -> file.getFileName().toString().endsWith(".json")),
                    "no JSON status files next to the job files");
        }
        try (FileJobStore restarted = new FileJobStore(temporary, mapper, records)) {
            restarted.recover();
            JobView interrupted = restarted.get(running);
            assertEquals(JobView.Status.FAILED, interrupted.status);
            assertEquals("SERVER_RESTARTED", interrupted.diagnostics.get(0).code);
            JobView lost = restarted.get(done);
            assertEquals("RESULT_UNAVAILABLE", lost.diagnostics.get(0).code, "SUCCEEDED without a result file");
        }
    }
}
