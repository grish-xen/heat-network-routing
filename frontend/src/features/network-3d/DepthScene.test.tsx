import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

const adapter = vi.hoisted(() => ({ resetView: vi.fn(), destroy: vi.fn() }))
vi.mock('./three-adapter', () => ({ createDepthSceneAdapter: vi.fn(() => adapter) }))

import { DepthScene } from './DepthScene'

describe('DepthScene', () => {
  it('labels vertical exaggeration and resets the scene view', async () => {
    const user = userEvent.setup()
    render(<DepthScene segments={[]} />)

    expect(screen.getByText(/вертикальный масштаб ×4/i)).toBeVisible()
    await user.click(screen.getByRole('button', { name: /сбросить ракурс/i }))
    expect(adapter.resetView).toHaveBeenCalled()
  })
})
