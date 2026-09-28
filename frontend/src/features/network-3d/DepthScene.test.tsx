import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

const adapter = vi.hoisted(() => ({ resetView: vi.fn(), destroy: vi.fn() }))
vi.mock('./three-adapter', () => ({ createDepthSceneAdapter: vi.fn(() => adapter) }))

import { DepthScene } from './DepthScene'

describe('DepthScene', () => {
  it('labels vertical exaggeration and resets the scene view', async () => {
    const user = userEvent.setup()
    render(<DepthScene segments={[{} as never]} />)

    expect(screen.getByText(/вертикальный масштаб ×4/i)).toBeVisible()
    await user.click(screen.getByRole('button', { name: /сбросить ракурс/i }))
    expect(adapter.resetView).toHaveBeenCalled()
  })

  it('shows an empty state instead of creating a scene without depth data', () => {
    render(<DepthScene segments={[]} />)

    expect(screen.getByText(/нет участков с глубиной/i)).toBeVisible()
  })
})
