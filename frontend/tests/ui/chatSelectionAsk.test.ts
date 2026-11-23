/**
 * P1 — selection "Ask AI" contract.
 *
 * A multi-select on a list page floats one Ask AI button that parks the
 * selected rows as reference chips and opens the dock over the current page.
 * The selection rides the EXISTING E3 chip channel end to end: payload ->
 * core ChatObjectRefs single validation point (kinds/cap/segment) ->
 * RefObjectResolver digest in the prompt. No new context keys, no second
 * validation door, no client-side copy of the cap (the server names the
 * numbers when the selection exceeds it — same fail-loud posture as chips).
 */
import { beforeEach, describe, expect, it } from 'vitest'
import dockRaw from '@/components/ChatDock.vue?raw'
import panelRaw from '@/components/ChatPanel.vue?raw'
import caseListRaw from '@/views/case/CaseListView.vue?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'
import {
  chatDockState,
  closeChatDock,
  consumeSelectionAsk,
  requestSelectionAsk,
} from '@/composables/useChatDock'

const chips = [
  { kind: 'case' as const, id: 7, title: '登录超时提示' },
  { kind: 'case' as const, id: 8, title: '密码强度校验' },
  { kind: 'case' as const, id: 9, title: '验证码刷新' },
]

describe('selection ask request (dock singleton)', () => {
  beforeEach(() => {
    closeChatDock()
    consumeSelectionAsk()
  })

  it('requesting an ask parks the chip set and opens the dock', () => {
    requestSelectionAsk(chips, '#/case')
    expect(chatDockState.visible).toBe(true)
    expect(chatDockState.openedFrom).toBe('#/case')
    const parked = consumeSelectionAsk()
    expect(parked).toHaveLength(3)
    expect(parked?.map((r) => r.id)).toEqual([7, 8, 9])
  })

  it('consume is one-shot: a second read is empty', () => {
    requestSelectionAsk(chips)
    expect(consumeSelectionAsk()).toHaveLength(3)
    expect(consumeSelectionAsk()).toBeNull()
  })

  it('a normal dock open never carries a selection request', () => {
    closeChatDock()
    consumeSelectionAsk()
    expect(consumeSelectionAsk()).toBeNull()
  })
})

describe('dock wiring (raw source contracts)', () => {
  it('the dock consumes the parked selection and hands it to the panel', () => {
    expect(dockRaw).toMatch(/consumeSelectionAsk/)
    expect(dockRaw).toMatch(/:selection-refs=/)
  })

  it('the panel merges incoming chips through dedupeRefs (single truth)', () => {
    expect(panelRaw).toMatch(/selectionRefs/)
    // the merge must run the shared dedupe, never a raw concat of duplicates
    expect(panelRaw).toMatch(/dedupeRefs\(\[[^)]*selection/)
  })

  it('the case list batch bar floats one Ask AI button through the singleton', () => {
    expect(caseListRaw).toMatch(/requestSelectionAsk/)
    expect(caseListRaw).toMatch(/data-test="case-ask-ai"/)
  })

  it('both locales carry the Ask AI label (no hardcoded wording)', () => {
    expect(zhRaw).toMatch(/askAi: '[^']*问 AI/)
    expect(enRaw).toMatch(/askAi: '[^']*Ask AI/)
  })
})
