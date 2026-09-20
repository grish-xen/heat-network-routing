import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { App } from './App'

describe('App', () => {
  it('identifies the service and fixture data honestly', () => {
    render(<App apiMode="fixture" />)

    expect(
      screen.getByRole('heading', { name: /моделирование тепловых сетей/i }),
    ).toBeVisible()
    expect(screen.getByText('Демонстрационные данные')).toBeVisible()
  })
})
