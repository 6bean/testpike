import { onUnmounted, ref, type Ref } from 'vue'
import { getAiBatch, type AiBatch } from '@/api/ai'


export function useAiBatchPolling(batchIdRef: Ref<number | null>) {
  const batch = ref<AiBatch | null>(null)
  const polling = ref(false)
  let timer: number | null = null

  async function fetchOnce(id: number): Promise<void> {
    try {
      batch.value = await getAiBatch(id)
    } catch {
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

  return { batch, polling, startPolling, stopPolling }
}
