
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it, expect } from 'vitest'
import { DEFAULT_ROW_HEIGHT } from '../../src/composables/useVirtualRows'

const SRC = fileURLToPath(new URL('../../src', import.meta.url))
const THEME = readFileSync(join(SRC, 'styles/theme.scss'), 'utf-8')

function walk(dir: string, out: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name)
    if (statSync(p).isDirectory()) walk(p, out)
    else if (/\.(vue|ts|scss)$/.test(name)) out.push(p)
  }
  return out
}


function styleRegion(file: string): string {
  const c = readFileSync(file, 'utf-8')
  if (!file.endsWith('.vue')) return c
  const i = c.indexOf('<style')
  return i > 0 ? c.slice(i) : ''
}

describe('T338 行高 40px 单源契约', () => {
  it('theme.scss :root 定义 --ds-table-row-height: 40px（单源唯一定义位）', () => {
    const defs = THEME.match(/--ds-table-row-height\s*:\s*[^;]+;/g) ?? []
    expect(defs.length).toBe(1)
    expect(defs[0].replace(/\s+/g, '')).toBe('--ds-table-row-height:40px;')
  })

  it('全局行高覆盖层存在且引用 token（calc 合成，禁再写面值=禁第二套）', () => {
    const m = THEME.match(/\.el-table\s+td\.el-table__cell[^{]*\{[^}]*\}/)
    expect(m, 'theme.scss 缺行高全局覆盖规则').toBeTruthy()
    const rule = m![0].replace(/\s+/g, '')
    expect(rule).toContain('padding:calc((var(--ds-table-row-height)')
    expect(rule).toContain('!important')
  })

  it('全库 token 定义位唯一（theme.scss 之外禁再定义 --ds-table-row-height）', () => {
    const offenders: string[] = []
    for (const f of walk(SRC)) {
      const region = styleRegion(f)
      if (/--ds-table-row-height\s*:/.test(region) && !f.endsWith('styles/theme.scss')) {
        offenders.push(f)
      }
    }
    expect(offenders).toEqual([])
  })

  it('幻影占位行 padding 归零保护仍在（spacer 高度纯由内联 __vspacer 驱动）', () => {
    expect(/\.el-table\s+\.virtual-spacer-row\s*>\s*td\.el-table__cell\s*\{[^}]*padding:\s*0\s*!important/.test(THEME))
      .toBe(true)
  })

  it('DEFAULT_ROW_HEIGHT 与 token 面值一致（40，改一边必露另一面）', () => {
    const tokenPx = Number(THEME.match(/--ds-table-row-height\s*:\s*(\d+)px/)?.[1])
    expect(tokenPx).toBe(40)
    expect(DEFAULT_ROW_HEIGHT).toBe(tokenPx)
  })

  it('组件样式区零行高散写：禁裸 height: 40px 面值（域外白名单见文件头注记）', () => {
    const offenders: string[] = []
    for (const f of walk(SRC)) {
      const region = styleRegion(f)
      const hits = region.match(/(^|\n)\s*[^{}-]height\s*:\s*40px/g)
      if (hits) offenders.push(`${f}: ${hits.map((h) => h.trim()).join(' | ')}`)
    }
    expect(offenders).toEqual([])
  })
})
