import { useQuery } from '@tanstack/react-query'

import type { HeatNetworkApi } from '../../shared/api/contracts'
import type { MapPage, MapQuery } from '../../shared/model/api'
import { mapQueryKey } from './map-query'

function abortIfNeeded(signal: AbortSignal): void {
  if (signal.aborted) throw new DOMException('Запрос карты отменён', 'AbortError')
}

export async function loadMapFeatures(
  api: HeatNetworkApi,
  jobId: string,
  query: MapQuery,
  signal: AbortSignal,
): Promise<MapPage> {
  const limit = query.limit ?? 1000
  if (!Number.isInteger(limit) || limit < 1 || limit > 5000) {
    throw new Error('Размер страницы карты должен быть от 1 до 5000')
  }
  const features: MapPage['features'][number][] = []
  const seenCursors = new Set<string>()
  let cursor: string | undefined
  do {
    abortIfNeeded(signal)
    const page = await api.getMapPage(jobId, { ...query, cursor }, signal)
    abortIfNeeded(signal)
    features.push(...page.features)
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
