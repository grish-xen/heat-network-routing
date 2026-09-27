import type { HeatNetworkApi } from './contracts'
import { ApiClientError } from './api-error'
import {
  parseApiErrorText,
  parseHealthText,
  parseJobText,
  parseMapBoundsText,
  parseMapPageText,
  parseVariantsText,
} from './parse-response'
import type { Health, Job, MapBounds, MapPage, MapQuery, VariantSummary } from '../model/api'
import type { ObjectId } from '../model/object-id'
import { formatObjectId } from '../model/object-id'

type ResponseParser<T> = (text: string) => T

export class HttpHeatNetworkApi implements HeatNetworkApi {
  readonly #baseUrl: string

  constructor(baseUrl = '') {
    this.#baseUrl = baseUrl.replace(/\/$/, '')
  }

  health(signal?: AbortSignal): Promise<Health> {
    return this.#request('/api/health', { signal }, parseHealthText)
  }

  createJob(file: File, mode: '2d', signal?: AbortSignal): Promise<Job> {
    const body = new FormData()
    body.append('file', file, file.name)
    body.append('mode', mode)
    return this.#request('/api/jobs', { method: 'POST', body, signal }, parseJobText)
  }

  getJob(jobId: string, signal?: AbortSignal): Promise<Job> {
    return this.#request(`/api/jobs/${encodeURIComponent(jobId)}`, { signal }, parseJobText)
  }

  listVariants(jobId: string, signal?: AbortSignal): Promise<readonly VariantSummary[]> {
    return this.#request(
      `/api/jobs/${encodeURIComponent(jobId)}/variants`,
      { signal },
      parseVariantsText,
    )
  }

  getMapBounds(jobId: string, variantId: ObjectId, signal?: AbortSignal): Promise<MapBounds> {
    const parameters = new URLSearchParams({ variantId: formatObjectId(variantId) })
    return this.#request(
      `/api/jobs/${encodeURIComponent(jobId)}/map/bounds?${parameters.toString()}`,
      { signal },
      parseMapBoundsText,
    )
  }

  getMapPage(jobId: string, query: MapQuery, signal?: AbortSignal): Promise<MapPage> {
    const parameters = new URLSearchParams({
      layer: query.layer,
      bbox: query.bbox.join(','),
      limit: String(query.limit ?? 1000),
    })
    if (query.variantId) parameters.set('variantId', formatObjectId(query.variantId))
    if (query.cursor) parameters.set('cursor', query.cursor)
    return this.#request(
      `/api/jobs/${encodeURIComponent(jobId)}/map?${parameters.toString()}`,
      { signal },
      parseMapPageText,
    )
  }

  getResultUrl(jobId: string): string {
    return this.#url(`/api/jobs/${encodeURIComponent(jobId)}/result`)
  }

  async #request<T>(path: string, init: RequestInit, parser: ResponseParser<T>): Promise<T> {
    const response = await fetch(this.#url(path), init)
    const text = await response.text()
    if (!response.ok) {
      let code = `HTTP_${response.status}`
      let message = response.statusText || 'Сервер вернул ошибку'
      try {
        const error = parseApiErrorText(text)
        code = error.code
        message = error.message
      } catch {
        // A malformed error response still remains an HTTP error, never fixture data.
      }
      throw new ApiClientError(
        response.status,
        code,
        message,
        response.headers.get('Retry-After') ?? undefined,
      )
    }
    return parser(text)
  }

  #url(path: string): string {
    return `${this.#baseUrl}${path}`
  }
}
