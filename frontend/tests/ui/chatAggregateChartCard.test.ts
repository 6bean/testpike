/**
 * P3 aggregate-query live card contracts (pure logic, node
 * vitest).
 *
 * The built-in `aggregate.query` tool journals its QueryResult answer onto the
 * trace frame's meta under `chart` (ai-service FrameLedgerStreamSink
 * metaKeyOf). The reducer folds it into turn.chart exactly like the flow guide
 * — the human trajectory line always survives in the process area, an
 * unusable meta folds to null so a server-side drift never loses the line.
 *
 * The chart plan (dimension axis / series / bar-vs-line) is derived by the
 * pure module the component consumes: first column = dimension, remaining
 * numeric columns = series; an all-numeric dimension reads as a trend line,
 * anything categorical as bars. The model never typesets these numbers — the
 * card renders what the database answered, verbatim.
 */
import { describe, expect, it } from 'vitest'
import {
  applyFrameToTurn,
  createAssistantTurn,
  createUserTurn,
} from '@/composables/chatDockReducer'
import { computeAxisScale, deriveChartPlan } from '@/composables/chatChartPlan'

/** One trace frame whose meta carries the journaled QueryResult under chart. */
function chartFrame(result: unknown, id = 1) {
  return {
    event: 'trace',
    id,
    data: '工具 aggregate.query 执行完成',
    meta: JSON.stringify({ phase: 'tool_done', tool: 'aggregate.query', success: true, chart: result }),
  }
}

function queryResult(overrides: Record<string, unknown> = {}) {
  return {
    target: 'case.count_by_status',
    columns: ['status_label', '用例数'],
    rows: [
      { status_label: '草稿', 用例数: 12 },
      { status_label: '待评审', 用例数: 5 },
      { status_label: '已通过', 用例数: 31 },
    ],
    rowLimitUsed: 200,
    ...overrides,
  }
}

describe('aggregate chart fold (reducer)', () => {
  it('folds a trace frame chart meta into turn.chart and keeps the human line', () => {
    const turn = createAssistantTurn(50)
    applyFrameToTurn(turn, chartFrame(queryResult()))
    expect(turn.process).toEqual([{ kind: 'trace', text: '工具 aggregate.query 执行完成' }])
    expect(turn.chart).not.toBeNull()
    expect(turn.chart!.target).toBe('case.count_by_status')
    expect(turn.chart!.columns).toEqual(['status_label', '用例数'])
    expect(turn.chart!.rows).toHaveLength(3)
    expect(turn.chart!.rowLimitUsed).toBe(200)
  })

  it('unwraps the core Response envelope; a refused code folds no card', () => {
    const ok = createAssistantTurn(51)
    applyFrameToTurn(ok, chartFrame({ code: '0', msg: '', data: queryResult() }))
    expect(ok.chart).not.toBeNull()
    expect(ok.chart!.target).toBe('case.count_by_status')

    const refused = createAssistantTurn(52)
    applyFrameToTurn(refused, chartFrame({ code: '500', msg: '目标未登记' }))
    expect(refused.chart).toBeNull()
    expect(refused.process).toHaveLength(1)
  })

  it('unusable shapes (missing/blank rows contract drift, bad JSON, scalars) fold null', () => {
    const turn = createAssistantTurn(53)
    applyFrameToTurn(turn, { event: 'trace', id: 1, data: 't', meta: '{"phase":"tool_done","tool":"other","success":true}' })
    applyFrameToTurn(turn, { event: 'trace', id: 2, data: 't', meta: '{broken json' })
    applyFrameToTurn(turn, chartFrame('not-an-object'))
    applyFrameToTurn(turn, chartFrame({ target: 'x', columns: 'should-be-array', rows: [], rowLimitUsed: 0 }))
    applyFrameToTurn(turn, chartFrame(queryResult({ rows: 'not-array' })))
    expect(turn.chart).toBeNull()
    expect(turn.process).toHaveLength(5)
  })

  it('a later chart frame replaces the earlier one (last answer wins, like guide)', () => {
    const turn = createAssistantTurn(54)
    applyFrameToTurn(turn, chartFrame(queryResult()))
    applyFrameToTurn(turn, chartFrame(queryResult({ target: 'run.count_by_env' }), 2))
    expect(turn.chart!.target).toBe('run.count_by_env')
    // the fold never duplicates rows into the process area
    expect(turn.process.filter((p) => p.kind === 'trace')).toHaveLength(2)
  })

  it('fresh turns start with chart null (no phantom card)', () => {
    expect(createAssistantTurn(55).chart).toBeNull()
    expect(createUserTurn(56, 'q').chart).toBeNull()
  })
})

describe('chart plan derivation (geometry source)', () => {
  it('first column is the dimension axis, remaining numeric columns are series', () => {
    const plan = deriveChartPlan(queryResult())
    expect(plan).not.toBeNull()
    expect(plan!.kind).toBe('bar')
    expect(plan!.dimension).toBe('status_label')
    expect(plan!.series.map((s) => s.name)).toEqual(['用例数'])
    expect(plan!.series[0].points).toEqual([
      { label: '草稿', value: 12 },
      { label: '待评审', value: 5 },
      { label: '已通过', value: 31 },
    ])
  })

  it('an all-numeric dimension reads as a trend line, categorical stays bars', () => {
    const plan = deriveChartPlan({
      target: 'usage.daily',
      columns: ['day', 'tokens'],
      rows: [
        { day: 1, tokens: 100 },
        { day: 2, tokens: 180 },
        { day: 3, tokens: 90 },
      ],
      rowLimitUsed: 200,
    })
    expect(plan!.kind).toBe('line')
  })

  it('non-numeric extra columns never become series (string rows would render NaN bars)', () => {
    const plan = deriveChartPlan({
      target: 't',
      columns: ['status_label', '用例数', '备注'],
      rows: [
        { status_label: '草稿', 用例数: '12', 备注: '文本' },
        { status_label: '待评审', 用例数: '5', 备注: '也文本' },
      ],
      rowLimitUsed: 200,
    })
    expect(plan!.series.map((s) => s.name)).toEqual(['用例数'])
    expect(plan!.series[0].points[0].value).toBe(12)
  })

  it('mixed null cells keep the point visible as a gap, never a fake zero', () => {
    const plan = deriveChartPlan({
      target: 't',
      columns: ['status_label', '通过率'],
      rows: [
        { status_label: 'A', 通过率: 0.5 },
        { status_label: 'B', 通过率: null },
        { status_label: 'C', 通过率: 0.25 },
      ],
      rowLimitUsed: 200,
    })
    expect(plan!.series[0].points.map((p) => p.value)).toEqual([0.5, null, 0.25])
  })

  it('empty result set plans to an honest empty (no invented axes)', () => {
    const plan = deriveChartPlan(queryResult({ rows: [] }))
    expect(plan).not.toBeNull()
    expect(plan!.empty).toBe(true)
  })

  it('scales to the worst fixture: 40 rows x 3 series all project (no silent truncation)', () => {
    const columns = ['d', 'a', 'b', 'c']
    const rows = Array.from({ length: 40 }, (_, i) => ({ d: `维度${i}`, a: i, b: i * 2, c: i % 7 }))
    const plan = deriveChartPlan({ target: 't', columns, rows, rowLimitUsed: 200 })
    expect(plan!.series).toHaveLength(3)
    plan!.series.forEach((s) => expect(s.points).toHaveLength(40))
  })

  it('a single numeric column (no series) or fewer than two columns plans to null', () => {
    expect(deriveChartPlan({ target: 't', columns: ['d'], rows: [{ d: 'x' }], rowLimitUsed: 10 })).toBeNull()
  })
})

describe('y-axis nice ticks (axis scale contract)', () => {
  it('count-like integer data gets integer ticks — never false precision like 5.67', () => {
    const scale = computeAxisScale([12, 5, 31])
    expect(scale.min).toBeLessThanOrEqual(5)
    expect(scale.max).toBeGreaterThanOrEqual(31)
    scale.ticks.forEach((v) => expect(Number.isInteger(v)).toBe(true))
    // three equal thirds of 0..31 would label 10.33/20.67 — the nice step
    // must come from the 1/2/5 ladder instead.
    expect(scale.ticks.length).toBeGreaterThanOrEqual(2)
  })

  it('keeps the zero baseline for non-negative data (bars start at 0)', () => {
    expect(computeAxisScale([12, 5, 31]).min).toBe(0)
  })

  it('legitimately fractional measures (avg/rate) keep decimals, nice-stepped and noise-free', () => {
    const scale = computeAxisScale([0.5, 0.25])
    expect(scale.max).toBeGreaterThanOrEqual(0.5)
    expect(scale.ticks.length).toBeGreaterThanOrEqual(2)
    // labels must print cleanly (0.2, not 0.19999999999999998)
    scale.ticks.forEach((v) => expect(String(v)).toMatch(/^-?\d+(\.\d{1,2})?$/))
  })

  it('ticks cover the data range and are monotonic from min to max', () => {
    const scale = computeAxisScale([12, 5, 31])
    expect(scale.ticks[0]).toBe(scale.min)
    expect(scale.ticks[scale.ticks.length - 1]).toBe(scale.max)
    for (let i = 1; i < scale.ticks.length; i++) {
      expect(scale.ticks[i]).toBeGreaterThan(scale.ticks[i - 1])
    }
  })

  it('flat data never collapses the axis (single repeated value still gets a span)', () => {
    const scale = computeAxisScale([7, 7, 7])
    expect(scale.max).toBeGreaterThan(scale.min)
    expect(scale.ticks.length).toBeGreaterThanOrEqual(2)
  })

  it('nulls (gaps) are skipped from bounds; all-null input falls back to 0..1', () => {
    expect(computeAxisScale([null, 4, null]).max).toBeGreaterThanOrEqual(4)
    const empty = computeAxisScale([null, null])
    expect(empty.min).toBe(0)
    expect(empty.max).toBe(1)
  })

  it('negative measures extend the floor without losing integer niceness', () => {
    const scale = computeAxisScale([-3, 12])
    expect(scale.min).toBeLessThanOrEqual(-3)
    scale.ticks.forEach((v) => expect(Number.isInteger(v)).toBe(true))
  })
})
