import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { HeatNetworkApi } from '../../shared/api/contracts'
import { ApiClientError } from '../../shared/api/api-error'
import type { MapFeature, VariantSummary } from '../../shared/model/api'

const mapMock = vi.hoisted(() => ({ render: vi.fn(), unavailable: false }))
vi.mock('../network-map/NetworkMap', () => ({
  NetworkMap: (props: { onUnavailable?: (reason: string) => void }) => {
    mapMock.render(props)
    if (mapMock.unavailable) queueMicrotask(() => props.onUnavailable?.('GPU_UNAVAILABLE'))
    return null
  },
}))
vi.mock('../network-3d/DepthScene', () => ({ DepthScene: () => <div>3D-сцена</div> }))

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

function renderWorkspace(mode: '2d' | 'depth' = '2d', suppliedApi = api()) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
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

beforeEach(() => { mapMock.unavailable = false; mapMock.render.mockReset() })

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

  it('keeps export available while depth geometry is loading', async () => {
    const depthApi = apiForDepthMap(vi.fn().mockImplementation(() => new Promise(() => undefined)))

    renderWorkspace('depth', depthApi)

    expect(await screen.findByText(/загружаем профиль и 3d/i)).toBeVisible()
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
    await waitFor(() => expect(getMapPage).toHaveBeenCalledTimes(2))
  })

  it('explains the map response size limit for HTTP 413', async () => {
    const depthApi = apiForDepthMap(vi.fn().mockRejectedValue(new ApiClientError(413, 'MAP_TOO_LARGE', 'too many features')))

    renderWorkspace('depth', depthApi)

    expect(await screen.findByRole('alert')).toHaveTextContent(/слишком большой объём/i)
    expect(screen.getByRole('link', { name: /скачать geojson/i })).toBeVisible()
  })
})
