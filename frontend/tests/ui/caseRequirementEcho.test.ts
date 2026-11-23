/**
 * the traceability row lives in docs/16).
 *
 * Evidence being defended: association rows exist in tp_case_requirement but
 * the block used to hide the requirement number and offered no way to reach
 * the requirement ledger. Invariants (pure logic, node env per vitest.config.ts):
 * - an AI-generated case labels its linked requirement rows as
 * auto-linked by the generation flow (case.source === 1 is the only
 * zero-write signal; manual links on generated cases over-claim and are
 * booked as a follow-up ticket, never silently dropped);
 * - a case from any other channel never shows the provenance tag;
 * - the deep link consumed by the requirement ledger page rides the
 * existing ids filter channel (single source, no second URL state);
 * - the ledger page keeps reading the same ids parameter shape.
 */
import { describe, expect, it } from 'vitest'
import {
  CASE_SOURCE_AI_GENERATED,
  isAutoLinkedRequirement,
  requirementLedgerDeepLink,
} from '@/utils/caseRequirementEcho'

describe('isAutoLinkedRequirement provenance', () => {
  it('tags requirement rows of an AI-generated case as generation auto-link', () => {
    expect(CASE_SOURCE_AI_GENERATED).toBe(1)
    expect(isAutoLinkedRequirement(1)).toBe(true)
  })

  it('never tags rows of template/import/cloned/manual cases', () => {
    for (const source of [0, 2, 3, 4, null, undefined]) {
      expect(isAutoLinkedRequirement(source as number | null | undefined)).toBe(false)
    }
  })
})

describe('requirementLedgerDeepLink rides the ids filter channel', () => {
  it('builds the ledger route with the ids parameter holding the requirement id', () => {
    const link = requirementLedgerDeepLink(23523, 12)
    expect(link.path).toBe('/requirements')
    expect(link.query).toEqual({ ids: '23523', projectId: '12' })
  })

  it('carries the case project so the landing slice never depends on the header', () => {
    // E2E-reproved: ids alone + an unselected (or foreign) header project
    // renders a misleading empty ledger although the row exists.
    expect(requirementLedgerDeepLink(165, 9)?.query.projectId).toBe('9')
  })

  it('falls back to the header context when the project id is absent, no fabrication', () => {
    expect(requirementLedgerDeepLink(165, null)?.query).toEqual({ ids: '165' })
    expect(requirementLedgerDeepLink(165, 0)?.query).toEqual({ ids: '165' })
    expect(requirementLedgerDeepLink(165)?.query).toEqual({ ids: '165' })
  })

  it('fails closed for non-positive ids instead of emitting a dead link', () => {
    expect(requirementLedgerDeepLink(0, 9)).toBeNull()
    expect(requirementLedgerDeepLink(-7, 9)).toBeNull()
    expect(requirementLedgerDeepLink(Number.NaN, 9)).toBeNull()
  })
})
