<template>
  <div class="page-root" data-test="process-page">
    <!-- Embedded mode : hosted by the execution page tab, whose own
 page-card already frames the content, so the inner card is dropped
 to avoid a frame-in-frame. Standalone class kept for the redirect
 era tooling (E2E anchors on process-page either way). -->
    <div :class="embedded ? 'process-embedded' : 'page-card'">
      <div class="toolbar">
        <div class="toolbar-right">
          <el-button
            v-if="canCancel"
            type="danger"
            plain
            :disabled="!selected.length"
            :loading="batchCancelling"
            data-test="process-batch-cancel"
            @click="onBatchCancel"
          >
            {{ t('process.batchCancel', { n: selected.length }) }}
          </el-button>
          <el-button :icon="Refresh" data-test="process-refresh" @click="load(1)">
            {{ t('process.refresh') }}
          </el-button>
        </div>
      </div>

      <el-alert
        v-if="!canCancel"
        type="info"
        :closable="false"
        class="process-readonly-hint"
        data-test="process-readonly-hint"
        :title="t('process.noPermHint')"
      />

      
      <div class="process-waterlevel" data-test="process-waterlevel">
        <span class="waterlabel">{{ t('process.waterTitle') }}</span>
        <el-tag :type="waterFull ? 'danger' : 'primary'" effect="dark" data-test="water-global">
          {{ t('process.waterGlobal', { running: water?.globalRunning ?? 0, max: maxText(water?.globalMax ?? 0) }) }}
        </el-tag>
        <el-tag type="info" effect="plain">{{ t('process.waterPerProject', { max: maxText(water?.perProjectMax ?? 0) }) }}</el-tag>
        <el-tag
          v-for="p in water?.projects ?? []"
          :key="p.projectId"
          type="warning"
          effect="plain"
          class="water-project-tag"
          data-test="water-project-item"
        >
          {{ t('process.waterProjectItem', { id: p.projectId, n: p.running }) }}
        </el-tag>
        <span v-if="!(water?.projects ?? []).length" class="muted">{{ t('process.waterEmpty') }}</span>
      </div>

      <TableSkeleton v-if="loading" :cols="8" data-test="processview-skeleton" />
      <el-table border
        v-else
        :data="rows"
        row-key="taskId"
        data-test="process-table"
        @selection-change="onSelectionChange"
        @sort-change="onSortChange"
      >
        <el-table-column v-if="canCancel" type="selection" width="44" :selectable="() => true" />
        <el-table-column prop="taskId" :label="t('process.taskId')" width="110" sortable="custom">
          <template #default="{ row }">
            <span class="mono">#{{ row.taskId }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="caseTitle" :label="t('process.caseTitle')" min-width="220" show-overflow-tooltip sortable="custom">
          <template #default="{ row }">
            <el-link type="primary" underline="never" @click="openCase(row)">{{ rowTitle(row) }}</el-link>
          </template>
        </el-table-column>
        <el-table-column prop="batch" :label="t('process.batch')" width="120" sortable="custom">
          <template #default="{ row }">
            <el-link v-if="row.batchId" type="primary" underline="never" @click="openBatch(row.batchId)">
              #{{ row.batchId }}
            </el-link>
            <span v-else class="muted">{{ t('process.trialRun') }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="executor" :label="t('process.executor')" width="120" sortable="custom">
          <template #default="{ row }">
            <span v-if="row.executorId" class="mono">EX-{{ row.executorId }}</span>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="startedAt" :label="t('process.startedAt')" width="170" sortable="custom">
          <template #default="{ row }"><TimeCell :iso="row.startedAt" /></template>
        </el-table-column>
        <el-table-column prop="elapsed" :label="t('process.elapsed')" width="120" sortable="custom">
          <template #default="{ row }">{{ fmtDuration(row.elapsedMs) }}</template>
        </el-table-column>
        <el-table-column :label="t('process.budget')" width="180">
          <template #default="{ row }">
            <el-progress
              :percentage="budgetPct(row)"
              :stroke-width="10"
              :color="budgetColor(row)"
              :format="() => `${fmtDuration(row.elapsedMs)} / ${fmtDuration(row.timeoutMs)}`"
              data-test="process-budget-bar"
            />
          </template>
        </el-table-column>
        <el-table-column v-if="canCancel" :label="t('common.action')" width="110" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="danger"
              :loading="cancellingId === row.taskId"
              data-test="process-cancel-btn"
              @click="onCancel(row)"
            >
              {{ t('process.cancel') }}
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="process-empty" data-test="process-empty">
            <el-icon :size="40" color="var(--tp-text-secondary)"><Odometer /></el-icon>
            <p>{{ t('process.empty') }}</p>
          </div>
        </template>
      </el-table>

      <div class="pagination-bar">
        <el-pagination
          v-if="total > 0"
          v-model:current-page="page"
          :page-size="size"
          :total="total"
          layout="total, prev, pager, next"
          data-test="process-pager"
          @current-change="load"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">

import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Odometer, Refresh } from '@element-plus/icons-vue'
import {
  batchCancelExecTasks,
  cancelExecTask,
  getParallelWaterLevel,
  listRunningTasks,
  type ParallelWaterLevelInfo,
  type RunningTaskInfo,
} from '@/api/executor'
import { useAuthStore } from '@/stores/auth'
import TableSkeleton from '@/components/TableSkeleton.vue'
import TimeCell from '@/components/TimeCell.vue'

/** merge host flag: rendered inside the execution page tab when true
 * (frame card suppressed; the 30s poll stays mounted — the tab is a live
 * view). Default false keeps standalone semantics for any leftover link. */
const props = withDefaults(defineProps<{ embedded?: boolean }>(), { embedded: false })
const embedded = computed(() => props.embedded)

const { t } = useI18n()
const router = useRouter()
const auth = useAuthStore()
const canCancel = computed(() => auth.hasPerm('perm:exec:task-cancel'))

const rows = ref<RunningTaskInfo[]>([])
const total = ref(0)
const page = ref(1)
const size = 20
const loading = ref(false)
const selected = ref<RunningTaskInfo[]>([])
const cancellingId = ref<number | null>(null)
const batchCancelling = ref(false)
const water = ref<ParallelWaterLevelInfo | null>(null)


const waterFull = computed(() => {
  const w = water.value
  return !!w && w.globalMax > 0 && w.globalRunning >= w.globalMax
})


function maxText(max: number): string {
  return max > 0 ? String(max) : t('process.waterUnlimited')
}

async function loadWater() {
  try {
    water.value = await getParallelWaterLevel()
  } catch {
    water.value = null
  }
}

async function load(toPage = page.value) {
  loading.value = true
  void loadWater()
  try {
    const res = await listRunningTasks(toPage, size, { sortBy: query.sortBy, sortDir: query.sortDir })
    rows.value = res.list ?? []
    total.value = res.total ?? 0
    page.value = toPage
  } catch {
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

// Column prop -> core running SORT_COLUMNS whitelist key (single source for
// the sortable decision and the request payload; . Sorting
// happens server-side over the whole running window, not the loaded page.
const SORT_KEY_MAP: Record<string, string> = {
  taskId: 'id',
  caseTitle: 'case_id',
  batch: 'batch_id',
  executor: 'executor_id',
  startedAt: 'started_at',
  elapsed: 'elapsed_ms',
}
const query = reactive<{ sortBy: string; sortDir: string }>({ sortBy: '', sortDir: '' })

/** Header sort: forward the whitelisted key to core; clearing a sort falls
 * back to the historical id ASC order; page resets to 1. */
function onSortChange({ prop, order }: { prop: string; order: string | null }): void {
  page.value = 1
  if (order === null) {
    query.sortBy = ''
    query.sortDir = ''
    void load(1)
    return
  }
  const key = SORT_KEY_MAP[prop]
  if (!key) {
    void load(1)
    return
  }
  query.sortBy = key
  query.sortDir = order === 'ascending' ? 'asc' : 'desc'
  void load(1)
}

function onSelectionChange(vals: RunningTaskInfo[]) {
  selected.value = vals
}

function rowTitle(row: RunningTaskInfo): string {
  return row.caseTitle || `#${row.taskId}`
}

function openCase(row: RunningTaskInfo) {
  void router.push({ path: `/cases`, query: { projectId: String(row.projectId) } })
}

function openBatch(batchId: number) {
  void router.push(`/batches/${batchId}`)
}


function fmtDuration(ms: number): string {
  if (!Number.isFinite(ms) || ms < 0) return '-'
  const s = Math.round(ms / 1000)
  if (s < 60) return t('process.sec', { s })
  return t('process.minSec', { m: Math.floor(s / 60), s: s % 60 })
}


function budgetPct(row: RunningTaskInfo): number {
  if (!row.timeoutMs || row.timeoutMs <= 0) return 0
  return Math.min(100, Math.round((row.elapsedMs / row.timeoutMs) * 100))
}

function budgetColor(row: RunningTaskInfo): string {
  const pct = budgetPct(row)
  if (pct >= 90) return 'var(--el-color-danger)'
  if (pct >= 70) return 'var(--el-color-warning)'
  return 'var(--el-color-primary)'
}

async function onCancel(row: RunningTaskInfo) {
  try {
    await ElMessageBox.confirm(
      t('process.cancelConfirm', { id: row.taskId, title: rowTitle(row) }),
      t('process.cancelTitle'),
      { type: 'warning', confirmButtonText: t('process.cancel'), cancelButtonText: t('common.cancel') },
    )
  } catch {
    return
  }
  cancellingId.value = row.taskId
  try {
    await cancelExecTask(row.taskId)
    ElMessage.success(t('process.cancelDone', { id: row.taskId }))
    await load()
  } catch {
  } finally {
    cancellingId.value = null
  }
}

async function onBatchCancel() {
  const ids = selected.value.map((r) => r.taskId)
  if (!ids.length) return
  try {
    await ElMessageBox.confirm(
      t('process.batchConfirm', { n: ids.length }),
      t('process.cancelTitle'),
      { type: 'warning', confirmButtonText: t('process.cancel'), cancelButtonText: t('common.cancel') },
    )
  } catch {
    return
  }
  batchCancelling.value = true
  try {
    const res = await batchCancelExecTasks(ids)
    const rejected = res.rejectedTaskIds?.length ?? 0
    if (rejected > 0) {
      ElMessage.warning(t('process.batchPartial', { ok: res.cancelled, n: rejected }))
    } else {
      ElMessage.success(t('process.batchDone', { ok: res.cancelled }))
    }
    selected.value = []
    await load()
  } catch {
  } finally {
    batchCancelling.value = false
  }
}

let pollTimer: ReturnType<typeof setInterval> | undefined

onMounted(() => {
  void load(1)
  pollTimer = setInterval(() => {
    if (!loading.value) void load()
  }, 30_000)
})

onUnmounted(() => clearInterval(pollTimer))
</script>

<style scoped>
.process-readonly-hint {
  margin-bottom: 12px;
}
.process-waterlevel {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}
.process-waterlevel .waterlabel {
  color: var(--tp-text-secondary);
  font-size: 13px;
}
.process-empty {
  padding: 32px 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}
.muted {
  color: var(--tp-text-secondary);
}
.mono {
  font-family: var(--tp-font-mono, monospace);
}
</style>
