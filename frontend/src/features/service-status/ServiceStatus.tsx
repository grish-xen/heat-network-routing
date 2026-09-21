import type { HeatNetworkApi } from '../../shared/api/contracts'
import { useHealth } from './use-health'

export function ServiceStatus({ api }: { readonly api: HeatNetworkApi }) {
  const health = useHealth(api)
  const available = health.data?.status === 'UP'
  const label = health.isPending ? 'Проверка API' : available ? 'API доступен' : 'API недоступен'

  return (
    <span className={`api-status api-status--${available ? 'available' : health.isPending ? 'pending' : 'unavailable'}`}>
      <span className="status-dot" aria-hidden="true" />
      {label}
    </span>
  )
}
