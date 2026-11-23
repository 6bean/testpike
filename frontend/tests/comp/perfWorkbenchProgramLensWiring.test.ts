/**
 * Perf workbench scenario ledger consumes the header program lens .
 *
 * The scenario page door is the MP3b-4 seven-parameter lens overload on core
 * (three-branch ProgramLensScope, conjunctive with the explicit projectId).
 * Wiring law (R30 program-dimension projection): no in-page program picker —
 * the page forwards useProgramContextStore.activeProgramId verbatim and
 * resets its window to page 1 on every lens flip, same posture as the
 * project-header followTick reload. Datasets inside the wizard are a write-
 * target selector (R30 exemption ①) and stay legacy by design.
 * Contracts are single sourced in tests/comp/lensWiringHarness.ts.
 */
import { beforeEach, vi } from 'vitest'

/** Captured pagePerfScenarios positional args, normalised to param records. */
const listCalls = vi.hoisted(() => [] as Record<string, unknown>[])

vi.mock('@/api/perf', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/perf')>()
  return {
    ...actual,
    pagePerfScenarios: async (
      projectId: number,
      page: number,
      pageSize: number,
      sort?: unknown,
      programId?: number,
    ) => {
      listCalls.push({ projectId, page, pageSize, sort, programId })
      return { list: [], total: 0 }
    },
  }
})

import PerfWorkbenchView from '@/views/perf/PerfWorkbenchView.vue'

import {
  describeProgramLensEmptyCopy,
  describeProgramLensWiring,
  mountPageWithLens,
} from './lensWiringHarness'

const mountPerf = (activeProgramId: number | null) =>
  mountPageWithLens({
    component: PerfWorkbenchView,
    routePath: '/perf',
    stubs: { PerfScenarioWizard: true },
    activeProgramId,
  })

describeProgramLensWiring({
  page: 'PerfWorkbenchView',
  mountWith: mountPerf,
  calls: () => listCalls,
  mountLensOnly: (activeProgramId) =>
    mountPageWithLens({ component: PerfWorkbenchView, routePath: '/perf', stubs: { PerfScenarioWizard: true }, activeProgramId, noProject: true }),
})

describeProgramLensEmptyCopy({
  page: 'PerfWorkbenchView',
  mountWith: mountPerf,
})

beforeEach(() => {
  listCalls.length = 0
})
