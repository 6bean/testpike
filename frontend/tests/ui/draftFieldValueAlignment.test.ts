/**
 * AL batch D10 close-out (post-fix re-score): the draft card field lines must
 * read as a two-column layout — one label column, one value column, aligned
 * across every row. The vision feed flagged per-row value indent differences;
 * the geometry probe proved the residual shape: a multi-line value grew into
 * a full-width flex item that wrapped under its own label, so the acceptance
 * criteria row started its value at the row's left edge while the short rows
 * started theirs after the label column.
 *
 * This is a source contract on ChatDraftCard.vue's style block (same posture
 * as the tag merge-band digest tests): it pins the single-source CSS shape,
 * not pixel numbers. Real-pixel verification lives in the capture tool's DOM
 * ledger + vision re-score.
 */
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

const SRC = readFileSync(
  fileURLToPath(new URL('../../src/components/ChatDraftCard.vue', import.meta.url)),
  'utf-8',
)

function styleRule(selector: string): string {
  // every top-level rule block whose selector list ends on this exact
  // selector, concatenated (CSS cascades across blocks; the contract is the
  // union of declared properties, e.g. .draft-after spans a shared block with
  // .draft-before plus its own colour/grow blocks)
  const re = new RegExp(`(^|\\n)([^{}\\n]*,\\s*)?${selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*\\{([^}]*)\\}`, 'g')
  return [...SRC.matchAll(re)].map((m) => m[3]).join('\n')
}

describe('draft card two-column field lines', () => {
  it('holds the label column fixed (flex:none + 4em floor) so values share one start line', () => {
    const field = styleRule('.draft-field')
    expect(field).toContain('flex: none')
    expect(field).toContain('min-width: 4em')
  })

  it('lets the value grow instead of wrapping under its own label', () => {
    const after = styleRule('.draft-after')
    expect(after).toMatch(/flex:\s*1\b/)
    expect(after).toContain('min-width: 0')
    expect(after).toContain('overflow-wrap: anywhere')
  })

  it('keeps rows on one line pair (baseline align, wrap allowed only as a fallback)', () => {
    const row = styleRule('.draft-diff-row')
    expect(row).toContain('align-items: baseline')
    expect(row).toContain('display: flex')
  })
})
