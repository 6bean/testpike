import { request, type PageResult } from './request'

/**
 * shared-asset pool contract mirror (admin /api/v1/shared-assets bridge).
 * Interface fields mirror the admin record DTOs 1:1 (SharedAssetView 11 /
 * AssetReferenceView 15). Head/stale math, the version-chain resolution and
 * the owner fence are core single-source — the pool page renders these rows
 * verbatim and never recomputes them here (R40).
 */

/** One asset version row; reference pins point at this row id, not the chain. */
export interface SharedAssetView {
  id: number
  projectId: number
  ownerProjectName: string
  kind: string
  name: string
  assetVersion: number
  prevAssetId: number | null
  /** 1 = published, 2 = withdrawn (chain level) */
  status: number
  /** content snapshot of the publishing project's entity */
  payloadJson: string
  /** true when this row is the current chain head */
  head: boolean
  /** chain-level pin counter from core's single list SELECT (pins across every
   *  version row; 0 for unpinned chains — never null, never a per-row probe) */
  refCount: number
}

/** One pin row of the bidirectional reference ledger. */
export interface AssetReferenceView {
  id: number
  assetRowId: number
  assetProjectId: number
  ownerProjectName: string
  kind: string
  name: string
  /** chain version captured at pin time */
  pinnedVersion: number
  /** pinned row status (1 published / 2 withdrawn) */
  assetStatus: number
  /** current head version of the chain */
  headVersion: number
  /** pinnedVersion behind headVersion (upstream republished) */
  stale: boolean
  referrerProjectId: number
  referrerProjectName: string
  /** host entity family, e.g. case_step */
  referrerKind: string
  /** host entity id inside the consuming project */
  referrerId: number
  createdAt: string
}

/**
 * One row of a chain's version timeline: the pin count attached to that
 * exact version row. The grouped-SELECT pin math and the head marker are
 * core single-source; the drawer renders these rows verbatim (R40).
 */
export interface AssetVersionTimelineView {
  /** tp_asset version row id the counted pins point at */
  assetRowId: number
  /** chain-local version number of this row */
  assetVersion: number
  /** chain status of the row: 1 published, 2 withdrawn */
  status: number
  /** true for the newest version row of the chain */
  head: boolean
  /** number of reference pins attached to this version row */
  pinnedCount: number
  /** when this version row was published */
  updatedAt: string
}

/**
 * Globally discoverable published heads, optional kind filter, paged.
 * sortBy/sortDir are the core-whitelisted server-side sort tokens (blank =
 * historical newest-chain order); the page never sorts client-side only (R21).
 */
export function listSharedAssets(params: {
  kind?: string
  page?: number
  size?: number
  sortBy?: string
  sortDir?: string
}): Promise<PageResult<SharedAssetView>> {
  return request({ url: '/shared-assets', method: 'get', params })
}

/** Read one version row; superseded rows stay resolvable for pinned views. */
export function getSharedAsset(id: number): Promise<SharedAssetView> {
  return request({ url: `/shared-assets/${id}`, method: 'get' })
}

/** Owner direction "who uses me": every pin across all versions of one chain. */
export function referencesToChain(params: {
  chainProjectId: number
  kind: string
  name: string
  page?: number
  size?: number
}): Promise<PageResult<AssetReferenceView>> {
  return request({ url: '/shared-assets/references/to-chain', method: 'get', params })
}

/**
 * Per-version pin counts for one whole chain, newest version first.
 * Chain anchor (project + kind + name) is forwarded verbatim to the
 * admin bridge; nothing is resolved client-side.
 */
export function chainVersions(params: {
  chainProjectId: number
  kind: string
  name: string
}): Promise<AssetVersionTimelineView[]> {
  return request({ url: '/shared-assets/references/chain-versions', method: 'get', params })
}

/** Consumer direction "what I use": every pin held by one project. */
export function referencesByConsumer(params: {
  consumerProjectId: number
  page?: number
  size?: number
}): Promise<PageResult<AssetReferenceView>> {
  return request({ url: '/shared-assets/references/by-consumer', method: 'get', params })
}

/**
 * Pin a catalog version row to a host entity inside the calling project.
 * The step-editor overlay registers case anchors (referrerKind='case',
 * referrerId=case id); core validates the pin shape and the write fence,
 * and superseded rows stay pinnable on purpose (pins point at version rows).
 */
export interface AssetPinForm {
  projectId: number
  assetRowId: number
  referrerKind: string
  referrerId: number
}

export function pinSharedAsset(form: AssetPinForm): Promise<void> {
  return request({ url: '/shared-assets/references', method: 'post', data: form })
}

/**
 * Withdraw the whole chain from the pool (owner project's lead only, fenced
 * server-side). Fail-closed while active pins exist unless force is set; the
 * force branch notifies every consumer and cascades the invalidation marker.
 */
export function withdrawSharedAsset(
  projectId: number,
  id: number,
  force: boolean,
): Promise<void> {
  return request({ url: `/shared-assets/${id}/withdraw`, method: 'post', params: { projectId, force } })
}
