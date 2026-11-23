/**
 * FB-3 yellow-card closeout: same-band merge layout contract for the
 * three pages carried in the S6 fb3_merge_candidates account (DataFactory /
 * AppPackage / CredentialVault), per the 10-22 merge-band ruling.
 *
 * Same posture as tagMergeBandT22 (the /tags precedent): disk truth = the
 * page template source read from disk, no memory lists. For each page:
 *  1. the emptied .page-header / .header-actions shell rows are removed as a
 *     whole (R23: page name rides the global top bar only);
 *  2. the page-level variant action (create) now sits in the
 *     .filter-bar-actions trailing slot — the slot's FIRST item;
 *  3. the variant action rides before the view-state actions (ColumnSettings
 *     / refresh), variants first / view-state last;
 *  4. the migrated button keeps its ownership gate unchanged (tooltip-wrapped
 *     disabled entry while the header context is empty, rule G);
 *  5. the data-test anchors consumed by e2e / comp / dyn scanners survive the
 *     migration with their original values (no broken chains).
 */
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const SRC = join(__dirname, '..', '..', 'src')

/** Text of the template block (up to the first <script / <style boundary). */
function templateOf(src: string): string {
  const start = src.indexOf('<template>')
  expect(start, 'page must carry a template block').toBeGreaterThan(-1)
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

interface MergePage {
  name: string
  file: string
  /** create-entry data-test anchor (e2e/comp consumer chain, must survive) */
  createBtn: string
  /** filter-bar data-test anchor (must survive) */
  filterBar: string
  /** view-state action marker the create button must precede in the slot */
  viewStateMarker: string
}

const pages: MergePage[] = [
  {
    name: 'DataFactoryListView',
    file: join(SRC, 'views', 'datafactory', 'DataFactoryListView.vue'),
    createBtn: 'datafactory-create-btn',
    filterBar: 'datafactory-list-filter-bar',
    viewStateMarker: '<ColumnSettings',
  },
  {
    name: 'AppPackageView',
    file: join(SRC, 'views', 'appauto', 'AppPackageView.vue'),
    createBtn: 'package-create-btn',
    filterBar: 'package-filter-bar',
    viewStateMarker: 'data-test="package-refresh"',
  },
  {
    name: 'CredentialVaultView',
    file: join(SRC, 'views', 'appauto', 'CredentialVaultView.vue'),
    createBtn: 'credential-create-btn',
    filterBar: 'credential-filter-bar',
    viewStateMarker: 'data-test="credential-refresh"',
  },
]

for (const p of pages) {
  describe(`FB-3 merge band (${p.name})`, () => {
    const raw = readFileSync(p.file, 'utf8')
    const tpl = templateOf(raw)

    it('carries no page-header / header-actions shell rows left behind', () => {
      expect(tpl, 'page-header band must be removed once the header is emptied').not.toMatch(
        /class="[^"]*\bpage-header\b/,
      )
      expect(tpl, 'header-actions container must be removed as a whole shell').not.toMatch(
        /class="[^"]*\bheader-actions\b/,
      )
    })

    it('merges the create entry into the filter-bar trailing slot (slot first item)', () => {
      const slots = divRegion(tpl, 'filter-bar-actions')
      expect(slots.length, 'filter-bar-actions trailing slot must exist').toBe(1)
      const slot = slots[0]
      expect(slot, 'trailing slot must host the create button anchor').toContain(
        `data-test="${p.createBtn}"`,
      )
      // the create entry is the FIRST element of the slot (variants first):
      // strip leading whitespace/comments, the slot must open with the
      // tooltip-wrapped create button, no other action token may precede it
      const head = slot.replace(/^(\s|<!--[\s\S]*?-->)*/, '')
      expect(head.startsWith('<el-tooltip'), 'trailing slot must open with the create entry').toBe(true)
    })

    it('orders the variant action before the view-state action', () => {
      const slot = divRegion(tpl, 'filter-bar-actions')[0] ?? ''
      const createAt = slot.indexOf(`data-test="${p.createBtn}"`)
      const viewAt = slot.indexOf(p.viewStateMarker)
      expect(createAt).toBeGreaterThan(-1)
      expect(viewAt, 'page must keep its view-state action in the slot').toBeGreaterThan(-1)
      expect(createAt, 'page-level variant action must ride before the view-state action').toBeLessThan(
        viewAt,
      )
    })

    it('keeps the ownership gate posture of the migrated button unchanged', () => {
      // rule G: tooltip-wrapped disabled entry while the header context is empty
      expect(raw).toMatch(
        new RegExp(
          `<el-tooltip[^>]*components\\.ownerEcho\\.pickInHeader[\\s\\S]{0,400}?data-test="${p.createBtn}"`,
        ),
      )
      expect(raw).toMatch(/:disabled="!!projectId"/)
      expect(raw).toMatch(new RegExp(`:disabled="!projectId"[^>]*data-test="${p.createBtn}"`))
    })

    it('preserves the filter-bar anchor consumed by the test chains', () => {
      expect(tpl).toContain(`data-test="${p.filterBar}"`)
    })
  })
}
