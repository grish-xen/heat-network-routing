import { useEffect, useRef, useState } from 'react'

import type { DepthSceneSegment } from '../depth-profile/depth-path'
import { VERTICAL_EXAGGERATION } from '../depth-profile/depth-path'
import { createDepthSceneAdapter, type DepthSceneAdapter } from './three-adapter'

export function DepthScene({ segments }: { readonly segments: readonly DepthSceneSegment[] }) {
  const host = useRef<HTMLDivElement>(null)
  const adapter = useRef<DepthSceneAdapter | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    if (!host.current) return
    try { adapter.current = createDepthSceneAdapter(host.current, segments) }
    catch { queueMicrotask(() => setError('3D-сцена недоступна в этом браузере. Откройте карту или профиль.')) }
    return () => { adapter.current?.destroy(); adapter.current = null }
  }, [segments])
  if (error) return <p className="depth-empty" role="alert">{error}</p>
  return <section className="depth-scene" aria-label="3D-сцена тепловой сети">
    <div className="depth-scene-toolbar"><span>Вертикальный масштаб ×{VERTICAL_EXAGGERATION}</span><button type="button" onClick={() => adapter.current?.resetView()}>Сбросить ракурс</button></div>
    <div className="depth-scene-canvas" ref={host} />
  </section>
}
