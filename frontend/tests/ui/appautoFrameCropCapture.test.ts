/**
 * Collaborative frame-capture surface L2 (W5 app-automation phase two P1-B4):
 *  - pure math single source = @/utils/frameCropCapture (display rectangle of the
 *    CSS-scaled <img> -> frame-pixel crop region the core capture API expects);
 *  - wiring guard: ExecMonitorWindow.vue must consume that single source and the
 *    /appauto/elements/capture bridge (no in-component coordinate re-derivation,
 *    no second capture client) — R40 duplication gate counterpart.
 * Rejections (degenerate box, zero-area selection, unloaded image) stay honest
 * nulls: the UI refuses silently-sized requests, core wording travels unmutated.
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { CROP_SCALE_PRESETS, toFrameRegion } from '@/utils/frameCropCapture'

const COMPONENT_SRC = readFileSync(
  new URL('../../src/components/ExecMonitorWindow.vue', import.meta.url),
  'utf-8',
)
const API_SRC = readFileSync(new URL('../../src/api/appauto.ts', import.meta.url), 'utf-8')

describe('frameCropCapture.toFrameRegion', () => {
  const box = { left: 100, top: 50, width: 360, height: 720 }

  it('maps a down-right drag through the natural/display scale factor', () => {
    const region = toFrameRegion(
      { clientX: 190, clientY: 150 },
      { clientX: 370, clientY: 510 },
      box, 1080, 2160,
    )
    // scale = 1080/360 = 3 and 2160/720 = 3; (190-100)*3=270, (150-50)*3=300
    expect(region).toEqual({ x: 270, y: 300, width: 540, height: 1080 })
  })

  it('normalizes an up-left (reversed) drag to the same positive rectangle', () => {
    const reversed = toFrameRegion(
      { clientX: 370, clientY: 510 },
      { clientX: 190, clientY: 150 },
      box, 1080, 2160,
    )
    expect(reversed).toEqual({ x: 270, y: 300, width: 540, height: 1080 })
  })

  it('clamps selections that overshoot the frame edges', () => {
    const region = toFrameRegion(
      { clientX: 50, clientY: 10 },
      { clientX: 600, clientY: 900 },
      box, 1080, 2160,
    )
    expect(region).toEqual({ x: 0, y: 0, width: 1080, height: 2160 })
  })

  it('returns null for degenerate inputs (zero-size box, unloaded image, sub-pixel selection)', () => {
    expect(toFrameRegion({ clientX: 0, clientY: 0 }, { clientX: 10, clientY: 10 },
      { left: 0, top: 0, width: 0, height: 100 }, 100, 100)).toBeNull()
    expect(toFrameRegion({ clientX: 0, clientY: 0 }, { clientX: 10, clientY: 10 },
      box, 0, 0)).toBeNull()
    // 1x1 display px on a 1:1 scale -> rounds below the minimum area
    expect(toFrameRegion({ clientX: 100, clientY: 100 }, { clientX: 101, clientY: 100 },
      box, 360, 720)).toBeNull()
  })

  it('exposes the preset scale list the dialog offers (1x/2x; empty = server default)', () => {
    expect(CROP_SCALE_PRESETS).toEqual([1, 2])
  })
})

describe('monitor window capture wiring (R40 single source)', () => {
  it('consumes the shared math module instead of re-deriving coordinates inline', () => {
    expect(COMPONENT_SRC).toContain("from '@/utils/frameCropCapture'")
    expect(COMPONENT_SRC).toContain('toFrameRegion(')
  })

  it('posts through the mirrored bridge API with stable test anchors', () => {
    expect(COMPONENT_SRC).toContain('captureElementFromFrame')
    expect(API_SRC).toContain("'/appauto/elements/capture'")
    for (const anchor of ['crop-toggle-btn', 'crop-selection', 'crop-name-input', 'crop-save-btn']) {
      expect(COMPONENT_SRC).toContain(anchor)
    }
  })

  it('keeps the disabled state honestly hinted (no silently dead button)', () => {
    expect(COMPONENT_SRC).toContain('cropNeedProject')
    expect(COMPONENT_SRC).toContain('cropNoFrame')
    expect(COMPONENT_SRC).toContain('cropMasked')
  })
})
