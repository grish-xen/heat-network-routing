import { useQuery } from '@tanstack/react-query'

import type { HeatNetworkApi } from '../../shared/api/contracts'

export function useVariants(api: HeatNetworkApi, jobId: string) {
  return useQuery({
    queryKey: ['variants', jobId],
    queryFn: ({ signal }) => api.listVariants(jobId, signal),
  })
}
