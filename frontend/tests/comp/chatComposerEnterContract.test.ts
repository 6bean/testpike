/**
 * Composer keyboard contract (R33 input rule + user 10-28 order): Enter sends,
 * Shift+Enter breaks a line, an IME-confirming Enter NEVER sends, and the send
 * button is an optional door hidden by default (per-user localStorage, shared
 * by both chat surfaces so one switch holds on the dock and the page alike).
 *
 * Same ?raw source-pinning posture as chatDockWiring: the behaviour itself is
 * covered by the live E2E spectrum; this contract keeps the rules from being
 * silently dropped by a later refactor.
 */
import { describe, expect, it } from 'vitest'
import panelRaw from '@/components/ChatPanel.vue?raw'
import settingsRaw from '@/components/ChatComposerSettings.vue?raw'
import prefsRaw from '@/composables/useChatComposerPrefs.ts?raw'

describe('composer keyboard path', () => {
  it('sends on plain Enter and breaks the line on Shift+Enter', () => {
    expect(panelRaw).toMatch(/e\.key === 'Enter' && !e\.shiftKey/)
    expect(panelRaw).toMatch(/void send\(\)/)
  })

  it('guards the IME: a candidate-confirming Enter never fires a send', () => {
    // isComposing plus the legacy keyCode 229 — both must veto the send path.
    expect(panelRaw).toMatch(/!e\.isComposing/)
    expect(panelRaw).toMatch(/e\.keyCode !== 229/)
  })

  it('the send button renders only when the display preference says so', () => {
    expect(panelRaw).toMatch(/v-if="!running && showSendButton"/)
    // Run-control doors stay independent of the display preference.
    expect(panelRaw).toMatch(/data-test="chat-dock-stop"/)
    expect(panelRaw).toMatch(/data-test="chat-dock-resume"/)
  })

  it('a send with no header slice names the reason, never fails silently', () => {
    // With the button hidden there is no disabled door to hover, so Enter must
    // point the user to the switch itself (user 10-28 report: Enter did nothing).
    expect(panelRaw).toMatch(/ctx\.primaryId == null[\s\S]{0,80}ElMessage\.warning\(t\('layout\.chatDock\.noProjectTip'\)\)/)
  })
})

describe('composer display preference storage', () => {
  it('defaults to hidden (Enter sends directly) — zero-config factory state', () => {
    expect(prefsRaw).toMatch(/const showSendButton = ref\(false\)/)
    expect(prefsRaw).toMatch(/bucket === null \? false : parseFlag/)
  })

  it('buckets per user id and never carries a server-side key', () => {
    expect(prefsRaw).toMatch(/tp_chat_send_button/)
    expect(prefsRaw).toMatch(/localStorage/)
    expect(prefsRaw).not.toMatch(/request|api\/|axios|fetch\(/)
  })

  it('both entry surfaces mount the SAME settings piece (zero second copy)', () => {
    // The dock header and the page composer row share one component.
    expect(panelRaw).toMatch(/<ChatComposerSettings \/>/)
    expect(settingsRaw).toMatch(/data-test="chat-dock-settings"/)
    expect(settingsRaw).toMatch(/data-test="chat-send-switch"/)
    expect((panelRaw.match(/ChatComposerSettings/g) || []).length).toBeGreaterThanOrEqual(2)
  })
})
