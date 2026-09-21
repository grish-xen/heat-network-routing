export type ObjectId =
  | { readonly kind: 'string'; readonly value: string }
  | { readonly kind: 'number'; readonly value: string }

export const objectIdKey = (id: ObjectId): string => `${id.kind}:${id.value}`

export const formatObjectId = (id: ObjectId): string => id.value
