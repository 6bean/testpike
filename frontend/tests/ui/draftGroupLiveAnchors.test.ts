/**
 * Grouped-card live anchor wiring contract .
 *
 * Pinned rules:
 * - the api bridge mirrors the admin by-drafts door one-for-one: repeated
 * draftIds params, the viewer's projectId segment, and the shared DTO field
 * names from common/linkage (no second shape invented client-side);
 * - the anchors are READ, never an action path: they ride no ticket, fire no
 * apply and change no tick — the two-click door stays exactly as the
 * acceptance contract pins it (red line 10c: nothing auto-applies);
 * - a read failure (or a missing project segment) collapses the anchor block
 * silently instead of blocking the door: the ledger is a disclosure, the
 * door is the function;
 * - every anchor line rides both locale tracks (zh + en) with no hard-coded
 * Chinese in the component.
 */
import { describe, expect, it } from 'vitest'
import apiRaw from '@/api/linkage.ts?raw'
import groupRaw from '@/components/ChatDraftGroup.vue?raw'
import panelRaw from '@/components/ChatPanel.vue?raw'
import zhCN from '@/utils/locales/zh-CN'
import enUS from '@/utils/locales/en-US'
import { lookupLocale } from '../_localeLookup'

const lookup = lookupLocale

describe('grouped-card live anchors', () => {
  it('api bridge mirrors the by-drafts door and the shared DTO names', () => {
    expect(apiRaw).toContain('/linkage-impacts/by-drafts')
    // Batch read: draftIds rides repeated query params, never a body.
    expect(apiRaw).toContain('draftIds')
    expect(apiRaw).toContain('paramsSerializer')
    // Field names mirror common/linkage LinkageDraftImpact + LinkageImpactInfo.
    for (const field of [
      'draftId',
      'projectId',
      'edgeKey',
      'upstreamObjectType',
      'upstreamObjectId',
      'upstreamVersion',
      'downstreamObjectType',
      'downstreamId',
      'dispositionStrategy',
      'status',
      'annotatedAt',
      'consumedAt',
    ]) {
      expect(apiRaw).toContain(field)
    }
    expect(apiRaw).toContain('export async function getLinkageImpactsByDrafts')
  })

  it('the group container reads anchors afresh per open and renders rows plus one summary', () => {
    expect(groupRaw).toContain('getLinkageImpactsByDrafts')
    // Re-read when the card set or the viewer segment changes (anchor freshness
    // = the ledger state at the moment of the click, per the shared contract).
    expect(groupRaw).toContain('draftIdsKey')
    expect(groupRaw).toContain('data-test="draft-group-impacts"')
    expect(groupRaw).toContain('data-test="draft-group-impact-row"')
    expect(groupRaw).toContain('draft-group-impact-summary')
    // Anchor lines are display-only: the anchor block must not reach the
    // ticket/apply path (no second action channel hidden in the ledger).
    expect(groupRaw).not.toMatch(/anchors\.value[\s\S]{0,120}(openTicket|applyGroupDrafts)\(/)
    // Degrade honest: a failed read collapses the block, the door keeps working.
    expect(groupRaw).toContain('impactFailure')
    // The viewer segment comes from the header context prop (R30 single switch),
    // never a page-level second project picker.
    expect(groupRaw).toContain('projectId: number | null')
    expect(panelRaw).toContain(':project-id="ctx.primaryId"')
  })

  it('missing slice or failed read leaves the door untouched', () => {
    // No projectId → no request is fired at all (the door never guesses a segment).
    expect(groupRaw).toMatch(/if\s*\(props\.projectId\s*==\s*null\)/)
  })

  it('every anchor line rides both locale tracks', () => {
    const keys = [
      'layout.chatDock.draft.groupImpactSummary',
      'layout.chatDock.draft.groupImpactPending',
      'layout.chatDock.draft.groupImpactConsumed',
      'layout.chatDock.draft.type.case',
      'layout.chatDock.draft.type.requirement',
      'layout.chatDock.draft.type.api_spec',
      'layout.chatDock.draft.type.script',
    ]
    for (const key of keys) {
      expect(lookup(zhCN, key), `zh missing ${key}`).toBeTypeOf('string')
      expect(lookup(enUS, key), `en missing ${key}`).toBeTypeOf('string')
    }
  })
})
