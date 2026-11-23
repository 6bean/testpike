/**
 * stage 2b — dock wiring contract (source-as-text, ?raw precedent of
 * chatFabMount.test.ts; component render itself is covered by E2E stage 3).
 *
 * Pinned structural rules:
 * - the session door rides the request wrapper (same-origin /api/v1 via the
 * vite proxy) — the browser NEVER connects to ai-service directly;
 * - the dock takes its project from the global header context (single source,
 * R30) and hosts no second selector;
 * - send opens the real stream (openChatStream) and folds frames through the
 * reducer (one parsing/folding point);
 * - a dropped stream resumes with the acknowledged cursor (never a second run);
 * - the dock template is fully i18n (zero CJK in source — bilingual locales
 * carry the wording);
 * - missing project context disables send with a human hint (G-group echo).
 */
import { describe, expect, it } from 'vitest'
import apiRaw from '@/api/aiChat.ts?raw'
import dockRaw from '@/components/ChatDock.vue?raw'
// Stage 3 kernel extraction: the conversation engine moved to ChatPanel (the
// dock became a mount-only drawer shell). Wiring assertions now pin the
// kernel; the dock keeps the drawer shell + width preference.
import panelRaw from '@/components/ChatPanel.vue?raw'

describe('session door of the api layer', () => {
  it('opens a session through the request wrapper on the admin door', () => {
    expect(apiRaw).toMatch(/export function createChatSession/)
    expect(apiRaw).toMatch(/url: '\/ai\/chat\/sessions'/)
    expect(apiRaw).toMatch(/method: 'post'/)
  })

  it('never reaches for ai-service directly', () => {
    expect(dockRaw).not.toMatch(/18084|8084|localhost/)
    expect(apiRaw).not.toMatch(/18084|8084|http:\/\//)
  })
})

describe('dock wiring to the live stream', () => {
  it('consumes the global header project as its only project source', () => {
    expect(panelRaw).toContain('useProjectContextStore')
    expect(panelRaw).toContain('ctx.primaryId')
    expect(panelRaw).not.toContain('ProjectSelect')
  })

  it('sends through the stream client and folds frames with the reducer', () => {
    expect(panelRaw).toContain('openChatStream')
    expect(panelRaw).toContain('createChatSession')
    expect(panelRaw).toContain('applyFrameToTurn')
  })

  it('resumes a dropped stream from the acknowledged cursor', () => {
    expect(panelRaw).toContain('resumeCursor')
    expect(panelRaw).toContain('.cursor()')
  })

  it('guards send on project context with a human hint', () => {
    expect(panelRaw).toContain('noProjectTip')
    expect(panelRaw).toMatch(/disabled=\S*!canSend/)
  })

  it('keeps the analysis-process area collapsible and data-test addressable', () => {
    expect(panelRaw).toContain('data-test="chat-dock-process"')
    expect(panelRaw).toContain('data-test="chat-process-toggle"')
  })

  it('follows the live stream to the bottom but never hijacks an upward read', () => {
    // Scroll-to-follow seam: frame-arrival scroll gated by the sticky-bottom flag.
    expect(panelRaw).toContain('pinnedToBottom')
    expect(panelRaw).toContain('scrollToBottom')
    expect(panelRaw).toMatch(/@scroll="onBodyScroll"/)
  })

  it('collapses the process area once the turn seals unless the reader pinned it open', () => {
    // S-group default-collapsed at scale after completion; manual toggles win.
    expect(panelRaw).toContain('manualToggles')
    expect(panelRaw).toMatch(/frame\.event === 'done'[^\n]*'error'/)
  })

  it('carries zero hardcoded Chinese (locale single source)', () => {
    expect(panelRaw).not.toMatch(/[\u4e00-\u9fff]/)
    expect(dockRaw).not.toMatch(/[\u4e00-\u9fff]/)
  })
})
