package ru.hackathon.heatnetwork.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import javax.sql.DataSource;

/** Full HTTP flow with job metadata in the database (H2 in PostgreSQL mode stands in for PostgreSQL). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JobApiDatabaseTest {
    @TempDir static Path temporary;
    @Autowired TestRestTemplate http;
    @Autowired DataSource dataSource;
    @Autowired JobRecords records;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("heat-network.jobs.storage-directory", () -> temporary.resolve("jobs").toString());
        registry.add("heat-network.input.storage-directory", () -> temporary.resolve("datasets").toString());
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:heat-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        registry.add("spring.datasource.username", () -> "sa");
    }

    @Test void jobStatusAndSummariesAreStoredInTheDatabase() throws Exception {
        assertTrue(records instanceof JdbcJobRecords);
        byte[] fixture;
        try (InputStream input = getClass().getResourceAsStream("/fixtures/synthetic/two-consumers/input.geojson")) {
            fixture = input.readAllBytes();
        }
        LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(fixture) { @Override public String getFilename() { return "input.geojson"; } });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<JsonNode> accepted = http.postForEntity("/api/jobs", new HttpEntity<>(body, headers), JsonNode.class);
        assertEquals(202, accepted.getStatusCodeValue());
        String id = accepted.getBody().path("jobId").asText();
        JsonNode job = accepted.getBody();
        for (int i = 0; i < 600 && !"SUCCEEDED".equals(job.path("status").asText())
                && !"FAILED".equals(job.path("status").asText()); i++) {
            Thread.sleep(100);
            job = http.getForObject("/api/jobs/" + id, JsonNode.class);
        }
        assertEquals("SUCCEEDED", job.path("status").asText(), job.toString());

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT status FROM heat_jobs WHERE job_id = ?", String.class, id));
        JsonNode variants = http.getForObject("/api/jobs/" + id + "/variants", JsonNode.class);
        assertEquals(variants.size(), jdbc.queryForObject(
                "SELECT COUNT(*) FROM heat_job_variants WHERE job_id = ?", Integer.class, id));
        BigDecimal score = jdbc.queryForObject(
                "SELECT score FROM heat_job_variants WHERE job_id = ? AND variant_rank = 1", BigDecimal.class, id);
        assertEquals(0, variants.get(0).path("score").decimalValue().compareTo(score));
        assertEquals(200, http.getForEntity("/api/jobs/" + id + "/result", byte[].class).getStatusCodeValue());
        try (Stream<Path> files = Files.list(temporary.resolve("jobs"))) {
            assertFalse(files.anyMatch(file -> file.getFileName().toString().endsWith(".json")),
                    "status and summaries are not kept as JSON files when the database is configured");
        }
    }
}
