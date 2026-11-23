/**
 * Tag-write draft card contract .
 *
 * A TAG_WRITE draft frame used to vanish on the dialog surface: the kind was
 * unknown to the reducer fold, defaulted to SCRIPT_REPAIR, failed the
 * anchored-edit requirement and returned null — the two-click door never
 * appeared for "AI creates a tag" (spectrum 5 of the chat-sync E2E suite,
 * known-red until this package lands).
 *
 * Pinned rules:
 * - the kind is registered in the fold (KNOWN_DRAFT_KINDS) and anchor-free
 * (ANCHOR_FREE_KINDS): a new tag owns no case/requirement anchor, so the
 * jump chip (caseId folds to 0 like the API-access / login-state families);
 * - folding is behavioural, asserted through the real parseRepairDraft on a
 * live-frame meta AND on the reload synthesis (foldDraftRowToCard) so the
 * stream and the reloaded view land the same card shape;
 * - the card titles and hints itself in the tag family before the generic
 * creates a tag);
 * - both locales carry the same key set with non-empty values, and the tag
 * family wording never claims a script write (one concept, one word).
 */
import { describe, expect, it } from 'vitest'
import cardRaw from '@/components/ChatDraftCard.vue?raw'
import reducerRaw from '@/composables/chatDockReducer.ts?raw'
import { foldDraftRowToCard, parseRepairDraft } from '@/composables/chatDockReducer'
import { lookupLocale } from '../_localeLookup'
import zhCN from '@/utils/locales/zh-CN'
import enUS from '@/utils/locales/en-US'

const lookup = lookupLocale

const DRAFT_KEYS = [
  'layout.chatDock.draft.titleTagWrite',
  'layout.chatDock.draft.tagWriteHint',
]

const META = JSON.stringify({
  draftId: 7,
  actionJson: '{"name":"冒烟标签"}',
  objectType: 'TAG_WRITE',
  toolName: 'tag.create',
  executionId: 0,
})

describe('tag-write draft card (registered anchor-free kind)', () => {
  it('folds a live TAG_WRITE frame into a create card', () => {
    const card = parseRepairDraft(META, '新建标签草案：冒烟标签')
    expect(card, 'TAG_WRITE frame folded to null — the vanished-card defect').not.toBeNull()
    expect(card!.objectType).toBe('TAG_WRITE')
    expect(card!.actionKind).toBe('create')
    expect(card!.caseId).toBe(0)
    expect(card!.requirementId).toBe(0)
    expect(card!.draftId).toBe(7)
    expect(card!.toolName).toBe('tag.create')
  })

  it('folds the reloaded draft row to the same card shape', () => {
    const card = foldDraftRowToCard({
      draftId: 7,
      objectType: 'TAG_WRITE',
      payloadJson: '{"name":"冒烟标签"}',
      targetId: 21,
      baseVersion: null,
      summary: '新建标签草案：冒烟标签',
    })
    expect(card).not.toBeNull()
    expect(card!.objectType).toBe('TAG_WRITE')
    // the ledger target_id of this family is the produced tag row, never a
    // case — folding it as caseId would hang a wrong-object jump chip
    expect(card!.caseId).toBe(0)
  })

  it('is registered in the fold whitelists and the caseId-zero family list', () => {
    expect(reducerRaw).toMatch(/const ANCHOR_FREE_KINDS = new Set\(\[[^\]]*'TAG_WRITE'[^\]]*\]\)/)
    expect(reducerRaw).toMatch(/const KNOWN_DRAFT_KINDS = new Set\(\[[^\]]*'TAG_WRITE'/s)
    expect(reducerRaw).toMatch(/objectType === 'LOGIN_STATE_WRITE' \|\| objectType === 'TAG_WRITE'\s*\n\s*\? 0/)
    // the card union type carries the kind (no second wire vocabulary)
    expect(reducerRaw).toContain("'CASE_ROLLBACK' | 'LOGIN_STATE_WRITE' | 'TAG_WRITE'")
  })

  it('titles, hints in the tag family before the generic fallback', () => {
    const titleAt = cardRaw.indexOf("objectType === 'TAG_WRITE') return 'layout.chatDock.draft.titleTagWrite'")
    const hintAt = cardRaw.indexOf("objectType === 'TAG_WRITE') return 'layout.chatDock.draft.tagWriteHint'")
    const titleFallbackAt = cardRaw.indexOf("return 'layout.chatDock.draft.title'")
    const hintFallbackAt = cardRaw.indexOf("'layout.chatDock.draft.hint'")
    expect(titleAt).toBeGreaterThan(-1)
    expect(hintAt).toBeGreaterThan(-1)
    expect(titleAt).toBeLessThan(titleFallbackAt)
    expect(hintAt).toBeLessThan(hintFallbackAt)
  })

  it('carries the same key set in both locales with non-empty values', () => {
    for (const key of DRAFT_KEYS) {
      const zh = lookup(zhCN, key)
      const en = lookup(enUS, key)
      expect(typeof zh, `zh missing ${key}`).toBe('string')
      expect(String(zh).trim().length, `zh empty ${key}`).toBeGreaterThan(0)
      expect(typeof en, `en missing ${key}`).toBe('string')
      expect(String(en).trim().length, `en empty ${key}`).toBeGreaterThan(0)
    }
  })

  it('never claims a tag card writes a script (one concept one word)', () => {
    const title = String(lookup(zhCN, 'layout.chatDock.draft.titleTagWrite'))
    expect(title).not.toContain('脚本')
    expect(title).toContain('标签')
    const hint = String(lookup(zhCN, 'layout.chatDock.draft.tagWriteHint'))
    expect(hint).not.toContain('脚本')
    expect(hint).toContain('标签')
  })
})
