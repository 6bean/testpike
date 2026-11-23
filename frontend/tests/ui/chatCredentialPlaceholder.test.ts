/**
 * Credential-screenshot placeholder on reloaded history (BD batch piece E
 * face 2, adjudication D2 = delete + placeholder): the original pixels of a
 * credential screenshot are purged at the terminal state, so a reloaded panel
 * must show a named placeholder row instead of the image (and instead of
 * nothing). Pinned rules:
 * - the sealed-history door carries one boolean per turn folded from the
 * credLedger marker of that turn's frames; the api mirror and the mapper
 * are the ONE folding point (no component-side meta parsing);
 * - a freshly sent user turn never shows the placeholder (the strip above
 * the composer already names the picked image; nothing is booked yet);
 * - the row renders from locale keys in both languages, never hard-coded
 * text (W6: user-facing sentence only, no internal bookkeeping words).
 */
import { describe, expect, it } from 'vitest'
import { mapHistoryToTurns } from '@/composables/chatHistoryMapper'
import { createUserTurn } from '@/composables/chatDockReducer'
import panelRaw from '@/components/ChatPanel.vue?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

function row(partial: Record<string, unknown> = {}) {
  return {
    id: 1, sessionId: 1, role: 'user', content: 'q', reasoning: null,
    status: 'done', seqFrom: null, seqTo: null, promptTokens: 0, completionTokens: 0,
    createdAt: '2026-11-14T09:30:00',
    ...partial,
  }
}

describe('history mapper folds the credential-screenshot marker (one folding point)', () => {
  it('a user row booked with credLedger folds the placeholder flag', () => {
    const turns = mapHistoryToTurns([row({ id: 6, credLedger: true })], 0)
    expect(turns[0].credPlaceholder).toBe(true)
  })

  it('rows without the marker (or older servers that omit the field) stay honest false', () => {
    expect(mapHistoryToTurns([row({ id: 7 })], 0)[0].credPlaceholder).toBe(false)
    expect(mapHistoryToTurns([row({ id: 8, credLedger: false })], 0)[0].credPlaceholder).toBe(false)
  })

  it('a freshly sent user turn starts without the placeholder', () => {
    expect(createUserTurn(1, 'q').credPlaceholder).toBe(false)
  })
})

describe('panel renders the placeholder row for booked turns only', () => {
  it('the user bubble branch is gated on the folded flag with a data-test anchor', () => {
    expect(panelRaw).toMatch(/m\.credPlaceholder[\s\S]{0,120}data-test="chat-cred-placeholder"/)
    expect(panelRaw).toMatch(/layout\.chatDock\.credential\.placeholder/)
  })

  it('both locales carry the sentence naming what happened and where the pixels went', () => {
    expect(zhRaw).toMatch(/credential:[\s\S]{0,200}placeholder: '[^']*登录态截图[^']*'/)
    expect(enRaw).toMatch(/credential:[\s\S]{0,200}placeholder: '[^']*credential screenshot[^']*'/i)
  })
})
