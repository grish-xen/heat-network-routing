import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import type { HeatNetworkApi } from '../../shared/api/contracts'
import { ServiceStatus } from './ServiceStatus'

function apiWithHealth(health: HeatNetworkApi['health']): HeatNetworkApi {
  return {
    health,
    createJob: vi.fn(),
    getJob: vi.fn(),
    listVariants: vi.fn(),
    getMapBounds: vi.fn(),
    getMapPage: vi.fn(),
    getResultUrl: vi.fn(),
  }
}

function renderStatus(api: HeatNetworkApi) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><ServiceStatus api={api} /></QueryClientProvider>)
}

describe('ServiceStatus', () => {
  it('reports an available API only for status UP', async () => {
    renderStatus(apiWithHealth(vi.fn().mockResolvedValue({ status: 'UP', contractVersion: '1.0', implementation: 'fixture' })))
    expect(await screen.findByText('API доступен')).toBeVisible()
  })

  it('reports contract and network failures without blocking the page', async () => {
    renderStatus(apiWithHealth(vi.fn().mockRejectedValue(new Error('offline'))))
    expect(await screen.findByText('API недоступен')).toBeVisible()
  })
})
