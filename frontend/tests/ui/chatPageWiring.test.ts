/**
 * stage 3 — standalone chat page wiring contract (source-as-text).
 *
 * Pinned rules from the adjudication (§seven + §thirteen):
 * - the standalone page mounts the SAME kernel component as the dock — the
 * conversation logic exists exactly once (no second copy);
 * - the page opens sessions with source=2 (standalone), the dock with
 * source=1; the badge deep-link value 3 stays reserved;
 * - the page is a registered route + menu leaf under the AI capability
 * group, i18n bilingual, permission-keyed with perm:aichat:use;
 * - R30: no project selector on the page — the segment rides the header;
 * - the dock and the floating bubble stay mutually exclusive as before.
 */
import { describe, expect, it } from 'vitest'
import panelRaw from '@/components/ChatPanel.vue?raw'
import dockRaw from '@/components/ChatDock.vue?raw'
import pageRaw from '@/views/ai/AiChatView.vue?raw'
import routesRaw from '@/router/routes.ts?raw'
import menuRaw from '@/config/menuStructure.ts?raw'
import zhCN from '@/utils/locales/zh-CN'
import { lookupLocale } from '../_localeLookup'
import enUS from '@/utils/locales/en-US'

// The dotted-key walk is the shared spec helper (single source).
const lookup = lookupLocale

describe('one kernel shared by dock and page (no second logic copy)', () => {
  it('the page mounts ChatPanel with source 2', () => {
    expect(pageRaw).toContain('ChatPanel')
    expect(pageRaw).toMatch(/:source="2"/)
  })

  it('the dock wraps ChatPanel with source 1 (or 3 on a badge deep link, AO T429)', () => {
    expect(dockRaw).toContain('ChatPanel')
    // E1: the plain dock entry stays source=1; a failure-badge open
    // switches to source=3 so the session row records the badge surface.
    expect(dockRaw).toMatch(/:source="hasBadge \? 3 : 1"/)
  })

  it('session creation lives in the kernel and takes source as a prop', () => {
    expect(panelRaw).toContain('createChatSession')
    expect(panelRaw).toContain('source')
    // Neither wrapper re-implements the stream flow.
    expect(dockRaw).not.toContain('openChatStream')
    expect(pageRaw).not.toContain('openChatStream')
  })

  it('the kernel stays locale-clean (zero hardcoded Chinese)', () => {
    expect(panelRaw).not.toMatch(/[\u4e00-\u9fff]/)
    expect(pageRaw).not.toMatch(/[\u4e00-\u9fff]/)
  })

  it('R30: the page hosts no second project selector', () => {
    expect(pageRaw).not.toContain('ProjectSelect')
    // segment rides the header context through the single-source page-context
    // composable (deep-link first, header fallback) — no direct store fork.
    expect(pageRaw).toContain('useProjectPageContext')
    expect(panelRaw).toContain('useProjectContextStore')
  })
})

describe('route + menu registration', () => {
  it('the route /ai/chat is registered with the perm gate', () => {
    expect(routesRaw).toContain("path: 'ai/chat'")
    expect(routesRaw).toContain('AiChatView')
    expect(routesRaw).toMatch(/perm: 'perm:aichat:use'/)
  })

  it('the menu leaf sits in the AI capability group with help mapping', () => {
    expect(menuRaw).toContain("path: '/ai/chat'")
    expect(menuRaw).toContain("dataTest: 'menu-ai-chat'")
    const leaf = /path: '\/ai\/chat'.*/.exec(menuRaw)?.[0] ?? ''
    expect(leaf).toContain("perm: 'perm:aichat:use'")
    expect(leaf).toContain('helpDomain:')
    expect(leaf).toContain('helpTipKey')
  })

  it('i18n bilingual keys hit for the leaf title and help tip', () => {
    for (const [locale, bag] of [['zh', zhCN], ['en', enUS]] as const) {
      expect(typeof lookup(bag, 'layout.aiChat'), `${locale} layout.aiChat`).toBe('string')
      expect(typeof lookup(bag, 'layout.leafHelp.aiChat'), `${locale} leafHelp`).toBe('string')
    }
  })
})
