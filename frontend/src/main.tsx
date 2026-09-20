import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './app/App'
import { AppProviders } from './app/providers'
import { resolveApiMode } from './shared/api/create-api'
import './app/styles.css'

const apiMode = resolveApiMode(import.meta.env.VITE_API_MODE, import.meta.env.PROD)
const root = document.getElementById('root')

if (!root) {
  throw new Error('Не найден корневой элемент приложения')
}

createRoot(root).render(
  <StrictMode>
    <AppProviders>
      <App apiMode={apiMode} />
    </AppProviders>
  </StrictMode>,
)
