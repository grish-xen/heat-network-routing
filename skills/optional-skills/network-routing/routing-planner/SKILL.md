---
name: routing-planner
description: Plan heat-network routes in EPSG:32637 with A* search, bbox prefilter, PriorityQueue open set, tree branching via tap points, and depth-contract loader. Keeps 2D intact; DEPTH reserved until calculation profile ready.
version: 0.1.0
author: User (master), Hermes Agent
license: MIT
platforms: [linux, macos, windows]
metadata:
  hermes:
    tags: [routing, heat-network, module-2, depth-contract, astar, tree-topology]
    related_skills: []
---

# Routing Planner (Module 2 — Routes and Geometry)

Use when implementing or auditing grid A* route planning, metric scene construction (`RoutingContext` in EPSG:32637), spatial validation (`DefaultSpatialValidator`), or depth-contract integration (`RulesCatalog.loadDepth()`) for the heat-network routing project. This covers 2D mode; `DEPTH` remains reserved by contract until the profile planner (module 3) and D1 contract fixes are complete.

Don't use for: full 3D profile building (calculation/module 3 owns profile splitting and `DEPTH_SLOPE_VIOLATION`), frontend map rendering, or HTTP API changes.

## When to Use

- User asks for module 2 routing improvements, planner performance fixes, or depth-mode preparation.
- A compilation error references `catalogStaticMinimal`, `existingLineDegreeAt`, `ObjectId.key()`, or `dataset.objects(null)`.
- A planner stops at fewer candidates than expected (search budget, `tieOptions` size, or `validOwnOksApproach` rejecting grid zigzags).
- A git merge conflict appears in `GridRoutePlanner.java` with `<<<<<<< HEAD` / `MAX_TRACES_PER_TARGET` markers.
- A user asks to read `docs/DEPTH_DEVELOPMENT_PLAN.md` or review P1–P4 before depth-mode integration.
- A repository check (`python scripts/check_repository.py`) passes and `mvn verify` must confirm 178+ tests with 0 failures.

Don't use for: rewriting `calculation.DefaultVariantCalculator` (owns profile splitting); editing frontend map components; or pushing to remote without agreement.

## Prerequisites

- `backend/src/main/resources/rules/catalog-v1.json` (shared numeric catalog, verified by `scripts/check_repository.py`)
- `backend/src/main/resources/rules/depth-v1.json` (depth supplement; not a second independent table — loaded via `RulesCatalog.loadDepth()` only)
- JDK 11-compatible code; Maven wrapper at `backend/pom.xml`
- `docs/MODULE_CONTRACTS.md` and `docs/DATA_MODEL.md` read before any contract change
- No hardcoded competition GeoJSON data (coordinates, object counts, geometry) in algorithm

## How to Run (Verification Order)

Every change must pass in this order; do not claim work done after only the first passing stage:

1. `mvn -f backend/pom.xml compile` — syntax/import fixes (`catalogStaticMinimal`, missing methods)
2. `mvn -f backend/pom.xml verify` — 14 `RoutingModuleTest` + 178 total tests, 0 failures
3. `python scripts/check_repository.py` — OK (catalog agreement)
4. Integration on synthetic fixtures (`test-data/synthetic/`), then on `competition-corrected.geojson`
5. Confirm competition result matches: 17/17 targets routed (`CompetitionDataInvestigationTest` output: `Connected: 17 / 17`)

## Quick Reference

```bash
# Compile
mvn -f backend/pom.xml compile -q

# Unit + integration tests
mvn -f backend/pom.xml verify -q
# Check specific test
mvn -f backend/pom.xml test -Dtest=RoutingModuleTest

# Repository contract checks
python scripts/check_repository.py

# Read depth rules (typed loader, no duplicate constants)
java -cp ... RulesCatalog.loadDepth()
```

## Procedure

### 1. Study contracts before changing inter-module types
Read `docs/MODULE_CONTRACTS.md`, `docs/DATA_MODEL.md`, `docs/DEPTH_DEVELOPMENT_PLAN.md`, `docs/DEPTH_CONTRACT.md`. Confirm any change to shared types updates these docs atomically in the same PR. Don't invent `DEPTH_PROFILE_NOT_FOUND` independently — module 3 (`DefaultVariantCalculator`) defines profile existence.

### 2. Load shared rules correctly (not duplicated)
Use `RulesCatalog.loadDefault()` for `catalog-v1.json`. For depth supplement: `RulesCatalog.loadDepth()` reads `/rules/depth-v1.json` once; never duplicate `ordinaryDepthM` (3.0), `minimumDepthM` (0.7), `maximumSlope` (0.10), or crossing rules inside planner or calculator. Verify loader exists before using:`grep -n "loadDepth\|DepthCatalog" RulesCatalog.java`.

### 3. Fix planner compilation errors from real sources
If a reference like `catalogStaticMinimal` or `existingLineDegreeAt` is missing, trace to `RulesCatalog` or `RoutingContext` definition; don't invent stub methods. If `ObjectId.key()` is called incorrectly, use `.equals()` on `ObjectId` instances (they hold `JsonNode` values). If `dataset.objects(null)` is used, replace with typed streams: `dataset.objects(InputType.OKS_CONNECTION_POINT)` etc.

### 4. Optimize A* open set correctly
Replace `HashSet<String> open` + linear min-scan (`bestF` loop) with `PriorityQueue<String>` ordered by `fScore::get`. The `HashSet` scan is `O(n)` per expansion; `PriorityQueue.poll()` is `O(log n)`. Confirm by searching for `PriorityQueue` in `GridRoutePlanner.java`.

### 5. Scale search budget by distance and step (not fixed cap)
Use dynamic `maxExpansions`: `BASE_MAX_EXPANSIONS * (25.0 / step) * distanceFactor` where `distanceFactor = max(0.5, min(5.0, straightDist / 1000))`. Don't use a hard `150_000` or `300_000` cap that ignores distance; the old fixed cap caused early termination on complex scenes.

### 6. Adjust search padding (not infinite 10x)
Set `padM = max(500.0, min(5000.0, straightDist * 3.0))` instead of `straightDist * 10.0`. Large unrestricted pads create 10 km × 10 km grids that time out on difficult targets.

### 7. Implement tree branching with tap points (not independent edges)
When direct tie options (`tieOptions`) fail, search `acceptedTraces.values()` for tap vertices (`i` between root index 0 and leaf `pts.size()-1`). For each untried vertex, check `tapsByParent.get(parentTargetId).get(i) < MAX_TAPS_PER_VERTEX` (2). Mark tried in `state.triedTaps`. Create `Trace` with `tapParentTargetId` and `tapVertexIndex`; `assembleCandidate()` splits parent polyline at registered taps using `branchNode()` (`NEW_CHAMBER` with geometry at vertex coordinate).

### 8. Resolve git conflicts by keeping feature branch
If `GridRoutePlanner.java` has `<<<<<<< HEAD` / `MAX_TRACES_PER_TARGET` / `BASE_MAX_EXPANSIONS` markers, resolve by keeping the feature branch version (new stage machine with `TargetAttemptState`, not the old fixed-cap version). Confirm with: `grep -c '<<<<<<<' backend/src/main/java/ru/hackathon/heatnetwork/routing/GridRoutePlanner.java` should return 0.

### 9. Update `feedback()` for cascading removal
After `acceptedTraces.remove(newestTarget)`, cascade: for each remaining trace whose `tapParentTargetId` was removed, call `removeTrace()` recursively. Then rebuild the candidate from the reduced trace set.

### 10. Verify space and time before claiming success
Don't claim `17/17` from a synthetic-only test. Confirm on `competition-corrected.geojson`: `CompetitionDataInvestigationTest` outputs `Connected: 17 / 17`, `Edges: 22`, `Nodes: 29`, `Max camera degree: 4`, and completes within ~70 s (with bbox prefilter). If it exceeds 300 s, check whether bbox prefilter is applied (`blockedByForbidden` and `violatesSpecialClearance` must use `Envelope` prefilter before JTS exact geometry).

### 11. Commit depth changes separately (per user agreement)
When the user requests depth loader or contract review, commit only `RulesCatalog.java`, `depth-v1.json`, and docs (`DEPTH_ASSUMPTIONS_REVIEW.md`) on a dedicated branch (`feature/depth-catalog-loader`). Don't mix planner rewrites (`GridRoutePlanner.java`) into this branch — keep planner improvements on `feature/routing-planner-improvements` so `main` can merge depth contracts without planner conflicts.

### 12. Confirm no new push until user agreement
Every push must be explicit (`git push origin ...` called by user or after direct instruction). If uncertain, say "Ready to push — confirm." Don't substitute local success (`mvn verify` green) for remote publication.

## Pitfalls

1. **Replacing `PriorityQueue` with `HashSet` linear scan.** The `PriorityQueue.poll()` is required for `O(log n)` open-set extraction; reverting to `HashSet` + manual `bestF` loop reintroduces `O(n)` per expansion and timeouts on 1M expansions.
2. **Using fixed `MAX_EXPANSIONS` without distance factor.** A 150k cap fails on 500 m+ straight distances; a 1M cap without scaling by step/distance creates unnecessary timeouts on short distances.
3. **Forgetting bbox prefilter in `blockedByForbidden`/`violatesSpecialClearance`.** Without `Envelope.contains` prefilter, every A* neighbor (8 per node) checks all ~120 obstacles with exact JTS distance/intersection; this is the root cause of previous timeouts.
4. **Creating a new `DEPTH_PROFILE_NOT_FOUND` error before module 3 profile is ready.** Module 2 owns routing geometry validation; profile splitting (`h(s)`, slope check, depth continuity at nodes) belongs to `calculation`. Don't invent profile splitting in planner code.
5. **Copying `catalog-v1.json` values manually into planner/calculator.** The contract requires a single source (`RulesCatalog.loadDefault()` + `loadDepth()`); duplicate `3.0` or `0.10` constants in code violate `docs/MODULE_CONTRACTS.md`.
6. **Submitting `CompetitionDataInvestigationTest.java` without cleaning untracked files.** The integration test is for investigation; it should not appear as an untracked artifact in `git status` at final verification. Confirm with: `git status --short` shows only intentional tracked modifications, no stray `CompetitionDataInvestigationTest.java` or `project-dashboard.html`.
7. **Confusing `DEPTH` mode with 2D.** The planner must throw `UNSUPPORTED_MODE` for `DEPTH` until the profile planner (module 3) provides `CalculatedEdge.depthStartM/depthEndM`. Returning a 2D result as `DEPTH` violates the depth contract (`DEPTH_DEVELOPMENT_PLAN.md` section 2: "DEPTH пока не реализован").

## Reference Depth (References)

See `references/depth-contract.md` for the P1–P4 assumption table (`DEPTH_ASSUMPTIONS_REVIEW.md`), crossing rules (`depth-v1.json`), and the decision sequence (D0 → D1 → D2 → D3 → D4) before full DEPTH integration.

## Verification

```bash
# Before any claim of module 2 readiness:
git log --oneline -1                  # HEAD message describes change
mvn -f backend/pom.xml verify -q      # 137+ total, RoutingModuleTest 14/14, INVESTIGATION 1/1, 0 failures
git status --short                    # only intended tracked changes, no stray test files
python scripts/check_repository.py    # OK
python scripts/check_depth_contract.py # (when D1 is fixed) — catalog agreement
```

After a `main` merge (`git merge origin/main --no-edit`), resolve conflict markers (`<<<<<<< HEAD`) by keeping the feature branch version (new stage machine with `TargetAttemptState`, not the old fixed-cap version). Confirm: `grep -c '<<<<<<<' backend/src/main/java/ru/hackathon/heatnetwork/routing/GridRoutePlanner.java` returns 0.
