import { describe, expect, it } from 'vitest'

import { mapQueryKey, serializeBbox, toBboxTuple } from './map-query'

describe('map query', () => {
  it('serializes a validated EPSG:4326 bbox without locale formatting', () => {
    const bbox = { minLon: 37.4, minLat: 55.6, maxLon: 37.5, maxLat: 55.7 }
    expect(serializeBbox(bbox)).toBe('37.4,55.6,37.5,55.7')
    expect(toBboxTuple(bbox)).toEqual([37.4, 55.6, 37.5, 55.7])
  })

  it.each([
    { minLon: -181, minLat: 0, maxLon: 1, maxLat: 1 },
    { minLon: 0, minLat: -91, maxLon: 1, maxLat: 1 },
    { minLon: 1, minLat: 0, maxLon: 1, maxLat: 1 },
    { minLon: 0, minLat: 2, maxLon: 1, maxLat: 1 },
  ])('rejects invalid bounds %#', (bbox) => expect(() => serializeBbox(bbox)).toThrow())

  it('makes bbox and exact variant identity part of the cache key', () => {
    const base = { layer: 'result' as const, bbox: [37.4, 55.6, 37.5, 55.7] as const, limit: 1000 }
    expect(mapQueryKey('job-1', { ...base, variantId: { kind: 'number', value: '1' } })).not.toEqual(
      mapQueryKey('job-1', { ...base, variantId: { kind: 'string', value: '1' } }),
    )
    expect(mapQueryKey('job-1', base)).not.toEqual(
      mapQueryKey('job-1', { ...base, bbox: [37.4, 55.6, 37.6, 55.7] }),
    )
  })
})
