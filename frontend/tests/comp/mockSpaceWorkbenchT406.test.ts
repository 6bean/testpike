/**
 * AN-4 step-1 layout contract (RED first) — AN-4 mock space workbench.
 * Authority = 16 §61 row + the AN batch ruling doc: the four screening
 * pages (/mock/docs /mock/scenarios /mock/recording /mock/inspector) merge
 * into a per-space workbench /mock/spaces/:id whose tab state rides the URL
 * query; the retired paths keep resolving through functional redirects that
 * carry the caller query over . The traffic
 * inspector tab keeps its own permission gate inside the workbench leaf
 * (menu leaf perm is mock:read only — a tab gate is required, not just a
 * route gate).
 */
import { describe, expect, it } from 'vitest'

import routesRaw from '@/router/routes.ts?raw'
import workbenchRaw from '@/views/mock/MockSpaceWorkbenchView.vue?raw'
import docsRaw from '@/views/mock/MockDocsView.vue?raw'
import scenariosRaw from '@/views/mock/MockScenarioListView.vue?raw'
import recordingRaw from '@/views/mock/MockRecordingView.vue?raw'
import inspectorRaw from '@/views/mock/MockInspectorView.vue?raw'

const TABS = ['docs', 'scenarios', 'recording', 'inspector'] as const

describe('T406 workbench route: /mock/spaces/:id exists as a hidden detail leaf', () => {
  it('registers the workbench route with perm:mock:read mirrored from the admin gate', () => {
    expect(routesRaw).toMatch(/path: 'mock\/spaces\/:id'/)
    expect(routesRaw).toMatch(/name: 'MockSpaceWorkbench'/)
    expect(routesRaw).toContain("component: () => import('@/views/mock/MockSpaceWorkbenchView.vue')")
    // meta perm mirrors the admin read gate (same code family as spaces/docs)
    const seg = /path: 'mock\/spaces\/:id'[\s\S]*?perm: 'perm:mock:read'/.exec(routesRaw)
    expect(seg).not.toBeNull()
  })

  it('is a hidden leaf parented on the spaces list (detail-page posture, no new menu row)', () => {
    const seg = /path: 'mock\/spaces\/:id'[\s\S]*?\n      \}/.exec(routesRaw)
    expect(seg).not.toBeNull()
    expect(seg![0]).toContain('hidden: true')
    expect(seg![0]).toContain("parent: '/mock/spaces'")
  })
})

describe('T406 retired paths: functional redirects carry the caller query and land on the right tab', () => {
  it.each(TABS)('mock/%s no longer mounts a component — it redirects through the workbench helper with its tab', (tab) => {
    // the retired route must not mount a view any more (the workbench is the
    // single source); one shared helper (>=4 near-copies break the no-dup rule)
    expect(routesRaw).toMatch(new RegExp(`path: 'mock/${tab}',\\s*\\n\\s*redirect: mockWorkbenchRedirect\\('${tab}'\\)`))
    expect(routesRaw).not.toMatch(new RegExp(`path: 'mock/${tab}',\\s*\\n\\s*name: 'Mock`))
  })

  it('the helper lands a URL spaceId on that space + tab, and without one falls back to the spaces list honestly (never a guessed space)', () => {
    const helper = /function mockWorkbenchRedirect[\s\S]*?\n\}/.exec(routesRaw)
    expect(helper).not.toBeNull()
    expect(helper![0]).toMatch(/\/mock\/spaces\/\$\{/)
    // the caller query survives the hop (typed passable copy) plus the tab key
    expect(helper![0]).toMatch(/query: \{ \.\.\.passable, tab \}/)
    expect(helper![0]).toMatch(/path: '\/mock\/spaces'/)
  })
})

describe('T406 workbench view: tabs, URL query sync, per-tab permission gate', () => {
  it('renders the four tabs with test anchors and injects the route space id into every embedded page', () => {
    for (const tab of TABS) {
      expect(workbenchRaw).toContain(`data-test="mock-workbench-tab-${tab}"`)
    }
    expect(workbenchRaw).toMatch(/:space-id="spaceId"/)
    expect(workbenchRaw).toMatch(/embedded/)
  })

  it('tab state is URL-query authoritative (query wins on entry, switch writes back)', () => {
    expect(workbenchRaw).toMatch(/route\.query\.tab/)
    // switch writes back via replace (no history spam); tab=docs is the
    // implicit default so the query stays clean on the home tab
    expect(workbenchRaw).toMatch(/router\.replace\(\{ query: \{ \.\.\.route\.query, tab: tab === 'docs' \? undefined : tab \} \}\)/)
    // and an incoming query change moves the visible tab (not just the URL)
    expect(workbenchRaw).toMatch(/watch\(\(\) => route\.query\.tab, \(\) => \{/)
  })

  it('the inspector tab is gated by perm:mock:traffic inside the leaf (route gate alone is not enough)', () => {
    expect(workbenchRaw).toMatch(/hasPerm\('perm:mock:traffic'\)/)
    expect(workbenchRaw).toMatch(/canTraffic/)
  })

  it('a space id that is absent from the loaded list lands on the list with a plain-word hint (honest deep link, never an empty shell)', () => {
    expect(workbenchRaw).toMatch(/mockWorkbench\.spaceNotFound/)
    expect(workbenchRaw).toMatch(/\/mock\/spaces/)
  })

  it('header actions host no selectors (T396 separation-of-screening rule inherited by the new page)', () => {
    const m = /<div class="header-actions">([\s\S]*?)<\/div>/.exec(workbenchRaw)
    const block = m ? m[1] : ''
    expect(block).not.toContain('ProjectSelect')
    expect(block).not.toMatch(/<el-select/)
    expect(block).not.toContain('QuestionFilled')
  })
})

describe('T406 embedded posture for the four reused views (T404/T405 precedent)', () => {
  const views: [string, string][] = [
    ['MockDocsView', docsRaw],
    ['MockScenarioListView', scenariosRaw],
    ['MockRecordingView', recordingRaw],
    ['MockInspectorView', inspectorRaw],
  ]

  it.each(views)('%s declares embedded + spaceId props (host injects the space context)', (_name, src) => {
    expect(src).toMatch(/withDefaults\(defineProps<\{ embedded\?: boolean; spaceId\?: number \| null \}>\(\), \{ embedded: false, spaceId: null \}\)/)
  })

  it.each(views)('%s drops the inner page-card in embedded mode (no frame-in-frame)', (_name, src) => {
    expect(src).toMatch(/:class="embedded \? '[\w-]+-embedded' : 'page-card'"/)
  })

  it.each(views)('%s space picker is hidden when embedded and the injected id wins over the local pick', (_name, src) => {
    // local pick stays for the standalone redirect era; injected id takes first
    expect(src).toMatch(/props\.spaceId \?\? pickedSpace\.value/)
    // the space picker el-select itself gets a !embedded guard (host owns it)
    const bar = /<el-select\s+v-if="!embedded"\s+v-model="pickedSpace"/.exec(src)
    expect(bar).not.toBeNull()
    expect(src).toMatch(/v-if="!embedded"/)
  })
})
