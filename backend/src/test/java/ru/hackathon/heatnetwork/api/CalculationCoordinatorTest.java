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
                (d, c, m) -> accepted(c.candidateId, candidates.get(index.getAndIncrement())), oneStrategy());
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
                (d, c, m) -> accepted(c.candidateId, candidates.get(index.getAndIncrement())), oneStrategy())
                .calculate(dataset, stage -> { });
        assertEquals(1, selected.size());
        assertTrue(selected.get(0).unconnectedPointIds.isEmpty());
        assertEquals(new BigDecimal("100"), selected.get(0).summary.score);
    }

    @Test void rejectionFeedsBackAndSearchRespectsItsBudget() throws Exception {
        RoutePlanner.SearchSession session = session(100);
        JobProperties properties = oneStrategy();
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
                (d, c, m) -> { throw new IllegalStateException("broken calculation"); }, oneStrategy());
        assertThrows(IllegalStateException.class, () -> coordinator.calculate(dataset, stage -> { }));
        verify(session).close();
        verify(session, never()).feedback(any());
    }

    @Test void interruptedSearchClosesSessionBeforeExport() {
        RoutePlanner.SearchSession session = session(1);
        CalculationCoordinator coordinator = new CalculationCoordinator((d, o) -> session,
                (d, c, m) -> { Thread.currentThread().interrupt(); return accepted(c.candidateId, variant("v", 1, 1)); }, oneStrategy());
        try { assertThrows(java.io.InterruptedIOException.class, () -> coordinator.calculate(dataset, stage -> { })); }
        finally { Thread.interrupted(); }
        verify(session).close();
    }

    @Test void differentDepthProfilesSurviveSelectionAndTheCheapestDuplicateWins() throws Exception {
        CalculatedVariant deep = profile("deep", Mode.DEPTH, 3.4, 9);
        CalculatedVariant shallow = profile("shallow", Mode.DEPTH, 2.4, 2);
        CalculatedVariant flat = profile("flat", Mode.DEPTH, 3.0, 4);
        CalculatedVariant duplicate = profile("renamed-deep", Mode.DEPTH, 3.4, 3);
        Collections.reverse(duplicate.nodes);
        Collections.reverse(duplicate.edges);
        List<CalculatedVariant> candidates = List.of(deep, shallow, flat, duplicate, deep);
        AtomicInteger index = new AtomicInteger();
        RoutePlanner.SearchSession session = session(candidates.size());
        CalculationCoordinator coordinator = new CalculationCoordinator((d, options) -> {
            assertEquals(Mode.DEPTH, options.mode);
            return session;
        }, (d, c, mode) -> {
            assertEquals(Mode.DEPTH, mode);
            return accepted(c.candidateId, candidates.get(index.getAndIncrement()));
        }, oneStrategy());

        List<CalculatedVariant> selected = coordinator.calculate(dataset, Mode.DEPTH, stage -> { });

        assertEquals(3, selected.size());
        assertSame(shallow.edges, selected.get(0).edges);
        assertSame(duplicate.edges, selected.get(1).edges);
        assertSame(flat.edges, selected.get(2).edges);
        for (int i = 0; i < selected.size(); i++) {
            assertEquals(Mode.DEPTH, selected.get(i).mode);
            assertEquals("variant-" + (i + 1), selected.get(i).variantId);
        }
        assertEquals("renamed-deep", duplicate.variantId);
        assertEquals(3.4, duplicate.edges.get(0).depthStartM);
        verify(session, times(candidates.size())).feedback(any());
        verify(session).close();
        verify(dataset, never()).close();
    }

    @Test void aShiftedRouteOfTheSameSchemeIsNotAnotherVariant() throws Exception {
        // Same attachment point and consumer, the route differs by 3 m: only the cheaper one is kept.
        CalculatedVariant straight = scheme("straight", 0, 5, 4);
        CalculatedVariant shifted = scheme("shifted", 3, 5, 6);
        CalculatedVariant elsewhere = scheme("elsewhere", 0, 40, 9);
        List<CalculatedVariant> candidates = List.of(shifted, straight, elsewhere);
        AtomicInteger index = new AtomicInteger();
        RoutePlanner.SearchSession session = session(candidates.size());
        List<CalculatedVariant> selected = new CalculationCoordinator((d, o) -> session,
                (d, c, m) -> accepted(c.candidateId, candidates.get(index.getAndIncrement())), oneStrategy())
                .calculate(dataset, stage -> { });
        assertEquals(2, selected.size());
        assertSame(straight.edges, selected.get(0).edges);
        assertSame(elsewhere.edges, selected.get(1).edges);
    }

    /** One chamber at (rootX, 0) serving consumer "p" at (100, 50) through a bend at (50, bendY). */
    private CalculatedVariant scheme(String id, double bendY, double rootX, int score) {
        CalculatedVariant variant = new CalculatedVariant(); variant.variantId = id; variant.mode = Mode.TWO_D;
        Node root = new Node(); root.id = id + "-root"; root.kind = NodeKind.NEW_CHAMBER;
        root.geometry = GF.createPoint(new Coordinate(400000 + rootX, 6170000));
        Node point = new Node(); point.id = id + "-point"; point.kind = NodeKind.CONNECTION_POINT;
        point.geometry = GF.createPoint(new Coordinate(400100, 6170050));
        point.inputObjectId = new ObjectId(TextNode.valueOf("p"));
        variant.nodes.add(root); variant.nodes.add(point);
        CalculatedEdge edge = new CalculatedEdge(); edge.id = id + "-edge"; edge.fromNodeId = root.id; edge.toNodeId = point.id;
        edge.geometry = GF.createLineString(new Coordinate[] {root.geometry.getCoordinate(),
                new Coordinate(400050, 6170000 + bendY), point.geometry.getCoordinate()});
        edge.flowTph = BigDecimal.ONE; edge.diameterMm = 80; edge.lengthM = edge.geometry.getLength();
        edge.layingMethod = LayingMethod.BASE; edge.specialCoefficient = BigDecimal.ONE;
        variant.edges.add(edge);
        Attachment attachment = new Attachment(); attachment.rootNodeId = root.id; variant.attachments.add(attachment);
        variant.summary = new Summary(); variant.summary.score = BigDecimal.valueOf(score);
        return variant;
    }

    @Test void signatureIgnoresGeneratedIdsAndListOrderInBothModes() throws Exception {
        for (Mode mode : Mode.values()) {
            CalculatedVariant first = profile("first", mode, 3.4, 1);
            CalculatedVariant renamed = profile("renamed", mode, 3.4, 2);
            Collections.reverse(renamed.nodes);
            Collections.reverse(renamed.edges);
            assertEquals(CalculationCoordinator.signature(first), CalculationCoordinator.signature(renamed));
        }
    }

    @Test void signatureIncludesModeEvenWhenEveryOtherFieldIsIdentical() throws Exception {
        CalculatedVariant variant = profile("v", Mode.DEPTH, 3.0, 1);
        String depth = CalculationCoordinator.signature(variant);
        variant.mode = Mode.TWO_D;
        assertNotEquals(depth, CalculationCoordinator.signature(variant));
    }

    @Test void signaturePreservesBothEndpointDepthsWithoutRoundingOrSubstitution() throws Exception {
        CalculatedVariant variant = profile("v", Mode.DEPTH, 3.4, 1);
        String original = CalculationCoordinator.signature(variant);
        CalculatedEdge edge = variant.edges.get(0);
        edge.depthStartM = 3.0000001;
        assertNotEquals(original, CalculationCoordinator.signature(variant));
        edge.depthStartM = 3.0;
        edge.depthEndM = 3.4000001;
        assertNotEquals(original, CalculationCoordinator.signature(variant));
        edge.depthEndM = 3.4;
        edge.depthStartM = null;
        assertNotEquals(original, CalculationCoordinator.signature(variant));
        edge.depthStartM = 3.0;
        edge.depthEndM = null;
        assertNotEquals(original, CalculationCoordinator.signature(variant));
    }

    @Test void signatureBindsDepthsToTheirDirectedEdges() throws Exception {
        CalculatedVariant variant = profile("v", Mode.DEPTH, 3.4, 1);
        String original = CalculationCoordinator.signature(variant);
        // Same multiset of depths, different values at the geometric endpoints.
        for (CalculatedEdge edge : variant.edges) {
            Double start = edge.depthStartM;
            edge.depthStartM = edge.depthEndM;
            edge.depthEndM = start;
        }
        assertNotEquals(original, CalculationCoordinator.signature(variant));
    }

    @Test void wrongOrMissingCalculatedModeCannotEnterTheRanking() {
        for (Mode requested : Mode.values()) {
            for (Mode returned : Arrays.asList(Mode.TWO_D, Mode.DEPTH, null)) {
                if (requested == returned) continue;
                RoutePlanner.SearchSession session = session(1);
                CalculatedVariant wrong = profile("wrong", returned, 3.0, 1);
                CalculationCoordinator coordinator = new CalculationCoordinator((d, o) -> session,
                        (d, c, m) -> accepted(c.candidateId, wrong), oneStrategy());
                assertThrows(IllegalStateException.class,
                        () -> coordinator.calculate(dataset, requested, stage -> { }));
                verify(session).close();
            }
        }
    }

    @Test void existingEntryPointStillRequestsTwoD() throws Exception {
        RoutePlanner.SearchSession session = session(1);
        CalculationCoordinator coordinator = new CalculationCoordinator((d, options) -> {
            assertEquals(Mode.TWO_D, options.mode);
            return session;
        }, (d, c, mode) -> {
            assertEquals(Mode.TWO_D, mode);
            return accepted(c.candidateId, profile("v", mode, 3.0, 1));
        }, oneStrategy());
        CalculatedVariant selected = coordinator.calculate(dataset, stage -> { }).get(0);
        assertEquals(Mode.TWO_D, selected.mode);
        for (CalculatedEdge edge : selected.edges) {
            assertNull(edge.depthStartM);
            assertNull(edge.depthEndM);
        }
    }

    @Test void searchDiagnosticsSurviveRejectedCandidatesAndBudgetLimitWithAResult() throws Exception {
        RoutePlanner.SearchSession session = session(3);
        JobProperties properties = oneStrategy();
        properties.setMaxCandidates(2);
        AtomicInteger calls = new AtomicInteger();
        List<ApiError> diagnostics = new ArrayList<>();
        CalculationCoordinator coordinator = new CalculationCoordinator((d, o) -> session, (d, c, mode) -> {
            if (calls.getAndIncrement() == 0) return new Evaluation();
            return accepted(c.candidateId, profile("ok", mode, 3.0, 1));
        }, properties);
        assertEquals(1, coordinator.calculate(dataset, Mode.DEPTH, stage -> { }, diagnostics).size());
        assertTrue(diagnostics.stream().anyMatch(d -> "SEARCH_BUDGET_EXHAUSTED".equals(d.code)));
        verify(session, times(2)).feedback(any());
    }

    @Test void candidateDiagnosticsArePreservedWithoutInventingBudgetExhaustionFromEmptyOptional() throws Exception {
        RoutePlanner.SearchSession session = session(1);
        RouteCandidate candidate = new RouteCandidate(); candidate.candidateId = "c";
        Diagnostic diagnostic = new Diagnostic(); diagnostic.code = "SEARCH_BUDGET_EXHAUSTED";
        diagnostic.message = "internal limit"; candidate.diagnostics.add(diagnostic);
        when(session.next()).thenReturn(Optional.of(candidate), Optional.empty());
        List<ApiError> diagnostics = new ArrayList<>();
        CalculationCoordinator coordinator = new CalculationCoordinator((d, o) -> session,
                (d, c, mode) -> accepted(c.candidateId, profile("ok", mode, 3, 1)), oneStrategy());
        coordinator.calculate(dataset, Mode.DEPTH, stage -> { }, diagnostics);
        assertEquals(1, diagnostics.size());
        assertEquals("internal limit", diagnostics.get(0).message);

        RoutePlanner.SearchSession ordinary = session(1);
        diagnostics.clear();
        new CalculationCoordinator((d, o) -> ordinary,
                (d, c, mode) -> accepted(c.candidateId, profile("ok", mode, 3, 1)), oneStrategy())
                .calculate(dataset, Mode.DEPTH, stage -> { }, diagnostics);
        assertTrue(diagnostics.isEmpty());
    }

    @Test void everyStrategyIsSearchedAndTheirBestDistinctVariantsAreRankedTogether() throws Exception {
        // The planner receives seed, seed + 1, seed + 2; each session yields one complete scheme.
        List<Long> seeds = Collections.synchronizedList(new ArrayList<>());
        Map<String, CalculatedVariant> byCandidate = Map.of(
                "s7", variant("joint", 1, 5), "s8", variant("line-ties", 2, 9), "s9", variant("near-first", 3, 7));
        JobProperties properties = new JobProperties();
        properties.setSearchSeed(7);
        properties.setVariantStrategies(3);
        List<ApiError> reported = new ArrayList<>();
        List<CalculatedVariant> selected = new CalculationCoordinator((d, options) -> {
            seeds.add(options.seed);
            RouteCandidate candidate = new RouteCandidate();
            candidate.candidateId = "s" + options.seed;
            if (options.seed != 7L) {
                // An alternative strategy that reports a budget stop must not mark the main result.
                Diagnostic stop = new Diagnostic();
                stop.code = "SEARCH_BUDGET_EXHAUSTED";
                stop.message = "alternative";
                candidate.diagnostics.add(stop);
            }
            RoutePlanner.SearchSession session = mock(RoutePlanner.SearchSession.class);
            when(session.next()).thenReturn(Optional.of(candidate), Optional.empty());
            return session;
        }, (d, c, m) -> accepted(c.candidateId, byCandidate.get(c.candidateId)), properties)
                .calculate(dataset, Mode.TWO_D, stage -> { }, reported);
        assertEquals(Set.of(7L, 8L, 9L), new HashSet<>(seeds));
        assertEquals(List.of(new BigDecimal("5"), new BigDecimal("7"), new BigDecimal("9")),
                List.of(selected.get(0).summary.score, selected.get(1).summary.score, selected.get(2).summary.score));
        assertEquals(List.of("variant-1", "variant-2", "variant-3"),
                List.of(selected.get(0).variantId, selected.get(1).variantId, selected.get(2).variantId));
        assertTrue(reported.isEmpty(), "only the main strategy reports search diagnostics: " + reported.size());
    }

    @Test void aSlowAlternativeIsInterruptedAndTheMainResultStands() throws Exception {
        JobProperties properties = new JobProperties();
        properties.setVariantStrategies(2);
        properties.setAlternativeTimeout(java.time.Duration.ofMillis(300));
        java.util.concurrent.CountDownLatch stopped = new java.util.concurrent.CountDownLatch(1);
        long started = System.nanoTime();
        List<CalculatedVariant> selected = new CalculationCoordinator((d, options) -> {
            if (options.seed == 0L) return session(1);
            RoutePlanner.SearchSession slow = mock(RoutePlanner.SearchSession.class);
            when(slow.next()).thenAnswer(call -> {
                try { Thread.sleep(60_000); } catch (InterruptedException interrupted) { stopped.countDown(); }
                throw new java.util.concurrent.CancellationException("Route search interrupted");
            });
            return slow;
        }, (d, c, m) -> accepted(c.candidateId, variant("main", 1, 3)), properties)
                .calculate(dataset, Mode.TWO_D, stage -> { }, new ArrayList<>());
        assertEquals(1, selected.size());
        assertEquals(new BigDecimal("3"), selected.get(0).summary.score);
        assertTrue(System.nanoTime() - started < java.util.concurrent.TimeUnit.SECONDS.toNanos(10));
        assertTrue(stopped.await(5, java.util.concurrent.TimeUnit.SECONDS), "the alternative search is interrupted");
    }

    private static JobProperties oneStrategy() {
        JobProperties properties = new JobProperties();
        properties.setVariantStrategies(1);
        return properties;
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

    // Prepared profiles exercise selection only; this is not a DEPTH solver test.
    private CalculatedVariant profile(String id, Mode mode, double middleDepth, int score) {
        CalculatedVariant variant = new CalculatedVariant();
        variant.variantId = id;
        variant.mode = mode;
        variant.summary = new Summary();
        variant.summary.score = BigDecimal.valueOf(score);
        for (int i = 0; i < 3; i++) {
            Node node = new Node();
            node.id = id + "-node-" + i;
            node.kind = i == 0 ? NodeKind.EXISTING_CHAMBER
                    : i == 1 ? NodeKind.TECHNICAL_NODE : NodeKind.CONNECTION_POINT;
            node.geometry = GF.createPoint(new Coordinate(400000 + i * 20, 6170000));
            if (i != 1) node.inputObjectId = new ObjectId(TextNode.valueOf(i == 0 ? "chamber" : "consumer"));
            variant.nodes.add(node);
        }
        Attachment attachment = new Attachment();
        attachment.rootNodeId = variant.nodes.get(0).id;
        attachment.existingObjectId = variant.nodes.get(0).inputObjectId;
        variant.attachments.add(attachment);
        for (int i = 0; i < 2; i++) {
            CalculatedEdge edge = new CalculatedEdge();
            edge.id = id + "-edge-" + i;
            edge.fromNodeId = variant.nodes.get(i).id;
            edge.toNodeId = variant.nodes.get(i + 1).id;
            edge.geometry = GF.createLineString(new Coordinate[]{
                    variant.nodes.get(i).geometry.getCoordinate(), variant.nodes.get(i + 1).geometry.getCoordinate()});
            edge.lengthM = edge.geometry.getLength();
            edge.diameterMm = 80;
            edge.flowTph = BigDecimal.ONE;
            edge.layingMethod = LayingMethod.BASE;
            edge.specialCoefficient = BigDecimal.ONE;
            if (mode == Mode.DEPTH) {
                edge.depthStartM = i == 0 ? 3.0 : middleDepth;
                edge.depthEndM = i == 0 ? middleDepth : 3.0;
            }
            variant.edges.add(edge);
        }
        return variant;
    }

    // Calculator is a test double here; real engineering checks are exercised by JobApiTest.
    // Each x is its own attachment point, so different x are different connection schemes.
    private CalculatedVariant variant(String id, double x, int score) {
        CalculatedVariant variant = new CalculatedVariant(); variant.variantId = id; variant.mode = Mode.TWO_D;
        Node node = new Node(); node.id = id + "-node"; node.kind = NodeKind.NEW_CHAMBER;
        node.geometry = GF.createPoint(new Coordinate(400000 + x, 6170000)); variant.nodes.add(node);
        Attachment attachment = new Attachment(); attachment.rootNodeId = node.id; variant.attachments.add(attachment);
        variant.summary = new Summary(); variant.summary.score = BigDecimal.valueOf(score);
        return variant;
    }
}
