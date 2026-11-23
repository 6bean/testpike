/**
 * Shared source-level wiring assertions for every list panel that consumes
 * the platform-wide useListAutoFollow beat (R40 single source: the contract
 * family — journey/report/mock — checks the same trio of imports and the same
 * "no second polling channel" shape, so the shape lives here once instead of
 * being copied per page).
 */
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { expect } from 'vitest'

const testDir = dirname(fileURLToPath(import.meta.url))

/** Read a repo file relative to the contract-test directory (shared loader, R40). */
export function readTestSource(rel: string): string {
  return readFileSync(resolve(testDir, rel), 'utf8')
}

/** The panel must ride the shared composable and the hot-reloadable beat key. */
export function expectSharedBeatImports(viewSrc: string): void {
  expect(viewSrc).toContain("import { useListAutoFollow } from '@/composables/useListAutoFollow'")
  expect(viewSrc).toContain("import { listAutoFollowMs } from '@/composables/tailWindowConfig'")
  expect(viewSrc).toContain('intervalMs: () => listAutoFollowMs.value')
}

/** No page-local timer may survive next to the shared beat. */
export function expectNoSecondPollingChannel(viewSrc: string): void {
  expect(viewSrc).not.toMatch(/setInterval\(/)
  expect(viewSrc).not.toMatch(/new Promise\(\s*\(r\)\s*=>\s*setTimeout/)
}
