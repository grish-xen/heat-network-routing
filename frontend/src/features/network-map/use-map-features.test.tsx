import { describe, expect, it, vi } from 'vitest'

import type { HeatNetworkApi } from '../../shared/api/contracts'
import type { MapPage, MapQuery } from '../../shared/model/api'
import { loadMapFeatures } from './use-map-features'

const query: MapQuery = { layer: 'input', bbox: [37.4, 55.6, 37.5, 55.7], limit: 1 }
const feature = (id: string): MapPage['features'][number] => ({
  type: 'Feature', geometry: { type: 'Point', coordinates: [37.45, 55.65] },
  properties: { id: { kind: 'string', value: id }, objectType: 'source' },
})

function apiWith(getMapPage: HeatNetworkApi['getMapPage']): HeatNetworkApi {
  return { health: vi.fn(), createJob: vi.fn(), getJob: vi.fn(), listVariants: vi.fn(), getMapBounds: vi.fn(), getMapPage, getResultUrl: vi.fn() }
}

describe('loadMapFeatures', () => {
  it('follows every cursor and combines the pages', async () => {
    const getMapPage = vi.fn()
      .mockResolvedValueOnce({ type: 'FeatureCollection', features: [feature('a')], nextCursor: 'page-2' })
      .mockResolvedValueOnce({ type: 'FeatureCollection', features: [feature('b')], nextCursor: null })
    const api = apiWith(getMapPage)
    const signal = new AbortController().signal

    const result = await loadMapFeatures(api, 'job-1', query, signal)

    expect(getMapPage).toHaveBeenNthCalledWith(1, 'job-1', { ...query, cursor: undefined }, signal)
    expect(getMapPage).toHaveBeenNthCalledWith(2, 'job-1', { ...query, cursor: 'page-2' }, signal)
    expect(result.features).toHaveLength(2)
  })

  it('rejects a cursor stream once its cumulative feature count exceeds the configured cap', async () => {
    const getMapPage = vi.fn()
      .mockResolvedValueOnce({ type: 'FeatureCollection', features: [feature('a'), feature('b')], nextCursor: 'page-2' })
      .mockResolvedValueOnce({ type: 'FeatureCollection', features: [feature('c')], nextCursor: 'page-3' })
    const api = apiWith(getMapPage)

    await expect(loadMapFeatures(api, 'job-1', query, new AbortController().signal, 2)).rejects.toThrow(/предел.*объект/i)

    expect(getMapPage).toHaveBeenCalledTimes(2)
  })

  it('does not treat vertices of one feature as separate loaded objects', async () => {
    const longLine: MapPage['features'][number] = {
      ...feature('long-line'), geometry: { type: 'LineString', coordinates: [[37.4, 55.6], [37.41, 55.61], [37.42, 55.62], [37.43, 55.63]] },
    }
    const getMapPage = vi.fn().mockResolvedValue({ type: 'FeatureCollection', features: [longLine], nextCursor: null })

    await expect(loadMapFeatures(apiWith(getMapPage), 'job-1', query, new AbortController().signal, 2)).resolves.toMatchObject({ features: [longLine] })
    expect(getMapPage).toHaveBeenCalledTimes(1)
  })

  it('does not request a later cursor after abort', async () => {
    const controller = new AbortController()
    const getMapPage = vi.fn().mockImplementation(async () => {
      controller.abort()
      return { type: 'FeatureCollection', features: [feature('a')], nextCursor: 'page-2' }
    })

    await expect(loadMapFeatures(apiWith(getMapPage), 'job-1', query, controller.signal)).rejects.toMatchObject({ name: 'AbortError' })
    expect(getMapPage).toHaveBeenCalledTimes(1)
  })

  it('rejects a server cursor cycle', async () => {
    const getMapPage = vi.fn()
      .mockResolvedValueOnce({ type: 'FeatureCollection', features: [], nextCursor: 'same' })
      .mockResolvedValueOnce({ type: 'FeatureCollection', features: [], nextCursor: 'same' })
    await expect(loadMapFeatures(apiWith(getMapPage), 'job-1', query, new AbortController().signal)).rejects.toThrow(/цикл/i)
  })

  it('rejects invalid bounds before making a request', async () => {
    const getMapPage = vi.fn()
    const invalidQuery: MapQuery = { ...query, bbox: [37.5, 55.6, 37.4, 55.7] }

    await expect(
      loadMapFeatures(apiWith(getMapPage), 'job-1', invalidQuery, new AbortController().signal),
    ).rejects.toThrow(/возрастать/i)
    expect(getMapPage).not.toHaveBeenCalled()
  })
})
