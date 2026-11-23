/**
 * Case provenance / evidence-grade row-rule contract.
 * Locks three facts the review queue owes the server-side single source:
 *  - the api mirror carries the two server-computed fields (evidenceGrade,
 *    sourceFacet) on CaseItem, and the two quick-filter params (sourceFacet,
 *    evidenceGrade) on CaseQuery; values are exactly what core emits
 *    (facet = chat|off_agent|import|manual, grade = 0..3) — the frontend
 *    never re-derives provenance (single-source rule, no second judge);
 *  - the case list row style function joins the evidence grade into the
 *    existing low-confidence red rule in-component: a row escalates to
 *    priority human review only when confidence is low AND the grade is
 *    weak (< 2 = no evidence / off-site self-report). The legacy
 *    low-confidence highlight itself is never relaxed;
 *  - the escalated visual ships from the theme single source (no per-page
 *    scoped copy of the red-tint language).
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const apiSrc = readFileSync(new URL('../../src/api/case.ts', import.meta.url), 'utf8')
const page = readFileSync(new URL('../../src/views/case/CaseListView.vue', import.meta.url), 'utf8')
const theme = readFileSync(new URL('../../src/styles/theme.scss', import.meta.url), 'utf8')

function block(src: string, header: RegExp): string {
  const start = src.search(header)
  expect(start, `block not found: ${header}`).toBeGreaterThanOrEqual(0)
  const end = src.indexOf('\n}', start)
  expect(end, `unterminated block for ${header}`).toBeGreaterThan(start)
  return src.slice(start, end)
}

describe('case provenance row-rule contract', () => {
  it('CaseItem mirrors the two server-computed fields', () => {
    const item = block(apiSrc, /export interface CaseItem \{/)
    expect(item).toMatch(/evidenceGrade\?:\s*number\s*\|\s*null/)
    expect(item).toMatch(/sourceFacet\?:\s*string\s*\|\s*null/)
  })

  it('CaseQuery carries the two quick-filter params', () => {
    const query = block(apiSrc, /export interface CaseQuery \{/)
    expect(query).toMatch(/sourceFacet\?:\s*string/)
    expect(query).toMatch(/evidenceGrade\?:\s*number/)
  })

  it('row style function joins the grade without relaxing the low-confidence rule', () => {
    const fn = block(page, /function rowClassName\(\{ row \}: \{ row: CaseItem \}\): string \{/)
    // legacy protection kept verbatim
    expect(fn).toContain('confidence === 3')
    expect(fn).toContain('low-confidence-row')
    // grade-weak escalation rides on top of the existing red rule; since the
    // quick-filter segment the threshold itself is single-sourced in the api
    // module (isWeakEvidence), shared with the row badge, so the row rule no
    // longer restates "evidenceGrade < 2" inline — assert the shared call.
    expect(fn).toContain('isWeakEvidence(row)')
    expect(fn).toContain('low-evidence-row')
  })

  it('escalated row visual ships from the theme single source', () => {
    expect(theme).toMatch(/\.el-table \.low-confidence-row:where\(\.low-evidence-row\) > td\.el-table__cell/)
    expect(theme).toMatch(/--tp-danger-weak-emph/)
  })
})
