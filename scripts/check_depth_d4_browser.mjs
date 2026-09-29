// Additional D4 checks with a real backend. No frontend implementation is changed.
// Uses the same browser environment variables as check_depth_browser.mjs.
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const base = process.env.BROWSER_BASE_URL || 'http://127.0.0.1:5174';
const output = path.resolve(process.env.BROWSER_OUTPUT || 'tmp/depth-d4-report');
const { chromium } = await import(pathToFileURL(path.resolve(process.env.PLAYWRIGHT_MODULE)).href);
const browser = await chromium.launch({ executablePath: process.env.BROWSER_EXECUTABLE,
  headless: true, args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
const report = { sourceCommit: process.env.SOURCE_COMMIT, findings: [], checks: {}, pageErrors: [] };
page.on('pageerror', e => report.pageErrors.push(e.message));
await fs.mkdir(output, { recursive: true });
try {
  await page.goto(base);
  await page.getByLabel('С учётом глубины', { exact: true }).check();
  await page.getByLabel('GeoJSON файл', { exact: true }).setInputFiles(path.join(root, 'test-data/synthetic/depth/gas-below/input.geojson'));
  const created = page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/api/jobs'));
  await page.getByRole('button', { name: 'Запустить расчёт', exact: true }).click();
  const response = await created;
  assert.equal(response.status(), 202);
  const { jobId } = await response.json(); report.jobId = jobId;
  await page.locator('.depth-scene-canvas canvas').waitFor({ timeout: 180000 });
  await page.waitForTimeout(800);
  report.checks.initialProperties = await page.getByLabel('Свойства выбранного участка', { exact: true }).innerText();
  assert.match(report.checks.initialProperties, /2,44–3,0/);
  assert.match(report.checks.initialProperties, /Коммуникации: [1-9]/);

  // Project actual API segment midpoints using the public camera helper. The
  // selection itself is a pointer click on the production WebGL canvas.
  const targets = await page.evaluate(async job => {
    const { HttpHeatNetworkApi } = await import('/src/shared/api/http-api.ts');
    const { buildDepthPaths, toMetricSegments } = await import('/src/features/depth-profile/depth-path.ts');
    const { cameraFrame } = await import('/src/features/network-3d/three-adapter.ts');
    const THREE = await import('/node_modules/three/build/three.module.js');
    const variants = await (await fetch(`/api/jobs/${job}/variants`)).json();
    const api = new HttpHeatNetworkApi();
    const data = await api.getMapPage(job, { layer: 'result', variantId: { kind: 'string', value: variants[0].variant_id }, bbox: [-180,-90,180,90], limit: 1000 });
    const segments = toMetricSegments(buildDepthPaths(data.features)[0]);
    const canvas = document.querySelector('.depth-scene-canvas canvas');
    const rect = canvas.getBoundingClientRect();
    const camera = new THREE.PerspectiveCamera(32, rect.width / rect.height, 0.1, 5000);
    const frame = cameraFrame(segments.flatMap(s => [s.start, s.end]), camera.aspect);
    camera.position.copy(frame.position); camera.lookAt(frame.target); camera.updateMatrixWorld();
    window.d4OriginalCanvas = canvas;
    window.d4InitialCamera = { position: frame.position.toArray(), target: frame.target.toArray(),
      points: segments.flatMap(s => [s.start, s.end]) };
    return segments.map((s, index) => {
      const at = new THREE.Vector3((s.start.x+s.end.x)/2, (s.start.y+s.end.y)/2,
        (s.start.z+s.end.z)/2 - s.heightM*2).project(camera);
      return { index, x: rect.x+(at.x+1)*rect.width/2, y: rect.y+(1-at.y)*rect.height/2 };
    });
  }, jobId);
  let selected = false;
  for (const target of targets.slice(1)) {
    await page.mouse.click(target.x, target.y);
    await page.waitForTimeout(350);
    const text = await page.getByLabel('Свойства выбранного участка', { exact: true }).innerText();
    if (text.includes(`Участок ${target.index + 1}`)) { selected = true; report.checks.selectedProperties = text; break; }
  }
  assert.ok(selected, 'A real pointer click must select a different pipe section');
  report.checks.selectionRecreatedCanvas = await page.evaluate(() => window.d4OriginalCanvas !== document.querySelector('.depth-scene-canvas canvas'));
  if (report.checks.selectionRecreatedCanvas) report.findings.push({ id: 'selection-recreates-scene',
    message: 'Selecting a pipe replaces the canvas; DepthScene recreates its adapter and initial camera on selectedSection changes.' });
  await page.screenshot({ path: path.join(output, 'd4-selected-section.png'), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(300);
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
  await page.getByRole('button', { name: 'Сбросить ракурс', exact: true }).click();
  report.checks.mobileResetProjection = await page.evaluate(async () => {
    const THREE = await import('/node_modules/three/build/three.module.js');
    const rect = document.querySelector('.depth-scene-canvas canvas').getBoundingClientRect();
    const initial = window.d4InitialCamera;
    const camera = new THREE.PerspectiveCamera(32, rect.width / rect.height, 0.1, 5000);
    camera.position.fromArray(initial.position); camera.lookAt(new THREE.Vector3().fromArray(initial.target));
    camera.updateMatrixWorld();
    // The adapter's resetView closes over this initial frame even after resize.
    const points = initial.points.map(p => new THREE.Vector3(p.x,p.y,p.z).project(camera));
    return { maxAbsX: Math.max(...points.map(p => Math.abs(p.x))), maxAbsY: Math.max(...points.map(p => Math.abs(p.y))) };
  });
  if (report.checks.mobileResetProjection.maxAbsX > 1 || report.checks.mobileResetProjection.maxAbsY > 1) {
    report.findings.push({ id: 'mobile-reset-keeps-desktop-frame',
      message: 'After resizing to 390 px, reset still uses the desktop camera frame. Route endpoints project outside the canvas; see mobile screenshot.' });
  }
  await page.screenshot({ path: path.join(output, 'd4-mobile-scene.png'), fullPage: true });
  report.checks.mobileScene = true;

  // Isolated adversarial geometry: a single input object can contain many
  // segments. Do not render it; verify the actual loader/conversion boundary.
  report.checks.sceneBudget = await page.evaluate(async () => {
    const { loadMapFeatures, MAX_DEPTH_SCENE_FEATURES } = await import('/src/features/network-map/use-map-features.ts');
    const { toSceneCommunications } = await import('/src/features/depth-profile/depth-path.ts');
    const count = MAX_DEPTH_SCENE_FEATURES + 1;
    const feature = { type: 'Feature', properties: { id: { kind: 'string', value: 'long-gas' }, objectType: 'restriction', restrictionType: 'gas_pipeline' },
      geometry: { type: 'LineString', coordinates: Array.from({ length: count+1 }, (_, i) => [37.4+i*0.000001,55.7]) } };
    const api = { getMapPage: async () => ({ type: 'FeatureCollection', features: [feature], nextCursor: null }) };
    const loaded = await loadMapFeatures(api, 'budget-probe', { layer: 'input', bbox: [-180,-90,180,90], limit: 1000 }, new AbortController().signal, MAX_DEPTH_SCENE_FEATURES);
    return { limit: MAX_DEPTH_SCENE_FEATURES, features: loaded.features.length,
      meshes: toSceneCommunications(loaded.features, { x: 400000, y: 6170000 }).length };
  });
  if (report.checks.sceneBudget.meshes > report.checks.sceneBudget.limit) report.findings.push({ id: 'scene-budget-counts-features-only',
    message: 'The feature limit does not bound generated scene meshes: one accepted LineString exceeds the scene limit.' });
  assert.deepEqual(report.pageErrors, []);
  if (report.findings.length) process.exitCode = 2;
} catch (e) {
  report.failure = e.stack; process.exitCode = 1;
  await page.screenshot({ path: path.join(output, 'd4-failure.png'), fullPage: true }).catch(() => {});
} finally {
  await fs.writeFile(path.join(output, 'd4-report.json'), JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report, null, 2));
  await browser.close();
}
