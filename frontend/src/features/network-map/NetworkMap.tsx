import { useEffect, useMemo, useRef, useState } from 'react'

import type { HeatNetworkApi } from '../../shared/api/contracts'
import type { MapQuery } from '../../shared/model/api'
import type { ObjectId } from '../../shared/model/object-id'
import { objectIdKey } from '../../shared/model/object-id'
import { Alert } from '../../shared/ui/Alert'
import { MapLegend } from './MapLegend'
import { expandBoundsForMapQuery, type ViewportBbox } from './map-query'
import { createMapLibreAdapter, type NetworkMapAdapter } from './maplibre-adapter'
import { useMapBounds, useMapFeatureCollections } from './use-map-features'

const INITIAL_BBOX: ViewportBbox = { minLon: 37.4, minLat: 55.65, maxLon: 37.43, maxLat: 55.68 }

export interface NetworkMapLayer {
  readonly layer: MapQuery['layer']
  readonly variantId?: ObjectId
}

interface NetworkMapProps {
  readonly api: HeatNetworkApi
  readonly jobId: string
  readonly layers: readonly NetworkMapLayer[]
  readonly onViewportChange?: (bbox: ViewportBbox) => void
  readonly onUnavailable?: (reason: 'GPU_UNAVAILABLE') => void
}

export function NetworkMap({ api, jobId, layers, onViewportChange, onUnavailable }: NetworkMapProps) {
  const container = useRef<HTMLDivElement>(null)
  const adapter = useRef<NetworkMapAdapter | null>(null)
  const callback = useRef(onViewportChange)
  const unavailableCallback = useRef(onUnavailable)
  const [bbox, setBbox] = useState(INITIAL_BBOX)
  const [fittedBoundsKey, setFittedBoundsKey] = useState<string | null>(null)
  const [mapError, setMapError] = useState<string | null>(null)
  const selectedVariantId = layers.find((layer) => layer.layer === 'result')?.variantId
  const bounds = useMapBounds(api, jobId, selectedVariantId)
  const boundsKey = selectedVariantId ? objectIdKey(selectedVariantId) : null
  const needsFit = bounds.data !== undefined && bounds.data !== null && boundsKey !== fittedBoundsKey
  const queryBbox: MapQuery['bbox'] = needsFit && bounds.data !== null && bounds.data !== undefined
    ? expandBoundsForMapQuery(bounds.data)
    : [bbox.minLon, bbox.minLat, bbox.maxLon, bbox.maxLat]
  const mapEnabled = selectedVariantId === undefined || (bounds.data !== undefined && bounds.data !== null)
  const queries: readonly MapQuery[] = layers.map(({ layer, variantId }) => ({
    layer, variantId, bbox: queryBbox, limit: 1000,
  }))
  const featureQueries = useMapFeatureCollections(api, jobId, queries, mapEnabled)
  const features = useMemo(() => ({
    type: 'FeatureCollection' as const,
    features: featureQueries.flatMap((featureQuery) => featureQuery.data?.features ?? []),
    nextCursor: null,
  }), [featureQueries])
  const hasFeatureError = featureQueries.some((featureQuery) => featureQuery.isError)

  useEffect(() => { callback.current = onViewportChange }, [onViewportChange])
  useEffect(() => { unavailableCallback.current = onUnavailable }, [onUnavailable])
  useEffect(() => {
    if (!container.current) return
    let timer: number | undefined
    let active = true
    try {
      adapter.current = createMapLibreAdapter(container.current, (nextBbox) => {
        window.clearTimeout(timer)
        timer = window.setTimeout(() => {
          setBbox(nextBbox)
          callback.current?.(nextBbox)
        }, 180)
      })
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Не удалось запустить карту'
      queueMicrotask(() => {
        if (active) {
          setMapError(message)
          if (error instanceof Error && error.name === 'MapUnsupportedError') {
            unavailableCallback.current?.('GPU_UNAVAILABLE')
          }
        }
      })
    }
    return () => {
      active = false
      window.clearTimeout(timer)
      adapter.current?.destroy()
      adapter.current = null
    }
  }, [])
  useEffect(() => {
    if (bounds.data && boundsKey !== fittedBoundsKey) {
      adapter.current?.fitBounds(bounds.data)
      const [minLon, minLat, maxLon, maxLat] = expandBoundsForMapQuery(bounds.data)
      setBbox({ minLon, minLat, maxLon, maxLat })
      setFittedBoundsKey(boundsKey)
    }
  }, [bounds.data, boundsKey, fittedBoundsKey])
  useEffect(() => {
    adapter.current?.setFeatureCollection(features)
  }, [features])

  return (
    <div className="network-map-shell">
      <div className="network-map" ref={container} aria-label="Карта тепловой сети" />
      {mapError && <div className="map-overlay"><Alert>{mapError}. Данные доступны в списке вариантов.</Alert></div>}
      {bounds.isError && <div className="map-overlay"><Alert>Не удалось определить границы карты.</Alert></div>}
      {hasFeatureError && <div className="map-overlay"><Alert>Не удалось загрузить объекты карты.</Alert></div>}
      <MapLegend setLayerGroupVisibility={(group, visible) => adapter.current?.setLayerGroupVisibility(group, visible)} />
    </div>
  )
}
