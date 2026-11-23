<!-- tp-subtitle-exempt: the subtitle is a live data chip (rendered card
 count), an echo of the dataset size, not a restatement of the page name.
 Kept by the stage-3 exemption ledger, item 3.
 tp-column-config-exempt (N-3): this page is a report detail view scoped to
 one judge run pair — the comparison table is five mutually-dependent columns
 (sample / score A / score B / delta / verdict) where hiding any side breaks
 the diff story, and the trend table is a bounded per-target run history.
 A page-level settings control could not honestly scope across the four
 report tabs (R25 mode 2, honest absence, declared here). -->

<template>
  <div class="page-root" data-test="judge-report-page">
    <div class="page-card">
      <div class="toolbar">
        <div class="toolbar-right">
          <el-button :icon="QuestionFilled" data-test="judge-help-btn" @click="openHelp('Y')">
            {{ t('judge.helpBtn') }}
          </el-button>
        </div>
      </div>

      
      <div class="filter-bar">
        <el-input
          v-model.number="runIdInput"
          :placeholder="t('judge.runIdPlaceholder')"
          style="width: 200px"
          data-test="judge-runid-input"
          @keyup.enter="loadRun"
        />
        <el-button type="primary" data-test="judge-load-btn" @click="loadRun">
          <el-icon><Search /></el-icon>{{ t('judge.loadBtn') }}
        </el-button>
        <el-button data-test="judge-cost-btn" :disabled="!scorecard" @click="loadCost">
          {{ t('judge.reloadBtn') }}
        </el-button>
      </div>

      <TableSkeleton v-if="loading" :cols="4" data-test="judgereportview-skeleton" />

      <template v-else-if="scorecard">
        
        <div class="judge-summary">
          <div class="judge-bigscore">
            <div class="judge-bigscore-num" data-test="judge-avg-score">{{ scorecard.avgFinalScore ?? '—' }}</div>
            <div class="judge-bigscore-label">{{ t('judge.avgScoreLabel') }}</div>
            <div class="judge-bigsize-meta" data-test="judge-summary-meta">
              {{ t('judge.summaryLine', {
                rubric: `${scorecard.rubricName} v${scorecard.rubricVersion ?? '?'}`,
                set: `${scorecard.evalsetName} v${scorecard.evalsetVersion ?? '?'}`,
                scored: scorecard.scored ?? 0,
                total: scorecard.sampleTotal ?? 0,
                pass: scorecard.passCount ?? 0,
                rate: pct(scorecard.passRate),
                passLine: scorecard.passScore ?? '—',
              }) }}
            </div>
          </div>
          <div ref="radarEl" class="judge-radar" data-test="judge-radar" />
        </div>

        <el-tabs v-model="activeTab" data-test="judge-tabs">
          
          <el-tab-pane :label="t('judge.tabCards')" name="cards">
            <div class="judge-cards-toolbar">
              <el-checkbox v-model="lowOnly" data-test="judge-low-only">{{ t('judge.lowOnly') }}</el-checkbox>
              <span class="page-subtitle">{{ t('judge.cardsTotal', { n: cardsTotal }) }}</span>
            </div>
            <div data-test="judge-card-list">
              <div v-for="(c, ci) in cards" :key="c.recordId ?? c.sampleId ?? `idx-${ci}`" class="judge-card">
                <div class="judge-card-head">
                  <span class="judge-card-score" :class="cardScoreClass(c)">{{ c.authoritativeScore ?? '—' }}</span>
                  <el-tag size="small" :type="c.ruleScore != null ? 'info' : 'warning'" data-test="judge-card-source">
                    {{ c.sampleSource || t('judge.sourceUnknown') }}
                  </el-tag>
                  <el-tag v-if="c.reviewed" size="small" type="success" data-test="judge-card-reviewed">
                    {{ t('judge.reviewedTag') }}
                  </el-tag>
                </div>
                <div class="judge-card-body">
                  <div class="judge-card-line"><b>{{ t('judge.inputLabel') }}</b>{{ c.inputPreview || '—' }}</div>
                  <div v-if="c.expectedPreview" class="judge-card-line"><b>{{ t('judge.expectedLabel') }}</b>{{ c.expectedPreview }}</div>
                  <div class="judge-card-line">
                    <b>{{ t('judge.machineLabel') }}</b>{{ c.machineFinalScore ?? '—' }}
                    <template v-if="c.manualFinalScore != null"> / {{ t('judge.manualLabel') }}{{ c.manualFinalScore }}</template>
                  </div>
                  <div v-if="c.ruleHits && c.ruleHits.length" class="judge-card-line">
                    <b>{{ t('judge.ruleHitsLabel') }}</b><span class="judge-rule-hit">{{ c.ruleHits.join('；') }}</span>
                  </div>
                  <div v-if="reasonLines(c).length" class="judge-card-reasons">
                    <div v-for="rl in reasonLines(c)" :key="rl.dim" class="judge-card-line">
                      <b>{{ rl.dim }}</b>{{ rl.text }}
                    </div>
                  </div>
                </div>
              </div>
            </div>
            <EmptyState v-if="cards.length === 0" :title="t('judge.cardsEmpty')" :hint="t('judge.cardsEmptyHint')" />
            <div class="pagination-bar">
              <el-pagination
                v-if="cardsTotal > 0"
                v-model:current-page="cardsPage"
                v-model:page-size="cardsSize"
                :total="cardsTotal"
                :page-sizes="[20, 50, 100]"
                layout="total, sizes, prev, pager, next"
                data-test="judge-cards-pager"
                @current-change="loadCards"
                @size-change="loadCards(1)"
              />
            </div>
          </el-tab-pane>

          
          <el-tab-pane :label="t('judge.tabDiff')" name="diff">
            <div class="filter-bar">
              <el-input v-model.number="diffA" :placeholder="t('judge.diffA')" style="width: 150px" data-test="judge-diff-a" />
              <el-input v-model.number="diffB" :placeholder="t('judge.diffB')" style="width: 150px" data-test="judge-diff-b" />
              <el-button type="primary" data-test="judge-diff-btn" @click="loadDiff()">{{ t('judge.diffBtn') }}</el-button>
            </div>
            <template v-if="diff">
              <div class="judge-diff-stats" data-test="judge-diff-stats">
                {{ t('judge.diffStats', {
                  up: diff.upCount, down: diff.downCount, flat: diff.flatCount,
                  onlyA: diff.onlyACount, onlyB: diff.onlyBCount,
                  avgA: num2(diff.avgA), avgB: num2(diff.avgB), avgDiff: num2(diff.avgDiff),
                }) }}
              </div>
              <div class="judge-diff-note" data-test="judge-diff-note">{{ diff.significanceNote || '—' }}</div>
              <el-table :data="diff.rows" border row-key="sampleId" data-test="judge-diff-table"
                @sort-change="onSortChange">
                <el-table-column prop="sampleId" :label="t('judge.colSample')" min-width="220" sortable="custom">
                  <template #default="{ row }">{{ row.inputPreview || '—' }}</template>
                </el-table-column>
                <el-table-column prop="scoreA" :label="t('judge.colA')" width="90" align="center" sortable="custom">
                  <template #default="{ row }">{{ row.scoreA ?? '—' }}</template>
                </el-table-column>
                <el-table-column prop="scoreB" :label="t('judge.colB')" width="90" align="center" sortable="custom">
                  <template #default="{ row }">{{ row.scoreB ?? '—' }}</template>
                </el-table-column>
                <el-table-column prop="delta" :label="t('judge.colDelta')" width="90" align="center" sortable="custom">
                  <template #default="{ row }">
                    <span :class="deltaClass(row.delta)">{{ row.delta ?? '—' }}</span>
                  </template>
                </el-table-column>
                <el-table-column prop="verdict" :label="t('judge.colVerdict')" width="90" align="center" />
              </el-table>
              <div class="pagination-bar">
                <el-pagination
                  v-if="diff.totalRows > 0"
                  v-model:current-page="diffPage"
                  v-model:page-size="diffSize"
                  :total="diff.totalRows"
                  :page-sizes="[20, 50]"
                  layout="total, prev, pager, next"
                  data-test="judge-diff-pager"
                  @current-change="loadDiff"
                  @size-change="loadDiff(1)"
                />
              </div>
            </template>
          </el-tab-pane>

          
          <el-tab-pane :label="t('judge.tabCost')" name="cost">
            <el-descriptions v-if="cost" :column="3" border size="small" data-test="judge-cost-desc">
              <el-descriptions-item :label="t('judge.tokensTotal')">{{ cost.tokensTotal ?? 0 }}</el-descriptions-item>
              <el-descriptions-item :label="t('judge.scoredCount')">{{ cost.scored ?? 0 }}</el-descriptions-item>
              <el-descriptions-item :label="t('judge.sampleTotal')">{{ cost.sampleTotal ?? 0 }}</el-descriptions-item>
              <el-descriptions-item :label="t('judge.samplingRate')">{{ pct(cost.samplingRate) }}</el-descriptions-item>
              <el-descriptions-item :label="t('judge.tokensPerSample')">{{ cost.tokensPerSample ?? '—' }}</el-descriptions-item>
              <el-descriptions-item :label="t('judge.priceState')">
                <el-tag size="small" :type="cost.priceConfigured ? 'success' : 'warning'" data-test="judge-price-tag">
                  {{ cost.priceConfigured ? t('judge.priceOn') : t('judge.priceOff') }}
                </el-tag>
              </el-descriptions-item>
              <el-descriptions-item :label="t('judge.pricePer1k')">{{ cost.pricePer1kTokens ?? '—' }}</el-descriptions-item>
              <el-descriptions-item :label="t('judge.estimatedCost')"><span data-test="judge-est-cost">{{ cost.estimatedCost ?? '—' }}</span></el-descriptions-item>
              <el-descriptions-item :label="t('judge.costPerSample')">{{ cost.costPerSample ?? '—' }}</el-descriptions-item>
            </el-descriptions>
            <div class="judge-diff-note" data-test="judge-cost-note">{{ cost?.note || '' }}</div>
          </el-tab-pane>

          
          <el-tab-pane :label="t('judge.tabTrend')" name="trend">
            <div class="filter-bar">
              <el-input v-model="trendName" :placeholder="t('judge.trendName')" style="width: 200px" data-test="judge-trend-name" />
              <el-input v-model="trendDomain" :placeholder="t('judge.trendDomain')" style="width: 160px" data-test="judge-trend-domain" />
              <el-button type="primary" data-test="judge-trend-btn" @click="loadTrend">{{ t('judge.trendBtn') }}</el-button>
            </div>
            <div ref="trendEl" class="judge-trend" data-test="judge-trend-chart" />
            <el-table v-if="sessionCards.length" :data="sessionCards" border row-key="runId" data-test="judge-session-table">
              <el-table-column prop="runId" label="Run" width="80" />
              <el-table-column prop="targetRef" :label="t('judge.colTargetRef')" min-width="180" show-overflow-tooltip />
              <el-table-column :label="t('judge.colJudgeModel')" width="140">
                <template #default="{ row }">{{ row.judgeModel || '—' }}</template>
              </el-table-column>
              <el-table-column :label="t('judge.colAvg')" width="90" align="center">
                <template #default="{ row }">{{ row.avgFinalScore ?? '—' }}</template>
              </el-table-column>
              <el-table-column :label="t('judge.colPassRate')" width="90" align="center">
                <template #default="{ row }">{{ pct(row.passRate) }}</template>
              </el-table-column>
              <el-table-column :label="t('judge.colTokensPerSample')" width="120" align="center">
                <template #default="{ row }">{{ row.tokensPerSample ?? '—' }}</template>
              </el-table-column>
              <el-table-column prop="finishedAt" :label="t('judge.colFinishedAt')" width="170" />
            </el-table>
            <EmptyState v-else-if="trendLoaded" :title="t('judge.trendEmpty')" :hint="t('judge.trendEmptyHint')" />
          </el-tab-pane>
        </el-tabs>
      </template>

      <EmptyState
        v-else
        :title="t('judge.noRunTitle')"
        :hint="t('judge.noRunHint')"
      />
    </div>
  </div>
</template>

<script setup lang="ts">

import { nextTick, onMounted, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Search, QuestionFilled } from '@element-plus/icons-vue'
import { openHelp } from '@/composables/useHelpDrawer'
import type { ECharts, EChartsOption } from 'echarts'
import EmptyState from '@/components/EmptyState.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import {
  getRunScorecard, getRunSampleCards, getRunCostCard, diffJudgeRuns,
  getScorecardTrend, getSessionCards,
  type RunScorecard, type RunSampleCard, type RunCostCard, type RunDiff,
  type GenerationSessionCard, type ScorecardTrendPoint,
} from '@/api/judge'

const { t } = useI18n()
const route = useRoute()

const runIdInput = ref<number | null>(null)
const scorecard = ref<RunScorecard | null>(null)
const cost = ref<RunCostCard | null>(null)
const loading = ref(false)
const activeTab = ref('cards')

const cards = ref<RunSampleCard[]>([])
const cardsTotal = ref(0)
const cardsPage = ref(1)
const cardsSize = ref(50)
const lowOnly = ref(false)

const diff = ref<RunDiff | null>(null)
const diffA = ref<number | null>(null)
const diffB = ref<number | null>(null)
const diffPage = ref(1)
const diffSize = ref(50)

const trendName = ref('')
const trendDomain = ref('')
const trendLoaded = ref(false)
const sessionCards = ref<GenerationSessionCard[]>([])

let radarChart: ECharts | null = null
let trendChart: ECharts | null = null
const radarEl = ref<HTMLElement | null>(null)
const trendEl = ref<HTMLElement | null>(null)
let resizeHandler: (() => void) | null = null

function pct(v: number | null | undefined): string {
  return v == null ? '—' : `${Math.round(v * 1000) / 10}%`
}
function num2(v: number | null | undefined): string {
  return v == null ? '—' : (Math.round(v * 100) / 100).toFixed(2)
}

function cardScoreClass(c: RunSampleCard): string {
  const line = scorecard.value?.passScore
  if (c.authoritativeScore == null || line == null) return 'judge-score-none'
  return c.authoritativeScore < line ? 'judge-score-low' : 'judge-score-pass'
}


function reasonLines(c: RunSampleCard): { dim: string; text: string }[] {
  const out: { dim: string; text: string }[] = []
  if (!c.reasons) return out
  for (const [dim, raw] of Object.entries(c.reasons)) {
    if (raw == null) continue
    const text = typeof raw === 'string' ? raw
      : typeof (raw as { text?: unknown }).text === 'string' ? String((raw as { text: string }).text)
        : JSON.stringify(raw)
    out.push({ dim, text })
  }
  return out
}

function deltaClass(d: number | null): string {
  if (d == null) return ''
  return d > 0 ? 'judge-delta-up' : d < 0 ? 'judge-delta-down' : ''
}

async function loadRun() {
  const id = Number(runIdInput.value)
  if (!Number.isFinite(id) || id <= 0) {
    ElMessage.warning(t('judge.runIdInvalid'))
    return
  }
  loading.value = true
  try {
    scorecard.value = await getRunScorecard(id)
    trendName.value = scorecard.value.rubricName
    trendDomain.value = scorecard.value.rubricDomain
    diffA.value = id
    diffB.value = id
    await Promise.all([loadCards(1), loadCost(), renderRadar()])
  } finally {
    loading.value = false
  }
}

async function loadCards(page = cardsPage.value) {
  cardsPage.value = page
  const line = scorecard.value?.passScore
  const fetchSize = lowOnly.value && line != null ? Math.min(100, cardsSize.value * 4) : cardsSize.value
  const resp = await getRunSampleCards(Number(runIdInput.value), page, fetchSize)
  cardsTotal.value = Number(resp.total) || 0
  let list = resp.cards ?? []
  if (lowOnly.value && line != null) list = list.filter((c) => c.authoritativeScore != null && c.authoritativeScore < line)
  cards.value = list
}

watch(lowOnly, () => { if (scorecard.value) void loadCards(cardsPage.value) })

async function loadCost() {
  if (!scorecard.value) return
  cost.value = await getRunCostCard(scorecard.value.runId ?? Number(runIdInput.value))
}

async function loadDiff(page = diffPage.value) {
  diffPage.value = page
  const a = Number(diffA.value)
  const b = Number(diffB.value)
  if (!Number.isFinite(a) || !Number.isFinite(b) || a <= 0 || b <= 0) {
    ElMessage.warning(t('judge.diffNeedIds'))
    return
  }
  diff.value = await diffJudgeRuns(a, b, page, diffSize.value,
    { sortBy: query.sortBy || undefined, sortDir: query.sortDir || undefined })
}

// Column prop -> core judge-diff SORT_COLUMNS whitelist key (single source for
// the sortable decision and the request payload; . Ordering runs
// over the whole merged window server-side, not the loaded pageful; the verdict
// column is a derived display and stays honestly unsorted.
const SORT_KEY_MAP: Record<string, string> = {
  sampleId: 'sample_id',
  scoreA: 'score_a',
  scoreB: 'score_b',
  delta: 'delta',
}
const query = reactive<{ sortBy: string; sortDir: string }>({ sortBy: '', sortDir: '' })

/** Header sort: forward the whitelisted key to core; clearing a sort falls back
 * to the historical sample-id ascending default; page resets to 1. */
function onSortChange({ prop, order }: { prop: string; order: string | null }): void {
  diffPage.value = 1
  if (order === null) {
    query.sortBy = ''
    query.sortDir = ''
  } else {
    const key = SORT_KEY_MAP[prop]
    if (key) {
      query.sortBy = key
      query.sortDir = order === 'ascending' ? 'asc' : 'desc'
    }
  }
  if (diff.value) void loadDiff(1)
}

async function loadTrend() {
  if (!trendName.value || !trendDomain.value) {
    ElMessage.warning(t('judge.trendNeedFamily'))
    return
  }
  const points = await getScorecardTrend(trendName.value, trendDomain.value, 50)
  sessionCards.value = await getSessionCards(trendName.value, trendDomain.value, 50)
  trendLoaded.value = true
  await nextTick()
  renderTrend(points ?? [])
}

async function renderRadar() {
  if (!scorecard.value || !radarEl.value) return
  const dims = Object.entries(scorecard.value.dimensionAverages ?? {})
  const { init } = await import('echarts')
  if (!radarChart) radarChart = init(radarEl.value)
  const option: EChartsOption = {
    tooltip: {},
    radar: {
      indicator: dims.map(([name]) => ({ name, max: 100 })),
      radius: '65%',
    },
    series: [{
      type: 'radar',
      data: [{
        value: dims.map(([, v]) => v),
        name: t('judge.dimAvg'),
        areaStyle: { opacity: 0.25 },
      }],
    }],
  }
  radarChart.setOption(option, true)
  radarChart.resize()
}

async function renderTrend(points: ScorecardTrendPoint[]) {
  if (!trendEl.value) return
  const { init } = await import('echarts')
  if (!trendChart) trendChart = init(trendEl.value)
  if (points.length === 0) {
    trendChart.clear()
    return
  }
  const versionChangeIdx = points.findIndex((p, i) => i > 0 && p.rubricVersion !== points[i - 1].rubricVersion)
  const option: EChartsOption = {
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: points.map((p) => p.finishedAt ?? String(p.runId ?? '')) },
    yAxis: { type: 'value', min: 0, max: 100 },
    series: [{
      type: 'line',
      data: points.map((p) => p.avgFinalScore),
      name: t('judge.avgScoreLabel'),
      markLine: versionChangeIdx > 0
        ? { symbol: 'none', label: { formatter: t('judge.rubricChange') }, lineStyle: { type: 'dashed' }, data: [{ xAxis: versionChangeIdx }] }
        : undefined,
    }],
  }
  trendChart.setOption(option, true)
  trendChart.resize()
}

onMounted(async () => {
  const q = Number(route.query.runId)
  if (Number.isFinite(q) && q > 0) {
    runIdInput.value = q
    await loadRun()
  }
  resizeHandler = () => { radarChart?.resize(); trendChart?.resize() }
  window.addEventListener('resize', resizeHandler)
})

onBeforeUnmount(() => {
  if (resizeHandler) window.removeEventListener('resize', resizeHandler)
  radarChart?.dispose()
  trendChart?.dispose()
  radarChart = null
  trendChart = null
})
</script>

<style scoped>
.judge-summary {
  display: flex;
  align-items: center;
  gap: 24px;
  margin: 12px 0 4px;
}
.judge-bigscore { flex: 0 0 320px; }
.judge-bigscore-num {
  font-size: 36px;
  font-weight: 600;
  line-height: 1.5;
}
.judge-bigscore-label { color: var(--el-text-color-secondary); margin-bottom: 6px; }
.judge-bigsize-meta { font-size: 13px; color: var(--el-text-color-secondary); line-height: 1.8; }
.judge-radar { width: 380px; height: 300px; }
.judge-trend { width: 100%; height: 300px; }
.judge-cards-toolbar { display: flex; align-items: center; gap: 16px; margin-bottom: 8px; }
.judge-card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 10px 14px;
  margin-bottom: 10px;
}
.judge-card-head { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.judge-card-score { font-size: 24px; font-weight: 600; min-width: 44px; text-align: center; }
.judge-score-low { color: var(--el-color-danger); }
.judge-score-pass { color: var(--el-color-success); }
.judge-score-none { color: var(--el-text-color-secondary); }
.judge-card-line { font-size: 13px; line-height: 1.8; word-break: break-all; }
.judge-card-line b { margin-right: 8px; color: var(--el-text-color-secondary); font-weight: 600; }
.judge-rule-hit { color: var(--el-color-warning); }
.judge-diff-stats { font-size: 13px; margin: 8px 0 4px; }
.judge-diff-note { font-size: 12px; color: var(--el-text-color-secondary); margin: 6px 0; }
.judge-delta-up { color: var(--el-color-success); font-weight: 600; }
.judge-delta-down { color: var(--el-color-danger); font-weight: 600; }
</style>
