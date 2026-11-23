/**
 * (B-10) report-face TLS tier line: the pinned verification tier
 * must surface on the run detail header through the shared snapshot reader.
 *
 * Contract under test:
 * - chartUtils ships a single tlsTierOf reader over the shared parseSnapshot
 * (no second snapshot parser);
 * - the run detail header carries a tier line keyed for E2E and states the
 * tier (strict / custom_ca / insecure) or an honest "not recorded" when the
 * snapshot predates the pin — never a fabricated tier;
 * - insecure keeps a standing yellow hint on the same page (the W1a exec
 * result yellow-hint precedent);
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

describe('B-10 4c shared parse helper: single source for the snapshot TLS tier', () => {
  it('chartUtils exports tlsTierOf reading the pinned snapshot value', () => {
    expect(utilsRaw).toMatch(/export function tlsTierOf/)
    expect(utilsRaw).toMatch(/parseSnapshot\(snap\)\?\.tlsMode/)
  })
})

describe('B-10 4c run detail header shows the pinned TLS tier honestly', () => {
  it('the tier line exists and is keyed for E2E', () => {
    expect(viewRaw).toContain('data-test="perf-run-tls-tier"')
    expect(viewRaw).toMatch(/tlsTierOf\(run\.value\?\.pressure_snapshot\)/)
  })

  it('all three tiers plus the missing case have their own wording', () => {
    for (const key of ['tlsTierStrict', 'tlsTierCustomCa', 'tlsTierInsecure', 'tlsTierMissing']) {
      expect(viewRaw).toContain(`perf.run.${key}`)
    }
  })

  it('insecure keeps a standing yellow hint (never silent)', () => {
    expect(viewRaw).toContain('data-test="perf-run-tls-insecure-hint"')
    expect(viewRaw).toMatch(/tlsTier === 'insecure'/)
  })
})

describe('B-10 4c i18n dual track and wording discipline', () => {
  for (const key of [
    'tlsTierStrict',
    'tlsTierCustomCa',
    'tlsTierInsecure',
    'tlsTierInsecureHint',
    'tlsTierMissing',
  ]) {
    it(`zh-CN and en-US both define perf.run.${key}`, () => {
      const zhBlock = zhRaw.slice(zhRaw.indexOf('tlsTierStrict:'), zhRaw.indexOf('canarySummary:'))
      const enBlock = enRaw.slice(enRaw.indexOf('tlsTierStrict:'), enRaw.indexOf('canarySummary:'))
      expect(zhBlock).toContain(`${key}:`)
      expect(enBlock).toContain(`${key}:`)
    })
  }

  it('the tier wording is operating information — no internal bookkeeping codes', () => {
    const zhBlock = zhRaw.slice(zhRaw.indexOf('tlsTierStrict:'), zhRaw.indexOf('canarySummary:'))
    expect(zhBlock).toMatch(/TLS 校验档/)
    expect(zhBlock).not.toMatch(/T\d{3}|B-10|§\d|W1a|裁决|棘轮|收口|切片/)
  })
})
