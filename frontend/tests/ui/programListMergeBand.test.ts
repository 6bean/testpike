/**
 * Action-band and page-shell layout contract for the program set console
 * page (R21 same-band merge + R23 single-source page name + R30 exemption).
 *
 * The page groups projects into programs, so it is a governance page with
 * no project segment: it deliberately does not consume useProjectPageContext
 * (R30 exemption posture, same shape as the deleted-approval leaf). With no
 * row-level batch bar and only two filter condition controls (<= the 4
 * threshold), the page-level variant action (create) merges into the
 * .filter-bar-actions trailing slot ahead of the view-state action
 * (ColumnSettings), and the emptied page-header row is removed as a whole.
 *
 * Disk truth = the ProgramListView template source (same posture as
 * tagMergeBand / caseListActionBands readers, no memory lists). The
 * data-test anchors below are the future E2E handles and must stay stable.
 */
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const SRC = join(__dirname, '..', '..', 'src')
const pageSrc = readFileSync(join(SRC, 'views', 'program', 'ProgramListView.vue'), 'utf8')

/** Text of the template block (up to the first <script / <style boundary). */
function templateOf(src: string): string {
  const start = src.indexOf('<template>')
  expect(start, 'ProgramListView must carry a template block').toBeGreaterThan(-1)
  let right = src.indexOf('<script', start)
  const styleAt = src.indexOf('<style', start)
  if (styleAt > start && (right < 0 || styleAt < right)) right = styleAt
  return src.slice(start, right < 0 ? src.length : right)
}

/** Inner text of every <div class="...cls..."> block via div pairing. */
function divRegion(src: string, cls: string): string[] {
  const out: string[] = []
  const open = new RegExp(`<div[^>]*class="[^"]*\\b${cls}\\b[^"]*"[^>]*>`, 'g')
  let m: RegExpExecArray | null
  while ((m = open.exec(src)) !== null) {
    const start = m.index + m[0].length
    let depth = 1
    let i = start
    const tok = /<div(?![\w-])|<\/div>/g
    tok.lastIndex = i
    let t: RegExpExecArray | null
    while (depth > 0 && (t = tok.exec(src)) !== null) {
      depth += t[0].startsWith('<div') ? 1 : -1
      i = t.index + t[0].length
    }
    out.push(src.slice(start, i))
  }
  return out
}

describe('program list page bands and shell (R21 merge / R23 / R30 exemption)', () => {
  const tpl = templateOf(pageSrc)

  it('carries no page-header shell row and no page-name line in the card body', () => {
    expect(tpl, 'header row must be removed as a whole shell (R21 merge)').not.toMatch(
      /class="[^"]*\bpage-header\b/,
    )
    expect(tpl, 'header-actions container must be removed with the row').not.toMatch(
      /class="[^"]*\bheader-actions\b/,
    )
    expect(tpl, 'page name is carried solely by the global top bar (R23)').not.toMatch(/<h2\b/)
  })

  it('merges the create entry into the filter trailing slot ahead of column settings', () => {
    const slots = divRegion(tpl, 'filter-bar-actions')
    expect(slots.length, 'exactly one trailing slot').toBe(1)
    const slot = slots[0]
    const createAt = slot.indexOf('data-test="program-create-btn"')
    const colSetAt = slot.indexOf('<ColumnSettings')
    expect(createAt, 'create button rides the trailing slot').toBeGreaterThan(-1)
    expect(colSetAt, 'view-state action shares the slot').toBeGreaterThan(-1)
    expect(createAt, 'page-level variant action stays before view-state action').toBeLessThan(
      colSetAt,
    )
  })

  it('is instant-filter: no query-submit button anywhere in the band (FB-2)', () => {
    const slot = divRegion(tpl, 'filter-bar-actions')[0] ?? ''
    expect(slot).not.toMatch(/data-test="program-(search|reset)"/)
    expect(tpl, 'no submit button: keyword and state filter react on change').not.toMatch(
      /program-submit/,
    )
  })

  it('merge precondition holds: at most four condition controls in the band', () => {
    const condition = divRegion(tpl, 'filter-bar-condition').join('\n')
    const controls = condition.match(/<el-(radio-group|input|select|date-picker)\b/g) ?? []
    expect(controls.length, 'condition control count drives the merge-band rule').toBeLessThanOrEqual(4)
  })

  it('empty state guides the first create with title, hint and action', () => {
    expect(tpl).toContain('<EmptyState')
    for (const prop of [':title=', ':hint=', ':action-text=', '@action=']) {
      expect(tpl, `empty state carries ${prop}`).toMatch(new RegExp(`EmptyState[\\s\\S]{0,400}?${prop}`))
    }
  })

  it('keeps the R30 exemption: governance page does not consume the project slice context', () => {
    // the template comment documents the exemption; only the wiring surface
    // (import + call) would mean an actual segment consumption
    expect(pageSrc, 'no import of the project-slice composable').not.toMatch(
      /import\s+\{[^}]*\buseProjectPageContext\b[^}]*\}\s+from/,
    )
    expect(pageSrc, 'no call of the project-slice composable').not.toMatch(
      /useProjectPageContext\(/,
    )
  })

  it('every referenced program.* i18n key is defined in the zh and en locales', () => {
    const keys = [...new Set([...pageSrc.matchAll(/\bt\('program\.([A-Za-z0-9_]+)'/g)].map((m) => m[1]))]
    expect(keys.length, 'page must reference i18n keys').toBeGreaterThan(10)
    for (const locale of ['zh-CN', 'en-US']) {
      const src = readFileSync(join(SRC, 'utils', 'locales', `${locale}.ts`), 'utf8')
      const block = programBlock(src)
      for (const key of keys) {
        expect(block, `${locale}: program.${key} defined`).toMatch(
          new RegExp(`^\\s{4}(?:'${key}'|"${key}"|${key}):`, 'm'),
        )
      }
    }
  })

  it('keeps the row-action and dialog anchors that the E2E chains will target', () => {
    for (const anchor of [
      'program-list-page',
      'program-create-btn',
      'program-projects-btn',
      'program-members-btn',
      'program-edit-btn',
      'program-delete-btn',
      'program-create-dialog',
      'program-form-name',
    ]) {
      expect(pageSrc, `anchor ${anchor} present`).toContain(anchor)
    }
  })
})

/** Source text of the top-level `program: { ... }` object in a locale file. */
function programBlock(src: string): string {
  const at = src.search(/\n\s{2}program:\s*\{/)
  expect(at, 'locale must carry a program section').toBeGreaterThan(-1)
  let depth = 0
  let i = src.indexOf('{', at)
  const start = i
  for (; i < src.length; i++) {
    if (src[i] === '{') depth++
    else if (src[i] === '}') {
      depth--
      if (depth === 0) return src.slice(start + 1, i)
    }
  }
  throw new Error('unbalanced program section')
}
