// SE-1 selector-searchability digestion contract (case list + batch list +
// cluster upgrade center + plan list).
//
// Scope digestion (slices 1-3 of the site-wide selector iron law): every
// select whose candidate list is remote/store driven must carry
// `filterable` (client-side filtering for sources <=200 entries), and any
// select exempted from searchability must state a machine-checkable reason
// in `data-se1-exempt`. This test pins the digested surface so a later
// edit cannot silently drop the attribute; the structural scanner
// (scripts/ui_audit_struct_s6.py, SE-1) keeps the whole-site ratchet.
//
// Assertion bodies are single-source helpers shared by every page block:
// a newly digested page registers its anchors here, it never copies an
// existing block body.
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { describe, expect, it } from 'vitest'

const SRC = join(dirname(fileURLToPath(import.meta.url)), '../../src')

/** Return the opening-tag attribute block of the el-select that carries the
 *  given data-test anchor, or throw when the anchor is missing. */
function selectAttrs(fileText: string, anchor: string): string {
  let idx = fileText.indexOf(`data-test="${anchor}"`)
  if (idx === -1) {
    // Row-scoped anchors are bound as template literals
    // (`assertion-op-${i}` style): match the literal prefix instead.
    idx = fileText.indexOf('`' + anchor)
  }
  if (idx === -1) {
    throw new Error(`anchor data-test="${anchor}" not found`)
  }
  const open = fileText.lastIndexOf('<el-select', idx)
  if (open === -1) {
    throw new Error(`no <el-select opening tag before anchor ${anchor}`)
  }
  const close = fileText.indexOf('>', idx)
  return fileText.slice(open, close === -1 ? fileText.length : close)
}

const FILTERABLE_ATTR = /(?<![\w-])filterable(?![\w-])/
const EXEMPT_REASON_ATTR = /data-se1-exempt="([^"]+)"/

/** Assert the anchored select offers client-side search. */
function expectFilterable(fileText: string, anchor: string): void {
  expect(selectAttrs(fileText, anchor)).toMatch(FILTERABLE_ATTR)
}

/** Pin a set of remote-driven anchors as searchable, one test each. */
function pinFilterableDigest(fileText: string, anchors: string[]): void {
  for (const anchor of anchors) {
    it(`${anchor} carries filterable`, () => {
      expectFilterable(fileText, anchor)
    })
  }
}

/** Return the exemption reason on the anchored select; fail when absent. */
function exemptReason(fileText: string, anchor: string): string {
  const m = selectAttrs(fileText, anchor).match(EXEMPT_REASON_ATTR)
  expect(m, `anchor ${anchor} lacks a data-se1-exempt reason`).not.toBeNull()
  return m?.[1] ?? ''
}

/** Pin an exempted select: its reason must be substantive (>4 chars). */
function pinExemptDigest(fileText: string, anchor: string, title: string): void {
  it(title, () => {
    expect(exemptReason(fileText, anchor).length).toBeGreaterThan(4)
  })
}

describe('SE-1 filterable digestion (case list page)', () => {
  const text = readFileSync(join(SRC, 'views/case/CaseListView.vue'), 'utf-8')

  pinFilterableDigest(text, [
    'case-filter-tag', // tag filter: remote project tags
  ])
})

describe('SE-1 filterable digestion (batch list page)', () => {
  const text = readFileSync(join(SRC, 'views/batch/BatchListView.vue'), 'utf-8')

  pinFilterableDigest(text, [
    'batch-form-env', // create dialog: project env list is remote
    'batch-form-pool', // create dialog: pool tags are remote
    'batch-schedule-env', // schedule dialog: env list is remote
    'batch-reuse-env', // reuse dialog: env list is remote
  ])
  pinExemptDigest(text, 'batch-filter-status', 'status filter exemption states a checkable reason')
})

describe('SE-1 filterable digestion (cluster upgrade center page)', () => {
  const text = readFileSync(join(SRC, 'views/cluster/UpgradeCenterView.vue'), 'utf-8')

  pinFilterableDigest(text, [
    'upgrade-plan-engines', // plan dialog: engine registry list is remote
    'upgrade-plan-canary', // plan dialog: node list is remote
    'upgrade-plan-manifest', // plan dialog: manifest versions are remote
    'upgrade-advisor-version', // insight tab: manifest versions are remote
  ])
})

describe('SE-1 filterable digestion (plan list page)', () => {
  const text = readFileSync(join(SRC, 'views/plan/PlanListView.vue'), 'utf-8')

  pinFilterableDigest(text, [
    'plan-wizard-envs', // wizard step 1: project env list is remote
  ])
  pinExemptDigest(text, 'plan-filter-status', 'status filter exemption states a checkable reason')
  pinExemptDigest(text, 'plan-wizard-phases', 'phases picker exemption states a checkable reason')
  pinExemptDigest(text, 'plan-wizard-priorities', 'priorities picker exemption states a checkable reason')
})

describe('SE-1 filterable digestion (target create wizard)', () => {
  const text = readFileSync(join(SRC, 'views/monitor/TargetCreateWizard.vue'), 'utf-8')

  pinFilterableDigest(text, [
    'wizard-env', // project env list is remote driven
    'wizard-ref-collection', // api collections of the project are remote
    'wizard-ref-request', // requests of a reference collection are remote
    'wizard-conn', // env connections are remote
  ])
})

describe('SE-1 filterable digestion (project list page)', () => {
  const text = readFileSync(join(SRC, 'views/project/ProjectListView.vue'), 'utf-8')

  pinFilterableDigest(text, [
    'project-template-select', // template registry is remote
    'project-test-owner-select', // member options are remote
    'project-dev-owner-select', // member options are remote
  ])
})

describe('SE-1 filterable digestion (perf scenario wizard)', () => {
  const text = readFileSync(join(SRC, 'views/perf/PerfScenarioWizard.vue'), 'utf-8')

  pinFilterableDigest(text, [
    'pw-env', // env list is remote driven
    'pw-engine', // engine registry is remote driven (lazy-loaded)
    'pw-schedule-env', // env list is remote driven
  ])
})

describe('SE-1 exemption digestion (skill list page)', () => {
  const text = readFileSync(join(SRC, 'views/skill/SkillListView.vue'), 'utf-8')

  // Sources are the fixed five-value SKILL_TYPE_KEYS / SKILL_STATUS_KEYS maps
  // in api/skillAdmin.ts — five candidates need no search affordance.
  pinExemptDigest(text, 'skill-type-select', 'type filter is a fixed five-value enum')
  pinExemptDigest(text, 'skill-status-select', 'status filter is a fixed five-value enum')
  pinExemptDigest(text, 'skill-form-type', 'form type picker is a fixed five-value enum')
})

describe('SE-1 filterable digestion (api inventory page)', () => {
  const text = readFileSync(join(SRC, 'views/apitest/ApiInventoryView.vue'), 'utf-8')

  pinFilterableDigest(text, [
    'api-inventory-compare-prev', // version chain grows with contract history
    'api-inventory-compare-next',
  ])
})

describe('SE-1 filterable digestion (orchestration builder page)', () => {
  const text = readFileSync(join(SRC, 'views/apitest/OrchestrationBuilder.vue'), 'utf-8')

  pinFilterableDigest(text, [
    'orchestration-pre-op', // operator set follows the picked field (derived list)
    'orchestration-until-op',
  ])
})

describe('SE-1 filterable digestion (case detail page)', () => {
  const text = readFileSync(join(SRC, 'views/case/CaseDetailView.vue'), 'utf-8')

  pinFilterableDigest(text, [
    'detail-edit-appauto-profile', // profile list is remote driven
    'env-select', // project env list is remote driven
  ])
})

describe('SE-1 filterable digestion (data factory list page)', () => {
  const text = readFileSync(join(SRC, 'views/datafactory/DataFactoryListView.vue'), 'utf-8')

  pinFilterableDigest(text, [
    'pool-filter-env', // env list is remote driven
    'datafactory-form-env',
  ])
})

describe('SE-1 filterable digestion (explore list page)', () => {
  const text = readFileSync(join(SRC, 'views/explore/ExploreListView.vue'), 'utf-8')

  pinFilterableDigest(text, [
    'explore-env', // explorable env list is remote driven
    'explore-cred', // credential list is remote driven
  ])
})

describe('SE-1 filterable digestion (knowledge doc list page)', () => {
  const text = readFileSync(join(SRC, 'views/knowledge/KnowledgeDocListView.vue'), 'utf-8')

  pinFilterableDigest(text, [
    'kb-source-select', // source options come from the shared enum module
    'kb-status-select', // status options come from the shared enum module
  ])
})

describe('SE-1 filterable digestion (mock scenario list page)', () => {
  const text = readFileSync(join(SRC, 'views/mock/MockScenarioListView.vue'), 'utf-8')

  pinFilterableDigest(text, [
    'mock-scenarios-space-select', // mock spaces are remote driven
    'mock-scenarios-f-edge-from', // declared states grow with the state machine
  ])
})

describe('SE-1 filterable digestion (suite list page)', () => {
  const text = readFileSync(join(SRC, 'views/suite/SuiteListView.vue'), 'utf-8')

  pinFilterableDigest(text, [
    'suite-form-envs', // create dialog: project env list is remote driven
    'suite-form-assignees', // create dialog: executable members grow with the roster
  ])
})

describe('SE-1 filterable digestion (appauto matrix fanout dialog)', () => {
  const text = readFileSync(join(SRC, 'components/AppautoMatrixFanoutDialog.vue'), 'utf-8')

  pinFilterableDigest(text, [
    'matrix-env-select', // batch env list is remote driven per project
    'matrix-combo-env-profile', // env profile list is remote driven (row-scoped anchor)
  ])
})

describe('SE-1 exemption digestion (assertion builder)', () => {
  const text = readFileSync(join(SRC, 'components/AssertionBuilder.vue'), 'utf-8')

  // Both option sets are compile-time fixed enums in utils/assertionForm.ts:
  // 6 (http) / 3 (ws) assertion types and at most 4 operators per type.
  pinExemptDigest(text, 'assertion-type', 'type picker is a fixed 6/3-value enum')
  pinExemptDigest(text, 'assertion-op', 'operator picker is a fixed per-type enum (max 4)')
  // AV batch: the extraction lane picker iterates EXTRACT_SOURCE_OPTIONS (3 fixed
  // values from the same kernel), so search would filter nothing worth filtering.
  pinExemptDigest(text, 'assertion-source', 'lane picker is a fixed 3-value enum')
})

// batch-3: the mock-space pickers load spaces from the mock-space
// API (user-grown, unbounded) and the recycle drawer lists projects, so all
// six selects must offer client-side search.
describe('SE-1 filterable digestion (slice5 batch3 mock space pickers + recycle project)', () => {
  const cases = [
    ['components/mock/MockStubDirectWizard.vue', 'stub-direct-space-select'],
    ['views/mock/MockDocsView.vue', 'mock-docs-space-select'],
    ['views/mock/MockInspectorView.vue', 'mock-inspector-space-select'],
    ['views/mock/MockRecordingView.vue', 'mock-recording-space-select'],
    ['components/MockSpacePicker.vue', 'mock-space-select'],
    ['views/skill/SkillRecycleDrawer.vue', 'skill-recycle-project-select'],
  ] as const
  for (const [rel, anchor] of cases) {
    const text = readFileSync(join(SRC, rel), 'utf-8')
    pinFilterableDigest(text, [anchor])
  }
})

// batch-4 (final SE-1 segment): the remaining ten candidates. Seven are
// remote/user-grown or schema-driven sources and must offer client-side
// search; three are bounded closed sets (state-machine transition subset,
// 2-value read flag, 5-value skill type) and carry a machine-checkable
// exemption reason instead. Closing this batch zeroes the site-wide SE-1
// ratchet.
describe('SE-1 filterable digestion (slice5 batch4 final seven searchable)', () => {
  const cases = [
    ['views/appauto/AppPackageView.vue', 'package-form-platform'],
    ['views/coverage/CoverageMatrixView.vue', 'matrix-chapter-filter'],
    ['views/mcp/McpTemplatesPanel.vue', 'mcp-inherit-template'],
    ['views/notification/NotificationCenterView.vue', 'notif-type-filter'],
    ['views/project/ProjectDetailView.vue', 'llm-config-select'],
    ['views/report/ReportView.vue', 'exec-plan-select'],
    ['components/StepTree.vue', 'step-tree-enum-'],
    ['views/apitest/AuthFormEditor.vue', 'session-state-picker'],
  ] as const
  for (const [rel, anchor] of cases) {
    const text = readFileSync(join(SRC, rel), 'utf-8')
    pinFilterableDigest(text, [anchor])
  }
})

describe('SE-1 exemption digestion (slice5 batch4 bounded sets)', () => {
  it('defect-transition-select carries a bounded-subset exemption', () => {
    const text = readFileSync(join(SRC, 'views/defect/DefectListView.vue'), 'utf-8')
    expect(exemptReason(text, 'defect-transition-select').length).toBeGreaterThan(4)
  })
  it('sample-type-select carries a closed-enum exemption', () => {
    const text = readFileSync(join(SRC, 'views/sample/TrainingSampleListView.vue'), 'utf-8')
    expect(exemptReason(text, 'sample-type-select').length).toBeGreaterThan(4)
  })
  it('market-type-select carries a closed-enum exemption', () => {
    const text = readFileSync(join(SRC, 'views/skill/SkillMarketView.vue'), 'utf-8')
    expect(exemptReason(text, 'market-type-select').length).toBeGreaterThan(4)
  })
})
