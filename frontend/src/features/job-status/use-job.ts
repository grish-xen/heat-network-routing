import { useQuery } from '@tanstack/react-query'

import type { HeatNetworkApi } from '../../shared/api/contracts'
import type { Job } from '../../shared/model/api'

export function useJob(api: HeatNetworkApi, initialJob: Job) {
  return useQuery({
    queryKey: ['job', initialJob.jobId],
    queryFn: ({ signal }) => api.getJob(initialJob.jobId, signal),
    initialData: initialJob,
    refetchOnMount: false,
    refetchInterval: (query) => {
      const status = query.state.data?.status
      return status === 'QUEUED' || status === 'RUNNING' ? 1_000 : false
    },
  })
}
