import { ref } from 'vue'
import { listConfigs } from '@/api/config'
import { useAuthStore } from '@/stores/auth'

/**
 * Chat composer height preference (, adjudication §two.5 — input-area
 * height, WeChat shape).
 *
 * Same discipline as useChatDockWidth (R40: drag geometry has one fold per
 * axis, the grab strip, the keyboard steps and the initial read all pass
 * through ONE clamp):
 * - rows clamp into [COMPOSER_MIN_ROWS, ceiling]; non-finite input falls
 * back to COMPOSER_DEFAULT_ROWS (the composer must never collapse to 0);
 * - the personal height rides per-user localStorage (personal-preference
 * ruling: never on the server) — the *ceiling* is the separate governance
 * number below;
 * - reset writes null = clear the bucket so the factory default shows again;
 * - the ceiling comes from the hot-updatable governance key
 * ai.chat.composer-max-rows (seed V5xx, factory 10). The reader fails
 * soft to the factory ceiling when the row is missing or unparsable,
 * never below COMPOSER_MIN_ROWS (a wild server value cannot lock the
 * composer smaller than its own default).
 */

/** Factory default rows (double-click reset target; ≥ the old fixed rows=2). */
export const COMPOSER_DEFAULT_ROWS = 3
/** Shortest usable composer (one row above this stays a single-line box). */
export const COMPOSER_MIN_ROWS = 3
/** Fallback ceiling when the governance key is absent or garbage. */
export const COMPOSER_FACTORY_MAX_ROWS = 10
/** Keyboard arrow step on the focused grab strip (X3: drag is never the only path). */
export const COMPOSER_ROWS_STEP = 1
/** Governance key read by the writer-side guard and the composer reader. */
export const COMPOSER_MAX_ROWS_KEY = 'ai.chat.composer-max-rows'

const COMPOSER_ROWS_STORAGE_KEY = 'tp_chat_composer_rows'
const ANON_BUCKET = 'anon'

type StorageLike = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>

function storageBucket(userId: number | string | null | undefined): string {
  return `${COMPOSER_ROWS_STORAGE_KEY}:${userId ?? ANON_BUCKET}`
}

/**
 * Clamp requested rows into [COMPOSER_MIN_ROWS, maxRows]; non-finite →
 * factory default. maxRows itself is normalized first: garbage or values
 * below the floor fall back to the factory ceiling, so a bad server value
 * can never squeeze the composer under its own default height.
 */
export function clampComposerRows(raw: number, maxRows: number = COMPOSER_FACTORY_MAX_ROWS): number {
  const ceiling = normalizeMaxRows(maxRows)
  if (!Number.isFinite(raw)) return Math.min(COMPOSER_DEFAULT_ROWS, ceiling)
  return Math.min(ceiling, Math.max(COMPOSER_MIN_ROWS, Math.round(raw)))
}

/** Normalize the governance ceiling: unparsable/below-floor → factory 10. */
export function normalizeMaxRows(raw: number | string | null | undefined): number {
  const parsed = typeof raw === 'string' ? Number(raw) : raw
  if (!Number.isFinite(parsed as number)) return COMPOSER_FACTORY_MAX_ROWS
  const int = Math.round(parsed as number)
  if (int < COMPOSER_MIN_ROWS) return COMPOSER_FACTORY_MAX_ROWS
  return int
}

/** Read the persisted rows (per-user bucket); anything broken → clamped default. */
export function readComposerRows(
  storage: StorageLike,
  userId: number | string | null | undefined,
  maxRows: number = COMPOSER_FACTORY_MAX_ROWS,
): number {
  const raw = storage.getItem(storageBucket(userId))
  if (raw === null) return clampComposerRows(COMPOSER_DEFAULT_ROWS, maxRows)
  return clampComposerRows(Number(raw), maxRows)
}

/** Persist rows through the clamp; null = reset (bucket cleared). */
export function writeComposerRows(
  storage: StorageLike,
  userId: number | string | null | undefined,
  rows: number | null,
  maxRows: number = COMPOSER_FACTORY_MAX_ROWS,
): void {
  const bucket = storageBucket(userId)
  if (rows === null) {
    storage.removeItem(bucket)
    return
  }
  storage.setItem(bucket, String(clampComposerRows(rows, maxRows)))
}

/** Shared ceiling view (same singleton posture as the paste switch): the
 *  composer reads this ref, the loader fills it once per session. */
const composerMaxRows = ref<number>(COMPOSER_FACTORY_MAX_ROWS)

export function composerMaxRowsView() {
  return composerMaxRows
}

let ceilingLoadStarted = false

/**
 * Read the governance ceiling once per session through the existing grouped
 * config query (no second remote channel — same posture as the paste switch).
 * Actors without the config-page permission keep the factory ceiling; any
 * read failure fails soft to the factory default (install-and-use).
 */
export async function loadComposerMaxRowsSwitch(): Promise<void> {
  if (ceilingLoadStarted) return
  // Without the permission (or before the profile lands) do NOT latch — the
  // identity watch retries once the actor is known.
  if (!useAuthStore().hasPerm('perm:system:system-config')) return
  ceilingLoadStarted = true
  try {
    // Group literal = the tp_sys_config seed contract (\u7cfb\u7edf = the
    // shared "system" group); wire value, not UI text.
    const rows = await listConfigs('\u7cfb\u7edf')
    const raw = rows.find((r) => r.configKey === COMPOSER_MAX_ROWS_KEY)?.configValue
    composerMaxRows.value = normalizeMaxRows(raw)
  } catch {
    composerMaxRows.value = COMPOSER_FACTORY_MAX_ROWS
  }
}
