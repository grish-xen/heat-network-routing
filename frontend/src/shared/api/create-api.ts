import type { HeatNetworkApi } from './contracts'
import { FixtureHeatNetworkApi } from './fixture-api'
import { HttpHeatNetworkApi } from './http-api'

export type ApiMode = 'fixture' | 'http'

export function resolveApiMode(value: string | undefined, production: boolean): ApiMode {
  if (value === 'fixture' || value === 'http') return value
  if (value === undefined && !production) return 'fixture'
  throw new Error('VITE_API_MODE должен быть явно задан как fixture или http')
}

function assertNever(value: never): never {
  throw new Error(`Неизвестный режим API: ${String(value)}`)
}

export function createHeatNetworkApi(mode: ApiMode): HeatNetworkApi {
  if (mode === 'fixture') return new FixtureHeatNetworkApi()
  if (mode === 'http') return new HttpHeatNetworkApi()
  return assertNever(mode)
}
