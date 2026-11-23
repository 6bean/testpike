/**
 * (CF-4 + U-9): shipped scenario templates reach the wizard as
 * data, and the workbench empty state guides a first scenario in three steps.
 *
 * Defect closed (design-level): the backend catalogue endpoint exists since
 * , but without a picker the wizard still forces a from-zero step table
 * for the four proven playbooks, and an empty project sees only a one-line
 * hint with no actionable journey.
 *
 * Contract asserted here:
 * 1. the api layer mirrors the contract (GET /perf/templates);
 * 2. the wizard offers a template select fed ONLY by that endpoint — zero
 * hardcoded template copies on the frontend (red line: config is the
 * single source, a new playbook must require no code change);
 * 3. picking a template pre-fills pressure model + config keys + step table
 * + SLA through the same hydration helpers an edit uses (no second
 * pre-fill logic);
 * 4. the workbench empty state renders a three-step guide card (template →
 * env/dataset → launch&live) with an actionable door into the wizard;
 * 5. every new string lives in both locales (i18n dual-track, zero hard CJK
 * in views).
 */
import { describe, expect, it } from 'vitest'
import apiRaw from '@/api/perf.ts?raw'
import wizardRaw from '@/views/perf/PerfScenarioWizard.vue?raw'
import workbenchRaw from '@/views/perf/PerfWorkbenchView.vue?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

describe('perf api contract mirror', () => {
  it('exports listPerfTemplates against GET /perf/templates', () => {
    expect(apiRaw).toMatch(/export function listPerfTemplates\(\)/)
    expect(apiRaw).toMatch(/url:\s*'\/perf\/templates',\s*method:\s*'get'/)
  })
})

describe('wizard template picker (CF-4)', () => {
  it('consumes the catalogue endpoint', () => {
    expect(wizardRaw).toMatch(/listPerfTemplates/)
  })

  it('offers a labelled searchable template select in the target step', () => {
    expect(wizardRaw).toMatch(/data-test="pw-template"/)
    // the select label points at the control id (no dangling label = Issues gate)
    expect(wizardRaw).toMatch(/for="pw-template-input"/)
    expect(wizardRaw).toMatch(/id="pw-template-input"/)
    // SE iron rule 1: the catalogue is data and grows with config rows —
    // the picker must stay searchable well before it reaches 10 options.
    expect(wizardRaw).toMatch(/data-test="pw-template"[\s\S]{0,80}filterable|filterable[\s\S]{0,160}data-test="pw-template"/)
  })

  it('zero hardcoded template copies — catalogue labels/keys never appear in view or api sources', () => {
    for (const src of [wizardRaw, apiRaw]) {
      expect(src).not.toMatch(/ecom-checkout/)
      expect(src).not.toMatch(/flash-sale/)
      expect(src).not.toMatch(/电商下单链路/)
      expect(src).not.toMatch(/秒杀脉冲/)
    }
  })

  it('pre-fill rides the shared hydration helpers, not a second mapping', () => {
    // steps reuse the edit-normalizer (JSON body round-trip + optional fields)
    expect(wizardRaw).toMatch(/applyTemplate/)
    expect(wizardRaw).toMatch(/normalizeHydratedSteps/)
    // config keys map through the same numeric readers openWith uses
    expect(wizardRaw).toMatch(/numOr\(cfg\./)
  })
})

describe('workbench empty-state three-step guide (U-9)', () => {
  it('renders the guide card instead of a bare hint when a project has no scenarios', () => {
    expect(workbenchRaw).toMatch(/data-test="perf-empty-guide"/)
    for (const n of ['1', '2', '3']) {
      expect(workbenchRaw).toMatch(new RegExp(`data-test="perf-guide-step-${n}"`))
    }
  })

  it('step 1 carries the actionable door into the wizard', () => {
    expect(workbenchRaw).toMatch(/data-test="perf-guide-start"[^>]*@click="openCreate"/)
  })

  it('guide strings are locale-sourced (no hard CJK in the view)', () => {
    const guideSlice = workbenchRaw.slice(
      workbenchRaw.indexOf('perf-empty-guide'),
      workbenchRaw.indexOf('perf-empty-guide') + 1600,
    )
    expect(guideSlice).not.toMatch(/[\u4e00-\u9fff]/)
  })
})

describe('i18n dual-track', () => {
  it('both locales carry the new keys', () => {
    for (const raw of [zhRaw, enRaw]) {
      expect(raw).toMatch(/template: '/)
      expect(raw).toMatch(/templateHint: '/)
      expect(raw).toMatch(/emptyGuideTitle: '/)
      expect(raw).toMatch(/guideStep1: '/)
      expect(raw).toMatch(/guideStep2: '/)
      expect(raw).toMatch(/guideStep3: '/)
      expect(raw).toMatch(/guideStart: '/)
    }
  })
})
