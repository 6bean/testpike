/**
 * stage 1 frontend face — per-turn thumbs feedback contract.
 *
 * Pinned rules (mirror the core intake semantics, one folding point):
 * - the message row is the only observation ledger: the api layer mirrors
 * POST /ai/chat/messages/{id}/feedback through the single request wrapper
 * (no second fetch path, no new table);
 * - sealed rows fold feedback / feedbackReason through the ONE history
 * mapper, so a reloaded panel shows the rating the user already gave;
 * - a complaint sentence only ever rides a 👎 (-1): the mapper drops any
 * reason attached to 0 / null the same way the server clears the column,
 * so a stale complaint never displays on a positive signal;
 * - the thumbs doors render on sealed assistant turns only (a running or a
 * user row has nothing to rate) and come from locale keys, never hard
 * -coded text (R27: the tooltip names the consequence, not internal
 * bookkeeping).
 */
import { describe, expect, it } from 'vitest'
import { mapHistoryToTurns } from '@/composables/chatHistoryMapper'
import apiRaw from '@/api/aiChat.ts?raw'
import panelRaw from '@/components/ChatPanel.vue?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

function row(partial: Record<string, unknown> = {}) {
  return {
    id: 1, sessionId: 1, role: 'user', content: 'q', reasoning: null,
    status: 'done', seqFrom: null, seqTo: null, promptTokens: 0, completionTokens: 0,
    createdAt: '2026-10-29T09:30:00',
    ...partial,
  }
}

describe('history mapper folds per-turn feedback (one folding point)', () => {
  it('a disliked assistant row carries -1 plus its complaint sentence', () => {
    const turns = mapHistoryToTurns([
      row({ id: 8, role: 'assistant', content: '答', feedback: -1, feedbackReason: '引用的文档过期了' }),
    ], 0)
    expect(turns[0].feedback).toBe(-1)
    expect(turns[0].feedbackReason).toBe('引用的文档过期了')
  })

  it('a liked assistant row carries +1 and never a stale reason', () => {
    const turns = mapHistoryToTurns([
      row({ id: 9, role: 'assistant', content: '答', feedback: 1, feedbackReason: '旧的一句抱怨' }),
    ], 0)
    expect(turns[0].feedback).toBe(1)
    expect(turns[0].feedbackReason).toBeNull()
  })

  // A never-rated row (NULL) renders no active state — the door is unpressed.
  it('an unrated assistant row folds null feedback', () => {
    const turns = mapHistoryToTurns([
      row({ id: 10, role: 'assistant', content: '答' }),
    ], 0)
    expect(turns[0].feedback ?? null).toBeNull()
    expect(turns[0].feedbackReason ?? null).toBeNull()
  })
})

describe('api mirror of the feedback door', () => {
  it('POSTs through the single request wrapper with rating and reason', () => {
    expect(apiRaw).toMatch(/export function submitChatMessageFeedback/)
    expect(apiRaw).toMatch(/\/ai\/chat\/messages\/\$\{messageId\}\/feedback/)
    expect(apiRaw).toMatch(/method: 'post'/)
  })

  it('ledger rows carry the two feedback fields (mirrors ChatMessageView)', () => {
    expect(apiRaw).toMatch(/feedback\??:/)
    expect(apiRaw).toMatch(/feedbackReason\??:/)
  })
})

describe('ChatPanel renders the thumbs doors', () => {
  it('a sealed assistant turn shows the 👍 / 👎 buttons', () => {
    expect(panelRaw).toMatch(/data-test="chat-feedback-up"/)
    expect(panelRaw).toMatch(/data-test="chat-feedback-down"/)
  })

  it('the doors render only on a sealed assistant row with a ledger id', () => {
    // The v-if guard must bind role + state + messageId — a running or a user
    // row has nothing to rate (and an unsaved row has no ledger id to POST).
    expect(panelRaw).toMatch(/m\.role === 'assistant'[\s\S]{0,160}m\.state === 'sealed'[\s\S]{0,160}m\.messageId/)
  })

  it('a 👎 opens the one-sentence complaint door', () => {
    expect(panelRaw).toMatch(/chat-feedback-reason/)
    expect(panelRaw).toMatch(/submitChatMessageFeedback/)
  })

  it('both locales carry the key family', () => {
    expect(zhRaw).toMatch(/feedbackUp:/)
    expect(zhRaw).toMatch(/feedbackDown:/)
    expect(zhRaw).toMatch(/feedbackReasonPlaceholder:/)
    expect(enRaw).toMatch(/feedbackUp:/)
    expect(enRaw).toMatch(/feedbackDown:/)
    expect(enRaw).toMatch(/feedbackReasonPlaceholder:/)
  })
})
