import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import type { VariantSummary } from '../../shared/model/api'
import { objectIdKey } from '../../shared/model/object-id'
import { VariantList } from './VariantList'

const variant = (rank: number): VariantSummary => ({
  id: { kind: 'string', value: `summary-${rank}` }, objectType: 'variant_summary',
  variantId: { kind: 'string', value: `v${rank}` }, rank,
  constructionCost: 20_000_000 + rank, chamberConstructionCost: 3_000_000,
  existingChamberTieInCount: 1, existingChamberTieInCost: 5_000_000,
  unconnectedPenalty: 0, calculatedCost: 26_391_400 + rank,
  newNetworkLength: 200, score: 1.3389592, unconnectedOksIds: [],
})

describe('VariantList', () => {
  it('renders server ranks in order and selects without recomputing scores', async () => {
    const user = userEvent.setup()
    const variants = [variant(2), variant(1)]
    const onSelect = vi.fn()
    render(<VariantList variants={variants} selectedKey={objectIdKey(variants[1]!.variantId)} onSelect={onSelect} />)

    const group = screen.getByRole('radiogroup', { name: /варианты трассировки/i })
    const radios = within(group).getAllByRole('radio')
    expect(radios.map((radio) => radio.getAttribute('aria-label'))).toEqual(['Вариант 1', 'Вариант 2'])
    expect(radios[0]).toBeChecked()
    expect(screen.getAllByText(/1,33895920/)).toHaveLength(2)
    await user.click(radios[1]!)
    expect(onSelect).toHaveBeenCalledWith(variants[0])
  })
})
