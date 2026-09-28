# Depth Contract Decisions (DEPTH, module 2 + 3 integration)

Used when reviewing `DEPTH_ASSUMPTIONS_REVIEW.md`, integrating `loadDepth()`, or resolving conflicts between routing planner and depth profile contracts (`DEPTH_DEVELOPMENT_PLAN.md`, `DEPTH_CONTRACT.md`).

## P1–P4 Assumptions (accepted for first DEPTH mode)

These are team agreements (not organizer requirements). They must be reviewed by participant 3 (`calculation`) before D1 (`DATA_MODEL.md` / `MODULE_CONTRACTS.md`) and can be revised.

| ID | Agreement | Routing (2) action | Calculation (3) dependency |
|--- | --- | --- | --- |
| P1 | Root and consumer top = 3.0 m | Search starts with fixed ends; no user input depth at connection points | Profile planner uses fixed ends; if path has no transition space, route fails with meaningful diagnostic |
| P2 | In chamber: top depth of new sections matches | Branch `NEW_CHAMBER` nodes created by `GridRoutePlanner` (`assembleCandidate`) share the same coordinate; no extra depth logic in planner | Profile splits parent edge at branch vertex; each branch gets its own `CalculatedEdge` with `depthStartM/depthEndM` derived from split |
| P3 | Special pass profile: constant depth within required length, transitions outside | Planner treats special pass as a regular obstacle buffer (`RoutingContext.obstacles()`); profile constancy validated by `DepthExportValidation` (`DEPTH_CONTINUITY_VIOLATION` at nodes) | Profile planner must enforce `maximumSlope` (0.10) and split at depth-transition boundaries; `DEPTH_SLOPE_VIOLATION` thrown by output validator |
| P4 | New traces don't intersect in plan outside shared node | `crossesAcceptedTraces()` allows parent polyline touch only at `tapPoint`; multi-layer intersections of new traces not allowed | No extra action needed from routing; profile doesn't change plan intersection rules |

## Rules

- `DEPTH` mode: planner throws `UNSUPPORTED_MODE` (`DEPTH_DEVELOPMENT_PLAN.md`: "DEPTH пока не реализован"). Removing this is the last step after profile planner (3) is ready.
- Catalog supplement (`depth-v1.json`) loaded only via `RulesCatalog.loadDepth()`; no duplicate `ordinaryDepthM`, `minimumDepthM`, `maximumSlope`, or crossing constants in planner/calculator (`docs/MODULE_CONTRACTS.md`: single source).
- `DEPTH_ASSUMPTIONS_REVIEW.md` is the official team review artifact; it must reference the actual file names (`DEPTH_DEVELOPMENT_PLAN.md`, `DEPTH_CONTRACT.md`) and not quote user text as rules.
- Before integrating `DEPTH` into `GridRoutePlanner.next()`, participant 3 must confirm profile splitting (`assembleCandidate()` split at branch node + tap split) aligns with `DepthExportValidation.check()` expectations (`DEPTH_CONTINUITY_VIOLATION` at node depth ranges ≤ `CONTINUITY_TOLERANCE_M` = 1e-6).
- When `DEPTH` is enabled, `GridRoutePlanner` must pass `step` to `aStar` correctly; coarser grids (40 m, 60 m) are allowed but must still pass bbox prefilter (`RoutingContext.blockedByForbidden` and `violatesSpecialClearance`).

## Cross-Reference (not duplicate rules)

- Planning optimization: `routing-planner` skill (`GridRoutePlanner.java` procedure, bbox prefilter in `RoutingContext.java`)
- Calculation contracts: `docs/CALCULATION_MODULE.md`
- Module contracts: `docs/MODULE_CONTRACTS.md`
- Repository verification: `python scripts/check_repository.py`
- Integration result: `CompetitionDataInvestigationTest` output (`Connected: 17 / 17`)
