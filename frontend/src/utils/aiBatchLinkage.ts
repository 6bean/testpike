/**
 * Requirement linkage helpers for the AI batch generation page.
 *
 * The batch rows carry an optional requirement-point id (filled by the
 * case-gap one-click handoff) so that generated cases can be linked back to
 * the requirement points they came from (server side: tp_ai_task.requirement_id).
 * Keeping the row model and payload builders here gives the view and the unit
 * tests a single source for the dual submit form (structured items plus the
 * legacy plain-text mirror kept for one compatibility cycle).
 */

/** A batch row as edited on screen: free text plus an optional carried link. */
export interface BatchRow {
  text: string
  requirementId?: number
}

/** Structured submit entry (mirrors the shared backend AiBatchItem record). */
export interface AiBatchItemPayload {
  text: string
  requirementId?: number
}

/** Minimal requirement shape needed when filling rows from the gap list. */
export interface GapRequirementLite {
  id: number
  title: string
  acceptanceCriteria?: string | null
}

/** Submit payload for /ai/batch/estimate and /ai/batch-generate (dual form). */
export interface AiBatchSubmitPayload {
  projectId: number
  requirements: string[]
  items: AiBatchItemPayload[]
}

/**
 * Fill batch rows from gap requirements: the same text channel as manual rows
 * (title, plus acceptance criteria with the localized prefix when present),
 * while each row keeps the requirement-point id it was generated from.
 */
export function rowsFromGapItems(items: GapRequirementLite[], acPrefix: string): BatchRow[] {
  return items.map((item) => ({
    text: item.acceptanceCriteria ? `${item.title}\n${acPrefix}${item.acceptanceCriteria}` : item.title,
    requirementId: item.id,
  }))
}

/** Normalize rows for submit: trim, drop blank rows, preserve carried ids. */
export function buildBatchItems(rows: BatchRow[]): AiBatchItemPayload[] {
  return rows
    .map((row) => ({
      text: row.text.trim(),
      ...(row.requirementId !== undefined && row.requirementId !== null
        ? { requirementId: row.requirementId }
        : {}),
    }))
    .filter((item) => item.text.length > 0)
}

/**
 * Build the dual-form submit payload: structured items win on the server,
 * the legacy requirements mirror keeps old contracts working one version cycle.
 */
export function buildBatchPayload(projectId: number, rows: BatchRow[]): AiBatchSubmitPayload {
  const items = buildBatchItems(rows)
  return { projectId, requirements: items.map((item) => item.text), items }
}

/** Id cap the batch page hands to the server-side list allow-list (same limit as core). */
export const AI_GENERATE_LINK_MAX_IDS = 200

/**
 * Build the one-click hand-off deep link into the AI batch generation page.
 * The checked/freshly-created requirement ids ride the allow-list query so the
 * target page loads exactly those rows; entries outside the project segment are
 * refused there with an honest count (never silently faked). Single source for
 * every entry point that jumps into generation carrying requirement ids.
 */
export function buildAiGenerateDeepLink(
  projectId: number,
  requirementIds: number[],
): { path: string; query: { projectId: string; requirementIds: string } } {
  const ids = requirementIds
    .filter((id) => Number.isFinite(id) && id > 0)
    .slice(0, AI_GENERATE_LINK_MAX_IDS)
  return {
    path: '/ai/generate',
    query: { projectId: String(projectId), requirementIds: ids.join(',') },
  }
}

/** Parse the comma-separated requirementIds query value; junk/non-positive entries drop out. */
export function parseRequirementIdsParam(raw: unknown): number[] {
  if (raw === undefined || raw === null || raw === '') return []
  return String(raw)
    .split(',')
    .map((part) => Number(part.trim()))
    .filter((n) => Number.isFinite(n) && n > 0)
}
