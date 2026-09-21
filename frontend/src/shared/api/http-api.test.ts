import { HttpResponse, http } from 'msw'
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'

import { ApiClientError } from './api-error'
import { HttpHeatNetworkApi } from './http-api'

const baseUrl = 'http://localhost'
const server = setupServer()

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

describe('HttpHeatNetworkApi', () => {
  it('uploads a file as multipart without overriding its boundary', async () => {
    server.use(
      http.post(`${baseUrl}/api/jobs`, async ({ request }) => {
        expect(request.headers.get('content-type')).toMatch(/^multipart\/form-data; boundary=/)
        const data = await request.formData()
        expect(data.get('mode')).toBe('2d')
        return HttpResponse.json(
          { jobId: 'job-1', status: 'QUEUED', stage: 'QUEUED', mode: '2d', diagnostics: [] },
          { status: 202 },
        )
      }),
    )
    const api = new HttpHeatNetworkApi(baseUrl)

    const job = await api.createJob(new File(['{}'], 'input.geojson'), '2d')

    expect(job.jobId).toBe('job-1')
  })

  it('serializes bbox, exact variant ID and pagination parameters', async () => {
    server.use(
      http.get(`${baseUrl}/api/jobs/job-1/map`, ({ request }) => {
        const url = new URL(request.url)
        expect(url.searchParams.get('layer')).toBe('result')
        expect(url.searchParams.get('bbox')).toBe('37.4,55.6,37.5,55.7')
        expect(url.searchParams.get('variantId')).toBe('9007199254740993')
        expect(url.searchParams.get('limit')).toBe('1000')
        expect(url.searchParams.get('cursor')).toBe('next page')
        return HttpResponse.json({ type: 'FeatureCollection', features: [], nextCursor: null })
      }),
    )
    const api = new HttpHeatNetworkApi(baseUrl)

    await api.getMapPage('job-1', {
      layer: 'result',
      variantId: { kind: 'number', value: '9007199254740993' },
      bbox: [37.4, 55.6, 37.5, 55.7],
      limit: 1000,
      cursor: 'next page',
    })
  })

  it('throws a structured API error and keeps Retry-After', async () => {
    server.use(
      http.get(`${baseUrl}/api/jobs/job-1`, () =>
        HttpResponse.json(
          { code: 'QUEUE_FULL', message: 'Попробуйте позже' },
          { status: 503, headers: { 'Retry-After': '4' } },
        ),
      ),
    )
    const api = new HttpHeatNetworkApi(baseUrl)

    await expect(api.getJob('job-1')).rejects.toEqual(
      expect.objectContaining<ApiClientError>({
        name: 'ApiClientError',
        status: 503,
        code: 'QUEUE_FULL',
        message: 'Попробуйте позже',
        retryAfter: '4',
      }),
    )
  })

  it('returns an encoded result URL without requesting it', () => {
    const api = new HttpHeatNetworkApi(baseUrl)
    expect(api.getResultUrl('job / 1')).toBe('http://localhost/api/jobs/job%20%2F%201/result')
  })
})
