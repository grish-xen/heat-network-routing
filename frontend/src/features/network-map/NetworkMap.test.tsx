import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { HeatNetworkApi } from '../../shared/api/contracts'
import type { MapBounds } from '../../shared/model/api'

const mapMock = vi.hoisted(() => ({
  adapter: {
    fitBounds: vi.fn(),
    setFeatureCollection: vi.fn(),
    setLayerGroupVisibility: vi.fn(),
    destroy: vi.fn(),
  },
  create: vi.fn(),
}))

vi.mock('./maplibre-adapter', () => ({ createMapLibreAdapter: mapMock.create }))

import { NetworkMap } from './NetworkMap'

function createApi(bounds: MapBounds = [37.4, 55.6, 37.5, 55.7]): HeatNetworkApi {
  return {
    health: vi.fn(), createJob: vi.fn(), getJob: vi.fn(), listVariants: vi.fn(), getResultUrl: vi.fn(),
    getMapBounds: vi.fn().mockResolvedValue(bounds),
    getMapPage: vi.fn().mockResolvedValue({ type: 'FeatureCollection', features: [], nextCursor: null }),
  }
}

function renderMap(element: React.ReactElement) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={client}>{element}</QueryClientProvider>)
}

beforeEach(() => {
  mapMock.create.mockReset().mockReturnValue(mapMock.adapter)
  mapMock.adapter.setFeatureCollection.mockReset()
  mapMock.adapter.fitBounds.mockReset()
  mapMock.adapter.setLayerGroupVisibility.mockReset()
  mapMock.adapter.destroy.mockReset()
})
afterEach(() => vi.useRealTimers())

describe('NetworkMap', () => {
  it('creates one adapter, loads each layer independently, publishes their data, and destroys it on unmount', async () => {
    const api = createApi()
    const layers = [
      { layer: 'input' as const },
      { layer: 'result' as const, variantId: { kind: 'string' as const, value: 'variant-1' } },
    ]
    const { rerender, unmount } = renderMap(<NetworkMap api={api} jobId="job-1" layers={layers} />)

    await waitFor(() => expect(api.getMapBounds).toHaveBeenCalledWith(
      'job-1',
      { kind: 'string', value: 'variant-1' },
      expect.any(AbortSignal),
    ))
    await waitFor(() => expect(mapMock.adapter.fitBounds).toHaveBeenCalledWith([37.4, 55.6, 37.5, 55.7]))
    await waitFor(() => expect(api.getMapPage).toHaveBeenCalledWith(
      'job-1',
      expect.objectContaining({ layer: 'input', variantId: undefined, bbox: [37.4, 55.6, 37.5, 55.7] }),
      expect.any(AbortSignal),
    ))
    expect(api.getMapPage).toHaveBeenCalledWith(
      'job-1',
      expect.objectContaining({ layer: 'result', variantId: { kind: 'string', value: 'variant-1' } }),
      expect.any(AbortSignal),
    )
    rerender(<QueryClientProvider client={new QueryClient()}><NetworkMap api={api} jobId="job-1" layers={layers} /></QueryClientProvider>)
    expect(mapMock.create).toHaveBeenCalledTimes(1)
    unmount()
    expect(mapMock.adapter.destroy).toHaveBeenCalledTimes(1)
  })

  it('does not fit or query geometry when the server reports no bounds', async () => {
    const api = createApi(null)
    renderMap(<NetworkMap api={api} jobId="job-1" layers={[
      { layer: 'input' },
      { layer: 'result', variantId: { kind: 'string', value: 'variant-1' } },
    ]} />)

    await waitFor(() => expect(api.getMapBounds).toHaveBeenCalledTimes(1))
    expect(mapMock.adapter.fitBounds).not.toHaveBeenCalled()
    expect(api.getMapPage).not.toHaveBeenCalled()
  })

  it('keeps the server bounds as the first geometry query until the map reports its fitted viewport', async () => {
    const api = createApi([40, 50, 41, 51])
    renderMap(<NetworkMap api={api} jobId="job-1" layers={[
      { layer: 'input' },
      { layer: 'result', variantId: { kind: 'string', value: 'variant-1' } },
    ]} />)

    await waitFor(() => expect(mapMock.adapter.fitBounds).toHaveBeenCalledWith([40, 50, 41, 51]))
    await new Promise((resolve) => window.setTimeout(resolve, 10))
    expect(api.getMapPage).toHaveBeenCalledTimes(2)
    expect(api.getMapPage).toHaveBeenCalledWith(
      'job-1',
      expect.objectContaining({ bbox: [40, 50, 41, 51] }),
      expect.any(AbortSignal),
    )
  })

  it('debounces viewport changes reported by the adapter', () => {
    vi.useFakeTimers()
    const onViewportChange = vi.fn()
    renderMap(<NetworkMap api={createApi()} jobId="job-1" layers={[{ layer: 'input' }]} onViewportChange={onViewportChange} />)
    const emitViewport = mapMock.create.mock.calls[0]?.[1]
    const bbox = { minLon: 37.41, minLat: 55.66, maxLon: 37.42, maxLat: 55.67 }

    emitViewport?.(bbox)
    vi.advanceTimersByTime(179)
    expect(onViewportChange).not.toHaveBeenCalled()
    vi.advanceTimersByTime(1)
    expect(onViewportChange).toHaveBeenCalledWith(bbox)
  })
})
