import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import { checkCaseSimilarity, type SimilarityHit } from '@/api/case'


export function useSimilarityHint() {
  const { t } = useI18n()
  
  const dialogRef = ref<{ open: (hits: SimilarityHit[], draftText: string) => Promise<boolean> } | null>(null)
  
  const similarityChecking = ref(false)
  
  const similarityUnavailable = ref(false)

  
  async function gate(projectId: number, queryText: string, excludeCaseId: number | null): Promise<boolean> {
    if (!useAuthStore().hasPerm('perm:case:similarity-hint')) return true
    similarityChecking.value = true
    similarityUnavailable.value = false
    try {
      const hits = await checkCaseSimilarity({
        projectId,
        queryText,
        excludeCaseId: excludeCaseId ?? undefined,
      })
      if (hits.length === 0) return true
      return dialogRef.value ? await dialogRef.value.open(hits, queryText) : true
    } catch {
      similarityUnavailable.value = true
      ElMessage.warning(t('case.similarityUnavailable'))
      return true
    } finally {
      similarityChecking.value = false
    }
  }

  return { dialogRef, similarityChecking, similarityUnavailable, gate }
}


export function buildSimilarityQueryText(parts: {
  title: string
  expectedResult?: string
  steps: Array<{ description: string }>
}): string {
  return [parts.title.trim(), (parts.expectedResult ?? '').trim(),
    ...parts.steps.map((s) => s.description.trim()).filter(Boolean)]
    .filter(Boolean)
    .join('\n')
}
