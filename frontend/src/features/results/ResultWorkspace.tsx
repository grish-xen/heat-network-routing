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
import { MAX_DEPTH_LOAD_FEATURES, MAX_DEPTH_SCENE_FEATURES, useMapBounds, useMapFeatureCollections } from '../network-map/use-map-features'
import { DepthProfile } from '../depth-profile/DepthProfile'
import { buildDepthPaths, sceneOrigin, selectDepthPath, toMetricSegments, toSceneCommunications } from '../depth-profile/depth-path'
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
  const [mapUnavailableScope, setMapUnavailableScope] = useState<string | null>(null)
  const [view, setView] = useState<'map' | 'profile' | '3d'>(mode === 'depth' ? '3d' : 'map')
  const [endpointSelection, setEndpointSelection] = useState<{ readonly scope: string; readonly key: string } | null>(null)
  const ranked = [...(variants.data ?? [])].sort((left, right) => left.rank - right.rank)
  const selected = ranked.find((variant) => objectIdKey(variant.variantId) === selectedKey) ?? ranked[0]
  const selectedVariantKey = selected ? objectIdKey(selected.variantId) : null
  const depthScope = `${jobId}:${selectedVariantKey ?? ''}`
  const endpointKey = endpointSelection?.scope === depthScope ? endpointSelection.key : null
  const mapUnavailable = mapUnavailableScope === depthScope
  const bounds = useMapBounds(api, jobId, selected?.variantId)
  const depthQueries = useMapFeatureCollections(api, jobId, selected && bounds.data ? [{
    layer: 'result' as const, variantId: selected.variantId, bbox: expandBoundsForMapQuery(bounds.data), limit: 1000,
  }] : [], mode === 'depth', { maxLoadFeatures: MAX_DEPTH_LOAD_FEATURES, keepPreviousData: false })
  const inputQueries = useMapFeatureCollections(api, jobId, bounds.data ? [{
    layer: 'input' as const, bbox: expandBoundsForMapQuery(bounds.data), limit: 1000,
  }] : [], mode === 'depth', { maxLoadFeatures: MAX_DEPTH_LOAD_FEATURES, keepPreviousData: false })
  const paths = buildDepthPaths(depthQueries.flatMap((query) => query.data?.features ?? []))
  const activePath = selectDepthPath(paths, endpointKey)
  const sceneSegments = activePath ? toMetricSegments(activePath) : []
  const sceneCommunications = activePath && sceneOrigin(activePath)
    ? toSceneCommunications(inputQueries.flatMap((query) => query.data?.features ?? []), sceneOrigin(activePath)!)
    : []
  const sceneObjectLimitExceeded = sceneSegments.length + sceneCommunications.length > MAX_DEPTH_SCENE_FEATURES
  const routeLoading = mode === 'depth' && (bounds.isPending || depthQueries.some((query) => query.isPending))
  const communicationsLoading = mode === 'depth' && inputQueries.some((query) => query.isPending)
  const routeError = mode === 'depth'
    ? (bounds.isError ? bounds.error : depthQueries.find((query) => query.isError)?.error)
    : undefined
  const communicationsError = mode === 'depth' ? inputQueries.find((query) => query.isError)?.error : undefined
  const routeErrorText = routeError instanceof ApiClientError && routeError.status === 413
    ? 'Слишком большой объём данных для профиля и 3D-сцены. Скачайте GeoJSON или сузьте расчёт и повторите загрузку.'
    : 'Не удалось загрузить данные глубинной модели. Попробуйте повторить загрузку.'
  const communicationsErrorText = communicationsError instanceof ApiClientError && communicationsError.status === 413
    ? 'Не удалось загрузить коммуникации для 3D-сцены: ответ слишком большой. Профиль и скачивание GeoJSON остаются доступны.'
    : 'Не удалось загрузить коммуникации для 3D-сцены. Профиль и скачивание GeoJSON остаются доступны.'
  const retryDepth = () => {
    void bounds.refetch()
    ;[...depthQueries, ...inputQueries].forEach((query) => { void query.refetch() })
  }
  const retryCommunications = () => inputQueries.forEach((query) => { void query.refetch() })

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
          <select value={objectIdKey(activePath!.endNodeId)} onChange={(event) => setEndpointSelection({ scope: depthScope, key: event.target.value })}>
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
            if (reason === 'GPU_UNAVAILABLE') setMapUnavailableScope(depthScope)
          }}
        />}
        {view === 'profile' && routeLoading && <div className="depth-visualization-state"><Spinner /> Загружаем профиль…</div>}
        {view === 'profile' && !routeLoading && routeError && <div className="depth-visualization-state"><Alert>{routeErrorText}</Alert><Button type="button" className="button--secondary" onClick={retryDepth}>Повторить загрузку</Button></div>}
        {view === 'profile' && !routeLoading && !routeError && <DepthProfile segments={sceneSegments} />}
        {view === '3d' && (routeLoading || communicationsLoading) && <div className="depth-visualization-state"><Spinner /> Загружаем 3D-сцену…</div>}
        {view === '3d' && !routeLoading && !communicationsLoading && routeError && <div className="depth-visualization-state"><Alert>{routeErrorText}</Alert><Button type="button" className="button--secondary" onClick={retryDepth}>Повторить загрузку</Button></div>}
        {view === '3d' && !routeLoading && !communicationsLoading && !routeError && communicationsError && <div className="depth-visualization-state"><Alert>{communicationsErrorText}</Alert><Button type="button" className="button--secondary" onClick={retryCommunications}>Повторить загрузку коммуникаций</Button></div>}
        {view === '3d' && !routeLoading && !communicationsLoading && !routeError && !communicationsError && sceneObjectLimitExceeded && <div className="depth-visualization-state"><Alert>Превышен предел объектов для 3D-сцены. Сузьте расчёт или скачайте GeoJSON.</Alert></div>}
        {view === '3d' && !routeLoading && !communicationsLoading && !routeError && !communicationsError && !sceneObjectLimitExceeded && <DepthScene segments={sceneSegments} communications={sceneCommunications} />}
      </section>
    </main>
  )
}
