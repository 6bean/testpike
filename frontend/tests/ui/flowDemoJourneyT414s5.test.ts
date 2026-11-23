/**
 * 2 contract: the factory demo journey must be reachable from the
 * UI without prior knowledge (task ruling: flow-pane empty state points at the
 * seeded journey + onboarding overlay consumes the same entry, single source).
 *
 * Clauses (posture = the ?raw source mirrors used by apiTestFlowPromoteT414c):
 * 1. journeys.ts grows a 6th quick journey `run-flow-demo` whose targetPath
 * deep-links the API-test flow pane (/api-test?view=flow — still a menu
 * leaf path, zero new route);
 * 2. probe registry carries a `flowDemo` probe reusing the existing apitest
 * mirrors (zero new backend endpoint) and gated by the same permission key
 * the collection GET controller enforces (perm:detail:case-tag — a probe
 * without the key would 403 and the E2E response-code gate fails the run);
 * 3. flow-pane empty state gains a primary action button leading to the
 * factory demo collection through the data-driven deep link
 * (?projectCode+collectionName — no numeric ids in URLs), plus the plain-
 * language hint sentence;
 * 4. ApiTestView resolves the deep-link params after collections load:
 * projectCode resolves through the auth store /me projects (already
 * visible-domain pruned — no privilege widening), name matches a
 * collection, then the flow pane opens on it;
 * 5. i18n dual track: every new key exists in zh-CN and en-US (red line 8).
 */
import { describe, expect, it } from 'vitest'

import journeysRaw from '../../src/config/journeys.ts?raw'
import probesRaw from '../../src/utils/journeyProbes.ts?raw'
import viewRaw from '../../src/views/apitest/ApiTestView.vue?raw'
import zhRaw from '../../src/utils/locales/zh-CN.ts?raw'
import enRaw from '../../src/utils/locales/en-US.ts?raw'
import { quickJourneys } from '../../src/config/journeys'

describe('T414 slice 5-2: factory demo journey is a first-class quick journey', () => {
  it('journeys list carries run-flow-demo as the sixth entry', () => {
    expect(quickJourneys.map((j) => j.id)).toEqual([
      'gen-from-doc', 'run-sample-case', 'batch-schedule',
      'reserve-device', 'connect-executor', 'run-flow-demo',
    ])
    const j = quickJourneys.find((x) => x.id === 'run-flow-demo')!
    expect(j.targetPath.startsWith('/api-test?view=flow')).toBe(true)
    expect(j.steps.map((s) => s.probeKey)).toEqual(['flowDemo'])
  })

  it('flowDemo probe is registered against existing mirrors with the controller permission key', () => {
    expect(probesRaw).toMatch(/flowDemo:\s*\{[\s\S]{0,400}permKey:\s*'perm:detail:case-tag'/)
    expect(probesRaw).toMatch(/flowDemo:[\s\S]{0,400}listCollections/)
    expect(probesRaw).toMatch(/flowDemo:[\s\S]{0,400}listRequests/)
    expect(journeysRaw).toContain('FLOW_DEMO_COLLECTION_NAME')
  })
})

describe('T414 slice 5-2: flow-pane empty state points at the factory demo', () => {
  it('empty state renders a plain hint and a demo action button', () => {
    expect(viewRaw).toContain("t('apitest.flow.emptyHint')")
    expect(viewRaw).toContain("t('apitest.flow.demoAction')")
    expect(viewRaw).toContain('data-test="apitest-flow-demo-btn"')
  })

  it('the action deep-links via data-driven params (no numeric ids in URLs)', () => {
    expect(journeysRaw).toMatch(/FLOW_DEMO_PROJECT_CODE\s*=\s*'demo-first'/)
    expect(journeysRaw).toMatch(/FLOW_DEMO_COLLECTION_NAME\s*=\s*'tp_demo_样例编排流程_下单旅程'/)
    expect(viewRaw).toContain('FLOW_DEMO_PROJECT_CODE')
    expect(viewRaw).toContain('FLOW_DEMO_COLLECTION_NAME')
    expect(viewRaw).toMatch(/projectCode:\s*FLOW_DEMO_PROJECT_CODE,\s*collectionName:\s*FLOW_DEMO_COLLECTION_NAME/)
  })

  it('ApiTestView resolves projectCode -> visible-domain project, then name -> collection, then opens the flow pane', () => {
    expect(viewRaw).toMatch(/const routeCollectionName\s*=/)
    expect(viewRaw).toMatch(/route\.query\.projectCode/)
    expect(viewRaw).toMatch(/p\.projectCode === code/)
    expect(viewRaw).toMatch(/railView\.value = 'flow'/)
    expect(viewRaw).toMatch(/await selectCollection\(match\)/)
  })

  it('railView consumes view=flow on arrival so the overlay jump lands on the flow pane directly', () => {
    expect(viewRaw).toMatch(/route\.query\.view\s*===\s*'flow'/)
  })
})

describe('T414 slice 5-2: i18n dual track', () => {
  const keys = [
    'journey.runFlowDemo',
    'journey.stepFlowDemoJourney',
    'apitest.flow.emptyHint',
    'apitest.flow.demoAction',
  ]
  function lookup(raw: string, dotted: string): boolean {
    const parts = dotted.split('.')
    // crude source-text check: last segment key present with a value line
    return new RegExp(`\\b${parts[parts.length - 1]}:`).test(raw)
  }
  it('every new key is carried by both locale files', () => {
    for (const k of keys) {
      expect(lookup(zhRaw, k), `zh-CN 缺 ${k}`).toBe(true)
      expect(lookup(enRaw, k), `en-US 缺 ${k}`).toBe(true)
    }
  })
})
