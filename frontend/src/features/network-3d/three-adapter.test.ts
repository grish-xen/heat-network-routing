import { describe, expect, it } from 'vitest'

import type { DepthSceneSegment } from '../depth-profile/depth-path'
import { buildEnvelopeGeometry, cameraFrame, sceneObjectDescriptors } from './three-adapter'
import type { DepthSceneCommunication } from '../depth-profile/depth-path'

const segment: DepthSceneSegment = {
  feature: { type: 'Feature', geometry: { type: 'LineString', coordinates: [[37.4, 55.6], [37.41, 55.61]] }, properties: { id: { kind: 'string', value: 'edge' }, objectType: 'heat_network' } },
  start: { x: 0, y: 0, z: -12 }, end: { x: 20, y: 0, z: -13.6 }, depthStart: 3, depthEnd: 3.4,
  widthM: 0.47, heightM: 0.16, distanceStartM: 0, distanceEndM: 20,
}

describe('3D scene data', () => {
  it('includes a surface and scaled design envelope for every selected segment', () => {
    expect(sceneObjectDescriptors([segment])).toEqual(expect.arrayContaining([
      expect.objectContaining({ kind: 'surface', z: 0, opacity: 0.1 }),
      expect.objectContaining({ kind: 'pipe', widthM: 0.47, heightM: 0.64, color: '#e9582f' }),
      expect.objectContaining({ kind: 'depth-guide', z: -12 }),
    ]))
  })

  it('keeps envelope width horizontal and height vertical on a sloped route', () => {
    const geometry = buildEnvelopeGeometry({ ...segment, end: { x: -4, y: 1, z: -13.65 } })
    const positions = geometry.getAttribute('position').array as Float32Array
    const elevations = Array.from(positions).filter((_, index) => index % 3 === 2)

    expect(Math.max(...elevations)).toBeCloseTo(-12)
    expect(Math.min(...elevations)).toBeCloseTo(-14.29)
  })

  it('frames westbound paths around their actual bounds', () => {
    const frame = cameraFrame([{ x: 0, y: 0, z: -12 }, { x: -100, y: 0, z: -12 }], 1.3)

    expect(frame.target.x).toBeCloseTo(-50)
    expect(frame.position.x).toBeLessThan(100)
  })

  it('describes documented communication envelopes alongside the new pipe', () => {
    const communication: DepthSceneCommunication = { kind: 'gas_pipeline', topDepthM: 2.8, widthM: 0.4, heightM: 0.4, start: { x: 2, y: -2, z: -11.2 }, end: { x: 2, y: 2, z: -11.2 } }
    expect(sceneObjectDescriptors([segment], [communication])).toContainEqual(expect.objectContaining({ kind: 'gas_pipeline', widthM: 0.4 }))
  })
})
