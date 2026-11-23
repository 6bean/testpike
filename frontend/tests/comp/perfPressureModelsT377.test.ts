/**
 * B-2 pressure-model wiring in the perf wizard .
 *
 * Defect the segment closes: the wizard radio still offered only the three legacy
 * models (CONSTANT_RPS/RAMP/SPIKE) and buildPressureConfig's trailing `else`
 * silently swallowed any new model into the SPIKE key set — so the four new
 * server-side models (FIXED_VU/LADDER/BUSINESS_CURVE/SOAK, compiled by both
 * engines in segment b2) could never be authored from the UI.
 *
 * Contract asserted here:
 * 1. the model radio renders all seven values (same domain as
 * PerfScenarioServiceImpl.PRESSURE_MODELS — no second, smaller enum in UI);
 * 2. buildPressureConfig is an exhaustive switch (no `else` fallback shape)
 * and emits each model's server REQUIRED_KEYS;
 * 3. openWith round-trips every new key (vu/loops/thinkTimeMs/stepRps/
 * stepSeconds/steps/durationHours/curve0..23) so an edit never drops them;
 * 4. BUSINESS_CURVE hour weights stay collapsed by default with an always
 * visible filled-hours chip row (worst-scale + SE-2 no-invisible-filter);
 * 5. i18n dual track: both locales carry all seven model names/descriptions.
 */
import { describe, expect, it } from 'vitest'
import wizardRaw from '@/views/perf/PerfScenarioWizard.vue?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

const MODELS = ['CONSTANT_RPS', 'RAMP', 'SPIKE', 'FIXED_VU', 'LADDER', 'BUSINESS_CURVE', 'SOAK']

describe('T377 B-2 pressure models in the perf wizard', () => {
  it('the model radio offers all seven server-domain values', () => {
    const radioBlock = wizardRaw.slice(
      wizardRaw.indexOf('data-test="pw-model"'),
      wizardRaw.indexOf('</el-radio-group>', wizardRaw.indexOf('data-test="pw-model"')),
    )
    for (const m of MODELS) {
      expect(radioBlock).toContain(`value="${m}"`)
    }
  })

  it('buildPressureConfig covers every model with its required key set (no else-swallow)', () => {
    const fn = wizardRaw.slice(
      wizardRaw.indexOf('function buildPressureConfig'),
      wizardRaw.indexOf('function buildSlaJson'),
    )
    // the legacy trailing-if (SPIKE keys for everything unmatched) must be gone
    expect(fn).not.toMatch(/return \{ baseRps: form\.baseRps[^}]*\}\s*\}\s*$/)
    for (const m of MODELS) expect(fn).toContain(`case '${m}'`)
    // per-model required keys mirror the server REQUIRED_KEYS
    expect(fn).toContain('thinkTimeMs')
    expect(fn).toContain('steps: form.ladderSteps')
    expect(fn).toContain('durationHours: form.durationHours')
    expect(fn).toMatch(/curve\$\{h\}/)
  })

  it('the shared pressure-config hydration round-trips the new keys (edit never drops the shape)', () => {
    // e17df854 folded the key back-fill into the shared applyPressureConfig
    // (one copy for template pre-fill + edit hydration), so the contract
    // window moved from the openWith body to that single source.
    const fn = wizardRaw.slice(
      wizardRaw.indexOf('function applyPressureConfig'),
      wizardRaw.indexOf('function applySla'),
    )
    for (const key of ['vu', 'loops', 'thinkTimeMs', 'stepRps', 'stepSeconds', 'durationHours']) {
      expect(fn).toContain(`cfg.${key}`)
    }
    expect(fn).toMatch(/curve\$\{h\}/)
    // and the edit lane actually feeds the stored shape through it
    expect(wizardRaw).toMatch(/openWith[\s\S]*applyPressureConfig\(cfg, form\.pressureModel\)/)
  })

  it('BUSINESS_CURVE weights collapse by default with a visible filled-hours chip row', () => {
    // collapsed panel gated on curveExpanded, chips gated on the filled count
    expect(wizardRaw).toContain('v-show="form.curveExpanded"')
    expect(wizardRaw).toContain('data-test="pw-curve-chips"')
    expect(wizardRaw).toContain('v-show="curveFilledCount"')
    // toggle button announces how many hours are filled (no invisible filters)
    expect(wizardRaw).toContain("t('perf.wizard.curveToggle', { filled: curveFilledCount })")
  })

  it('i18n dual track carries all seven model names and descriptions', () => {
    for (const raw of [zhRaw, enRaw]) {
      // anchor on the pressure-model blocks themselves (a bare `model: {` also
      // exists in unrelated sections of the locale file)
      const nameBlock = raw.slice(raw.indexOf('model: {', raw.indexOf('runs:')), raw.indexOf('modelDesc:'))
      const descStart = raw.indexOf('modelDesc: {')
      const descBlock = raw.slice(descStart, raw.indexOf('login: {', descStart))
      for (const m of MODELS) {
        expect(nameBlock).toContain(`${m}:`)
        expect(descBlock).toContain(`${m}:`)
      }
    }
  })
})
