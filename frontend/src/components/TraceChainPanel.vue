<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import {
  EXEC_STATUS_LABEL, TRACE_TYPE_LABEL,
  type TraceChainResult, type TraceNodeItem, type TraceNodeType,
} from '@/api/trace'

const props = defineProps<{
  chain: TraceChainResult | null
  loading?: boolean
}>()

const router = useRouter()
const { t } = useI18n()

const TYPE_TAG: Record<TraceNodeType, 'primary' | 'success' | 'danger'> = {
  REQUIREMENT: 'primary',
  CASE: 'success',
  DEFECT: 'danger',
}

const nodes = computed(() => props.chain?.nodes ?? [])
const hasChain = computed(() => !!props.chain && (nodes.value.length > 0))

function openNode(node: TraceNodeItem): void {
  if (node.routePath) router.push(node.routePath)
}
</script>

<template>
  <div v-loading="props.loading" class="trace-panel" data-test="trace-panel">
    <template v-if="props.chain">
      
      <div class="trace-row trace-root" :data-test="'trace-root-' + props.chain.root.type.toLowerCase()">
        <el-tag :type="TYPE_TAG[props.chain.root.type]" size="small">
          {{ TRACE_TYPE_LABEL[props.chain.root.type] }}
        </el-tag>
        <el-link type="primary" underline="never" class="trace-label" data-test="trace-root-link"
          @click="openNode(props.chain.root)">
          {{ props.chain.root.label }}
        </el-link>
        <span v-if="props.chain.root.statusText" class="trace-status">{{ props.chain.root.statusText }}</span>
        <span v-if="props.chain.root.lastBatchName" class="trace-exec" data-test="trace-root-exec">
          {{ t('components.lastBatchPrefix') }}{{ props.chain.root.lastBatchName }}
          <el-tag size="small" :type="props.chain.root.lastExecStatus === 3 ? 'success' : 'warning'">
            {{ EXEC_STATUS_LABEL[props.chain.root.lastExecStatus ?? 0] ?? props.chain.root.lastExecStatus }}
          </el-tag>
          <span class="trace-time">{{ props.chain.root.lastExecTime }}</span>
        </span>
      </div>

      <el-divider style="margin: 8px 0" />

      
      <div v-if="!hasChain" class="trace-empty" data-test="trace-empty">
        <el-empty :image-size="60" :description="t('components.chainEmpty')" />
      </div>

      <div v-else class="trace-nodes">
        <div
          v-for="node in nodes"
          :key="node.type + '-' + node.id"
          class="trace-row"
          :data-test="'trace-node-' + node.type.toLowerCase()"
        >
          <el-tag :type="TYPE_TAG[node.type]" size="small">
            {{ TRACE_TYPE_LABEL[node.type] }}
          </el-tag>
          <el-link type="primary" underline="never" class="trace-label" @click="openNode(node)">
            {{ node.label }}
          </el-link>
          <span v-if="node.statusText" class="trace-status">{{ node.statusText }}</span>
          <span v-if="node.type === 'CASE' && node.lastBatchName" class="trace-exec" data-test="trace-case-exec">
            {{ t('components.lastBatchPrefix') }}{{ node.lastBatchName }}
            <el-tag size="small" :type="node.lastExecStatus === 3 ? 'success' : 'warning'">
              {{ EXEC_STATUS_LABEL[node.lastExecStatus ?? 0] ?? node.lastExecStatus }}
            </el-tag>
            <span class="trace-time">{{ node.lastExecTime }}</span>
          </span>
          <span v-else-if="node.type === 'CASE'" class="trace-exec trace-exec-none">{{ t('components.neverBatched') }}</span>
        </div>
      </div>

      <div v-if="props.chain.truncated" class="trace-truncated" data-test="trace-truncated">
        {{ t('components.traceTruncated', { shown: nodes.length, total: props.chain.count }) }}
      </div>
    </template>
  </div>
</template>

<style scoped>
.trace-panel {
  min-height: 80px;
}
.trace-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 0;
  border-bottom: 1px dashed var(--el-border-color-lighter);
}
.trace-row:last-child {
  border-bottom: none;
}
.trace-label {
  font-weight: 500;
  max-width: 360px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.trace-status {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.trace-exec {
  color: var(--el-text-color-regular);
  font-size: 12px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.trace-exec-none {
  color: var(--el-text-color-placeholder);
}
.trace-time {
  color: var(--el-text-color-secondary);
}
.trace-truncated {
  margin-top: 8px;
  color: var(--el-color-warning);
  font-size: 12px;
}
</style>
