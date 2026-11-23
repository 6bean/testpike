/**
 * Contract for the table label click-focus guard (cold-boot checkbox fix).
 *
 * Background: Element Plus tbody carries tabindex="-1", so a mousedown on a
 * row checkbox's non-focusable inner span focuses the tbody; when the table
 * overflows horizontally the browser scrolls the oversized body into view
 * mid-click and the first tick of the checkbox is silently swallowed. The
 * shared guard (src/utils/tableLabelFocusGuard.ts) moves focus to the label's
 * control without scrolling and cancels the default focus step.
 *
 * Gates:
 *  1) pure truth table of shouldTakeOverFocus — take-over fires exactly on
 *     the tbody-steals-label-control shape and nothing else;
 *  2) main.ts installs the guard exactly once (wiring cannot be silently
 *     dropped by a future refactor);
 *  3) page-level mousedown sites stay at the baseline list (three drag
 *     handles) — pages must not re-solve this locally (R40 single source);
 *  4) the WCAG 2.4.11 scroll-padding rule in theme.scss stays in place —
 *     the fix must not relax the focus-obscured protection it exists for.
 * The real-browser truth (first trusted mouse click on a cold boot toggles
 * the row checkbox while the table jumps) is proven by the cold-boot E2E leg,
 * not here — static binding alone cannot prove the wiring behaves.
 */
import { readFileSync } from 'node:fs'
import { join, relative } from 'node:path'
import { describe, expect, it } from 'vitest'
import { shouldTakeOverFocus } from '../../src/utils/tableLabelFocusGuard'
import { walkSourceFiles } from './sourceTreeShared'

const SRC = new URL('../../src', import.meta.url).pathname
const MAIN = readFileSync(join(SRC, 'main.ts'), 'utf-8')
const THEME = readFileSync(join(SRC, 'styles/theme.scss'), 'utf-8')

describe('table label click-focus guard', () => {
  it('takes over exactly the tbody-steals-label-control shape', () => {
    const ok = {
      insideTableBody: true,
      focusableBetweenHitAndBody: false,
      hitInsideLabel: true,
      labelControlUsable: true,
    }
    expect(shouldTakeOverFocus(ok)).toBe(true)
    // Outside a table body: native walk is already safe.
    expect(shouldTakeOverFocus({ ...ok, insideTableBody: false })).toBe(false)
    // A focusable button/input between hit and tbody: native focus lands on
    // the control itself, nothing to take over.
    expect(
      shouldTakeOverFocus({ ...ok, focusableBetweenHitAndBody: true }),
    ).toBe(false)
    // Plain cell text (no label): keep the tbody focus for row keyboard nav.
    expect(shouldTakeOverFocus({ ...ok, hitInsideLabel: false })).toBe(false)
    // Disabled or detached control: never fake a focus target.
    expect(shouldTakeOverFocus({ ...ok, labelControlUsable: false })).toBe(false)
  })

  it('is wired once from main.ts', () => {
    expect(MAIN.match(/installTableLabelFocusGuard\(\)/g)?.length).toBe(1)
    expect(MAIN).toContain(
      "from './utils/tableLabelFocusGuard'",
    )
  })

  it('keeps page-level mousedown sites at the drag-handle baseline', () => {
    // Baseline 2026-11-14: three splitter/resize drag handles. They are
    // unrelated to focus; new sites mean a page re-solving this locally.
    const BASELINE = [
      'views/apitest/ApiTestView.vue',
      'components/ExecMonitorWindow.vue',
      'layouts/MainLayout.vue',
    ]
    const GUARD = relative(SRC, join(SRC, 'utils/tableLabelFocusGuard.ts'))
    const hits = walkSourceFiles(SRC)
      .filter((f) => relative(SRC, f) !== GUARD)
      .filter((f) => {
        if (f.endsWith('main.ts')) return false
        const text = readFileSync(f, 'utf-8')
        return /addEventListener\(\s*['"]mousedown|@mousedown/.test(text)
      })
      .map((f) => relative(SRC, f).replaceAll('\\', '/'))
      .sort()
    expect(hits).toEqual(BASELINE.slice().sort())
  })

  it('leaves the WCAG 2.4.11 scroll-padding rule in place', () => {
    expect(THEME).toContain('scroll-padding-inline-end')
    expect(THEME).toContain('--tp-table-pin-overlay')
  })
})
