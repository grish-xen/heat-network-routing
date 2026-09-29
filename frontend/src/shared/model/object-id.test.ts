import { describe, expect, it } from 'vitest'

import { parseJobText } from '../api/parse-response'
import { formatObjectId, formatTypedObjectId, objectIdKey } from './object-id'

describe('ObjectId', () => {
  it('distinguishes text and numeric IDs without rounding', () => {
    const parsed = parseJobText(
      '{"jobId":"j","status":"FAILED","stage":"FAILED","mode":"2d","diagnostics":[{"code":"INVALID_ID","message":"bad","details":[{"inputObjectId":9007199254740993}]}]}',
    )
    const numeric = parsed.diagnostics[0]?.details?.[0]?.inputObjectId

    expect(formatObjectId(numeric!)).toBe('9007199254740993')
    expect(objectIdKey(numeric!)).toBe('number:9007199254740993')
    expect(objectIdKey({ kind: 'string', value: '9007199254740993' })).not.toBe(
      objectIdKey(numeric!),
    )
  })

  it('labels numeric and text IDs differently for people', () => {
    expect(formatTypedObjectId({ kind: 'string', value: '1' })).toBe('строковый «1»')
    expect(formatTypedObjectId({ kind: 'number', value: '1' })).toBe('числовой 1')
  })
})
