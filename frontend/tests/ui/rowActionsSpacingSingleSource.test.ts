/**
 * Row-action column spacing single-source contract.
 *
 * Vision audit follow-up : the delete
 * link sat roughly 8px further right on some rows of the operation column.
 * Root cause: Element Plus ships a global `.el-button + .el-button {
 * margin-left: 12px }`; inside the `.row-actions` inline-flex row that margin
 * stacks onto the 4px flex gap (16px total) for button-to-button neighbours,
 * while a button followed by the "more" dropdown keeps the plain 4px gap.
 * The mixed rhythm is what made identical actions land at different x
 * positions row by row.
 *
 * Fix lives once in RowActions.vue: adjacent-button margin is suppressed
 * there so the flex `gap` is the only spacing channel. Gates:
 * 1) RowActions.vue declares the suppression rule for the EP adjacency
 * margin inside `.row-actions`;
 * 2) the rule zeroes the margin (not a page-local magic number);
 * 3) pages must not re-declare their own `.el-button + .el-button` margin
 * overrides inside row-action scoped blocks — component is the single
 * source (sites outside the component are a shrinking ratchet).
 */
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const SRC = new URL('../../src', import.meta.url).pathname
const COMPONENT = join(SRC, 'components', 'RowActions.vue')

function walk(dir: string, out: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    if (name === 'node_modules') continue
    const p = join(dir, name)
    if (statSync(p).isDirectory()) walk(p, out)
    else if (name.endsWith('.vue')) out.push(p)
  }
  return out
}

describe('row-actions spacing single source', () => {
  const source = readFileSync(COMPONENT, 'utf8')

  it('suppresses the Element Plus adjacency margin inside the component', () => {
    // The rule must target the EP sibling-button margin and set it to zero.
    const rule = /\.row-actions[^{]*\.el-button\s*\+\s*\.el-button\s*\{[^}]*margin-left:\s*0[^}]*\}/
    expect(rule.test(source)).toBe(true)
  })

  it('keeps flex gap as the only spacing channel', () => {
    expect(source).toMatch(/\.row-actions\s*\{[^}]*gap:\s*4px/)
  })

  it('no row-action page re-declares an adjacency button margin (ratchet)', () => {
    // Scoped to the row-action family on purpose: pages like the standalone
    // password form legitimately zero the same EP margin for their own
    // vertical button stacks. A page that renders RowActions and still
    // patches button adjacency is duplicating what the component owns.
    const offenders: string[] = []
    for (const file of walk(join(SRC, 'views'))) {
      const text = readFileSync(file, 'utf8')
      if (!text.includes('RowActions')) continue
      if (/\.el-button\s*\+\s*\.el-button\s*\{[^}]*margin/.test(text)) {
        offenders.push(file.replace(SRC, 'src'))
      }
    }
    expect(offenders).toEqual([])
  })
})
