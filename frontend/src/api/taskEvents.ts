/**
 * Tail client for AI long-task process events .
 *
 * The main road is the document's SSE door
 * (GET /api/v1/ai/documents/{id}/extraction-events/stream): EventSource
 * cannot carry the login token, so the stream rides fetch + ReadableStream
 * exactly like the dialog stream door and reuses the one shared frame
 * decoder (no second parsing copy, R40). When the wire drops, the client
 * degrades to the plain cursor-window GET (poll fallback) and reopens from
 * the last cursor it saw — seen frames ∪ tail-cursor continuation, never a
 * rollback to 0, nothing skipped, nothing replayed twice.
 *
 * Frame shape on the wire (admin ExtractionEventStreamBridge): `event:` is
 * the level (step / thinking / error), `id:` is the cursor, `data:` is the
 * human summary and the optional `:meta` comment carries the machine detail.
 * SSE frames carry no server timestamp, so a tailed row is stamped with the
 * local observation time; poll windows bring the server landedAt as-is.
 */
import { getToken, request, ensureFreshAccessToken, renewAccessTokenOnce } from './request'
import { createSseFrameDecoder } from './sseFrameParser'

/** One process step as shown in the tail drawer. */
export interface ExtractionEvent {
  /** Strictly increasing row id (the replay cursor). */
  cursor: number
  /** Viewer filter domain: thinking / step / error. */
  level: string
  /** Human-readable one-line summary (server sentence, shown as-is). */
  summary: string
  /** Machine-readable part: token counts, stage codes, failure codes. */
  detail: string | null
  /** ISO local timestamp; '' when only the arrival moment is known. */
  landedAt: string
  /**
 * Aggregate-ledger tag (multi-source tails only, P4): when one drawer
 * merges the ledgers of several tasks, cursors are only unique per source,
 * so the merge key is scope+cursor — without the tag, two sources' row 1
 * would erase each other. Single-door tails leave it undefined.
 */
  scope?: string
}

/**
 * Render a machine-readable detail payload as readable text for the tail
 * drawer. SSE `:meta` frames carry raw JSON; printing it verbatim shows a
 * wall of braces and quotes to the viewer (vision review D1, 11-12). JSON
 * objects/arrays are flattened recursively into `path=value` pairs joined
 * with the fullwidth bar; anything that is not JSON is returned unchanged.
 * The original text still rides the row tooltip (title), so no information
 * is lost — only the wall-of-braces presentation is fixed.
 */
export function formatDetailForDisplay(detail: string | null): string {
  if (!detail) return ''
  const trimmed = detail.trim()
  if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) return detail
  let parsed: unknown
  try {
    parsed = JSON.parse(trimmed)
  } catch {
    return detail
  }
  const parts: string[] = []
  collectLeafPairs(parsed, '', parts)
  return parts.join('｜')
}

function collectLeafPairs(value: unknown, prefix: string, out: string[]): void {
  if (Array.isArray(value)) {
    value.forEach((item, idx) => collectLeafPairs(item, prefix ? `${prefix}.${idx}` : `${idx}`, out))
  } else if (value !== null && typeof value === 'object') {
    Object.entries(value as Record<string, unknown>).forEach(([key, item]) =>
      collectLeafPairs(item, prefix ? `${prefix}.${key}` : key, out))
  } else {
    out.push(`${prefix}=${value === null || value === undefined ? '' : String(value)}`)
  }
}

/** Poll cadence of the degradation road (ms). Hot-tunable per deployment. */
export const TAIL_POLL_INTERVAL_MS = 2000
/** After this many degraded polls the client re-attempts the SSE road once. */
export const TAIL_SSE_RETRY_EVERY_POLLS = 5
/** Worst-case scale cap: the drawer keeps only the newest rows on screen. */
export const TAIL_MAX_EVENTS = 500

/**
 * Prefix of the honest gap-declaration rows : a level=step row
 * per declared missing-resource category, emitted by the generation service.
 * The literal itself is a wire contract, not display copy — it lives in
 * utils/serverTextWire (outside the P7e display sweep); this module owns the
 * parse helper and re-exports the key for existing consumers.
 */
export { RESOURCE_GAP_PREFIX } from '@/utils/serverTextWire'
import { RESOURCE_GAP_PREFIX } from '@/utils/serverTextWire'

/** One declared gap, ready for the chip row. */
export interface ResourceGapItem {
  cursor: number
  /** Display text as recorded on the ledger: "category" or "category——intent". */
  text: string
}

/** Pure selector: declaration rows out of a tail window, cursor-ascending.
 * Non-declaration rows and other levels never count (soft signal, additive). */
export function extractResourceGaps(events: ExtractionEvent[]): ResourceGapItem[] {
  return events
    .filter(e => e.level === 'step' && e.summary.startsWith(RESOURCE_GAP_PREFIX))
    .map(e => ({ cursor: e.cursor, text: e.summary.slice(RESOURCE_GAP_PREFIX.length) }))
    .sort((a, b) => a.cursor - b.cursor)
}

/** Cursor window GET of one document's extraction (admin relay door).
 *  A non-empty runToken replays that historical run (revisit door); an
 *  absent token keeps the standing latest-run window unchanged. */
export async function fetchExtractionEvents(
  documentId: number,
  cursor: number,
  limit?: number,
  runToken?: string,
): Promise<ExtractionEvent[]> {
  return fetchEventWindow(`/ai/documents/${documentId}/extraction-events`, cursor, limit,
    runToken ? { runToken } : undefined)
}

/**
 * Cursor window GET of one step-generation task (admin outward door,
 * GET /api/v1/ai/tasks/{taskId}/process-events). Same row shape as the
 * extraction door — one decoder, one merge rule, no second copy (R40).
 */
export async function fetchTaskProcessEvents(
  taskId: number,
  cursor: number,
  limit?: number,
): Promise<ExtractionEvent[]> {
  return fetchEventWindow(`/ai/tasks/${taskId}/process-events`, cursor, limit)
}

/**
 * Cursor window GET of one document's channel-B spec extraction (admin relay
 * door GET /api/v1/ai/documents/{id}/spec-extraction-events, C-face).
 * Same row shape as every other process door — one shared fetchEventWindow,
 * no second decoder copy (R40).
 */
export async function fetchSpecExtractionEvents(
  documentId: number,
  cursor: number,
  limit?: number,
  runToken?: string,
): Promise<ExtractionEvent[]> {
  return fetchEventWindow(`/ai/documents/${documentId}/spec-extraction-events`, cursor, limit,
    runToken ? { runToken } : undefined)
}

/** Shared cursor-window GET behind every process-event door. */
async function fetchEventWindow(
  path: string,
  cursor: number,
  limit?: number,
  extra?: Record<string, string>,
): Promise<ExtractionEvent[]> {
  const rows = await request<ExtractionEvent[]>({
    url: path,
    method: 'get',
    params: {
      cursor,
      ...(limit === undefined ? {} : { limit }),
      ...(extra ?? {}),
    },
  })
  return Array.isArray(rows) ? rows : []
}

/**
 * Merge newly seen events into the ledger: dedupe by (scope, cursor) — the
 * plain cursor stays unique for single-door tails, while an aggregated
 * multi-task ledger must never collide two sources on their own row numbers
 * — keep ascending order, cap at the newest TAIL_MAX_EVENTS rows. Pure so
 * the spectra and the stream client share one merge point (never two copies
 * of the rule).
 */
export function mergeEvents(
  seen: ExtractionEvent[],
  incoming: ExtractionEvent[],
): ExtractionEvent[] {
  const byKey = new Map<string, ExtractionEvent>()
  for (const row of seen) byKey.set(mergeKey(row), row)
  for (const row of incoming) {
    if (!byKey.has(mergeKey(row))) byKey.set(mergeKey(row), row)
  }
  const merged = [...byKey.values()].sort((a, b) => a.cursor - b.cursor)
  return merged.length > TAIL_MAX_EVENTS ? merged.slice(-TAIL_MAX_EVENTS) : merged
}

function mergeKey(row: ExtractionEvent): string {
  return `${row.scope ?? ''}:${row.cursor}`
}

/** Handle over one live tail; close() is idempotent and ends both roads. */
export interface TailHandle {
  close(): void
  /** Last cursor the client actually saw (replay anchor for reconnects). */
  cursor(): number
}

/**
 * Open the live tail of one document extraction. onEvents fires with each
 * freshly merged batch (SSE frame or one poll window). The handle never
 * throws at the caller: a dead wire silently becomes the poll road.
 */
export function openExtractionEventTail(
  documentId: number,
  startCursor: number,
  onEvents: (batch: ExtractionEvent[]) => void,
): TailHandle {
  return openEventTail(
    cursor => fetchExtractionEvents(documentId, cursor),
    `/api/v1/ai/documents/${documentId}/extraction-events/stream`,
    startCursor,
    onEvents,
  )
}

/**
 * Open the live tail of one step-generation task over the same shared
 * machinery (no second pump copy, R40): SSE road first, poll road on a
 * dead wire, forward-only cursor, sealed stream keeps polling alive.
 */
/** Split the pump's meta side channel into display detail + true landing time. */
function unwrapLedgerMeta(meta: string | null | undefined): { detail: string | null; landedAt: string } {
  if (meta === null || meta === undefined) return { detail: null, landedAt: '' }
  if (meta.startsWith('{"tpTS"')) {
    try {
      const parsed = JSON.parse(meta) as { tpTS?: string; d?: string }
      return { detail: parsed.d ?? null, landedAt: parsed.tpTS ?? '' }
    } catch {
      return { detail: meta, landedAt: '' }
    }
  }
  return { detail: meta, landedAt: '' }
}

export function openTaskProcessEventTail(
  taskId: number,
  startCursor: number,
  onEvents: (batch: ExtractionEvent[]) => void,
): TailHandle {
  return openEventTail(
    cursor => fetchTaskProcessEvents(taskId, cursor),
    `/api/v1/ai/tasks/${taskId}/process-events/stream`,
    startCursor,
    onEvents,
  )
}

/** The one live-tail pump: cursor-window fallback + SSE stream road. */
function openEventTail(
  fetchWindow: (cursor: number) => Promise<ExtractionEvent[]>,
  streamUrl: string,
  startCursor: number,
  onEvents: (batch: ExtractionEvent[]) => void,
): TailHandle {
  const controller = new AbortController()
  let lastCursor = startCursor
  let closed = false
  let pollTimer: ReturnType<typeof setTimeout> | null = null
  let degradedPolls = 0

  const emit = (rows: ExtractionEvent[]): void => {
    const fresh = rows.filter(row => row.cursor > lastCursor)
    if (fresh.length === 0) return
    lastCursor = Math.max(...fresh.map(row => row.cursor))
    onEvents(fresh)
  }

  const schedulePoll = (delayMs: number): void => {
    if (closed || pollTimer !== null) return
    pollTimer = setTimeout(() => {
      pollTimer = null
      if (closed) return
      void pollOnce()
    }, delayMs)
  }

  async function pollOnce(): Promise<void> {
    try {
      const rows = await fetchWindow(lastCursor)
      emit(rows)
      degradedPolls = 0
    } catch {
      // The task-state poll in the host dialog keeps its own cadence; a
      // failed event window is retried on the next tick, never silent-fails
      // into a claim of completion.
      degradedPolls += 1
    }
    if (closed) return
    if (degradedPolls > 0 && degradedPolls % TAIL_SSE_RETRY_EVERY_POLLS === 0) {
      void runStream()
      return
    }
    schedulePoll(TAIL_POLL_INTERVAL_MS)
  }

  async function openDoor(authToken: string): Promise<Response | null> {
    try {
      return await fetch(
        `${streamUrl}?cursor=${lastCursor}`,
        { headers: { Authorization: `Bearer ${authToken}` }, signal: controller.signal },
      )
    } catch {
      return null
    }
  }

  async function runStream(): Promise<void> {
    if (closed) return
    // Renew BEFORE opening: this door is a bare fetch and physically bypasses
    // the axios interceptor's 401 -> single-flight refresh -> replay road, so
    // a session older than the access TTL would otherwise answer every stream
    // (re)open with a user-visible 401 (WT window r19, journey B at 1938s).
    // A 'transient' renewal verdict (: churn swallowed the refresh,
    // no server judgement) must NOT knock on the door with the stale token —
    // that is how a storm burst put 8x401 on the wire. The door stays shut
    // and the cursor-window poll cadence owns the retry.
    let response: Response | null
    try {
      response = await openDoor(await ensureFreshAccessToken())
    } catch {
      if (!closed) schedulePoll(TAIL_POLL_INTERVAL_MS)
      return
    }
    if (response === null) {
      if (!closed) schedulePoll(TAIL_POLL_INTERVAL_MS)
      return
    }
    if (response.status === 401 && !closed) {
      // Safety net for a token that expired un-readably early (revocation,
      // clock skew): renew once, reopen exactly once — never a retry loop.
      // 'transient' is not success: no stale-token reopen (=== true only).
      if ((await renewAccessTokenOnce()) === true) {
        response = await openDoor(getToken())
        if (response === null) {
          if (!closed) schedulePoll(TAIL_POLL_INTERVAL_MS)
          return
        }
      }
    }
    if (!response.ok || !response.body) {
      if (!closed) schedulePoll(TAIL_POLL_INTERVAL_MS)
      return
    }
    const decoder = createSseFrameDecoder()
    const reader = response.body.getReader()
    const text = new TextDecoder()
    const cancelReader = () => {
      void reader.cancel()
    }
    controller.signal.addEventListener('abort', cancelReader)
    try {
      for (;;) {
        let chunk
        try {
          chunk = await reader.read()
        } catch {
          break
        }
        if (chunk.done) break
        for (const frame of decoder.push(text.decode(chunk.value, { stream: true }))) {
          if (frame.id === null) {
            // No replay anchor on the frame: fabricating a cursor here would
            // park the row under a made-up number, and the poll window (which
            // carries the TRUE ledger ids) would then re-append the same event
            // under its real cursor — the same error shown twice. An
            // id-less frame instead triggers one prompt poll of the true
            // window; the ledger stays the single source of row numbers.
            schedulePoll(0)
            continue
          }
          const cursor = frame.id
          if (cursor <= lastCursor) continue
          // Ledger meta side channel (user report 2026-11-20): when the pump
          // knows the true landing time it rides {tpTS, d} JSON; a plain meta
          // string is the legacy detail payload. Without the envelope the row
          // would be stamped with the ARRIVAL clock, so a reconnect replay of
          // a finished run showed one identical timestamp for every row.
          const meta = unwrapLedgerMeta(frame.meta)
          emit([{
            cursor,
            level: frame.event,
            summary: frame.data,
            detail: meta.detail,
            landedAt: meta.landedAt || localStampNow(),
          }])
        }
      }
    } finally {
      controller.signal.removeEventListener('abort', cancelReader)
    }
    // The stream sealed (document settled or wire broke): keep the poll road
    // alive so a reconnect gap never shows a frozen drawer.
    if (!closed) schedulePoll(TAIL_POLL_INTERVAL_MS)
  }

  void runStream()

  return {
    close(): void {
      closed = true
      if (pollTimer !== null) {
        clearTimeout(pollTimer)
        pollTimer = null
      }
      controller.abort()
    },
    cursor(): number {
      return lastCursor
    },
  }
}

/** Local wall clock in the same 'YYYY-MM-DDTHH:MM:SS' shape the server sends. */
function localStampNow(): string {
  const d = new Date()
  const pad = (n: number): string => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

// ---------------------------------------------------------------------------
// Gated read doors over one exchange trail , backend
// core ExchangeTrailQuery behind the admin session-state / credential bridges)
// ---------------------------------------------------------------------------

// Trail wire-contract markers (mask fingerprint / section heads / over-budget
// pointer): the literals and their ProbeExchangeRunner emitter pairing live in
// utils/serverTextWire outside the P7e display sweep — re-exported here so the
// shared tail drawer and any future consumer read one parse source.
export {
  TRAIL_MASK_MARK,
  TRAIL_REQUEST_SECTION_MARK,
  TRAIL_RESPONSE_SECTION_MARK,
  TRAIL_BODY_SECTION_MARK,
  TRAIL_FULL_POINTER_MARK,
} from '@/utils/serverTextWire'

/** Plaintext answer of the reveal door: one entry per stored line, trail order. */
export interface TrailRevealAnswer {
  field: string
  values: string[]
}

/** One page of the unclipped trail behind the pointer line. */
export interface TrailFullTextPage {
  text: string
  from: number
  totalChars: number
  hasMore: boolean
}

/**
 * The two audited read doors of one trail host (eventId = the row's cursor,
 * i.e. the ledger row id). Only the login-state probe and the credential
 * test have these doors; extraction / generation tails pass no door object
 * and the drawer keeps the legacy masked-only presentation there.
 */
export interface TrailDoorApi {
  reveal(eventId: number, field: string): Promise<TrailRevealAnswer>
  fullText(eventId: number, from: number): Promise<TrailFullTextPage>
}

/** One builder for both doors — the URL pair must never drift apart (R40). */
function trailDoorsFor(base: string): TrailDoorApi {
  return {
    reveal(eventId, field) {
      return request<TrailRevealAnswer>({
        url: `${base}/${eventId}/reveal`, method: 'get', params: { field },
      })
    },
    fullText(eventId, from) {
      return request<TrailFullTextPage>({
        url: `${base}/${eventId}/full`, method: 'get', params: { from },
      })
    },
  }
}

/** Trail doors of one login state's probe trail. */
export function probeTrailDoors(stateId: number): TrailDoorApi {
  return trailDoorsFor(`/session-states/${stateId}/probe-events`)
}

/** Trail doors of one login state's keep-alive trail (same door grammar). */
export function keepaliveTrailDoors(stateId: number): TrailDoorApi {
  return trailDoorsFor(`/session-states/${stateId}/keepalive-events`)
}

/** Trail doors of one credential's test trail. */
export function credentialTrailDoors(credentialId: number): TrailDoorApi {
  return trailDoorsFor(`/appauto/credentials/${credentialId}/test-events`)
}

/**
 * One aggregated run row of the process event ledger (R67 revisit entry):
 * newest run first, error frames rolled up into hasError. Same shape as the
 * admin bridges' shared run view — the aggregation lives once on the core
 * read door, no page re-derives it (R40).
 */
export interface AiTaskRunRow {
  runToken: string
  eventCount: number
  hasError: boolean
  firstAt: string
  lastAt: string
}

/** Run history of one login state's probe trail (newest first). */
export function listProbeRuns(stateId: number): Promise<AiTaskRunRow[]> {
  return request<AiTaskRunRow[]>({ url: `/session-states/${stateId}/probe-runs`, method: 'get' })
}

/** Run history of one login state's keep-alive trail (newest first). */
export function listKeepaliveRuns(stateId: number): Promise<AiTaskRunRow[]> {
  return request<AiTaskRunRow[]>({ url: `/session-states/${stateId}/keepalive-runs`, method: 'get' })
}

/** Run history of one credential's test trail (newest first). */
export function listCredentialTestRuns(credentialId: number): Promise<AiTaskRunRow[]> {
  return request<AiTaskRunRow[]>({ url: `/appauto/credentials/${credentialId}/test-runs`, method: 'get' })
}

/** Run history of one document's extraction process ledger (newest first,
 *  same shared row shape as every other run door — no second view type). */
export function fetchDocumentExtractionRuns(documentId: number): Promise<AiTaskRunRow[]> {
  return request<AiTaskRunRow[]>({ url: `/ai/documents/${documentId}/extraction-runs`, method: 'get' })
}

/** Run history of one document's channel-B spec extraction ledger
 *  (R67 revisit door: the import list's row-level entry rides this). */
export function fetchDocumentSpecExtractionRuns(documentId: number): Promise<AiTaskRunRow[]> {
  return request<AiTaskRunRow[]>({ url: `/ai/documents/${documentId}/spec-extraction-runs`, method: 'get' })
}
