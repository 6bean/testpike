/// <reference types="vite/client" />

// Build-time constants injected by vite `define` (see vite.config.ts);
// same source as dist/version.json written at build time.
declare const __APP_VERSION__: string
declare const __APP_BUILD_TIME__: string

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
   
  const component: DefineComponent<{}, {}, any>
  export default component
}
