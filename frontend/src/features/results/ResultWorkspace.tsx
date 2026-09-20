import { useState } from 'react'

import type { HeatNetworkApi } from '../../shared/api/contracts'
import { objectIdKey } from '../../shared/model/object-id'
import { Alert } from '../../shared/ui/Alert'
import { Button } from '../../shared/ui/Button'
import { Spinner } from '../../shared/ui/Spinner'
import { NetworkMap } from '../network-map/NetworkMap'
import { VariantComparison } from '../variants/VariantComparison'
import { VariantList } from '../variants/VariantList'
import { useVariants } from '../variants/use-variants'

interface ResultWorkspaceProps {
  readonly api: HeatNetworkApi
  readonly jobId: string
  readonly demo?: boolean
  readonly onReset?: () => void
}

export function ResultWorkspace({ api, jobId, demo = false, onReset }: ResultWorkspaceProps) {
  const variants = useVariants(api, jobId)
  const [selectedKey, setSelectedKey] = useState<string | null>(null)
  const [mapUnavailable, setMapUnavailable] = useState(false)
  const ranked = [...(variants.data ?? [])].sort((left, right) => left.rank - right.rank)
  const selected = ranked.find((variant) => objectIdKey(variant.variantId) === selectedKey) ?? ranked[0]

  if (variants.isPending) {
    return <main className="result-loading"><Spinner /> Загружаем варианты…</main>
  }
  if (variants.isError || !selected) {
    return <main className="result-loading"><Alert>Не удалось получить варианты расчёта.</Alert></main>
  }

  return (
    <main className="workspace workspace--result" aria-label="Результаты расчёта">
      <section className="workspace-panel result-panel">
        <p className="section-kicker">Расчёт завершён</p>
        <h2>Сравните варианты трассировки</h2>
        <VariantList
          variants={ranked}
          selectedKey={objectIdKey(selected.variantId)}
          onSelect={(variant) => setSelectedKey(objectIdKey(variant.variantId))}
        />
        <VariantComparison variant={selected} />
        {mapUnavailable && <Alert>Карта недоступна без WebGL2. Сравнение вариантов и файл результата остаются доступны.</Alert>}
        <div className="result-actions">
          <a className="button download-link" href={api.getResultUrl(jobId)} download={`${jobId}.geojson`}>
            Скачать GeoJSON{demo ? ' · демонстрационные данные' : ''}
          </a>
          {onReset && <Button className="button--secondary" type="button" onClick={onReset}>Начать заново</Button>}
        </div>
      </section>
      <section className="map-placeholder" aria-label="Карта выбранного варианта">
        <NetworkMap
          api={api}
          jobId={jobId}
          layer="result"
          variantId={selected.variantId}
          onUnavailable={(reason) => {
            if (reason === 'GPU_UNAVAILABLE') setMapUnavailable(true)
          }}
        />
      </section>
    </main>
  )
}
