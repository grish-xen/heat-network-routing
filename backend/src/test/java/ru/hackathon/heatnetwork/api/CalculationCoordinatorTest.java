package ru.hackathon.heatnetwork.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.*;
import ru.hackathon.heatnetwork.model.Dataset;
import ru.hackathon.heatnetwork.model.Model.*;
import ru.hackathon.heatnetwork.model.ObjectId;
import ru.hackathon.heatnetwork.routing.RoutePlanner;

class CalculationCoordinatorTest {
    private static final GeometryFactory GF = new GeometryFactory(new PrecisionModel(), 32637);
    private final Dataset dataset = mock(Dataset.class);

    @Test void feedbackIsSentForEachCandidateAndOnlyThreeDistinctBestVariantsSurvive() throws Exception {
        List<CalculatedVariant> candidates = List.of(variant("a", 1, 8), variant("b", 2, 4), variant("duplicate", 2, 4),
                variant("c", 3, 2), variant("d", 4, 6));
        AtomicInteger index = new AtomicInteger();
        RoutePlanner.SearchSession session = session(candidates.size());
        CalculationCoordinator coordinator = new CalculationCoordinator((d, o) -> session,
                (d, c, m) -> accepted(c.candidateId, candidates.get(index.getAndIncrement())), new JobProperties());
        List<CalculatedVariant> selected = coordinator.calculate(dataset, stage -> { });
        assertEquals(List.of(new BigDecimal("2"), new BigDecimal("4"), new BigDecimal("6")),
                List.of(selected.get(0).summary.score, selected.get(1).summary.score, selected.get(2).summary.score));
        assertEquals("variant-1", selected.get(0).variantId);
        assertEquals("c", candidates.get(3).variantId, "coordinator must not rename original evaluation");
        verify(session, times(5)).feedback(any());
        verify(session).close();
        verify(dataset, never()).close();
    }

    @Test void aCompleteCandidateReplacesACheaperIntermediatePartial() throws Exception {
        CalculatedVariant partial = variant("partial", 1, 1);
        partial.unconnectedPointIds.add(new ObjectId(TextNode.valueOf("target")));
        CalculatedVariant complete = variant("complete", 2, 100);
        List<CalculatedVariant> candidates = List.of(partial, complete, partial);
        AtomicInteger index = new AtomicInteger();
        RoutePlanner.SearchSession session = session(3);
        List<CalculatedVariant> selected = new CalculationCoordinator((d, o) -> session,
                (d, c, m) -> accepted(c.candidateId, candidates.get(index.getAndIncrement())), new JobProperties())
                .calculate(dataset, stage -> { });
        assertEquals(1, selected.size());
        assertTrue(selected.get(0).unconnectedPointIds.isEmpty());
        assertEquals(new BigDecimal("100"), selected.get(0).summary.score);
    }

    @Test void rejectionFeedsBackAndSearchRespectsItsBudget() throws Exception {
        RoutePlanner.SearchSession session = session(100);
        JobProperties properties = new JobProperties();
        properties.setMaxCandidates(2);
        CalculationCoordinator coordinator = new CalculationCoordinator((d, o) -> session,
                (d, c, m) -> { Evaluation e = new Evaluation(); e.candidateId = c.candidateId; return e; }, properties);
        CalculationCoordinator.NoValidVariantException failure = assertThrows(CalculationCoordinator.NoValidVariantException.class,
                () -> coordinator.calculate(dataset, stage -> { }));
        assertEquals("ROUTE_NOT_FOUND", failure.diagnostics.get(0).code);
        verify(session, times(2)).next();
        verify(session, times(2)).feedback(any());
        verify(session).close();
    }

    @Test void internalFailureClosesSessionAndDoesNotBecomeAnUnconnectedSuccess() {
        RoutePlanner.SearchSession session = session(1);
        CalculationCoordinator coordinator = new CalculationCoordinator((d, o) -> session,
                (d, c, m) -> { throw new IllegalStateException("broken calculation"); }, new JobProperties());
        assertThrows(IllegalStateException.class, () -> coordinator.calculate(dataset, stage -> { }));
        verify(session).close();
        verify(session, never()).feedback(any());
    }

    @Test void interruptedSearchClosesSessionBeforeExport() {
        RoutePlanner.SearchSession session = session(1);
        CalculationCoordinator coordinator = new CalculationCoordinator((d, o) -> session,
                (d, c, m) -> { Thread.currentThread().interrupt(); return accepted(c.candidateId, variant("v", 1, 1)); }, new JobProperties());
        try { assertThrows(java.io.InterruptedIOException.class, () -> coordinator.calculate(dataset, stage -> { })); }
        finally { Thread.interrupted(); }
        verify(session).close();
    }

    private RoutePlanner.SearchSession session(int count) {
        RoutePlanner.SearchSession session = mock(RoutePlanner.SearchSession.class);
        AtomicInteger sequence = new AtomicInteger();
        when(session.next()).thenAnswer(call -> {
            int i = sequence.getAndIncrement();
            if (i >= count) return Optional.empty();
            RouteCandidate candidate = new RouteCandidate(); candidate.candidateId = "c" + i;
            return Optional.of(candidate);
        });
        return session;
    }

    private Evaluation accepted(String id, CalculatedVariant variant) {
        Evaluation evaluation = new Evaluation(); evaluation.candidateId = id; evaluation.variant = variant;
        return evaluation;
    }

    // Calculator is a test double here; real engineering checks are exercised by JobApiTest.
    private CalculatedVariant variant(String id, double x, int score) {
        CalculatedVariant variant = new CalculatedVariant(); variant.variantId = id; variant.mode = Mode.TWO_D;
        Node node = new Node(); node.id = id + "-node"; node.kind = NodeKind.NEW_CHAMBER;
        node.geometry = GF.createPoint(new Coordinate(400000 + x, 6170000)); variant.nodes.add(node);
        variant.summary = new Summary(); variant.summary.score = BigDecimal.valueOf(score);
        return variant;
    }
}
