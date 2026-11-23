/**
 * Circle-selection single source contract .
 *
 * Stage0 scenario previews are draft rows; the selection panel turns the
 * human circle-selection into the final submit items: N selected scenarios
 * become exactly N tasks (the N→N acceptance anchor of the row contract),
 * a row enumerated under the switch-off shape keeps its legacy one-line item
 * with zero drift, and a row whose scenarios are all deselected produces
 * nothing (the gate decides, we never auto-revive unselected work).
 */
import { describe, expect, it } from 'vitest'
import {
  DEFAULT_SCENARIO_LABELS,
  buildPreviewRunPlan,
  defaultSelectedKeys,
  expandSelectedItems,
  newPreviewBatchId,
  requirementLinksByRowId,
  scenarioSelectionKey,
  type ScenarioPreviewRow,
} from '@/utils/aiScenarioSelection'

function row(over: Partial<ScenarioPreviewRow> & { id: number }): ScenarioPreviewRow {
  return {
    batchId: 1,
    seq: 0,
    projectId: 7,
    requirementText: '登录支持短信验证码',
    priority: 0,
    expansionEnabled: true,
    scenarios: [
      { kind: 'main-flow', title: '正常登录', anchor: '短信验证码', note: null },
      { kind: 'counter-example', title: '验证码过期', anchor: '过期', note: '超时 5 分钟' },
    ],
    residualClaims: [],
    roundsUsed: 2,
    modelCalls: 4,
    converged: true,
    ...over,
  }
}

describe('stage0 scenario circle selection', () => {
  it('hands out a session-unique positive preview batch id', () => {
    const first = newPreviewBatchId()
    const second = newPreviewBatchId()
    expect(Number.isSafeInteger(first)).toBe(true)
    expect(first).toBeGreaterThan(0)
    expect(second).toBeGreaterThanOrEqual(first)
  })

  it('selects every enumerated scenario by default', () => {
    const rows = [row({ id: 10 }), row({ id: 11, seq: 1, scenarios: [] })]
    const keys = defaultSelectedKeys(rows)
    expect(keys.has(scenarioSelectionKey(10, 0))).toBe(true)
    expect(keys.has(scenarioSelectionKey(10, 1))).toBe(true)
    expect([...keys]).toHaveLength(2)
  })

  it('expands N selected scenarios into exactly N submit items with the scenario block appended', () => {
    const rowsFixed = [
      row({ id: 10 }),
      row({
        id: 11,
        seq: 1,
        requirementText: '支付退款',
        scenarios: [{ kind: 'boundary', title: '金额为 0', anchor: '0 元', note: null }],
      }),
    ]
    const selection = defaultSelectedKeys(rowsFixed)
    // deselect the counter-example of the first row: 1 of row 10 + 1 of row 11 remain
    selection.delete(scenarioSelectionKey(10, 1))
    const items = expandSelectedItems(rowsFixed, selection, DEFAULT_SCENARIO_LABELS)
    expect(items).toHaveLength(2)
    expect(items[0].text).toContain('登录支持短信验证码')
    expect(items[0].text).toContain('正常登录')
    expect(items[0].text).toContain('短信验证码')
    expect(items[1].text).toContain('金额为 0')
    expect(items[0].text).not.toContain('验证码过期')
  })

  it('keeps the legacy one-item-per-line shape when expansion is off (zero drift)', () => {
    const rows = [row({ id: 12, expansionEnabled: false })]
    const items = expandSelectedItems(rows, new Set(), DEFAULT_SCENARIO_LABELS)
    expect(items).toHaveLength(1)
    expect(items[0].text).toBe('登录支持短信验证码')
  })

  it('skips a row whose scenarios are all deselected and falls back when none were enumerated', () => {
    const deselected = [row({ id: 13 })]
    expect(expandSelectedItems(deselected, new Set(), DEFAULT_SCENARIO_LABELS)).toHaveLength(0)
    const emptyList = [row({ id: 14, scenarios: [] })]
    const fallback = expandSelectedItems(emptyList, defaultSelectedKeys(emptyList), DEFAULT_SCENARIO_LABELS)
    expect(fallback).toHaveLength(1)
    expect(fallback[0].text).toBe('登录支持短信验证码')
  })

  it('carries the requirement-point link onto every expanded scenario item', () => {
    const rows = [row({ id: 15 })]
    const links = new Map<number, number>([[15, 88]])
    const items = expandSelectedItems(rows, defaultSelectedKeys(rows), DEFAULT_SCENARIO_LABELS, links)
    expect(items).toHaveLength(2)
    expect(items[0].requirementId).toBe(88)
    expect(items[1].requirementId).toBe(88)
  })
})

describe('preview run plan (circle-selection step, one POST per filled row)', () => {
  it('drops blank rows, keeps fill order and numbers seq from 0 consecutively', () => {
    const plan = buildPreviewRunPlan(
      [{ text: '  登录支持短信验证码  ' }, { text: '   ' }, { text: '金额为 0 时拦截' }],
      { batchId: 1794000000000, projectId: 7, priorityOf: () => 1 },
    )
    expect(plan).toHaveLength(2)
    expect(plan.map((step) => step.seq)).toEqual([0, 1])
    expect(plan[0].requirementText).toBe('登录支持短信验证码')
    expect(plan[0].batchId).toBe(1794000000000)
    expect(plan[0].projectId).toBe(7)
    expect(plan[1].priority).toBe(1)
  })

  it('resolves priority per row so P0 lines enumerate counter-examples and boundaries', () => {
    const plan = buildPreviewRunPlan(
      [{ text: 'A', requirementId: 41 }, { text: 'B' }],
      {
        batchId: 1,
        projectId: 7,
        priorityOf: (r) => (r.requirementId === 41 ? 0 : 1),
      },
    )
    expect(plan[0].priority).toBe(0)
    expect(plan[1].priority).toBe(1)
  })
})

describe('requirement links read back onto preview rows (seq alignment)', () => {
  it('maps every preview row id to the requirement point of the source row', () => {
    const links = requirementLinksByRowId(
      [{ text: 'A', requirementId: 88 }, { text: 'B' }],
      [
        { id: 501, seq: 0 },
        { id: 502, seq: 1 },
      ],
    )
    expect(links.get(501)).toBe(88)
    expect(links.has(502)).toBe(false)
  })

  it('drops preview rows whose seq has no source row (server list is the truth)', () => {
    const links = requirementLinksByRowId(
      [{ text: 'A', requirementId: 88 }],
      [{ id: 501, seq: 0 }],
    )
    expect(links.size).toBe(1)
    expect(links.get(501)).toBe(88)
    const orphan = requirementLinksByRowId([{ text: 'A', requirementId: 88 }], [{ id: 502, seq: 9 }])
    expect(orphan.size).toBe(0)
  })
})
