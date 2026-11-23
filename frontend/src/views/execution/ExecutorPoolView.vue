<!-- tp-sort-exempt: registered executors/pools are a handful per environment, served as a full resident set. -->
<template>
  <div class="page-root" data-test="pool-page">
    <div class="page-card">
      <div class="toolbar">
        <div class="toolbar-actions">
          <!-- Page-level variant action sits ahead of the view-state actions
               (R21 same-band merge: no filter conditions on this page). -->
          <el-button type="primary" :icon="Plus" data-test="pool-wizard-open" @click="wizardVisible = true">
            {{ t('pool.wizardAction') }}
          </el-button>
          <el-button :icon="Refresh" data-test="pool-refresh" :loading="loading" @click="load">
            {{ t('pool.refresh') }}
          </el-button>
          <!-- View-state actions stay grouped: the column settings popover sits
               beside refresh in the page action band (no filter conditions on
               this page; AuditLogView precedent, R21 view-state grouping). -->
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

      <!-- Add-pool wizard: the page-level write entry (create pool through the
           single tag channel + enrollment-token issuance through the fleet
           bridge). Landing reloads the pool list through the same load(). -->
      <FleetPoolWizard v-model="wizardVisible" :pools="pools" @landed="load()" />

      <!-- T294d fleet elasticity band: one card per pool from the autoscaler round
           (admin /cluster/pools/scale-plan). Every value shown is the server's own
           answer — the page maps the action enum to a colour and draws the occupancy
           bar from active/current replicas, it never re-decides anything (red line 6). -->
      <div class="fleet-scale-section" data-test="fleet-scale-band">
        <div class="fleet-scale-head">
          <span class="fleet-scale-title">{{ t('pool.elasticTitle') }}</span>
        </div>
        <div v-if="scaleLoading" class="fleet-scale-skeleton" data-test="fleet-scale-skeleton" />
        <div v-else-if="!scalePlans.length" class="fleet-scale-empty" data-test="fleet-scale-empty">
          {{ t('pool.elasticEmpty') }}
        </div>
        <div v-else class="fleet-scale-grid">
          <div
            v-for="plan in scalePlans"
            :key="plan.executorType"
            class="fleet-scale-card"
            :data-test="`fleet-scale-card-${plan.executorType}`"
          >
            <div class="fleet-scale-card-head">
              <span class="fleet-scale-type">{{ plan.executorType }}</span>
              <el-tag
                size="small"
                disable-transitions
                :type="elasticTone(plan.decision.action)"
                :data-test="`fleet-scale-state-${plan.executorType}`"
              >
                <span data-test="fleet-scale-state">{{ t(`pool.elasticAction.${plan.decision.action}`) }}</span>
              </el-tag>
            </div>
            <div class="fleet-scale-metrics">
              <div class="metric">
                <span class="metric-value" data-test="fleet-scale-desired">{{ plan.decision.desiredReplicas }}</span>
                <span class="metric-label">{{ t('pool.elasticDesired') }}</span>
              </div>
              <div class="metric">
                <span class="metric-value" data-test="fleet-scale-actual">{{ plan.demand.currentReplicas }}</span>
                <span class="metric-label">{{ t('pool.elasticActual') }}</span>
              </div>
              <div class="metric">
                <span class="metric-value" data-test="fleet-scale-pending">{{ plan.demand.pendingDispatchDepth }}</span>
                <span class="metric-label">{{ t('pool.elasticPending') }}</span>
              </div>
              <div class="metric">
                <span class="metric-value" data-test="fleet-scale-wait-p95">{{ plan.demand.p95WaitSeconds }}s</span>
                <span class="metric-label">{{ t('pool.elasticWaitP95') }}</span>
              </div>
              <div class="metric metric-health">
                <span class="health-light" :class="`health-light--${elasticTone(plan.decision.action)}`" data-test="fleet-scale-health" />
                <span class="metric-label">{{ t('pool.elasticHealth') }}</span>
              </div>
            </div>
            <el-progress
              :percentage="occupancyPct(plan)"
              :stroke-width="10"
              :color="elasticTone(plan.decision.action) === 'info' ? 'var(--el-color-info)' : undefined"
              :show-percentage="true"
              :data-test="`fleet-scale-occupancy-${plan.executorType}`"
            >
              <span class="occupancy-caption" data-test="fleet-scale-occupancy">
                {{ plan.demand.activeExecutors }} / {{ plan.demand.currentReplicas }}
              </span>
            </el-progress>
            <div class="fleet-scale-reason" data-test="fleet-scale-reason">
              {{ plan.decision.reason || t('pool.elasticReason') }}
            </div>
          </div>
        </div>
      </div>

      <!-- T294d fleet node matrix: one row per reported component version.
           Same data surface as the upgrade-centre panorama (listClusterComponents)
           and the same badge source (@/utils/verdict) — this page never re-maps
           supportState itself (red line 6: the verdict is core's). The ops band
           is honestly disabled: the fleet write channel is not wired in this
           delivery, so the buttons state that instead of pretending to work. -->
      <div class="fleet-matrix-section" data-test="fleet-matrix-band">
        <div class="fleet-matrix-head">
          <span class="fleet-matrix-title">{{ t('pool.matrixTitle') }}</span>
          <el-input v-model="matrixFilter" size="small" clearable class="fleet-band-filter"
            :placeholder="t('pool.matrixFilterPlaceholder')" data-test="fleet-matrix-filter" />
          <div class="fleet-matrix-actions">
            <el-tooltip :content="t('pool.matrixActionNotWired')" placement="top">
              <span>
                <el-button size="small" disabled data-test="fleet-action-scale-out">{{ t('pool.matrixActionScaleOut') }}</el-button>
              </span>
            </el-tooltip>
            <el-tooltip :content="t('pool.matrixActionNotWired')" placement="top">
              <span>
                <el-button size="small" disabled data-test="fleet-action-drain">{{ t('pool.matrixActionDrain') }}</el-button>
              </span>
            </el-tooltip>
            <el-tooltip :content="t('pool.matrixActionNotWired')" placement="top">
              <span>
                <el-button size="small" disabled data-test="fleet-action-pause">{{ t('pool.matrixActionPause') }}</el-button>
              </span>
            </el-tooltip>
          </div>
        </div>
        <TableSkeleton v-if="matrixLoading" :cols="7" data-test="fleet-matrix-skeleton" />
        <div v-else-if="!componentRows.length" class="fleet-matrix-empty" data-test="fleet-matrix-empty">
          {{ t('pool.matrixEmpty') }}
        </div>
        <el-table v-else :data="visibleMatrixRows" size="small" border row-key="id" :max-height="480" data-test="fleet-matrix-table">
          <el-table-column :label="t('pool.matrixColNode')" prop="nodeId" min-width="150" />
          <el-table-column :label="t('pool.matrixColFamily')" prop="componentFamily" min-width="120" />
          <el-table-column :label="t('pool.matrixColCur')" min-width="110">
            <template #default="{ row }">{{ row.currentVersion || '—' }}</template>
          </el-table-column>
          <el-table-column :label="t('pool.matrixColTarget')" min-width="110">
            <template #default="{ row }">{{ row.targetVersion || '—' }}</template>
          </el-table-column>
          <el-table-column :label="t('pool.matrixColState')" width="110">
            <template #default="{ row }">
              <el-tooltip :content="row.stateReason || '—'" placement="top">
                <el-tag :type="verdictTagType(row.supportState)" size="small" disable-transitions
                  :data-test="`fleet-matrix-state-${row.id}`">
                  {{ supportStateLabel(row.supportState, t) }}
                </el-tag>
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column :label="t('pool.matrixColHeartbeat')" width="150">
            <template #default="{ row }">
              <TimeCell :iso="row.reportedAt" />
            </template>
          </el-table-column>
          <el-table-column :label="t('pool.matrixColJump')" width="110" align="center">
            <template #default="{ row }">
              <el-button v-if="executorForNode(row.nodeId)" link type="primary"
                :data-test="`fleet-matrix-jump-${row.nodeId}`" @click="gotoExecutionCenter">
                {{ t('pool.matrixJump') }}
              </el-button>
              <span v-else class="muted" :data-test="`fleet-matrix-jump-none-${row.nodeId}`">—</span>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <!-- T294d fleet scale-event timeline: one row per autoscale action the apply
           channel recorded (newest-first). The channel is not wired in this round,
           so an unwired fleet honestly shows the stated empty line. Layout styles
           ride the closing vision round together with the matrix head band. -->
      <div class="fleet-timeline-section" data-test="fleet-timeline-band">
        <div class="fleet-timeline-head">
          <span class="fleet-timeline-title">{{ t('pool.timelineTitle') }}</span>
          <el-input v-model="timelineFilter" size="small" clearable class="fleet-band-filter"
            :placeholder="t('pool.timelineFilterPlaceholder')" data-test="fleet-timeline-filter" />
          <el-button link type="primary" size="small" data-test="fleet-timeline-show-all"
            :disabled="timelineAll" @click="loadTimelineAll">{{ t('pool.timelineShowAll') }}</el-button>
          <span class="fleet-timeline-window" data-test="fleet-timeline-window">{{ t(timelineAll ? 'pool.timelineWindowAll' : 'pool.timelineWindow') }}</span>
        </div>
        <TableSkeleton v-if="timelineLoading" :cols="5" data-test="fleet-timeline-skeleton" />
        <div v-else-if="!scaleEvents.length" class="fleet-timeline-empty" data-test="fleet-timeline-empty">
          {{ t('pool.timelineEmpty') }}
        </div>
        <!-- Bounded viewport (max-height) keeps the band layout true when the
             window fills up: rows stay in DOM, the band itself never waterfalls. -->
        <el-table v-else :data="visibleScaleEvents" size="small" border :max-height="480" data-test="fleet-timeline-table">
          <el-table-column :label="t('pool.timelineColTime')" width="170">
            <template #default="{ row }">
              <TimeCell :iso="eventIso(row.createdEpochSeconds)" />
            </template>
          </el-table-column>
          <el-table-column :label="t('pool.timelineColPool')" prop="poolKey" min-width="140" show-overflow-tooltip />
          <el-table-column :label="t('pool.timelineColAction')" width="150">
            <template #default="{ row, $index }">
              <el-tag :type="elasticTone(row.action)" size="small"
                :data-test="`fleet-timeline-action-${row.action}-${$index}`">{{ actionLabel(row.action) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('pool.timelineColChange')" width="120">
            <template #default="{ row, $index }">
              <span :data-test="`fleet-timeline-change-${$index}`">{{ row.currentReplicas }} → {{ row.desiredReplicas }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('pool.timelineColReason')" min-width="240" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="fleet-timeline-reason">{{ row.reason || t('pool.elasticReason') }}</span>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <!-- T294d fleet command ledger band: one row per fleet command the protocol
           plane produced (admin fleet command bridge -> core ledger, newest first).
           Status label/colour is a display lookup over the core value domain and
           the progress/receipt sentences cross unchanged — the page derives no
           second verdict (red line 6). Bounded viewport like the timeline band. -->
      <div class="fleet-commands-section" data-test="fleet-commands-band">
        <div class="fleet-commands-head">
          <span class="fleet-commands-title">{{ t('pool.commandsTitle') }}</span>
          <el-input v-model="commandsFilter" size="small" clearable class="fleet-band-filter"
            :placeholder="t('pool.commandsFilterPlaceholder')" data-test="fleet-commands-filter" />
          <span class="fleet-commands-total" data-test="fleet-commands-total">{{ t('pool.commandsTotal', { n: commandRows.length }) }}</span>
          <span class="fleet-commands-note" data-test="fleet-commands-note">{{ t('pool.commandsNote') }}</span>
        </div>
        <TableSkeleton v-if="commandsLoading" :cols="7" data-test="fleet-commands-skeleton" />
        <div v-else-if="!commandRows.length" class="fleet-commands-empty" data-test="fleet-commands-empty">
          {{ t('pool.commandsEmpty') }}
        </div>
        <el-table v-else :data="visibleCommandRows" size="small" border row-key="id" :max-height="480" data-test="fleet-commands-table">
          <el-table-column :label="t('pool.commandsColTime')" width="170">
            <template #default="{ row }">
              <TimeCell :iso="row.createdAt" />
            </template>
          </el-table-column>
          <el-table-column :label="t('pool.commandsColType')" prop="commandType" min-width="130" show-overflow-tooltip />
          <el-table-column :label="t('pool.commandsColTarget')" min-width="140" show-overflow-tooltip>
            <template #default="{ row }">{{ commandTargetLabel(row.targetType) }} #{{ row.targetId }}</template>
          </el-table-column>
          <el-table-column :label="t('pool.commandsColStatus')" width="110">
            <template #default="{ row }">
              <el-tag :type="commandStatusTone(row.status)" size="small"
                :data-test="`fleet-command-status-${row.id}`">
                {{ commandStatusLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('pool.commandsColProgress')" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">{{ row.lastProgress?.message || '—' }}</template>
          </el-table-column>
          <el-table-column :label="t('pool.commandsColReceipt')" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">{{ row.resultDetail || '—' }}</template>
          </el-table-column>
          <el-table-column :label="t('pool.commandsColClaimedBy')" width="110" align="center">
            <template #default="{ row }">{{ row.claimedBy ?? '—' }}</template>
          </el-table-column>
        </el-table>
      </div>

      <!-- T294d fleet watermark curve: one lazily created echarts line chart
           per executor type (admin /cluster/pools/watermarks -> core ledger).
           Every plotted value is a persisted observation from the autoscaler
           sampler — the page groups by type and sorts time ascending, it
           computes no trend and never fills missing samples (red line 6).
           Chart instances are paired with disposal on unmount (R57). -->
      <div class="fleet-watermark-section" data-test="fleet-watermark-band">
        <div class="fleet-watermark-head">
          <span class="fleet-watermark-title">{{ t('pool.watermarkTitle') }}</span>
          <span class="fleet-watermark-window" data-test="fleet-watermark-window">{{ t('pool.watermarkWindow') }}</span>
        </div>
        <TableSkeleton v-if="watermarkLoading" :cols="3" data-test="fleet-watermark-skeleton" />
        <div v-else-if="!watermarkSeries.length" class="fleet-watermark-empty" data-test="fleet-watermark-empty">
          {{ t('pool.watermarkEmpty') }}
        </div>
        <div v-else class="fleet-watermark-grid">
          <div
            v-for="series in watermarkSeries"
            :key="series.executorType"
            class="fleet-watermark-card"
            :data-test="`fleet-watermark-card-${series.executorType}`"
          >
            <div class="fleet-watermark-card-head">
              <span class="fleet-watermark-type">{{ series.executorType }}</span>
              <span class="fleet-watermark-p95" :data-test="`fleet-watermark-p95-${series.executorType}`">
                {{ t('pool.watermarkP95Label') }} {{ series.latest.p95WaitSeconds }}s
              </span>
            </div>
            <div :ref="(el) => setWatermarkChartEl(series.executorType, el)" class="fleet-watermark-chart" />
          </div>
        </div>
      </div>

      <TableSkeleton v-if="loading" :cols="6" data-test="poolview-skeleton" />
      <el-empty v-else-if="!pools.length" data-test="pool-empty" :description="t('pool.empty')">
        <span class="empty-hint">{{ t('pool.emptyHint') }}</span>
        <div class="empty-guide-action">
          <el-button type="primary" data-test="pool-empty-add" @click="wizardVisible = true">
            {{ t('pool.wizardAction') }}
          </el-button>
        </div>
      </el-empty>

      <div v-else class="pool-grid">
        <div v-for="p in pools" :key="p.poolId" class="pool-card" :data-test="`pool-card-${p.poolId}`">
          <div class="pool-card-head">
            <span class="pool-name">{{ p.poolName }}</span>
            <el-tag type="info" size="small">{{ t('pool.ownerProject') }}：{{ projectNameOf(p.projectId) }}</el-tag>
          </div>
          <div class="pool-metrics">
            <div class="metric"><span class="metric-value">{{ p.memberCount }}</span><span class="metric-label">{{ t('pool.members') }}</span></div>
            <div class="metric"><span class="metric-value">{{ p.onlineCount }}</span><span class="metric-label">{{ t('pool.online') }}</span></div>
            <div class="metric"><span class="metric-value">{{ p.capacitySum }}</span><span class="metric-label">{{ t('pool.capacity') }}</span></div>
            <div class="metric"><span class="metric-value">{{ p.runningTaskCount }}</span><span class="metric-label">{{ t('pool.running') }}</span></div>
            <div class="metric"><span class="metric-value">{{ p.pendingTaskCount }}</span><span class="metric-label">{{ t('pool.pending') }}</span></div>
          </div>
          <el-table
            v-if="p.projects.length"
            border
            :data="p.projects"
            :size="colCfg.state.density"
            :data-test="`pool-projects-${p.poolId}`"
          >
            <el-table-column
              v-for="col in colCfg.visibleColumns.value"
              :key="col.key"
              :prop="col.key"
              :fixed="col.fixed || false"
              :label="t(col.labelKey)"
              :width="col.width ?? undefined"
              :min-width="col.width ? undefined : POOL_FLEX_MIN[col.key]"
              :data-test="`pool-proj-col-${col.key}`"
            >
              <template #default="{ row }">
                <template v-if="col.key === 'quota'">
                  <el-tag v-if="row.quotaPct == null" type="info" size="small">{{ t('pool.unlimited') }}</el-tag>
                  <span v-else>{{ row.quotaPct }}%</span>
                </template>
                <template v-else-if="col.key === 'slotCap'">
                  <el-tag v-if="row.slotCap === -1" type="info" size="small">{{ t('pool.unlimited') }}</el-tag>
                  <span v-else>{{ row.slotCap }}</span>
                </template>
                <template v-else-if="col.key === 'runningPending'">
                  <span>{{ row.runningInPool }} / {{ row.pendingCount }}</span>
                  <el-tooltip v-if="row.slotCap === 0" :content="t('pool.noOnlineMember')" placement="top">
                    <el-icon class="quota-warn-icon" :size="14"><WarningFilled /></el-icon>
                  </el-tooltip>
                </template>
                <template v-else-if="col.key === 'water'">
                  <el-progress
                    :percentage="waterLevelPct(row)"
                    :stroke-width="10"
                    :color="waterLevelColor(row)"
                    :show-percentage="false"
                    :data-test="`pool-water-${row.projectId}`"
                  />
                  <el-tag
                    v-if="isQuotaFull(row)"
                    type="danger"
                    size="small"
                    class="quota-full-tag"
                    data-test="pool-quota-full"
                  >{{ t('pool.quotaFull') }}</el-tag>
                </template>
                <span v-else>{{ row[col.key] }}</span>
              </template>
            </el-table-column>
          </el-table>
          <div v-else class="muted no-project">{{ t('pool.noQuotaProject') }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">

import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { Refresh, Plus, WarningFilled } from '@element-plus/icons-vue'
import type { ECharts, EChartsOption } from 'echarts'
import { listExecutorPools, listExecutors, type PoolInfo, type PoolProjectInfo, type ExecutorInfo } from '@/api/executor'
import { getFleetScalePlan, getFleetScaleEvents, getFleetPoolWatermarks, listClusterComponents, listFleetCommands, type FleetCommand, type FleetPoolScalePlan, type FleetScaleEvent, type FleetPoolWatermarkPoint, type ClusterComponentRow } from '@/api/cluster'
import { verdictTagType, supportStateLabel } from '@/utils/verdict'
import TimeCell from '@/components/TimeCell.vue'
import { useAuthStore } from '@/stores/auth'
import TableSkeleton from '@/components/TableSkeleton.vue'
import ColumnSettings from '@/components/ColumnSettings.vue'
import FleetPoolWizard from './FleetPoolWizard.vue'
import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'

const { t, te } = useI18n()
const auth = useAuthStore()

const pools = ref<PoolInfo[]>([])
const wizardVisible = ref(false)
const loading = ref(false)
/** Latest autoscaler round per pool; kept separate from the pool list because
 *  the two endpoints answer different questions (composition vs elasticity). */
const scalePlans = ref<FleetPoolScalePlan[]>([])
const scaleLoading = ref(false)
/** Node matrix rows (same ledger as the upgrade-centre panorama) + the executor
 *  roster used only to light up the running-tasks jump for matching nodes. */
const componentRows = ref<ClusterComponentRow[]>([])
const matrixLoading = ref(false)
const executors = ref<ExecutorInfo[]>([])
const router = useRouter()

/** Agent nodes register under the executor name; no match = honest dash, never a dead link. */
function executorForNode(nodeId: string): ExecutorInfo | undefined {
  return executors.value.find((ex) => ex.name === nodeId)
}

function gotoExecutionCenter(): void {
  void router.push({ name: 'ExecutionCenter' })
}

async function loadMatrix(): Promise<void> {
  matrixLoading.value = true
  try {
    const [rows, exs] = await Promise.all([listClusterComponents(), listExecutors()])
    componentRows.value = rows
    executors.value = exs
  } catch {
    // The request layer already toasts the readable cause; keep the band in its
    // stated empty shape instead of freezing the whole page.
    componentRows.value = []
    executors.value = []
  } finally {
    matrixLoading.value = false
  }
}

/** Action enum → tag tone. Display mapping only: the verdict itself is core's. */
function elasticTone(action: string): 'success' | 'warning' | 'danger' | 'info' {
  if (action === 'HELD') return 'success'
  if (action === 'SCALE_OUT' || action === 'SCALE_IN') return 'warning'
  if (action === 'BUDGET_PAUSED') return 'danger'
  return 'info'
}

/** Bar width straight from the server's own occupancy numbers. */
function occupancyPct(plan: FleetPoolScalePlan): number {
  if (plan.demand.currentReplicas <= 0) return 0
  return Math.min(100, Math.round((plan.demand.activeExecutors / plan.demand.currentReplicas) * 100))
}

async function loadScalePlan(): Promise<void> {
  scaleLoading.value = true
  try {
    scalePlans.value = await getFleetScalePlan()
  } catch {
    // The request layer already surfaces the failure; the band falls back to its
    // honest empty line instead of leaving stale numbers on screen.
    scalePlans.value = []
  } finally {
    scaleLoading.value = false
  }
}

/** Recorded autoscale actions (newest-first window). The ledger is written by the
 *  apply channel, which this delivery round does not wire yet, so a zero-row answer
 *  is the designed state and renders the stated empty line (no fake rows). */
const scaleEvents = ref<FleetScaleEvent[]>([])
const timelineLoading = ref(false)

async function loadTimeline(): Promise<void> {
  timelineLoading.value = true
  try {
    scaleEvents.value = await getFleetScaleEvents(undefined, timelineAll.value ? 200 : undefined)
  } catch {
    scaleEvents.value = []
  } finally {
    timelineLoading.value = false
  }
}

/** Widen the timeline window to the ledger's single-source clamp (200).
 *  Re-clicking is a no-op: the entry disables itself once the wide window loaded. */
async function loadTimelineAll(): Promise<void> {
  if (timelineAll.value) return
  timelineAll.value = true
  await loadTimeline()
}

/** Ledger instants travel as epoch seconds; TimeCell takes an ISO string. */
function eventIso(epochSeconds: number): string {
  return new Date(epochSeconds * 1000).toISOString()
}

/** Action label = the pool.elasticAction value domain (single source with the
 *  elasticity cards); an unknown enum falls back to the raw token, never a guess. */
function actionLabel(action: string): string {
  const key = `pool.elasticAction.${action}`
  return te(key) ? t(key) : action
}

/** Fleet command ledger (admin fleet command bridge; newest-first stays a
 *  core-side ordering — the band renders the rows exactly as served). */
const commandRows = ref<FleetCommand[]>([])
const commandsLoading = ref(false)

async function loadCommands(): Promise<void> {
  commandsLoading.value = true
  try {
    commandRows.value = await listFleetCommands()
  } catch {
    // Read failure degrades the band to its stated empty line; the toast is
    // the request interceptor's single channel (no second error copy here).
    commandRows.value = []
  } finally {
    commandsLoading.value = false
  }
}

/** Instant band filters (view state only): each band narrows the rows it
 *  already loaded, matching the words actually rendered (labels go through
 *  the same display lookups the columns use — R40, no second mapping). The
 *  query never refetches and never re-decides a server verdict. */
const matrixFilter = ref('')
const timelineFilter = ref('')
const commandsFilter = ref('')
const timelineAll = ref(false)

function bandRowMatches(query: string, values: unknown[]): boolean {
  const q = query.trim().toLowerCase()
  if (!q) return true
  return values.some((v) => v != null && String(v).toLowerCase().includes(q))
}

const visibleMatrixRows = computed(() =>
  componentRows.value.filter((row) =>
    bandRowMatches(matrixFilter.value, [
      row.nodeId, row.componentFamily, row.currentVersion, row.targetVersion,
      supportStateLabel(row.supportState, t),
    ]),
  ),
)
const visibleScaleEvents = computed(() =>
  scaleEvents.value.filter((row) =>
    bandRowMatches(timelineFilter.value, [
      row.poolKey, actionLabel(row.action), row.reason,
      `${row.currentReplicas} → ${row.desiredReplicas}`,
    ]),
  ),
)
const visibleCommandRows = computed(() =>
  commandRows.value.filter((row) =>
    bandRowMatches(commandsFilter.value, [
      row.commandType, commandTargetLabel(row.targetType), `#${row.targetId}`,
      commandStatusLabel(row.status), row.claimedBy, row.lastProgress?.message,
      row.resultDetail,
    ]),
  ),
)

/** Command status label/colour = display lookup over the core numeric value
 *  domain (0..5, mirror doc in api/cluster FleetCommand). The page compares no
 *  status numbers and derives no second verdict (red line 6); an out-of-domain
 *  value falls back to the raw number, never a guessed word. */
const COMMAND_STATUS_VALUES = ['PENDING', 'CLAIMED', 'EXECUTING', 'SUCCEEDED', 'FAILED', 'REVOKED'] as const

function commandStatusLabel(status: number): string {
  const name = COMMAND_STATUS_VALUES[status]
  const key = name ? `pool.commandsStatus.${name}` : ''
  return key && te(key) ? t(key) : String(status)
}

function commandStatusTone(status: number): 'success' | 'warning' | 'danger' | 'info' {
  const tone: Record<number, 'success' | 'warning' | 'danger' | 'info'> = {
    0: 'info', 1: 'warning', 2: 'warning', 3: 'success', 4: 'danger', 5: 'info',
  }
  return tone[status] ?? 'info'
}

/** Target type label = the protocol's two-value domain; unknown tokens show
 *  as-is (honest raw value beats an invented category). */
function commandTargetLabel(targetType: string): string {
  const key = `pool.commandsTarget.${targetType}`
  return te(key) ? t(key) : targetType
}

/** Persisted sampler history (newest-first from the core ledger). Kept apart
 *  from the elasticity round: the curve answers "how did the pool trend",
 *  the round answers "what does the autoscaler want right now". */
const watermarks = ref<FleetPoolWatermarkPoint[]>([])
const watermarkLoading = ref(false)

interface WatermarkSeries {
  executorType: string
  /** One pool's samples oldest-first, as the plot needs them. */
  points: FleetPoolWatermarkPoint[]
  /** Newest sample (head of the newest-first window). */
  latest: FleetPoolWatermarkPoint
}

/** Group rows by executor type and flip to ascending time — ordering and
 *  grouping only; every plotted number is the server's own observation. */
const watermarkSeries = computed<WatermarkSeries[]>(() => {
  const byType = new Map<string, FleetPoolWatermarkPoint[]>()
  for (const point of watermarks.value) {
    const list = byType.get(point.executorType)
    if (list) list.push(point)
    else byType.set(point.executorType, [point])
  }
  return [...byType.entries()]
    .map(([executorType, points]) => ({
      executorType,
      points: [...points].sort((a, b) => a.sampledEpochSeconds - b.sampledEpochSeconds),
      latest: points[0],
    }))
    .sort((a, b) => a.executorType.localeCompare(b.executorType))
})

/** Card mount points and lazily created charts, keyed by executor type.
 *  Every init here is paired with the disposal below (R57). */
const watermarkChartEls = new Map<string, HTMLElement>()
const watermarkCharts = new Map<string, ECharts>()
let watermarkResizeHandler: (() => void) | null = null

function setWatermarkChartEl(executorType: string, el: unknown): void {
  if (el) watermarkChartEls.set(executorType, el as HTMLElement)
  else watermarkChartEls.delete(executorType)
}

function watermarkOption(series: WatermarkSeries): EChartsOption {
  return {
    animation: false,
    grid: { left: 36, right: 16, top: 28, bottom: 22 },
    legend: { textStyle: { fontSize: 11 }, top: 0 },
    tooltip: { trigger: 'axis' },
    xAxis: {
      type: 'category',
      data: series.points.map((p) => new Date(p.sampledEpochSeconds * 1000).toLocaleTimeString()),
      axisLabel: { fontSize: 10 },
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      axisLabel: { fontSize: 10 },
      splitLine: { lineStyle: { opacity: 0.25 } },
    },
    series: [
      { name: t('pool.watermarkSeriesPending'), type: 'line', data: series.points.map((p) => p.pendingDispatchDepth), symbolSize: 4, lineStyle: { width: 1.5 } },
      { name: t('pool.watermarkSeriesReplicas'), type: 'line', data: series.points.map((p) => p.currentReplicas), symbolSize: 4, lineStyle: { width: 1.5 } },
      { name: t('pool.watermarkSeriesActive'), type: 'line', data: series.points.map((p) => p.activeExecutors), symbolSize: 4, lineStyle: { width: 1.5 } },
    ],
  }
}

/** Lazily import echarts (perf-pane precedent: keep the chart library off the
 *  first chunk), then set one option per visible type card. */
async function renderWatermarkCharts(): Promise<void> {
  if (!watermarkSeries.value.length) {
    disposeWatermarkCharts()
    return
  }
  const { init } = await import('echarts')
  await nextTick()
  for (const series of watermarkSeries.value) {
    const el = watermarkChartEls.get(series.executorType)
    if (!el) continue
    let chart = watermarkCharts.get(series.executorType)
    if (!chart || chart.isDisposed()) {
      chart = init(el)
      watermarkCharts.set(series.executorType, chart)
    }
    chart.setOption(watermarkOption(series))
  }
  if (!watermarkResizeHandler) {
    watermarkResizeHandler = () => {
      for (const chart of watermarkCharts.values()) {
        if (!chart.isDisposed()) chart.resize()
      }
    }
    window.addEventListener('resize', watermarkResizeHandler)
  }
}

function disposeWatermarkCharts(): void {
  for (const chart of watermarkCharts.values()) {
    if (!chart.isDisposed()) chart.dispose()
  }
  watermarkCharts.clear()
}

async function loadWatermark(): Promise<void> {
  watermarkLoading.value = true
  try {
    watermarks.value = await getFleetPoolWatermarks()
  } catch {
    // The request layer already surfaces the readable cause; the band falls
    // back to its stated empty line instead of freezing the page.
    watermarks.value = []
  } finally {
    watermarkLoading.value = false
  }
}

watch(watermarkSeries, () => { void renderWatermarkCharts() })

onBeforeUnmount(() => {
  if (watermarkResizeHandler) {
    window.removeEventListener('resize', watermarkResizeHandler)
    watermarkResizeHandler = null
  }
  disposeWatermarkCharts()
})

const projectNameById = computed<Map<number, string>>(
  () => new Map(auth.projects.map((p) => [p.projectId, p.projectName])),
)

function projectNameOf(projectId: number): string {
  return projectNameById.value.get(projectId) ?? String(projectId)
}

/** Column defs of the per-pool quota table. Every pool card renders through
 *  this one shared configuration so hide/order/pin/density apply page-wide. */
const POOL_COLUMNS: ColumnDef[] = [
  { key: 'projectName', labelKey: 'pool.projectName', width: null },
  { key: 'quota', labelKey: 'pool.quotaCol', width: 90 },
  { key: 'slotCap', labelKey: 'pool.slotCap', width: 90 },
  { key: 'runningPending', labelKey: 'pool.runningPending', width: 100 },
  { key: 'water', labelKey: 'pool.waterLevel', width: null },
]
/** Flexible columns fall back to min-width when no explicit width is set. */
const POOL_FLEX_MIN: Record<string, number> = { projectName: 120, water: 140 }
const colCfg = useColumnConfig('pool', POOL_COLUMNS)
/** Popover rows (column state merged with labelKey for rendering). */
const settingsRows = computed(() =>
  colCfg.state.columns.map((c) => ({ ...c, labelKey: POOL_COLUMNS.find((d) => d.key === c.key)!.labelKey })),
)


function waterLevelPct(row: PoolProjectInfo): number {
  if (row.slotCap === -1) return 0
  if (row.slotCap === 0) return 100
  return Math.min(100, Math.round((row.runningInPool / row.slotCap) * 100))
}

function waterLevelColor(row: PoolProjectInfo): string {
  const pct = waterLevelPct(row)
  return pct >= 100 ? 'var(--el-color-danger)' : pct >= 70 ? 'var(--el-color-warning)' : 'var(--el-color-primary)'
}


function isQuotaFull(row: PoolProjectInfo): boolean {
  return row.slotCap >= 0 && row.runningInPool >= row.slotCap && row.pendingCount > 0
}

async function load(): Promise<void> {
  loading.value = true
  // Composition, elasticity, the node matrix, the event timeline, the command
  // ledger and the watermark series are six read endpoints; refresh reloads all
  // of them together so no band can show an older round than another.
  const scale = loadScalePlan()
  const matrix = loadMatrix()
  const timeline = loadTimeline()
  const commands = loadCommands()
  const watermark = loadWatermark()
  try {
    pools.value = await listExecutorPools()
  } catch {
    pools.value = []
  } finally {
    loading.value = false
    await Promise.all([scale, matrix, timeline, commands, watermark])
  }
}

onMounted(load)
</script>

<style scoped>
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-left: auto;
}
.pool-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(520px, 1fr));
  gap: 16px;
}
/* Five fleet bands (elasticity / node matrix / scale-event timeline / command
   ledger / watermark curve) share one head-and-body rhythm: one grouped rule
   instead of five copies, so a later page-level patch can never drift one band
   away from the others (R40). */
.fleet-scale-section,
.fleet-matrix-section,
.fleet-timeline-section,
.fleet-commands-section,
.fleet-watermark-section {
  margin-bottom: 16px;
}
.fleet-scale-head,
.fleet-matrix-head,
.fleet-timeline-head,
.fleet-commands-head,
.fleet-watermark-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}
.fleet-scale-title,
.fleet-matrix-title,
.fleet-timeline-title,
.fleet-commands-title,
.fleet-watermark-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.fleet-matrix-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
/* Recency caption on the timeline, ledger and curve heads: one muted side note
   each, riding the shared head flex so it can never drift the band rhythm. */
.fleet-timeline-window,
.fleet-commands-note,
.fleet-commands-total,
.fleet-watermark-window {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
/* Ledger total count: the rows array is the honest full set (the core list
   answers without LIMIT), so one auto margin pins it with the note on the
   head's right edge — same caption tokens, no second counter channel. */
.fleet-commands-total {
  margin-left: auto;
}
/* Curve cards mirror the elasticity card rhythm (same border family and
   auto-fit grid), only the chart body is new here. */
.fleet-watermark-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 16px;
}
.fleet-watermark-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 12px 16px;
}
.fleet-watermark-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.fleet-watermark-type {
  font-weight: 600;
  font-size: 15px;
}
.fleet-watermark-p95 {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.fleet-watermark-chart {
  height: 160px;
}
.fleet-watermark-empty {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  padding: 8px 0;
}
.fleet-scale-grid {
  display: grid;
  /* auto-fit (not auto-fill): with one or two pools the empty tracks collapse
     so the cards stretch across the band instead of leaving dead space right. */
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 16px;
}
.fleet-scale-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 12px 16px;
}
.fleet-scale-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.fleet-scale-type {
  font-weight: 600;
  font-size: 15px;
}
.fleet-scale-metrics {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
}
.metric-health {
  justify-content: flex-start;
}
.health-light {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  display: inline-block;
}
.health-light--success { background: var(--el-color-success); }
.health-light--warning { background: var(--el-color-warning); }
.health-light--danger { background: var(--el-color-danger); }
.health-light--info { background: var(--el-color-info); }
.fleet-scale-reason {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.fleet-scale-empty {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  padding: 8px 0;
}
.fleet-scale-skeleton {
  height: 88px;
  border-radius: 8px;
  background: var(--el-fill-color-light);
  animation: fleet-scale-pulse 1.2s ease-in-out infinite;
}
@keyframes fleet-scale-pulse {
  0%, 100% { opacity: 0.6; }
  50% { opacity: 0.9; }
}
.occupancy-caption {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.pool-card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 12px 16px;
}
.pool-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.pool-name {
  font-weight: 600;
  font-size: 16px;
}
.pool-metrics {
  display: flex;
  gap: 24px;
  margin-bottom: 10px;
}
.metric {
  display: flex;
  flex-direction: column;
  align-items: center;
}
.metric-value {
  font-size: 18px;
  font-weight: 600;
}
.metric-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.quota-warn-icon {
  margin-left: 4px;
  vertical-align: -2px;
  color: var(--el-color-warning);
}
.quota-full-tag {
  margin-left: 8px;
}
.no-project {
  padding: 8px 0;
}
.empty-hint {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.muted {
  color: var(--el-text-color-secondary);
}
/* Band-head instant filters share one width rhythm (view-state controls;
   no query submit button anywhere — R21 stays satisfied without a filter band). */
.fleet-band-filter { width: 200px; }
</style>
