/**
 * R63 single-source error toast: every global rejection path shows the cause
 * line and, when the text carries a fix pointer, a dedicated "next step" line.
 * No guidance -> plain one-line error (the slot never renders an empty shell).
 */
import { ElMessage } from 'element-plus'
import { parseErrorGuidance, buildGuidanceHtml } from './errorGuidance'

/**
 * Show an on-screen error with the optional next-step slot.
 * @param message raw backend/i18n error text
 * @param nextStepLabel localized prefix for the guidance line (caller resolves i18n)
 * @param type toast severity; warning keeps pre-428 semantics while gaining the slot
 */
export function showGuidedError(
  message: string,
  nextStepLabel = '下一步',
  type: 'error' | 'warning' = 'error',
): void {
  const { reason, guidance } = parseErrorGuidance(message)
  if (!guidance) {
    if (type === 'warning') ElMessage.warning(reason)
    else ElMessage.error(reason)
    return
  }
  ElMessage({
    type,
    duration: 6000,
    showClose: true,
    dangerouslyUseHTMLString: true,
    message: buildGuidanceHtml(reason, guidance, nextStepLabel),
  })
}
