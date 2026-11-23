/**
 * U-2 pressure-curve preview in the perf wizard .
 *
 * Defect this segment closes: step ② of the wizard renders seven radio groups of
 * numbers only — authors cannot see the load shape they are declaring, and the
 * old wording invited reading "peak RPS" as "concurrent users" (a units error
 * the SPIKE/VU bugs proved real). This segment adds a pure-frontend SVG preview
 * derived with the SAME arithmetic as the server compilation (PerfSpikeSegments
 * / PerfCurveSegments mirrors, display-only, never a scheduling claim) plus a
 * caliber correction line naming the vertical axis unit honestly.
 *
 * Contract asserted here:
 * 1. CONSTANT/RAMP/flat models produce a window equal to the declared duration;
 * 2. SPIKE legs follow the server share rule (duration/5 floor ≥1 per rise/hold/
 * fall, tail absorbs the remainder) and the pulse returns to base;
 * 3. LADDER climbs startRps + i*stepRps per level, last level = declared peak;
 * 4. BUSINESS_CURVE emits only active hours inside the window, rate floors at 1;
 * 5. FIXED_VU loops-only (no wall-clock window) yields an honest null preview —
 * never a zero-length or NaN curve;
 * 6. the wizard wires the preview (data-test anchors) and the caliber line with
 * i18n dual-track keys in both locales (no hardcoded CJK in the template).
 */
import { describe, expect, it } from 'vitest'
import { derivePressurePreview } from '@/views/perf/pressurePreview'
import wizardRaw from '@/views/perf/PerfScenarioWizard.vue?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

describe('T377 c-4 U-2 pressure preview derivation', () => {
  it('CONSTANT_RPS is a flat line over the declared window', () => {
    const p = derivePressurePreview('CONSTANT_RPS', { targetRps: 50, durationSeconds: 60 })
    expect(p).not.toBeNull()
    expect(p!.totalSeconds).toBe(60)
    expect(p!.peak).toBe(50)
    expect(p!.unit).toBe('RPS')
    expect(p!.points).toEqual([[0, 50], [60, 50]])
  })

  it('RAMP rises start→peak then holds peak for holdSeconds', () => {
    const p = derivePressurePreview('RAMP', { startRps: 10, peakRps: 200, rampSeconds: 30, holdSeconds: 60 })
    expect(p!.totalSeconds).toBe(90)
    expect(p!.points).toEqual([[0, 10], [30, 200], [90, 200]])
  })

  it('SPIKE follows the server share rule (duration/5 legs, tail remainder)', () => {
    const p = derivePressurePreview('SPIKE', { baseRps: 20, peakRps: 200, durationSeconds: 100 })
    // rise/hold/fall = 20 each, tail = 40 — same arithmetic as PerfSpikeSegments.derive
    expect(p!.points).toEqual([[0, 20], [20, 200], [40, 200], [60, 20], [100, 20]])
    expect(p!.totalSeconds).toBe(100)
  })

  it('LADDER climbs one stepRps per level and ends at the declared peak', () => {
    const p = derivePressurePreview('LADDER', { startRps: 10, stepRps: 20, stepSeconds: 60, ladderSteps: 5 })
    expect(p!.totalSeconds).toBe(300)
    expect(p!.peak).toBe(110) // startRps + stepRps*steps, mirrors peakConcurrency
    const firsts = p!.points.filter((pt, i) => i === 0 || pt[1] !== p!.points[i - 1][1])
    expect(firsts.map((pt) => pt[1])).toEqual([10, 30, 50, 70, 90, 110])
  })

  it('BUSINESS_CURVE emits only active hours in-window with a rate floor of 1', () => {
    const p = derivePressurePreview('BUSINESS_CURVE', {
      peakRps: 200, durationSeconds: 7200, curve1: 50, curve3: 10, curve4: 0,
    })
    // hour 2 (weight 0/absent) never becomes a fake zero-load segment; hour 4 is
    // outside the 2-hour window; rates = round(peak*weight/100) floored at 1.
    const steps = p!.points
    expect(steps.some((pt) => pt[0] === 3600 && pt[1] === 100)).toBe(true)
    expect(steps.every((pt) => pt[0] <= 7200)).toBe(true)
    expect(steps.some((pt) => pt[1] === 0)).toBe(false)
    expect(p!.peak).toBe(100)
    expect(p!.totalSeconds).toBe(7200)
  })

  it('FIXED_VU loops-only declares no window and yields an honest null', () => {
    expect(derivePressurePreview('FIXED_VU', { vu: 50, loops: 10, durationSeconds: 0 })).toBeNull()
  })

  it('FIXED_VU with a window names the vertical axis in VU, not RPS', () => {
    const p = derivePressurePreview('FIXED_VU', { vu: 40, loops: null, durationSeconds: 300 })
    expect(p!.unit).toBe('VU')
    expect(p!.peak).toBe(40)
  })
})

describe('T377 c-4 U-2 wizard wiring and copy', () => {
  it('the wizard renders the preview and the caliber line behind data-test anchors', () => {
    expect(wizardRaw).toContain('data-test="pw-pressure-preview"')
    expect(wizardRaw).toContain('data-test="pw-pressure-caliber"')
    expect(wizardRaw).toContain("from './pressurePreview'")
    // display-only guard: the preview must never claim scheduling authority
    expect(wizardRaw).toMatch(/derivePressurePreview\(/)
    // caliber binds the declared MODEL (unit truth holds even when the preview
    // is an honest null — loops-only FIXED_VU still measures the axis in VU)
    expect(wizardRaw).toMatch(/pw-pressure-caliber[\s\S]{0,120}form\.pressureModel === 'FIXED_VU'/)
  })

  it('the vision close-out keeps the card padding symmetric and the title weighted', () => {
    // vision fix (dark/light ladder + fixed-vu states): the caliber line is the
    // card's last row — its --block 16px bottom margin fused an asymmetric
    // inner gutter; the title needs a weight step above the hint rows.
    expect(wizardRaw).toMatch(/\.pw__preview \.pw__hint--block \{ margin-bottom: 0; \}/)
    expect(wizardRaw).toMatch(/\.pw__preview-title \{[^}]*font-weight: 600/)
    // vision fix round 2: the flat full-height line read as an empty box —
    // a zero-value baseline reference keeps the shape visually anchored.
    expect(wizardRaw).toMatch(/pw__preview-axis/)
  })

  it('i18n dual track carries the preview title and caliber keys', () => {
    for (const raw of [zhRaw, enRaw]) {
      expect(raw).toContain('previewNone:')
      expect(raw).toContain('caliberRps:')
      expect(raw).toContain('caliberVu:')
    }
    // anchor inside the wizard section to dodge the unrelated replay previewTitle
    for (const raw of [zhRaw, enRaw]) {
      const from = raw.indexOf('curveFilledChip')
      const wizardSlice = raw.slice(from, raw.indexOf('estimate:', from))
      expect(from).toBeGreaterThan(-1)
      expect(wizardSlice).toContain('previewTitle:')
    }
  })
})
