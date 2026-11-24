<template>
  <div class="page-root batch-list-page" data-test="batch-list-page">
    <div class="page-card">
      <!-- AN-2 half B (P-1 ruling): the standalone schedule-fires page
 merged into this page as a tab. Tab state rides the URL query
 (?tab=fires) so deep links and the retired /schedule/fires redirect
 land on the right tab. No permission gate: the fires list carried
 no route perm key before the merge (backend single-point read
 guard), so tab visibility matches the page itself — unchanged
 semantics. -->
      <el-tabs v-model="pageTab" data-test="batch-page-tabs" class="batch-page-tabs">
        <el-tab-pane name="list">
          <template #label><span data-test="batch-tab-list">{{ t('batch.pageTabList') }}</span></template>
        </el-tab-pane>
        <el-tab-pane name="fires">
          <template #label><span data-test="batch-tab-fires">{{ t('batch.pageTabFires') }}</span></template>
        </el-tab-pane>
      </el-tabs>

      <!-- Timed-trigger ledger tab = ScheduleFireListView reused as-is (no
           markup copy; its own filters/table/pagination/sort stay the single
           source). -->
      <ScheduleFireListView v-if="pageTab === 'fires'" embedded />

      <template v-if="pageTab !== 'fires'">
      <div class="page-header">
        <div class="header-actions">
          <el-button :disabled="!projectId" data-test="batch-template-btn" @click="openTemplateDialog">
            {{ t('batch.templateManage') }}
          </el-button>
          <el-tooltip
            :content="t('components.ownerEcho.pickInHeader')"
            placement="bottom"
            :disabled="!!projectId"
          >
            <span>
              <el-button type="primary" :icon="Plus" :disabled="!projectId" data-test="batch-create-btn" @click="openCreate">
                {{ t('batch.create') }}
              </el-button>
            </span>
          </el-tooltip>
        </div>
      </div>

      <!-- IA fix: screening lives in the shared filter-bar row; the
 project rides the global header context (page selector removed). -->
      <div class="filter-bar" data-test="batch-list-filter-bar">
          <el-select
            v-model="filters.status"
            :placeholder="t('batch.statusAll')"
            clearable
            style="width: 130px"
            data-test="batch-filter-status"
            data-se1-exempt="batch status options are a fixed 8-value enum local to this file; never grows with remote data"
            @change="load(1)"
          >
            <el-option v-for="(label, s) in statusOptions" :key="s" :label="label" :value="Number(s)" />
          </el-select>
          <el-input
            v-model="filters.keyword"
            :placeholder="t('batch.searchPlaceholder')"
            clearable
            style="width: 180px"
            data-test="batch-filter-keyword"
            @keyup.enter="load(1)"
            @clear="load(1)"
          />
          <!-- migration batch (R21 revision 2): the query submit belongs inline at the
 end of the filter group; column settings is a view-state action and
 rides the trailing slot. Anchors unchanged. -->
          <el-button :icon="Search" data-test="batch-search-btn" @click="load(1)">{{ t('batch.search') }}</el-button>
          <div class="filter-bar-actions">
            <ColumnSettings
              :columns="settingsRows"
              :density="colCfg.state.density"
              @toggle="colCfg.toggle"
              @move="colCfg.move"
              @density="colCfg.setDensity"
              @reset="colCfg.reset"
              @pin="colCfg.toggleFixed"
            />
          </div>
        </div>

      <TableSkeleton v-if="loading" :cols="8" data-test="batchlistview-skeleton" />
      <DataTablePage
        :data="rows"
        data-test="batch-table"
        class="data-table"
        row-key="id"
        :size="colCfg.state.density"
        :column-config="colCfg"
        @sort-change="onSortChange"
        v-else
      >
        <!-- configurable data columns (show/hide, order, width,
 density persist through the shared useColumnConfig piece). -->
        <el-table-column
          v-for="col in colCfg.visibleColumns.value"
          :key="col.key"
          :prop="col.key"
          :fixed="col.fixed || false"
          :label="t(col.labelKey)"
          :width="col.width ?? undefined"
          :min-width="col.width ? undefined : BATCH_FLEX_MIN[col.key]"
          :align="col.key === 'caseCount' || col.key === 'queuePos' ? 'center' : undefined"
          :sortable="isColSortable(col.key) ? 'custom' : false"
          :show-overflow-tooltip="col.key === 'name' || col.key === 'env'"
          :data-test="`batch-col-${col.key}`"
        >
          <template #default="{ row }">
            <el-link v-if="col.key === 'name'" type="primary" data-test="batch-name-link" @click="goDetail(row.id)">{{ row.name }}</el-link>
            <el-tag v-else-if="col.key === 'status'" :type="batchStatusTag(row.status)" size="small" data-test="batch-status-tag">{{ row.statusLabel }}</el-tag>
            <span v-else-if="col.key === 'env'">{{ row.envName || '-' }}</span>
            <span v-else-if="col.key === 'queuePos'">
              <span v-if="row.queuePos" data-test="batch-queue-pos">{{ t('batch.queueNth', { n: row.queuePos }) }}</span>
              <span v-else>-</span>
            </span>
            <TimeCell v-else-if="col.key === 'createdAt'" :iso="row.createdAt" />
            <span v-else>{{ row[col.key] }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('batch.operation')" width="220" fixed="right">
          <template #default="{ row }">
            <el-button size="small" data-test="batch-detail-btn" @click="goDetail(row.id)">{{ t('batch.detail') }}</el-button>
            <el-button
              v-if="row.caseCount > 0"
              size="small"
              type="primary"
              plain
              data-test="batch-save-template-btn"
              @click="saveAsTemplate(row)"
            >
              {{ t('batch.saveTemplate') }}
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <EmptyState
            data-test="batch-empty-create"
            :filtered="batchEmptyCopy.filtered"
            :title="batchEmptyCopy.title"
            :hint="batchEmptyCopy.hint || t('batch.emptyNoData')"
            :action-text="t('batch.create')"
            :action-disabled="!projectId"
            @action="openCreate"
          />
        </template>
      </DataTablePage>

      <div class="pagination-bar">
        <el-pagination v-if="total > 0"
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          data-test="batch-pagination"
          @current-change="load()"
          @size-change="load(1)"
        />
      </div>
      </template>

   
      <el-dialog v-model="createVisible" :title="t('batch.createTitle')" width="800px" data-test="batch-create-dialog">
        <!-- ownership echo (iron rule 1): read-only, single-sourced from the
 header project context - the record lands on the visible segment -->
        <ProjectOwnerEcho />
        <el-form label-width="96px">
          <el-form-item :label="t('batch.name')" required>
            <el-input v-model="createForm.name" maxlength="200" data-test="batch-form-name" :placeholder="t('batch.namePlaceholder')" />
          </el-form-item>
          <el-form-item :label="t('batch.env')">
            <el-select v-model="createForm.envId" clearable filterable style="width: 100%" data-test="batch-form-env"
              :placeholder="t('batch.envPlaceholder')">
              <el-option v-for="e in envs" :key="e.id" :label="`${e.name}（${envTypeLabel(e.envType)}${e.endTypes ? '·' + e.endTypes : ''}）`" :value="e.id" />
            </el-select>
          </el-form-item>
   
          <el-form-item>
            <template #label>
              <span>{{ t('batch.feishuGateLabel') }}</span>
              <el-tooltip :content="t('batch.feishuGateTip')" placement="top">
                <el-icon :size="14" style="margin-left:4px;vertical-align:-2px"><QuestionFilled /></el-icon>
              </el-tooltip>
            </template>
            <el-switch v-model="createForm.notifyFeishu" :active-value="1" :inactive-value="0" data-test="batch-feishu-gate-switch" />
          </el-form-item>
   
          <el-form-item>
            <template #label>
              <span>{{ t('batch.cleanGateLabel') }}</span>
              <el-tooltip :content="t('batch.cleanGateTip')" placement="top">
                <el-icon :size="14" style="margin-left:4px;vertical-align:-2px"><QuestionFilled /></el-icon>
              </el-tooltip>
            </template>
            <el-switch v-model="createForm.cleanAppData" :active-value="1" :inactive-value="0" data-test="batch-clean-gate-switch" />
          </el-form-item>
   
          <el-form-item>
            <template #label>
              <span>{{ t('batch.poolLabel') }}</span>
              <el-tooltip :content="t('batch.poolTip')" placement="top">
                <el-icon :size="14" style="margin-left:4px;vertical-align:-2px"><QuestionFilled /></el-icon>
              </el-tooltip>
            </template>
            <el-select v-model="createForm.poolTagId" clearable filterable style="width: 100%" data-test="batch-form-pool"
              :placeholder="t('batch.poolPlaceholder')">
              <el-option v-for="pt in poolTags" :key="pt.id" :label="pt.name" :value="pt.id" />
            </el-select>
          </el-form-item>
   
          <el-form-item>
            <template #label>
              <span>{{ t('batch.browserLabel') }}</span>
              <el-tooltip :content="t('batch.browserTip')" placement="top">
                <el-icon :size="14" style="margin-left:4px;vertical-align:-2px"><QuestionFilled /></el-icon>
              </el-tooltip>
            </template>
            <BrowserSelect v-model="createForm.browser" :env-browsers="createFormEnvBrowsers"
              data-test="batch-form-browser" />
          </el-form-item>
          <el-form-item :label="t('batch.casePick')">
            <div class="picker-box">
              <div class="picker-toolbar">
                <el-input v-model="caseKeyword" size="small" style="width: 220px" data-test="batch-case-keyword"
                  :placeholder="t('batch.caseSearchPlaceholder')" clearable @keyup.enter="loadCases(1)" />
                <el-select v-model="casePriority" size="small" style="width: 120px" clearable data-test="batch-case-priority"
                  :placeholder="t('batch.priorityPlaceholder')" @change="loadCases(1)">
                  <el-option label="P0" :value="0" /><el-option label="P1" :value="1" /><el-option label="P2" :value="2" />
                </el-select>
                <el-button size="small" data-test="batch-case-search" @click="loadCases(1)">{{ t('batch.search') }}</el-button>
                <span class="picked-hint" data-test="batch-picked-count">{{ t('batch.pickedCount', { n: selectedCaseIds.length }) }}</span>
              </div>
              <DataTablePage ref="caseTableRef" v-loading="caseLoading" :data="caseRows" size="small" height="300"
                row-key="id" data-test="batch-case-table" @select="onCaseSelect" @select-all="onCaseSelectAll" :loading="caseLoading">
                <el-table-column type="selection" width="42" reserve-selection />
                <el-table-column prop="caseNo" :label="t('batch.colCaseNo')" width="120" />
                <el-table-column prop="title" :label="t('batch.colTitle')" min-width="200" show-overflow-tooltip />
                <el-table-column :label="t('batch.endType')" width="90">
                  <template #default="{ row }">{{ END_TYPE_LABEL[row.endType] ?? row.endType }}</template>
                </el-table-column>
              </DataTablePage>
              <div class="pagination-bar">
                <el-pagination v-if="caseTotal > 0" v-model:current-page="casePage" :page-size="20" :total="caseTotal"
                  layout="prev, pager, next" small data-test="batch-case-pagination" @current-change="loadCases()" />
              </div>
            </div>
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="createVisible = false">{{ t('batch.cancel') }}</el-button>
          <el-button type="primary" :loading="saving" data-test="batch-form-submit" @click="submitCreate">
            {{ t('batch.confirmCreate') }}
          </el-button>
        </template>
      </el-dialog>

   
      <el-dialog v-model="templateVisible" :title="t('batch.templateTitle')" width="800px" data-test="batch-template-dialog">
        <el-table border v-loading="tplLoading" :data="templates" size="small" data-test="batch-template-table">
          <el-table-column prop="name" :label="t('batch.templateName')" min-width="180" show-overflow-tooltip />
          <el-table-column :label="t('batch.caseCount')" width="90" align="center">
            <template #default="{ row }">{{ row.caseIds.length }}</template>
          </el-table-column>
        <el-table-column prop="createdAt" :label="t('batch.createdAt')" width="160">
          <template #default="{ row }"><TimeCell :iso="row.createdAt" /></template>
        </el-table-column>
   
        <el-table-column :label="t('batch.cleanGateLabel')" width="110" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.cleanAppData === 1" type="warning" size="small" data-test="batch-tpl-clean-tag">{{ t('batch.cleanOn') }}</el-tag>
            <span v-else data-test="batch-tpl-clean-off">-</span>
          </template>
        </el-table-column>
        
        <el-table-column :label="t('batch.scheduleCol')" width="170" show-overflow-tooltip>
          <template #default="{ row }">
            <el-tag v-if="row.enabled === 1" type="success" size="small" data-test="batch-tpl-schedule-tag">
              {{ row.cronExpr }}
            </el-tag>
            <span v-else data-test="batch-tpl-schedule-off">-</span>
          </template>
        </el-table-column>
          <el-table-column :label="t('batch.operation')" width="250" fixed="right">
            <template #default="{ row }">
              <el-button size="small" type="primary" data-test="batch-tpl-reuse-btn" @click="openReuse(row)">
                {{ t('batch.reuse') }}
              </el-button>
              <el-button size="small" data-test="batch-tpl-schedule-btn" @click="openSchedule(row)">
                {{ t('batch.schedule') }}
              </el-button>
              <el-button size="small" type="danger" plain data-test="batch-tpl-delete-btn" @click="removeTemplate(row)">
                {{ t('batch.delete') }}
              </el-button>
            </template>
          </el-table-column>
          <template #empty><el-empty :description="t('batch.templateEmpty')" :image-size="60" /></template>
        </el-table>
      </el-dialog>

      
      <el-dialog v-model="scheduleVisible" :title="t('batch.scheduleTitle')" width="480px" data-test="batch-schedule-dialog">
        <el-form label-width="110px">
          <el-form-item :label="t('batch.scheduleEnable')">
            <el-switch v-model="scheduleForm.enabled" :active-value="1" :inactive-value="0" data-test="batch-schedule-enable" />
          </el-form-item>
          <el-form-item :label="t('batch.scheduleCron')">
            <el-input v-model="scheduleForm.cronExpr" data-test="batch-schedule-cron"
              :placeholder="t('batch.scheduleCronPlaceholder')" :disabled="scheduleForm.enabled !== 1" />
          </el-form-item>
          <el-form-item :label="t('batch.scheduleEnv')">
            <el-select v-model="scheduleForm.scheduleEnvId" filterable style="width: 100%" data-test="batch-schedule-env"
              :placeholder="t('batch.envRequired')" :disabled="scheduleForm.enabled !== 1" clearable>
              <el-option v-for="e in envs" :key="e.id" :label="`${e.name}（${envTypeLabel(e.envType)}）`" :value="e.id" />
            </el-select>
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="scheduleVisible = false">{{ t('batch.cancel') }}</el-button>
          <el-button type="primary" :loading="saving" data-test="batch-schedule-submit" @click="submitSchedule">
            {{ t('batch.confirmCreate') }}
          </el-button>
        </template>
      </el-dialog>

      
      <el-dialog v-model="reuseVisible" :title="t('batch.reuseTitle')" width="480px" data-test="batch-reuse-dialog">
        <el-form label-width="96px">
          <el-form-item :label="t('batch.name')">
            <el-input v-model="reuseName" maxlength="200" data-test="batch-reuse-name" :placeholder="t('batch.reuseNamePlaceholder')" />
          </el-form-item>
          <el-form-item :label="t('batch.env')" required>
            <el-select v-model="reuseEnvId" filterable style="width: 100%" data-test="batch-reuse-env" :placeholder="t('batch.envRequired')">
              <el-option v-for="e in envs" :key="e.id" :label="`${e.name}（${envTypeLabel(e.envType)}）`" :value="e.id" />
            </el-select>
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="reuseVisible = false">{{ t('batch.cancel') }}</el-button>
          <el-button type="primary" :loading="saving" data-test="batch-reuse-submit" @click="submitReuse">
            {{ t('batch.confirmCreate') }}
          </el-button>
        </template>
      </el-dialog>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useKeepAliveList } from '@/composables/useKeepAliveList'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import { hasQueryScope, lensEmptyCopy, listProjectScope, programIdParam, watchProgramLens } from '@/composables/useProgramLens'
import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'
import { useObjectSync } from '@/composables/useObjectSync'
import ColumnSettings from '@/components/ColumnSettings.vue'
import EmptyState from '@/components/EmptyState.vue'
import ProjectOwnerEcho from '@/components/ProjectOwnerEcho.vue'
import ScheduleFireListView from '@/views/schedule/ScheduleFireListView.vue'
import { Plus, QuestionFilled, Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { TableInstance } from 'element-plus'
import { bizErrorMsg } from '@/utils/bizError'
import { getProjectEnvs, envTypeLabel, type ProjectEnv } from '@/api/project'
import { listTags, type TagItem } from '@/api/tags'
import { listCases, END_TYPE_LABEL, type CaseItem } from '@/api/case'
import { td } from '@/utils/dictLabel'
import BrowserSelect from '@/components/BrowserSelect.vue'
import {
  createBatch,
  createBatchFromTemplate,
  deleteBatchTemplate,
  listBatchTemplates,
  listBatches,
  saveBatchAsTemplate,
  updateTemplateSchedule,
  batchStatusTag,
  type BatchTemplateView,
  type BatchView,
} from '@/api/batch'

defineOptions({ name: 'BatchList' })

const { t } = useI18n()
const router = useRouter()

const { projectId, followTick } = useProjectPageContext()

/** Program lens : header switcher owns the id — this page only
 * reads it; param/watch fragments single-sourced in useProgramLens (R40). */

// ---------- half B: page tab (list / fires), URL-query synced ----------
const route = useRoute()
/** Query value wins on entry; an unknown key falls back to the batches tab. */
function tabFromQuery(): 'list' | 'fires' {
  return route.query.tab === 'fires' ? 'fires' : 'list'
}
const pageTab = ref<'list' | 'fires'>(tabFromQuery())

// Switching tabs writes the query back (replace = no history spam), so any
// tab state is shareable/refresh-stable per the AN tab-in-URL rule.
watch(pageTab, (tab) => {
  void router.replace({ query: { ...route.query, tab: tab === 'list' ? undefined : tab } })
})
// External navigations into this page carrying ?tab=fires (redirect from
// /schedule/fires, ⌘K deep link) must move the visible tab, not just the URL.
watch(() => route.query.tab, () => {
  pageTab.value = tabFromQuery()
})
// This page is keep-alive cached (N9 list-view whitelist): re-entering it from
// the sidebar after leaving on ?tab=fires revives the stale component without
// re-running setup, and the query watcher above never fires because
// tab goes undefined -> undefined. The URL stays the single source of truth
// for tab state, so re-read it on every activation (bare /batches = list tab).
onActivated(() => {
  pageTab.value = tabFromQuery()
})

// ---------- column configuration (shared useColumnConfig piece) ----------
/** Configurable data columns (the fixed-right action column stays locked out). */
const BATCH_COLUMNS: ColumnDef[] = [
  { key: 'name', labelKey: 'batch.name', width: null },
  { key: 'batchNo', labelKey: 'batch.batchNo', width: 140 },
  { key: 'status', labelKey: 'batch.status', width: 110 },
  { key: 'env', labelKey: 'batch.env', width: 140 },
  { key: 'caseCount', labelKey: 'batch.caseCount', width: 110 },
  { key: 'queuePos', labelKey: 'batch.queuePos', width: 120 },
  { key: 'createdAt', labelKey: 'batch.createdAt', width: 160 },
]
/** Flexible columns fall back to min-width when no explicit width is set. */
const BATCH_FLEX_MIN: Record<string, number> = { name: 200 }
// (ledger batch B): column prop -> core SORT_COLUMNS whitelist key.
// Single source for both the sortable-column decision and the request payload —
// columns outside the whitelist never emit sort params (no client-only fake sort).
const SORT_KEY_MAP: Record<string, string> = {
  name: 'name',
  batchNo: 'batch_no',
  status: 'status',
  caseCount: 'case_count',
  queuePos: 'queue_pos',
  createdAt: 'created_at',
}
/** Whether this column has a server-side sort key (shared decision source). */
function isColSortable(key: string): boolean {
  return key in SORT_KEY_MAP
}
const colCfg = useColumnConfig('batches', BATCH_COLUMNS)
/** Popover rows (column state merged with labelKey for rendering). */
const settingsRows = computed(() =>
  colCfg.state.columns.map((c) => ({ ...c, labelKey: BATCH_COLUMNS.find((d) => d.key === c.key)!.labelKey })),
)

const rows = ref<BatchView[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
// server-side sort state (same shape as mcp/skill/tool pages).
const query = reactive<{ sortBy: string; sortDir: string }>({ sortBy: '', sortDir: '' })
const loading = ref(false)
const saving = ref(false)
const envs = ref<ProjectEnv[]>([])
const filters = reactive<{ status: number | null; keyword: string }>({ status: null, keyword: '' })

// Empty-state copy : lens ON + no filters → name the program
// segment in the el-empty line; lens OFF keeps the legacy line verbatim.
const batchEmptyCopy = computed(() => lensEmptyCopy({
  translate: t,
  titleKey: 'batch.lensEmptyTitle',
  emptyKey: 'batch.emptyNoData',
  filtered: filters.status !== null || filters.keyword.trim() !== '',
}))

const statusOptions = computed(() => {
  const s = (k: string) => td(`dict.batch.status.${k}`)
  return { 1: s('draft'), 2: s('pending'), 3: s('envUnavailable'), 4: s('executing'), 5: s('paused'), 6: s('done'), 7: s('partialFail'), 8: s('canceled') }
})

async function load(p = page.value): Promise<void> {
  const scope = listProjectScope(projectId.value)
  if (scope == null) return
  page.value = p
  loading.value = true
  try {
    const res = await listBatches({
      projectId: scope, status: filters.status,
      keyword: filters.keyword || undefined, page: page.value, size: size.value,
      sortBy: query.sortBy || undefined, sortDir: query.sortDir || undefined,
      programId: programIdParam(),
    })
    rows.value = res.list || []
    total.value = res.total || 0
  } catch {
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

// : a landed batch write re-reads this list on its current
// page and sort as-is — silent convergence, no toast/confirm/jump (iron
// rules 2-3); an object landing outside the filter set simply changes rows.
useObjectSync({
  watch: ['BATCH_WRITE'],
  onSync: () => void load(),
})

/** Header sort: forward the whitelisted key to the server (ORDER BY happens in SQL);
 * clearing a sort falls back to the backend default order; page resets to 1. */
function onSortChange({ prop, order }: { prop: string; order: string | null }): void {
  if (order === null) {
    query.sortBy = ''
    query.sortDir = ''
    page.value = 1
    void load()
    return
  }
  const key = SORT_KEY_MAP[prop]
  if (!key) {
    void load()
    return
  }
  query.sortBy = key
  query.sortDir = order === 'ascending' ? 'asc' : 'desc'
  page.value = 1
  void load()
}

async function loadEnvs(): Promise<void> {
  if (!projectId.value) return
  envs.value = (await getProjectEnvs(projectId.value)) || []
}


function goDetail(id: number): void {
  router.push({ path: `/batches/${id}` })
}

const createVisible = ref(false)
const createForm = reactive<{ name: string; envId: number | null; notifyFeishu: number; poolTagId: number | null; browser: string | null; cleanAppData: number }>({ name: '', envId: null, notifyFeishu: 0, poolTagId: null, browser: null, cleanAppData: 0 })

const createFormEnvBrowsers = computed<string | null>(
  () => envs.value.find((e) => e.id === createForm.envId)?.browsers ?? null)

const poolTags = ref<TagItem[]>([])
const caseTableRef = ref<TableInstance>()
const caseRows = ref<CaseItem[]>([])
const caseTotal = ref(0)
const casePage = ref(1)
const caseLoading = ref(false)
const caseKeyword = ref('')
const casePriority = ref<number | null>(null)
const selectedCaseIds = ref<number[]>([])

async function loadCases(p = casePage.value): Promise<void> {
  if (!projectId.value) return
  casePage.value = p
  caseLoading.value = true
  try {
    const res = await listCases({
      projectId: projectId.value, keyword: caseKeyword.value || undefined,
      priority: casePriority.value ?? undefined, page: casePage.value, size: 20,
    })
    caseRows.value = res.list || []
    caseTotal.value = res.total || 0
  } finally {
    caseLoading.value = false
  }
}

function onCaseSelect(selection: CaseItem[], row: CaseItem): void {
  const picked = selection.some((s) => s.id === row.id)
  selectedCaseIds.value = picked
    ? [...new Set([...selectedCaseIds.value, row.id])]
    : selectedCaseIds.value.filter((id) => id !== row.id)
}

function onCaseSelectAll(selection: CaseItem[]): void {
  const pageIds = caseRows.value.map((c) => c.id)
  if (selection.length > 0) {
    selectedCaseIds.value = [...new Set([...selectedCaseIds.value, ...pageIds])]
  } else {
    selectedCaseIds.value = selectedCaseIds.value.filter((id) => !pageIds.includes(id))
  }
}

async function openCreate(): Promise<void> {
  createForm.name = ''
  createForm.envId = null
  createForm.notifyFeishu = 0
  createForm.cleanAppData = 0
  createForm.poolTagId = null
  createForm.browser = null
  selectedCaseIds.value = []
  caseKeyword.value = ''
  casePriority.value = null
  caseTableRef.value?.clearSelection()
  createVisible.value = true
  await loadEnvs()
  await loadCases(1)
  try {
    if (projectId.value) {
      const tags = await listTags(projectId.value)
      poolTags.value = tags.filter((x) => x.type === 7)
    } else {
      poolTags.value = []
    }
  } catch {
    poolTags.value = []
  }
}

async function submitCreate(): Promise<void> {
  if (!createForm.name.trim()) {
    ElMessage.warning(t('batch.nameRequired'))
    return
  }
  if (selectedCaseIds.value.length === 0) {
    ElMessage.warning(t('batch.caseRequired'))
    return
  }
  if (!projectId.value) return
  saving.value = true
  try {
    const created = await createBatch({
      projectId: projectId.value, name: createForm.name.trim(),
      envId: createForm.envId, caseIds: selectedCaseIds.value,
      notifyFeishu: createForm.notifyFeishu,
      cleanAppData: createForm.cleanAppData,
      poolTagId: createForm.poolTagId ?? null,
      browser: createForm.browser,
    })
    ElMessage.success(t('batch.createOk', { count: created.caseCount }))
    createVisible.value = false
    load(1)
  } catch (e: unknown) {
    ElMessage.error(bizErrorMsg(e, t('batch.createFail')))
  } finally {
    saving.value = false
  }
}

const templateVisible = ref(false)
const templates = ref<BatchTemplateView[]>([])
const tplLoading = ref(false)

async function loadTemplates(): Promise<void> {
  if (!projectId.value) return
  tplLoading.value = true
  try {
    templates.value = (await listBatchTemplates(projectId.value)) || []
  } finally {
    tplLoading.value = false
  }
}

async function openTemplateDialog(): Promise<void> {
  templateVisible.value = true
  await loadTemplates()
}

function saveAsTemplate(row: BatchView): void {
  ElMessageBox.prompt(t('batch.saveTemplatePrompt', { name: row.name }), t('batch.saveTemplate'), {
    inputValue: t('batch.templateNameSuffix', { name: row.name }),
    inputValidator: (v: string) => (v && v.trim() ? true : t('batch.templateNameRequired')),
  })
    .then(async ({ value }) => {
      await saveBatchAsTemplate(row.id, value.trim())
      ElMessage.success(t('batch.saveTemplateOk'))
    })
    .catch((e: unknown) => {
      if (e !== 'cancel' && typeof e === 'object') ElMessage.error(bizErrorMsg(e, t('batch.saveTemplateFail')))
    })
}

const reuseVisible = ref(false)
const reuseTemplateId = ref(0)
const reuseName = ref('')
const reuseEnvId = ref<number | null>(null)

function openReuse(tpl: BatchTemplateView): void {
  reuseTemplateId.value = tpl.id
  reuseName.value = ''
  reuseEnvId.value = null
  reuseVisible.value = true
}

async function submitReuse(): Promise<void> {
  if (!reuseEnvId.value) {
    ElMessage.warning(t('batch.envRequired'))
    return
  }
  saving.value = true
  try {
    const created = await createBatchFromTemplate({
      templateId: reuseTemplateId.value,
      name: reuseName.value.trim() || null,
      envId: reuseEnvId.value,
    })
    ElMessage.success(created.message || t('batch.reuseOk', { count: created.caseCount }))
    reuseVisible.value = false
    templateVisible.value = false
    load(1)
  } catch (e: unknown) {
    ElMessage.error(bizErrorMsg(e, t('batch.reuseFail')))
  } finally {
    saving.value = false
  }
}

const scheduleVisible = ref(false)
const scheduleTemplateId = ref(0)
const scheduleForm = reactive<{ cronExpr: string; enabled: number; scheduleEnvId: number | null }>(
  { cronExpr: '', enabled: 0, scheduleEnvId: null })

function openSchedule(tpl: BatchTemplateView): void {
  scheduleTemplateId.value = tpl.id
  scheduleForm.cronExpr = tpl.cronExpr || ''
  scheduleForm.enabled = tpl.enabled === 1 ? 1 : 0
  scheduleForm.scheduleEnvId = tpl.scheduleEnvId ?? null
  scheduleVisible.value = true
}

async function submitSchedule(): Promise<void> {
  if (scheduleForm.enabled === 1 && (!scheduleForm.cronExpr.trim() || !scheduleForm.scheduleEnvId)) {
    ElMessage.warning(t('batch.scheduleRequired'))
    return
  }
  saving.value = true
  try {
    await updateTemplateSchedule(scheduleTemplateId.value, {
      cronExpr: scheduleForm.cronExpr.trim() || null,
      enabled: scheduleForm.enabled,
      scheduleEnvId: scheduleForm.enabled === 1 ? scheduleForm.scheduleEnvId : null,
    })
    ElMessage.success(t('batch.scheduleOk'))
    scheduleVisible.value = false
    loadTemplates()
  } catch (e: unknown) {
    ElMessage.error(bizErrorMsg(e, t('batch.scheduleFail')))
  } finally {
    saving.value = false
  }
}

function removeTemplate(tpl: BatchTemplateView): void {
  ElMessageBox.confirm(t('batch.deleteTemplateConfirm', { name: tpl.name }), t('batch.delete'), { type: 'warning' })
    .then(async () => {
      await deleteBatchTemplate(tpl.id)
      ElMessage.success(t('batch.deleteTemplateOk'))
      loadTemplates()
    })
    .catch((e: unknown) => {
      if (e !== 'cancel' && typeof e === 'object') ElMessage.error(bizErrorMsg(e, t('batch.deleteTemplateFail')))
    })
}

onMounted(() => {
  if (projectId.value) {
    void loadEnvs()
  }
  // Lens-only landing has no project segment but does have a resolvable
  // program-wide anchor — gate on the shared predicate (R40).
  if (hasQueryScope(projectId.value)) void load()
})

watch(followTick, () => {
  if (!hasQueryScope(projectId.value)) return
  if (projectId.value) void loadEnvs()
  void load(1)
})

// Header lens flip (pick / switch / detach) → refetch the visible segment from
// page 1, same posture as the project-header followTick reload above.
watchProgramLens(() => void load(1), () => hasQueryScope(projectId.value))

useKeepAliveList(async () => {
  if (!hasQueryScope(projectId.value)) return
  if (projectId.value) await Promise.all([loadEnvs(), load()])
  else await load()
})
</script>

<style scoped>
.page-root {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.picker-box {
  width: 100%;
}
.picker-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.picked-hint {
  margin-left: auto;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
</style>
