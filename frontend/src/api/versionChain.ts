/**
 * Shared version-chain snapshot projection (AL batch dedup: the case chain
 * and the requirement ledger chain expose the identical admin-side columns).
 * Mirrors the *_version table read model; entity id rides the extending
 * interface, never this base.
 */
export interface VersionChainFields {
  id: number
  version: number
  /** 1=edit 2=review/status change 3=delete snapshot (entity-specific 3/4 in case chain) */
  changeType: number
  operatorId?: number | null
  operatorName?: string | null
  createdAt: string
  /** changed field count vs previous version (missing baseline = 0; corrupted = null) */
  changedFieldCount?: number | null
  /** snapshot integrity flag (diff/rollback entries disabled when true) */
  corrupted: boolean
}
