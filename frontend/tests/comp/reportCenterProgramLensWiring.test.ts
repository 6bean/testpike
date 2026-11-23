/**
 * Report-center ledger page consumes the header program lens — real-mount
 * behaviour contract (Vitest Browser Mode, real Chromium; MP4-c
 * closing piece). Assembly is single sourced in tests/comp/lensWiringHarness.ts
 * (R40) — this file only contributes the page's own API mocks and the
 * component under mount.
 *
 * Wiring law (R30 program-dimension projection): the page never owns a
 * second program picker — it reads useProgramContextStore.activeProgramId
 * and forwards it on the list query. With the backend list already narrowed
 * through ProgramLensScope (merged 6aead2a8c), this locks the page face so a
 * later batch cannot silently drop the wiring.
 */
import { beforeEach, vi } from 'vitest'

/** Captured listReportCenter parameter objects, in call order. */
const listCalls = vi.hoisted(() => [] as Record<string, unknown>[])

vi.mock('@/api/reportCenter', (io) => makeReportCenterDouble(io, { list: listCalls }))
vi.mock('@/api/project', (io) => projectApiDouble(io))
vi.mock('@/api/report', (io) => reportApiDouble(io))

// The harness must be imported before the view: the page module graph pulls
// '@/api/project' during its evaluation, and the hoisted vi.mock factories
// reference the harness bindings at that moment.
import {
  describeProgramLensEmptyCopy,
  describeProgramLensWiring,
  makeReportCenterDouble,
  mountPageWithLens,
  projectApiDouble,
  reportApiDouble,
} from './lensWiringHarness'
import ReportCenterView from '@/views/report-center/ReportCenterView.vue'


describeProgramLensWiring({
  page: 'ReportCenterView',
  mountWith: (activeProgramId) =>
    mountPageWithLens({ component: ReportCenterView, routePath: '/report-center', activeProgramId }),
  calls: () => listCalls,
  // The report ledger slices by scopeId (tp_report keys on scope_id), not projectId.
  sliceKey: 'scopeId',
  mountLensOnly: (activeProgramId) =>
    mountPageWithLens({ component: ReportCenterView, routePath: '/report-center', activeProgramId, noProject: true }),
})

beforeEach(() => {
  listCalls.length = 0
})

describeProgramLensEmptyCopy({
  page: 'ReportCenterView',
  mountWith: (activeProgramId) =>
    mountPageWithLens({ component: ReportCenterView, routePath: '/report-center',  activeProgramId }),
})
