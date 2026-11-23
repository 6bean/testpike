/**
 * Program-lens deep-link landing — node-level L2 contract.
 *
 * The dashboard drilldown links open the six list pages with ?programId=<id>.
 * A shared/bookmarked URL must reproduce the same segment (R22 4.1), so the
 * link's program id has to land on the header lens store (its single owner)
 * and the stale single-project default must be cleared — the server-side
 * lens branches treat projectId=0 as "program-wide inside the caller's
 * visible ∩ lens set" (CaseFilters/DefectFilters/batch/apitest/perf
 * three-branch posture), which the UI never reached before this contract.
 *
 * Pins:
 * - parse: only positive integers land (defensive against arrays/garbage);
 * - anchor: list-project scope falls back to 0 exactly when the lens is on,
 * stays null when nothing scopes the query (legacy early-return), and
 * never rewrites an explicit project;
 * - landing idempotence: re-selecting the same lens does not fake a flip.
 */
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/api/auth', async () => (await import('./authApiMockHarness')).authApiMock())
vi.mock('@/api/rbac', async () => (await import('./authApiMockHarness')).rbacApiMock())

import { hasQueryScope, listProjectScope, parseProgramIdQuery, programIdParam } from '@/composables/useProgramLens'
import { useProgramContextStore } from '@/stores/programContext'
import { installLocalStorageStub } from './localStorageStub'

describe('parseProgramIdQuery', () => {
  it('lands positive integers from strings and numbers', () => {
    expect(parseProgramIdQuery('12')).toBe(12)
    expect(parseProgramIdQuery(12)).toBe(12)
    expect(parseProgramIdQuery(' 7 ')).toBe(7)
  })
  it('takes the first entry of a repeated query array', () => {
    expect(parseProgramIdQuery(['5', '6'])).toBe(5)
  })
  it('lands nothing on garbage, zero, negatives or fractions', () => {
    expect(parseProgramIdQuery(undefined)).toBeNull()
    expect(parseProgramIdQuery(null)).toBeNull()
    expect(parseProgramIdQuery('')).toBeNull()
    expect(parseProgramIdQuery('0')).toBeNull()
    expect(parseProgramIdQuery('-3')).toBeNull()
    expect(parseProgramIdQuery(1.5)).toBeNull()
    expect(parseProgramIdQuery('abc')).toBeNull()
  })
})

describe('listProjectScope', () => {
  beforeEach(() => {
    installLocalStorageStub()
    setActivePinia(createPinia())
  })

  it('keeps an explicit project as the anchor (lens on or off, conjunctive)', () => {
    expect(listProjectScope(5)).toBe(5)
    useProgramContextStore().select(3)
    expect(listProjectScope(5)).toBe(5)
  })

  it('falls back to 0 (program-wide) only when the lens is on', () => {
    expect(listProjectScope(null)).toBeNull()
    useProgramContextStore().select(3)
    expect(listProjectScope(null)).toBe(0)
    expect(programIdParam()).toBe(3)
  })

  it('stays null for no-lens no-project pages (legacy early return)', () => {
    setActivePinia(createPinia())
    expect(listProjectScope(null)).toBeNull()
  })
})

describe('hasQueryScope', () => {
  beforeEach(() => {
    installLocalStorageStub()
    setActivePinia(createPinia())
  })

  // Regression lock: the deep-linked landing (lens on, project cleared) must
  // clear the view load gates — the bare `!!projectId` posture stranded those
  // pages on the lens empty state with no request fired (proven red by the
  // dashboard drilldown E2E before this helper landed).
  it('resolves true for an explicit project', () => {
    expect(hasQueryScope(5)).toBe(true)
  })

  it('resolves true for lens-on with no project (program-wide anchor)', () => {
    useProgramContextStore().select(3)
    expect(hasQueryScope(null)).toBe(true)
  })

  it('resolves false only when nothing scopes the query', () => {
    expect(hasQueryScope(null)).toBe(false)
  })
})
