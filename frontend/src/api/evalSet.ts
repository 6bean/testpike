import { request } from './request'

// ---- AW4 run doors (picker / create / dispatch mirrors) -----------------

/** Rubric family option (latest version per name x domain — the version
 *  decision stays in core; this picker only echoes the rows). */
export interface RubricOption {
  id: number
  name: string
  domain: string | null
  version: number | null
  passScore: number | null
  status: number | null
}

/** Published evaluation-set version option (immutable version segments). */
export interface EvalsetVersionOption {
  setName: string
  version: number
  snapshotDigest: string | null
  sampleCount: number | null
}

/** Create-run payload (anchors pinned by the picker; createdBy is stamped
 *  from the login session server-side — the page never reports who acts). */
export interface JudgeRunCreateInput {
  rubricId: number
  evalsetName: string
  evalsetVersion: number
  evalsetDigest: string | null
  targetType: 'pipeline' | 'external'
  targetRef: string
  llmConfigId?: number | null
}

/** Dispatch receipt (the dispatch gates and per-sample enqueue live in
 *  core; the receipt is echoed untouched). */
export interface JudgeDispatchReceipt {
  dispatched: number
  judgeProvider: string | null
}

/** Rubric picker options for the run-creation form. */
export function listRubricOptions(): Promise<RubricOption[]> {
  return request<RubricOption[]>({ url: '/judge-runs/rubrics', method: 'get' })
}

/** Published evalset version options (name x version x digest x count). */
export function listEvalsetVersions(): Promise<EvalsetVersionOption[]> {
  return request<EvalsetVersionOption[]>({ url: '/eval-sets/evalsets', method: 'get' })
}

/** Create a run: both anchors freeze at creation; core rejections carry
 *  their own reason and are shown as-is. */
export function createJudgeRun(input: JudgeRunCreateInput): Promise<JudgeRunRow> {
  return request<JudgeRunRow>({ url: '/judge-runs', method: 'post', data: input })
}

/** Dispatch a pending run into the judge queue (optional provider pin). */
export function dispatchJudgeRun(runId: number, evaluatedProvider?: string | null): Promise<JudgeDispatchReceipt> {
  return request<JudgeDispatchReceipt>({
    url: `/judge-runs/${runId}/dispatch`, method: 'post',
    data: { evaluatedProvider: evaluatedProvider ?? null },
  })
}

/**
 * Evaluation-set door API mirror (admin bridge /api/v1/eval-sets +
 * /api/v1/judge-runs added by the judge write-chain facade batch).
 * Every decision (version lineage, promotion gates, run state machine)
 * stays single-source in core — this layer only types what the page
 * renders; rejections from core are rethrown by the bridge with their
 * own message and shown as-is.
 */

export interface EvalSample {
  id: number
  setName: string
  version: number
  snapshotDigest: string | null
  input: string
  expected: string | null
  referenceDocs: string[] | null
  source: string | null
  sensitive: number | null
  createdBy: number | null
  createdAt: string | null
}

/** Row shape of the run-history page (admin bridge JudgeRunView mirror;
 *  only the fields this door renders are typed). */
export interface JudgeRunRow {
  id: number
  rubricName?: string | null
  rubricDomain?: string | null
  rubricVersion?: number | null
  evalsetName?: string | null
  evalsetVersion?: number | null
  targetType?: string | null
  targetRef?: string | null
  status?: number | null
  createdAt?: string | null
  finishedAt?: string | null
}

export interface JudgeRunPage {
  page: number
  size: number
  total: number
  runs: JudgeRunRow[]
}

/** Samples of one frozen evaluation-set version (AW1 read door). */
export function getEvalSamples(setName: string, version: number): Promise<EvalSample[]> {
  return request<EvalSample[]>({
    url: '/eval-sets/samples', method: 'get',
    params: { setName, version },
  })
}

/**
 * Run history page (newest first) — also the source of known set names.
 * Optional server-side filters (set name / status) keep the worst-case
 * table navigable; the page-size ceiling is enforced in core and its
 * rejection is echoed, never mirrored here.
 */
export function listJudgeRuns(page = 1, size = 50, filters?: { evalsetName?: string; status?: number }): Promise<JudgeRunPage> {
  return request<JudgeRunPage>({
    url: '/judge-runs', method: 'get',
    params: {
      page, size,
      ...(filters?.evalsetName ? { evalsetName: filters.evalsetName } : {}),
      ...(filters?.status != null ? { status: filters.status } : {}),
    },
  })
}

/** Result of one publish/promotion: core freezes a new immutable version. */
export interface EvalSetVersionView {
  setName: string
  version: number
  snapshotDigest: string | null
  sampleCount: number
}

/** One sample row sent to publish (field shape mirrors the admin DTO). */
export interface EvalPublishSampleInput {
  input: string
  expected: string | null
  referenceDocs: string[] | null
  source: string
  sensitive: number
}

/**
 * Publish the whole sample list as a NEW version of the set (AW2 manual
 * door). Re-publishing the same set never overwrites a previous version —
 * version lineage and snapshot digest are decided single-source in core.
 * The operator id is taken from the login session server-side; the page
 * never reports who is acting.
 */
export function publishEvalSet(setName: string, samples: EvalPublishSampleInput[]): Promise<EvalSetVersionView> {
  return request<EvalSetVersionView>({
    url: '/eval-sets/publish', method: 'post',
    data: { setName, samples },
  })
}

/** Promote selected training samples into a new set version (AW2 source ③). */
export function raiseFromTrainingSamples(setName: string, sampleIds: number[]): Promise<EvalSetVersionView> {
  return request<EvalSetVersionView>({
    url: '/eval-sets/raise', method: 'post',
    data: { setName, sampleIds },
  })
}

/** Promote adopted cases into a new set version (AW2 source ①; the
 * adoption triple-gate stays a core decision, the bridge only forwards). */
export function raiseFromAdoptedCases(setName: string, caseIds: number[]): Promise<EvalSetVersionView> {
  return request<EvalSetVersionView>({
    url: '/eval-sets/raise-from-adopted', method: 'post',
    data: { setName, caseIds },
  })
}

/** Promote failed/timeout tasks of one execution batch into a new set
 * version (AW2 source ②; the terminal-status gate stays a core decision,
 * the bridge only forwards). */
export function raiseFromBatch(setName: string, taskIds: number[]): Promise<EvalSetVersionView> {
  return request<EvalSetVersionView>({
    url: '/eval-sets/raise-from-batch', method: 'post',
    data: { setName, taskIds },
  })
}

// ---- AW3 question-generation doors (admin bridge mirrors, zero recomputation) --

/** One generated QA pair (field shape = ai-service preview item, passed
 * through by the bridge untouched). */
export interface QaGenItem {
  question?: string
  answer?: string
  sourceAnchor?: string
  [key: string]: unknown
}

/** Submit receipt: exactly one of the two shapes is filled (sync cap
 * decided single-source by ai-service — the page never re-derives it). */
export interface QaGenSubmit {
  mode: 'sync' | 'accepted'
  runToken: string | null
  preview: { items: QaGenItem[]; chunkCount: number; promptTokens: number; completionTokens: number; model: string } | null
}

/** Polling face row of a background question-generation run. */
export interface QaGenJob {
  documentId: number
  runToken: string
  status: 'running' | 'done' | 'failed'
  totalGroups: number
  doneGroups: number
  items: QaGenItem[]
  promptTokens: number
  completionTokens: number
  model: string
  errorMsg: string | null
}

/** One process step of a running job (same frame shape the shared task
 * tail client decodes, R40 — no second decoder here). */
export interface QaGenEvent {
  cursor: number
  level: string
  summary: string
  detail: string | null
  landedAt: string
}

/** Ask ai-service for a question preview of one document (AW3 step 1).
 * Inline answer within the sync cap; beyond it the receipt carries a run
 * token and the caller polls {@link getQaGenJob}. */
export function qaPreview(projectId: number, documentId: number, modelHint?: string): Promise<QaGenSubmit> {
  return request<QaGenSubmit>({
    url: '/eval-sets/qa-preview', method: 'post',
    params: { projectId, documentId, modelHint },
  })
}

/** Latest job row (status + cumulative groups/items) of the document. */
export function getQaGenJob(documentId: number): Promise<QaGenJob> {
  return request<QaGenJob>({
    url: '/eval-sets/qa-gen-job', method: 'get',
    params: { documentId },
  })
}

/** Cursor window of the run's process events (replay-idempotent tail). */
export function getQaGenEvents(documentId: number, runToken: string, cursor = 0, limit = 50): Promise<QaGenEvent[]> {
  return request<QaGenEvent[]>({
    url: '/eval-sets/qa-gen-events', method: 'get',
    params: { documentId, runToken, cursor, limit },
  })
}

/** One historical question-generation run .
 * Status is resolved read-side by the server: running / done / failed. */
export interface QaGenRun {
  runToken: string
  status: string
  eventCount: number
  firstAt: string
  lastAt: string
}

/** The document's run history, newest first. A null limit defers to the
 *  ai-service hot key — no second default lives in the page layer. */
export function listQaGenRuns(documentId: number, limit?: number): Promise<QaGenRun[]> {
  return request<QaGenRun[]>({
    url: '/eval-sets/qa-gen-runs', method: 'get',
    params: { documentId, limit },
  })
}

/**
 * Map one generated QA item onto a publish sample (AW3 → AW2 write door).
 * Pure function so the mapping contract is testable without a browser:
 * question→input, answer→expected, sourceAnchor→single-element
 * referenceDocs, frozen source value 'ai-generated' (core SOURCES closed
 * set), sensitive defaults to 0 — the reviewer edits sensitivity later
 * through the normal sample doors, the wizard never guesses it.
 */
export function qaItemToPublishSample(item: QaGenItem): EvalPublishSampleInput {
  const anchor = typeof item.sourceAnchor === 'string' && item.sourceAnchor.trim() !== ''
    ? item.sourceAnchor.trim()
    : null
  return {
    input: (item.question ?? '').toString().trim(),
    expected: item.answer != null ? String(item.answer) : null,
    referenceDocs: anchor ? [anchor] : null,
    source: 'ai-generated',
    sensitive: 0,
  }
}

/**
 * Echo a stored sample back as a publish input (carrying the current
 * version forward when a door appends rows). Single-source helper shared
 * by the manual door and the question-generation wizard (R40 — the two
 * doors used to each keep a copy).
 */
export function sampleToPublishInput(s: EvalSample): EvalPublishSampleInput {
  return {
    input: s.input,
    expected: s.expected,
    referenceDocs: s.referenceDocs,
    source: s.source || 'manual',
    sensitive: s.sensitive ?? 0,
  }
}
