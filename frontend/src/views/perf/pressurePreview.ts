/**
 * U-2 pressure-curve preview derivation .
 *
 * Turns the wizard's declared pressure model + parameters into a polyline the
 * author can see before submitting. The arithmetic mirrors the server-side
 * compilation single sources (PerfSpikeSegments share rule, PerfCurveSegments
 * hour-segment rule, PerfBudgetPolicy window derivation) so the picture and
 * the run
 * never disagree — but this file claims NO scheduling authority: the core run
 * leg stays the only place a window is decided.
 *
 * A null return is an honest answer, not an error: models with no wall-clock
 * claim (FIXED_VU loops-only) have no curve to draw, and the wizard shows the
 * "no fixed window" line instead of a fake zero-length shape.
 */

export interface PressurePreview {
  /** [t seconds, rate] vertices in time order — step-shaped for LADDER/curve. */
  points: [number, number][]
  /** Declared total window in seconds (matches the estimated-machine-time line). */
  totalSeconds: number
  /** Maximum value reached on the vertical axis. */
  peak: number
  /** Vertical-axis unit: RPS for load models, VU for the closed-pool model. */
  unit: 'RPS' | 'VU'
  /** 'step' = value changes are instantaneous jumps (segment executors);
   * 'linear' = the engine ramps between vertices (ramping executors). */
  shape: 'linear' | 'step'
}

type Params = Record<string, number | null | undefined>

function num(p: Params, key: string, fallback = 0): number {
  const v = p[key]
  return typeof v === 'number' && Number.isFinite(v) ? v : fallback
}

/**
 * SPIKE leg timing — same rule as PerfSpikeSegments.derive (server single
 * source): rise/hold/fall each take floor(duration/5) with a 1s floor, the
 * tail absorbs the remainder so the legs always sum to the declared window.
 */
function spikeLegs(durationSeconds: number): [number, number, number, number] {
  if (durationSeconds < 4) {
    return [1, 1, 1, Math.max(durationSeconds - 3, 1)]
  }
  const share = Math.max(Math.floor(durationSeconds / 5), 1)
  const tail = Math.max(durationSeconds - share * 3, 1)
  return [share, share, share, tail]
}

/**
 * Derive the preview polyline for one declared model. Returns null when the
 * model declares no wall-clock window (nothing honest to draw).
 */
export function derivePressurePreview(
  model: string,
  p: Params,
): PressurePreview | null {
  switch (model) {
    case 'CONSTANT_RPS': {
      const dur = num(p, 'durationSeconds')
      const rate = num(p, 'targetRps')
      if (dur <= 0 || rate <= 0) return null
      return { points: [[0, rate], [dur, rate]], totalSeconds: dur, peak: rate, unit: 'RPS', shape: 'step' }
    }
    case 'RAMP': {
      const ramp = num(p, 'rampSeconds')
      const hold = num(p, 'holdSeconds')
      const total = ramp + hold
      const start = num(p, 'startRps')
      const peak = num(p, 'peakRps')
      if (total <= 0 || peak <= 0) return null
      // Rise is linear in k6/Gatling arrival terms: two vertices are enough to
      // draw the slope; hold stays flat at peak.
      const points: [number, number][] = [[0, start], [ramp, peak]]
      if (hold > 0) points.push([total, peak])
      return { points, totalSeconds: total, peak, unit: 'RPS', shape: 'linear' }
    }
    case 'SPIKE': {
      const dur = num(p, 'durationSeconds')
      const base = num(p, 'baseRps')
      const peak = num(p, 'peakRps')
      if (dur <= 0 || peak <= 0) return null
      const [rise, hold, fall, tail] = spikeLegs(dur)
      const tRise = rise
      const tHold = rise + hold
      const tFall = rise + hold + fall
      const points: [number, number][] = [
        [0, base], [tRise, peak], [tHold, peak], [tFall, base],
      ]
      if (tail > 0) points.push([tFall + tail, base])
      return { points, totalSeconds: dur, peak, unit: 'RPS', shape: 'linear' }
    }
    case 'FIXED_VU': {
      const dur = num(p, 'durationSeconds')
      const vu = num(p, 'vu')
      // loops-only (duration 0) declares no window — honest null, no fake shape.
      if (dur <= 0 || vu <= 0) return null
      return { points: [[0, vu], [dur, vu]], totalSeconds: dur, peak: vu, unit: 'VU', shape: 'step' }
    }
    case 'LADDER': {
      const stepSec = num(p, 'stepSeconds')
      const steps = num(p, 'ladderSteps')
      const start = num(p, 'startRps')
      const stepRps = num(p, 'stepRps')
      const total = stepSec * steps
      if (total <= 0 || steps <= 0) return null
      // Staircase mirror of K6ScriptTemplate.ladderBlock / the Gatling twin:
      // level i (1..steps) walks to startRps + i*stepRps and holds stepSeconds,
      // so the drawn top equals the declared ceiling the pre-check names.
      const points: [number, number][] = [[0, start]]
      for (let i = 1; i <= steps; i++) {
        points.push([i * stepSec, start + stepRps * i])
      }
      return { points, totalSeconds: total, peak: start + stepRps * steps, unit: 'RPS', shape: 'linear' }
    }
    case 'SOAK': {
      const hours = num(p, 'durationHours')
      const rate = num(p, 'targetRps')
      const total = hours * 3600
      if (total <= 0 || rate <= 0) return null
      return { points: [[0, rate], [total, rate]], totalSeconds: total, peak: rate, unit: 'RPS', shape: 'step' }
    }
    case 'BUSINESS_CURVE': {
      const dur = num(p, 'durationSeconds')
      const peakRps = num(p, 'peakRps')
      if (dur <= 0 || peakRps <= 0) return null
      // Hour segments mirror PerfCurveSegments.derive: only active hours inside
      // the window become segments (idle hours stay absent, never a fake 0-rate
      // stage) and every rate floors at 1 so a small weight compiles a trickle
      // instead of silence.
      const points: [number, number][] = []
      let drawnPeak = 0
      let prevRate: number | null = null
      for (let hour = 0; hour < 24; hour++) {
        const from = hour * 3600
        if (from >= dur) break
        const weight = num(p, `curve${hour}`)
        // Idle hours collapse into the previous segment: carry the last drawn
        // rate forward as a flat line so the picture stays continuous without
        // inventing a zero-load stage the server would never emit.
        const rate: number | null = weight > 0 ? Math.max(1, Math.round((peakRps * weight) / 100)) : prevRate
        if (rate === null) continue
        if (prevRate === null || rate !== prevRate) points.push([from, rate])
        drawnPeak = Math.max(drawnPeak, rate)
        prevRate = rate
      }
      if (!points.length || drawnPeak <= 0) return null
      points.push([dur, prevRate ?? points[points.length - 1][1]])
      return { points, totalSeconds: dur, peak: drawnPeak, unit: 'RPS', shape: 'step' }
    }
    default:
      return null
  }
}

/**
 * Map a preview to an SVG polyline path inside a width×height box (pure
 * geometry, no DOM). Step shapes draw the jump vertically at the vertex time
 * (segment executors change rate instantly); linear shapes ramp between
 * vertices (ramping executors). Returned path starts at t=0 of the box.
 */
export function previewPath(
  pv: PressurePreview,
  width = 320,
  height = 96,
): string {
  const pad = 6
  const tMax = pv.totalSeconds || 1
  const vMax = pv.peak || 1
  const x = (t: number) => pad + (t / tMax) * (width - pad * 2)
  const y = (v: number) => height - pad - (v / vMax) * (height - pad * 2)
  const pts = pv.points
  if (!pts.length) return ''
  let d = `M ${x(pts[0][0]).toFixed(1)} ${y(pts[0][1]).toFixed(1)}`
  for (let i = 1; i < pts.length; i++) {
    if (pv.shape === 'step') {
      d += ` L ${x(pts[i][0]).toFixed(1)} ${y(pts[i - 1][1]).toFixed(1)}`
    }
    d += ` L ${x(pts[i][0]).toFixed(1)} ${y(pts[i][1]).toFixed(1)}`
  }
  return d
}
