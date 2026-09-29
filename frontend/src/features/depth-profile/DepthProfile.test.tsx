import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import type { DepthSceneSegment } from './depth-path'
import { DepthProfile } from './DepthProfile'

const segment: DepthSceneSegment = {
  feature: { type: 'Feature', geometry: { type: 'LineString', coordinates: [[37.4, 55.6], [37.41, 55.61]] }, properties: { id: { kind: 'string', value: 'edge' }, objectType: 'heat_network' } },
  start: { x: 0, y: 0, z: -12 }, end: { x: 20, y: 0, z: -13.6 },
  depthStart: 3, depthEnd: 3.4, widthM: 0.47, heightM: 0.16, distanceStartM: 0, distanceEndM: 20,
}

describe('DepthProfile', () => {
  afterEach(() => vi.restoreAllMocks())

  it('shows a selected route with horizontal-distance and depth axes', () => {
    render(<DepthProfile segments={[segment]} />)

    expect(screen.getByRole('img', { name: /продольный профиль/i })).toBeVisible()
    expect(screen.getByText(/горизонтальное расстояние, м/i)).toBeVisible()
    expect(screen.getByText(/глубина, м/i)).toBeVisible()
    expect(screen.getByText('3,0 м')).toBeVisible()
    expect(screen.getByText('3,4 м')).toBeVisible()
  })

  it('uses distinct React keys for parts of one polyline', () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    const secondPart: DepthSceneSegment = {
      ...segment,
      start: segment.end,
      end: { x: 40, y: 10, z: -12 },
      distanceStartM: 20,
      distanceEndM: 42,
    }

    render(<DepthProfile segments={[segment, secondPart]} />)

    expect(consoleError.mock.calls.flat().join(' ')).not.toMatch(/same key/i)
  })
})
