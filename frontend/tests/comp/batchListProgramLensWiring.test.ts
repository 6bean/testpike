/**
 * Batch list page consumes the header program lens — real-mount behaviour
 * contract (Vitest Browser Mode, real Chromium; MP4-a first segment,
 * assembly refactored onto the shared harness from MP4-b onward, R40).
 *
 * Wiring law (R30 program-dimension projection): the page NEVER owns a
 * second program picker — it reads useProgramContextStore.activeProgramId
 * as the single source and forwards it on the list query. The four contracts
 * are single sourced in tests/comp/lensWiringHarness.ts.
 * Division of labour: store mechanics = tests/ui/programHeaderLens.test.ts,
 * header pruning = MainLayout, full user journey + worst-case fixture =
 * MP4-d E2E batch. This file only locks the page-consumption face so a later
 * batch cannot silently drop the wiring (static-assertion fake-green lesson,
 * R25 — hence a real mount with captured request params, not a ?raw scan).
 */
import { beforeEach, vi } from 'vitest'

/** Captured listBatches parameter objects, in call order. */
const listCalls = vi.hoisted(() => [] as Record<string, unknown>[])

vi.mock('@/api/batch', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/batch')>()
  return {
    ...actual,
    listBatches: async (params: Record<string, unknown>) => {
      listCalls.push(params)
      return { list: [], total: 0 }
    },
  }
})
vi.mock('@/api/project', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/project')>()
  return { ...actual, getProjectEnvs: async () => [] }
})
vi.mock('@/api/case', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/case')>()
  return { ...actual, listCases: async () => ({ list: [], total: 0 }) }
})
vi.mock('@/api/tags', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/tags')>()
  return { ...actual, listTags: async () => [] }
})

import BatchListView from '@/views/batch/BatchListView.vue'

import {
  describeProgramLensEmptyCopy,
  describeProgramLensWiring,
  mountPageWithLens,
} from './lensWiringHarness'

describeProgramLensWiring({
  page: 'BatchListView',
  mountWith: (activeProgramId) =>
    mountPageWithLens({ component: BatchListView, routePath: '/batches', stubs: { ScheduleFireListView: true }, activeProgramId }),
  calls: () => listCalls,
  mountLensOnly: (activeProgramId) =>
    mountPageWithLens({ component: BatchListView, routePath: '/batches', stubs: { ScheduleFireListView: true }, activeProgramId, noProject: true }),
})

beforeEach(() => {
  listCalls.length = 0
})

describeProgramLensEmptyCopy({
  page: 'BatchListView',
  mountWith: (activeProgramId) =>
    mountPageWithLens({ component: BatchListView, routePath: '/batches', stub: { ScheduleFireListView: true }, activeProgramId }),
})
