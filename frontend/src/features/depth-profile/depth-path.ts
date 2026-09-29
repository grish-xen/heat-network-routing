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

export interface DepthSceneOrigin { readonly x: number; readonly y: number }
export interface DepthSceneCommunication {
  readonly kind: 'gas_pipeline' | 'power_cable' | 'heat_network'
  readonly topDepthM: number
  readonly widthM: number
  readonly heightM: number
  readonly start: DepthScenePoint
  readonly end: DepthScenePoint
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

export function selectDepthPath(paths: readonly DepthPath[], endpointKey: string | null): DepthPath | undefined {
  return paths.find((path) => objectIdKey(path.endNodeId) === endpointKey) ?? paths[0]
}

function metres(position: Position): readonly [number, number] {
  const point = proj4('EPSG:4326', UTM_37N, [position[0], position[1]])
  return [point[0]!, point[1]!]
}

export function sceneOrigin(path: DepthPath): DepthSceneOrigin | undefined {
  const geometry = path.segments[0]?.feature.geometry
  if (!geometry || geometry.type !== 'LineString') return undefined
  const [x, y] = metres(geometry.coordinates[0]!)
  return { x, y }
}

export function toSceneCommunications(features: readonly MapFeature[], origin: DepthSceneOrigin): readonly DepthSceneCommunication[] {
  const rules = new Map(visualizationRules.crossings.map((rule) => [rule.type, rule]))
  return features.flatMap((feature) => {
    const geometry = feature.geometry
    if (geometry.type !== 'LineString') return []
    const kind = feature.properties.objectType === 'heat_network' ? 'heat_network' : feature.properties.restrictionType
    if (kind !== 'gas_pipeline' && kind !== 'power_cable' && kind !== 'heat_network') return []
    const rule = rules.get(kind)
    if (!rule || !('existingTopDepthM' in rule)) return []
    const topDepthM = rule.existingTopDepthM
    if (topDepthM === undefined) return []
    const dimensions = kind === 'heat_network'
      ? visualizationRules.diameters.find((item) => item.diameterMm === feature.properties.diameter)
      : { widthM: rule.profileWidthM, heightM: rule.profileHeightM }
    if (!dimensions || dimensions.widthM === undefined || dimensions.heightM === undefined) return []
    const points = geometry.coordinates.map(metres)
    return points.slice(1).map((end, index) => {
      const start = points[index]!
      const z = -topDepthM * VERTICAL_EXAGGERATION
      return { kind, topDepthM, widthM: dimensions.widthM!, heightM: dimensions.heightM!,
        start: { x: start[0] - origin.x, y: start[1] - origin.y, z }, end: { x: end[0] - origin.x, y: end[1] - origin.y, z } }
    })
  })
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
  const origin = sceneOrigin(path)!
  let distanceStartM = 0
  return path.segments.flatMap((segment) => {
    const geometry = segment.feature.geometry
    if (geometry.type !== 'LineString') throw new Error('Маршрут глубины должен состоять из линий')
    const projected = geometry.coordinates.map(metres)
    const catalog = visualizationRules.diameters.find((item) => item.diameterMm === segment.feature.properties.diameter)
    if (!catalog) throw new Error(`Нет габарита для ДУ ${segment.feature.properties.diameter ?? '—'}`)
    const length = distance(projected)
    const depthStart = segment.feature.properties.depthStart!
    const depthEnd = segment.feature.properties.depthEnd!
    let edgeDistanceM = 0
    return projected.slice(1).map((end, index) => {
      const start = projected[index]!
      const pieceLength = Math.hypot(end[0] - start[0], end[1] - start[1])
      const ratioStart = edgeDistanceM / length
      edgeDistanceM += pieceLength
      const ratioEnd = edgeDistanceM / length
      const pieceDepthStart = depthStart + (depthEnd - depthStart) * ratioStart
      const pieceDepthEnd = depthStart + (depthEnd - depthStart) * ratioEnd
      const sceneSegment: DepthSceneSegment = {
        feature: segment.feature,
        start: { x: start[0] - origin.x, y: start[1] - origin.y, z: -pieceDepthStart * VERTICAL_EXAGGERATION },
        end: { x: end[0] - origin.x, y: end[1] - origin.y, z: -pieceDepthEnd * VERTICAL_EXAGGERATION },
        depthStart: pieceDepthStart,
        depthEnd: pieceDepthEnd,
        widthM: catalog.widthM,
        heightM: catalog.heightM,
        distanceStartM,
        distanceEndM: distanceStartM + pieceLength,
      }
      distanceStartM += pieceLength
      return sceneSegment
    })
  })
}

export { VERTICAL_EXAGGERATION }
