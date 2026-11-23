/**
 * List auto-follow loop (R62 ①③): while a panel shows rows whose status is
 * still an in-flight background process, re-read the list on a fixed beat so
 * the screen settles on its own — the user never needs a manual refresh.
 *
 * Shape of the loop (mirrors the governance of the shared tail window, one
 * implementation for every list panel, R40):
 * - recursive setTimeout with a single request in flight at a time — a slow
 *   reload cannot stack parallel reads;
 * - a failed reload keeps the previous snapshot on screen and the beat
 *   continues (the reload owner decides what the user hears; silence here
 *   prevents one network blip from killing the follow-up);
 * - the loop self-stops when the panel closes (active becomes false) or when
 *   no row is pending anymore (terminal states reached);
 * - watch(active) mounts/unmounts with the panel, and the timer is cleared on
 *   scope dispose, so no orphan beat survives the component (R57).
 */
import { onScopeDispose, ref, watch, type Ref } from 'vue'

export interface ListAutoFollowOptions<T> {
  /** false while the owning panel is closed — the loop must not run hidden */
  active: () => boolean
  /** current rows as shown on screen */
  rows: () => T[]
  /** true while the row represents a still-running background process */
  isPending: (row: T) => boolean
  /** re-read the list; rejections are swallowed here on purpose (see header) */
  reload: () => Promise<void>
  /** ms between beats; read from the hot-updated knob at every reschedule */
  intervalMs: () => number
}

export interface ListAutoFollowHandle {
  /** drives the "statuses update automatically" badge on the panel */
  following: Ref<boolean>
  /** begin following when the panel is open and something is pending */
  start: () => void
  /** stop following and clear the pending beat */
  stop: () => void
}

export function useListAutoFollow<T>(options: ListAutoFollowOptions<T>): ListAutoFollowHandle {
  const following = ref(false)
  let timer: ReturnType<typeof setTimeout> | null = null
  let inFlight = false

  function hasPending(): boolean {
    return options.rows().some(options.isPending)
  }

  function clearBeat() {
    if (timer !== null) {
      clearTimeout(timer)
      timer = null
    }
  }

  function scheduleBeat() {
    clearBeat()
    // clamp so a mis-typed knob value cannot spin the network (server side
    // range enforcement is the authority; this is only the client guard)
    timer = setTimeout(runBeat, Math.max(250, options.intervalMs()))
  }

  async function runBeat() {
    timer = null
    if (!options.active()) {
      following.value = false
      return
    }
    if (inFlight) {
      scheduleBeat()
      return
    }
    inFlight = true
    try {
      await options.reload()
    } catch {
      // keep the previous snapshot and keep following — the next beat retries
    } finally {
      inFlight = false
    }
    if (!options.active() || !hasPending()) {
      following.value = false
      return
    }
    scheduleBeat()
  }

  function start() {
    if (!options.active() || !hasPending()) return
    following.value = true
    scheduleBeat()
  }

  function stop() {
    following.value = false
    clearBeat()
  }

  watch(
    () => options.active(),
    (on) => {
      if (on) start()
      else stop()
    },
  )

  onScopeDispose(clearBeat)

  return { following, start, stop }
}
