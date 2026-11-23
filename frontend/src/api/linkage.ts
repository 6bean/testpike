/**
 * Downstream-linkage ledger reads .
 *
 * The linkage bus records one `tp_linkage_impact` row per downstream object
 * an upstream save invalidated; these doors expose that ledger for the UI.
 * Field names below mirror the shared backend contract in
 * `com.sixbean.testpike.common.linkage` one-for-one (single source: the
 * server record is authoritative, this file never invents a second shape).
 *
 * Read-only by design: accepting an impact goes through the draft-card
 * ticket door (see api/aiChat group endpoints), never through this module —
 * a disclosure channel must not smuggle an action path (red line 10c).
 */
import { request } from './request'

/** One ledger row behind a card (mirrors LinkageImpactInfo). */
export interface LinkageImpactInfo {
  id: number
  edgeKey: string
  upstreamObjectType: string
  upstreamObjectId: number
  upstreamVersion: number | null
  downstreamObjectType: string
  downstreamId: number
  /** mark_only | grouped_draft | disclose_only | notify */
  dispositionStrategy: string
  /** 1=stale mark, 2=draft pending, 3=disclosed, 4=consumed */
  status: number
  annotatedAt: string | null
  consumedAt: string | null
}

/**
 * Pending-impact ledger answer with its ownership anchor (mirrors the
 * shared record LinkagePendingImpacts — the server owns the shape).
 */
export interface LinkagePendingImpactsShape {
  /** Owning project of the upstream object; null only for an empty ledger. */
  projectId: number | null
  /** Pending rows (statuses 1/2/3), oldest first. */
  impacts: LinkageImpactInfo[]
}

/** A grouped-card row paired with its freshly read anchor (mirrors LinkageDraftImpact). */
export interface LinkageDraftImpact {
  draftId: number
  projectId: number | null
  impact: LinkageImpactInfo
}

/**
 * Live pending ledger of one saved upstream object the
 * pinned-panel badge reads this door; the core query owns which statuses
 * still await disposal). `upstreamType` rides the ledger's own object-kind
 * token (lowercase, single-sourced by the bus registry); `projectId` is the
 * viewer's segment and can never widen the answer.
 */
export async function getLinkagePendingImpacts(
  upstreamType: string,
  upstreamId: number,
  projectId: number | null,
): Promise<LinkagePendingImpactsShape> {
  return request<LinkagePendingImpactsShape>({
    url: '/linkage-impacts/pending',
    method: 'get',
    params: { upstreamType, upstreamId, ...(projectId != null ? { projectId } : {}) },
  })
}

/**
 * Live anchors for the draft cards of one grouped card, batch read per open.
 * `projectId` is the viewer's segment from the header context (R30 single
 * switch) — the server resolves the real owner from the draft rows and
 * rejects the whole batch on any foreign draft, so a partial list can never
 * leak another project.
 */
export async function getLinkageImpactsByDrafts(
  draftIds: number[],
  projectId: number | null,
): Promise<LinkageDraftImpact[]> {
  return request<LinkageDraftImpact[]>({
    url: '/linkage-impacts/by-drafts',
    method: 'get',
    params: { draftIds, ...(projectId != null ? { projectId } : {}) },
    paramsSerializer: { indexes: null },
  })
}
