import { describe, expect, it } from 'vitest'

import type { MapFeature } from '../../shared/model/api'
import { buildDepthPaths, selectDepthPath, toMetricSegments } from './depth-path'

const id = (value: string) => ({ kind: 'string' as const, value })
const edge = (from: string, to: string, depthStart = 3, depthEnd = 3, coordinates: readonly (readonly [number, number])[] = [[37.426, 55.666], [37.4265, 55.666]]): MapFeature => ({
  type: 'Feature',
  geometry: { type: 'LineString', coordinates },
  properties: {
    id: id(`${from}-${to}`), objectType: 'heat_network', startNodeId: id(from), endNodeId: id(to),
    diameter: 80, depthStart, depthEnd,
  },
})

describe('depth path construction', () => {
  it('creates separate source-to-leaf paths instead of joining branches', () => {
    const paths = buildDepthPaths([edge('source', 'junction'), edge('junction', 'consumer-a'), edge('junction', 'consumer-b')])

    expect(paths).toHaveLength(2)
    expect(paths.map((path) => path.endNodeId.value)).toEqual(['consumer-a', 'consumer-b'])
    expect(paths[0]?.segments.map((segment) => segment.feature.properties.id.value)).toEqual(['source-junction', 'junction-consumer-a'])
  })

  it('selects the path for an explicit endpoint instead of the first branch', () => {
    const paths = buildDepthPaths([edge('source', 'junction'), edge('junction', 'consumer-a'), edge('junction', 'consumer-b')])

    expect(selectDepthPath(paths, 'string:consumer-b')?.endNodeId.value).toBe('consumer-b')
  })

  it('ignores 2D edges whose depth is null or absent', () => {
    const twoDimensional = edge('source', 'consumer')
    delete (twoDimensional.properties as { depthStart?: number }).depthStart
    delete (twoDimensional.properties as { depthEnd?: number }).depthEnd

    expect(buildDepthPaths([twoDimensional])).toEqual([])
  })

  it('makes translated metre segments with cumulative horizontal distance and catalog dimensions', () => {
    const path = buildDepthPaths([edge('source', 'consumer', 3, 3.4)])[0]!
    const scene = toMetricSegments(path)

    expect(scene).toHaveLength(1)
    expect(scene[0]).toMatchObject({
      start: { x: 0, y: 0, z: -12 },
      depthStart: 3,
      depthEnd: 3.4,
      widthM: 0.47,
      heightM: 0.16,
      distanceStartM: 0,
    })
    expect(scene[0]!.distanceEndM).toBeGreaterThan(1)
    expect(scene[0]!.end.x).toBeGreaterThan(1)
  })

  it('keeps every bend as a separate render segment', () => {
    const bent = edge('source', 'consumer', 3, 3, [[37.426, 55.666], [37.4262, 55.6662], [37.4265, 55.666]])

    const scene = toMetricSegments(buildDepthPaths([bent])[0]!)

    expect(scene).toHaveLength(2)
    expect(scene[0]!.end.x).not.toBe(scene[1]!.end.x)
    expect(scene[1]!.distanceStartM).toBe(scene[0]!.distanceEndM)
  })
})
