import { useState } from 'react'

import { MAP_LEGEND_ITEMS, type LayerGroup } from './map-style'

interface MapLegendProps {
  readonly setLayerGroupVisibility: (group: LayerGroup, visible: boolean) => void
}

export function MapLegend({ setLayerGroupVisibility }: MapLegendProps) {
  const [visibility, setVisibility] = useState<Record<LayerGroup, boolean>>({
    network: true, nodes: true, connections: true, restrictions: true,
  })
  return (
    <fieldset className="map-legend">
      <legend>Слои карты</legend>
      {MAP_LEGEND_ITEMS.map((item) => (
        <label key={item.group}>
          <input
            type="checkbox"
            checked={visibility[item.group]}
            onChange={(event) => {
              const visible = event.target.checked
              setVisibility((current) => ({ ...current, [item.group]: visible }))
              setLayerGroupVisibility(item.group, visible)
            }}
          />
          <span className="legend-swatch" style={{ backgroundColor: item.color }} aria-hidden="true" />
          {item.label}
        </label>
      ))}
    </fieldset>
  )
}
