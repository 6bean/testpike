<script setup lang="ts">
/**
 * Task-kind badge cell :
 * renders the single-source EXEC_TASK_TYPE vocabulary over an exec-task row
 * and deep-links pressure rows to their pinned run detail page. A pressure
 * row whose pinned run id is missing (dirty envelope) stays a plain badge —
 * honest absence, never a broken link. Both the project trial-run table and
 * the K2 executor task table consume this one piece (no second mapping).
 */
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { EXEC_TASK_TYPE, type ExecTask } from '@/api/executor'

const props = defineProps<{
  /** Exec task row (taskType + perfRunId trailing fields from the core read line). */
  row: Pick<ExecTask, 'taskType' | 'perfRunId'>
  /** Project scope carried into the run detail deep link (query, same shape as the ledger view). */
  projectId?: number | string | null
}>()

const router = useRouter()
const { t } = useI18n()

const meta = computed(() => (props.row.taskType != null ? EXEC_TASK_TYPE[props.row.taskType] ?? null : null))

/** Deep link target: only a pressure row with a pinned run id is clickable. */
const linkTarget = computed(() =>
  props.row.taskType === 5 && props.row.perfRunId != null && props.row.perfRunId > 0
    ? `/perf/run/${props.row.perfRunId}`
    : null,
)

function openRun(): void {
  if (!linkTarget.value) return
  router.push({ path: linkTarget.value, query: { projectId: String(props.projectId ?? '') } })
}
</script>

<template>
  <span v-if="meta">
    <el-tag
      v-if="linkTarget"
      :type="meta.type"
      size="small"
      class="perf-run-link"
      data-test="exec-task-type-perf-link"
      :title="t('exec.perfRunLinkHint')"
      @click="openRun"
    >
      {{ meta.label }}
    </el-tag>
    <el-tag v-else :type="meta.type" size="small" data-test="exec-task-type-badge">
      {{ meta.label }}
    </el-tag>
  </span>
  <span v-else class="muted">-</span>
</template>

<style scoped>
.perf-run-link {
  cursor: pointer;
}
.muted {
  color: var(--tp-text-secondary);
}
</style>
