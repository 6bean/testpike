/**
 * Dialog popper mount single-source contract.
 *
 * Reported defect: dropdown/option layers opened inside dialogs render
 * hidden ("clipped") behind the dialog overlay. Two structural causes are
 * gated here; the rendered-behaviour truth (visible option count >= total
 * options for every select inside a dialog) is asserted in the E2E journey,
 * not in jsdom.
 *
 * Gates:
 *  1) theme.scss must carry the GENERAL popper/dialog z-index coordination
 *     rule at one marked single-source site (`tp-popper-z-single-source`).
 *     Today the rule is absent and only page-specific patches exist = RED;
 *     GREEN lands when the general rule is added and patches are absorbed.
 *  2) Page-scoped named popper `z-index ... !important` patches inside the
 *     theme file form a shrinking ratchet — the selector-site count must
 *     never grow beyond the captured baseline (existing sites get absorbed
 *     into the single source, never replicated).
 *  3) No NEW page-level (scoped/SFC) popper z-index !important patches may
 *     appear outside the theme file — same ratchet discipline as the
 *     focus-ring single-source contract.
 */
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { walkStyleSources } from './support/walkStyleSources'

const SRC = new URL('../../src', import.meta.url).pathname

const theme = readFileSync(join(SRC, 'styles/theme.scss'), 'utf-8')

/** Selector sites that carry a `z-index: <n> !important` popper patch. */
function popperZPatchSites(css: string): string[] {
  const sites: string[] = []
  // Count selector sites, not prose: strip CSS comments first, otherwise a
  // comma inside the single-source block's explanatory comment would be
  // misread as an extra selector segment (detector measured the marker text
  // itself on the GREEN cut — fixed 2026-11-11, semantics stay selector-level).
  const code = css.replace(/\/\*[\s\S]*?\*\//g, '')
  // Split top-level rule blocks; a block qualifies when its body forces a
  // z-index with !important AND its prelude names a popper-class selector.
  const blockRe = /([^{}]+)\{([^{}]*)\}/g
  let m: RegExpExecArray | null
  while ((m = blockRe.exec(code)) !== null) {
    const prelude = m[1]
    const body = m[2]
    if (/z-index\s*:\s*\d+\s*!important/.test(body) && /popper/i.test(prelude)) {
      // Each comma-separated selector counts as one patch site.
      for (const sel of prelude.split(',').map((s) => s.trim())) {
        if (/popper/i.test(sel)) sites.push(sel)
      }
    }
  }
  return sites
}

// Baseline captured 2026-11-11: three page-specific popper classes patched
// with z-index 3100 !important (member user/role, assign). Ratchet: only down.
// Absorbed 2026-11-11 (GREEN): the three named patches were replaced by the
// general `.el-popper` single-source block in theme.scss, so the count is now
// exactly that one marked site. Any growth above 1 = a new page-specific
// popper patch proliferating — the recurrence this gate exists to prevent.
const THEME_POPPER_PATCH_BASELINE = 1

describe('dialog popper mount single source', () => {
  it('theme.scss carries the general popper/dialog z coordination single source', () => {
    // The marked block is the one source of truth for popper layering over
    // dialogs; absence = per-page patch proliferation (the reported defect).
    expect(theme).toMatch(/tp-popper-z-single-source/)
    // The single source must target generic popper selectors, not one page.
    const markerIdx = theme.indexOf('tp-popper-z-single-source')
    const around = theme.slice(markerIdx, markerIdx + 600)
    expect(around).toMatch(/\.el-popper|\.el-select__popper/)
    expect(around).toMatch(/z-index/)
  })

  it('page-specific popper z-index patches do not grow beyond baseline', () => {
    const sites = popperZPatchSites(theme)
    expect(sites.length).toBeLessThanOrEqual(THEME_POPPER_PATCH_BASELINE)
  })

  it('no page-level SFC adds new popper z-index !important patches', () => {
    const offenders: string[] = []
    for (const file of walkStyleSources(SRC)) {
      if (file.endsWith('theme.scss')) continue
      const text = readFileSync(file, 'utf-8')
      if (/\.popper[^{]*\{[^}]*z-index\s*:\s*\d+\s*!important/.test(text) ||
          /-popper[^{]*\{[^}]*z-index\s*:\s*\d+\s*!important/.test(text)) {
        offenders.push(file.slice(SRC.length + 1))
      }
    }
    // Baseline: zero. Any new scoped patch = the recurrence pattern the
    // single source exists to prevent.
    expect(offenders).toEqual([])
  })
})
