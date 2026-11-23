/**
 * Case list page consumes the header program lens .
 *
 * The mount assembly, store seeding and the four wiring contracts are single
 * sourced in tests/comp/lensWiringHarness.ts (R40) — this file only mocks the
 * modules CaseListView touches on mount and records the outgoing listCases
 * params. The page must forward programId on EVERY list call it issues for
 * its own segment, including the unassigned-count probe (same page, one lens
 * scope — a mixed scope would show the auto-assign entry against rows the
 * lens just hid).
 */
import { beforeEach, vi } from 'vitest'

/** Captured listCases parameter objects, in call order (main load + count probe). */
const caseCalls = vi.hoisted(() => [] as Record<string, unknown>[])

vi.mock('@/api/case', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/case')>()
  return {
    ...actual,
    listCases: async (params: Record<string, unknown>) => {
      caseCalls.push(params)
      return { list: [], total: 0 }
    },
    listFlakyRestoreProposals: async () => [],
  }
})
vi.mock('@/api/project', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/project')>()
  return { ...actual, getProjectMembers: async () => [] }
})
vi.mock('@/api/tags', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/tags')>()
  return { ...actual, listTags: async () => [] }
})
vi.mock('@/api/appauto', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/appauto')>()
  return { ...actual, listAppautoEnvProfiles: async () => [] }
})
vi.mock('@/api/favorite', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/favorite')>()
  return { ...actual, listFavorites: async () => [] }
})

import CaseListView from '@/views/case/CaseListView.vue'

import {
  describeProgramLensEmptyCopy,
  describeProgramLensWiring,
  mountPageWithLens,
} from './lensWiringHarness'

describeProgramLensWiring({
  page: 'CaseListView',
  mountWith: (activeProgramId) =>
    mountPageWithLens({ component: CaseListView, routePath: '/cases', stubs: { SuiteListView: true }, activeProgramId }),
  calls: () => caseCalls,
  mountLensOnly: (activeProgramId) =>
    mountPageWithLens({ component: CaseListView, routePath: '/cases', stubs: { SuiteListView: true }, activeProgramId, noProject: true }),
})

beforeEach(() => {
  caseCalls.length = 0
})

describeProgramLensEmptyCopy({
  page: 'CaseListView',
  mountWith: (activeProgramId) =>
    mountPageWithLens({ component: CaseListView, routePath: '/cases', activeProgramId }),
})
