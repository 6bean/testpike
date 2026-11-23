import { onBeforeUnmount, onMounted } from 'vue'




const SUBMIT_DEBOUNCE_MS = 500

let lastPressAt = 0


function findActiveDialog(): HTMLElement | null {
  const overlays = Array.from(document.querySelectorAll<HTMLElement>('.el-overlay')).reverse()
  for (const overlay of overlays) {
    if (getComputedStyle(overlay).display === 'none') continue
    if (overlay.querySelector('.el-message-box')) return null
    const dialog = overlay.querySelector<HTMLElement>('.el-dialog')
    if (dialog) return dialog
  }
  return null
}


function findSubmitButton(dialog: HTMLElement): HTMLButtonElement | null {
  const primaries = dialog.querySelectorAll<HTMLButtonElement>('.el-dialog__footer .el-button--primary')
  return primaries.length ? primaries[primaries.length - 1] : null
}

function onKeydown(e: KeyboardEvent): void {
  if (!(e.ctrlKey || e.metaKey) || e.key.toLowerCase() !== 's') return
  const dialog = findActiveDialog()
  if (!dialog) return
  e.preventDefault()
  const now = Date.now()
  if (now - lastPressAt < SUBMIT_DEBOUNCE_MS) return
  lastPressAt = now
  findSubmitButton(dialog)?.click()
}

export function useFormHotkeys(): void {
  onMounted(() => window.addEventListener('keydown', onKeydown))
  onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
}
