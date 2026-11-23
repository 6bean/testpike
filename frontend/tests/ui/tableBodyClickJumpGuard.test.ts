/**
 * Contract for the plain-cell table body click jump guard
 * (src/utils/tableLabelFocusGuard.ts, installTableBodyClickJumpGuard).
 *
 * Background (requirements-page report 2026-11-22): Element Plus 2.14 renders
 * <tbody tabindex="-1">. A trusted click on a NON-interactive cell of a table
 * that overflows horizontally focuses that tbody and the browser reveals the
 * oversized body — the horizontal scrollbar snaps to the right edge inside one
 * click (live: requirements list at 1280px, scrollLeft 0 -> 466 = max). The
 * shared guard remembers the scroll position on the capture mousedown and
 * restores it synchronously in the matching tbody focusin, so no frame paints
 * the jump. Text selection and keyboard row navigation stay intact.
 *
 * Gates:
 *  1) pure truth table of isPlainCellBodyFocus — the guard arms exactly on the
 *     plain-cell-steals-focus-to-tbody shape and nothing else (labels belong
 *     to the label take-over, controls focus themselves);
 *  2) main.ts installs the guard exactly once (wiring cannot be silently
 *     dropped by a future refactor);
 *  3) pages/components must not add local focusin/scroll-restore patches
 *     (R40 single source; baseline = zero sites);
 *  4) the sibling reveal enhancer must skip container focus targets — it
 *     exists for controls covered by pinned overlays, never for a whole tbody.
 * The real-browser truth (cold click on the title cell keeps scrollLeft) is
 * asserted by tests/e2e/test_req_title_click_scroll.py — static binding alone
 * cannot prove the wiring behaves.
 */
import { readFileSync } from 'node:fs'
import { join, relative } from 'node:path'
import { describe, expect, it } from 'vitest'
import { isPlainCellBodyFocus, shouldTakeOverFocus } from '../../src/utils/tableLabelFocusGuard'
import { isContainerFocus } from '../../src/utils/tableFocusReveal'
import { walkSourceFiles } from './sourceTreeShared'

const SRC = new URL('../../src', import.meta.url).pathname
const MAIN = readFileSync(join(SRC, 'main.ts'), 'utf-8')

describe('plain-cell table body click jump guard', () => {
  it('arms exactly on the plain-cell -> tbody-focus shape', () => {
    const plainCell = {
      insideTableBody: true,
      focusableBetweenHitAndBody: false,
      hitInsideLabel: false,
    }
    expect(isPlainCellBodyFocus(plainCell)).toBe(true)
  })

  it('never arms outside the table body', () => {
    expect(isPlainCellBodyFocus({ insideTableBody: false, focusableBetweenHitAndBody: false, hitInsideLabel: false })).toBe(false)
  })

  it('never arms when a real control takes the focus', () => {
    expect(isPlainCellBodyFocus({ insideTableBody: true, focusableBetweenHitAndBody: true, hitInsideLabel: false })).toBe(false)
  })

  it('never arms on label hits — those belong to the label take-over', () => {
    const labelHit = { insideTableBody: true, focusableBetweenHitAndBody: false, hitInsideLabel: true }
    expect(isPlainCellBodyFocus(labelHit)).toBe(false)
    // and the two guards really are disjoint on that shape
    expect(shouldTakeOverFocus({ ...labelHit, labelControlUsable: true })).toBe(true)
  })

  it('main.ts installs the jump guard exactly once', () => {
    const installs = MAIN.match(/installTableBodyClickJumpGuard\(\)/g) ?? []
    expect(installs).toHaveLength(1)
    expect(MAIN).toMatch(/import\s*\{[^}]*installTableBodyClickJumpGuard[^}]*\}\s*from\s*'\.\/utils\/tableLabelFocusGuard'/)
  })

  it('no page or component adds a local focusin / scrollLeft-restore patch', () => {
    const offenders: string[] = []
    for (const file of walkSourceFiles(SRC)) {
      const rel = relative(SRC, file)
      // the shared guards themselves own the sanctioned sites
      if (rel === join('utils', 'tableLabelFocusGuard.ts')) continue
      if (rel === join('utils', 'tableFocusReveal.ts')) continue
      if (rel === join('utils', 'tableViewportHScrollbar.ts')) continue
      const text = readFileSync(file, 'utf-8')
      if (/addEventListener\(\s*['"]focusin['"]/.test(text)) offenders.push(`${rel} (local focusin patch)`)
      // ChatDock scroll-to-bottom etc. legitimately write scrollTop; only the
      // horizontal restore belongs to the shared guard.
      if (/\.scrollLeft\s*[+]?=[^=]/.test(text)) offenders.push(`${rel} (local scrollLeft patch)`)
    }
    expect(offenders, `scroll-restore must stay single-source: ${offenders.join(', ')}`).toEqual([])
  })

  it('the reveal enhancer ignores container focus (tbody/tr are not controls)', () => {
    expect(isContainerFocus('TBODY')).toBe(true)
    expect(isContainerFocus('TR')).toBe(true)
    expect(isContainerFocus('TABLE')).toBe(true)
    expect(isContainerFocus('BUTTON')).toBe(false)
    expect(isContainerFocus('INPUT')).toBe(false)
    const reveal = readFileSync(join(SRC, 'utils', 'tableFocusReveal.ts'), 'utf-8')
    expect(reveal).toMatch(/if \(isContainerFocus\(target\.tagName\)\) return/)
  })
})
