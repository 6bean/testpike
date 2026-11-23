/**
 * stage 2 — failure-badge deep link contract (E1 entry).
 *
 * Every failed task badge across the site carries one "Analyze with AI"
 * button that: (1) requests a diagnosis through the shared dock singleton
 * (no second dock state — R30/§seven posture); (2) opens the dock with
 * source=3 so the session row records the badge as its entry surface;
 * (3) puts the execution pointer into the session whitelist context so the
 * core run door folds it into the broker message (stage 1 chain) — an
 * already-open thread is reset because the diagnosis pointer lives on the
 * session row, matching the adjudication entry "badge click creates a
 * source=3 session carrying executionId";
 * (4) pre-fills one question in the composer (locale single source, the
 * user can edit before sending — nothing fires automatically).
 */
import { beforeEach, describe, expect, it } from 'vitest'
import dockRaw from '@/components/ChatDock.vue?raw'
import panelRaw from '@/components/ChatPanel.vue?raw'
import execCenterRaw from '@/views/execution/ExecutionCenterView.vue?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'
import { buildPageContext } from '@/composables/chatContextBuilder'
import {
  chatDockState,
  closeChatDock,
  consumeBadgeRequest,
  requestFailureDiagnosis,
} from '@/composables/useChatDock'

describe('badge diagnosis request (dock singleton)', () => {
  beforeEach(() => {
    closeChatDock()
    consumeBadgeRequest()
  })

  it('opening a badge request shows the dock and parks the execution id', () => {
    requestFailureDiagnosis(45, '#/execution')
    expect(chatDockState.visible).toBe(true)
    expect(chatDockState.openedFrom).toBe('#/execution')
    const req = consumeBadgeRequest()
    expect(req?.executionId).toBe(45)
  })

  it('consume is one-shot: a second read is empty', () => {
    requestFailureDiagnosis(46)
    expect(consumeBadgeRequest()?.executionId).toBe(46)
    expect(consumeBadgeRequest()).toBeNull()
  })

  it('a normal dock open never carries a badge request', () => {
    closeChatDock()
    consumeBadgeRequest()
    expect(consumeBadgeRequest()).toBeNull()
  })
})

describe('buildPageContext explicit execution pointer', () => {
  it('uses the explicit badge id when the URL carries none', () => {
    const ctx = JSON.parse(buildPageContext('/execution', {}, 42, 45))
    expect(ctx.executionId).toBe(45)
    expect(ctx.projectId).toBe(42)
  })

  it('query executionId still wins over the explicit pointer', () => {
    const ctx = JSON.parse(buildPageContext('/execution', { executionId: 7 }, 42, 45))
    expect(ctx.executionId).toBe(7)
  })

  it('without either pointer no executionId key is emitted', () => {
    const ctx = JSON.parse(buildPageContext('/batch', {}, 42))
    expect(ctx.executionId).toBeUndefined()
  })

  it('a non-finite explicit pointer is dropped (never smuggles garbage)', () => {
    const ctx = JSON.parse(buildPageContext('/execution', {}, 42, Number.NaN))
    expect(ctx.executionId).toBeUndefined()
  })
})

describe('dock and panel wire the badge request through', () => {
  it('the dock switches the panel source to 3 while a badge request waits', () => {
    expect(dockRaw).toContain('consumeBadgeRequest')
    expect(dockRaw).toMatch(/:source="hasBadge \? 3 : 1"/)
    expect(dockRaw).toContain('badge-execution-id')
  })

  it('the panel takes the badge pointer, resets to a fresh thread, pre-fills one question', () => {
    expect(panelRaw).toContain('badgeExecutionId')
    expect(panelRaw).toContain('badgeQuestion')
    // the pointer rides into the session context through the builder override
    expect(panelRaw).toMatch(/buildPageContext\(route\.path, route\.query, ctx\.primaryId, badgeId\.value\)/)
    // fresh thread on badge click: persisted pointer dropped (session row carries the id)
    expect(panelRaw).toContain('localStorage.removeItem(key)')
  })

  it('the panel clears the badge pointer once the session row is opened', () => {
    expect(panelRaw).toMatch(/badgeId\.value = null/)
  })
})

describe('execution center carries the badge button (first surface)', () => {
  it('failed rows offer one Analyze-with-AI action through the shared request', () => {
    expect(execCenterRaw).toContain('requestFailureDiagnosis')
    expect(execCenterRaw).toContain('exec-ai-analyze')
    expect(execCenterRaw).toMatch(/row\.status === 4 \|\| row\.status === 5/)
  })
})

describe('locale single source (bilingual, zero hard-coded CJK in components)', () => {
  it('both tracks carry the button label and the pre-filled question', () => {
    for (const raw of [zhRaw, enRaw]) {
      expect(raw).toContain('aiAnalyze')
      expect(raw).toContain('badgeQuestion')
    }
    expect(zhRaw).toMatch(/aiAnalyze: '让 AI 分析'/)
    expect(enRaw).toMatch(/aiAnalyze: 'Analyze with AI'/)
  })
})
