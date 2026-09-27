package ru.hackathon.heatnetwork.api;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import org.locationtech.jts.geom.Coordinate;
import org.springframework.stereotype.Component;
import ru.hackathon.heatnetwork.calculation.VariantCalculator;
import ru.hackathon.heatnetwork.model.Dataset;
import ru.hackathon.heatnetwork.model.Model.*;
import ru.hackathon.heatnetwork.model.ObjectId;
import ru.hackathon.heatnetwork.routing.RoutePlanner;

/** Owns a search session, not the Dataset. Retains at most three calculated graphs. */
@Component
public final class CalculationCoordinator {
    private final RoutePlanner planner;
    private final VariantCalculator calculator;
    private final JobProperties properties;

    public CalculationCoordinator(RoutePlanner planner, VariantCalculator calculator, JobProperties properties) {
        this.planner = planner;
        this.calculator = calculator;
        this.properties = properties;
    }

    @FunctionalInterface interface Progress { void stage(JobView.Stage stage) throws IOException; }

    List<CalculatedVariant> calculate(Dataset dataset, Progress progress) throws IOException {
        SearchOptions options = new SearchOptions();
        options.maxCandidates = properties.getMaxCandidates();
        options.seed = properties.getSearchSeed();
        List<Choice> best = new ArrayList<>();
        // A partial intermediate result must not beat a later result that connects all
        // its consumers plus others. Keep coverage evidence even for a discarded graph.
        List<Set<ObjectId>> coverage = new ArrayList<>();
        List<ApiError> rejections = new ArrayList<>();
        interrupted();
        progress.stage(JobView.Stage.ROUTING);
        try (RoutePlanner.SearchSession session = planner.open(dataset, options)) {
            for (int count = 0; count < options.maxCandidates; count++) {
                interrupted();
                progress.stage(JobView.Stage.ROUTING);
                Optional<RouteCandidate> next = session.next();
                interrupted();
                if (next.isEmpty()) break;
                progress.stage(JobView.Stage.CALCULATING);
                Evaluation evaluation = calculator.evaluate(dataset, next.get(), Mode.TWO_D);
                interrupted();
                session.feedback(evaluation);
                if (!evaluation.accepted()) {
                    for (Diagnostic diagnostic : evaluation.diagnostics) {
                        if (rejections.size() < 20) rejections.add(ApiError.from(diagnostic));
                    }
                    continue;
                }
                CalculatedVariant variant = evaluation.variant;
                Set<ObjectId> missing = new HashSet<>(variant.unconnectedPointIds);
                if (coverage.stream().anyMatch(old -> missing.size() > old.size() && missing.containsAll(old))) continue;
                coverage.removeIf(old -> old.size() > missing.size() && old.containsAll(missing));
                if (!coverage.contains(missing)) coverage.add(missing);
                best.removeIf(old -> old.missing.size() > missing.size() && old.missing.containsAll(missing));
                String signature = signature(variant);
                Choice duplicate = best.stream().filter(old -> old.signature.equals(signature)).findFirst().orElse(null);
                if (duplicate != null) {
                    if (duplicate.variant.summary.score.compareTo(variant.summary.score) <= 0) continue;
                    best.remove(duplicate);
                }
                best.add(new Choice(variant, missing, signature));
                best.sort(Comparator.comparing((Choice c) -> c.variant.summary.score).thenComparing(c -> c.signature));
                if (best.size() > 3) best.remove(3);
            }
        }
        interrupted();
        if (best.isEmpty()) {
            rejections.add(0, new ApiError("ROUTE_NOT_FOUND", "В пределах бюджета поиска не найден допустимый вариант. Это не доказывает невозможность подключения."));
            throw new NoValidVariantException(rejections);
        }
        List<CalculatedVariant> result = new ArrayList<>();
        for (Choice choice : best) {
            // Public IDs belong to this job, independent of the planner's candidate IDs.
            CalculatedVariant copy = new CalculatedVariant();
            CalculatedVariant original = choice.variant;
            copy.variantId = "variant-" + (result.size() + 1);
            copy.mode = original.mode;
            copy.nodes = original.nodes;
            copy.edges = original.edges;
            copy.attachments = original.attachments;
            copy.newChambers = original.newChambers;
            copy.unconnectedPointIds = original.unconnectedPointIds;
            copy.summary = original.summary;
            result.add(copy);
        }
        return result;
    }

    static void interrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Calculation interrupted");
    }

    /** Ignores generated IDs and list order, compares geometry at the shared 1 mm tolerance. */
    static String signature(CalculatedVariant variant) throws InterruptedIOException {
        List<String> parts = new ArrayList<>();
        Map<String, String> nodes = new HashMap<>();
        for (Node node : variant.nodes) {
            String key = node.kind + ":" + point(node.geometry.getCoordinate()) + ":" + id(node.inputObjectId);
            nodes.put(node.id, key);
            parts.add("node:" + key);
        }
        for (CalculatedEdge edge : variant.edges) {
            interrupted();
            MessageDigest digest = sha256();
            update(digest, nodes.get(edge.fromNodeId));
            update(digest, nodes.get(edge.toNodeId));
            update(digest, edge.diameterMm + ":" + edge.flowTph.stripTrailingZeros().toPlainString()
                    + ":" + edge.layingMethod + ":" + edge.specialCoefficient.stripTrailingZeros().toPlainString());
            for (int i = 0; i < edge.geometry.getNumPoints(); i++) {
                if ((i & 1023) == 0) interrupted();
                update(digest, point(edge.geometry.getCoordinateN(i)));
            }
            parts.add("edge:" + Base64.getEncoder().encodeToString(digest.digest()));
        }
        for (Attachment attachment : variant.attachments) parts.add("attachment:" + nodes.get(attachment.rootNodeId) + ":" + id(attachment.existingObjectId));
        for (ObjectId missing : variant.unconnectedPointIds) parts.add("missing:" + id(missing));
        Collections.sort(parts);
        MessageDigest digest = sha256();
        for (String part : parts) update(digest, part);
        return Base64.getEncoder().encodeToString(digest.digest());
    }

    private static String point(Coordinate c) { return Math.round(c.x * 1000) + "," + Math.round(c.y * 1000); }
    private static String id(ObjectId id) {
        if (id == null) return "null";
        return id.value().isTextual() ? "s:" + id.value().toString() : "n:" + id.value().decimalValue().stripTrailingZeros().toPlainString();
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        // Length prefix makes arbitrary string IDs unambiguous.
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
    }
    private static final class Choice {
        final CalculatedVariant variant;
        final Set<ObjectId> missing;
        final String signature;
        Choice(CalculatedVariant variant, Set<ObjectId> missing, String signature) {
            this.variant = variant; this.missing = missing; this.signature = signature;
        }
    }
    static final class NoValidVariantException extends IOException {
        final List<ApiError> diagnostics;
        NoValidVariantException(List<ApiError> diagnostics) { super("No valid variant"); this.diagnostics = List.copyOf(diagnostics); }
    }
}
