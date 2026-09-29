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

    @Test void uploadCalculatesRealVariantsAndDownloadsMatchingGeoJson() throws Exception {
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
        assertEquals("SUCCEEDED", result.path("status").asText(), result.toString());
        assertEquals("DONE", result.path("stage").asText());
        ResponseEntity<JsonNode> variants = http.getForEntity(location + "/variants", JsonNode.class);
        assertEquals(200, variants.getStatusCodeValue());
        assertEquals("no-store", variants.getHeaders().getCacheControl());
        // Complete schemes of the planner strategies replace intermediate partial results.
        assertTrue(variants.getBody().size() >= 1 && variants.getBody().size() <= 3, variants.getBody().toString());
        java.util.Set<String> distinct = new java.util.HashSet<>();
        for (JsonNode summary : variants.getBody()) {
            assertEquals(0, summary.path("unconnected_oks_ids").size(), "no partial variant: " + summary);
            distinct.add(summary.path("new_network_length").asText() + "/" + summary.path("calculated_cost").asText());
        }
        assertEquals(variants.getBody().size(), distinct.size(), "variants differ in length or cost");
        String rawVariants = http.getForObject(location + "/variants", String.class);
        assertFalse(rawVariants.matches("(?s).*\\d[eE][+-]?\\d.*"), "no exponent notation in costs: " + rawVariants);
        ResponseEntity<byte[]> download = http.getForEntity(location + "/result", byte[].class);
        assertEquals(200, download.getStatusCodeValue());
        assertEquals("application/geo+json", download.getHeaders().getContentType().toString());
        assertTrue(download.getHeaders().getFirst("Content-Disposition").startsWith("attachment;"));
        assertEquals(download.getBody().length, download.getHeaders().getContentLength());
        JsonNode collection = mapper.readTree(download.getBody());
        assertEquals("FeatureCollection", collection.path("type").asText());
        int summaries = 0;
        for (JsonNode feature : collection.path("features")) {
            if ("variant_summary".equals(feature.at("/properties/object_type").asText())) {
                JsonNode view = variants.getBody().get(summaries++);
                JsonNode exported = feature.path("properties");
                assertEquals(exported.size(), view.size());
                exported.fields().forEachRemaining(field -> {
                    JsonNode actual = view.path(field.getKey());
                    if (field.getValue().isNumber()) assertEquals(0, field.getValue().decimalValue().compareTo(actual.decimalValue()), field.getKey());
                    else assertEquals(field.getValue(), actual, field.getKey());
                });
            }
        }
        assertEquals(variants.getBody().size(), summaries);
        String variantId = variants.getBody().get(0).path("variant_id").asText();
        java.util.List<JsonNode> inputFeatures = mapPages(location + "/map?layer=input&bbox=-180,-90,180,90&limit=2");
        java.util.List<JsonNode> expectedInput = new java.util.ArrayList<>();
        mapper.readTree(fixture).path("features").forEach(expectedInput::add);
        assertEquals(expectedInput, inputFeatures);
        java.util.List<JsonNode> resultFeatures = mapPages(location + "/map?layer=result&variantId=" + variantId + "&bbox=-180,-90,180,90&limit=1");
        java.util.List<JsonNode> expectedResult = new java.util.ArrayList<>();
        collection.path("features").forEach(feature -> {
            if (!feature.path("geometry").isNull() && variantId.equals(feature.at("/properties/variant_id").asText())) {
                expectedResult.add(feature);
            }
        });
        assertEquals(expectedResult, resultFeatures);
        ResponseEntity<JsonNode> bounds = http.getForEntity(location + "/map/bounds?variantId=" + variantId, JsonNode.class);
        assertEquals(200, bounds.getStatusCodeValue());
        assertEquals("no-store", bounds.getHeaders().getCacheControl());
        assertEquals(1, bounds.getBody().size());
        double[] expectedBounds = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (JsonNode feature : expectedInput) extendBounds(expectedBounds, feature.path("geometry").path("coordinates"));
        for (JsonNode feature : expectedResult) extendBounds(expectedBounds, feature.path("geometry").path("coordinates"));
        for (int i = 0; i < 4; i++) assertEquals(expectedBounds[i], bounds.getBody().path("bbox").get(i).asDouble());
        assertContract("/api/jobs/{jobId}/map/bounds", "get", "200", bounds.getBody());
        assertError(404, "VARIANT_NOT_FOUND", http.getForEntity(location + "/map/bounds?variantId=absent", JsonNode.class));
        assertError(400, "INVALID_VARIANT", http.getForEntity(location + "/map/bounds", JsonNode.class));
        assertError(400, "INVALID_VARIANT", http.getForEntity(location + "/map/bounds?variantId=", JsonNode.class));
        assertError(400, "INVALID_MAP_QUERY", http.getForEntity(location + "/map/bounds?variantId=a&variantId=b", JsonNode.class));
        assertError(400, "INVALID_MAP_QUERY", http.getForEntity(location + "/map/bounds?variantId=a&bbox=0,0,1,1", JsonNode.class));
        assertEquals(0, http.getForObject(location + "/map?layer=input&bbox=0,0,1,1", JsonNode.class).path("features").size());
        assertError(404, "VARIANT_NOT_FOUND", http.getForEntity(location + "/map?layer=result&variantId=absent&bbox=0,0,1,1", JsonNode.class));
        assertError(400, "INVALID_VARIANT", http.getForEntity(location + "/map?layer=result&bbox=0,0,1,1", JsonNode.class));
        assertError(400, "INVALID_BBOX", http.getForEntity(location + "/map?layer=input&bbox=0,0,0,1", JsonNode.class));
        assertError(400, "INVALID_MAP_QUERY", http.getForEntity(location + "/map?layer=input&bbox=0,0,1,1&limit=1&limit=2", JsonNode.class));
        assertEquals("no-store", http.getForEntity(location, JsonNode.class).getHeaders().getCacheControl());
        assertContract("/api/jobs", "post", "202", job);
        assertContract("/api/jobs/{jobId}", "get", "200", result);
    }

    @Test void invalidGeoJsonIsAnAsynchronousFailureWithUsefulDiagnostics() throws Exception {
        ResponseEntity<JsonNode> accepted = upload(body("{ broken json".getBytes(java.nio.charset.StandardCharsets.UTF_8)), "2d");
        assertEquals(202, accepted.getStatusCodeValue());
        JsonNode result = completed(accepted.getHeaders().getLocation().toString());
        assertEquals("INVALID_INPUT", result.at("/diagnostics/0/code").asText());
        assertError(409, "RESULT_NOT_READY", http.getForEntity(accepted.getHeaders().getLocation() + "/map/bounds?variantId=variant-1", JsonNode.class));
        assertError(409, "RESULT_NOT_READY", http.getForEntity(accepted.getHeaders().getLocation() + "/variants", JsonNode.class));
        assertError(409, "RESULT_NOT_READY", http.getForEntity(accepted.getHeaders().getLocation() + "/result", JsonNode.class));
        assertError(409, "RESULT_NOT_READY", http.getForEntity(accepted.getHeaders().getLocation() + "/map?layer=input&bbox=0,0,1,1", JsonNode.class));
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

    @Test void duplicateModesHaveExplicitErrors() {
        MultiValueMap<String, Object> duplicate = body(new byte[] {1});
        duplicate.add("mode", "2d");
        assertError(400, "INVALID_INPUT", upload(duplicate, "2d"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"flat", "gas-below", "gas-above", "cable-below", "network-below", "road", "branch"})
    void depthHttpRunsRealModulesAndMatchesResultMapAndSummary(String name) throws Exception {
        byte[] fixture;
        try (InputStream input = getClass().getResourceAsStream("/fixtures/synthetic/depth/" + name + "/input.geojson")) {
            assertNotNull(input);
            fixture = input.readAllBytes();
        }
        ResponseEntity<JsonNode> accepted = upload(body(fixture), "depth");
        assertEquals(202, accepted.getStatusCodeValue());
        String location = accepted.getHeaders().getLocation().toString();
        JsonNode job = completed(location);
        assertEquals("depth", job.path("mode").asText());
        assertEquals("SUCCEEDED", job.path("status").asText(), job.toString());
        JsonNode variants = http.getForObject(location + "/variants", JsonNode.class);
        assertEquals(0, variants.get(0).path("unconnected_oks_ids").size());
        JsonNode result = mapper.readTree(http.getForObject(location + "/result", byte[].class));
        java.util.List<JsonNode> expectedMap = new java.util.ArrayList<>();
        int networks = 0;
        boolean changedDepth = false;
        for (JsonNode feature : result.path("features")) {
            JsonNode p = feature.path("properties");
            if (p.path("object_type").asText().equals("heat_network")) {
                networks++;
                assertTrue(p.path("depth_start").isNumber());
                assertTrue(p.path("depth_end").isNumber());
                assertTrue(p.path("depth_start").asDouble() >= 0.7);
                changedDepth |= p.path("depth_start").asDouble() != 3.0 || p.path("depth_end").asDouble() != 3.0;
            }
            if (p.path("variant_id").asText().equals(variants.get(0).path("variant_id").asText())) {
                if (!feature.path("geometry").isNull()) expectedMap.add(feature);
                else assertTrue(p.equals((a, b) -> a.isNumber() && b.isNumber()
                        ? a.decimalValue().compareTo(b.decimalValue()) : a.equals(b) ? 0 : 1, variants.get(0)));
            }
        }
        assertTrue(networks > 0);
        if (name.startsWith("gas")) assertTrue(changedDepth, "gas crossing needs a real vertical transition");
        String variantId = variants.get(0).path("variant_id").asText();
        assertEquals(expectedMap, mapPages(location + "/map?layer=result&variantId=" + variantId + "&bbox=-180,-90,180,90&limit=2"));
        assertEquals(4, http.getForObject(location + "/map/bounds?variantId=" + variantId, JsonNode.class).path("bbox").size());
    }

    @Test void invalidDepthInputFailsAsDepthWithoutPublishingAResult() throws Exception {
        ResponseEntity<JsonNode> accepted = upload(body(new byte[]{1}), "depth");
        assertEquals(202, accepted.getStatusCodeValue());
        String location = accepted.getHeaders().getLocation().toString();
        JsonNode result = completed(location);
        assertEquals("FAILED", result.path("status").asText());
        assertEquals("depth", result.path("mode").asText());
        assertError(409, "RESULT_NOT_READY", http.getForEntity(location + "/result", JsonNode.class));
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

    @Test void liveSwaggerDescribesResultsAndMap() {
        JsonNode spec = http.getForObject("/v3/api-docs", JsonNode.class);
        assertNotNull(spec);
        assertEquals("createJob", spec.at("/paths/~1api~1jobs/post/operationId").asText());
        assertTrue(spec.at("/paths/~1api~1jobs/post/responses").has("202"));
        assertTrue(spec.path("paths").has("/api/jobs/{jobId}"));
        assertEquals("listVariants", spec.at("/paths/~1api~1jobs~1{jobId}~1variants/get/operationId").asText());
        assertEquals("downloadResult", spec.at("/paths/~1api~1jobs~1{jobId}~1result/get/operationId").asText());
        assertEquals("getMapPage", spec.at("/paths/~1api~1jobs~1{jobId}~1map/get/operationId").asText());
        assertEquals("getMapBounds", spec.at("/paths/~1api~1jobs~1{jobId}~1map~1bounds/get/operationId").asText());
    }

    @Test void missingResultsUseJson404ForBothEndpoints() {
        for (String id : new String[] {UUID.randomUUID().toString(), "invalid-id"}) {
            for (String endpoint : new String[] {"variants", "result", "map/bounds?variantId=variant-1"}) {
                ResponseEntity<JsonNode> response = http.getForEntity("/api/jobs/" + id + "/" + endpoint, JsonNode.class);
                assertError(404, "JOB_NOT_FOUND", response);
                assertTrue(MediaType.APPLICATION_JSON.isCompatibleWith(response.getHeaders().getContentType()));
            }
        }
    }

    private static void extendBounds(double[] bounds, JsonNode coordinates) {
        if (coordinates.size() >= 2 && coordinates.get(0).isNumber()) {
            bounds[0] = Math.min(bounds[0], coordinates.get(0).asDouble());
            bounds[1] = Math.min(bounds[1], coordinates.get(1).asDouble());
            bounds[2] = Math.max(bounds[2], coordinates.get(0).asDouble());
            bounds[3] = Math.max(bounds[3], coordinates.get(1).asDouble());
        } else for (JsonNode child : coordinates) extendBounds(bounds, child);
    }

    private JsonNode completed(String location) {
        eventually(() -> {
            String status = http.getForObject(location, JsonNode.class).path("status").asText();
            return "FAILED".equals(status) || "SUCCEEDED".equals(status);
        });
        return http.getForObject(location, JsonNode.class);
    }

    private java.util.List<JsonNode> mapPages(String url) {
        java.util.List<JsonNode> features = new java.util.ArrayList<>();
        String cursor = null;
        java.util.Set<String> seen = new java.util.HashSet<>();
        do {
            ResponseEntity<JsonNode> response = http.getForEntity(url + (cursor == null ? "" : "&cursor=" + cursor), JsonNode.class);
            assertEquals(200, response.getStatusCodeValue(), () -> String.valueOf(response.getBody()));
            assertEquals("application/geo+json", response.getHeaders().getContentType().toString());
            assertEquals("no-store", response.getHeaders().getCacheControl());
            JsonNode page = response.getBody();
            assertEquals("FeatureCollection", page.path("type").asText());
            assertTrue(page.has("nextCursor"));
            page.path("features").forEach(features::add);
            cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
            if (cursor != null) assertTrue(seen.add(cursor), "pagination must advance");
        } while (cursor != null);
        return features;
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
