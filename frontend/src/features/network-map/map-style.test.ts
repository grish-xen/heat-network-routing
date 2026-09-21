import { describe, expect, it } from 'vitest'

import { mapFeatureId, MAP_LAYER_DEFINITIONS, MAP_LEGEND_ITEMS } from './map-style'

describe('map style definitions', () => {
  it('gives every supported object type a visible layer treatment', () => {
    const objectTypes = new Set(MAP_LAYER_DEFINITIONS.map(({ objectType }) => objectType))
    expect(objectTypes).toEqual(new Set([
      'source', 'heat_network', 'heat_chamber', 'oks_connection_point', 'restriction', 'technical_node',
    ]))
    expect(new Set(MAP_LAYER_DEFINITIONS.map(({ id }) => id)).size).toBe(MAP_LAYER_DEFINITIONS.length)
  })

  it('distinguishes calculated network and restriction boundaries', () => {
    const existing = MAP_LAYER_DEFINITIONS.find(({ id }) => id === 'existing-network')
    const calculated = MAP_LAYER_DEFINITIONS.find(({ id }) => id === 'calculated-network')
    expect(existing?.paint).not.toEqual(calculated?.paint)
    expect(calculated?.paint).toMatchObject({ lineWidth: 4, lineDasharray: [1.5, 1] })
    expect(MAP_LAYER_DEFINITIONS.filter(({ objectType }) => objectType === 'restriction').map(({ kind }) => kind))
      .toEqual(expect.arrayContaining(['fill', 'line']))
  })

  it('shares layer groups with the visible legend', () => {
    expect(new Set(MAP_LAYER_DEFINITIONS.map(({ group }) => group))).toEqual(
      new Set(MAP_LEGEND_ITEMS.map(({ group }) => group)),
    )
  })

  it('uses exact ID kind and value for GeoJSON feature IDs', () => {
    expect(mapFeatureId({ kind: 'number', value: '9007199254740993' })).toBe('number:9007199254740993')
    expect(mapFeatureId({ kind: 'string', value: '9007199254740993' })).toBe('string:9007199254740993')
  })
})
