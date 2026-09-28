import { useState, type DragEvent } from 'react'

import type { HeatNetworkApi } from '../../shared/api/contracts'
import { Alert } from '../../shared/ui/Alert'
import { Button } from '../../shared/ui/Button'
import { Spinner } from '../../shared/ui/Spinner'
import type { Job } from '../../shared/model/api'
import { useCreateJob } from './use-create-job'

interface JobUploadProps {
  readonly api: HeatNetworkApi
  readonly onCreated: (job: Job) => void
}

export function JobUpload({ api, onCreated }: JobUploadProps) {
  const [file, setFile] = useState<File | null>(null)
  const [mode, setMode] = useState<Job['mode']>('2d')
  const mutation = useCreateJob(api, onCreated)

  const selectFirstFile = (files: FileList | readonly File[] | null) => {
    const nextFile = files?.[0]
    if (nextFile) {
      setFile(nextFile)
      mutation.reset()
    }
  }

  const handleDrop = (event: DragEvent<HTMLLabelElement>) => {
    event.preventDefault()
    selectFirstFile(Array.from(event.dataTransfer.files))
  }

  return (
    <form
      className="upload-form"
      onSubmit={(event) => {
        event.preventDefault()
        if (file) mutation.mutate({ file, mode })
      }}
    >
      <label
        className="file-dropzone"
        data-testid="file-dropzone"
        onDragOver={(event) => event.preventDefault()}
        onDrop={handleDrop}
      >
        <input
          className="visually-hidden"
          type="file"
          accept=".geojson,.json,application/geo+json,application/json"
          aria-label="GeoJSON файл"
          onChange={(event) => selectFirstFile(event.target.files)}
        />
        <span className="dropzone-icon" aria-hidden="true">↥</span>
        <strong>{file ? file.name : 'Выберите GeoJSON'}</strong>
        <span>{file ? `${(file.size / 1024).toFixed(1)} КБ` : 'или перетащите файл сюда'}</span>
      </label>
      <fieldset className="mode-selector">
        <legend>Режим расчёта</legend>
        <label>
          <input type="radio" name="mode" value="2d" checked={mode === '2d'} onChange={() => setMode('2d')} />
          2D
        </label>
        <label>
          <input type="radio" name="mode" value="depth" checked={mode === 'depth'} onChange={() => setMode('depth')} />
          С учётом глубины
        </label>
      </fieldset>
      <p className="upload-hint">Файл передаётся серверу без обработки в браузере · режим {mode === '2d' ? '2D' : 'с учётом глубины'}</p>
      {mutation.error && (
        <Alert>{mutation.error instanceof Error ? mutation.error.message : 'Не удалось загрузить файл'}</Alert>
      )}
      <Button type="submit" disabled={!file || mutation.isPending}>
        {mutation.isPending ? (
          <>
            <Spinner /> Загружаем…
          </>
        ) : (
          'Запустить расчёт'
        )}
      </Button>
    </form>
  )
}
