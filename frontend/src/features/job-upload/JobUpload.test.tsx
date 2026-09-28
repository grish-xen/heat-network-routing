import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import type { HeatNetworkApi } from '../../shared/api/contracts'
import { ApiClientError } from '../../shared/api/api-error'
import { JobUpload } from './JobUpload'

function createApi(): HeatNetworkApi {
  return {
    health: vi.fn(),
    createJob: vi.fn().mockResolvedValue({
      jobId: 'job-1',
      status: 'QUEUED',
      stage: 'QUEUED',
      mode: '2d',
      diagnostics: [],
    }),
    getJob: vi.fn(),
    listVariants: vi.fn(),
    getMapBounds: vi.fn(),
    getMapPage: vi.fn(),
    getResultUrl: vi.fn(),
  }
}

function renderUpload(api: HeatNetworkApi, onCreated = vi.fn()) {
  const client = new QueryClient({ defaultOptions: { mutations: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <JobUpload api={api} onCreated={onCreated} />
    </QueryClientProvider>,
  )
  return onCreated
}

describe('JobUpload', () => {
  it('uploads the selected GeoJSON with mode 2d', async () => {
    const user = userEvent.setup()
    const api = createApi()
    const onCreated = renderUpload(api)
    const file = new File(['{}'], 'input.geojson', { type: 'application/geo+json' })

    await user.upload(screen.getByLabelText(/geojson/i), file)
    await user.click(screen.getByRole('button', { name: /запустить расчёт/i }))

    expect(api.createJob).toHaveBeenCalledWith(file, '2d', expect.any(AbortSignal))
    expect(onCreated).toHaveBeenCalledWith(expect.objectContaining({ jobId: 'job-1' }))
  })

  it('lets the user explicitly request a depth calculation', async () => {
    const user = userEvent.setup()
    const api = createApi()
    renderUpload(api)
    const file = new File(['{}'], 'input.geojson', { type: 'application/geo+json' })

    await user.upload(screen.getByLabelText(/geojson/i), file)
    await user.click(screen.getByRole('radio', { name: /с учётом глубины/i }))
    await user.click(screen.getByRole('button', { name: /запустить расчёт/i }))

    expect(api.createJob).toHaveBeenCalledWith(file, 'depth', expect.any(AbortSignal))
  })

  it('accepts drag-and-drop and retains the file after a server error', async () => {
    const user = userEvent.setup()
    const api = createApi()
    vi.mocked(api.createJob).mockRejectedValue(
      new ApiClientError(413, 'FILE_TOO_LARGE', 'Файл слишком большой'),
    )
    renderUpload(api)
    const file = new File(['{}'], 'large.geojson', { type: 'application/geo+json' })

    fireEvent.drop(screen.getByTestId('file-dropzone'), { dataTransfer: { files: [file] } })
    await user.click(screen.getByRole('button', { name: /запустить расчёт/i }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Файл слишком большой')
    expect(screen.getByText('large.geojson')).toBeVisible()
    expect(screen.getByRole('button', { name: /запустить расчёт/i })).toBeEnabled()
  })
})
