/**
 * layout contract for the three mock pages (RED first, ?raw source-scan
 * mirror following the projectPageWiringBatch posture).
 * Authority = the 2026-10-14 IA ruling doc (mock information-architecture fix):
 * - header-actions must hold zero selectors (page-level ProjectSelect removed;
 * the project comes from the global header context, AK batch, single source);
 * - space picker / refresh live in the filter-bar row (shared theme.scss class,
 * no per-page scoped copies);
 * - help is the "?" affordance next to the page title, never an action slot;
 * - the pages keep consuming the URL-precedence chain via useProjectPageContext
 * so a deep link with ?projectId lands immediately (red line: no second copy
 * of selector state).
 * Remaining same-disease pages are appended to this list batch by batch
 * (full-repo scan inventory = scripts/tmp_t396_scan_header_actions.py).
 */
import { describe, expect, it } from 'vitest'

import docsRaw from '@/views/mock/MockDocsView.vue?raw'
import inspectorRaw from '@/views/mock/MockInspectorView.vue?raw'
import spacesRaw from '@/views/mock/MockSpacesView.vue?raw'

/** Extract the header-actions block (the action row of the page header). */
function headerActions(src: string): string {
  const m = /<div class="header-actions">([\s\S]*?)<\/div>/.exec(src)
  return m ? m[1] : ''
}

const pages: [string, string][] = [
  ['MockSpacesView', spacesRaw],
  ['MockDocsView', docsRaw],
  ['MockInspectorView', inspectorRaw],
]

describe('T396 mock pages: header actions hold no filters (separation of screening vs doing)', () => {
  it.each(pages)('%s header-actions contains no ProjectSelect and no el-select', (_name, src) => {
    const block = headerActions(src)
    expect(block).not.toContain('ProjectSelect')
    expect(block).not.toMatch(/<el-select/)
  })

  it.each(pages)('%s drops the page-level ProjectSelect import entirely (global context is the single source)', (_name, src) => {
    expect(src).not.toMatch(/import ProjectSelect from/)
  })

  it.each(pages)("MockInspector-style shells: no QuestionFilled parked in header-actions", (_name, src) => {
    expect(headerActions(src)).not.toContain('QuestionFilled')
    // R23 stage-2 invariant: the in-page "?" rides along WITH the title shell —
    // shell present means the page still carries its openHelp trigger, shell
    // removed means the trigger is gone too (help rides the single topbar
    // dot pinned in helpDrawer.test.ts). Half-states (orphan openHelp after
    // a shell removal, or a shell without its "?") fail here.
    const shellPresent = /<h2 class="page-title"><el-icon/.test(src)
    const inPageHelp = /openHelp\('T', t\('layout\.leafHelpAnchor\.mock/.test(src)
    expect(inPageHelp).toBe(shellPresent)
  })

  it.each(pages)('%s still consumes the global context through useProjectPageContext (deep-link projectId lands on entry)', (_name, src) => {
    expect(src).toContain("import { useProjectPageContext } from '@/composables/useProjectPageContext'")
    expect(src).toMatch(/const \{ projectId[^}]*\} = useProjectPageContext\(\)/)
  })
})

describe('T396 mock pages: screening controls live in the filter-bar row', () => {
  it.each([['MockDocsView', docsRaw], ['MockInspectorView', inspectorRaw]])(
    '%s keeps the space picker inside a filter-bar block (shared theme.scss class)',
    (_name, src) => {
      // the row may carry v-if/v-show guards; anchor on the class itself
      const bar = /<div[^>]*class="filter-bar"[^>]*>([\s\S]*?)<\/div>/.exec(src)
      expect(bar).not.toBeNull()
      expect(bar![1]).toMatch(/<el-select[\s\S]*space-select/)
    },
  )

  it('MockSpacesView invents no empty filter-bar (a page without screening dimensions stays without one)', () => {
    expect(spacesRaw).not.toMatch(/<div class="filter-bar">\s*<\/div>/)
  })
})

// Batch A of the full-repo same-disease sweep (ruling: fix all pages, not just
// the three mock ones). These ten views already consume useProjectPageContext,
// so the surgery is purely layout: screening controls leave header-actions and
// the page-level ProjectSelect goes (the global header context is the single
// source for the project).
import batchRaw from '@/views/batch/BatchListView.vue?raw'
import boardRaw from '@/views/plan/BoardView.vue?raw'
import datafactoryRaw from '@/views/datafactory/DataFactoryListView.vue?raw'
import defectRaw from '@/views/defect/DefectListView.vue?raw'
import planListRaw from '@/views/plan/PlanListView.vue?raw'
import reportListRaw from '@/views/report/TestReportListView.vue?raw'
import scheduleRaw from '@/views/schedule/ScheduleFireListView.vue?raw'
import suiteRaw from '@/views/suite/SuiteListView.vue?raw'
import tagRaw from '@/views/tag/TagListView.vue?raw'
import docRaw from '@/views/ai/DocumentListView.vue?raw'

const batchAPages: [string, string][] = [
  ['BatchListView', batchRaw],
  ['BoardView', boardRaw],
  ['DataFactoryListView', datafactoryRaw],
  ['DefectListView', defectRaw],
  ['PlanListView', planListRaw],
  ['TestReportListView', reportListRaw],
  ['ScheduleFireListView', scheduleRaw],
  ['SuiteListView', suiteRaw],
  ['TagListView', tagRaw],
]

describe('T396 batch A: same-disease pages move screening out of header-actions', () => {
  it.each(batchAPages)('%s header-actions holds no ProjectSelect / el-select / el-input', (_name, src) => {
    const block = headerActions(src)
    expect(block).not.toContain('ProjectSelect')
    expect(block).not.toMatch(/<el-select/)
    expect(block).not.toMatch(/<el-input/)
  })

  it.each(batchAPages)('%s has no page-level project selector anywhere in the header', (_name, src) => {
    expect(src).not.toMatch(/-project-select/)
  })

  it.each(batchAPages)('%s still consumes the global context via useProjectPageContext', (_name, src) => {
    expect(src).toContain('useProjectPageContext')
  })

  // Pages that keep screening dimensions render them through the shared
  // filter-bar row; pages without any screening never invent an empty bar.
  it.each([['DefectListView', defectRaw], ['BatchListView', batchRaw], ['SuiteListView', suiteRaw],
    ['DataFactoryListView', datafactoryRaw], ['PlanListView', planListRaw], ['TestReportListView', reportListRaw],
    ['ScheduleFireListView', scheduleRaw]])(
    '%s keeps screening inside a filter-bar row', (_name, src) => {
      expect(src).toMatch(/class="filter-bar"/)
    })

  it('TagListView and BoardView screen through the shared filter-bar row', () => {
    // Assertion-lag fix (R23 stage-2): the migration batch moved the
    // refresh action of these pages into a brand-new filter-bar trailing
    // slot, so "never invent an empty bar" no longer describes the shape —
    // both pages now carry real screening controls in the shared row.
    expect(tagRaw).toMatch(/class="filter-bar"/)
    expect(boardRaw).toMatch(/class="filter-bar"/)
  })

  // Batch A2 first page: DocumentListView. Upload category is part of the
  // upload action (rides into the dialog), status/keyword are screening
  // (shared filter-bar row). Assertion-lag fix : the FB-3 same-band
  // merge (R21 revision 3) moved upload/refresh into the .filter-bar-actions
  // trailing slot and split the emptied header band shell, so the
  // screening-free contract is pinned on the slot instead.
  it('DocumentListView keeps the upload action in the merged trailing slot, screening-free', () => {
    expect(/<div class="header-actions">/.test(docRaw), 'header band shell must stay removed').toBe(false)
    const slotStart = docRaw.indexOf('<div class="filter-bar-actions">')
    expect(slotStart, 'merged trailing slot present').toBeGreaterThan(-1)
    const uploadIdx = docRaw.indexOf('data-test="doc-upload-btn"')
    expect(uploadIdx, 'upload anchor present').toBeGreaterThan(slotStart)
    expect(docRaw.slice(slotStart, uploadIdx)).not.toMatch(/<el-select|<el-input|ProjectSelect/)
  })
  it('DocumentListView drops the page-level project selector and its dead handler', () => {
    expect(docRaw).not.toMatch(/import ProjectSelect from/)
    expect(docRaw).not.toMatch(/doc-project-select/)
    expect(docRaw).not.toMatch(/onProjectChange/)
  })
  it('DocumentListView keeps screening in the filter-bar row and upload category inside the dialog', () => {
    expect(docRaw).toMatch(/class="filter-bar"/)
    expect(docRaw).toContain('useProjectPageContext')
    const dialog = /<FileUploadDialog[\s\S]*?<\/FileUploadDialog>/.exec(docRaw)?.[0] ?? ''
    expect(dialog).toContain('doc-category-select')
  })
})

// Batch A3: skill/tool list pages. Unlike batch A these pages never had a
// page-level ProjectSelect (their backend query key is optional: null project =
// system-wide view incl. system-level rows), so the surgery is wiring the
// global header context into the query segment + layout move. The create-form
// ProjectSelect stays: it is a form prefill slot, not a list-screening control
// (system-level skill/tool creation needs an explicit null-project choice).
import skillRaw from '@/views/skill/SkillListView.vue?raw'
import toolRaw from '@/views/tool/ToolListView.vue?raw'
import appPkgRaw from '@/views/appauto/AppPackageView.vue?raw'
import aiSamplesRaw from '@/views/appauto/AiSampleBoardView.vue?raw'
import reservationRaw from '@/views/appauto/DeviceReservationCalendarView.vue?raw'
import engineCenterRaw from '@/views/appauto/EngineCenterView.vue?raw'
import matrixReportRaw from '@/views/appauto/MatrixReportView.vue?raw'
import stepHealingRaw from '@/views/appauto/StepHealingView.vue?raw'
import perfWorkbenchRaw from '@/views/perf/PerfWorkbenchView.vue?raw'
import credentialVaultRaw from '@/views/appauto/CredentialVaultView.vue?raw'
import popupRuleRaw from '@/views/appauto/PopupRuleApprovalView.vue?raw'
import deviceWallRaw from '@/views/appauto/DeviceWallView.vue?raw'

const batchA3Pages: [string, string][] = [
  ['SkillListView', skillRaw],
  ['ToolListView', toolRaw],
]

describe('T396 batch A3: skill/tool pages ride the global context and share the filter row', () => {
  it.each(batchA3Pages)('%s header-actions holds no el-select / el-input (screening left the action row)', (_name, src) => {
    const block = headerActions(src)
    expect(block).not.toMatch(/<el-select/)
    expect(block).not.toMatch(/<el-input/)
  })

  it.each(batchA3Pages)('%s keeps its write/main buttons in the merged trailing slot', (_name, src) => {
    // Assertion-lag fix (, FB-3 same-band merge): the create variant
    // action rides the .filter-bar-actions slot and the emptied header band
    // shell was split.
    expect(/<div class="header-actions">/.test(src), 'header band shell must stay removed').toBe(false)
    expect(src).toMatch(/<div class="filter-bar-actions"[\s\S]*-create-btn/)
  })

  it.each(batchA3Pages)('%s renders screening through the shared filter-bar row', (_name, src) => {
    expect(src).toMatch(/class="filter-bar"/)
  })

  it.each(batchA3Pages)('%s consumes the global project context via useProjectPageContext', (_name, src) => {
    expect(src).toContain("import { useProjectPageContext } from '@/composables/useProjectPageContext'")
    expect(src).toMatch(/const \{ projectId[^}]*\} = useProjectPageContext\(\)/)
    // header switches must reload the segment
    expect(src).toMatch(/watch\(followTick/)
  })

  it.each(batchA3Pages)('%s passes the context project into the list query (project slice; null = honest system-wide view)', (_name, src) => {
    expect(src).toMatch(/projectId: projectId\.value \?\? undefined/)
  })

  it.each(batchA3Pages)('%s prefills the create form project from the context (form-slot exemption pinned, not a screening control)', (_name, src) => {
    expect(src).toMatch(/form\.projectId = projectId\.value/)
    expect(src).toMatch(/import ProjectSelect from/)
  })
})

describe('T396 vision audit follow-up: empty-state affordances hide, not idle', () => {
  // Vision score flagged a dead-looking disabled pager over the empty hit
  // ledger; platform baseline (requirement/audit lists) gates the pager on
  // total > 0 — pinned here so mock pages cannot drift back.
  it('MockInspectorView pager hides on an empty ledger (v-if total > 0, shared baseline)', () => {
    // segment 4 aligned the pager onto the global .pagination-bar shell: the
    // total>0 gate now lives on the wrapper div (same hiding semantics,
    // one class everywhere), so accept either anchor position.
    expect(inspectorRaw).toMatch(/(<el-pagination\s+v-if="total > 0"|v-if="total > 0"[^>]*class="pagination-bar")/)
  })
})

const batchA4Pages: [string, string][] = [
  ['AppPackageView', appPkgRaw],
  ['CredentialVaultView', credentialVaultRaw],
  ['PopupRuleApprovalView', popupRuleRaw],
  ['DeviceWallView', deviceWallRaw],
]

describe('T396 batch A4a: appauto asset pages ride the global context (page-level selector removed)', () => {
  it.each(batchA4Pages)('%s header-actions holds no ProjectSelect / el-select / el-input', (_name, src) => {
    const block = headerActions(src)
    expect(block).not.toContain('ProjectSelect')
    expect(block).not.toMatch(/<el-select/)
    expect(block).not.toMatch(/<el-input/)
  })

  it.each(batchA4Pages)('%s drops the page-level ProjectSelect import (global context is the single source)', (_name, src) => {
    expect(src).not.toMatch(/import ProjectSelect from/)
  })

  it.each(batchA4Pages)('%s consumes the global project context and follows header switches', (_name, src) => {
    expect(src).toContain("import { useProjectPageContext } from '@/composables/useProjectPageContext'")
    expect(src).toMatch(/const \{ projectId, followTick \} = useProjectPageContext\(\)/)
    expect(src).toMatch(/watch\(followTick/)
  })

  it.each(batchA4Pages)('%s keeps its action buttons in the header band or the merged trailing slot', (_name, src) => {
    // FB-3 same-band merge : AppPackageView / CredentialVaultView
    // emptied their header band into the .filter-bar-actions trailing slot, so
    // the pinned intent here is "the create entry survived the selector
    // removal", not "it literally sits in header-actions" (merged pages assert
    // the anchor inside the slot instead of silently passing on an empty block).
    const block = headerActions(src)
    if (block.trim() === '') {
      const slot = /<div[^>]*class="[^"]*\bfilter-bar-actions\b[^"]*"[^>]*>([\s\S]*?)<\/div>\s*<\/div>/.exec(src)
      expect(slot, 'a page without a header band must carry its action in the trailing slot').not.toBeNull()
      expect(slot![1]).toMatch(/-btn/)
    } else {
      expect(block).toMatch(/-btn/)
    }
  })

  it.each(batchA4Pages.slice(1))(
    '%s screens through the shared filter-bar row',
    (_name, src) => {
      expect(src).toMatch(/class="filter-bar"/)
    },
  )

  it('AppPackageView screens through the shared filter-bar row', () => {
    // Assertion-lag fix (R23 stage-2): the batch moved the refresh out
    // of the header into a new filter-bar trailing slot — the row is real.
    expect(appPkgRaw).toMatch(/class="filter-bar"/)
  })
})

const batchA4bPages: [string, string][] = [
  ['AiSampleBoardView', aiSamplesRaw],
  ['DeviceReservationCalendarView', reservationRaw],
  ['EngineCenterView', engineCenterRaw],
  ['MatrixReportView', matrixReportRaw],
  ['StepHealingView', stepHealingRaw],
  ['PerfWorkbenchView', perfWorkbenchRaw],
]

// EngineCenterView joined the screened set with the band migration
// (refresh now rides its new filter-bar trailing slot — assertion-lag fix).
// PerfWorkbenchView joined with the FB-3 same-band merge (create rides the
// trailing slot inside the shared row, header band split — fix).
const a4bScreened = ['AiSampleBoardView', 'DeviceReservationCalendarView', 'MatrixReportView', 'StepHealingView', 'EngineCenterView', 'PerfWorkbenchView']

describe('T396 batch A4b: the last six selector pages ride the global context (full-repo scan zeroes)', () => {
  it.each(batchA4bPages)('%s header-actions holds no ProjectSelect / el-select / el-input', (_name, src) => {
    const block = headerActions(src)
    expect(block).not.toContain('ProjectSelect')
    expect(block).not.toMatch(/<el-select/)
    expect(block).not.toMatch(/<el-input/)
  })

  it.each(batchA4bPages)('%s drops the page-level ProjectSelect import (global context is the single source)', (_name, src) => {
    expect(src).not.toMatch(/import ProjectSelect from/)
  })

  it.each(batchA4bPages)('%s consumes the global project context and follows header switches', (_name, src) => {
    expect(src).toContain("import { useProjectPageContext } from '@/composables/useProjectPageContext'")
    expect(src).toMatch(/const \{ projectId, followTick \} = useProjectPageContext\(\)/)
    expect(src).toMatch(/watch\(followTick/)
  })

  it('no A4b page keeps a legacy auto-pick fallback (deep link + header context own the slice)', () => {
    for (const [, src] of batchA4bPages) {
      expect(src).not.toMatch(/autoSelectFirst/)
      expect(src).not.toMatch(/projects\[0\]\.projectId/)
    }
  })

  it('EngineCenterView retires the supportProjectId second copy (support picker rides the context)', () => {
    expect(engineCenterRaw).not.toMatch(/supportProjectId/)
    expect(engineCenterRaw).toContain('listEngineSupport(projectId.value)')
  })

  it('PerfWorkbenchView keeps the upgradeProject prefill chain (T275h contract, composable lands it)', () => {
    expect(perfWorkbenchRaw).toMatch(/const pid = Number\(q\.upgradeProject\)/)
    expect(perfWorkbenchRaw).toMatch(/projectId\.value = pid/)
  })

  it('the context-driven write entry keeps its honest disabled gate (no context, no action)', () => {
    expect(engineCenterRaw).toMatch(/:disabled="!projectId"/)
    expect(perfWorkbenchRaw).toMatch(/:disabled="!projectId"/)
  })

  it.each(batchA4bPages.filter(([name]) => a4bScreened.includes(name)))(
    '%s screens through the shared filter-bar row',
    (_name, src) => {
      expect(src).toMatch(/class="filter-bar"/)
    },
  )

  it('PerfWorkbenchView rides the FB-3 merge: its row hosts the merged action slot, never an empty shell', () => {
    // Assertion-lag fix : the create variant action moved down into
    // .filter-bar-actions and the emptied header band shell was split — the
    // shared row exists by ruling and carries real content.
    expect(perfWorkbenchRaw).toMatch(/class="filter-bar"[\s\S]*class="filter-bar-actions"[\s\S]*perf-create-btn/)
    expect(/<div class="header-actions">/.test(perfWorkbenchRaw), 'header band shell must stay removed').toBe(false)
  })
})
