<template>
  <el-drawer draggable
    :model-value="modelValue"
    :title="t('skill.recycleTitle')"
    size="640px"
    data-test="skill-recycle-drawer"
    @update:model-value="emit('update:modelValue', $event)"
    @open="load(1)"
  >
    <div class="recycle-toolbar">
      <el-input
        v-model="keyword"
        :placeholder="t('skill.recycleSearchPh')"
        clearable
        style="width: 220px"
        :prefix-icon="Search"
        data-test="skill-recycle-search"
        @keyup.enter="load(1)"
        @clear="load(1)"
      />
      <el-select
        v-model="projectId"
        filterable
        :placeholder="t('skill.scopePh')"
        clearable
        style="width: 180px"
        popper-class="tp-skill-recycle-project-popper"
        data-test="skill-recycle-project-select"
        @change="load(1)"
      >
        <el-option v-for="p in auth.projects" :key="p.projectId" :label="p.projectName" :value="p.projectId" />
      </el-select>
    </div>

    <DataTablePage v-loading="loading" :data="list" data-test="skill-recycle-table" :loading="loading">
      <el-table-column prop="name" :label="t('common.name')" min-width="170" show-overflow-tooltip />
      <el-table-column :label="t('skill.type')" width="120">
        <template #default="{ row }">{{ typeLabel(row.type) }}</template>
      </el-table-column>
      <el-table-column :label="t('skill.colStatusBeforeDelete')" width="110">
        <template #default="{ row }">
          <el-tag :type="row.status === 3 ? 'success' : row.status === 2 ? 'warning' : row.status === 4 ? 'danger' : 'info'" size="small">
            {{ statusLabel(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('skill.colProject')" width="90">
        <template #default="{ row }">
          <el-tag v-if="row.projectId == null" size="small" type="warning">{{ t('skill.systemLevel') }}</el-tag>
          <span v-else class="text-secondary">{{ t('skill.projectLevel') }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('skill.colVersion')" width="70" align="center">
        <template #default="{ row }">v{{ row.currentVersion }}</template>
      </el-table-column>
      <el-table-column :label="t('skill.colDeletedBy')" min-width="150">
        <template #default="{ row }">
          <div>{{ row.deletedByName || '—' }}</div>
          <div class="text-secondary"><TimeCell :iso="row.deletedAt" /></div>
        </template>
      </el-table-column>
      <el-table-column :label="t('common.action')" width="90" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" :data-test="`skill-recycle-restore-${row.id}`" @click="onRestore(row)">{{ t('skill.restore') }}</el-button>
        </template>
      </el-table-column>
    </DataTablePage>
    <el-empty v-if="!loading && list.length === 0" :description="t('skill.recycleEmpty')" data-test="skill-recycle-empty" />
    <div v-if="total > size" class="pagination-bar">
      <el-pagination
        v-model:current-page="page"
        :page-size="size"
        :total="total"
        layout="total, prev, pager, next"
        data-test="skill-recycle-pagination"
        @current-change="load()"
      />
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import DataTablePage from '@/components/DataTablePage.vue'
import TimeCell from '@/components/TimeCell.vue'
import { useAuthStore } from '@/stores/auth'
import { bizErrorMsg } from '@/utils/bizError'
import {
  listSkillRecycleBin, restoreSkill,
  SKILL_STATUS_KEYS, SKILL_TYPE_KEYS,
  type SkillRecycleItem,
} from '@/api/skillAdmin'


const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'changed'): void
}>()

const { t } = useI18n()
const auth = useAuthStore()

function typeLabel(type: number): string {
  const key = SKILL_TYPE_KEYS[type]
  return key ? t(key) : '—'
}

function statusLabel(status: number): string {
  const key = SKILL_STATUS_KEYS[status]
  return key ? t(key) : String(status)
}
const loading = ref(false)
const list = ref<SkillRecycleItem[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')

const projectId = ref<number | null>(null)

async function load(p?: number): Promise<void> {
  if (p) page.value = p
  loading.value = true
  try {
    const r = await listSkillRecycleBin({
      projectId: projectId.value ?? undefined,
      keyword: keyword.value || undefined,
      page: page.value,
      size: size.value,
    })
    list.value = r.list
    total.value = Number(r.total)
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('skill.recycleLoadFailed')))
  } finally {
    loading.value = false
  }
}


async function onRestore(row: SkillRecycleItem): Promise<void> {
  try {
    await ElMessageBox.confirm(
      t('skill.restoreConfirm', { name: row.name, status: statusLabel(row.status) }),
      t('skill.restoreTitle'),
      { type: 'warning', confirmButtonText: t('skill.restore'), cancelButtonText: t('common.cancel') },
    )
  } catch {
    return
  }
  try {
    await restoreSkill(row.id, row.version)
    ElMessage.success(t('skill.restored', { name: row.name }))
    await load()
    emit('changed')
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('skill.restoreFailed')))
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
