import type { ButtonHTMLAttributes, PropsWithChildren } from 'react'

type ButtonProps = PropsWithChildren<ButtonHTMLAttributes<HTMLButtonElement>>

export function Button({ className = '', children, ...props }: ButtonProps) {
  return (
    <button className={`button ${className}`.trim()} {...props}>
      {children}
    </button>
  )
}
