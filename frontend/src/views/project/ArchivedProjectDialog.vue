<template>
  <el-dialog draggable
    :model-value="modelValue"
    :title="t('archivedProject.title')"
    width="800px"
    destroy-on-close
    data-test="archived-dialog"
    @update:model-value="emit('update:modelValue', $event)"
    @open="load(1)"
  >
    <DataTablePage v-loading="loading" :data="list" data-test="archived-table" :loading="loading">
      <el-table-column prop="name" :label="t('archivedProject.colName')" min-width="180" />
      <el-table-column prop="code" :label="t('archivedProject.colCode')" width="140" />
      <el-table-column :label="t('archivedProject.colArchivedAt')" width="170">
        <template #default="{ row }"><TimeCell :iso="row.updatedAt" /></template>
      </el-table-column>
      <el-table-column :label="t('common.action')" width="120" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" :data-test="`archived-restore-${row.id}`" @click="onRestore(row)">{{ t('archivedProject.restore') }}</el-button>
        </template>
      </el-table-column>
    </DataTablePage>
    <el-empty v-if="!loading && list.length === 0" :description="t('archivedProject.empty')" data-test="archived-empty" />
    <div v-if="total > size" class="pagination-bar">
      <el-pagination
        v-model:current-page="page"
        :page-size="size"
        :total="total"
        layout="total, prev, pager, next"
        data-test="archived-pagination"
        @current-change="load()"
      />
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { bizErrorMsg } from '@/utils/bizError'
import { getArchivedProjects, restoreProject, type Project } from '@/api/project'


const { t } = useI18n()

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'restored'): void
}>()
void props

const loading = ref(false)
const list = ref<Project[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)

async function load(p?: number): Promise<void> {
  if (p) page.value = p
  loading.value = true
  try {
    const r = await getArchivedProjects({ page: page.value, size: size.value })
    list.value = r.list
    total.value = Number(r.total)
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('archivedProject.loadFail')))
  } finally {
    loading.value = false
  }
}

async function onRestore(row: Project): Promise<void> {
  try {
    await restoreProject(row.id)
    ElMessage.success(t('archivedProject.restoreOk', { name: row.name }))
    await load()
    emit('restored')
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('archivedProject.restoreFail')))
  }
}

defineExpose({ load })
</script>

<style scoped>
</style>
