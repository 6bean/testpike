import { ElMessage, ElMessageBox } from 'element-plus'
import type { Composer } from 'vue-i18n'
import { batchReview, REVIEW_ACTION_LABEL, type BatchReviewResult, type CaseItem, type ReviewAction } from '@/api/case'


export function useBatchReview(t: Composer['t']) {
  
  async function confirmBatchPass(targets: CaseItem[]): Promise<boolean> {
    const lowCount = targets.filter((r) => r.confidence === 3).length
    const msg = String(t('case.batchReviewConfirm', { n: targets.length, action: REVIEW_ACTION_LABEL.pass }))
      + (lowCount ? `<br/><span class="text-warning">${t('case.batchLowConfidenceHint', { n: lowCount })}</span>` : '')
    try {
      await ElMessageBox.confirm(msg, t('case.batchReviewTitle'), {
        confirmButtonText: t('case.batchPass'),
        cancelButtonText: t('common.cancel'),
        type: 'warning',
        dangerouslyUseHTMLString: true,
      })
      return true
    } catch {
      return false
    }
  }

  
  async function submitBatch(
    targets: CaseItem[], action: ReviewAction, remark: string, feedbackTypes?: string[],
  ): Promise<BatchReviewResult | null> {
    if (!targets.length) return null
    if ((action === 'need_change' || action === 'reject') && !remark.trim()) {
      ElMessage.warning(t('case.batchRemarkRequired'))
      return null
    }
    try {
      const lowCount = targets.filter((r) => r.confidence === 3).length
      const result = await batchReview({
        action,
        caseIds: targets.map((r) => r.id),
        remark: remark || undefined,
        confirmLowConfidence: lowCount > 0,
        feedbackTypes: feedbackTypes?.length ? feedbackTypes : undefined,
      })
      const succeeded = result.succeeded?.length ?? 0
      const skipped = result.skipped ?? []
      ElMessage.success(
        String(t('case.batchReviewSuccess', { succeeded }))
        + (skipped.length ? String(t('case.batchReviewSkipped', { skipped: skipped.length, reasons: skipped.map((s) => s.reason).join('；') })) : ''),
      )
      return result
    } catch (e) {
      const msg = (e as Error).message || ''
      if (msg.includes('403') || msg.includes('权限')) {
        ElMessage.error(t('case.batchReviewNoPermission'))
      } else {
        ElMessage.error(t('case.batchReviewFail', { msg }))
      }
      return null
    }
  }

  return { confirmBatchPass, submitBatch }
}
