/**
 * Anchors dialog shape contract (R group R1/R3, V-a ticket from the refs pack):
 *  - the dialog switches its width tier by content density: sparse face (both
 *    bounded sections empty) drops to the 720 tier, dense face keeps 1080 —
 *    a flat wide strip outside 1.2–1.8 aspect is the defect this pins out;
 *  - the density gate is ONE computed reading both bounded sections (no
 *    second source, R40);
 *  - the dense face carries a min-height floor on the content wrapper while
 *    the sparse face must not (short content never stretches into dead space);
 *  - widths stay theme single-source: the component never hardcodes a dialog
 *    pixel width, it only picks tp-dialog tier classes.
 *
 * Mechanism: static source reads + regex extraction, browser-free. Live
 * geometry re-measure (boundingBox aspect in [1.2, 1.8] both faces) runs in
 * the vision recheck window after the slot5 L4 window closes.
 */
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

const viewRaw = readFileSync(
  fileURLToPath(new URL('../../src/views/webelement/WebElementLedgerView.vue', import.meta.url)),
  'utf-8'
)

const anchorsDialogOpen = viewRaw.match(/<el-dialog[^>]*data-test="ledger-anchors-dialog"[^>]*>/s)
const anchorsBodyBlock = viewRaw.slice(
  viewRaw.indexOf('data-test="ledger-anchors-dialog"'),
  viewRaw.indexOf('<template #footer>', viewRaw.indexOf('data-test="ledger-anchors-dialog"'))
)

describe('anchors dialog density tier switching (R1 shape guard)', () => {
  it('opens the dialog with a conditional tp-dialog tier bound to the density gate', () => {
    expect(anchorsDialogOpen).not.toBeNull()
    expect(anchorsDialogOpen?.[0]).toMatch(
      /:class="anchorsSparse\s*\?\s*'tp-dialog--md'\s*:\s*'tp-dialog--lg'"/
    )
    // the old static large tier must be fully gone (no half-migrated state)
    expect(anchorsDialogOpen?.[0]).not.toMatch(/class="tp-dialog--lg"/)
  })

  it('derives the density gate from both bounded sections in one computed', () => {
    expect(viewRaw).toMatch(
      /const anchorsSparse = computed\(\(\) => refs\.rows\.length === 0 && healRecent\.rows\.length === 0\)/
    )
  })

  it('gates the content floor with the same switch: dense floors, sparse does not', () => {
    expect(anchorsBodyBlock).toMatch(
      /<div class="anchors-body" :class="\{ 'is-dense': !anchorsSparse \}">/
    )
    expect(viewRaw).toMatch(/\.anchors-body\.is-dense\s*\{[^}]*min-height:\s*480px/)
    // no bare .anchors-body floor outside the is-dense gate (sparse stretch ban, R3)
    expect(viewRaw).not.toMatch(/\.anchors-body\s*\{[^}]*min-height/)
  })

  it('keeps dialog widths theme single-source (component hardcodes no dialog px width)', () => {
    // the tier classes are the only width mechanism used on any dialog here
    expect(viewRaw).not.toMatch(/<el-dialog[^>]*style="[^"]*width/s)
    const tiers = [...viewRaw.matchAll(/tp-dialog--(sm|md|lg|xl)/g)].map((m) => m[1])
    expect(tiers.length).toBeGreaterThan(0)
    expect(tiers.every((t) => ['sm', 'md', 'lg', 'xl'].includes(t))).toBe(true)
  })
})
