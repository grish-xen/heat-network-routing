import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { HeatNetworkApi } from '../../shared/api/contracts'
import type { VariantSummary } from '../../shared/model/api'

const mapMock = vi.hoisted(() => ({ render: vi.fn(), unavailable: false }))
vi.mock('../network-map/NetworkMap', () => ({
  NetworkMap: (props: { onUnavailable?: (reason: string) => void }) => {
    mapMock.render(props)
    if (mapMock.unavailable) queueMicrotask(() => props.onUnavailable?.('GPU_UNAVAILABLE'))
    return null
  },
}))

import { ResultWorkspace } from './ResultWorkspace'

const variant = (rank: number): VariantSummary => ({
  id: { kind: 'string', value: `summary-${rank}` }, objectType: 'variant_summary',
  variantId: { kind: 'string', value: `v${rank}` }, rank,
  constructionCost: rank, chamberConstructionCost: rank, existingChamberTieInCount: rank,
  existingChamberTieInCost: rank, unconnectedPenalty: 0, calculatedCost: rank,
  newNetworkLength: rank, score: rank, unconnectedOksIds: [],
})

function api(): HeatNetworkApi {
  return {
    health: vi.fn(), createJob: vi.fn(), getJob: vi.fn(), getMapBounds: vi.fn(), getMapPage: vi.fn(),
    listVariants: vi.fn().mockResolvedValue([variant(2), variant(1)]),
    getResultUrl: vi.fn().mockReturnValue('/api/jobs/job-1/result'),
  }
}

function renderWorkspace(mode: '2d' | 'depth' = '2d') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><ResultWorkspace api={api()} jobId="job-1" mode={mode} /></QueryClientProvider>)
}

beforeEach(() => { mapMock.unavailable = false; mapMock.render.mockReset() })

describe('ResultWorkspace', () => {
  it('selects the lowest server rank and passes the exact selected ID with the input layer to the map', async () => {
    const user = userEvent.setup()
    renderWorkspace()
    const first = await screen.findByRole('radio', { name: /вариант 1/i })
    expect(first).toBeChecked()
    await user.click(screen.getByRole('radio', { name: /вариант 2/i }))
    await waitFor(() => expect(mapMock.render).toHaveBeenLastCalledWith(
      expect.objectContaining({
        layers: [
          { layer: 'input' },
          { layer: 'result', variantId: { kind: 'string', value: 'v2' } },
        ],
      }),
    ))
  })

  it('keeps selection and download available without WebGL2', async () => {
    mapMock.unavailable = true
    renderWorkspace()
    expect(await screen.findByText(/карта недоступна без webgl2/i)).toBeVisible()
    expect(screen.getByRole('radio', { name: /вариант 1/i })).toBeEnabled()
    expect(screen.getByRole('link', { name: /скачать geojson/i })).toHaveAttribute('href', '/api/jobs/job-1/result')
  })

  it('opens a depth result in the 3D view and keeps map and profile available', async () => {
    renderWorkspace('depth')

    expect(await screen.findByText(/глубинная модель/i)).toBeVisible()
    expect(await screen.findByRole('tab', { name: /3d-сцена/i })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('tab', { name: /карта/i })).toBeEnabled()
    expect(screen.getByRole('tab', { name: /профиль/i })).toBeEnabled()
  })
})
