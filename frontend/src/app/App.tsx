import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'

import { JobStatus } from '../features/job-status/JobStatus'
import { useJob } from '../features/job-status/use-job'
import { JobUpload } from '../features/job-upload/JobUpload'
import { ServiceStatus } from '../features/service-status/ServiceStatus'
import type { HeatNetworkApi } from '../shared/api/contracts'
import type { ApiMode } from '../shared/api/create-api'
import type { Job } from '../shared/model/api'

interface ActiveJobProps {
  readonly api: HeatNetworkApi
  readonly initialJob: Job
  readonly onReset: () => void
}

function ActiveJob({ api, initialJob, onReset }: ActiveJobProps) {
  const job = useJob(api, initialJob)
  return <JobStatus job={job.data ?? initialJob} isPollingError={job.isRefetchError} onReset={onReset} />
}

export function App({ apiMode, api }: { apiMode: ApiMode; api: HeatNetworkApi }) {
  const [activeJob, setActiveJob] = useState<Job | null>(null)
  const queryClient = useQueryClient()
  const reset = () => {
    if (activeJob) queryClient.removeQueries({ queryKey: ['job', activeJob.jobId], exact: true })
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
      <main className="workspace" aria-label="Рабочая область">
        <section className="workspace-panel" aria-label="Параметры расчёта">
          {activeJob ? (
            <ActiveJob api={api} initialJob={activeJob} onReset={reset} />
          ) : (
            <>
              <p className="section-kicker">Новый расчёт</p>
              <h2>Подключение объектов к сети</h2>
              <p className="intro-copy">
                Загрузите единый GeoJSON. Сервис проверит данные, построит варианты
                трасс и подготовит их для сравнения на карте.
              </p>
              <JobUpload api={api} onCreated={setActiveJob} />
            </>
          )}
        </section>
        <section className="map-placeholder" aria-label="Карта результата">
          <div className="map-grid" aria-hidden="true" />
          <div className="map-empty-state">
            <span className="map-pin" aria-hidden="true" />
            <p>Карта появится после запуска расчёта</p>
          </div>
        </section>
      </main>
    </div>
  )
}
