import { request, type PageResult } from './request'

/**
 * MCP knowledge sync subscription API client.
 *
 * Mirrors the admin bridge face `/api/v1/mcp-servers/{serverId}/knowledge-syncs`
 * (eight legs). The bridge forwards to the core service where every guard is
 * single-sourced (read-only tool whitelist, name uniqueness inside a project,
 * interval range, target project existence); this client mirrors the wire
 * shape only and recomputes nothing.
 *
 * `runNow` follows the core round contract: an expected failure (for example
 * a DOWN probe idle) answers HTTP 200 carrying a typed round outcome, so the
 * drawer branches on `status` instead of catching an exception.
 */

/** Ledger row (mirror of the admin KnowledgeSyncView record). */
export interface KnowledgeSyncItem {
  id: number
  projectId: number
  serverId: number
  serverName: string
  name: string
  toolName: string
  paramMapping: Record<string, unknown> | null
  syncIntervalMinutes: number | null
  /** Knowledge landing bucket: 0 = global ledger, >0 = owning project. */
  targetProjectId: number
  /** Optional content pack, 0 = not pack-owned. */
  targetPackId: number
  autoActivate: boolean
  enabled: boolean
  /** updated | unchanged | failed; null before the first round. */
  lastStatus: string | null
  lastError: string | null
  lastSyncAt: string | null
  lastNewCount: number | null
  lastSkippedCount: number | null
  consecutiveFailures: number | null
}

/** Register/update payload (mirror of the admin KnowledgeSyncUpsertRequest).
 *  The nested path binds the server scope on the server side; the payload
 *  keeps the field so the DTO shape matches the bridge one-to-one. */
export interface KnowledgeSyncUpsertPayload {
  projectId: number
  serverId?: number | null
  name: string
  toolName: string
  paramMapping?: Record<string, unknown> | null
  /** null = use the hot-updated default from ai.mcp.sync.interval-minutes. */
  syncIntervalMinutes?: number | null
  targetProjectId: number
  targetPackId: number
  /** null/omitted = factory default (off); on lands entries activated straight away. */
  autoActivate?: boolean | null
}

/** One sync round outcome (mirror of the admin KnowledgeSyncRoundView). */
export interface KnowledgeSyncRound {
  status: string
  error: string | null
  newCount: number
  skippedCount: number
}

export interface KnowledgeSyncQuery {
  projectId?: number
  page?: number
  size?: number
}

/** Ledger list for one server (optional project filter, paged). */
export function listKnowledgeSyncs(
  serverId: number,
  q: KnowledgeSyncQuery,
): Promise<PageResult<KnowledgeSyncItem>> {
  return request<PageResult<KnowledgeSyncItem>>({
    url: `/mcp-servers/${serverId}/knowledge-syncs`,
    method: 'get',
    params: q,
  })
}

/** One subscription with its bookkeeping columns. */
export function getKnowledgeSync(serverId: number, id: number): Promise<KnowledgeSyncItem> {
  return request<KnowledgeSyncItem>({ url: `/mcp-servers/${serverId}/knowledge-syncs/${id}`, method: 'get' })
}

/** Register a subscription under the server (auto_activate defaults off). */
export function createKnowledgeSync(
  serverId: number,
  input: KnowledgeSyncUpsertPayload,
): Promise<KnowledgeSyncItem> {
  return request<KnowledgeSyncItem>({ url: `/mcp-servers/${serverId}/knowledge-syncs`, method: 'post', data: input })
}

/** Replace the editable columns under the same core guards. */
export function updateKnowledgeSync(
  serverId: number,
  id: number,
  input: KnowledgeSyncUpsertPayload,
): Promise<KnowledgeSyncItem> {
  return request<KnowledgeSyncItem>({ url: `/mcp-servers/${serverId}/knowledge-syncs/${id}`, method: 'put', data: input })
}

/** Remove the subscription (round history stays in the entry ledger). */
export function deleteKnowledgeSync(serverId: number, id: number): Promise<void> {
  return request<void>({ url: `/mcp-servers/${serverId}/knowledge-syncs/${id}`, method: 'delete' })
}

/** Pause: excluded from the due scan until resumed. */
export function pauseKnowledgeSync(serverId: number, id: number): Promise<void> {
  return request<void>({ url: `/mcp-servers/${serverId}/knowledge-syncs/${id}/pause`, method: 'post' })
}

/** Resume: restarts the consecutive-failure path from zero. */
export function resumeKnowledgeSync(serverId: number, id: number): Promise<void> {
  return request<void>({ url: `/mcp-servers/${serverId}/knowledge-syncs/${id}/resume`, method: 'post' })
}

/** Manual trigger: one immediate round through the DOWN gate (200 + typed outcome). */
export function runKnowledgeSyncNow(serverId: number, id: number): Promise<KnowledgeSyncRound> {
  return request<KnowledgeSyncRound>({ url: `/mcp-servers/${serverId}/knowledge-syncs/${id}/run-now`, method: 'post' })
}
