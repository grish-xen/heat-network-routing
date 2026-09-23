package ru.hackathon.heatnetwork.calculation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import ru.hackathon.heatnetwork.model.Model.Edge;
import ru.hackathon.heatnetwork.routing.RulesCatalog;
import ru.hackathon.heatnetwork.routing.RulesCatalog.DiameterRow;

/**
 * Diameter selection by section 2.3 of the appendix and clarifications 1–2.
 *
 * <p>A run is a maximal chain of edges between nodes where the flow changes (roots,
 * branching chambers, connection points); its flow and diameter are constant, so the
 * diameter is never changed only to restart the length count. Runs are sized from the
 * leaves to the roots: the smallest catalog diameter that carries the flow, is not
 * smaller than any downstream run (no decrease towards the attachment) and keeps every
 * continuous path of one diameter within its maximum length. A shared run counts in
 * each path through it; parallel branches are not summed.</p>
 */
final class DiameterSelector {
    private static final double LENGTH_EPSILON_M = 1e-6;

    private DiameterSelector() { }

    /** Returns the diameter of every candidate edge. */
    static Map<String, Integer> select(CandidateTree tree, Map<String, Double> edgeLengths, RulesCatalog catalog)
            throws Rejection {
        List<DiameterRow> rows = catalog.diameters();
        List<Run> runs = runs(tree, edgeLengths);
        Map<String, Integer> result = new HashMap<>();
        for (int i = runs.size() - 1; i >= 0; i--) {
            Run run = runs.get(i);
            int first = firstForFlow(rows, run.flow);
            if (first < 0) {
                throw Rejection.of("DIAMETER_LIMIT", "Расход " + run.flow.toPlainString()
                        + " т/ч превышает пропускную способность наибольшего ДУ.", null, run.edges.get(0).id);
            }
            for (Run child : run.children) {
                first = Math.max(first, child.index);
            }
            boolean sized = false;
            for (int index = first; index < rows.size() && !sized; index++) {
                double sameDiameterBelow = 0;
                for (Run child : run.children) {
                    if (child.index == index) {
                        sameDiameterBelow = Math.max(sameDiameterBelow, child.sameDiameterLength);
                    }
                }
                double pathLength = run.length + sameDiameterBelow;
                if (pathLength <= rows.get(index).maxLengthM + LENGTH_EPSILON_M) {
                    run.index = index;
                    run.sameDiameterLength = pathLength;
                    sized = true;
                }
            }
            if (!sized) {
                throw Rejection.of("LENGTH_LIMIT", "Ни один ДУ не удовлетворяет предельной длине для расхода "
                        + run.flow.toPlainString() + " т/ч.", null, run.edges.get(0).id);
            }
            for (Edge edge : run.edges) {
                result.put(edge.id, rows.get(run.index).diameterMm);
            }
        }
        return result;
    }

    private static int firstForFlow(List<DiameterRow> rows, BigDecimal flow) {
        for (int i = 0; i < rows.size(); i++) {
            if (BigDecimal.valueOf(rows.get(i).capacityTph).compareTo(flow) >= 0) {
                return i;
            }
        }
        return -1;
    }

    /** Runs in breadth-first order from the roots: parents precede their children. */
    private static List<Run> runs(CandidateTree tree, Map<String, Double> edgeLengths) {
        List<Run> runs = new ArrayList<>();
        Map<String, List<Run>> startingAt = new HashMap<>();
        for (String nodeId : tree.order) {
            List<Edge> out = tree.children(nodeId);
            if (!tree.isRoot(nodeId) && out.size() < 2) {
                continue;
            }
            for (Edge first : out) {
                Run run = new Run();
                Edge edge = first;
                while (true) {
                    run.edges.add(edge);
                    run.length += edgeLengths.get(edge.id);
                    List<Edge> next = tree.children(edge.toNodeId);
                    if (next.size() != 1) {
                        run.endNodeId = edge.toNodeId;
                        break;
                    }
                    edge = next.get(0);
                }
                run.flow = tree.downstreamFlow.get(first.toNodeId);
                startingAt.computeIfAbsent(nodeId, key -> new ArrayList<>()).add(run);
                runs.add(run);
            }
        }
        // tree.order visits a branching node after the run that ends in it.
        for (Run run : runs) {
            run.children.addAll(startingAt.getOrDefault(run.endNodeId, new ArrayList<>()));
        }
        return runs;
    }

    private static final class Run {
        final List<Edge> edges = new ArrayList<>();
        final List<Run> children = new ArrayList<>();
        String endNodeId;
        BigDecimal flow;
        double length;
        int index = -1;
        /** Longest continuous path of this run's diameter starting at the run's upstream end. */
        double sameDiameterLength;
    }
}
