/**
 * Focus reveal contract for wide tables with pinned columns (X-2 posture).
 *
 * Gates:
 *  1) pure geometry revealDelta frees controls covered (partially or wholly)
 *     by right/left pinned overlays, leaves clear controls alone;
 *  2) scanPinnedEdges reduces multiple sticky cells to the widest left band
 *     edge and the narrowest right band edge;
 *  3) main.ts installs the enhancer exactly once;
 *  4) page-level focus/scroll patches must not appear (baseline 0) — the fix
 *     is shared, pages never re-solve it locally.
 * The real-browser truth (tabbing a header caret scrolls the pinned overlay
 * clear) is asserted by the login-states vision window's UX leg (X-2 = 0),
 * not here — static binding alone cannot prove the wiring behaves.
 */
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  REVEAL_MARGIN,
  mirrorBandScroll,
  revealDelta,
  scanPinnedEdges,
} from '../../src/utils/tableFocusReveal'

const SRC = new URL('../../src', import.meta.url).pathname
const MAIN = readFileSync(join(SRC, 'main.ts'), 'utf-8')

function walk(dir: string, out: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name)
    if (statSync(p).isDirectory()) {
      if (name !== 'node_modules') walk(p, out)
    } else if (name.endsWith('.vue')) {
      out.push(p)
    }
  }
  return out
}

describe('revealDelta (pure focus-reveal geometry)', () => {
  it('scrolls right to free a caret wholly under a right-pinned overlay', () => {
    // live X-2 evidence: caret at [1302,1326] behind the pinned action
    // column starting at x=1207 (1440 viewport, login-state ledger)
    expect(revealDelta({ left: 1302, right: 1326 }, null, 1207)).toBeCloseTo(
      1326 + REVEAL_MARGIN - 1207,
    )
  })

  it('scrolls right to free a partially covered wide control', () => {
    expect(revealDelta({ left: 1250, right: 1500 }, null, 1207)).toBeCloseTo(
      1500 + REVEAL_MARGIN - 1207,
    )
  })

  it('scrolls left to free a control behind a left-pinned overlay', () => {
    expect(revealDelta({ left: 40, right: 64 }, 120, null)).toBeCloseTo(
      40 - REVEAL_MARGIN - 120,
    )
  })

  it('leaves clear controls untouched', () => {
    expect(revealDelta({ left: 100, right: 124 }, null, 1207)).toBe(0)
    expect(revealDelta({ left: 200, right: 224 }, 120, null)).toBe(0)
    expect(revealDelta({ left: 100, right: 124 }, null, null)).toBe(0)
  })

  it('exposes the margin as a tunable parameter, not a scattered literal', () => {
    expect(REVEAL_MARGIN).toBeGreaterThan(0)
    expect(revealDelta({ left: 1302, right: 1326 }, null, 1207, 16)).toBeCloseTo(1326 + 16 - 1207)
  })
})

describe('scanPinnedEdges (sticky overlay reduction)', () => {
  it('takes the widest left band and the narrowest right band', () => {
    const edges = scanPinnedEdges([
      { side: 'right', left: 1207, right: 1407 },
      { side: 'right', left: 1250, right: 1450 },
      { side: 'left', left: 0, right: 120 },
      { side: 'left', left: 0, right: 160 },
    ])
    expect(edges).toEqual({ left: 160, right: 1207 })
  })

  it('returns nulls when the table pins nothing', () => {
    expect(scanPinnedEdges([])).toEqual({ left: null, right: null })
  })
})

describe('mirrorBandScroll (EP 2.14 header geometry fix)', () => {
  // EP 2.14 syncs .el-table__header-wrapper scrollLeft from the body
  // scrollbar's "scroll" event ONLY (style-helper syncPosition), and scroll
  // events fire asynchronously. A focus hit-test in the same turn must see
  // the moved header, so the enhancer mirrors the reveal delta onto the
  // band wrappers synchronously. node-env contract via duck-typed stubs.
  type Band = { scrollLeft: number }
  function fakeTable(bands: Record<string, Band | null>) {
    const present = Object.values(bands).filter((b): b is Band => b !== null)
    return {
      querySelector: (sel: string) => bands[sel] ?? null,
      querySelectorAll: () => present,
    } as unknown as Element
  }
  const HEADER = '.el-table__header-wrapper'
  const FOOTER = '.el-table__footer-wrapper'

  it('applies the same delta to header and footer bands synchronously', () => {
    const header = { scrollLeft: 96 }
    const footer = { scrollLeft: 96 }
    mirrorBandScroll(fakeTable({ [HEADER]: header, [FOOTER]: footer }), 127)
    expect(header.scrollLeft).toBe(223)
    expect(footer.scrollLeft).toBe(223)
  })

  it('keeps body and header aligned by mirroring, not replacing, the body scroll', () => {
    // revealFocused first moves the body wrap by the same delta; the mirror
    // lands the band on the identical value, so EP's async sync is a no-op.
    const header = { scrollLeft: 0 }
    mirrorBandScroll(fakeTable({ [HEADER]: header }), 127)
    expect(header.scrollLeft).toBe(127)
  })

  it('is a no-op for delta 0 and survives tables without a footer band', () => {
    const header = { scrollLeft: 40 }
    const table = fakeTable({ [HEADER]: header, [FOOTER]: null })
    mirrorBandScroll(table, 0)
    expect(header.scrollLeft).toBe(40)
    expect(() => mirrorBandScroll(table, 127)).not.toThrow()
    expect(header.scrollLeft).toBe(167)
  })

  it('revealFocused wiring: source mirrors bands after nudging the body wrap', () => {
    const src = readFileSync(join(SRC, 'utils', 'tableFocusReveal.ts'), 'utf-8')
    expect(src).toMatch(/wrap\.scrollLeft \+= delta/)
    expect(src).toMatch(/mirrorBandScroll\(table, delta\)/)
    // guard against reverting to the async-only posture (defect)
    expect(src).not.toMatch(/await .*syncPosition/)
  })
})

describe('single-source wiring', () => {
  it('main.ts installs the focus reveal enhancer exactly once', () => {
    expect(MAIN.match(/installTableFocusReveal\(\)/g)?.length).toBe(1)
  })

  it('no page carries a local focus-reveal/scroll patch', () => {
    const offenders: string[] = []
    for (const file of walk(join(SRC, 'views'))) {
      const text = readFileSync(file, 'utf-8')
      if (/focusin[\s\S]{0,200}scrollLeft/.test(text)) offenders.push(file)
    }
    expect(offenders).toEqual([])
  })
})
