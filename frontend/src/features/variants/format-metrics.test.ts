import { describe, expect, it } from 'vitest'

import { formatMetres, formatRubles, formatScore } from './format-metrics'

describe('metric formatters', () => {
  it('uses explicit Russian presentation', () => {
    expect(formatRubles(26_391_400)).toBe('26 391 400 ₽')
    expect(formatMetres(200)).toBe('200 м')
    expect(formatScore(1.3389592)).toBe('1,33895920')
  })
})
