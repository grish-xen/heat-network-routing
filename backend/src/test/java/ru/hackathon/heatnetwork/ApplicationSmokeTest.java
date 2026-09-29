package ru.hackathon.heatnetwork;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "heat-network.jobs.storage-directory=${java.io.tmpdir}/heat-smoke-${random.uuid}")
class ApplicationSmokeTest {
    @Autowired private TestRestTemplate http;
    @Autowired private ru.hackathon.heatnetwork.output.ResultExporter exporter;

    @Test void startsServerAndDocumentsTheImplementedHealthEndpoint() {
        assertNotNull(exporter);
        ResponseEntity<JsonNode> health = http.getForEntity("/api/health", JsonNode.class);
        assertEquals(200, health.getStatusCodeValue());
        assertNotNull(health.getBody());
        assertEquals("2d+depth", health.getBody().get("implementation").asText());
        assertEquals("1.0", health.getBody().get("contractVersion").asText());
        ResponseEntity<JsonNode> spec = http.getForEntity("/v3/api-docs", JsonNode.class);
        assertEquals(200, spec.getStatusCodeValue());
        assertNotNull(spec.getBody());
        assertTrue(spec.getBody().get("paths").has("/api/health"));
    }
}
