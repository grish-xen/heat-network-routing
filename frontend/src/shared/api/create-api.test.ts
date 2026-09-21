import { describe, expect, it } from 'vitest'

import { FixtureHeatNetworkApi } from './fixture-api'
import { HttpHeatNetworkApi } from './http-api'
import { createHeatNetworkApi, resolveApiMode } from './create-api'

describe('API selection', () => {
  it('creates only the explicitly requested implementation', () => {
    expect(createHeatNetworkApi('fixture')).toBeInstanceOf(FixtureHeatNetworkApi)
    expect(createHeatNetworkApi('http')).toBeInstanceOf(HttpHeatNetworkApi)
  })

  it('defaults development to fixtures but requires production configuration', () => {
    expect(resolveApiMode(undefined, false)).toBe('fixture')
    expect(resolveApiMode('http', true)).toBe('http')
    expect(() => resolveApiMode(undefined, true)).toThrow(/VITE_API_MODE/)
    expect(() => resolveApiMode('unexpected', false)).toThrow(/VITE_API_MODE/)
  })
})
