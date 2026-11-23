/**
 * Chat thread undo door contract : one-key undo of
 * the session's last accepted case write must keep its anchor, its wire, its
 * refusal echo and its two-locale sentences — a later refactor may not
 * silently drop the door or invent a second client-side judgement. Same
 * ?raw source-pinning posture as chatImageAttachContract; the live behaviour
 * is covered by the E2E spectrum.
 */
import { describe, expect, it } from 'vitest'
import actionsRaw from '@/components/ChatThreadActions.vue?raw'
import apiRaw from '@/api/aiChat.ts?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

describe('thread undo door', () => {
  it('renders the undo button with a data-test anchor, aria-label and in-flight lock', () => {
    expect(actionsRaw).toMatch(/data-test="chat-undo-btn"/)
    expect(actionsRaw).toMatch(/:aria-label="t\('layout\.chatDock\.threadActions\.undoAction'\)"/)
    expect(actionsRaw).toMatch(/data-test="chat-undo-btn"[\s\S]{0,120}:loading="undoing"/)
  })

  it('the door stays resident while a session exists — no second grey-out rule', () => {
    // The only disable condition is the session id itself; whether an accepted
    // write exists is core-side knowledge echoed back as a refusal sentence.
    expect(actionsRaw).toMatch(/data-test="chat-undo-btn"[\s\S]{0,160}:disabled="sessionId == null"/)
    expect(actionsRaw).not.toMatch(/:disabled="[^"]*(hasAcceptedWrite|undoable)[^"]*"/)
  })

  it('refusals echo the core-side wording via the shared refusalText fallback path', () => {
    expect(actionsRaw).toMatch(/ElMessage\.error\(refusalText\(e, t\('layout\.chatDock\.threadActions\.undoFailed'\)\)\)/)
  })

  it('double-click while in flight is blocked at the door (F-group guard)', () => {
    expect(actionsRaw).toMatch(/if \(props\.sessionId == null \|\| undoing\.value\) return/)
  })
})

describe('undo wire mirror', () => {
  it('the API mirrors POST /api/v1/ai/chat/sessions/{id}/undo and returns the card id', () => {
    expect(apiRaw).toMatch(/export function undoChatSession\(sessionId: number\): Promise<number>/)
    expect(apiRaw).toMatch(/url: `\/ai\/chat\/sessions\/\$\{sessionId\}\/undo`,\s*method: 'post'/)
  })

  it('the success toast names the card id (object of the action)', () => {
    expect(actionsRaw).toMatch(/undoChatSession\(props\.sessionId\)/)
    expect(actionsRaw).toMatch(/undoDone'\), \{ cardId \}|undoDone', \{ cardId \}\)/)
  })
})

describe('two-locale parity', () => {
  const keys = ['undoAction', 'undoDone', 'undoFailed', 'sedimentFailed']
  it.each(keys)('layout.chatDock.threadActions.%s exists in zh and en', (k) => {
    const block = (raw: string) => raw.match(/threadActions: \{([\s\S]{0,2000}?)\},\n/) as RegExpMatchArray
    expect(block(zhRaw)[1]).toContain(`${k}:`)
    expect(block(enRaw)[1]).toContain(`${k}:`)
  })

  it('the undo sentences state the confirmation door and the not-yet-rolled-back fact', () => {
    // W-group: name the object and the consequence, no governance wording.
    expect(zhRaw).toContain('确认前不会回退任何数据')
    expect(enRaw).toContain('nothing rolls back until you confirm')
  })
})
