import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { HeatNetworkApi } from '../../shared/api/contracts'
import { ApiClientError } from '../../shared/api/api-error'
import type { MapFeature, MapPage, VariantSummary } from '../../shared/model/api'

const mapMock = vi.hoisted(() => ({ render: vi.fn(), unavailable: false }))
const sceneMock = vi.hoisted(() => ({ render: vi.fn() }))
vi.mock('../network-map/NetworkMap', () => ({
  NetworkMap: (props: { onUnavailable?: (reason: string) => void }) => {
    mapMock.render(props)
    if (mapMock.unavailable) queueMicrotask(() => props.onUnavailable?.('GPU_UNAVAILABLE'))
    return null
  },
}))
vi.mock('../network-3d/DepthScene', () => ({ DepthScene: (props: unknown) => { sceneMock.render(props); return <div data-testid="depth-scene">3D-сцена</div> } }))

import { ResultWorkspace } from './ResultWorkspace'

const variant = (rank: number): VariantSummary => ({
  id: { kind: 'string', value: `summary-${rank}` }, objectType: 'variant_summary',
  variantId: { kind: 'string', value: `v${rank}` }, rank,
  constructionCost: rank, chamberConstructionCost: rank, existingChamberTieInCount: rank,
  existingChamberTieInCost: rank, unconnectedPenalty: 0, calculatedCost: rank,
  newNetworkLength: rank, score: rank, unconnectedOksIds: [],
})

function api(): HeatNetworkApi {
  return {
    health: vi.fn(), createJob: vi.fn(), getJob: vi.fn(), getMapBounds: vi.fn(), getMapPage: vi.fn(),
    listVariants: vi.fn().mockResolvedValue([variant(2), variant(1)]),
    getResultUrl: vi.fn().mockReturnValue('/api/jobs/job-1/result'),
  }
}

function renderWorkspace(
  mode: '2d' | 'depth' = '2d',
  suppliedApi = api(),
  prepareClient?: (client: QueryClient) => void,
) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  prepareClient?.(client)
  render(<QueryClientProvider client={client}><ResultWorkspace api={suppliedApi} jobId="job-1" mode={mode} /></QueryClientProvider>)
}

const depthEdge = (endNodeId: MapFeature['properties']['id']): MapFeature => ({
  type: 'Feature', geometry: { type: 'LineString', coordinates: [[37.4, 55.6], [37.41, 55.61]] },
  properties: {
    id: { kind: 'string', value: `edge-${endNodeId.kind}-${endNodeId.value}` }, objectType: 'heat_network',
    startNodeId: { kind: 'string', value: 'source' }, endNodeId, diameter: 80, depthStart: 3, depthEnd: 3,
  },
})

function apiForDepthMap(getMapPage: HeatNetworkApi['getMapPage']): HeatNetworkApi {
  const depthApi = api()
  vi.mocked(depthApi.getMapBounds).mockResolvedValue([37.4, 55.6, 37.42, 55.62])
  vi.mocked(depthApi.getMapPage).mockImplementation(getMapPage)
  return depthApi
}

beforeEach(() => { mapMock.unavailable = false; mapMock.render.mockReset(); sceneMock.render.mockReset() })

describe('ResultWorkspace', () => {
  it('selects the lowest server rank and passes the exact selected ID with the input layer to the map', async () => {
    const user = userEvent.setup()
    renderWorkspace()
    const first = await screen.findByRole('radio', { name: /вариант 1/i })
    expect(first).toBeChecked()
    await user.click(screen.getByRole('radio', { name: /вариант 2/i }))
    await waitFor(() => expect(mapMock.render).toHaveBeenLastCalledWith(
      expect.objectContaining({
        layers: [
          { layer: 'input' },
          { layer: 'result', variantId: { kind: 'string', value: 'v2' } },
        ],
      }),
    ))
  })

  it('keeps selection and download available without WebGL2', async () => {
    mapMock.unavailable = true
    renderWorkspace()
    expect(await screen.findByText(/карта недоступна без webgl2/i)).toBeVisible()
    expect(screen.getByRole('radio', { name: /вариант 1/i })).toBeEnabled()
    expect(screen.getByRole('link', { name: /скачать geojson/i })).toHaveAttribute('href', '/api/jobs/job-1/result')
  })

  it('opens a depth result in the 3D view and keeps map and profile available', async () => {
    renderWorkspace('depth')

    expect(await screen.findByText(/глубинная модель/i)).toBeVisible()
    expect(await screen.findByRole('tab', { name: /3d-сцена/i })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('tab', { name: /карта/i })).toBeEnabled()
    expect(screen.getByRole('tab', { name: /профиль/i })).toBeEnabled()
  })

  it('labels textual and numeric consumers differently without changing their selection keys', async () => {
    const depthApi = apiForDepthMap(vi.fn().mockResolvedValue({
      type: 'FeatureCollection',
      features: [depthEdge({ kind: 'string', value: '1' }), depthEdge({ kind: 'number', value: '1' })],
      nextCursor: null,
    }))

    renderWorkspace('depth', depthApi)

    expect(await screen.findByRole('option', { name: 'строковый «1»' })).toHaveValue('string:1')
    expect(screen.getByRole('option', { name: 'числовой 1' })).toHaveValue('number:1')
  })

  it('clears an old path and shows loading while the newly selected variant is still loading', async () => {
    let resolveNewVariant: ((page: { type: 'FeatureCollection'; features: MapFeature[]; nextCursor: null }) => void) | undefined
    const depthApi = apiForDepthMap(vi.fn((_jobId, mapQuery) => {
      if (mapQuery.variantId?.value === 'v2') {
        return new Promise<MapPage>((resolve) => { resolveNewVariant = resolve })
      }
      return Promise.resolve({ type: 'FeatureCollection' as const, features: [
        depthEdge({ kind: 'string', value: 'старый потребитель' }),
        depthEdge({ kind: 'string', value: 'старый резервный потребитель' }),
      ], nextCursor: null })
    }))
    const user = userEvent.setup()

    renderWorkspace('depth', depthApi, (client) => {
      client.setQueryData(['map-bounds', 'job-1', 'string:v2'], [37.4, 55.6, 37.42, 55.62])
    })

    expect(await screen.findByRole('option', { name: 'строковый «старый потребитель»' })).toBeVisible()
    await user.selectOptions(screen.getByRole('combobox', { name: /конечный потребитель/i }), 'string:старый резервный потребитель')
    await user.click(screen.getByRole('radio', { name: /вариант 2/i }))

    expect(await screen.findByText(/загружаем 3d-сцену/i)).toBeVisible()
    expect(screen.queryByRole('option', { name: 'строковый «старый потребитель»' })).not.toBeInTheDocument()

    await act(async () => {
      resolveNewVariant?.({ type: 'FeatureCollection', features: [
        depthEdge({ kind: 'string', value: 'новый потребитель' }),
        depthEdge({ kind: 'string', value: 'старый резервный потребитель' }),
      ], nextCursor: null })
    })
    expect(await screen.findByRole('option', { name: 'строковый «новый потребитель»' })).toBeVisible()
    expect(screen.getByRole('combobox', { name: /конечный потребитель/i })).toHaveValue('string:новый потребитель')
  })

  it('keeps export available while depth geometry is loading', async () => {
    const depthApi = apiForDepthMap(vi.fn().mockImplementation(() => new Promise(() => undefined)))

    renderWorkspace('depth', depthApi)

    expect(await screen.findByText(/загружаем 3d-сцену/i)).toBeVisible()
    expect(screen.getByRole('link', { name: /скачать geojson/i })).toBeVisible()
  })

  it('explains a failed depth map request and retries it', async () => {
    const getMapPage = vi.fn()
      .mockRejectedValueOnce(new Error('gateway failure'))
      .mockResolvedValue({ type: 'FeatureCollection', features: [], nextCursor: null })
    const depthApi = apiForDepthMap(getMapPage)
    const user = userEvent.setup()

    renderWorkspace('depth', depthApi)

    expect(await screen.findByRole('alert')).toHaveTextContent(/не удалось загрузить/i)
    expect(screen.getByRole('link', { name: /скачать geojson/i })).toBeVisible()
    await user.click(screen.getByRole('button', { name: /повторить загрузку/i }))
    await waitFor(() => expect(getMapPage).toHaveBeenCalledTimes(4))
  })

  it('explains the map response size limit for HTTP 413', async () => {
    const depthApi = apiForDepthMap(vi.fn().mockRejectedValue(new ApiClientError(413, 'MAP_TOO_LARGE', 'too many features')))

    renderWorkspace('depth', depthApi)

    expect(await screen.findByRole('alert')).toHaveTextContent(/слишком большой объём/i)
    expect(screen.getByRole('link', { name: /скачать geojson/i })).toBeVisible()
  })

  it('blocks a 3D scene when route and communication parts exceed their shared limit', async () => {
    const coordinates = Array.from({ length: 1201 }, (_, index) => [37.4 + index * 0.000001, 55.6] as const)
    const route = { ...depthEdge({ kind: 'string', value: 'consumer' }), geometry: { type: 'LineString' as const, coordinates } }
    const gas: MapFeature = {
      type: 'Feature', geometry: { type: 'LineString', coordinates },
      properties: { id: { kind: 'string', value: 'gas' }, objectType: 'restriction', restrictionType: 'gas_pipeline' },
    }
    const depthApi = apiForDepthMap(vi.fn((_jobId, mapQuery) => Promise.resolve({
      type: 'FeatureCollection' as const,
      features: mapQuery.layer === 'result' ? [route] : [gas],
      nextCursor: null,
    })))

    renderWorkspace('depth', depthApi)

    expect(await screen.findByRole('alert')).toHaveTextContent(/превышен предел объектов.*3d/i)
    expect(screen.getByRole('link', { name: /скачать geojson/i })).toBeVisible()
    expect(sceneMock.render).not.toHaveBeenCalled()
  })

  it('uses the 3D budget only for objects that are actually rendered', async () => {
    const routeCoordinates = Array.from({ length: 72 }, (_, index) => [37.4 + index * 0.000001, 55.6] as const)
    const buildingRing = Array.from({ length: 5939 }, (_, index) => [37.5 + index * 0.000001, 55.7] as const)
    const route = { ...depthEdge({ kind: 'string', value: 'consumer' }), geometry: { type: 'LineString' as const, coordinates: routeCoordinates } }
    const building: MapFeature = {
      type: 'Feature', geometry: { type: 'Polygon', coordinates: [buildingRing] },
      properties: { id: { kind: 'string', value: 'building' }, objectType: 'restriction' },
    }
    const depthApi = apiForDepthMap(vi.fn((_jobId, mapQuery) => Promise.resolve({
      type: 'FeatureCollection' as const,
      features: mapQuery.layer === 'result' ? [route] : [building],
      nextCursor: null,
    })))

    renderWorkspace('depth', depthApi)

    expect(await screen.findByTestId('depth-scene')).toBeVisible()
    expect(screen.queryByText(/превышен предел объектов для 3d-сцены/i)).not.toBeInTheDocument()
    expect(sceneMock.render).toHaveBeenLastCalledWith(expect.objectContaining({ segments: expect.arrayContaining([expect.any(Object)]) }))
  })

  it('keeps the profile available when communications cannot be loaded', async () => {
    const depthApi = apiForDepthMap(vi.fn((_jobId, mapQuery) => mapQuery.layer === 'input'
      ? Promise.reject(new Error('input layer failed'))
      : Promise.resolve({ type: 'FeatureCollection' as const, features: [depthEdge({ kind: 'string', value: 'consumer' })], nextCursor: null })))
    const user = userEvent.setup()

    renderWorkspace('depth', depthApi)

    await user.click(await screen.findByRole('tab', { name: /профиль/i }))
    expect(await screen.findByRole('img', { name: /продольный профиль/i })).toBeVisible()
    expect(screen.getByRole('link', { name: /скачать geojson/i })).toBeVisible()
  })
})
