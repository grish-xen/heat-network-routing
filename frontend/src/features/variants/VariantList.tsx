import type { VariantSummary } from '../../shared/model/api'
import { objectIdKey } from '../../shared/model/object-id'
import { formatMetres, formatRubles, formatScore } from './format-metrics'

interface VariantListProps {
  readonly variants: readonly VariantSummary[]
  readonly selectedKey: string
  readonly onSelect: (variant: VariantSummary) => void
}

export function VariantList({ variants, selectedKey, onSelect }: VariantListProps) {
  const ranked = [...variants].sort((left, right) => left.rank - right.rank)
  return (
    <div className="variant-list" role="radiogroup" aria-label="Варианты трассировки">
      {ranked.map((variant) => {
        const key = objectIdKey(variant.variantId)
        const selected = key === selectedKey
        return (
          <label className={`variant-card${selected ? ' variant-card--selected' : ''}`} key={key}>
            <input
              type="radio"
              name="variant"
              value={key}
              checked={selected}
              aria-label={`Вариант ${variant.rank}`}
              onChange={() => onSelect(variant)}
            />
            <span className="variant-rank">#{variant.rank}</span>
            <strong>Вариант {variant.rank}</strong>
            <span>{formatRubles(variant.calculatedCost)}</span>
            <small>{formatMetres(variant.newNetworkLength)} · score {formatScore(variant.score)}</small>
          </label>
        )
      })}
    </div>
  )
}
