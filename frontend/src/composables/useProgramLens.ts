/**
 * Header program-lens consumption — single source for list pages ( MP4-b,
 * R40). The header switcher owns the program id; a page only READS it
 * (R30 set-dimension projection — an in-page program picker is forbidden).
 *
 * Every lens page forwarded the same two fragments verbatim
 * (`programId: activeProgramId ?? undefined` inside the list query and the
 * flip-then-reload-at-page-1 watcher). That is exactly the duplication the
 * R40 gate caught between BatchListView and DefectListView, so both
 * fragments live here once and each page consumes them.
 */
import { watch, type WatchStopHandle } from 'vue'
import { useProgramContextStore } from '@/stores/programContext'

/**
 * The param value forwarded onto a list query. null/absent lens → undefined
 * so the request layer drops the key entirely — the request stays
 * byte-identical to the pre-feature (legacy) shape.
 */
export function programIdParam(): number | undefined {
  return useProgramContextStore().activeProgramId ?? undefined
}

/**
 * Header lens flip (pick / switch / detach) → refetch the visible segment.
 * `guard` blocks the reload while the page has no project context yet (no
 * data to scope); `reload` carries the page-1 reset, same posture as the
 * project-header followTick reload.
 */
export function watchProgramLens(
  reload: () => void,
  guard?: () => boolean,
): WatchStopHandle {
  const lens = useProgramContextStore()
  return watch(() => lens.activeProgramId, () => {
    if (guard && !guard()) return
    reload()
  })
}

/**
 * Empty-state copy branch table . When the
 * list result is empty the copy must say WHY in operational terms and give
 * the way out:
 * - program lens ON: the rows are filtered to the picked program, so the
 * title names the program segment (page-owned key with the domain noun)
 * and the shared hint points at the header switcher — detach the lens
 * or pick another program (there is deliberately no in-page picker,
 * R30: the header is the only switch);
 * - lens ON + active filters: the same title, but the hint stays the
 * filter one and the page keeps its clear-filter exit (filter copy
 * wins the hint because the exit lives in that same area);
 * - lens OFF: the page's legacy title key verbatim (hint stays '' — the
 * page keeps its own legacy hint, zero drift for users who never pick a
 * program).
 * The caller passes its own setup-scoped `t` (same instance the page renders
 * with); reading useI18n inside the resolver would bind injection at
 * computed-evaluation time — after unmount the context is gone (unhandled
 * error, caught by the wiring contracts).
 */
export interface LensEmptyCopyOptions {
  /** The page's setup-scoped translate function (from `useI18n()`). */
  translate: (key: string) => string
  /** Program-slice title key owned by the page, e.g. `defect.lensEmptyTitle`. */
  titleKey: string
  /** Legacy empty title key of the page (no lens → shown as-is). */
  emptyKey: string
  /** Active filter/search state of the list query. */
  filtered?: boolean
}

export function lensEmptyCopy(o: LensEmptyCopyOptions) {
  const lensActive = programIdParam() !== undefined
  if (!lensActive) {
    return { lensActive, filtered: o.filtered ?? false, title: o.translate(o.emptyKey), hint: '' }
  }
  return {
    lensActive,
    filtered: o.filtered ?? false,
    title: o.translate(o.titleKey),
    hint: o.translate(o.filtered ? 'common.emptyFilteredHint' : 'common.lensEmptyHint'),
  }
}

/**
 * Deep-link landing parse: a URL ?programId=<n> carries the lens
 * the link author intends — dashboard drilldowns, shared and bookmarked links
 * must reproduce the same segment (R22 4.1). Defensive: only a positive integer
 * lands; a repeated query takes its first entry; anything else lands nothing.
 */
export function parseProgramIdQuery(raw: unknown): number | null {
  const first = Array.isArray(raw) ? raw[0] : raw
  const text = typeof first === 'number' ? String(first) : typeof first === 'string' ? first.trim() : ''
  if (!/^\d+$/.test(text)) return null
  const v = Number(text)
  return v > 0 && Number.isSafeInteger(v) ? v : null
}

/**
 * List-query project anchor under the lens (R40 single source).
 * A deep-linked program view lands with the single-project segment cleared, so
 * the query must carry projectId=0 — the server-side three-branch posture
 * (CaseFilters/DefectFilters/batch/apitest/perf) resolves 0 + lens into
 * "program-wide inside the caller's visible ∩ lens set". null = nothing
 * scopes the query (no project AND no lens) — pages keep their early return.
 */
export function listProjectScope(projectId: number | null): number | null {
  if (projectId != null && projectId > 0) return projectId
  return programIdParam() != null ? 0 : null
}

/**
 * Load-gate companion of listProjectScope (R40 single source). True when the
 * page has a resolvable list scope: an explicit project OR the header lens
 * (program-wide anchor). Views must gate their list fetches here instead of a
 * bare `!!projectId` — otherwise a deep-linked landing (lens on, project
 * cleared) never fires a request and strands on the lens empty state.
 */
export function hasQueryScope(projectId: number | null): boolean {
  return listProjectScope(projectId) != null
}
