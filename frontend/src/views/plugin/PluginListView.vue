<!-- tp-sort-exempt: the plugin catalog lists installed plugins (a dozen), a full resident set. -->
<template>
  <div class="page-root" data-test="plugin-list-page">
    <div :class="embedded ? 'plugin-list-embedded' : 'page-card'">
      <div class="toolbar">
        <div class="toolbar-right">
          <el-button type="primary" data-test="plugin-upload-open" @click="openUpload">
            <el-icon><Upload /></el-icon>{{ t('plugin.uploadBtn') }}
          </el-button>
        </div>
      </div>

      <div class="filter-bar">
        <el-input
          v-model="query.industry"
          :placeholder="t('plugin.industryPh')"
          clearable
          style="width: 200px"
          data-test="plugin-industry"
          @keyup.enter="load"
          @clear="load"
        />
        <el-select
          v-model="query.enabled"
          :placeholder="t('plugin.statusPh')"
          clearable
          style="width: 140px"
          popper-class="tp-plugin-enabled-popper"
          data-test="plugin-enabled-select"
          @change="load"
        >
          <el-option :label="t('common.enabled')" :value="true" />
          <el-option :label="t('common.disabled')" :value="false" />
        </el-select>
        <el-button data-test="plugin-search" @click="load">
          <el-icon><Search /></el-icon>{{ t('plugin.query') }}
        </el-button>
      </div>

      <TableSkeleton v-if="loading" :cols="6" data-test="pluginlistview-skeleton" />
      <el-table border v-else :data="pagedList" row-key="id" data-test="plugin-list-table">
        <el-table-column :label="t('plugin.colPlugin')" min-width="200">
          <template #default="{ row }">
            <span class="plugin-name">{{ row.name }}</span>
            <el-tag v-if="row.source === 1" size="small" type="info" style="margin-left: 8px">{{ t('plugin.builtinTag') }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('plugin.colIndustry')" width="110">
          <template #default="{ row }">{{ row.industry || '—' }}</template>
        </el-table-column>
        <el-table-column prop="version" :label="t('plugin.colVersion')" width="100" />
        <el-table-column :label="t('plugin.colDesc')" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="plugin-desc">{{ manifestText(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('plugin.colEnabled')" width="90" align="center">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabled"
              :loading="togglingId === row.id"
              :data-test="`plugin-enabled-${row.id}`"
              @change="(v: boolean) => onToggle(row, v)"
            />
          </template>
        </el-table-column>
        <el-table-column :label="t('plugin.colActions')" width="90" align="center">
          <template #default="{ row }">
            <el-button link type="primary" :data-test="`plugin-view-open-${row.id}`" @click="openDetail(row)">
              {{ t('plugin.viewBtn') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <!-- Worst-scale gate (S group): the catalog is a full resident set that
 can exceed one screen (39 rows observed under load), so paging is
 mandatory once rows exceed a page. Client-side segment — the list API
 returns the whole catalog (installed-plugin shape, no server page). -->
      <div class="pagination-bar" v-if="list.length > pageSize">
        <el-pagination
          v-model:current-page="page"
          :page-size="pageSize"
          :total="list.length"
          layout="total, prev, pager, next"
          data-test="plugin-pagination"
        />
      </div>
      <EmptyState v-if="!loading && list.length === 0" :title="t('plugin.emptyTitle')" />
    </div>

    
    <FileUploadDialog
      v-model="uploadVisible"
      :title="t('plugin.uploadBtn')"
      accept=".yaml,.yml"
      :max-size-mb="50"
      :submit-text="t('plugin.submitRegister')"
      :upload-fn="doUpload"
      @uploaded="onUploaded"
    >
      <template #tip>
        {{ t('plugin.uploadTip') }}
      </template>
    </FileUploadDialog>

    
    <PluginDetailDrawer v-model="detailVisible" :plugin="detailRow" @saved="onSaved" />
  </div>
</template>

<script setup lang="ts">

import { onMounted, reactive, ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Search, Upload } from '@element-plus/icons-vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import FileUploadDialog from '@/components/FileUploadDialog.vue'
import PluginDetailDrawer from '@/views/plugin/PluginDetailDrawer.vue'
import {
  pluginList,
  pluginRegister,
  pluginSetEnabled,
  type PluginItem,
} from '@/api/plugin'

/** AN-5 embedded flag: renders inside the /extensions tab host when
 * true (outer card dropped, host frames the content). Default false keeps
 * standalone semantics for any leftover deep link. */
const props = withDefaults(defineProps<{ embedded?: boolean }>(), { embedded: false })
const embedded = computed(() => props.embedded)

const { t } = useI18n()

const loading = ref(false)
const list = ref<PluginItem[]>([])
const togglingId = ref<number | null>(null)
// Client-side paging for the resident catalog (worst-scale gate): keep the
// visible window bounded even when the installed set exceeds one screen.
const page = ref(1)
const pageSize = 20
const pagedList = computed(() =>
  list.value.slice((page.value - 1) * pageSize, page.value * pageSize),
)

const query = reactive<{ industry: string; enabled: boolean | undefined }>({
  industry: '',
  enabled: undefined,
})


function manifestText(row: PluginItem): string {
  const m = row.manifest
  if (!m) return '—'
  const desc = m.description
  return typeof desc === 'string' && desc ? desc : '—'
}

async function load(): Promise<void> {
  loading.value = true
  page.value = 1 // filter changes reset the visible window
  try {
    list.value = (await pluginList({ industry: query.industry || undefined, enabled: query.enabled })) || []
  } catch (e) {
    ElMessage.error((e as Error).message || t('plugin.listLoadFailed'))
  } finally {
    loading.value = false
  }
}


async function onToggle(row: PluginItem, next: boolean): Promise<void> {
  togglingId.value = row.id
  try {
    const updated = await pluginSetEnabled(row.id, next)
    row.enabled = updated.enabled
    ElMessage.success(next ? t('plugin.enabledOk', { name: row.name }) : t('plugin.disabledOk', { name: row.name }))
  } catch (e) {
    ElMessage.error((e as Error).message || t('plugin.toggleFailed'))
  } finally {
    togglingId.value = null
  }
}

const uploadVisible = ref(false)

function openUpload(): void {
  uploadVisible.value = true
}

function doUpload(file: File, onProgress: (p: number) => void): Promise<PluginItem> {
  return pluginRegister(file, onProgress)
}

function onUploaded(res: unknown): void {
  const created = res as PluginItem
  ElMessage.success(t('plugin.registered', { name: created.name, version: created.version }))
  void load()
}

const detailVisible = ref(false)
const detailRow = ref<PluginItem | null>(null)

function openDetail(row: PluginItem): void {
  detailRow.value = row
  detailVisible.value = true
}

function onSaved(updated: PluginItem): void {
  const idx = list.value.findIndex((x) => x.id === updated.id)
  if (idx >= 0) list.value[idx] = updated
  detailRow.value = updated
}

onMounted(() => {
  void load()
})
</script>

<style scoped>
.plugin-name {
  font-weight: 600;
}
.plugin-desc {
  color: var(--tp-text-secondary);
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: inline-block;
  max-width: 100%;
  vertical-align: bottom;
}
</style>
