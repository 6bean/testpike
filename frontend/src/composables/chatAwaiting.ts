/**
 * Awaiting-window helpers for a streaming chat turn.
 *
 * The question-to-first-frame gap is invisible today: the bubble renders the
 * shared markdown empty placeholder while the server is still assembling the
 * grounding context and calling the model. These pure functions define the
 * waiting-state shape (band escalation + stage word) so the component only
 * supplies "now" and the thresholds keep a single source instead of per-page
 * magic numbers.
 */

/** Dwell after which the stage sentence appears (server still working). */
export const AWAITING_STAGE_ESCALATION_MS = 15_000
/** Dwell after which the slow-path explanation is appended. */
export const AWAITING_SLOW_PATH_ESCALATION_MS = 60_000

export interface AwaitingTurnShape {
  state: string
  content: string
}

export interface ProcessEntryShape {
  kind: string
  text: string
}

export type AwaitingBand = 'typing' | 'stage' | 'slow'

/**
 * Stage shown inside the dwell sentence: either the server's own state-frame
 * sentence (shown verbatim, server authority) or a locale key mapped from
 * the newest journaled process entry kind.
 */
export type StageDescriptor =
  | { source: 'frame'; text: string }
  | { source: 'key'; key: string }

/** True while the assistant turn exists but nothing of the answer arrived. */
export function isAwaitingTurn(turn: AwaitingTurnShape): boolean {
  return turn.state === 'running' && turn.content.trim() === ''
}

/** Whole elapsed seconds, clamped at zero (clock jitter never shows -1s). */
export function awaitingElapsedSeconds(elapsedMs: number): number {
  return Math.max(0, Math.floor(elapsedMs / 1000))
}

/** Which waiting form to show for a given dwell time. */
export function awaitingBand(elapsedMs: number): AwaitingBand {
  if (elapsedMs >= AWAITING_SLOW_PATH_ESCALATION_MS) return 'slow'
  if (elapsedMs >= AWAITING_STAGE_ESCALATION_MS) return 'stage'
  return 'typing'
}

/**
 * Process-entry kinds to a stage word. Wire frame kinds are NOT extended
 * here (AT-batch red line): cite/plan/reason/draft/clarify already exist.
 */
const STAGE_KIND_KEYS: Record<string, string> = {
  cite: 'retrieval',
  plan: 'planning',
  reason: 'generation',
  draft: 'generation',
  clarify: 'generation',
}

/** Newest meaningful signal wins; nothing journaled yet = accepted. */
export function stageOfProcess(entries: ProcessEntryShape[]): StageDescriptor {
  for (let i = entries.length - 1; i >= 0; i -= 1) {
    const entry = entries[i]
    if (entry.kind === 'state' && entry.text.trim() !== '') {
      return { source: 'frame', text: entry.text.trim() }
    }
    const key = STAGE_KIND_KEYS[entry.kind]
    if (key) return { source: 'key', key }
  }
  return { source: 'key', key: 'accepted' }
}
