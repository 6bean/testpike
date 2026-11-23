
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it, expect } from 'vitest'

const THEME = readFileSync(
  join(fileURLToPath(new URL('../../src', import.meta.url)), 'styles/theme.scss'),
  'utf-8',
)


function ruleBody(selectorPattern: RegExp, label: string): string {
  const m = THEME.match(selectorPattern)
  expect(m, `theme.scss 缺规则：${label}`).toBeTruthy()
  const block = THEME.slice((m?.index ?? 0) + m![0].length).match(/^([^}]*)/)
  expect(block, `规则后缺声明块：${label}`).toBeTruthy()
  return block![1].replace(/\s+/g, ' ')
}

describe('低置信/快评行晕染制契约', () => {
  it('成对 danger-weak token 双主题定义且面值与裁决一致', () => {
    expect(THEME).toMatch(/--tp-danger-weak:\s*rgba\(185,\s*28,\s*28,\s*\.07\)/)
    expect(THEME).toMatch(/--tp-danger-weak-solid:\s*#fbecec/)
    expect(THEME).toMatch(/--tp-danger-weak:\s*rgba\(239,\s*68,\s*68,\s*\.10\)/)
    expect(THEME).toMatch(/--tp-danger-weak-solid:\s*#3a2325/)
  })

  it('低置信行改晕染底并撤红竖条', () => {
    const body = ruleBody(
      /\.el-table \.low-confidence-row > td\.el-table__cell\s*\{/, '低置信行底')
    expect(body).toContain('var(--tp-danger-weak)')
    expect(body).not.toMatch(/box-shadow:[^;]*inset/)
  })

  it('低置信固定列合成 solid 档（防横滚透底）', () => {
    const body = ruleBody(
      /\.el-table \.low-confidence-row > td\.el-table__cell\.el-table-fixed-column--left[\s,]/,
      '低置信固定列')
    expect(body).toContain('var(--tp-danger-weak-solid)')
  })

  it('快评焦点行保留晕染底撤蓝竖条', () => {
    const body = ruleBody(
      /\.el-table \.quick-active-row > td\.el-table__cell\s*\{/, '快评焦点行底')
    expect(body).toContain('var(--tp-primary-weak)')
    expect(body).not.toMatch(/box-shadow:[^;]*inset/)
  })

  it('叠加优先级=声明序（低置信在前、焦点在后：同行兼具时焦点蓝底胜出）', () => {
    const low = THEME.indexOf('.el-table .low-confidence-row > td.el-table__cell {')
    const quick = THEME.indexOf('.el-table .quick-active-row > td.el-table__cell {')
    expect(low).toBeGreaterThan(-1)
    expect(quick).toBeGreaterThan(-1)
    expect(low, '低置信规则须声明在焦点行之前').toBeLessThan(quick)
  })
})
