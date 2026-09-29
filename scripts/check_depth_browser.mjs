// Browser acceptance against a running HTTP frontend/backend, with optional fault injection.
// Requires an external playwright-core installation; no frontend dependencies are changed.
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const baseUrl = process.env.BROWSER_BASE_URL || 'http://127.0.0.1:5174';
const output = path.resolve(process.env.BROWSER_OUTPUT || path.join(root, 'tmp/depth-browser-report'));
const modulePath = process.env.PLAYWRIGHT_MODULE;
if (!modulePath) throw new Error('Set PLAYWRIGHT_MODULE to playwright-core/index.mjs');
const { chromium } = await import(pathToFileURL(path.resolve(modulePath)).href);
await fs.mkdir(output, { recursive: true });
const browser = await chromium.launch({
  ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : { channel: 'chrome' }),
  headless: true, args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader'],
});
const report = { sourceCommit: process.env.SOURCE_COMMIT || null, browser: browser.version(), baseUrl,
  cases: [], findings: [], requests: [], pageErrors: [], consoleErrors: [] };
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, acceptDownloads: true });
const page = await context.newPage();
page.setDefaultTimeout(30000);
page.on('pageerror', error => report.pageErrors.push(error.message));
page.on('console', message => { if (message.type() === 'error') report.consoleErrors.push(message.text()); });
page.on('response', response => {
  if (response.url().includes('/api/')) report.requests.push({ url: response.url(), status: response.status(), method: response.request().method() });
});
const screenshot = name => page.screenshot({ path: path.join(output, `${name}.png`), fullPage: true });
const finding = (id, message, evidence) => report.findings.push({ id, message, evidence });
async function json(url) {
  const response = await context.request.get(baseUrl + url);
  assert.equal(response.status(), 200, url);
  return response.json();
}
async function upload(name, mode, file) {
  await page.goto(baseUrl, { waitUntil: 'networkidle' });
  assert.equal(await page.getByText('Демонстрационные данные', { exact: true }).count(), 0);
  await page.getByLabel(mode === 'depth' ? 'С учётом глубины' : '2D', { exact: true }).check();
  await page.getByLabel('GeoJSON файл', { exact: true }).setInputFiles(file);
  const created = page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/api/jobs'));
  await page.getByRole('button', { name: 'Запустить расчёт', exact: true }).click();
  const response = await created;
  assert.equal(response.status(), 202);
  const job = await response.json();
  assert.equal(job.mode, mode);
  return { name, mode, jobId: job.jobId };
}
async function result(item) {
  await page.getByRole('heading', { name: 'Сравните варианты трассировки' }).waitFor({ timeout: 180000 });
  item.job = await json(`/api/jobs/${item.jobId}`);
  assert.equal(item.job.status, 'SUCCEEDED');
  item.variants = await json(`/api/jobs/${item.jobId}/variants`);
  item.exported = await json(`/api/jobs/${item.jobId}/result`);
  const summaries = item.exported.features.filter(f => f.properties.object_type === 'variant_summary').map(f => f.properties);
  assert.deepEqual(summaries, item.variants);
  assert.equal(item.variants[0].unconnected_oks_ids.length, 0);
  await page.waitForLoadState('networkidle');
  await fs.writeFile(path.join(output, `${item.name}-result.geojson`), JSON.stringify(item.exported, null, 2));
  return item;
}
async function depthViews(item) {
  const canvas = page.locator('.depth-scene-canvas canvas');
  const loadError = page.locator('.depth-visualization-state [role="alert"]');
  await canvas.or(loadError).first().waitFor({ timeout: 30000 });
  if (await loadError.isVisible()) {
    item.depthLoadError = await loadError.innerText();
    item.mapBudgetDiagnostics = await page.evaluate(async ({ jobId, variantId }) => {
      const { HttpHeatNetworkApi } = await import('/src/shared/api/http-api.ts');
      const { loadMapFeatures, MAX_DEPTH_SCENE_FEATURES } = await import('/src/features/network-map/use-map-features.ts');
      const { expandBoundsForMapQuery } = await import('/src/features/network-map/map-query.ts');
      const { buildDepthPaths, toMetricSegments, toSceneCommunications, sceneOrigin } = await import('/src/features/depth-profile/depth-path.ts');
      const api = new HttpHeatNetworkApi();
      const id = { kind: 'string', value: variantId };
      const bbox = expandBoundsForMapQuery(await api.getMapBounds(jobId, id));
      const query = { layer: 'input', bbox, limit: 1000 };
      let boundedInputError = null;
      try { await loadMapFeatures(api, jobId, query, new AbortController().signal, MAX_DEPTH_SCENE_FEATURES); }
      catch (error) { boundedInputError = error.message; }
      // Diagnostic only, on our fixed competition fixture: read the accepted
      // HTTP pages without the UI's cap, never create GPU geometry from them.
      const input = await loadMapFeatures(api, jobId, query, new AbortController().signal);
      const result = await loadMapFeatures(api, jobId, { ...query, layer: 'result', variantId: id }, new AbortController().signal);
      const active = buildDepthPaths(result.features)[0];
      const parts = geometry => {
        const line = points => Math.max(1, points.length - 1);
        if (geometry.type === 'LineString') return line(geometry.coordinates);
        if (['MultiLineString', 'Polygon'].includes(geometry.type)) return geometry.coordinates.reduce((n, p) => n + line(p), 0);
        if (geometry.type === 'MultiPolygon') return geometry.coordinates.reduce((n, rings) => n + rings.reduce((m, p) => m + line(p), 0), 0);
        return 1;
      };
      return { limit: MAX_DEPTH_SCENE_FEATURES, boundedInputError, inputFeatures: input.features.length,
        inputGeometryParts: input.features.reduce((n, f) => n + parts(f.geometry), 0),
        routeParts: active ? toMetricSegments(active).length : 0,
        communicationParts: active ? toSceneCommunications(input.features, sceneOrigin(active)).length : 0 };
    }, { jobId: item.jobId, variantId: item.variants[0].variant_id });
    await page.getByRole('tab', { name: 'Профиль', exact: true }).click();
    item.profileBlockedBySameError = await loadError.isVisible();
    finding('depth-input-budget-blocks-result', item.depthLoadError, item.mapBudgetDiagnostics);
    throw new Error(`Depth visualization blocked for ${item.name}; see mapBudgetDiagnostics`);
  }
  await canvas.waitFor({ timeout: 30000 });
  await page.waitForTimeout(600);
  item.sceneText = await page.getByLabel('Свойства выбранного участка', { exact: true }).innerText();
  const features = item.exported.features.filter(f => f.properties.object_type === 'heat_network');
  item.depthRange = [Math.min(...features.flatMap(f => [f.properties.depth_start, f.properties.depth_end])),
    Math.max(...features.flatMap(f => [f.properties.depth_start, f.properties.depth_end]))];
  await screenshot(`${item.name}-3d`);
  const initial = await canvas.screenshot();
  const box = await canvas.boundingBox();
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down(); await page.mouse.move(box.x + box.width / 2 + 120, box.y + box.height / 2 + 40, { steps: 12 }); await page.mouse.up();
  await page.waitForTimeout(500);
  item.rotationChangesImage = !initial.equals(await canvas.screenshot());
  assert.ok(item.rotationChangesImage, '3D rotation must visibly change canvas');
  await page.getByRole('button', { name: 'Сбросить ракурс', exact: true }).click();
  await page.waitForTimeout(400);
  await page.getByRole('tab', { name: 'Профиль', exact: true }).click();
  await page.getByRole('img', { name: 'Продольный профиль выбранного пути', exact: true }).waitFor();
  const choices = page.locator('.depth-endpoint-selector select');
  item.pathOptions = await choices.count() ? await choices.locator('option').evaluateAll(nodes => nodes.map(n => ({ value: n.value, label: n.textContent }))) : [];
  // Parse the actual API response through the real HTTP adapter; inspect the same selected paths.
  item.paths = await page.evaluate(async ({ jobId, variantId }) => {
    const { HttpHeatNetworkApi } = await import('/src/shared/api/http-api.ts');
    const { buildDepthPaths, toMetricSegments } = await import('/src/features/depth-profile/depth-path.ts');
    const api = new HttpHeatNetworkApi();
    const features = []; let cursor;
    do {
      const p = await api.getMapPage(jobId, { layer: 'result', variantId: { kind: 'string', value: variantId }, bbox: [-180, -90, 180, 90], limit: 2, cursor });
      features.push(...p.features); cursor = p.nextCursor ?? undefined;
    } while (cursor);
    return buildDepthPaths(features).map(p => ({ endpoint: p.endNodeId, segments: toMetricSegments(p).map(s => ({
      id: s.feature.properties.id.value, a: s.depthStart, b: s.depthEnd, x: s.distanceStartM, y: s.distanceEndM,
      width: s.widthM, height: s.heightM, zStart: s.start.z, zEnd: s.end.z,
    })) }));
  }, { jobId: item.jobId, variantId: item.variants[0].variant_id });
  item.profileChecks = [];
  assert.equal(item.pathOptions.length, item.paths.length > 1 ? item.paths.length : 0);
  if (new Set(item.pathOptions.map(p => p.label)).size !== item.pathOptions.length) {
    finding('ambiguous-consumer-label', 'Разные типы ID показаны одинаковыми подписями в списке потребителей.', item.pathOptions);
  }
  for (let i = 0; i < item.paths.length; i++) {
    const endpoint = item.paths[i].endpoint;
    if (item.pathOptions.length) await choices.selectOption(`${endpoint.kind}:${endpoint.value}`);
    const segments = item.paths[i].segments;
    for (const id of new Set(segments.map(s => s.id))) {
      const parts = segments.filter(s => s.id === id);
      const exported = features.find(f => String(f.properties.id) === id);
      assert.ok(exported, `Export must contain ${id}`);
      assert.equal(parts[0].a, exported.properties.depth_start);
      assert.equal(parts.at(-1).b, exported.properties.depth_end);
      assert.ok(Math.abs(parts.at(-1).y - parts[0].x - exported.properties.length) < 0.001);
      assert.equal(parts.length, exported.geometry.coordinates.length - 1);
    }
    const maxDistance = segments.at(-1).y || 1;
    const maxDepth = Math.max(1, ...segments.flatMap(s => [s.a, s.b]));
    const expected = segments.map(s => [56 + s.x / maxDistance * 638, 30 + s.a / maxDepth * 242]);
    expected.push([694, 30 + segments.at(-1).b / maxDepth * 242]);
    await page.waitForFunction(expectedPoints => {
      const points = document.querySelector('.depth-profile-line')?.getAttribute('points')?.split(' ').map(p => p.split(',').map(Number));
      return points?.length === expectedPoints.length && points.every((p, j) => p.every((v, k) => Math.abs(v - expectedPoints[j][k]) < 1e-6));
    }, expected);
    const actual = (await page.locator('.depth-profile-line').getAttribute('points')).split(' ').map(p => p.split(',').map(Number));
    assert.equal(actual.length, expected.length);
    for (let j = 0; j < actual.length; j++) for (let k = 0; k < 2; k++) assert.ok(Math.abs(actual[j][k] - expected[j][k]) < 1e-6);
    for (const s of segments) { assert.equal(s.zStart, -4 * s.a); assert.equal(s.zEnd, -4 * s.b); }
    item.profileChecks.push({ endpoint: item.paths[i].endpoint, segments: segments.length, matchesApi: true });
  }
  await screenshot(`${item.name}-profile`);
  if (item.name === 'gas' && item.depthRange[0] !== item.depthRange[1] && item.sceneText.includes('3,0–3,0')) {
    finding('scene-depth-label', '3D показывает «Глубина 3,0–3,0 м», хотя внутри пути глубина меняется.', { actualRange: item.depthRange, text: item.sceneText });
  }
  await page.setViewportSize({ width: 390, height: 844 });
  await screenshot(`${item.name}-mobile-profile`);
  item.mobileOverflow = await page.evaluate(() => document.documentElement.scrollWidth > innerWidth);
  if (item.mobileOverflow) finding('mobile-overflow', 'Горизонтальное переполнение на ширине 390 px.', item.name);
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.getByRole('tab', { name: 'Карта', exact: true }).click();
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1500); // Source parsing and GPU rendering continue after HTTP completes.
  const mapImage = await page.locator('.network-map canvas').screenshot();
  item.mapRoutePixels = await page.evaluate(async dataUrl => {
    const img = new Image(); img.src = dataUrl; await img.decode();
    const canvas = document.createElement('canvas'); canvas.width = img.width; canvas.height = img.height;
    const ctx = canvas.getContext('2d'); ctx.drawImage(img, 0, 0);
    const pixels = ctx.getImageData(0, 0, canvas.width, canvas.height).data;
    let count = 0;
    for (let i = 0; i < pixels.length; i += 4) if (pixels[i] > 180 && pixels[i + 1] > 30 && pixels[i + 1] < 130 && pixels[i + 2] < 100) count++;
    return count;
  }, `data:image/png;base64,${mapImage.toString('base64')}`);
  if (item.mapRoutePixels < 20) finding('map-route-not-visible', 'После загрузки карты расчётная трасса визуально не обнаружена.', item.name);
  await screenshot(`${item.name}-map`);
  const event = page.waitForEvent('download');
  await page.getByRole('link', { name: 'Скачать GeoJSON', exact: true }).click();
  const download = await event;
  await download.saveAs(path.join(output, `${item.name}-download.geojson`));
  assert.deepEqual(JSON.parse(await fs.readFile(path.join(output, `${item.name}-download.geojson`), 'utf8')), item.exported);
  delete item.exported;
}
try {
  for (const [name, fixture] of [['gas', 'synthetic/depth/gas-below/input.geojson'], ['branch', 'synthetic/depth/branch/input.geojson'], ['competition', 'competition-corrected.geojson']]) {
    const item = await result(await upload(name, 'depth', path.join(root, 'test-data', fixture)));
    report.cases.push(item);
    await depthViews(item);
  }
  const twoD = await result(await upload('two-d', '2d', path.join(root, 'test-data/synthetic/two-consumers/input.geojson')));
  assert.equal(await page.getByRole('tab', { name: '3D-сцена', exact: true }).count(), 0);
  assert.ok(twoD.exported.features.filter(f => f.properties.object_type === 'heat_network').every(f => f.properties.depth_start === null && f.properties.depth_end === null));
  delete twoD.exported; report.cases.push(twoD);

  const invalid = await upload('invalid', 'depth', { name: 'invalid.geojson', mimeType: 'application/geo+json', buffer: Buffer.from('{ broken json') });
  await page.getByRole('heading', { name: 'Задача завершилась с ошибкой', exact: true }).waitFor({ timeout: 30000 });
  invalid.text = await page.locator('body').innerText();
  assert.ok(invalid.text.includes('INVALID_INPUT'));
  await screenshot('invalid'); report.cases.push(invalid);

  // Explicit fault injection; successful cases above do not intercept or replace API responses.
  for (const status of [500, 413]) {
    await page.route('**/api/jobs/*/map?*', route => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify({ code: status === 500 ? 'INTERNAL_ERROR' : 'MAP_FEATURE_TOO_LARGE', message: 'Injected map failure' }) }));
    const failedMap = await upload(`map-${status}-injected`, 'depth', path.join(root, 'test-data/synthetic/depth/gas-below/input.geojson'));
    await page.getByRole('heading', { name: 'Сравните варианты трассировки' }).waitFor({ timeout: 30000 });
    await page.waitForTimeout(12000);
    failedMap.text = await page.locator('body').innerText();
    await screenshot(failedMap.name);
    if (failedMap.text.includes('Нет участков с глубиной') && !failedMap.text.includes('Injected map failure')) {
      finding('map-error-hidden', `При HTTP ${status} загрузки профиля показано «Нет участков с глубиной», причина и повтор загрузки отсутствуют.`, failedMap.text);
    }
    report.cases.push(failedMap);
    await page.unroute('**/api/jobs/*/map?*');
  }

  await page.addInitScript(() => {
    const original = HTMLCanvasElement.prototype.getContext;
    HTMLCanvasElement.prototype.getContext = function (type, ...args) {
      if (/webgl/i.test(type)) return null;
      return original.call(this, type, ...args);
    };
  });
  const noWebGL = await result(await upload('webgl-disabled', 'depth', path.join(root, 'test-data/synthetic/depth/gas-below/input.geojson')));
  await page.getByRole('alert').filter({ hasText: '3D-сцена недоступна' }).waitFor();
  await screenshot('webgl-disabled-3d');
  await page.getByRole('tab', { name: 'Профиль', exact: true }).click();
  await page.getByRole('img', { name: 'Продольный профиль выбранного пути', exact: true }).waitFor();
  noWebGL.profileAvailable = true;
  await page.getByRole('tab', { name: 'Карта', exact: true }).click();
  await page.getByRole('alert').filter({ hasText: 'Карта недоступна без WebGL2' }).waitFor();
  await screenshot('webgl-disabled-map');
  delete noWebGL.exported;
  report.cases.push(noWebGL);
  assert.deepEqual(report.pageErrors, [], 'No uncaught browser errors');
  const duplicateKeys = [...new Set(report.consoleErrors.filter(e => e.includes('same key')))];
  if (duplicateKeys.length) finding('duplicate-profile-keys', 'React сообщает о повторных ключах точек профиля у полилиний с промежуточными вершинами.', duplicateKeys);
  if (process.env.BROWSER_FAIL_ON_FINDINGS === '1' && report.findings.length) process.exitCode = 2;
} catch (error) {
  report.failure = error.stack;
  process.exitCode = 1;
  await screenshot('failure').catch(() => {});
} finally {
  await fs.writeFile(path.join(output, 'report.json'), JSON.stringify(report, null, 2));
  console.log(JSON.stringify({ cases: report.cases.map(c => ({ name: c.name, jobId: c.jobId, profiles: c.profileChecks?.length })), findings: report.findings, pageErrors: report.pageErrors, failure: report.failure }, null, 2));
  await browser.close();
}
