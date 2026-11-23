/**
 * Dialog scale tiers contract .
 *
 * Dialog width rides a single theme-owned four-tier ladder
 * (sm=480 / md=720 / lg=1080 / xl=min(1440px, 92vw)), the dialog shell is
 * capped at 85vh with its body scrolling inside, and pages pick a tier
 * class instead of a page-level width prop. First page converted to the
 * ladder is the step editor (StepTree.vue, xl tier — user-named lead).
 *
 * Hard assertions only (no conditional wrappers): the theme block and the
 * lead page are both owned by this package, so a miss is a real regression.
 */
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const SRC = new URL('../../src', import.meta.url).pathname
const THEME = readFileSync(join(SRC, 'styles/theme.scss'), 'utf-8')
const STEP_TREE = readFileSync(join(SRC, 'components/StepTree.vue'), 'utf-8')

function ruleBody(src: string, selector: RegExp): string {
  const m = src.match(selector)
  return m ? m[0] : ''
}

describe('dialog scale tiers (theme single source)', () => {
  it.each([
    ['sm', '480px'],
    ['md', '720px'],
    ['lg', '1080px'],
    ['xl', 'min(1440px, 92vw)'],
  ])('tier %s widths exactly %s', (cls, width) => {
    const body = ruleBody(THEME, new RegExp(`\\.tp-dialog--${cls}\\s*\\{[^}]*\\}`))
    expect(body).toContain(`width: ${width}`)
  })

  it('dialog shell caps at 85vh (content scrolls inside the body)', () => {
    const body = ruleBody(THEME, /\.el-dialog\s*\{[^}]*\}/)
    expect(body).toContain('max-height: 85vh')
    expect(THEME).not.toMatch(/max-height:\s*80vh/)
  })

  it('tier classes anchor on the dialog shell so they beat the component default', () => {
    expect(THEME).toMatch(/\.el-dialog\.tp-dialog--(sm|md|lg|xl)\s*\{/)
  })
})

describe('step editor rides the xl tier (user-named lead page)', () => {
  const openTag = ruleBody(STEP_TREE, /<el-dialog\b[\s\S]*?>/)
  it('opens with the xl tier class', () => {
    expect(openTag).toContain('tp-dialog--xl')
  })
  it('carries no page-level width prop anymore', () => {
    expect(openTag).not.toMatch(/\bwidth="[^"]*"/)
  })
})
