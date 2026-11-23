<template>
  <div class="ctx-panel" data-test="review-ctx-panel">
    <div class="ctx-header">
      <span class="ctx-title">{{ t('review.ctxTitle') }}</span>
      <el-tag v-if="caseNo" size="small" class="mono" data-test="ctx-case-no">{{ caseNo }}</el-tag>
    </div>

    
    <el-empty v-if="!caseId" :image-size="48" :description="t('review.ctxNoSelection')" />

    <template v-else>
      
      <div class="ctx-section" data-test="ctx-requirements">
        <div class="ctx-section-title">
          <el-icon><Link /></el-icon>
          <span>{{ t('review.ctxRequirements') }}</span>
          <el-tag
            size="small"
            :type="requirements.length ? 'success' : 'warning'"
            data-test="ctx-coverage-status"
          >
            {{ requirements.length ? t('review.covered') : t('review.uncovered') }}
          </el-tag>
        </div>
        <div v-if="reqLoading" class="ctx-loading"><el-skeleton :rows="2" animated /></div>
        <template v-else>
          <div v-for="r in requirements" :key="r.id" class="ctx-item" :data-test="`ctx-req-${r.id}`">
            <span class="ctx-item-title" :title="r.title">{{ r.title }}</span>
            <el-tag size="small" :type="REQUIREMENT_STATUS[r.status]?.tag ?? 'info'">
              {{ REQUIREMENT_STATUS[r.status]?.label ?? r.status }}
            </el-tag>
          </div>
          <div v-if="!requirements.length" class="text-secondary ctx-empty-line">{{ t('review.ctxNoRequirements') }}</div>
        </template>
      </div>

      
      <div class="ctx-section" data-test="ctx-review-history">
        <div class="ctx-section-title">
          <el-icon><Clock /></el-icon>
          <span>{{ t('review.ctxHistory') }}</span>
        </div>
        <div v-if="logsLoading" class="ctx-loading"><el-skeleton :rows="2" animated /></div>
        <template v-else>
          <el-timeline v-if="logs.length" class="ctx-timeline">
            <el-timeline-item
              v-for="log in logs"
              :key="log.id"
              :timestamp="logTime(log.createdAt)"
              :type="ACTION_DOT_TYPE[log.action] ?? 'info'"
              placement="top"
              size="normal"
              :data-test="`ctx-log-${log.id}`"
            >
              <div class="ctx-log-line">
                <el-tag size="small" :type="ACTION_TAG_TYPE[log.action] ?? 'info'">{{ logActionLabel(log.action) }}</el-tag>
                <span class="ctx-log-operator">{{ log.operatorName || `#${log.userId}` }}</span>
              </div>
              <div v-if="log.feedbackTypes?.length" class="ctx-log-types" data-test="ctx-log-types">
                <el-tag
                  v-for="(ft, i) in log.feedbackTypes"
                  :key="ft"
                  size="small"
                  type="info"
                  class="ctx-log-type-tag"
                  :data-test="`ctx-log-type-${i}`"
                >{{ typeLabel(ft) }}</el-tag>
              </div>
              <div v-if="log.remark" class="ctx-log-remark">{{ log.remark }}</div>
            </el-timeline-item>
          </el-timeline>
          <div v-else class="text-secondary ctx-empty-line" data-test="ctx-history-empty">{{ t('review.ctxNoHistory') }}</div>
        </template>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { timeAgo } from '@/utils/timeAgo'
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { Clock, Link } from '@element-plus/icons-vue'
import { listCaseReviewLogs, type CaseReviewLogItem } from '@/api/case'
import { listCaseRequirements, REQUIREMENT_STATUS, type RequirementItem } from '@/api/requirement'
import { useObjectSync } from '@/composables/useObjectSync'

const props = defineProps<{ caseId: number | null; caseNo?: string }>()

const { t } = useI18n()
const requirements = ref<RequirementItem[]>([])
const logs = ref<CaseReviewLogItem[]>([])
const reqLoading = ref(false)
const logsLoading = ref(false)


const ACTION_TAG_TYPE: Record<string, 'success' | 'warning' | 'danger' | 'info'> = {
  '\u901a\u8fc7': 'success', '\u9700\u4fee\u6539': 'warning', '\u62d2\u7edd': 'danger',
}
const ACTION_DOT_TYPE: Record<string, 'primary' | 'success' | 'warning' | 'danger' | 'info'> = {
  '\u901a\u8fc7': 'success', '\u9700\u4fee\u6539': 'warning', '\u62d2\u7edd': 'danger',
}
const ACTION_LOG_KEYS: Record<string, string> = {
  '\u901a\u8fc7': 'dict.reviewLog.pass', '\u9700\u4fee\u6539': 'dict.reviewLog.revise', '\u62d2\u7edd': 'dict.reviewLog.reject',
}
function logActionLabel(action: string): string {
  const key = ACTION_LOG_KEYS[action]
  return key ? t(key) : action
}

const TYPE_LABEL_KEYS: Record<string, string> = {
  '\u6b65\u9aa4\u4e0d\u51c6\u786e': 'case.feedbackType.step_inaccurate',
  '\u9884\u671f\u7ed3\u679c\u9519\u8bef': 'case.feedbackType.expected_wrong',
  '\u9009\u62e9\u5668\u95ee\u9898': 'case.feedbackType.selector_issue',
  '\u903b\u8f91\u9057\u6f0f': 'case.feedbackType.logic_missing',
  '\u63aa\u8f9e\u4e0d\u51c6\u786e': 'case.feedbackType.wording',
  '\u5b9a\u4f4d\u9519\u8bef': 'case.feedbackType.locate_error',
}
function typeLabel(v: string): string {
  const key = TYPE_LABEL_KEYS[v]
  return key ? t(key) : v
}

function logTime(v?: string): string {
  return timeAgo(v)
}

let reqSeq = 0
let logSeq = 0

async function loadRequirements(caseId: number): Promise<void> {
  const seq = ++reqSeq
  reqLoading.value = true
  try {
    const data = await listCaseRequirements(caseId)
    if (seq === reqSeq) requirements.value = data || []
  } catch {
    if (seq === reqSeq) requirements.value = []
  } finally {
    if (seq === reqSeq) reqLoading.value = false
  }
}

async function loadLogs(caseId: number): Promise<void> {
  const seq = ++logSeq
  logsLoading.value = true
  try {
    const data = await listCaseReviewLogs(caseId)
    if (seq === logSeq) logs.value = data || []
  } catch {
    if (seq === logSeq) logs.value = []
  } finally {
    if (seq === logSeq) logsLoading.value = false
  }
}


function refresh(): void {
  if (props.caseId) {
    void loadRequirements(props.caseId)
    void loadLogs(props.caseId)
  }
}

// : a landed CASE_REVIEW decision appends the review trace of
// the case this panel renders. Re-read silently, pinned to the focused row
// through the objectId anchor so landings of any other case never touch this
// panel (BG iron rules 2-3).
useObjectSync({
  watch: ['CASE_REVIEW'],
  anchor: (event) => event.objectId !== null && event.objectId === props.caseId,
  onSync: () => refresh(),
})

watch(() => props.caseId, (id) => {
  if (id) {
    void loadRequirements(id)
    void loadLogs(id)
  } else {
    requirements.value = []
    logs.value = []
  }
}, { immediate: true })

defineExpose({ refresh })
</script>

<style scoped lang="scss">
.ctx-panel {
  height: 100%;
  overflow-y: auto;
}

.ctx-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.ctx-title {
  font-size: 16px;
  font-weight: 600;
}

.ctx-section {
  margin-bottom: 16px;
}

.ctx-section-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--tp-text);
  margin-bottom: 8px;
}

.ctx-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 6px;
  background: var(--tp-bg-hover, rgba(127, 127, 127, 0.06));
  margin-bottom: 6px;
}

.ctx-item-title {
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
}

.ctx-empty-line {
  font-size: 12px;
  padding: 4px 2px;
}

.ctx-loading {
  padding: 4px 2px;
}

.ctx-timeline {
  padding-left: 2px;

  :deep(.el-timeline-item__timestamp) {
    font-size: 11px;
  }
}

.ctx-log-line {
  display: flex;
  align-items: center;
  gap: 8px;
}

.ctx-log-operator {
  font-size: 12px;
  color: var(--tp-text-secondary);
}

.ctx-log-types {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 4px;
}
.ctx-log-type-tag {
  max-width: 100%;
}
.ctx-log-remark {
  font-size: 12px;
  color: var(--tp-text);
  margin-top: 4px;
  word-break: break-all;
}
</style>
