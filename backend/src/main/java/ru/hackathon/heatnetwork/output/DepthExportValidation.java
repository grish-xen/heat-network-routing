package ru.hackathon.heatnetwork.output;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import ru.hackathon.heatnetwork.model.Model.CalculatedEdge;

/** Export boundary checks only; does not establish clearance, price or feasibility of a profile. */
final class DepthExportValidation {
    // depth-contract-1 arithmetic tolerance at shared nodes, not a clearance allowance.
    private static final double CONTINUITY_TOLERANCE_M = 1e-6;
    private final double minimumDepth;
    private final double maximumSlope;
    private final double costThreshold;
    private final Map<String, double[]> nodeDepths = new HashMap<>();

    DepthExportValidation() throws IOException {
        // Read the shared supplemental catalog. Keep no second table of engineering constants.
        try (InputStream input = DepthExportValidation.class.getResourceAsStream("/rules/depth-v1.json")) {
            if (input == null) throw new IOException("Missing rules/depth-v1.json");
            JsonNode rules = new ObjectMapper().readTree(input);
            if (rules == null) throw new IOException("Empty depth rules");
            minimumDepth = positive(rules.path("minimumDepthM"), "minimumDepthM");
            maximumSlope = positive(rules.path("maximumSlope"), "maximumSlope");
            costThreshold = positive(rules.path("cost").path("thresholdM"), "cost.thresholdM");
        }
    }

    void check(CalculatedEdge edge) throws InvalidResultException {
        validDepth(edge.depthStartM, edge.id);
        validDepth(edge.depthEndM, edge.id);
        double start = edge.depthStartM;
        double end = edge.depthEndM;
        double allowedChange = maximumSlope * edge.lengthM;
        // Permit only floating-point subtraction noise at the exact slope boundary.
        double roundoff = Math.min(1e-9,
                8 * Math.max(Math.ulp(start), Math.max(Math.ulp(end), Math.ulp(allowedChange))));
        if (Math.abs(end - start) > allowedChange + roundoff) {
            throw new InvalidResultException("DEPTH_SLOPE_VIOLATION: превышен уклон участка " + edge.id);
        }
        if (Math.min(start, end) < costThreshold && Math.max(start, end) > costThreshold) {
            throw new InvalidResultException("Участок не разделён на границе коэффициента глубины: " + edge.id);
        }
        register(edge.fromNodeId, start);
        register(edge.toNodeId, end);
    }

    private void validDepth(Double value, String edgeId) throws InvalidResultException {
        if (value == null || !Double.isFinite(value) || value < minimumDepth) {
            throw new InvalidResultException("DEPTH_VALUE_INVALID: отсутствующая или недопустимая глубина участка " + edgeId);
        }
    }

    private void register(String nodeId, double value) throws InvalidResultException {
        double[] range = nodeDepths.computeIfAbsent(nodeId, ignored -> new double[] {value, value});
        range[0] = Math.min(range[0], value);
        range[1] = Math.max(range[1], value);
        if (range[1] - range[0] > CONTINUITY_TOLERANCE_M) {
            throw new InvalidResultException("DEPTH_CONTINUITY_VIOLATION: глубины не согласованы в узле " + nodeId);
        }
    }

    private static double positive(JsonNode value, String field) throws IOException {
        if (!value.isNumber() || !Double.isFinite(value.doubleValue()) || value.doubleValue() <= 0) {
            throw new IOException("Invalid depth rule: " + field);
        }
        return value.doubleValue();
    }
}
