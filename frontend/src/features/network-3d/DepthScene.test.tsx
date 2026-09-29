import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const adapter = vi.hoisted(() => ({ resetView: vi.fn(), destroy: vi.fn(), emitSelect: (index: number) => { void index } }))
vi.mock('./three-adapter', () => ({ createDepthSceneAdapter: vi.fn((_host: HTMLElement, _segments: unknown, _communications: unknown, options: { onSelect?: (index: number) => void }) => {
  adapter.emitSelect = (index: number) => options.onSelect?.(index)
  return adapter
}) }))

import { DepthScene } from './DepthScene'
import { createDepthSceneAdapter } from './three-adapter'

beforeEach(() => { adapter.resetView.mockReset(); adapter.destroy.mockReset() })

const segment = {
  feature: { type: 'Feature' as const, geometry: { type: 'LineString' as const, coordinates: [[37.4, 55.6] as const, [37.41, 55.61] as const] }, properties: { id: { kind: 'string' as const, value: 'edge-1' }, objectType: 'heat_network' as const, diameter: 80 } },
  start: { x: 0, y: 0, z: -12 }, end: { x: 25, y: 0, z: -13.6 }, depthStart: 3, depthEnd: 3.4,
  widthM: 0.47, heightM: 0.16, distanceStartM: 0, distanceEndM: 25,
}

describe('DepthScene', () => {
  it('labels vertical exaggeration and resets the scene view', async () => {
    const user = userEvent.setup()
    render(<DepthScene segments={[segment]} />)

    expect(screen.getByText(/вертикальный масштаб ×4/i)).toBeVisible()
    await user.click(screen.getByRole('button', { name: /сбросить ракурс/i }))
    expect(adapter.resetView).toHaveBeenCalled()
  })

  it('shows an empty state instead of creating a scene without depth data', () => {
    render(<DepthScene segments={[]} />)

    expect(screen.getByText(/нет участков с глубиной/i)).toBeVisible()
  })

  it('explains when WebGL cannot create a 3D scene', async () => {
    vi.mocked(createDepthSceneAdapter).mockImplementationOnce(() => { throw new Error('WebGL unavailable') })
    render(<DepthScene segments={[segment]} />)

    expect(await screen.findByRole('alert')).toHaveTextContent(/3d-сцена недоступна/i)
  })

  it('shows a compact legend and properties for the selected route', () => {
    render(<DepthScene segments={[segment]} />)

    expect(screen.getByText(/условная поверхность/i)).toBeVisible()
    expect(screen.getByText(/3d-сцена выбранного пути/i)).toBeVisible()
    expect(screen.getByText(/расчётная оболочка трубы/i)).toBeVisible()
    expect(screen.getByText('ДУ 80')).toBeVisible()
    expect(screen.getByText(/3,0–3,4 м/i)).toBeVisible()
  })

  it('shows the full depth range including interior sections', () => {
    const interior = {
      ...segment,
      start: { x: 25, y: 0, z: -9.76 },
      end: { x: 50, y: 0, z: -12 },
      depthStart: 2.44,
      depthEnd: 3,
      distanceStartM: 25,
      distanceEndM: 50,
    }

    render(<DepthScene segments={[{ ...segment, depthEnd: 3 }, interior, { ...segment, start: interior.end, end: { x: 75, y: 0, z: -12 }, depthEnd: 3, distanceStartM: 50, distanceEndM: 75 }]} />)

    expect(screen.getByText(/диапазон глубин/i)).toBeVisible()
    expect(screen.getByText(/2,44–3,0 м/i)).toBeVisible()
  })

  it('shows properties for the selected pipe section and keeps camera reset available', async () => {
    const user = userEvent.setup()
    const second = { ...segment, start: { x: 25, y: 0, z: -13.6 }, end: { x: 50, y: 0, z: -12 }, distanceStartM: 25, distanceEndM: 50 }
    render(<DepthScene segments={[segment, second]} />)

    adapter.emitSelect(1)
    expect(await screen.findByText(/участок 2/i)).toBeVisible()
    expect(screen.getByText('ДУ 80')).toBeVisible()
    await user.click(screen.getByRole('button', { name: /сбросить ракурс/i }))
    expect(adapter.resetView).toHaveBeenCalledTimes(1)
  })
})
