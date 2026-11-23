/**
 * History folding for the chat kernel .
 *
 * A reloaded page reads the sealed ledger rows back through the admin door
 * (GET /ai/chat/sessions/{id}/messages, bodies already rebuilt core-side from
 * the delta frames). This module turns those rows into visible turns through
 * ONE pure mapping point — the same DockTurn shape the live stream folds into
 * — so the reload view can never drift from the live view (rule: no second
 * copy of the folding semantics).
 *
 * Status machine it mirrors (core ChatFrameService): streaming -> done |
 * aborted | error. Only sealed rows ever arrive here (the server filters
 * streaming out). done/aborted = sealed with the text the user watched
 * (abort keeps the partial, never erases it); error = failed, never a fake
 * completion. Pure module: no Vue, no network — the unit spectra and both
 * entry surfaces share these exact semantics.
 */
import { parseGoalOpenTail, parseRepairDraft, foldGoalProgress, foldDraftRowToCard, type DockTurn } from './chatDockReducer'

/** One landed draft as the history door folds it onto its message row
 *  (mirrors core ChatDraftInfo / admin ChatDraftView — ledger fields only). */
export interface ChatDraftRowView {
  id: number
  objectType: string
  summary: string | null
  payloadJson: string
  status: number
  targetId: number | null
  appliedVersion: number | null
  rolledBack: boolean
  /** Optimistic anchor the proposal was cut against (null for creates). */
  baseVersion: number | null
}

/** Still-open goal-plan tail shape: single source = the api door's mirror of
 * core ChatGoalPlanInfo / admin ChatGoalPlanView — re-exported
 * here so the fold and the transport can never drift into two copies (R40). */
export type { ChatGoalPlanRowView } from '@/api/aiChat'
import type { ChatGoalPlanRowView } from '@/api/aiChat'

/** One ledger row as the admin door returns it (mirrors ChatMessageView). */
export interface ChatMessageRowView {
  id: number
  sessionId: number
  role: string
  content: string | null
  reasoning: string | null
  status: string
  seqFrom: number | null
  seqTo: number | null
  promptTokens: number
  completionTokens: number
  /** Ledger instant (ISO); optional so pre- fixtures stay valid. */
  createdAt?: string | null
  /** Per-turn rating mirrored from ChatMessageView (1 👍 / -1 👎 / 0 cleared / null unrated). */
  feedback?: number | null
  /** Complaint sentence that only ever rides a -1 rating. */
  feedbackReason?: string | null
  /** Confirmable draft cards landed on this message . */
  drafts?: ChatDraftRowView[]
  /** Still-open goal-plan tail projected onto the newest assistant row
 * . */
  goalPlan?: ChatGoalPlanRowView | null
  /** Per-turn credential-screenshot marker folded core-side from the
 * credLedger frame bookings ; optional so pre-feature
 * fixtures and older servers stay valid. */
  credLedger?: boolean
}

/**
 * Map sealed rows (oldest first) onto turns continuing after `rowSeqBase` so
 * freshly pushed local rows never collide on keys. Usage numbers ride only
 * the assistant rows that actually recorded them (user echoes carry zeros).
 */
export function mapHistoryToTurns(rows: ChatMessageRowView[], rowSeqBase: number): DockTurn[] {
  return rows.map((row, i) => {
    const turn: DockTurn = {
      id: rowSeqBase + i + 1,
      role: row.role === 'user' ? 'user' : 'assistant',
      content: row.content ?? '',
      process: [],
      // Reloaded rows carry the rebuilt answer only: cite frames of sealed
      // turns do not replay through this door, so the card zone starts empty.
      citations: [],
      // Sealed draft cards fold back through the ONE live parser
      // (chatDockReducer.parseRepairDraft): a landed ledger row is synthesised
      // into the frame-meta shape the reducer already trusts, so the reload
      // view can render and confirm the card exactly like the live stream
      // .
      repairs: (row.drafts ?? []).flatMap((d) => {
        const card = foldDraftRowToCard({
          draftId: d.id,
          objectType: d.objectType,
          payloadJson: d.payloadJson,
          targetId: d.targetId,
          baseVersion: d.baseVersion,
          summary: d.summary,
        })
        if (!card) return []
        card.landed = { status: d.status, appliedVersion: d.appliedVersion, rolledBack: d.rolledBack }
        return [card]
      }),
      // Flow-guide buttons : the journaled answer replays only
      // through the live stream today (sealed rows fold text like cite/draft),
      // so the reload view keeps an honest empty guide instead of buttons the
      // mapper would have to re-invent.
      guide: null,
      // Aggregate-query cards : the journaled chart replays
      // only through the live stream today (sealed rows fold text like
      // cite/draft/guide), so the reload view keeps an honest empty chart.
      chart: null,
      // Plan cards : the journaled plan replays through the
      // live stream and the reconnect door (both fold in the reducer); a
      // history reload keeps an honest empty plan zone like guide/chart.
      plan: null,
      // Clarify cards : the journaled clarify frame replays
      // through the live stream and the reconnect door exactly like the plan
      // (both fold in the reducer); a history reload keeps an honest empty
      // zone — the sealed question already rode the answer text, no card is
      // re-invented here.
      clarify: null,
      // Suggestion chips : the done frame replays only through
      // the live stream door, so a reloaded row keeps an honest empty zone
      // exactly like guide/chart/plan/clarify — no second folding semantics.
      suggest: [],
      // Goal-progress card : the sealed-history door projects
      // the session's still-open ledger tail onto the newest assistant row;
      // that sentence rides the SAME live parsers (parseGoalOpenTail →
      // foldGoalProgress) — one folding semantics, so the reload view can
      // never drift from the stream. Without a journaled plan the open titles
      // seed the card as pending rows; a drifted server sentence folds to no
      // card (never a fabricated one).
      goal: foldReloadGoal(row),
      // Honest terminal shape: error stays failed; done/aborted are sealed
      // with whatever body the ledger holds (partials included).
      state: row.status === 'error' ? 'failed' : 'sealed',
      messageId: row.role === 'assistant' ? row.id : null,
      usage: row.role === 'assistant' && (row.promptTokens > 0 || row.completionTokens > 0)
        ? { promptTokens: row.promptTokens, completionTokens: row.completionTokens }
        : null,
      errorText: row.status === 'error' ? (row.content ?? '') : null,
      // Ledger instant folds to epoch millis ; an
      // older row without the stamp degrades to "now" instead of NaN, which
      // would hide the day divider of the whole reload view.
      createdAt: row.createdAt != null ? new Date(row.createdAt).getTime() : Date.now(),
      // Per-turn rating : folded with the same invariant the server
      // enforces — a complaint sentence survives ONLY on -1, so a reloaded
      // panel can never show a stale complaint on a positive/cleared rating.
      feedback: row.feedback ?? null,
      feedbackReason: row.feedback === -1 ? (row.feedbackReason ?? null) : null,
      // Credential-screenshot placeholder : the server
      // folds the credLedger marker per turn — one folding point, the
      // component never re-reads frame meta. Absent marker (older server,
      // unbooked turn) folds false, never a guessed true.
      credPlaceholder: row.credLedger === true,
    }
    return turn
  })
}

/**
 * Fold the projected goal-plan tail into the progress card through the ONE
 * live sentence parsers . Sealed rows never journal their plan
 * frame through this door, so the ledger's own open titles seed the card —
 * each seed row takes the state the projection names for it ( residual
 * face: a blocked step reloads as blocked, not flattened to pending; a
 * projection without per-step states — an older server — keeps the substring
 * rule's pending posture). foldGoalProgress is called unmodified in its live
 * shape, no second folding semantics. A missing projection or a server
 * sentence that drifted from the pinned shape answers "no card": the reload
 * view never fabricates progress the ledger did not state.
 */
function foldReloadGoal(row: ChatMessageRowView) {
  const tail = row.goalPlan ? parseGoalOpenTail(row.goalPlan.openTailSentence) : null
  if (!tail || !row.goalPlan) return null
  const plan = { steps: row.goalPlan.openTitles.map((title, i) => ({ index: i + 1, title })) }
  const states = row.goalPlan.openStates
  const openStates: Record<string, string> = {}
  if (Array.isArray(states)) {
    row.goalPlan.openTitles.forEach((title, i) => {
      const state = states[i]
      if (typeof state === 'string') openStates[title] = state
    })
  }
  return foldGoalProgress(plan, tail, row.goalPlan.openTailSentence, openStates)
}
