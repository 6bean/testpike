/**
 * layout contract for the whole-collection run launcher.
 *
 * Posture = the ?raw source mirror used by ownerEchoWiringT396b.test.ts:
 * the assertions read the shipped template text, so a regression that
 * re-introduces a second project channel or an orphaned <label> fails
 * here long before the browser ever renders it.
 *
 * Clauses:
 * 1. ownership echo (site-wide iron rule): the launch dialog imports and
 * hosts the shared <ProjectOwnerEcho /> — the owning project is
 * single-sourced from the global header context;
 * 2. no second selector: the dialog template must never mount a
 * ProjectSelect (or any project-picker duplicate) inside itself;
 * 3. gated primary action: the launch button stays disabled until an
 * environment is picked (pickedEnvId === null guard is pinned);
 * 4. accessible group caption: the radio-group caption is a plain element
 * wired via aria-labelledby — a bare <label> without for= or a nested
 * labelable control trips the Chromium form-label association issue
 * (FormLabelHasNeitherForNorNestedInputError), which the E2E zero-
 * tolerance gate rejects, so the orphan shape is banned at source.
 */
import { describe, expect, it } from 'vitest'

import dialogRaw from '@/views/apitest/RunSetLaunchDialog.vue?raw'

/** segment the dialog template block from its data-test anchor to the close tag. */
function dialogBlock(src: string, anchor: string): string {
  const start = src.indexOf(`data-test="${anchor}"`)
  expect(start, `dialog anchor ${anchor} missing`).toBeGreaterThan(-1)
  const end = src.indexOf('</el-dialog>', start)
  expect(end, `dialog ${anchor} never closes`).toBeGreaterThan(start)
  return src.slice(start, end)
}

const block = dialogBlock(dialogRaw, 'apitest-runset-dialog')

describe('T369 runset dialog: ownership echo + single channel', () => {
  it('imports the shared ProjectOwnerEcho component', () => {
    expect(dialogRaw).toContain("import ProjectOwnerEcho from '@/components/ProjectOwnerEcho.vue'")
  })

  it('hosts <ProjectOwnerEcho /> inside the dialog (value single-sourced from the header context)', () => {
    expect(block).toMatch(/<ProjectOwnerEcho\s*\/?>/)
  })

  it('keeps zero second project selectors in the dialog (no ProjectSelect import or mount)', () => {
    expect(dialogRaw).not.toMatch(/import\s+\w+\s+from\s+.*ProjectSelect\.vue/)
    expect(block).not.toMatch(/<ProjectSelect/)
    expect(block).not.toMatch(/project-select/)
  })
})

describe('T369 runset dialog: gated launch + accessible captions', () => {
  it('disables the launch button until an environment is picked', () => {
    expect(block).toMatch(/data-test="apitest-runset-launch-btn"[\s\S]{0,120}?:disabled="[^"]*pickedEnvId === null/)
  })

  it('avoids the Chromium issue: the launch button is only disabled before any run is possible', () => {
    // A disabled-by-default primary action is intentional here (env unset);
    // pin that the guard text also carries the empty-preview branch so the
    // button cannot fire against a collection with no requests.
    expect(block).toMatch(/preview\.length === 0/)
  })

  it('carries no orphaned <label> (every label binds for=; group captions are non-label elements)', () => {
    // Strip HTML comments first — prose inside them (e.g. the rationale note
    // above the group caption) must not be mistaken for markup shape.
    const markup = block.replace(/<!--[\s\S]*?-->/g, '')
    const labels = markup.match(/<label\b[^>]*>/g) ?? []
    for (const tag of labels) {
      expect(tag, `orphan label shape: ${tag}`).toMatch(/\sfor="/)
    }
    // The radio-group caption must keep the aria-labelledby wiring.
    expect(block).toMatch(/aria-labelledby="apitest-runset-mode-label"/)
    expect(block).toMatch(/id="apitest-runset-mode-label"/)
  })
})
