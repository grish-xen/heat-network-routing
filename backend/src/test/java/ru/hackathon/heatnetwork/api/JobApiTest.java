package ru.hackathon.heatnetwork.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import static ru.hackathon.heatnetwork.api.TestPolling.eventually;
import static org.junit.jupiter.api.Assertions.*;

/** Real embedded Tomcat: multipart parsing and its size limits are not mocked. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "heat-network.jobs.max-file-bytes=32768")
@DirtiesContext
class JobApiTest {
    @TempDir static Path temporary;
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper mapper;

    @DynamicPropertySource
    static void directories(DynamicPropertyRegistry registry) {
        registry.add("heat-network.jobs.storage-directory", () -> temporary.resolve("jobs").toString());
        registry.add("heat-network.input.storage-directory", () -> temporary.resolve("datasets").toString());
    }

    @Test void uploadReturns202AndLocationThenActualValidationState() throws Exception {
        byte[] fixture;
        try (InputStream input = getClass().getResourceAsStream("/fixtures/synthetic/two-consumers/input.geojson")) {
            assertNotNull(input);
            fixture = input.readAllBytes();
        }
        ResponseEntity<JsonNode> accepted = upload(body(fixture), null);
        assertEquals(202, accepted.getStatusCodeValue());
        JsonNode job = accepted.getBody();
        assertNotNull(job);
        assertEquals("QUEUED", job.path("status").asText());
        assertEquals("QUEUED", job.path("stage").asText());
        assertEquals("2d", job.path("mode").asText());
        assertTrue(job.path("diagnostics").isArray());
        String location = "/api/jobs/" + job.path("jobId").asText();
        assertEquals(location, accepted.getHeaders().getLocation().toString());
        JsonNode result = completed(location);
        assertEquals("FAILED", result.path("status").asText());
        assertEquals("PROCESSING_UNAVAILABLE", result.at("/diagnostics/0/code").asText());
        assertEquals("no-store", http.getForEntity(location, JsonNode.class).getHeaders().getCacheControl());
        assertContract("/api/jobs", "post", "202", job);
        assertContract("/api/jobs/{jobId}", "get", "200", result);
    }

    @Test void invalidGeoJsonIsAnAsynchronousFailureWithUsefulDiagnostics() throws Exception {
        ResponseEntity<JsonNode> accepted = upload(body("{ broken json".getBytes(java.nio.charset.StandardCharsets.UTF_8)), "2d");
        assertEquals(202, accepted.getStatusCodeValue());
        JsonNode result = completed(accepted.getHeaders().getLocation().toString());
        assertEquals("INVALID_INPUT", result.at("/diagnostics/0/code").asText());
        assertFalse(result.toString().contains(temporary.toString()));
        assertContract("/api/jobs/{jobId}", "get", "200", result);
    }

    @Test void missingEmptyOrMultipleFilesAreRejectedBeforeJobCreation() {
        assertError(400, "INVALID_INPUT", upload(new LinkedMultiValueMap<>(), null));
        assertError(400, "INVALID_INPUT", upload(body(new byte[0]), null));
        MultiValueMap<String, Object> multiple = body(new byte[] {1});
        multiple.add("file", resource(new byte[] {2}));
        assertError(400, "INVALID_INPUT", upload(multiple, null));
    }

    @ParameterizedTest @ValueSource(strings = {"", "3d", "TWO_D", "2D"})
    void invalidModesAreNotSilentlyReplacedBy2d(String mode) {
        assertError(400, "INVALID_INPUT", upload(body(new byte[] {1}), mode));
    }

    @Test void depthAndDuplicateModesHaveExplicitErrors() {
        assertError(422, "UNSUPPORTED_MODE", upload(body(new byte[] {1}), "depth"));
        MultiValueMap<String, Object> duplicate = body(new byte[] {1});
        duplicate.add("mode", "2d");
        assertError(400, "INVALID_INPUT", upload(duplicate, "2d"));
    }

    @Test void servletEnforcesFileAndTotalRequestLimitsWithJson413() {
        assertError(413, "FILE_TOO_LARGE", upload(body(new byte[32769]), null));
        // Individual files fit, but their multipart request exceeds file limit + 1 MiB overhead.
        MultiValueMap<String, Object> many = new LinkedMultiValueMap<>();
        for (int i = 0; i < 34; i++) many.add("file", resource(new byte[32768]));
        assertError(413, "FILE_TOO_LARGE", upload(many, null));
    }

    @Test void unknownJobAndWrongContentTypeUseErrorSchema() throws Exception {
        ResponseEntity<JsonNode> missing = http.getForEntity("/api/jobs/" + UUID.randomUUID(), JsonNode.class);
        assertError(404, "JOB_NOT_FOUND", missing);
        assertContract("/api/jobs/{jobId}", "get", "404", missing.getBody());
        assertError(404, "JOB_NOT_FOUND", http.getForEntity("/api/jobs/invalid-id", JsonNode.class));
        assertError(415, "INVALID_INPUT", http.postForEntity("/api/jobs", "text", JsonNode.class));
    }

    @Test void liveSwaggerDescribesUploadAndStatusButNotPlannedMapAndResults() {
        JsonNode spec = http.getForObject("/v3/api-docs", JsonNode.class);
        assertNotNull(spec);
        assertEquals("createJob", spec.at("/paths/~1api~1jobs/post/operationId").asText());
        assertTrue(spec.at("/paths/~1api~1jobs/post/responses").has("202"));
        assertTrue(spec.path("paths").has("/api/jobs/{jobId}"));
        assertFalse(spec.path("paths").has("/api/jobs/{jobId}/map"));
    }

    private JsonNode completed(String location) {
        eventually(() -> "FAILED".equals(http.getForObject(location, JsonNode.class).path("status").asText()));
        return http.getForObject(location, JsonNode.class);
    }

    private ResponseEntity<JsonNode> upload(MultiValueMap<String, Object> body, String mode) {
        if (mode != null) body.add("mode", mode);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return http.postForEntity("/api/jobs", new HttpEntity<>(body, headers), JsonNode.class);
    }

    private MultiValueMap<String, Object> body(byte[] bytes) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", resource(bytes));
        return body;
    }

    private ByteArrayResource resource(byte[] bytes) {
        return new ByteArrayResource(bytes) { @Override public String getFilename() { return "input.geojson"; } };
    }

    private void assertError(int status, String code, ResponseEntity<JsonNode> response) {
        assertEquals(status, response.getStatusCodeValue(), () -> String.valueOf(response.getBody()));
        assertNotNull(response.getBody());
        assertEquals(code, response.getBody().path("code").asText());
        assertFalse(response.getBody().path("message").asText().isBlank());
        assertFalse(response.getBody().has("trace"));
    }

    private void assertContract(String path, String method, String status, JsonNode body) throws Exception {
        JsonNode spec;
        try (InputStream input = getClass().getResourceAsStream("/contracts/openapi.json")) { spec = mapper.readTree(input); }
        JsonNode schema = spec.path("paths").path(path).path(method).path("responses").path(status)
                .path("content").path("application/json").path("schema");
        if (schema.has("$ref")) schema = spec.at(schema.path("$ref").asText().substring(1));
        assertTrue(schema.has("required"));
        for (JsonNode required : schema.path("required")) assertTrue(body.has(required.asText()), required.asText());
        for (String name : ListFields.JOB_ENUMS) {
            JsonNode allowed = schema.path("properties").path(name).path("enum");
            if (allowed.isArray()) {
                boolean present = false;
                for (JsonNode item : allowed) present |= item.equals(body.path(name));
                assertTrue(present, name);
            }
        }
    }

    private static final class ListFields {
        static final String[] JOB_ENUMS = {"status", "stage", "mode"};
    }
}
