package ru.hackathon.heatnetwork.calculation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import ru.hackathon.heatnetwork.model.Model.Edge;
import ru.hackathon.heatnetwork.model.Model.Node;
import ru.hackathon.heatnetwork.model.Model.NodeKind;

/**
 * Vertical profile of a candidate tree in the depth mode (section 5, depth-contract-1).
 *
 * <p>Team policy P1–P3: the top of the new network is at the ordinary depth at the root and at
 * each consumer; adjacent new edges share the top depth in a chamber; the depth is constant
 * across the whole extent of a special pass (a "block"). Between blocks the profile leaves a
 * block at the maximum slope, runs at the ordinary depth and reaches the next block at the
 * maximum slope; when the gap is too short for that it keeps a straight line, so a changed depth
 * is held between close obstacles instead of an unnecessary rise and descent.</p>
 *
 * <p>Block levels are chosen from the ends of the admissible intervals and the ordinary depth by
 * dynamic programming along each edge; chamber depths are chosen jointly for the whole tree.
 * The objective is the depth-weighted construction cost. The result is a list of profile
 * vertices (position along the edge, depth) with a vertex wherever the slope changes or the
 * profile crosses the 3 m mark.</p>
 */
final class DepthProfilePlanner {
    private static final double EPSILON = 1e-9;
    /**
     * Transitions are planned a hair below the maximum slope, so that the lengths measured on the
     * final cut geometry (floating point on UTM coordinates) never exceed it.
     */
    private static final double SLOPE_MARGIN = 1e-7;

    private DepthProfilePlanner() { }

    /** A stretch of an edge with one constant depth: special passes and the envelope overlap. */
    static final class Block {
        final double start;
        final double end;
        final List<double[]> admissible;
        /** Σ length × Kспец inside the block. */
        final double weight;

        Block(double start, double end, List<double[]> admissible, double weight) {
            this.start = start;
            this.end = end;
            this.admissible = admissible;
            this.weight = weight;
        }
    }

    static final class EdgeInput {
        final double length;
        final List<Block> blocks;
        final double rateRubPerM;

        EdgeInput(double length, List<Block> blocks, double rateRubPerM) {
            this.length = length;
            this.blocks = blocks;
            this.rateRubPerM = rateRubPerM;
        }
    }

    static final class Solution {
        final double weight;
        final List<double[]> vertices;

        Solution(double weight, List<double[]> vertices) {
            this.weight = weight;
            this.vertices = vertices;
        }
    }

    /** Profile vertices of every candidate edge, or a rejection when no admissible profile exists. */
    static Map<String, List<double[]>> plan(CandidateTree tree, Map<String, EdgeInput> edges, DepthRules rules)
            throws Rejection {
        Map<String, double[]> levels = new HashMap<>();
        for (String nodeId : tree.order) {
            levels.put(nodeId, nodeLevels(tree, nodeId, edges, rules));
        }
        Map<String, double[]> best = new HashMap<>();
        Map<String, int[]> choice = new HashMap<>();
        Map<String, Solution[][]> solved = new HashMap<>();
        for (int i = tree.order.size() - 1; i >= 0; i--) {
            String nodeId = tree.order.get(i);
            double[] own = levels.get(nodeId);
            double[] total = new double[own.length];
            for (Edge edge : tree.children(nodeId)) {
                double[] childLevels = levels.get(edge.toNodeId);
                double[] childBest = best.get(edge.toNodeId);
                Solution[][] table = new Solution[own.length][childLevels.length];
                int[] picked = new int[own.length];
                EdgeInput input = edges.get(edge.id);
                for (int a = 0; a < own.length; a++) {
                    double bestValue = Double.POSITIVE_INFINITY;
                    picked[a] = -1;
                    for (int b = 0; b < childLevels.length; b++) {
                        if (Double.isInfinite(childBest[b])) {
                            continue;
                        }
                        Solution solution = solveEdge(input, own[a], childLevels[b], rules);
                        table[a][b] = solution;
                        if (solution == null) {
                            continue;
                        }
                        double value = input.rateRubPerM * solution.weight + childBest[b];
                        if (value < bestValue - EPSILON) {
                            bestValue = value;
                            picked[a] = b;
                        }
                    }
                    total[a] += bestValue;
                }
                solved.put(edge.id, table);
                choice.put(edge.id, picked);
            }
            best.put(nodeId, total);
        }

        Map<String, List<double[]>> result = new HashMap<>();
        Map<String, Integer> chosen = new HashMap<>();
        for (String root : tree.attachmentByRoot.keySet()) {
            if (Double.isInfinite(best.get(root)[0])) {
                throw new Rejection(notFound(tree, root, solved, best));
            }
            chosen.put(root, 0);
        }
        for (String nodeId : tree.order) {
            int level = chosen.get(nodeId);
            for (Edge edge : tree.children(nodeId)) {
                int childLevel = choice.get(edge.id)[level];
                chosen.put(edge.toNodeId, childLevel);
                result.put(edge.id, solved.get(edge.id)[level][childLevel].vertices);
            }
        }
        return result;
    }

    /** Roots and consumers are fixed (P1); a chamber may take the ordinary depth or a level of a nearby block. */
    private static double[] nodeLevels(CandidateTree tree, String nodeId, Map<String, EdgeInput> edges,
                                       DepthRules rules) {
        Node node = tree.nodes.get(nodeId);
        if (tree.isRoot(nodeId) || node.kind == NodeKind.CONNECTION_POINT) {
            return new double[] {rules.ordinaryM};
        }
        TreeSet<Double> values = new TreeSet<>();
        values.add(rules.ordinaryM);
        Edge parent = tree.parentEdge.get(nodeId);
        if (parent != null) {
            List<Block> blocks = edges.get(parent.id).blocks;
            if (!blocks.isEmpty()) {
                addEnds(values, blocks.get(blocks.size() - 1).admissible, rules);
            }
        }
        for (Edge child : tree.children(nodeId)) {
            List<Block> blocks = edges.get(child.id).blocks;
            if (!blocks.isEmpty()) {
                addEnds(values, blocks.get(0).admissible, rules);
            }
        }
        List<Double> ordered = byDeviation(values, rules);
        double[] result = new double[ordered.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = ordered.get(i);
        }
        return result;
    }

    /** Levels closest to the ordinary depth first: on equal cost the smallest change is kept. */
    private static List<Double> byDeviation(TreeSet<Double> values, DepthRules rules) {
        List<Double> ordered = new ArrayList<>(values);
        ordered.sort((a, b) -> Double.compare(Math.abs(a - rules.ordinaryM), Math.abs(b - rules.ordinaryM)));
        return ordered;
    }

    private static void addEnds(TreeSet<Double> values, List<double[]> intervals, DepthRules rules) {
        for (double[] interval : intervals) {
            for (double end : interval) {
                if (Double.isFinite(end) && end >= rules.minimumM) {
                    values.add(end);
                }
            }
        }
    }

    /** Cheapest profile of one edge between fixed end depths, or null if none satisfies the blocks and slope. */
    static Solution solveEdge(EdgeInput edge, double startDepth, double endDepth, DepthRules rules) {
        List<Block> blocks = edge.blocks;
        if (blocks.isEmpty()) {
            Segment only = free(startDepth, 0, endDepth, edge.length, rules);
            return only == null ? null : new Solution(only.weight, finish(only.vertices, rules));
        }
        TreeSet<Double> candidates = new TreeSet<>();
        candidates.add(rules.ordinaryM);
        candidates.add(startDepth);
        candidates.add(endDepth);
        for (Block block : blocks) {
            addEnds(candidates, block.admissible, rules);
        }
        int n = blocks.size();
        List<List<Double>> options = new ArrayList<>();
        for (Block block : blocks) {
            List<Double> admissible = new ArrayList<>();
            for (double level : byDeviation(candidates, rules)) {
                if (DepthRules.contains(block.admissible, level)) {
                    admissible.add(level);
                }
            }
            options.add(admissible);
        }
        List<double[]> cost = new ArrayList<>();
        List<int[]> previous = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Block block = blocks.get(i);
            List<Double> levels = options.get(i);
            double[] row = new double[levels.size()];
            int[] back = new int[levels.size()];
            Arrays.fill(row, Double.POSITIVE_INFINITY);
            Arrays.fill(back, -1);
            for (int j = 0; j < levels.size(); j++) {
                double level = levels.get(j);
                double inside = rules.k(level) * block.weight;
                if (i == 0) {
                    Segment approach = free(startDepth, 0, level, block.start, rules);
                    if (approach != null) {
                        row[j] = approach.weight + inside;
                    }
                    continue;
                }
                Block before = blocks.get(i - 1);
                List<Double> earlier = options.get(i - 1);
                for (int m = 0; m < earlier.size(); m++) {
                    if (Double.isInfinite(cost.get(i - 1)[m])) {
                        continue;
                    }
                    Segment between = free(earlier.get(m), before.end, level, block.start, rules);
                    if (between != null && cost.get(i - 1)[m] + between.weight + inside < row[j] - EPSILON) {
                        row[j] = cost.get(i - 1)[m] + between.weight + inside;
                        back[j] = m;
                    }
                }
            }
            cost.add(row);
            previous.add(back);
        }
        Block last = blocks.get(n - 1);
        double bestValue = Double.POSITIVE_INFINITY;
        int bestLevel = -1;
        for (int j = 0; j < options.get(n - 1).size(); j++) {
            if (Double.isInfinite(cost.get(n - 1)[j])) {
                continue;
            }
            Segment exit = free(options.get(n - 1).get(j), last.end, endDepth, edge.length, rules);
            if (exit != null && cost.get(n - 1)[j] + exit.weight < bestValue - EPSILON) {
                bestValue = cost.get(n - 1)[j] + exit.weight;
                bestLevel = j;
            }
        }
        if (bestLevel < 0) {
            return null;
        }
        double[] chosen = new double[n];
        for (int i = n - 1, j = bestLevel; i >= 0; i--) {
            chosen[i] = options.get(i).get(j);
            j = previous.get(i)[j];
        }
        List<double[]> vertices = new ArrayList<>();
        vertices.addAll(free(startDepth, 0, chosen[0], blocks.get(0).start, rules).vertices);
        for (int i = 0; i < n; i++) {
            vertices.add(new double[] {blocks.get(i).start, chosen[i]});
            vertices.add(new double[] {blocks.get(i).end, chosen[i]});
            if (i + 1 < n) {
                vertices.addAll(free(chosen[i], blocks.get(i).end, chosen[i + 1], blocks.get(i + 1).start, rules).vertices);
            }
        }
        vertices.addAll(free(chosen[n - 1], last.end, endDepth, edge.length, rules).vertices);
        return new Solution(bestValue, finish(vertices, rules));
    }

    private static final class Segment {
        final double weight;
        final List<double[]> vertices;

        Segment(double weight, List<double[]> vertices) {
            this.weight = weight;
            this.vertices = vertices;
        }
    }

    /**
     * Free stretch between depth a at x0 and b at x1: to the ordinary depth and back at the maximum
     * slope when it fits, otherwise one straight line; null when even that is too steep.
     */
    private static Segment free(double a, double x0, double b, double x1, DepthRules rules) {
        double gap = x1 - x0;
        if (gap < -EPSILON) {
            return null;
        }
        gap = Math.max(0, gap);
        double ordinary = rules.ordinaryM;
        double slope = rules.maximumSlope * (1 - SLOPE_MARGIN);
        double rampA = Math.abs(a - ordinary) / slope;
        double rampB = Math.abs(b - ordinary) / slope;
        List<double[]> vertices = new ArrayList<>();
        double weight;
        if (rampA + rampB <= gap + EPSILON) {
            vertices.add(new double[] {x0, a});
            vertices.add(new double[] {x0 + rampA, ordinary});
            vertices.add(new double[] {x1 - rampB, ordinary});
            vertices.add(new double[] {x1, b});
            weight = rules.weightedLength(a, ordinary, rampA)
                    + rules.weightedLength(ordinary, ordinary, Math.max(0, gap - rampA - rampB))
                    + rules.weightedLength(ordinary, b, rampB);
        } else if (Math.abs(a - b) <= slope * gap) {
            vertices.add(new double[] {x0, a});
            vertices.add(new double[] {x1, b});
            weight = rules.weightedLength(a, b, gap);
        } else {
            return null;
        }
        return new Segment(weight, vertices);
    }

    /** Drops repeated positions and collinear vertices, then adds a vertex where the profile crosses 3 m. */
    private static List<double[]> finish(List<double[]> raw, DepthRules rules) {
        List<double[]> points = new ArrayList<>();
        for (double[] vertex : raw) {
            double[] last = points.isEmpty() ? null : points.get(points.size() - 1);
            if (last != null && vertex[0] - last[0] <= EPSILON) {
                continue;
            }
            points.add(vertex);
        }
        List<double[]> simplified = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            if (i > 0 && i + 1 < points.size()) {
                double[] a = simplified.get(simplified.size() - 1);
                double[] b = points.get(i);
                double[] c = points.get(i + 1);
                double slopeIn = (b[1] - a[1]) / (b[0] - a[0]);
                double slopeOut = (c[1] - b[1]) / (c[0] - b[0]);
                if (Math.abs(slopeIn - slopeOut) <= 1e-9) {
                    continue;
                }
            }
            simplified.add(points.get(i));
        }
        List<double[]> result = new ArrayList<>();
        double threshold = rules.thresholdM();
        for (int i = 0; i < simplified.size(); i++) {
            double[] b = simplified.get(i);
            if (i > 0) {
                double[] a = simplified.get(i - 1);
                if ((a[1] - threshold) * (b[1] - threshold) < 0) {
                    double share = (threshold - a[1]) / (b[1] - a[1]);
                    result.add(new double[] {a[0] + share * (b[0] - a[0]), threshold});
                }
            }
            result.add(b);
        }
        return result;
    }

    private static List<ru.hackathon.heatnetwork.model.Model.Diagnostic> notFound(
            CandidateTree tree, String root, Map<String, Solution[][]> solved, Map<String, double[]> best) {
        List<ru.hackathon.heatnetwork.model.Model.Diagnostic> result = new ArrayList<>();
        for (String nodeId : tree.order) {
            for (Edge edge : tree.children(nodeId)) {
                boolean below = false;
                for (double value : best.get(edge.toNodeId)) {
                    below |= !Double.isInfinite(value);
                }
                boolean any = false;
                for (Solution[] row : solved.get(edge.id)) {
                    for (Solution solution : row) {
                        any |= solution != null;
                    }
                }
                // Report the edge itself only when the network below it is feasible.
                if (below && !any) {
                    result.add(Rejection.diag("DEPTH_PROFILE_NOT_FOUND", "Для участка " + edge.id
                            + " нет профиля глубины с уклоном не более допустимого, который выполняет"
                            + " вертикальные требования пересечений и глубину 3 м у концов (P1–P3).",
                            null, edge.id));
                }
            }
        }
        if (result.isEmpty()) {
            result.add(Rejection.diag("DEPTH_PROFILE_NOT_FOUND", "Не найдено согласованных глубин в камерах сети от "
                    + root + " при принятых правилах P1–P3.", null, null));
        }
        return result;
    }
}
