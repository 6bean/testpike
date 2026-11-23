import { reactive } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessageBox } from 'element-plus'


export interface DraftGuard {
  
  snapshot: () => void
  
  isDirty: () => boolean
  
  beforeClose: (done: () => void) => void
  
  discard: () => void
}

export function useDraftGuard(form: Record<string, unknown>, reset: () => void): DraftGuard {
  const { t } = useI18n()
  const state = reactive({ baseline: '' })

  function snapshot(): void {
    state.baseline = JSON.stringify(form)
  }

  function isDirty(): boolean {
    return JSON.stringify(form) !== state.baseline
  }

  function discard(): void {
    reset()
    snapshot()
  }

  function beforeClose(done: () => void): void {
    if (!isDirty()) {
      done()
      return
    }
    ElMessageBox.confirm(t('common.unsavedTip'), t('common.unsavedTitle'), {
      confirmButtonText: t('common.discardDraft'),
      cancelButtonText: t('common.keepEditing'),
      type: 'warning',
    })
      .then(() => {
        discard()
        done()
      })
      .catch(() => {
      })
  }

  snapshot()
  return { snapshot, isDirty, beforeClose, discard }
}
