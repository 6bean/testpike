import { request } from './request'




export interface ReleaseManifestItem {
  id: number
  version: string
  
  componentMatrix: string | null
  
  breakingChanges: string | null
  executorVersionMin: string | null
  executorVersionMax: string | null
  suggestedPath: string | null
  
  source: string | null
  registeredAt: string | null
}


export interface ExecutorCompatRow {
  id: number
  name: string
  version: string | null
  
  compatible: boolean | null
  reason: string | null
}


export function getLatestManifest(): Promise<ReleaseManifestItem | null> {
  return request<ReleaseManifestItem | null>({ url: '/cluster/release-manifests/latest', method: 'get' })
}


export function listReleaseManifests(): Promise<ReleaseManifestItem[]> {
  return request<ReleaseManifestItem[]>({ url: '/cluster/release-manifests', method: 'get' })
}


export function getCompatDiff(): Promise<ExecutorCompatRow[]> {
  return request<ExecutorCompatRow[]>({ url: '/cluster/release-manifests/compat-diff', method: 'get' })
}

/** Hypothetical-range impact pre-check for the candidate confirm drawer (admin
 * ReleaseManifestController /compat-diff-with mirror): min/max are forwarded
 * verbatim and every out-of-range verdict is scored core-side — the drawer only
 * counts red rows from this response, it never re-scores locally (red line 6). */
export function getCompatDiffWith(min: string | null, max: string | null): Promise<ExecutorCompatRow[]> {
  return request<ExecutorCompatRow[]>({
    url: '/cluster/release-manifests/compat-diff-with',
    method: 'get',
    params: { min: min || undefined, max: max || undefined },
  })
}


export interface PlatformVersionInfo {
  runningVersion: string | null
  buildTime: string | null
  latestVersion: string | null
  /** UP_TO_DATE / BEHIND / RUNNING_AHEAD_UNREGISTERED / NO_RELEASE / UNKNOWN_BUILD */
  verdict: string
}


export function getPlatformVersion(): Promise<PlatformVersionInfo> {
  return request<PlatformVersionInfo>({ url: '/cluster/release-manifests/platform-version', method: 'get' })
}

// ---- Release candidates (AF two-state model: detection lands in the candidate
// table, publishing stays a human-confirm act; pack-C four-endpoint mirror).
// Field-for-field mirror of admin ReleaseCandidateView (payloadJson passes through
// raw — this page parses it at the single consumption point, no bridge rework).
// Version match / blank-reason / disposed-row rejections are validated in core —
// human-worded errors propagate untouched (red line 6, no second spectrum).

/** Candidate row (admin ReleaseCandidateView mirror; status domain detected/
 * confirmed/dismissed/replaced — the list endpoint only returns detected rows). */
export interface ReleaseCandidateItem {
  id: number
  version: string
  /** released = stable channel / prerelease = pre-release channel */
  channel: string
  /** poll = scheduled probe / webhook = push / import = manual intake */
  detectedSource: string
  detectedAt: string | null
  /** Raw detection frame json (release_name/release_notes_md/published_at/assets/
   * detected_repo; missing fields stay null — never guessed, never fabricated). */
  payloadJson: string | null
  status: string
  /** Authoritative manifest row id stamped on confirmation (null until confirmed). */
  confirmedManifestId: number | null
}

/** Confirm input (same seven fields as admin ReleaseRegisterRequest; version must
 * equal the candidate version — core rejects mismatched clicks in plain words.
 * source is forced to "import" by core; the key is still sent for contract
 * completeness without inventing a second meaning for it. */
export interface ReleaseCandidateConfirmPayload {
  version: string
  componentMatrixJson: string | null
  breakingChangesJson: string | null
  executorVersionMin: string | null
  executorVersionMax: string | null
  suggestedPath: string | null
  source: string | null
}

/** Pending candidates (version-descending = card order; empty list = the page is
 * element-for-element identical to today — progressive enhancement, no new noise). */
export function listReleaseCandidates(): Promise<ReleaseCandidateItem[]> {
  return request<ReleaseCandidateItem[]>({ url: '/cluster/release-candidates', method: 'get' })
}

/** Pending count for the menu badge (0 renders as an honest zero, never null-swallowed). */
export function getPendingCandidateCount(): Promise<number> {
  return request<number>({ url: '/cluster/release-candidates/pending-count', method: 'get' })
}

/** Confirm a candidate = promote through the core register validation single point
 * (returns the authoritative manifest row; version mismatch rejected in plain words). */
export function confirmReleaseCandidate(id: number, data: ReleaseCandidateConfirmPayload): Promise<ReleaseManifestItem> {
  return request<ReleaseManifestItem>({ url: `/cluster/release-candidates/${id}/confirm`, method: 'post', data })
}

/** Dismiss a candidate (blank reason hard-rejected by core; the reason is kept in the payload trail). */
export function dismissReleaseCandidate(id: number, reason: string): Promise<ReleaseCandidateItem> {
  return request<ReleaseCandidateItem>({ url: `/cluster/release-candidates/${id}/dismiss`, method: 'post', data: { reason } })
}

/** Structured detection frame (the only prefill source for the confirm drawer;
 * missing fields = honest null defaults). */
export interface CandidatePayload {
  release_name: string | null
  release_notes_md: string | null
  published_at: string | null
  assets: string[]
  detected_repo: string | null
}

/** Single-point payloadJson parser: dirty values/missing fields fall back per key
 * instead of crashing the view (same posture as parseMatrix, silent-failure P3). */
export function parseCandidatePayload(raw: string | null): CandidatePayload {
  const empty: CandidatePayload = { release_name: null, release_notes_md: null, published_at: null, assets: [], detected_repo: null }
  if (!raw) return empty
  try {
    const v = JSON.parse(raw) as Record<string, unknown>
    if (!v || typeof v !== 'object') return empty
    return {
      release_name: typeof v.release_name === 'string' && v.release_name ? v.release_name : null,
      release_notes_md: typeof v.release_notes_md === 'string' && v.release_notes_md ? v.release_notes_md : null,
      published_at: typeof v.published_at === 'string' && v.published_at ? v.published_at : null,
      assets: Array.isArray(v.assets) ? v.assets.filter((x): x is string => typeof x === 'string') : [],
      detected_repo: typeof v.detected_repo === 'string' && v.detected_repo ? v.detected_repo : null,
    }
  } catch {
    return empty
  }
}



export interface UpgradePlanCreatePayload {
  
  scope: string
  
  engineCodes: string[]
  
  canaryNodeIds: string[]
  batchSize: number | null
  baselineRef: string | null
  manifestVersion: string | null
  /** Declared production scope (the server never guesses the environment; drives the dual-review gate) */
  productionScope?: boolean | null
  /** Declared maintenance window (human-readable range) */
  maintenanceWindow?: string | null
  operator?: string | null
}


export interface UpgradePlanRow {
  id: number
  scope: string
  targetEngineCodes: string | null
  strategy: string | null
  manifestVersion: string | null
  /** Declared production scope — the row carries the declaration; the server never guesses the environment */
  productionScope: boolean | null
  /** Declared maintenance window (human-readable range; start-time prerequisite under strict dual review) */
  maintenanceWindow: string | null
  /** Second-person reviewer (login context bridged by admin; null = not reviewed yet) */
  reviewedBy: string | null
  /** When the review was confirmed (null = not reviewed yet) */
  reviewedAt: string | null
  
  status: string
  brakeReason: string | null
  operator: string | null
  createdAt: string | null
}


export interface UpgradePlanStepRow {
  id: number | null
  planId: number | null
  nodeId: string
  kind: string
  engineCode: string | null
  phase: string
  currentVersion: string | null
  targetVersion: string | null
  status: string
  commandId: number | null
  evidence: string | null
  // Platform wave columns mirrored from admin UpgradePlanStepView (13 fields,
  // same-name straight map); null for engine/node steps.
  componentFamily: string | null
  waveSeq: number | null
}

/** Mirror of admin ArtifactPrecheckItemView — verdicts come from the core prober; the UI only renders them. */
export interface ArtifactPrecheckItem {
  componentFamily: string
  url: string
  declaredSize: number
  observedSize: number
  /** reachable / unreachable / size_mismatch / host_blocked (core enum, never rewritten client-side) */
  verdict: string
  /** plain-language sentence computed server-side (shown as-is; no client-side recomputation) */
  reason: string
}


export interface UpgradePlanImpact {
  scope: string
  engineCodes: string[]
  canaryNodeIds: string[]
  affectedNodes: number
  stepCount: number
  waves: number
  batchSize: number
  cooldownSeconds: number
  estimatedMinutes: number
  activePlans: number
  /** Artifact probe rows for the pinned manifest (empty array when nothing is pinned or declared — honest empty state) */
  precheck: ArtifactPrecheckItem[]
}


export interface ClusterComponentRow {
  id: number
  nodeId: string
  componentFamily: string
  
  currentVersion: string | null
  targetVersion: string | null
  requirement: string | null
  /** GREEN/YELLOW/RED/UNKNOWN */
  supportState: string
  stateReason: string | null
  source: string | null
  reportedAt: string | null
  /** Third-party official homepage resolved core-side (ThirdPartyHomepageRules single source); null = self-built/unknown family, honest dash */
  homepageUrl: string | null
}


export function createUpgradePlan(data: UpgradePlanCreatePayload): Promise<UpgradePlanRow> {
  return request<UpgradePlanRow>({ url: '/cluster/upgrade-plans', method: 'post', data })
}


export function previewUpgradeImpact(data: UpgradePlanCreatePayload): Promise<UpgradePlanImpact> {
  return request<UpgradePlanImpact>({ url: '/cluster/upgrade-plans/impact', method: 'post', data })
}


export function listUpgradePlans(): Promise<UpgradePlanRow[]> {
  return request<UpgradePlanRow[]>({ url: '/cluster/upgrade-plans', method: 'get' })
}


export function getUpgradePlan(id: number): Promise<UpgradePlanRow> {
  return request<UpgradePlanRow>({ url: `/cluster/upgrade-plans/${id}`, method: 'get' })
}


export function listUpgradePlanSteps(id: number): Promise<UpgradePlanStepRow[]> {
  return request<UpgradePlanStepRow[]>({ url: `/cluster/upgrade-plans/${id}/steps`, method: 'get' })
}


export function startUpgradePlan(id: number): Promise<UpgradePlanRow> {
  return request<UpgradePlanRow>({ url: `/cluster/upgrade-plans/${id}/start`, method: 'post' })
}

/** Second-person review for a production-declared PREVIEW plan. The reviewer is
 * the login-context username bridged down by admin — the body carries nothing;
 * self-review and duplicate review are refused by core in plain language. */
export function reviewUpgradePlan(id: number): Promise<UpgradePlanRow> {
  return request<UpgradePlanRow>({ url: `/cluster/upgrade-plans/${id}/review`, method: 'post' })
}


export function pauseUpgradePlan(id: number): Promise<UpgradePlanRow> {
  return request<UpgradePlanRow>({ url: `/cluster/upgrade-plans/${id}/pause`, method: 'post' })
}


export function resumeUpgradePlan(id: number): Promise<UpgradePlanRow> {
  return request<UpgradePlanRow>({ url: `/cluster/upgrade-plans/${id}/resume`, method: 'post' })
}


export function abortUpgradePlan(id: number): Promise<UpgradePlanRow> {
  return request<UpgradePlanRow>({ url: `/cluster/upgrade-plans/${id}/abort`, method: 'post' })
}


export function listClusterComponents(): Promise<ClusterComponentRow[]> {
  return request<ClusterComponentRow[]>({ url: '/cluster/components', method: 'get' })
}

// ---- Fleet elasticity (admin /api/v1/cluster/pools/scale-plan mirror, T294d) ----
// Field names are the admin record component names as they appear on the wire;
// the demand/decision pair is one autoscaler round straight from core, so the
// page never re-derives a verdict (red line 6 keeps that authority server-side).

/** Demand snapshot for one pool (admin ExecutorPoolDemandView mirror). */
export interface FleetPoolDemand {
  executorType: string
  /** Dispatches still waiting to be handed to an executor */
  pendingDispatchDepth: number
  /** Replica line the fleet runs right now */
  currentReplicas: number
  /** Executors actually carrying work in this pool */
  activeExecutors: number
  /** P95 seconds a pending dispatch has been waiting */
  p95WaitSeconds: number
  /** Epoch seconds of the last scale event; 0 = the pool never scaled */
  lastScaleEpochSeconds: number
}

/** Autoscaler answer for one pool (admin ExecutorPoolScaleDecisionView mirror). */
export interface FleetPoolDecision {
  /** SCALE_OUT / SCALE_IN / HELD / COOLDOWN_HELD / PAUSED / BUDGET_PAUSED (core enum) */
  action: string
  desiredReplicas: number
  targetReplicas: number
  /** true = capacity is released only after the instance drains its running tasks */
  drainFirst: boolean
  /** Plain-language sentence computed core-side, shown as-is */
  reason: string | null
}

/** One autoscaler round for one pool: the inputs plus the verdict taken on them. */
export interface FleetPoolScalePlan {
  executorType: string
  demand: FleetPoolDemand
  decision: FleetPoolDecision
}

/** Latest elasticity round per pool (empty array = nothing registered to scale). */
export function getFleetScalePlan(): Promise<FleetPoolScalePlan[]> {
  return request<FleetPoolScalePlan[]>({ url: '/cluster/pools/scale-plan', method: 'get' })
}

// ---- Fleet scale-event timeline (admin /api/v1/cluster/pools/scale-events mirror) ----

/** One recorded autoscale action (admin ExecutorPoolScaleEventView mirror).
 *  The reason sentence was written by the apply channel and is rendered as-is;
 *  createdEpochSeconds keeps the envelope wire-stable across the bridge. */
export interface FleetScaleEvent {
  poolKey: string
  /** SCALE_OUT / SCALE_IN — only actions actually issued reach the ledger (core enum) */
  action: string
  desiredReplicas: number
  currentReplicas: number
  /** Plain-language sentence computed when the action was issued, shown unchanged */
  reason: string | null
  /** Epoch seconds of the recorded instant */
  createdEpochSeconds: number
}

/** Newest-first window of recorded scale actions; omit poolKey for the whole
 *  fleet, limit is clamped server-side. Empty array = nothing has scaled yet. */
export function getFleetScaleEvents(poolKey?: string, limit?: number): Promise<FleetScaleEvent[]> {
  return request<FleetScaleEvent[]>({
    url: '/cluster/pools/scale-events',
    method: 'get',
    params: { poolKey: poolKey || undefined, limit: limit || undefined },
  })
}

// ---- Fleet pool-watermark curve (admin /api/v1/cluster/pools/watermarks mirror) ----

/** One persisted watermark sample (admin ExecutorPoolWatermarkView mirror,
 *  field-for-field with the core ledger point). Every number is a captured
 *  observation from the autoscaler sampler; the bridge restates nothing. */
export interface FleetPoolWatermarkPoint {
  executorType: string
  /** Claimable backlog depth at sample time */
  pendingDispatchDepth: number
  /** Replica line at sample time */
  currentReplicas: number
  /** Executors actually reporting work at sample time */
  activeExecutors: number
  /** P95 wait seconds of the claimable backlog at sample time */
  p95WaitSeconds: number
  /** Epoch seconds of the server-side sample moment */
  sampledEpochSeconds: number
}

/** Newest-first watermark series for the fleet curve band; omit executorType
 *  for the whole fleet, the default window and the clamp live in the core
 *  ledger source. Empty array = the sampler has not run yet. */
export function getFleetPoolWatermarks(executorType?: string, limit?: number): Promise<FleetPoolWatermarkPoint[]> {
  return request<FleetPoolWatermarkPoint[]>({
    url: '/cluster/pools/watermarks',
    method: 'get',
    params: { executorType: executorType || undefined, limit: limit || undefined },
  })
}

// ---- Fleet enrollment tokens (admin /api/v1/cluster/enroll-tokens bridge mirror, T294d) ----
// Field names are the admin record component names as they appear on the wire.
// The ledger view is structurally plaintext-free by design (core stores only a
// digest); the issuance answer carries the one-time plaintext and nothing else
// repeats it, so callers must keep that value in component state only.

/** One enrollment-ledger row (admin FleetEnrollTokenView mirror). */
export interface FleetEnrollToken {
  id: number
  role: string
  projectScope: string | null
  maxUses: number
  usedCount: number
  /** 0 pending / 1 redeemed / 2 expired / 3 revoked (core value domain) */
  status: number
  boundFingerprint: string | null
  expiresAt: string | null
  remark: string | null
  createdAt: string | null
  updatedAt: string | null
}

/** Issuance answer (admin FleetEnrollTokenIssuedView mirror; token is the
 *  one-time plaintext shown exactly once). */
export interface FleetEnrollTokenIssued {
  id: number
  token: string
  role: string
  projectScope: string | null
  maxUses: number
  expiresAt: string | null
}

/** Issuance input (admin FleetEnrollIssueBody mirror; maxUses null defaults to 1). */
export interface FleetEnrollIssuePayload {
  role: string
  projectScope?: string | null
  maxUses?: number | null
  remark?: string | null
}

/** Current enrollment ledger (GET /cluster/enroll-tokens). */
export function listFleetEnrollTokens(): Promise<FleetEnrollToken[]> {
  return request<FleetEnrollToken[]>({ url: '/cluster/enroll-tokens', method: 'get' })
}

/** Mint a one-time enrollment token (POST /cluster/enroll-tokens). */
export function issueFleetEnrollToken(payload: FleetEnrollIssuePayload): Promise<FleetEnrollTokenIssued> {
  return request<FleetEnrollTokenIssued>({ url: '/cluster/enroll-tokens', method: 'post', data: payload })
}

/** Revoke an unconsumed token (POST /cluster/enroll-tokens/{id}/revoke). */
export function revokeFleetEnrollToken(id: number): Promise<void> {
  return request<void>({ url: `/cluster/enroll-tokens/${id}/revoke`, method: 'post' })
}

/**
 * Fleet command ledger row (admin ClusterCommandView mirror, protocol 03 read
 * side). Mirrored field-by-field; null progress/receipt/claimant is the
 * server's honest absence, never a defaulted value (red line 6). Status keeps
 * the core numeric value domain: 0 pending dispatch, 1 claimed, 2 executing,
 * 3 succeeded, 4 failed, 5 revoked.
 */
export interface FleetCommand {
  id: number
  commandType: string
  targetType: string
  targetId: number
  payload: Record<string, unknown> | null
  status: number
  claimedBy: number | null
  lastProgress: { step?: string; message?: string; at?: string } | null
  resultDetail: string | null
  expiresAt: string | null
  createdAt: string
}

/** Fleet command ledger (GET /cluster/commands); absent executor id spans the whole fleet. */
export function listFleetCommands(targetExecutorId?: number): Promise<FleetCommand[]> {
  return request<FleetCommand[]>({
    url: '/cluster/commands',
    method: 'get',
    params: targetExecutorId === undefined ? {} : { targetExecutorId },
  })
}
