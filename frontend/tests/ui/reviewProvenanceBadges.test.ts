/**
 * Review workbench provenance badges contract .
 *
 * The review queue is where a human decides whether a script may pass, so the
 * reviewer must see the SAME two server-computed dimensions the case list
 * shows — producing channel (sourceFacet) and evidence grade — without any
 * second vocabulary. Locks three facts:
 * - the workbench consumes the label/type maps from the api/case single
 * source (no page-local re-derivation, R40);
 * - both badges render inside the queue row with stable data-test anchors
 * for the E2E display spectrum;
 * - the badge only appears when the server actually emitted the value
 * (null/undefined = honest absence, never a guessed default).
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const page = readFileSync(new URL('../../src/views/review/ReviewWorkbenchView.vue', import.meta.url), 'utf8')
const apiSrc = readFileSync(new URL('../../src/api/case.ts', import.meta.url), 'utf8')

describe('review workbench provenance badges contract', () => {
  it('imports the facet and grade maps from the api single source', () => {
    const importBlock = page.match(/import \{([^}]*)\} from '@\/api\/case'/s)
    expect(importBlock, 'no @/api/case import block found').not.toBeNull()
    for (const sym of ['FACET_LABEL', 'FACET_TYPE', 'EVIDENCE_GRADE_LABEL', 'EVIDENCE_GRADE_TYPE']) {
      expect(importBlock![1], `missing single-source import: ${sym}`).toContain(sym)
    }
  })

  it('renders both badges in queue rows with E2E anchors', () => {
    expect(page).toMatch(/data-test="review-facet-badge"/)
    expect(page).toMatch(/data-test="review-evidence-badge"/)
    expect(page).toMatch(/FACET_LABEL\[row\.sourceFacet\]/)
    expect(page).toMatch(/EVIDENCE_GRADE_LABEL\[row\.evidenceGrade\]/)
  })

  it('shows a badge only when the server emitted the value', () => {
    const facetTag = page.match(/<el-tag[^>]*data-test="review-facet-badge"[^>]*>/s)
    const gradeTag = page.match(/<el-tag[^>]*data-test="review-evidence-badge"[^>]*>/s)
    expect(facetTag, 'facet badge tag missing').not.toBeNull()
    expect(gradeTag, 'evidence badge tag missing').not.toBeNull()
    expect(facetTag![0]).toMatch(/v-if="row\.sourceFacet"/)
    expect(gradeTag![0]).toMatch(/row\.evidenceGrade !== undefined && row\.evidenceGrade !== null/)
  })

  it('keeps the vocabulary single-sourced (no page-local label maps)', () => {
    // The page must not define its own facet/grade dictionaries.
    expect(page).not.toMatch(/const\s+(FACET|EVIDENCE_GRADE)_(LABEL|TYPE)\s*[:=]/)
    // Sanity: the single source really exports the four symbols we import.
    for (const sym of ['CASE_FACET_VALUES', 'EVIDENCE_GRADE_VALUES']) {
      expect(apiSrc, `single source lost: ${sym}`).toContain(sym)
    }
  })
})
