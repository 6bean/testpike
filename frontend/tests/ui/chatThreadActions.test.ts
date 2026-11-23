/**
 * stage 4 — thread action layout contract (export + save-to-knowledge).
 *
 * Pinned rules (structure via ?raw, the chatDockWiring precedent):
 * - one shared action component (no second copy between the dock header and
 * the standalone page — reuse >= 2 extracted, red line 6);
 * - both doors ride the request wrapper on the admin path — the browser
 * NEVER talks to ai-service directly (mirror of the api-layer contract);
 * - the save-to-knowledge toast names the produced document and offers the
 * one-click route to the document management tab (W-group: point at the
 * object, make it reachable);
 * - double-submit guard: each action disables while in flight (F-group);
 * - zero server-side judgment copied to the front (no empty-dialog check —
 * core answers 40001 with its own human wording);
 * - fully i18n: no CJK literals in the component source (bilingual locales
 * carry the wording).
 */
import { describe, expect, it } from 'vitest'
import apiRaw from '@/api/aiChat.ts?raw'
import actionsRaw from '@/components/ChatThreadActions.vue?raw'
import panelRaw from '@/components/ChatPanel.vue?raw'
import pageRaw from '@/views/ai/AiChatView.vue?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

describe('api mirror of the two session doors', () => {
  it('exports one session through the admin door', () => {
    expect(apiRaw).toMatch(/export function exportChatSession/)
    expect(apiRaw).toMatch(/url: `\/ai\/chat\/sessions\/\$\{sessionId\}\/export`/)
    expect(apiRaw).toMatch(/method: 'get'/)
  })

  it('saves one session to the knowledge base through the admin door', () => {
    expect(apiRaw).toMatch(/export function sedimentChatSession/)
    expect(apiRaw).toMatch(/url: `\/ai\/chat\/sessions\/\$\{sessionId\}\/sediment`/)
    expect(apiRaw).toMatch(/method: 'post'/)
  })

  it('never reaches for ai-service directly', () => {
    expect(actionsRaw).not.toMatch(/18084|8084|localhost/)
  })
})

describe('shared thread action component', () => {
  it('is the single copy mounted by both surfaces', () => {
    expect(panelRaw).toContain('ChatThreadActions')
    expect(pageRaw).toContain('ChatThreadActions')
  })

  it('guards double submits with per-action busy flags', () => {
    expect(actionsRaw).toMatch(/exporting/)
    expect(actionsRaw).toMatch(/sedimenting/)
    expect(actionsRaw).toMatch(/:loading=/)
    expect(actionsRaw).toMatch(/:disabled=/)
  })

  it('copies no server judgment — no empty-dialog pre-check on the client', () => {
    // The refusal wording (40001/40301/40904) is single-sourced in core; the
    // component shows whatever the server answers.
    expect(actionsRaw).not.toMatch(/turns\.length|messageCount/)
  })

  it('routes the success toast to the document management tab', () => {
    expect(actionsRaw).toMatch(/\/extraction/)
    expect(actionsRaw).toMatch(/tab: 'documents'/)
  })

  it('carries zero CJK literals (bilingual locale single source)', () => {
    const template = actionsRaw.slice(0, actionsRaw.indexOf('<script'))
    expect(template).not.toMatch(/[\u4e00-\u9fff]/)
  })
})

describe('bilingual locale keys', () => {
  it('zh and en both carry the thread action family', () => {
    for (const raw of [zhRaw, enRaw]) {
      expect(raw).toMatch(/exportAction:/)
      expect(raw).toMatch(/sedimentAction:/)
      expect(raw).toMatch(/sedimentDone:/)
      expect(raw).toMatch(/viewDocuments:/)
    }
  })
})
