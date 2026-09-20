export class ApiClientError extends Error {
  readonly status: number
  readonly code: string
  readonly retryAfter?: string

  constructor(status: number, code: string, message: string, retryAfter?: string) {
    super(message)
    this.name = 'ApiClientError'
    this.status = status
    this.code = code
    this.retryAfter = retryAfter
  }
}
