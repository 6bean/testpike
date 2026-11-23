/**
 * T396b package 2: per-page wiring contract for the ownership-echo iron rule
 *
 * Clauses enforced here (?raw source mirror, same posture as
 * mockHeaderLayoutT396.test.ts):
 * 1. every create/write dialog on a page consuming the global project context
 * renders the shared <ProjectOwnerEcho /> read-only ownership line - the
 * value is single-sourced from the store, so no page may keep a selector
 * inside the dialog instead;
 * 2. the create entry is disabled while the context is empty AND carries a
 * plain-language pointer (the shared pickInHeader copy) on that disabled
 * state, instead of a post-submit generic "required field" error.
 *
 * Batch scope = the 11 real-violation pages inventoried in package 1 plus the
 * MockSpaces follow-up stroke (package 4 wires its dialog echo + gated entry, so
 * the page converges instead of staying exempt). Skill/Tool stay exempt: their
 * dialog-side ProjectSelect is the "global asset with optional owner" contract
 * (projectId null = system-level skill/tool), not a second-channel violation.
 * AiGenerate retired its legacy selector in the package-3 follow-up stroke.
 */
import { describe, expect, it } from 'vitest'

import appPackageRaw from '@/views/appauto/AppPackageView.vue?raw'
import credentialRaw from '@/views/appauto/CredentialVaultView.vue?raw'
import reservationRaw from '@/views/appauto/DeviceReservationCalendarView.vue?raw'
import batchRaw from '@/views/batch/BatchListView.vue?raw'
import datafactoryRaw from '@/views/datafactory/DataFactoryListView.vue?raw'
import defectRaw from '@/views/defect/DefectListView.vue?raw'
import planRaw from '@/views/plan/PlanListView.vue?raw'
import requirementRaw from '@/views/requirement/RequirementListView.vue?raw'
import suiteRaw from '@/views/suite/SuiteListView.vue?raw'
import exploreRaw from '@/views/explore/ExploreListView.vue?raw'
import perfWorkbenchRaw from '@/views/perf/PerfWorkbenchView.vue?raw'
import mockSpacesRaw from '@/views/mock/MockSpacesView.vue?raw'
import tagRaw from '@/views/tag/TagListView.vue?raw'
import aiGenRaw from '@/views/ai/AiGenerateView.vue?raw'
import perfWizardSrc from '@/views/perf/PerfScenarioWizard.vue?raw'

interface Site {
  name: string
  src: string
  /** data-test anchor of the create/write dialog hosting the echo */
  dialogTest: string
  /** data-test anchor of the primary create button (context-gated entry) */
  btnTest: string
}

const sites: Site[] = [
  { name: 'AppPackageView', src: appPackageRaw, dialogTest: 'package-create-dialog', btnTest: 'package-create-btn' },
  { name: 'CredentialVaultView', src: credentialRaw, dialogTest: 'credential-dialog', btnTest: 'credential-create-btn' },
  { name: 'DeviceReservationCalendarView', src: reservationRaw, dialogTest: 'reservation-create-dialog', btnTest: 'reservation-create-btn' },
  { name: 'BatchListView', src: batchRaw, dialogTest: 'batch-create-dialog', btnTest: 'batch-create-btn' },
  { name: 'DataFactoryListView', src: datafactoryRaw, dialogTest: 'datafactory-dialog', btnTest: 'datafactory-create-btn' },
  { name: 'DefectListView', src: defectRaw, dialogTest: 'defect-create-dialog', btnTest: 'defect-create-btn' },
  { name: 'PlanListView', src: planRaw, dialogTest: 'plan-wizard-dialog', btnTest: 'plan-create-btn' },
  { name: 'RequirementListView', src: requirementRaw, dialogTest: 'req-edit-dialog', btnTest: 'req-create' },
  { name: 'SuiteListView', src: suiteRaw, dialogTest: 'suite-dialog', btnTest: 'suite-create-btn' },
  { name: 'ExploreListView', src: exploreRaw, dialogTest: 'explore-create-dialog', btnTest: 'explore-create' },
  // T396b package 4 (ruling line 37 of the package-1 inventory): MockSpaces was
  // inventoried as "page-level empty block covers rule 2, dialog echo still owed"
  // — the follow-up stroke wires the shared echo AND the gated entry so the page
  // converges on the same posture as every other context page (no exemption).
  { name: 'MockSpacesView', src: mockSpacesRaw, dialogTest: 'mock-spaces-create-dialog', btnTest: 'mock-spaces-new' },
  // closing: TagListView was a missed page in the T396b inventory sweep
  // (git show proves the echo was never wired there) — pinned here so the
  // create dialog echo + gated entry converge on the same whole-site posture.
  { name: 'TagListView', src: tagRaw, dialogTest: 'tag-create-dialog', btnTest: 'tag-create-btn' },
]

// Perf splits across two files: the gated entry lives on the workbench page,
// the wizard dialog that hosts the echo is a child component. Locked together.
const perfWizardRaw = perfWizardSrc
describe('T396b rule 1 (perf): the scenario wizard echoes the owning project', () => {
  it('imports the shared ProjectOwnerEcho component', () => {
    expect(perfWizardRaw).toContain("import ProjectOwnerEcho from '@/components/ProjectOwnerEcho.vue'")
  })
  it('hosts <ProjectOwnerEcho /> at the top of the wizard dialog (before the steps)', () => {
    const block = dialogBlock(perfWizardRaw, 'perf-wizard')
    expect(block).toMatch(/<ProjectOwnerEcho\s*\/?>/)
    const echoAt = block.search(/<ProjectOwnerEcho\s*\/?>/)
    const formAt = block.search(/<el-steps/)
    expect(formAt).toBeGreaterThan(-1)
    expect(echoAt).toBeLessThan(formAt)
  })
  it('retired the legacy in-wizard project selector (rule 1: no second channel)', () => {
    const block = dialogBlock(perfWizardRaw, 'perf-wizard')
    expect(block).not.toMatch(/<ProjectSelect/)
    expect(block).not.toMatch(/project-select/)
  })
})

describe('T396b rule 2 (perf): the workbench create entry is gated with a pointer', () => {
  it('keeps the create button gated on the global context', () => {
    expect(perfWorkbenchRaw).toMatch(
      /:disabled="[^"]*!projectId[^"]*"[\s\S]{0,200}?data-test="perf-create-btn"/,
    )
  })
  it('wraps the gated create button in a tooltip carrying the pick-in-header copy', () => {
    expect(perfWorkbenchRaw).toMatch(
      /<el-tooltip[^>]*components\.ownerEcho\.pickInHeader[\s\S]{0,400}?data-test="perf-create-btn"/,
    )
    expect(perfWorkbenchRaw).toMatch(/:disabled="!!projectId"/)
  })
})

/** segment out one dialog's template block from its data-test anchor to the close tag. */
function dialogBlock(src: string, dialogTest: string): string {
  const start = src.indexOf(`data-test="${dialogTest}"`)
  expect(start, `dialog anchor ${dialogTest} missing`).toBeGreaterThan(-1)
  const end = src.indexOf('</el-dialog>', start)
  expect(end, `dialog ${dialogTest} never closes`).toBeGreaterThan(start)
  return src.slice(start, end)
}

describe('T396b rule 1: create dialogs echo the owning project read-only', () => {
  it.each(sites)('%s imports the shared ProjectOwnerEcho component', (site) => {
    expect(site.src).toContain("import ProjectOwnerEcho from '@/components/ProjectOwnerEcho.vue'")
  })

  it.each(sites)('%s hosts <ProjectOwnerEcho /> inside the create dialog (before the form fields)', (site) => {
    const block = dialogBlock(site.src, site.dialogTest)
    expect(block).toMatch(/<ProjectOwnerEcho\s*\/?>/)
    // single source: the echo line is the first thing the dialog body shows
    const echoAt = block.search(/<ProjectOwnerEcho\s*\/?>/)
    const formAt = block.search(/<el-form|<el-steps/)
    expect(formAt).toBeGreaterThan(-1)
    expect(echoAt).toBeLessThan(formAt)
  })

  it.each(sites)('%s never grows a project selector inside the dialog (rule 1: no second channel)', (site) => {
    const block = dialogBlock(site.src, site.dialogTest)
    expect(block).not.toMatch(/<ProjectSelect/)
    expect(block).not.toMatch(/project-select/)
  })
})

// T396b pack-3 follow-up stroke: the AI generate wizard rides the header
// context too — the legacy in-page ai-gen-project selector retires here.
describe('T396b rule 1+2 (AiGenerateView): wizard inherits the header context', () => {
  it('imports the shared context wiring piece and the echo component (single source)', () => {
    expect(aiGenRaw).toContain("import { useProjectPageContext } from '@/composables/useProjectPageContext'")
    expect(aiGenRaw).toContain("import ProjectOwnerEcho from '@/components/ProjectOwnerEcho.vue'")
  })
  it('retires the legacy project selector site-wide (rule 1: no second channel)', () => {
    expect(aiGenRaw).not.toMatch(/<ProjectSelect/)
    expect(aiGenRaw).not.toMatch(/ai-gen-project/)
  })
  it('echoes the owning project read-only inside both form modes', () => {
    const echoes = aiGenRaw.match(/<ProjectOwnerEcho\s*\/?>/g) || []
    expect(echoes.length).toBeGreaterThanOrEqual(2)
  })
  it('gates the single submit button on the global context with the pointer tooltip', () => {
    expect(aiGenRaw).toMatch(/:disabled="[^"]*!projectId[^"]*"[\s\S]{0,200}?data-test="ai-gen-submit"/)
    expect(aiGenRaw).toMatch(/<el-tooltip[^>]*components\.ownerEcho\.pickInHeader[\s\S]{0,400}?data-test="ai-gen-submit"/)
    expect(aiGenRaw).toMatch(/:disabled="!!projectId"/)
  })
  it('gates the batch submit button the same way', () => {
    expect(aiGenRaw).toMatch(/:disabled="[^"]*!projectId[^"]*"[\s\S]{0,200}?data-test="ai-gen-batch-submit"/)
  })
})

describe('T396b rule 2: context-less create entry is disabled with a plain-language pointer', () => {
  it.each(sites)('%s keeps the create button gated on the global context', (site) => {
    const btn = new RegExp(`:disabled="[^"]*!projectId[^"]*"[\\s\\S]{0,200}?data-test="${site.btnTest}"`)
    expect(site.src).toMatch(btn)
  })

  it.each(sites)('%s wraps the gated create button in a tooltip carrying the pick-in-header copy', (site) => {
    const gate = new RegExp(
      `<el-tooltip[^>]*components\\.ownerEcho\\.pickInHeader[\\s\\S]{0,400}?data-test="${site.btnTest}"`,
    )
    expect(site.src).toMatch(gate)
    // the hint must only speak while the context is missing, not on every hover
    expect(site.src).toMatch(/:disabled="!!projectId"/)
  })
})
