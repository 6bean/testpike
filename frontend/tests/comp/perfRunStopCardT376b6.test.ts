/**
 * segment b6: U-6 terminal freeze + U-7 three-stage stop card (RED first, then wiring).
 *
 * U-7 contract (F-group discipline):
 * - stage 1 (before click): the confirm text must name the object and the consequence
 * (run id, collected seconds, curve freezes, stored data kept) — no generic
 * continue-style one-liner;
 * - stage 2 (in flight): the stop button carries loading+disabled while the request runs,
 * and a re-entrant guard blocks a second dispatch (double-click = 1 request);
 * - stage 3 (receipt): the success toast names the run id alongside the stopped-task count.
 * U-6 contract:
 * - terminal state shows a frozen-curve note next to the chart (polling stopped, last
 * collected second is the truth anchor);
 * - a stored data-loss event (core A-9 guard) is surfaced on the page itself, not only
 * inside the events drawer — the tail gap must never masquerade as a complete curve.
 * Posture follows perfSummaryCardT374.test.ts (?raw mirror assertions).
 */
import { describe, expect, it } from 'vitest'
import viewRaw from '@/views/perf/PerfRunDetailView.vue?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

describe('T376 b6 U-7: stop flow names the object, blocks double-click, receipts with the id', () => {
  it('stage 1: confirm text is the parameterized cost statement (run id + collected seconds)', () => {
    expect(viewRaw).toMatch(/perf\.run\.stopCost/)
    expect(viewRaw).toMatch(/id:\s*run\.value\.id/)
    expect(viewRaw).toMatch(/collected:\s*collectedShown\.value/)
    // The old generic one-liner is gone from the template chain.
    expect(viewRaw).not.toMatch(/t\('perf\.run\.stopConfirm'\)/)
  })

  it('stage 2: stop button is loading+disabled through the request and guarded re-entrantly', () => {
    expect(viewRaw).toMatch(/:loading="stopPending"/)
    expect(viewRaw).toMatch(/:disabled="!run \|\| stopPending"/)
    // The action sets the flag before awaiting and clears it in a finally block.
    expect(viewRaw).toMatch(/if \(stopPending\.value \|\| !run\.value\) return/)
    expect(viewRaw).toMatch(/stopPending\.value = true/)
    const stopBlock = viewRaw.slice(viewRaw.indexOf('async function stopRun'))
    expect(stopBlock.slice(0, 1200)).toMatch(/finally\s*\{[\s\S]*stopPending\.value = false/)
  })

  it('stage 3: the receipt toast carries the run id and the stopped-task count', () => {
    expect(viewRaw).toMatch(/t\('perf\.run\.stopOk',\s*\{\s*id:\s*run\.value\.id,\s*n\s*\}\)/)
  })
})

describe('T376 b6 U-6: terminal freeze annotation and honest tail-loss surfacing', () => {
  it('frozen-curve note renders in the chart card once the run is terminal', () => {
    expect(viewRaw).toContain('data-test="perf-run-curve-frozen"')
    expect(viewRaw).toMatch(/v-else-if="!isRunning"[\s\S]{0,200}curve-frozen|curve-frozen[^]*isRunning/)
  })

  it('a stored data-loss event is surfaced on the page itself (not only in the drawer)', () => {
    expect(viewRaw).toContain('data-test="perf-run-data-loss"')
    expect(viewRaw).toMatch(/events\.value\.find\(\(e\) => e\.event_kind === 'data-loss'\)|events\.value\.find\(e => e\.event_kind === 'data-loss'\)/)
  })
})

describe('T376 b6 i18n dual track: every new user-facing key exists in both locales', () => {
  for (const key of ['stopCost', 'stopOk', 'curveFrozen', 'dataLossTitle']) {
    it(`zh-CN and en-US both define perf.run.${key}`, () => {
      const zhBlock = zhRaw.slice(zhRaw.indexOf('stopTitle:'), zhRaw.indexOf('chartEmpty:'))
      const enBlock = enRaw.slice(enRaw.indexOf('stopTitle:'), enRaw.indexOf('chartEmpty:'))
      expect(zhBlock).toContain(`${key}:`)
      expect(enBlock).toContain(`${key}:`)
    })
  }
})
