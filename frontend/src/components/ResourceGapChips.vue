<!--
 Resource-gap declaration chip row .

 Consumes the tail-window process events of one script-generation task and
 human one-click actions the design ruling defines: "add the name and
 regenerate" (parent re-submits generation) and "ignore" (parent marks the
 task dismissed). The row is absent when there is no declaration or the
 human already dismissed it — a soft signal that never blocks the script
 block around it. Parsing lives in the shared extractResourceGaps helper
 (single source with the tail drawer face).
-->
<template>
  <div
    v-if="visible.length > 0"
    class="resource-gap-row"
    data-test="resource-gap-row"
  >
    <span class="resource-gap-label" data-test="resource-gap-label">{{ t('caseDetail.resourceGapLabel') }}</span>
    <el-tag
      v-for="gap in visible"
      :key="gap.cursor"
      type="warning"
      size="small"
      class="resource-gap-chip"
      :data-test="`resource-gap-chip-${gap.cursor}`"
    >{{ gap.text }}</el-tag>
    <span class="resource-gap-actions">
      <el-button
        type="primary"
        size="small"
        data-test="resource-gap-regen"
        @click="emit('regen')"
      >{{ t('caseDetail.resourceGapRegen') }}</el-button>
      <el-button
        size="small"
        data-test="resource-gap-dismiss"
        @click="emit('dismiss')"
      >{{ t('caseDetail.resourceGapDismiss') }}</el-button>
    </span>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ExtractionEvent } from '@/api/taskEvents'
import { extractResourceGaps } from '@/api/taskEvents'

const props = defineProps<{
  /** Tail window of the bound generation task (mixed levels allowed). */
  events: ExtractionEvent[]
  /** True once the human chose "ignore" for this task's declarations. */
  dismissed?: boolean
}>()

const emit = defineEmits<{ regen: []; dismiss: [] }>()

const { t } = useI18n()

const visible = computed(() => (props.dismissed ? [] : extractResourceGaps(props.events)))
</script>

<style scoped>
.resource-gap-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin: 8px 0;
  padding: 8px 12px;
  border: 1px solid var(--el-color-warning-light-5);
  border-radius: 6px;
  background: var(--el-color-warning-light-9);
}

.resource-gap-label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.resource-gap-actions {
  margin-left: auto;
  display: inline-flex;
  gap: 8px;
}
</style>
