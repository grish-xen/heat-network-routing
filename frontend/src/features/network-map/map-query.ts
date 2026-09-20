import type { MapQuery } from '../../shared/model/api'
import { objectIdKey } from '../../shared/model/object-id'

export interface ViewportBbox {
  readonly minLon: number
  readonly minLat: number
  readonly maxLon: number
  readonly maxLat: number
}

function validateBbox(bbox: ViewportBbox): void {
  const values = [bbox.minLon, bbox.minLat, bbox.maxLon, bbox.maxLat]
  if (!values.every(Number.isFinite)) throw new Error('Границы карты должны быть конечными числами')
  if (bbox.minLon < -180 || bbox.maxLon > 180) throw new Error('Долгота должна быть от −180 до 180')
  if (bbox.minLat < -90 || bbox.maxLat > 90) throw new Error('Широта должна быть от −90 до 90')
  if (bbox.minLon >= bbox.maxLon || bbox.minLat >= bbox.maxLat) throw new Error('Границы карты должны возрастать')
}

export function serializeBbox(bbox: ViewportBbox): string {
  validateBbox(bbox)
  return [bbox.minLon, bbox.minLat, bbox.maxLon, bbox.maxLat].join(',')
}

export function toBboxTuple(bbox: ViewportBbox): MapQuery['bbox'] {
  validateBbox(bbox)
  return [bbox.minLon, bbox.minLat, bbox.maxLon, bbox.maxLat]
}

export function mapQueryKey(jobId: string, query: MapQuery) {
  return [
    'map-features',
    jobId,
    query.layer,
    query.variantId ? objectIdKey(query.variantId) : null,
    query.bbox.join(','),
    query.limit ?? 1000,
  ] as const
}
