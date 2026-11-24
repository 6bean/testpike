/**
 * Source chunk viewer door contract (requirement-page small fixes).
 *
 * The viewer is one shared dialog consumed by two doors (extraction preview
 * and the requirement list "view source" button), so both must honour the
 * same two rules:
 * 1. dialog posture: the D-group scale tier puts every dialog at max-height
 *    85vh *vertically centered* (theme single source). The chunk dialog is
 *    opened from an 800+-row list page; without align-center Element Plus
 *    parks it at the default 15vh top offset while the 85vh ceiling makes
 *    it touch the bottom edge — the reported "big gap above, flush below".
 *    Real-render geometry is asserted live in the E2E companion; this
 *    contract pins the declaration the E2E measures.
 * 2. neighbour walk: inside the dialog the "view this chunk's context"
 *    button emits update:chunkId. A door that binds chunk-id one-way
 *    silently swallows that emit — the click does nothing (reported dead
 *    button). Every consumer of this shared dialog must therefore bind
 *    chunk-id with v-model so the watcher re-reads the walked chunk.
 */
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const SRC = join(__dirname, '..', '..', 'src')

function read(rel: string): string {
  return readFileSync(join(SRC, rel), 'utf8')
}

/** The opening <el-dialog ...> tag of the shared chunk viewer. */
function viewerDialogTag(src: string): string {
  const start = src.indexOf('<el-dialog')
  expect(start, 'viewer must render an el-dialog').toBeGreaterThan(-1)
  const end = src.indexOf('>', start)
  expect(end, 'el-dialog tag must close').toBeGreaterThan(start)
  return src.slice(start, end)
}

describe('SourceChunkViewerDialog door contract', () => {
  const viewer = read('components/SourceChunkViewerDialog.vue')

  it('rides the D-group dialog tier: vertically centered (85vh + align-center)', () => {
    const tag = viewerDialogTag(viewer)
    // align-center is the Element Plus mechanism that vertically centers the
    // dialog; combined with the theme single-source max-height 85vh the door
    // keeps equal edge gaps instead of a 15vh top gap and a flush bottom.
    expect(tag, 'chunk viewer dialog must set align-center').toMatch(/\balign-center\b(?![-\w])/)
    expect(tag, 'no page-level top magic number next to centering').not.toMatch(/:?\stop=|"top="/)
  })

  it('both consumer doors keep the neighbour-walk emit alive (v-model:chunk-id)', () => {
    const consumers = [
      'views/ai/ExtractPreviewDialog.vue',
      'views/requirement/RequirementListView.vue',
    ]
    for (const rel of consumers) {
      const src = read(rel)
      const start = src.indexOf('<SourceChunkViewerDialog')
      expect(start, `${rel} must mount the shared viewer`).toBeGreaterThan(-1)
      const block = src.slice(start, start + 800)
      expect(
        block,
        `${rel} must bind v-model:chunk-id so "view this chunk's context" re-reads instead of dead-clicking`,
      ).toMatch(/v-model:chunk-id/)
    }
  })
})
