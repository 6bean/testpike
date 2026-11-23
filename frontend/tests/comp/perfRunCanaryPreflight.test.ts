/**
 * CF-6 segment f-3 frontend half: the canary pre-flight outcome pinned in
 * the launch snapshot must surface on the run detail header (RED first).
 *
 * Contract under test:
 * - chartUtils exports canaryOf reading the snapshot's nested "canary" object
 * through the shared parser (no second snapshot parser, red line 6);
 * - the run detail header carries a canary line keyed for E2E, shown only when
 * the snapshot actually recorded a pre-flight — a run launched with the
 * switch off shows nothing at all (honest absence, never an empty row);
 * - the line names answered/probes counts, the measured baseline p95 and the
 * engine, and adds the compile pre-warm clause only when prewarmed is true;
 * - i18n dual track: every new user-facing key exists in zh-CN and en-US;
 * - wording discipline: user-facing text carries operating information only
 * (no internal bookkeeping codes).
 * Posture follows perfRunTargetVersion.test.ts (?raw mirror assertions).
 */
import { describe, expect, it } from 'vitest'
import viewRaw from '@/views/perf/PerfRunDetailView.vue?raw'
import utilsRaw from '@/views/perf/chartUtils.ts?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

describe('CF-6 f-3 shared parse helper: single source for the canary snapshot block', () => {
  it('chartUtils exports canaryOf reading the nested canary outcome', () => {
    expect(utilsRaw).toMatch(/export function canaryOf/)
    expect(utilsRaw).toMatch(/parseSnapshot\(snap\)\?\.canary/)
  })

  it('canaryOf normalizes the five pinned fields and rejects malformed shapes', () => {
    // Counts and baseline must be numbers; the helper guards each type instead
    // of trusting the payload, returning null when the block is unusable.
    // (Guard written in negative form — reject what is not a number.)
    expect(utilsRaw).toMatch(/typeof .*\.probes [!=]== 'number'/)
    expect(utilsRaw).toMatch(/typeof .*\.answered [!=]== 'number'/)
    expect(utilsRaw).toMatch(/baselineP95Ms/)
    expect(utilsRaw).toMatch(/prewarmed/)
  })
})

describe('CF-6 f-3 run detail header shows the pre-flight honestly', () => {
  it('the canary line exists, is keyed for E2E and consumed through the helper', () => {
    expect(viewRaw).toContain('data-test="perf-run-canary"')
    expect(viewRaw).toMatch(/canaryOf\(run\.value\?\.pressure_snapshot\)/)
  })

  it('absence of a recorded pre-flight renders nothing (switch off = zero drift)', () => {
    // v-if on the computed canary object — never a placeholder row.
    expect(viewRaw).toMatch(/v-if="canary"/)
    expect(viewRaw).not.toMatch(/canaryMissing/)
  })

  it('the pre-warm clause is conditional on the pinned prewarmed flag', () => {
    expect(viewRaw).toMatch(/canary\.prewarmed/)
    expect(viewRaw).toMatch(/perf\.run\.canaryPrewarmed'/)
  })
})

describe('CF-6 f-3 i18n dual track and wording discipline', () => {
  for (const key of ['canarySummary', 'canaryPrewarmed']) {
    it(`zh-CN and en-US both define perf.run.${key}`, () => {
      const zhBlock = zhRaw.slice(zhRaw.indexOf('dataLossTitle:'), zhRaw.indexOf('chartEmpty:'))
      const enBlock = enRaw.slice(enRaw.indexOf('dataLossTitle:'), enRaw.indexOf('chartEmpty:'))
      expect(zhBlock).toContain(`${key}:`)
      expect(enBlock).toContain(`${key}:`)
    })
  }

  it('the pre-flight wording is operating information — no internal bookkeeping codes', () => {
    const zhBlock = zhRaw.slice(zhRaw.lastIndexOf('canarySummary:'), zhRaw.lastIndexOf('chartEmpty:'))
    expect(zhBlock).toMatch(/预检/)
    expect(zhBlock).not.toMatch(/T\d{3}|CF-6|§\d|裁决|棘轮|收口|人话|拒件|单源/)
  })
})
