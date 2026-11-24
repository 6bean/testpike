<template>
  <div class="page-root scheduler-health-page" data-test="scheduler-health-page">
    <div class="page-card">
      <!-- R25 mode declaration (scope-aware single control, TB group): this page
           carries no tabs and exactly one page-surface table, so the page-level
           column-settings popover equals the table scope: show/hide, order,
           pinning and density persist per user through the shared useColumnConfig
           piece (viewKey scheduler-health).
           R21 same-band merge (FB group): no row-level batch action bar and a
           single screening control (keyword), so the page-level variant action
           "run now" rides the filter-bar trailing slot (variant first, then
           settings, then refresh) and the now-empty header band is dropped —
           the page name rides the global top bar per R23. The keyword field
           already submits on enter/clear. Anchors kept. -->
      <div class="filter-bar" data-test="scheduler-health-filter-bar">
        <el-input
          v-model="keyword"
          :placeholder="t('schedulerHealth.keywordPlaceholder')"
          clearable
          style="width: 200px"
          data-test="scheduler-health-keyword"
          @keyup.enter="load(1)"
          @clear="load(1)"
        />
        <div class="filter-bar-actions">
          <el-button type="primary" :icon="VideoPlay" data-test="scheduler-run-now-open" @click="openRunDialog">
            {{ t('schedulerHealth.runNow') }}
          </el-button>
          <ColumnSettings
            :columns="settingsRows"
            :density="colCfg.state.density"
            @toggle="colCfg.toggle"
            @move="colCfg.move"
            @density="colCfg.setDensity"
            @reset="colCfg.reset"
            @pin="colCfg.toggleFixed"
          />
          <!-- refresh stays a view-state action in the same trailing slot, behind settings -->
          <el-button :icon="Refresh" data-test="scheduler-health-reload" @click="load()">
            {{ t('schedule.refresh') }}
          </el-button>
        </div>
      </div>

      <TableSkeleton v-if="loading" :cols="colCfg.visibleColumns.value.length" data-test="schedulerhealthview-skeleton" />
      <el-table border v-else :data="rows" :size="colCfg.state.density" @sort-change="onSortChange" data-test="scheduler-health-table" class="data-table" row-key="jobName">
        <el-table-column
          v-for="col in colCfg.visibleColumns.value"
          :key="col.key"
          :prop="col.key"
          :fixed="col.fixed || false"
          :label="t(col.labelKey)"
          :width="col.width ?? undefined"
          :min-width="col.width ? undefined : SCHEDULER_HEALTH_FLEX_MIN[col.key]"
          :sortable="col.key === 'status' ? false : 'custom'"
          :show-overflow-tooltip="col.key === 'jobName' || col.key === 'lockedBy'"
          :data-test="`scheduler-health-col-${col.key}`"
        >
          <template #default="{ row }">
            <template v-if="col.key === 'jobName'">{{ row.jobName }}</template>
            <template v-else-if="col.key === 'description'">
              <span class="desc-cell" data-test="scheduler-health-desc">{{ row.description }}</span>
            </template>
            <template v-else-if="col.key === 'status'">
              <el-tag :type="statusTag(row).type" size="small" data-test="scheduler-health-status">
                {{ statusTag(row).label }}
              </el-tag>
            </template>
            <template v-else-if="col.key === 'lockedBy'">{{ row.lockedBy || '—' }}</template>
            <template v-else-if="col.key === 'lockedAt'"><TimeCell v-if="row.lockedAt" :iso="toIso(row.lockedAt)" /><span v-else>—</span></template>
            <template v-else-if="col.key === 'lockUntil'"><TimeCell v-if="row.lockUntil" :iso="toIso(row.lockUntil)" /><span v-else>—</span></template>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty :description="t('schedulerHealth.emptyNoData')" />
        </template>
      </el-table>

      <div class="pagination-bar">
        <el-pagination v-if="total > 0"
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          data-test="scheduler-health-pagination"
          @current-change="load()"
          @size-change="load(1)"
        />
      </div>

      
      <el-dialog v-model="runDialogVisible" :title="t('schedulerHealth.dialogTitle')" width="640px"
        data-test="scheduler-run-dialog">
        <el-select v-model="selectedJob" :placeholder="t('schedulerHealth.dialogSelectPlaceholder')"
          filterable style="width: 100%" :loading="jobsLoading" data-test="scheduler-run-job-select">
          <el-option v-for="job in jobs" :key="job" :label="describeJob(job)" :value="job"
            data-test="scheduler-run-job-option" />
        </el-select>
        <p class="dialog-hint" data-test="scheduler-run-dialog-hint">{{ t('schedulerHealth.dialogHint') }}</p>
        <template #footer>
          <el-button @click="runDialogVisible = false" :disabled="running" data-test="scheduler-run-cancel">
            {{ t('common.cancel') }}
          </el-button>
          <el-button type="primary" :loading="running" :disabled="!selectedJob"
            data-test="scheduler-run-confirm" @click="confirmRun">
            {{ running ? t('schedulerHealth.runningWait') : t('schedulerHealth.dialogRunConfirm') }}
          </el-button>
        </template>
      </el-dialog>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Refresh, VideoPlay } from '@element-plus/icons-vue'
import TimeCell from '@/components/TimeCell.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import ColumnSettings from '@/components/ColumnSettings.vue'
import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'
import {
  getSchedulerHealth,
  getSchedulerJobs,
  runSchedulerJobNow,
  type SchedulerHealthRow,
} from '@/api/schedule'

defineOptions({ name: 'SchedulerHealthView' })

const { t } = useI18n()

// Column-configuration wiring (N-3 repayment, shared useColumnConfig piece):
// all six health-table data columns flow through the visibleColumns loop so
// show/hide, order, pinning and density persist per user under viewKey
// 'scheduler-health'; there is no locked slot outside the loop, so the
// skeleton mirrors the visible count one-to-one. The composed status badge
// has no server sort key and honestly stays unsorted (SORT_KEY_MAP below).
const SCHEDULER_HEALTH_COLUMNS: ColumnDef[] = [
  { key: 'jobName', labelKey: 'schedulerHealth.job', width: null },
  { key: 'description', labelKey: 'schedulerHealth.description', width: null },
  { key: 'status', labelKey: 'schedulerHealth.status', width: 110 },
  { key: 'lockedBy', labelKey: 'schedulerHealth.lockedBy', width: 160 },
  { key: 'lockedAt', labelKey: 'schedulerHealth.lockedAt', width: 170 },
  { key: 'lockUntil', labelKey: 'schedulerHealth.lockUntil', width: 170 },
]
// job name and description are the two flexible columns (description keeps
// its wrapped full text, no tooltip ellipsis, per the page contract)
const SCHEDULER_HEALTH_FLEX_MIN: Record<string, number> = { jobName: 200, description: 240 }
const colCfg = useColumnConfig('scheduler-health', SCHEDULER_HEALTH_COLUMNS)
const settingsRows = computed(() =>
  colCfg.state.columns.map((c) => ({ ...c, labelKey: SCHEDULER_HEALTH_COLUMNS.find((d) => d.key === c.key)!.labelKey })),
)

const rows = ref<SchedulerHealthRow[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const keyword = ref('')

const runDialogVisible = ref(false)
const jobs = ref<string[]>([])
const jobsLoading = ref(false)
const selectedJob = ref('')
const running = ref(false)

// Column prop -> core scheduler-health SORT_KEYS whitelist key (single source
// for the sortable decision and the request payload; the composed status
// badge has no whitelist entry and honestly stays unsorted). The health rows
// are merged in core memory from the job catalog x shedlock, and the same
// whitelist gate orders that merged window server-side.
const SORT_KEY_MAP: Record<string, string> = {
  jobName: 'job_name',
  description: 'description',
  lockedBy: 'locked_by',
  lockedAt: 'locked_at',
  lockUntil: 'lock_until',
}
const query = reactive<{ sortBy: string; sortDir: string }>({ sortBy: '', sortDir: '' })

/** Header sort: forward the whitelisted key to core (ordering happens in the
 * server-side merged window, not the loaded page); clearing a sort falls back
 * to the catalog registration order; page resets to 1. */
function onSortChange({ prop, order }: { prop: string; order: string | null }): void {
  if (order === null) {
    query.sortBy = ''
    query.sortDir = ''
    page.value = 1
    void load()
    return
  }
  const key = SORT_KEY_MAP[prop]
  if (!key) return
  query.sortBy = key
  query.sortDir = order === 'ascending' ? 'asc' : 'desc'
  page.value = 1
  void load()
}


const statusTag = (row: SchedulerHealthRow): { type: 'success' | 'info' | 'warning'; label: string } => {
  if (!row.lockedBy) return { type: 'info', label: t('schedulerHealth.statusNeverRun') }
  if (row.running) return { type: 'success', label: t('schedulerHealth.statusRunning') }
  return { type: 'warning', label: t('schedulerHealth.statusIdle') }
}


function toIso(ts: string): string {
  return ts.replace(' ', 'T')
}


function describeJob(job: string): string {
  const row = rows.value.find((r) => r.jobName === job)
  return row && row.description !== job ? `${row.description}（${job}）` : job
}

async function load(to: number = page.value) {
  loading.value = true
  try {
    const resp = await getSchedulerHealth({
      keyword: keyword.value.trim() || undefined,
      page: to,
      size: size.value,
      sortBy: query.sortBy || undefined,
      sortDir: query.sortDir || undefined,
    })
    rows.value = resp.list
    total.value = resp.total
    page.value = to
  } finally {
    loading.value = false
  }
}

async function openRunDialog() {
  runDialogVisible.value = true
  selectedJob.value = ''
  jobsLoading.value = true
  try {
    jobs.value = await getSchedulerJobs()
  } catch (e) {
    jobs.value = []
    ElMessage.error(e instanceof Error ? e.message : t('schedulerHealth.jobsLoadFailed'))
  } finally {
    jobsLoading.value = false
  }
}

async function confirmRun() {
  if (!selectedJob.value || running.value) return
  running.value = true
  try {
    const message = await runSchedulerJobNow(selectedJob.value)
    ElMessage.success(message || t('schedulerHealth.runSuccess', { job: selectedJob.value }))
    runDialogVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : t('schedulerHealth.runFailed', { job: selectedJob.value }))
  } finally {
    running.value = false
  }
}

onMounted(() => load(1))

</script>

<style scoped>
.scheduler-health-page {
  padding: 0;
}
.desc-cell {
  white-space: normal;
  word-break: break-all;
}
.dialog-hint {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.data-table {
  width: 100%;
}
.pagination-bar {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
</style>
