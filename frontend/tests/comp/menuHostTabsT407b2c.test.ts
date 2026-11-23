/**
 * AN-5 (cases half) — tab-host layout contract.
 *
 * segment 1 retired /suites as a menu leaf and turned the old path into a
 * query-preserving redirect (routes.ts: /suites -> /cases?tab=suites);
 * gave SuiteListView the embedded frame switch. This file is the
 * RED anchor for the second half of CaseListView becomes the tab
 * host —
 * - the host renders an el-tabs bar (data-test=case-page-tabs) with
 * case-tab-cases / case-tab-suites label anchors, so ⌘K/redirect/
 * breadcrumb land on one visible carrier;
 * - the suites child renders with `embedded` (no frame-in-frame);
 * - the tab state is URL-first: query value wins on entry, unknown keys
 * fall back to the cases tab, switching writes the query back through
 * router.replace (undefined on the default tab = no history spam);
 * - unlike the skill/tool hosts, CaseList IS keep-alive cached
 * (MainLayout CACHED_LIST_VIEWS): re-entering from the sidebar after
 * leaving on ?tab=suites revives the instance without setup and the
 * query watcher never fires (tab undefined -> undefined), so an
 * onActivated re-read of ?tab= is mandatory (BatchListView
 * precedent).
 */
import { describe, expect, it } from 'vitest'
// Source-as-text reads ride the vite ?raw suffix ( precedent).
import caseRaw from '@/views/case/CaseListView.vue?raw'
import suiteRaw from '@/views/suite/SuiteListView.vue?raw'
import routesRaw from '@/router/routes.ts?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

describe('T407 slice 2b (cases half): /cases carries the suites tab', () => {
  it('renders the tab bar with cases/suites labels', () => {
    expect(caseRaw, 'host tabs bar missing').toContain('data-test="case-page-tabs"')
    expect(caseRaw, 'cases tab label anchor missing').toContain('data-test="case-tab-cases"')
    expect(caseRaw, 'suites tab label anchor missing').toContain('data-test="case-tab-suites"')
  })

  it('renders SuiteListView embedded inside the host', () => {
    expect(caseRaw, 'SuiteListView not imported').toMatch(/import SuiteListView from/)
    expect(caseRaw, 'SuiteListView not rendered embedded').toMatch(/<SuiteListView\s+embedded/)
    // The child keeps its own frame switch — guard against regressions.
    expect(suiteRaw, 'SuiteListView lost its embedded prop').toMatch(/embedded/)
  })

  it('keeps the tab state URL-first with replace write-back', () => {
    // Query wins on entry; anything but the suites word is the cases tab.
    expect(caseRaw, 'query-first tab init missing').toContain(
      "route.query.tab === 'suites' ? 'suites' : 'cases'",
    )
    // Switching writes back through replace (undefined = default tab).
    expect(caseRaw, 'replace write-back missing').toContain(
      "void router.replace({ query: { ...route.query, tab: tab === 'cases' ? undefined : tab } })",
    )
    // In-app navigations that only change ?tab= reuse the instance.
    expect(caseRaw, 'route.query.tab watcher missing').toMatch(
      /watch\(\(\) => route\.query\.tab[\s\S]{0,120}pageTab\.value =/,
    )
    // keep-alive revival re-reads the URL (BatchListView L406 precedent).
    expect(caseRaw, 'onActivated re-read missing').toMatch(
      /onActivated\(\(\) => \{[\s\S]{0,80}pageTab\.value = tabFromQuery\(\)/,
    )
  })
})

describe('T407 slice 2b (cases half): the redirect and i18n riders stay in place', () => {
  it('the retired /suites path still redirects keeping ?tab=suites', () => {
    expect(routesRaw, '/suites redirect lost').toMatch(
      /path: 'suites',[\s\S]{0,200}redirect: \(to\) => \(\{ path: '\/cases', query: \{ \.\.\.to\.query, tab: 'suites' \} \}\)/,
    )
  })

  it('both locales carry the tab-label keys (dual track, no hard-coded text)', () => {
    expect(zhRaw, 'zh tab keys missing').toContain('pageTabCases:')
    expect(zhRaw, 'zh suites tab key missing').toContain('pageTabSuites:')
    expect(enRaw, 'en tab keys missing').toContain('pageTabCases:')
    expect(enRaw, 'en suites tab key missing').toContain('pageTabSuites:')
    // The template must read the keys, not inline CJK labels.
    expect(caseRaw, 'cases label not keyed').toContain("t('case.pageTabCases')")
    expect(caseRaw, 'suites label not keyed').toContain("t('case.pageTabSuites')")
  })
})
