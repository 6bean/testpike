/**
 * Defect library page consumes the header program lens ( MP4-b, second
 * segment). The defect-domain backend already conjuncts programId since
 * MP3b-2 (five Judgments in DefectListProgramLensTest) — this file locks the
 * front-end consumption face: listDefectLibrary carries the lens id, and a
 * lens flip reloads at page 1. The create-dialog case picker (listCases with
 * an explicit projectId) is form-semantic, not the list segment, and stays
 * out of this contract by design.
 */
import { beforeEach, vi } from 'vitest'

/** Captured listDefectLibrary parameter objects, in call order. */
const defectCalls = vi.hoisted(() => [] as Record<string, unknown>[])

vi.mock('@/api/defectLibrary', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/defectLibrary')>()
  return {
    ...actual,
    listDefectLibrary: async (params: Record<string, unknown>) => {
      defectCalls.push(params)
      return { list: [], total: 0 }
    },
  }
})
vi.mock('@/api/case', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/case')>()
  return { ...actual, listCases: async () => ({ list: [], total: 0 }) }
})
vi.mock('@/api/plan', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/plan')>()
  return { ...actual, listPlans: async () => ({ list: [], total: 0 }) }
})
vi.mock('@/api/project', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/project')>()
  return { ...actual, getProjectMembers: async () => [] }
})
vi.mock('@/api/favorite', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/favorite')>()
  return { ...actual, listFavorites: async () => [] }
})

import DefectListView from '@/views/defect/DefectListView.vue'

import {
  describeProgramLensEmptyCopy,
  describeProgramLensWiring,
  mountPageWithLens,
} from './lensWiringHarness'

describeProgramLensWiring({
  page: 'DefectListView',
  mountWith: (activeProgramId) =>
    mountPageWithLens({ component: DefectListView, routePath: '/defects', activeProgramId }),
  calls: () => defectCalls,
  mountLensOnly: (activeProgramId) =>
    mountPageWithLens({ component: DefectListView, routePath: '/defects', activeProgramId, noProject: true }),
})

beforeEach(() => {
  defectCalls.length = 0
})

describeProgramLensEmptyCopy({
  page: 'DefectListView',
  mountWith: (activeProgramId) =>
    mountPageWithLens({ component: DefectListView, routePath: '/defects', activeProgramId }),
})
