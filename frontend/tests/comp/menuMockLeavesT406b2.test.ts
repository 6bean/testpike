/**
 * AN-4 menu-leaf retirement + contract migration layout gate.
 *
 * segment 1 merged the four screening pages (docs / scenarios / recording /
 * inspector) into the per-space workbench (/mock/spaces/:id?tab=…) and turned
 * the old paths into functional redirects. segment 2 moves the surrounding
 * ledges so the whole site tells one story — this file is its RED anchor:
 * - menuStructure: the mocking group goes 6 leaves -> 2 (spaces + endpoints).
 * - menuSynonyms: ⌘K words of a retired path ride the host path (
 * precedent); a synonym key must never dangle on a non-leaf path (the
 * menuSearch gate enforces it — we pin the merged words here).
 * - mockJump §12a return door + every door that already knows its space
 * targets the workbench path directly with the tab in the query (riding the
 * redirect would work but loses tab intent at the spaces-list fallback, so
 * the redirect fallback must carry the tab too and the list forwards it).
 * - the spaces list is the only menu door into a space — it must expose an
 * open-workbench row action (forwarding tab/source intent from its own URL).
 * Doors without a space id in hand keep pushing the retired path and resolve
 * honestly through the redirect (never a guessed space).
 */
import { describe, expect, it } from 'vitest'
// Source-as-text reads ride the vite ?raw suffix (browser-safe; same posture as
// the gate mockSpaceWorkbenchT406.test.ts — node:fs is externalized).
import menuRaw from '@/config/menuStructure.ts?raw'
import synonymsRaw from '@/config/menuSynonyms.ts?raw'
import jumpRaw from '@/utils/mockJump.ts?raw'
import routesRaw from '@/router/routes.ts?raw'
import spacesRaw from '@/views/mock/MockSpacesView.vue?raw'
import wizardRaw from '@/views/apitest/MockStubDirectWizard.vue?raw'
import endpointsRaw from '@/views/mock/MockEndpointsView.vue?raw'
import docsRaw from '@/views/mock/MockDocsView.vue?raw'
import scenarioDetailRaw from '@/views/mock/MockScenarioDetailView.vue?raw'

const RETIRED = ['/mock/docs', '/mock/scenarios', '/mock/recording', '/mock/inspector']

describe('T406 blade 2: mocking menu group collapses to two leaves', () => {
  it('the four retired paths are no longer menu leaves', () => {
    for (const path of RETIRED) {
      expect(menuRaw, `leaf not retired: ${path}`).not.toContain(`path: '${path}'`)
    }
  })

  it('the group keeps exactly spaces + endpoints, both gated by the read perm', () => {
    const group = /index: 'mock'[\s\S]*?items: \[([\s\S]*?)\n    \],/.exec(menuRaw)
    expect(group, 'mock group missing').toBeTruthy()
    const paths = [...group![1].matchAll(/\{ path: '(\/[^']*)'/g)].map((m) => m[1])
    expect(paths).toEqual(['/mock/spaces', '/mock/endpoints'])
    expect(group![1]).toContain("perm: 'perm:mock:read'")
    // permission ledger zero-change: the read key stays on the surviving leaves
    expect(group![1].match(/perm: 'perm:mock:read'/g)?.length).toBe(2)
  })
})

describe('T406 blade 2: search synonyms ride the host path (T405 precedent)', () => {
  it('no synonym key dangles on a retired path', () => {
    for (const path of RETIRED) {
      expect(synonymsRaw, `dangling synonym key: ${path}`).not.toContain(`'${path}':`)
    }
  })

  it('the inspector words move onto /mock/spaces so the old search term still lands', () => {
    const entry = /'\/mock\/spaces': \[([^\]]*)\]/.exec(synonymsRaw)
    expect(entry, '/mock/spaces synonym entry missing').toBeTruthy()
    for (const word of ['流量检视', '命中', '检视器']) {
      expect(entry![1], `word not merged: ${word}`).toContain(word)
    }
  })
})

describe('T406 blade 2: every space-owning door lands on the workbench + tab', () => {
  it('the §12a return door for record/nl-ai rules goes to the inspector tab of its space', () => {
    expect(jumpRaw).toContain('path: `/mock/spaces/${stub.spaceId}`')
    expect(jumpRaw).toContain("tab: 'inspector'")
    // a row without a space id must not fabricate one — honest list landing
    expect(jumpRaw).toContain("path: '/mock/spaces'")
  })

  it('the wizard -> docs door (holds pickedSpaceId) targets the workbench docs tab', () => {
    expect(wizardRaw).toMatch(/path: `\/mock\/spaces\/\$\{pickedSpaceId\.value\}`, query: \{ projectId: props\.projectId, tab: 'docs' \}/)
  })

  it('the endpoint inventory -> docs door (holds row.spaceId) targets the workbench docs tab', () => {
    expect(endpointsRaw).toMatch(/path: `\/mock\/spaces\/\$\{row\.spaceId\}`, query: \{ projectId: String\(projectId\.value \?\? ''\), tab: 'docs' \}/)
  })

  it('the docs -> scenario-list summary bar targets the scenarios tab of the space', () => {
    expect(docsRaw).toMatch(/path: `\/mock\/spaces\/\$\{spaceId\.value\}`, query: \{ tab: 'scenarios', projectId: String\(projectId\.value \?\? ''\) \}/)
  })

  it('the scenario detail back door returns to the scenarios tab (no dead ends)', () => {
    expect(scenarioDetailRaw).toMatch(/path: `\/mock\/spaces\/\$\{spaceId\.value\}`, query: \{ tab: 'scenarios', projectId: String\(projectIdQ\.value \?\? ''\) \}/)
  })
})

describe('T406 blade 2: the spaces list is the only menu door — it opens the workbench', () => {
  it('each row exposes an open-workbench action that forwards tab/source intent from its own URL', () => {
    expect(spacesRaw).toContain('data-test="mock-spaces-open-workbench"')
    expect(spacesRaw).toMatch(/path: `\/mock\/spaces\/\$\{row\.id\}`/)
    // carried intent: an inbound tab/source (e.g. redirect fallback from a door
    // without a space id) survives into the workbench instead of dead-ending
    expect(spacesRaw).toContain("route.query.tab")
    expect(spacesRaw).toContain("route.query.source")
  })

  it('the redirect fallback keeps the tab key when no space id is present', () => {
    const helper = /function mockWorkbenchRedirect\(tab: string\)([\s\S]*?)\n\}/.exec(routesRaw)
    expect(helper, 'helper missing').toBeTruthy()
    expect(helper![0]).toMatch(/path: '\/mock\/spaces', query: \{ \.\.\.passable, tab \}/)
  })
})
