/**
 * Chat dock open/close state .
 *
 * Module-level singleton so the floating bubble, the standalone page and any
 * future "let AI analyze" badge share ONE dock state (rule: no second copy of
 * drawer state). Mirrors useHelpDrawer's discipline: readonly consumption,
 * mutations only through open/close/toggle.
 *
 * Guard-rail from adjudication §thirteen: while the dock body is open the
 * bubble hides (no double-channel noise on the same band). Embedded-frame
 * pages never render the bubble — consumers check `fabVisible`.
 */
import { computed, readonly, ref } from 'vue'
import type { RefObjectPayload } from '@/api/aiChat'
import {
  clearSession,
  markCompleted,
  readLedger,
  unreadTotal,
  writeLedger,
  type MiniStorage,
  type UnreadLedger,
} from './chatUnreadBadge'

/** A pending "Analyze with AI" badge click . */
export interface BadgeDiagnosisRequest {
  executionId: number
}

/**
 * A pending question parked by the ⌘K palette : the palette owns
 * no second chat surface — it hands the natural-language keyword over as one
 * editable question and opens the dock, same one-shot posture as the badge
 * and selection channels.
 */
export interface ChatAskRequest {
  question: string
}

/**
 * An execution-task pointer parked by a replay capsule : the batch
 * detail page resolves the case's run in this batch and drops the task id on
 * the SAME executionId pointer channel the failure badge already uses — the
 * server-side replay timeline rides that pointer through the diagnosis segment
 * gate, so no second pointer mechanism exists client-side.
 */
export interface ReplayAskRequest {
  taskId: number
}

/**
 * One reference chip parked by a list-page selection — exactly
 * the wire triple (kind, id, title) the E3 chip channel already uses: the
 * selection rides it end to end, core ChatObjectRefs stays the single
 * validation point (kinds, cap, project segment), the client copies no rule.
 */
export type SelectionAskRef = RefObjectPayload

const visible = ref(false)
/** Route path captured when the dock opened, for the "return to context" hint. */
const openedFrom = ref('')
/**
 * Badge request parked for the dock to consume (one-shot): the badge button
 * lives on any failure surface, the dock owns the conversation — they meet
 * only through this singleton, never through a second dock state copy.
 */
const badgeRequest = ref<BadgeDiagnosisRequest | null>(null)
/**
 * Selection parked for the dock to consume : the list
 * page owns no second dock state — it drops its checked rows here and opens
 * the dock, which folds them into the composer as reference chips. The chips
 * ride the same send payload the @-mention popup produces, so the whole
 * server-side chain (canonicalisation, segment check, digest) stays single.
 */
const selectionAsk = ref<SelectionAskRef[] | null>(null)
/**
 * Question parked by the ⌘K palette : the palette closes
 * itself, the dock opens, and the panel prefills the composer with the typed
 * natural language. Prefill never auto-sends — the send door stays the human's
 * (same editable-question posture as the badge deep link).
 */
const chatAsk = ref<ChatAskRequest | null>(null)
/**
 * Replay pointer parked for the dock to consume : the
 * capsule installs it as the badge executionId pointer and the panel prefills
 * the replay question. Prefill never auto-sends — the send door stays the
 * human's.
 */
const replayAsk = ref<ReplayAskRequest | null>(null)

/**
 * Live-stream flag : the panel sets it while a turn is running.
 * Closing the dock is NOT interrupting — the server keeps streaming and the
 * bubble shows a soft "running" halo so a collapsed dock still reports work
 * in flight; reopening resumes replay through the Last-Event-ID channel.
 */
const streaming = ref(false)

/**
 * Pin state : false = the light floating dock where
 * click-outside collapses it (the R33 default, zero drift); true = the dock
 * stays resident until the close button or Esc. Persisted per user by the
 * shell through useChatDockPin — the panel only flips it through this one
 * setter (no second dock state copy).
 */
const pinned = ref(false)

export const chatDockState = readonly({
  visible,
  openedFrom,
  badgeRequest,
  selectionAsk,
  chatAsk,
  replayAsk,
  streaming,
  pinned,
})

export function setChatStreaming(value: boolean): void {
  streaming.value = value
}

export function setChatDockPin(value: boolean): void {
  pinned.value = value
}

/**
 * Unread ledger : completions SEALED
 * while the dock stayed closed, one count per session. The bubble presents
 * it, the panel folds it at the seal point, the shell binds the per-user
 * storage once — a single copy lives here (no second dock state).
 */
const unread = ref<UnreadLedger>({})
let unreadBinding: { storage: MiniStorage; userId: string } | null = null

export function bindChatUnreadStorage(storage: MiniStorage, userId: string): void {
  unreadBinding = { storage, userId }
  unread.value = readLedger(storage, userId)
}

/** One sealed turn finished behind a closed dock: raise that session's count. */
export function markChatUnreadCompleted(sessionKey: string, delta: number): void {
  const next = markCompleted(unread.value, sessionKey, delta)
  if (next === unread.value) return
  unread.value = next
  if (unreadBinding) writeLedger(unreadBinding.storage, unreadBinding.userId, next)
}

/** The user entered this session: its anchor zeroes, other sessions stay. */
export function clearChatUnreadSession(sessionKey: string): void {
  const next = clearSession(unread.value, sessionKey)
  if (next === unread.value) return
  unread.value = next
  if (unreadBinding) writeLedger(unreadBinding.storage, unreadBinding.userId, next)
}

/** What the bubble shows: the honest sum across all sessions. */
export const chatUnreadTotal = computed(() => unreadTotal(unread.value))

/** The dock and the bubble are mutually exclusive: bubble = open && !dock. */
export const fabVisible = computed(() => !visible.value)

/**
 * Failure badge entry (E1): park the execution pointer and open the dock.
 * The dock consumes the request once, hands the id to the panel as the
 * session's whitelist context executionId, and the panel pre-fills one
 * editable question — nothing is sent before the user acts.
 */
export function requestFailureDiagnosis(executionId: number, fromPath = ''): void {
  badgeRequest.value = { executionId }
  openChatDock(fromPath)
}

/** Take the parked badge request, leaving the slot empty (consume = one-shot). */
export function consumeBadgeRequest(): BadgeDiagnosisRequest | null {
  const request = badgeRequest.value
  badgeRequest.value = null
  return request
}

/**
 * Selection-ask entry : park the checked rows as wire triples and
 * open the dock. Nothing sends — the dock folds the set into the composer as
 * chips the user can prune before asking, same editable-question posture as
 * the badge deep link. An empty set is a no-op (a button never renders on it).
 */
export function requestSelectionAsk(refs: SelectionAskRef[], fromPath = ''): void {
  if (refs.length === 0) return
  selectionAsk.value = refs
  openChatDock(fromPath)
}

/** Take the parked selection, leaving the slot empty (consume = one-shot). */
export function consumeSelectionAsk(): SelectionAskRef[] | null {
  const request = selectionAsk.value
  selectionAsk.value = null
  return request
}

/**
 * Palette ask entry : park the typed question and open the dock.
 * A blank question is a no-op — the palette row only renders on non-blank
 * input, and the guard here keeps any other caller from seeding an empty ask.
 */
export function requestChatAsk(question: string, fromPath = ''): void {
  const trimmed = question.trim()
  if (!trimmed) return
  chatAsk.value = { question: trimmed }
  openChatDock(fromPath)
}

/** Take the parked question, leaving the slot empty (consume = one-shot). */
export function consumeChatAsk(): ChatAskRequest | null {
  const request = chatAsk.value
  chatAsk.value = null
  return request
}

/**
 * Replay capsule entry : park the execution-task pointer and open
 * the dock. A non-positive id is a no-op (a capsule only renders on a run that
 * actually produced a task). The dock folds the pointer onto the badge channel
 * and the panel prefills one editable question — nothing is sent before the
 * user acts.
 */
export function requestReplayAsk(taskId: number, fromPath = ''): void {
  if (!Number.isFinite(taskId) || taskId <= 0) return
  replayAsk.value = { taskId }
  openChatDock(fromPath)
}

/** Take the parked replay pointer, leaving the slot empty (consume = one-shot). */
export function consumeReplayAsk(): ReplayAskRequest | null {
  const request = replayAsk.value
  replayAsk.value = null
  return request
}

export function openChatDock(fromPath = ''): void {
  visible.value = true
  openedFrom.value = fromPath
}

export function closeChatDock(): void {
  visible.value = false
}

export function toggleChatDock(fromPath = ''): void {
  if (visible.value) closeChatDock()
  else openChatDock(fromPath)
}
