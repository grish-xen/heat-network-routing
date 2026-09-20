import { useEffect, useRef, useState } from 'react'

import type { HeatNetworkApi } from '../../shared/api/contracts'
import type { MapQuery } from '../../shared/model/api'
import type { ObjectId } from '../../shared/model/object-id'
import { Alert } from '../../shared/ui/Alert'
import { MapLegend } from './MapLegend'
import type { ViewportBbox } from './map-query'
import { createMapLibreAdapter, type NetworkMapAdapter } from './maplibre-adapter'
import { useMapFeatures } from './use-map-features'

const INITIAL_BBOX: ViewportBbox = { minLon: 37.4, minLat: 55.65, maxLon: 37.43, maxLat: 55.68 }

interface NetworkMapProps {
  readonly api: HeatNetworkApi
  readonly jobId: string
  readonly layer: MapQuery['layer']
  readonly variantId?: ObjectId
  readonly onViewportChange?: (bbox: ViewportBbox) => void
}

export function NetworkMap({ api, jobId, layer, variantId, onViewportChange }: NetworkMapProps) {
  const container = useRef<HTMLDivElement>(null)
  const adapter = useRef<NetworkMapAdapter | null>(null)
  const callback = useRef(onViewportChange)
  const [bbox, setBbox] = useState(INITIAL_BBOX)
  const [mapError, setMapError] = useState<string | null>(null)
  const query: MapQuery = {
    layer, variantId, bbox: [bbox.minLon, bbox.minLat, bbox.maxLon, bbox.maxLat], limit: 1000,
  }
  const features = useMapFeatures(api, jobId, query)

  useEffect(() => { callback.current = onViewportChange }, [onViewportChange])
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
        if (active) setMapError(message)
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
    if (features.data) adapter.current?.setFeatureCollection(features.data)
  }, [features.data])

  return (
    <div className="network-map-shell">
      <div className="network-map" ref={container} aria-label="Карта тепловой сети" />
      {mapError && <div className="map-overlay"><Alert>{mapError}. Данные доступны в списке вариантов.</Alert></div>}
      {features.isError && <div className="map-overlay"><Alert>Не удалось загрузить объекты карты.</Alert></div>}
      <MapLegend setLayerGroupVisibility={(group, visible) => adapter.current?.setLayerGroupVisibility(group, visible)} />
    </div>
  )
}
