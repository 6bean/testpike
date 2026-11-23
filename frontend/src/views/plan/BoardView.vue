<!-- tp-sort-exempt: the board shows plan status cards of the current project segment, bounded by active plans. -->
<template>
  <div class="page-root board-page" data-test="board-page">
    <div class="page-card">
      <!-- migration batch (R21 bands): the header band carried only the refresh
 button — a view-state action — so the whole header row is dropped
 (page name rides the global top bar per R23) and refresh moved into
 the filter-bar trailing slot (anchor kept). -->
      <div class="filter-bar" data-test="board-filter-bar">
        <div class="filter-bar-actions">
          <el-button :icon="Refresh" :disabled="!projectId" data-test="board-refresh" @click="load()">
            {{ t('plan.boardRefresh') }}
          </el-button>
          <!-- Column show/hide, order, density and reset ride the shared
               useColumnConfig piece; the control sits in the filter-bar
               trailing slot (view-state actions never live in a header). -->
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

      <div v-if="!loading && projects.length === 0" class="empty-box" data-test="board-empty-no-project">
        <EmptyState :title="t('plan.noProject')" :action-text="t('plan.create')" data-test="board-empty-action" @action="router.push('/plans')" />
      </div>

      <template v-else>
        
        <div class="stat-cards">
          <el-card shadow="never" class="stat-card" data-test="board-stat-total">
            <div class="stat-value">{{ stats.total }}</div>
            <div class="stat-label">{{ t('plan.boardStatTotal') }}</div>
          </el-card>
          <el-card shadow="never" class="stat-card" data-test="board-stat-running">
            <div class="stat-value running">{{ stats.running }}</div>
            <div class="stat-label">{{ t('plan.boardStatRunning') }}</div>
          </el-card>
          <el-card shadow="never" class="stat-card" data-test="board-stat-completed">
            <div class="stat-value completed">{{ stats.completed }}</div>
            <div class="stat-label">{{ t('plan.boardStatCompleted') }}</div>
          </el-card>
          <el-card shadow="never" class="stat-card" data-test="board-stat-rate">
            <div class="stat-value" :class="rateClass">{{ (stats.avgPassRate * 100).toFixed(1) }}%</div>
            <div class="stat-label">{{ t('plan.boardStatAvgRate') }}</div>
          </el-card>
        </div>

        
        <el-card shadow="never" class="board-card">
          <TableSkeleton v-if="loading" :cols="9" data-test="boardview-skeleton" />
          <DataTablePage
            :data="rows"
            data-test="board-table"
            class="data-table"
            row-key="planId"
            :size="colCfg.state.density"
            :column-config="colCfg"
            v-else
            :empty-title="t('plan.boardEmpty')"
            :empty-hint="t('plan.boardEmptyHint')"
          >
            <el-table-column
              v-for="col in colCfg.visibleColumns.value"
              :key="col.key"
              :prop="col.key === 'period' ? undefined : col.key"
              :fixed="col.fixed || false"
              :label="t(col.labelKey)"
              :width="col.width ?? undefined"
              :min-width="col.width ? undefined : BOARD_FLEX_MIN[col.key]"
              :align="BOARD_CENTER.includes(col.key) ? 'center' : undefined"
              :show-overflow-tooltip="col.key === 'planName'"
              :data-test="`board-col-${col.key}`"
            >
              <template #default="{ row }">
                <el-link v-if="col.key === 'planName'" type="primary" data-test="board-plan-link" @click="goDetail(row.planId)">
                  {{ row.planName }}
                </el-link>
                <el-tag v-else-if="col.key === 'statusLabel'" :type="planStatusTag(row.status)" size="small" data-test="board-status-tag">{{
                  row.statusLabel
                }}</el-tag>
                <span v-else-if="col.key === 'passed'" class="num-passed">{{ row.passed }}</span>
                <span v-else-if="col.key === 'failed'" class="num-failed">{{ row.failed }}</span>
                <div v-else-if="col.key === 'passRate'" class="rate-cell">
                  <el-progress
                    :percentage="Math.round(row.passRate * 100)"
                    :color="rateColor(row.passRate)"
                    :stroke-width="10"
                    data-test="board-rate-bar"
                  />
                  <span class="rate-text" data-test="board-rate-text">{{ (row.passRate * 100).toFixed(1) }}%</span>
                </div>
                <span v-else-if="col.key === 'period'">{{ row.planStart || '-' }} ~ {{ row.planEnd || '-' }}</span>
                <span v-else>{{ row[col.key] }}</span>
              </template>
            </el-table-column>
          </DataTablePage>
        </el-card>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { Refresh } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import { useObjectSync } from '@/composables/useObjectSync'
import ColumnSettings from '@/components/ColumnSettings.vue'
import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'
import { getPlanProgressSummary, planStatusTag, type PlanSummaryRow } from '@/api/plan'

const { t } = useI18n()
const router = useRouter()
const auth = useAuthStore()

/** Column defs of the progress-summary table (locked rows/ops columns stay out). */
const BOARD_COLUMNS: ColumnDef[] = [
  { key: 'planName', labelKey: 'plan.name', width: null },
  { key: 'statusLabel', labelKey: 'plan.status', width: 100 },
  { key: 'caseCount', labelKey: 'plan.caseCount', width: 90 },
  { key: 'passed', labelKey: 'plan.passed', width: 80 },
  { key: 'failed', labelKey: 'plan.failed', width: 80 },
  { key: 'running', labelKey: 'plan.running', width: 80 },
  { key: 'notRun', labelKey: 'plan.notRun', width: 90 },
  { key: 'passRate', labelKey: 'plan.boardPassRate', width: 180 },
  { key: 'period', labelKey: 'plan.period', width: 200 },
]
/** Flexible columns fall back to min-width when no explicit width is set. */
const BOARD_FLEX_MIN: Record<string, number> = { planName: 200 }
/** Count/number columns render center-aligned. */
const BOARD_CENTER = ['caseCount', 'passed', 'failed', 'running', 'notRun']
const colCfg = useColumnConfig('board', BOARD_COLUMNS)
/** Popover rows (column state merged with labelKey for rendering). */
const settingsRows = computed(() =>
  colCfg.state.columns.map((c) => ({ ...c, labelKey: BOARD_COLUMNS.find((d) => d.key === c.key)!.labelKey })),
)

const projects = computed(() => auth.projects)
const { projectId, followTick } = useProjectPageContext()
const rows = ref<PlanSummaryRow[]>([])
const loading = ref(false)


const rateClass = computed(() => {
  if (stats.value.executedCount === 0) return 'rate'
  const r = stats.value.avgPassRate
  if (r >= 0.8) return 'rate-good'
  if (r >= 0.6) return 'rate-warn'
  return 'rate-bad'
})


const stats = computed(() => {
  const total = rows.value.length
  const running = rows.value.filter((r) => r.status === 3).length
  const completed = rows.value.filter((r) => r.status === 4).length
  const executed = rows.value.filter((r) => r.passed + r.failed > 0)
  const avgPassRate = executed.length
    ? executed.reduce((s, r) => s + r.passRate, 0) / executed.length
    : 0
  return { total, running, completed, avgPassRate, executedCount: executed.length }
})

function rateColor(rate: number): string {
  
  if (rate >= 0.95) return '#16a34a'
  if (rate >= 0.8) return '#d97706'
  return '#dc2626'
}

function goDetail(id: number): void {
  router.push(`/plans/${id}`)
}

async function load(): Promise<void> {
  if (!projectId.value) return
  loading.value = true
  try {
    rows.value = (await getPlanProgressSummary(projectId.value)) || []
  } catch {
    rows.value = []
  } finally {
    loading.value = false
  }
}


onMounted(() => {
  if (projectId.value) void load()
})

watch(followTick, () => {
  if (projectId.value) void load()
})

// : the progress summary rows are plan aggregates — a landed plan write
// re-reads them silently on the current project segment (iron rule 2).
useObjectSync({
  watch: ['PLAN_WRITE'],
  onSync: () => void load(),
})
</script>

<style scoped>
.stat-cards {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 16px;
}
.stat-card {
  text-align: center;
  border-radius: 10px;
}
.stat-value {
  font-size: 28px;
  font-weight: 600;
  line-height: 1.3;
}
.stat-value.running {
  
  color: var(--tp-warning);
}
.stat-value.completed {
  
  color: var(--tp-success);
}
.stat-value.rate {
  color: var(--tp-primary, #409eff);
}
.stat-value.rate-good {
  color: var(--tp-success, #10b981);
}
.stat-value.rate-warn {
  color: var(--tp-warning, #f59e0b);
}
.stat-value.rate-bad {
  color: var(--tp-danger, #ef4444);
}
.stat-label {
  margin-top: 6px;
  font-size: 13px;
  color: var(--tp-text-secondary, #909399);
}
.board-card {
  border-radius: 10px;
}
.num-passed {
  
  color: var(--tp-success);
  font-weight: 600;
}
.num-failed {
  
  color: var(--tp-danger);
  font-weight: 600;
}
.rate-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}
.rate-cell .el-progress {
  flex: 1;
}
.rate-text {
  min-width: 44px;
  text-align: right;
  font-size: 12px;
  color: var(--tp-text-secondary, #909399);
}
</style>
