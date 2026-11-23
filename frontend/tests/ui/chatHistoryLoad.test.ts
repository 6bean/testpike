/**
 * final segment — reload history contract (TC-AO-06: history survives a reload).
 *
 * Pinned rules:
 * - the admin door GET /api/v1/ai/chat/sessions/{id}/messages is mirrored in
 * the api layer (one mirror, no second fetch path);
 * - sealed ledger rows fold into visible turns through ONE pure mapper
 * (done/aborted = sealed with the body the user watched, error = failed
 * without a fake completion, user echoes keep their text);
 * - the kernel persists the session id per user+project in localStorage
 * (tp_chat_ctx_<userId>_<projectId>, personal preference stays client-side
 * like the dock width / fab position precedents) and re-reads it on mount;
 * - worst-case scale: the thread rides the shared windowed list so a 500-turn
 * session never renders all rows at once (data-windowed-row + pads).
 */
import { describe, expect, it } from 'vitest'
import { mapHistoryToTurns } from '@/composables/chatHistoryMapper'
import apiRaw from '@/api/aiChat.ts?raw'
import panelRaw from '@/components/ChatPanel.vue?raw'

describe('history mapper (sealed rows -> visible turns, one folding point)', () => {
  it('user echo rows come back sealed with their verbatim text', () => {
    const turns = mapHistoryToTurns([
      { id: 11, sessionId: 1, role: 'user', content: '批次怎么查', reasoning: null, status: 'done', seqFrom: 1, seqTo: 1, promptTokens: 0, completionTokens: 0 },
    ], 100)
    expect(turns).toHaveLength(1)
    expect(turns[0].role).toBe('user')
    expect(turns[0].state).toBe('sealed')
    expect(turns[0].content).toBe('批次怎么查')
  })

  it('a done assistant row is sealed with rebuilt body and usage numbers', () => {
    const turns = mapHistoryToTurns([
      { id: 12, sessionId: 1, role: 'assistant', content: '答案正文', reasoning: null, status: 'done', seqFrom: 2, seqTo: 9, promptTokens: 120, completionTokens: 34 },
    ], 0)
    expect(turns[0].state).toBe('sealed')
    expect(turns[0].content).toBe('答案正文')
    expect(turns[0].usage).toEqual({ promptTokens: 120, completionTokens: 34 })
    expect(turns[0].messageId).toBe(12)
  })

  it('an aborted row keeps its partial answer and never looks running', () => {
    const turns = mapHistoryToTurns([
      { id: 13, sessionId: 1, role: 'assistant', content: '数到一半', reasoning: null, status: 'aborted', seqFrom: 3, seqTo: 7, promptTokens: 5, completionTokens: 6 },
    ], 0)
    expect(turns[0].state).toBe('sealed')
    expect(turns[0].content).toBe('数到一半')
  })

  it('an error row is failed, not a fake completion (and still carries any partial text)', () => {
    const turns = mapHistoryToTurns([
      { id: 14, sessionId: 1, role: 'assistant', content: null, reasoning: null, status: 'error', seqFrom: null, seqTo: null, promptTokens: 0, completionTokens: 0 },
    ], 0)
    expect(turns[0].state).toBe('failed')
  })

  it('row ids continue after the local counter so keys never collide', () => {
    const turns = mapHistoryToTurns([
      { id: 15, sessionId: 1, role: 'user', content: 'a', reasoning: null, status: 'done', seqFrom: null, seqTo: null, promptTokens: 0, completionTokens: 0 },
      { id: 16, sessionId: 1, role: 'assistant', content: 'b', reasoning: null, status: 'done', seqFrom: null, seqTo: null, promptTokens: 0, completionTokens: 0 },
    ], 100)
    expect(turns.map((x) => x.id)).toEqual([101, 102])
  })
})

describe('kernel wiring (mirror + persistence + windowing)', () => {
  it('the api layer mirrors the history door exactly once', () => {
    expect(apiRaw).toContain('getChatHistory')
    expect(apiRaw).toContain('/ai/chat/sessions/${sessionId}/messages')
  })

  it('the kernel persists the session id per user+project and re-reads it', () => {
    expect(panelRaw).toContain('CHAT_CTX_KEY_PREFIX')
    expect(panelRaw).toContain('tp_chat_ctx_')
    expect(panelRaw).toContain('getChatHistory')
    expect(panelRaw).toContain('mapHistoryToTurns')
  })

  it('worst-case scale: the thread rides the shared windowed list', () => {
    expect(panelRaw).toContain('useWindowedList')
    expect(panelRaw).toContain('data-windowed-row')
  })
})
