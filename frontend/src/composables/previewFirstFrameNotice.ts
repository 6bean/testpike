/**
 * Hot knob + honest waiting helper for the contract-bulk preview step (MJ P7).
 *
 * The wizard preview is a deterministic synchronous render, but a very large
 * OpenAPI contract can still keep the operator in front of a bare spinner.
 * Past the first-frame budget the screen shows one honest waiting line instead
 * of guessing progress or silently hanging (R62 first-response degradation).
 *
 * Reading posture mirrors composables/tailWindowConfig.ts: ONE reading point
 * for the key (R40 — no component copies the literal or the default), the
 * permission gates the REMOTE value only, and any failure or out-of-range row
 * falls back to the factory default (fail-soft, R32). Write-side range
 * enforcement stays server-owned (tp_sys_config).
 */
import { ref, type Ref } from 'vue'
import { listConfigs } from '@/api/config'
import { useAuthStore } from '@/stores/auth'

const KEY_PREVIEW_FIRST_FRAME_MS = 'mock.spec-import.preview-first-frame-ms'

/** factory default, kept in sync with the seed migration */
export const PREVIEW_FIRST_FRAME_DEFAULT_MS = 1500

/** ms a preview round may stay invisible before the waiting line appears */
export const previewFirstFrameMs = ref<number>(PREVIEW_FIRST_FRAME_DEFAULT_MS)

let previewKnobLoadStarted = false

/** Pull the knob once per page load; garbage rows keep the default. */
export async function loadPreviewFirstFrameConfig(): Promise<void> {
  if (previewKnobLoadStarted) return
  if (!useAuthStore().hasPerm('perm:system:system-config')) return
  previewKnobLoadStarted = true
  try {
    const rows = await listConfigs('接口仿真')
    const row = rows.find((r) => r.configKey === KEY_PREVIEW_FIRST_FRAME_MS)
    if (row) {
      const parsed = Number.parseInt(String(row.configValue).trim(), 10)
      if (Number.isFinite(parsed) && parsed >= 200 && parsed <= 30000) {
        previewFirstFrameMs.value = parsed
      }
    }
  } catch {
    // fail-soft: the factory default mirrors the seed row.
  }
}

/** Waiting-line handle used around one preview round. */
export interface LateFrameNotice {
  /** true once the round has been invisible longer than the budget */
  late: Ref<boolean>
  /** (re)start the budget clock; a re-arm clears a previous late flag */
  arm: () => void
  /** stop the clock (response arrived or round failed) */
  disarm: () => void
}

/**
 * Pure budget timer: `arm()` shows nothing until `thresholdMs` elapses with no
 * disarm. Exported separately so the semantics are unit-testable without a
 * browser and shared by any future preview-like round (no second timer spec).
 */
export function createLateFrameNotice(threshold: number | (() => number)): LateFrameNotice {
  const late = ref(false)
  let timer: ReturnType<typeof setTimeout> | null = null
  const stop = () => {
    if (timer !== null) {
      clearTimeout(timer)
      timer = null
    }
  }
  return {
    late,
    arm: () => {
      late.value = false
      stop()
      const ms = typeof threshold === 'function' ? threshold() : threshold
      timer = setTimeout(() => { late.value = true }, ms)
    },
    disarm: () => {
      stop()
    },
  }
}
