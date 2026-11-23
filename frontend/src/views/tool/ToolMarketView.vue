<template>
  <div class="page-root" data-test="tool-market-page">
    <div :class="embedded ? 'tool-market-embedded' : 'page-card'">
      <div class="toolbar">
        <div class="toolbar-left">
          <!-- R23: page name lives on the top bar only; this market block is
 embedded as a tab, its duplicate h2 was retired . -->
          <span class="page-subtitle">{{ t('market.toolSubtitle') }}</span>
        </div>
        <div class="toolbar-right">
          <el-button type="primary" data-test="tool-market-import-open" @click="openImport">
            <el-icon><Upload /></el-icon>{{ t('market.importTool') }}
          </el-button>
        </div>
      </div>

      <div class="filter-bar">
        <el-input
          v-model="query.keyword"
          :placeholder="t('market.searchToolPh')"
          clearable
          style="width: 240px"
          data-test="tool-market-keyword"
          @keyup.enter="reload"
          @clear="reload"
        />
        <el-select
          v-model="query.type"
          :placeholder="t('market.type')"
          clearable
          style="width: 140px"
          popper-class="tp-tool-market-type-popper"
          data-test="tool-market-type-select"
          @change="reload"
        >
          <el-option v-for="opt in TOOL_TYPES" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
        <el-input
          v-model="query.category"
          :placeholder="t('market.category')"
          clearable
          style="width: 140px"
          data-test="tool-market-category"
          @keyup.enter="reload"
          @clear="reload"
        />
        <el-button data-test="tool-market-search" @click="reload">
          <el-icon><Search /></el-icon>{{ t('market.query') }}
        </el-button>
      </div>

      <TableSkeleton v-if="loading" :cols="7" data-test="toolmarketview-skeleton" />
      <el-table border
        v-else
        :data="list"
        row-key="id"
        data-test="tool-market-table"
        :default-sort="{ prop: 'downloadCount', order: 'descending' }"
        @sort-change="onSortChange"
      >
        <el-table-column :label="t('market.colTool')" min-width="220">
          <template #default="{ row }">
            <span class="tool-name">{{ row.name }}</span>
            <el-tag v-if="row.projectId == null" size="small" type="warning" style="margin-left: 6px">
              {{ t('market.systemLevel') }}
            </el-tag>
            <div class="tool-preview" data-test="tool-market-preview">{{ row.bodyPreview || '—' }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="typeLabel" :label="t('market.type')" width="100" />
        <el-table-column :label="t('market.category')" width="110">
          <template #default="{ row }">{{ row.category || '—' }}</template>
        </el-table-column>
        <el-table-column :label="t('market.tags')" min-width="140">
          <template #default="{ row }">
            <el-tag v-for="tag in row.tags || []" :key="tag" size="small" class="tag-chip">{{ tag }}</el-tag>
            <span v-if="!row.tags || row.tags.length === 0" class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('market.author')" width="110">
          <template #default="{ row }">{{ row.authorName || '—' }}</template>
        </el-table-column>
        <el-table-column prop="downloadCount" :label="t('market.downloads')" width="100" sortable="custom" align="right" data-test="col-downloads" />
        <el-table-column prop="refProjectCount" :label="t('market.refProjects')" width="110" sortable="custom" align="right" data-test="col-refprojects" />
        <el-table-column prop="call30dCount" :label="t('market.call30d')" width="110" sortable="custom" align="right" data-test="col-call30d" />
        <el-table-column :label="t('market.version')" width="80" align="center">
          <template #default="{ row }">v{{ row.currentVersion }}</template>
        </el-table-column>
        <el-table-column :label="t('common.action')" width="100" fixed="right">
          <template #default="{ row }">
            <el-dropdown trigger="click" @command="(cmd: string) => onDownload(row, cmd as 'md' | 'zip')">
              <el-button link type="primary" data-test="tool-market-download">{{ t('market.download') }}</el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item v-if="row.type === 1" command="md">{{ t('market.mdDoc') }}</el-dropdown-item>
                  <el-dropdown-item command="zip">{{ t('market.zipContract') }}</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </el-table-column>
      </el-table>
      <EmptyState v-if="!loading && list.length === 0" :title="t('market.toolEmptyTitle')" />

      <div class="pagination-bar">
        <el-pagination
          v-if="total > 0"
          v-model:current-page="query.page"
          :page-size="query.size"
          :total="total"
          layout="total, prev, pager, next"
          data-test="tool-market-pagination"
          @current-change="load"
        />
      </div>
    </div>

    
    <FileUploadDialog
      v-model="importVisible"
      :title="t('market.importTool')"
      accept=".json,.zip"
      :max-size-mb="50"
      :submit-text="t('market.submitImport')"
      :upload-fn="doImport"
      @uploaded="onImported"
    >
      <template #extra>
        <el-form label-width="100px">
          <el-form-item :label="t('market.ownerLabel')">
            <ProjectSelect
              v-if="authStore.isAdmin"
              v-model="importProjectId"
              :placeholder="t('market.ownerPhAdmin')"
              width="100%"
              data-test="tool-market-import-project"
            />
            <ProjectSelect
              v-else
              v-model="importProjectId"
              :placeholder="t('market.ownerPhUser')"
              width="100%"
              data-test="tool-market-import-project"
            />
          </el-form-item>
        </el-form>
      </template>
      <template #tip>
        {{ t('market.toolTip') }}
      </template>
    </FileUploadDialog>
  </div>
</template>

<script setup lang="ts">

import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Search, Upload } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import ProjectSelect from '@/components/ProjectSelect.vue'
import FileUploadDialog from '@/components/FileUploadDialog.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import type { ToolItem } from '@/api/tool'
import {
  importTool,
  marketDownload,
  marketList,
  type ToolMarketDownload,
  type ToolMarketItem,
  type ToolMarketQuery,
} from '@/api/toolMarket'

/** AN-5 embedded flag: renders inside a host tab card when true (the
 * host already frames the content, so the outer card is dropped to avoid a
 * frame-in-frame). Default false keeps standalone semantics. */
const props = withDefaults(defineProps<{ embedded?: boolean }>(), { embedded: false })
const embedded = computed(() => props.embedded)

const { t } = useI18n()
const authStore = useAuthStore()


const TOOL_TYPES = computed(() => [
  { value: 1, label: t('tool.typeDoc') },
  { value: 2, label: t('tool.typeExec') },
])


const SORT_KEY_MAP: Record<string, string> = {
  downloadCount: 'downloadCount',
  refProjectCount: 'refProjectCount',
  call30dCount: 'call30d',
}

const loading = ref(false)
const list = ref<ToolMarketItem[]>([])
const total = ref(0)

const query = reactive<ToolMarketQuery & { page: number; size: number }>({
  page: 1,
  size: 20,
  keyword: '',
  type: null,
  category: '',
  sortBy: 'downloadCount',
  sortDir: 'desc',
})

async function load(): Promise<void> {
  loading.value = true
  try {
    const page = await marketList({
      page: query.page,
      size: query.size,
      keyword: query.keyword || undefined,
      type: query.type ?? undefined,
      category: query.category || undefined,
      sortBy: query.sortBy,
      sortDir: query.sortDir,
    })
    list.value = page.list || []
    total.value = page.total || 0
  } catch (e) {
    ElMessage.error((e as Error).message || t('market.toolLoadFailed'))
  } finally {
    loading.value = false
  }
}

function reload(): void {
  query.page = 1
  void load()
}


function onSortChange({ prop, order }: { prop: string; order: string | null }): void {
  const key = SORT_KEY_MAP[prop]
  if (!key) {
    ElMessage.warning(t('market.sortUnsupported'))
    void load()
    return
  }
  query.sortBy = key
  query.sortDir = order === 'ascending' ? 'asc' : 'desc'
  query.page = 1
  void load()
}

async function onDownload(row: ToolMarketItem, format: 'md' | 'zip'): Promise<void> {
  try {
    const view: ToolMarketDownload = await marketDownload(row.id, format)
    const win = window.open(view.url, '_blank')
    if (!win) {
      ElMessage.warning(t('market.popBlocked'))
      return
    }
    ElMessage.success(t('market.downloaded', { file: view.fileName }))
    void load()
  } catch (e) {
    ElMessage.error((e as Error).message || t('market.downloadFailed'))
  }
}

const importVisible = ref(false)
const importProjectId = ref<number | null>(null)

function openImport(): void {
  importProjectId.value = null
  importVisible.value = true
}

function doImport(file: File, onProgress: (p: number) => void): Promise<ToolItem> {
  return importTool(file, importProjectId.value, onProgress)
}

function onImported(res: unknown): void {
  const tool = res as ToolItem
  ElMessage.success(t('market.imported', { name: tool.name }))
  void load()
}

onMounted(() => {
  void load()
})
</script>

<style scoped>
.tool-name {
  font-weight: 600;
}
.tool-preview {
  color: var(--tp-text-secondary);
  font-size: 12px;
  margin-top: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tag-chip {
  margin-right: 4px;
}
.muted {
  color: var(--tp-text-secondary);
}
</style>
