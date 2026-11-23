/**
 * CF-8 explainable safety-gate ladder (RED first, then the shared component).
 * The precheck used to show only the first rejection; the backend now returns the
 * full eight-door ladder (PerfGateStep[]: code/label/state/detail/fixBy/entryPath)
 * and the wizard must render every row as a traffic light so the operator sees
 * what passed, what is missing, who can fix it and where.
 * Posture follows ownerEchoT396b.test.ts (real mount in browser mode + ?raw mirror).
 */
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { describe, expect, it } from 'vitest'
import zhCN from '@/utils/locales/zh-CN'
import PerfGateLadder from '@/components/PerfGateLadder.vue'
import ladderRaw from '@/components/PerfGateLadder.vue?raw'
import wizardRaw from '@/views/perf/PerfScenarioWizard.vue?raw'
import type { PerfGateStep } from '@/api/perf'

function step(code: string, state: string, over: Partial<PerfGateStep> = {}): PerfGateStep {
  return {
    code,
    label: `门-${code}`,
    state,
    detail: state === 'FAIL' ? '缺少备案记录' : '检查通过',
    fixBy: state === 'FAIL' ? '项目管理员' : '',
    entryPath: state === 'FAIL' ? '压测工作台 › 白名单备案' : '',
    ...over,
  }
}

const LADDER: PerfGateStep[] = [
  step('OWNERSHIP', 'PASS'),
  step('BLOCKED_SEGMENT', 'PASS'),
  step('GLOBAL_SWITCH', 'SKIP', { detail: '非生产环境不需要' }),
  step('WHITELIST', 'FAIL'),
  step('ISOLATION_DECLARATION', 'SKIP'),
  step('APPROVAL', 'FAIL', { code: 'APPROVAL' }),
  step('ISOLATION_ALLOWED', 'PASS'),
  step('TARGET_MATCH', 'PASS'),
]

function factory(steps: PerfGateStep[] | null | undefined) {
  const host = document.createElement('div')
  document.body.appendChild(host)
  const i18n = createI18n({ legacy: false, locale: 'zh-CN', fallbackLocale: 'zh-CN', messages: { 'zh-CN': zhCN } })
  return mount(PerfGateLadder, { props: { steps: steps as PerfGateStep[] }, attachTo: host, global: { plugins: [i18n] } })
}

describe('T374 CF-8 PerfGateLadder: every gate door renders its own row', () => {
  it('renders one row per ladder step with the machine-keyed data-test anchor', () => {
    const w = factory(LADDER)
    expect(w.find('[data-test="pw-gate-ladder"]').exists()).toBe(true)
    expect(w.findAll('[data-test^="pw-gate-step-"]')).toHaveLength(LADDER.length)
    expect(w.find('[data-test="pw-gate-step-WHITELIST"]').exists()).toBe(true)
  })

  it('FAIL rows show what is missing, who fixes it and the in-app entry (three-part explanation)', () => {
    const w = factory(LADDER)
    const failRow = w.find('[data-test="pw-gate-step-WHITELIST"]')
    expect(failRow.text()).toContain('缺少备案记录')
    expect(failRow.text()).toContain('项目管理员')
    expect(failRow.text()).toContain('压测工作台')
    expect(failRow.text()).toContain('白名单备案')
  })

  it('PASS/SKIP rows hide the fix meta but keep their evidence line', () => {
    const w = factory(LADDER)
    const passRow = w.find('[data-test="pw-gate-step-OWNERSHIP"]')
    expect(passRow.text()).toContain('检查通过')
    expect(passRow.find('[data-test$="-fixby"]').exists()).toBe(false)
    const skipRow = w.find('[data-test="pw-gate-step-GLOBAL_SWITCH"]')
    expect(skipRow.text()).toContain('非生产环境不需要')
  })

  it('renders nothing when the payload has no ladder (legacy precheck responses)', () => {
    expect(factory([]).find('[data-test="pw-gate-ladder"]').exists()).toBe(false)
    expect(factory(null).find('[data-test="pw-gate-ladder"]').exists()).toBe(false)
    expect(factory(undefined).find('[data-test="pw-gate-ladder"]').exists()).toBe(false)
  })

  it('ladder title and fix labels come from locale, no hard-coded CJK in the template', () => {
    // the component template must not embed user-facing Chinese literals
    const templateBlock = ladderRaw.slice(ladderRaw.indexOf('<template>'), ladderRaw.indexOf('</template>'))
    expect(/[\u4e00-\u9fff]/.test(templateBlock)).toBe(false)
    expect(ladderRaw).toContain("t('perf.ladder.title')")
  })

  it('wizard wires the ladder below the gate banner inside pw-gate-result', () => {
    expect(wizardRaw).toContain('PerfGateLadder')
    expect(wizardRaw).toContain('pw-gate-result')
    // summary banner stays (overall verdict), ladder is additive
    expect(wizardRaw).toContain('gate.steps')
  })
})
