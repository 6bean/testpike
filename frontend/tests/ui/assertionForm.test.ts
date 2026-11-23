
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { ASSERTION_TYPE_META, validateAssertionRow } from '@/utils/assertionForm'

const here = dirname(fileURLToPath(import.meta.url))
const shared = JSON.parse(
  readFileSync(resolve(here, '../../../shared/assertion_form_cases.json'), 'utf-8'),
)

describe('assertionForm：正向断言行全部放行（与 core 保存闸同口径）', () => {
  for (const c of shared.positives) {
    it(`放行 ${JSON.stringify(c.row)}`, () => {
      expect(validateAssertionRow(c.row, c.protocol ?? 'http')).toBe(null)
    })
  }
})

describe('assertionForm：负向断言行逐条人话点名（判定文案单源直取，禁二套）', () => {
  for (const c of shared.negatives) {
    it(`拒绝 ${JSON.stringify(c.row)}（${c.why}）`, () => {
      const v = validateAssertionRow(c.row, c.protocol ?? 'http')
      expect(typeof v, '应给出违例人话文案').toBe('string')
      for (const anchor of c.errorIncludes) {
        expect(v as string, `违例文案应含「${anchor}」`).toContain(anchor)
      }
    })
  }
})

describe('assertionForm：WS2 表单元数据（下拉+灰字示例，与 core 保存闸同口径）', () => {
  it('type 下拉=六枚举（含 schema 结构校验），禁自由表达式与脚本断言', () => {
    expect(Object.keys(ASSERTION_TYPE_META).sort()).toEqual(
      ['body', 'duration', 'extract', 'header', 'schema', 'status',
        'ws.frequency', 'ws.order', 'ws.receive'],
    )
  })
  it('每型带灰字示例与 op 候选（status/extract/schema 无 op）', () => {
    for (const [type, meta] of Object.entries(ASSERTION_TYPE_META)) {
      expect(meta.example, `${type} 缺灰字示例`).not.toBe('')
      if (type === 'status' || type === 'extract' || type === 'schema'
        || type === 'ws.order' || type === 'ws.frequency') {
        expect(meta.ops).toEqual([])
      } else {
        expect(meta.ops.length, `${type} 缺 op 候选`).toBeGreaterThan(0)
      }
    }
  })
})
