/**
 * U-8 report layering (knife f-4): the report block reads as a layered
 * story — conclusion on top, bottleneck clues, gold-run comparison lane,
 * copyable caliber footnote — plus the three leftovers from the previous
 * vision audit (auth-zero hover wording, breaker-threshold naming, spacing
 * rhythm). RED first, then wiring.
 *
 * Posture follows perfRunTargetVersion.test.ts (?raw mirror assertions).
 */
import { describe, expect, it } from 'vitest'
import reportRaw from '@/views/perf/PerfReportView.vue?raw'
import detailRaw from '@/views/perf/PerfRunDetailView.vue?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

describe('U-8 layer 2: bottleneck clue card inside the report', () => {
  it('the report consumes the error TopN read face (no second computation)', () => {
    expect(reportRaw).toMatch(/listPerfErrorTopN/)
    expect(reportRaw).toContain('data-test="perf-report-clues"')
  })
  it('a zero-error run says so honestly instead of an empty-looking card', () => {
    expect(reportRaw).toMatch(/perf\.report\.cluesEmpty/)
    expect(reportRaw).toMatch(/perf\.report\.cluesTitle/)
  })
})

describe('U-8 layer 3: gold-run lane in the comparison band', () => {
  it('a pinned golden run can be picked for comparison in one click', () => {
    expect(reportRaw).toMatch(/mark === 'GOLDEN'|mark === "GOLDEN"/)
    expect(reportRaw).toContain('data-test="perf-compare-golden"')
  })
})

describe('U-8 layer 4: the caliber footnote is copyable', () => {
  it('a copy button writes the caliber text to the clipboard with feedback', () => {
    expect(reportRaw).toContain('data-test="perf-report-copy-caliber"')
    expect(reportRaw).toMatch(/navigator\.clipboard\.writeText/)
    expect(reportRaw).toMatch(/perf\.report\.caliberCopied/)
  })
})

describe('U-8 leftover 1: auth metrics explain a zero instead of looking broken', () => {
  it('zero-valued auth cards carry a hover explanation', () => {
    expect(reportRaw).toMatch(/el-tooltip/)
    expect(reportRaw).toMatch(/perf\.report\.authNotUsed/)
  })
})

describe('U-8 leftover 2: breaker naming is consistent (threshold, not line)', () => {
  it('zh legend and hit banner say 熔断阈值 — no 熔断线 left on screen', () => {
    expect(zhRaw).toMatch(/legendBreaker: '熔断阈值/)
    expect(zhRaw).toMatch(/breakerHit: '错误率已触及熔断阈值/)
    expect(zhRaw).not.toMatch(/熔断线/)
  })
  it('en legend and hit banner say threshold — no "breaker line" left on screen', () => {
    expect(enRaw).toMatch(/legendBreaker: 'Breaker threshold/)
    expect(enRaw).toMatch(/breakerHit: 'Error rate reached the breaker threshold/)
    expect(enRaw).not.toMatch(/breaker line/)
  })
})

describe('U-8 leftover 3: spacing rhythm lands on the 4px grid', () => {
  it('the report legend sits 8px under the chart (was 6px, off-grid)', () => {
    expect(reportRaw).toMatch(/\.perf-report__legend \{[^}]*margin-top: 8px/)
  })
})

describe('U-8 vision close-out: the compare pick is searchable, notes carry no phantom swatch', () => {
  it('the run-B select is filterable (SE rule 1: >10 options must be searchable)', () => {
    expect(reportRaw).toMatch(/<el-select[^>]*filterable[^>]*data-test="perf-compare-select"/)
  })
  it('the breaker banner names the consequence instead of self-describing the mechanism', () => {
    expect(zhRaw).toMatch(/breakerHit: '[^']*压测已由服务端中止'/)
    expect(zhRaw).not.toMatch(/执行回报/)
    expect(enRaw).toMatch(/breakerHit: '[^']*stopped by the server'/)
    expect(enRaw).not.toMatch(/execution report/)
  })
  it('plain-text legend notes skip the series swatch slot (no phantom gap)', () => {
    expect(reportRaw).toMatch(/\.perf-report__legend \.lg-note::before \{ display: none; \}/)
    expect(detailRaw).toMatch(/\.perf-run__legend \.lg-note::before \{ display: none; \}/)
  })
})

describe('U-8 i18n dual track', () => {
  for (const key of ['cluesTitle', 'cluesEmpty', 'compareGolden', 'caliberCopied', 'authNotUsed', 'copy']) {
    it(`perf.report.${key} exists in zh-CN and en-US`, () => {
      const zhBlock = zhRaw.slice(zhRaw.indexOf("      title: '压测报告'"), zhRaw.indexOf("      ceiling: {"))
      const enBlock = enRaw.slice(enRaw.indexOf("      title: 'Load Test Report'"), enRaw.indexOf("      ceiling: {"))
      expect(zhBlock).toContain(`${key}:`)
      expect(enBlock).toContain(`${key}:`)
    })
  }

  it('new user-facing wording carries operating information only (no bookkeeping codes)', () => {
    const zhBlock = zhRaw.slice(zhRaw.indexOf("      title: '压测报告'"), zhRaw.indexOf("      ceiling: {"))
    expect(zhBlock).not.toMatch(/T\d{3}|U-8|§\d|裁决|棘轮|收口/)
  })
})
