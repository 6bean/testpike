<template>
  <!--
 Aggregate-query live card : the journaled server-side
 QueryResult of the built-in aggregate.query tool renders as an in-dialog
 chart drawn with native SVG (ECharts stays off this surface per the
 license ruling). Every number on screen came from the database — the model
 only composed the intent (ruling: the model never typesets card data).
 Three honest shapes: an unusable plan degrades to the plain value table
 (the numbers still ride), an empty result says so, and rows that hit the
 server row cap name the cap (never a silently truncated chart).
 Worst-scale design: axis labels thin out past a dozen categories, the
 per-row value table folds into a collapsed disclosure, the SVG scales via
 viewBox so 40 categories x 3 series stay legible without JS measuring.
 -->
  <div class="live-card" data-test="chat-live-card">
    <div class="live-head">
      <span class="live-title">{{ t('layout.chatDock.liveCard.title') }}</span>
      <span class="live-target" :title="chart.target">{{ chart.target }}</span>
    </div>

    <p v-if="plan === null || plan.empty" class="live-note" data-test="chat-live-note">
      {{ plan === null
        ? t('layout.chatDock.liveCard.noSeries')
        : t('layout.chatDock.liveCard.empty') }}
    </p>

    <svg
      v-if="plan !== null && !plan.empty"
      class="live-svg"
      data-test="chat-live-svg"
      :viewBox="`0 0 ${W} ${H}`"
      role="img"
      :aria-label="t('layout.chatDock.liveCard.aria', { target: chart.target })"
      preserveAspectRatio="xMidYMid meet"
    >
      <!-- horizontal gridlines + y tick labels (nice bounds over every series) -->
      <g class="live-grid">
        <template v-for="(tick, ti) in ticks" :key="`grid-${ti}`">
          <line :x1="PAD_L" :x2="W - PAD_R" :y1="tick.y" :y2="tick.y" />
          <text :x="PAD_L - 6" :y="tick.y + 3" text-anchor="end">{{ tick.label }}</text>
        </template>
      </g>

      <!-- bars: one cluster per category, one bar per series -->
      <g v-if="plan.kind === 'bar'" data-test="chat-live-bars">
        <rect
          v-for="b in barRects"
          :key="b.key"
          class="live-bar"
          :x="b.x"
          :y="b.y"
          :width="b.w"
          :height="b.h"
          :fill="b.color"
          :data-value="b.value"
          :data-series="b.series"
          :data-label="b.label"
          data-test="chat-live-bar"
        >
          <title>{{ b.label }} · {{ b.series }} · {{ b.display }}</title>
        </rect>
      </g>

      <!-- line: one polyline path per series, null cells break the stroke (gap) -->
      <g v-else data-test="chat-live-lines">
        <template v-for="(s, si) in linePaths" :key="`line-${si}`">
          <path
            v-for="(d, di) in s.d"
            :key="`seg-${si}-${di}`"
            class="live-line"
            :d="d"
            :stroke="s.color"
            :data-test="`chat-live-line-${s.name}`"
          />
          <circle
            v-for="p in s.dots"
            :key="`dot-${si}-${p.i}`"
            class="live-dot"
            :cx="p.x"
            :cy="p.y"
            r="3"
            :fill="s.color"
            :data-value="p.value"
            :data-series="s.name"
            :data-label="p.label"
            data-test="chat-live-point"
          >
            <title>{{ p.label }} · {{ s.name }} · {{ p.display }}</title>
          </circle>
        </template>
      </g>

      <!-- x axis labels, thinned past a dozen categories (scale safety) -->
      <g class="live-axis">
        <line :x1="PAD_L" :x2="W - PAD_R" :y1="axisY" :y2="axisY" />
        <text
          v-for="a in axisLabels"
          :key="`ax-${a.i}`"
          :x="a.x"
          :y="axisY + 16"
          text-anchor="middle"
        >{{ a.text }}</text>
      </g>
    </svg>

    <!-- legend (series names = result column labels, verbatim) -->
    <div v-if="plan !== null && !plan.empty && plan.series.length" class="live-legend" data-test="chat-live-legend">
      <span v-for="(s, i) in plan.series" :key="`lg-${i}`" class="live-legend-item">
        <i class="live-swatch" :style="{ background: colorAt(i) }" />{{ s.name }}
      </span>
    </div>

    <!-- cap honesty + collapsed full-value table (worst-scale disclosure) -->
    <p v-if="capReached" class="live-note live-cap-note" data-test="chat-live-cap">
      {{ t('layout.chatDock.liveCard.capNote', { n: chart.rowLimitUsed }) }}
    </p>
    <details v-if="plan === null || !plan.empty" class="live-table" data-test="chat-live-table">
      <summary>{{ t('layout.chatDock.liveCard.tableToggle', { n: chart.rows.length }) }}</summary>
      <div class="live-table-scroll">
      <table>
        <thead>
          <tr><th v-for="c in chart.columns" :key="c">{{ c }}</th></tr>
        </thead>
        <tbody>
          <tr v-for="(r, ri) in chart.rows" :key="`tr-${ri}`">
            <td v-for="c in chart.columns" :key="`td-${ri}-${c}`" :data-test="c === chart.columns[0] ? 'chat-live-dim' : 'chat-live-value'">{{ cellText(r[c]) }}</td>
          </tr>
        </tbody>
      </table>
      </div>
    </details>
  </div>
</template>

<script setup lang="ts">
/**
 * Native-SVG live card of the aggregate.query answer (no charting library on
 * this surface — keeps ECharts off the bundle for flow/canvas pages only,
 * and the dialog card stays dependency-free by the same ruling). Geometry is
 * derived by the pure chatChartPlan module (one fold semantics, testable in
 * node vitest); this component only projects plan numbers into pixels.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { AggregateChart } from '@/composables/chatDockReducer'
import { computeAxisScale, deriveChartPlan, type ChartPlan } from '@/composables/chatChartPlan'

const props = defineProps<{ chart: AggregateChart }>()
const { t } = useI18n()

const W = 640
const H = 240
const PAD_L = 48
const PAD_R = 12
const PAD_T = 10
const AXIS_BOTTOM = 30

const plan = computed<ChartPlan | null>(() => deriveChartPlan(props.chart))
const axisY = H - AXIS_BOTTOM

/** Palette: theme primary first, then calm hue steps (dark + light safe). */
const PALETTE = ['#38bdf8', '#818cf8', '#34d399', '#fbbf24', '#f472b6']
function colorAt(i: number): string {
  return PALETTE[i % PALETTE.length]
}

/**
 * Nice axis across every series (null gaps skipped, zero baseline kept). The
 * 1/2/5 step ladder lives in the pure module so the tick contract is unit
 * tested without a DOM: integer measures never label false precision (5.67),
 * average measures keep clean decimals.
 */
const axis = computed(() =>
  computeAxisScale((plan.value?.series ?? []).flatMap((s) => s.points.map((p) => p.value))))

function scaleY(v: number): number {
  const span = axis.value.max - axis.value.min || 1
  return axisY - ((v - axis.value.min) / span) * (axisY - PAD_T)
}

const ticks = computed(() =>
  axis.value.ticks.map((v) => ({ y: scaleY(v), label: fmt(v) })))

function fmt(v: number): string {
  if (Number.isInteger(v)) return String(v)
  return v.toFixed(2).replace(/0+$/, '').replace(/\.$/, '')
}

function cellText(value: unknown): string {
  if (value === null || value === undefined) return '—'
  return String(value)
}

/** Category slot center for index i of n (shared by bars, lines, labels). */
function slotX(i: number, n: number): number {
  const plot = W - PAD_L - PAD_R
  return PAD_L + (plot / n) * (i + 0.5)
}

const barRects = computed(() => {
  const p = plan.value
  if (!p || p.kind !== 'bar') return []
  const n = p.series[0]?.points.length ?? 0
  if (n === 0) return []
  const slot = (W - PAD_L - PAD_R) / n
  const inner = slot * 0.72
  const bw = inner / p.series.length
  const rects: Array<{ key: string; x: number; y: number; w: number; h: number; color: string; value: number | null; series: string; label: string; display: string }> = []
  const zeroY = scaleY(0)
  p.series.forEach((s, si) => {
    s.points.forEach((pt, i) => {
      if (pt.value === null) return
      const y = Math.min(scaleY(pt.value), zeroY)
      const h = Math.max(Math.abs(zeroY - scaleY(pt.value)), 1)
      rects.push({
        key: `${si}-${i}`,
        x: PAD_L + slot * i + slot * 0.14 + bw * si,
        y,
        w: bw,
        h,
        color: colorAt(si),
        value: pt.value,
        series: s.name,
        label: pt.label,
        display: fmt(pt.value),
      })
    })
  })
  return rects
})

const linePaths = computed(() => {
  const p = plan.value
  if (!p || p.kind !== 'line') return []
  const n = p.series[0]?.points.length ?? 0
  return p.series.map((s, si) => {
    // Consecutive numeric points join into one stroke run; a null cell ends
    // the run and the next point opens a fresh sub-path (an honest gap).
    const runs: string[] = []
    const dots: Array<{ i: number; x: number; y: number; value: number; label: string; display: string }> = []
    let cur: string[] = []
    s.points.forEach((pt, i) => {
      if (pt.value === null) {
        if (cur.length >= 2) runs.push(cur.join(' '))
        cur = []
        return
      }
      const x = slotX(i, n)
      const y = scaleY(pt.value)
      cur.push(cur.length === 0 ? `M${x.toFixed(1)},${y.toFixed(1)}` : `L${x.toFixed(1)},${y.toFixed(1)}`)
      dots.push({ i, x, y, value: pt.value, label: pt.label, display: fmt(pt.value) })
    })
    if (cur.length >= 2) runs.push(cur.join(' '))
    // an isolated point (single-point run) still renders via its dot below
    return { name: s.name, color: colorAt(si), d: runs, dots }
  })
})

/** Axis labels thinned past a dozen categories (never a squeezed wall). */
const axisLabels = computed(() => {
  const p = plan.value
  if (!p || p.empty) return []
  const labels = p.series[0]?.points ?? []
  const every = Math.max(1, Math.ceil(labels.length / 12))
  return labels
    .map((pt, i) => ({ i, x: slotX(i, labels.length), text: shorten(pt.label) }))
    .filter((a) => a.i % every === 0)
})

function shorten(text: string): string {
  return text.length > 10 ? `${text.slice(0, 9)}…` : text
}

const capReached = computed(() =>
  props.chart.rowLimitUsed > 0 && props.chart.rows.length >= props.chart.rowLimitUsed)
</script>

<style scoped>
/* Card shell matches the draft-card band (theme single source, no new visual
   dialect): same border/background tokens as ChatDraftCard. */
.live-card {
  margin: 8px 0;
  padding: 10px 12px;
  border: 1px solid var(--tp-border, rgba(148, 163, 184, 0.2));
  border-radius: 8px;
  background: var(--tp-primary-weak, rgba(56, 189, 248, 0.08));
}
.live-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 6px;
}
.live-title {
  font-weight: 600;
  color: var(--tp-text-primary, #e2e8f0);
}
.live-target {
  font-family: var(--tp-font-mono, monospace);
  font-size: 12px;
  color: var(--tp-text-secondary, #98a2b3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 60%;
}
.live-svg {
  display: block;
  width: 100%;
  height: auto;
}
.live-grid line {
  stroke: var(--tp-border, rgba(148, 163, 184, 0.2));
  stroke-dasharray: 3 3;
}
.live-grid text,
.live-axis text {
  font-size: 10px;
  fill: var(--tp-text-secondary, #98a2b3);
}
.live-axis line {
  stroke: var(--tp-border, rgba(148, 163, 184, 0.35));
}
.live-bar {
  opacity: 0.9;
}
.live-bar:hover {
  opacity: 1;
}
.live-line {
  fill: none;
  stroke-width: 2;
}
.live-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 8px;
  font-size: 12px;
  color: var(--tp-text-secondary, #98a2b3);
}
.live-swatch {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 2px;
  margin-right: 4px;
}
.live-note {
  margin: 4px 0;
  font-size: 12px;
  color: var(--tp-text-secondary, #98a2b3);
}
.live-cap-note {
  color: var(--el-color-warning, #e6a23c);
}
.live-table {
  margin-top: 6px;
  font-size: 12px;
}
.live-table summary {
  cursor: pointer;
  color: var(--el-color-primary, #409eff);
}
.live-table table {
  width: 100%;
  border-collapse: collapse;
  margin-top: 6px;
}
/* Worst-scale containment: a wide result set folds inside a capped,
   scrollable wrapper (kept as display:table so head and body share one
   column grid) instead of running the page off-screen. */
.live-table-scroll {
  max-height: 320px;
  overflow-y: auto;
}
.live-table th,
.live-table td {
  padding: 2px 8px;
  border-bottom: 1px solid var(--tp-border, rgba(148, 163, 184, 0.15));
  text-align: left;
  color: var(--tp-text-secondary, #98a2b3);
}
.live-table th {
  color: var(--tp-text-primary, #e2e8f0);
  font-weight: 600;
}
</style>
