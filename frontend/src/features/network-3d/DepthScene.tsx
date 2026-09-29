import { useEffect, useRef, useState } from 'react'

import type { DepthSceneCommunication, DepthSceneSegment } from '../depth-profile/depth-path'
import { VERTICAL_EXAGGERATION } from '../depth-profile/depth-path'
import { createDepthSceneAdapter, type DepthSceneAdapter } from './three-adapter'

export function DepthScene({ segments, communications = [] }: { readonly segments: readonly DepthSceneSegment[]; readonly communications?: readonly DepthSceneCommunication[] }) {
  const host = useRef<HTMLDivElement>(null)
  const adapter = useRef<DepthSceneAdapter | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [selectedSection, setSelectedSection] = useState(0)
  const sceneKey = segments.map((segment) => `${segment.feature.properties.id.kind}:${segment.feature.properties.id.value}:${segment.start.x}:${segment.start.y}:${segment.end.x}:${segment.end.y}`).join('|')
  const communicationsKey = communications.map((communication) => `${communication.kind}:${communication.start.x}:${communication.start.y}:${communication.end.x}:${communication.end.y}`).join('|')
  useEffect(() => {
    if (!sceneKey) return
    if (!host.current) return
    try { adapter.current = createDepthSceneAdapter(host.current, segments, communications, { onSelect: (index) => { adapter.current?.setSelectedSection(index); setSelectedSection(index) } }) }
    catch { queueMicrotask(() => setError('3D-сцена недоступна в этом браузере. Откройте карту или профиль.')) }
    return () => { adapter.current?.destroy(); adapter.current = null }
    // `sceneKey` and `communicationsKey` deliberately detect value changes, not new array identities.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sceneKey, communicationsKey])
  if (segments.length === 0) return <p className="depth-empty">Нет участков с глубиной для 3D-сцены.</p>
  const first = segments[0]!
  const selected = segments[selectedSection] ?? first
  const last = segments.at(-1)!
  const depths = segments.flatMap((segment) => [segment.depthStart, segment.depthEnd])
  const depthRange = `${Math.min(...depths).toLocaleString('ru-RU', { minimumFractionDigits: 1 })}–${Math.max(...depths).toLocaleString('ru-RU', { minimumFractionDigits: 1 })} м`
  const length = Math.round(last.distanceEndM - first.distanceStartM)
  if (error) return <p className="depth-empty" role="alert">{error}</p>
  return <section className="depth-scene" aria-label="3D-сцена тепловой сети">
    <div className="depth-scene-toolbar"><div><strong>3D-сцена выбранного пути</strong><span>Глубина показана вертикальными направляющими · вертикальный масштаб ×{VERTICAL_EXAGGERATION}</span></div><button type="button" onClick={() => adapter.current?.resetView()}>Сбросить ракурс</button></div>
    <div className="depth-scene-canvas" ref={host} />
    <aside className="depth-scene-info" aria-label="Свойства выбранного участка">
      <div className="depth-scene-legend"><span><i className="surface-swatch" />Условная поверхность</span><span><i className="pipe-swatch" />Расчётная оболочка трубы</span>{communications.length > 0 && <span>Коммуникации: {communications.length}</span>}</div>
      <dl><div><dt>Участок</dt><dd>Участок {selectedSection + 1}</dd></div><div><dt>Диапазон глубин</dt><dd>{depthRange}</dd></div><div><dt>Диаметр</dt><dd>ДУ {selected.feature.properties.diameter ?? '—'}</dd></div><div><dt>Длина пути</dt><dd>{length} м</dd></div></dl>
    </aside>
  </section>
}
