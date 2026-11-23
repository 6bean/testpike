/**
 * step-table parameter editor (layout contract, RED first).
 *
 * Defect: the wizard step rows only exposed action/method/url/auth while the
 * server save chain and both engine templates already consume headers/body/
 * expect/saveAs — the promised capability had no settings surface (the old
 * stepsHint even promised "URL variable references stay as written in the
 * action schema" without offering any place to edit them).
 *
 * Contract asserted here (raw-source anchors, same style as the GA-1
 * precedent — the anchors are the stable data-test surface):
 * 1. every step row renders a collapsible parameter area (collapsed by
 * default) plus page-level expand-all / collapse-all controls;
 * 2. the four slots exist: headers key/value rows, body textarea, expect
 * status code, saveAs variable name;
 * 3. body stays honestly disabled for body-less methods (GET note);
 * 4. dataset column chips are clickable token inserts (no second chip kit);
 * 5. saveAs / status vocabularies import the single mirror sources from
 * assertionForm (no second copy of the contract regex);
 * 6. stepsHint names the parameter entry instead of the bare schema promise.
 */
import { describe, expect, it } from 'vitest'
import wizardRaw from '@/views/perf/PerfScenarioWizard.vue?raw'
import formRaw from '@/utils/assertionForm.ts?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

describe('T442 perf wizard step parameter editor', () => {
  it('step rows render a collapsible parameter area with expand/collapse-all', () => {
    expect(wizardRaw).toContain('pw-step-params-toggle-')
    expect(wizardRaw).toContain('pw-step-params-')
    expect(wizardRaw).toContain('data-test="pw-params-expand-all"')
    expect(wizardRaw).toContain('data-test="pw-params-collapse-all"')
  })

  it('the four parameter slots render inside the expanded area', () => {
    for (const anchor of [
      'data-test="pw-param-hkey"',
      'data-test="pw-param-hvalue"',
      'data-test="pw-param-hadd"',
      'data-test="pw-param-body"',
      'data-test="pw-param-status"',
      'data-test="pw-param-saveas"',
    ]) {
      expect(wizardRaw).toContain(anchor)
    }
  })

  it('body input is honestly disabled for body-less methods', () => {
    // The gate predicate is a single named helper, not inline magic per slot.
    expect(wizardRaw).toMatch(/function methodAllowsBody\(/)
    expect(wizardRaw).toContain('paramBodyNaNote')
  })

  it('dataset column chips insert the {{VAR}} token into the focused field', () => {
    expect(wizardRaw).toContain('data-test="pw-var-chip"')
    expect(wizardRaw).toMatch(/@click="insertVarToken\(/)
    expect(wizardRaw).toMatch(/function insertVarToken\(/)
  })

  it('saveAs and status vocabularies ride the single mirror sources', () => {
    expect(formRaw).toMatch(/export const SAVE_AS_NAME/)
    expect(formRaw).toMatch(/export const STATUS_MIN/)
    expect(formRaw).toMatch(/export const STATUS_MAX/)
    expect(wizardRaw).toMatch(/SAVE_AS_NAME,\s*STATUS_MAX,\s*STATUS_MIN,?\s*\} from '@\/utils\/assertionForm'/)
  })

  it('stepsHint names the parameter entry and drops the bare schema promise', () => {
    const zhHint = zhRaw.slice(zhRaw.indexOf('stepsHint'), zhRaw.indexOf('stepsHint') + 600)
    expect(zhHint).not.toContain('照动作 Schema 原样带入')
    expect(zhHint).toContain('参数')
    const enHint = enRaw.slice(enRaw.indexOf('stepsHint'), enRaw.indexOf('stepsHint') + 700)
    expect(enHint).not.toContain('stay as written in the action schema')
    expect(enHint).toMatch(/[Pp]arameters/)
  })
})
