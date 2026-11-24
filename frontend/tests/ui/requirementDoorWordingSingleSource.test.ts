/**
 * Requirement-door wording and affordance single-source contract.
 *
 * Three defects the vision round scored visible on the requirement doors:
 *  - the chunk viewer title only carried the chunk sequence, never naming
 *    which requirement the door was opened for (title must point at the
 *    object, R63 family);
 *  - one concept carried two labels — the collapse header used a name built
 *    from "source text" while the neighbour row button said "in context",
 *    and the help chapter quoted the first variant (same concept, same
 *    word, wording group W);
 *  - case numbers in the link dialog rendered like plain text, hiding the
 *    fact that clicking them opens the case detail (accent emphasis,
 *    theme single source, no per-page copies).
 * Gates here assert wording, wiring and the theme rule; pixel truth is
 * re-scored by the door screenshot rig (vision trend round).
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const SRC = new URL('../../src', import.meta.url).pathname
const ROOT = new URL('../../../', import.meta.url).pathname
const zh = readFileSync(`${SRC}/utils/locales/zh-CN.ts`, 'utf-8')
const en = readFileSync(`${SRC}/utils/locales/en-US.ts`, 'utf-8')
const viewer = readFileSync(`${SRC}/components/SourceChunkViewerDialog.vue`, 'utf-8')
const list = readFileSync(`${SRC}/views/requirement/RequirementListView.vue`, 'utf-8')
const theme = readFileSync(`${SRC}/styles/theme.scss`, 'utf-8')

function localeValue(src: string, key: string): string {
  const m = src.match(new RegExp(`${key}: '([^']*)'`))
  expect(m, `locale key ${key} present`).not.toBeNull()
  return m![1]
}

describe('需求点分块门 标题点名对象', () => {
  it('带主体的标题键在中英双语都存在且同时点名主体与块序号', () => {
    for (const [name, src] of [['zh', zh], ['en', en]] as const) {
      const v = localeValue(src, 'extractSourceTitleWithSubject')
      expect(v, `${name} carries the subject placeholder`).toContain('{subject}')
      expect(v, `${name} carries the seq placeholder`).toContain('{seq}')
    }
  })
  it('查看器组件消费 subject 属性，主体缺省时回落旧标题键', () => {
    expect(viewer).toContain('subject?: string')
    expect(viewer).toContain("t('document.extractSourceTitleWithSubject'")
    expect(viewer).toContain("t('document.extractSourceTitle'")
  })
  it('需求点列表门把行标题传入主体（入口侧接线在位）', () => {
    expect(list).toContain(':subject="sourceSubject"')
    expect(list).toContain('sourceSubject.value = row.title')
  })
})

describe('上下文术语同词族（相邻原文退役面）', () => {
  it('zh 折叠头与邻块钮共用「上下文」词族', () => {
    expect(localeValue(zh, 'extractContextToggle')).toContain('上下文')
    expect(localeValue(zh, 'extractContextNeighborBtn')).toContain('上下文')
  })
  it('en 折叠头与邻块钮共用 context 词族', () => {
    expect(localeValue(en, 'extractContextToggle').toLowerCase()).toContain('context')
    expect(localeValue(en, 'extractContextNeighborBtn').toLowerCase()).toContain('context')
  })
  it('上屏载体不再出现旧词（locale 双面；装机基线含销账段）', () => {
    for (const [name, src] of [['zh-CN', zh], ['en-US', en]] as const) {
      expect(src, `${name} retired the old label`).not.toContain('相邻原文')
    }
    // The bootstrap merges historical migrations verbatim (they stay
    // untouched), so its replayed final chapter state is guarded by the
    // fix migration being present in the generated file.
    expect(readFileSync(`${ROOT}db/schema-init.sql`, 'utf-8'))
      .toContain('V652__help_context_wording_single_family.sql')
  })
})

describe('关联用例编号可辨性 主题单源', () => {
  it('theme.scss 承载 .req-case-no-link 强调规则（单源在位）', () => {
    const rule = theme.match(/\.req-case-no-link\s*\{[^}]*\}/)
    expect(rule, 'single source rule present').not.toBeNull()
    expect(rule![0]).toContain('var(--tp-primary)')
  })
  it('页面 scoped 未复制该规则（禁第二份）', () => {
    expect(list).not.toMatch(/\.req-case-no-link\s*\{/)
  })
})

describe('关联钮 动宾文案与空态指路同字面', () => {
  // The submit button names its object (wording group W: verb + object,
  // never a bare "Link Selected"), and the empty-state hint quotes the
  // button by that exact literal — a hint pointing at a label the screen
  // never shows is a dead path (R63 family).
  it('中英钮文案都点名宾语用例', () => {
    expect(localeValue(zh, 'linkSelected')).toContain('关联选中用例')
    expect(localeValue(en, 'linkSelected').toLowerCase()).toContain('link selected cases')
  })
  it('空态指路引用的钮名与钮文案同字面（双语联动）', () => {
    const zhBtn = localeValue(zh, 'linkSelected').replace('（{n}）', '')
    expect(localeValue(zh, 'linkedEmpty'), 'zh hint quotes the live button label')
      .toContain(`「${zhBtn}」`)
    expect(localeValue(en, 'linkedEmpty'), 'en hint quotes the live button label')
      .toContain('Link selected cases')
  })
})
