/**
 * P2 dialog snapshot share, front-end contract.
 *
 * Pinned rules (structure via ?raw, the chatThreadActions precedent):
 * - the three owner doors ride the request wrapper on the admin path
 * (create / list / revoke); the browser NEVER talks to ai-service;
 * - the public read rides a dedicated unauthenticated door
 * (GET /open/chat-share/{token}) on its own axios instance — zero JWT
 * header, zero refresh-on-401, zero login redirect, because a link
 * holder has no session and a shared instance would bounce them to
 * the login page (red line: public surface stays honest to its scope);
 * - shareToken is a credential: it may only appear on the owner's share
 * panel (the list the owner opens for their own session) — never on
 * any other page, never in the thread op strip itself;
 * - the public view mounts OUTSIDE the authenticated layout with
 * meta.public (router guard precedent = Login/PwdExpired);
 * - the public view never invents wording: expired/revoked/unknown all
 * show core's own sentence carried by the response msg;
 * - markdown rendering reuses the escape-first MarkdownPreview piece
 * (no new dependency, no raw v-html copy);
 * - fully i18n: zero CJK literals in component source; bilingual locales
 * carry every string (W6: operational wording only, no governance talk).
 */
import { describe, expect, it } from 'vitest'
import viteCfgRaw from '../../vite.config.ts?raw'
import apiRaw from '@/api/aiChat.ts?raw'
import actionsRaw from '@/components/ChatThreadActions.vue?raw'
import panelRaw from '@/components/ChatSharePanel.vue?raw'
import viewRaw from '@/views/ShareChatView.vue?raw'
import routesRaw from '@/router/routes.ts?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

describe('admin mirror of the three owner doors', () => {
  it('creates a share through the admin door', () => {
    expect(apiRaw).toMatch(/export function createChatShare/)
    expect(apiRaw).toMatch(/url: `\/ai\/chat\/sessions\/\$\{sessionId\}\/share`/)
    expect(apiRaw).toMatch(/method: 'post'/)
  })

  it('lists and revokes through the admin door', () => {
    expect(apiRaw).toMatch(/export function listChatShares/)
    expect(apiRaw).toMatch(/url: `\/ai\/chat\/sessions\/\$\{sessionId\}\/shares`/)
    expect(apiRaw).toMatch(/export function revokeChatShare/)
    expect(apiRaw).toMatch(/url: `\/ai\/chat\/shares\/\$\{shareId\}`/)
    expect(apiRaw).toMatch(/method: 'delete'/)
  })

  it('never reaches for ai-service directly', () => {
    expect(actionsRaw).not.toMatch(/18084|8084/)
    expect(panelRaw).not.toMatch(/18084|8084|localhost/)
  })
})

describe('public read door', () => {
  it('rides its own unauthenticated axios instance, not the shared wrapper', () => {
    // The shared request() injects JWT and bounces 401/403 to the login page;
    // a link holder must never see either. A separate instance is the pin.
    expect(apiRaw).toMatch(/export async function fetchChatShareSnapshot/)
    expect(apiRaw).toMatch(/\/open\/chat-share\//)
    expect(apiRaw).toMatch(/xsrfCookieName: ''/)
  })

  it('is proxied by the dev server so same-origin works without login', () => {
    // vite.config proxy must carry '/open' → api service (8082) in dev;
    // without it a public link opened on :5173 would 404 on the vite host.
    expect(viteCfgRaw).toMatch(/'\/open'/)
    expect(viteCfgRaw).toMatch(/8082/)
  })
})

describe('owner share panel', () => {
  it('is the single copy mounted through the thread actions of both surfaces', () => {
    expect(actionsRaw).toContain('ChatSharePanel')
    expect(panelRaw).toMatch(/data-test="chat-share-btn"/)
  })

  it('offers ttl choice, copy and revoke with per-action busy guards', () => {
    expect(panelRaw).toMatch(/ttlHours/)
    expect(panelRaw).toMatch(/writeText|copyLink/)
    expect(panelRaw).toMatch(/revokeChatShare/)
    expect(panelRaw).toMatch(/:loading=|busy/)
  })

  it('shows the token-bearing link only inside this owner panel', () => {
    // The op strip itself carries no token; only the panel does.
    expect(actionsRaw).not.toMatch(/shareToken/)
    expect(panelRaw).toMatch(/shareToken/)
  })
})

describe('public read-only page', () => {
  it('is registered as a top-level public route outside the layout', () => {
    expect(routesRaw).toMatch(/path: '\/share\/chat\/:token'/)
    expect(routesRaw).toMatch(/public: true/)
  })

  it('renders the frozen markdown through the escape-first preview piece', () => {
    expect(viewRaw).toContain('MarkdownPreview')
    expect(viewRaw).not.toMatch(/v-html/)
  })

  it('copies no judgment — dead links show the server sentence', () => {
    expect(viewRaw).toMatch(/message|msg/)
    expect(viewRaw).not.toMatch(/expiresAt\s*<|Date\.now\(\)/)
  })

  it('never renders any login affordance or session id', () => {
    expect(viewRaw).not.toMatch(/sessionId|ownerUserId/)
  })
})

describe('bilingual locales', () => {
  it('carry the share strings in both languages', () => {
    for (const raw of [zhRaw, enRaw]) {
      expect(raw).toMatch(/shareAction/)
      expect(raw).toMatch(/chatShare:/)
    }
  })

  it('keep component sources free of hard-coded CJK', () => {
    const cjk = /[\u4e00-\u9fff]/
    expect(cjk.test(panelRaw)).toBe(false)
    expect(cjk.test(viewRaw)).toBe(false)
  })

  it('render expiry times in the readable YYYY-MM-DD HH:mm shape', () => {
    // Browser-default toLocaleString answers "10/31/2026, 2:04:19 PM" in a
    // zh interface — the American shape was a vision-pass finding on both
    // share faces; the single-sourced formatter piece is the pin.
    expect(panelRaw).not.toMatch(/toLocaleString\(\)/)
    expect(viewRaw).not.toMatch(/toLocaleString\(\)/)
    expect(panelRaw).toMatch(/formatLocalDateTime/)
    expect(viewRaw).toMatch(/formatLocalDateTime/)
  })

  it('phrase the expiry meta as a complete clause, verb-object op names', () => {
    // (W6 operational wording, R19 naming).
    expect(zhRaw).toMatch(/linkMeta: '\{turns\} 轮问答 · 将于 \{expires\} 失效'/)
    expect(zhRaw).toMatch(/meta: '只读快照 · \{turns\} 轮问答 · 将于 \{expires\} 失效'/)
    expect(zhRaw).toMatch(/revoke: '撤回链接'/)
    expect(enRaw).toMatch(/linkMeta: '\{turns\} Q&A turns · expires \{expires\}'/)
    expect(enRaw).toMatch(/revoke: 'Revoke link'/)
  })
})
