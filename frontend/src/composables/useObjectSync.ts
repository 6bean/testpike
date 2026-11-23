/**
 * BG batch — shared consumption hook for landed dialog drafts.
 *
 * Pages that display an object a chat draft can land must converge on the
 * new value WITHOUT any user action and WITHOUT any toast/confirm/page jump
 * (BG iron rules 2 and 3). This file is the single source for that posture
 * (R40): the page declares which objectTypes it shows, an optional anchor
 * predicate that pins the announcement to the object it currently renders,
 * and a silent re-read callback. The subscription always unhooks itself on
 * unmount, so the bus ledger counts back down (R57).
 *
 * mergeUntouchedFields implements the edit-state half of iron rule 3:
 * fields the user has not touched since the baseline snapshot take the new
 * server value (the version baseline rides along, so a later save uses the
 * fresh number against the existing column-level merge instead of a false
 * 40901 refusal); fields the user HAS typed keep their draft — silently
 * overwriting user input would lose data.
 */
import { onBeforeUnmount } from 'vue'
import { subscribeObjectSync } from './chatSyncBus'
import type { ChatObjectSyncEvent } from './chatSyncBus'

export type { ChatObjectSyncEvent }

export interface ObjectSyncOptions {
  /** Draft objectTypes this page renders; landings of anything else are ignored. */
  watch: readonly string[]
  /** Pin the announcement to the exact object on screen; absent = any id. */
  anchor?: (event: ChatObjectSyncEvent) => boolean
  /** Silent re-read callback — toasts/confirmations here violate BG iron rule 2. */
  onSync: (event: ChatObjectSyncEvent) => void
}

/**
 * Subscribe to landing announcements for the lifetime of the component.
 * Returns the unhook as well, for consumers that prefer an explicit
 * onBeforeUnmount(edit) pair; the auto-unhook below always runs, so the
 * subscription can never outlive the page.
 */
export function useObjectSync(options: ObjectSyncOptions): () => void {
  const unhook = subscribeObjectSync((event) => {
    if (!options.watch.includes(event.objectType)) return
    if (options.anchor && !options.anchor(event)) return
    options.onSync(event)
  })
  onBeforeUnmount(unhook)
  return unhook
}

/** Per-field baseline map for mergeUntouchedFields — one JSON stamp per key. */
export function fieldSnapshots(form: object): Record<string, string> {
  const snapshot: Record<string, string> = {}
  for (const key of Object.keys(form)) {
    snapshot[key] = JSON.stringify((form as Record<string, unknown>)[key])
  }
  return snapshot
}

/**
 * Apply fresh server values field-by-field: a field whose current value still
 * equals the baseline was not touched and swaps; a field the user edited
 * keeps their input. Returns how many fields were swapped (0 = everything on
 * screen was user draft or already equal).
 */
export function mergeUntouchedFields(
  form: Record<string, unknown>,
  baseline: Record<string, string>,
  fresh: Record<string, unknown>,
): number {
  let applied = 0
  for (const key of Object.keys(fresh)) {
    const next = fresh[key]
    if (next === undefined) continue
    if (JSON.stringify(form[key]) !== baseline[key]) continue
    if (JSON.stringify(next) === JSON.stringify(form[key])) continue
    form[key] = next
    baseline[key] = JSON.stringify(next)
    applied += 1
  }
  return applied
}
