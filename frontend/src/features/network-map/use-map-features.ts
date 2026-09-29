import { useQueries, useQuery } from '@tanstack/react-query'

import type { HeatNetworkApi } from '../../shared/api/contracts'
import type { MapPage, MapQuery } from '../../shared/model/api'
import type { ObjectId } from '../../shared/model/object-id'
import { objectIdKey } from '../../shared/model/object-id'
import { mapQueryKey, serializeBbox } from './map-query'

export const MAX_DEPTH_SCENE_FEATURES = 2_000

interface MapFeatureCollectionOptions {
  readonly maxFeatures?: number
  readonly keepPreviousData?: boolean
}

function abortIfNeeded(signal: AbortSignal): void {
  if (signal.aborted) throw new DOMException('Запрос карты отменён', 'AbortError')
}

function featurePartCount(feature: MapPage['features'][number]): number {
  const { geometry } = feature
  const lineParts = (points: readonly unknown[]) => Math.max(1, points.length - 1)
  switch (geometry.type) {
    case 'LineString': return lineParts(geometry.coordinates)
    case 'MultiLineString': return geometry.coordinates.reduce((sum, line) => sum + lineParts(line), 0)
    case 'Polygon': return geometry.coordinates.reduce((sum, ring) => sum + lineParts(ring), 0)
    case 'MultiPolygon': return geometry.coordinates.reduce((sum, polygon) => sum + polygon.reduce((ringSum, ring) => ringSum + lineParts(ring), 0), 0)
    default: return 1
  }
}

export async function loadMapFeatures(
  api: HeatNetworkApi,
  jobId: string,
  query: MapQuery,
  signal: AbortSignal,
  maxFeatures?: number,
): Promise<MapPage> {
  const [minLon, minLat, maxLon, maxLat] = query.bbox
  serializeBbox({ minLon, minLat, maxLon, maxLat })
  const limit = query.limit ?? 1000
  if (!Number.isInteger(limit) || limit < 1 || limit > 5000) {
    throw new Error('Размер страницы карты должен быть от 1 до 5000')
  }
  if (maxFeatures !== undefined && (!Number.isInteger(maxFeatures) || maxFeatures < 1)) {
    throw new Error('Предел объектов карты должен быть положительным целым числом')
  }
  const features: MapPage['features'][number][] = []
  let featureParts = 0
  const seenCursors = new Set<string>()
  let cursor: string | undefined
  do {
    abortIfNeeded(signal)
    const page = await api.getMapPage(jobId, { ...query, cursor }, signal)
    abortIfNeeded(signal)
    const pageParts = page.features.reduce((sum, feature) => sum + featurePartCount(feature), 0)
    if (maxFeatures !== undefined && featureParts + pageParts > maxFeatures) {
      throw new Error(`Превышен предел объектов для глубинной сцены: ${maxFeatures}`)
    }
    features.push(...page.features)
    featureParts += pageParts
    cursor = page.nextCursor ?? undefined
    if (cursor) {
      if (seenCursors.has(cursor)) throw new Error('Сервер вернул цикл курсоров карты')
      seenCursors.add(cursor)
    }
  } while (cursor)
  return { type: 'FeatureCollection', features, nextCursor: null }
}

export function useMapFeatures(api: HeatNetworkApi, jobId: string, query: MapQuery) {
  return useQuery({
    queryKey: mapQueryKey(jobId, query),
    queryFn: ({ signal }) => loadMapFeatures(api, jobId, query, signal),
    placeholderData: (previous) => previous,
  })
}

export function useMapBounds(api: HeatNetworkApi, jobId: string, variantId: ObjectId | undefined) {
  return useQuery({
    queryKey: ['map-bounds', jobId, variantId ? objectIdKey(variantId) : null],
    queryFn: ({ signal }) => {
      if (!variantId) throw new Error('Для границ карты нужен вариант')
      return api.getMapBounds(jobId, variantId, signal)
    },
    enabled: variantId !== undefined,
    staleTime: Number.POSITIVE_INFINITY,
  })
}

export function useMapFeatureCollections(
  api: HeatNetworkApi,
  jobId: string,
  queries: readonly MapQuery[],
  enabled = true,
  options: MapFeatureCollectionOptions = {},
) {
  return useQueries({
    queries: queries.map((query) => ({
      queryKey: mapQueryKey(jobId, query),
      queryFn: ({ signal }: { signal: AbortSignal }) => loadMapFeatures(api, jobId, query, signal, options.maxFeatures),
      ...(options.keepPreviousData === false
        ? {}
        : { placeholderData: (previous: MapPage | undefined) => previous }),
      enabled,
    })),
  })
}
