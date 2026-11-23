/**
 * Device-wall live-thumbnail round poller (P1 wall live face).
 *
 * The wall shows one 1fps thumbnail cell per device. The page owns ONE round
 * loop instead of per-cell timers: every second the current device ids fan
 * their GET /appauto/devices/{id}/thumbnail reads out through the shared
 * runBounded piece (single-source throttle — a second limiter or an unbounded
 * burst is exactly the 32-wide proxy-stretch failure measured for the
 * pinned-object panel). Cell truth is server-sourced and passes through
 * untouched (LIVE frame vs honest IDLE/NO_FRAME reason).
 *
 * Failure discipline: a transport error marks the cell failed (never an old
 * frame pretending to be live) and backs that device out of the next rounds
 * with exponential skip (1,2,4… capped) so a dead device cannot burn the
 * round budget of healthy ones; any success clears the streak.
 *
 * Hygiene: rounds are skipped while the tab is hidden (no background storm),
 * a slower-than-cadence round never overlaps, removed devices lose their
 * cells and stop being fetched, and unmount stops the loop.
 */
import { onUnmounted, ref, type Ref } from 'vue'
import { fetchDeviceThumbnail, type AppautoDeviceThumbnail } from '@/api/appauto'
import { runBounded } from '@/utils/asyncFanout'

export interface DeviceThumbnailCell {
  /** last server view for this device (null = nothing honest to show yet) */
  view: AppautoDeviceThumbnail | null
  /** latest round ended in a transport error for this device */
  failed: boolean
}

export interface DeviceThumbnailPollOptions {
  /** injectable fetch face (defaults to the api bridge) — test seam only */
  fetch?: (deviceId: number) => Promise<AppautoDeviceThumbnail>
  /** max in-flight thumbnail reads per round (runBounded lanes) */
  limit?: number
  /** round cadence in ms */
  intervalMs?: number
  /** injectable hidden-tab probe (repo test env is node without jsdom) */
  isHidden?: () => boolean
}

const DEFAULT_LIMIT = 6
const DEFAULT_INTERVAL_MS = 1000
const MAX_BACKOFF_ROUNDS = 8

export function useDeviceWallThumbnails(
  getDeviceIds: () => number[],
  options: DeviceThumbnailPollOptions = {},
): {
  cells: Ref<Record<number, DeviceThumbnailCell>>
  start: () => void
  stop: () => void
} {
  const fetcher = options.fetch ?? fetchDeviceThumbnail
  const limit = options.limit ?? DEFAULT_LIMIT
  const intervalMs = options.intervalMs ?? DEFAULT_INTERVAL_MS
  const isHidden = options.isHidden ?? (() => typeof document !== 'undefined' && document.hidden)

  const cells = ref<Record<number, DeviceThumbnailCell>>({})
  const failStreak = new Map<number, number>()
  const retryFromRound = new Map<number, number>()
  let roundIndex = 0
  let timer: ReturnType<typeof setInterval> | null = null
  let inFlight = false

  async function runRound(): Promise<void> {
    // hidden tab: this tick simply does not run (polling resumes on visible)
    if (isHidden()) return
    if (inFlight) return // a slow round never overlaps the next tick
    inFlight = true
    const round = roundIndex++
    try {
      const ids = getDeviceIds()
      const live = new Set(ids)
      for (const key of Object.keys(cells.value)) {
        const id = Number(key)
        if (!live.has(id)) {
          delete cells.value[id]
          failStreak.delete(id)
          retryFromRound.delete(id)
        }
      }
      const due = ids.filter((id) => (retryFromRound.get(id) ?? 0) <= round)
      if (!due.length) return
      await runBounded(
        due.map((id) => async () => {
          try {
            const view = await fetcher(id)
            failStreak.delete(id)
            retryFromRound.delete(id)
            cells.value[id] = { view, failed: false }
          } catch {
            const streak = (failStreak.get(id) ?? 0) + 1
            failStreak.set(id, streak)
            const backoff = Math.min(2 ** (streak - 1), MAX_BACKOFF_ROUNDS)
            retryFromRound.set(id, round + 1 + backoff)
            cells.value[id] = { view: null, failed: true }
          }
        }),
        limit,
      )
    } finally {
      inFlight = false
    }
  }

  function start(): void {
    if (timer !== null) return // idempotent: one loop per page instance
    void runRound()
    // bare setInterval: a number in the browser, a Timeout handle under the
    // node test env — the typed handle form keeps both shapes working
    timer = setInterval(() => void runRound(), intervalMs)
  }

  function stop(): void {
    if (timer !== null) {
      clearInterval(timer)
      timer = null
    }
  }

  onUnmounted(stop)

  return { cells, start, stop }
}
