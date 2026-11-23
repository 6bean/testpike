<script setup lang="ts">

import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { ArrowDown } from '@element-plus/icons-vue'
import { bizErrorMsg } from '@/utils/bizError'
import {
  buildPerfReport,
  comparePerfRuns,
  exportPerfReport,
  listPerfErrorTopN,
  listPerfRuns,
  requestPerfRunInsight,
  sendPerfReportCard,
  type PerfCompareView,
  type PerfErrorClassItem,
  type PerfReportView,
  type PerfRunInsightView,
  type PerfRunItem,
} from '@/api/perf'
import { CHART_H, CHART_W, buildPolySegments, fmtNum, fmtRate, fmtTime, maxOf, runStatusTag, scaleX, scaleY, targetVersionOf } from './chartUtils'

const props = defineProps<{
  projectId: number
  runId: number
  scenarioId: number
}>()

const emit = defineEmits<{
  (e: 'goto-run', id: number): void
}>()

const { t } = useI18n()

const report = ref<PerfReportView | null>(null)
const reportError = ref(false)
const sending = ref(false)

/** B-12 consume face: export the run report as pdf/html. One click = one
 *  authenticated blob download through the shared helper; the file name is
 *  server-composed (Content-Disposition) and refusal wording rides through
 *  untranslated — the browser keeps no second copy of either. In flight the
 *  door is loading + disabled (R22 double-click guard). */
const exporting = ref(false)

async function exportReport(format: 'pdf' | 'html') {
  if (exporting.value) return
  exporting.value = true
  try {
    await exportPerfReport(props.projectId, props.runId, format)
    ElMessage.success(t('perf.report.exportDone', { format }))
  } catch (e) {
    void bizErrorMsg(e, t('perf.opFail'))
  } finally {
    exporting.value = false
  }
}

/** B-11 AI run-insight read-out. One click = one model hop, so the
 * door carries the R22 double-click guard (loading+disabled in flight) and a
 * cost hint before first use. The reply rides through untranslated: a closed
 * switch, a foreign run or a pending run all display the backend wording —
 * the browser keeps no second copy of any refusal spec (red line 6). */
const insight = ref<PerfRunInsightView | null>(null)
const insightError = ref<string | null>(null)
const insighting = ref(false)

const candidates = ref<PerfRunItem[]>([])
const compareRunB = ref<number | null>(null)
const compare = ref<PerfCompareView | null>(null)
const compareLoading = ref(false)

/** U-8 layer 2 (bottleneck clues): the B-6 error-class TopN read face, same
 *  endpoint the events drawer consumes — the report only draws, never recomputes. */
const errorTopN = ref<PerfErrorClassItem[]>([])

/** U-8 layer 3: a pinned golden run of the same scenario, if any — one click
 *  compares against it without hunting through the dropdown. */
const goldenCandidate = computed(() => candidates.value.find((r) => r.mark === 'GOLDEN') ?? null)

async function loadReport() {
  report.value = null
  reportError.value = false
  compare.value = null
  compareRunB.value = null
  errorTopN.value = []
  insight.value = null
  insightError.value = null
  try {
    report.value = await buildPerfReport(props.projectId, props.runId)
  } catch (e) {
    reportError.value = true
    void bizErrorMsg(e, t('perf.report.loadFail'))
  }
  try {
    errorTopN.value = await listPerfErrorTopN(props.projectId, props.runId)
  } catch {
    errorTopN.value = []
  }
  try {
    const all = await listPerfRuns(props.projectId, props.scenarioId)
    candidates.value = all.filter((r) => r.status !== 1 && r.id !== props.runId)
  } catch {
    candidates.value = []
  }
}

watch(() => [props.projectId, props.runId, props.scenarioId] as const, () => void loadReport(), { immediate: true })


async function sendCard() {
  sending.value = true
  try {
    const n = await sendPerfReportCard(props.projectId, props.runId)
    ElMessage.success(t('perf.report.cardSent', { n }))
  } catch (e) {
    void bizErrorMsg(e, t('perf.opFail'))
  } finally {
    sending.value = false
  }
}

/** B-11 one click spends one model hop server-side; in flight the
 * door is loading+disabled (double-click ships exactly one request), and a
 * refusal (closed switch / pending / foreign run) renders the backend phrase
 * verbatim as an honest card band instead of an invented explanation. */
async function fetchInsight() {
  if (insighting.value) return
  insighting.value = true
  try {
    insight.value = await requestPerfRunInsight(props.projectId, props.runId)
    insightError.value = null
  } catch (e) {
    insight.value = null
    insightError.value = bizErrorMsg(e, t('perf.report.insightFail'))
  } finally {
    insighting.value = false
  }
}

async function runCompare() {
  if (!compareRunB.value) return
  compareLoading.value = true
  try {
    compare.value = await comparePerfRuns(props.projectId, props.runId, compareRunB.value)
  } catch (e) {
    compare.value = null
    void bizErrorMsg(e, t('perf.report.compareFail'))
  } finally {
    compareLoading.value = false
  }
}

/** U-8 layer 3: one click pins the golden run as B and compares — the baseline
 *  lane of the report (gold run is the approved reference of the scenario). */
function compareWithGolden() {
  const g = goldenCandidate.value
  if (!g) return
  compareRunB.value = g.id
  void runCompare()
}

/** U-8 layer 4: the caliber footnote is what people paste into review threads —
 *  copy it verbatim instead of selecting tiny gray text. */
async function copyCaliber() {
  const text = report.value?.caliberFootnote ?? ''
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(t('perf.report.caliberCopied'))
  } catch {
    void bizErrorMsg(null, t('perf.report.copyFail'))
  }
}

const verdictTag = computed(() => {
  const v = report.value?.verdict
  if (!v) return null
  return v.passed ? { label: t('perf.report.pass'), type: 'success' as const } : { label: t('perf.report.fail'), type: 'danger' as const }
})

const cmpMinSec = computed(() => {
  const s = compare.value?.series ?? []
  return s.length ? s[0].secondOffset : 0
})
const cmpMaxSec = computed(() => {
  const s = compare.value?.series ?? []
  return s.length ? s[s.length - 1].secondOffset : 1
})
const cmpSegs = computed(() => {
  const s = compare.value?.series ?? []
  if (!s.length) return { a: [] as string[], b: [] as string[] }
  const x = scaleX(cmpMinSec.value, cmpMaxSec.value)
  const y = scaleY(maxOf(s.flatMap((p) => [p.a, p.b])))
  return {
    a: buildPolySegments(s, (p) => x(p.secondOffset), (p) => p.a, y),
    b: buildPolySegments(s, (p) => x(p.secondOffset), (p) => p.b, y),
  }
})

function cmpRunTag(r: PerfRunItem | undefined) {
  return r ? runStatusTag(r.status) : null
}

/** GA-5 per-side pinned build for the compare block (upgrade-vs-baseline
 *  self-proof): reads the launch snapshot through the shared helper; an
 *  environment without a recorded version says so honestly. */
function cmpVersion(r: PerfRunItem | undefined): string {
  const v = r ? targetVersionOf(r.pressure_snapshot) : null
  return v ? t('perf.run.targetVersion', { version: v }) : t('perf.run.targetVersionMissing')
}

/** Ceiling table row tint: breach step red, last passing step (watermark base) green —
 *  mirrors the core finding's step indices, no second calculation here. */
function ceilingRowClass({ row }: { row: { step: number; passed: boolean } }) {
  const c = report.value?.ceiling
  if (!c) return ''
  if (c.breachStep === row.step) return 'ceiling-row-breach'
  if (c.lastPassStep === row.step && row.passed) return 'ceiling-row-lastpass'
  return ''
}
</script>

<template>
  <section class="perf-report" data-test="perf-report">
    <p v-if="reportError" class="perf-report__error" data-test="perf-report-error">{{ t('perf.report.loadFail') }}</p>

    <template v-if="report">
      <div class="perf-report__head">
        <h3 class="perf-report__title">{{ t('perf.report.title') }}</h3>
        <el-tag v-if="verdictTag" size="large" :type="verdictTag.type" data-test="perf-report-verdict">{{ verdictTag.label }}</el-tag>
        <el-button
          plain
          :loading="insighting"
          :disabled="insighting"
          data-test="perf-report-insight-btn"
          :title="t('perf.report.insightCostHint')"
          style="margin-left: auto"
          @click="fetchInsight"
        >
          {{ t('perf.report.insightGo') }}
        </el-button>
        <!-- B-12: report export (pdf/html) — split door. The primary button
             ships PDF, the Format menu carries the alternate. Verb + object
             wording, in-flight loading + disabled per the double-click rule. -->
        <el-button
          type="primary"
          plain
          :loading="exporting"
          :disabled="exporting"
          data-test="perf-report-export-pdf"
          :title="t('perf.report.exportHint')"
          @click="exportReport('pdf')"
        >
          {{ t('perf.report.exportGo') }}
        </el-button>
        <el-dropdown trigger="click" :disabled="exporting" data-test="perf-report-export-btn" @command="(fmt: 'pdf' | 'html') => exportReport(fmt)">
          <el-button plain :disabled="exporting" data-test="perf-report-export-format-toggle">
            {{ t('perf.report.exportFormat') }}<el-icon class="el-icon--right"><ArrowDown /></el-icon>
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="pdf" data-test="perf-report-export-pdf-item">{{ t('perf.report.exportPdf') }}</el-dropdown-item>
              <el-dropdown-item command="html" data-test="perf-report-export-html">{{ t('perf.report.exportHtml') }}</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-button
          type="primary"
          plain
          :loading="sending"
          data-test="perf-report-send-card"
          @click="sendCard"
        >
          {{ t('perf.report.sendCard') }}
        </el-button>
      </div>

      <p class="perf-report__conclusion" data-test="perf-report-conclusion">{{ report.verdict.conclusion }}</p>
      <p v-if="report.verdict.breachedGates.length" class="perf-report__gates">
        {{ t('perf.report.breachedGates') }}: {{ report.verdict.breachedGates.join(', ') }}
      </p>

      <!-- B-11 AI read-out (tinted band, never a dialog). Three honest
 shapes: refusal message verbatim, pending judge phrase (zero tokens),
 or the model read-out — each carries the judge verdict it annotates. -->
      <div
        v-if="insight || insightError"
        class="perf-report__insight"
        :class="{ 'perf-report__insight--refused': !!insightError }"
        data-test="perf-report-insight-card"
      >
        <h4 class="perf-report__subtitle">{{ t('perf.report.insightTitle') }}</h4>
        <p v-if="insightError" class="perf-report__insight-refuse" data-test="perf-report-insight-refuse">
          {{ insightError }}
        </p>
        <template v-else>
          <p v-if="insight && !insight.insight" class="perf-report__insight-pending" data-test="perf-report-insight-pending">
            {{ insight.verdictPhrase }}
          </p>
          <p v-else class="perf-report__insight-text" data-test="perf-report-insight-text">{{ insight?.insight }}</p>
        </template>
      </div>

      <!-- U-8 layer 2: bottleneck clues — error-class TopN straight from core (B-6),
           drawn here so a failed run names WHERE it hurt without opening the drawer. -->
      <div class="perf-report__clues" data-test="perf-report-clues">
        <h4 class="perf-report__subtitle">{{ t('perf.report.cluesTitle') }}</h4>
        <p v-if="!errorTopN.length" class="perf-report__hint" data-test="perf-report-clues-empty">
          {{ t('perf.report.cluesEmpty') }}
        </p>
        <ul v-else class="perf-report__clues-list">
          <li v-for="e in errorTopN" :key="e.errorClass" class="perf-report__clue-row" data-test="perf-report-clue-row">
            <el-tag size="small" type="danger">{{ t(`perf.run.errorClass.${e.errorClass}`, e.errorClass) }}</el-tag>
            <span class="perf-report__clue-count">{{ e.count }}</span>
          </li>
        </ul>
      </div>

      <el-descriptions :column="4" border data-test="perf-report-cards" class="perf-report__cards">
        <el-descriptions-item :label="t('perf.report.card.sampledSeconds')">{{ report.card.sampledSeconds }}</el-descriptions-item>
        <el-descriptions-item :label="t('perf.report.card.avgRps')">{{ fmtNum(report.card.avgRps) }}</el-descriptions-item>
        <el-descriptions-item :label="t('perf.report.card.maxRps')">{{ fmtNum(report.card.maxRps) }}</el-descriptions-item>
        <el-descriptions-item :label="t('perf.report.card.peakP95')">{{ fmtNum(report.card.peakSecondP95Ms, 0) }} ms</el-descriptions-item>
        <el-descriptions-item :label="t('perf.report.card.avgError')">{{ fmtRate(report.card.avgErrorRate) }}</el-descriptions-item>
        <!-- Auth trio: a zero is explained on hover, never left to look like a fault. -->
        <el-descriptions-item :label="t('perf.report.card.authSeconds')">
          <el-tooltip :disabled="report.card.authSeconds !== 0" :content="t('perf.report.authNotUsed')" placement="top">
            <span data-test="perf-report-auth-seconds">{{ report.card.authSeconds }}</span>
          </el-tooltip>
        </el-descriptions-item>
        <el-descriptions-item :label="t('perf.report.card.authCount')">
          <el-tooltip :disabled="report.card.authRequestCount !== 0" :content="t('perf.report.authNotUsed')" placement="top">
            <span data-test="perf-report-auth-count">{{ report.card.authRequestCount }}</span>
          </el-tooltip>
        </el-descriptions-item>
        <el-descriptions-item :label="t('perf.report.card.authAvg')">
          <el-tooltip :disabled="report.card.authAvgMs !== null" :content="t('perf.report.authNotUsed')" placement="top">
            <span data-test="perf-report-auth-avg">{{ fmtNum(report.card.authAvgMs, 0) }}</span>
          </el-tooltip>
        </el-descriptions-item>
      </el-descriptions>

      <div v-if="report.ceiling" class="perf-report__ceiling" data-test="perf-ceiling-card">
        <h4 class="perf-report__subtitle">{{ t('perf.report.ceiling.title') }}</h4>
        <p class="perf-report__conclusion" data-test="perf-ceiling-conclusion">{{ report.ceiling.conclusion }}</p>
        <p v-if="report.ceiling.kneeDescription" class="perf-report__knee" data-test="perf-ceiling-knee">
          {{ t('perf.report.ceiling.knee') }}：{{ report.ceiling.kneeDescription }}
        </p>
        <el-table
          v-if="report.ceiling.applicable && report.ceiling.tiers.length"
          border
          stripe
          size="small"
          max-height="260"
          :data="report.ceiling.tiers"
          :row-class-name="ceilingRowClass"
          data-test="perf-ceiling-table"
        >
          <el-table-column prop="step" :label="t('perf.report.ceiling.col.step')" width="70" />
          <el-table-column :label="t('perf.report.ceiling.col.nominalRps')" min-width="110">
            <template #default="{ row }">{{ fmtNum(row.nominalRps, 0) }}</template>
          </el-table-column>
          <el-table-column :label="t('perf.report.ceiling.col.verdict')" min-width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.passed ? 'success' : 'danger'">
                {{ row.passed ? t('perf.report.ceiling.pass') : t('perf.report.ceiling.fail') }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('perf.report.ceiling.col.gates')" min-width="140">
            <template #default="{ row }">{{ row.breachedGates.join('、') || '—' }}</template>
          </el-table-column>
          <el-table-column prop="requests" :label="t('perf.report.ceiling.col.requests')" min-width="100" />
        </el-table>
        <p class="perf-report__foot" data-test="perf-ceiling-caliber">{{ report.ceiling.caliberNote }}</p>
      </div>

      <!-- GB-4 : residual-cleanup guide. Every string is produced
 by core from the launch-pinned isolation declaration — the page only
 renders the structure (mode badge reuses the existing perf.isolation
 spectrum, section items verbatim). A null guide means the mode was
 never declared: the whole block stays absent, never an empty shell. -->
      <div v-if="report.residualGuide" class="perf-report__residual" data-test="perf-residual-guide">
        <div class="perf-report__residual-head">
          <h4 class="perf-report__subtitle">{{ t('perf.report.residualGuideTitle') }}</h4>
          <el-tag size="small" type="info" data-test="perf-residual-mode">
            {{ t(`perf.isolation.${report.residualGuide.mode}`) }}
          </el-tag>
        </div>
        <p class="perf-report__hint" data-test="perf-residual-intro">{{ t('perf.report.residualGuideIntro') }}</p>
        <div
          v-for="(sec, i) in report.residualGuide.sections"
          :key="i"
          class="perf-report__residual-section"
          data-test="perf-residual-section"
        >
          <p class="perf-report__residual-sec-title">{{ sec.title }}</p>
          <ol class="perf-report__residual-items">
            <li v-for="(item, j) in sec.items" :key="j">{{ item }}</li>
          </ol>
        </div>
        <p class="perf-report__foot" data-test="perf-residual-boundary">{{ report.residualGuide.boundaryNote }}</p>
      </div>

      <!-- U-8 layer 4: caliber footnote with a copy affordance (review threads quote it verbatim). -->
      <div class="perf-report__caliber" data-test="perf-report-caliber">
        <p class="perf-report__foot">{{ report.caliberFootnote }}</p>
        <el-button link type="primary" size="small" data-test="perf-report-copy-caliber" @click="copyCaliber">
          {{ t('perf.report.copy') }}
        </el-button>
      </div>
      <p v-if="report.driverLimit !== null" class="perf-report__foot">{{ t('perf.report.driverLimit', { n: report.driverLimit }) }}</p>
      <p v-if="report.boundaryNote" class="perf-report__foot" data-test="perf-report-boundary">{{ report.boundaryNote }}</p>
    </template>

    <div v-if="report" class="perf-report__compare" data-test="perf-compare">
      <h4 class="perf-report__subtitle">{{ t('perf.report.compareTitle') }}</h4>
      <p v-if="!candidates.length" class="perf-report__hint">{{ t('perf.report.compareEmpty') }}</p>
      <div v-else class="perf-report__compare-bar">
        <span class="cmp-label">A #{{ runId }}</span>
        <span class="cmp-vs">vs</span>
        <el-select v-model="compareRunB" filterable style="width: 280px" data-test="perf-compare-select" :placeholder="t('perf.report.comparePick')">
          <el-option
            v-for="c in candidates"
            :key="c.id"
            :value="c.id"
            :label="`#${c.id} · ${runStatusTag(c.status).label} · ${fmtTime(c.started_at)}`"
          />
        </el-select>
        <el-button type="primary" plain :disabled="!compareRunB" :loading="compareLoading" data-test="perf-compare-btn" @click="runCompare">
          {{ t('perf.report.compareGo') }}
        </el-button>
        <!-- U-8 layer 3: the scenario's golden run is the approved baseline — one click
             against it, no hunting through the list. Absent = honest absence. -->
        <el-button
          v-if="goldenCandidate"
          plain
          :loading="compareLoading"
          data-test="perf-compare-golden"
          @click="compareWithGolden"
        >
          {{ t('perf.report.compareGolden', { id: goldenCandidate.id }) }}
        </el-button>
      </div>

      <template v-if="compare">
        <div class="perf-report__cmp-head">
          <div>
            A #{{ compare.runA.id }}
            <el-tag size="small" :type="cmpRunTag(compare.runA)?.type">{{ cmpRunTag(compare.runA)?.label }}</el-tag>
            · {{ fmtTime(compare.runA.started_at) }}
            <span class="cmp-version" data-test="perf-compare-version-a">{{ cmpVersion(compare.runA) }}</span>
            <el-button link type="primary" size="small" data-test="perf-compare-goto-a" @click="emit('goto-run', compare.runA.id)">{{ t('perf.report.compareOpen') }}</el-button>
          </div>
          <div>
            B #{{ compare.runB.id }}
            <el-tag size="small" :type="cmpRunTag(compare.runB)?.type">{{ cmpRunTag(compare.runB)?.label }}</el-tag>
            · {{ fmtTime(compare.runB.started_at) }}
            <span class="cmp-version" data-test="perf-compare-version-b">{{ cmpVersion(compare.runB) }}</span>
            <el-button link type="primary" size="small" data-test="perf-compare-goto-b" @click="emit('goto-run', compare.runB.id)">{{ t('perf.report.compareOpen') }}</el-button>
          </div>
        </div>
        <svg :viewBox="`0 0 ${CHART_W} ${CHART_H}`" class="perf-report__cmp-svg" role="img" :aria-label="t('perf.report.compareTitle')">
          <line :x1="CHART_W - 10" y1="10" :x2="CHART_W - 10" :y2="CHART_H - 10" class="axis" />
          <line x1="10" :y1="CHART_H - 10" :x2="CHART_W - 10" :y2="CHART_H - 10" class="axis" />
          <polyline v-for="(s, i) in cmpSegs.a" :key="`a${i}`" :points="s" class="line-a" fill="none" />
          <polyline v-for="(s, i) in cmpSegs.b" :key="`b${i}`" :points="s" class="line-b" fill="none" />
        </svg>
        <div class="perf-report__legend">
          <span class="lg lg-a">{{ t('perf.report.legendA', { id: compare.runA.id }) }}</span>
          <span class="lg lg-b">{{ t('perf.report.legendB', { id: compare.runB.id }) }}</span>
          <span class="lg lg-note">{{ t('perf.report.seriesNote') }}</span>
        </div>
        <el-table border :data="compare.diffs" size="small" max-height="280" data-test="perf-compare-table" stripe>
          <el-table-column prop="secondOffset" :label="t('perf.report.col.second')" width="90" />
          <el-table-column :label="t('perf.report.col.rpsDelta')" min-width="110">
            <template #default="{ row }">{{ fmtNum(row.rpsDelta) }}</template>
          </el-table-column>
          <el-table-column :label="t('perf.report.col.p95Delta')" min-width="110">
            <template #default="{ row }">{{ fmtNum(row.p95DeltaMs, 0) }}</template>
          </el-table-column>
          <el-table-column :label="t('perf.report.col.errDelta')" min-width="110">
            <template #default="{ row }">{{ fmtRate(row.errorRateDelta) }}</template>
          </el-table-column>
        </el-table>
      </template>
    </div>
  </section>
</template>

<style scoped>
.perf-report { border: 1px solid var(--el-border-color); border-radius: 8px; padding: 12px 16px; }
.perf-report__head { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; margin-bottom: 8px; }
.perf-report__title { margin: 0; font-size: 16px; }
.perf-report__subtitle { margin: 16px 0 8px; font-size: 14px; }
.perf-report__conclusion { margin: 4px 0 8px; font-size: 13px; }
.perf-report__gates { margin: 0 0 8px; font-size: 12px; color: var(--el-color-danger); }
/* B-11 AI read-out band — tinted by a theme token (both shells), never
 a dialog; a refusal keeps the same band shape with the danger tint so the
 honest "why not" is as visible as the answer. */
.perf-report__insight { background: var(--el-color-primary-light-9); border-radius: 6px; padding: 8px 12px; margin: 0 0 8px; }
.perf-report__insight--refused { background: var(--el-color-danger-light-9); }
/* 4px-grid single source: band title -> body gap aligned to the report's
 8px block rhythm (was 6px — off-grid magic number caught by the
 vision close-out). */
.perf-report__insight .perf-report__subtitle { margin: 0 0 8px; }
.perf-report__insight-text { margin: 0; font-size: 13px; white-space: pre-wrap; word-break: break-word; }
.perf-report__insight-pending { margin: 0; font-size: 13px; color: var(--el-text-color-secondary); }
.perf-report__insight-refuse { margin: 0; font-size: 13px; color: var(--el-color-danger); }
.perf-report__cards { margin-bottom: 8px; }
.perf-report__foot { margin: 4px 0; font-size: 12px; color: var(--el-text-color-secondary); word-break: break-all; }
.perf-report__ceiling { margin: 0 0 8px; }
/* GB-4 residual guide block: same rhythm as the ceiling card (4px grid). */
.perf-report__residual { margin: 0 0 8px; }
.perf-report__residual-head { display: flex; align-items: center; justify-content: space-between; margin: 16px 0 8px; }
.perf-report__residual-head .perf-report__subtitle { margin: 0; }
.perf-report__residual .perf-report__foot { margin-top: 12px; }
.perf-report__residual-section { margin: 8px 0 0; }
.perf-report__residual-sec-title { margin: 0; font-size: 13px; font-weight: 600; }
.perf-report__residual-items { margin: 4px 0 0; padding-left: 20px; font-size: 13px; }
.perf-report__residual-items li { margin: 4px 0; }
/* Ladder tier table -> caliber footnote band gap: vision audit measured 4px (visual
 collision, ); theme spacing grid is 4px, 12px keeps a readable separation. */
.perf-report__ceiling .perf-report__foot { margin-top: 12px; }
.perf-report__knee { margin: 4px 0 8px; font-size: 12px; color: var(--el-color-warning); }
.perf-report__ceiling :deep(.ceiling-row-breach) { --el-table-tr-bg-color: var(--el-color-danger-light-9); }
.perf-report__ceiling :deep(.ceiling-row-lastpass) { --el-table-tr-bg-color: var(--el-color-success-light-9); }
.perf-report__error { color: var(--el-color-danger); font-size: 13px; }
.perf-report__hint { color: var(--el-text-color-secondary); font-size: 13px; }
/* U-8 layer 2: bottleneck clue rows sit inline, tight to their section title. */
.perf-report__clues { margin: 0 0 8px; }
.perf-report__clues-list { list-style: none; margin: 0; padding: 0; display: flex; gap: 16px; flex-wrap: wrap; }
.perf-report__clue-row { display: flex; align-items: center; gap: 6px; font-size: 13px; }
.perf-report__clue-count { color: var(--el-text-color-secondary); }
/* U-8 layer 4: footnote text and its copy button share one line band. */
.perf-report__caliber { display: flex; align-items: baseline; gap: 8px; }
.perf-report__caliber .perf-report__foot { margin: 4px 0; }
.perf-report__compare-bar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.perf-report__compare-bar .cmp-label { font-weight: 600; font-size: 13px; }
.perf-report__compare-bar .cmp-vs { color: var(--el-text-color-secondary); font-size: 12px; }
.perf-report__cmp-head { display: flex; gap: 24px; font-size: 12px; color: var(--el-text-color-secondary); margin: 8px 0; flex-wrap: wrap; }
.perf-report__cmp-svg { width: 100%; height: auto; display: block; }
.perf-report__cmp-svg .axis { stroke: var(--el-border-color); stroke-width: 1; }
.perf-report__cmp-svg .line-a { stroke: var(--el-color-primary); stroke-width: 2; }
.perf-report__cmp-svg .line-b { stroke: var(--el-color-success); stroke-width: 2; stroke-dasharray: 5 3; }
.perf-report__legend { display: flex; gap: 16px; margin-top: 8px; font-size: 12px; color: var(--el-text-color-secondary); flex-wrap: wrap; }
.perf-report__legend .lg::before { content: ''; display: inline-block; width: 14px; height: 3px; margin-right: 4px; vertical-align: middle; }
.perf-report__legend .lg-a::before { background: var(--el-color-primary); }
.perf-report__legend .lg-b::before { background: repeating-linear-gradient(90deg, var(--el-color-success) 0 5px, transparent 5px 8px); }
/* The caliber note is plain text, not a series entry — no phantom swatch slot. */
.perf-report__legend .lg-note::before { display: none; }
</style>
