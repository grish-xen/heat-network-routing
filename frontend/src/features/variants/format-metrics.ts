const normalizeSpaces = (value: string) => value.replace(/[\u00a0\u202f]/g, ' ')

const integer = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 0 })
const score = new Intl.NumberFormat('ru-RU', { minimumFractionDigits: 8, maximumFractionDigits: 8 })

export const formatRubles = (value: number): string => `${normalizeSpaces(integer.format(value))} ₽`
export const formatMetres = (value: number): string => `${normalizeSpaces(integer.format(value))} м`
export const formatScore = (value: number): string => normalizeSpaces(score.format(value))
