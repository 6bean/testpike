/**
 * close-out rectification contract (vision round items A/B/C).
 *
 * A. The per-step parameter fold toggle must read as verb + object and
 * track its own state (show / hide parameters) — a bare noun tells
 * the user nothing about what the click does (copy group: main buttons
 * verb+object; state-linked labels name the consequence).
 * B. The step toolbar sits one band-gap (16px, theme single source) below
 * the step list, and the list heads itself with an honest step-count
 * line so the worst scale is disclosed before scrolling.
 * C. The expected-status placeholder is one plain sentence (no nested
 * parenthesis stacking) that still names the consequence of leaving it
 * unset.
 *
 * i18n dual-track: every key asserted here exists in both locales.
 */
import { describe, expect, it } from 'vitest'
import wizardRaw from '@/views/perf/PerfScenarioWizard.vue?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

describe('T442 rectification A: state-linked verb-object fold toggle', () => {
  it('toggle label switches with the open state instead of a bare noun', () => {
    // Re-anchored after the windowed step-table refactor (commit 971df70b): the
    // row loop variable became entry.index, so the state-linked toggle and the
    // aria-expanded channel both read isOpen(entry.index).
    expect(wizardRaw).toContain("isOpen(entry.index) ? t('perf.wizard.paramCollapse') : t('perf.wizard.paramExpand')")
    expect(wizardRaw).not.toContain("t('perf.wizard.stepParams')")
    // aria-expanded stays the machine-readable state channel.
    expect(wizardRaw).toContain(':aria-expanded="isOpen(entry.index)"')
  })

  it('both toggle keys exist in both locales with the shipped wording', () => {
    expect(zhRaw).toContain("paramExpand: '展开参数'")
    expect(zhRaw).toContain("paramCollapse: '收起参数'")
    expect(enRaw).toContain("paramExpand: 'Show parameters'")
    expect(enRaw).toContain("paramCollapse: 'Hide parameters'")
    // The bare-noun key is retired on both tracks (no orphan copy).
    expect(zhRaw).not.toContain('stepParams:')
    expect(enRaw).not.toContain('stepParams:')
  })
})

describe('T442 rectification B: band gap + honest step count', () => {
  it('step toolbar leads with the 16px band gap (theme single source)', () => {
    expect(wizardRaw).toMatch(/\.pw__step-toolbar \{[^}]*margin-top: 16px/)
  })

  it('step list heads itself with a step-count disclosure line', () => {
    expect(wizardRaw).toContain('data-test="pw-steps-count"')
    expect(wizardRaw).toContain("t('perf.wizard.stepsCount'")
    expect(zhRaw).toContain('stepsCount:')
    expect(enRaw).toContain('stepsCount:')
  })
})

describe('T442 rectification C: plain expect-status placeholder', () => {
  it('placeholder drops nested parentheses but keeps the consequence', () => {
    expect(zhRaw).toMatch(/paramExpectNa: '[^']*'/)
    const zhNa = zhRaw.match(/paramExpectNa: '([^']*)'/)![1]
    const enNa = enRaw.match(/paramExpectNa: '([^']*)'/)![1]
    expect(zhNa).not.toContain('（')
    expect(enNa).not.toContain('(')
    expect(zhNa).toContain('未设置')
    expect(enNa.toLowerCase()).toContain('not set')
  })
})
