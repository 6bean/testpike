/**
 * GA-5 segment f-2: the pinned target version must surface on the faces that
 * consume the launch snapshot (RED first, then wiring).
 *
 * Contract under test:
 * - the run detail header reads pressure_snapshot.targetVersion through the
 * shared parse helper (no second snapshot parser) and names the version;
 * - a run whose environment carried no recorded build shows an honest
 * "not recorded" wording — never a blank row and never a guessed label;
 * - the compare block shows the per-side version for both A and B (the
 * upgrade-vs-baseline self-proof is the whole point of the pinning);
 * - i18n dual track: every new user-facing key exists in zh-CN and en-US;
 * - wording discipline: user-facing text carries operating information only
 * (no internal bookkeeping codes).
 * Posture follows perfRunStopCardT376b6.test.ts (?raw mirror assertions).
 */
import { describe, expect, it } from 'vitest'
import viewRaw from '@/views/perf/PerfRunDetailView.vue?raw'
import reportRaw from '@/views/perf/PerfReportView.vue?raw'
import utilsRaw from '@/views/perf/chartUtils.ts?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

describe('GA-5 f-2 shared parse helper: single source for the snapshot target version', () => {
  it('chartUtils exports targetVersionOf reading the pinned snapshot value', () => {
    expect(utilsRaw).toMatch(/export function targetVersionOf/)
    expect(utilsRaw).toMatch(/targetVersion/)
  })
})

describe('GA-5 f-2 run detail header shows the pinned target version honestly', () => {
  it('the version line exists and is keyed for E2E', () => {
    expect(viewRaw).toContain('data-test="perf-run-target-version"')
    expect(viewRaw).toMatch(/targetVersionOf\(run\.value\?\.pressure_snapshot\)/)
  })

  it('a null version falls to the not-recorded wording (never blank, never guessed)', () => {
    expect(viewRaw).toMatch(/perf\.run\.targetVersion'/)
    expect(viewRaw).toMatch(/perf\.run\.targetVersionMissing'/)
  })
})

describe('GA-5 f-2 compare block names the version on both sides', () => {
  it('A and B rows each carry a version anchor fed by the snapshot helper', () => {
    expect(reportRaw).toContain('data-test="perf-compare-version-a"')
    expect(reportRaw).toContain('data-test="perf-compare-version-b"')
    // Both rows go through the shared cmpVersion reader (single snapshot parser, no fork).
    expect(reportRaw).toMatch(/cmpVersion\(compare\.runA\)/)
    expect(reportRaw).toMatch(/cmpVersion\(compare\.runB\)/)
    expect(reportRaw).toMatch(/function cmpVersion[\s\S]*targetVersionOf\(r\.pressure_snapshot\)/)
  })
})

describe('GA-5 f-2 i18n dual track and wording discipline', () => {
  for (const key of ['targetVersion', 'targetVersionMissing']) {
    it(`zh-CN and en-US both define perf.run.${key}`, () => {
      const zhBlock = zhRaw.slice(zhRaw.indexOf('dataLossTitle:'), zhRaw.indexOf('chartEmpty:'))
      const enBlock = enRaw.slice(enRaw.indexOf('dataLossTitle:'), enRaw.indexOf('chartEmpty:'))
      expect(zhBlock).toContain(`${key}:`)
      expect(enBlock).toContain(`${key}:`)
    })
  }

  it('the version wording is operating information — no internal bookkeeping codes', () => {
    // lastIndexOf anchors the perf.run.* pair (earlier targetVersion keys live in
    // the upgrade-center namespace).
    const zhBlock = zhRaw.slice(zhRaw.lastIndexOf('targetVersion:'), zhRaw.lastIndexOf('targetVersionMissing:'))
    expect(zhBlock).toMatch(/被测版本/)
    expect(zhBlock).not.toMatch(/T\d{3}|GA-5|§\d|裁决|棘轮|收口/)
  })
})
