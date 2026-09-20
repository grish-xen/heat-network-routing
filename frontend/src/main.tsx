import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App, type ApiMode } from './app/App'
import { AppProviders } from './app/providers'
import './app/styles.css'

const apiMode: ApiMode = import.meta.env.VITE_API_MODE === 'http' ? 'http' : 'fixture'
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
