import type { Job, JobStage } from '../../shared/model/api'
import { formatObjectId } from '../../shared/model/object-id'
import { Alert } from '../../shared/ui/Alert'
import { Button } from '../../shared/ui/Button'

const stages: readonly { value: JobStage; label: string }[] = [
  { value: 'QUEUED', label: 'В очереди' },
  { value: 'VALIDATING', label: 'Проверка данных' },
  { value: 'ROUTING', label: 'Построение трасс' },
  { value: 'CALCULATING', label: 'Расчёт стоимости' },
  { value: 'EXPORTING', label: 'Подготовка результата' },
  { value: 'DONE', label: 'Готово' },
  { value: 'FAILED', label: 'Ошибка' },
]

const stageIndex = (stage: JobStage) => stages.findIndex(({ value }) => value === stage)

interface JobStatusProps {
  readonly job: Job
  readonly isPollingError?: boolean
  readonly onReset?: () => void
}

export function JobStatus({ job, isPollingError = false, onReset }: JobStatusProps) {
  const currentIndex = stageIndex(job.stage)
  const processingUnavailable = job.diagnostics.some(({ code }) => code === 'PROCESSING_UNAVAILABLE')
  const isTerminal = job.status === 'SUCCEEDED' || job.status === 'FAILED'

  return (
    <section className="job-status" aria-labelledby="job-status-title">
      <p className="section-kicker">Расчёт {job.jobId}</p>
      <div aria-live="polite">
        <h2 id="job-status-title">
          {processingUnavailable
            ? 'Модуль расчёта ещё подключается'
            : job.status === 'FAILED'
              ? 'Задача завершилась с ошибкой'
              : job.status === 'SUCCEEDED'
                ? 'Расчёт готов'
                : 'Выполняем расчёт'}
        </h2>
      </div>
      {isPollingError && (
        <Alert>Связь временно потеряна. Показываем последний полученный статус и пробуем снова.</Alert>
      )}
      <ol className="stage-list" aria-label="Этапы расчёта">
        {stages.map((stage, index) => {
          const state = index === currentIndex
            ? 'current'
            : job.stage !== 'FAILED' && index < currentIndex
              ? 'done'
              : 'pending'
          return (
            <li key={stage.value} className={`stage stage--${state}`} aria-current={state === 'current' ? 'step' : undefined}>
              <span className="stage-marker" aria-hidden="true">{state === 'done' ? '✓' : index + 1}</span>
              <span>{stage.label}</span>
            </li>
          )
        })}
      </ol>
      {job.diagnostics.length > 0 && (
        <div className="diagnostics">
          {job.diagnostics.map((diagnostic, index) => (
            <article className="diagnostic" key={`${diagnostic.code}-${index}`}>
              <strong>{diagnostic.code}</strong>
              <p>{diagnostic.message}</p>
              {diagnostic.details?.map((detail, detailIndex) =>
                detail.inputObjectId ? (
                  <p className="diagnostic-reference" key={detailIndex}>
                    Объект: {formatObjectId(detail.inputObjectId)}
                  </p>
                ) : null,
              )}
            </article>
          ))}
        </div>
      )}
      {isTerminal && onReset && (
        <Button type="button" className="button--secondary" onClick={onReset}>Начать заново</Button>
      )}
    </section>
  )
}
