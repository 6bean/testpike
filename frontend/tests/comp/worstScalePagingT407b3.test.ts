/**
 * AN-5 worst-scale paging layout contract.
 *
 * The vision review under the 35-row-per-shape fixture flagged the plugin
 * catalog as a full-resident list with no pager while the other three tabs
 * paged (dim4 scale-safety defect). The fix pages the catalog client-side;
 * this contract pins that every merged tab view now carries a visible pager
 * so a future refactor cannot silently drop the bounded-viewport shape again
 * at 50~200 rows. Programmatic gate companion to the DOM re-measure + vision
 * re-shoot.
 */
import { describe, expect, it } from 'vitest'
// Source-as-text reads ride the vite ?raw suffix (T406b2/T407b2 precedent).
import pluginRaw from '@/views/plugin/PluginListView.vue?raw'
import mcpRaw from '@/views/mcp/McpServerView.vue?raw'
import docRaw from '@/views/ai/DocumentListView.vue?raw'
import reqRaw from '@/views/requirement/RequirementListView.vue?raw'

const PAGED_VIEWS: Array<[string, string]> = [
  ['PluginListView', pluginRaw],
  ['McpServerView', mcpRaw],
  ['DocumentListView', docRaw],
  ['RequirementListView', reqRaw],
]

describe('T407 slice 3b: every merged tab view carries a pager', () => {
  it.each(PAGED_VIEWS)('%s renders el-pagination', (name, src) => {
    expect(src, `${name}: pager missing`).toMatch(/<el-pagination/)
  })

  it('plugin catalog slices client-side and resets the window on reload', () => {
    // The plugin list API returns the whole resident catalog (no server page),
    // so paging must be a computed window over the fetched array.
    expect(pluginRaw).toMatch(/const pagedList = computed\(/)
    expect(pluginRaw).toMatch(/:data="pagedList"/)
    // Filter changes reload the list; the visible window goes back to page 1
    // so a stale offset never shows an empty segment after narrowing.
    expect(pluginRaw).toMatch(/page\.value = 1 \/\/ filter changes reset/)
  })

  it('plugin pager stays hidden for small catalogs (no pager noise under one page)', () => {
    expect(pluginRaw).toMatch(/v-if="list\.length > pageSize"/)
  })
})
