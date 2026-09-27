import {
  GPUInitializationError,
  Map as MapLibreMap,
  NavigationControl,
  Popup,
  ScaleControl,
  setWorkerUrl,
  type GeoJSONSource,
  type MapLayerMouseEvent,
} from 'maplibre-gl'
import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url'
import 'maplibre-gl/dist/maplibre-gl.css'

import type { MapPage } from '../../shared/model/api'
import { objectIdKey } from '../../shared/model/object-id'
import type { ViewportBbox } from './map-query'
import {
  mapSourceFeatureId,
  MAP_LAYER_DEFINITIONS,
  type LayerGroup,
  type MapLayerDefinition,
} from './map-style'

setWorkerUrl(workerUrl)

const SOURCE_ID = 'network-features'

export interface NetworkMapAdapter {
  setFeatureCollection(page: MapPage): void
  setLayerGroupVisibility(group: LayerGroup, visible: boolean): void
  destroy(): void
}

export class MapUnsupportedError extends Error {
  constructor(cause: unknown) {
    super('Для карты требуется поддержка WebGL 2', { cause })
    this.name = 'MapUnsupportedError'
  }
}

function filterFor(definition: MapLayerDefinition): unknown[] {
  const objectFilter: unknown[] = ['==', ['get', 'object_type'], definition.objectType]
  if (definition.variant === 'existing') return ['all', objectFilter, ['!', ['has', 'variant_id']]]
  if (definition.variant === 'calculated') return ['all', objectFilter, ['has', 'variant_id']]
  return objectFilter
}

function paintFor(definition: MapLayerDefinition): Record<string, unknown> {
  const keys: Record<string, string> = {
    lineColor: 'line-color', lineWidth: 'line-width', lineDasharray: 'line-dasharray',
    circleColor: 'circle-color', circleRadius: 'circle-radius', circleStrokeColor: 'circle-stroke-color',
    circleStrokeWidth: 'circle-stroke-width', fillColor: 'fill-color', fillOpacity: 'fill-opacity',
    textColor: 'text-color', textHaloColor: 'text-halo-color', textHaloWidth: 'text-halo-width',
  }
  return Object.fromEntries(Object.entries(definition.paint).map(([key, value]) => [keys[key] ?? key, value]))
}

function toGeoJson(page: MapPage) {
  return {
    type: 'FeatureCollection' as const,
    features: page.features.map((feature) => ({
      type: 'Feature' as const,
      id: mapSourceFeatureId(feature.properties),
      geometry: feature.geometry,
      properties: {
        id: objectIdKey(feature.properties.id),
        object_type: feature.properties.objectType,
        ...(feature.properties.variantId ? { variant_id: objectIdKey(feature.properties.variantId) } : {}),
        ...(feature.properties.diameter === undefined ? {} : { diameter: feature.properties.diameter }),
        ...(feature.properties.flowTph === undefined ? {} : { flow_tph: feature.properties.flowTph }),
        ...(feature.properties.cost === undefined ? {} : { cost: feature.properties.cost }),
        ...(feature.properties.length === undefined ? {} : { length: feature.properties.length }),
      },
    })),
  }
}

export function createMapLibreAdapter(
  container: HTMLElement,
  onViewportChange: (bbox: ViewportBbox) => void,
): NetworkMapAdapter {
  let map: MapLibreMap
  try {
    map = new MapLibreMap({
      container,
      center: [37.411, 55.665],
      zoom: 14,
      attributionControl: false,
      style: { version: 8, sources: {}, layers: [{ id: 'background', type: 'background', paint: { 'background-color': '#dfe7e1' } }] },
    })
  } catch (error) {
    if (error instanceof GPUInitializationError) throw new MapUnsupportedError(error)
    throw error
  }
  let pending: MapPage = { type: 'FeatureCollection', features: [], nextCursor: null }
  let ready = false
  const visibility = new Map<LayerGroup, boolean>()

  map.addControl(new NavigationControl({ showCompass: false }), 'top-right')
  map.addControl(new ScaleControl({ unit: 'metric' }), 'bottom-right')
  map.on('load', () => {
    map.addSource(SOURCE_ID, { type: 'geojson', data: toGeoJson(pending) })
    for (const definition of MAP_LAYER_DEFINITIONS) {
      map.addLayer({
        id: definition.id,
        type: definition.kind,
        source: SOURCE_ID,
        filter: filterFor(definition) as never,
        ...(definition.kind === 'symbol'
          ? { layout: { 'text-field': '◆', 'text-size': 14, 'text-allow-overlap': true } }
          : {}),
        paint: paintFor(definition),
      } as never)
      if (visibility.get(definition.group) === false) {
        map.setLayoutProperty(definition.id, 'visibility', 'none')
      }
    }
    ready = true
  })
  map.on('moveend', () => {
    const bounds = map.getBounds()
    onViewportChange({
      minLon: Math.max(-180, bounds.getWest()), minLat: Math.max(-90, bounds.getSouth()),
      maxLon: Math.min(180, bounds.getEast()), maxLat: Math.min(90, bounds.getNorth()),
    })
  })
  for (const definition of MAP_LAYER_DEFINITIONS) {
    map.on('click', definition.id, (event: MapLayerMouseEvent) => {
      const properties = event.features?.[0]?.properties
      if (!properties) return
      const content = document.createElement('div')
      const title = document.createElement('strong')
      title.textContent = String(properties.object_type ?? 'Объект сети')
      const id = document.createElement('div')
      id.textContent = `ID: ${String(properties.id ?? '—')}`
      content.append(title, id)
      new Popup({ closeButton: true }).setLngLat(event.lngLat).setDOMContent(content).addTo(map)
    })
  }

  return {
    setFeatureCollection(page) {
      pending = page
      if (ready) (map.getSource(SOURCE_ID) as GeoJSONSource).setData(toGeoJson(page) as never)
    },
    setLayerGroupVisibility(group, visible) {
      visibility.set(group, visible)
      if (!ready) return
      for (const layer of MAP_LAYER_DEFINITIONS.filter((item) => item.group === group)) {
        map.setLayoutProperty(layer.id, 'visibility', visible ? 'visible' : 'none')
      }
    },
    destroy() {
      map.remove()
    },
  }
}
