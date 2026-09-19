package ru.hackathon.heatnetwork.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;

/** Internal contract v1. All geometries are EPSG:32637, not public API DTOs. */
public final class Model {
    private Model() { }
    public static final int METRIC_SRID = 32637;
    public static final String CONTRACT_VERSION = "1.0";

    public enum InputType { SOURCE, HEAT_NETWORK, HEAT_CHAMBER, OKS_CONNECTION_POINT, RESTRICTION }
    public enum NodeKind { EXISTING_CHAMBER, NEW_CHAMBER, CONNECTION_POINT, TECHNICAL_NODE }
    public enum LayingMethod { BASE, SPECIAL }
    public enum Mode { TWO_D, DEPTH }

    public static class InputObject {
        public ObjectId id;
        public InputType type;
        public Geometry geometry;
        public Integer diameterMm;
        public BigDecimal flowTph;
        public String restrictionType;
    }

    public static class Diagnostic {
        public String code;
        public String message;
        public ObjectId inputObjectId;
        public String candidateId;
        public String segmentId;
    }

    public static class Node {
        public String id;
        public NodeKind kind;
        public Point geometry;
        /** Set only for an existing chamber or input connection point. */
        public ObjectId inputObjectId;
    }

    public static class Edge {
        public String id;
        /** Internal orientation: attachment -> consumer. Coordinates follow this order. */
        public String fromNodeId;
        public String toNodeId;
        public LineString geometry;
    }

    public static class Attachment {
        public String rootNodeId;
        /** Existing chamber ID for EXISTING_CHAMBER; existing line ID for NEW_CHAMBER. */
        public ObjectId existingObjectId;
    }

    public static class RouteCandidate {
        public String candidateId;
        public List<Node> nodes = new ArrayList<>();
        public List<Edge> edges = new ArrayList<>();
        public List<Attachment> attachments = new ArrayList<>();
        public List<ObjectId> unconnectedPointIds = new ArrayList<>();
        public List<Diagnostic> diagnostics = new ArrayList<>();
    }

    public static class CalculatedEdge extends Edge {
        public BigDecimal flowTph;
        public int diameterMm;
        public double lengthM;
        public LayingMethod layingMethod;
        public Double depthStartM;
        public Double depthEndM;
        public BigDecimal specialCoefficient;
        public BigDecimal costRub;
        /** IDs whose special-pass requirements apply to this entire final edge. */
        public List<ObjectId> crossedObjectIds = new ArrayList<>();
    }

    public static class ChamberCost {
        public String nodeId;
        public int diameterMm;
        public BigDecimal costRub;
    }

    public static class Summary {
        public BigDecimal constructionCost;
        public BigDecimal chamberConstructionCost;
        public int existingChamberTieInCount;
        public BigDecimal existingChamberTieInCost;
        public BigDecimal unconnectedPenalty;
        public BigDecimal calculatedCost;
        public double newNetworkLength;
        public BigDecimal score;
    }

    public static class CalculatedVariant {
        public String variantId;
        public Mode mode;
        public List<Node> nodes = new ArrayList<>();
        public List<CalculatedEdge> edges = new ArrayList<>();
        public List<Attachment> attachments = new ArrayList<>();
        public List<ChamberCost> newChambers = new ArrayList<>();
        public List<ObjectId> unconnectedPointIds = new ArrayList<>();
        public Summary summary;
    }

    public static class Evaluation {
        public String candidateId;
        /** Non-null only when the candidate passes all required validation. */
        public CalculatedVariant variant;
        public List<Diagnostic> diagnostics = new ArrayList<>();
        public boolean accepted() { return variant != null; }
    }

    public static class SearchOptions {
        public Mode mode = Mode.TWO_D;
        public int maxCandidates = 100;
        public long seed = 0;
    }
}
