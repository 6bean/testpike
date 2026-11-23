
import { describe, expect, it } from 'vitest'
import {
  normalizePopupRuleRows,
  popupRuleStatusTag,
  RULE_STATUS_LABEL_KEY,
} from '@/utils/popupRule'

describe('appauto.ts 契约镜像闸（popup-rules 五口 ↔ admin AppautoPopupRuleController）', () => {
  const raw = Object.values(
    import.meta.glob('../../src/api/appauto.ts', { query: '?raw', import: 'default', eager: true }),
  )[0] as string

  it('learn=POST /appauto/popup-rules/learn', () => {
    expect(raw).toMatch(/url: '\/appauto\/popup-rules\/learn',\s*\n\s*method: 'post'/)
  })
  it('list=GET /appauto/popup-rules（projectId/status/limit 参数面）', () => {
    expect(raw).toMatch(/url: '\/appauto\/popup-rules',\s*\n\s*method: 'get'/)
  })
  it('confirm/reject=POST /appauto/popup-rules/{id}/confirm|reject', () => {
    expect(raw).toMatch(/url: `\/appauto\/popup-rules\/\$\{id\}\/confirm`, method: 'post'/)
    expect(raw).toMatch(/url: `\/appauto\/popup-rules\/\$\{id\}\/reject`, method: 'post'/)
  })
})

describe('normalizePopupRuleRows（读面容错，禁炸渲染链）', () => {
  it('合法数组原样保留', () => {
    const rows = normalizePopupRuleRows([
      { id: 1, projectId: 1, sourceTaskId: 9, title: 't', rule: '{}', status: 1, source: 'AI', createdAt: 'x' },
    ])
    expect(rows).toHaveLength(1)
  })
  it('null/非数组/行非对象/id 非数值=诚实剔除', () => {
    expect(normalizePopupRuleRows(null)).toEqual([])
    expect(normalizePopupRuleRows('x')).toEqual([])
    expect(normalizePopupRuleRows([null, 3, { title: 'no-id' }])).toEqual([])
  })
})

describe('popupRuleStatusTag（1待审/2入库/3驳回 状态机色型单源）', () => {
  it('三态映射 warning/success/info；未知值=info 回退原文不炸', () => {
    expect(popupRuleStatusTag(1)).toBe('warning')
    expect(popupRuleStatusTag(2)).toBe('success')
    expect(popupRuleStatusTag(3)).toBe('info')
    expect(popupRuleStatusTag(99)).toBe('info')
  })
  it('标签键三枚齐（i18n 双轨由 i18nKeys 闸兜底，此处钉键名拼写）', () => {
    expect(RULE_STATUS_LABEL_KEY[1]).toBe('appauto.popupRules.statusPending')
    expect(RULE_STATUS_LABEL_KEY[2]).toBe('appauto.popupRules.statusStored')
    expect(RULE_STATUS_LABEL_KEY[3]).toBe('appauto.popupRules.statusRejected')
  })
})
