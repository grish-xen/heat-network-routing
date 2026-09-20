package ru.hackathon.heatnetwork.output;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;
import org.locationtech.jts.geom.CoordinateSequence;
import org.springframework.stereotype.Component;
import ru.hackathon.heatnetwork.model.Dataset;
import ru.hackathon.heatnetwork.model.Model.*;
import ru.hackathon.heatnetwork.model.ObjectId;
import ru.hackathon.heatnetwork.output.ExportValidation.PreparedNode;
import ru.hackathon.heatnetwork.output.ExportValidation.PreparedVariant;

/** Serializes approved calculated variants. Does not solve routes or recalculate tariffs/score. */
@Component
public final class GeoJsonResultExporter implements ResultExporter {
    public static final long MAX_OUTPUT_BYTES = 500L * 1024 * 1024;
    private final ObjectMapper mapper = new ObjectMapper();
    private final long maxBytes;

    public GeoJsonResultExporter() { this(MAX_OUTPUT_BYTES); }

    /** Lower limits are used by package tests to exercise the exact same byte-counting path. */
    GeoJsonResultExporter(long maxBytes) {
        if (maxBytes < 1 || maxBytes > MAX_OUTPUT_BYTES) throw new IllegalArgumentException("Invalid export size limit");
        this.maxBytes = maxBytes;
    }

    @Override public void write(Dataset dataset, List<CalculatedVariant> rankedVariants, OutputStream target) throws IOException {
        Objects.requireNonNull(target, "target");
        Wgs84Writer projection = new Wgs84Writer();
        try {
            // Validate all variants before emitting the first byte. Keep the supplied graph,
            // not a second GeoJSON tree or an array of transformed coordinates.
            List<PreparedVariant> variants = ExportValidation.prepare(dataset, rankedVariants, projection);
            IdSequence ids = new IdSequence(dataset);
            try (JsonGenerator json = mapper.getFactory().createGenerator(new BoundedTarget(target, maxBytes))) {
                json.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
                json.disable(JsonGenerator.Feature.AUTO_CLOSE_JSON_CONTENT);
                json.writeStartObject();
                json.writeStringField("type", "FeatureCollection");
                json.writeArrayFieldStart("features");
                for (int i = 0; i < variants.size(); i++) writeVariant(json, projection, variants.get(i), ids, i + 1);
                json.writeEndArray();
                json.writeEndObject();
            }
        } catch (UncheckedIOException exception) { throw exception.getCause(); }
    }

    private void writeVariant(JsonGenerator json, Wgs84Writer projection, PreparedVariant prepared,
                              IdSequence ids, int rank) throws IOException {
        CalculatedVariant variant = prepared.variant;
        for (PreparedNode node : prepared.nodes.values()) {
            ExportValidation.interrupted();
            node.outputId = node.node.inputObjectId == null ? ids.next() : node.node.inputObjectId;
        }
        for (CalculatedEdge edge : variant.edges) {
            ExportValidation.interrupted();
            PreparedNode from = prepared.nodes.get(edge.fromNodeId);
            PreparedNode to = prepared.nodes.get(edge.toNodeId);
            feature(json, ids.next(), "heat_network", variant.variantId);
            idField(json, "start_node_id", from.outputId);
            idField(json, "end_node_id", to.outputId);
            json.writeNumberField("flow_tph", edge.flowTph);
            json.writeNumberField("diameter", edge.diameterMm);
            json.writeNumberField("length", edge.lengthM);
            json.writeStringField("laying_method", edge.layingMethod == LayingMethod.BASE ? "base" : "special");
            json.writeNullField("depth_start");
            json.writeNullField("depth_end");
            json.writeNumberField("cost", edge.costRub);
            json.writeEndObject(); // properties
            json.writeObjectFieldStart("geometry");
            json.writeStringField("type", "LineString");
            json.writeArrayFieldStart("coordinates");
            CoordinateSequence coordinates = edge.geometry.getCoordinateSequence();
            for (int i = 0; i < coordinates.size(); i++) {
                if ((i & 1023) == 0) ExportValidation.interrupted();
                // Snap only the validated sub-millimetre discrepancy. All lines sharing a
                // node consequently emit exactly the same endpoint coordinates.
                if (i == 0) projection.position(json, from.location.getX(), from.location.getY());
                else if (i == coordinates.size() - 1) projection.position(json, to.location.getX(), to.location.getY());
                else projection.position(json, coordinates.getX(i), coordinates.getY(i));
            }
            json.writeEndArray();
            json.writeEndObject();
            json.writeEndObject();
        }
        for (PreparedNode node : prepared.nodes.values()) {
            ExportValidation.interrupted();
            if (node.node.kind != NodeKind.NEW_CHAMBER && node.node.kind != NodeKind.TECHNICAL_NODE) continue;
            boolean chamber = node.node.kind == NodeKind.NEW_CHAMBER;
            feature(json, node.outputId, chamber ? "heat_chamber" : "technical_node", variant.variantId);
            if (chamber) {
                json.writeNumberField("diameter", node.chamber.diameterMm);
                json.writeNumberField("cost", node.chamber.costRub);
            }
            json.writeEndObject();
            json.writeObjectFieldStart("geometry");
            json.writeStringField("type", "Point");
            json.writeFieldName("coordinates");
            projection.position(json, node.location.getX(), node.location.getY());
            json.writeEndObject();
            json.writeEndObject();
        }
        summary(json, variant, ids.next(), rank);
    }

    private void summary(JsonGenerator json, CalculatedVariant variant, ObjectId id, int rank) throws IOException {
        Summary summary = variant.summary;
        feature(json, id, "variant_summary", variant.variantId);
        json.writeNumberField("rank", rank);
        json.writeNumberField("construction_cost", summary.constructionCost);
        json.writeNumberField("chamber_construction_cost", summary.chamberConstructionCost);
        json.writeNumberField("existing_chamber_tie_in_count", summary.existingChamberTieInCount);
        json.writeNumberField("existing_chamber_tie_in_cost", summary.existingChamberTieInCost);
        json.writeNumberField("unconnected_penalty", summary.unconnectedPenalty);
        json.writeNumberField("calculated_cost", summary.calculatedCost);
        json.writeNumberField("new_network_length", summary.newNetworkLength);
        json.writeNumberField("score", summary.score);
        json.writeArrayFieldStart("unconnected_oks_ids");
        for (ObjectId unconnected : variant.unconnectedPointIds) {
            ExportValidation.interrupted();
            json.writeTree(unconnected.value());
        }
        json.writeEndArray();
        json.writeEndObject();
        json.writeNullField("geometry");
        json.writeEndObject();
    }

    private void feature(JsonGenerator json, ObjectId id, String type, String variantId) throws IOException {
        json.writeStartObject();
        json.writeStringField("type", "Feature");
        json.writeObjectFieldStart("properties");
        idField(json, "id", id);
        json.writeStringField("object_type", type);
        json.writeStringField("variant_id", variantId);
    }

    private void idField(JsonGenerator json, String field, ObjectId id) throws IOException {
        json.writeFieldName(field);
        json.writeTree(id.value());
    }

    /** Monotonic IDs cannot collide with one another; input IDs are probed on disk, not cached. */
    private static final class IdSequence {
        private final Dataset dataset;
        private long next = 1;
        IdSequence(Dataset dataset) { this.dataset = dataset; }
        ObjectId next() throws IOException {
            while (true) {
                ExportValidation.interrupted();
                ObjectId id = new ObjectId(TextNode.valueOf("hnr-" + next++));
                if (dataset.find(id).isEmpty()) return id;
            }
        }
    }

    private static final class BoundedTarget extends OutputStream {
        private final OutputStream target;
        private final long limit;
        private long count;
        BoundedTarget(OutputStream target, long limit) { this.target = target; this.limit = limit; }
        @Override public void write(int value) throws IOException {
            reserve(1);
            target.write(value);
        }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            reserve(length);
            target.write(bytes, offset, length);
        }
        @Override public void flush() throws IOException { target.flush(); }
        private void reserve(int bytes) throws IOException {
            ExportValidation.interrupted();
            if (bytes > limit - count) throw new OutputLimitExceededException(limit);
            count += bytes;
        }
    }
}
