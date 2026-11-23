/**
 * AN-5 embedded-frame layout contract.
 *
 * segment 1 merged seven pages into tab hosts at the menu/route level;
 * makes them render *inside* a host card without a frame-in-frame. This file
 * is the RED anchor for that structure:
 * - every merged page declares an `embedded` prop (ProcessView precedent:
 * withDefaults + root class swap page-card -> <slug>-embedded);
 * - after the swap the page renders zero hard-coded outer `page-card` frames,
 * so exactly one border surrounds the content when hosted;
b2 header
 */
import { describe, expect, it } from 'vitest'
// Source-as-text reads ride the vite ?raw suffix (T406b2 precedent).
import skillMarketRaw from '@/views/skill/SkillMarketView.vue?raw'
import toolMarketRaw from '@/views/tool/ToolMarketView.vue?raw'
import pluginRaw from '@/views/plugin/PluginListView.vue?raw'
import mcpRaw from '@/views/mcp/McpServerView.vue?raw'
import suiteRaw from '@/views/suite/SuiteListView.vue?raw'
import extHostRaw from '@/views/extension/ExtensionsHostView.vue?raw'

const PROP = /withDefaults\(defineProps<\{ embedded\?: boolean \}>\(\), \{ embedded: false \}\)/
const SWAP = /:class="embedded \? '[a-z-]+-embedded' : 'page-card'"/

const MERGED_PAGES: Array<[string, string]> = [
  ['SkillMarketView', skillMarketRaw],
  ['ToolMarketView', toolMarketRaw],
  ['PluginListView', pluginRaw],
  ['McpServerView', mcpRaw],
  ['SuiteListView', suiteRaw],
]

describe('T407 blade 2a: merged pages expose the embedded frame switch', () => {
  it.each(MERGED_PAGES)('%s declares the embedded prop and swaps its frames', (name, src) => {
    expect(src, `${name}: embedded prop declaration missing`).toMatch(PROP)
    expect(src, `${name}: page-card frame swap missing`).toMatch(SWAP)
    // The outer (and inner, for McpServerView) hard frames all become the
    // swap expression — no unconditional page-card div may survive, or the
    // hosted view shows a frame-in-frame again.
    const leftovers = [...src.matchAll(/<div class="page-card">/g)].length
    expect(leftovers, `${name}: residual hard frames`).toBe(0)
  })

  it('McpServerView swaps both panel frames (servers + templates)', () => {
    expect([...mcpRaw.matchAll(/:class="embedded \? 'mcp-embedded' : 'page-card'"/g)].length).toBe(2)
  })
})

describe('T407 blade 2a: the two new hosts frame their children embedded', () => {
  it('extensions host passes embedded to plugin + mcp children', () => {
    expect(extHostRaw).toMatch(/<PluginListView\s+embedded/)
    expect(extHostRaw).toMatch(/<McpServerView\s+embedded/)
  })
})
