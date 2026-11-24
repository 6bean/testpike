/**
 * Hot-updated geometry knobs of the process tail window ( segment d/e,
 * R62). ONE reading point for the governance keys
 * `element.survey.tail-cap` / `ui.tail.heartbeat-seconds` /
 * `ui.tail.first-frame-ms` / `ui.tail.terminal-dwell-ms` /
 * `ui.list.auto-follow-ms` / `ui.tail.poll-failure-threshold` so no
 * component copies a key or a default (R40).
 *
 * Reading posture mirrors chatScrollBudget.loadChatScrollBudget: the
 * permission gates the REMOTE value only; without it (or on any failure or
 * out-of-range row) the factory default keeps the window honest
 * (fail-soft, R32). Write-side range enforcement stays server-owned.
 */
import { ref } from 'vue'
import { listConfigs } from '@/api/config'
import { useAuthStore } from '@/stores/auth'

const KEY_TAIL_CAP = 'element.survey.tail-cap'
const KEY_HEARTBEAT_SECONDS = 'ui.tail.heartbeat-seconds'
const KEY_FIRST_FRAME_MS = 'ui.tail.first-frame-ms'
const KEY_TERMINAL_DWELL_MS = 'ui.tail.terminal-dwell-ms'
const KEY_LIST_AUTO_FOLLOW_MS = 'ui.list.auto-follow-ms'
const KEY_POLL_FAILURE_THRESHOLD = 'ui.tail.poll-failure-threshold'

export const SURVEY_TAIL_CAP_DEFAULT = 400
export const TAIL_HEARTBEAT_DEFAULT_S = 15
export const TAIL_FIRST_FRAME_DEFAULT_MS = 2000
export const TAIL_TERMINAL_DWELL_DEFAULT_MS = 1500
export const LIST_AUTO_FOLLOW_DEFAULT_MS = 2000
export const TAIL_POLL_FAILURE_THRESHOLD_DEFAULT = 3

/** events kept in memory + DOM by the survey tail before oldest frames drop */
export const surveyTailCap = ref<number>(SURVEY_TAIL_CAP_DEFAULT)
/** silence beyond this many seconds shows the "still running" heartbeat row */
export const tailHeartbeatSeconds = ref<number>(TAIL_HEARTBEAT_DEFAULT_S)
/** newest frame must reach the screen within this many ms, else degrade row */
export const tailFirstFrameMs = ref<number>(TAIL_FIRST_FRAME_DEFAULT_MS)
/** ms the drawer stays open after the terminal frame, then auto-collapses */
export const tailTerminalDwellMs = ref<number>(TAIL_TERMINAL_DWELL_DEFAULT_MS)
/** ms between auto re-reads of a list panel with in-flight background jobs */
export const listAutoFollowMs = ref<number>(LIST_AUTO_FOLLOW_DEFAULT_MS)
/** consecutive failed progress re-reads before the face shows a stalled marker */
export const tailPollFailureThreshold = ref<number>(TAIL_POLL_FAILURE_THRESHOLD_DEFAULT)

let tailConfigLoadStarted = false

/** Pull the knobs once per page load; each keeps its own range guard
 *  so one bad row cannot poison the others. */
export async function loadTailWindowConfig(): Promise<void> {
  if (tailConfigLoadStarted) return
  if (!useAuthStore().hasPerm('perm:system:system-config')) return
  tailConfigLoadStarted = true
  try {
    // Group literal = the tp_sys_config seed contract (\u7cfb\u7edf = the
    // shared "system" group the other R32 feature switches read).
    const rows = await listConfigs('\u7cfb\u7edf')
    surveyTailCap.value = pickInt(rows, KEY_TAIL_CAP, SURVEY_TAIL_CAP_DEFAULT, 50, 5000)
    tailHeartbeatSeconds.value = pickInt(rows, KEY_HEARTBEAT_SECONDS, TAIL_HEARTBEAT_DEFAULT_S, 2, 300)
    tailFirstFrameMs.value = pickInt(rows, KEY_FIRST_FRAME_MS, TAIL_FIRST_FRAME_DEFAULT_MS, 500, 60000)
    tailTerminalDwellMs.value = pickInt(rows, KEY_TERMINAL_DWELL_MS, TAIL_TERMINAL_DWELL_DEFAULT_MS, 0, 10000)
    listAutoFollowMs.value = pickInt(rows, KEY_LIST_AUTO_FOLLOW_MS, LIST_AUTO_FOLLOW_DEFAULT_MS, 1000, 60000)
    tailPollFailureThreshold.value = pickInt(rows, KEY_POLL_FAILURE_THRESHOLD, TAIL_POLL_FAILURE_THRESHOLD_DEFAULT, 2, 20)
  } catch {
    // fail-soft: defaults already mirror the factory seed values.
  }
}

function pickInt(
  rows: { configKey: string; configValue: string }[],
  key: string,
  fallback: number,
  min: number,
  max: number,
): number {
  const raw = Number(rows.find((r) => r.configKey === key)?.configValue)
  return Number.isFinite(raw) && raw >= min && raw <= max ? raw : fallback
}

/** Test seam: forget the latch so a fresh mount can re-read the keys. */
export function resetTailWindowConfigLoad(): void {
  tailConfigLoadStarted = false
  surveyTailCap.value = SURVEY_TAIL_CAP_DEFAULT
  tailHeartbeatSeconds.value = TAIL_HEARTBEAT_DEFAULT_S
  tailFirstFrameMs.value = TAIL_FIRST_FRAME_DEFAULT_MS
  tailTerminalDwellMs.value = TAIL_TERMINAL_DWELL_DEFAULT_MS
  listAutoFollowMs.value = LIST_AUTO_FOLLOW_DEFAULT_MS
  tailPollFailureThreshold.value = TAIL_POLL_FAILURE_THRESHOLD_DEFAULT
}
