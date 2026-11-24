import { onUnmounted, ref, type Ref } from 'vue'
import { getAiBatch, type AiBatch } from '@/api/ai'
import { tailPollFailureThreshold } from './tailWindowConfig'


export function useAiBatchPolling(batchIdRef: Ref<number | null>) {
  const batch = ref<AiBatch | null>(null)
  const polling = ref(false)
  /** consecutive failed progress re-reads, zeroed by the next success */
  const pollFailures = ref(0)
  /** true once failures reach the hot threshold: the numbers on screen are
   *  frozen and the face must say so instead of dialing the door silently */
  const readStalled = ref(false)
  let timer: number | null = null

  async function fetchOnce(id: number): Promise<void> {
    try {
      batch.value = await getAiBatch(id)
      pollFailures.value = 0
      readStalled.value = false
    } catch {
      // The capture window proved the failure mode: an expired sign-in made
      // every re-read reject while the old empty catch kept swallowing the
      // errors, freezing the progress head with no marker. Failures now
      // count against the hot threshold so the view can surface a stalled
      // state with the named repair entry.
      pollFailures.value += 1
      if (pollFailures.value >= tailPollFailureThreshold.value) {
        readStalled.value = true
      }
    }
  }

  
  async function startPolling(): Promise<void> {
    const id = batchIdRef.value
    if (!id) return
    await fetchOnce(id)
    if (timer !== null) return
    polling.value = true
    timer = window.setInterval(() => {
      const cur = batchIdRef.value
      if (!cur) {
        stopPolling()
        return
      }
      const b = batch.value
      // Settled = done + failed + cancelled: a human-stopped item is a settled
      // bucket too, so the drawer seals instead of dialing the door forever
      // .
      if (b && b.done + b.failed + b.cancelled >= b.total) {
        stopPolling()
        return
      }
      void fetchOnce(cur)
    }, 2000)
  }

  
  function stopPolling(): void {
    if (timer !== null) {
      window.clearInterval(timer)
      timer = null
    }
    polling.value = false
  }

  onUnmounted(stopPolling)

  return { batch, polling, pollFailures, readStalled, startPolling, stopPolling }
}
