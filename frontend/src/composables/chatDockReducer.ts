/**
 * Frame reducer for the chat dock .
 *
 * One folding point that turns live SSE frames into the visible state of a
 * single assistant turn. Kept as a pure module (no Vue, no network) so the
 * dock component, the future standalone page and the unit spectra all share
 * the same semantics (rule: no second copy of the folding logic).
 *
 * Wire contract it mirrors (frame protocol adjudication):
 * - delta = final-answer growth;
 * - state / trace / draft = discrete analysis-process entries, one per frame
 * in arrival order; reason = paragraph-folded fragments (: the
 * model streams thinking in tiny pieces, so consecutive fragments grow the
 * open reason paragraph and only a model newline starts a new entry) — the
 * user still always sees what the assistant is doing;
 * - cite = a grounded source : its `:meta` side-channel folds into a
 * clickable card under the answer, a frame without parsable meta degrades
 * to a process line instead of disappearing;
 * - done = terminal, carries {messageId, promptTokens, completionTokens};
 * - error = terminal degradation — the server's sentence is shown verbatim
 * (it already names the reason and the remedy; rewording would be a copy);
 * - heartbeats never arrive here (the SSE decoder drops them upstream).
 */
import type { SseFrame } from '@/api/sseFrameParser'
import { allMenuLeaves } from '@/config/menuStructure'
import { formatLocalDateTime } from '@/utils/localDateTime'

/** One line of the analysis-process area. */
export interface ProcessEntry {
  /** Frame kind: state / reason / trace / draft / cite. */
  kind: string
  /** Human line as it arrived on the wire. */
  text: string
  /**
 * Reason entries only : true once the model's own
 * paragraph break closed this entry, so the next fragment opens a new one.
 * A display-side marker — never part of what the fold renders.
 */
  closed?: boolean
}

/** One clickable source card : the machine fields of the
 * frame's `:meta` side-channel, folded once so the tail zone renders a real
 * deep link (help chapter by domain+title anchor, knowledge doc by refId).
 */
export interface SourceCitation {
  index: number
  kind: string
  refId: number
  domainCode: string | null
  title: string
  excerpt: string
  score: number
  /** Knowledge paragraph anchor (vector-row id); null for help/defect rows. */
  chunkId: string | null
}

/**
 * One confirmable draft : the
 * machine fields of a draft frame's `:meta` side-channel, landed by the core
 * projection (the `draftId` is pinned onto the frame row in the same
 * transaction that persists the draft — commit 24bcbba4). objectType routes
 * the card's presentation (SCRIPT_REPAIR diffs against the case's current
 * script; CASE_WRITE create has no target yet, CASE_WRITE edit is cut
 * against baseVersion; EVOLUTION_PROPOSAL — stage 5 — targets a prompt
 * family / tool row / grounding document, so it carries NO case anchor and
 * folds its payload subKind + target name for the card surface) — ONE fold,
 * one card component, no second clone.
 * The card drives the two-click confirm/apply flow against POST
 * /ai/chat/drafts/{draftId}/confirm-token and .../apply. A draft frame whose
 * meta carries no draftId (skipped landing, old server) never becomes a card
 * — it degrades to the process line instead.
 */
export interface RepairDraftCard {
  draftId: number
  /** Draft kind from the ledger's value set (DraftObjectType, core-side). */
  objectType: 'SCRIPT_REPAIR' | 'CASE_WRITE' | 'EVOLUTION_PROPOSAL' | 'REQUIREMENT_WRITE' | 'API_ACCESS_WRITE' | 'CASE_ROLLBACK' | 'LOGIN_STATE_WRITE' | 'TAG_WRITE'
  /** create = no case yet (CASE_WRITE with caseId 0); edit = anchored write. */
  actionKind: 'create' | 'edit'
  /** Tool the model called (case.create / case.update / repair); '' on old meta. */
  toolName: string
  executionId: number
  caseId: number
  /**
 * Requirement anchor : the tp_requirement row the edit
 * proposal acts on; 0 = create (no row exists yet). Its own field on
 * jump chip on the card, the same defect the evolution family pinned out.
 */
  requirementId: number
  baseVersion: number
  schemaVersion: string
  model: string
  /** Proposed body, verbatim snapshot of what the user confirms. */
  actionJson: string
  /** Human sentence of the frame payload (what the proposal changes). */
  summary: string
  /**
 * Evolution proposal family folded from the payload :
 * PROMPT_TPL / TOOL_DEF / GROUNDING; '' for every other draft kind. The
 * card renders its title, receipt and rollback door off this discriminator.
 */
  subKind: string
  /**
   * Human name of the object the proposal targets (template family, tool
   * name, grounding filename — folded from the payload); '' when absent.
   * The receipt names its object with it (R22: never a bare "done").
   */
  targetName: string
  /**
 * Ledger state of an ALREADY- LANDED card read back after a reload
 * : the live stream never sets it (a freshly
 * folded card is by definition pending), the history mapper folds it from
 * the draft row so a reloaded applied card re-renders its receipt instead
 * of pretending to be new, and a voided card shows why its door is closed.
 */
  landed?: { status: number; appliedVersion: number | null; rolledBack: boolean }
}

/**
 * Draft kinds whose card needs no case anchor to be actionable ( stage 5
 * whitelist, single source): CASE_WRITE create carries caseId 0 by design
 * (the project comes from the session server-side) and EVOLUTION_PROPOSAL
 * targets non-case objects. Everything else (unregistered or old-emitter
 * metas defaulting to SCRIPT_REPAIR) keeps the anchored-edit requirement —
 * a card the diff has nothing to aim at must never appear.
 */
// API_ACCESS_WRITE joins the whitelist: a pass-through change is aimed at a catalog entry plus the
// session's project, so it owns no case anchor, and its ledger target_id is the object the change
// produced (a tag row, a mock space, whatever the entry writes) — never a case.
// TAG_WRITE joins the whitelist: a tag create owns no existing object to
// anchor against (the project comes from the session server-side), so the
// card folds without caseId/baseVersion — the same shape as the create-side
// families above .
const ANCHOR_FREE_KINDS = new Set(['CASE_WRITE', 'EVOLUTION_PROPOSAL', 'REQUIREMENT_WRITE', 'API_ACCESS_WRITE', 'LOGIN_STATE_WRITE', 'TAG_WRITE'])

/** Registered draft kinds on the wire (core DraftObjectType value set). */
const KNOWN_DRAFT_KINDS = new Set(['SCRIPT_REPAIR', 'CASE_WRITE', 'EVOLUTION_PROPOSAL', 'REQUIREMENT_WRITE',
  'API_ACCESS_WRITE', 'CASE_ROLLBACK', 'LOGIN_STATE_WRITE', 'TAG_WRITE'])

/** Visibility state of one assistant turn. */
export type TurnState = 'running' | 'sealed' | 'failed'

/**
 * One action button of the case flow guide : the machine row of
 * a guide frame's `meta.guide`, whose `page`/`queryKey` the core state machine
 * single-sources (never a client guess). The button pushes exactly that route
 * with the case anchored — guarded again against the menu leaves at fold time
 * so a stale or fabricated guide row can never point the dialog at a route the
 * user has no door to.
 */
export interface FlowGuideActionButton {
  event: string
  eventLabel: string
  toStatus: number
  toStatusLabel: string
  page: string
  queryKey: string | null
}

/**
 * The journaled flow-guide answer of one turn (core FlowGuideInfo projected
 * through the ai-service ledger). `actions` are the transitions legal from the
 * case's CURRENT status (the state node matching currentStatus) — the dialog
 * renders them as buttons; a vocabulary-map answer (no case) folds with an
 * empty list.
 */
export interface FlowGuideAnswer {
  caseId: number | null
  caseTitle: string | null
  currentStatus: number | null
  currentStatusLabel: string | null
  actions: FlowGuideActionButton[]
}

/**
 * The journaled aggregate-query answer of one turn (core QueryResult projected
 * through the ai-service ledger onto a trace frame's meta.chart, P3).
 * Columns/rows ride verbatim — every number was computed by the database, the
 * model never typesets card data. The component derives geometry through the
 * pure chatChartPlan module, never re-parses the meta itself.
 */
export interface AggregateChart {
  /** Registry key that answered (named on the card surface). */
  target: string
  /** Result column labels (group keys then measure labels), select order. */
  columns: string[]
  /** Ordered rows, each keyed by the labels in `columns`. */
  rows: Array<Record<string, unknown>>
  /** Effective row cap the server applied (honesty note when rows hit it). */
  rowLimitUsed: number
}

/**
 * One step of a journaled multi-step plan : the machine row
 * of a plan frame's `meta.steps` — the built-in plan.propose tool executed
 * nothing, this is the ordered step list the user ticks and reorders before
 * their own next question starts the run.
 */
export interface PlanStep {
  index: number
  title: string
}

/** The plan card side channel folded from one plan frame (one fold point). */
export interface PlanCard {
  steps: PlanStep[]
}

/**
 * Ledger state of one goal-plan step as the dialog card can know it
 * . The stored vocabulary is the server's
 * (pending/done/skipped/blocked, tp_ai_chat_goal_plan.steps_json); the
 * open-tail sentence names the still-open steps, so today's wire folds
 * pending vs done — skipped/blocked render as soon as a machine channel
 * carries them, without a second card language.
 */
export type GoalStepState = 'pending' | 'done' | 'skipped' | 'blocked'

/** One row of the collapsible goal-progress card. */
export interface GoalStep {
  title: string
  state: GoalStepState
}

/** The goal-progress card folded from one open-tail state frame . */
export interface GoalProgress {
  steps: GoalStep[]
  /** Step count the server's sentence itself declared still open. */
  openCount: number
  /** The server's human sentence: the fold's door since ; the card
 * itself stopped rendering it in (the header + badges replaced the
 * duplicated summary paragraph). */
  sentence: string
  /** Session tokens already spent, folded from the server's cost tail
 * : the SAME session ledger the budget gate and
 * the E9 cost view read — the front end never re-computes a cost
 * figure. Null when no cost tail rode the sentence (old server) or the
 * consumption door honestly could not answer this turn — never a
 * fabricated zero. */
  consumedTokens: number | null
}

/**
 * One clarification question card folded from a clarify frame's `:meta`
 * side-channel : the built-in chat.clarify tool
 * suspended the turn on a question the model genuinely could not answer —
 * the shape contract is the orchestrator's emitClarifyFrame (question = the
 * restated ambiguity, options = the server-accepted 2-4 one-sentence
 * readings, missingSlot = the name of the still-missing piece, omitted on
 * the wire when empty). One card per turn, like the plan fold.
 */
export interface ClarifyCard {
  question: string
  options: string[]
  missingSlot: string
}

/**
 * One next-step suggestion chip folded from the done frame's optional
 * `suggest` member : the built-in chat.suggest tool
 * proposed questions the user can pick up with one click, each naming the
 * capability it would route to. The server already intersection-gated the
 * list (a capability the actor cannot use never reaches the wire); the fold
 * here only re-verifies the SHAPE, never the permission.
 */
export interface ChatSuggestion {
  text: string
  capability: string
}

/**
 * Shape gate for the done frame's suggest member. The server's accepted
 * shape is 1-3 objects with non-empty text and capability strings; anything
 * else (missing member, drifted body, half-shape item) degrades to an empty
 * zone — the chip row is optional garnish on a sealed answer, never worth
 * baiting a click on a broken shape or a duplicated folding semantics.
 */
export function parseSuggestions(raw: unknown): ChatSuggestion[] {
  if (!Array.isArray(raw) || raw.length === 0 || raw.length > 3) return []
  const chips: ChatSuggestion[] = []
  for (const item of raw) {
    if (item === null || typeof item !== 'object') return []
    const text = (item as Record<string, unknown>).text
    const capability = (item as Record<string, unknown>).capability
    if (typeof text !== 'string' || text.trim() === '') return []
    if (typeof capability !== 'string') return []
    chips.push({ text, capability })
  }
  return chips
}

/** One message row of the dock (user rows carry no process area). */
export interface DockTurn {
  id: number
  role: 'user' | 'assistant'
  /** Answer text (assistant) or the asked question (user). */
  content: string
  /** Analysis-process entries, arrival order. */
  process: ProcessEntry[]
  /** Clickable source cards from cite frames . */
  citations: SourceCitation[]
  /** Confirmable repair drafts from draft frames . */
  repairs: RepairDraftCard[]
  /**
 * Case flow guide journaled by a trace frame's meta : the
 * current status' legal transitions, rendered as action buttons under the
 * answer. One fold point — the component never re-parses the meta itself.
 */
  guide: FlowGuideAnswer | null
  /**
 * Aggregate-query live card journaled by a trace frame's meta : the
 * server-computed columns/rows of the built-in aggregate.query answer. One
 * fold point like the guide; a later frame replaces the earlier card.
 */
  chart: AggregateChart | null
  /**
 * Multi-step plan journaled by a plan frame : tickable,
 * reorderable steps awaiting the user's own confirmation. One fold point
 * like the chart; a frame with no usable meta keeps the human sentence in
 * the process area instead (never a card-less silent drop).
 */
  plan: PlanCard | null
  /**
 * Goal-progress card folded from the open-tail state frame the
 * orchestrator journals before done when the turn's ledger still owes
 * steps . Null until such a sentence lands on a
 * turn that journaled its plan in the same run.
 */
  goal: GoalProgress | null
  /**
 * Clarification question card journaled by a clarify frame (AX batch
 * : the one door that un-suspends a turn the model could
 * not answer. One fold point like the plan; a frame with no usable meta
 * keeps the human sentence in the process area instead.
 */
  clarify: ClarifyCard | null
  /**
 * Next-step suggestion chips folded from the done frame's optional
 * suggest member . Empty on every legacy payload
 * and on every drifted shape — the row is honest garnish, never a seal
 * breaker. The panel shows them on the newest sealed turn only.
 */
  suggest: ChatSuggestion[]
  /**
   * Data-moment mini-label folded from the retrieval state frame's meta
   * `dataMoment` member (behaviour kernel round-2 item 5, front half): the
   * as-of moment of the grounded pool, pre-formatted at this ONE folding
   * point. Kept optional and never set on a drifted shape — an untimed pool,
   * a zero-hit answer, a legacy server and every reloaded row all stay
   * label-free (same honest posture as guide/chart/plan on reload), so the
   * answer surface never invents a clock.
   */
  dataMoment?: string | null
  state: TurnState
  /** Assistant message id once a done/error frame named it (abort seam). */
  messageId: number | null
  /** Usage numbers of the sealed turn (E9 attribution). */
  usage: { promptTokens: number; completionTokens: number } | null
  /** Terminal degradation sentence, shown verbatim (never empty on failed). */
  errorText: string | null
  /**
 * Epoch millis of the turn . Live rows stamp the
 * moment the row is pushed; reloaded rows fold the ledger created_at —
 * through the one mapper, so the divider/time label never drift between a
 * live thread and the same thread after a reload.
 */
  createdAt: number
  /**
 * Per-turn rating folded from the ledger : 1 👍 / -1 👎 /
 * 0 cleared / undefined = never rated on a live row. The complaint sentence
 * only ever rides -1 — the mapper drops any stale text exactly the way the
 * server clears the column, so a mixed signal never shows on both surfaces.
 */
  feedback?: number | null
  feedbackReason?: string | null
  /**
 * Credential-screenshot placeholder :
 * true when this turn booked a screenshot whose original pixels were
 * purged at the terminal state, so the reload view shows a named
 * placeholder row instead of the image. Folded from the server's per-turn
 * marker at the ONE history-mapping point; a fresh turn starts false —
 * nothing is booked yet and the pending strip already names the picked
 * file.
 */
  credPlaceholder?: boolean
}

/**
 * True when the row at `index` is the FIRST turn of its calendar day
 * .
 * An out-of-range index on an empty window still opens the divider.
 */
export function isFirstOfDay(rows: Array<{ createdAt: number }>, index: number): boolean {
  if (index <= 0) return true
  const prev = rows[index - 1]
  const cur = rows[index]
  if (!prev || !cur) return true
  return new Date(prev.createdAt).toDateString() !== new Date(cur.createdAt).toDateString()
}

// 'user-inject' (mid-turn steering echo): the ledger frame proving the user's
// in-flight injection was accepted — one visible process line, identical on the
// live stream, the reconnect replay and a history reload (silent drop is the
// defect this frame exists to disprove).
const PROCESS_KINDS = new Set(['state', 'trace', 'draft', 'cite', 'user-inject'])

/**
 * : fold one reason fragment into the turn's paragraph entries.
 * Consecutive fragments grow the last OPEN reason entry (typewriter posture,
 * same as answer deltas); the model's own newline is the paragraph break —
 * it closes the current entry and the remainder opens a fresh one. Leading
 * blank lines of a remainder are display noise and dropped. Pure display
 * aggregation: every frame that reaches here still arrived on the wire and
 * in the ledger, untouched.
 */
function appendReasonParagraph(turn: DockTurn, data: string): void {
  if (data === '') return
  const cut = data.indexOf('\n')
  if (cut < 0) {
    const open = openReasonEntry(turn)
    if (open) open.text += data
    else turn.process.push({ kind: 'reason', text: data })
    return
  }
  const head = data.slice(0, cut)
  const open = openReasonEntry(turn)
  if (head !== '') {
    if (open) {
      open.text += head
      open.closed = true
    } else {
      turn.process.push({ kind: 'reason', text: head, closed: true })
    }
  } else if (open) {
    open.closed = true
  }
  const tail = data.slice(cut + 1).replace(/^[\n\s]+/, '')
  if (tail !== '') turn.process.push({ kind: 'reason', text: tail })
}

/** The last process entry when it is an open reason paragraph, else null. */
function openReasonEntry(turn: DockTurn): ProcessEntry | null {
  const last = turn.process[turn.process.length - 1]
  return last && last.kind === 'reason' && !last.closed ? last : null
}

export function createAssistantTurn(id: number, createdAt: number = Date.now()): DockTurn {
  return {
    id,
    role: 'assistant',
    content: '',
    process: [],
    citations: [],
    repairs: [],
    guide: null,
    chart: null,
    plan: null,
    goal: null,
    clarify: null,
    suggest: [],
    state: 'running',
    messageId: null,
    usage: null,
    errorText: null,
    createdAt,
  }
}

export function createUserTurn(id: number, text: string, createdAt: number = Date.now()): DockTurn {
  return {
    id,
    role: 'user',
    content: text,
    process: [],
    citations: [],
    repairs: [],
    guide: null,
    chart: null,
    plan: null,
    goal: null,
    clarify: null,
    suggest: [],
    state: 'sealed',
    messageId: null,
    usage: null,
    errorText: null,
    createdAt,
    credPlaceholder: false,
  }
}

/**
 * Apply one decoded frame to the turn (mutates in place — the dock renders
 * the same object reactively). Unknown event names are ignored quietly: a
 * server-side protocol addition must never break a live conversation.
 */
export function applyFrameToTurn(turn: DockTurn, frame: SseFrame): void {
  if (frame.event === 'delta') {
    turn.content += frame.data
    return
  }
  if (frame.event === 'cite') {
    // : a cite frame with a parsable meta side-channel becomes a clickable
    // source card; without one (old server, broken payload) it degrades honestly
    // to a plain process line — the human sentence always rides in the payload.
    const card = parseCitation(frame.meta)
    if (card) {
      turn.citations.push(card)
    } else {
      turn.process.push({ kind: frame.event, text: frame.data })
    }
    return
  }
  if (frame.event === 'draft') {
    // stage 3c: a draft frame whose meta carries the landed draftId
    // becomes a confirmable repair card; anything else (skipped landing, old
    // server without the side-channel) keeps the human sentence in the
    // process area — the card door needs a real draft row to aim at.
    const card = parseRepairDraft(frame.meta, frame.data)
    if (card) {
      turn.repairs.push(card)
    } else {
      turn.process.push({ kind: frame.event, text: frame.data })
    }
    return
  }
  if (frame.event === 'reason') {
    // (user complaint 2026-10-30, adjudicated display semantics): the
    // model streams its thinking as dozens-to-hundreds of tiny reason frames;
    // one entry per frame rendered a fragment waterfall. Fold consecutive
    // reason fragments into the last open reason entry (typewriter growth,
    // same posture as answer deltas) and open a new entry only at the model's
    // own paragraph breaks — a newline carried by a fragment. Discrete kinds
    // below keep one entry per frame. Display-side aggregation only: the wire
    // protocol, the ledger frame granularity and replay semantics are
    // untouched, and all three surfaces (live stream, reconnect replay,
    // history reload) share this one folding point.
    appendReasonParagraph(turn, frame.data)
    return
  }
  if (frame.event === 'plan') {
    // package 3: the journaled multi-step plan folds into a tickable
    // card with the same degrade semantics as cite/draft — usable meta
    // becomes the card side channel, anything else keeps the human line in
    // the process area. One card per turn: the orchestrator journals exactly
    // one plan frame before the done frame, so a replace-on-fold is enough.
    const card = parsePlanCard(frame.meta)
    if (card) {
      turn.plan = card
    } else {
      turn.process.push({ kind: frame.event, text: frame.data })
    }
    return
  }
  if (frame.event === 'clarify') {
    // the journaled clarification question folds into the
    // one-click answer card with the same degrade semantics as plan/cite/
    // draft — usable meta becomes the card side channel, anything else keeps
    // the human sentence in the process area (never a card-less silent drop,
    // never a half-shape card baiting a click). One card per turn: the
    // orchestrator journals exactly one clarify frame (per-turn quota), so a
    // replace-on-fold is enough.
    const card = parseClarifyCard(frame.meta)
    if (card) {
      turn.clarify = card
    } else {
      turn.process.push({ kind: frame.event, text: frame.data })
    }
    return
  }
  if (PROCESS_KINDS.has(frame.event)) {
    // the orchestrator's open-tail sentence (the one state
    // frame journaled before done when the goal-plan ledger still owes
    // steps) folds into the collapsible progress card instead of a bare
    // process line — but only on a turn that journaled its plan here, and
    // only while the sentence keeps its pinned shape. Anything else (no
    // plan frame, drifted wording, an ordinary status line) rides the
    // process area exactly as before: never a card-less silent drop.
    if (frame.event === 'state' && turn.plan) {
      const tail = parseGoalOpenTail(frame.data)
      if (tail) {
        turn.goal = foldGoalProgress(turn.plan, tail, frame.data)
        return
      }
    }
    if (frame.event === 'state' && frame.meta) {
      // Data-moment stamp (behaviour kernel round-2 item 5, front half): the
      // orchestrator parks {"dataMoment":"<ISO offset datetime>"} on the
      // retrieval state frame's meta — folded here at the ONE point into a
      // pre-formatted label text, while the human sentence still rides the
      // process area exactly as before (side channel, never a replacement).
      // Any drifted shape (malformed JSON, absent/non-string member) leaves
      // the turn untouched: no label beats an invented clock. A fresher
      // retrieval frame replaces the earlier stamp (one pool per turn).
      try {
        const raw = JSON.parse(frame.meta) as Record<string, unknown>
        if (typeof raw.dataMoment === 'string' && raw.dataMoment) {
          turn.dataMoment = formatLocalDateTime(raw.dataMoment)
        }
      } catch {
        // Unusable meta: honest no-label posture, sentence line survives.
      }
    }
    turn.process.push({ kind: frame.event, text: clipGoalOpenTailLine(frame.data) })
    // stage 3b: the flow-guide tool journals its structured answer onto
    // the trace frame's meta side-channel. The human line stays in the process
    // area exactly like every other trace; the guide is the folded side effect
    // that powers the action buttons. An unusable meta shape simply folds to
    // null — the trace line never disappears because of it.
    if (frame.event === 'trace') {
      const guide = parseFlowGuide(frame.meta)
      if (guide) {
        turn.guide = guide
      }
      // P3 the built-in aggregate.query tool journals its
      // server-computed QueryResult onto meta.chart — folded through the same
      // one-point semantics as the guide (a later answer replaces the card;
      // an unusable meta never removes the human trajectory line).
      const chart = parseAggregateChart(frame.meta)
      if (chart) {
        turn.chart = chart
      }
    }
    return
  }
  if (frame.event === 'done') {
    turn.state = 'sealed'
    const usage = parseUsage(frame.data)
    if (usage) {
      turn.messageId = usage.messageId ?? turn.messageId
      turn.usage = { promptTokens: usage.promptTokens ?? 0, completionTokens: usage.completionTokens ?? 0 }
      // the optional suggest member folds through the ONE
      // shape gate; a legacy payload (no member) lands on an empty zone, so
      // the seal path is byte-compatible with every pre-feature frame.
      turn.suggest = parseSuggestions(usage.suggest)
    }
    return
  }
  if (frame.event === 'error') {
    // Honest partial: whatever delta landed stays on screen; the server's
    // sentence is the terminal statement, kept verbatim.
    turn.state = 'failed'
    turn.errorText = frame.data
    return
  }
  // Anything else (retry hints, future events): quietly ignored.
}

function parseUsage(data: string): { messageId?: number; promptTokens?: number; completionTokens?: number; suggest?: unknown } | null {
  try {
    return JSON.parse(data)
  } catch {
    return null
  }
}

/**
 * Fold the machine-readable meta of one cite frame into a source card. Returns
 * null for anything unusable (missing side-channel, malformed JSON, absent
 * required scalars) — the caller then keeps the human line in the process area
 * so a server-side protocol drift never silently loses the citation's text.
 */
function parseCitation(meta: string | undefined): SourceCitation | null {
  if (!meta) return null
  let raw: Record<string, unknown>
  try {
    raw = JSON.parse(meta) as Record<string, unknown>
  } catch {
    return null
  }
  if (typeof raw.title !== 'string' || typeof raw.kind !== 'string') return null
  if (typeof raw.refId !== 'number' || typeof raw.index !== 'number') return null
  return {
    index: raw.index,
    kind: raw.kind,
    refId: raw.refId,
    domainCode: typeof raw.domainCode === 'string' ? raw.domainCode : null,
    title: raw.title,
    excerpt: typeof raw.excerpt === 'string' ? raw.excerpt : '',
    score: typeof raw.score === 'number' ? raw.score : 0,
    chunkId: typeof raw.chunkId === 'string' && raw.chunkId !== '' ? raw.chunkId : null,
  }
}

/**
 * Fold the journaled flow-guide answer out of one trace frame's meta.
 * Shape contract (ai-service commit da7cafe5 + core dial chain): the tool is
 * dialed as an HTTP tool against the core internal door, so `guide` arrives as
 * that door's Response envelope `{code:"0",msg,data}` — the envelope is
 * unwrapped here (a non-zero code = a refused read, folded to null so no
 * buttons are invented from an error body). Bare-object metas (fixtures, older
 * emitters) pass through. `actions` are then projected to the transitions
 * legal from the case's CURRENT status, each guarded against the menu leaves
 * (`page` must be a real route — a stale or fabricated guide row can never
 * point the dialog at a door the user has none of). Anything unusable returns
 * null: the human trace line rides the process area regardless.
 */
function parseFlowGuide(meta: string | undefined): FlowGuideAnswer | null {
  if (!meta) return null
  let raw: Record<string, unknown>
  try {
    raw = JSON.parse(meta) as Record<string, unknown>
  } catch {
    return null
  }
  let guide: unknown = raw.guide
  if (guide && typeof guide === 'object' && 'code' in (guide as Record<string, unknown>)) {
    const envelope = guide as Record<string, unknown>
    // The core door answers with Response{code,msg,data}; only code "0"
    // carries a real FlowGuideInfo. Anything else = refused, no card.
    if (envelope.code !== '0' || typeof envelope.data !== 'object' || envelope.data === null) {
      return null
    }
    guide = envelope.data
  }
  if (!guide || typeof guide !== 'object') return null
  const info = guide as Record<string, unknown>
  if (!Array.isArray(info.states)) return null
  const currentStatus = typeof info.currentStatus === 'number' ? info.currentStatus : null
  const actions = currentStatus === null
    ? []
    : (info.states as Array<Record<string, unknown>>)
        .filter((node) => node.code === currentStatus)
        .flatMap((node) => Array.isArray(node.actions) ? node.actions as Array<Record<string, unknown>> : [])
        .filter((a) => typeof a.event === 'string' && typeof a.eventLabel === 'string'
          && typeof a.page === 'string' && allMenuLeaves.some((leaf) => leaf.path === a.page))
        .map((a) => ({
          event: a.event as string,
          eventLabel: a.eventLabel as string,
          toStatus: typeof a.toStatus === 'number' ? a.toStatus : 0,
          toStatusLabel: typeof a.toStatusLabel === 'string' ? a.toStatusLabel : '',
          page: a.page as string,
          queryKey: typeof a.queryKey === 'string' ? a.queryKey : null,
        }))
  return {
    caseId: typeof info.caseId === 'number' ? info.caseId : null,
    caseTitle: typeof info.caseTitle === 'string' ? info.caseTitle : null,
    currentStatus,
    currentStatusLabel: typeof info.currentStatusLabel === 'string' ? info.currentStatusLabel : null,
    actions,
  }
}

/**
 * Fold the journaled aggregate-query answer out of one trace frame's meta.
 * Shape contract : the
 * built-in aggregate.query tool's QueryResult body ({target, columns, rows,
 * rowLimitUsed}) is embedded verbatim under `chart`; the CoreGateway already
 * unwraps the core Response envelope server-side, but a wrapped shape is
 * tolerated here exactly like parseFlowGuide does (a non-zero code = refused,
 * no card). Anything unusable returns null — the human trajectory line rides
 * the process area regardless, and the card never renders invented numbers.
 */
function parseAggregateChart(meta: string | undefined): AggregateChart | null {
  if (!meta) return null
  let raw: Record<string, unknown>
  try {
    raw = JSON.parse(meta) as Record<string, unknown>
  } catch {
    return null
  }
  let chart: unknown = raw.chart
  if (chart && typeof chart === 'object' && 'code' in (chart as Record<string, unknown>)) {
    const envelope = chart as Record<string, unknown>
    if (envelope.code !== '0' || typeof envelope.data !== 'object' || envelope.data === null) {
      return null
    }
    chart = envelope.data
  }
  if (!chart || typeof chart !== 'object') return null
  const result = chart as Record<string, unknown>
  if (typeof result.target !== 'string') return null
  if (!Array.isArray(result.columns) || !result.columns.every((c) => typeof c === 'string')) return null
  if (!Array.isArray(result.rows) || !result.rows.every((r) => r !== null && typeof r === 'object')) return null
  return {
    target: result.target,
    columns: result.columns as string[],
    rows: result.rows as Array<Record<string, unknown>>,
    rowLimitUsed: typeof result.rowLimitUsed === 'number' ? result.rowLimitUsed : 0,
  }
}

/**
 * Fold the machine-readable meta of one plan frame into the card's ordered
 * steps . Requires a non-empty `steps` array where every row
 * carries a numeric `index` and a non-empty string `title` — exactly the
 * shape ChatRunOrchestrator.emitPlanFrame journals. Anything else returns
 * null, and the caller keeps the human sentence as a process line: a drifted
 * or truncated meta must never render a half-shape card baiting a click.
 * Exported for the contract spectra and any future reload door so the plan
 * fold stays a single source (rule: no second copy of the folding logic).
 */
export function parsePlanCard(meta: string | undefined): PlanCard | null {
  if (!meta) return null
  let raw: Record<string, unknown>
  try {
    raw = JSON.parse(meta) as Record<string, unknown>
  } catch {
    return null
  }
  if (!Array.isArray(raw.steps) || raw.steps.length === 0) return null
  const steps: PlanStep[] = []
  for (const row of raw.steps) {
    if (!row || typeof row !== 'object') return null
    const step = row as Record<string, unknown>
    if (typeof step.index !== 'number' || typeof step.title !== 'string' || step.title === '') {
      return null
    }
    steps.push({ index: step.index, title: step.title })
  }
  return { steps }
}

/**
 * Fold the machine-readable meta of one clarify frame into the answer card
 * . Shape contract = ChatRunOrchestrator.emitClarifyFrame:
 * a non-empty `question`, an `options` array of 2-4 non-empty strings (the
 * exact range ClarifyTool.isAcceptedShape admits server-side — a drifted or
 * truncated meta must never render a half-shape card baiting a click), and
 * an optional `missingSlot` (the server omits it when empty; anything
 * non-string on it refuses the card). Returns null for anything unusable —
 * the caller then keeps the human sentence as a process line, never a
 * card-less silent drop. Exported for the contract spectra and any future
 * reload door so the clarify fold stays a single source (no second copy).
 */
export function parseClarifyCard(meta: string | undefined): ClarifyCard | null {
  if (!meta) return null
  let raw: Record<string, unknown>
  try {
    raw = JSON.parse(meta) as Record<string, unknown>
  } catch {
    return null
  }
  if (typeof raw.question !== 'string' || raw.question === '') return null
  if (!Array.isArray(raw.options) || raw.options.length < 2 || raw.options.length > 4) return null
  if (!raw.options.every((o) => typeof o === 'string' && o !== '')) return null
  if ('missingSlot' in raw && typeof raw.missingSlot !== 'string') return null
  return {
    question: raw.question,
    options: raw.options as string[],
    missingSlot: typeof raw.missingSlot === 'string' ? raw.missingSlot : '',
  }
}

/**
 * Prefix of the orchestrator's goal-plan open-tail sentence ,
 * wording revised for autonomous continuation): single-source copy of
 * ChatRunOrchestrator.runGoalAutoBatches' framing — batch frames read
 * The frame kind stays the nine-type state frame (red line: zero new
 * ChatFrameType kinds), so the card door recognises the ledger tail by
 * this prefix and nothing else.
 */
export const GOAL_OPEN_TAIL_PREFIX = '目标计划还有'

/**
 * Fold the open-tail sentence into {openCount, openText} or null. Requires
 * the pinned shape (prefix, digit count, full-width parentheses); a drifted
 * or truncated sentence returns null so the caller keeps the honest
 * process-line posture. openText stays the RAW inside-parentheses string:
 * step titles may themselves contain the enumeration comma, so membership
 * is a substring test against this text, never a comma split.
 */
export function parseGoalOpenTail(data: string): { openCount: number; openText: string } | null {
  if (!data.startsWith(GOAL_OPEN_TAIL_PREFIX)) return null
  const m = /^目标计划还有 (\d+) 步未完成（(.*)）/s.exec(data)
  if (!m) return null
  const count = Number(m[1])
  if (!Number.isFinite(count) || count < 0) return null
  return { openCount: count, openText: m[2] }
}

/**
 * Clip an open-tail sentence for the process area :
 * a long ledger must never dump every open step name as one wall of text —
 * the enumeration shows the first three names plus an explicit ellipsis, the
 * count and the trailing clause (which batch / cap stop + resume hint) stay
 * verbatim, and any sentence that is not a pinned open-tail passes through
 * untouched. The FULL sentence still rides GoalProgress.sentence, so the
 * card's membership semantics and the frame ledger never change — this is a
 * display clip at the single folding point, never a data rewrite.
 */
export function clipGoalOpenTailLine(data: string): string {
  const tail = parseGoalOpenTail(data)
  if (!tail) return data
  const names = tail.openText.split('、').map((s) => s.trim()).filter((s) => s !== '')
  if (names.length <= 3) return data
  const m = /^目标计划还有 \d+ 步未完成（.*）/s.exec(data)
  if (!m) return data
  return `${GOAL_OPEN_TAIL_PREFIX} ${tail.openCount} 步未完成（${names.slice(0, 3).join('、')}…）`
    + data.slice(m[0].length)
}

/**
 * Build the progress card from the turn's plan steps + the open tail
 * , one folding point shared by live stream and reconnect
 * replay). Steps whose title appears inside the sentence's parentheses stay
 * pending — the ledger keeps open steps (pending or blocked) in its
 * enumeration — steps that dropped out read done on the card; an open title
 * the plan frame never listed is appended as a pending row, so the card can
 * never silently drop a step the server just named.
 */
export function foldGoalProgress(
  plan: PlanCard,
  tail: { openCount: number; openText: string },
  sentence: string,
  openStates?: Readonly<Record<string, string>>,
): GoalProgress {
  // The reload projection may name an open step's own stored state; only the
  // open vocabulary (pending/blocked) is honoured — anything else (drift,
  // unknown value) keeps the stream's pending posture, never a fake glyph.
  const stateOf = (title: string): GoalStepState =>
    openStates?.[title] === 'blocked' ? 'blocked' : 'pending'
  const steps: GoalStep[] = plan.steps.map((s) => ({
    title: s.title,
    state: tail.openText.includes(s.title) ? stateOf(s.title) : 'done',
  }))
  for (const title of tail.openText.split('、')) {
    const trimmed = title.trim()
    // A stored step title may itself contain the enumeration comma, so the
    // — appending it as a new pending row inflates the card (: 5 open
    // steps rendered 7~8 rows, done pinned at 0). The equality the old guard
    // made is subsumed here: an exact title contains itself.
    if (trimmed !== '' && !steps.some((s) => s.title.includes(trimmed))) {
      steps.push({ title: trimmed, state: stateOf(trimmed) })
    }
  }
  return { steps, openCount: tail.openCount, sentence, consumedTokens: parseGoalCostTail(sentence) }
}

/**
 * Fold the session-consumption figure out of the server's cost tail
 * ( item 5 / §6 — GoalCostLine.appendCostTail is the single
 * emitting source, the wording is pinned there). Append-only by contract:
 * the pinned open-tail prefix the card folds on never moves, so parsing
 * the tail here cannot break the step fold.
 * Returns the token count for a known figure, null for the honest unknown
 * tail and null when no tail rode the sentence at all (old server) — the
 * card then shows no cost chip instead of inventing a number.
 */
export function parseGoalCostTail(sentence: string): number | null {
  const known = /本会话已消耗 (\d+) tokens/.exec(sentence)
  if (known) {
    const value = Number(known[1])
    return Number.isFinite(value) ? value : null
  }
  return null
}

/**
 * The columns every draft read door projects for one tp_ai_chat_draft row:
 * the sealed-history door (chatHistoryMapper) and the pinned panel's pending
 * group read (admin GET /api/v1/ai/chat/drafts/group/pending) both fit it, so
 * both fold through one semantics.
 */
export interface DraftRowShape {
  draftId: number
  objectType: string
  payloadJson: string | null
  targetId: number | null
  baseVersion: number | null
  summary: string | null
}

/**
 * Fold one stored draft row into a card through the ONE live parser below:
 * the row is synthesised into the frame-meta shape the reducer already trusts,
 * so every surface that shows a stored draft — the reload view ( stage 5
 * fix and the pinned panel's pending groups
 * — renders and confirms exactly like the stream. One folding semantics: no
 * surface gets to grow a second copy of the synthesis.
 */
export function foldDraftRowToCard(row: DraftRowShape): RepairDraftCard | null {
  return parseRepairDraft(JSON.stringify({
    draftId: row.draftId,
    objectType: row.objectType,
    actionJson: row.payloadJson,
    caseId: row.targetId,
    baseVersion: row.baseVersion,
  }), row.summary ?? '')
}

/**
 * Fold the machine-readable meta of one draft frame into a confirmable card.
 * Requires the landed `draftId` (the confirmation doors are addressed by it;
 * a draft without a row is not confirmable) plus the proposal body. The kind
 * defaults to SCRIPT_REPAIR exactly like the core-side landing does when old
 * emitters pin no objectType; an edit keeps its case anchor + base version, a
 * CASE_WRITE create (caseId 0) needs neither — the project comes from the
 * session server-side, so the card never has to know it. Anything unusable
 * returns null — the caller keeps the human sentence in the process area so a
 * skipped landing or an old server never shows a card that cannot be
 * confirmed.
 * Exported for the history mapper : a reloaded
 * draft row is synthesised into this same meta shape and folded through THIS
 * parser — one folding semantics, never a second copy for the reload view.
 */
export function parseRepairDraft(meta: string | undefined, summary: string): RepairDraftCard | null {
  if (!meta) return null
  let raw: Record<string, unknown>
  try {
    raw = JSON.parse(meta) as Record<string, unknown>
  } catch {
    return null
  }
  if (typeof raw.draftId !== 'number') return null
  if (typeof raw.actionJson !== 'string') return null
  // Registered kinds pass through; anything else (old emitters pin no
  // objectType, future drift) keeps the SCRIPT_REPAIR default exactly like
  // the core-side landing does — and with it the anchored-edit requirement.
  const objectType = KNOWN_DRAFT_KINDS.has(String(raw.objectType))
    ? String(raw.objectType) as RepairDraftCard['objectType']
    : 'SCRIPT_REPAIR'
  // Anchored kinds (script repairs, unknown drift) are edits of a real case;
  // the anchor-free whitelist above folds without caseId/baseVersion.
  if (!ANCHOR_FREE_KINDS.has(objectType)
    && (typeof raw.caseId !== 'number' || typeof raw.baseVersion !== 'number')) {
    return null
  }
  // The ledger's target_id is family-specific: for CASE_WRITE/SCRIPT_REPAIR it
  // IS the case row, for EVOLUTION_PROPOSAL it is the template/tool/document
  // the evolution card (found by the stage-5 vision pass) — the anchor-
  // free evolution families never own a case, and the card receipt names its
  // own target instead.
  const caseId = objectType === 'EVOLUTION_PROPOSAL' || objectType === 'REQUIREMENT_WRITE'
    || objectType === 'API_ACCESS_WRITE' || objectType === 'LOGIN_STATE_WRITE' || objectType === 'TAG_WRITE'
    ? 0
    : (typeof raw.caseId === 'number' ? raw.caseId : 0)
  // Requirement anchor read family-first: the live frame meta carries the
  // generic targetId (ai-service writeDraftMeta), the reload synthesis
  // aliases the ledger's target_id onto caseId (chatHistoryMapper) — read
  // both, one fold, so the stream and the reload land the SAME card shape
  // (the mapper's pinned no-second-semantics rule).
  const requirementId = objectType === 'REQUIREMENT_WRITE'
    ? (typeof raw.targetId === 'number' ? raw.targetId
      : typeof raw.caseId === 'number' ? raw.caseId : 0)
    : 0
  // Evolution proposals carry their family + target object inside the payload
  // the card confirms verbatim (core generator writes subKind/name/filename);
  // folded once here so the card surface never re-parses the JSON itself.
  let subKind = ''
  let targetName = ''
  if (objectType === 'EVOLUTION_PROPOSAL') {
    try {
      const payload = JSON.parse(raw.actionJson) as Record<string, unknown>
      subKind = typeof payload.subKind === 'string' ? payload.subKind : ''
      targetName = typeof payload.name === 'string' ? payload.name
        : typeof payload.filename === 'string' ? payload.filename : ''
    } catch {
      // An unparsable payload still shows the card (the confirmation door is
      // real); the family line simply reads as unknown on the card surface.
    }
  }
  return {
    draftId: raw.draftId,
    objectType,
    actionKind: caseId > 0 || requirementId > 0 ? 'edit' : 'create',
    toolName: typeof raw.toolName === 'string' ? raw.toolName : '',
    executionId: typeof raw.executionId === 'number' ? raw.executionId : 0,
    caseId,
    requirementId,
    baseVersion: typeof raw.baseVersion === 'number' ? raw.baseVersion : 0,
    schemaVersion: typeof raw.schemaVersion === 'string' ? raw.schemaVersion : '',
    model: typeof raw.model === 'string' ? raw.model : '',
    actionJson: raw.actionJson,
    summary,
    subKind,
    targetName,
  }
}
