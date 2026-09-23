package ru.hackathon.heatnetwork.routing;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Typed view of the shared numeric catalog {@code rules/catalog-v1.json}.
 * Single source of numbers for module 2; the file is verified against the DOCX
 * by {@code scripts/check_repository.py}, so no values are duplicated here.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE)
@JsonIgnoreProperties(ignoreUnknown = true)
public final class RulesCatalog {

    private String version;
    private String mode;

    private List<DiameterRow> diameters = new ArrayList<>();
    private List<ChamberRow> chambers = new ArrayList<>();
    private List<RestrictionRule> restrictions = new ArrayList<>();

    private int maxChamberDegree;
    private double existingChamberRadiusM;
    private double maxTurnDeg;
    private long existingChamberTieInRub;
    private long unconnectedFixedRub;
    private double unconnectedPerTphRub;
    private Ranking ranking = new Ranking();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class DiameterRow {
        public int diameterMm;
        public double capacityTph;
        public double maxLengthM;
        public double newCostRubPerM;
        public double widthM;
        public double heightM;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class ChamberRow {
        public int maxDiameterMm;
        public long costRub;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class ClearanceBand {
        public int maxDiameterMm;
        public double clearanceM;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class RestrictionRule {
        public String type;
        /** "forbidden" or "special" per table 2 of the appendix. */
        public String crossing;
        public Double clearanceM;
        public Double minAngleDeg;
        public Double extensionEachSideM;
        public String extentReference;
        public Double specialCoefficient;
        /** Width of the object's own design profile (table 2 note), null when not given. */
        public Double profileWidthM;
        public List<ClearanceBand> clearanceBands;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Ranking {
        public double costWeight;
        public double lengthWeight;
        public long costBaseRub;
        public double lengthBaseM;
    }

    public static RulesCatalog loadDefault() {
        try (InputStream in = RulesCatalog.class.getResourceAsStream("/rules/catalog-v1.json")) {
            if (in == null) {
                throw new IllegalStateException("rules/catalog-v1.json is not on the classpath");
            }
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(in, RulesCatalog.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load rules/catalog-v1.json", e);
        }
    }

    /** Ascending by diameter; callers rely on the order. */
    public void setDiameters(List<DiameterRow> rows) {
        List<DiameterRow> sorted = new ArrayList<>(rows);
        Collections.sort(sorted, (a, b) -> Integer.compare(a.diameterMm, b.diameterMm));
        this.diameters = sorted;
    }

    public List<DiameterRow> diameters() {
        return Collections.unmodifiableList(diameters);
    }

    public DiameterRow row(int diameterMm) {
        for (DiameterRow row : diameters) {
            if (row.diameterMm == diameterMm) {
                return row;
            }
        }
        throw new IllegalArgumentException("Unknown diameter: " + diameterMm);
    }

    /** Smallest diameter whose capacity covers the flow; null when the flow exceeds the largest pipe. */
    public DiameterRow minimalForFlow(double flowTph) {
        for (DiameterRow row : diameters) {
            if (row.capacityTph >= flowTph) {
                return row;
            }
        }
        return null;
    }

    /** Next bigger diameter or null when the given one is the largest. */
    public DiameterRow nextAfter(int diameterMm) {
        for (DiameterRow row : diameters) {
            if (row.diameterMm > diameterMm) {
                return row;
            }
        }
        return null;
    }

    /** Half of the pair width: the outer boundary of the pipe pair is this far from the axis. */
    public double halfWidthM(int diameterMm) {
        return row(diameterMm).widthM / 2.0;
    }

    public double maxLengthM(int diameterMm) {
        return row(diameterMm).maxLengthM;
    }

    public List<RestrictionRule> restrictionRules() {
        return Collections.unmodifiableList(restrictions);
    }

    public RestrictionRule rule(String restrictionType) {
        if (restrictionType == null) {
            return null;
        }
        for (RestrictionRule rule : restrictions) {
            if (restrictionType.equals(rule.type)) {
                return rule;
            }
        }
        return null;
    }

    /** Horizontal clearance for the restriction type at the given diameter (oks uses bands). */
    public double clearanceFor(String restrictionType, int diameterMm) {
        RestrictionRule rule = rule(restrictionType);
        if (rule == null) {
            return 0.0;
        }
        if (rule.clearanceBands != null && !rule.clearanceBands.isEmpty()) {
            for (ClearanceBand band : rule.clearanceBands) {
                if (diameterMm <= band.maxDiameterMm) {
                    return band.clearanceM;
                }
            }
            return rule.clearanceBands.get(rule.clearanceBands.size() - 1).clearanceM;
        }
        return rule.clearanceM == null ? 0.0 : rule.clearanceM;
    }

    /** Chamber price by the largest adjacent diameter (section 3.2). */
    public long chamberCost(int maxAdjacentDiameterMm) {
        for (ChamberRow row : chambers) {
            if (maxAdjacentDiameterMm <= row.maxDiameterMm) {
                return row.costRub;
            }
        }
        return chambers.get(chambers.size() - 1).costRub;
    }

    public long tieInCostRub() {
        return existingChamberTieInRub;
    }

    public BigDecimal unconnectedPenalty(double flowTph) {
        BigDecimal total = BigDecimal.valueOf(unconnectedFixedRub)
                .add(BigDecimal.valueOf(unconnectedPerTphRub).multiply(BigDecimal.valueOf(flowTph)));
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    public int maxChamberDegree() {
        return maxChamberDegree;
    }

    public double existingChamberRadiusM() {
        return existingChamberRadiusM;
    }

    public double maxTurnDeg() {
        return maxTurnDeg;
    }

    public Ranking ranking() {
        return ranking;
    }

    public String version() {
        return version;
    }
}
