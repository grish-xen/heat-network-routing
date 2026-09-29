# DEPTH D4 Critical Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make profile and 3D results truthful and recoverable when the real DEPTH map request is loading, fails, or returns no usable depth geometry.

**Architecture:** Keep paged map retrieval in `use-map-features`, but derive a presentation state in `ResultWorkspace` from its query results. Keep typed IDs lossless by adding a display-only formatter while selection continues to use `objectIdKey`; render keys use that stable typed key plus the split-line ordinal.

**Tech Stack:** React 19, TypeScript, TanStack Query, Vitest, Testing Library, Three.js.

**Spec:** `docs/superpowers/specs/2026-09-29-depth-d4-critical-design.md`

## Global Constraints

- Do not change HTTP endpoints or shared backend contracts.
- DEPTH must run against `VITE_API_MODE=http`; 2D continues to omit profile and 3D views.
- The export link remains usable in loading and error states.
- Preserve ObjectId type in selection and all React keys.
- Do not replace a failed, incomplete, or unknown result with invented geometry.

## Review Focus

- HTTP 413 must explain the response-size restriction and retain the export link; test in Task 2.
- A delayed old variant request must not become an apparently valid empty depth path; covered in the advanced plan’s Task 2.
- An intermediate depth lower than both endpoint depths must appear in the 3D range; test in Task 3.
- `number:1` and `string:1` must remain distinct in both selector text and value; test in Task 1.
- A multi-part LineString must not cause duplicate React keys after switching a branch; test in Task 1.

---

### Task 1: Typed endpoint labels and profile keys

**Files:**
- Modify: `frontend/src/shared/model/object-id.ts`
- Test: `frontend/src/shared/model/object-id.test.ts`
- Modify: `frontend/src/features/results/ResultWorkspace.tsx`
- Modify: `frontend/src/features/depth-profile/DepthProfile.tsx`
- Test: `frontend/src/features/results/ResultWorkspace.test.tsx`
- Test: `frontend/src/features/depth-profile/DepthProfile.test.tsx`

**Interfaces:**
- Produces: `formatTypedObjectId(id: ObjectId): string`, returning `строковый «value»` or `числовой value`.
- Consumes: `objectIdKey(id)` to set selector values and point keys.

- [ ] **Step 1: Write failing tests for type-disambiguated labels and multipart path rendering**

```ts
expect(formatTypedObjectId({ kind: 'string', value: '1' })).toBe('строковый «1»')
expect(formatTypedObjectId({ kind: 'number', value: '1' })).toBe('числовой 1')
expect(screen.getByRole('option', { name: 'строковый «1»' })).toBeVisible()
expect(consoleError).not.toHaveBeenCalledWith(expect.stringMatching(/same key/i))
```

- [ ] **Step 2: Run the focused tests to verify they fail**

Run: `npm --prefix frontend test -- object-id.test.ts ResultWorkspace.test.tsx DepthProfile.test.tsx`

Expected: FAIL because the typed formatter and unique profile keys do not exist.

- [ ] **Step 3: Implement typed label formatting and ordinal profile keys**

Use `formatTypedObjectId` only for visible option text. Keep option `value` and path selection on `objectIdKey`; use ``${objectIdKey(id)}:${index}`` for every rendered profile point group.

- [ ] **Step 4: Run the focused tests to verify they pass**

Run: `npm --prefix frontend test -- object-id.test.ts ResultWorkspace.test.tsx DepthProfile.test.tsx`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/shared/model/object-id.ts frontend/src/shared/model/object-id.test.ts frontend/src/features/results/ResultWorkspace.tsx frontend/src/features/results/ResultWorkspace.test.tsx frontend/src/features/depth-profile/DepthProfile.tsx frontend/src/features/depth-profile/DepthProfile.test.tsx
git commit -m "fix: distinguish depth endpoints and profile parts"
```

### Task 2: Honest depth data state and retry

**Files:**
- Modify: `frontend/src/features/results/ResultWorkspace.tsx`
- Test: `frontend/src/features/results/ResultWorkspace.test.tsx`
- Modify: `frontend/src/app/styles.css`

**Interfaces:**
- Consumes: TanStack Query result fields `isPending`, `isError`, `error`, and `refetch` returned by `useMapFeatureCollections`.
- Produces: visual states `loading`, `error`, `empty`, and `ready` for profile/3D only.

- [ ] **Step 1: Write failing tests for loading, generic map failure, HTTP 413, and retry**

```tsx
expect(screen.getByText(/загружаем профиль и 3d/i)).toBeVisible()
expect(screen.getByRole('alert')).toHaveTextContent(/не удалось загрузить/i)
expect(screen.getByRole('alert')).toHaveTextContent(/слишком большой объём/i)
await user.click(screen.getByRole('button', { name: /повторить загрузку/i }))
expect(getMapPage).toHaveBeenCalledTimes(2)
expect(screen.getByRole('link', { name: /скачать geojson/i })).toBeVisible()
```

- [ ] **Step 2: Run the focused test to verify it fails**

Run: `npm --prefix frontend test -- ResultWorkspace.test.tsx`

Expected: FAIL because an empty `sceneSegments` currently renders the no-depth message for pending and error requests.

- [ ] **Step 3: Derive and render the depth state in `ResultWorkspace`**

Treat query pending as loading, any query error as error, and only a successful query with no paths as empty. Detect 413 from the HTTP error status/body without depending on a fixture string. Retry calls every active depth query `refetch`; leave `result-actions` outside the state branch. Add compact responsive styles.

- [ ] **Step 4: Run the focused test to verify it passes**

Run: `npm --prefix frontend test -- ResultWorkspace.test.tsx`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/results/ResultWorkspace.tsx frontend/src/features/results/ResultWorkspace.test.tsx frontend/src/app/styles.css
git commit -m "fix: show depth load errors and retry"
```

### Task 3: Exact range in the 3D summary

**Files:**
- Modify: `frontend/src/features/network-3d/DepthScene.tsx`
- Test: `frontend/src/features/network-3d/DepthScene.test.tsx`

**Interfaces:**
- Consumes: every `DepthSceneSegment.depthStart` and `.depthEnd` in the selected path.
- Produces: a `Диапазон глубин` value with the path-wide minimum and maximum.

- [ ] **Step 1: Write a failing 3D range test with an interior 2.44 m segment**

```tsx
render(<DepthScene segments={[atThreeMetres, interiorAt2_44, atThreeMetres]} />)
expect(screen.getByText(/2,4–3,0 м/i)).toBeVisible()
expect(screen.getByText(/диапазон глубин/i)).toBeVisible()
```

- [ ] **Step 2: Run the focused test to verify it fails**

Run: `npm --prefix frontend test -- DepthScene.test.tsx`

Expected: FAIL because only first `depthStart` and last `depthEnd` are used.

- [ ] **Step 3: Compute and label the path-wide min/max**

Flatten every segment endpoint, compute `Math.min`/`Math.max`, preserve Russian one-decimal formatting, and replace the ambiguous `Глубина` label with `Диапазон глубин`.

- [ ] **Step 4: Run the focused test to verify it passes**

Run: `npm --prefix frontend test -- DepthScene.test.tsx`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/network-3d/DepthScene.tsx frontend/src/features/network-3d/DepthScene.test.tsx
git commit -m "fix: show full depth range in scene"
```

### Task 4: First-PR verification and handoff

**Files:**
- Modify: `docs/DEPTH_BROWSER_CHECK.md` only if the rerun’s factual results need a dated update.

- [ ] **Step 1: Run frontend verification**

Run: `npm --prefix frontend test && npm --prefix frontend run lint && npm --prefix frontend run build`

Expected: all tests, lint, and production build pass. Record the existing Vite chunk-size warning separately from a failure.

- [ ] **Step 2: Run the HTTP browser check for the first PR**

Run: `BROWSER_FAIL_ON_FINDINGS=1 node scripts/check_depth_browser.mjs`

Expected: `gas-below`, `branch`, `competition-corrected.geojson`, 2D, 500/413 and no-WebGL scenarios complete with none of the four critical findings.

- [ ] **Step 3: Commit factual browser-report update if changed**

```bash
git add docs/DEPTH_BROWSER_CHECK.md
git commit -m "docs: record critical depth frontend check"
```

### Task 5: Open the critical-fix PR

- [ ] **Step 1: Push branch and open a PR**

Use branch `fix/depth-d4-critical`, state the exact test/browser commands and the Vite warning, and link the four resolved findings. Do not claim second-PR functionality in this PR.

