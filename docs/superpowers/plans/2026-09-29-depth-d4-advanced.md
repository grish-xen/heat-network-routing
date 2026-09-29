# DEPTH D4 Advanced Frontend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the remaining frontend D4 scene safeguards, communication visualisation, selected-section interaction, and resilience checks after the critical-fix PR is reviewable.

**Architecture:** Extend the existing paginated loader with a total feature cap and no stale placeholder data for depth views. Convert relevant `input` map features through the same local metric coordinate system as the selected route, then pass immutable scene descriptors and a selection callback to the Three adapter.

**Tech Stack:** React 19, TypeScript, TanStack Query, MapLibre data contract, Three.js, Vitest, browser acceptance script.

**Spec:** `docs/superpowers/specs/2026-09-29-depth-d4-critical-design.md`

## Global Constraints

- Start only after the critical PR is reviewable; branch anew from its merged `main`.
- Enforce one explicit cumulative feature limit for profile/3D loading; do not treat the page-size `limit` as a total limit.
- Abort stale requests and never render old route geometry for a new job or variant.
- Use only `layer=input`, `layer=result`, and `visualization-rules.v1.json`; do not invent unavailable communications.
- Preserve a working SVG profile and export when WebGL is unavailable.

## Review Focus

- A cursor stream exceeding the total cap must stop before the next page and explain the limit; test in Task 1.
- A slow aborted request must not render after switching variant/job; test in Task 2.
- Unknown `restrictionType` must be ignored visibly as unsupported, never assigned a guessed depth; test in Task 3.
- Pointer selection must preserve the selected pipe after resize and reset; test in Task 4.
- At 390 px, scene controls must not produce document-level horizontal overflow; browser-test in Task 5.

---

### Task 1: Cumulative depth feature cap

**Files:**
- Modify: `frontend/src/features/network-map/use-map-features.ts`
- Test: `frontend/src/features/network-map/use-map-features.test.tsx`

- [ ] **Step 1: Write a failing capped-pagination test**

```ts
await expect(loadMapFeatures(api, 'job', query, signal, 2)).rejects.toThrow(/предел.*объект/i)
expect(getMapPage).toHaveBeenCalledTimes(2)
```

- [ ] **Step 2: Verify the focused test fails**

Run: `npm --prefix frontend test -- use-map-features.test.tsx`

- [ ] **Step 3: Add `maxFeatures?: number` to `loadMapFeatures` and depth query options**

Reject before appending or requesting beyond the configured total. Keep default map behaviour unchanged; pass a named `MAX_DEPTH_SCENE_FEATURES` only from depth workspace queries.

- [ ] **Step 4: Verify focused tests pass and commit**

Run: `npm --prefix frontend test -- use-map-features.test.tsx`

```bash
git add frontend/src/features/network-map/use-map-features.ts frontend/src/features/network-map/use-map-features.test.tsx frontend/src/features/results/ResultWorkspace.tsx
git commit -m "feat: cap depth scene feature loading"
```

### Task 2: Abort and clear obsolete depth data

**Files:**
- Modify: `frontend/src/features/network-map/use-map-features.ts`
- Modify: `frontend/src/features/results/ResultWorkspace.tsx`
- Test: `frontend/src/features/results/ResultWorkspace.test.tsx`

- [ ] **Step 1: Write failing delayed-request switch tests**

```tsx
await user.click(screen.getByRole('radio', { name: /вариант 2/i }))
expect(screen.getByText(/загружаем профиль и 3d/i)).toBeVisible()
await resolveOldRequest()
expect(screen.queryByText(/старый потребитель/i)).not.toBeInTheDocument()
```

- [ ] **Step 2: Verify the focused test fails**

Run: `npm --prefix frontend test -- ResultWorkspace.test.tsx`

- [ ] **Step 3: Disable previous-data retention for depth queries and reset local route selection**

Keep ordinary map behaviour unchanged. Use effect/keyed state to reset `endpointKey` and selected scene section when job ID or selected variant key changes.

- [ ] **Step 4: Verify focused tests pass and commit**

Run: `npm --prefix frontend test -- ResultWorkspace.test.tsx`

```bash
git add frontend/src/features/network-map/use-map-features.ts frontend/src/features/results/ResultWorkspace.tsx frontend/src/features/results/ResultWorkspace.test.tsx
git commit -m "fix: clear stale depth views on selection change"
```

### Task 3: Communication descriptors from existing input map data

**Files:**
- Modify: `frontend/src/features/depth-profile/depth-path.ts`
- Test: `frontend/src/features/depth-profile/depth-path.test.ts`
- Modify: `frontend/src/features/network-3d/three-adapter.ts`
- Test: `frontend/src/features/network-3d/three-adapter.test.ts`
- Modify: `frontend/src/features/results/ResultWorkspace.tsx`

- [ ] **Step 1: Write failing tests for gas, cable, and unknown restrictions**

```ts
expect(toSceneCommunications([gasFeature], origin)).toMatchObject([{ kind: 'gas_pipeline', topDepthM: 2.8 }])
expect(toSceneCommunications([unknownFeature], origin)).toEqual([])
expect(sceneObjectDescriptors(segments, communications)).toContainEqual(expect.objectContaining({ kind: 'gas_pipeline' }))
```

- [ ] **Step 2: Verify focused tests fail**

Run: `npm --prefix frontend test -- depth-path.test.ts three-adapter.test.ts`

- [ ] **Step 3: Project supported input communications and render their documented envelopes**

Use catalog values for gas, cable and existing heat-network only; project geometry relative to the route origin. Render road/tram as surface zones. Query input objects under the same result bounds and pass descriptors only after their successful load.

- [ ] **Step 4: Verify focused tests pass and commit**

Run: `npm --prefix frontend test -- depth-path.test.ts three-adapter.test.ts ResultWorkspace.test.tsx`

```bash
git add frontend/src/features/depth-profile/depth-path.ts frontend/src/features/depth-profile/depth-path.test.ts frontend/src/features/network-3d/three-adapter.ts frontend/src/features/network-3d/three-adapter.test.ts frontend/src/features/results/ResultWorkspace.tsx
git commit -m "feat: render depth crossing communications"
```

### Task 4: Selected pipe section and reliable camera reset

**Files:**
- Modify: `frontend/src/features/network-3d/DepthScene.tsx`
- Test: `frontend/src/features/network-3d/DepthScene.test.tsx`
- Modify: `frontend/src/features/network-3d/three-adapter.ts`
- Test: `frontend/src/features/network-3d/three-adapter.test.ts`

- [ ] **Step 1: Write failing selection and reset tests**

```tsx
adapter.emitSelect(1)
expect(screen.getByText(/участок 2/i)).toBeVisible()
expect(screen.getByText(/ду 80/i)).toBeVisible()
await user.click(screen.getByRole('button', { name: /сбросить ракурс/i }))
expect(adapter.resetView).toHaveBeenCalledTimes(1)
```

- [ ] **Step 2: Verify focused tests fail**

Run: `npm --prefix frontend test -- DepthScene.test.tsx three-adapter.test.ts`

- [ ] **Step 3: Add adapter selection callback and selected material state**

Raycast only new-pipe envelopes, emit a segment index, and use a distinct material for the active mesh. Recompute a deterministic frame on resize and path replacement; communications do not change selection.

- [ ] **Step 4: Verify focused tests pass and commit**

Run: `npm --prefix frontend test -- DepthScene.test.tsx three-adapter.test.ts`

```bash
git add frontend/src/features/network-3d/DepthScene.tsx frontend/src/features/network-3d/DepthScene.test.tsx frontend/src/features/network-3d/three-adapter.ts frontend/src/features/network-3d/three-adapter.test.ts
git commit -m "feat: select depth scene pipe sections"
```

### Task 5: Mobile, WebGL fallback, and second-PR acceptance

**Files:**
- Modify: `frontend/src/app/styles.css`
- Test: `frontend/src/features/network-3d/DepthScene.test.tsx`
- Modify: `docs/DEPTH_BROWSER_CHECK.md` only for factual rerun results.

- [ ] **Step 1: Write a failing no-WebGL fallback test**

```tsx
createDepthSceneAdapter.mockImplementation(() => { throw new Error('WebGL unavailable') })
expect(screen.getByRole('alert')).toHaveTextContent(/3d-сцена недоступна/i)
```

- [ ] **Step 2: Verify focused test fails, implement responsive/fallback styles, then verify pass**

Run: `npm --prefix frontend test -- DepthScene.test.tsx`

Ensure controls and metadata stack below 620 px without horizontal overflow; profile and export remain in the workspace.

- [ ] **Step 3: Run complete checks and strict browser acceptance**

Run: `npm --prefix frontend test && npm --prefix frontend run lint && npm --prefix frontend run build && BROWSER_FAIL_ON_FINDINGS=1 node scripts/check_depth_browser.mjs`

Expected: all automated frontend checks pass; record actual browser limitations, hardware/GPU coverage, and bundle-size warning.

- [ ] **Step 4: Commit report and open the advanced D4 PR**

```bash
git add frontend/src/app/styles.css frontend/src/features/network-3d/DepthScene.tsx frontend/src/features/network-3d/DepthScene.test.tsx docs/DEPTH_BROWSER_CHECK.md
git commit -m "fix: harden depth scene fallback and mobile layout"
```
