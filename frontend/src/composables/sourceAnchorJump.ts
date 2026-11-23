/**
 * Single-source anchor-jump doors for the three conclusion-level traceability
 * anchor families (citation cards, extract-ledger evidence rows, execution
 * step evidence). One route builder and one reveal helper shared by every
 * consumer — no domain may hand-roll a second jump query or a second flash
 * timing contract (R40: a copied door is a drifting door).
 */

/** One vue-router location descriptor (structural, so no router import cycle). */
export interface AnchorRoute {
  path: string
  query: Record<string, string>
}

/**
 * The /knowledge door: document level by default, paragraph level when the
 * anchor carries a chunkId (vector-row id). Empty or missing chunkIds are
 * normalized away so the page never sees a stale `?chunkId=` pointer.
 */
export function knowledgeCitationRoute(refId: number, chunkId?: string | null): AnchorRoute {
  const query: Record<string, string> = { docId: String(refId) }
  if (chunkId !== null && chunkId !== undefined && chunkId !== '') {
    query.chunkId = chunkId
  }
  return { path: '/knowledge', query }
}

/** How long one anchored target stays flashed while being revealed. */
export const ANCHOR_FLASH_MS = 1600

/**
 * Reveal one anchored element: center it in the viewport and flash a class.
 * The host page owns the CSS, this owns the timing and the add/remove so all
 * three anchor families degrade identically. A null element (the anchor is
 * not on this page) is an honest no-op — the jump still landed on the right
 * document, only the paragraph highlight is lost.
 */
export function flashRevealElement(el: Element | null, flashClass: string): void {
  if (!el) return
  el.scrollIntoView({ behavior: 'smooth', block: 'center' })
  el.classList.add(flashClass)
  window.setTimeout(() => el.classList.remove(flashClass), ANCHOR_FLASH_MS)
}
