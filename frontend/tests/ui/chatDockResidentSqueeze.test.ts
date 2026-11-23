/**
 * resident dock squeeze contract (adjudication §two.2).
 *
 * The resident (pinned, wide viewport) form must SHARE the page instead of
 * covering it: the drawer floats over its own reserved gutter, while every
 * other fixed layer (modal dialogs in particular, whose centering box is the
 * full viewport) stays clear of the gutter. The light floating dock keeps
 * byte-identical R33 behaviour; the compact form stays a full-width overlay
 * with click-outside close (D2, touch reachability).
 *
 * Pure judges (chatDockLayout is the ONE source of the resident predicate —
 * the click-outside guard and the root class must both read it, R40):
 * - resident = pinned && !compact;
 * - overlay offset = the live gutter width in resident mode, 0 otherwise
 * (a centered dialog shifts into the visible page, never under the dock);
 * - drawer body width = min(width, viewport - gutter) so a dock dragged to
 * the max width can never bleed past the gutter into the page.
 *
 * Wiring assertions are source-as-text (the chatDockPin precedent):
 * - ChatDock binds the resident class through the single judge and toggles
 * the document root class from the SAME judge (no second copy of the
 * pinned && !compact expression);
 * - the click-outside guard reads the judge instead of re-spelling it;
 * - the two S6 collision criteria live in the structure scanner with
 * bidirectional fixtures (a gate that never bit is an idle gate).
 */
import { describe, expect, it } from 'vitest'
import {
  DOCK_GUTTER_PX,
  dockOverlayOffsetPx,
  dockResident,
} from '@/composables/chatDockLayout'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import dockRaw from '@/components/ChatDock.vue?raw'

const themeRaw = readFileSync(
  fileURLToPath(new URL('../../src/styles/theme.scss', import.meta.url)),
  'utf-8',
)
const scannerRaw = readFileSync(
  fileURLToPath(new URL('../../../tools/ui_structure_audit.py', import.meta.url)),
  'utf-8',
)

describe('resident dock squeeze pure contract', () => {
  it('resident form is pinned on a wide viewport only', () => {
    expect(dockResident({ pinned: true, compact: false })).toBe(true)
    expect(dockResident({ pinned: true, compact: true })).toBe(false)
    expect(dockResident({ pinned: false, compact: false })).toBe(false)
    expect(dockResident({ pinned: false, compact: true })).toBe(false)
  })

  it('fixed layers clear the gutter only in resident mode', () => {
    expect(dockOverlayOffsetPx(true, 420)).toBe('420px')
    expect(dockOverlayOffsetPx(false, 420)).toBe(DOCK_GUTTER_PX)
    expect(dockOverlayOffsetPx(true, 0)).toBe('0px')
  })
})

describe('resident dock squeeze wiring', () => {
  it('ChatDock owns one resident predicate and publishes both squeeze numbers', () => {
    expect(dockRaw).toContain("import { dockOverlayOffsetPx, dockResident as dockResidentJudge } from '@/composables/chatDockLayout'")
    expect(dockRaw).toContain("document.documentElement.classList.toggle('dock-resident', resident)")
    expect(dockRaw).toContain("const DOCK_OFFSET_VAR = '--tp-chat-dock-overlay-offset'")
    expect(dockRaw).toContain('dockOverlayOffsetPx(dockResident.value, open.value ? width.value : 0)')
    expect(dockRaw).toContain('watch([open, width, dockResident], applyDockReserve)')
    // The dock's own anchor never moves: the gutter is reserved by the page,
    // so no inline right offset on the drawer (double reservation).
    expect(dockRaw).not.toContain(':style="{ right: overlayOffset }"')
    // The click-outside guard must consume the judge, not re-spell the rule.
    expect(dockRaw).toContain('if (dockResident.value) return')
    expect(dockRaw).toContain(':class="{ \'is-resident\': dockResident }"')
    expect(dockRaw).not.toContain('chatDockState.pinned && !compactViewport.value')
  })

  it('theme carries the dialog-tier collision rule under the resident root class', () => {
    expect(themeRaw).toContain('--tp-chat-dock-overlay-offset: 0px;')
    expect(themeRaw).toContain('html.dock-resident .el-overlay-dialog')
    expect(themeRaw).toContain('right: var(--tp-chat-dock-overlay-offset, 0px);')
  })

  it('structure scanner carries both S-16 judges with fixtures', () => {
    expect(scannerRaw).toContain('def s16_judge(src_, rel_=')
    expect(scannerRaw).toContain("['S-16'")
    expect(scannerRaw).toContain("'S-16 red side")
    expect(scannerRaw).toContain("'S-16 green side")
    expect(scannerRaw).toContain("'S-16 owner exemption")
    expect(scannerRaw).toContain('S-16 resident-dock collision gate')
  })
})
