/**
 * segment 3 of the chat-awaiting contract: every failed reply line names its
 * reason in one of four classes (network break / stream break / timeout /
 * empty completion), stays red-bordered, and offers one-click resend of the
 * original question (text + attachments + context anchor, verbatim) plus a
 * copy of the original text.
 *
 * Pure helpers (chatFailureResend, same discipline as chatAwaiting):
 * - failureClassOf: the wire kinds fold into the four display classes;
 * no-body reads as a stream break, and a refused door stays the server's
 * own sentence (kind 'server'), never re-phrased by the dock;
 * - canResend: only a failed line that captured its own request payload is
 * resendable — sealed/running lines and pre-feature turns are not;
 * - isBlankAnswer: the empty-completion detector (done frame + no text);
 * - CHAT_FIRST_FRAME_TIMEOUT_MS: one exported dwell source for the stream
 * layer's first-frame watchdog (no per-caller magic numbers).
 *
 * Wiring assertions are source-as-text (the chatAwaitingState.test.ts
 * precedent):
 * - openChatStream carries a 'timeout' failure kind fired by the watchdog
 * and cleared when the first frame arrives;
 * - the dock seals a done frame with no answer text as a failed 'empty'
 * turn (the shared empty-body placeholder never becomes a fake seal);
 * - a fresh stream captures the request as the turn's resend payload, and
 * the resend door replays it verbatim through the SAME send path (new
 * turn, nothing double-booked, anchor kept);
 * - the failure line renders the resend and copy controls (data-test
 * anchors) and both locale bundles carry the new sentences (parity).
 */
import { describe, expect, it } from 'vitest'
import {
  CHAT_FIRST_FRAME_TIMEOUT_MS,
  canResend,
  failureClassOf,
  isBlankAnswer,
} from '@/composables/chatFailureResend'
import panelRaw from '@/components/ChatPanel.vue?raw'
import streamRaw from '@/api/aiChat.ts?raw'
import zh from '@/utils/locales/zh-CN'
import en from '@/utils/locales/en-US'

describe('failure classification (pure)', () => {
  it('the wire kinds fold into the four display classes', () => {
    expect(failureClassOf('network')).toBe('network')
    expect(failureClassOf('interrupted')).toBe('interrupted')
    expect(failureClassOf('timeout')).toBe('timeout')
    expect(failureClassOf('empty')).toBe('empty')
  })

  it('a missing response body reads as a stream break; a refusal keeps the server sentence', () => {
    expect(failureClassOf('no-body')).toBe('interrupted')
    expect(failureClassOf('refused')).toBe('server')
    expect(failureClassOf(null)).toBe(null)
    expect(failureClassOf(undefined)).toBe(null)
  })

  it('the first-frame dwell threshold is one exported source sized for slow models', () => {
    expect(CHAT_FIRST_FRAME_TIMEOUT_MS).toBe(300_000)
  })

  it('blank answer detection trims whitespace but never eats real text', () => {
    expect(isBlankAnswer('')).toBe(true)
    expect(isBlankAnswer('  \n\t ')).toBe(true)
    expect(isBlankAnswer('你好')).toBe(false)
  })
})

describe('resend eligibility (pure)', () => {
  it('only a failed line that captured its request is resendable', () => {
    expect(canResend({ state: 'failed', resendPayload: { prompt: 'q' } })).toBe(true)
    expect(canResend({ state: 'sealed', resendPayload: { prompt: 'q' } })).toBe(false)
    expect(canResend({ state: 'running', resendPayload: { prompt: 'q' } })).toBe(false)
    expect(canResend({ state: 'failed', resendPayload: null })).toBe(false)
    expect(canResend({ state: 'failed' })).toBe(false)
    expect(canResend(null)).toBe(false)
  })
})

describe('stream-layer wiring (source-as-text)', () => {
  it('openChatStream knows the timeout kind and watches the first frame', () => {
    expect(streamRaw).toContain("{ kind: 'timeout' }")
    expect(streamRaw).toContain('CHAT_FIRST_FRAME_TIMEOUT_MS')
    // The watchdog is cleared as soon as the first frame lands.
    expect(streamRaw).toMatch(/clearTimeout/)
  })
})

describe('dock wiring (source-as-text)', () => {
  it('a done frame sealing a blank answer becomes a failed empty turn', () => {
    expect(panelRaw).toContain("errorKind = 'empty'")
    expect(panelRaw).toContain('isBlankAnswer')
  })

  it('every fresh stream captures its request for the resend door', () => {
    expect(panelRaw).toContain('resendPayload')
    // The resend replays the captured request through the same startStream door.
    expect(panelRaw).toMatch(/function resendFailed[\s\S]*?startStream\(/)
  })

  it('the failure line carries the resend and copy controls', () => {
    expect(panelRaw).toContain('data-test="chat-msg-resend"')
    expect(panelRaw).toContain('data-test="chat-msg-copy"')
    expect(panelRaw).toContain('data-test="chat-msg-error"')
  })

  it('the timeout and empty classes get their own locale sentences', () => {
    expect(panelRaw).toContain("errTimeout")
    expect(panelRaw).toContain("errEmpty")
  })
})

describe('locale parity', () => {
  const keys = ['errTimeout', 'errEmpty', 'resend', 'copyOriginal'] as const

  it('both bundles carry every new chat failure sentence', () => {
    const zhDock = (zh.layout as Record<string, Record<string, unknown>>).chatDock
    const enDock = (en.layout as Record<string, Record<string, unknown>>).chatDock
    for (const k of keys) {
      expect(typeof zhDock?.[k], `zh key ${k}`).toBe('string')
      expect(typeof enDock?.[k], `en key ${k}`).toBe('string')
    }
  })

  it('the reason sentences name the fix door 「重发」 (R63 three elements)', () => {
    const zhDock = (zh.layout as Record<string, Record<string, unknown>>).chatDock
    expect(String(zhDock.errTimeout)).toContain('「重发」')
    expect(String(zhDock.errEmpty)).toContain('「重发」')
  })
})

describe('empty placeholder suppression (rendering criterion sealed)', () => {
  it('the dock never renders the shared empty-body placeholder for a blank answer', () => {
    // zero occurrences before completion (awaiting branch carries the wait)
    // and a completed empty answer is the failed 'empty' LINE (locale
    // sentence + resend door), not a placeholder body. The preview branch
    // must therefore be gated on isBlankAnswer, never a plain v-else.
    expect(panelRaw).toMatch(/MarkdownPreview[^>]*v-else-if="!isBlankAnswer\(m\.content\)"/)
    expect(panelRaw).not.toMatch(/<MarkdownPreview v-else :source="m\.content"/)
  })
})
