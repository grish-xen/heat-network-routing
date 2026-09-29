import { useEffect, useRef, useState } from 'react'

import type { DepthSceneSegment } from '../depth-profile/depth-path'
import { VERTICAL_EXAGGERATION } from '../depth-profile/depth-path'
import { createDepthSceneAdapter, type DepthSceneAdapter } from './three-adapter'

export function DepthScene({ segments }: { readonly segments: readonly DepthSceneSegment[] }) {
  const host = useRef<HTMLDivElement>(null)
  const adapter = useRef<DepthSceneAdapter | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    if (segments.length === 0) return
    if (!host.current) return
    try { adapter.current = createDepthSceneAdapter(host.current, segments) }
    catch { queueMicrotask(() => setError('3D-сцена недоступна в этом браузере. Откройте карту или профиль.')) }
    return () => { adapter.current?.destroy(); adapter.current = null }
  }, [segments])
  if (segments.length === 0) return <p className="depth-empty">Нет участков с глубиной для 3D-сцены.</p>
  const first = segments[0]!
  const last = segments.at(-1)!
  const depths = segments.flatMap((segment) => [segment.depthStart, segment.depthEnd])
  const depthRange = `${Math.min(...depths).toLocaleString('ru-RU', { minimumFractionDigits: 1 })}–${Math.max(...depths).toLocaleString('ru-RU', { minimumFractionDigits: 1 })} м`
  const length = Math.round(last.distanceEndM - first.distanceStartM)
  if (error) return <p className="depth-empty" role="alert">{error}</p>
  return <section className="depth-scene" aria-label="3D-сцена тепловой сети">
    <div className="depth-scene-toolbar"><div><strong>3D-сцена выбранного пути</strong><span>Глубина показана вертикальными направляющими · вертикальный масштаб ×{VERTICAL_EXAGGERATION}</span></div><button type="button" onClick={() => adapter.current?.resetView()}>Сбросить ракурс</button></div>
    <div className="depth-scene-canvas" ref={host} />
    <aside className="depth-scene-info" aria-label="Свойства выбранного участка">
      <div className="depth-scene-legend"><span><i className="surface-swatch" />Условная поверхность</span><span><i className="pipe-swatch" />Расчётная оболочка трубы</span></div>
      <dl><div><dt>Диапазон глубин</dt><dd>{depthRange}</dd></div><div><dt>Диаметр</dt><dd>ДУ {first.feature.properties.diameter ?? '—'}</dd></div><div><dt>Длина пути</dt><dd>{length} м</dd></div></dl>
    </aside>
  </section>
}
