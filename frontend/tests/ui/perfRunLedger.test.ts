/**
 * Project-wide load-run ledger page contract .
 *
 * What this pins: the ledger is a first-class menu leaf beside the workbench,
 * sliced by the header project context (single-source rule R30 — no in-page
 * project selector), with the api mirror door (pageProjectPerfRuns carrying
 * the server sort window) and the run-ledger view consuming that door.
 *
 * Why a gate: the server door (listProjectPaged) already lists decoupled
 * history (runs whose scenario was deleted, A-13); without a menu-registered
 * page that history has no user-visible home — the workbench drawer is
 * scenario-keyed and hides exactly those rows.
 */
import { describe, expect, it } from 'vitest'
import { allMenuLeaves } from '../../src/config/menuStructure'
import zhCN from '../../src/utils/locales/zh-CN'
import enUS from '../../src/utils/locales/en-US'
import { menuSynonyms } from '../../src/config/menuSynonyms'
import routesRaw from '@/router/routes.ts?raw'
import apiPerfRaw from '@/api/perf.ts?raw'
import { lookupLocale } from '../_localeLookup'
import ledgerViewRaw from '@/views/perf/PerfRunLedgerView.vue?raw'

// The dotted-key walk is the shared spec helper (single source).
const lookup = lookupLocale

describe('GB-2 ledger leaf is registered everywhere a leaf must be', () => {
  it('menuStructure carries a /perf/runs leaf with help mapping', () => {
    const leaf = allMenuLeaves.find((l) => l.path === '/perf/runs')
    expect(leaf, '/perf/runs 叶缺失').toBeTruthy()
    expect(leaf!.titleKey).toBe('layout.perfRuns')
    expect(leaf!.helpDomain).toBe('W')
    expect(leaf!.helpTipKey).toBe('layout.leafHelp.perfRuns')
    expect(leaf!.dataTest).toBe('menu-perf-runs')
  })

  it('title/hover copy hit both locale tracks (i18n double-rail, red line 8)', () => {
    const zh = lookup(zhCN, 'layout.perfRuns')
    const en = lookup(enUS, 'layout.perfRuns')
    expect(typeof zh, 'zh 缺页名').toBe('string')
    expect(typeof en, 'en 缺页名').toBe('string')
    const tipZh = lookup(zhCN, 'layout.leafHelp.perfRuns')
    const tipEn = lookup(enUS, 'layout.leafHelp.perfRuns')
    expect(typeof tipZh, 'zh 缺悬浮文案').toBe('string')
    expect((tipZh as string).length).toBeGreaterThanOrEqual(8)
    expect(typeof tipEn, 'en 缺悬浮文案').toBe('string')
    expect((tipEn as string).length).toBeGreaterThanOrEqual(8)
  })

  it('the leaf has ⌘K synonyms anchored on its own path (no dangling key)', () => {
    const syn = (menuSynonyms as Record<string, string[]>)['/perf/runs']
    expect(Array.isArray(syn), '/perf/runs 无同义词表').toBe(true)
    expect(syn!.length).toBeGreaterThan(0)
  })

  it('routes register the ledger view behind the leaf path', () => {
    expect(routesRaw).toContain("path: 'perf/runs'")
    expect(routesRaw).toContain('PerfRunLedgerView.vue')
  })
})

describe('api mirror carries the project-wide ledger window door', () => {
  it('pageProjectPerfRuns exists with the server sort pass-through', () => {
    expect(apiPerfRaw).toContain('export function pageProjectPerfRuns')
    const fn = apiPerfRaw.slice(apiPerfRaw.indexOf('export function pageProjectPerfRuns'))
    expect(fn.slice(0, 800)).toContain('/perf/runs/project-page')
    expect(fn.slice(0, 800)).toContain('sortBy')
    expect(fn.slice(0, 800)).toContain('sortDir')
  })

  it('the ledger door carries the optional status/mark narrowing filters', () => {
    const fn = apiPerfRaw.slice(apiPerfRaw.indexOf('export function pageProjectPerfRuns'))
    expect(fn.slice(0, 1200)).toContain('status?: number')
    expect(fn.slice(0, 1200)).toContain('mark?: string')
  })

  it('PerfRunItem annotates the ledger read-model columns (scenario_name)', () => {
    expect(apiPerfRaw).toContain('scenario_name')
  })
})

describe('ledger view obeys the site-wide front-end iron laws', () => {
  it('consumes the header project context single source (R30, no in-page selector)', () => {
    expect(ledgerViewRaw).toContain('useProjectPageContext')
    expect(ledgerViewRaw, '页内禁第二项目选择器').not.toContain('ProjectSelect')
  })

  it('refresh parks in the filter-bar trailing slot (R21 bands)', () => {
    expect(ledgerViewRaw).toContain('filter-bar-actions')
  })

  it('server-side sort headers stay inside the core whitelist (sortable=custom)', () => {
    expect(ledgerViewRaw).toContain("'custom'")
    expect(ledgerViewRaw).toContain('sort-change')
    // scenario_name is an annotated read-model field, never a sort key (core refuses it)
    const anchor = ledgerViewRaw.indexOf('const LEDGER_SORT_MAP')
    const sortBlock = ledgerViewRaw.slice(anchor, ledgerViewRaw.indexOf('}', anchor) + 1)
    expect(sortBlock.length).toBeGreaterThan(100)
    expect(sortBlock).not.toContain('scenario_name')
  })

  it('page state rides the URL query (F15: page/size/sort restorable on reload)', () => {
    expect(ledgerViewRaw).toContain('route.query.page')
    expect(ledgerViewRaw).toContain('route.query.sortBy')
    expect(ledgerViewRaw).toContain('router.replace')
  })

  it('status/mark filters lead the band and ride the URL query (F15 + R21)', () => {
    expect(ledgerViewRaw).toContain('perf-ledger-filter-status')
    expect(ledgerViewRaw).toContain('perf-ledger-filter-mark')
    // filters sit before the trailing view-state slot (band order = screening
    // first, refresh/column-settings last — R21 three-band law)
    const band = ledgerViewRaw.indexOf('perf-ledger-filter-bar')
    const status = ledgerViewRaw.indexOf('perf-ledger-filter-status')
    const trailing = ledgerViewRaw.indexOf('filter-bar-actions')
    expect(status).toBeGreaterThan(band)
    expect(trailing).toBeGreaterThan(status)
    expect(ledgerViewRaw).toContain('route.query.status')
    expect(ledgerViewRaw).toContain('route.query.mark')
  })

  it('rows deep-link into the run detail carrying the slice (existing detail door)', () => {
    expect(ledgerViewRaw).toContain('/perf/run/')
  })
})
