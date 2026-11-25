/**
 * Shared side-effect disclosure line contract (WT ticket 1 close-out).
 * The "editing a published entity restarts approval" note must live in one
 * template piece consumed by both editors, never as two inline copies:
 *  - both list views import and render FormSideEffectHint with stable
 *    data-test anchors and their domain locale key;
 *  - the inline span markup and the dead scoped style are gone from both;
 *  - the locale keys stay present on both language tracks.
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const skillView = readFileSync(
  new URL('../../src/views/skill/SkillListView.vue', import.meta.url),
  'utf8',
)
const toolView = readFileSync(
  new URL('../../src/views/tool/ToolListView.vue', import.meta.url),
  'utf8',
)
const hintPiece = readFileSync(
  new URL('../../src/components/FormSideEffectHint.vue', import.meta.url),
  'utf8',
)
const zh = readFileSync(new URL('../../src/utils/locales/zh-CN.ts', import.meta.url), 'utf8')
const en = readFileSync(new URL('../../src/utils/locales/en-US.ts', import.meta.url), 'utf8')

describe('form side-effect hint single source (skill/tool editors)', () => {
  it('both editors consume the shared piece with stable anchors', () => {
    for (const view of [skillView, toolView]) {
      expect(view).toContain("import FormSideEffectHint from '@/components/FormSideEffectHint.vue'")
      expect(view).toContain('<FormSideEffectHint')
    }
    expect(skillView).toContain("test-id=\"skill-form-edit-hint\"")
    expect(skillView).toContain(":text=\"t('skill.editHint')\"")
    expect(toolView).toContain("test-id=\"tool-form-edit-hint\"")
    expect(toolView).toContain(":text=\"t('tool.editHint')\"")
  })

  it('no inline copy or dead scoped style remains in either editor', () => {
    for (const view of [skillView, toolView]) {
      expect(view).not.toContain('<span class="text-secondary edit-hint"')
      expect(view).not.toMatch(/\.edit-hint\s*\{/)
    }
  })

  it('the shared piece renders the disclosure row with a bound data-test', () => {
    expect(hintPiece).toContain('<el-form-item label=" ">')
    expect(hintPiece).toContain(':data-test="testId"')
    expect(hintPiece).toContain('form-side-effect-hint')
  })

  it('locale keys stay on both language tracks', () => {
    for (const dict of [zh, en]) {
      expect(dict).toContain('editHint:')
      // two occurrences: the SKILL editor and the tool editor
      expect(dict.split('editHint:').length - 1).toBeGreaterThanOrEqual(2)
    }
  })
})
