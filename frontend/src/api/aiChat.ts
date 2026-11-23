/**
 * Dialog stream client for the chat dock .
 *
 * One door, two shapes: a fresh question posts {prompt}; a reconnect posts
 * {resumeCursor} (Last-Event-ID semantics carried in the body — the server
 * never starts a second run behind the user's back). EventSource cannot be
 * used here: it carries neither the JWT header nor the POST body, so the
 * stream rides fetch + ReadableStream and the SSE blocks are decoded by the
 * shared sseFrameParser (one parsing point, no copy in the component).
 *
 * Honesty rules mirrored from the wire contract:
 * - the ledger seq (SSE id) is the cursor — heartbeats carry none and never
 * shift it (replaying them would re-send old frames);
 * - a refused door (403 etc.) surfaces the backend's human-readable msg —
 * no second phrasing invented here;
 * - close aborts the in-flight request: the turn keeps streaming on the
 * server until the pump's own timers seal it; use the abort message
 * endpoint (abortChatMessage) to stop the turn itself.
 */
import axios from 'axios'
import { BizError, getToken, postMultipart, request, type ApiResponse } from './request'
import { createSseFrameDecoder, type SseFrame } from './sseFrameParser'
import { emitObjectSync } from '../composables/chatSyncBus'

export type SseFrameEvent = SseFrame

/** Session row handed back by the admin door (mirrors ChatSessionView). */
export interface ChatSession {
  id: number
  projectId: number
  userId: number
  title: string
  source: number
  status: string
  lastSeq: number
  totalTokens: number
}

/**
 * Open a conversation inside one project segment (admin session door).
 * The operator id is pinned server-side from the login context — the body
 * never carries it (horizontal-access red line). Rides the request wrapper so
 * it shares the /api/v1 proxy and the JWT header with every admin call; the
 * browser never talks to ai-service directly.
 */
export function createChatSession(payload: {
  projectId: number
  title?: string
  source?: number
  contextJson?: string
}): Promise<ChatSession> {
  return request<ChatSession>({ url: '/ai/chat/sessions', method: 'post', data: payload })
}

/** Opening shape of one stream: exactly one of the two fields is sent. */
export interface ChatStreamRequest {
  prompt?: string
  resumeCursor?: number
  /**
 * Send-time ask-from anchor : JSON of header project + current
 * page + at most one object/execution pointer, produced by the shared
 * whitelist builder; the core door re-validates every key. A resume
 * reconnect never carries it (one run keeps one audited origin).
 */
  questionContext?: string
  /**
 * @-mentioned objects accepted as chips : the chip
 * list is the single truth of a reference — typed "@text" without a chip
 * is plain text and never rides here. The grounding side consumes it in
 * knife 4b; until then the server ignores the field (forward-compatible).
 */
  refObjects?: RefObjectPayload[]
  /**
 * User images attached to a fresh question ( P4 , mobile-photo
 * bug report): pairs returned by the upload door, re-validated unchanged by
 * core's single validation point at the run door. A resume never carries
 * them; clients that send no images keep the old shape (forward-compatible).
 */
  attachments?: ChatImagePayload[]
}

/** Reference triple pinned by the dialog contract: kind + id + human title. */
export interface RefObjectPayload {
  kind: 'case' | 'defect' | 'batch' | 'doc' | 'skill' | 'tool'
  id: number
  title: string
}

/** Upload door view of one chat image (admin mirror of core's ChatImageView). */
export interface ChatImageView {
  objectKey: string
  fileName: string
  contentType: string
  sizeBytes: number
}

/** Attachment pair the stream request carries back to the run door. */
export interface ChatImagePayload {
  objectKey: string
  fileName: string
}

/**
 * Upload one image into the caller's project segment (multipart part `file`,
 * projectId as form field — same posture as the document upload door).
 * Size/extension/magic refusals come from core's single gate with the
 * server sentence passed through verbatim; a refused upload leaves zero
 * objects behind. Permission rides the existing dialog key.
 */
export function uploadChatImage(projectId: number, file: File): Promise<ChatImageView> {
  return postMultipart<ChatImageView>('/ai/chat/images', file, { projectId: String(projectId) })
}

/**
 * @-mention candidates of one project segment .
 * The admin door answers with the core assembly rows themselves — published
 * skills ∪ five-way-gated callable tools, skills before tools — so the
 * palette can never offer what the run would refuse. Titles come from the
 * server rows (honest after a rename). Rides the shared wrapper: JWT header
 * + /api/v1 proxy, the browser never talks to ai-service directly.
 */
export interface MentionCandidate {
  kind: 'skill' | 'tool'
  id: number
  title: string
}

export function fetchMentionCandidates(projectId: number): Promise<MentionCandidate[]> {
  return request<MentionCandidate[]>({
    url: `/ai/chat/projects/${projectId}/mention-candidates`,
    method: 'get',
  })
}

/**
 * Why a stream ended badly, without wording: the dock maps each kind to its
 * locale entry (i18n single source — the API layer never ships display text;
 * precedent = the router guard refuses with a key, not a sentence).
 * A refused door keeps the backend's human msg verbatim (the server wording
 * is already the authority; wrapping it again would be a second copy).
 */
import { CHAT_FIRST_FRAME_TIMEOUT_MS } from '@/composables/chatFailureResend'

export type ChatStreamFailure =
  | { kind: 'network' }
  | { kind: 'interrupted' }
  | { kind: 'no-body' }
  | { kind: 'timeout' }
  | { kind: 'refused'; serverMsg: string }

/** Handle over one live stream. */
export interface ChatStreamHandle {
  /** Last acknowledged ledger seq (send this back as resumeCursor on reconnect). */
  cursor(): number
  /** Stop reading locally (does NOT abort the server-side turn). */
  close(): void
  /** Resolves when the stream ended (done/error frame, EOF, refusal, abort). */
  settled: Promise<void>
}

export function openChatStream(
  sessionId: number,
  request: ChatStreamRequest,
  onFrame: (frame: SseFrameEvent) => void,
  onError: (failure: ChatStreamFailure) => void,
): ChatStreamHandle {
  const controller = new AbortController()
  let cursor = 0

  // First-frame watchdog: silence for the whole dwell threshold means nothing
  // EVER arrived (the dwell bands above it only narrate slowness), so the
  // line fails honestly as a timeout with the resend door instead of sitting
  // in an awaiting bubble forever. Cleared by the first frame (see loop) and
  // by every exit path via `settled` below — never a dangling timer.
  let sawFrame = false
  const watchdog = setTimeout(() => {
    if (sawFrame || controller.signal.aborted) return
    controller.abort()
    onError({ kind: 'timeout' })
  }, CHAT_FIRST_FRAME_TIMEOUT_MS)

  const run = (async () => {
    let response: Response
    try {
      response = await fetch(`/api/v1/ai/chat/sessions/${sessionId}/stream`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${getToken()}`,
        },
        body: JSON.stringify(request),
        signal: controller.signal,
      })
    } catch (networkOrAbort) {
      // A local close() lands here too; the dock treats it as a quiet end.
      if (!controller.signal.aborted) {
        onError({ kind: 'network' })
      }
      return
    }
    if (!response.ok) {
      // The admin door refuses with a human-readable msg (ownership, budget …)
      // — the server's sentence is the single source, shown as-is.
      let serverMsg = ''
      try {
        const body = await response.text()
        const parsed = JSON.parse(body) as { msg?: string }
        serverMsg = parsed.msg ?? ''
      } catch {
        // Non-JSON error page: the dock falls back to its status-shaped entry.
      }
      onError({ kind: 'refused', serverMsg })
      return
    }
    if (!response.body) {
      onError({ kind: 'no-body' })
      return
    }
    const decoder = createSseFrameDecoder()
    const reader = response.body.getReader()
    // An abort must also release a pending read() (platform-independent close),
    // otherwise the loop would sit on a connection the user already walked away from.
    const cancelReader = () => { void reader.cancel() }
    if (controller.signal.aborted) {
      // Closed between the request and this line: honor it immediately.
      cancelReader()
    } else {
      controller.signal.addEventListener('abort', cancelReader)
    }
    const text = new TextDecoder()
    try {
      for (;;) {
        let chunk
        try {
          chunk = await reader.read()
        } catch (streamBroken) {
          if (!controller.signal.aborted) {
            onError({ kind: 'interrupted' })
          }
          return
        }
        if (chunk.done) {
          return
        }
        for (const frame of decoder.push(text.decode(chunk.value, { stream: true }))) {
          if (!sawFrame) {
            sawFrame = true
            clearTimeout(watchdog)
          }
          if (frame.id !== null && frame.id > cursor) {
            cursor = frame.id
          }
          onFrame(frame)
        }
      }
    } finally {
      controller.signal.removeEventListener('abort', cancelReader)
    }
  })()

  // Whatever path the stream exits on (frame, refusal, EOF, abort), the
  // watchdog dies with it — resource pairing, no timer outlives its stream.
  const settled = run.finally(() => clearTimeout(watchdog))
  return {
    cursor: () => cursor,
    close: () => controller.abort(),
    settled,
  }
}

/**
 * Interrupt a streaming turn through the admin relay: the partial answer
 * stays on screen and the ledger seals the turn as aborted (never a fake
 * completion, never thrown-away text).
 */
export async function abortChatMessage(messageId: number): Promise<void> {
  await request({ url: `/ai/chat/messages/${messageId}/abort`, method: 'post' })
}

/**
 * Pending un-landed draft count for one object (AM batch door;
 * mirrors admin GET /api/v1/ai/chat/drafts/pending-count). The count is
 * computed single-sourced in core — this client only carries the pointer
 * (project segment + object type + id) and never folds an empty answer: a
 * failed read stays a visible partial-load note in the pinned panel.
 */
export function fetchDraftPendingCount(
  projectId: number,
  objectType: string,
  targetId: number,
): Promise<number> {
  return request<number>({
    url: `/ai/chat/drafts/pending-count?projectId=${projectId}&objectType=${objectType}&targetId=${targetId}`,
    method: 'get',
  })
}

/**
 * Landing verdict of the mid-turn steering door :
 * the supplement either joined the answer that is still streaming (it replays
 * as a user-inject frame at `seq`), or no answer was running and the same text
 * went out as a regular message whose assistant turn is `messageId`. Either
 * way a steering sentence is never silently dropped.
 */
export interface ChatInjectView {
  injectedIntoRunningTurn: boolean
  messageId: number
  seq: number
}

/**
 * Steer the answer that is still streaming: the supplement rides the inject
 * door of this session and joins the running turn. The echo the reader sees is
 * the journaled user-inject frame arriving on the open stream (the ledger is
 * the single truth — nothing is painted from the click), so the same line shows
 * live, after a reconnect replay and in a history reload.
 */
export function injectChatSupplement(
  sessionId: number,
  content: string,
): Promise<ChatInjectView> {
  return request<ChatInjectView>({
    url: `/ai/chat/sessions/${sessionId}/injects`,
    method: 'post',
    data: { content },
  })
}

/**
 * Rate one sealed answer (self-evolution observation intake): 1 = helpful,
 * -1 = not helpful with an optional one-sentence complaint, 0 = rating
 * cleared. The server keeps the complaint on -1 only — any other state write
 * clears it — and the reply is the fresh ledger row, so the caller folds the
 * authoritative values back instead of trusting the optimistic click.
 */
export function submitChatMessageFeedback(
  messageId: number,
  feedback: 1 | -1 | 0,
  reason?: string | null,
): Promise<ChatMessageRowView> {
  return request<ChatMessageRowView>({
    url: `/ai/chat/messages/${messageId}/feedback`,
    method: 'post',
    data: { feedback, reason: reason ?? null },
  })
}

/**
 * One-shot confirmation ticket of a dialog draft ( stage 3b ,
 * generalized in stage 1; mirrors DraftTicketView): the countdown is the
 * TTL the server granted, the token is consumed by exactly one apply call
 * (replay = refusal 40001).
 */
export interface RepairDraftTicket {
  token: string
  expiresInSeconds: number
}

/**
 * Receipt of an applied dialog draft (mirrors DraftApplyReceiptView, the
 * generic receipt of core DraftApplyResult after stage 1): the
 * object-level answer the card echoes back — which entity, at which version
 * (R22: the success line names its object, never a bare "done"). The repair
 * card projects targetId/appliedVersion onto its case/version vocabulary.
 */
export interface DraftApplyReceipt {
  draftId: number
  objectType: string
  targetId: number | null
  appliedVersion: number | null
}

/**
 * BG batch single-source landing broadcast. Every dialog draft write
 * that lands (or rolls back) through these doors announces the changed
 * object ONCE, here — pages subscribe to chatSyncBus and re-read silently;
 * no page, card, or door may copy the announcement (R40, BG iron rule 1).
 * The kind value rides the receipt the server returned, so a newly
 * registered write kind is covered the moment the door returns it; only the
 * rollback door, whose receipt carries no kind field, declares one — and the
 * coverage gate (tests/ui/chatSyncCoverageGate) keeps that declaration
 * inside the core DraftObjectType roster. requestDraftConfirmTicket is the
 * FIRST click and writes nothing, so it never broadcasts.
 */
function broadcastDraftSync(
  receipt: { objectType?: string; targetId?: number | null },
  projectId: number | null = null,
  declaredKind?: string,
): void {
  const objectType = receipt.objectType ?? declaredKind
  if (!objectType) return
  emitObjectSync({ objectType, projectId, objectId: receipt.targetId ?? null })
}

/**
 * First click of the confirmation flow: opens the door for one pending draft
 * (audited core-side, writes no script). Identity is pinned server-side from
 * the login context — the body carries nothing.
 */
export function requestDraftConfirmTicket(draftId: number): Promise<RepairDraftTicket> {
  return request<RepairDraftTicket>({ url: `/ai/chat/drafts/${draftId}/confirm-token`, method: 'post' })
}

/**
 * Re-tick which candidate rows a pending login-state card authorises, BEFORE
 * the confirm ticket is asked for ( piece D3; PATCH
 * /ai/chat/drafts/{id}/checked-subset). Names only — the values live in the
 * server-side snapshot and never round-trip through the browser. Core refuses
 * the whole edit when a name is not in the snapshot (the card surfaces the
 * server sentence, no ticket is issued), and an unchanged list writes nothing
 * back. The answer is the number of rows left ticked.
 */
export function updateDraftCheckedSubset(draftId: number, names: string[]): Promise<number> {
  return request<number>({ url: `/ai/chat/drafts/${draftId}/checked-subset`, method: 'patch', data: { names } })
}

/**
 * Second click: applies the confirmed draft with its one-shot ticket. The
 * ticket is the ONLY field sent — the case anchor and action JSON never
 * round-trip through the browser, so a forged request cannot aim the script
 * write at another case. Rejections surface as BizError codes (40001 no/used
 * ticket, 40301 foreign session, 40904 not pending, 40901 optimistic-lock
 * conflict) — the card maps each to its locale line, never rephrases silently.
 * A dual-payload requirement card additionally rides its
 * selected branch; every other kind leaves it unset and the door is unchanged.
 */
export function applyRepairDraft(
  draftId: number,
  confirmToken: string,
  selectedAction?: 'create' | 'update',
): Promise<DraftApplyReceipt> {
  return request<DraftApplyReceipt>({
    url: `/ai/chat/drafts/${draftId}/apply`,
    method: 'post',
    data: { confirmToken, selectedAction },
  }).then((receipt) => {
    broadcastDraftSync(receipt)
    return receipt
  })
}

/**
 * One line of the group-apply receipt (AM batch piece 3; mirrors the
 * admin bridge DTO DraftGroupApplyItemView field-for-field — no second shape).
 * `outcome` is the core value set: "landed" = written (the row carries its new
 * version), "stale-excluded" = the object moved under the proposal, so the
 * card was pulled out of the group with both versions and the
 * regenerate exit in `detail`, "not-ticked" = this card was left out of the
 * ticked subset — nothing was written and it stays pending for later. The
 * ticket belongs to the whole group; a rejected line never burns it.
 */
export interface DraftGroupReceiptItem {
  draftId: number
  objectType: string
  objectName: string
  targetId: number | null
  appliedVersion: number | null
  outcome: 'landed' | 'stale-excluded' | 'not-ticked'
  detail: string
}

/** Group-apply receipt (mirrors DraftGroupApplyReceiptView). */
export interface DraftGroupReceipt {
  messageId: number
  items: DraftGroupReceiptItem[]
}

/**
 * First click of the group door: one ticket over every still-pending draft
 * of this answer (core keys it on the message id, so ids from another answer
 * in the apply body are a shape error that leaves the ticket untouched).
 */
export function requestGroupDraftConfirmTicket(messageId: number): Promise<RepairDraftTicket> {
  return request<RepairDraftTicket>({
    url: `/ai/chat/drafts/group/${messageId}/confirm-token`,
    method: 'post',
  })
}

/**
 * Second click of the group door: applies the human-ticked subset in one
 * bodies never round-trip through the browser). The receipt answers line by
 * line; the write door itself is unchanged — nothing applies without these
 * two clicks.
 */
export function applyGroupDrafts(
  messageId: number,
  confirmToken: string,
  draftIds: number[],
): Promise<DraftGroupReceipt> {
  return request<DraftGroupReceipt>({
    url: `/ai/chat/drafts/group/${messageId}/apply`,
    method: 'post',
    data: { confirmToken, draftIds },
  }).then((receipt) => {
    // Only lines that actually wrote announce; a stale-excluded or
    // not-ticked row changed nothing, so the bus stays quiet for it.
    for (const item of receipt.items ?? []) {
      if (item.outcome === 'landed') broadcastDraftSync(item)
    }
    return receipt
  })
}

/**
 * One pending card of a system-origin group as the read door lists it (AN
 * batch piece D4c; mirrors admin PendingGroupCardView field-for-field —
 * no second shape). The pinned panel renders this list BEFORE any ticket
 * exists, so nothing on the view can authorise a write.
 */
export interface PendingGroupCard {
  draftId: number
  projectId: number
  objectType: string
  summary: string
  targetId: number | null
  baseVersion: number | null
  schemaVersion: string
  payloadJson: string
  status: number
}

/**
 * Pending cards of one system-origin group (admin GET
 * /api/v1/ai/chat/drafts/group/pending): what the linkage bus still owes the
 * viewer for one project + object kind. Pure read — the two scope doors below
 * stay the only write path, and an empty group reads as an empty list.
 */
export function fetchPendingGroupCards(
  projectId: number,
  objectType?: string,
): Promise<PendingGroupCard[]> {
  const kindQuery = objectType ? `&objectType=${objectType}` : ''
  return request<PendingGroupCard[]>({
    url: `/ai/chat/drafts/group/pending?projectId=${projectId}${kindQuery}`,
    method: 'get',
  })
}

/**
 * First click of the scope-shaped group door: one ticket over every still
 * pending card of one project + object kind (admin POST
 * /api/chat/drafts/group/confirm-token). The caller names the group by its two
 * segment coordinates instead of hand-building the group wire; group resolution,
 * the one-shot ticket and every row guard stay single-sourced in core.
 */
export function requestGroupScopeConfirmTicket(
  projectId: number,
  objectType: string,
): Promise<RepairDraftTicket> {
  return request<RepairDraftTicket>({
    url: '/ai/chat/drafts/group/confirm-token',
    method: 'post',
    data: { projectId, objectType },
  })
}

/**
 * Second click of the scope-shaped group door: the human-ticked subset applies
 * in one core-side transaction (admin POST /ai/chat/drafts/group/apply). Same
 * discipline as the answer-shaped door — only the group coordinates, the
 * ticket and the ticked ids ride the wire, so a forged request cannot aim any
 * write. The receipt is the shared group answer, line by line.
 */
export function applyGroupScopeDrafts(
  projectId: number,
  objectType: string,
  confirmToken: string,
  draftIds: number[],
): Promise<DraftGroupReceipt> {
  return request<DraftGroupReceipt>({
    url: '/ai/chat/drafts/group/apply',
    method: 'post',
    data: { projectId, objectType, confirmToken, draftIds },
  }).then((receipt) => {
    for (const item of receipt.items ?? []) {
      if (item.outcome === 'landed') broadcastDraftSync(item, projectId)
    }
    return receipt
  })
}

export interface EvolutionRollbackReceipt {
  id: number
  name: string
  domain: string
  version: number
  status: number
  createdBy: number
  rollbackTo: number
  createdAt: string
}

/**
 * One-click rollback of a landed self-evolution proposal (AF-i phase 5):
 * undoes the prompt version the card landed as by copying its predecessor
 * forward through the prompt domain's own version chain. Thin by design —
 * every guard (card kind, applied state, session ownership, once-only mark,
 * family chain) is single-sourced in core; the frontend never pre-judges, so
 * a tool/grounding card shows the server's refusal sentence naming where the
 * object is managed instead. Identity is pinned from the login context.
 */
export function rollbackEvolutionProposal(draftId: number): Promise<EvolutionRollbackReceipt> {
  return request<EvolutionRollbackReceipt>({
    url: `/ai/chat/evolution/proposals/${draftId}/rollback`,
    method: 'post',
  }).then((receipt) => {
    // Rollback undoes a landed EVOLUTION_PROPOSAL draft; its receipt is the
    // prompt-template row (no kind field), so the door declares the kind —
    // the coverage gate keeps declared kinds inside the core enum roster.
    broadcastDraftSync({ targetId: receipt.id }, null, 'EVOLUTION_PROPOSAL')
    return receipt
  })
}

/**
 * Sealed conversation history of one session (page reload): oldest first,
 * streamed answers already rebuilt server-side from the delta frames. The
 * core door re-checks ownership per user (AF-g) — a foreign session refuses
 * with the server's human sentence, never an empty list pretending success.
 */
export function getChatHistory(sessionId: number): Promise<ChatMessageRowView[]> {
  return request<ChatMessageRowView[]>({ url: `/ai/chat/sessions/${sessionId}/messages`, method: 'get' })
}

/**
 * Reload-resume signal: the one still-unsealed streaming turn of a session,
 * or an honest null when every turn is sealed. The sealed history door
 * deliberately leaves the streaming turn out (double-render guard), so a
 * page that came back mid-answer needs this second read to learn a turn is
 * running — it then restores the resume door and replays through the same
 * SSE path (no second fold semantic anywhere).
 */
export function getChatActiveTurn(sessionId: number): Promise<ChatMessageRowView | null> {
  return request<ChatMessageRowView | null>({ url: `/ai/chat/sessions/${sessionId}/active-message`, method: 'get' })
}

/**
 * Session export + knowledge-sediment doors ( stages 2/2b admin API,
 * consumed by the thread action row). Both are read-only for the dialog
 * itself — export writes nothing, sediment is the ONE user-confirmed path
 * into the knowledge base. Every refusal (foreign session 40301, dialog
 * without Q&A yet 40001, same-minute re-save 40904) comes back from core as
 * a human sentence; the client never re-judges or re-writes it.
 */

/** One Markdown export (mirrors ChatExportMirrorView). */
export interface ChatExportResult {
  sessionId: number
  title: string
  markdown: string
  filename: string
  turnCount: number
}

/** One sediment receipt (mirrors ChatSedimentMirrorView). */
export interface ChatSedimentResult {
  sessionId: number
  documentId: number
  documentName: string
  chunkCount: number
}

export function exportChatSession(sessionId: number): Promise<ChatExportResult> {
  return request<ChatExportResult>({
    url: `/ai/chat/sessions/${sessionId}/export`,
    method: 'get',
  })
}

export function sedimentChatSession(sessionId: number): Promise<ChatSedimentResult> {
  return request<ChatSedimentResult>({
    url: `/ai/chat/sessions/${sessionId}/sediment`,
    method: 'post',
  })
}

/**
 * One-key undo of the session's last accepted case write ,
 * mirror of POST /api/v1/ai/chat/sessions/{id}/undo). The answer is the id of
 * a NEW pending rollback card anchored on the newest sealed answer — nothing
 * rolls back here: the human confirmation door on that card stays the only
 * writer (red line of the batch). Every refusal (no accepted write yet,
 * foreign session) is worded core-side and shown verbatim.
 */
export function undoChatSession(sessionId: number): Promise<number> {
  return request<number>({
    url: `/ai/chat/sessions/${sessionId}/undo`,
    method: 'post',
  })
}

// ---- context waterline ----

/**
 * Mirror of the admin waterline door (GET /api/v1/ai/chat/sessions/{id}/
 * context-waterline, : every number is computed server-side from
 * the sealed ledger plus the K8 config — the panel only renders them.
 * An unlimited budget arrives as budgetUnlimited=true with a zero cap,
 * never as an exhausted meter.
 */
export interface ChatContextWaterlineView {
  promptTokensUsed: number
  budgetTokens: number
  budgetUnlimited: boolean
  sealedUserTurns: number
  keepRecentTurns: number
  foldedTurns: number
  pinnedFactsCount: number
}

export function fetchContextWaterline(sessionId: number): Promise<ChatContextWaterlineView> {
  return request<ChatContextWaterlineView>({
    url: `/ai/chat/sessions/${sessionId}/context-waterline`,
    method: 'get',
  })
}

// ---- dialog snapshot shares ----

/** One share row as its owner sees it (mirrors ChatShareMirrorView).
 *  shareToken is the public credential of the frozen copy — it renders only
 *  inside the owner's share panel, never on any other surface. */
export interface ChatShareInfo {
  id: number
  sessionId: number
  shareToken: string
  title: string
  turnCount: number
  expiresAt: string
  revoked: number
  createdAt: string
}

/** The frozen copy one public link serves (mirrors ChatShareSnapshotView).
 *  Deliberately id-free: the unauthenticated door never carries session or
 *  owner identifiers through. */
export interface ChatShareSnapshot {
  title: string
  markdown: string
  turnCount: number
  expiresAt: string
}

/** Freeze the session into a read-only share (owner door, admin path).
 *  Core clamps ttlHours into its window; the client forwards the choice. */
export function createChatShare(sessionId: number, ttlHours: number): Promise<ChatShareInfo> {
  return request<ChatShareInfo>({
    url: `/ai/chat/sessions/${sessionId}/share`,
    method: 'post',
    params: { ttlHours },
  })
}

/** Live shares of one owned session, newest first (revoked rows visible). */
export function listChatShares(sessionId: number): Promise<ChatShareInfo[]> {
  return request<ChatShareInfo[]>({
    url: `/ai/chat/sessions/${sessionId}/shares`,
    method: 'get',
  })
}

/** Kill one share link (idempotent; the audit row stays hidden-but-kept). */
export function revokeChatShare(shareId: number): Promise<ChatShareInfo> {
  return request<ChatShareInfo>({
    url: `/ai/chat/shares/${shareId}`,
    method: 'delete',
  })
}

/**
 * Read the frozen snapshot through the PUBLIC api door (8082, no login).
 * A dedicated bare instance — NOT the shared request(): that one injects
 * the JWT header and bounces 401/403 to the login page, and a link holder
 * has no session to lose. Shape note (real-response pinned): the public
 * door answers the view RAW (ResponseEntity body, no {code,msg,data}
 * envelope — ChatSharePublicController), while refusals arrive as the
 * GlobalExceptionHandler's Response envelope with an HTTP 400/404 status;
 * both are unwrapped here, and the caller shows core's msg verbatim.
 */
const publicHttp = axios.create({ xsrfCookieName: '', xsrfHeaderName: '', timeout: 15000 })

export async function fetchChatShareSnapshot(shareToken: string): Promise<ChatShareSnapshot> {
  try {
    const resp = await publicHttp.get<ChatShareSnapshot | ApiResponse<never>>(
      `/open/chat-share/${shareToken}`)
    const d = resp.data
    if (d && typeof d === 'object' && 'markdown' in d) return d as ChatShareSnapshot
    // Defensive: an enveloped success would answer through data (never the
    // current shape, but do not silently treat it as dead content).
    throw new BizError('0', '')
  } catch (e) {
    // HTTP-mapped refusals (400/404) carry the core sentence in the body;
    // when the body has none, the message stays empty and the VIEW supplies
    // the locale wording (zero hard-coded text in the api layer).
    const data = (e as { response?: { data?: ApiResponse<never> } }).response?.data
    if (data && typeof data === 'object' && 'code' in data) {
      throw new BizError(data.code, data.msg ?? '')
    }
    throw e instanceof BizError ? e : new BizError('0', '')
  }
}

/**
 * Session-history doors ( admin surface, consumed by the session
 * library of ). Everything pages and filters SERVER-side — the browser
 * never accumulates the whole ledger (S-group worst-case scale).
 */

/** One history row (mirrors ChatSessionListItemView). */
export interface ChatSessionListItem {
  id: number
  projectId: number
  title: string
  source: number
  /** 0 active / 1 archived — the core default face returns active only. */
  status: number
  updatedAt: string
  messageCount: number
}

/** One page of history rows (mirrors ChatSessionPageMirrorView). */
export interface ChatSessionPage {
  list: ChatSessionListItem[]
  total: number
  page: number
  pageSize: number
}

/**
 * Page/search the caller's own sessions (scope = login user core-side; the
 * project segment is intersected with the visible domain server-side). An
 * archived view MUST pass status explicitly: without it the door answers the
 * active face only (ChatSessionServiceImpl semantics, pinned by L3).
 */
export function listChatSessions(params: {
  projectIds?: number[]
  status?: number
  source?: number
  keyword?: string
  page?: number
  pageSize?: number
}): Promise<ChatSessionPage> {
  return request<ChatSessionPage>({ url: '/ai/chat/sessions', method: 'get', params })
}

/** Rename one owned session (empty titles are refused server-side). */
export function renameChatSession(sessionId: number, title: string): Promise<ChatSession> {
  return request<ChatSession>({ url: `/ai/chat/sessions/${sessionId}`, method: 'patch', data: { title } })
}

/** Archive one owned session: history stays, the thread parks; idempotent. */
export function archiveChatSession(sessionId: number): Promise<ChatSession> {
  return request<ChatSession>({ url: `/ai/chat/sessions/${sessionId}/archive`, method: 'post' })
}

/**
 * Date-group bucket boundaries (hot key ai.chat.session-group-days, parsed
 * single-sourced admin-side — a broken value already answers the default, so
 * the list never invents its own buckets and never hardcodes [1,7,30]).
 */
export function getChatSessionGroupDays(): Promise<{ buckets: number[] }> {
  return request<{ buckets: number[] }>({ url: '/ai/chat/sessions/group-days', method: 'get' })
}

/** Still-open goal-plan tail projected onto the newest assistant row of the
 * sealed history door ; mirrors core ChatGoalPlanInfo /
 * admin ChatGoalPlanView — ledger fields only, one shape for both doors). */
export interface ChatGoalPlanRowView {
  planVersion: number
  revision: number
  openCount: number
  openTitles: string[]
  /** Each open title's own stored state, parallel to openTitles
 * ( residual face: a blocked step must reload as blocked, not pending;
 * absent on an older server — every open row then keeps pending). */
  openStates?: string[] | null
  openTailSentence: string
}

/** Ledger row shape mirrored from the admin ChatMessageView (folding lives in
 *  chatHistoryMapper — the api layer ships data, never display logic). */
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
  /** Confirmable draft cards landed on this message (mirrors ChatDraftView). */
  drafts?: Array<{
    id: number
    objectType: string
    summary: string | null
    payloadJson: string
    status: number
    targetId: number | null
    appliedVersion: number | null
    rolledBack: boolean
    baseVersion: number | null
  }>
  /** Still-open goal-plan tail projected onto the newest assistant row
 * (mirrors ChatGoalPlanView, ; null elsewhere). */
  goalPlan?: ChatGoalPlanRowView | null
  /** Turn booked a credential screenshot whose original pixels were purged
 * (mirrors ChatMessageView.credLedger, piece E). */
  credLedger?: boolean
}
