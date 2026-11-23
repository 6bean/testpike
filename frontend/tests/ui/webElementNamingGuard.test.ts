/**
 * Naming unification guard (11-17 user order): the web locator asset is called
 * The legacy word stays allowed only in search synonyms (old mental model must
 * still find the page) and in non-visible identifiers.
 */
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const SRC = join(__dirname, '..', '..', 'src')
const zh = readFileSync(join(SRC, 'utils', 'locales', 'zh-CN.ts'), 'utf8')
const en = readFileSync(join(SRC, 'utils', 'locales', 'en-US.ts'), 'utf8')
const syn = readFileSync(join(SRC, 'config', 'menuSynonyms.ts'), 'utf8')

function block(src: string, start: string, end: string): string {
  const i = src.indexOf(start)
  expect(i, `missing ${start}`).toBeGreaterThan(-1)
  const j = src.indexOf(end, i)
  return src.slice(i, j > 0 ? j : i + 4000)
}

describe('页面元素库命名统一守卫', () => {
  it('zh locale 的 webElement 域与 layout 元素叶不含「账本」', () => {
    expect(block(zh, "webElement: {", "\n  },")).not.toContain('账本')
    for (const key of ['webElementLedger']) {
      for (const m of zh.matchAll(new RegExp(`${key}[^\n]*`, 'g'))) {
        expect(m[0], 'zh leaf wording drifted').not.toContain('账本')
      }
    }
    expect(zh).toContain("webElementLedger: '页面元素库'")
  })

  it('en locale 同域不含 ledger 字样且键值双轨在位', () => {
    const webEn = block(en, "webElement: {", "\n  },")
    expect(webEn).not.toMatch(/: '[^']*ledger[^']*'/i)
    expect(en).toContain("webElementLedger: 'Page Element Library'")
    // the English track must not ship the Chinese label either (leafHelp tooltip face)
    // only the shipped VALUES are the user surface; the key name (identifier) is exempt
    const values = [...en.matchAll(/webElementLedger: '([^']*)'/g)].map(m => m[1])
    expect(values.length).toBeGreaterThanOrEqual(2)
    for (const v of values) {
      expect(v).not.toMatch(/页面元素/)
      expect(v.toLowerCase()).not.toContain('ledger')
      expect(v.toLowerCase()).toContain('element librar')
    }
  })

  it('搜索同义词保留旧词=旧心智可检索，主词已换', () => {
    const line = syn.slice(syn.indexOf("'/web-elements'"))
    expect(line.slice(0, 160)).toContain('页面元素库')
    expect(line.slice(0, 160)).toContain('页面元素账本')
  })
})
