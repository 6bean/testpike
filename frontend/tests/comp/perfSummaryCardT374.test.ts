/**
 * U-5 pre-launch summary card (RED first, then the wiring).
 * The confirm step used to show an estimated-duration line plus a raw JSON dump of
 * the SLA map; the card must now present, on one screen with no folding: peak RPS,
 * estimated duration, the SERVER-computed dispatch budget (PerfBudgetPolicy single
 * source — the wizard never recomputes the hot margin locally), the breaker row in
 * human words, every SLA gate (unfilled keys honestly marked) and the isolation mode.
 * Posture follows perfGateLadderT374.test.ts (real mount where feasible + ?raw mirror).
 */
import { describe, expect, it } from 'vitest'
import wizardRaw from '@/views/perf/PerfScenarioWizard.vue?raw'
import apiRaw from '@/api/perf.ts?raw'

describe('T374 U-5 summary card: every pre-launch number has its own row', () => {
  it('confirm step renders dedicated anchors for peak / budget / breaker / SLA / isolation', () => {
    for (const anchor of [
      'pw-sum-peak', 'pw-sum-budget', 'pw-sum-breaker', 'pw-sum-sla',
      'pw-sum-isolation', 'pw-sum-precheck',
    ]) {
      expect(wizardRaw).toContain(`data-test="${anchor}"`)
    }
  })

  it('the raw JSON dump of the SLA map is gone from the confirm step', () => {
    // The old card printed JSON.stringify(buildSlaJson()) — banned on a user-facing surface.
    const confirmBlock = wizardRaw.slice(
      wizardRaw.indexOf('pw-step-confirm'),
      wizardRaw.indexOf('pw__sum-list') > 0 ? wizardRaw.length : wizardRaw.length,
    )
    expect(confirmBlock).not.toMatch(/JSON\.stringify\(buildSlaJson\(\)/)
  })

  it('the budget number comes from the server response, never recomputed in the wizard', () => {
    // budgetText must read gate.budgetMs / gate.budgetMarginMinutes...
    expect(wizardRaw).toMatch(/gate\.value\?\.budgetMs/)
    expect(wizardRaw).toMatch(/gate\.value\?\.budgetMarginMinutes/)
    // ...and the wizard must NOT compute (duration + margin) locally anywhere.
    expect(wizardRaw).not.toMatch(/budgetMargin\b\s*=\s*form\./)
    expect(wizardRaw).not.toMatch(/estimatedSeconds\.value\s*\+\s*.*margin/i)
  })

  it('the precheck payload declares the pressure so the server can compute the preview', () => {
    const precheckCall = wizardRaw.slice(wizardRaw.indexOf('precheckPerfGate({'))
    expect(precheckCall).toContain('pressureModel: form.pressureModel')
    expect(precheckCall).toContain('pressureConfig: buildPressureConfig()')
  })

  it('api mirror carries the budget preview fields (optional = legacy-response tolerant)', () => {
    expect(apiRaw).toContain('runDurationSeconds?: number')
    expect(apiRaw).toContain('budgetMarginMinutes?: number')
    expect(apiRaw).toContain('budgetMs?: number')
    expect(apiRaw).toContain('pressureModel?: string | null')
    expect(apiRaw).toContain('pressureConfig?: Record<string, number> | null')
  })

  it('SLA rows show unfilled keys honestly instead of silently dropping them', () => {
    expect(wizardRaw).toMatch(/slaNotSet/)
    expect(wizardRaw).toMatch(/breakerNotDeclared/)
    expect(wizardRaw).toMatch(/isolationNotDeclared/)
  })
})
