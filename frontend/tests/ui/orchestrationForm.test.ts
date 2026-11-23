/**
 * orchestration TS mirror L2 (both-ends fixture = repo-root
 * shared/orchestration_form_cases.json). Authority = common
 * ApiOrchestrationContract (Java, core save gate); this file is the UX
 * pre-gate mirror consumed by the form builder — same verdict strings,
 * no second vocabulary (red line: one shared fixture guards both sides).
 */
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { validateLoopRow, validatePreconditionRow } from '@/utils/orchestrationForm'

const here = dirname(fileURLToPath(import.meta.url))
const shared = JSON.parse(
  readFileSync(resolve(here, '../../../shared/orchestration_form_cases.json'), 'utf-8'),
)

describe('orchestrationForm：正向前置条件行全部放行（与 core 保存闸同口径）', () => {
  for (const c of shared.precondition.positives) {
    it(`放行 ${JSON.stringify(c.row)}`, () => {
      expect(validatePreconditionRow(c.row)).toBe(null)
    })
  }
})

describe('orchestrationForm：负向前置条件行逐条人话点名（判定文案单源直取，禁二套）', () => {
  for (const c of shared.precondition.negatives) {
    it(`拒绝 ${JSON.stringify(c.row)}（${c.why}）`, () => {
      const v = validatePreconditionRow(c.row)
      expect(typeof v, '应给出违例人话文案').toBe('string')
      for (const anchor of c.errorIncludes) {
        expect(v as string, `违例文案应含「${anchor}」`).toContain(anchor)
      }
    })
  }
})

describe('orchestrationForm：正向循环行全部放行', () => {
  for (const c of shared.loop.positives) {
    it(`放行 ${JSON.stringify(c.row)}`, () => {
      expect(validateLoopRow(c.row)).toBe(null)
    })
  }
})

describe('orchestrationForm：负向循环行逐条人话点名', () => {
  for (const c of shared.loop.negatives) {
    it(`拒绝 ${JSON.stringify(c.row)}（${c.why}）`, () => {
      const v = validateLoopRow(c.row)
      expect(typeof v, '应给出违例人话文案').toBe('string')
      for (const anchor of c.errorIncludes) {
        expect(v as string, `违例文案应含「${anchor}」`).toContain(anchor)
      }
    })
  }
})

describe('orchestrationForm：表单元数据与 core 枚举域一致（词表单源镜像）', () => {
  it('前置条件 field 下拉=status / var 两谱，op 候选按 field 分档', () => {
    expect(validatePreconditionRow({ field: 'code', op: 'eq', expect: 200 })).toContain('status / var')
    expect(validatePreconditionRow({ field: 'status', op: 'gt', expect: 200 })).toContain('eq / ne / in')
    expect(validatePreconditionRow({ field: 'var', op: 'gt', name: 'a', expect: 1 }))
      .toContain('exists / absent / eq / ne / matches')
  })
  it('循环模式下拉=count / until；times 上限 100 在文案中点名', () => {
    expect(validateLoopRow({ mode: 'while', times: 3 })).toContain('count / until')
    expect(validateLoopRow({ mode: 'count', times: 101 })).toContain('1~100')
  })
})
