<template>
  <el-drawer
    v-model="visible"
    :title="t('skill.memoryRecycleTitle')"
    size="640px"
    destroy-on-close
    data-test="memory-recycle-drawer"
  >
    <el-table v-loading="loading" border :data="rows" row-key="id" data-test="memory-recycle-table">
      <el-table-column prop="content" :label="t('skill.memoryColContent')" min-width="240" show-overflow-tooltip />
      <el-table-column :label="t('skill.memoryColInject')" width="100" align="center">
        <template #default="{ row }">
          <el-tag size="small" :type="row.injectStatus === 1 ? 'success' : 'info'" effect="light">
            {{ row.injectStatus === 1 ? t('skill.memoryStateActive') : t('skill.memoryStateDisabled') }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="updatedAt" :label="t('common.updatedAt')" width="170" />
      <el-table-column :label="t('common.action')" width="90" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" :data-test="`memory-recycle-restore-${row.id}`" @click="onRestore(row)">
            {{ t('skill.restore') }}
          </el-button>
        </template>
      </el-table-column>
      <template #empty>
        <EmptyState :title="t('skill.memoryRecycleEmpty')" />
      </template>
    </el-table>
    <div class="pagination-bar">
      <el-pagination
        v-if="total > 0"
        v-model:current-page="page"
        :page-size="size"
        :total="total"
        layout="total, prev, pager, next"
        data-test="memory-recycle-pagination"
        @current-change="load"
      />
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
/**
 * Project-memory recycle bin drawer ; SkillRecycleDrawer
 * shaped exit). Lists soft-deleted ledger entries of the current project and
 * restores them (previous injection state returns — restore semantics and any
 * cross-project refusal are core single-source, the drawer only surfaces the
 * backend human message on a 403/409).
 */
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import EmptyState from '@/components/EmptyState.vue'
import { listProjectMemoryRecycleBin, restoreProjectMemory, type ProjectMemoryItem } from '@/api/projectMemory'

const props = defineProps<{ projectId: number | null }>()
const emit = defineEmits<{ (e: 'changed'): void }>()
const { t } = useI18n()

const visible = defineModel<boolean>({ required: true })

const loading = ref(false)
const rows = ref<ProjectMemoryItem[]>([])
const total = ref(0)
const page = ref(1)
const size = 20

async function load(): Promise<void> {
  if (props.projectId == null) return
  loading.value = true
  try {
    const res = await listProjectMemoryRecycleBin({ projectId: props.projectId, page: page.value, size })
    rows.value = res.list || []
    total.value = res.total || 0
  } catch (e) {
    ElMessage.error((e as Error).message || t('skill.memoryLoadFailed'))
  } finally {
    loading.value = false
  }
}

watch(visible, (open) => {
  if (open) {
    page.value = 1
    void load()
  }
})

async function onRestore(row: ProjectMemoryItem): Promise<void> {
  if (props.projectId == null) return
  try {
    await ElMessageBox.confirm(t('skill.memoryConfirmRestore'), t('components.confirmActionTitle'), { type: 'info' })
  } catch {
    return
  }
  try {
    await restoreProjectMemory(row.id, props.projectId)
    ElMessage.success(t('skill.memoryRestoredOk'))
    void load()
    emit('changed')
  } catch (e) {
    ElMessage.error((e as Error).message || t('skill.memoryActionFailed'))
  }
}
</script>
