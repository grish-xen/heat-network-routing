import type { ObjectId } from './object-id'

export interface Health {
  readonly status: string
  readonly contractVersion: string
  readonly implementation: string
}

export type JobStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'
export type JobStage =
  | 'QUEUED'
  | 'VALIDATING'
  | 'ROUTING'
  | 'CALCULATING'
  | 'EXPORTING'
  | 'DONE'
  | 'FAILED'

export interface DiagnosticDetail {
  readonly inputObjectId?: ObjectId
  readonly candidateId?: ObjectId
  readonly segmentId?: ObjectId
  readonly [key: string]: unknown
}

export interface ApiErrorBody {
  readonly code: string
  readonly message: string
  readonly details?: readonly DiagnosticDetail[]
}

export interface Job {
  readonly jobId: string
  readonly status: JobStatus
  readonly stage: JobStage
  readonly mode: '2d' | 'depth'
  readonly diagnostics: readonly ApiErrorBody[]
}

export interface VariantSummary {
  readonly id: ObjectId
  readonly objectType: 'variant_summary'
  readonly variantId: ObjectId
  readonly rank: number
  readonly constructionCost: number
  readonly chamberConstructionCost: number
  readonly existingChamberTieInCount: number
  readonly existingChamberTieInCost: number
  readonly unconnectedPenalty: number
  readonly calculatedCost: number
  readonly newNetworkLength: number
  readonly score: number
  readonly unconnectedOksIds: readonly ObjectId[]
}

export type Position = readonly [number, number]
export type Geometry =
  | { readonly type: 'Point'; readonly coordinates: Position }
  | { readonly type: 'LineString'; readonly coordinates: readonly Position[] }
  | { readonly type: 'MultiLineString'; readonly coordinates: readonly (readonly Position[])[] }
  | { readonly type: 'Polygon'; readonly coordinates: readonly (readonly Position[])[] }
  | {
      readonly type: 'MultiPolygon'
      readonly coordinates: readonly (readonly (readonly Position[])[])[]
    }

export type MapObjectType =
  | 'source'
  | 'heat_network'
  | 'heat_chamber'
  | 'oks_connection_point'
  | 'restriction'
  | 'technical_node'

export interface MapFeatureProperties {
  readonly id: ObjectId
  readonly objectType: MapObjectType
  readonly variantId?: ObjectId
  readonly startNodeId?: ObjectId
  readonly endNodeId?: ObjectId
  readonly diameter?: number
  readonly flowTph?: number
  readonly depthStart?: number
  readonly depthEnd?: number
  readonly restrictionType?: string
  readonly cost?: number
  readonly length?: number
  readonly layingMethod?: 'base' | 'special'
}

export interface MapFeature {
  readonly type: 'Feature'
  readonly geometry: Geometry
  readonly properties: MapFeatureProperties
}

export interface MapPage {
  readonly type: 'FeatureCollection'
  readonly features: readonly MapFeature[]
  readonly nextCursor: string | null
}

export type MapBounds = readonly [number, number, number, number] | null

export interface MapQuery {
  readonly layer: 'input' | 'result'
  readonly variantId?: ObjectId
  readonly bbox: readonly [number, number, number, number]
  readonly limit?: number
  readonly cursor?: string
}
