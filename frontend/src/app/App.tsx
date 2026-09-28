import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'

import { JobStatus } from '../features/job-status/JobStatus'
import { useJob } from '../features/job-status/use-job'
import { JobUpload } from '../features/job-upload/JobUpload'
import { ResultWorkspace } from '../features/results/ResultWorkspace'
import { ServiceStatus } from '../features/service-status/ServiceStatus'
import type { HeatNetworkApi } from '../shared/api/contracts'
import type { ApiMode } from '../shared/api/create-api'
import type { Job } from '../shared/model/api'

interface ActiveJobProps {
  readonly api: HeatNetworkApi
  readonly apiMode: ApiMode
  readonly initialJob: Job
  readonly onReset: () => void
}

function ActiveJob({ api, apiMode, initialJob, onReset }: ActiveJobProps) {
  const query = useJob(api, initialJob)
  const job = query.data ?? initialJob
  if (job.status === 'SUCCEEDED' && job.stage === 'DONE') {
    return <ResultWorkspace api={api} jobId={job.jobId} mode={job.mode} demo={apiMode === 'fixture'} onReset={onReset} />
  }
  return (
    <main className="workspace" aria-label="Рабочая область">
      <section className="workspace-panel" aria-label="Статус расчёта">
        <JobStatus job={job} isPollingError={query.isRefetchError} onReset={onReset} />
      </section>
      <section className="map-placeholder" aria-label="Состояние карты">
        <div className="map-grid" aria-hidden="true" />
        <div className="map-empty-state">
          <span className="map-pin" aria-hidden="true" />
          <p>Карта будет доступна после успешного расчёта</p>
        </div>
      </section>
    </main>
  )
}

export function App({ apiMode, api }: { apiMode: ApiMode; api: HeatNetworkApi }) {
  const [activeJob, setActiveJob] = useState<Job | null>(null)
  const queryClient = useQueryClient()
  const reset = () => {
    if (activeJob) {
      queryClient.removeQueries({ queryKey: ['job', activeJob.jobId], exact: true })
      queryClient.removeQueries({ queryKey: ['variants', activeJob.jobId], exact: true })
    }
    setActiveJob(null)
  }

  return (
    <div className="app-shell">
      <header className="app-header">
        <div className="brand-lockup">
          <span className="brand-mark" aria-hidden="true">
            ТС
          </span>
          <div>
            <p className="eyebrow">ЛЦТ 2026 · инженерный сервис</p>
            <h1>Моделирование тепловых сетей</h1>
          </div>
        </div>
        <div className="header-status">
          <ServiceStatus api={api} />
          {apiMode === 'fixture' && (
            <span className="mode-badge">Демонстрационные данные</span>
          )}
        </div>
      </header>
      {activeJob ? (
        <ActiveJob api={api} apiMode={apiMode} initialJob={activeJob} onReset={reset} />
      ) : (
      <main className="workspace" aria-label="Рабочая область">
        <section className="workspace-panel" aria-label="Параметры расчёта">
          <p className="section-kicker">Новый расчёт</p>
          <h2>Подключение объектов к сети</h2>
          <p className="intro-copy">
            Загрузите единый GeoJSON. Сервис проверит данные, построит варианты
            трасс и подготовит их для сравнения на карте.
          </p>
          <JobUpload api={api} onCreated={setActiveJob} />
        </section>
        <section className="map-placeholder" aria-label="Карта результата">
          <div className="map-grid" aria-hidden="true" />
          <div className="map-empty-state">
            <span className="map-pin" aria-hidden="true" />
            <p>Карта появится после запуска расчёта</p>
          </div>
        </section>
      </main>
      )}
    </div>
  )
}
