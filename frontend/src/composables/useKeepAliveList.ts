import { onActivated, onDeactivated } from 'vue'


export const SCROLL_PREFIX = 'tp-list-scroll:'

export function useKeepAliveList(onRefresh: () => void | Promise<void>): void {
  let activatedCount = 0

  function mainEl(): HTMLElement | null {
    return document.querySelector('.layout-main')
  }

  onActivated((): void => {
    activatedCount++
    if (activatedCount > 1) {
      const saved = sessionStorage.getItem(SCROLL_PREFIX + location.hash.split('?')[0])
      const el = mainEl()
      if (el && saved) el.scrollTop = Number(saved) || 0
      void onRefresh()
    }
  })

  onDeactivated((): void => {
    const el = mainEl()
    if (el) sessionStorage.setItem(SCROLL_PREFIX + location.hash.split('?')[0], String(el.scrollTop))
  })
}
