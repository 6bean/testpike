/**
 * Single-source presentation logic for self-heal evidence rows in the
 * execution report surface . The badges, the anchor diff labels
 * and the batch numbers all derive here so the report view and the ledger
 * detail drawer never carry two copies of the same judgement (R40).
 *
 * Batch rule from the WE design doc §8.4: the healed rate and the
 * a high heal rate alongside a rising mismatch count is the signal that a
 * UI redesign may be masking real defects. The mismatch count is the number
 * of failed assertion-family steps already present in the step timeline;
 * no new backend metric is invented here.
 */
import type { StepHealEvidenceRow } from '@/api/webElements'

/** Assertion verbs of the web executor vocabulary (actions_common.BUILTIN_ACTIONS). */
const ASSERTION_VERBS = ['assertVisible', 'assertNotVisible', 'assertText']

/** Timeline row shape this logic reads (structurally compatible with StepTimelineItem). */
export interface HealReportStep {
  stepNo?: number | null
  action?: string | null
  status?: string | null
}

/** One visible badge row: a timeline step paired with its heal evidence (0..n). */
export interface HealBadgeItem {
  stepNo: number
  rows: StepHealEvidenceRow[]
}


export function healAnchorDiffText(row: StepHealEvidenceRow): string {
  return `${row.fromAnchorType}: ${row.fromAnchorValue} → ${row.usedAnchorType}: ${row.usedAnchorValue}`
}

/**
 * when evidence rows reference its step number. Evidence whose step number
 * is absent from the timeline (older data, step renumbered) is reported in
 * `orphanRows` so the summary never silently drops audit rows.
 */
export function buildHealBadgeItems(
  steps: HealReportStep[],
  rows: StepHealEvidenceRow[],
): { items: HealBadgeItem[]; orphanRows: StepHealEvidenceRow[] } {
  const byStep = new Map<number, StepHealEvidenceRow[]>()
  const orphans: StepHealEvidenceRow[] = []
  for (const row of rows) {
    if (row.stepNo == null) {
      orphans.push(row)
      continue
    }
    const bucket = byStep.get(row.stepNo)
    if (bucket) bucket.push(row)
    else byStep.set(row.stepNo, [row])
  }
  const items: HealBadgeItem[] = []
  for (const step of steps) {
    const bucket = step.stepNo != null ? byStep.get(step.stepNo) : undefined
    if (bucket) {
      items.push({ stepNo: step.stepNo as number, rows: bucket })
      byStep.delete(step.stepNo as number)
    }
  }
  const orphanRows = [...orphans, ...[...byStep.values()].flat()]
  return { items, orphanRows }
}

/** True when the step is an assertion-family step (server vocabulary, not a heuristic). */
export function isAssertionStep(step: HealReportStep): boolean {
  return typeof step.action === 'string' && ASSERTION_VERBS.includes(step.action)
}

/** Failed assertion-family steps = the assertion-mismatch number shown next to the rate. */
export function countAssertionMismatches(steps: HealReportStep[]): number {
  return steps.filter((s) => isAssertionStep(s) && s.status != null && s.status !== 'ok').length
}

/** One-line batch summary numbers (rate denominator = steps in this report). */
export interface HealSummary {
  healedStepCount: number
  evidenceRowCount: number
  ledgerStepTotal: number
  /** null = denominator unknown/zero: display an honest dash, never a fake 0%/100%. */
  healRate: number | null
  assertionMismatchCount: number
  orphanRowCount: number
}

export function buildHealSummary(steps: HealReportStep[], rows: StepHealEvidenceRow[]): HealSummary {
  const { items, orphanRows } = buildHealBadgeItems(steps, rows)
  const denominator = steps.length
  return {
    healedStepCount: items.length,
    evidenceRowCount: rows.length,
    ledgerStepTotal: denominator,
    healRate: denominator > 0 ? items.length / denominator : null,
    assertionMismatchCount: countAssertionMismatches(steps),
    orphanRowCount: orphanRows.length,
  }
}

/** Percent label with one decimal; null rate answers the honest dash. */
export function formatHealRate(rate: number | null): string {
  if (rate == null) return '-'
  return `${Math.round(rate * 1000) / 10}%`
}
