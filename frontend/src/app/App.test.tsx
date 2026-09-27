import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { HeatNetworkApi } from '../shared/api/contracts'
import { App } from './App'
import { AppProviders } from './providers'

const mapMock = vi.hoisted(() => ({ render: vi.fn() }))
vi.mock('../features/network-map/NetworkMap', () => ({
  NetworkMap: (props: unknown) => {
    mapMock.render(props)
    return null
  },
}))

function api(status: 'QUEUED' | 'FAILED' = 'QUEUED'): HeatNetworkApi {
  return {
    health: vi.fn().mockResolvedValue({ status: 'UP', contractVersion: '1.0', implementation: 'test' }),
    createJob: vi.fn().mockResolvedValue({ jobId: 'job-1', status, stage: status === 'FAILED' ? 'FAILED' : 'QUEUED', mode: '2d', diagnostics: [] }),
    getJob: vi.fn(), listVariants: vi.fn(), getMapBounds: vi.fn(), getMapPage: vi.fn(), getResultUrl: vi.fn(),
  }
}

describe('App', () => {
  it('identifies the service and fixture data honestly', () => {
    render(
      <AppProviders>
        <App apiMode="fixture" api={api()} />
      </AppProviders>,
    )

    expect(
      screen.getByRole('heading', { name: /моделирование тепловых сетей/i }),
    ).toBeVisible()
    expect(screen.getByText('Демонстрационные данные')).toBeVisible()
    expect(screen.getByRole('button', { name: /запустить расчёт/i })).toBeDisabled()
  })

  it.each(['QUEUED', 'FAILED'] as const)('does not request a map before a %s job has succeeded', async (status) => {
    const user = userEvent.setup()
    mapMock.render.mockReset()
    render(<AppProviders><App apiMode="http" api={api(status)} /></AppProviders>)

    await user.upload(screen.getByLabelText(/geojson файл/i), new File(['{}'], 'input.geojson', { type: 'application/geo+json' }))
    await user.click(screen.getByRole('button', { name: /запустить расчёт/i }))

    await waitFor(() => expect(screen.getByRole('heading', { name: status === 'FAILED' ? /задача завершилась с ошибкой/i : /выполняем расчёт/i })).toBeVisible())
    expect(mapMock.render).not.toHaveBeenCalled()
  })
})
