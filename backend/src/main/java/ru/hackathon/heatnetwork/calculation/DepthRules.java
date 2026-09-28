package ru.hackathon.heatnetwork.calculation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import ru.hackathon.heatnetwork.calculation.SpecialPasses.Obstacle;
import ru.hackathon.heatnetwork.routing.RulesCatalog.DepthCatalog;
import ru.hackathon.heatnetwork.routing.RulesCatalog.DepthCatalog.CrossingRule;

/**
 * Depth rules of section 5 and table 2 (depth mode) read from {@code rules/depth-v1.json}.
 * Depth h is measured to the top of the design envelope, positive downwards.
 */
final class DepthRules {
    /** Admissible depths are kept on a millimetre grid, rounded to the safe side. */
    private static final double STEPS_PER_M = 1000;

    final double ordinaryM;
    final double minimumM;
    final double maximumSlope;
    private final double thresholdM;
    private final double baseCoefficient;
    private final double incrementPerM;
    private final Map<String, CrossingRule> crossings = new HashMap<>();

    DepthRules(DepthCatalog depth) {
        this.ordinaryM = depth.ordinaryDepthM;
        this.minimumM = depth.minimumDepthM;
        this.maximumSlope = depth.maximumSlope;
        this.thresholdM = depth.cost.thresholdM;
        this.baseCoefficient = depth.cost.baseCoefficient;
        this.incrementPerM = depth.cost.incrementPerM;
        for (CrossingRule rule : depth.crossings) {
            crossings.put(rule.type, rule);
        }
    }

    /** Kгл at depth h: 1 up to 3 m, then 1 + 0.10·(h − 3). */
    double k(double h) {
        return h <= thresholdM ? baseCoefficient : baseCoefficient + incrementPerM * (h - thresholdM);
    }

    BigDecimal kDecimal(double h) {
        BigDecimal base = BigDecimal.valueOf(baseCoefficient);
        if (h <= thresholdM) {
            return base;
        }
        return base.add(BigDecimal.valueOf(incrementPerM)
                .multiply(BigDecimal.valueOf(h).subtract(BigDecimal.valueOf(thresholdM))));
    }

    double thresholdM() {
        return thresholdM;
    }

    /** Length × Kгл of a uniform slope; the part on each side of the 3 m mark uses its own mean coefficient. */
    double weightedLength(double h1, double h2, double length) {
        if (length <= 0) {
            return 0;
        }
        if ((h1 - thresholdM) * (h2 - thresholdM) < 0) {
            double share = (thresholdM - h1) / (h2 - h1);
            return weightedLength(h1, thresholdM, length * share) + weightedLength(thresholdM, h2, length * (1 - share));
        }
        return length * (k(h1) + k(h2)) / 2;
    }

    /**
     * Admissible top depths of a new pipe of height {@code newHeightM} where it crosses the object:
     * a minimum depth under surface objects, or above / below a buried one with the vertical clearance
     * measured between the envelopes. Every interval is within [minimum, +∞).
     */
    List<double[]> admissible(Obstacle obstacle, double newHeightM) {
        CrossingRule rule = crossings.get(obstacle.type);
        List<double[]> result = new ArrayList<>();
        if (rule == null) {
            result.add(new double[] {minimumM, Double.POSITIVE_INFINITY});
            return result;
        }
        if (rule.minimumNewTopDepthM != null) {
            result.add(new double[] {up(Math.max(minimumM, rule.minimumNewTopDepthM)), Double.POSITIVE_INFINITY});
            return result;
        }
        if (rule.existingTopDepthM == null) {
            result.add(new double[] {minimumM, Double.POSITIVE_INFINITY});
            return result;
        }
        double clearance = rule.minimumVerticalClearanceM == null ? 0 : rule.minimumVerticalClearanceM;
        double objectHeight = rule.profileHeightM != null ? rule.profileHeightM : obstacle.ownHeightM;
        double above = down(rule.existingTopDepthM - clearance - newHeightM);
        if (above >= minimumM) {
            result.add(new double[] {minimumM, above});
        }
        result.add(new double[] {up(Math.max(minimumM, rule.existingTopDepthM + objectHeight + clearance)),
                Double.POSITIVE_INFINITY});
        return result;
    }

    /** Intersection of two unions of closed intervals. */
    static List<double[]> intersect(List<double[]> a, List<double[]> b) {
        List<double[]> result = new ArrayList<>();
        for (double[] x : a) {
            for (double[] y : b) {
                double low = Math.max(x[0], y[0]);
                double high = Math.min(x[1], y[1]);
                if (low <= high) {
                    result.add(new double[] {low, high});
                }
            }
        }
        result.sort((p, q) -> Double.compare(p[0], q[0]));
        return Collections.unmodifiableList(result);
    }

    static boolean contains(List<double[]> intervals, double h) {
        for (double[] interval : intervals) {
            if (h >= interval[0] - 1e-9 && h <= interval[1] + 1e-9) {
                return true;
            }
        }
        return false;
    }

    private static double up(double value) {
        return Math.ceil(value * STEPS_PER_M - 1e-6) / STEPS_PER_M;
    }

    private static double down(double value) {
        return Math.floor(value * STEPS_PER_M + 1e-6) / STEPS_PER_M;
    }
}
