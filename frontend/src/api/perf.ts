import { postMultipart, request } from './request'
import { fetchAndDownloadBlob } from '@/utils/exportDownload'





export interface PerfScenarioItem {
  id: number
  project_id: number
  env_id: number | null
  name: string
  /** CONSTANT_RPS / RAMP / SPIKE */
  pressure_model: string
  pressure_config: string | null
  /** PER_VUSER / PER_SESSION_N */
  login_strategy: string | null
  driver_limit: number | null
  /** Persisted engine binding (W-d; null = default routing resolves it at dispatch) */
  engine?: string | null
  steps_json: string | null
  sla_json: string | null
  /** Bound CSV dataset id */
  dataset_id: number | null
  /** Persisted row-allocation strategy (null = resolve the K8 default at dispatch) */
  row_alloc_mode: string | null
  /** Scheduled-firing declaration */
  schedule_cron?: string | null
  schedule_enabled?: number | null
  schedule_env_id?: number | null
  created_at: string
  updated_at: string
}


export interface PerfScenarioSavePayload {
  projectId: number
  name: string
  envId: number | null
  
  pressureModel: string
  pressureConfig: Record<string, number>
  loginStrategy?: string | null
  driverLimit?: number | null
  
  steps: Record<string, unknown>[]
  
  slaJson?: Record<string, number> | null
  /** Declared isolation mode (SHADOW_TABLE/NS_ACCOUNT/HEADER_MARK; null = not declared — legal only for non-production scenarios, the launch safety gate rejects a production launch without it) */
  isolationMode?: string | null
  /** Bound CSV dataset */
  datasetId?: number | null
  /** Row allocation strategy (SEQUENTIAL_EXCLUSIVE/VUSER_EXCLUSIVE/CYCLE; legal only with datasetId; null = K8 default at dispatch) */
  rowAllocMode?: string | null
  /** Scheduled-firing cron (Spring 6-segment; core validates fail-closed on save; null/blank = not scheduled) */
  scheduleCron?: string | null
  /** Scheduled-firing switch (refused by core without a cron — an enabled control that can never act) */
  scheduleEnabled?: boolean | null
  /** Pinned launch-time environment for scheduled firing (null = the scenario's own env at fire time) */
  scheduleEnvId?: number | null
  /** Declared engine pin (W-d third engine; null = default routing at dispatch) */
  engine?: string | null
}



export interface PerfRunItem {
  id: number
  scenario_id: number
  project_id: number
  status: number
  pressure_snapshot: string | null
  stop_reason: string | null
  /** Queue transparency wording : non-null only while the run is in flight
 * and its dispatch task is still pending — names ahead-task count or the
 * machine-availability situation (backend wording, rendered as-is). */
  queue_note?: string | null
  /** B-7 marking (GOLDEN / CANDIDATE / VOID / null = unmarked): the backend
   *  read face already carries it; the report consumes it for the golden lane. */
  mark?: string | null
  operator_id: number | null
  started_at: string | null
  finished_at: string | null
  created_at: string
  /** Ledger read-model annotation : live scenario name, falling back
 * to the name frozen in the run's launch snapshot when the scenario was
 * deleted (A-13 decoupling). Only the project-wide window fills it — the
 * scenario-keyed drawer rows keep it null (the name is already implicit). */
  scenario_name?: string | null
}


export interface PerfFrameItem {
  second_offset: number
  rps: number | null
  p95_ms: number | null
  error_rate: number | null
  ttft_ms?: number | null
  itl_ms?: number | null
  tokens_per_sec?: number | null
  auth_ms?: number | null
  auth_count?: number | null
}


export interface PerfEventItem {
  second_offset: number | null
  event_kind: string
  detail: string | null
}

/** Error-class TopN row (B-6): {errorClass, count} from the run's aggregate frames,
 *  descending by count; classes never collected are absent (03 §2.4 five-key vocab). */
export interface PerfErrorClassItem {
  errorClass: string
  count: number
}



export interface PerfReportCard {
  sampledSeconds: number
  avgRps: number
  maxRps: number
  peakSecondP95Ms: number
  avgErrorRate: number
  authSeconds: number
  authRequestCount: number
  authAvgMs: number | null
}


export interface PerfVerdict {
  passed: boolean
  conclusion: string
  breachedGates: string[]
}


export interface PerfReportView {
  run: PerfRunItem
  frames: PerfFrameItem[]
  card: PerfReportCard
  verdict: PerfVerdict
  caliberFootnote: string
  driverLimit: number | null
  boundaryNote: string | null
  /** CF-1 ceiling-search row : present only for LADDER runs;
 * null = another pressure model (honest absence, never a fabricated tiers table). */
  ceiling: PerfCeilingView | null
  /** GB-4 residual-cleanup guide : per-mode
 * cleanup instruction sheet produced by core from the launch-pinned isolation
 * declaration; null = mode never declared (honest absence, guide never invented).
 * All prose is backend-owned — the page only renders this structure. */
  residualGuide: PerfResidualGuideView | null
}

/** One cleanup-instruction section (title + action items, core single source). */
export interface PerfResidualGuideSection {
  title: string
  items: string[]
}

/** Guide payload mirrored from PerfResidualGuide.build (mode/sections/boundaryNote). */
export interface PerfResidualGuideView {
  mode: string
  sections: PerfResidualGuideSection[]
  boundaryNote: string
}

/** One staircase step of the ceiling walk (nominal rate caliber, core single source). */
export interface PerfCeilingTier {
  step: number
  nominalRps: number
  passed: boolean
  breachedGates: string[]
  requests: number
}

/** CF-1 ceiling finding rendered by the report page — analysis lives in core
 *  (PerfLadderCeilingAnalyzer); this page only draws, never recomputes. */
export interface PerfCeilingView {
  applicable: boolean
  tiers: PerfCeilingTier[]
  breachStep: number | null
  lastPassStep: number | null
  safeWatermarkRps: number | null
  kneeStep: number | null
  kneeDescription: string | null
  conclusion: string
  caliberNote: string
}


export interface PerfCompareView {
  scenarioId: number
  runA: PerfRunItem
  runB: PerfRunItem
  series: { secondOffset: number; a: number | null; b: number | null }[]
  diffs: { secondOffset: number; rpsDelta: number; p95DeltaMs: number; errorRateDelta: number }[]
}



export interface PerfWhitelistItem {
  id: number
  project_id: number
  env_id: number
  max_concurrency: number
  allowed_modes: string | null
  created_at: string
  updated_at: string
}


export interface PerfWhitelistSavePayload {
  projectId: number
  envId: number
  maxConcurrency: number
  allowedModes: string[]
}


export interface PerfApprovalItem {
  id: number
  env_id: number
  requested_concurrency: number
  isolation_mode: string
  reason: string | null
  reject_reason: string | null
  status: number
  handled_at: string | null
  created_at: string
  /** D8 linked scenario (null = plain approval, approving never auto-launches). */
  scenario_id?: number | null
  /** Auto-launch outcome line: run id on success, plain gate reason on refusal,
   *  null when the switch was off or nothing fired. */
  auto_launch_note?: string | null
}

/**
 * scenarioId = D8 approval-linked scenario: with the auto-launch switch on, approving
 * the ticket fires this scenario through the single launch channel; omit it when the
 * scenario is not saved yet (the wizard links only persisted scenarios). */
export interface PerfApprovalApplyPayload {
  projectId: number
  envId: number
  requestedConcurrency: number
  isolationMode: string
  reason: string
  scenarioId?: number | null
}

/** Precheck result (gate = machine key, reason = human sentence shown in full; steps = CF-8 ladder rows). */
export interface PerfGateStep {
  code: string
  label: string
  /** PASS / FAIL / SKIP (SKIP = the door does not apply to this launch, e.g. production-only doors on non-production) */
  state: string
  /** observed fact; for FAIL = what is missing */
  detail: string
  /** who has the authority to fix a FAIL (empty for PASS/SKIP) */
  fixBy: string
  /** in-app entry hint for the fix (empty for PASS/SKIP) */
  entryPath: string
}

export interface PerfGateDecision {
  passed: boolean
  gate: string
  reason: string
  steps: PerfGateStep[]
  /** U-5 declared run horizon (seconds; 0 = model not declared). Optional = tolerate legacy responses. */
  runDurationSeconds?: number
  /** U-5 hot-updatable margin actually applied by the server (minutes). */
  budgetMarginMinutes?: number
  /** U-5 dispatch budget ms, same PerfBudgetPolicy single source the dispatcher writes. */
  budgetMs?: number
}


export interface PerfGateCheckPayload {
  projectId: number
  envId: number
  concurrency: number
  isolationMode: string
  targetBaseUrl?: string | null
  /** U-5 budget preview input: declared pressure (same keys the save payload sends). */
  pressureModel?: string | null
  pressureConfig?: Record<string, number> | null
}

// ---------- Dataset ledger ----------

/** CSV dataset ledger row (core tp_perf_dataset SELECT columns verbatim; columns_json = JSON-array text of the validated header names, which double as the {{VAR}} domain). */
export interface PerfDatasetItem {
  id: number
  project_id: number
  name: string
  file_name: string
  /** JSON-array text of the validated CSV header names (page parses with a tolerant fallback) */
  columns_json: string
  row_count: number
  size_bytes: number
  created_at: string
  updated_at: string
}

/** Dataset detail view = ledger row + preview_rows (first N rows from object storage, N = hot key perf.dataset.preview-rows). */
export interface PerfDatasetView extends PerfDatasetItem {
  preview_rows: string[][]
}

/** Paged dataset window (core PerfDatasetPageView mirror; the wizard picker never pulls an unbounded list — display-scale iron law). */
export interface PerfDatasetPage {
  list: PerfDatasetItem[]
  total: number
  page: number
  pageSize: number
}

/** Engine × row-allocation matrix from the single policy door (core PerfDatasetAllocPolicy; the wizard's honest-disable UI and the adapter dispatch guard read the same source). */
export interface PerfAllocPolicy {
  /** resolved dispatch engine (GATLING / K6) */
  engine: string
  /** K8 default strategy actually resolved at dispatch */
  defaultMode: string
  /** strategies the engine natively honours */
  supportedModes: string[]
  /** full value domain (PerfRowAllocModes) */
  allModes: string[]
}

/** Upload one CSV into the project ledger (multipart file+projectId+name; row/size/column validation is core-side single source, plain-words refusal passes through). */
export function uploadPerfDataset(projectId: number, name: string, file: File): Promise<number> {
  return postMultipart<number>('/perf/datasets', file, { projectId: String(projectId), name })
}

/** One paged window of the project's dataset ledger. */
export function pagePerfDatasets(projectId: number, page = 1, pageSize = 20): Promise<PerfDatasetPage> {
  return request<PerfDatasetPage>({ url: '/perf/datasets/page', method: 'get', params: { projectId, page, pageSize } })
}

/** Dataset detail + first-N preview rows (object storage streamed by core). */
export function findPerfDataset(projectId: number, id: number): Promise<PerfDatasetView> {
  return request<PerfDatasetView>({ url: `/perf/datasets/${id}`, method: 'get', params: { projectId } })
}

/** Engine × strategy matrix for the wizard's honest-disable UI (global semantics — no project segment on this door). */
export function allocPerfDatasetPolicy(): Promise<PerfAllocPolicy> {
  return request<PerfAllocPolicy>({ url: '/perf/datasets/alloc-policy', method: 'get' })
}

/**
 * Launch-time resource pre-flight readout (CF-5 + CF-7, admin
 * PerfGateController mirror). Read-only advance notice in front of the launch
 * button: one capacity sentence (fleet/capability/idle/queue ahead) plus a
 * dataset health line (demand rows vs pool for exclusive allocation). Nothing
 * here blocks — rejection stays owned by the launch transaction gates. Null
 * wording fields = honest "no claim" shapes (unbound pool, malformed config).
 */
export interface PerfPreflightView {
  /** engine the dispatcher would pick (K8 default) */
  engine: string | null
  /** capacity outlook sentence, or null when no honest claim exists */
  capacityNote: string | null
  /** data-pool health sentence, or null when no pool is bound */
  datasetNote: string | null
  /** required concurrency tier the launch gate would derive (null = undecidable) */
  requiredConcurrency: number | null
}

/** Pre-flight readout for one scenario's next launch (projectId+scenarioId). */
export function getResourcePreflight(projectId: number, scenarioId: number): Promise<PerfPreflightView> {
  return request<PerfPreflightView>({ url: '/perf/resource-preflight', method: 'get', params: { projectId, scenarioId } })
}

/** Delete a dataset (ledger row + stored object; scenarios still bound to it are refused server-side). */
export function deletePerfDataset(projectId: number, id: number): Promise<void> {
  return request<void>({ url: `/perf/datasets/${id}`, method: 'delete', params: { projectId } })
}


/**
 * Shipped scenario template (admin PerfTemplateDto / core PerfTemplateInfo
 * mirror, CF-4). `scenario` is shaped exactly like the scenario save input
 * (pressureModel/pressureConfig/steps/slaJson) — rows are pre-judged against
 * the same single-source domains the save gate uses in core, so the wizard
 * pre-fill can never produce a scenario the user then cannot save.
 */
export interface PerfTemplateItem {
  /** Stable key below the perf.template. config prefix — the picker identity. */
  templateKey: string
  /** Human-readable playbook name served by the catalogue (never copied here). */
  label: string
  scenario: {
    pressureModel: string
    pressureConfig: Record<string, number>
    steps: Record<string, unknown>[]
    slaJson?: Record<string, number> | null
  }
}

/**
 * CF-4 shipped scenario-template catalogue. Project-independent read:
 * templates carry {{ENV:}} placeholders and example paths only, so no
 * projectId param exists on the contract (admin core bridge is verbatim).
 */
export function listPerfTemplates(): Promise<PerfTemplateItem[]> {
  return request<PerfTemplateItem[]>({ url: '/perf/templates', method: 'get' })
}

/**
 * Engine catalogue row (W-d third engine; admin GET /perf/engines bridges
 * core verbatim). `caveat` is the server's rate-caliber disclosure wording —
 * rendered as-is, never paraphrased or stored into a second copy.
 */
export interface PerfEngineOption {
  engine: string
  caveat: string | null
}

/** Declared-engine option supply for the wizard (read-only, project-independent). */
export function listPerfEngines(): Promise<PerfEngineOption[]> {
  return request<PerfEngineOption[]>({ url: '/perf/engines', method: 'get' })
}

/**
 * AI scenario draft reply (B-11 ; admin/core mirror). `steps` are
 * already trimmed to exactly what the scenario save gate accepts — the single
 * judging point lives in core, the frontend consumes and never re-validates.
 * `droppedReasons` names every dropped step in plain words and MUST be shown.
 */
export interface PerfScenarioDraftView {
  steps: Record<string, unknown>[]
  droppedCount: number
  droppedReasons: string[]
}

/**
 * Draft a step table from one sentence of load-test intent (POST
 * /perf/scenarios/draft). Nothing is persisted — the reply prefills the
 * wizard and saving stays behind the human-confirmed create/update doors.
 * When the AI switch is off the server refuses with a management-gate reason.
 */
export function draftPerfScenario(projectId: number, description: string): Promise<PerfScenarioDraftView> {
  // One click = one real model hop; the read budget rides above the backend
  // legs (core→ai 90s, admin→core 100s) so an inner honest refusal still
  // arrives untranslated instead of a browser-side timeout.
  return request<PerfScenarioDraftView>({ url: '/perf/scenarios/draft', method: 'post', data: { projectId, description }, timeout: 120000 })
}


export function listPerfScenarios(projectId: number): Promise<PerfScenarioItem[]> {
  return request<PerfScenarioItem[]>({ url: '/perf/scenarios', method: 'get', params: { projectId } })
}

/** Paged scenario window (core PerfScenarioPageView mirror, . */
export interface PerfScenarioPage {
  list: PerfScenarioItem[]
  total: number
  page: number
  pageSize: number
}

/** Server-side sort pair forwarded into the window request. */
export interface PerfSortOptions {
  sortBy?: string
  sortDir?: string
}

/** One paged window of the visible segment (display-scale iron law: the
 * workbench ledger consumes this door; clamp/whitelist order/total truth are
 * core semantics — the page never re-sorts or re-paginates). programId =
 * header program lens : server-side conjunctive with projectId
 * through the core three-branch ProgramLensScope; absent = exact pre-feature
 * request. */
export function pagePerfScenarios(
  projectId: number,
  page: number,
  pageSize: number,
  sort?: PerfSortOptions,
  programId?: number,
): Promise<PerfScenarioPage> {
  return request<PerfScenarioPage>({
    url: '/perf/scenarios/page',
    method: 'get',
    params: { projectId, page, pageSize, sortBy: sort?.sortBy, sortDir: sort?.sortDir, programId },
  })
}


export function findPerfScenario(projectId: number, id: number): Promise<PerfScenarioItem> {
  return request<PerfScenarioItem>({ url: `/perf/scenarios/${id}`, method: 'get', params: { projectId } })
}

/**
 * Scenario trend row : one terminal run with whole-run
 * statistics recomputed from merged histogram buckets. Rows ascend by run id,
 * bounded server-side by the hot-updatable window key. Legacy runs without
 * buckets honestly fall back to the worst-second P95 (p50/p99 stay null, never
 * fabricated); calibers ride as enums the page translates into display wording.
 */
export interface PerfTrendRow {
  runId: number
  status: number
  mark: string | null
  startedAt: string | null
  finishedAt: string | null
  dslHash: string | null
  totalReq: number
  sampleSeconds: number
  p50Ms: number | null
  p95Ms: number | null
  p99Ms: number | null
  quantileCaliber: 'BUCKET' | 'FALLBACK'
  errorRate: number
  errorCaliber: 'WEIGHTED' | 'MEAN'
}

/** Ascending terminal-run trend rows for one scenario (guard = project view, no new permission key). */
export function getPerfScenarioTrend(projectId: number, scenarioId: number): Promise<PerfTrendRow[]> {
  return request<PerfTrendRow[]>({ url: `/perf/scenarios/${scenarioId}/trend`, method: 'get', params: { projectId } })
}


export function createPerfScenario(data: PerfScenarioSavePayload): Promise<number> {
  return request<number>({ url: '/perf/scenarios', method: 'post', data })
}


export function updatePerfScenario(id: number, data: PerfScenarioSavePayload): Promise<void> {
  return request<void>({ url: `/perf/scenarios/${id}`, method: 'put', params: { projectId: data.projectId }, data })
}

/**
 * Delete-impact ledger (A-13; mirror of the core impact read face): real
 * counts for the confirmation card — runs still in flight, finished runs the
 * deletion keeps, approval tickets losing their scenario link, and the
 * scenario source so a mounted CUSTOM script can be named.
 */
export interface PerfScenarioDeleteImpact {
  runningRuns: number
  historicalRuns: number
  linkedApprovals: number
  pendingApprovals: number
  source: string
}

/** Impact ledger for one scenario's delete card (management gate mirrors core). */
export function getScenarioDeleteImpact(projectId: number, id: number): Promise<PerfScenarioDeleteImpact> {
  return request<PerfScenarioDeleteImpact>({ url: `/perf/scenarios/${id}/delete-impact`, method: 'get', params: { projectId } })
}

/**
 * Delete a scenario (A-13, D2 ruling): finished runs survive decoupled with a
 * NULL link; a scenario with a run in flight is refused server-side with a
 * human reason naming the count (passed through untranslated).
 */
export function deletePerfScenario(projectId: number, id: number): Promise<void> {
  return request<void>({ url: `/perf/scenarios/${id}`, method: 'delete', params: { projectId } })
}


export function launchPerfRun(projectId: number, scenarioId: number, envId?: number | null): Promise<number> {
  // GB-1: optional envId overrides the scenario-bound environment for this run
  // (absent = scenario default; core refuses unknown/cross-project ids).
  return request<number>({ url: `/perf/scenarios/${scenarioId}/runs`, method: 'post', params: { projectId, envId } })
}


export function listPerfRuns(projectId: number, scenarioId: number): Promise<PerfRunItem[]> {
  return request<PerfRunItem[]>({ url: '/perf/runs', method: 'get', params: { projectId, scenarioId } })
}

/** Paged run window (core PerfRunPageView mirror, . */
export interface PerfRunPage {
  list: PerfRunItem[]
  total: number
  page: number
  pageSize: number
}

/** One paged window of a scenario's runs (display-scale iron law: the run
 * drawer consumes this door; clamp/total truth are core semantics — the page
 * never re-paginates. Sort stays exempted: the drawer is a headerless div
 * list, no column headers to click). */
export function pagePerfRuns(
  projectId: number,
  scenarioId: number,
  page: number,
  pageSize: number,
): Promise<PerfRunPage> {
  return request<PerfRunPage>({
    url: '/perf/runs/page',
    method: 'get',
    params: { projectId, scenarioId, page, pageSize },
  })
}

/**
 * Project-wide run ledger window .
 * Lists every run of the project segment — finished history whose scenario was
 * deleted included (the drawer above is scenario-keyed and hides those rows).
 * sortBy/sortDir pass through to the core whitelist (id-less physical columns
 * status/mark/started_at/finished_at/created_at; an off-list key is refused
 * fail-closed server-side, scenario_name is never sortable — annotation field).
 * status/mark are optional narrowing filters (blank = unfiltered; core counts
 * the narrowed total so the pager stays truthful).
 * Clamp/total truth stay core semantics.
 */
export function pageProjectPerfRuns(
  projectId: number,
  page: number,
  pageSize: number,
  sortBy?: string,
  sortDir?: string,
  status?: number,
  mark?: string,
): Promise<PerfRunPage> {
  return request<PerfRunPage>({
    url: '/perf/runs/project-page',
    method: 'get',
    params: {
      projectId, page, pageSize,
      sortBy: sortBy || undefined, sortDir: sortDir || undefined,
      status: status ?? undefined, mark: mark || undefined,
    },
  })
}


export function findPerfRun(projectId: number, runId: number): Promise<PerfRunItem> {
  return request<PerfRunItem>({ url: `/perf/runs/${runId}`, method: 'get', params: { projectId } })
}


export function listPerfFrames(projectId: number, runId: number): Promise<PerfFrameItem[]> {
  return request<PerfFrameItem[]>({ url: `/perf/runs/${runId}/frames`, method: 'get', params: { projectId } })
}

/** Live-curve incremental view : cursor append + server-side adaptive downsample.
 * sampled=true rows are bucket summaries (p95=bucket max, error rate request-weighted). */
export interface PerfLiveCurveView {
  frames: PerfFrameItem[]
  sampled: boolean
  bucketSeconds: number
  lastOffset: number | null
  collectedTotal: number
  maxPoints: number
}

/** Live-curve incremental read leg (polls every 2s carrying the cursor; an oversized
 *  window arrives server-side downsampled with an honest annotation). */
export function listPerfLiveCurve(projectId: number, runId: number,
  sinceOffset?: number | null): Promise<PerfLiveCurveView> {
  return request<PerfLiveCurveView>({
    url: `/perf/runs/${runId}/live-curve`,
    method: 'get',
    params: { projectId, sinceOffset: sinceOffset ?? undefined },
  })
}


export function listPerfEvents(projectId: number, runId: number): Promise<PerfEventItem[]> {
  return request<PerfEventItem[]>({ url: `/perf/runs/${runId}/events`, method: 'get', params: { projectId } })
}

/** Error-class TopN (B-6 drawer read face; aggregate-caliber counts, descending). */
export function listPerfErrorTopN(projectId: number, runId: number): Promise<PerfErrorClassItem[]> {
  return request<PerfErrorClassItem[]>({ url: `/perf/runs/${runId}/error-topn`, method: 'get', params: { projectId } })
}


export function buildPerfReport(projectId: number, runId: number): Promise<PerfReportView> {
  return request<PerfReportView>({ url: `/perf/runs/${runId}/report`, method: 'get', params: { projectId } })
}

/** AI run-insight read-out (B-11 mirror of the admin door). The core
 * orchestration answers with the judge verdict plus either a model read-out or
 * an honest null (pending shape); refusal wording (closed switch, foreign run)
 * arrives as a business error and is displayed verbatim. Nothing persists —
 * a read-out is transient. */
export interface PerfRunInsightView {
  runId: number
  verdict: string
  verdictPhrase: string
  insight: string | null
}

/** Spend exactly one model hop on one finished run (switch gate + view guard
 *  + cost logging stay server-side). */
export function requestPerfRunInsight(projectId: number, runId: number): Promise<PerfRunInsightView> {
  // Same model-leg budget posture as draftPerfScenario (inner legs fire first).
  return request<PerfRunInsightView>({ url: `/perf/runs/${runId}/insight`, method: 'post', params: { projectId }, timeout: 120000 })
}

/** Download the run report as pdf/html (B-12 consume face mirror of the
 *  admin door). Rides the shared authenticated blob helper: the file name
 *  comes from the server-composed Content-Disposition, the browser never
 *  assembles one (red line 6). Refusal wording (unknown format, foreign
 *  run) arrives as a BizError and is displayed verbatim by the caller. */
export function exportPerfReport(projectId: number, runId: number, format: 'pdf' | 'html'): Promise<void> {
  return fetchAndDownloadBlob(`/api/v1/perf/runs/${runId}/report-export?projectId=${projectId}&format=${format}`,
    `perf-run-${runId}.${format}`)
}


export function comparePerfRuns(projectId: number, runA: number, runB: number): Promise<PerfCompareView> {
  return request<PerfCompareView>({ url: '/perf/runs/compare', method: 'get', params: { projectId, runA, runB } })
}


export function stopPerfRun(projectId: number, runId: number): Promise<number> {
  return request<number>({ url: `/perf/runs/${runId}/stop`, method: 'post', params: { projectId } })
}


export function stopAllPerfRuns(projectId: number): Promise<number> {
  return request<number>({ url: '/perf/runs/stop-all', method: 'post', params: { projectId } })
}


export function sendPerfReportCard(projectId: number, runId: number): Promise<number> {
  return request<number>({ url: `/perf/runs/${runId}/report-card`, method: 'post', params: { projectId } })
}


export function listPerfWhitelist(projectId: number): Promise<PerfWhitelistItem[]> {
  return request<PerfWhitelistItem[]>({ url: '/perf/whitelist', method: 'get', params: { projectId } })
}


export function createPerfWhitelist(data: PerfWhitelistSavePayload): Promise<number> {
  return request<number>({ url: '/perf/whitelist', method: 'post', data })
}


export function deletePerfWhitelist(projectId: number, id: number): Promise<void> {
  return request<void>({ url: `/perf/whitelist/${id}`, method: 'delete', params: { projectId } })
}


export function listPerfApprovals(projectId: number): Promise<PerfApprovalItem[]> {
  return request<PerfApprovalItem[]>({ url: '/perf/approvals', method: 'get', params: { projectId } })
}


export function applyPerfApproval(data: PerfApprovalApplyPayload): Promise<number> {
  return request<number>({ url: '/perf/approvals', method: 'post', data })
}


export function approvePerfApproval(projectId: number, id: number): Promise<void> {
  return request<void>({ url: `/perf/approvals/${id}/approve`, method: 'post', data: { projectId } })
}


export function rejectPerfApproval(projectId: number, id: number, rejectReason: string): Promise<void> {
  return request<void>({ url: `/perf/approvals/${id}/reject`, method: 'post', data: { projectId, rejectReason } })
}


export function precheckPerfGate(data: PerfGateCheckPayload): Promise<PerfGateDecision> {
  return request<PerfGateDecision>({ url: '/perf/gate/precheck', method: 'post', data })
}



export const PERF_PRESSURE_MODELS = ['CONSTANT_RPS', 'RAMP', 'SPIKE'] as const


export const PERF_SLA_KEYS = ['p95Ms', 'errorRate', 'minRps', 'breakerErrorRate'] as const
export const PERF_SLA_RATE_KEYS = ['errorRate', 'breakerErrorRate']


export const PERF_ISOLATION_MODES = ['SHADOW_TABLE', 'NS_ACCOUNT', 'HEADER_MARK'] as const


export const PERF_RUN_STATUS: Record<number, { label: string; type: 'primary' | 'success' | 'danger' | 'warning' }> = {
  1: { label: 'dict.perfRun.1', type: 'primary' },
  2: { label: 'dict.perfRun.2', type: 'success' },
  3: { label: 'dict.perfRun.3', type: 'danger' },
  4: { label: 'dict.perfRun.4', type: 'warning' },
}
