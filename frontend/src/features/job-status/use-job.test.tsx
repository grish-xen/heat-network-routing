import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import type { HeatNetworkApi } from '../../shared/api/contracts'
import type { Job } from '../../shared/model/api'
import { useJob } from './use-job'

const queued: Job = { jobId: 'job-1', status: 'QUEUED', stage: 'QUEUED', mode: '2d', diagnostics: [] }

function apiWith(getJob: HeatNetworkApi['getJob']): HeatNetworkApi {
  return {
    health: vi.fn(),
    createJob: vi.fn(),
    getJob,
    listVariants: vi.fn(),
    getMapPage: vi.fn(),
    getResultUrl: vi.fn(),
  }
}

function wrapper() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return function Wrapper({ children }: { children: React.ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>
  }
}

afterEach(() => vi.useRealTimers())

describe('useJob', () => {
  it('polls active jobs after one second and stops on success', async () => {
    vi.useFakeTimers()
    const done: Job = { ...queued, status: 'SUCCEEDED', stage: 'DONE' }
    const getJob = vi.fn().mockResolvedValue(done)
    const { result } = renderHook(() => useJob(apiWith(getJob), queued), { wrapper: wrapper() })

    expect(getJob).not.toHaveBeenCalled()
    await act(() => vi.advanceTimersByTimeAsync(1_000))
    await act(async () => {
      await Promise.resolve()
      await vi.advanceTimersByTimeAsync(1)
    })
    expect(getJob).toHaveBeenCalledTimes(1)
    expect(result.current.data).toEqual(done)
    await act(() => vi.advanceTimersByTimeAsync(2_000))
    expect(getJob).toHaveBeenCalledTimes(1)
  })

  it('retains the last job after a transient polling failure', async () => {
    vi.useFakeTimers()
    const validating: Job = { ...queued, status: 'RUNNING', stage: 'VALIDATING' }
    const getJob = vi.fn().mockRejectedValue(new Error('offline'))
    const { result } = renderHook(() => useJob(apiWith(getJob), validating), { wrapper: wrapper() })

    await act(() => vi.advanceTimersByTimeAsync(1_000))
    await act(async () => {
      await Promise.resolve()
      await vi.advanceTimersByTimeAsync(1)
    })
    expect(result.current.isRefetchError).toBe(true)
    expect(result.current.data).toEqual(validating)
  })
})
