<script setup lang="ts">

import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ECharts, EChartsOption } from 'echarts'
import type { PerfSampleRow } from '@/utils/stepEvidence'

const props = defineProps<{ samples: PerfSampleRow[] }>()
const { t } = useI18n()

const chartEl = ref<HTMLDivElement | null>(null)
let chart: ECharts | null = null
let resizeHandler: (() => void) | null = null


function xs(rows: PerfSampleRow[]): number[] {
  const t0 = rows.length ? rows[0].ts : 0
  return rows.map((r) => Math.round((r.ts - t0) * 10) / 10)
}

function seriesOf(rows: PerfSampleRow[], key: keyof PerfSampleRow): (number | null)[] {
  return rows.map((r) => (typeof r[key] === 'number' ? (r[key] as number) : null))
}

function buildOption(rows: PerfSampleRow[]): EChartsOption {
  const x = xs(rows)
  return {
    animation: false,
    grid: { left: 44, right: 52, top: 30, bottom: 24 },
    legend: { textStyle: { fontSize: 11 }, top: 0 },
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: x, name: t('exec.perfAxisTime'), nameGap: 4, axisLabel: { fontSize: 10 } },
    yAxis: [
      { type: 'value', axisLabel: { fontSize: 10 }, splitLine: { lineStyle: { opacity: 0.25 } } },
      { type: 'value', name: t('exec.perfAxisPss'), axisLabel: { fontSize: 10 }, splitLine: { show: false } },
    ],
    series: [
      { name: t('exec.perfSeriesJank'), type: 'line', data: seriesOf(rows, 'jankPct'), connectNulls: false, symbolSize: 4, lineStyle: { width: 1.5 } },
      { name: t('exec.perfSeriesCpu'), type: 'line', data: seriesOf(rows, 'cpuPct'), connectNulls: false, symbolSize: 4, lineStyle: { width: 1.5 } },
      { name: t('exec.perfSeriesTemp'), type: 'line', data: seriesOf(rows, 'tempC'), connectNulls: false, symbolSize: 4, lineStyle: { width: 1.5 } },
      { name: t('exec.perfSeriesPss'), type: 'line', yAxisIndex: 1, data: seriesOf(rows, 'pssKb').map((v) => (v == null ? null : Math.round(v / 1024))), connectNulls: false, symbolSize: 4, lineStyle: { width: 1.5 } },
    ],
  }
}

async function render(): Promise<void> {
  if (!chartEl.value || !props.samples.length) return
  const { init } = await import('echarts')
  if (!chartEl.value) return
  if (!chart) chart = init(chartEl.value)
  chart.setOption(buildOption(props.samples))
}

onMounted(() => {
  void render()
  resizeHandler = () => chart?.resize()
  window.addEventListener('resize', resizeHandler)
})
watch(() => props.samples, () => void render(), { deep: true })
onBeforeUnmount(() => {
  if (resizeHandler) window.removeEventListener('resize', resizeHandler)
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div class="perf-pane" data-test="perf-timeline-pane">
    <div class="perf-head" data-test="perf-sample-count">
      {{ t('exec.perfSamples', { count: samples.length }) }}
    </div>
    <div v-if="!samples.length" class="perf-empty">{{ t('exec.perfEmpty') }}</div>
    <div v-else ref="chartEl" class="perf-chart" />
  </div>
</template>

<style scoped lang="scss">
.perf-pane {
  width: 100%;

  .perf-head {
    font-size: 12px;
    color: var(--tp-text-secondary);
    margin-bottom: 4px;
  }

  .perf-empty {
    font-size: 12px;
    color: var(--tp-warning);
  }

  .perf-chart {
    width: 100%;
    height: 220px;
    border: 1px solid var(--tp-border);
    border-radius: 6px;
    background: var(--tp-bg-elevated);
  }
}
</style>
