<!--
 Request drag-reorder panel for the API test rail .
 Posture = StepReorderPanel (case domain precedent): the whole state
 machine lives in useStepDragSort, row rendering/keyboard/edge-scroll in
 the shared StepDragList shell — no second drag engine (red line 6).
 The buffer carries request ids next to the two-field description the
 kernel expects; saving emits the FULL ordered id list so the parent can
 PUT it to the door. The server reference change (reload after
 save) resets the buffer, which is what clears the dirty flag.
 ⚠️ Rows render the full collection list, not the scroll window — the
 entry gate keeps this mode closed while a filter is active, because the
 core door validates the full order and a partial submit would 400.
-->
<template>
  <div class="ap-reorder-panel" data-test="apitest-reorder-panel">
    <div class="ap-reorder-head">
      <span class="ap-reorder-hint">
        <el-icon><Rank /></el-icon>
        {{ t('apitest.reorder.hint') }}
      </span>
      <el-button link size="small" data-test="apitest-reorder-close" @click="emit('close')">
        {{ t('apitest.reorder.close') }}
      </el-button>
    </div>
    <StepDragList
      :core="core"
      :empty-text="t('apitest.reorder.empty')"
      :aria-label="t('apitest.reorder.panelAria')"
    />
    <div class="ap-reorder-actions">
      <el-button size="small" :disabled="!core.canUndo.value" data-test="apitest-reorder-undo" @click="core.undo()">
        {{ t('apitest.reorder.undo') }}
      </el-button>
      <el-tooltip :disabled="core.dirty.value" :content="t('apitest.reorder.noChangeTip')" placement="top">
        <span>
          <el-button
            size="small"
            type="primary"
            :disabled="!core.dirty.value"
            :loading="saving"
            data-test="apitest-reorder-save"
            @click="save"
          >
            {{ t('apitest.reorder.save') }}
          </el-button>
        </span>
      </el-tooltip>
    </div>
  </div>
</template>

<script setup lang="ts">
import { watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { Rank } from '@element-plus/icons-vue'
import StepDragList from '@/components/StepDragList.vue'
import { useStepDragSort, type StepBufferItem } from '@/composables/useStepDragSort'
import type { ApiRequestItem } from '@/api/apiTest'

const props = defineProps<{
  /** Server-side request list of the picked collection — the single source.
   *  A new array reference (reload after save / collection switch) resets
   *  the local buffer; never pass an inline `|| []` from the template. */
  requests: ApiRequestItem[]
  /** Parent PUT in flight (save button loading). */
  saving?: boolean
}>()

const emit = defineEmits<{
  /** Save confirmed: full ordered request-id list in the current buffer order. */
  (e: 'save', orderedRequestIds: number[]): void
  /** Leave reorder mode (windowed browsing list comes back). */
  (e: 'close'): void
}>()

const { t } = useI18n()

/** Kernel item extended with the request id (description/isAssert are the
 *  kernel contract; extra fields survive its shallow snapshot copies). */
type RequestDragItem = StepBufferItem & { id: number }

function toDragItems(list: ApiRequestItem[]): RequestDragItem[] {
  return list.map((r) => ({ id: r.id, description: `${r.method} ${r.name}`, isAssert: 0 }))
}

const core = useStepDragSort()

watch(
  () => props.requests,
  (list) => core.reset(toDragItems(list || [])),
  { immediate: true },
)

function save(): void {
  const ids = (core.ordered.value as unknown as RequestDragItem[]).map((item) => item.id)
  emit('save', ids)
}
</script>

<style scoped>
.ap-reorder-panel {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-height: 0;
}
.ap-reorder-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.ap-reorder-hint {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.ap-reorder-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
.ap-reorder-panel :deep(.step-drag-list) {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}
</style>
