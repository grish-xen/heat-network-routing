import { useQuery } from '@tanstack/react-query'

import type { HeatNetworkApi } from '../../shared/api/contracts'

export function useHealth(api: HeatNetworkApi) {
  return useQuery({
    queryKey: ['health'],
    queryFn: ({ signal }) => api.health(signal),
    staleTime: 30_000,
    refetchInterval: false,
  })
}
