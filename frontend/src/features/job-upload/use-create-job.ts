import { useMutation } from '@tanstack/react-query'
import { useEffect, useRef } from 'react'

import type { HeatNetworkApi } from '../../shared/api/contracts'
import type { Job } from '../../shared/model/api'

export function useCreateJob(api: HeatNetworkApi, onCreated: (job: Job) => void) {
  const controller = useRef<AbortController | null>(null)
  useEffect(() => () => controller.current?.abort(), [])
  return useMutation({
    mutationFn: (file: File) => {
      controller.current?.abort()
      controller.current = new AbortController()
      return api.createJob(file, '2d', controller.current.signal)
    },
    onSuccess: (job) => onCreated(job),
  })
}
