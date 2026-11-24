
<template>
  <div class="reorder-panel" data-test="flow-reorder-panel">
    <div class="reorder-head">
      <span class="reorder-title">{{ t('stepReorder.title') }}</span>
      <span class="reorder-hint">
        <el-icon><Rank /></el-icon>
        {{ t('stepReorder.hint') }}
      </span>
    </div>
    <StepDragList :core="core" :empty-text="t('stepReorder.empty')" renamable @rename="openAliasDialog" />
    <div class="reorder-actions">
      <el-button size="small" :disabled="!core.canUndo.value" data-test="flow-reorder-undo" @click="core.undo()">
        {{ t('stepReorder.undo') }}
      </el-button>
      <el-tooltip :disabled="core.dirty.value" :content="t('stepReorder.saveTip')" placement="top">
        <span>
          <el-button size="small" type="primary" :disabled="!core.dirty.value" data-test="flow-reorder-save" @click="diffVisible = true">
            {{ t('stepReorder.save') }}
          </el-button>
        </span>
      </el-tooltip>
    </div>

 
    <el-dialog
      v-model="diffVisible"
      :title="t('stepReorder.diffTitle')"
      width="800px"
      destroy-on-close
      data-test="flow-diff-dialog"
    >
      <DataTablePage :data="core.diffRows.value" size="small" border data-test="flow-diff-table">
        <el-table-column prop="seq" :label="t('stepReorder.colSeq')" width="60" />
        <el-table-column :label="t('stepReorder.colBefore')" min-width="260">
          <template #default="{ row }">
            <div class="flow-diff-cell" :class="{ 'flow-diff-changed': row.before !== row.after }" data-test="flow-diff-before">
              {{ row.before ?? '—' }}
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('stepReorder.colAfter')" min-width="260">
          <template #default="{ row }">
            <div class="flow-diff-cell flow-diff-after" :class="{ 'flow-diff-changed': row.before !== row.after }" data-test="flow-diff-after">
              {{ row.after ?? '—' }}
            </div>
          </template>
        </el-table-column>
      </DataTablePage>
      <div class="flow-diff-tip">{{ t('stepReorder.diffTip') }}</div>
      <template #footer>
        <el-button data-test="flow-diff-cancel" @click="diffVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" data-test="flow-diff-confirm" @click="confirmSave">
          {{ t('stepReorder.confirmSave') }}
        </el-button>
      </template>
    </el-dialog>
 
    <el-dialog
      v-model="aliasVisible"
      :title="t('stepReorder.renameTitle', { n: aliasStepNo })"
      width="480px"
      destroy-on-close
      data-test="step-alias-dialog"
    >
      <el-form @submit.prevent="saveStepAlias">
        <el-form-item :label="t('stepReorder.renameLabel')">
          <el-input v-model="aliasName" data-test="step-alias-name-input"
            :placeholder="t('stepReorder.renameNamePh')" maxlength="64" />
        </el-form-item>
        <p class="step-alias-hint" data-test="step-alias-hint">
          {{ t('stepReorder.renameHint', { name: aliasName.trim() || t('stepReorder.renameNameSample') }) }}
        </p>
        <p v-if="existingAlias" class="step-alias-current" data-test="step-alias-current">
          {{ t('stepReorder.renameCurrent', { name: existingAlias }) }}
        </p>
      </el-form>
      <template #footer>
        <el-button data-test="step-alias-cancel" @click="aliasVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="aliasSaving" data-test="step-alias-save" @click="saveStepAlias">
          {{ t('stepReorder.renameSave') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Rank } from '@element-plus/icons-vue'
import type { CaseStep } from '@/api/case'
import { bizErrorMsg } from '@/utils/bizError'
import { createStepAlias, deleteStepAlias, listStepAliases, type StepAliasItem } from '@/api/apiTest'
import StepDragList from '@/components/StepDragList.vue'
import { useStepDragSort } from '@/composables/useStepDragSort'

const props = defineProps<{
  
  steps?: CaseStep[]
  
  saving?: boolean
  
  projectId?: number
}>()

const emit = defineEmits<{
  
  (e: 'save', reordered: { stepNo: number; description: string; isAssert: number }[]): void
}>()

const { t } = useI18n()

const core = useStepDragSort()

const diffVisible = ref(false)


watch(
  () => props.steps,
  (list) => {
    core.reset((list || []).map((s) => ({ description: s.description, isAssert: s.isAssert ?? 0 })))
    diffVisible.value = false
  },
  { immediate: true },
)

function confirmSave(): void {
  emit('save', core.toOrderedSteps())
  diffVisible.value = false
}

const aliasVisible = ref(false)
const aliasName = ref('')
const aliasSaving = ref(false)
const aliasStepNo = ref(0)

const aliases = ref<StepAliasItem[]>([])

const existingAlias = computed(() => {
  const hit = aliases.value.find((a) => a.kind === 'step' && a.target === String(aliasStepNo.value))
  return hit ? hit.aliasName : ''
})

async function loadAliases(): Promise<void> {
  if (!props.projectId) {
    aliases.value = []
    return
  }
  try {
    aliases.value = await listStepAliases(props.projectId)
  } catch {
  }
}


function openAliasDialog(idx: number): void {
  if (!props.projectId) {
    ElMessage.warning(t('stepReorder.renameNoProject'))
    return
  }
  const serverSteps = props.steps || []
  const stepNo = serverSteps[idx]?.stepNo ?? idx + 1
  aliasStepNo.value = stepNo
  aliasName.value = existingAlias.value
  aliasVisible.value = true
}

async function saveStepAlias(): Promise<void> {
  const name = aliasName.value.trim()
  if (!name) {
    ElMessage.warning(t('stepReorder.renameNameRequired'))
    return
  }
  if (name === existingAlias.value) {
    aliasVisible.value = false
    return
  }
  aliasSaving.value = true
  try {
    const stale = aliases.value.find((a) => a.kind === 'step' && a.target === String(aliasStepNo.value))
    await createStepAlias({
      projectId: props.projectId || 0, aliasName: name, kind: 'step', target: String(aliasStepNo.value),
    })
    if (stale) {
      await deleteStepAlias(stale.id).catch(() => undefined)
    }
    ElMessage.success(t('stepReorder.renameSaved'))
    aliasVisible.value = false
    await loadAliases()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('stepReorder.renameSaveFail')))
  } finally {
    aliasSaving.value = false
  }
}

watch(() => props.projectId, () => { void loadAliases() }, { immediate: true })


function discard(): void {
  core.discard()
}

defineExpose({ discard })
</script>

<style scoped>
.reorder-panel {
  flex: 0 0 320px;
  min-width: 0;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 10px 12px;
  background: var(--el-fill-color-blank);
}
.reorder-head {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 8px;
}
.reorder-title {
  font-weight: 600;
  font-size: 13px;
}
.reorder-hint {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.reorder-actions {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}
.flow-diff-cell {
  white-space: pre-wrap;
  word-break: break-all;
}
.flow-diff-after {
  background: var(--el-color-success-light-9);
}
.flow-diff-changed {
  font-weight: 600;
}
.flow-diff-tip {
  margin-top: 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.step-alias-hint {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.step-alias-current {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--el-color-success);
}
</style>
