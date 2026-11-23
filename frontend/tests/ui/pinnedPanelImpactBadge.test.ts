/**
 * Pinned-panel downstream-impact badge contract .
 *
 * The urge/expire backend lands the ledger; this segment puts the per-object
 * "N downstream impacts awaiting disposal" badge on the pinned object rows.
 *
 * Pinned rules:
 * - the badge counts come from the ONE pending-impact read door
 * (GET /api/v1/linkage-impacts/pending, core-owned statuses 1/2/3);
 * the api bridge mirrors the LinkagePendingImpacts shape one-for-one;
 * - clicking the badge reveals the group-card section (expand + scroll),
 * reusing the ONE door area from piece D4c — no second disposal surface;
 * - disposal zeroing is server-sourced: after a door answers, the badge is
 * re-read from the ledger, never patched to zero client-side
 * (no-cache posture — a local decrement would lie when other objects
 * still await disposal under the same door).
 */
import { describe, expect, it } from 'vitest'
import apiRaw from '@/api/linkage.ts?raw'
import panelRaw from '@/components/PinnedObjectPanel.vue?raw'
import sectionRaw from '@/components/PinnedPendingGroups.vue?raw'
import zhCN from '@/utils/locales/zh-CN'
import enUS from '@/utils/locales/en-US'
import { lookupLocale } from '../_localeLookup'

const lookup = lookupLocale

describe('pinned panel downstream-impact badge', () => {
  it('api bridge exposes the one pending-impact read door', () => {
    expect(apiRaw).toContain("url: '/linkage-impacts/pending'")
    expect(apiRaw).toContain('export async function getLinkagePendingImpacts')
    // The shared shape is mirrored, not re-invented (server record owns it).
    expect(apiRaw).toContain('interface LinkagePendingImpactsShape')
    expect(apiRaw).toContain('projectId: number | null')
    expect(apiRaw).toContain('impacts: LinkageImpactInfo[]')
    // Viewer's segment rides the query only when known (R30 single switch).
    expect(apiRaw).toContain('...(projectId != null ? { projectId } : {})')
  })

  it('badge renders per pinned row from the ledger read, zero stays absent', () => {
    // The row reads the live ledger through the shared bounded fan-out.
    expect(panelRaw).toContain('getLinkagePendingImpacts(')
    expect(panelRaw).toContain('impactOf(row)')
    expect(panelRaw).toContain('data-test="pinned-impact-badge"')
    expect(panelRaw).toContain("impactOf(row) > 0")
    // Empty ledger renders no badge (absent capability, absent row).
    expect(panelRaw).toMatch(/v-if="impactOf\(row\) > 0"/)
  })

  it('badge click reveals the ONE group-card door area', () => {
    // Expand if collapsed, then scroll the existing section into view.
    expect(panelRaw).toContain('revealPendingGroups')
    expect(panelRaw).toContain('collapsed.value = false')
    expect(panelRaw).toContain('scrollIntoView')
    // The section gets a ref for the reveal; no second disposal surface is
    // created for the badge.
    expect(panelRaw).toContain('pendingGroupsRef')
    expect(panelRaw).toContain('ref="pendingGroupsRef"')
  })

  it('disposal zeroing re-reads the ledger instead of patching locally', () => {
    // The door area bubbles an answered event upward…
    expect(sectionRaw).toContain("emit('answered'")
    // …and the panel reacts by re-running the server reads.
    expect(panelRaw).toContain('@answered="onPendingDoorAnswered"')
    expect(panelRaw).toContain('onPendingDoorAnswered')
    // No client-side decrement/zeroing of the badge counter.
    expect(panelRaw).not.toMatch(/impactCounts\.value\[[^\]]+\]\s*=\s*0/)
    expect(panelRaw).not.toMatch(/impactCounts\.value\[[^\]]+\]\s*-\s*=/)
  })

  it('badge sentence rides both locale tracks', () => {
    const zh = lookup(zhCN, 'layout.chatDock.pinnedPanel.impactBadge')
    const en = lookup(enUS, 'layout.chatDock.pinnedPanel.impactBadge')
    expect(zh).toContain('{n}')
    expect(en).toContain('{n}')
    expect(zh).toBe('下游影响 {n} 项待处置')
    expect(en).toBe('{n} downstream impacts awaiting disposal')
    // Click affordance hint text (operation info, no internal jargon).
    expect(lookup(zhCN, 'layout.chatDock.pinnedPanel.impactBadgeHint')).toContain('处置')
    expect(lookup(enUS, 'layout.chatDock.pinnedPanel.impactBadgeHint')).toContain('dispose')
  })
})
