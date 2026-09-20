import type { VariantSummary } from '../../shared/model/api'
import { formatMetres, formatRubles, formatScore } from './format-metrics'

export function VariantComparison({ variant }: { readonly variant: VariantSummary }) {
  const metrics = [
    ['Итоговая стоимость', formatRubles(variant.calculatedCost)],
    ['Длина новой сети', formatMetres(variant.newNetworkLength)],
    ['Оценка', formatScore(variant.score)],
    ['Строительство камер', formatRubles(variant.chamberConstructionCost)],
    ['Врезки в существующие камеры', String(variant.existingChamberTieInCount)],
    ['Стоимость врезок', formatRubles(variant.existingChamberTieInCost)],
    ['Штраф за неподключённые точки', formatRubles(variant.unconnectedPenalty)],
    ['Неподключённые точки', String(variant.unconnectedOksIds.length)],
  ] as const
  return (
    <dl className="metric-grid" aria-label={`Метрики варианта ${variant.rank}`}>
      {metrics.map(([label, value]) => (
        <div key={label}>
          <dt>{label}</dt>
          <dd>{value}</dd>
        </div>
      ))}
    </dl>
  )
}
