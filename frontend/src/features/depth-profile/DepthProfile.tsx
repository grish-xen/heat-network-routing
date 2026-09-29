import type { DepthSceneSegment } from './depth-path'
import { objectIdKey } from '../../shared/model/object-id'

interface DepthProfileProps {
  readonly segments: readonly DepthSceneSegment[]
}

function depthText(value: number): string {
  return `${value.toLocaleString('ru-RU', { minimumFractionDigits: 1, maximumFractionDigits: 1 })} м`
}

export function DepthProfile({ segments }: DepthProfileProps) {
  if (segments.length === 0) return <p className="depth-empty">Для профиля нет участков с глубиной.</p>
  const width = 720
  const height = 320
  const padding = { left: 56, right: 26, top: 30, bottom: 48 }
  const maxDistance = segments.at(-1)!.distanceEndM || 1
  const maxDepth = Math.max(...segments.flatMap((segment) => [segment.depthStart, segment.depthEnd]), 1)
  const x = (distance: number) => padding.left + (distance / maxDistance) * (width - padding.left - padding.right)
  const y = (depth: number) => padding.top + (depth / maxDepth) * (height - padding.top - padding.bottom)
  const points = segments.flatMap((segment, index) => [
    `${x(segment.distanceStartM)},${y(segment.depthStart)}`,
    ...(index === segments.length - 1 ? [`${x(segment.distanceEndM)},${y(segment.depthEnd)}`] : []),
  ]).join(' ')

  return (
    <section className="depth-profile" aria-label="Продольный профиль">
      <svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label="Продольный профиль выбранного пути">
        <line x1={padding.left} y1={padding.top} x2={padding.left} y2={height - padding.bottom} className="depth-profile-axis" />
        <line x1={padding.left} y1={height - padding.bottom} x2={width - padding.right} y2={height - padding.bottom} className="depth-profile-axis" />
        <polyline points={points} className="depth-profile-line" />
        {segments.map((segment, index) => (
          <g key={`${objectIdKey(segment.feature.properties.id)}:${index}`}>
            <circle cx={x(segment.distanceStartM)} cy={y(segment.depthStart)} r="4" className="depth-profile-point" />
          </g>
        ))}
        <text x={width / 2} y={height - 12} textAnchor="middle">Горизонтальное расстояние, м</text>
        <text x="16" y={height / 2} textAnchor="middle" transform={`rotate(-90 16 ${height / 2})`}>Глубина, м</text>
      </svg>
      <div className="depth-profile-values" aria-label="Глубины выбранного пути">
        <span>{depthText(segments[0]!.depthStart)}</span>
        <span>{depthText(segments.at(-1)!.depthEnd)}</span>
      </div>
    </section>
  )
}
