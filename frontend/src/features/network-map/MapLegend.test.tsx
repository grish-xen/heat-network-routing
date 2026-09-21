import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { MapLegend } from './MapLegend'

describe('MapLegend', () => {
  it('toggles a layer group without changing source data', async () => {
    const user = userEvent.setup()
    const setLayerGroupVisibility = vi.fn()
    render(<MapLegend setLayerGroupVisibility={setLayerGroupVisibility} />)

    await user.click(screen.getByRole('checkbox', { name: /тепловая сеть/i }))
    expect(setLayerGroupVisibility).toHaveBeenCalledWith('network', false)
  })
})
