<template>
  <div class="page-root">
    <div class="page-card" data-test="execution-root">
      <!-- AN-2 (P-1 ruling): the standalone process page merged into this
 page as a tab. Tab state rides the URL query (?tab=process) so deep
 links and the retired /process redirect land on the right tab. The
 process tab is permission-gated with the same key the old route meta
 carried — keyless roles keep seeing exactly what they saw before. -->
      <el-tabs v-model="pageTab" data-test="execution-page-tabs" class="execution-page-tabs">
        <el-tab-pane name="trial">
          <template #label><span data-test="execution-tab-trial">{{ t('exec.pageTabTrial') }}</span></template>
        </el-tab-pane>
        <el-tab-pane v-if="canViewProcess" name="process">
          <template #label><span data-test="execution-tab-process">{{ t('exec.pageTabProcess') }}</span></template>
        </el-tab-pane>
      </el-tabs>

      <!-- Running-tasks tab = ProcessView component reused as-is (no markup
           copy; its own table/pagination/30s poll stay the single source). -->
      <ProcessView v-if="pageTab === 'process'" embedded />

      <template v-if="pageTab !== 'process'">
      
      <div class="section-head">
        <div>
          <h2 class="page-title">{{ t('exec.executorSection') }}</h2>
          <p class="section-desc">{{ t('exec.executorSectionDesc') }}</p>
        </div>
        <el-button :icon="Refresh" size="small" data-test="executor-refresh" @click="loadExecutors">
          {{ t('exec.refresh') }}
        </el-button>
      </div>

      <div v-if="executorLoading" class="executor-grid">
        <el-skeleton v-for="i in 2" :key="i" :rows="3" animated class="executor-skeleton" />
      </div>
      <div v-else-if="executors.length" class="executor-grid" data-test="executor-list">
        <div v-for="ex in executors" :key="ex.id" class="executor-card" data-test="executor-card">
          <div class="executor-card-head">
            <span class="executor-name mono" data-test="executor-name">{{ ex.name }}</span>
            <el-tag :type="executorStatusMeta(ex).type" size="small" data-test="executor-status">
              {{ executorStatusMeta(ex).label }}
            </el-tag>
          </div>
          <div class="executor-meta">
            <span>{{ t('exec.capacity') }}：{{ ex.capacity }}</span>
            <span>{{ t('exec.runningCnt') }}：{{ ex.runningCnt }}</span>
            <span>{{ t('exec.endTypes') }}：{{ endTypesLabel(ex.endTypes) }}</span>
            <span>{{ t('exec.browsers') }}：{{ ex.browsers || '-' }}</span>
            <span v-if="ex.version">{{ t('exec.version') }}：{{ ex.version }}</span>
          </div>
          <div class="executor-capacity-bar" :title="`${ex.runningCnt}/${ex.capacity}`">
            <div
              class="capacity-fill"
              :style="{ '--fill': pct(ex.runningCnt, ex.capacity) }"
              :class="{ full: ex.runningCnt >= ex.capacity }"
            />
          </div>
          <div class="executor-heartbeat" data-test="executor-heartbeat" :title="fmtHeartbeat(ex.lastHeartbeat)">
            {{ t('exec.lastHeartbeat') }}：{{ fmtHeartbeat(ex.lastHeartbeat) }}
            <span v-if="ex.lastHeartbeat" class="heartbeat-ago">（{{ timeAgo(ex.lastHeartbeat) }}）</span>
          </div>
          <div class="executor-card-foot">
            <el-button link type="primary" size="small" data-test="executor-tasks-btn" @click="openExecutorTasks(ex)">
              {{ t('exec.viewTasks') }}
            </el-button>
            <el-button link type="primary" size="small" data-test="executor-self-check-btn" @click="openSelfCheck(ex)">
              {{ t('exec.selfCheck') }}
            </el-button>
          </div>
        </div>
      </div>
      <div v-else class="executor-empty" data-test="executor-empty">
        <el-icon :size="40" color="var(--tp-text-secondary)"><Cpu /></el-icon>
        <p>{{ t('exec.noExecutor') }}</p>
        <p class="guide">{{ t('exec.noExecutorGuide') }}</p>
      </div>

      
      <div v-if="activeExecutor" class="section-head task-head">
        <div>
          <h2 class="page-title" data-test="exec-tasks-title">
            {{ t('exec.executorTasksTitle') }}：{{ activeExecutor.name }}
          </h2>
          <p class="section-desc">{{ t('exec.executorTasksDesc') }}</p>
        </div>
        <el-button size="small" :icon="Close" data-test="exec-tasks-close" @click="closeExecutorTasks">
          {{ t('exec.close') }}
        </el-button>
      </div>

      <template v-if="activeExecutor">
        <el-tabs v-model="execTab" data-test="exec-tasks-tabs" @tab-change="loadExecutorTasks(1)">
          <el-tab-pane :label="`${t('exec.tabPending')}（${execPage?.pending ?? 0}）`" name="pending" />
          <el-tab-pane :label="`${t('exec.tabRunning')}（${execPage?.running ?? 0}）`" name="running" />
          <el-tab-pane :label="`${t('exec.tabDone')}（${((execPage?.done ?? 0) + (execPage?.failed ?? 0))}）`" name="terminal">
            <template #label>
              <span data-test="exec-tab-done">
                {{ t('exec.tabDone') }}（{{ (execPage?.done ?? 0) + (execPage?.failed ?? 0) }}）
                <span v-if="execPage" class="tab-count-detail">{{ t('exec.tabDoneDetail', { done: execPage.done, failed: execPage.failed }) }}</span>
              </span>
            </template>
          </el-tab-pane>
        </el-tabs>

        <TableSkeleton v-if="execTaskLoading" :cols="8" data-test="executioncenterview-skeleton" />
        <DataTablePage :data="execTasks" border data-test="exec-tasks-table"
          @sort-change="onExecSortChange" v-else>
          <el-table-column prop="taskId" :label="t('exec.taskId')" width="110" sortable="custom">
            <template #default="{ row }">
              <span class="mono">#{{ row.id }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="caseNo" :label="t('exec.caseNo')" min-width="180" show-overflow-tooltip sortable="custom">
            <template #default="{ row }">
              <el-link type="primary" underline="never" @click="openCase(row.caseId)">{{ t('exec.caseLink', { id: row.caseId }) }}</el-link>
            </template>
          </el-table-column>
          <el-table-column prop="taskStatus" :label="t('exec.taskStatus')" width="100" sortable="custom">
            <template #default="{ row }">
              <el-tag :type="EXEC_TASK_STATUS[row.status]?.type ?? 'info'" size="small">
                {{ EXEC_TASK_STATUS[row.status]?.label ?? row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <!-- Task kind badge (same single-source piece as the trial-run table;
               static column, not in SORT_KEY_MAP — core whitelist carries no task_type). -->
          <el-table-column :label="t('exec.taskType')" width="100">
            <template #default="{ row }">
              <ExecTaskTypeBadge :row="row" :project-id="row.projectId" />
            </template>
          </el-table-column>
          <el-table-column :label="t('exec.errorDetail')" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="row.errorDetail" class="error-text">{{ row.errorDetail }}</span>
              <span v-else class="muted">-</span>
            </template>
          </el-table-column>
          <el-table-column prop="duration" :label="t('exec.duration')" width="110" sortable="custom">
            <template #default="{ row }">
              {{ row.durationMs != null ? t('exec.durationMs', { ms: row.durationMs }) : '-' }}
            </template>
          </el-table-column>
          <el-table-column prop="createdAt" :label="t('exec.createdAt')" width="165" sortable="custom">
            <template #default="{ row }"><TimeCell :iso="row.createdAt" /></template>
          </el-table-column>
          <el-table-column :label="t('exec.action')" width="210" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" data-test="exec-tasks-detail" @click="openDetail(row)">
                {{ t('exec.viewDetail') }}
              </el-button>
              <el-button
                v-if="row.status === 4 || row.status === 5"
                link
                type="primary"
                size="small"
                data-test="exec-ai-analyze"
                @click="analyzeFailure(row)"
              >
                {{ t('exec.aiAnalyze') }}
              </el-button>
              <el-button
                v-if="RERUNNABLE.includes(row.status)"
                link
                type="success"
                size="small"
                :loading="rerunningId === row.id"
                :data-test="`exec-tasks-rerun-${row.id}`"
                @click="rerun(row)"
              >
                {{ t('exec.rerun') }}
              </el-button>
            </template>
          </el-table-column>
        </DataTablePage>
        <div class="pagination-bar">
          <el-pagination v-if="execTotal > 0"
            v-model:current-page="execPageNum"
            :page-size="size"
            :total="execTotal"
            layout="total, prev, pager, next"
            data-test="exec-tasks-pager"
            @current-change="loadExecutorTasks"
          />
        </div>
      </template>

      
      <div class="section-head task-head">
        <div>
          <h2 class="page-title">{{ t('exec.taskSection') }}</h2>
        </div>
        <div class="task-filters">
          <el-button :icon="Refresh" size="small" data-test="exec-task-refresh" @click="loadTasks(1)">
            {{ t('exec.refresh') }}
          </el-button>
          <!-- shared column-settings popover (single piece, no fork) -->
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

      <template v-if="projectId">
        <TableSkeleton v-if="taskLoading" :cols="8" data-test="executioncenterview-skeleton" />
        <DataTablePage
          :data="tvRenderData"
          :size="colCfg.state.density"
          :column-config="colCfg"
          border
          :span-method="tvSpanMethod"
          :row-style="tvRowStyle"
          :row-class-name="tvRowClass"
          data-test="exec-task-table"
          @sort-change="onSortChange"
         v-else>
          <!-- configurable data columns (show/hide, order, width,
 density persist through the shared useColumnConfig piece). -->
          <el-table-column
            v-for="col in colCfg.visibleColumns.value"
            :key="col.key"
            :prop="col.key"
            :fixed="col.fixed || false"
            :label="t(col.labelKey)"
            :width="col.width ?? undefined"
            :min-width="col.width ? undefined : EXEC_FLEX_MIN[col.key]"
            :sortable="isColSortable(col.key) ? 'custom' : false"
            :show-overflow-tooltip="col.key === 'caseNo' || col.key === 'errorDetail'"
            :data-test="`exec-col-${col.key}`"
          >
            <template #default="{ row }">
              <span v-if="col.key === 'taskId'" class="mono">#{{ row.id }}</span>
              <ExecTaskTypeBadge v-else-if="col.key === 'taskType'" :row="row" :project-id="projectId" />
              <el-link v-else-if="col.key === 'caseNo'" type="primary" underline="never" @click="openCase(row.caseId)">{{ t('exec.caseLink', { id: row.caseId }) }}</el-link>
              <el-tag v-else-if="col.key === 'taskStatus'" :type="EXEC_TASK_STATUS[row.status]?.type ?? 'info'" size="small">
                {{ EXEC_TASK_STATUS[row.status]?.label ?? row.status }}
              </el-tag>
              <span v-else-if="col.key === 'duration'">{{ row.durationMs != null ? t('exec.durationMs', { ms: row.durationMs }) : '-' }}</span>
              <TimeCell v-else-if="col.key === 'createdAt'" :iso="row.createdAt" />
              <span v-else>{{ row[col.key] }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('exec.action')" width="210" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" data-test="exec-task-detail" @click="openDetail(row)">
                {{ t('exec.viewDetail') }}
              </el-button>
              <el-button
                v-if="row.status === 4 || row.status === 5"
                link
                type="primary"
                size="small"
                :data-test="`exec-ai-analyze-${row.id}`"
                @click="analyzeFailure(row)"
              >
                {{ t('exec.aiAnalyze') }}
              </el-button>
              <el-button
                v-if="RERUNNABLE.includes(row.status)"
                link
                type="success"
                size="small"
                :loading="rerunningId === row.id"
                :data-test="`exec-task-rerun-${row.id}`"
                @click="rerun(row)"
              >
                {{ t('exec.rerun') }}
              </el-button>
            </template>
          </el-table-column>
        </DataTablePage>
        <div class="pagination-bar">
          <el-pagination v-if="total > 0"
            v-model:current-page="page"
            :page-size="taskSize"
            :page-sizes="[10, 20, 50, 100, 1000]"
            :total="total"
            layout="total, sizes, prev, pager, next"
            data-test="exec-task-pager"
            @size-change="onTaskSizeChange"
            @current-change="loadTasks"
          />
        </div>
      </template>
      <div v-else class="executor-empty" data-test="exec-task-empty">
        <el-icon :size="40" color="var(--tp-text-secondary)"><List /></el-icon>
        <p>{{ t('exec.emptyTasksGuide') }}</p>
        <el-button type="primary" plain data-test="exec-empty-gocases" @click="router.push('/cases')">
          {{ t('exec.goCases') }}
        </el-button>
      </div>
      </template>
    </div>

    
    <el-dialog
      v-model="detailVisible"
      :title="t('exec.taskDetail')"
      width="640px"
      destroy-on-close
      data-test="exec-detail-dialog"
    >
      <ExecTaskResultView :task="detailTask" />
      <template #footer>
        <el-button data-test="exec-detail-close" @click="detailVisible = false">{{ t('exec.close') }}</el-button>
      </template>
    </el-dialog>

 
    <ExecutorSelfCheckDrawer v-model="selfCheckVisible" :executor-id="selfCheckExecutorId" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Cpu, List, Refresh, Close } from '@element-plus/icons-vue'
import {
  EXEC_TASK_STATUS, EXECUTOR_STATUS, endTypesLabel, getExecTask, listExecTasks, listExecutors,
  listExecutorTasks, rerunBatchCase, type ExecTask, type ExecutorInfo, type ExecutorTaskPage,
} from '@/api/executor'
import { useAuthStore } from '@/stores/auth'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import { requestFailureDiagnosis } from '@/composables/useChatDock'
import { useVirtualRows } from '@/composables/useVirtualRows'
import ExecTaskResultView from '@/components/ExecTaskResultView.vue'
import ExecTaskTypeBadge from '@/components/ExecTaskTypeBadge.vue'
import ExecutorSelfCheckDrawer from '@/components/ExecutorSelfCheckDrawer.vue'
import ProcessView from './ProcessView.vue'
import { timeAgo } from '@/utils/timeAgo'
import ColumnSettings from '@/components/ColumnSettings.vue'
import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'

const { t } = useI18n()

// ---------- page tab (trial / process), URL-query synced ----------
const route = useRoute()
const auth = useAuthStore()
/** Process tab visibility mirrors the retired /process route meta key
    (perm:exec:task-cancel) — single source, no new permission semantics. */
const canViewProcess = computed(() => auth.hasPerm('perm:exec:task-cancel'))
/** Query value wins on entry; an unknown key falls back to the trial tab. */
function tabFromQuery(): 'trial' | 'process' {
  return route.query.tab === 'process' && canViewProcess.value ? 'process' : 'trial'
}
const pageTab = ref<'trial' | 'process'>(tabFromQuery())

// Switching tabs writes the query back (replace = no history spam), so any
// tab state is shareable/refresh-stable per the AN tab-in-URL rule.
watch(pageTab, (tab) => {
  void router.replace({ query: { ...route.query, tab: tab === 'trial' ? undefined : tab } })
})
// External navigations into this page carrying ?tab=process (redirect from
// /process, ⌘K deep link) must move the visible tab, not just the URL.
watch(() => route.query.tab, () => {
  pageTab.value = tabFromQuery()
})

// ---------- column configuration (shared useColumnConfig piece) ----------
/** Configurable data columns of the trial-run task table (the fixed-right
    action column stays locked out). */
const EXEC_TASK_COLUMNS: ColumnDef[] = [
  { key: 'taskId', labelKey: 'exec.taskId', width: 110 },
  { key: 'taskType', labelKey: 'exec.taskType', width: 100 },
  { key: 'caseNo', labelKey: 'exec.caseNo', width: null },
  { key: 'taskStatus', labelKey: 'exec.taskStatus', width: 100 },
  // Widened to the 110px header budget (title + sort caret + padding): at 100px
  // the four-character title wrapped to two lines, which the page-wide
  // single-line header gate flags (measured cell height 46px).
  { key: 'schemaVersion', labelKey: 'exec.schemaVersion', width: 110 },
  { key: 'duration', labelKey: 'exec.duration', width: 110 },
  { key: 'createdAt', labelKey: 'exec.createdAt', width: 165 },
]
/** Flexible columns fall back to min-width when no explicit width is set. */
const EXEC_FLEX_MIN: Record<string, number> = { caseNo: 220 }
const colCfg = useColumnConfig('execTasks', EXEC_TASK_COLUMNS)
/** Popover rows (column state merged with labelKey for rendering). */
const settingsRows = computed(() =>
  colCfg.state.columns.map((c) => ({ ...c, labelKey: EXEC_TASK_COLUMNS.find((d) => d.key === c.key)!.labelKey })),
)

const router = useRouter()

const executors = ref<ExecutorInfo[]>([])
const executorLoading = ref(false)

function pct(running: number, capacity: number): string {
  if (capacity <= 0) return '0'
  return String(Math.min(100, Math.round((running / capacity) * 100)) / 100)
}


function executorStatusMeta(ex: ExecutorInfo): { label: string; type: 'success' | 'warning' | 'danger' | 'info' | 'primary' } {
  const base = EXECUTOR_STATUS[ex.status] ?? { label: String(ex.status), type: 'info' as const }
  if (ex.status === 1) {
    return ex.runningCnt > 0
      ? { label: t('exec.statusBusy'), type: 'warning' }
      : { label: t('exec.statusIdle'), type: 'success' }
  }
  return base
}

function fmtHeartbeat(v?: string | null): string {
  if (!v) return t('exec.heartbeatNever')
  return v.replace('T', ' ').slice(0, 19)
}

const activeExecutor = ref<ExecutorInfo | null>(null)
const execTab = ref('pending')
const execTasks = ref<ExecTask[]>([])
const execTaskLoading = ref(false)
const execPageNum = ref(1)
const execTotal = ref(0)
const execPage = ref<ExecutorTaskPage | null>(null)

// Executor-tab sort state : the executor table renders static
// columns whose props (taskId/caseNo/…) are exactly the SORT_KEY_MAP keys, so
// the trial-run table's single map drives both tables (red line 6, no second
// map). Separate state keeps the two tables' sorts independent; every change
// resets to page 1 and clearing falls back to the backend default order.
const execQuery = reactive<{ sortBy: string; sortDir: string }>({ sortBy: '', sortDir: '' })
function onExecSortChange({ prop, order }: { prop: string; order: string | null }): void {
  execPageNum.value = 1
  if (order === null) {
    execQuery.sortBy = ''
    execQuery.sortDir = ''
    void loadExecutorTasks(1)
    return
  }
  const key = SORT_KEY_MAP[prop]
  if (!key) {
    void loadExecutorTasks(1)
    return
  }
  execQuery.sortBy = key
  execQuery.sortDir = order === 'ascending' ? 'asc' : 'desc'
  void loadExecutorTasks(1)
}

const selfCheckVisible = ref(false)
const selfCheckExecutorId = ref<number | null>(null)

function openSelfCheck(ex: ExecutorInfo): void {
  selfCheckExecutorId.value = ex.id
  selfCheckVisible.value = true
}

async function openExecutorTasks(ex: ExecutorInfo): Promise<void> {
  activeExecutor.value = ex
  execTab.value = 'pending'
  await loadExecutorTasks(1)
}

function closeExecutorTasks(): void {
  activeExecutor.value = null
  execTasks.value = []
  execPage.value = null
  execTotal.value = 0
}

async function loadExecutorTasks(p?: number): Promise<void> {
  const ex = activeExecutor.value
  if (!ex) return
  execTaskLoading.value = true
  try {
    const group = execTab.value === 'terminal' ? 'terminal' : execTab.value
    const data = await listExecutorTasks(ex.id, {
      statusGroup: group, page: p ?? execPageNum.value, size,
      sortBy: execQuery.sortBy, sortDir: execQuery.sortDir,
    })
    execTasks.value = data.list
    execTotal.value = data.total
    execPageNum.value = p ?? execPageNum.value
    execPage.value = data
  } catch (e) {
    ElMessage.error((e as Error).message || t('exec.loadTasksFail'))
  } finally {
    execTaskLoading.value = false
  }
}

async function loadExecutors(): Promise<void> {
  executorLoading.value = true
  try {
    executors.value = await listExecutors()
  } catch (e) {
    executors.value = []
    ElMessage.error((e as Error).message || t('exec.loadExecutorsFail'))
  } finally {
    executorLoading.value = false
  }
}

// R30: single project switch = header global context (wiring piece: URL-first
// chain + follow on header switches). The page-level picker was retired.
const { projectId, followTick } = useProjectPageContext()
watch(followTick, () => {
  if (projectId.value) void loadTasks(1)
})
const tasks = ref<ExecTask[]>([])
const taskLoading = ref(false)
const page = ref(1)
const size = 10
const total = ref(0)

// Column prop -> core exec-task SORT_COLUMNS whitelist key (single source for
// the sortable decision and the request payload; . Sorting
// happens server-side over the whole scoped window, not the loaded page.
const SORT_KEY_MAP: Record<string, string> = {
  taskId: 'id',
  caseNo: 'case_id',
  taskStatus: 'status',
  schemaVersion: 'schema_version',
  duration: 'duration_ms',
  createdAt: 'created_at',
}
function isColSortable(key: string): boolean {
  return key in SORT_KEY_MAP
}
const query = reactive<{ sortBy: string; sortDir: string }>({ sortBy: '', sortDir: '' })

/** Header sort: forward the whitelisted key to core; clearing a sort falls
 * back to the historical newest-first order; page resets to 1. */
function onSortChange({ prop, order }: { prop: string; order: string | null }): void {
  if (order === null) {
    query.sortBy = ''
    query.sortDir = ''
    page.value = 1
    void loadTasks(1)
    return
  }
  const key = SORT_KEY_MAP[prop]
  if (!key) {
    void loadTasks(1)
    return
  }
  query.sortBy = key
  query.sortDir = order === 'ascending' ? 'asc' : 'desc'
  page.value = 1
  void loadTasks(1)
}

const VIRTUAL_SIZE = 1000
const taskSize = ref(10)
const taskVsEnabled = computed(() => taskSize.value >= VIRTUAL_SIZE)
const taskVs = useVirtualRows(tasks, {
  enabled: taskVsEnabled,
  scroller: () => document.querySelector<HTMLElement>('.layout-main'),
  tableEl: () => document.querySelector<HTMLElement>('[data-test="exec-task-table"] .el-scrollbar__view table'),
  // One data column added by the task-kind badge (7 configurable + fixed action col).
  columnCount: computed(() => 8),
})
const tvRenderData = taskVs.renderData
const tvSpanMethod = taskVs.spanMethod
const tvRowStyle = taskVs.rowStyle
const tvRowClass = taskVs.rowClassName()

function onTaskSizeChange(s: number): void {
  taskSize.value = s
  taskVs.resetWindow()
  void loadTasks(1)
}

watch(page, () => {
  if (taskVsEnabled.value) taskVs.resetWindow()
})

async function loadTasks(p?: number): Promise<void> {
  if (!projectId.value) return
  taskLoading.value = true
  try {
    const data = await listExecTasks({
      projectId: projectId.value,
      page: p ?? page.value,
      size: taskSize.value,
      sortBy: query.sortBy,
      sortDir: query.sortDir,
    })
    tasks.value = data.list
    total.value = data.total
    page.value = p ?? page.value
    if (taskVsEnabled.value && !p) taskVs.onScroll()
  } catch (e) {
    ElMessage.error((e as Error).message || t('exec.loadTrialTasksFail'))
  } finally {
    taskLoading.value = false
  }
}

function openCase(caseId: number): void {
  router.push(`/cases/${caseId}`)
}

const detailVisible = ref(false)
const detailTask = ref<ExecTask | null>(null)

const RERUNNABLE = [3, 4, 5, 6]
const rerunningId = ref<number | null>(null)

async function rerun(row: ExecTask): Promise<void> {
  try {
    await ElMessageBox.confirm(t('exec.rerunConfirm'), t('exec.rerun'), { type: 'warning' })
  } catch {
    return
  }
  rerunningId.value = row.id
  try {
    const res = await rerunBatchCase(row.id)
    ElMessage.success(res.message || t('exec.rerunOk', { msg: '' }))
    if (projectId.value) void loadTasks(1)
    if (activeExecutor.value) void loadExecutorTasks(1)
  } catch (e) {
    ElMessage.error(t('exec.rerunFail', { msg: (e as Error).message || String(e) }))
  } finally {
    rerunningId.value = null
  }
}

/**
 * E1 failure-badge deep link : open the chat dock with this task's
 * execution pointer — the dock pre-fills one editable question and the new
 * session row carries the id into the diagnosis chain.
 */
function analyzeFailure(row: ExecTask): void {
  requestFailureDiagnosis(row.id, window.location.hash || '/')
}

async function openDetail(row: ExecTask): Promise<void> {
  detailVisible.value = true
  detailTask.value = null
  try {
    detailTask.value = await getExecTask(row.id)
  } catch (e) {
    ElMessage.error((e as Error).message || t('exec.loadTaskDetailFail'))
  }
}

onMounted(() => {
  void loadExecutors()
  // R30: the retired picker's autoSelectFirst echo used to drive the first
  // loadTasks; the wired context (deep link/header primaryId) must land the
  // task table on mount instead — otherwise a direct visit renders empty.
  if (projectId.value) void loadTasks(1)
})
</script>

<style scoped lang="scss">
.execution-page-tabs {
  margin-bottom: 12px;
}
.section-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;

  .page-title {
    margin: 0;
  }

  .section-desc {
    margin: 4px 0 0;
    color: var(--tp-text-secondary);
    font-size: 12px;
  }
}

.task-head {
  margin-top: 24px;
  align-items: center;

  .task-filters {
    display: flex;
    align-items: center;
    gap: 10px;
  }
}

.executor-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 12px;

  .executor-skeleton {
    padding: 12px;
    border: 1px solid var(--tp-border);
    border-radius: 10px;
  }
}

.executor-card {
  border: 1px solid var(--tp-border);
  border-radius: 10px;
  padding: 12px 16px;
  background: var(--tp-bg-card);

  .executor-card-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 10px;

    .executor-name {
      font-weight: 600;
      font-size: 14px;
    }
  }

  .executor-meta {
    display: flex;
    flex-wrap: wrap;
    gap: 4px 14px;
    color: var(--tp-text-secondary);
    font-size: 12px;
    margin-bottom: 10px;
  }

  .executor-capacity-bar {
    height: 6px;
    background: var(--tp-border);
    border-radius: 3px;
    overflow: hidden;
    margin-bottom: 10px;

    .capacity-fill {
      height: 100%;
      width: 100%;
      transform-origin: left center;
      transform: scaleX(var(--fill, 0));
      background: var(--tp-primary, #409eff);
      border-radius: 3px;
      transition: transform 0.3s;

      &.full {
        background: var(--tp-warning);   
      }
    }
  }

  .executor-heartbeat {
  .heartbeat-ago {
    color: var(--tp-primary);
    opacity: 0.9;
  }

    font-size: 12px;
    color: var(--tp-text-secondary);
  }
}

.executor-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 40px 20px;
  border: 1px dashed var(--tp-border);
  border-radius: 10px;
  color: var(--tp-text-secondary);
  text-align: center;

  p {
    margin: 0;
  }

  .guide {
    font-size: 12px;
  }
}

.executor-card-foot {
  margin-top: 8px;
  border-top: 1px solid var(--tp-border);
  padding-top: 8px;
  display: flex;
  justify-content: flex-end;
}

.tab-count-detail {
  margin-left: 6px;
  font-size: 12px;
  color: var(--tp-text-secondary);
}

.error-text {
  color: var(--tp-danger, #f56c6c);
  font-size: 12px;
}

.muted {
  color: var(--tp-text-secondary);
}

</style>
