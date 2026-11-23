<script setup lang="ts">
/**
 * Polling contract = every 2s refresh findPerfRun + listPerfLiveCurve + listPerfEvents (the
 * live-curve leg now carries an incremental cursor, GA-3: only seconds after the cursor
 * are shipped, oversized windows arrive server-side bucketed with an honest sampled flag;
 * the report page keeps the full caliber). Metric caliber/verdict/breaker authority stays
 * in core — this page only draws and reads directly.
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import { ElMessage, ElMessageBox } from 'element-plus'
import EmptyState from '@/components/EmptyState.vue'
import PerfReportView from './PerfReportView.vue'
import { bizErrorMsg } from '@/utils/bizError'
import {
  findPerfRun,
  launchPerfRun,
  listPerfErrorTopN,
  listPerfEvents,
  listPerfLiveCurve,
  stopPerfRun,
  type PerfErrorClassItem,
  type PerfEventItem,
  type PerfFrameItem,
  type PerfRunItem,
} from '@/api/perf'
import {
  CHART_H,
  CHART_W,
  buildPolySegments,
  canaryOf,
  flatSegment,
  fmtRate,
  fmtTime,
  maxOf,
  parseSnapshot,
  runStatusTag,
  scaleX,
  scaleY,
  targetVersionOf,
  tlsTierOf,
} from './chartUtils'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()

const runId = computed(() => Number(route.params.id))
// R30: deep-link ?projectId wins; when absent the header global context lands
// (wiring piece also mirrors the id back into the query for reload/share).
const { projectId } = useProjectPageContext()

const run = ref<PerfRunItem | null>(null)
const frames = ref<PerfFrameItem[]>([])
const events = ref<PerfEventItem[]>([])
const errorTopN = ref<PerfErrorClassItem[]>([])
const loading = ref(false)
const notFound = ref(false)
const eventDrawerOpen = ref(false)

let pollTimer: ReturnType<typeof setInterval> | null = null

const statusTag = computed(() => (run.value ? runStatusTag(run.value.status) : null))
const isRunning = computed(() => run.value?.status === 1)

/** GA-5 pinned target version of this run (null = the environment carried no
 *  recorded build at launch — an honest "not recorded", never a blank or guess). */
const targetVersion = computed(() => targetVersionOf(run.value?.pressure_snapshot))

/** CF-6 canary pre-flight pinned in the launch snapshot (null = the switch was
 *  off at launch — the header line stays absent, zero behaviour drift). */
const canary = computed(() => canaryOf(run.value?.pressure_snapshot))

/** B-10 pinned TLS verification tier : the report face states
 * which tier produced these numbers; null = a legacy run predating the tier
 * pin — an honest "not recorded", never a fabricated "strict". */
const tlsTier = computed(() => tlsTierOf(run.value?.pressure_snapshot))


const breakerRate = computed<number | null>(() => {
  const snap = parseSnapshot(run.value?.pressure_snapshot)
  const sla = snap?.sla
  if (!sla || typeof sla !== 'object') return null
  const v = (sla as Record<string, unknown>).breakerErrorRate
  return typeof v === 'number' ? v : null
})


const breakerHit = computed(() => {
  if (breakerRate.value === null || !frames.value.length) return false
  const last = frames.value[frames.value.length - 1]
  return last.error_rate !== null && last.error_rate >= breakerRate.value
})

const minSec = computed(() => (frames.value.length ? frames.value[0].second_offset : 0))
const maxSec = computed(() => (frames.value.length ? frames.value[frames.value.length - 1].second_offset : 1))
const errMax = computed(() => Math.max(maxOf(frames.value.map((f) => f.error_rate)), breakerRate.value ?? 0))

const xOf = computed(() => scaleX(minSec.value, maxSec.value))
const rpsSegs = computed(() => {
  const y = scaleY(maxOf(frames.value.map((f) => f.rps)))
  return buildPolySegments(frames.value, (f) => xOf.value(f.second_offset), (f) => f.rps, y)
})
const p95Segs = computed(() => {
  const y = scaleY(maxOf(frames.value.map((f) => f.p95_ms)))
  return buildPolySegments(frames.value, (f) => xOf.value(f.second_offset), (f) => f.p95_ms, y)
})

const errSegs = computed(() => {
  const y = scaleY(errMax.value)
  return buildPolySegments(frames.value, (f) => xOf.value(f.second_offset), (f) => f.error_rate, y)
})
const breakerY = computed<number | null>(() => {
  if (breakerRate.value === null || !frames.value.length) return null
  return scaleY(errMax.value)(breakerRate.value)
})

/** Cursor = deepest second the page already saw ;
 * sampled/bucket/maxPoints = server-echoed state of the applied downsample. */
const curveCursor = ref<number | null>(null)
const curveSampled = ref(false)
const curveBucketSeconds = ref(1)
const curveMaxPoints = ref(0)
const curveCollectedTotal = ref(0)
const collectedShown = computed(() => curveCollectedTotal.value)

/** U-6 tail-loss truth: core's A-9 coverage guard stores a `data-loss` event when the
 *  pressed span did not all reach the curve. Surfaced on the page body (not only the
 *  drawer) so a truncated tail can never impersonate a complete run. */
const dataLossEvent = computed(() => events.value.find((e) => e.event_kind === 'data-loss') ?? null)

async function refreshOnce(): Promise<PerfRunItem | null> {
  if (!projectId.value || !Number.isFinite(runId.value)) return null
  try {
    const [r, ev, tn] = await Promise.all([
      findPerfRun(projectId.value, runId.value),
      listPerfEvents(projectId.value, runId.value),
      listPerfErrorTopN(projectId.value, runId.value),
    ])
    // Curve leg: append-only past the cursor. Once the appended raw tail outgrows the
    // server's point ceiling (or the window arrives bucketed), resync from scratch so
    // the screen always shows the downsampled full shape instead of an unbounded DOM.
    // The leg re-checks the finite id: a route switch that clears :id mid-await must
    // never fire a /runs/NaN request (trend-row jump race, browser-reproved).
    if (!Number.isFinite(runId.value)) return r
    const resync = curveSampled.value || frames.value.length >= curveMaxPoints.value
    const view = await listPerfLiveCurve(
      projectId.value, runId.value, resync ? null : curveCursor.value)
    if (resync || view.sampled) {
      frames.value = view.frames
    } else {
      const seen = new Set(frames.value.map((f) => f.second_offset))
      frames.value = [...frames.value, ...view.frames.filter((f) => !seen.has(f.second_offset))]
        .sort((a, b) => a.second_offset - b.second_offset)
    }
    curveCursor.value = view.lastOffset ?? curveCursor.value
    curveSampled.value = view.sampled
    curveBucketSeconds.value = view.bucketSeconds
    curveMaxPoints.value = view.maxPoints
    curveCollectedTotal.value = view.collectedTotal
    run.value = r
    events.value = ev
    errorTopN.value = tn
    if (r.status !== 1) stopPolling()
    return r
  } catch (e) {
    notFound.value = true
    stopPolling()
    void bizErrorMsg(e, t('perf.run.loadFail'))
    return null
  }
}

function startPolling() {
  stopPolling()
  pollTimer = setInterval(() => void refreshOnce(), 2000)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

async function loadAll() {
  if (!projectId.value) return
  notFound.value = false
  loading.value = true
  run.value = null
  frames.value = []
  curveCursor.value = null
  curveSampled.value = false
  curveBucketSeconds.value = 1
  events.value = []
  errorTopN.value = []
  try {
    const r = await refreshOnce()
    if (r && r.status === 1) startPolling()
  } finally {
    loading.value = false
  }
}

/** U-7 three-stage stop (F-group discipline):
 *  stage 1 = confirm names the object and the consequence (run id + collected seconds,
 *  curve freezes, stored data kept); stage 2 = loading+disabled while in flight with a
 *  re-entrant guard (double-click ships exactly one request); stage 3 = success toast
 *  receipts with the run id and the stopped-task count. Kill-switch authority stays
 *  server-side — this page only dispatches and echoes. */
const stopPending = ref(false)

async function stopRun() {
  if (stopPending.value || !run.value) return
  try {
    await ElMessageBox.confirm(
      t('perf.run.stopCost', { id: run.value.id, collected: collectedShown.value }),
      t('perf.run.stopTitle'),
      { type: 'warning', confirmButtonText: t('perf.run.stop'), cancelButtonText: t('common.cancel') },
    )
  } catch {
    return
  }
  stopPending.value = true
  try {
    const n = await stopPerfRun(projectId.value!, run.value.id)
    ElMessage.success(t('perf.run.stopOk', { id: run.value.id, n }))
    void refreshOnce()
  } catch (e) {
    void bizErrorMsg(e, t('perf.opFail'))
  } finally {
    stopPending.value = false
  }
}


async function rerun() {
  if (!run.value) return
  try {
    const newRunId = await launchPerfRun(projectId.value!, run.value.scenario_id)
    ElMessage.success(t('perf.launchOk'))
    router.replace({ path: `/perf/run/${newRunId}`, query: { projectId: String(projectId.value) } })
  } catch (e) {
    void bizErrorMsg(e, t('perf.opFail'))
  }
}


function gotoRun(id: number) {
  router.push({ path: `/perf/run/${id}`, query: { projectId: String(projectId.value) } })
}

watch(runId, () => {
  if (projectId.value) void loadAll()
})

onMounted(() => {
  if (projectId.value) void loadAll()
})

onBeforeUnmount(stopPolling)
</script>

<template>
  <div class="perf-run" data-test="perf-run-page">
    <div class="perf-run__header">
      <div>
        <h2 class="perf-run__title">
          {{ t('perf.run.title') }} <span v-if="run">#{{ run.id }}</span>
          <el-tag v-if="statusTag" size="small" :type="statusTag.type" data-test="perf-run-status">{{ statusTag.label }}</el-tag>
        </h2>
        <p class="perf-run__meta">
          {{ t('perf.run.started') }} {{ fmtTime(run?.started_at) }} → {{ fmtTime(run?.finished_at) }}
          <span v-if="run?.stop_reason"> · {{ run.stop_reason }}</span>
        </p>
        <p class="perf-run__version" data-test="perf-run-target-version">
          {{ targetVersion ? t('perf.run.targetVersion', { version: targetVersion }) : t('perf.run.targetVersionMissing') }}
        </p>
        <!-- B-10 report tier line : echoes the TLS verification
 tier pinned at launch; insecure keeps a standing yellow hint (the
 W1a exec-result yellow-hint precedent). Missing tier = an honest
 "not recorded" — never a fabricated strict. -->
        <p class="perf-run__tls" data-test="perf-run-tls-tier">
          {{ tlsTier === 'strict' ? t('perf.run.tlsTierStrict')
            : tlsTier === 'custom_ca' ? t('perf.run.tlsTierCustomCa')
            : tlsTier === 'insecure' ? t('perf.run.tlsTierInsecure')
            : t('perf.run.tlsTierMissing') }}
        </p>
        <el-alert
          v-if="tlsTier === 'insecure'"
          type="warning"
          :closable="false"
          show-icon
          class="perf-run__tls-alert"
          data-test="perf-run-tls-insecure-hint"
          :title="t('perf.run.tlsTierInsecureHint')"
        />
        <p v-if="canary" class="perf-run__canary" data-test="perf-run-canary">
          {{ t('perf.run.canarySummary', { answered: canary.answered, probes: canary.probes, p95: canary.baselineP95Ms, engine: canary.engine ?? '—' })
          }}<template v-if="canary.prewarmed"> · {{ t('perf.run.canaryPrewarmed') }}</template>
        </p>
        <p v-if="run?.queue_note" class="perf-run__queue" data-test="perf-run-queue-note">{{ run.queue_note }}</p>
      </div>
      <div class="perf-run__ops">
        <el-button data-test="perf-run-events-btn" :disabled="!run" @click="eventDrawerOpen = true">
          {{ t('perf.run.events') }}
        </el-button>
        <el-button v-if="isRunning" type="danger" data-test="perf-run-stop-btn"
          :loading="stopPending" :disabled="!run || stopPending" @click="stopRun">
          {{ t('perf.run.stop') }}
        </el-button>
        <el-button v-else-if="run" data-test="perf-run-rerun-btn" @click="rerun">
          {{ t('perf.run.rerun') }}
        </el-button>
        <el-button link @click="router.push('/perf')">{{ t('perf.run.back') }}</el-button>
      </div>
    </div>

    <el-skeleton v-if="loading" :rows="5" animated />
    <EmptyState
      v-else-if="!projectId"
      icon="Odometer"
      :title="t('perf.noProject')"
      :hint="t('perf.noProjectHint')"
    />
    <EmptyState
      v-else-if="notFound"
      icon="Odometer"
      :title="t('perf.run.notFound')"
      :hint="t('perf.run.notFoundHint')"
    />

    <template v-else-if="run">
      <el-alert
        v-if="breakerHit"
        type="error"
        :closable="false"
        show-icon
        data-test="perf-run-breaker-alert"
        :title="t('perf.run.breakerHit', { rate: fmtRate(breakerRate) })"
        class="perf-run__alert"
      />

      <el-alert
        v-if="dataLossEvent"
        type="warning"
        :closable="false"
        show-icon
        data-test="perf-run-data-loss"
        class="perf-run__alert"
        :title="t('perf.run.dataLossTitle')"
        :description="dataLossEvent.detail"
      />

      <section class="perf-run__chart-card" data-test="perf-run-chart">
        <h3 class="perf-run__section">{{ t('perf.run.liveChart') }}</h3>
        <p v-if="curveSampled" class="perf-run__chart-sampled" data-test="perf-run-curve-sampled">
          {{ t('perf.run.curveSampled', { bucket: curveBucketSeconds, total: collectedShown }) }}
        </p>
        <p v-else-if="!isRunning && run && frames.length" class="perf-run__chart-frozen" data-test="perf-run-curve-frozen">
          {{ t('perf.run.curveFrozen', { total: collectedShown, last: maxSec }) }}
        </p>
        <p v-if="!frames.length" class="perf-run__chart-empty">{{ t('perf.run.chartEmpty') }}</p>
        <template v-else>
          <svg :viewBox="`0 0 ${CHART_W} ${CHART_H}`" class="perf-run__svg" role="img" :aria-label="t('perf.run.liveChart')">
            <line :x1="CHART_W - 10" y1="10" :x2="CHART_W - 10" :y2="CHART_H - 10" class="axis" />
            <line x1="10" :y1="CHART_H - 10" :x2="CHART_W - 10" :y2="CHART_H - 10" class="axis" />
            <polyline v-for="(s, i) in rpsSegs" :key="`r${i}`" :points="s" class="line-rps" fill="none" />
            <polyline v-for="(s, i) in p95Segs" :key="`p${i}`" :points="s" class="line-p95" fill="none" />
            <polyline v-for="(s, i) in errSegs" :key="`e${i}`" :points="s" class="line-err" fill="none" />
            <polyline v-if="breakerY !== null" :points="flatSegment(breakerY)" class="line-breaker" fill="none" />
          </svg>
          <div class="perf-run__legend">
            <span class="lg lg-rps">{{ t('perf.run.legendRps') }}</span>
            <span class="lg lg-p95">{{ t('perf.run.legendP95') }}</span>
            <span class="lg lg-err">{{ t('perf.run.legendErr') }}</span>
            <span v-if="breakerRate !== null" class="lg lg-breaker">
              {{ t('perf.run.legendBreaker', { rate: fmtRate(breakerRate) }) }}
            </span>
            <span class="lg lg-note">{{ t('perf.run.legendNote') }}</span>
          </div>
        </template>
      </section>

      <PerfReportView
        v-if="!isRunning"
        :project-id="projectId!"
        :run-id="run.id"
        :scenario-id="run.scenario_id"
        @goto-run="gotoRun"
      />
    </template>

    <el-drawer v-model="eventDrawerOpen" size="480px" :title="t('perf.run.eventsTitle')" data-test="perf-run-events-drawer">
      <section class="perf-run__topn" data-test="perf-error-topn">
        <h4 class="perf-run__topn-title">{{ t('perf.run.errorTopNTitle') }}</h4>
        <p v-if="!errorTopN.length" class="perf-run__drawer-empty">{{ t('perf.run.errorTopNEmpty') }}</p>
        <ul v-else class="perf-topn-list">
          <li v-for="e in errorTopN" :key="e.errorClass" class="perf-topn-row" data-test="perf-error-topn-row">
            <el-tag size="small" type="danger">{{ t(`perf.run.errorClass.${e.errorClass}`, e.errorClass) }}</el-tag>
            <span class="perf-topn-count">{{ e.count }}</span>
          </li>
        </ul>
      </section>
      <el-divider />
      <p v-if="!events.length" class="perf-run__drawer-empty">{{ t('perf.run.eventsEmpty') }}</p>
      <div v-for="(e, i) in events" :key="i" class="perf-event" data-test="perf-event-row">
        <span class="perf-event__sec">{{ e.second_offset !== null ? `+${e.second_offset}s` : '—' }}</span>
        <el-tag size="small" type="warning">{{ e.event_kind }}</el-tag>
        <p v-if="e.detail" class="perf-event__detail">{{ e.detail }}</p>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.perf-run { padding: 16px; }
.perf-run__header { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; margin-bottom: 12px; }
.perf-run__title { margin: 0 0 4px; font-size: 18px; display: flex; align-items: center; gap: 8px; }
.perf-run__meta { margin: 0; color: var(--el-text-color-secondary); font-size: 13px; }
/* GA-5 pinned build line: same quiet secondary tone, its own class so the run-time
   meta locator stays unambiguous for tests and screen readers. */
.perf-run__version { margin: 2px 0 0; color: var(--el-text-color-secondary); font-size: 13px; }
.perf-run__tls { margin: 2px 0 0; color: var(--el-text-color-secondary); font-size: 13px; }
.perf-run__tls-alert { margin: 6px 0 0; max-width: 640px; }
.perf-run__canary { margin: 2px 0 0; color: var(--el-text-color-secondary); font-size: 13px; }
.perf-run__queue { margin: 4px 0 0; color: var(--el-color-warning); font-size: 13px; }
.perf-run__ops { display: flex; gap: 8px; align-items: center; }
.perf-run__alert { margin-bottom: 16px; }
.perf-run__chart-card { border: 1px solid var(--el-border-color); border-radius: 8px; padding: 12px 16px; margin-bottom: 16px; }
.perf-run__section { margin: 0 0 8px; font-size: 16px; }
.perf-run__chart-empty { color: var(--el-text-color-secondary); font-size: 13px; }
.perf-run__chart-frozen { margin: 0 0 8px; color: var(--el-text-color-secondary); font-size: 13px; }
.perf-run__svg { width: 100%; height: auto; display: block; }
.perf-run__svg .axis { stroke: var(--el-border-color); stroke-width: 1; }
.perf-run__svg .line-rps { stroke: var(--el-color-primary); stroke-width: 2; }
.perf-run__svg .line-p95 { stroke: var(--el-color-warning); stroke-width: 2; }
.perf-run__svg .line-err { stroke: var(--el-color-danger); stroke-width: 2; }
.perf-run__svg .line-breaker { stroke: var(--el-color-danger); stroke-width: 1.5; stroke-dasharray: 6 4; opacity: 0.7; }
.perf-run__legend { display: flex; gap: 16px; margin-top: 8px; font-size: 12px; color: var(--el-text-color-secondary); flex-wrap: wrap; }
.perf-run__legend .lg::before { content: ''; display: inline-block; width: 14px; height: 3px; margin-right: 4px; vertical-align: middle; }
.perf-run__legend .lg-rps::before { background: var(--el-color-primary); }
.perf-run__legend .lg-p95::before { background: var(--el-color-warning); }
.perf-run__legend .lg-err::before { background: var(--el-color-danger); }
.perf-run__legend .lg-breaker::before { background: repeating-linear-gradient(90deg, var(--el-color-danger) 0 4px, transparent 4px 7px); }
/* The caliber note is plain text, not a series entry — no phantom swatch slot. */
.perf-run__legend .lg-note::before { display: none; }
.perf-run__drawer-empty { color: var(--el-text-color-secondary); font-size: 13px; }
.perf-run__topn-title { margin: 0 0 8px; font-size: 14px; color: var(--el-text-color-primary); }
.perf-topn-list { list-style: none; margin: 0; padding: 0; }
.perf-topn-row { display: flex; justify-content: space-between; align-items: center; padding: 6px 0; }
.perf-topn-count { font-variant-numeric: tabular-nums; font-weight: 600; }
.perf-event { border-bottom: 1px solid var(--el-border-color-lighter); padding: 8px 0; }
.perf-event__sec { font-size: 12px; color: var(--el-text-color-secondary); margin-right: 8px; }
.perf-event__detail { margin: 6px 0 0; font-size: 13px; word-break: break-all; }
</style>
