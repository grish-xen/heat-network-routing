import type { ApiMode } from '../shared/api/create-api'

export function App({ apiMode }: { apiMode: ApiMode }) {
  return (
    <div className="app-shell">
      <header className="app-header">
        <div className="brand-lockup">
          <span className="brand-mark" aria-hidden="true">
            ТС
          </span>
          <div>
            <p className="eyebrow">ЛЦТ 2026 · инженерный сервис</p>
            <h1>Моделирование тепловых сетей</h1>
          </div>
        </div>
        <div className="header-status">
          <span className="api-status" aria-label="Состояние API">
            <span className="status-dot" aria-hidden="true" />
            Подключение к API
          </span>
          {apiMode === 'fixture' && (
            <span className="mode-badge">Демонстрационные данные</span>
          )}
        </div>
      </header>
      <main className="workspace" aria-label="Рабочая область">
        <section className="workspace-panel" aria-label="Параметры расчёта">
          <p className="section-kicker">Новый расчёт</p>
          <h2>Подключение объектов к сети</h2>
          <p className="intro-copy">
            Загрузите единый GeoJSON. Сервис проверит данные, построит варианты
            трасс и подготовит их для сравнения на карте.
          </p>
        </section>
        <section className="map-placeholder" aria-label="Карта результата">
          <div className="map-grid" aria-hidden="true" />
          <div className="map-empty-state">
            <span className="map-pin" aria-hidden="true" />
            <p>Карта появится после запуска расчёта</p>
          </div>
        </section>
      </main>
    </div>
  )
}
