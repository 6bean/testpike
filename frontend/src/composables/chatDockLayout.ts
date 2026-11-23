/**
 * Resident dock layout , adjudication §two.2 — shrink, not
 * cover) — the ONE source of the resident-form predicate and the two squeeze
 * numbers it implies (R40: the click-outside guard, the root class, the theme
 * collision rules and the E2E measurements all read these, none re-spell them).
 *
 * Forms:
 * - light floating dock (default, R33): pinned=false — zero semantic drift,
 * click-outside still collapses it;
 * - resident dock (pinned on a wide viewport): shares the page instead of
 * covering it. The main content already yields the gutter through the
 * single token --tp-chat-dock-reserve (owned by ChatDock, ux gate X-2);
 * layers whose centering box is the whole viewport (element-plus overlay
 * containers: modal dialogs among them) must additionally be offset by
 * that gutter, or a centered dialog sits half-under the dock;
 * - compact viewport (narrow screens, D2): the dock stays a full-width
 * overlay and click-outside close survives the pin — a resident squeeze
 * on a phone would leave no page to show.
 *
 * Personal-layout math only: NEVER server state, NEVER a K8 governance key
 * (same discipline as the width and pin preferences).
 */

/** Factory gutter for fixed layers: matches the theme.scss token default. */
export const DOCK_GUTTER_PX = '0px'

export interface DockFormInput {
  pinned: boolean
  compact: boolean
}

/** The resident form is pinned-on-a-wide-viewport only (single judge). */
export function dockResident(input: DockFormInput): boolean {
  return input.pinned && !input.compact
}

/**
 * Right offset for viewport-boxed fixed layers (el-overlay containers):
 * the live gutter in resident mode so centered dialogs re-center into the
 * visible page, the factory zero otherwise (light/compact forms unchanged).
 * Driven by the --tp-chat-dock-reserve token value, not a second width copy.
 * The pairing measurement (visible page = viewport - gutter) is asserted at
 * runtime by the pin spectrum E2E; the authored clamp stays in
 * useChatDockWidth, so this module holds no second arithmetic.
 */
export function dockOverlayOffsetPx(resident: boolean, reservePx: number): string {
  return resident ? `${reservePx}px` : DOCK_GUTTER_PX
}
