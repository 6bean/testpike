<template>
  <el-drawer draggable
    :model-value="modelValue"
    :title="t('caseRecycle.title')"
    size="640px"
    data-test="case-recycle-drawer"
    @update:model-value="emit('update:modelValue', $event)"
    @open="load(1)"
  >
    <div class="recycle-toolbar">
      <el-input
        v-model="keyword"
        :placeholder="t('caseRecycle.searchPlaceholder')"
        clearable
        style="width: 220px"
        :prefix-icon="Search"
        data-test="recycle-search"
        @keyup.enter="load(1)"
        @clear="load(1)"
      />
      <el-popconfirm
        :title="t('caseRecycle.clearConfirm')"
        width="300"
        :confirm-button-text="t('caseRecycle.clearBtn')"
        :cancel-button-text="t('common.cancel')"
        confirm-button-type="danger"
        @confirm="onClear"
      >
        <template #reference>
          <el-button type="danger" plain :disabled="!list.length" data-test="recycle-clear">{{ t('caseRecycle.clearAllBtn') }}</el-button>
        </template>
      </el-popconfirm>
    </div>

    <DataTablePage v-loading="loading" :data="list" data-test="recycle-table" :loading="loading">
      <el-table-column prop="caseNo" :label="t('caseRecycle.colCaseNo')" width="170" />
      <el-table-column prop="title" :label="t('caseRecycle.colTitle')" min-width="160" show-overflow-tooltip />
      <el-table-column :label="t('caseRecycle.colPriority')" width="80">
        <template #default="{ row }">
          <el-tag :type="PRIORITY_TYPE[row.priority] ?? 'info'" size="small">{{ PRIORITY_LABEL[row.priority] }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('caseRecycle.colDeletedBy')" min-width="150">
        <template #default="{ row }">
          <div>{{ row.deletedByName || '—' }}</div>
          <div class="text-secondary"><TimeCell :iso="row.deletedAt" /></div>
        </template>
      </el-table-column>
      <el-table-column :label="t('common.action')" width="150" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" :data-test="`recycle-restore-${row.id}`" @click="onRestore(row)">{{ t('caseRecycle.restore') }}</el-button>
          <el-popconfirm
            :title="t('caseRecycle.purgeConfirm')"
            width="300"
            :confirm-button-text="t('common.delete')"
            :cancel-button-text="t('common.cancel')"
            confirm-button-type="danger"
            @confirm="onPurge(row)"
          >
            <template #reference>
              <el-button link type="danger" :data-test="`recycle-purge-${row.id}`">{{ t('caseRecycle.purge') }}</el-button>
            </template>
          </el-popconfirm>
        </template>
      </el-table-column>
    </DataTablePage>
    <el-empty v-if="!loading && list.length === 0" :description="t('caseRecycle.empty')" data-test="recycle-empty" />
    <div v-if="total > size" class="pagination-bar">
      <el-pagination
        v-model:current-page="page"
        :page-size="size"
        :total="total"
        layout="total, prev, pager, next"
        data-test="recycle-pagination"
        @current-change="load()"
      />
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { bizErrorMsg } from '@/utils/bizError'
import {
  CASE_STATUS, PRIORITY_LABEL, PRIORITY_TYPE,
  clearRecycleBin, listRecycleBin, purgeCase, restoreCase,
  type CaseRecycleItem,
} from '@/api/case'


const { t } = useI18n()

const props = defineProps<{ modelValue: boolean; projectId: number | null }>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'changed'): void
}>()

const loading = ref(false)
const list = ref<CaseRecycleItem[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const keyword = ref('')

async function load(p?: number): Promise<void> {
  if (!props.projectId) return
  if (p) page.value = p
  loading.value = true
  try {
    const r = await listRecycleBin({ projectId: props.projectId, keyword: keyword.value || undefined, page: page.value, size: size.value })
    list.value = r.list
    total.value = Number(r.total)
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('caseRecycle.loadFail')))
  } finally {
    loading.value = false
  }
}

async function onRestore(row: CaseRecycleItem): Promise<void> {
  try {
    await restoreCase(row.id, row.version)
    ElMessage.success(t('caseRecycle.restoreOk', { caseNo: row.caseNo, status: CASE_STATUS[row.status]?.label ?? row.status }))
    await load()
    emit('changed')
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('caseRecycle.restoreFail')))
  }
}

async function onPurge(row: CaseRecycleItem): Promise<void> {
  try {
    await purgeCase(row.id)
    ElMessage.success(t('caseRecycle.purgeOk', { caseNo: row.caseNo }))
    await load()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('caseRecycle.purgeFail')))
  }
}

async function onClear(): Promise<void> {
  if (!props.projectId) return
  try {
    const n = await clearRecycleBin(props.projectId)
    ElMessage.success(t('caseRecycle.clearOk', { n }))
    await load(1)
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('caseRecycle.clearFail')))
  }
}

defineExpose({ load })
</script>

<style scoped>
.recycle-toolbar {
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
}
.text-secondary {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
