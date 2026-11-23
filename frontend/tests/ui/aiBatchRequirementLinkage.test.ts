/**
 * Batch generation rows must keep the requirement-point link (ruling:
 * docs/change-eval BA batch section 3; the traceability row lives in docs/16).
 *
 * Invariants under test (pure logic, node env per vitest.config.ts):
 *  - filling rows from the case-gap list carries each requirement point id;
 *  - the gap text stays "title + acceptance criteria" (same channel as manual rows);
 *  - editing a row's text never clears its carried id;
 *  - submit payload is the dual form: structured items (winner server-side) plus
 *    the legacy plain-text requirements mirror for one compatibility cycle;
 *  - rows without a link serialize with no requirementId at all.
 */
import { describe, expect, it } from 'vitest'
import {
  AI_GENERATE_LINK_MAX_IDS,
  buildAiGenerateDeepLink,
  buildBatchItems,
  buildBatchPayload,
  parseRequirementIdsParam,
  rowsFromGapItems,
  type BatchRow,
} from '@/utils/aiBatchLinkage'

describe('rowsFromGapItems keeps the requirement linkage', () => {
  it('carries the requirement point id and joins acceptance criteria text', () => {
    const rows = rowsFromGapItems(
      [
        { id: 11, title: '登录功能', acceptanceCriteria: '正确账号密码登录成功' },
        { id: 12, title: '导出功能', acceptanceCriteria: null },
      ],
      '验收标准：',
    )
    expect(rows).toHaveLength(2)
    expect(rows[0].requirementId).toBe(11)
    expect(rows[0].text).toBe('登录功能\n验收标准：正确账号密码登录成功')
    expect(rows[1].requirementId).toBe(12)
    expect(rows[1].text).toBe('导出功能')
  })
})

describe('buildBatchItems normalizes rows for submit', () => {
  it('drops blank rows, trims text and preserves carried ids', () => {
    const rows: BatchRow[] = [
      { text: '  带链接的行  ', requirementId: 7 },
      { text: '   ' },
      { text: '无链接的行' },
    ]
    // unlinked rows serialize without a requirementId key at all
    expect(buildBatchItems(rows)).toEqual([
      { text: '带链接的行', requirementId: 7 },
      { text: '无链接的行' },
    ])
  })

  it('editing a row text afterwards does not clear its requirement id', () => {
    const rows: BatchRow[] = [{ text: '原标题', requirementId: 9 }]
    rows[0].text = '改写后的测试目标'
    const items = buildBatchItems(rows)
    expect(items[0].requirementId).toBe(9)
    expect(items[0].text).toBe('改写后的测试目标')
  })
})

describe('buildBatchPayload emits the dual form', () => {
  it('sends items plus the legacy requirements mirror', () => {
    const rows: BatchRow[] = [
      { text: '第一条', requirementId: 3 },
      { text: '第二条' },
    ]
    const payload = buildBatchPayload(42, rows)
    expect(payload.projectId).toBe(42)
    expect(payload.requirements).toEqual(['第一条', '第二条'])
    expect(payload.items).toEqual([
      { text: '第一条', requirementId: 3 },
      { text: '第二条' },
    ])
  })

  it('empty rows yield empty lists (caller gates before submit)', () => {
    const payload = buildBatchPayload(1, [{ text: '' }])
    expect(payload.items).toEqual([])
    expect(payload.requirements).toEqual([])
  })
})

describe('buildAiGenerateDeepLink carries the requirement id allow-list', () => {
  it('joins positive ids in order and rides the project slice query', () => {
    const link = buildAiGenerateDeepLink(7, [3, 1, 2])
    expect(link.path).toBe('/ai/generate')
    expect(link.query).toEqual({ projectId: '7', requirementIds: '3,1,2' })
  })
  it('drops non-positive ids and caps the list at the server-side limit', () => {
    const link = buildAiGenerateDeepLink(9, [5, 0, -3, 6])
    expect(link.query.requirementIds).toBe('5,6')
    const many = Array.from({ length: AI_GENERATE_LINK_MAX_IDS + 100 }, (_, i) => i + 1)
    const capped = buildAiGenerateDeepLink(9, many)
    expect(capped.query.requirementIds.split(',')).toHaveLength(AI_GENERATE_LINK_MAX_IDS)
  })
})

describe('parseRequirementIdsParam reads the comma list honestly', () => {
  it('keeps positive integers and drops junk entries', () => {
    expect(parseRequirementIdsParam('1, 2, x, 0, -4, 3')).toEqual([1, 2, 3])
  })
  it('accepts a bare number and treats missing/empty values as none', () => {
    expect(parseRequirementIdsParam(12)).toEqual([12])
    expect(parseRequirementIdsParam(undefined)).toEqual([])
    expect(parseRequirementIdsParam('')).toEqual([])
  })
})
