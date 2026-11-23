/**
 * Stage0 scenario circle-selection helpers for the AI batch generation page.
 *
 * The batch flow gains a draft-enumeration step (server side: the preview
 * ledger tp_ai_scenario_preview, BA batch ): every requirement line runs
 * through the scenario enumeration leg and comes back as a preview row whose
 * scenario list the operator circles over. This module is the single source
 * for that UI contract:
 * - a session grouping id for one preview submission (the preview table
 * references a batch logically without a foreign key, so the circle-
 * selection round uses a client-side group id and the submit itself
 * carries the expanded final payload),
 * - default-all-selected bookkeeping (adjudication 3: P0 lines enumerate
 * counter-examples and boundaries, everything selected unless the
 * operator trims it),
 * - the N→N expansion: exactly one submit item per selected scenario, the
 * requirement original plus a scenario block (title / grounding anchor /
 * kind) so generation becomes a constrained fill-in instead of open
 * writing, with the requirement-point link carried onto every item.
 * Rows enumerated while the expansion switch was off keep the legacy
 * one-item-per-line shape untouched (zero drift); a row whose scenarios are
 * all deselected is honestly dropped — the selection gate decides, we never
 * revive unselected work.
 */
import type { AiBatchItemPayload } from './aiBatchLinkage'

/** One enumerated scenario of a preview row (wire mirror of ScenarioPreviewItemView). */
export interface ScenarioPreviewItem {
  /** hyphen wire code owned by the enumeration service vocabulary */
  kind: string
  title: string
  anchor?: string | null
  note?: string | null
}

/** One persisted preview row read back for the circle-selection step. */
export interface ScenarioPreviewRow {
  id: number
  batchId?: number | null
  seq: number
  projectId: number
  requirementText: string
  /** 0=P0 1=P1 2=P2 */
  priority: number
  /** K8 switch state at enumeration time (false = legacy shape was kept) */
  expansionEnabled: boolean
  scenarios: ScenarioPreviewItem[]
  residualClaims?: string[]
  roundsUsed: number
  modelCalls: number
  converged: boolean
}

/** Localized labels injected by the view (data side defaults to Chinese business words). */
export interface ScenarioLabels {
  scenarioWord: string
  basisWord: string
  kindWords: Record<string, string>
}

export const DEFAULT_SCENARIO_LABELS: ScenarioLabels = {
  scenarioWord: '场景',
  basisWord: '原文依据',
  kindWords: {
    'main-flow': '主流程',
    equivalence: '等价类',
    'counter-example': '反例',
    boundary: '边界',
    'state-combination': '状态组合',
  },
}

/**
 * Session grouping id for one preview submission. The preview table carries a
 * logical batch reference without a foreign key; tp_ai_batch sequence ids
 * stay small integers, so an epoch-millisecond key cannot realistically
 * collide with them and is monotone within the tab.
 */
export function newPreviewBatchId(): number {
  return Date.now()
}

/** Stable key of one scenario cell in the selection matrix. */
export function scenarioSelectionKey(rowId: number, scenarioIndex: number): string {
  return `${rowId}:${scenarioIndex}`
}

/** One POST body for the stage0 enumeration run of a single filled row. */
export interface PreviewRunStep {
  batchId: number
  seq: number
  projectId: number
  requirementText: string
  /** GeneratedCase convention 0=P0 1=P1 2=P2 */
  priority: number
}

/**
 * Turn the on-screen batch rows into the preview run plan: blank rows drop,
 * the fill order decides seq (0-based, consecutive) and the trimmed original
 * text is what the enumeration anchors on. Priority is resolved per row by
 * the caller (linked requirement point wins, otherwise the P1 middle) so P0
 * lines enumerate counter-examples and boundaries while manual lines stay
 * conservative.
 */
export function buildPreviewRunPlan(
  rows: Array<{ text: string; requirementId?: number }>,
  opts: { batchId: number; projectId: number; priorityOf: (row: { text: string; requirementId?: number }) => number },
): PreviewRunStep[] {
  return rows
    .map((row) => ({ row, text: row.text.trim() }))
    .filter((entry) => entry.text.length > 0)
    .map((entry, seq) => ({
      batchId: opts.batchId,
      seq,
      projectId: opts.projectId,
      requirementText: entry.text,
      priority: opts.priorityOf(entry.row),
    }))
}

/**
 * Per-call read budget for one stage0 enumeration row (ms). The server
 * forwards the enumeration with a 360s read timeout, so a row can
 * legitimately run for tens of seconds; 420s keeps the client strictly
 * above the server budget so a slow-but-legal row answers before the
 * browser aborts. Scoped to this call alone — the global axios default
 * (30s) stays for every other endpoint. Single source: the api mirror and
 * the contract test read this constant.
 */
export const SCENARIO_PREVIEW_REQUEST_TIMEOUT_MS = 420_000

/** One plan row that could not be enumerated (kept for honest partial state). */
export interface PreviewRunFailure {
  seq: number
  message: string
}

/** Outcome of a fault-tolerant enumeration pass over the whole plan. */
export interface PreviewRunOutcome {
  completed: number
  failures: PreviewRunFailure[]
}

/**
 * Drive the preview plan row by row with per-row fault tolerance: a failed
 * row (timeout, rejection, provider error) is recorded and the remaining
 * rows keep enumerating — one slow row must never abort a 30-row plan.
 * Failures are returned, never swallowed here; the caller presents the
 * partial state plainly.
 */
export async function enumeratePreviewPlan(
  steps: PreviewRunStep[],
  runOne: (step: PreviewRunStep) => Promise<unknown>,
): Promise<PreviewRunOutcome> {
  const failures: PreviewRunFailure[] = []
  let completed = 0
  for (const step of steps) {
    try {
      await runOne(step)
      completed += 1
    } catch (err) {
      failures.push({ seq: step.seq, message: err instanceof Error ? err.message : String(err) })
    }
  }
  return { completed, failures }
}

/**
 * Re-attach the requirement-point link after the rows are read back: the
 * preview ledger stores the seq the plan submitted, so the link maps preview
 * row id -> requirement point through seq alignment. A preview row without a
 * source row (or a source row without a link) contributes nothing — the
 * server list is the truth and we never invent a link.
 */
export function requirementLinksByRowId(
  rows: Array<{ text?: string; requirementId?: number }>,
  previews: Array<{ id: number; seq: number }>,
): Map<number, number> {
  const links = new Map<number, number>()
  for (const preview of previews) {
    const source = rows[preview.seq]
    if (source !== undefined && source.requirementId !== undefined && source.requirementId !== null) {
      links.set(preview.id, source.requirementId)
    }
  }
  return links
}

/** Default circle-selection = every enumerated scenario of every row (adjudication 3). */
export function defaultSelectedKeys(rows: ScenarioPreviewRow[]): Set<string> {
  const keys = new Set<string>()
  for (const row of rows) {
    row.scenarios.forEach((_, index) => keys.add(scenarioSelectionKey(row.id, index)))
  }
  return keys
}

function scenarioBlock(scenario: ScenarioPreviewItem, labels: ScenarioLabels): string {
  const kindWord = labels.kindWords[scenario.kind] ?? scenario.kind
  const parts = [
    `【${labels.scenarioWord}·${kindWord}】${scenario.title}`,
    scenario.anchor ? `【${labels.basisWord}】${scenario.anchor}` : '',
    scenario.note ? `【说明】${scenario.note}` : '',
  ]
  return parts.filter((part) => part.length > 0).join('\n')
}

/**
 * Turn circle-selected preview rows into the final submit items (N→N): one
 * item per selected scenario carrying the requirement original plus the
 * scenario block. Rows enumerated under the switch-off shape, and rows whose
 * list came back empty, fall back to their single legacy item; rows whose
 * scenarios are all deselected contribute nothing.
 */
export function expandSelectedItems(
  rows: ScenarioPreviewRow[],
  selected: Set<string>,
  labels: ScenarioLabels,
  requirementLinks?: Map<number, number>,
): AiBatchItemPayload[] {
  const items: AiBatchItemPayload[] = []
  for (const row of [...rows].sort((a, b) => a.seq - b.seq)) {
    const link = requirementLinks?.get(row.id)
    const legacyOnly = !row.expansionEnabled || row.scenarios.length === 0
    if (legacyOnly) {
      items.push({
        text: row.requirementText,
        ...(link !== undefined && link !== null ? { requirementId: link } : {}),
      })
      continue
    }
    row.scenarios.forEach((scenario, index) => {
      if (!selected.has(scenarioSelectionKey(row.id, index))) {
        return
      }
      items.push({
        text: `${row.requirementText}\n${scenarioBlock(scenario, labels)}`,
        ...(link !== undefined && link !== null ? { requirementId: link } : {}),
      })
    })
  }
  return items
}
