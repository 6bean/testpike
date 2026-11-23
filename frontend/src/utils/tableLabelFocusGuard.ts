/**
 * Click-focus guards for Element Plus table bodies.
 *
 * Two site-wide guards share this module because they solve two faces of the
 * same root cause (EP renders <tbody tabindex="-1>, so a plain click can hand
 * focus to the tbody): 1) the label take-over below (checkbox first-tick), and
 * 2) installTableBodyClickJumpGuard — undoing the browser's right-edge reveal
 * when a PLAIN (non-interactive) cell click focuses the tbody in a table that
 * overflows horizontally. See that function's doc for the live evidence.
 *
 * Why: Element Plus renders the table body as a focusable tbody (it carries
 * tabindex="-1"; see node_modules/element-plus/es/components/table/src/
 * table-body). A left mousedown on a row checkbox hits `.el-checkbox__inner`,
 * a span that is not focusable, and the browser's focus walk climbs past the
 * label (labels never take focus themselves) onto the tbody. Focusing an
 * element scrolls it into view: on a table wider than its scroll container
 * the whole body jumps toward its right edge between mousedown and mouseup,
 * mouseup lands on a different cell, no click ever reaches the label, and
 * the user's first tick of the checkbox is silently swallowed. Reproduced
 * live on the case list cold boot: focusin target TBODY, scrollLeft 0 -> 434
 * inside a single trusted click.
 *
 * What: while capturing mousedown inside a table body, when nothing
 * focusable sits between the hit node and the tbody and the hit belongs to a
 * label whose associated control is usable, focus that control directly
 * (without scrolling) and cancel the browser's default focus move. The click
 * event still runs the label's activation behaviour, so toggling works
 * exactly as before and keyboard focus lands where the user meant it.
 *
 * Scope discipline: only label controls are taken over. Clicks on plain
 * cells keep the native tbody focus so Element Plus keyboard row navigation
 * stays available, and tables without horizontal overflow never jump either
 * way. The WCAG 2.4.11 scroll-padding rule in theme.scss is NOT touched:
 * real focused controls are still revealed past the pinned overlay by the
 * sibling enhancer tableFocusReveal.
 *
 * Single source: installed once from main.ts; pages must NOT add local
 * mousedown label-focus patches (guard: tests/ui/tableLabelFocusGuard.test.ts).
 */

const FOCUSABLE_SELECTOR = [
  'input:not([disabled])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  'button:not([disabled])',
  'a[href]',
  '[tabindex]',
].join(', ')

export interface GuardFacts {
  insideTableBody: boolean
  focusableBetweenHitAndBody: boolean
  hitInsideLabel: boolean
  labelControlUsable: boolean
}

/** Pure decision: take over the focus move exactly when the tbody would
 *  steal it from a usable label control. Exported so the contract test pins
 *  the full truth table without a DOM. */
export function shouldTakeOverFocus(f: GuardFacts): boolean {
  return (
    f.insideTableBody &&
    !f.focusableBetweenHitAndBody &&
    f.hitInsideLabel &&
    f.labelControlUsable
  )
}

function collectFacts(target: Element): GuardFacts {
  const tbody = target.closest('tbody')
  const insideTableBody = tbody !== null
  let focusableBetweenHitAndBody = false
  if (tbody) {
    const nearest = target.closest(FOCUSABLE_SELECTOR)
    focusableBetweenHitAndBody =
      nearest !== null && nearest !== tbody && tbody.contains(nearest)
  }
  const label = target.closest('label')
  const hitInsideLabel = label !== null && (!tbody || tbody.contains(label))
  let labelControlUsable = false
  if (hitInsideLabel && label instanceof HTMLLabelElement) {
    const control = label.control
    labelControlUsable =
      control !== null &&
      !('disabled' in control && (control as { disabled?: boolean }).disabled === true) &&
      (!tbody || tbody.contains(control))
  }
  return { insideTableBody, focusableBetweenHitAndBody, hitInsideLabel, labelControlUsable }
}

/** Install the site-wide capture-phase mousedown guard (call once). */
export function installTableLabelFocusGuard(doc: Document = document): void {
  doc.addEventListener(
    'mousedown',
    (event: MouseEvent) => {
      if (event.button !== 0 || event.defaultPrevented) return
      const target = event.target
      if (!(target instanceof Element)) return
      if (!shouldTakeOverFocus(collectFacts(target))) return
      const label = target.closest('label') as HTMLLabelElement
      const control = label.control as HTMLElement
      control.focus({ preventScroll: true })
      event.preventDefault()
    },
    true,
  )
}

/**
 * Plain-cell horizontal jump guard (2026-11-22 requirements-page report).
 *
 * Symptom (live): on the requirement list at 1280px the table overflows
 * horizontally (scrollWidth 1432 > clientWidth 966). A single trusted left
 * click on a NON-interactive cell — the title span, the chapter cell — moved
 * the whole body's scrollLeft 0 -> 466 (its maximum), snapping the horizontal
 * scrollbar to the far right and pushing the columns the user was reading out
 * of view. Reproduced cold: focusin target TBODY + scrollLeft jumps within one
 * click; programmatic `.focus()`/`.focus({preventScroll:true})` on the same
 * tbody does NOT jump, so the trigger is specifically the browser's
 * click-to-focus reveal of the oversized tbody, not the label steal that
 * installTableLabelFocusGuard already fixes.
 *
 * Root cause: Element Plus 2.14 gives `<tbody tabindex="-1">`. A plain-cell
 * click has nothing focusable between the hit node and the tbody, so the
 * browser hands focus to the tbody and scrolls it into view — on a body wider
 * than the scroll port that reveal lands on the right edge.
 *
 * Fix: remember the scroll container's scrollLeft on the capture mousedown of
 * a plain-cell click, and restore it in the matching tbody focusin — synchronously, so no frame paints the jump.
 * Scope discipline: fires ONLY when focus lands on the TBODY container itself
 * (not a control, not a label — those paths are covered elsewhere) and only
 * undoes the horizontal reveal this specific interaction causes. Text
 * selection, keyboard row navigation and the tableFocusReveal caret-reveal
 * enhancement are all left intact.
 *
 * Single source: installed once from main.ts; pages must NOT add local
 * scroll-restore patches (guard: tests/ui/tableFocusReveal.test.ts).
 */
export function isPlainCellBodyFocus(f: GuardFacts): boolean {
  // The tbody steals focus only when nothing focusable (and no usable label)
  // sits between the hit node and the body — the exact click that triggers the
  // browser's reveal-to-right-edge.
  return f.insideTableBody && !f.focusableBetweenHitAndBody && !f.hitInsideLabel
}

export function installTableBodyClickJumpGuard(doc: Document = document): void {
  let pending: { wrap: HTMLElement; left: number } | null = null
  doc.addEventListener(
    'mousedown',
    (event: MouseEvent) => {
      pending = null
      if (event.button !== 0 || event.defaultPrevented) return
      const target = event.target
      if (!(target instanceof Element)) return
      if (!isPlainCellBodyFocus(collectFacts(target))) return
      const tbody = target.closest('tbody')
      const wrap = tbody?.closest('.el-scrollbar__wrap') as HTMLElement | null
      if (wrap && wrap.scrollWidth - wrap.clientWidth > 4) pending = { wrap, left: wrap.scrollLeft }
    },
    true,
  )
  doc.addEventListener(
    'focusin',
    (event: FocusEvent) => {
      if (!pending) return
      // Focus landed on the tbody (container), the native reveal already moved
      // scrollLeft; pull it back to where the user had it, in this same turn.
      if (event.target instanceof HTMLElement && event.target.tagName === 'TBODY') {
        const { wrap, left } = pending
        if (wrap.scrollLeft !== left) wrap.scrollLeft = left
      }
      pending = null
    },
    true,
  )
}
