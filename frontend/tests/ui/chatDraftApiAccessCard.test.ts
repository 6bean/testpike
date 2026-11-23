/**
 * Interface pass-through draft card contract (generic API pass-through P1, front half).
 *
 * Why this file exists: the landing door for a pass-through change is the human one-click
 * confirmation on this card, so the card is the safety surface — not decoration. Before this
 * package the new kind fell into the generic `v-else` branch, which (a) titled the door with the
 * (a) titled the door with the script-repair wording on a change that calls this application's own
 *     endpoint, (b) printed argument keys without any label column and (c) answered the apply
 *     receipt with the case-centric "case #0 version null" template. Each of those belongs to the
 *     same defect family the REQUIREMENT_WRITE and EVOLUTION_PROPOSAL corrections already fixed.
 *
 * Pinned rules:
 *  - the kind has its own template branch, ordered BEFORE the generic fallback (an order-sensitive
 *    assertion: it is exactly the shape that made this kind fall into the generic fallback);
 *  - title, idle hint and apply receipt all route off API_ACCESS_WRITE, never off the default;
 *  - the payload folds into readable lines: entry + owning project + every declared argument, with
 *    unknown argument names travelling verbatim (dropping them would hide what is about to be sent);
 *  - an unparsable payload says so honestly instead of rendering an empty card;
 *  - both locales carry the same key set with non-empty values (no silent single-language label).
 */
import { describe, expect, it } from 'vitest'
import cardRaw from '@/components/ChatDraftCard.vue?raw'
import reducerRaw from '@/composables/chatDockReducer.ts?raw'
import { lookupLocale } from '../_localeLookup'
import zhCN from '@/utils/locales/zh-CN'
import enUS from '@/utils/locales/en-US'

// The dotted-key walk is a shared spec helper (R40 single source), not a per-suite copy.
const lookup = lookupLocale

const BRANCH = "card.objectType === 'API_ACCESS_WRITE'"
const DRAFT_KEYS = [
  'layout.chatDock.draft.titleApiAccess',
  'layout.chatDock.draft.apiAccessHint',
  'layout.chatDock.draft.apiAccessEndpoint',
  'layout.chatDock.draft.apiAccessProject',
  'layout.chatDock.draft.apiAccessNote',
  'layout.chatDock.draft.apiAccessUnreadable',
  'layout.chatDock.draft.appliedApiAccess',
  'layout.chatDock.draft.appliedApiAccessProduced',
]

describe('interface pass-through draft card', () => {
  it('owns a template branch that comes before the generic fallback', () => {
    expect(cardRaw).toContain('chat-draft-api-access-fields')
    const branchAt = cardRaw.indexOf(BRANCH)
    const fallbackAt = cardRaw.indexOf('<template v-else>')
    expect(branchAt).toBeGreaterThan(-1)
    expect(fallbackAt).toBeGreaterThan(-1)
    expect(branchAt).toBeLessThan(fallbackAt)
  })

  it('titles and hints the door in the interface family, not the script-repair default', () => {
    expect(cardRaw).toMatch(/objectType === 'API_ACCESS_WRITE'\) return 'layout\.chatDock\.draft\.titleApiAccess'/)
    expect(cardRaw).toMatch(/objectType === 'API_ACCESS_WRITE'\) return 'layout\.chatDock\.draft\.apiAccessHint'/)
  })

  it('folds entry, owning project and every declared argument into lines', () => {
    expect(cardRaw).toContain('const apiAccessLines = computed')
    expect(cardRaw).toContain("field: 'endpoint'")
    expect(cardRaw).toContain("field: 'project'")
    // argument names travel verbatim (the label map only overrides the envelope words)
    expect(cardRaw).toMatch(/for \(const \[key, value\] of Object\.entries\(args\)\)/)
    expect(cardRaw).toMatch(/lines\.push\(\{ field: key,/)
    // The project anchor must say it comes from the conversation (the landing door re-stamps it from
    // the session, so a payload cannot aim elsewhere) - but that sentence travels in the hint, not in
    // the label: the label column is shared with every other card family at a 4em floor, and a
    // parenthetical there pushes this one row's value off the common start line.
    const hint = String(lookup(zhCN, 'layout.chatDock.draft.apiAccessHint'))
    expect(hint).toContain('会话')
    expect(String(lookup(enUS, 'layout.chatDock.draft.apiAccessHint'))).toMatch(/conversation/i)
  })

  it('says honestly when the payload cannot be parsed', () => {
    expect(cardRaw).toContain("field: 'note'")
    expect(cardRaw).toContain('layout.chatDock.draft.apiAccessUnreadable')
  })

  it('answers the apply receipt in this family instead of the case-centric default', () => {
    expect(cardRaw).toContain('layout.chatDock.draft.appliedApiAccessProduced')
    expect(cardRaw).toContain('layout.chatDock.draft.appliedApiAccess')
    const receiptAt = cardRaw.indexOf('layout.chatDock.draft.appliedApiAccess')
    const defaultAt = cardRaw.indexOf("return t('layout.chatDock.draft.applied',")
    expect(receiptAt).toBeGreaterThan(-1)
    expect(defaultAt).toBeGreaterThan(-1)
    expect(receiptAt).toBeLessThan(defaultAt)
  })

  it('folds the kind without a case anchor and never names its target a case', () => {
    // The ledger's target_id for this family is whatever the change produced (a tag row today), so
    // routing it through caseId would hang a wrong-object case jump chip on the card — the same
    // defect the evolution families were corrected for.
    expect(reducerRaw).toMatch(/const ANCHOR_FREE_KINDS = new Set\(\[[^\]]*'API_ACCESS_WRITE'[^\]]*\]\)/)
    expect(reducerRaw).toMatch(/const KNOWN_DRAFT_KINDS = new Set\(\[[^\]]*'API_ACCESS_WRITE'/)
    expect(reducerRaw).toMatch(/objectType === 'API_ACCESS_WRITE'[^\n]*\n\s*\? 0/)
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

  it('never claims a pass-through card writes a script (W-group one concept one word)', () => {
    const titleKey = String(lookup(zhCN, 'layout.chatDock.draft.title'))
    expect(titleKey).toContain('脚本')
    const apiTitle = String(lookup(zhCN, 'layout.chatDock.draft.titleApiAccess'))
    expect(apiTitle).not.toContain('脚本')
    expect(apiTitle).toContain('接口')
  })

  it('keeps field labels inside the shared label column and moves the caveat to the hint', () => {
    // The card families share one label column with a 4em floor (draftFieldValueAlignment pins it).
    // A label carrying its own parenthetical pushes that row's value off the common start line - a
    // second layout inside one card - while the same sentence belongs to the hint, which already
    // explains the door.
    const project = String(lookup(zhCN, 'layout.chatDock.draft.apiAccessProject'))
    expect(project).toBe('归属项目')
    expect(project, 'the label column is not a place for caveats').not.toMatch(/[（(]/)
    expect(String(lookup(enUS, 'layout.chatDock.draft.apiAccessProject'))).toBe('Owning project')
    expect(String(lookup(zhCN, 'layout.chatDock.draft.apiAccessHint'))).toContain('归属项目')
    expect(String(lookup(enUS, 'layout.chatDock.draft.apiAccessHint'))).toContain('owning project')
  })

  it('renders every declared value through the shared clamp, whole value still reachable', () => {
    // This card is a review surface. An unbounded paragraph grows the confirmation door past the
    // fold (the field list then pushes itself off screen), while a silent cut hides what the reviewer
    // is approving. Both halves are pinned: every field row goes through the one clamp helper that
    // already exists in this family, and the untouched value travels in the title attribute.
    const bare = cardRaw.match(/<span class="draft-after">\{\{ line\.value \}\}/g) ?? []
    expect(bare.length, 'a field row prints the raw value instead of the clamp').toBe(0)
    const clamped = cardRaw.match(/:title="fullValueTitle\(line\.value\)">\{\{ compactValue\(line\.value, shapeWord\) \}\}/g) ?? []
    expect(clamped.length, 'field rows must clamp through compactValue').toBeGreaterThanOrEqual(3)
    expect(cardRaw).toMatch(/function fullValueTitle\(value: unknown\): string \{[\s\S]*?JSON\.stringify\(value\)/)
  })

  it('closes the door on a terminal refusal and keeps it open on a retryable one', () => {
    // Which refusals end a draft is decided by the sentence the card shows the user: an expired
    // ticket literally asks them to confirm again, so disabling that button would contradict the
    // card's own wording. Asserted both directions - a check that only looks at the positive half
    // still passes when the list is emptied (an idle-spinning gate is a defect, not a green).
    const list = cardRaw.match(/const TERMINAL_FAILURE_KEYS = \[([^\]]*)\]/)
    expect(list, 'terminal refusal list missing').not.toBeNull()
    for (const key of ['notPending', 'gone', 'conflict', 'caseConflict']) {
      expect(list![1], `'${key}' must close the door`).toContain(`'${key}'`)
    }
    for (const retryable of ['ticketExpired', 'ticketInvalid', 'generic']) {
      expect(list![1], `'${retryable}' is retryable: its door stays open`).not.toContain(`'${retryable}'`)
    }
    // The disabled expression may carry further honest gates added later
    // (zero-tick blocking, piece D3); this contract pins that
    // terminalFailure gates BOTH doors and nothing more of the expression.
    expect(cardRaw).toMatch(/data-test="chat-draft-confirm"[^>]*:disabled="loadingTicket \|\| terminalFailure(\s*\|\|\s*zeroTickBlocked)?"/)
    expect(cardRaw).toMatch(/data-test="chat-draft-apply"[^>]*:disabled="applying \|\| terminalFailure"/)
    // and the idle guidance disappears with the door it describes
    expect(cardRaw).toContain('v-if="!terminalFailure" class="draft-hint"')
  })
})
