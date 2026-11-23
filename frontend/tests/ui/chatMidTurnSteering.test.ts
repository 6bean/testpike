/**
 * Mid-turn steering contract .
 *
 * What must never regress:
 * 1. the supplement door is mirrored exactly once in the API layer — the
 * session's injects route, POST, body { content } (the admin record
 * ChatInjectRequest/ChatInjectView is the authority, the client must not
 * invent a second shape or a second path);
 * 2. the echo the reader sees is the journaled `user-inject` frame folded by
 * the ONE reducer: a live frame of that kind becomes exactly one visible
 * process entry carrying the user's own words (painting nothing from the
 * click is what keeps the live view and a reconnect replay identical);
 * 3. while an answer is streaming the composer stays alive: Enter routes to
 * the steering door (never a second question), the steering door only
 * exists while running, and the placeholder switches to the steering
 * wording (R33 in-place supplement entry);
 * 4. i18n dual track: every key this surface uses exists in both locales.
 */
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { applyFrameToTurn, createUserTurn } from '@/composables/chatDockReducer'
import zhCN from '@/utils/locales/zh-CN'
import enUS from '@/utils/locales/en-US'

const apiSrc = readFileSync(fileURLToPath(new URL('../../src/api/aiChat.ts', import.meta.url)), 'utf8')
const panelSrc = readFileSync(fileURLToPath(new URL('../../src/components/ChatPanel.vue', import.meta.url)), 'utf8')

describe('mid-turn steering door (T450)', () => {
  it('mirrors the admin injects door once: POST /ai/chat/sessions/{id}/injects with { content }', () => {
    expect(apiSrc).toContain('url: `/ai/chat/sessions/${sessionId}/injects`')
    expect(apiSrc).toContain("method: 'post'")
    expect(apiSrc).toContain('data: { content }')
    // The landing verdict keeps the server's two honest outcomes in one field:
    // joined the running turn, or went out as a regular message.
    expect(apiSrc).toContain('injectedIntoRunningTurn')
  })

  it('folds one user-inject frame into exactly one visible process line', () => {
    const turn = createUserTurn(1, 'original question')
    applyFrameToTurn(turn, { event: 'user-inject', id: 7, data: '先看登录相关的用例' })
    expect(turn.process).toHaveLength(1)
    expect(turn.process[0].kind).toBe('user-inject')
    expect(turn.process[0].text).toBe('先看登录相关的用例')
    // A second supplement is a second line, never a merge into the first one.
    applyFrameToTurn(turn, { event: 'user-inject', id: 9, data: '只要移动端' })
    expect(turn.process).toHaveLength(2)
  })

  it('keeps the composer alive while an answer streams: Enter steers, and the steer door exists only while running', () => {
    // Enter routes through one submit door that switches on the running flag.
    expect(panelSrc).toMatch(/function submitComposer\(\)[\s\S]{0,120}running\.value[\s\S]{0,60}injectSupplement[\s\S]{0,60}send\(\)/)
    expect(panelSrc).toContain('submitComposer()')
    // The steering entry is bound to the streaming state and gated on text.
    expect(panelSrc).toMatch(/data-test="chat-dock-steer"[\s\S]{0,120}:disabled="!canSteer"/)
    expect(panelSrc).toMatch(/v-if="running"[\s\S]{0,40}data-test="chat-dock-steer"/)
    expect(panelSrc).toContain("const canSteer = computed(() => running.value && draft.value.trim() !== '')")
    // In-place entry never opens a second run: the supplement rides the session door.
    expect(panelSrc).toContain('injectChatSupplement(sessionId.value, text)')
    // The placeholder speaks the steering posture while an answer runs.
    expect(panelSrc).toContain("running ? t('layout.chatDock.steerPlaceholder')")
  })

  it('refuses to drop a supplement silently: the degraded verdict is spoken and the thread re-reads from the ledger', () => {
    expect(panelSrc).toContain('layout.chatDock.steerDegraded')
    expect(panelSrc).toContain('threadReloadPending')
    // The reload rides the SHARED session door (one loader, no second mapper).
    expect(panelSrc).toMatch(/threadReloadPending = false[\s\S]{0,160}openSession\(settledId\)/)
  })

  it('ships every steering key in both locales', () => {
    const keys = ['steerSend', 'steerPlaceholder', 'steerDegraded', 'steerFailed', 'injectChip'] as const
    for (const key of keys) {
      expect((zhCN.layout as any).chatDock[key], `zh-CN ${key}`).toBeTruthy()
      expect((enUS.layout as any).chatDock[key], `en-US ${key}`).toBeTruthy()
    }
  })
})
