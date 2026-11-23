/**
 * layout contract for the API-test workbench rebuild.
 *
 * Posture = the ?raw source mirror used by runsetT369.test.ts: assertions
 * read the shipped template text of ApiTestView.vue, so a regression that
 * re-introduces the nested-selector maze (project select inside the page,
 * collection/request dropdowns) fails here before the browser renders it.
 *
 * Clauses (design ruling for the information-architecture rebuild):
 * 1. no page-level project selector — the page consumes the global header
 * project context through useProjectPageContext (single-source AK rule);
 * 2. no dropdown-maze selectors — the old collection/request el-select
 * pickers are replaced by two visible lists in the left rail;
 * 3. left rail carries BOTH entity lists: collection cards
 * (data-test=apitest-collection-list) and the request rows of the
 * selected collection (data-test=apitest-request-list);
 * 4. each list has its own instant search box (SE rule: filter control
 * lives next to the list it filters) — collection filter is new,
 * request filter keeps its existing anchor;
 * 5. collection-row actions are folded into a per-row "more" dropdown
 * (≤3 visible-button discipline), not a flat toolbar dump;
 * 6. project-level doors (spec import / subscription / mock bridge /
 * stub wizard) move into a single "data source" menu, clearing the
 * toolbar of mixed-scope actions.
 */
import { describe, expect, it } from 'vitest'

import viewRaw from '@/views/apitest/ApiTestView.vue?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'
import prefsRaw from '@/composables/useRailWidthPrefs.ts?raw'

describe('T413 blade-1: single project channel', () => {
  it('never mounts the in-page ProjectSelect component', () => {
    expect(viewRaw).not.toMatch(/import\s+\w+\s+from\s+.*ProjectSelect\.vue/)
    expect(viewRaw).not.toMatch(/<ProjectSelect/)
  })

  it('consumes the global header project context via the shared wiring composable', () => {
    expect(viewRaw).toContain("import { useProjectPageContext } from '@/composables/useProjectPageContext'")
    expect(viewRaw).toContain('useProjectPageContext()')
  })
})

describe('T413 blade-1: two visible lists replace the dropdown maze', () => {
  it('drops the collection dropdown selector', () => {
    expect(viewRaw).not.toContain('apitest-collection-select')
  })

  it('drops the request dropdown selector', () => {
    expect(viewRaw).not.toContain('apitest-request-select')
  })

  it('renders the collection card list in the left rail', () => {
    expect(viewRaw).toContain('data-test="apitest-collection-list"')
  })

  it('renders the request row list of the selected collection', () => {
    expect(viewRaw).toContain('data-test="apitest-request-list"')
  })

  it('keeps an instant search box for each list (SE rule 3 worst-scale reachability)', () => {
    expect(viewRaw).toContain('data-test="apitest-collection-filter"')
    expect(viewRaw).toContain('data-test="apitest-request-filter"')
  })

  it('shows the interface count badge on every collection card', () => {
    expect(viewRaw).toContain('data-test="apitest-collection-count"')
  })
})

describe('T413 blade-1: actions grouped by their object', () => {
  it('folds collection row actions into a per-row more dropdown', () => {
    expect(viewRaw).toContain('data-test="apitest-collection-more"')
  })

  it('moves project-level doors into a single data-source menu', () => {
    expect(viewRaw).toContain('data-test="apitest-datasource-menu"')
    // the four project-level entries live inside that dropdown, not on the toolbar
    expect(viewRaw).toMatch(/apitest-datasource-menu[\s\S]*apitest-import-from-spec-btn/)
    expect(viewRaw).toMatch(/apitest-datasource-menu[\s\S]*apitest-subscription-btn/)
    expect(viewRaw).toMatch(/apitest-datasource-menu[\s\S]*apitest-view-in-mock/)
    expect(viewRaw).toMatch(/apitest-datasource-menu[\s\S]*apitest-stub-direct-btn/)
  })

  it('keeps request-level actions in the right workspace header', () => {
    expect(viewRaw).toMatch(/apitest-workspace-head[\s\S]*apitest-promote-btn/)
    expect(viewRaw).toMatch(/apitest-workspace-head[\s\S]*apitest-save-as-perf-btn/)
  })

  // stage 4 (action-area consolidation, page-responsibility rubric 1.1):
  // the two cross-domain exits are folded behind one "More" dropdown so the
  // workspace header never shows a flat row of mixed-scope buttons.
  it('folds the cross-domain exits into a single More dropdown', () => {
    expect(viewRaw).toContain('data-test="apitest-more-actions-menu"')
    expect(viewRaw).toMatch(/apitest-more-actions-menu[\s\S]*apitest-promote-btn/)
    expect(viewRaw).toMatch(/apitest-more-actions-menu[\s\S]*apitest-save-as-perf-btn/)
    // both fold into the same anchored dropdown, still inside the header
    expect(viewRaw).toMatch(/ap-workspace-actions[\s\S]*apitest-more-actions-menu/)
  })

  it('plain-language collection concept line ships on the interface (ruling: concept explained on-screen)', () => {
    expect(viewRaw).toContain("t('apitest.collectionConcept')")
  })
})

describe('T413 stage-2: naming ruling (collection -> endpoint set in UI vocabulary)', () => {
  it('apitest zh block carries zero bare 集合 — every surface string says 接口集', () => {
    const apitestSection = zhRaw.slice(zhRaw.indexOf('  apitest: {'))
    const end = apitestSection.indexOf('\n  },\n')
    const body = apitestSection.slice(0, end === -1 ? apitestSection.length : end)
    expect(body).not.toMatch(/集合/)
    expect(body).toContain('接口集')
  })

  it('english side keeps Collection untouched (Postman-standard word, ruling: EN unchanged)', () => {
    const enSection = enRaw.slice(enRaw.indexOf('  apitest: {'))
    const end = enSection.indexOf('\n  },\n')
    const body = enSection.slice(0, end === -1 ? enSection.length : end)
    expect(body).toMatch(/[Cc]ollection/)
  })
})

describe('T413 stage-2: rail width preference (drag 280~480)', () => {
  it('the rail mounts a right-edge grabber wired to mousedown + dblclick reset', () => {
    expect(viewRaw).toContain('data-test="apitest-rail-resizer"')
    expect(viewRaw).toContain('@mousedown="railResizeStart"')
    expect(viewRaw).toContain('@dblclick="railResizeReset"')
  })

  it('rail width is a per-user localStorage preference, never a server key', () => {
    expect(prefsRaw).toContain("localStorage.setItem(`${RAIL_WIDTH_KEY}:${userId ?? ANON_BUCKET}`")
    expect(prefsRaw).toContain('RAIL_MIN_WIDTH = 280')
    expect(prefsRaw).toContain('RAIL_MAX_WIDTH = 480')
    expect(prefsRaw).not.toMatch(/fetch|axios|api\//i)
  })

  it('the interface-count badge consumes the server column when present', () => {
    expect(viewRaw).toContain("if (typeof c.requestCount === 'number') return c.requestCount")
  })
})
