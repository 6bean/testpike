/**
 * Disposition-page draft-card wiring contract (source-as-text).
 *
 * Pinned rules:
 *  - the disposition queue mirrors the admin bridge one-for-one: a pregen
 *    action posts to /doc-change-analyses/{id}/pregen-drafts (the receipts
 *    field names ride the admin DTO — no second shape);
 *  - opening drafts never writes a requirement itself: the confirmation door
 *    stays the human one-click accept inside the chat session (a zero-click
 *    auto-apply door is never opened without a named order);
 *  - the receipt is user-facing operation info only (dispatched / skipped
 *    counts) plus a deep link that lands the chat page on that session
 *    (?session=<id> rides the kernel's exposed openSession door);
 *  - the affected-case extension resolves case titles per expanded row
 *    (lazy, bounded) instead of showing bare ids only.
 */
import { describe, expect, it } from 'vitest'
import apiRaw from '@/api/docChangeAnalysis.ts?raw'
import viewRaw from '@/views/ai/DocumentListView.vue?raw'
import chatPageRaw from '@/views/ai/AiChatView.vue?raw'
import cardRaw from '@/components/ChatDraftCard.vue?raw'
import reducerRaw from '@/composables/chatDockReducer.ts?raw'
import zhCN from '@/utils/locales/zh-CN'
import { lookupLocale } from '../_localeLookup'
import enUS from '@/utils/locales/en-US'

// The dotted-key walk is the shared spec helper (single source).
const lookup = lookupLocale

describe('disposition draft-card wiring', () => {
  it('api bridge mirrors the pregen endpoint on the analysis row', () => {
    expect(apiRaw).toContain('export function pregenDrafts')
    expect(apiRaw).toContain('pregen-drafts')
    // receipt mirror carries the admin DTO field names verbatim
    expect(apiRaw).toContain('skippedExisting')
    expect(apiRaw).toContain('skippedNoContent')
  })

  it('disposition queue exposes the pregen action and renders the receipt', () => {
    expect(viewRaw).toContain('doc-change-pregen-btn')
    expect(viewRaw).toContain('doc-change-draft-receipt')
    expect(viewRaw).toContain('doc-change-draft-go')
    expect(viewRaw).toContain('pregenDrafts')
  })

  it('affected-case extension renders resolved titles, not bare ids only', () => {
    expect(viewRaw).toContain('doc-change-case-chip')
    expect(viewRaw).toContain('getCase')
  })

  it('chat page consumes the ?session= deep link through the kernel door', () => {
    expect(chatPageRaw).toContain('query.session')
    expect(chatPageRaw).toContain('openSession')
  })

  it('i18n bilingual keys present on both locales with real values', () => {
    for (const key of [
      'document.changePregen',
      'document.changePregenOk',
      'document.changePregenGo',
      'document.changePregenFail',
      'document.changePregenNone',
      'document.affectedCasesTitle',
      'document.affectedCaseMissing',
    ]) {
      expect(typeof lookup(zhCN, key), key).toBe('string')
      expect((lookup(zhCN, key) as string).length, key).toBeGreaterThan(0)
      expect(typeof lookup(enUS, key), key).toBe('string')
      expect((lookup(enUS, key) as string).length, key).toBeGreaterThan(0)
    }
  })

  // D10 (vision pass): the confirmation card of a REQUIREMENT_WRITE draft
  // that decides whether a requirement version lands. The card must speak the
  // requirement family: its own titles, a hint that names the requirement
  // version chain, a receipt that names the requirement, and a payload fold
  // that shows the proposed requirement fields (never a script diff).
  it('draft card titles, hints, receipts and payload fold name the requirement family', () => {
    expect(cardRaw).toContain("'REQUIREMENT_WRITE'")
    for (const key of [
      'layout.chatDock.draft.titleRequirementCreate',
      'layout.chatDock.draft.titleRequirementEdit',
      'layout.chatDock.draft.requirementHint',
      'layout.chatDock.draft.appliedRequirementCreate',
      'layout.chatDock.draft.appliedRequirementEdit',
    ]) {
      expect(cardRaw).toContain(key.split('.').pop()!)
    }
    for (const key of [
      'layout.chatDock.draft.titleRequirementCreate',
      'layout.chatDock.draft.titleRequirementEdit',
      'layout.chatDock.draft.requirementHint',
      'layout.chatDock.draft.appliedRequirementCreate',
      'layout.chatDock.draft.appliedRequirementEdit',
    ]) {
      expect(typeof lookup(zhCN, key), key).toBe('string')
      expect((lookup(zhCN, key) as string).length, key).toBeGreaterThan(0)
      expect(typeof lookup(enUS, key), key).toBe('string')
      expect((lookup(enUS, key) as string).length, key).toBeGreaterThan(0)
    }
  })

  it('reducer registers REQUIREMENT_WRITE as an anchor-free kind', () => {
    expect(reducerRaw).toMatch(/KNOWN_DRAFT_KINDS[\s\S]*'REQUIREMENT_WRITE'/)
    expect(reducerRaw).toMatch(/ANCHOR_FREE_KINDS[\s\S]*'REQUIREMENT_WRITE'/)
  })
})
