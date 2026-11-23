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
import {
  mergeEvents,
  openTaskProcessEventTail,
  type ExtractionEvent,
  type TailHandle,
} from '@/api/taskEvents'

export function useGenBatchEventTail(batch: Ref<AiBatch | null>) {
  const events = ref<ExtractionEvent[]>([])
  const handles = new Map<number, TailHandle>()

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
  }

  sync()
  watch(batch, sync)

  /** true while any sub-task is still pending or running (drives the drawer hint). */
  const live = computed(() => {
    const current = batch.value
    return current !== null && current.pending + current.running > 0
  })

  onScopeDispose(reset)

  return { events, live }
}
