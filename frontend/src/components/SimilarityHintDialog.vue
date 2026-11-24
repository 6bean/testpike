<template>
  <el-dialog
    v-model="visible"
    :title="t('case.similarityTitle')"
    width="880px"
    destroy-on-close
    :close-on-click-modal="false"
    data-test="similarity-dialog"
    @closed="answer(false)"
  >
    <el-alert type="warning" :closable="false" class="similarity-tip" data-test="similarity-tip">
      {{ t('case.similarityTip') }}
    </el-alert>
    <div class="similarity-list" data-test="similarity-list">
      <div v-for="hit in hits" :key="hit.caseId" class="similarity-item" data-test="similarity-item">
        <div class="item-head">
          <el-tag :type="hit.level === 'STRONG' ? 'danger' : 'warning'" size="small" data-test="similarity-level">
            {{ hit.level === 'STRONG' ? t('case.similarityStrong') : t('case.similarityHint') }}
          </el-tag>
          <span class="item-title" :data-test="`similarity-title-${hit.caseId}`">{{ hit.title }}</span>
          <span class="item-score mono" :data-test="`similarity-score-${hit.caseId}`">
            {{ (hit.similarity * 100).toFixed(1) }}%
          </span>
          <el-button
            link
            type="primary"
            :data-test="`similarity-diff-${hit.caseId}`"
            @click="toggleDiff(hit.caseId)"
          >
            {{ expanded.has(hit.caseId) ? t('case.similarityCollapse') : t('case.similarityViewDiff') }}
          </el-button>
          <el-button
            link
            type="primary"
            :data-test="`similarity-open-${hit.caseId}`"
            @click="openCase(hit.caseId)"
          >
            {{ t('case.similarityOpenCase') }}
          </el-button>
        </div>
        <div v-if="expanded.has(hit.caseId)" class="diff-row" :data-test="`similarity-diff-row-${hit.caseId}`">
          <div class="diff-col">
            <div class="diff-col-title">{{ t('case.similarityDraft') }}</div>
            <pre class="diff-pre" data-test="similarity-diff-draft">{{ draftText }}</pre>
          </div>
          <div class="diff-col">
            <div class="diff-col-title">{{ t('case.similarityHitContent') }}</div>
            <pre class="diff-pre">{{ hit.content }}</pre>
          </div>
        </div>
      </div>
    </div>
    <template #footer>
      <el-button @click="answer(false)">{{ t('common.cancel') }}</el-button>
      <el-button
        type="primary"
        data-test="similarity-continue"
        @click="answer(true)"
      >
        {{ t('case.similarityContinue') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import type { SimilarityHit } from '@/api/case'

const { t } = useI18n()
const router = useRouter()

const visible = ref(false)
const hits = ref<SimilarityHit[]>([])
const draftText = ref('')
const expanded = ref(new Set<number>())
let resolver: ((go: boolean) => void) | null = null


function open(newHits: SimilarityHit[], newDraftText: string): Promise<boolean> {
  hits.value = newHits
  draftText.value = newDraftText
  expanded.value = new Set()
  visible.value = true
  return new Promise<boolean>((resolve) => { resolver = resolve })
}

function toggleDiff(caseId: number): void {
  const next = new Set(expanded.value)
  if (next.has(caseId)) next.delete(caseId)
  else next.add(caseId)
  expanded.value = next
}

function answer(go: boolean): void {
  visible.value = false
  resolver?.(go)
  resolver = null
}

function openCase(caseId: number): void {
  answer(false)
  void router.push(`/cases/${caseId}`)
}

defineExpose({ open })
</script>

<style scoped>
.similarity-tip { margin-bottom: 12px; }
.similarity-item { padding: 8px 0; border-bottom: 1px solid var(--el-border-color-lighter); }
.item-head { display: flex; align-items: center; gap: 8px; }
.item-title { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.item-score { color: var(--el-text-color-secondary); }
.diff-row { display: flex; gap: 12px; margin-top: 8px; }
.diff-col { flex: 1; min-width: 0; }
.diff-col-title { font-size: 12px; color: var(--el-text-color-secondary); margin-bottom: 4px; }
.diff-pre {
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 220px;
  overflow: auto;
  margin: 0;
  padding: 8px;
  background: var(--el-fill-color-light);
  border-radius: 4px;
  font-size: 12px;
}
</style>
