/**
 * Login-state draft card contract .
 *
 * The card is the authorisation surface for registering a login state read
 * out of a DevTools screenshot: the server snapshot carries EVERY parsed
 * candidate row (unticked ones included) and the human's ticks decide which
 * rows the apply will land. Before this package the kind was unknown to the
 * reducer fold — it defaulted to SCRIPT_REPAIR, failed the anchored-edit
 * requirement and the card vanished entirely (a write door that never shows).
 *
 * Pinned rules:
 * - the kind is registered in the fold (KNOWN/ANCHOR_FREE sets) and owns no
 * - its template branch sits BEFORE the generic fallback (order-sensitive:
 * falling through is exactly the vanished-card defect family);
 * - a re-ticked subset is written back (PATCH checked-subset, names only)
 * strictly BEFORE the confirm ticket is asked — a refused subset costs no
 * ticket and no values ever ride the payload;
 * - zero ticks honestly disable the confirm door and the hint names the
 * missing act (no silent dead button, no generic success toast);
 * - the receipt names the login state, never the case-centric default;
 * - both locales carry the same key set with non-empty values.
 */
import { describe, expect, it } from 'vitest'
import cardRaw from '@/components/ChatDraftCard.vue?raw'
import reducerRaw from '@/composables/chatDockReducer.ts?raw'
import apiRaw from '@/api/aiChat.ts?raw'
import { lookupLocale } from '../_localeLookup'
import zhCN from '@/utils/locales/zh-CN'
import enUS from '@/utils/locales/en-US'

const lookup = lookupLocale

const BRANCH = "card.objectType === 'LOGIN_STATE_WRITE'"
const DRAFT_KEYS = [
  'layout.chatDock.draft.titleLoginState',
  'layout.chatDock.draft.loginStateHint',
  'layout.chatDock.draft.loginStateNeedTick',
  'layout.chatDock.draft.loginStateCount',
  'layout.chatDock.draft.loginStateUnreadable',
  'layout.chatDock.draft.appliedLoginState',
]

describe('login-state draft card (checked-subset door)', () => {
  it('is a registered anchor-free kind in the fold', () => {
    expect(reducerRaw).toMatch(/const ANCHOR_FREE_KINDS = new Set\(\[[^\]]*'LOGIN_STATE_WRITE'[^\]]*\]\)/)
    expect(reducerRaw).toMatch(/const KNOWN_DRAFT_KINDS = new Set\(\[[^\]]*'LOGIN_STATE_WRITE'/s)
    // no case anchor folded for this family (a wrong-object case chip is the
    // defect the evolution/API-access corrections already pinned out).
    // Line-break agnostic: later packages may extend the zero-fold family
    // list on the same line (TAG_WRITE did), the contract is LOGIN_STATE_WRITE
    // folds to 0 before the anchored else-branch.
    expect(reducerRaw).toMatch(/objectType === 'API_ACCESS_WRITE' \|\| objectType === 'LOGIN_STATE_WRITE'[\s\S]{0,80}\? 0/)
  })

  it('owns a template branch that comes before the generic fallback', () => {
    expect(cardRaw).toContain('chat-draft-loginstate-rows')
    const branchAt = cardRaw.indexOf(BRANCH)
    const fallbackAt = cardRaw.indexOf('<template v-else>')
    expect(branchAt).toBeGreaterThan(-1)
    expect(fallbackAt).toBeGreaterThan(-1)
    expect(branchAt).toBeLessThan(fallbackAt)
  })

  it('renders every candidate row of the snapshot as a tickable line', () => {
    // rows iterate the parsed snapshot, not a filtered "selected" subset
    expect(cardRaw).toContain('v-for="row in loginStateRows"')
    expect(cardRaw).toContain(':model-value="loginChecked.has(row.name)"')
    expect(cardRaw).toContain('@change="toggleLoginStateRow(row.name)"')
    // the fold keeps unselected rows (selected:false) — unticked, never dropped
    expect(cardRaw).toContain('out.push({ name, selected: row.selected === true })')
  })

  it('writes the re-ticked subset back strictly before the confirm ticket', () => {
    const syncAt = cardRaw.indexOf('await updateDraftCheckedSubset(props.card.draftId, [...loginChecked.value])')
    const ticketAt = cardRaw.indexOf('await requestDraftConfirmTicket(props.card.draftId)')
    expect(syncAt).toBeGreaterThan(-1)
    expect(ticketAt).toBeGreaterThan(-1)
    expect(syncAt).toBeLessThan(ticketAt)
    // the sync is gated on this family AND on a changed subset (R28 no-op skip)
    expect(cardRaw).toMatch(/objectType === 'LOGIN_STATE_WRITE' && loginSubsetDirty\.value/)
  })

  it('sends names only — values never cross the browser boundary', () => {
    expect(apiRaw).toContain('export function updateDraftCheckedSubset(draftId: number, names: string[])')
    expect(apiRaw).toContain("url: `/ai/chat/drafts/${draftId}/checked-subset`, method: 'patch', data: { names }")
  })

  it('disables the confirm door on zero ticks and names the missing act', () => {
    expect(cardRaw).toMatch(/data-test="chat-draft-confirm"[^>]*:disabled="loadingTicket \|\| terminalFailure \|\| zeroTickBlocked"/)
    expect(cardRaw).toContain("t(zeroTickBlocked ? 'layout.chatDock.draft.loginStateNeedTick' : draftHintKey)")
  })

  it('titles, hints and receipts in the login-state family, not the case default', () => {
    expect(cardRaw).toMatch(/objectType === 'LOGIN_STATE_WRITE'\) return 'layout\.chatDock\.draft\.titleLoginState'/)
    expect(cardRaw).toMatch(/objectType === 'LOGIN_STATE_WRITE'\) return 'layout\.chatDock\.draft\.loginStateHint'/)
    const receiptAt = cardRaw.indexOf('layout.chatDock.draft.appliedLoginState')
    const defaultAt = cardRaw.indexOf("return t('layout.chatDock.draft.applied',")
    expect(receiptAt).toBeGreaterThan(-1)
    expect(defaultAt).toBeGreaterThan(-1)
    expect(receiptAt).toBeLessThan(defaultAt)
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

  it('never claims a login-state card writes a script (one concept one word)', () => {
    const title = String(lookup(zhCN, 'layout.chatDock.draft.titleLoginState'))
    expect(title).not.toContain('脚本')
    expect(title).toContain('登录态')
    const hint = String(lookup(zhCN, 'layout.chatDock.draft.loginStateHint'))
    expect(hint).toContain('勾选')
    expect(hint).toContain('凭证值不经过对话内容')
  })
})
