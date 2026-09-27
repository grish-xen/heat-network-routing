import { describe, expect, it } from 'vitest'

import {
  parseJobText,
  parseMapBoundsText,
  parseMapPageText,
  parseVariantsText,
  ResponseContractError,
} from './parse-response'

describe('lossless API parsing', () => {
  it('rejects missing required fields and invalid enums', () => {
    expect(() => parseJobText('{"jobId":"j"}')).toThrow(ResponseContractError)
    expect(() =>
      parseJobText('{"jobId":"j","status":"LATER","stage":"QUEUED","mode":"2d","diagnostics":[]}'),
    ).toThrow(/unknown|неизвестное/i)
  })

  it('rejects unsafe integer measurements', () => {
    expect(() =>
      parseVariantsText(
        '[{"id":1,"object_type":"variant_summary","variant_id":1,"rank":9007199254740993,"construction_cost":1,"chamber_construction_cost":1,"existing_chamber_tie_in_count":1,"existing_chamber_tie_in_cost":1,"unconnected_penalty":1,"calculated_cost":1,"new_network_length":1,"score":1,"unconnected_oks_ids":[]}]',
      ),
    ).toThrow(/безопасное целое/i)
  })

  it('rejects a measurement that cannot be represented without precision loss', () => {
    expect(() =>
      parseVariantsText(
        '[{"id":1,"object_type":"variant_summary","variant_id":1,"rank":1,"construction_cost":9007199254740993,"chamber_construction_cost":1,"existing_chamber_tie_in_count":1,"existing_chamber_tie_in_cost":1,"unconnected_penalty":1,"calculated_cost":1,"new_network_length":1,"score":1,"unconnected_oks_ids":[]}]',
      ),
    ).toThrow(/без потери точности/i)
  })

  it('parses a typed map page and preserves its numeric ID', () => {
    const page = parseMapPageText(
      '{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[37.6,55.7]},"properties":{"id":9007199254740993,"object_type":"source"}}],"nextCursor":null}',
    )

    expect(page.features[0]?.properties.id).toEqual({ kind: 'number', value: '9007199254740993' })
    expect(page.features[0]?.geometry.type).toBe('Point')
  })

  it('parses nullable WGS84 map bounds and rejects inverted bounds', () => {
    expect(parseMapBoundsText('{"bbox":[37.4,55.6,37.5,55.7]}')).toEqual([37.4, 55.6, 37.5, 55.7])
    expect(parseMapBoundsText('{"bbox":null}')).toBeNull()
    expect(() => parseMapBoundsText('{"bbox":[37.5,55.6,37.4,55.7]}')).toThrow(ResponseContractError)
  })
})
