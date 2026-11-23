/**
 * Focus-ring single-source contract (R29, recurring "box inside a box" defect).
 *
 * The global `:focus-visible` outline in theme.scss also hits Element Plus
 * components' inner native inputs (`.el-select__input` of a filterable select,
 * `.el-input__inner`), drawing a second ring INSIDE the control shell — the
 * inner border users keep reporting on select boxes. The fix lives once in
 * theme.scss (suppress inner outline; the shell keeps its own focus shadow).
 *
 * Gates:
 *  1) theme.scss must contain the suppression block (single source present);
 *  2) page scoped styles must not add NEW per-page `outline: none` patches on
 *     EP inner-input selectors — existing sites are a shrinking ratchet
 *     (baseline captured live, any increase is red);
 *  3) behavioral truth (inner outline none + outer shell ring present) is
 *     asserted in the E2E focus-ring journey, not here.
 */
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const SRC = new URL('../../src', import.meta.url).pathname

function walk(dir: string, out: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name)
    if (statSync(p).isDirectory()) {
      if (name === 'node_modules') continue
      walk(p, out)
    } else if (name.endsWith('.vue') || name.endsWith('.scss') || name.endsWith('.css')) {
      out.push(p)
    }
  }
  return out
}

const theme = readFileSync(join(SRC, 'styles/theme.scss'), 'utf-8')

describe('R29 焦点环单源（内层框中框根治面）', () => {
  it('theme.scss 承载内层 outline 压制块（单源在位）', () => {
    expect(theme).toMatch(/\.el-select__input:focus-visible[\s\S]*?outline:\s*none/)
    expect(theme).toMatch(/\.el-input__inner:focus-visible[\s\S]*?outline:\s*none/)
  })

  it('页面级 scoped 补丁只减不增（基线=存量账，新增站点=红）', () => {
    // Inner-input outline patch surface: style rules whose selector block names
    // an EP inner primitive (.el-select__input / .el-select__inner /
    // .el-input__inner) and zeroes outline — per-page patches duplicating the
    // theme-level single source. theme.scss itself is excluded (it IS the source).
    const files = walk(SRC).filter((f) => !f.endsWith('styles/theme.scss'))
    const sites: string[] = []
    for (const f of files) {
      const text = readFileSync(f, 'utf-8')
      const styleBlock = /<style[\s\S]*<\/style>/.test(text) ? text.slice(text.indexOf('<style')) : ''
      if (!styleBlock) continue
      // One rule = selector chunk since previous '}' up to next '}'
      let pos = 0
      while (pos < styleBlock.length) {
        const open = styleBlock.indexOf('{', pos)
        const close = styleBlock.indexOf('}', open)
        if (open === -1 || close === -1) break
        const selector = styleBlock.slice(pos, open)
        const body = styleBlock.slice(open, close)
        if (/\.el-(select__input|select__inner|input__inner)/.test(selector) && /outline:\s*none/.test(body)) {
          const line = styleBlock.slice(0, open).split('\n').length
          sites.push(`${f.slice(SRC.length + 1)}:${line}`)
        }
        pos = close + 1
      }
    }
    // Ratchet baseline = legacy ProjectSelect.vue patch (covered by theme.scss
    // since 2026-10-25; removal rides that page's next closing cut). Any NEW
    // site means someone patched per-page instead of fixing the single source.
    const BASELINE = 1
    expect(
      sites.length,
      `新增页面级内层 outline 补丁=违反 R29 单源纪律（应改 theme.scss）：\n${sites.join('\n')}`,
    ).toBeLessThanOrEqual(BASELINE)
  })
})
