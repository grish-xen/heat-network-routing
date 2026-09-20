import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import type { Job } from '../../shared/model/api'
import { JobStatus } from './JobStatus'

describe('JobStatus', () => {
  it('keeps the current stage visible during a transient connection error', () => {
    const job: Job = {
      jobId: 'job-1', status: 'RUNNING', stage: 'VALIDATING', mode: '2d', diagnostics: [],
    }
    render(<JobStatus job={job} isPollingError />)

    expect(screen.getByText(/связь временно потеряна/i)).toBeVisible()
    expect(screen.getByText(/проверка данных/i)).toBeVisible()
    expect(screen.queryByText(/задача завершилась с ошибкой/i)).not.toBeInTheDocument()
  })

  it('explains an unavailable processing module and prints exact object IDs', () => {
    const job: Job = {
      jobId: 'job-1', status: 'FAILED', stage: 'FAILED', mode: '2d',
      diagnostics: [{
        code: 'PROCESSING_UNAVAILABLE',
        message: 'Расчёт пока не подключён',
        details: [{ inputObjectId: { kind: 'number', value: '9007199254740993' } }],
      }],
    }
    render(<JobStatus job={job} />)

    expect(screen.getByRole('heading', { name: /модуль расчёта ещё подключается/i })).toBeVisible()
    expect(screen.getByText('PROCESSING_UNAVAILABLE')).toBeVisible()
    expect(screen.getByText(/9007199254740993/)).toBeVisible()
  })
})
