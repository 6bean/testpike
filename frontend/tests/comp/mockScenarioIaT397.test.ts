/**
 * layout contract for the standalone scenario surface (RED first).
 * Authority = the 2026-10-14 IA ruling doc (mock information-architecture fix)
 * plus the display-scale / ownership-echo iron laws:
 * - the scenario list page consumes the paged door (pageScenarios) with a real
 * el-pagination and a live instant search — never an unbounded full render;
 * - the heavy asset (the state-machine SVG) renders one per screen inside the
 * addressable detail page, never as a page-level v-for on the list;
 * - the docs page keeps only a summary bar (count/health/jump) — its inline
 * full-graph block is retired;
 * - the list page carries the ownership-echo trio (read-only echo in the create
 * dialog + disabled create without header context + visible project segment)
 * and shares the graph layout math from one module (no forked copy).
 */
import { describe, expect, it } from 'vitest'

import listRaw from '@/views/mock/MockScenarioListView.vue?raw'
import detailRaw from '@/views/mock/MockScenarioDetailView.vue?raw'
import docsRaw from '@/views/mock/MockDocsView.vue?raw'
import layoutRaw from '@/utils/mockScenarioGraphLayout.ts?raw'

describe('T397 scenario list page: bounded window + screening + no inline heavy asset', () => {
  it('consumes the paged door and renders a pager (display-scale iron law)', () => {
    expect(listRaw).toMatch(/pageScenarios\(/)
    expect(listRaw).toMatch(/<el-pagination/)
    // the page must not fall back to the unbounded full-list door
    expect(listRaw).not.toMatch(/listScenarios\(/)
  })

  it('carries an instant search over the loaded window (name filter)', () => {
    expect(listRaw).toMatch(/data-test="mock-scenarios-search"/)
    expect(listRaw).toMatch(/keyword/)
  })

  it('renders no state-machine SVG inline — the graph lives on the detail page', () => {
    expect(listRaw).not.toMatch(/<svg/)
    expect(listRaw).toMatch(/data-test="mock-scenarios-row"/)
    // a row points at the addressable detail route with the space context riding
    expect(listRaw).toMatch(/mock\/scenarios\/\$\{/)
  })

  it('holds zero selectors in the action slot (shell retired; the write entry rides the filter-bar leading slot) and rides the global context', () => {
    // R23 stage-2: the title shell (and its header-actions row) is gone —
    // the create entry lives at the head of the filter-bar row.
    expect(listRaw).not.toMatch(/<div class="header-actions">/)
    expect(listRaw).toContain("import { useProjectPageContext } from '@/composables/useProjectPageContext'")
    // the space picker lives in the filter-bar row (shared theme.scss class)
    expect(listRaw).toMatch(/class="filter-bar"/)
  })

  it('wires the ownership-echo trio (dialog echo + disabled create + slice visible)', () => {
    expect(listRaw).toContain('ProjectOwnerEcho')
    expect(listRaw).toMatch(/:disabled="!projectId"/)
    // R23 stage-2: the in-page "?" retired with the shell — help rides the
    // single topbar dot (data-test=header-help-btn, pinned in helpDrawer.test.ts)
    expect(listRaw).not.toMatch(/openHelp\('T', t\('layout\.leafHelpAnchor\.mockScenarios'\)/)
  })
})

describe('T397 scenario detail page: one heavy asset per screen, addressable', () => {
  it('renders the graph via the shared layout module (no forked math)', () => {
    expect(detailRaw).toMatch(/from '@\/utils\/mockScenarioGraphLayout'/)
    expect(detailRaw).toMatch(/buildLayout\(/)
    expect(detailRaw).toMatch(/<svg/)
  })

  it('keeps a return door that preserves the space context (deep-link discipline)', () => {
    expect(detailRaw).toMatch(/data-test="mock-scenario-detail-back"/)
    expect(detailRaw).toMatch(/spaceId/)
  })
})

describe('T397 docs page: scenario block collapsed to a summary bar', () => {
  it('drops the inline full-graph render and the local layout math', () => {
    expect(docsRaw).not.toMatch(/scenario-cards/)
    expect(docsRaw).not.toMatch(/<svg[\s\S]*?scenario-graph/)
    expect(docsRaw).not.toMatch(/function buildLayout/)
    // the shared module is now the single source of the layout math
    expect(layoutRaw).toMatch(/export function buildLayout/)
  })

  it('shows the summary bar with the jump to the standalone list (space context rides)', () => {
    expect(docsRaw).toMatch(/data-test="mock-docs-scenario-summary"/)
    expect(docsRaw).toMatch(/\/mock\/scenarios/)
  })

  it('adds instant search and path grouping to the stub doc area (collapse by default)', () => {
    expect(docsRaw).toMatch(/data-test="mock-docs-stub-search"/)
    expect(docsRaw).toMatch(/data-test="mock-docs-stub-group"/)
    expect(docsRaw).toMatch(/expandedGroups/)
  })
})
