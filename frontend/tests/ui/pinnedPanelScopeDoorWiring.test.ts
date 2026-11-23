/**
 * Pinned-panel pending-disposal wiring contract .
 *
 * Pinned rules:
 * - the api bridge mirrors the three scope-shaped group endpoints and the
 * admin PendingGroupCardView fields one-for-one (no second shape);
 * - the pinned panel reuses the ONE group door (ChatDraftGroup) for a
 * system-origin group instead of growing a second acceptance flow, and a
 * stored draft row folds through the shared ledger-row fold that the reload
 * view already uses;
 * - the door keeps its two-click posture for both shapes: no path from a
 * pending read to a write without a held one-shot ticket;
 * - every sentence the section renders rides both locale tracks.
 */
import { describe, expect, it } from 'vitest'
import apiRaw from '@/api/aiChat.ts?raw'
import groupRaw from '@/components/ChatDraftGroup.vue?raw'
import panelRaw from '@/components/PinnedObjectPanel.vue?raw'
import sectionRaw from '@/components/PinnedPendingGroups.vue?raw'
import historyRaw from '@/composables/chatHistoryMapper.ts?raw'
import reducerRaw from '@/composables/chatDockReducer.ts?raw'
import zhCN from '@/utils/locales/zh-CN'
import enUS from '@/utils/locales/en-US'
import { lookupLocale } from '../_localeLookup'

const lookup = lookupLocale

describe('pinned panel scope-shaped group door', () => {
  it('api bridge mirrors the three scope endpoints and the read view fields', () => {
    expect(apiRaw).toContain('/ai/chat/drafts/group/pending?projectId=${projectId}${kindQuery}')
    expect(apiRaw).toContain("url: '/ai/chat/drafts/group/confirm-token'")
    expect(apiRaw).toContain("url: '/ai/chat/drafts/group/apply'")
    // The read mirror carries the admin DTO fields verbatim (no invented shape).
    for (const field of ['draftId', 'projectId', 'objectType', 'summary', 'targetId', 'baseVersion', 'schemaVersion', 'payloadJson', 'status']) {
      expect(apiRaw).toContain(field)
    }
    // Bodies carry only the segment coordinates, the ticket and the ticked ids.
    expect(apiRaw).toContain('data: { projectId, objectType }')
    expect(apiRaw).toContain('data: { projectId, objectType, confirmToken, draftIds }')
  })

  it('one group door serves both group shapes without a second flow', () => {
    // The scope branch exists for both clicks and shares everything else.
    expect(groupRaw).toContain('scope?: { projectId: number; objectType: string } | null')
    expect(groupRaw).toContain('requestGroupScopeConfirmTicket(props.scope.projectId, props.scope.objectType)')
    expect(groupRaw).toContain('applyGroupScopeDrafts(')
    // Neither shape can reach the door without a held ticket, and the payload
    // beyond the ticket is still only the ticked subset.
    expect(groupRaw).toContain("ticket === ''")
    expect(groupRaw).toContain('props.scope === null && props.messageId === null')
    expect(groupRaw).toContain('selectedIds.value')
    // The answer tells its caller to re-read instead of patching rows locally.
    expect(groupRaw).toContain("emit('applied', answered.items)")
  })

  it('the panel section reads pending cards and folds them through the shared fold', () => {
    expect(panelRaw).toContain('<PinnedPendingGroups')
    expect(panelRaw).toContain(':project-id="projectId"')
    expect(sectionRaw).toContain('fetchPendingGroupCards(projectId)')
    expect(sectionRaw).toContain('foldDraftRowToCard(')
    expect(sectionRaw).toContain('<ChatDraftGroup')
    expect(sectionRaw).toContain(':scope="{ projectId: g.projectId, objectType: g.objectType }"')
    expect(sectionRaw).toContain('onDoorApplied(`${g.projectId}:${g.objectType}`)')
    // D4d: one enumerated read owns the segment; the panel keeps no kind
    // vocabulary and never fans out per pinned-row kind again.
    expect(sectionRaw).not.toContain('runBounded(')
    expect(sectionRaw).not.toContain('groupKinds')
    expect(sectionRaw).toContain('byKind')
    // No write path of its own: the section never calls the door endpoints, so
    // the two clicks inside the shared door stay the only way anything lands.
    expect(sectionRaw).not.toContain('applyGroupScopeDrafts')
    expect(sectionRaw).not.toContain('requestGroupScopeConfirmTicket')
  })

  it('the ledger-row fold is single-sourced, not copied per surface', () => {
    expect(reducerRaw).toContain('export function foldDraftRowToCard(')
    // The reload view consumes the shared fold; its inline synthesis is gone.
    expect(historyRaw).toContain('foldDraftRowToCard({')
    expect(historyRaw).not.toContain('parseRepairDraft(JSON.stringify(')
  })

  it('every new sentence rides both locale tracks', () => {
    const keys = [
      'layout.chatDock.pinnedPanel.pendingGroupsTitle',
      'layout.chatDock.pinnedPanel.pendingGroupsFailed',
    ]
    for (const key of keys) {
      expect(lookup(zhCN, key), `zh missing ${key}`).toBeTruthy()
      expect(lookup(enUS, key), `en missing ${key}`).toBeTruthy()
    }
  })
})
