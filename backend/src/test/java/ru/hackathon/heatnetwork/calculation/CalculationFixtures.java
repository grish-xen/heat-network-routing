package ru.hackathon.heatnetwork.calculation;

import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import ru.hackathon.heatnetwork.model.Dataset;
import ru.hackathon.heatnetwork.model.Model;
import ru.hackathon.heatnetwork.model.Model.Attachment;
import ru.hackathon.heatnetwork.model.Model.Edge;
import ru.hackathon.heatnetwork.model.Model.InputObject;
import ru.hackathon.heatnetwork.model.Model.InputType;
import ru.hackathon.heatnetwork.model.Model.Node;
import ru.hackathon.heatnetwork.model.Model.NodeKind;
import ru.hackathon.heatnetwork.model.Model.RouteCandidate;
import ru.hackathon.heatnetwork.model.ObjectId;

/** Metric scenes in EPSG:32637 around (400000, 6170000) and a fluent candidate builder. */
final class CalculationFixtures {
    static final GeometryFactory GF = new GeometryFactory(new PrecisionModel(), Model.METRIC_SRID);
    static final double X0 = 400000;
    static final double Y0 = 6170000;

    private CalculationFixtures() { }

    static Coordinate xy(double dx, double dy) {
        return new Coordinate(X0 + dx, Y0 + dy);
    }

    static ObjectId id(String value) {
        return new ObjectId(TextNode.valueOf(value));
    }

    static ObjectId id(int value) {
        return new ObjectId(IntNode.valueOf(value));
    }

    static Polygon rectangle(double dx1, double dy1, double dx2, double dy2) {
        return GF.createPolygon(new Coordinate[] {xy(dx1, dy1), xy(dx2, dy1), xy(dx2, dy2), xy(dx1, dy2), xy(dx1, dy1)});
    }

    static final class Scene implements Dataset {
        final Map<ObjectId, InputObject> objects = new LinkedHashMap<>();

        Scene line(String id, int diameter, Coordinate... points) {
            return add(id(id), InputType.HEAT_NETWORK, GF.createLineString(points), o -> o.diameterMm = diameter);
        }

        Scene chamber(String id, Coordinate point) {
            return add(id(id), InputType.HEAT_CHAMBER, GF.createPoint(point), o -> { });
        }

        Scene point(ObjectId id, double flow, Coordinate point) {
            return add(id, InputType.OKS_CONNECTION_POINT, GF.createPoint(point), o -> o.flowTph = BigDecimal.valueOf(flow));
        }

        Scene restriction(String id, String type, Geometry geometry) {
            return add(id(id), InputType.RESTRICTION, geometry, o -> o.restrictionType = type);
        }

        private Scene add(ObjectId id, InputType type, Geometry geometry, java.util.function.Consumer<InputObject> extra) {
            InputObject object = new InputObject();
            object.id = id;
            object.type = type;
            object.geometry = geometry;
            extra.accept(object);
            objects.put(id, object);
            return this;
        }

        @Override public Stream<InputObject> objects(InputType type) {
            return objects.values().stream().filter(object -> object.type == type);
        }

        @Override public Optional<InputObject> find(ObjectId id) {
            return Optional.ofNullable(objects.get(id));
        }

        @Override public Stream<InputObject> query(Envelope bounds) {
            return objects.values().stream().filter(object -> object.geometry.getEnvelopeInternal().intersects(bounds));
        }

        @Override public void close() { }
    }

    static final class CandidateBuilder {
        final RouteCandidate candidate = new RouteCandidate();

        CandidateBuilder(String id) {
            candidate.candidateId = id;
        }

        CandidateBuilder existingRoot(String nodeId, String chamberId, Coordinate at) {
            node(nodeId, NodeKind.EXISTING_CHAMBER, id(chamberId), at);
            return attach(nodeId, id(chamberId));
        }

        CandidateBuilder newRoot(String nodeId, String lineId, Coordinate at) {
            node(nodeId, NodeKind.NEW_CHAMBER, null, at);
            return attach(nodeId, id(lineId));
        }

        CandidateBuilder chamber(String nodeId, Coordinate at) {
            return node(nodeId, NodeKind.NEW_CHAMBER, null, at);
        }

        CandidateBuilder target(String nodeId, ObjectId pointId, Coordinate at) {
            return node(nodeId, NodeKind.CONNECTION_POINT, pointId, at);
        }

        CandidateBuilder node(String nodeId, NodeKind kind, ObjectId input, Coordinate at) {
            Node node = new Node();
            node.id = nodeId;
            node.kind = kind;
            node.inputObjectId = input;
            node.geometry = GF.createPoint(at);
            candidate.nodes.add(node);
            return this;
        }

        CandidateBuilder edge(String edgeId, String from, String to, Coordinate... points) {
            Edge edge = new Edge();
            edge.id = edgeId;
            edge.fromNodeId = from;
            edge.toNodeId = to;
            edge.geometry = new GeometryFactory().createLineString(points);
            candidate.edges.add(edge);
            return this;
        }

        CandidateBuilder unconnected(ObjectId pointId) {
            candidate.unconnectedPointIds.add(pointId);
            return this;
        }

        private CandidateBuilder attach(String nodeId, ObjectId existing) {
            Attachment attachment = new Attachment();
            attachment.rootNodeId = nodeId;
            attachment.existingObjectId = existing;
            candidate.attachments.add(attachment);
            return this;
        }

        RouteCandidate build() {
            return candidate;
        }
    }
}
