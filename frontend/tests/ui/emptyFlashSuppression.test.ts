// Contract guard for the site-wide empty-flash standard (user order
// 2026-11-02): the shared table shell must never paint empty-state copy
// while the first load is in flight, and the theme must keep the single
// global mask rule. Source-on-disk assertions per tests/ui convention.
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const SHELL = readFileSync(join(__dirname, '../../src/components/DataTablePage.vue'), 'utf-8')
const THEME = readFileSync(join(__dirname, '../../src/styles/theme.scss'), 'utf-8')

describe('empty-flash suppression single sources', () => {
  it('shell renders skeleton (not EmptyState) while loading', () => {
    expect(SHELL).toMatch(/<TableSkeleton v-if="loading"/)
    expect(SHELL).toMatch(/<EmptyState v-else/)
  })

  it('theme hides the empty block behind any active loading mask', () => {
    expect(THEME).toMatch(/\.el-table:has\(\>\s*\.el-loading-mask\)\s*\.el-table__empty-block/)
    expect(THEME).toMatch(/visibility:\s*hidden/)
  })

  it('ledger gate exists for raw page debt', () => {
    const gate = readFileSync(join(__dirname, '../../../tools/empty_flash_ledger.py'), 'utf-8')
    expect(gate).toContain('--emit-baseline')
    expect(gate).toContain('--check')
  })
})
