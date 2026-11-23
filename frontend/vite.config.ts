import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Version authority for the frontend bundle = package.json version (CI release
// bumps it alongside backend -Drevision); build clock stamped at config time.
// The upgrade center banner consumes __APP_VERSION__ so operators can see
// which bundle is running, mirroring backend /actuator/info build sections.
const pkg = JSON.parse(
  readFileSync(fileURLToPath(new URL('./package.json', import.meta.url)), 'utf8'),
) as { version: string }
const appBuildTime = new Date().toISOString()

/** Writes dist/version.json next to the bundle (same source as define). */
function versionStamp() {
  return {
    name: 'version-stamp',
    apply: 'build' as const,
    closeBundle() {
      const outDir = fileURLToPath(new URL('./dist', import.meta.url))
      mkdirSync(outDir, { recursive: true })
      writeFileSync(
        join(outDir, 'version.json'),
        `${JSON.stringify({ version: pkg.version, buildTime: appBuildTime }, null, 2)}\n`,
      )
    },
  }
}

// Shared proxy map consumed by BOTH the dev and the preview servers. Kept in
// one const on purpose: the detached E2E window recipes serve the built bundle
// through `vite preview`, and a duplicated map would drift out of sync with the
// dev one. Targets are env-overridable so a second stack (parallel chain /
// isolated verification) points at its own admin/api ports without touching the
// shipped 8081/8082 defaults. Resolution order mirrors the chain environment
// loader: an explicit TP_ADMIN_PORT/TP_API_PORT override wins, otherwise the
// install-time ADMIN_PORT/API_PORT keys exported by the chain .env are used
// (a slot chain that only sources its own .env must still proxy to its own
// stack — measured failure: browser traffic silently rode the main chain's
// admin on 8081, so a slot-only account 404'd there and was force-redirected
// to the password-expired door). '/api' rides the admin service
// (auth.mode=local); public share-link and badge doors ride the api service
// via the same-origin '/open' path so a frozen snapshot reads without login.
const proxyMap = {
  '/api': {
    target: `http://127.0.0.1:${process.env.TP_ADMIN_PORT || process.env.ADMIN_PORT || '8081'}`,
    changeOrigin: true,
  },
  '/open': {
    target: `http://127.0.0.1:${process.env.TP_API_PORT || process.env.API_PORT || '8082'}`,
    changeOrigin: true,
  },
}

export default defineConfig({
  plugins: [vue(), versionStamp()],
  define: {
    __APP_VERSION__: JSON.stringify(pkg.version),
    __APP_BUILD_TIME__: JSON.stringify(appBuildTime),
  },
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: Number(process.env.VITE_PORT || '5173'),
    host: '0.0.0.0',
    proxy: proxyMap,
  },
  preview: {
    // The detached E2E windows serve the built bundle here. Without this the
    // preview server answers every relative '/api' call itself (404/501), the
    // SPA session bootstrap fails and tests get stuck on the login page.
    port: Number(process.env.VITE_PORT || '4173'),
    host: '0.0.0.0',
    proxy: proxyMap,
  },
  build: {
    outDir: 'dist',
    chunkSizeWarningLimit: 1500,
    rollupOptions: {
      output: {
        manualChunks(id: string) {
          if (id.includes('node_modules/element-plus') || id.includes('node_modules/@element-plus')) return 'element-plus'
          if (/node_modules\/(vue|vue-router|pinia|axios|vue-i18n|@vue)\//.test(id)) return 'vendor'
        },
      },
    },
  },
})
