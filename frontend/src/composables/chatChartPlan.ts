/**
 * Geometry derivation for the aggregate-query live card .
 *
 * Pure module (no Vue, no SVG) so the fold contract and the worst-scale
 * fixture spectra share one source with the component — same baseline as
 * chatDockReducer. The card renders exactly what the database answered: the
 * first column is the dimension axis, every remaining column whose cells all
 * read as numbers becomes a series; nothing is invented, dropped or averaged
 * client-side.
 *
 * Chart kind (the line-vs-bar adjudication, P3): an all-numeric dimension is a
 * sequence, so the series draw as a trend line; a categorical dimension (text
 * labels) draws as bars. Values outside the numeric contract (nulls, blanks)
 * stay gaps — a fake zero would misreport the data.
 */
import type { AggregateChart } from './chatDockReducer'

/** One plotted point of a series (label = dimension cell, value = measure). */
export interface ChartPoint {
  label: string
  /** null when the cell is missing/non-numeric — the renderer leaves a gap. */
  value: number | null
}

/** One measure column projected onto the dimension axis. */
export interface ChartSeries {
  name: string
  points: ChartPoint[]
}

/** The renderable plan of one QueryResult (labels + geometry, no pixels). */
export interface ChartPlan {
  /** 'line' = numeric dimension (trend), 'bar' = categorical dimension. */
  kind: 'line' | 'bar'
  /** Dimension column label (first result column). */
  dimension: string
  /** Measure series in select order (numeric columns after the dimension). */
  series: ChartSeries[]
  /** True when the server answered with zero rows (honest empty state). */
  empty: boolean
  /** Row cap the server applied, forwarded for the honesty note. */
  rowLimitUsed: number
}

/**
 * Read a cell as the number the database produced: JSON numbers pass through,
 * numeric strings (driver typing drift, e.g. NUMERIC arriving as text) parse
 * strictly, everything else (null, blank, text) is a gap.
 */
function toNumeric(value: unknown): number | null {
  if (typeof value === 'number') return Number.isFinite(value) ? value : null
  if (typeof value === 'string') {
    const trimmed = value.trim()
    if (trimmed === '') return null
    const parsed = Number(trimmed)
    return Number.isFinite(parsed) ? parsed : null
  }
  return null
}

/** The dimension cell text as it should appear on the axis (never '[object]'). */
function axisLabel(value: unknown): string {
  if (value === null || value === undefined) return '—'
  return String(value)
}

/** Nice y-axis scale: data-free bounds plus the tick values to label. */
export interface AxisScale {
  /** Bottom of the (nicely rounded) axis range — 0 stays the baseline. */
  min: number
  /** Top of the nicely rounded range — every data point sits inside it. */
  max: number
  /** Monotonic tick values, min first and max last (2..6 values). */
  ticks: number[]
}

/**
 * Project raw measure values onto a nice axis (the classic 1/2/5 step ladder).
 * The renderer used to split the data range into three equal thirds, which
 * labelled count semantics with false precision (5.67 / 11.33 on a bar chart
 * of case counts). A nice step fixes that: integer measures always get integer
 * ticks, while genuinely fractional measures (averages, rates) keep at most
 * two clean decimals — never floating noise like 0.19999999999999998.
 * Nulls are gaps and never join the bounds; all-null input falls back to 0..1.
 */
export function computeAxisScale(values: Array<number | null>): AxisScale {
  const nums = values.filter((v): v is number => v !== null)
  let dataMin = nums.length ? Math.min(...nums) : 0
  let dataMax = nums.length ? Math.max(...nums) : 1
  // Bars and lines keep the zero baseline whenever the data is non-negative.
  if (dataMin > 0) dataMin = 0
  if (dataMax < 0) dataMax = 0
  if (dataMax === dataMin) dataMax = dataMin + 1

  const integerOnly = nums.length > 0 && nums.every(Number.isInteger)
  const rawStep = (dataMax - dataMin) / 4
  const mag = 10 ** Math.floor(Math.log10(rawStep))
  const norm = rawStep / mag
  let step = (norm < 1.5 ? 1 : norm < 3 ? 2 : norm < 7 ? 5 : 10) * mag
  // An integer measure never gets a fractional grid (2 rows of counts must
  // not label 0.5), even when the nice ladder suggests a sub-unit step.
  if (integerOnly && step < 1) step = 1

  // Clean = round onto the grid, then strip accumulated float drift
  // (0.30000000000000004 → 0.3) so labels print exactly what they mean.
  const clean = (v: number) => Number((Math.round(v / step) * step).toPrecision(12))
  let bottom = Math.floor(dataMin / step) * step
  let top = Math.ceil(dataMax / step) * step
  // Safety valve: a pathological span never renders a 40-line axis.
  while ((top - bottom) / step > 8) step *= 2

  const count = Math.round((top - bottom) / step)
  const ticks = Array.from({ length: count + 1 }, (_, i) => clean(bottom + i * step))
  return { min: ticks[0], max: ticks[ticks.length - 1], ticks }
}

/**
 * Plan one journaled QueryResult. Returns null when the result cannot form a
 * chart at all (fewer than two columns, or no numeric measure column) — the
 * caller then shows the table-only form instead of an empty SVG.
 */
export function deriveChartPlan(chart: AggregateChart): ChartPlan | null {
  const { columns, rows, rowLimitUsed } = chart
  if (columns.length < 2) return null
  const dimension = columns[0]
  if (rows.length === 0) {
    // An honest empty answer keeps every non-dimension column as a plotted
    // (zero-point) series — the card shows the empty state, not a null plan.
    return {
      kind: 'bar',
      dimension,
      series: columns.slice(1).map((name) => ({ name, points: [] })),
      empty: true,
      rowLimitUsed,
    }
  }
  const measureNames = columns.slice(1).filter((name) =>
    // a column counts as a measure when at least one cell reads numeric
    rows.some((row) => toNumeric(row[name]) !== null))
  if (measureNames.length === 0) return null

  const series: ChartSeries[] = measureNames.map((name) => ({
    name,
    points: rows.map((row) => ({ label: axisLabel(row[dimension]), value: toNumeric(row[name]) })),
  }))

  // line when every dimension cell is numeric (a sequence), bars otherwise.
  const dimensionIsNumeric = rows.length > 0
    && rows.every((row) => toNumeric(row[dimension]) !== null)

  return {
    kind: dimensionIsNumeric ? 'line' : 'bar',
    dimension,
    series,
    empty: rows.length === 0,
    rowLimitUsed,
  }
}
