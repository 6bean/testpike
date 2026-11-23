/**
 * Per-question ask-from anchor contract :
 * - the send door stamps the header project + the page the question is asked
 * from onto the stream body, through the SAME whitelist builder the session
 * door uses (never a second implementation — core re-validates);
 * - a reconnect never carries an anchor (one run keeps one audited origin);
 * - the composer shows, before typing, where the AI is looking (light hint,
 * present only when a project segment exists — the honest-absence shape of
 * a disabled door, W6 wording: operation info only);
 * - page display names resolve through ONE menu-leaf shared with the topbar.
 */
import { describe, expect, it } from 'vitest'
import panelRaw from '@/components/ChatPanel.vue?raw'
import layoutRaw from '@/layouts/MainLayout.vue?raw'
import apiRaw from '@/api/aiChat.ts?raw'
import { allMenuLeaves, resolveMenuTitleKey } from '@/config/menuStructure'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

describe('send-door anchor', () => {
  it('send stamps the question anchor through the shared whitelist builder', () => {
    expect(panelRaw).toMatch(
      /questionContext: buildPageContext\(route\.path, route\.query, ctx\.primaryId\)/,
    )
  })

  it('the anchor rides the send body ONLY — a resume reconnect carries none', () => {
    // exactly one wiring point inside the panel (send), never in resume()
    expect((panelRaw.match(/questionContext/g) ?? []).length).toBe(1)
    expect(panelRaw).toMatch(/startStream\(\{ resumeCursor: handle\?\.cursor\(\) \?\? 0 \}\)/)
  })

  it('the wire shape mirrors the admin door (optional string, forward-compatible)', () => {
    expect(apiRaw).toMatch(/questionContext\?: string/)
  })
})

describe('composer context hint', () => {
  it('the hint renders with a data-test anchor and stays absent without a slice', () => {
    expect(panelRaw).toMatch(/data-test="chat-context-hint"/)
    expect(panelRaw).toMatch(/v-if="ctx\.primaryId != null"[^>]*data-test="chat-context-hint"/)
    expect(panelRaw).toMatch(/layout\.chatDock\.contextHint/)
  })

  it('both locales carry the hint sentence with project + page placeholders', () => {
    for (const raw of [zhRaw, enRaw]) {
      const line = raw.split('\n').find((l) => l.trim().startsWith('contextHint:'))
      expect(line, 'contextHint missing in one locale').toBeTruthy()
      expect(line).toContain('{project}')
      expect(line).toContain('{page}')
    }
  })

  it('the topbar title resolves through the shared resolver, no second lookup', () => {
    expect(layoutRaw).toMatch(/resolveMenuTitleKey\(/)
  })
})

describe('resolveMenuTitleKey (single source for page display names)', () => {
  it('prefers the route meta title when the route declares one', () => {
    expect(resolveMenuTitleKey('/anything', 'nav.ghost')).toBe('nav.ghost')
  })

  it('falls back to the menu-leaf mapping for detail routes without meta', () => {
    for (const leaf of allMenuLeaves.slice(0, 5)) {
      expect(resolveMenuTitleKey(leaf.path, undefined)).toBe(leaf.titleKey)
    }
  })

  it('ends honestly with the app name for unknown paths', () => {
    expect(resolveMenuTitleKey('/no-such-page', undefined)).toBe('common.appName')
  })
})
