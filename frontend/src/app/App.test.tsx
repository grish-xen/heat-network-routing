import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { FixtureHeatNetworkApi } from '../shared/api/fixture-api'
import { App } from './App'
import { AppProviders } from './providers'

describe('App', () => {
  it('identifies the service and fixture data honestly', () => {
    render(
      <AppProviders>
        <App apiMode="fixture" api={new FixtureHeatNetworkApi()} />
      </AppProviders>,
    )

    expect(
      screen.getByRole('heading', { name: /моделирование тепловых сетей/i }),
    ).toBeVisible()
    expect(screen.getByText('Демонстрационные данные')).toBeVisible()
    expect(screen.getByRole('button', { name: /запустить расчёт/i })).toBeDisabled()
  })
})
