import { onUnmounted, ref, type Ref } from 'vue'
import { getAiTask, type AiTask } from '@/api/ai'


export function useAiTaskPolling(taskIdRef: Ref<number | null>) {
  const task = ref<AiTask | null>(null)
  const polling = ref(false)
  let timer: number | null = null

  async function fetchOnce(id: number): Promise<void> {
    try {
      task.value = await getAiTask(id)
    } catch {
    }
  }

  
  async function startPolling(): Promise<void> {
    const id = taskIdRef.value
    if (!id) return
    await fetchOnce(id)
    if (timer !== null) return
    polling.value = true
    timer = window.setInterval(() => {
      const cur = taskIdRef.value
      if (!cur) {
        stopPolling()
        return
      }
      if (task.value && (task.value.status === 3 || task.value.status === 4 || task.value.status === 5)) {
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

  return { task, polling, startPolling, stopPolling }
}
