import { describe, expect, it } from 'vitest'

import { objectIdKey } from '../model/object-id'
import { ApiClientError } from './api-error'
import { FixtureHeatNetworkApi } from './fixture-api'

describe('FixtureHeatNetworkApi', () => {
  it('progresses a job deterministically from queue to success', async () => {
    let now = 10_000
    const api = new FixtureHeatNetworkApi({ now: () => now })
    const queued = await api.createJob(new File(['{}'], 'input.geojson'), '2d')

    expect(queued).toMatchObject({ jobId: 'fixture-1', status: 'QUEUED', stage: 'QUEUED' })
    now += 1_200
    expect(await api.getJob(queued.jobId)).toMatchObject({ status: 'RUNNING', stage: 'VALIDATING' })
    now += 4_000
    expect(await api.getJob(queued.jobId)).toMatchObject({ status: 'SUCCEEDED', stage: 'DONE' })
  })

  it('pages intersecting features without duplicates', async () => {
    const api = new FixtureHeatNetworkApi()
    const job = await api.createJob(new File(['{}'], 'input.geojson'), '2d')
    const ids: string[] = []
    let cursor: string | undefined

    do {
      const page = await api.getMapPage(job.jobId, {
        layer: 'input',
        bbox: [37.4, 55.6, 37.5, 55.7],
        limit: 1,
        cursor,
      })
      ids.push(...page.features.map((feature) => objectIdKey(feature.properties.id)))
      cursor = page.nextCursor ?? undefined
    } while (cursor)

    expect(ids.length).toBeGreaterThan(1)
    expect(new Set(ids).size).toBe(ids.length)
  })

  it.each(['-1', '1.5', '999'])('rejects invalid cursor %s', async (cursor) => {
    const api = new FixtureHeatNetworkApi()
    const job = await api.createJob(new File(['{}'], 'input.geojson'), '2d')

    await expect(
      api.getMapPage(job.jobId, {
        layer: 'input',
        bbox: [37.4, 55.6, 37.5, 55.7],
        cursor,
      }),
    ).rejects.toBeInstanceOf(ApiClientError)
  })
})
