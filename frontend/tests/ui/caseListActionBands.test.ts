/**
 * R21 action-band contract for the case list page ( vision residual:
 * the export dropdown and the column-settings control were parked on the
 * page-header row together with the page-level variant actions).
 *
 * Posture = disk truth (same helper shape as mergeBandFilterBarActions):
 * the page template is read from disk, no memory lists. Assertions:
 * 1. the header band now uses the canonical .page-header / .header-actions
 * layout classes so the S6 FB-1 scanner covers the page (the legacy
 * .toolbar wrapper was a scanner blind spot);
 * 2. the header band carries only page-level/business variant actions
 * (create, import, quick-review toggle, recycle bin, auto-assign,
 * mock jump) — no view-state tokens (ColumnSettings / export);
 * 3. the view-state actions (export dropdown + column settings) ride the
 * .filter-bar-actions trailing slot (margin-left:auto via theme token);
 * 4. the trailing slot holds no query-submit button (FB-2: the search and
 * reset buttons stay inline at the end of the filter group);
 * 5. every migrated data-test anchor keeps its original value so the e2e
 * chains (test_p2_export / test_f3_cases) survive the move.
 */
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const SRC = join(__dirname, '..', '..', 'src')

function templateOf(src: string): string {
  const start = src.indexOf('<template>')
  expect(start, 'page must carry a template block').toBeGreaterThan(-1)
  let right = src.indexOf('<script', start)
  const styleAt = src.indexOf('<style', start)
  if (styleAt > start && (right < 0 || styleAt < right)) right = styleAt
  return src.slice(start, right < 0 ? src.length : right)
}

function divRegion(src: string, cls: string): string[] {
  const out: string[] = []
  const open = new RegExp(`<div[^>]*class="[^"]*\\b${cls}\\b[^"]*"[^>]*>`, 'g')
  let m: RegExpExecArray | null
  while ((m = open.exec(src)) !== null) {
    const start = m.index + m[0].length
    let depth = 1
    let i = start
    const tok = /<div(?![\w-])|<\/div>/g
    tok.lastIndex = i
    let t: RegExpExecArray | null
    while (depth > 0 && (t = tok.exec(src)) !== null) {
      depth += t[0].startsWith('<div') ? 1 : -1
      i = t.index + t[0].length
    }
    out.push(src.slice(start, i))
  }
  return out
}

describe('case list page action bands (R21 three-band, FB-1/FB-2 surface)', () => {
  const tpl = templateOf(readFileSync(join(SRC, 'views/case/CaseListView.vue'), 'utf8'))
  const headerRegions = divRegion(tpl, 'header-actions')
  const trailingRegions = divRegion(tpl, 'filter-bar-actions')

  it('uses the canonical header band so the S6 scanner sees the page', () => {
    expect(divRegion(tpl, 'page-header').length, 'page-header band present').toBeGreaterThanOrEqual(1)
    expect(headerRegions.length, 'header-actions band present').toBeGreaterThanOrEqual(1)
    expect(tpl).not.toMatch(/class="toolbar"/)
  })

  it('header band carries only variant/business actions, no view-state tokens', () => {
    const header = headerRegions.join('\n')
    expect(header).toContain('case-create')
    expect(header).toContain('case-import')
    expect(header).toContain('quick-review-toggle')
    expect(header, 'column settings is view-state and belongs to the filter trailing slot').not.toContain('<ColumnSettings')
    expect(header, 'export is view-state and belongs to the filter trailing slot').not.toContain('case-export-dropdown')
  })

  it('filter trailing slot carries the migrated view-state actions', () => {
    expect(trailingRegions.length, 'trailing slot present').toBeGreaterThanOrEqual(1)
    const slot = trailingRegions.join('\n')
    expect(slot).toContain('<ColumnSettings')
    expect(slot).toContain('case-export-dropdown')
  })

  it('trailing slot holds no query-submit button (FB-2: submit stays inline)', () => {
    const slot = trailingRegions.join('\n')
    expect(slot).not.toContain('data-test="case-search"')
    expect(slot).not.toContain("t('case.search')")
  })

  it('migrated anchors keep their data-test values for the e2e chains', () => {
    for (const anchor of ['case-export-btn', 'case-export-dropdown', 'quick-review-toggle', 'case-create', 'case-import', 'case-recycle-open', 'case-auto-assign', 'case-view-in-mock']) {
      expect(tpl, `anchor ${anchor} survives`).toContain(anchor)
    }
  })
})
