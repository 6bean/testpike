/**
 * Shared tail runner for the "test now" doors (login-state probe and
 * credential test) and the asynchronous survey tail . Both synchronous
 * doors run server-side, so the trail is usually complete at the first fetch;
 * the short safety poll covers the tail rows landing a beat later without
 * pinning the UI to one snapshot.
 *
 * segment d (R62) adds three OPT-IN window knobs for asynchronous doors —
 * a memory tail-cap trim, a heartbeat staleness signal and an interrupted
 * marker. segment e adds the terminal dwell: the drawer holds the end-state
 * frame on screen for `terminalDwellMs` before the auto-collapse. Every
 * default reproduces the original synchronous-door behaviour exactly: the
 * login-state and credential doors keep zero drift without passing anything
 * (contract-tested in taskTailWindowing.test.ts).
 */
import { ref } from 'vue'

export interface ProbeOutcome {
  runToken: string
  /** verdict pair is only consumed by the legacy synchronous-door
 * heuristic; asynchronous doors (survey tail, ) leave it out */
  verdict?: string
  verdictHuman?: string
}

/**
 * Tail-window tuning knobs . Defaults reproduce the
 * original synchronous-door behaviour exactly — the login-state and
 * credential doors keep zero drift without passing anything.
 */
export interface TestRunTailOptions {
  /**
   * Terminal-frame predicate for asynchronous runs: when given, it fully
   * replaces the legacy verdict-suffix heuristic and the poll stops on the batch
   * that contains the terminal frame (no extra drain fetch — the terminal
   * frame is by contract the last row of the stream).
   */
  terminal?: (row: TestRunEvent) => boolean
  /** consecutive-empty-poll grace before the stream is treated as over */
  emptyGracePolls?: number
  /** hard poll budget, keeps a crashed run from pinning the drawer open */
  maxPolls?: number
  pollIntervalMs?: number
  /** auto-collapse the drawer once the terminal frame is replayed */
  closeOnTerminal?: boolean
  /**
 * segment e (R62): milliseconds the drawer stays open AFTER the terminal
 * frame was appended, so the closing reason actually renders and is readable
 * before the auto-collapse. Without it the terminal frame and the v-if
 * removal land in the same tick — the user (and any DOM observer) never
 * sees the end state. Default 0 keeps the legacy collapse behaviour.
 */
  terminalDwellMs?: number | (() => number)
  /**
   * Memory retention cap for the event buffer (R62 tail-cap): the oldest
   * frames drop once the window is over budget, newest frames are kept.
   * Accepts a getter so a hot-updated config value is read per batch.
   */
  cap?: number | (() => number)
  /**
   * Silence beyond this many ms flips `stalled` (the heartbeat door that
   * proves the stream is still alive instead of a silently empty window).
   */
  heartbeatMs?: number | (() => number)
}

/** Mirrors admin TaskEventView; cursor = ledger row id (replay cursor). */
export interface TestRunEvent {
  cursor: number
  level: string
  summary: string
  detail: string | null
  landedAt: string
}

const POLL_INTERVAL_MS = 500
const MAX_EMPTY_POLLS = 4
const MAX_POLLS = 200

export function useTestRunTail(
  fetchEvents: (runToken: string, cursor: number) => Promise<TestRunEvent[]>,
  options: TestRunTailOptions = {},
) {
  const events = ref<TestRunEvent[]>([])
  const running = ref(false)
  const drawerOpen = ref(false)
  const lastOutcome = ref<ProbeOutcome | null>(null)
  /** true while the stream is silent beyond heartbeatMs (no frames landing) */
  const stalled = ref(false)
  /** true when the poll budget/empty-grace ran out WITHOUT a terminal frame */
  const interrupted = ref(false)
  /**
 * r4 fix (R62): true between the submit click and the dispatch ack.
 * The window opens BEFORE the POST round trip so the kickoff is instantly
 * on screen; while pending there is no cursor yet, so the drawer honestly
 * shows its waiting frame (and the first-frame degrade text once the
 * budget passes) instead of showing nothing.
 */
  const pending = ref(false)
  /**
   * R67 retention exit (T701d-expiry): the ONE expired flag every host
   * shares instead of per-dialog copies. A host sets it when the run
   * history proves frames existed while the replay window came back
   * empty; the drawer renders the three-part pointer from this state.
   */
  const expired = ref(false)

  function numberOption(value: number | (() => number) | undefined): number | undefined {
    return typeof value === 'function' ? value() : value
  }

  /** Drop the oldest frames until the buffer fits the cap (newest kept). */
  function trimToCap(cap: number | undefined): void {
    if (!cap || cap <= 0) return
    const excess = events.value.length - cap
    if (excess > 0) events.value.splice(0, excess)
  }

  /**
 * Open the window without a cursor :
 * the badge and the drawer's honest waiting frame are on screen before the
 * dispatch POST returns. Pair with openRun on ack, cancelPending on failure.
 */
  function beginPending(): void {
    events.value = []
    lastOutcome.value = null
    running.value = true
    drawerOpen.value = true
    stalled.value = false
    interrupted.value = false
    expired.value = false
    pending.value = true
  }

  /** Close a pending window whose dispatch never got acknowledged. */
  function cancelPending(): void {
    pending.value = false
    running.value = false
    if (events.value.length === 0) drawerOpen.value = false
  }

  async function openRun(outcome: ProbeOutcome) {
    const emptyGrace = options.emptyGracePolls ?? MAX_EMPTY_POLLS
    const interval = options.pollIntervalMs ?? POLL_INTERVAL_MS
    const maxPolls = options.maxPolls ?? MAX_POLLS
    lastOutcome.value = outcome
    pending.value = false
    events.value = []
    running.value = true
    drawerOpen.value = true
    stalled.value = false
    interrupted.value = false
    expired.value = false
    let lastFrameAt = Date.now()
    let cursor = 0
    let terminalHit = false
    try {
      for (let guard = 0; guard < maxPolls; guard++) {
        const batch = await fetchEvents(outcome.runToken, cursor)
        if (batch.length === 0) {
          // silence window: the heartbeat door tells "still polling, stream
          // alive" apart from "no news" — only when the host enabled it
          const heartbeat = numberOption(options.heartbeatMs)
          if (heartbeat && heartbeat > 0 && Date.now() - lastFrameAt >= heartbeat) {
            stalled.value = true
          }
          // the run finished server-side; a few empty polls are enough
          if (guard > emptyGrace) {
            interrupted.value = true
            break
          }
          await sleep(interval)
          continue
        }
        events.value.push(...batch)
        trimToCap(numberOption(options.cap))
        cursor = batch[batch.length - 1].cursor
        lastFrameAt = Date.now()
        stalled.value = false
        const done = options.terminal
          ? batch.some(options.terminal)
          : batch.some((row) => row.summary.endsWith('结束：' + outcome.verdictHuman)
              || (row.summary.includes('结束') && row.summary.includes('：')
                  && !row.summary.startsWith('提示')))
        if (done) {
          terminalHit = true
          if (!options.terminal) {
            // legacy synchronous doors: the verdict row may land a beat later
            await sleep(interval)
            const rest = await fetchEvents(outcome.runToken, cursor)
            events.value.push(...rest)
            trimToCap(numberOption(options.cap))
          }
          // terminal dwell: let the end-state frame render before the
          // collapse, otherwise the terminal row unmounts in the same tick
          const dwell = numberOption(options.terminalDwellMs) ?? 0
          if (dwell > 0) await sleep(dwell)
          break
        }
      }
      if (!terminalHit) interrupted.value = true
    } finally {
      running.value = false
      if (terminalHit && options.closeOnTerminal) drawerOpen.value = false
    }
  }

  function close() {
    drawerOpen.value = false
  }

  return { events, running, drawerOpen, lastOutcome, stalled, interrupted, pending, expired,
    openRun, beginPending, cancelPending, close }
}

function sleep(ms: number) {
  return new Promise<void>((resolve) => setTimeout(resolve, ms))
}
