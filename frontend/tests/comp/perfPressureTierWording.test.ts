/**
 * Pressure-tier display wording contract (load-test tier shown as
 *
 * The tier a launch gate checks against the whitelist filing and the
 * approval request is a derived pressure tier; the legacy display word
 * names such as max_concurrency stay untouched — display wording only).
 *
 * Asserted here:
 * 1. zh locale carries no legacy word and uses the canonical phrase;
 * 2. en locale mirrors it (no "concurrency level" on the perf face);
 * information: what the number is checked against);
 * 4. the wizard template actually renders the two hint lines.
 */
import { describe, expect, it } from 'vitest'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'
import wizardRaw from '@/views/perf/PerfScenarioWizard.vue?raw'

describe('pressure-tier wording on the perf wizard face', () => {
  it('zh locale retired the legacy tier word', () => {
    expect(zhRaw).not.toContain('并发档位')
    expect(zhRaw).not.toContain('并发上限（档）')
    expect(zhRaw).toContain('压力档位（RPS）')
  })
  it('en locale mirrors the canonical phrase', () => {
    expect(enRaw).not.toContain('concurrency level')
    expect(enRaw).not.toContain('Requested concurrency level')
    expect(enRaw).toContain('pressure tier (RPS)')
  })
  it('both tier fields carry their own tooltip hint keys', () => {
    expect(zhRaw).toContain('maxConcurrencyTip')
    expect(zhRaw).toContain('concurrencyTip')
    expect(enRaw).toContain('maxConcurrencyTip')
    expect(enRaw).toContain('concurrencyTip')
  })
  it('wizard renders the two hint lines next to the tier inputs', () => {
    expect(wizardRaw).toContain('perf.wl.maxConcurrencyTip')
    expect(wizardRaw).toContain('perf.applied.concurrencyTip')
  })
  it('edit hydration carries the stored model so a loops-only FIXED_VU keeps the honest no-window estimate', () => {
    // Regression anchor: the edit lane once called applyPressureConfig(cfg)
    // without the model, so the honest zero-guard could not fire and the form
    // default leaked a fake one-minute window into the estimate line.
    expect(wizardRaw).toContain('applyPressureConfig(cfg, form.pressureModel)')
  })
})
