import proj4 from 'proj4'

import visualizationRules from '../../../../contracts/visualization-rules.v1.json'
import type { MapFeature, Position } from '../../shared/model/api'
import type { ObjectId } from '../../shared/model/object-id'
import { objectIdKey } from '../../shared/model/object-id'

const VERTICAL_EXAGGERATION = 4
const UTM_37N = '+proj=utm +zone=37 +datum=WGS84 +units=m +no_defs +type=crs'

export interface DepthPathSegment {
  readonly feature: MapFeature
  readonly startNodeId: ObjectId
  readonly endNodeId: ObjectId
}

export interface DepthPath {
  readonly endNodeId: ObjectId
  readonly segments: readonly DepthPathSegment[]
}

export interface DepthScenePoint {
  readonly x: number
  readonly y: number
  readonly z: number
}

export interface DepthSceneSegment {
  readonly feature: MapFeature
  readonly start: DepthScenePoint
  readonly end: DepthScenePoint
  readonly depthStart: number
  readonly depthEnd: number
  readonly widthM: number
  readonly heightM: number
  readonly distanceStartM: number
  readonly distanceEndM: number
}

function isDepthEdge(feature: MapFeature): feature is MapFeature & { readonly geometry: { readonly type: 'LineString'; readonly coordinates: readonly Position[] } } {
  const { properties } = feature
  return feature.geometry.type === 'LineString' &&
    properties.objectType === 'heat_network' &&
    properties.startNodeId !== undefined &&
    properties.endNodeId !== undefined &&
    properties.depthStart !== undefined &&
    properties.depthEnd !== undefined &&
    properties.diameter !== undefined
}

function edgeFor(feature: MapFeature): DepthPathSegment | null {
  if (!isDepthEdge(feature)) return null
  return { feature, startNodeId: feature.properties.startNodeId!, endNodeId: feature.properties.endNodeId! }
}

export function buildDepthPaths(features: readonly MapFeature[]): readonly DepthPath[] {
  const edges = features.map(edgeFor).filter((edge): edge is DepthPathSegment => edge !== null)
  const endNodes = new Set(edges.map((edge) => objectIdKey(edge.endNodeId)))
  const roots = edges.filter((edge) => !endNodes.has(objectIdKey(edge.startNodeId)))
  const byStart = new Map<string, DepthPathSegment[]>()
  for (const edge of edges) {
    const key = objectIdKey(edge.startNodeId)
    byStart.set(key, [...(byStart.get(key) ?? []), edge])
  }
  const paths: DepthPath[] = []
  const visit = (edge: DepthPathSegment, path: readonly DepthPathSegment[], seen: ReadonlySet<string>) => {
    const nextPath = [...path, edge]
    const endKey = objectIdKey(edge.endNodeId)
    const next = (byStart.get(endKey) ?? []).filter((candidate) => !seen.has(objectIdKey(candidate.feature.properties.id)))
    if (next.length === 0) {
      paths.push({ endNodeId: edge.endNodeId, segments: nextPath })
      return
    }
    const nextSeen = new Set(seen).add(objectIdKey(edge.feature.properties.id))
    for (const candidate of next) visit(candidate, nextPath, nextSeen)
  }
  for (const root of roots) visit(root, [], new Set())
  return paths
}

function metres(position: Position): readonly [number, number] {
  const point = proj4('EPSG:4326', UTM_37N, [position[0], position[1]])
  return [point[0]!, point[1]!]
}

function distance(points: readonly (readonly [number, number])[]): number {
  return points.slice(1).reduce((sum, point, index) => {
    const previous = points[index]!
    return sum + Math.hypot(point[0] - previous[0], point[1] - previous[1])
  }, 0)
}

export function toMetricSegments(path: DepthPath): readonly DepthSceneSegment[] {
  const first = path.segments[0]?.feature.geometry
  if (!first || first.type !== 'LineString') return []
  const origin = metres(first.coordinates[0]!)
  let distanceStartM = 0
  return path.segments.map((segment) => {
    const geometry = segment.feature.geometry
    if (geometry.type !== 'LineString') throw new Error('Маршрут глубины должен состоять из линий')
    const projected = geometry.coordinates.map(metres)
    const catalog = visualizationRules.diameters.find((item) => item.diameterMm === segment.feature.properties.diameter)
    if (!catalog) throw new Error(`Нет габарита для ДУ ${segment.feature.properties.diameter ?? '—'}`)
    const length = distance(projected)
    const start = projected[0]!
    const end = projected.at(-1)!
    const depthStart = segment.feature.properties.depthStart!
    const depthEnd = segment.feature.properties.depthEnd!
    const sceneSegment: DepthSceneSegment = {
      feature: segment.feature,
      start: { x: start[0] - origin[0], y: start[1] - origin[1], z: -depthStart * VERTICAL_EXAGGERATION },
      end: { x: end[0] - origin[0], y: end[1] - origin[1], z: -depthEnd * VERTICAL_EXAGGERATION },
      depthStart,
      depthEnd,
      widthM: catalog.widthM,
      heightM: catalog.heightM,
      distanceStartM,
      distanceEndM: distanceStartM + length,
    }
    distanceStartM += length
    return sceneSegment
  })
}

export { VERTICAL_EXAGGERATION }
