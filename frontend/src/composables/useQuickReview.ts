import { computed, ref } from 'vue'
import type { CaseItem } from '@/api/case'


export interface QuickReviewActions {
  onPass: (row: CaseItem) => void
  onNeedChange: (row: CaseItem) => void
  onReject: (row: CaseItem) => void
  
  onBatch?: () => void
}

export function useQuickReview(getList: () => CaseItem[], actions: QuickReviewActions) {
  const quickMode = ref(false)
  const activeIndex = ref(-1)
  const activeRow = computed(() => {
    const list = getList()
    return activeIndex.value >= 0 && activeIndex.value < list.length ? list[activeIndex.value] : null
  })

  function toggleQuickMode(): void {
    quickMode.value = !quickMode.value
    activeIndex.value = quickMode.value && getList().length ? 0 : -1
  }

  
  function afterLoad(): void {
    const len = getList().length
    if (activeIndex.value >= len) activeIndex.value = len - 1
    if (quickMode.value && activeIndex.value < 0 && len) activeIndex.value = 0
  }

  function scrollToActive(): void {
    requestAnimationFrame(() => {
      document.querySelector('.quick-active-row')?.scrollIntoView({ block: 'nearest' })
    })
  }

  function onKeydown(e: KeyboardEvent): void {
    if (!quickMode.value || getList().length === 0) return
    const target = e.target as HTMLElement | null
    const tag = target?.tagName
    if (tag === 'INPUT' || tag === 'TEXTAREA' || target?.isContentEditable) return
    switch (e.key.toLowerCase()) {
      case 'j': {
        e.preventDefault()
        activeIndex.value = Math.min(activeIndex.value + 1, getList().length - 1)
        scrollToActive()
        break
      }
      case 'k': {
        e.preventDefault()
        activeIndex.value = Math.max(activeIndex.value - 1, 0)
        scrollToActive()
        break
      }
      case 'enter': {
        e.preventDefault()
        if (activeRow.value) actions.onPass(activeRow.value)
        break
      }
      case 'r': {
        e.preventDefault()
        if (activeRow.value) actions.onNeedChange(activeRow.value)
        break
      }
      case 'x': {
        e.preventDefault()
        if (activeRow.value) actions.onReject(activeRow.value)
        break
      }
      case 's': {
        e.preventDefault()
        activeIndex.value = Math.min(activeIndex.value + 1, getList().length - 1)
        scrollToActive()
        break
      }
      case 'b': {
        e.preventDefault()
        actions.onBatch?.()
        break
      }
      case 'escape': {
        quickMode.value = false
        activeIndex.value = -1
        break
      }
      default:
        break
    }
  }

  
  function rowClassName({ row }: { row: CaseItem }): string {
    if (quickMode.value && activeRow.value?.id === row.id) return 'quick-active-row'
    return ''
  }

  
  function onRowClick(row: CaseItem): void {
    if (!quickMode.value) return
    const idx = getList().findIndex((r) => r.id === row.id)
    if (idx >= 0) activeIndex.value = idx
  }

  return { quickMode, activeRow, toggleQuickMode, onKeydown, afterLoad, rowClassName, onRowClick }
}
