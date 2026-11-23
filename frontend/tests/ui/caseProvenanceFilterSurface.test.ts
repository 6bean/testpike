/**
 * Case list provenance quick-filter + row-badge surface contract .
 * Locks four things the review queue owes the server-side single source:
 * - the provenance-channel / evidence-grade vocabularies and their labels ship
 * from one module (src/api/case.ts) mirroring core CaseSourceFacet — the page
 * never re-derives provenance and never hardcodes an option array;
 * - the weak-evidence test exists exactly once (isWeakEvidence) and is consumed
 * by both the row style rule and the row badge, so the highlight and the
 * badge can never drift apart;
 * - the two quick filters ride the filter bar, the list payload, the export
 * payload, the reset and the "active filter" test — list and export keep the
 * same WHERE;
 * - page state of the two new filters is recoverable from the URL (deep link +
 * refresh) and the zh/en locale key sets stay symmetric.
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const apiSrc = readFileSync(new URL('../../src/api/case.ts', import.meta.url), 'utf8')
const page = readFileSync(new URL('../../src/views/case/CaseListView.vue', import.meta.url), 'utf8')
const zh = readFileSync(new URL('../../src/utils/locales/zh-CN.ts', import.meta.url), 'utf8')
const en = readFileSync(new URL('../../src/utils/locales/en-US.ts', import.meta.url), 'utf8')

function section(src: string, header: RegExp, closer = '\n}'): string {
  const start = src.search(header)
  expect(start, `block not found: ${header}`).toBeGreaterThanOrEqual(0)
  const end = src.indexOf(closer, start)
  expect(end, `unterminated block for ${header}`).toBeGreaterThan(start)
  return src.slice(start, end)
}

/** The keys declared inside a locale sub-namespace (dict.case.<namespace>). */
function keysUnder(src: string, namespace: string): string[] {
  const start = src.indexOf(`${namespace}: {`)
  expect(start, `locale namespace not found: ${namespace}`).toBeGreaterThanOrEqual(0)
  const body = src.slice(start, src.indexOf('\n      }', start))
  return [...body.matchAll(/^ {8}(\w+):/gm)].map((m) => m[1])
}

describe('case provenance quick-filter and badge surface', () => {
  it('channel + evidence-grade vocabularies ship from the api single source', () => {
    expect(apiSrc).toMatch(/export const CASE_FACET_VALUES\s*=\s*\[[^\]]*'chat'[^\]]*'off_agent'[^\]]*'import'[^\]]*'manual'/)
    expect(apiSrc).toMatch(/export const EVIDENCE_GRADE_VALUES\s*=\s*\[[^\]]*0,[^\]]*1,[^\]]*2,[^\]]*3/)
    const facetLabel = section(apiSrc, /export const FACET_LABEL: Record<string, string> = \{/)
    for (const v of ['chat', 'off_agent', 'import', 'manual']) {
      expect(facetLabel).toContain(v)
    }
    const gradeLabel = section(apiSrc, /export const EVIDENCE_GRADE_LABEL: Record<number, string> = \{/)
    for (const g of [0, 1, 2, 3]) {
      expect(gradeLabel).toMatch(new RegExp(`get ${g}\\(\\)`))
    }
    expect(apiSrc).toMatch(/export const EVIDENCE_GRADE_TYPE/)
  })

  it('weak-evidence test exists once and is shared by row rule and badge', () => {
    const helper = section(apiSrc, /export function isWeakEvidence\([^)]*\)\s*:\s*boolean\s*\{/)
    expect(helper).toMatch(/< 2/)
    // single-source consumption in the page (no second inline threshold)
    expect(page).toContain('isWeakEvidence')
    expect(page).not.toMatch(/evidenceGrade\s*\?\?\s*0\)\s*<\s*2/)
    expect(page).toMatch(/function rowClassName[\s\S]{0,700}isWeakEvidence\(row\)/)
  })

  it('the two quick filters sit in the filter bar and iterate the single source', () => {
    const bar = section(page, /<div class="filter-bar">/, '\n      </div>')
    expect(bar).toContain('data-test="case-filter-facet"')
    expect(bar).toContain('data-test="case-filter-evidence"')
    expect(bar).toContain('filters.sourceFacet')
    expect(bar).toContain('filters.evidenceGrade')
    expect(bar).toMatch(/v-for="v in CASE_FACET_VALUES"/)
    expect(bar).toMatch(/v-for="g in EVIDENCE_GRADE_VALUES"/)
    // FB-2: the query submit button still closes the condition flow before the
    // trailing view-state slot — new controls never push it to the right edge.
    const searchIdx = bar.indexOf('data-test="case-search"')
    const trailingIdx = bar.indexOf('filter-bar-actions')
    expect(searchIdx).toBeGreaterThan(0)
    expect(trailingIdx).toBeGreaterThan(searchIdx)
  })

  it('list payload, export payload, reset and active-filter test all carry both params', () => {
    expect(page).toMatch(/sourceFacet:\s*filters\.sourceFacet/)
    expect(page).toMatch(/evidenceGrade:\s*filters\.evidenceGrade/)
    // two call sites minimum (list + export share the same WHERE)
    expect((page.match(/sourceFacet:\s*filters\.sourceFacet/g) ?? []).length).toBeGreaterThanOrEqual(2)
    expect(page).toMatch(/filters\.sourceFacet = undefined/)
    expect(page).toMatch(/filters\.evidenceGrade = undefined/)
    expect(page).toMatch(/filters\.sourceFacet !== undefined/)
  })

  it('page state of the two filters is URL-recoverable', () => {
    expect(page).toMatch(/function applyProvenanceQuery/)
    expect(page).toMatch(/function syncProvenanceQuery/)
    expect(page).toMatch(/query: \{ \.\.\.route\.query, sourceFacet/)
    expect(page).toMatch(/route\.query\.sourceFacet/)
    expect(page).toMatch(/route\.query\.evidenceGrade/)
    expect(page).toMatch(/applyProvenanceQuery\(\)/)
  })

  it('the source column renders channel + grade badges from the shared maps', () => {
    const cell = section(page, /col\.key === 'source'/, '</template>')
    expect(cell).toContain('data-test="case-facet-badge"')
    expect(cell).toContain('data-test="case-evidence-badge"')
    expect(cell).toContain('FACET_LABEL')
    expect(cell).toContain('EVIDENCE_GRADE_LABEL')
    expect(cell).toContain('isWeakEvidence')
  })

  it('zh and en locale key sets stay symmetric', () => {
    for (const src of [zh, en]) {
      expect(keysUnder(src, 'facet')).toEqual(['chat', 'offAgent', 'import', 'manual'])
      expect(keysUnder(src, 'evidence')).toEqual(['none', 'selfReport', 'attested', 'platformReproof'])
    }
    for (const key of ['createChannelAll', 'evidenceAll']) {
      expect(zh).toContain(`${key}:`)
      expect(en).toContain(`${key}:`)
    }
  })
})
