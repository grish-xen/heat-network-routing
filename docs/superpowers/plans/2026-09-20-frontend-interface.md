# Frontend Interface Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a testable React interface for GeoJSON upload, asynchronous job status, variant comparison, paged map rendering, and result download with honest fixture and HTTP modes.

**Architecture:** A single-page React application depends on a typed `HeatNetworkApi` boundary with fixture and HTTP implementations. TanStack Query owns server state and polling; feature components own only local UI state; MapLibre is isolated behind `NetworkMap`, while pure map-style and pagination functions remain independently testable.

**Tech Stack:** Node.js 22, npm, React, TypeScript, Vite, TanStack Query, MapLibre GL JS, lossless-json, Vitest, React Testing Library, MSW, ESLint.

**Spec:** [`docs/superpowers/specs/2026-09-20-frontend-interface-design.md`](../specs/2026-09-20-frontend-interface-design.md)

## Global Constraints

- Use Node.js 22.12 or newer; the current workspace provides Node.js 22.19.0 and npm 10.9.3.
- Use only relative `/api` URLs in HTTP mode and a Vite dev proxy to `http://localhost:8080`.
- Keep `fixture` and `http` modes explicit; never fall back from HTTP failures to fixture data.
- Show a persistent «Демонстрационные данные» badge whenever fixture mode is active.
- Preserve JSON ID type and decimal spelling; numeric `1` and string `"1"` are different IDs.
- Convert coordinates, costs, lengths, flows, ranks, and scores to JavaScript numbers only after a safe finite-number check.
- Poll `QUEUED` and `RUNNING` jobs every 1000 ms and stop only at `SUCCEEDED` or `FAILED`.
- Treat only `SUCCEEDED / DONE` as a completed calculation; `FAILED / PROCESSING_UNAVAILABLE` never opens fixture results in HTTP mode.
- Request map data by EPSG:4326 bbox, `limit <= 5000`, and repeat identical query parameters while following `nextCursor`.
- Do not calculate diameter, cost, score, or engineering validity in the browser.
- Do not read the uploaded GeoJSON into memory before upload and do not fetch the result into JavaScript before download.
- Keep all user-facing copy in Russian and include visible keyboard focus and non-color layer distinctions.
- Do not change `contracts/openapi.json`, backend Java code, Docker Compose, or shared model types in this plan.
- End every task with a focused commit; never combine unrelated backend or contract changes.

## Review Focus

- A numeric ID larger than `9007199254740991` must render and compare without rounding; Task 2 adds the regression test.
- A polling request that temporarily fails must show a retry state without changing the server job to `FAILED`; Task 4 adds the regression test.
- A stale map request must be aborted after the viewport or selected variant changes; Task 5 adds the regression test.
- A map page with `nextCursor` must reuse the original bbox/layer/variant/limit and stop only at `null`; Task 5 adds the regression test.
- A browser without WebGL2 must keep variant summaries usable and show a map-specific explanation; Task 6 adds the regression test.

---

### Task 1: React shell and test foundation

**Files:**
- Create: `frontend/package.json`
- Create: `frontend/package-lock.json`
- Create: `frontend/index.html`
- Create: `frontend/tsconfig.json`
- Create: `frontend/tsconfig.app.json`
- Create: `frontend/tsconfig.node.json`
- Create: `frontend/vite.config.ts`
- Create: `frontend/eslint.config.js`
- Create: `frontend/src/main.tsx`
- Create: `frontend/src/app/App.tsx`
- Create: `frontend/src/app/App.test.tsx`
- Create: `frontend/src/app/providers.tsx`
- Create: `frontend/src/app/styles.css`
- Create: `frontend/src/test/setup.ts`
- Create: `frontend/src/vite-env.d.ts`
- Modify: `.gitignore`

**Interfaces:**
- Consumes: `VITE_API_MODE` with allowed values `fixture | http`.
- Produces: `App`, `AppProviders`, global design tokens, `npm run lint`, `npm test`, and `npm run build`.

- [ ] **Step 1: Define the npm project and install locked dependencies**

Create `package.json` with scripts before installing:

```json
{
  "name": "heat-network-routing-frontend",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "engines": { "node": ">=22.12" },
  "scripts": {
    "dev": "vite",
    "build": "tsc -b && vite build",
    "lint": "eslint .",
    "test": "vitest run",
    "test:watch": "vitest"
  }
}
```

Run:

```bash
npm --prefix frontend install react react-dom @tanstack/react-query maplibre-gl lossless-json
npm --prefix frontend install -D typescript vite @vitejs/plugin-react vitest jsdom @testing-library/react @testing-library/dom @testing-library/jest-dom @testing-library/user-event msw eslint @eslint/js typescript-eslint eslint-plugin-react-hooks eslint-plugin-react-refresh @types/react @types/react-dom
```

Expected: `frontend/package-lock.json` is created and `npm audit` exits without a high/critical production vulnerability. If npm reports one, inspect it before proceeding rather than using `--force`.

- [ ] **Step 2: Write the failing shell test**

```tsx
import { render, screen } from '@testing-library/react'
import { App } from './App'

it('identifies the service and fixture data honestly', () => {
  render(<App apiMode="fixture" />)
  expect(screen.getByRole('heading', { name: /моделирование тепловых сетей/i })).toBeVisible()
  expect(screen.getByText('Демонстрационные данные')).toBeVisible()
})
```

- [ ] **Step 3: Run the shell test and observe the expected failure**

Run: `npm --prefix frontend test -- --run src/app/App.test.tsx`

Expected: FAIL because `App.tsx` and the test environment do not exist yet.

- [ ] **Step 4: Create the Vite, TypeScript, ESLint, and Vitest configuration**

Configure `vite.config.ts` with the React plugin, `environment: 'jsdom'`, setup file `src/test/setup.ts`, fixture JSON access from the repository root, and:

```ts
server: {
  proxy: {
    '/api': 'http://localhost:8080',
  },
}
```

Configure `tsconfig.app.json` with `strict: true`, `noUncheckedIndexedAccess: true`, `resolveJsonModule: true`, and `jsx: 'react-jsx'`. Add `frontend/coverage/` to `.gitignore`.

- [ ] **Step 5: Implement the minimal application shell and providers**

`AppProviders` creates one `QueryClient` with `retry: false` in tests and wraps children in `QueryClientProvider`. `App` renders a semantic header, main content, API status placeholder, and the fixture badge only for `apiMode === 'fixture'`.

```tsx
export type ApiMode = 'fixture' | 'http'

export function App({ apiMode }: { apiMode: ApiMode }) {
  return (
    <div className="app-shell">
      <header className="app-header">
        <div>
          <p className="eyebrow">ЛЦТ 2026</p>
          <h1>Моделирование тепловых сетей</h1>
        </div>
        {apiMode === 'fixture' && <span className="mode-badge">Демонстрационные данные</span>}
      </header>
      <main className="workspace" aria-label="Рабочая область" />
    </div>
  )
}
```

- [ ] **Step 6: Add the visual tokens and responsive shell**

Define CSS custom properties for neutral surfaces, text, focus, existing network, new network, restrictions, success, warning, and danger. Use a `minmax(22.5rem, 26rem) 1fr` desktop grid and one-column layout below `900px`. Add `:focus-visible` and `prefers-reduced-motion` rules.

- [ ] **Step 7: Run foundation checks**

Run:

```bash
npm --prefix frontend test
npm --prefix frontend run lint
npm --prefix frontend run build
```

Expected: all commands exit 0 and `frontend/dist/index.html` exists.

- [ ] **Step 8: Commit the foundation**

```bash
git add .gitignore frontend
git commit -m "Add React frontend foundation"
```

### Task 2: Exact HTTP model and lossless API boundary

**Files:**
- Create: `frontend/src/shared/model/api.ts`
- Create: `frontend/src/shared/model/object-id.ts`
- Create: `frontend/src/shared/model/object-id.test.ts`
- Create: `frontend/src/shared/api/contracts.ts`
- Create: `frontend/src/shared/api/parse-response.ts`
- Create: `frontend/src/shared/api/parse-response.test.ts`
- Create: `frontend/src/shared/api/http-api.ts`
- Create: `frontend/src/shared/api/http-api.test.ts`
- Create: `frontend/src/shared/api/api-error.ts`

**Interfaces:**
- Consumes: browser `fetch`, OpenAPI response shapes, `lossless-json`.
- Produces: `ObjectId`, `objectIdKey`, `formatObjectId`, `HeatNetworkApi`, `HttpHeatNetworkApi`, `ApiClientError`.

- [ ] **Step 1: Write failing exact-ID tests**

```ts
it('distinguishes text and numeric IDs without rounding', () => {
  const parsed = parseJobText('{"jobId":"j","status":"FAILED","stage":"FAILED","mode":"2d","diagnostics":[{"code":"INVALID_ID","message":"bad","details":[{"inputObjectId":9007199254740993}]}]}')
  const numeric = parsed.diagnostics[0]?.details?.[0]?.inputObjectId
  expect(formatObjectId(numeric!)).toBe('9007199254740993')
  expect(objectIdKey(numeric!)).toBe('number:9007199254740993')
  expect(objectIdKey({ kind: 'string', value: '9007199254740993' })).not.toBe(objectIdKey(numeric!))
})
```

- [ ] **Step 2: Run the ID test and verify failure**

Run: `npm --prefix frontend test -- --run src/shared/model/object-id.test.ts`

Expected: FAIL because the parser and ID helpers are undefined.

- [ ] **Step 3: Define API types and exact ObjectId representation**

Use discriminated values:

```ts
export type ObjectId =
  | { readonly kind: 'string'; readonly value: string }
  | { readonly kind: 'number'; readonly value: string }

export const objectIdKey = (id: ObjectId) => `${id.kind}:${id.value}`
export const formatObjectId = (id: ObjectId) => id.value
```

Define `Health` with `status`, `contractVersion`, and `implementation`, plus exact unions for `JobStatus`, `JobStage`, `Job`, `ApiError`, `VariantSummary`, `MapFeature`, `MapPage`, and `MapQuery`. Keep `variantId` as `ObjectId`; convert it to its exact decimal/text query value only in the HTTP adapter.

- [ ] **Step 4: Implement schema-aware lossless response parsing**

Parse response text with `lossless-json`. Add explicit `expectObject`, `expectString`, `expectArray`, `expectFiniteNumber`, `expectInteger`, and `expectObjectId` helpers. `expectObjectId` accepts a string or `LosslessNumber`, while numeric measurement helpers reject unsafe/non-finite conversion. Export `parseHealthText`, `parseJobText`, `parseVariantsText`, `parseMapPageText`, and `parseApiErrorText`; every HTTP JSON operation must call its matching parser.

```ts
export function expectObjectId(value: unknown, path: string): ObjectId {
  if (typeof value === 'string') return { kind: 'string', value }
  if (isLosslessNumber(value)) return { kind: 'number', value: value.toString() }
  throw new ResponseContractError(`${path}: ожидается строковый или числовой ID`)
}
```

- [ ] **Step 5: Run the exact-ID and parser tests**

Run: `npm --prefix frontend test -- --run src/shared/model/object-id.test.ts src/shared/api/parse-response.test.ts`

Expected: PASS for large numeric IDs, string/number distinction, missing required fields, invalid enums, and unsafe measurement rejection.

- [ ] **Step 6: Write failing HTTP adapter tests**

Test these observable requests with MSW:

```ts
expect(request.headers.get('content-type')).toMatch(/^multipart\/form-data; boundary=/)
expect(url.searchParams.get('bbox')).toBe('37.4,55.6,37.5,55.7')
expect(url.searchParams.get('variantId')).toBe('9007199254740993')
expect(url.searchParams.get('limit')).toBe('1000')
```

Also assert that the adapter throws `ApiClientError` containing HTTP status, server code, message, and optional `Retry-After`, and never substitutes fixture data.

- [ ] **Step 7: Implement `HeatNetworkApi` and `HttpHeatNetworkApi`**

```ts
export interface HeatNetworkApi {
  health(signal?: AbortSignal): Promise<Health>
  createJob(file: File, mode: '2d', signal?: AbortSignal): Promise<Job>
  getJob(jobId: string, signal?: AbortSignal): Promise<Job>
  listVariants(jobId: string, signal?: AbortSignal): Promise<readonly VariantSummary[]>
  getMapPage(jobId: string, query: MapQuery, signal?: AbortSignal): Promise<MapPage>
  getResultUrl(jobId: string): string
}
```

Use `FormData` and do not set `Content-Type` manually. Read JSON responses with `response.text()` and the schema-aware parser. Return `/api/jobs/${encodeURIComponent(jobId)}/result` without prefetching the download.

- [ ] **Step 8: Run API checks and commit**

Run:

```bash
npm --prefix frontend test -- --run src/shared
npm --prefix frontend run lint
npm --prefix frontend run build
```

Expected: all commands exit 0.

```bash
git add frontend/src/shared frontend/package.json frontend/package-lock.json
git commit -m "Add typed frontend API boundary"
```

### Task 3: Fixture API with realistic paging and job progression

**Files:**
- Create: `frontend/src/shared/api/fixture-api.ts`
- Create: `frontend/src/shared/api/fixture-api.test.ts`
- Create: `frontend/src/shared/api/create-api.ts`
- Create: `frontend/src/shared/api/create-api.test.ts`
- Modify: `frontend/vite.config.ts`

**Interfaces:**
- Consumes: `HeatNetworkApi`, repository fixtures under `test-data/api` and `test-data/synthetic/two-consumers`.
- Produces: `FixtureHeatNetworkApi`, `createHeatNetworkApi(mode)`, deterministic fake jobs and cursor pages.

- [ ] **Step 1: Write failing fixture progression and paging tests**

Use injected time instead of real sleeps:

```ts
const api = new FixtureHeatNetworkApi({ now: () => now })
const queued = await api.createJob(new File(['{}'], 'input.geojson'), '2d')
expect(queued.status).toBe('QUEUED')
now += 1_200
expect((await api.getJob(queued.jobId)).stage).toBe('VALIDATING')
now += 2_000
expect((await api.getJob(queued.jobId)).status).toBe('SUCCEEDED')
```

Request a page with `limit: 1`, follow `nextCursor`, and assert that the combined IDs are unique and the final cursor is `null`.

- [ ] **Step 2: Run fixture tests and verify failure**

Run: `npm --prefix frontend test -- --run src/shared/api/fixture-api.test.ts`

Expected: FAIL because `FixtureHeatNetworkApi` does not exist.

- [ ] **Step 3: Import shared fixtures without duplicating them**

Configure Vite `server.fs.allow` for the repository root and import JSON through explicit module paths. Normalize fixtures through the same runtime parsers used by HTTP mode. Filter `variant_summary` out of result map features because it has null geometry.

- [ ] **Step 4: Implement deterministic fixture jobs and cursors**

Generate fixture job IDs as `fixture-1`, `fixture-2`, and store only creation timestamps. Stage timing:

```ts
const FIXTURE_STAGES = [
  { untilMs: 1_000, status: 'QUEUED', stage: 'QUEUED' },
  { untilMs: 2_000, status: 'RUNNING', stage: 'VALIDATING' },
  { untilMs: 3_000, status: 'RUNNING', stage: 'ROUTING' },
  { untilMs: 4_000, status: 'RUNNING', stage: 'CALCULATING' },
  { untilMs: 5_000, status: 'RUNNING', stage: 'EXPORTING' },
  { untilMs: Infinity, status: 'SUCCEEDED', stage: 'DONE' },
] as const
```

Encode fixture cursors as decimal offsets. Reject a cursor that is negative, non-integer, or beyond the filtered result. Apply bbox intersection before slicing to the requested limit.

- [ ] **Step 5: Implement explicit API selection**

```ts
export function createHeatNetworkApi(mode: ApiMode): HeatNetworkApi {
  if (mode === 'fixture') return new FixtureHeatNetworkApi()
  if (mode === 'http') return new HttpHeatNetworkApi()
  return assertNever(mode)
}
```

Read `import.meta.env.VITE_API_MODE` in `main.tsx`; accept only `fixture` or `http`. Development defaults to `fixture`. Production build throws a clear startup configuration error when the variable is missing.

- [ ] **Step 6: Run fixture checks and commit**

Run:

```bash
npm --prefix frontend test -- --run src/shared/api
npm --prefix frontend run lint
npm --prefix frontend run build -- --mode development
```

Expected: tests and build pass without copied fixture files.

```bash
git add frontend
git commit -m "Add replaceable fixture API"
```

### Task 4: Upload, polling, and diagnostic workflow

**Files:**
- Create: `frontend/src/features/job-upload/JobUpload.tsx`
- Create: `frontend/src/features/job-upload/JobUpload.test.tsx`
- Create: `frontend/src/features/job-status/JobStatus.tsx`
- Create: `frontend/src/features/job-status/JobStatus.test.tsx`
- Create: `frontend/src/features/job-status/use-job.ts`
- Create: `frontend/src/features/job-status/use-job.test.tsx`
- Create: `frontend/src/features/service-status/ServiceStatus.tsx`
- Create: `frontend/src/features/service-status/ServiceStatus.test.tsx`
- Create: `frontend/src/features/service-status/use-health.ts`
- Create: `frontend/src/shared/ui/Alert.tsx`
- Create: `frontend/src/shared/ui/Button.tsx`
- Create: `frontend/src/shared/ui/Spinner.tsx`
- Modify: `frontend/src/app/App.tsx`
- Modify: `frontend/src/app/App.test.tsx`
- Modify: `frontend/src/app/styles.css`

**Interfaces:**
- Consumes: `HeatNetworkApi.createJob`, `HeatNetworkApi.getJob`, TanStack Query.
- Produces: `JobUpload`, `JobStatus`, `ServiceStatus`, `useCreateJob`, `useJob`, and `App` state transition from upload to terminal job.

- [ ] **Step 1: Write failing upload behavior tests**

Cover one selected file, drag-and-drop, retained file after a 413 error, and submission without manual content-type handling. The primary assertion:

```tsx
await user.upload(screen.getByLabelText(/geojson/i), file)
await user.click(screen.getByRole('button', { name: /запустить расчёт/i }))
expect(api.createJob).toHaveBeenCalledWith(file, '2d', expect.any(AbortSignal))
```

- [ ] **Step 2: Run the upload test and verify failure**

Run: `npm --prefix frontend test -- --run src/features/job-upload/JobUpload.test.tsx`

Expected: FAIL because the component does not exist.

- [ ] **Step 3: Implement accessible file selection and upload mutation**

Use a real `<input type="file" accept=".geojson,.json,application/geo+json,application/json">`. Do not parse file contents. Keep the file selected when `ApiClientError` is returned. Disable submit only while the current mutation is pending or no file is selected.

- [ ] **Step 4: Write failing polling tests including transient failure**

Use fake timers. Assert requests occur after 1000 ms for `QUEUED`/`RUNNING`, stop after `SUCCEEDED`/`FAILED`, and retain the last server job when one polling call rejects:

```tsx
expect(screen.getByText(/связь временно потеряна/i)).toBeVisible()
expect(screen.getByText(/проверка данных/i)).toBeVisible()
expect(screen.queryByText(/задача завершилась с ошибкой/i)).not.toBeInTheDocument()
```

- [ ] **Step 5: Implement `useJob` and status presentation**

Use a query key `['job', jobId]` and:

```ts
refetchInterval: query => {
  const status = query.state.data?.status
  return status === 'QUEUED' || status === 'RUNNING' ? 1_000 : false
}
```

Render all defined stages with the current one highlighted. Put stage changes in an `aria-live="polite"` region. Render diagnostic `code`, `message`, and formatted `inputObjectId` when present. Give `PROCESSING_UNAVAILABLE` its own explanatory heading.

- [ ] **Step 6: Connect the workflow in `App`**

Keep `activeJobId` in `App`. After `createJob`, render status for that exact job. Provide «Начать заново» only for terminal states; it clears query data for the job and returns to upload. Do not open result components unless `status === 'SUCCEEDED' && stage === 'DONE'`.

Implement `useHealth` with query key `['health']`, a 30-second stale time, and no polling. `ServiceStatus` renders «API доступен» only when `status === 'UP'`; network or contract errors render «API недоступен» without blocking fixture mode. Add a component test for both states and place it in the application header.

- [ ] **Step 7: Run workflow checks and commit**

Run:

```bash
npm --prefix frontend test -- --run src/features/job-upload src/features/job-status src/app
npm --prefix frontend run lint
npm --prefix frontend run build -- --mode development
```

Expected: all commands exit 0.

```bash
git add frontend/src
git commit -m "Add upload and job status workflow"
```

### Task 5: Bbox pagination and isolated MapLibre rendering

**Files:**
- Create: `frontend/src/features/network-map/map-query.ts`
- Create: `frontend/src/features/network-map/map-query.test.ts`
- Create: `frontend/src/features/network-map/map-style.ts`
- Create: `frontend/src/features/network-map/map-style.test.ts`
- Create: `frontend/src/features/network-map/use-map-features.ts`
- Create: `frontend/src/features/network-map/use-map-features.test.tsx`
- Create: `frontend/src/features/network-map/NetworkMap.tsx`
- Create: `frontend/src/features/network-map/NetworkMap.test.tsx`
- Create: `frontend/src/features/network-map/MapLegend.tsx`
- Create: `frontend/src/features/network-map/maplibre-adapter.ts`
- Modify: `frontend/src/app/styles.css`

**Interfaces:**
- Consumes: `HeatNetworkApi.getMapPage`, `MapPage`, MapLibre public API.
- Produces: `ViewportBbox`, `mapQueryKey`, `loadMapFeatures`, `NetworkMap`, stable GeoJSON source/layer definitions.

- [ ] **Step 1: Write failing bbox and cursor-chain tests**

```ts
const result = await loadMapFeatures(api, 'job-1', query, signal)
expect(api.getMapPage).toHaveBeenNthCalledWith(1, 'job-1', { ...query, cursor: undefined }, signal)
expect(api.getMapPage).toHaveBeenNthCalledWith(2, 'job-1', { ...query, cursor: 'page-2' }, signal)
expect(result.features).toHaveLength(2)
```

Add an abort test where `controller.abort()` rejects with `AbortError`, and verify no later cursor is requested. Add `mapQueryKey` assertions showing different bbox and variant IDs produce different keys.

- [ ] **Step 2: Run map-query tests and verify failure**

Run: `npm --prefix frontend test -- --run src/features/network-map/map-query.test.ts src/features/network-map/use-map-features.test.tsx`

Expected: FAIL because the query helpers do not exist.

- [ ] **Step 3: Implement bbox serialization and complete cursor loading**

Validate longitude `[-180, 180]`, latitude `[-90, 90]`, increasing min/max, and limit `1..5000`. Serialize without locale formatting:

```ts
export const serializeBbox = (bbox: ViewportBbox) =>
  [bbox.minLon, bbox.minLat, bbox.maxLon, bbox.maxLat].join(',')
```

Implement `loadMapFeatures` with a `Set` of seen cursors to reject server cursor cycles and preserve the original query while only replacing `cursor`.

- [ ] **Step 4: Write failing layer-style tests**

Assert each object type maps to a source/layer with a unique visual treatment, calculated and existing heat networks use different widths/dashes, and restriction polygons use fill plus outline. Assert IDs use `objectIdKey`, not lossy numeric conversion.

- [ ] **Step 5: Implement pure layer definitions and legend data**

Expose one `MAP_LEGEND_ITEMS` array used by both MapLibre layer creation and the visible legend. Keep colors in CSS variables mirrored by explicit MapLibre color strings. Use circle/symbol distinction for source, chamber, connection point, and technical node. `MapLegend` renders checkbox controls for `network`, `nodes`, `connections`, and `restrictions`; a test asserts toggling a group calls `setLayerGroupVisibility(group, false)` without deleting its source data.

- [ ] **Step 6: Write failing `NetworkMap` lifecycle tests**

Mock only `maplibre-adapter.ts`, not MapLibre internals. Assert the component creates one adapter, calls `setFeatureCollection` when query data changes, calls `destroy` on unmount, and reports viewport changes through a debounced callback.

- [ ] **Step 7: Implement the MapLibre adapter and component**

The adapter owns `new Map`, navigation/scale controls, source creation, layers, popup, `moveend`, visibility changes, and cleanup. `NetworkMap` owns React effects and data queries. Use named ESM imports from `maplibre-gl`, import its CSS once, configure the v6 worker URL once with `setWorkerUrl` and Vite's `?url` asset import, and catch `GPUInitializationError` as a map-specific unsupported state. The production build in Step 8 must prove that the worker asset resolves correctly.

- [ ] **Step 8: Run map checks and commit**

Run:

```bash
npm --prefix frontend test -- --run src/features/network-map
npm --prefix frontend run lint
npm --prefix frontend run build -- --mode development
```

Expected: all commands exit 0; no real WebGL context is needed in jsdom tests.

```bash
git add frontend/src/features/network-map frontend/src/app/styles.css
git commit -m "Add paged network map"
```

### Task 6: Variant comparison, result workspace, and download

**Files:**
- Create: `frontend/src/features/variants/VariantList.tsx`
- Create: `frontend/src/features/variants/VariantList.test.tsx`
- Create: `frontend/src/features/variants/VariantComparison.tsx`
- Create: `frontend/src/features/variants/VariantComparison.test.tsx`
- Create: `frontend/src/features/variants/use-variants.ts`
- Create: `frontend/src/features/variants/format-metrics.ts`
- Create: `frontend/src/features/variants/format-metrics.test.ts`
- Create: `frontend/src/features/results/ResultWorkspace.tsx`
- Create: `frontend/src/features/results/ResultWorkspace.test.tsx`
- Modify: `frontend/src/app/App.tsx`
- Modify: `frontend/src/app/App.test.tsx`
- Modify: `frontend/src/app/styles.css`

**Interfaces:**
- Consumes: `HeatNetworkApi.listVariants`, `getResultUrl`, `NetworkMap`.
- Produces: ranked variant selector, comparison metrics, result download link, complete success workspace.

- [ ] **Step 1: Write failing formatter and ranking tests**

Use explicit Russian output:

```ts
expect(formatRubles(26_391_400)).toBe('26 391 400 ₽')
expect(formatMetres(200)).toBe('200 м')
expect(formatScore(1.3389592)).toBe('1,33895920')
```

Assert variants are displayed by server `rank` without recomputing score and the lowest rank is initially selected.

- [ ] **Step 2: Run variant tests and verify failure**

Run: `npm --prefix frontend test -- --run src/features/variants`

Expected: FAIL because formatters and components do not exist.

- [ ] **Step 3: Implement metric formatters and accessible variant selection**

Use `Intl.NumberFormat('ru-RU')` with non-breaking spaces normalized to regular spaces for stable tests. Implement variant cards as a labelled radiogroup. Display rank, calculated cost, new network length, score, new chamber cost, tie-in count/cost, penalty, and count of unconnected points.

- [ ] **Step 4: Write failing result-workspace tests including missing WebGL**

Mock `NetworkMap` in the normal selection test. In the unsupported-map test, have it report `GPU_UNAVAILABLE` and assert:

```tsx
expect(screen.getByText(/карта недоступна без webgl2/i)).toBeVisible()
expect(screen.getByRole('radio', { name: /вариант 1/i })).toBeEnabled()
expect(screen.getByRole('link', { name: /скачать geojson/i })).toHaveAttribute('href', '/api/jobs/job-1/result')
```

- [ ] **Step 5: Implement result workspace and download**

Fetch variants only after `SUCCEEDED / DONE`. Store selected variant by `objectIdKey(variant.variantId)`. Pass the exact selected `ObjectId` to `NetworkMap`. Use a normal `<a href>` with `download` so the browser streams the result directly.

- [ ] **Step 6: Integrate success state into `App`**

Render `ResultWorkspace` only for the active successful job. Preserve the top service header and fixture badge. In fixture mode, the download link may point to the checked-in expected GeoJSON asset but must remain labelled as a demonstration download.

- [ ] **Step 7: Run full frontend checks and commit**

Run:

```bash
npm --prefix frontend test
npm --prefix frontend run lint
npm --prefix frontend run build -- --mode development
```

Expected: the complete fixture flow, WebGL fallback, variants, and download checks pass.

```bash
git add frontend/src
git commit -m "Add variant comparison workspace"
```

### Task 7: CI, operating documentation, and final acceptance

**Files:**
- Modify: `.github/workflows/verify.yml`
- Modify: `frontend/README.md`
- Create: `frontend/.env.example`
- Modify: `README.md`
- Modify: `docs/TEAM_WORKFLOW.md`
- Test: `scripts/check_repository.py`

**Interfaces:**
- Consumes: all frontend npm scripts and API modes.
- Produces: reproducible CI, local startup instructions, integration handoff for participant 1.

- [ ] **Step 1: Write the operational README content before changing CI**

Document exact commands:

```bash
npm --prefix frontend ci
npm --prefix frontend run dev
npm --prefix frontend test
npm --prefix frontend run lint
npm --prefix frontend run build -- --mode development
```

Document `VITE_API_MODE=fixture|http`, the default fixture badge, backend proxy, current `PROCESSING_UNAVAILABLE` behavior, Node `>=22.12`, and that planned endpoints must be implemented before HTTP mode can show results.

- [ ] **Step 2: Add environment example and root navigation**

Create:

```dotenv
VITE_API_MODE=fixture
```

Update the root README “Сейчас работают” and “Начало работы” sections to describe the frontend commands without claiming that routing is connected. Update the participant 4 row in `TEAM_WORKFLOW.md` to link to the frontend README, without changing ownership.

- [ ] **Step 3: Add a failing CI expectation check**

Run:

```bash
rg -n "npm --prefix frontend ci|npm --prefix frontend test|npm --prefix frontend run build" .github/workflows/verify.yml
```

Expected: FAIL/no matches before CI is updated.

- [ ] **Step 4: Add the frontend CI job**

Add an independent `frontend` job using Ubuntu 22.04 and `actions/setup-node@v4` with Node 22 and npm cache keyed by `frontend/package-lock.json`. Run `npm ci`, tests, lint, and the development-mode production build. Do not modify the existing backend job steps.

- [ ] **Step 5: Run repository and frontend acceptance checks**

Run:

```bash
python3 scripts/check_repository.py
npm --prefix frontend ci
npm --prefix frontend test
npm --prefix frontend run lint
npm --prefix frontend run build -- --mode development
git diff --check
git status --short
```

Expected: all verification commands exit 0; only Task 7 files are modified before commit. Maven verification remains a separate environment requirement because this workstation currently lacks JDK and Maven.

- [ ] **Step 6: Inspect the running interface in both modes**

Run fixture mode:

```bash
VITE_API_MODE=fixture npm --prefix frontend run dev -- --host 127.0.0.1
```

Verify keyboard upload, fixture badge, progress, variant selection, layer toggles, popup, and download. Then run HTTP mode with the backend when JDK/Maven or Docker is available and verify a valid upload ends visibly in `PROCESSING_UNAVAILABLE`, not in fixture results.

- [ ] **Step 7: Commit documentation and CI**

```bash
git add .github/workflows/verify.yml frontend/README.md frontend/.env.example README.md docs/TEAM_WORKFLOW.md
git commit -m "Document and verify frontend workflow"
```

- [ ] **Step 8: Perform branch verification and review handoff**

Run the verification-before-completion workflow, review `git log --oneline origin/main..HEAD`, and request a whole-branch code review focused on OpenAPI fidelity, exact IDs, fixture honesty, abort behavior, accessibility, and map memory usage. Fix findings in focused commits before proposing a pull request.
