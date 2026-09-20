import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import type { VariantSummary } from '../../shared/model/api'
import { VariantComparison } from './VariantComparison'

const selected: VariantSummary = {
  id: { kind: 'string', value: 'summary' }, objectType: 'variant_summary',
  variantId: { kind: 'string', value: 'v1' }, rank: 1,
  constructionCost: 20_000_000, chamberConstructionCost: 3_000_000,
  existingChamberTieInCount: 1, existingChamberTieInCost: 5_000_000,
  unconnectedPenalty: 1_000, calculatedCost: 26_391_400,
  newNetworkLength: 200, score: 1.3389592,
  unconnectedOksIds: [{ kind: 'string', value: 'consumer-2' }],
}

describe('VariantComparison', () => {
  it('shows all decision metrics from the selected server variant', () => {
    render(<VariantComparison variant={selected} />)
    expect(screen.getByText('26 391 400 ₽')).toBeVisible()
    expect(screen.getByText('200 м')).toBeVisible()
    expect(screen.getByText('3 000 000 ₽')).toBeVisible()
    expect(screen.getByText('5 000 000 ₽')).toBeVisible()
    expect(screen.getByText('1 000 ₽')).toBeVisible()
    expect(screen.getAllByText('1', { selector: 'dd' })).toHaveLength(2)
  })
})
