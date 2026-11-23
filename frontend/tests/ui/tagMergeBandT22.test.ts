/**
 * same-band merge layout contract for the tag list page (FB group,
 * 10-22 merge-band ruling).
 *
 * When a list page has no row-level batch action bar and its filter band
 * carries <= 4 condition controls, page-level variant actions (create /
 * import) merge into the .filter-bar-actions trailing slot on the same row
 * as the view-state actions (create first), and the emptied .page-header /
 * .header-actions row is removed as a whole shell.
 *
 * Disk truth = the TagListView template source (same posture as the
 * titleDedupContract / executionDomainMerge readers, no memory lists).
 * The data-test anchor 'tag-create-btn' is consumed by three e2e files and
 * must survive the migration unchanged.
 */
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const SRC = join(__dirname, '..', '..', 'src')
const tagSrc = readFileSync(join(SRC, 'views', 'tag', 'TagListView.vue'), 'utf8')

/** Text of the template block (up to the first <script / <style boundary). */
function templateOf(src: string): string {
  const start = src.indexOf('<template>')
  expect(start, 'TagListView must carry a template block').toBeGreaterThan(-1)
  let right = src.indexOf('<script', start)
  const styleAt = src.indexOf('<style', start)
  if (styleAt > start && (right < 0 || styleAt < right)) right = styleAt
  return src.slice(start, right < 0 ? src.length : right)
}

/** Inner text of every <div class="...cls..."> block via div pairing. */
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

describe('T422 tag page merge band (FB same-band rule)', () => {
  const tpl = templateOf(tagSrc)

  it('carries no page-header / header-actions shell rows left behind', () => {
    expect(tpl, 'page-header band must be removed once the header is emptied').not.toMatch(
      /class="[^"]*\bpage-header\b/,
    )
    expect(tpl, 'header-actions container must be removed as a whole shell').not.toMatch(
      /class="[^"]*\bheader-actions\b/,
    )
  })

  it('merges the create entry into the filter-bar trailing slot', () => {
    const slots = divRegion(tpl, 'filter-bar-actions')
    expect(slots.length, 'filter-bar-actions trailing slot must exist').toBe(1)
    expect(slots[0], 'trailing slot must host the create button anchor').toContain(
      'data-test="tag-create-btn"',
    )
  })

  it('orders the variant action before the view-state action (create first)', () => {
    const slot = divRegion(tpl, 'filter-bar-actions')[0] ?? ''
    const createAt = slot.indexOf('data-test="tag-create-btn"')
    const colSetAt = slot.indexOf('<ColumnSettings')
    expect(createAt).toBeGreaterThan(-1)
    expect(colSetAt).toBeGreaterThan(-1)
    expect(createAt, 'page-level variant action must ride before ColumnSettings').toBeLessThan(
      colSetAt,
    )
  })

  it('keeps the ownership gate posture of the migrated button unchanged', () => {
    // rule G: tooltip-wrapped disabled entry while the header context is empty
    expect(tagSrc).toMatch(
      /<el-tooltip[^>]*components\.ownerEcho\.pickInHeader[\s\S]{0,400}?data-test="tag-create-btn"/,
    )
    expect(tagSrc).toMatch(/:disabled="!!projectId"/)
  })
})
