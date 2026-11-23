/**
 * layout contract: the "promote the whole flow" entry wired
 * from the rail flow pane to the collection-level promotion door.
 *
 * Posture = the ?raw source mirror used by apiTestArchT413a.test.ts: the
 * assertions read the shipped template text, so a regression that re-arms
 * promotion through a second path (a locally built script, a per-request
 * loop from the browser) fails here before the browser renders it.
 *
 * Clauses (design basis = door + the breakpoint ruling):
 * 1. the api mirror exports promoteFlow POSTing the body-less door
 * /apitest/collections/{id}/promote-flow — zero request body, all
 * judgement (order / fail-closed refusals / badges) is core-side;
 * 2. the flow pane head carries the entry (data-test=apitest-flow-promote-btn)
 * bound to a computed disabled gate — an empty cut or a pending call
 * disables it with a plain-language tooltip reason (prevention, matching
 * the reorder-entry posture; the core 400 stays the honest backstop);
 * 3. the click path confirms FIRST (asset-creating write) then calls the
 * mirror exactly once for the whole collection — no per-request loop;
 * 4. refusal texts pass through verbatim via bizErrorMsg (red line: the
 * core plain-language refusal is surfaced, never rewritten on the side);
 * 5. success answers with the created case number AND navigates to the
 * promoted case detail (the human-language result jump the ruling asks
 * for — double-direction lineage from flow to case);
 * 6. i18n dual track: zh-CN and en-US carry the identical apitest.flowPromote
 * key set, zero hard-coded CJK in the view for these strings.
 */
import { describe, expect, it } from 'vitest'

import viewRaw from '@/views/apitest/ApiTestView.vue?raw'
import apiRaw from '@/api/apiTest.ts?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

describe('T414 slice 4c: api mirror to the collection-level promotion door', () => {
  it('exports promoteFlow POSTing the body-less door', () => {
    expect(apiRaw).toMatch(/export function promoteFlow\(/)
    expect(apiRaw).toContain('`/apitest/collections/${collectionId}/promote-flow`')
    expect(apiRaw).toMatch(/promoteFlow[\s\S]{0,220}method:\s*'post'/)
  })

  it('the view consumes the mirror through the api layer, not a raw fetch', () => {
    expect(viewRaw).toMatch(/import \{[^}]*\bpromoteFlow\b[^}]*\} from '@\/api\/apiTest'/)
    expect(viewRaw).toContain('promoteFlow(collectionId.value)')
  })
})

describe('T414 slice 4c: flow-pane entry with an honest disabled gate', () => {
  it('flow pane head carries the entry bound to a computed gate', () => {
    expect(viewRaw).toContain('data-test="apitest-flow-promote-btn"')
    expect(viewRaw).toContain(':disabled="flowPromoteDisabled"')
    expect(viewRaw).toMatch(/const flowPromoteDisabled = computed\(/)
  })

  it('the gate names its blocking reasons in plain language via tooltip', () => {
    expect(viewRaw).toMatch(/flowPromoteBlockedReason/)
    expect(viewRaw).toMatch(/:content="flowPromoteBlockedReason \|\| t\('apitest\.flowPromote\.tip'\)"/)
  })

  it('the entry lives inside the flow pane, not in the workspace header', () => {
    const paneStart = viewRaw.indexOf('data-test="apitest-flow-pane"')
    const btnAt = viewRaw.indexOf('data-test="apitest-flow-promote-btn"')
    expect(paneStart).toBeGreaterThan(-1)
    expect(btnAt).toBeGreaterThan(paneStart)
    // the pane ends before the browse-empty branch that follows it
    const browseEmptyAt = viewRaw.indexOf("railView === 'browse' && filteredRequests.length === 0")
    expect(btnAt).toBeLessThan(browseEmptyAt)
  })
})

describe('T414 slice 4c: confirm-first single call, verbatim refusals, result jump', () => {
  it('confirms before creating the asset and calls the door once', () => {
    expect(viewRaw).toMatch(/async function promoteWholeFlow/)
    const fnStart = viewRaw.indexOf('async function promoteWholeFlow')
    expect(fnStart).toBeGreaterThan(-1)
    const fnBody = viewRaw.slice(fnStart, fnStart + 1600)
    expect(fnBody).toContain('ElMessageBox.confirm')
    expect(fnBody).toContain("t('apitest.flowPromote.confirm'")
    // exactly one door call in the whole flow path (no per-request loop)
    expect(fnBody.match(/promoteFlow\(/g)?.length).toBe(1)
  })

  it('refusals surface core plain language verbatim through bizErrorMsg', () => {
    const fnStart = viewRaw.indexOf('async function promoteWholeFlow')
    const fnBody = viewRaw.slice(fnStart, fnStart + 1600)
    expect(fnBody).toContain("bizErrorMsg(e, t('apitest.flowPromote.fail'))")
  })

  it('success names the case number and navigates to the promoted case', () => {
    const fnStart = viewRaw.indexOf('async function promoteWholeFlow')
    const fnBody = viewRaw.slice(fnStart, fnStart + 1600)
    expect(fnBody).toContain("t('apitest.flowPromote.ok'")
    expect(fnBody).toContain('router.push(`/cases/${result.caseId}`)')
  })
})

describe('T414 slice 4c: i18n dual track for the flowPromote key family', () => {
  const keys = ['entry', 'tip', 'confirm', 'ok', 'fail', 'blockedNoCollection', 'blockedEmpty']

  it('both locales carry the identical key set', () => {
    for (const key of keys) {
      expect(zhRaw).toContain(`${key}:`)
      expect(enRaw).toContain(`${key}:`)
    }
    const zhFamily = zhRaw.slice(zhRaw.indexOf('flowPromote: {'))
    const enFamily = enRaw.slice(enRaw.indexOf('flowPromote: {'))
    expect(zhFamily).not.toBe('')
    expect(enFamily).not.toBe('')
    for (const key of keys) {
      expect(zhFamily.slice(0, 900)).toContain(`${key}: `)
      expect(enFamily.slice(0, 900)).toContain(`${key}: `)
    }
  })

  it('zh copy keeps the T413 interface-set naming ruling in the entry text', () => {
    expect(zhRaw).toMatch(/entry: '整流程升格为回归用例'/)
  })
})
