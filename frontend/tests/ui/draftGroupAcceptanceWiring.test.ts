/**
 * Group draft-acceptance wiring contract .
 *
 * Pinned rules:
 * - the api bridge mirrors the admin group door endpoints and the receipt
 * DTO field names one-for-one (no second shape; outcome rides the core
 * value set "landed" / "stale-excluded" / "not-ticked" — the receipt
 * answers every card of the answer, an unticked one included);
 * - the group container never auto-applies: acceptance is still two clicks
 * (ticket, then apply) and the checked subset is the only payload beyond
 * the ticket (red line: no zero-click pass-through);
 * - ChatPanel folds a multi-draft answer through the group container while
 * a single draft keeps the plain card;
 * - every group line renders in the locale vocabulary (zh + en), and a
 * pulled-out card's detail (server sentence with the regenerate exit) is
 * shown verbatim instead of being re-phrased.
 */
import { describe, expect, it } from 'vitest'
import apiRaw from '@/api/aiChat.ts?raw'
import groupRaw from '@/components/ChatDraftGroup.vue?raw'
import panelRaw from '@/components/ChatPanel.vue?raw'
import cardRaw from '@/components/ChatDraftCard.vue?raw'
import zhCN from '@/utils/locales/zh-CN'
import enUS from '@/utils/locales/en-US'
import { lookupLocale } from '../_localeLookup'

const lookup = lookupLocale

describe('group draft acceptance wiring', () => {
  it('api bridge mirrors the group endpoints and the receipt DTO names', () => {
    expect(apiRaw).toContain('/ai/chat/drafts/group/${messageId}/confirm-token')
    expect(apiRaw).toContain('/ai/chat/drafts/group/${messageId}/apply')
    // Receipt mirror rides the admin DTO field names verbatim.
    for (const field of ['draftId', 'objectType', 'objectName', 'targetId', 'appliedVersion', 'outcome', 'detail']) {
      expect(apiRaw).toContain(field)
    }
    expect(apiRaw).toContain("'landed' | 'stale-excluded' | 'not-ticked'")
    // Apply body carries ticket + the ticked subset only.
    expect(apiRaw).toContain('data: { confirmToken, draftIds }')
  })

  it('group door keeps the two-click posture and the ticked subset', () => {
    expect(groupRaw).toContain('requestGroupDraftConfirmTicket')
    expect(groupRaw).toContain('applyGroupDrafts(props.messageId as number, ticket, selectedIds.value')
    // The door is disabled until the answer id exists and something is ticked
    // — the group never fires at a guessed message id.
    expect(groupRaw).toContain('props.messageId === null')
    // No auto-apply path: apply() bails without a held ticket.
    expect(groupRaw).toContain("ticket === ''")
    expect(groupRaw).not.toMatch(/applyGroupDrafts\([^)]*\)\s*\n?\s*[^/]*openTicket/)
    // Receipts read outcome lines; a refused apply keeps the ticket story to
    // the server sentence (no invented second wording).
    expect(groupRaw).toContain('groupReceiptLanded')
    expect(groupRaw).toContain('item.detail')
  })

  it('a worst-case answer folds through the group and a lone draft keeps the card', () => {
    expect(panelRaw).toContain('<ChatDraftGroup v-if="m.repairs.length > 1" :message-id="m.messageId" :cards="m.repairs" :project-id="ctx.primaryId" />')
    expect(panelRaw).toContain('<ChatDraftCard v-else-if="m.repairs.length === 1"')
    expect(panelRaw).toContain("import ChatDraftGroup from '@/components/ChatDraftGroup.vue'")
    // Individual card doors stay intact (the group is additive, not a replacement).
    expect(groupRaw).toContain('<ChatDraftCard :card="d" />')
    expect(cardRaw).toContain('data-test="chat-draft-confirm"')
  })

  it('every group line rides both locale tracks', () => {
    const keys = [
      'layout.chatDock.draft.groupTitle',
      'layout.chatDock.draft.groupChecked',
      'layout.chatDock.draft.groupPick',
      'layout.chatDock.draft.groupHint',
      'layout.chatDock.draft.groupAccept',
      'layout.chatDock.draft.groupApply',
      'layout.chatDock.draft.groupGenerating',
      'layout.chatDock.draft.groupNone',
      'layout.chatDock.draft.groupReceiptLanded',
      'layout.chatDock.draft.groupReceiptLandedPlain',
      'layout.chatDock.draft.groupReceiptSummary',
    ]
    for (const key of keys) {
      expect(lookup(zhCN, key), `zh missing ${key}`).toBeTruthy()
      expect(lookup(enUS, key), `en missing ${key}`).toBeTruthy()
    }
  })
})
