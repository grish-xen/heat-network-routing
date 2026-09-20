import { isLosslessNumber, isSafeNumber, parse } from 'lossless-json'

import type {
  ApiErrorBody,
  DiagnosticDetail,
  Geometry,
  Health,
  Job,
  JobStage,
  JobStatus,
  MapFeature,
  MapObjectType,
  MapPage,
  Position,
  VariantSummary,
} from '../model/api'
import type { ObjectId } from '../model/object-id'

export class ResponseContractError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'ResponseContractError'
  }
}

type JsonObject = Record<string, unknown>

export function expectObject(value: unknown, path: string): JsonObject {
  if (typeof value === 'object' && value !== null && !Array.isArray(value) && !isLosslessNumber(value)) {
    return value as JsonObject
  }
  throw new ResponseContractError(`${path}: ожидается объект`)
}

export function expectString(value: unknown, path: string): string {
  if (typeof value === 'string') return value
  throw new ResponseContractError(`${path}: ожидается строка`)
}

export function expectArray(value: unknown, path: string): readonly unknown[] {
  if (Array.isArray(value)) return value
  throw new ResponseContractError(`${path}: ожидается массив`)
}

export function expectFiniteNumber(value: unknown, path: string): number {
  if (!isLosslessNumber(value)) {
    throw new ResponseContractError(`${path}: ожидается число`)
  }
  const text = value.toString()
  if (!isSafeNumber(text, { approx: false })) {
    throw new ResponseContractError(`${path}: число невозможно представить без потери точности`)
  }
  return Number(text)
}

export function expectInteger(value: unknown, path: string): number {
  if (!isLosslessNumber(value)) {
    throw new ResponseContractError(`${path}: ожидается целое число`)
  }
  const text = value.toString()
  const number = Number(text)
  if (!Number.isSafeInteger(number) || !/^-?(?:0|[1-9]\d*)$/.test(text)) {
    throw new ResponseContractError(`${path}: ожидается безопасное целое число`)
  }
  return number
}

export function expectObjectId(value: unknown, path: string): ObjectId {
  if (typeof value === 'string') return { kind: 'string', value }
  if (isLosslessNumber(value)) return { kind: 'number', value: value.toString() }
  throw new ResponseContractError(`${path}: ожидается строковый или числовой ID`)
}

function parseText(text: string): unknown {
  try {
    return parse(text)
  } catch (error) {
    throw new ResponseContractError(
      `Ответ сервера не является JSON: ${error instanceof Error ? error.message : 'неизвестная ошибка'}`,
    )
  }
}

function expectEnum<const T extends string>(value: unknown, values: readonly T[], path: string): T {
  const text = expectString(value, path)
  if ((values as readonly string[]).includes(text)) return text as T
  throw new ResponseContractError(`${path}: неизвестное значение «${text}»`)
}

function optionalString(value: unknown, path: string): string | undefined {
  return value === undefined ? undefined : expectString(value, path)
}

function optionalNumber(value: unknown, path: string): number | undefined {
  return value === undefined ? undefined : expectFiniteNumber(value, path)
}

function optionalInteger(value: unknown, path: string): number | undefined {
  return value === undefined ? undefined : expectInteger(value, path)
}

function parseDetail(value: unknown, path: string): DiagnosticDetail {
  const object = expectObject(value, path)
  const result: Record<string, unknown> = {}
  for (const [key, item] of Object.entries(object)) {
    if (key === 'inputObjectId' || key === 'candidateId' || key === 'segmentId') {
      result[key] = expectObjectId(item, `${path}.${key}`)
    } else if (isLosslessNumber(item)) {
      result[key] = expectFiniteNumber(item, `${path}.${key}`)
    } else {
      result[key] = item
    }
  }
  return result
}

function parseApiError(value: unknown, path: string): ApiErrorBody {
  const object = expectObject(value, path)
  const details = object.details
  return {
    code: expectString(object.code, `${path}.code`),
    message: expectString(object.message, `${path}.message`),
    ...(details === undefined
      ? {}
      : {
          details: expectArray(details, `${path}.details`).map((item, index) =>
            parseDetail(item, `${path}.details[${index}]`),
          ),
        }),
  }
}

export function parseHealthText(text: string): Health {
  const object = expectObject(parseText(text), '$')
  return {
    status: expectString(object.status, '$.status'),
    contractVersion: expectString(object.contractVersion, '$.contractVersion'),
    implementation: expectString(object.implementation, '$.implementation'),
  }
}

export function parseJobText(text: string): Job {
  const object = expectObject(parseText(text), '$')
  return {
    jobId: expectString(object.jobId, '$.jobId'),
    status: expectEnum<JobStatus>(object.status, ['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED'], '$.status'),
    stage: expectEnum<JobStage>(
      object.stage,
      ['QUEUED', 'VALIDATING', 'ROUTING', 'CALCULATING', 'EXPORTING', 'DONE', 'FAILED'],
      '$.stage',
    ),
    mode: expectEnum(object.mode, ['2d', 'depth'], '$.mode'),
    diagnostics: expectArray(object.diagnostics, '$.diagnostics').map((item, index) =>
      parseApiError(item, `$.diagnostics[${index}]`),
    ),
  }
}

export function parseApiErrorText(text: string): ApiErrorBody {
  return parseApiError(parseText(text), '$')
}

function parseVariant(value: unknown, path: string): VariantSummary {
  const object = expectObject(value, path)
  return {
    id: expectObjectId(object.id, `${path}.id`),
    objectType: expectEnum(object.object_type, ['variant_summary'], `${path}.object_type`),
    variantId: expectObjectId(object.variant_id, `${path}.variant_id`),
    rank: expectInteger(object.rank, `${path}.rank`),
    constructionCost: expectFiniteNumber(object.construction_cost, `${path}.construction_cost`),
    chamberConstructionCost: expectFiniteNumber(
      object.chamber_construction_cost,
      `${path}.chamber_construction_cost`,
    ),
    existingChamberTieInCount: expectInteger(
      object.existing_chamber_tie_in_count,
      `${path}.existing_chamber_tie_in_count`,
    ),
    existingChamberTieInCost: expectFiniteNumber(
      object.existing_chamber_tie_in_cost,
      `${path}.existing_chamber_tie_in_cost`,
    ),
    unconnectedPenalty: expectFiniteNumber(object.unconnected_penalty, `${path}.unconnected_penalty`),
    calculatedCost: expectFiniteNumber(object.calculated_cost, `${path}.calculated_cost`),
    newNetworkLength: expectFiniteNumber(object.new_network_length, `${path}.new_network_length`),
    score: expectFiniteNumber(object.score, `${path}.score`),
    unconnectedOksIds: expectArray(object.unconnected_oks_ids, `${path}.unconnected_oks_ids`).map(
      (item, index) => expectObjectId(item, `${path}.unconnected_oks_ids[${index}]`),
    ),
  }
}

export function parseVariantsText(text: string): readonly VariantSummary[] {
  return expectArray(parseText(text), '$').map((item, index) => parseVariant(item, `$[${index}]`))
}

function parsePosition(value: unknown, path: string): Position {
  const coordinates = expectArray(value, path)
  if (coordinates.length !== 2) {
    throw new ResponseContractError(`${path}: координата должна содержать ровно два числа`)
  }
  return [
    expectFiniteNumber(coordinates[0], `${path}[0]`),
    expectFiniteNumber(coordinates[1], `${path}[1]`),
  ]
}

function parseLine(value: unknown, path: string, minimum: number): readonly Position[] {
  const line = expectArray(value, path)
  if (line.length < minimum) throw new ResponseContractError(`${path}: недостаточно координат`)
  return line.map((item, index) => parsePosition(item, `${path}[${index}]`))
}

function parseGeometry(value: unknown, path: string): Geometry {
  const object = expectObject(value, path)
  const type = expectEnum(
    object.type,
    ['Point', 'LineString', 'MultiLineString', 'Polygon', 'MultiPolygon'],
    `${path}.type`,
  )
  if (type === 'Point') return { type, coordinates: parsePosition(object.coordinates, `${path}.coordinates`) }
  if (type === 'LineString') {
    return { type, coordinates: parseLine(object.coordinates, `${path}.coordinates`, 2) }
  }
  if (type === 'MultiLineString') {
    const lines = expectArray(object.coordinates, `${path}.coordinates`)
    if (lines.length < 1) throw new ResponseContractError(`${path}.coordinates: пустой массив`)
    return {
      type,
      coordinates: lines.map((line, index) => parseLine(line, `${path}.coordinates[${index}]`, 2)),
    }
  }
  if (type === 'Polygon') {
    const rings = expectArray(object.coordinates, `${path}.coordinates`)
    if (rings.length < 1) throw new ResponseContractError(`${path}.coordinates: пустой массив`)
    return {
      type,
      coordinates: rings.map((ring, index) => parseLine(ring, `${path}.coordinates[${index}]`, 4)),
    }
  }
  const polygons = expectArray(object.coordinates, `${path}.coordinates`)
  if (polygons.length < 1) throw new ResponseContractError(`${path}.coordinates: пустой массив`)
  return {
    type,
    coordinates: polygons.map((polygon, polygonIndex) => {
      const rings = expectArray(polygon, `${path}.coordinates[${polygonIndex}]`)
      if (rings.length < 1) {
        throw new ResponseContractError(`${path}.coordinates[${polygonIndex}]: пустой массив`)
      }
      return rings.map((ring, ringIndex) =>
        parseLine(ring, `${path}.coordinates[${polygonIndex}][${ringIndex}]`, 4),
      )
    }),
  }
}

function parseMapFeature(value: unknown, path: string): MapFeature {
  const object = expectObject(value, path)
  const properties = expectObject(object.properties, `${path}.properties`)
  return {
    type: expectEnum(object.type, ['Feature'], `${path}.type`),
    geometry: parseGeometry(object.geometry, `${path}.geometry`),
    properties: {
      id: expectObjectId(properties.id, `${path}.properties.id`),
      objectType: expectEnum<MapObjectType>(
        properties.object_type,
        ['source', 'heat_network', 'heat_chamber', 'oks_connection_point', 'restriction', 'technical_node'],
        `${path}.properties.object_type`,
      ),
      ...(properties.variant_id === undefined
        ? {}
        : { variantId: expectObjectId(properties.variant_id, `${path}.properties.variant_id`) }),
      ...(optionalInteger(properties.diameter, `${path}.properties.diameter`) === undefined
        ? {}
        : { diameter: optionalInteger(properties.diameter, `${path}.properties.diameter`) }),
      ...(optionalNumber(properties.flow_tph, `${path}.properties.flow_tph`) === undefined
        ? {}
        : { flowTph: optionalNumber(properties.flow_tph, `${path}.properties.flow_tph`) }),
      ...(optionalString(properties.restriction_type, `${path}.properties.restriction_type`) === undefined
        ? {}
        : { restrictionType: optionalString(properties.restriction_type, `${path}.properties.restriction_type`) }),
      ...(optionalNumber(properties.cost, `${path}.properties.cost`) === undefined
        ? {}
        : { cost: optionalNumber(properties.cost, `${path}.properties.cost`) }),
      ...(optionalNumber(properties.length, `${path}.properties.length`) === undefined
        ? {}
        : { length: optionalNumber(properties.length, `${path}.properties.length`) }),
      ...(properties.laying_method === undefined
        ? {}
        : { layingMethod: expectEnum(properties.laying_method, ['base', 'special'], `${path}.properties.laying_method`) }),
    },
  }
}

export function parseMapPageText(text: string): MapPage {
  const object = expectObject(parseText(text), '$')
  const nextCursor = object.nextCursor
  return {
    type: expectEnum(object.type, ['FeatureCollection'], '$.type'),
    features: expectArray(object.features, '$.features').map((item, index) =>
      parseMapFeature(item, `$.features[${index}]`),
    ),
    nextCursor: nextCursor === null ? null : expectString(nextCursor, '$.nextCursor'),
  }
}
