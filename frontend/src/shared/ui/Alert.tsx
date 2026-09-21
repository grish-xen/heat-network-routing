import type { PropsWithChildren } from 'react'

export function Alert({ children }: PropsWithChildren) {
  return (
    <div className="alert" role="alert">
      {children}
    </div>
  )
}
