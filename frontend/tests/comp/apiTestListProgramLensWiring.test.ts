/**
 * API-test collection rail consumes the header program lens .
 *
 * The rail is a non-paged two-level tree door (S-group ledger page): under a
 * lens the core bridge answers a multi-root forest — collections of every
 * visible project inside the program, each item already carrying its own
 * projectId. Wiring law (R30 program-dimension projection): the page never
 * owns a second program picker; it forwards useProgramContextStore
 * .activeProgramId verbatim on the list query and reloads on every lens
 * flip. Contracts are single sourced in tests/comp/lensWiringHarness.ts.
 */
import { beforeEach, vi } from 'vitest'

/** Captured listCollections argument tuples, normalised to param records. */
const listCalls = vi.hoisted(() => [] as Record<string, unknown>[])

vi.mock('@/api/apiTest', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/apiTest')>()
  return {
    ...actual,
    listCollections: async (projectId: number, programId?: number) => {
      listCalls.push({ projectId, programId })
      return []
    },
    // The alias door rides the same mount chain BEFORE the rail loads; let it
    // settle locally so the captured listCollections call is deterministic.
    listStepAliases: async () => [],
  }
})

import ApiTestView from '@/views/apitest/ApiTestView.vue'

import {
  describeProgramLensEmptyCopy,
  describeProgramLensWiring,
  mountPageWithLens,
} from './lensWiringHarness'

describeProgramLensWiring({
  page: 'ApiTestView',
  paged: false,
  mountWith: (activeProgramId) =>
    mountPageWithLens({ component: ApiTestView, routePath: '/api-test', activeProgramId }),
  calls: () => listCalls,
  mountLensOnly: (activeProgramId) =>
    mountPageWithLens({ component: ApiTestView, routePath: '/api-test', activeProgramId, noProject: true }),
})

beforeEach(() => {
  listCalls.length = 0
})

describeProgramLensEmptyCopy({
  page: 'ApiTestView',
  mountWith: (activeProgramId) =>
    mountPageWithLens({ component: ApiTestView, routePath: '/api-test',  activeProgramId }),
})
