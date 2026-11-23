<!-- tp-owner-echo-exempt (rule-1 file-level only): this page hosts the gated
     create entry; the create dialog itself is the child component
     PerfScenarioWizard.vue, which carries the shared <ProjectOwnerEcho /> —
     the pair is locked together by the ownerEchoWiringT396b vitest contract. -->
<script setup lang="ts">

import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import { hasQueryScope, lensEmptyCopy, listProjectScope, programIdParam, watchProgramLens } from '@/composables/useProgramLens'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import EmptyState from '@/components/EmptyState.vue'
import ColumnSettings from '@/components/ColumnSettings.vue'
import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'
import PerfScenarioWizard from './PerfScenarioWizard.vue'
import { bizErrorMsg } from '@/utils/bizError'
import { useWindowedList } from '@/composables/useWindowedList'
import { useObjectSync } from '@/composables/useObjectSync'
import { getProjectEnvs, type ProjectEnv } from '@/api/project'
import {
  deletePerfScenario,
  findPerfScenario,
  getScenarioDeleteImpact,
  getResourcePreflight,
  getPerfScenarioTrend,
  launchPerfRun,
  pagePerfRuns,
  pagePerfScenarios,
  type PerfPreflightView,
  type PerfScenarioDeleteImpact,
  type PerfRunItem,
  type PerfScenarioItem,
  type PerfTrendRow,
} from '@/api/perf'
import {
  CHART_H,
  CHART_W,
  buildPolySegments,
  fmtNum,
  fmtRate,
  maxOf,
  runStatusTag,
  scaleX,
  scaleY,
} from './chartUtils'

const { t, te } = useI18n()
const router = useRouter()

// : the scenario segment rides the global header context (the page-level
// selector is gone); ?projectId deep links and the T275h upgradeProject
// prefill both land through the composable (its watch syncs the context).
const { projectId, followTick } = useProjectPageContext()
const scenarios = ref<PerfScenarioItem[]>([])
const loading = ref(false)

const drawerOpen = ref(false)
const currentScenario = ref<PerfScenarioItem | null>(null)
const runs = ref<PerfRunItem[]>([])
const runsLoading = ref(false)
// the drawer reads bounded windows, not the whole ledger.
const runsTotal = ref(0)
const runsPage = ref(1)
const RUNS_WINDOW_SIZE = 20

const wizardRef = ref<InstanceType<typeof PerfScenarioWizard> | null>(null)
const route = useRoute()


onMounted(() => {
  const q = route.query
  const pid = Number(q.upgradeProject)
  const cid = Number(q.upgradeCollection)
  const rid = Number(q.upgradeRequest)
  if (!Number.isFinite(pid) || !Number.isFinite(cid) || !Number.isFinite(rid) || pid <= 0 || cid <= 0 || rid <= 0) {
    // Trend deep link (?trend=ID, page-state iron law): a valid id lands with
    // the panel already open after reload/back/forward; an invalid one is
    // dropped quietly (the ledger below loads normally either way).
    const tid = Number(q.trend)
    if (Number.isFinite(tid) && tid > 0) void openTrendById(tid)
    return
  }
  projectId.value = pid
  void loadAll()
  void router.replace({ path: '/perf' })
  wizardRef.value?.openWithAssetRef(pid, cid, rid)
})

async function loadAll() {
  const scope = listProjectScope(projectId.value)
  if (scope == null) return
  loading.value = true
  try {
    const res = await pagePerfScenarios(scope, page.value, pageSize.value, {
      sortBy: query.sortBy, sortDir: query.sortDir,
    }, programIdParam())
    scenarios.value = res.list
    total.value = Number(res.total)
  } catch (e) {
    scenarios.value = []
    total.value = 0
    void bizErrorMsg(e, t('perf.loadFail'))
  } finally {
    loading.value = false
  }
}

// Server-side window state display-scale iron law: the
// ledger pages a bounded core window instead of rendering the whole project
// segment; total is core's truthful count, the page never re-sorts/re-paginates).
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
// Column prop -> core scenario SORT_COLUMNS whitelist key (row fields are the
// snake_case core columns, so keys and values coincide; columns outside the
// whitelist never emit sort params, no client-only fake sort).
const SORT_KEY_MAP: Record<string, string> = {
  name: 'name',
  pressure_model: 'pressure_model',
  login_strategy: 'login_strategy',
  driver_limit: 'driver_limit',
  updated_at: 'updated_at',
}

/**
 * Scenario ledger column definitions for the shared column-config piece (N-3
 * repayment). Widths mirror the previous hard-coded el-table-column attributes
 * so the default layout is unchanged; show/hide, order, pinning and density
 * persist per user through the piece, and the trailing action column stays
 * locked outside the loop. Sortability rides the existing SORT_KEY_MAP
 * whitelist (single source: a column core refuses to sort never shows a
 * clickable header after reconfiguration).
 */
const WORKBENCH_COLUMNS: ColumnDef[] = [
  { key: 'name', labelKey: 'perf.col.name', width: null },
  { key: 'pressure_model', labelKey: 'perf.col.model', width: 130 },
  { key: 'login_strategy', labelKey: 'perf.col.login', width: 150 },
  { key: 'driver_limit', labelKey: 'perf.col.driverLimit', width: 120 },
  { key: 'updated_at', labelKey: 'perf.col.updated', width: 170 },
]
const colCfg = useColumnConfig('perf-workbench', WORKBENCH_COLUMNS)
/** Popover rows (column state merged with labelKey for rendering). */
const settingsRows = computed(() =>
  colCfg.state.columns.map((c) => ({ ...c, labelKey: WORKBENCH_COLUMNS.find((d) => d.key === c.key)!.labelKey })),
)
const query = reactive<{ sortBy: string; sortDir: string }>({ sortBy: '', sortDir: '' })

/** Header sort: forward the whitelisted key to core (ORDER BY happens in SQL);
 * clearing a sort falls back to the historical newest-first order; page resets
 * to 1 so paging windows stay consistent. */
function onSortChange({ prop, order }: { prop: string; order: string | null }): void {
  if (order === null) {
    query.sortBy = ''
    query.sortDir = ''
    page.value = 1
    void loadAll()
    return
  }
  const key = SORT_KEY_MAP[prop]
  if (!key) {
    void loadAll()
    return
  }
  query.sortBy = key
  query.sortDir = order === 'ascending' ? 'asc' : 'desc'
  page.value = 1
  void loadAll()
}

function openCreate() {
  wizardRef.value?.openWizard()
}

function openEdit(sc: PerfScenarioItem) {
  wizardRef.value?.openWith(sc)
}

async function openRuns(sc: PerfScenarioItem) {
  currentScenario.value = sc
  drawerOpen.value = true
  await loadRuns(sc, 1)
}

/**
 * Load one window of the scenario's runs . The drawer used
 * to pull every run in one unbounded list; now page 1 opens the drawer and
 * "load more" appends the next window — newest-first order and the snake_case
 * row fields are unchanged, only the transport gained a window. Appending
 * keeps the existing rows on screen, so a slow page never blanks the panel.
 */
async function loadRuns(sc: PerfScenarioItem, targetPage: number) {
  runsLoading.value = true
  try {
    const res = await pagePerfRuns(projectId.value!, sc.id, targetPage, RUNS_WINDOW_SIZE)
    runs.value = targetPage <= 1 ? res.list : [...runs.value, ...res.list]
    runsTotal.value = Number(res.total)
    runsPage.value = res.page
  } catch (e) {
    if (targetPage <= 1) runs.value = []
    void bizErrorMsg(e, t('perf.runsLoadFail'))
  } finally {
    runsLoading.value = false
  }
}

function loadMoreRuns() {
  const sc = currentScenario.value
  if (!sc) return
  void loadRuns(sc, runsPage.value + 1)
}

/**
 * Launch a run: the management gate + precheck fail fast server-side (core
 * refuses with a human reason, surfaced through the interceptor).
 * GB-1 : instead of firing directly, the row action opens a small
 * dialog whose environment selector defaults to the scenario-bound env; leaving
 * it untouched keeps byte-compatible behavior (core resolves null → scenario
 * default, and here we simply send the default id which core accepts as-is).
 */
const launchDialogOpen = ref(false)
const launchTarget = ref<PerfScenarioItem | null>(null)
const launchEnvId = ref<number | null>(null)
const launchEnvs = ref<ProjectEnv[]>([])
const launching = ref(false)
// CF-5 + CF-7 pre-flight readout: a read-only card that fills in after the
// dialog opens. It never gates the confirm button — rejection stays owned by
// the launch transaction (red line: notice, not gate). Null = no claim yet.
const preflight = ref<PerfPreflightView | null>(null)

async function openLaunch(sc: PerfScenarioItem) {
  launchTarget.value = sc
  launchEnvId.value = sc.env_id
  preflight.value = null
  launchDialogOpen.value = true
  // Pre-flight runs concurrently with the env list; a failure here degrades to
  // a silent card (the operator still launches — the server gate owns refusal).
  void getResourcePreflight(projectId.value!, sc.id)
    .then((pf) => {
      if (launchTarget.value?.id === sc.id) preflight.value = pf
    })
    .catch(() => {
      preflight.value = null
    })
  try {
    launchEnvs.value = await getProjectEnvs(projectId.value!)
  } catch (e) {
    launchEnvs.value = []
    void bizErrorMsg(e, t('perf.opFail'))
  }
}

async function confirmLaunch() {
  const sc = launchTarget.value
  if (!sc || launchEnvId.value == null) return
  launching.value = true
  try {
    const runId = await launchPerfRun(projectId.value!, sc.id, launchEnvId.value)
    launchDialogOpen.value = false
    ElMessage.success(t('perf.launchOk'))
    drawerOpen.value = false
    router.push({ path: `/perf/run/${runId}`, query: { projectId: String(projectId.value) } })
  } catch (e) {
    void bizErrorMsg(e, t('perf.opFail'))
  } finally {
    launching.value = false
  }
}

function goRun(run: PerfRunItem) {
  drawerOpen.value = false
  router.push({ path: `/perf/run/${run.id}`, query: { projectId: String(projectId.value) } })
}

/**
 * Delete a scenario (A-13 frontend half): the row action first reads core's
 * impact ledger, then opens a confirmation card that names the consequences
 * with the server's own numbers — finished runs kept decoupled, approval
 * tickets losing their scenario leg, a mounted CUSTOM script retiring with the
 * row. While the ledger says a run is still in flight the confirm button is
 * honestly disabled (the door refuses server-side anyway; the card tells why
 * up front instead of failing after a click). D2 ruling: history survives.
 */
const deleteDialogOpen = ref(false)
const deleteTarget = ref<PerfScenarioItem | null>(null)
const deleteImpact = ref<PerfScenarioDeleteImpact | null>(null)
const deleteImpactLoading = ref(false)
const deleting = ref(false)

async function openDelete(sc: PerfScenarioItem) {
  deleteTarget.value = sc
  deleteImpact.value = null
  deleteDialogOpen.value = true
  deleteImpactLoading.value = true
  try {
    const impact = await getScenarioDeleteImpact(projectId.value!, sc.id)
    if (deleteTarget.value?.id === sc.id) deleteImpact.value = impact
  } catch (e) {
    // the card stays closed on a failed ledger read — no card without numbers
    deleteDialogOpen.value = false
    void bizErrorMsg(e, t('perf.opFail'))
  } finally {
    deleteImpactLoading.value = false
  }
}

async function confirmDelete() {
  const sc = deleteTarget.value
  if (!sc || !deleteImpact.value || deleteImpact.value.runningRuns > 0) return
  deleting.value = true
  try {
    await deletePerfScenario(projectId.value!, sc.id)
    deleteDialogOpen.value = false
    ElMessage.success(t('perf.deleteOk', { name: sc.name }))
    // reload the window so the row disappears from the live ledger
    await loadAll()
  } catch (e) {
    void bizErrorMsg(e, t('perf.opFail'))
  } finally {
    deleting.value = false
  }
}

function fmtTime(v: string | null): string {
  return v ? v.replace('T', ' ').slice(0, 19) : '—'
}

function modelLabel(m: string): string {
  const key = `perf.model.${m}`
  return te(key) ? t(key) : (m || '—')
}

function statusOf(run: PerfRunItem | PerfTrendRow) {
  return runStatusTag(run.status)
}

/**
 * Scenario trend panel : the ascending terminal-run
 * rows from the core trend read face, drawn as percentile polylines (geometry
 * helpers from chartUtils — same single source as the run detail curves, no
 * second chart piece) plus a windowed row ledger (useWindowedList: at the
 * server-bounded window of up to 200 runs the DOM never renders the full list).
 * Caliber honesty: legacy rows without histogram buckets keep only the worst-
 * second P95 (p50/p99 render '—', never fabricated) and the row/foot note says
 * which caliber a number came from. Open state is URL-addressable (?trend=ID)
 * so reload/back/links restore the panel (page-state iron law).
 */
const trendOpen = ref(false)
const trendScenarioName = ref('')
const trendRows = ref<PerfTrendRow[]>([])
/** Ledger order = newest first (same reading posture as the runs drawer);
 * the chart keeps the core-ascending array — time axis left→right. */
const trendLedger = computed(() => [...trendRows.value].reverse())
const trendLoading = ref(false)
const trendScroller = ref<HTMLElement | null>(null)
const trendList = useWindowedList(trendLedger, { scroller: () => trendScroller.value, rowPitch: 44, buffer: 4 })

async function loadTrendRows(scenarioId: number) {
  trendLoading.value = true
  try {
    trendRows.value = await getPerfScenarioTrend(projectId.value!, scenarioId)
    trendList.resetWindow()
    await nextTick()
    trendList.sync()
    const nodes = trendScroller.value?.querySelectorAll<HTMLElement>('.perf-trend__row')
    if (nodes && nodes.length >= 2) trendList.measurePitch(Array.from(nodes))
  } catch (e) {
    trendRows.value = []
    void bizErrorMsg(e, t('perf.trendLoadFail'))
  } finally {
    trendLoading.value = false
  }
}

// perf-domain segment: a landed PERF_LAUNCH draft presses a run through
// the same PerfRunService.launch transaction the drawer uses, and the draft's
// target_id anchor names the scenario it was emitted on (the strategy states
// this — applied version stays null because a run row has no version ledger).
// The scenario ledger rows themselves carry only scenario fields, so the two
// surfaces a landing moves are the open runs drawer and the open trend panel:
// both re-read silently, newest-first, no toast, no confirmation, no page
// jump (BG iron rule 2). Drawer window depth resets to the latest page-1
// window — the landed row always surfaces.
useObjectSync({
  watch: ['PERF_LAUNCH'],
  anchor: (event) => event.projectId !== null && event.projectId === projectId.value,
  onSync: (event) => {
    const landedScenarioId = event.objectId
    if (landedScenarioId == null) return
    const drawerScenario = currentScenario.value
    if (drawerOpen.value && drawerScenario && drawerScenario.id === landedScenarioId) {
      void loadRuns(drawerScenario, 1)
    }
    if (trendOpen.value && Number(route.query.trend) === landedScenarioId) {
      void loadTrendRows(landedScenarioId)
    }
  },
})

/** Deep-link friendly: resolves the title through the scenario page when the
 * id is not on the visible window (no error toast — trend rows carry their own
 * ownership refusal message from core). */
async function openTrend(sc: PerfScenarioItem) {
  trendScenarioName.value = sc.name
  trendOpen.value = true
  void router.replace({ path: '/perf', query: { ...route.query, trend: String(sc.id) } })
  await loadTrendRows(sc.id)
}

async function openTrendById(scenarioId: number) {
  if (!projectId.value) return
  const known = scenarios.value.find((s) => s.id === scenarioId)
  if (known) {
    void openTrend(known)
    return
  }
  trendScenarioName.value = ''
  trendOpen.value = true
  try {
    const sc = await findPerfScenario(projectId.value!, scenarioId)
    trendScenarioName.value = sc.name
  } catch {
    /* title falls back to the run id below; rows still refuse honestly on bad ids */
  }
  await loadTrendRows(scenarioId)
}

function closeTrend() {
  // Only mirror the query while the user is still on the workbench: when the
  // drawer closes because a row navigated to the run detail, replacing back
  // to /perf would race the in-flight navigation and land the detail page on
  // a route without :id (runId=NaN — browser-reproved in the trend E2E).
  if (route.path !== '/perf') return
  const q = { ...route.query }
  delete q.trend
  void router.replace({ path: '/perf', query: q })
}

/** Drawer closes (any path, including backdrop): the query mirror follows. */
watch(trendOpen, (open) => {
  if (!open && route.query.trend) closeTrend()
})

/** Late-binding safety for the deep link: when the header context resolves
 * after mount (visible-domain refresh path), a still-pending ?trend re-opens
 * the panel — the reload-restores-state contract never silently drops. */
watch(projectId, (v) => {
  const tid = Number(route.query.trend)
  if (v && !trendOpen.value && Number.isFinite(tid) && tid > 0) void openTrendById(tid)
})

function trendMarkTag(mark: string | null): { label: string; type: 'primary' | 'warning' | 'info' } | null {
  if (mark !== 'GOLDEN' && mark !== 'CANDIDATE' && mark !== 'VOID') return null
  const key = `perf.mark.${mark}`
  if (!te(key)) return null
  return { label: t(key), type: mark === 'GOLDEN' ? 'primary' : mark === 'CANDIDATE' ? 'warning' : 'info' }
}

const trendXOf = computed(() => scaleX(0, Math.max(trendRows.value.length - 1, 1)))
const trendYOf = computed(() => scaleY(maxOf(trendRows.value.map((r) => r.p95Ms))))
const p95Segs = computed(() =>
  buildPolySegments(trendRows.value, (_r, i) => trendXOf.value(i), (r) => r.p95Ms, trendYOf.value))
const p50Segs = computed(() =>
  buildPolySegments(trendRows.value, (_r, i) => trendXOf.value(i), (r) => r.p50Ms, trendYOf.value))
const p99Segs = computed(() =>
  buildPolySegments(trendRows.value, (_r, i) => trendXOf.value(i), (r) => r.p99Ms, trendYOf.value))
const goldenDots = computed(() =>
  trendRows.value
    .map((r, i) => ({ r, i }))
    .filter(({ r }) => r.mark === 'GOLDEN' && r.p95Ms !== null)
    .map(({ r, i }) => ({ x: trendXOf.value(i), y: trendYOf.value(r.p95Ms as number) })))

function fmtMs(v: number | null): string {
  return v === null ? '—' : fmtNum(v, 0)
}

/** Caliber short badge: one display phrase per (quantile, error) caliber pair —
 * machine enums (BUCKET/FALLBACK/WEIGHTED/MEAN) never reach the screen. */
function trendCaliberShort(r: PerfTrendRow): string {
  return t(r.quantileCaliber === 'BUCKET' ? 'perf.trend.caliberFull' : 'perf.trend.caliberFallback')
}

function trendCaliberNote(r: PerfTrendRow): string {
  const quantile = r.quantileCaliber === 'BUCKET' ? t('perf.trend.noteBucket') : t('perf.trend.noteFallback')
  const err = r.errorCaliber === 'WEIGHTED' ? t('perf.trend.noteWeighted') : t('perf.trend.noteMean')
  return `${quantile}；${err}`
}

// : a header context switch reloads from the new segment (in-page
// switches reload through their own controls, so followTick only fires
// on context-driven changes and never double-loads). The window resets to
// page 1 so a segment switch never shows project B rows under project A's page.
watch(followTick, () => {
  page.value = 1
  // segment switch: the trend panel never rides project B's rows under project A.
  if (trendOpen.value) trendOpen.value = false
  void loadAll()
})

// Header program lens flip = re-scope the ledger to the new segment (
// MP4-c; fragments single sourced in useProgramLens, R40 — same posture as
// the project followTick reload above: window back to page 1).
watchProgramLens(() => {
  page.value = 1
  void loadAll()
}, () => hasQueryScope(projectId.value))

// Empty-state copy : with the lens ON the guide header names the
// program segment and how to leave it; the legacy three-step guide is verbatim.
const perfEmptyCopy = computed(() => lensEmptyCopy({
  translate: t,
  titleKey: 'perf.lensEmptyTitle',
  emptyKey: 'perf.emptyGuideTitle',
}))

onMounted(() => {
  if (hasQueryScope(projectId.value) && !route.query.upgradeProject) void loadAll()
})

</script>

<template>
  <!-- tp-page-root-exempt: root carries its own 16px padding (live-measured
       1440x900), content edge gap present without the theme .page-root. -->
  <div class="perf-page" data-test="perf-page">
    <div class="page-header">
      <div>
        <p class="perf-page__desc">{{ t('perf.pageDesc') }}</p>
      </div>
    </div>

    <!-- FB same-band merge (10-22 ruling, tag-list precedent): no row-level batch
         bar and zero screening conditions, so the page-level variant action
         (create) rides the .filter-bar-actions trailing slot on the same row as
         the view-state action (column settings), create first; the emptied
         header-actions container is removed as a whole shell. The create entry
         keeps its ownership gate: disabled while the header context is empty,
         the tooltip points at the header instead of a late form error.
         R25 mode declaration (scope-aware single control, TB group): this page
         carries no tabs and exactly one table, so the page-level settings
         popover equals the table scope; show/hide, order, pinning and density
         persist per user via the piece. -->
    <div class="filter-bar" data-test="perf-filter-bar">
      <div class="filter-bar-actions">
        <el-tooltip :content="t('components.ownerEcho.pickInHeader')" placement="bottom" :disabled="!!projectId">
          <span>
            <el-button type="primary" :disabled="!projectId" data-test="perf-create-btn" @click="openCreate">
              {{ t('perf.createScenario') }}
            </el-button>
          </span>
        </el-tooltip>
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

    <el-skeleton v-if="loading" :rows="4" animated />
    <EmptyState
      v-else-if="!projectId"
      icon="Odometer"
      :title="t('perf.noProject')"
      :hint="t('perf.noProjectHint')"
    />
    <!-- U-9 : a project with zero scenarios gets the three-step
 journey card instead of a bare hint — ①pick a shipped template in the
 wizard ②confirm env/dataset ③launch and watch live. Step 1 carries
 the actionable door; steps 2-3 are honest roadmap text (the wizard
 and the live page own them). -->
    <div v-else-if="!scenarios.length" class="perf-empty-guide" data-test="perf-empty-guide">
      <p class="perf-empty-guide__title">{{ perfEmptyCopy.title }}</p>
      <p v-if="perfEmptyCopy.hint" class="perf-empty-guide__hint" data-test="perf-lens-empty-hint">
        {{ perfEmptyCopy.hint }}
      </p>
      <ol class="perf-empty-guide__list">
        <li class="perf-empty-guide__step" data-test="perf-guide-step-1">
          <span class="perf-empty-guide__no">1</span>
          <span class="perf-empty-guide__text">{{ t('perf.guideStep1') }}</span>
          <el-button type="primary" size="small" data-test="perf-guide-start" @click="openCreate">
            {{ t('perf.guideStart') }}
          </el-button>
        </li>
        <li class="perf-empty-guide__step" data-test="perf-guide-step-2">
          <span class="perf-empty-guide__no">2</span>
          <span class="perf-empty-guide__text">{{ t('perf.guideStep2') }}</span>
        </li>
        <li class="perf-empty-guide__step" data-test="perf-guide-step-3">
          <span class="perf-empty-guide__no">3</span>
          <span class="perf-empty-guide__text">{{ t('perf.guideStep3') }}</span>
        </li>
      </ol>
    </div>

    <el-table
      border
      v-else
      stripe
        :data="scenarios"
        :size="colCfg.state.density"
        data-test="perf-scenario-table"
        @sort-change="onSortChange"
      >
        <el-table-column
          v-for="col in colCfg.visibleColumns.value"
          :key="col.key"
          :prop="col.key"
          :fixed="col.fixed || false"
          :label="t(col.labelKey)"
          :width="col.width ?? undefined"
          :min-width="col.width ? undefined : 180"
          :sortable="SORT_KEY_MAP[col.key] ? 'custom' : false"
          :show-overflow-tooltip="col.key === 'name'"
          :data-test="`perf-col-${col.key}`"
        >
          <template #default="{ row }">
            <template v-if="col.key === 'pressure_model'">{{ modelLabel(row.pressure_model) }}</template>
            <template v-else-if="col.key === 'login_strategy'">{{ row.login_strategy || 'PER_VUSER' }}</template>
            <template v-else-if="col.key === 'driver_limit'">{{ row.driver_limit ?? '—' }}</template>
            <template v-else-if="col.key === 'updated_at'">{{ fmtTime(row.updated_at) }}</template>
            <template v-else>{{ row[col.key] }}</template>
          </template>
        </el-table-column>
        <el-table-column :label="t('perf.col.actions')" width="340" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" data-test="perf-row-edit" @click="openEdit(row)">
            {{ t('perf.action.edit') }}
          </el-button>
          <el-button link type="primary" size="small" data-test="perf-row-runs" @click="openRuns(row)">
            {{ t('perf.action.runs') }}
          </el-button>
          <el-button link type="primary" size="small" data-test="perf-row-trend" @click="openTrend(row)">
            {{ t('perf.action.trend') }}
          </el-button>
          <el-button link type="danger" size="small" data-test="perf-row-launch" @click="openLaunch(row)">
            {{ t('perf.action.launch') }}
          </el-button>
          <el-button link type="danger" size="small" data-test="perf-row-delete" @click="openDelete(row)">
            {{ t('perf.deleteAction') }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- The window is bounded server-side (display-scale iron law,
 ; the pager stays hidden on an empty ledger so it never
 invites dead clicks. -->
    <div v-if="total > 0" class="pagination-bar" data-test="perf-scenario-pager">
      <el-pagination
        v-model:current-page="page"
        :page-size="pageSize"
        :total="total"
        layout="prev, pager, next, total"
        @current-change="loadAll"
      />
    </div>
    <el-drawer v-model="drawerOpen" size="520px" :title="currentScenario ? `${currentScenario.name} · ${t('perf.runsTitle')}` : t('perf.runsTitle')" data-test="perf-runs-drawer">
      <el-skeleton v-if="runsLoading" :rows="3" animated />
      <template v-else>
        <p v-if="!runs.length" class="perf-drawer__empty">{{ t('perf.runsEmpty') }}</p>
        <div v-for="r in runs" :key="r.id" class="perf-run-row" data-test="perf-run-row" role="button" tabindex="0" @click="goRun(r)" @keydown.enter="goRun(r)">
          <div class="perf-run-row__main">
            <span class="perf-run-row__id">#{{ r.id }}</span>
            <el-tag size="small" :type="statusOf(r).type">{{ statusOf(r).label }}</el-tag>
          </div>
          <div class="perf-run-row__meta">
            <span>{{ fmtTime(r.started_at) }} → {{ fmtTime(r.finished_at) }}</span>
          </div>
          <p v-if="r.stop_reason" class="perf-run-row__stop">{{ r.stop_reason }}</p>
          <p v-else-if="r.queue_note" class="perf-run-row__stop" data-test="perf-drawer-queue-note">{{ r.queue_note }}</p>
        </div>
        <!-- Bounded window : the count line tells how many
 runs the ledger really has, "load more" appends the next window.
 Hidden while everything shown — no dead click. -->
        <p v-if="runs.length" class="perf-drawer__count" data-test="perf-runs-count">
          {{ t('perf.runsCount', { shown: runs.length, total: runsTotal }) }}
        </p>
        <el-button
          v-if="runs.length < runsTotal"
          link
          type="primary"
          :loading="runsLoading"
          data-test="perf-runs-loadmore"
          @click="loadMoreRuns"
        >
          {{ t('common.loadMore') }}
        </el-button>
      </template>
    </el-drawer>

    <!-- Scenario trend drawer : whole-run percentile curve over
 the terminal-run window + windowed row ledger. Percentiles/error-rate
 recomputation and the window bound live in core; this face draws, maps
 caliber enums to display wording (never leaks machine codes), and
 renders only the visible window of rows. -->
    <el-drawer
      v-model="trendOpen"
      size="720px"
      :title="trendScenarioName ? `${trendScenarioName} · ${t('perf.trendTitle')}` : t('perf.trendTitle')"
      data-test="perf-trend-drawer"
    >
      <!-- Flex fill keeps the drawer body honest at every state: the row
           window grows into the available height instead of a fixed 320px
           strip floating over an empty lower half (vision dim-3 defect). -->
      <div class="perf-trend__body">
      <el-skeleton v-if="trendLoading" :rows="4" animated />
      <template v-else>
        <el-empty v-if="!trendRows.length" class="perf-trend__empty" :image-size="40" :description="t('perf.trendEmpty')" data-test="perf-trend-empty" />
        <template v-else>
          <svg :viewBox="`0 0 ${CHART_W} ${CHART_H}`" class="perf-trend__svg" role="img" :aria-label="t('perf.trendChart')" data-test="perf-trend-chart">
            <line :x1="CHART_W - 10" y1="10" :x2="CHART_W - 10" :y2="CHART_H - 10" class="axis" />
            <line x1="10" :y1="CHART_H - 10" :x2="CHART_W - 10" :y2="CHART_H - 10" class="axis" />
            <polyline v-for="(s, i) in p50Segs" :key="`t50-${i}`" :points="s" class="line-p50" fill="none" />
            <polyline v-for="(s, i) in p95Segs" :key="`t95-${i}`" :points="s" class="line-p95" fill="none" />
            <polyline v-for="(s, i) in p99Segs" :key="`t99-${i}`" :points="s" class="line-p99" fill="none" />
            <circle v-for="(d, i) in goldenDots" :key="`g${i}`" :cx="d.x" :cy="d.y" r="4" class="dot-golden" />
          </svg>
          <p class="perf-trend__legend">
            <span class="perf-trend__swatch sw-p50" /> {{ t('perf.trend.p50') }}
            <span class="perf-trend__swatch sw-p95" /> {{ t('perf.trend.p95') }}
            <span class="perf-trend__swatch sw-p99" /> {{ t('perf.trend.p99') }}
            <span class="perf-trend__swatch dot-golden-swatch" /> {{ t('perf.trend.goldenDot') }}
          </p>
          <div
            class="perf-trend__scroller"
            ref="trendScroller"
            data-test="perf-trend-list"
            @scroll="trendList.sync()"
          >
            <div :style="{ height: trendList.padTop.value + 'px' }" />
            <div v-for="r in trendList.visible.value" :key="r.runId" class="perf-trend__row" data-test="perf-trend-row" role="button" tabindex="0" @click="router.push({ path: `/perf/run/${r.runId}`, query: { projectId: String(projectId) } })" @keydown.enter="router.push({ path: `/perf/run/${r.runId}`, query: { projectId: String(projectId) } })">
              <span class="perf-trend__runid">#{{ r.runId }}</span>
              <el-tag size="small" :type="statusOf(r).type">{{ statusOf(r).label }}</el-tag>
              <el-tag v-if="trendMarkTag(r.mark)" size="small" :type="trendMarkTag(r.mark)!.type">{{ trendMarkTag(r.mark)!.label }}</el-tag>
              <span class="perf-trend__time">{{ fmtTime(r.startedAt) }}</span>
              <span class="perf-trend__stat">{{ t('perf.trend.p50') }} {{ fmtMs(r.p50Ms) }}ms</span>
              <span class="perf-trend__stat">{{ t('perf.trend.p95') }} {{ fmtMs(r.p95Ms) }}ms</span>
              <span class="perf-trend__stat">{{ t('perf.trend.p99') }} {{ fmtMs(r.p99Ms) }}ms</span>
              <span class="perf-trend__stat">{{ t('perf.trend.errRate') }} {{ fmtRate(r.errorRate) }}</span>
              <span class="perf-trend__sep" aria-hidden="true" />
              <el-tooltip :content="trendCaliberNote(r)" placement="top">
                <span class="perf-trend__caliber" data-test="perf-trend-caliber">{{ trendCaliberShort(r) }}</span>
              </el-tooltip>
            </div>
            <div :style="{ height: trendList.padBottom.value + 'px' }" />
          </div>
          <p class="perf-trend__foot" data-test="perf-trend-foot">{{ t('perf.trend.foot', { n: trendRows.length }) }}</p>
        </template>
      </template>
      </div>
    </el-drawer>

    <!-- GB-1 launch dialog: pick the target environment for this run; the
         selector defaults to the scenario-bound env (leaving it untouched =
         the historical behavior). The gate/precheck stays server-side. -->
    <el-dialog
      v-model="launchDialogOpen"
      :title="t('perf.launchTitle')"
      width="560px"
      data-test="perf-launch-dialog"
    >
      <p class="perf-launch__confirm">{{ t('perf.launchConfirm', { name: launchTarget?.name ?? '' }) }}</p>
      <div class="perf-launch__field">
        <label for="pl-env-input">{{ t('perf.launchEnvLabel') }}</label>
        <el-select
          v-model="launchEnvId"
          id="pl-env-input"
          data-test="pl-env"
          filterable
          style="width: 240px"
        >
          <el-option v-for="e in launchEnvs" :key="e.id" :label="e.name" :value="e.id" />
        </el-select>
      </div>
      <p class="perf-launch__hint">{{ t('perf.launchEnvHint') }}</p>
      <!-- CF-5 + CF-7 read-only pre-flight card (notice, not gate): wording is
           projected server-side from the dispatch path's own single sources.
           While loading or when no honest claim exists, nothing renders. -->
      <div v-if="preflight && (preflight.capacityNote || preflight.datasetNote)" class="perf-preflight" data-test="perf-preflight-card">
        <p class="perf-preflight__title">{{ t('perf.launchPreflightTitle') }}<span class="perf-preflight__title-note">{{ t('perf.launchPreflightNote') }}</span></p>
        <p v-if="preflight.engine" class="perf-preflight__engine">{{ t('perf.launchPreflightEngine', { engine: preflight.engine }) }}</p>
        <p v-if="preflight.capacityNote" class="perf-preflight__line">{{ preflight.capacityNote }}</p>
        <p v-if="preflight.datasetNote" class="perf-preflight__line perf-preflight__line--warn">{{ preflight.datasetNote }}</p>
      </div>
      <template #footer>
        <el-button @click="launchDialogOpen = false">{{ t('common.cancel') }}</el-button>
        <el-button
          type="danger"
          :loading="launching"
          :disabled="launchEnvId == null"
          data-test="perf-launch-confirm"
          @click="confirmLaunch"
        >
          {{ t('perf.launchConfirmBtn') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- A-13 delete confirmation card: every consequence line carries the
         ledger numbers read from core just now (no generic "are you sure",
         no locally re-derived counts). In-flight runs disable the confirm
         button with the reason shown; the door itself still refuses
         server-side (notice + honest absence, gate stays single-point). -->
    <el-dialog
      v-model="deleteDialogOpen"
      :title="t('perf.deleteTitle', { name: deleteTarget?.name ?? '' })"
      width="560px"
      data-test="perf-delete-dialog"
    >
      <el-skeleton v-if="deleteImpactLoading" :rows="3" animated />
      <div v-else-if="deleteImpact" data-test="perf-delete-impact">
        <p class="perf-delete__question">{{ t('perf.deleteQuestion') }}</p>
        <p
          v-if="deleteImpact.runningRuns > 0"
          class="perf-delete__blocked"
          data-test="perf-delete-blocked"
        >{{ t('perf.deleteBlocked', { n: deleteImpact.runningRuns }) }}</p>
        <ul class="perf-delete__ledger">
          <li>{{ t('perf.deleteKeepsRuns', { n: deleteImpact.historicalRuns }) }}</li>
          <li>{{ t('perf.deleteUnlinksApprovals', { n: deleteImpact.linkedApprovals }) }}</li>
          <li v-if="deleteImpact.source === 'CUSTOM'" data-test="perf-delete-script">
            {{ t('perf.deleteScriptNote') }}
          </li>
        </ul>
      </div>
      <template #footer>
        <el-button @click="deleteDialogOpen = false">{{ t('common.cancel') }}</el-button>
        <el-button
          type="danger"
          :loading="deleting"
          :disabled="deleteImpact == null || deleteImpact.runningRuns > 0"
          data-test="perf-delete-confirm"
          @click="confirmDelete"
        >
          {{ t('perf.deleteConfirmBtn') }}
        </el-button>
      </template>
    </el-dialog>

    <PerfScenarioWizard ref="wizardRef" :project-id="projectId" @created="loadAll" />
  </div>
</template>

<style scoped>
.perf-page { padding: 16px; }
.perf-page__desc { margin: 0; color: var(--el-text-color-secondary); font-size: 13px; max-width: 720px; }
/* U-9 three-step empty-state card: numbered journey, step 1 actionable. */
.perf-empty-guide { max-width: 680px; margin: 24px auto; padding: 20px 24px; border: 1px solid var(--el-border-color); border-radius: 10px; background: var(--el-fill-color-blank); }
.perf-empty-guide__title { margin: 0 0 14px; font-size: 15px; font-weight: 600; color: var(--el-text-color-primary); }
.perf-empty-guide__list { margin: 0; padding: 0; list-style: none; display: flex; flex-direction: column; gap: 12px; }
.perf-empty-guide__step { display: flex; align-items: flex-start; gap: 10px; }
.perf-empty-guide__no { flex: none; width: 22px; height: 22px; margin-top: 1px; border-radius: 50%; background: var(--el-color-primary-light-9); color: var(--el-color-primary); font-size: 12px; font-weight: 600; display: inline-flex; align-items: center; justify-content: center; }
.perf-empty-guide__text { flex: 1; font-size: 13px; line-height: 1.6; color: var(--el-text-color-regular); }
.perf-drawer__empty { color: var(--el-text-color-secondary); font-size: 13px; }
.perf-launch__confirm { margin: 0 0 12px; font-size: 13px; color: var(--el-text-color-regular); }
.perf-launch__field { display: flex; align-items: center; gap: 8px; }
.perf-launch__field label { font-size: 13px; color: var(--el-text-color-secondary); }
.perf-launch__hint { margin: 8px 0 0; font-size: 12px; color: var(--el-text-color-secondary); }
/* CF-5/CF-7 pre-flight card: a background-tinted read-only notice inside the
   launch dialog (no modal, no side bar) — advance information only, it never
   disables the confirm button; rejection stays owned by the server gates.
   Spacing on the 16px rhythm: 16px from the block above, breathing room kept
   toward the footer; even 12/16 inner padding, title one step above body. */
.perf-preflight { margin: 16px 0 16px; padding: 12px 16px; border-radius: 8px; background: var(--el-color-info-light-9); border: 1px solid var(--el-color-info-light-7); }
.perf-preflight__title { margin: 0 0 6px; font-size: 13px; font-weight: 600; line-height: 1.6; color: var(--el-text-color-primary); }
.perf-preflight__title-note { margin-left: 4px; font-size: 12px; font-weight: 400; color: var(--el-text-color-secondary); }
.perf-preflight__engine { margin: 0; font-size: 12px; line-height: 1.6; color: var(--el-text-color-secondary); }
.perf-preflight__line { margin: 4px 0 0; font-size: 12px; line-height: 1.6; color: var(--el-text-color-regular); word-break: break-all; }
.perf-preflight__line--warn { color: var(--el-color-warning); }
.perf-drawer__count { margin: 12px 0 8px; padding-top: 8px; border-top: 1px solid var(--el-border-color-lighter); font-size: 12px; color: var(--el-text-color-secondary); }
.perf-run-row { border: 1px solid var(--el-border-color); border-radius: 8px; padding: 10px 12px; margin-bottom: 10px; cursor: pointer; }
.perf-run-row:hover { box-shadow: var(--el-box-shadow-light); }
.perf-run-row__main { display: flex; align-items: center; gap: 8px; }
.perf-run-row__id { font-weight: 600; font-size: 13px; }
.perf-run-row__meta { margin-top: 6px; font-size: 12px; color: var(--el-text-color-secondary); }
.perf-run-row__stop { margin: 6px 0 0; font-size: 12px; color: var(--el-color-warning); word-break: break-all; }
.perf-trend__body { display: flex; flex-direction: column; height: 100%; }
.perf-trend__empty { margin: auto; max-width: 480px; }
.perf-trend__empty :deep(.el-empty__description) { line-height: 1.6; }
/* display:block kills the inline-baseline gap under the svg so the legend
   spacing lands on the 4px rhythm grid (vision dim-3: 11/12px free-run). */
.perf-trend__svg { display: block; width: 100%; height: auto; }
.perf-trend__svg .axis { stroke: var(--el-border-color); stroke-width: 1; }
.perf-trend__svg .line-p50 { stroke: var(--el-color-success); stroke-width: 1.5; }
.perf-trend__svg .line-p95 { stroke: var(--el-color-primary); stroke-width: 2; }
.perf-trend__svg .line-p99 { stroke: var(--el-color-danger); stroke-width: 1.5; }
.perf-trend__svg .dot-golden { fill: #d4a017; stroke: var(--el-bg-color); stroke-width: 1; }
.perf-trend__legend { display: flex; align-items: center; gap: 6px; margin: 8px 0 12px; font-size: 12px; color: var(--el-text-color-secondary); flex-wrap: wrap; }
.perf-trend__swatch { display: inline-block; width: 14px; height: 3px; border-radius: 2px; }
.perf-trend__swatch.sw-p50 { background: var(--el-color-success); }
.perf-trend__swatch.sw-p95 { background: var(--el-color-primary); }
.perf-trend__swatch.sw-p99 { background: var(--el-color-danger); }
.perf-trend__swatch.dot-golden-swatch { width: 8px; height: 8px; border-radius: 50%; background: #d4a017; }
/* flex:1 (was a fixed 320px strip) so the row window fills the drawer body —
   worst-scale rows still scroll, the lower half no longer floats empty. */
.perf-trend__scroller { flex: 1; min-height: 200px; overflow-y: auto; overflow-anchor: none; border: 1px solid var(--el-border-color-lighter); border-radius: 8px; }
.perf-trend__row { display: flex; align-items: center; gap: 8px; height: 44px; padding: 0 12px; font-size: 12px; border-bottom: 1px solid var(--el-border-color-lighter); cursor: pointer; }
.perf-trend__row:hover { background: var(--el-fill-color-light); }
.perf-trend__runid { font-weight: 600; min-width: 44px; }
.perf-trend__time { color: var(--el-text-color-secondary); }
.perf-trend__stat { margin-left: auto; color: var(--el-text-color-regular); white-space: nowrap; }
.perf-trend__sep { flex: none; width: 1px; height: 14px; background: var(--el-border-color); }
.perf-trend__caliber { color: var(--el-text-color-secondary); white-space: nowrap; cursor: help; }
.perf-trend__foot { margin: 12px 0 0; font-size: 12px; color: var(--el-text-color-secondary); }
</style>
