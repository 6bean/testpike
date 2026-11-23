<template>
  
  <el-dialog
    v-model="visible"
    draggable
    :title="t('case.autoAssignPreviewTitle')"
    width="600px"
    data-test="auto-assign-dialog"
    :close-on-click-modal="false"
    @open="onOpen"
  >
    <div v-loading="loading" data-test="auto-assign-body">
      <p class="auto-assign-summary" data-test="auto-assign-summary">
        {{ t('case.autoAssignHint', { n: plan.assignedCount, m: plan.byAssignee.length }) }}
      </p>
      <el-table border :data="plan.byAssignee" size="small" data-test="auto-assign-table" max-height="280">
        <el-table-column prop="name" :label="t('case.autoAssignColWho')" min-width="160" />
        <el-table-column prop="count" :label="t('case.autoAssignColCount')" width="120" align="center" />
      </el-table>
      <p class="text-secondary auto-assign-note">{{ t('case.autoAssignBalanceNote') }}</p>
    </div>
    <template #footer>
      <el-button data-test="auto-assign-cancel" @click="visible = false">{{ t('common.cancel') }}</el-button>
      <el-button
        type="primary"
        :loading="confirming"
        :disabled="loading || plan.assignedCount === 0"
        data-test="auto-assign-confirm"
        @click="onConfirm"
      >
        {{ t('case.autoAssignConfirm') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { autoAssignCases, type AutoAssignRow } from '@/api/case'


const props = defineProps<{ projectId: number | null }>()
const emit = defineEmits<{ (e: 'done', assignedCount: number): void }>()
const visible = defineModel<boolean>({ required: true })

const { t } = useI18n()
const loading = ref(false)
const confirming = ref(false)
const plan = reactive<{ assignedCount: number; byAssignee: AutoAssignRow[] }>({ assignedCount: 0, byAssignee: [] })

async function onOpen(): Promise<void> {
  if (!props.projectId) return
  loading.value = true
  plan.assignedCount = 0
  plan.byAssignee = []
  try {
    const result = await autoAssignCases({ projectId: props.projectId, preview: true })
    plan.assignedCount = result.assignedCount
    plan.byAssignee = result.byAssignee || []
    if (result.assignedCount === 0) {
      ElMessage.info(t('case.autoAssignEmpty'))
    }
  } catch {
  } finally {
    loading.value = false
  }
}

async function onConfirm(): Promise<void> {
  if (!props.projectId) return
  confirming.value = true
  try {
    const result = await autoAssignCases({ projectId: props.projectId, preview: false })
    ElMessage.success(t('case.autoAssignDone', { n: result.assignedCount, m: result.byAssignee.length }))
    visible.value = false
    emit('done', result.assignedCount)
  } catch {
  } finally {
    confirming.value = false
  }
}
</script>

<style scoped lang="scss">
.auto-assign-summary {
  margin: 0 0 12px;
  font-size: 14px;
}
.auto-assign-note {
  margin: 10px 0 0;
  font-size: 12px;
}
</style>
