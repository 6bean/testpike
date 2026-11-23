// Chain-scoped dev-server wrapper for verification runs that need their own
// backend lane. It reuses the shared vite.config.ts as the single source and
// overrides ONLY the listen port and the /api proxy target through environment
// knobs, so a verification chain can drive its own admin backend on its own
// ports without touching the developer defaults (main lane stays 5173 -> 8081).
import baseConfig from './vite.config'

const port = Number(process.env.CHAIN_VITE_PORT ?? 5273)
const apiTarget = process.env.CHAIN_ADMIN_TARGET ?? 'http://localhost:18081'
const openTarget = process.env.CHAIN_OPEN_TARGET ?? 'http://localhost:8082'

export default {
  ...baseConfig,
  server: {
    ...(baseConfig.server ?? {}),
    port,
    proxy: {
      '/api': { target: apiTarget, changeOrigin: true },
      '/open': { target: openTarget, changeOrigin: true },
    },
  },
}
