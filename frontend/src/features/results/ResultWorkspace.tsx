import { useState } from 'react'

import type { HeatNetworkApi } from '../../shared/api/contracts'
import { ApiClientError } from '../../shared/api/api-error'
import type { Job } from '../../shared/model/api'
import { formatTypedObjectId, objectIdKey } from '../../shared/model/object-id'
import { Alert } from '../../shared/ui/Alert'
import { Button } from '../../shared/ui/Button'
import { Spinner } from '../../shared/ui/Spinner'
import { NetworkMap } from '../network-map/NetworkMap'
import { expandBoundsForMapQuery } from '../network-map/map-query'
import { useMapBounds, useMapFeatureCollections } from '../network-map/use-map-features'
import { DepthProfile } from '../depth-profile/DepthProfile'
import { buildDepthPaths, selectDepthPath, toMetricSegments } from '../depth-profile/depth-path'
import { DepthScene } from '../network-3d/DepthScene'
import { VariantComparison } from '../variants/VariantComparison'
import { VariantList } from '../variants/VariantList'
import { useVariants } from '../variants/use-variants'

interface ResultWorkspaceProps {
  readonly api: HeatNetworkApi
  readonly jobId: string
  readonly mode?: Job['mode']
  readonly demo?: boolean
  readonly onReset?: () => void
}

export function ResultWorkspace({ api, jobId, mode = '2d', demo = false, onReset }: ResultWorkspaceProps) {
  const variants = useVariants(api, jobId)
  const [selectedKey, setSelectedKey] = useState<string | null>(null)
  const [mapUnavailable, setMapUnavailable] = useState(false)
  const [view, setView] = useState<'map' | 'profile' | '3d'>(mode === 'depth' ? '3d' : 'map')
  const [endpointKey, setEndpointKey] = useState<string | null>(null)
  const ranked = [...(variants.data ?? [])].sort((left, right) => left.rank - right.rank)
  const selected = ranked.find((variant) => objectIdKey(variant.variantId) === selectedKey) ?? ranked[0]
  const bounds = useMapBounds(api, jobId, selected?.variantId)
  const depthQueries = useMapFeatureCollections(api, jobId, selected && bounds.data ? [{
    layer: 'result' as const, variantId: selected.variantId, bbox: expandBoundsForMapQuery(bounds.data), limit: 1000,
  }] : [], mode === 'depth')
  const paths = buildDepthPaths(depthQueries.flatMap((query) => query.data?.features ?? []))
  const activePath = selectDepthPath(paths, endpointKey)
  const sceneSegments = activePath ? toMetricSegments(activePath) : []
  const depthLoading = mode === 'depth' && (bounds.isPending || depthQueries.some((query) => query.isPending))
  const depthError = mode === 'depth'
    ? (bounds.isError ? bounds.error : depthQueries.find((query) => query.isError)?.error)
    : undefined
  const depthErrorText = depthError instanceof ApiClientError && depthError.status === 413
    ? 'Слишком большой объём данных для профиля и 3D-сцены. Скачайте GeoJSON или сузьте расчёт и повторите загрузку.'
    : 'Не удалось загрузить данные глубинной модели. Попробуйте повторить загрузку.'
  const retryDepth = () => {
    void bounds.refetch()
    depthQueries.forEach((query) => { void query.refetch() })
  }

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
        {mode === 'depth' && <section className="result-depth-controls" aria-label="Режим визуализации глубинной модели">
          <div className="result-depth-heading"><strong>Глубинная модель</strong><span>Карта, профиль или объёмная сцена трассы</span></div>
          <div className="result-view-tabs" role="tablist" aria-label="Представление результата">
            <button type="button" role="tab" aria-selected={view === 'map'} onClick={() => setView('map')}>Карта</button>
            <button type="button" role="tab" aria-selected={view === 'profile'} onClick={() => setView('profile')}>Профиль</button>
            <button type="button" role="tab" aria-selected={view === '3d'} onClick={() => setView('3d')}>3D-сцена</button>
          </div>
        </section>}
        {mode === 'depth' && paths.length > 1 && <label className="depth-endpoint-selector">
          Конечный потребитель
          <select value={objectIdKey(activePath!.endNodeId)} onChange={(event) => setEndpointKey(event.target.value)}>
            {paths.map((path) => <option key={objectIdKey(path.endNodeId)} value={objectIdKey(path.endNodeId)}>{formatTypedObjectId(path.endNodeId)}</option>)}
          </select>
        </label>}
        {mapUnavailable && <Alert>Карта недоступна без WebGL2. Сравнение вариантов и файл результата остаются доступны.</Alert>}
        <div className="result-actions">
          <a className="button download-link" href={api.getResultUrl(jobId)} download={`${jobId}.geojson`}>
            Скачать GeoJSON{demo ? ' · демонстрационные данные' : ''}
          </a>
          {onReset && <Button className="button--secondary" type="button" onClick={onReset}>Начать заново</Button>}
        </div>
      </section>
      <section className="map-placeholder" aria-label="Визуализация выбранного варианта">
        {view === 'map' && <NetworkMap
          api={api}
          jobId={jobId}
          layers={[
            { layer: 'input' },
            { layer: 'result', variantId: selected.variantId },
          ]}
          onUnavailable={(reason) => {
            if (reason === 'GPU_UNAVAILABLE') setMapUnavailable(true)
          }}
        />}
        {view !== 'map' && depthLoading && <div className="depth-visualization-state"><Spinner /> Загружаем профиль и 3D-сцену…</div>}
        {view !== 'map' && !depthLoading && depthError && <div className="depth-visualization-state"><Alert>{depthErrorText}</Alert><Button type="button" className="button--secondary" onClick={retryDepth}>Повторить загрузку</Button></div>}
        {view === 'profile' && !depthLoading && !depthError && <DepthProfile segments={sceneSegments} />}
        {view === '3d' && !depthLoading && !depthError && <DepthScene segments={sceneSegments} />}
      </section>
    </main>
  )
}
