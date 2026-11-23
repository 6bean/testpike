/**
 * P6 — command palette "Ask AI" contract (search/dialogue fusion).
 *
 * The ⌘K palette carries one pinned "Ask AI" row whenever the input is not
 * blank: natural language that finds nothing (or anything) can turn into a
 * conversation question in one click. The handover rides the EXISTING dock
 * singleton one-shot channel (same posture as the P1 selection ask and
 * the badge link): palette parks the question, the dock consumes it once,
 * the panel prefills the composer. The question never auto-sends — the human
 * presses send (write-door red line), and no second chat panel is created.
 */
import { beforeEach, describe, expect, it } from 'vitest'
import dockRaw from '@/components/ChatDock.vue?raw'
import panelRaw from '@/components/ChatPanel.vue?raw'
import paletteRaw from '@/components/GlobalSearchDialog.vue?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'
import {
  chatDockState,
  closeChatDock,
  consumeChatAsk,
  requestChatAsk,
} from '@/composables/useChatDock'

describe('palette ask request (dock singleton)', () => {
  beforeEach(() => {
    closeChatDock()
    consumeChatAsk()
  })

  it('requesting an ask parks the question and opens the dock', () => {
    requestChatAsk('这个批次为什么全部失败', '#/batch')
    expect(chatDockState.visible).toBe(true)
    expect(chatDockState.openedFrom).toBe('#/batch')
    const parked = consumeChatAsk()
    expect(parked?.question).toBe('这个批次为什么全部失败')
  })

  it('consume is one-shot: a second read is empty', () => {
    requestChatAsk('为什么超时')
    expect(consumeChatAsk()?.question).toBe('为什么超时')
    expect(consumeChatAsk()).toBeNull()
  })

  it('a blank or whitespace question is a no-op (no empty ask lands)', () => {
    closeChatDock()
    requestChatAsk('   ')
    expect(chatDockState.visible).toBe(false)
    expect(consumeChatAsk()).toBeNull()
  })

  it('a normal dock open never carries a palette ask', () => {
    closeChatDock()
    consumeChatAsk()
    expect(consumeChatAsk()).toBeNull()
  })
})

describe('palette wiring (raw source contracts)', () => {
  it('the palette routes the ask through the dock singleton (no second panel)', () => {
    expect(paletteRaw).toMatch(/requestChatAsk/)
    expect(paletteRaw).toMatch(/data-test="global-search-ask-ai"/)
    // the label rides locale keys only — no hardcoded wording in the component
    expect(paletteRaw).toMatch(/t\('components\.askAiQuery'/)
  })

  it('the palette ask row renders only from a non-blank keyword input', () => {
    // the pinned row is conditioned on the trimmed keyword, never on results
    expect(paletteRaw).toMatch(/keyword\.value\.trim\(\)/)
  })

  it('the dock consumes the parked ask and hands it to the panel', () => {
    expect(dockRaw).toMatch(/consumeChatAsk/)
    expect(dockRaw).toMatch(/:ask-question=/)
  })

  it('the panel prefills the composer from the ask prop without auto-sending', () => {
    expect(panelRaw).toMatch(/askQuestion/)
    // prefill lands on draft (the editable composer model), never on a send call
    expect(panelRaw).toMatch(/draft\.value = /)
  })
})

describe('locale pairing', () => {
  it('both locales carry the palette ask label (no hardcoded wording)', () => {
    expect(zhRaw).toMatch(/askAiQuery: '[^']*问 AI/)
    expect(enRaw).toMatch(/askAiQuery: '[^']*Ask AI/)
  })
})
