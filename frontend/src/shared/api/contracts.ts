import type { Health, Job, MapPage, MapQuery, VariantSummary } from '../model/api'

export interface HeatNetworkApi {
  health(signal?: AbortSignal): Promise<Health>
  createJob(file: File, mode: '2d', signal?: AbortSignal): Promise<Job>
  getJob(jobId: string, signal?: AbortSignal): Promise<Job>
  listVariants(jobId: string, signal?: AbortSignal): Promise<readonly VariantSummary[]>
  getMapPage(jobId: string, query: MapQuery, signal?: AbortSignal): Promise<MapPage>
  getResultUrl(jobId: string): string
}
