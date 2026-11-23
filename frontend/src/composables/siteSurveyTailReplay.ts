/**
 * Replay buffer for one whole-site survey run (WU revisit door, R67).
 *
 * The persisted process tail is the source of truth; this module only
 * keeps the browser-side read window per run id so the cursor moves
 * FORWARD across dialog open/close and page visits — leaving the ledger
 * and coming back continues from the last seen frame instead of rewinding
 * to zero (nothing skipped, nothing replayed twice). Reads ride the ONE
 * cursor door every survey tail already uses (fetchSurveyProcessEvents);
 * no second event path exists by design.
 *
 * Memory discipline (R57): buffers are bounded by RUN_TAIL_CACHE_MAX and
 * evicted FIFO (the evicted entry's follow timer is cancelled first).
 * Eviction only drops the client window — the server ledger stays, and a
 * later open refetches from the cursor the door still knows.
 */
import { reactive } from 'vue'
import { fetchSurveyProcessEvents, isSurveyTerminalRow } from '@/api/webElements'
import type { ExtractionEvent } from '@/api/taskEvents'
import type { SiteSurveyRunRow } from '@/api/siteProfiles'

/** One run's read window; reactive so any mounted drawer mirrors it. */
export interface RunTailState {
  events: ExtractionEvent[]
  /** last cursor seen; the next window starts here (never rewinds) */
  cursor: number
  /** true while the crawl itself is still running */
  live: boolean
  /** first window resolved at least once (drives the expired honesty) */
  started: boolean
  /** server returned a short window: the tail is fully on screen */
  exhausted: boolean
  loading: boolean
  /** terminal run whose tail came back empty (retention window passed) */
  expiredHint: boolean
  timer: ReturnType<typeof setInterval> | null
}

const REPLAY_PAGE_SIZE = 500
const FOLLOW_POLL_MS = 2000
/** Bounded in-session cache of replay windows (R57 cap + FIFO eviction). */
export const RUN_TAIL_CACHE_MAX = 8

const buffers = new Map<number, RunTailState>()

/** Observable occupancy of the client window cache (R57: leaks need a number). */
export function runTailCacheSize(): number {
  return buffers.size
}

export function getRunTail(runId: number): RunTailState {
  let st = buffers.get(runId)
  if (!st) {
    st = reactive({
      events: [], cursor: 0, live: false, started: false,
      exhausted: false, loading: false, expiredHint: false, timer: null,
    }) as unknown as RunTailState
    buffers.set(runId, st)
    while (buffers.size > RUN_TAIL_CACHE_MAX) {
      const oldest = buffers.keys().next().value as number
      stopRunTailFollow(oldest)
      buffers.delete(oldest)
    }
  }
  return st
}

/**
 * Pull the next cursor window for one run. Throws after resetting the
 * loading flag so the drawer owns the failure copy (no silent empty
 * window masquerading as history).
 */
export async function loadRunTailPage(run: SiteSurveyRunRow): Promise<void> {
  const st = getRunTail(run.runId)
  if (st.loading || (st.exhausted && !st.live)) return
  const sessionId = run.exploreSessionId
  if (sessionId === null || sessionId === undefined) return
  st.loading = true
  try {
    const page = await fetchSurveyProcessEvents(sessionId, st.cursor, REPLAY_PAGE_SIZE)
    if (page.length > 0) {
      st.events.push(...page)
      st.cursor = page[page.length - 1].cursor
      if (page.some((row) => isSurveyTerminalRow(row))) {
        st.live = false
        stopRunTailFollow(run.runId)
      }
    }
    if (page.length < REPLAY_PAGE_SIZE) st.exhausted = true
    if (!st.started && st.events.length === 0 && run.status !== 'running') {
      st.expiredHint = true
    }
    st.started = true
  } finally {
    st.loading = false
  }
}

export function stopRunTailFollow(runId: number): void {
  const st = buffers.get(runId)
  if (st?.timer) {
    clearInterval(st.timer)
    st.timer = null
  }
}

/**
 * Follow a live run while the dialog holds it open: poll the same cursor
 * door and append. The terminal frame (or the crawl flipping to a terminal
 * status) stops the follow and reports back so the host silently re-reads
 * its lists — never a manual-refresh instruction.
 */
export function startRunTailFollow(run: SiteSurveyRunRow, onTerminal?: () => void): void {
  const st = getRunTail(run.runId)
  if (!st.live || st.timer) return
  st.timer = setInterval(() => {
    void loadRunTailPage(run)
      .catch(() => undefined)
      .then(() => {
        if (!st.live) {
          stopRunTailFollow(run.runId)
          onTerminal?.()
        }
      })
  }, FOLLOW_POLL_MS)
}
