import { parse, stringify } from 'lossless-json'

import inputMapText from '../../../../test-data/api/map-input.json?raw'
import variantsText from '../../../../test-data/api/variants.json?raw'
import resultMapText from '../../../../test-data/synthetic/two-consumers/expected.geojson?raw'
import depthInputMapText from '../../../../test-data/api/depth/map-input.json?raw'
import depthVariantsText from '../../../../test-data/api/depth/variants.json?raw'
import depthResultMapText from '../../../../test-data/api/depth/map-result.json?raw'
import depthResultDownloadText from '../../../../test-data/synthetic/depth/gas-below/expected.geojson?raw'
import type { Geometry, Health, Job, MapBounds, MapFeature, MapPage, MapQuery, VariantSummary } from '../model/api'
import type { ObjectId } from '../model/object-id'
import { objectIdKey } from '../model/object-id'
import { ApiClientError } from './api-error'
import type { HeatNetworkApi } from './contracts'
import { expectArray, expectObject, parseMapPageText, parseVariantsText } from './parse-response'

const FIXTURE_STAGES = [
  { untilMs: 1_000, status: 'QUEUED', stage: 'QUEUED' },
  { untilMs: 2_000, status: 'RUNNING', stage: 'VALIDATING' },
  { untilMs: 3_000, status: 'RUNNING', stage: 'ROUTING' },
  { untilMs: 4_000, status: 'RUNNING', stage: 'CALCULATING' },
  { untilMs: 5_000, status: 'RUNNING', stage: 'EXPORTING' },
  { untilMs: Number.POSITIVE_INFINITY, status: 'SUCCEEDED', stage: 'DONE' },
] as const

interface FixtureOptions {
  readonly now?: () => number
}

function normalizeMapFixture(text: string, excludeSummaries = false): MapPage {
  const object = expectObject(parse(text), 'fixture')
  const features = expectArray(object.features, 'fixture.features').filter((item) => {
    if (!excludeSummaries) return true
    const feature = expectObject(item, 'fixture.features[]')
    const properties = expectObject(feature.properties, 'fixture.features[].properties')
    return properties.object_type !== 'variant_summary'
  })
  const normalized = stringify({ ...object, features, nextCursor: null })
  if (normalized === undefined) throw new Error('Не удалось сериализовать демонстрационную карту')
  return parseMapPageText(normalized)
}

const inputFeatures = normalizeMapFixture(inputMapText).features
const resultFeatures = normalizeMapFixture(resultMapText, true).features
const fixtureVariants = parseVariantsText(variantsText)
const depthInputFeatures = normalizeMapFixture(depthInputMapText).features
const depthResultFeatures = normalizeMapFixture(depthResultMapText, true).features
const depthFixtureVariants = parseVariantsText(depthVariantsText)

interface FixtureJob {
  readonly createdAt: number
  readonly mode: Job['mode']
}

function positions(geometry: Geometry): readonly (readonly [number, number])[] {
  if (geometry.type === 'Point') return [geometry.coordinates]
  if (geometry.type === 'LineString') return geometry.coordinates
  if (geometry.type === 'MultiLineString' || geometry.type === 'Polygon') {
    return geometry.coordinates.flat()
  }
  return geometry.coordinates.flat(2)
}

function intersects(feature: MapFeature, bbox: MapQuery['bbox']): boolean {
  const coordinates = positions(feature.geometry)
  const longitudes = coordinates.map(([longitude]) => longitude)
  const latitudes = coordinates.map(([, latitude]) => latitude)
  return (
    Math.min(...longitudes) <= bbox[2] &&
    Math.max(...longitudes) >= bbox[0] &&
    Math.min(...latitudes) <= bbox[3] &&
    Math.max(...latitudes) >= bbox[1]
  )
}

function boundsFor(features: readonly MapFeature[]): MapBounds {
  const coordinates = features.flatMap((feature) => positions(feature.geometry))
  if (coordinates.length === 0) return null
  const longitudes = coordinates.map(([longitude]) => longitude)
  const latitudes = coordinates.map(([, latitude]) => latitude)
  return [Math.min(...longitudes), Math.min(...latitudes), Math.max(...longitudes), Math.max(...latitudes)]
}

export class FixtureHeatNetworkApi implements HeatNetworkApi {
  readonly #now: () => number
  readonly #jobs = new Map<string, FixtureJob>()
  #nextJobId = 1

  constructor(options: FixtureOptions = {}) {
    this.#now = options.now ?? Date.now
  }

  async health(): Promise<Health> {
    return { status: 'UP', contractVersion: '1.0', implementation: 'fixture' }
  }

  async createJob(file: File, mode: Job['mode']): Promise<Job> {
    void file
    const jobId = `fixture-${this.#nextJobId++}`
    this.#jobs.set(jobId, { createdAt: this.#now(), mode })
    return this.#job(jobId)
  }

  async getJob(jobId: string): Promise<Job> {
    this.#requireJob(jobId)
    return this.#job(jobId)
  }

  async listVariants(jobId: string): Promise<readonly VariantSummary[]> {
    return this.#requireJob(jobId).mode === 'depth' ? depthFixtureVariants : fixtureVariants
  }

  async getMapBounds(jobId: string, variantId: ObjectId): Promise<MapBounds> {
    const job = this.#requireJob(jobId)
    const key = objectIdKey(variantId)
    const inputs = job.mode === 'depth' ? depthInputFeatures : inputFeatures
    const results = job.mode === 'depth' ? depthResultFeatures : resultFeatures
    return boundsFor([
      ...inputs,
      ...results.filter((feature) => feature.properties.variantId && objectIdKey(feature.properties.variantId) === key),
    ])
  }

  async getMapPage(jobId: string, query: MapQuery): Promise<MapPage> {
    const job = this.#requireJob(jobId)
    const limit = query.limit ?? 1000
    if (!Number.isInteger(limit) || limit < 1 || limit > 5000) {
      throw new ApiClientError(400, 'INVALID_LIMIT', 'Размер страницы должен быть от 1 до 5000')
    }
    const source = query.layer === 'input'
      ? (job.mode === 'depth' ? depthInputFeatures : inputFeatures)
      : (job.mode === 'depth' ? depthResultFeatures : resultFeatures)
    const filtered = source.filter(
      (feature) =>
        intersects(feature, query.bbox) &&
        (query.layer !== 'result' ||
          query.variantId === undefined ||
          (feature.properties.variantId !== undefined &&
            objectIdKey(feature.properties.variantId) === objectIdKey(query.variantId))),
    )
    const offset = query.cursor === undefined ? 0 : Number(query.cursor)
    if (
      !Number.isInteger(offset) ||
      offset < 0 ||
      String(offset) !== (query.cursor ?? '0') ||
      offset > filtered.length
    ) {
      throw new ApiClientError(400, 'INVALID_CURSOR', 'Некорректный курсор страницы')
    }
    const features = filtered.slice(offset, offset + limit)
    const nextOffset = offset + features.length
    return {
      type: 'FeatureCollection',
      features,
      nextCursor: nextOffset < filtered.length ? String(nextOffset) : null,
    }
  }

  getResultUrl(jobId: string): string {
    const job = this.#requireJob(jobId)
    const result = job.mode === 'depth' ? depthResultDownloadText : resultMapText
    return `data:application/geo+json;charset=utf-8,${encodeURIComponent(result)}`
  }

  #requireJob(jobId: string): FixtureJob {
    const job = this.#jobs.get(jobId)
    if (job === undefined) {
      throw new ApiClientError(404, 'JOB_NOT_FOUND', 'Расчёт не найден')
    }
    return job
  }

  #job(jobId: string): Job {
    const fixtureJob = this.#requireJob(jobId)
    const elapsed = this.#now() - fixtureJob.createdAt
    const state = FIXTURE_STAGES.find(({ untilMs }) => elapsed < untilMs) ?? FIXTURE_STAGES.at(-1)!
    return {
      jobId,
      status: state.status,
      stage: state.stage,
      mode: fixtureJob.mode,
      diagnostics: [],
    }
  }
}
