/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_MODE?: 'fixture' | 'http'
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
