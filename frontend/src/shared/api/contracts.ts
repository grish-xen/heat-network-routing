import type { Health, Job, MapBounds, MapPage, MapQuery, VariantSummary } from '../model/api'
import type { ObjectId } from '../model/object-id'

export interface HeatNetworkApi {
  health(signal?: AbortSignal): Promise<Health>
  createJob(file: File, mode: '2d', signal?: AbortSignal): Promise<Job>
  getJob(jobId: string, signal?: AbortSignal): Promise<Job>
  listVariants(jobId: string, signal?: AbortSignal): Promise<readonly VariantSummary[]>
  getMapBounds(jobId: string, variantId: ObjectId, signal?: AbortSignal): Promise<MapBounds>
  getMapPage(jobId: string, query: MapQuery, signal?: AbortSignal): Promise<MapPage>
  getResultUrl(jobId: string): string
}
