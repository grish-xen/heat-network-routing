import type { MapObjectType } from '../../shared/model/api'
import type { ObjectId } from '../../shared/model/object-id'
import { objectIdKey } from '../../shared/model/object-id'

export type LayerGroup = 'network' | 'nodes' | 'connections' | 'restrictions'
export type LayerKind = 'line' | 'fill' | 'circle' | 'symbol'

export interface MapLayerDefinition {
  readonly id: string
  readonly group: LayerGroup
  readonly objectType: MapObjectType
  readonly kind: LayerKind
  readonly variant: 'any' | 'existing' | 'calculated'
  readonly paint: Readonly<Record<string, string | number | readonly number[]>>
}

export const MAP_LEGEND_ITEMS: readonly {
  readonly group: LayerGroup
  readonly label: string
  readonly color: string
}[] = [
  { group: 'network', label: 'Тепловая сеть', color: '#46657a' },
  { group: 'nodes', label: 'Источники и камеры', color: '#157d68' },
  { group: 'connections', label: 'Точки подключения', color: '#e9582f' },
  { group: 'restrictions', label: 'Ограничения', color: '#8f6ca8' },
]

export const MAP_LAYER_DEFINITIONS: readonly MapLayerDefinition[] = [
  {
    id: 'existing-network', group: 'network', objectType: 'heat_network', kind: 'line', variant: 'existing',
    paint: { lineColor: '#46657a', lineWidth: 2.5 },
  },
  {
    id: 'calculated-network', group: 'network', objectType: 'heat_network', kind: 'line', variant: 'calculated',
    paint: { lineColor: '#e9582f', lineWidth: 4, lineDasharray: [1.5, 1] },
  },
  {
    id: 'sources', group: 'nodes', objectType: 'source', kind: 'circle', variant: 'any',
    paint: { circleColor: '#157d68', circleRadius: 8, circleStrokeColor: '#ffffff', circleStrokeWidth: 2 },
  },
  {
    id: 'chambers', group: 'nodes', objectType: 'heat_chamber', kind: 'circle', variant: 'any',
    paint: { circleColor: '#13231f', circleRadius: 6, circleStrokeColor: '#ffffff', circleStrokeWidth: 2 },
  },
  {
    id: 'technical-nodes', group: 'nodes', objectType: 'technical_node', kind: 'symbol', variant: 'any',
    paint: { textColor: '#13231f', textHaloColor: '#ffffff', textHaloWidth: 1.5 },
  },
  {
    id: 'connection-points', group: 'connections', objectType: 'oks_connection_point', kind: 'circle', variant: 'any',
    paint: { circleColor: '#e9582f', circleRadius: 5, circleStrokeColor: '#ffffff', circleStrokeWidth: 2 },
  },
  {
    id: 'restrictions-fill', group: 'restrictions', objectType: 'restriction', kind: 'fill', variant: 'any',
    paint: { fillColor: '#8f6ca8', fillOpacity: 0.22 },
  },
  {
    id: 'restrictions-outline', group: 'restrictions', objectType: 'restriction', kind: 'line', variant: 'any',
    paint: { lineColor: '#8f6ca8', lineWidth: 2, lineDasharray: [2, 1] },
  },
]

export const mapFeatureId = (id: ObjectId): string => objectIdKey(id)
