/**
 * P4: aggregate the live tail of every AI batch-generation sub-task
 * into one ledger for the shared tail drawer on the batch progress face.
 * Each task id opens exactly one stream through the shared pump
 * (openTaskProcessEventTail); this file never re-implements the wire, and
 * every freshly merged window goes through mergeEvents so cursor dedupe and
 * the tail cap stay single-sourced. Handles close when the batch resets to
 * null (a new submission) and when the owning scope disposes (unmount),
 * so no stream outlives the face that opened it.
 */
import { computed, onScopeDispose, ref, watch, type Ref } from 'vue'
import type { AiBatch } from '@/api/ai'
import { fetchBatchProcessRuns } from '@/api/batch'
import {
  mergeEvents,
  openTaskProcessEventTail,
  type ExtractionEvent,
  type TailHandle,
} from '@/api/taskEvents'
import { replayExpiryVerdict } from '@/composables/useTestRunTail'

export function useGenBatchEventTail(batch: Ref<AiBatch | null>) {
  const events = ref<ExtractionEvent[]>([])
  const handles = new Map<number, TailHandle>()
  /** R67 revisit door: set when the batch settled but the sweeper already
   *  retired every frame (run history proves they existed). */
  const expired = ref(false)

  /** Open one stream per unseen task id; nothing ever reopens for the same id. */
  function sync(): void {
    const current = batch.value
    if (!current) {
      reset()
      return
    }
    for (const item of current.items) {
      if (handles.has(item.taskId)) continue
      const handle = openTaskProcessEventTail(item.taskId, 0, window => {
        // Row ids are unique per ledger, not across ledgers: tag every row
        // with its source task so the merged dedupe never collides two
        // tasks' cursors (nor splits one task's rows across keys).
        const scoped = window.map(row => ({ ...row, scope: String(item.taskId) }))
        events.value = mergeEvents(events.value, scoped)
      })
      handles.set(item.taskId, handle)
    }
  }

  /** Close every stream and clear the ledger (new submission resets the face). */
  function reset(): void {
    for (const handle of handles.values()) handle.close()
    handles.clear()
    events.value = []
    expired.value = false
  }

  /**
   * R67 revisit door for the batch face. Once the batch settled and the
   * aggregated window came back empty, the run history is consulted: if it
   * reports frames, the sweeper retired them and the drawer swaps to the
   * three-element pointer. The decision itself is the shared
   * replayExpiryVerdict formula (single source, no second derivation).
   */
  async function assessExpiry(): Promise<void> {
    const current = batch.value
    if (!current) return
    if (current.pending + current.running > 0) {
      expired.value = false
      return
    }
    if (events.value.length > 0) {
      expired.value = false
      return
    }
    const runs = await fetchBatchProcessRuns(current.id)
    expired.value = replayExpiryVerdict(
      events.value.length,
      runs.reduce((sum, run) => sum + run.eventCount, 0),
    )
  }

  sync()
  watch(batch, sync)

  /** true while any sub-task is still pending or running (drives the drawer hint). */
  const live = computed(() => {
    const current = batch.value
    return current !== null && current.pending + current.running > 0
  })

  onScopeDispose(reset)

  return { events, live, expired, assessExpiry }
}
