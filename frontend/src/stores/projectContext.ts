import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'
import { useAuthStore } from './auth'

/**
 * Header global project context (AK batch): pick once, every page inherits;
 * persisted per user as a UI preference.
 * - Single-select contract: the selection is constructionally at most ONE
 *   id (primaryId === selectedIds[0] or null). The array shape is kept so the
 *   five consumer pages stay untouched; legacy multi-select payloads loaded
 *   from disk are truncated to the first item (migration, zero errors).
 * - Persistence = localStorage key tp_proj_ctx_<userId> (no cross-account
 *   bleed); cleared on logout. Ids that leave the visible domain are pruned
 *   lazily and counted (lastPrunedCount feeds the UI-layer plain-word toast —
 *   the store stays component-library free).
 * - Context = filter state, NOT an authorization credential: every request is
 *   still guarded server-side (red line preserved); this store only remembers
 *   what was picked.
 */
export const PROJECT_CTX_KEY_PREFIX = 'tp_proj_ctx_'

const ANON_BUCKET = 'anon'

function ctxKey(userId: number | null | undefined): string {
  return `${PROJECT_CTX_KEY_PREFIX}${userId ?? ANON_BUCKET}`
}

/** Defensive on-disk parsing: only positive integers are trusted, and the
 *  trusted set is reduced to ONE id (single-select migration — a legacy
 *  multi-select payload from before the switch lands as its first item instead
 *  of erroring). */
function parseStoredIds(raw: string | null): number[] {
  if (!raw) return []
  try {
    const arr: unknown = JSON.parse(raw)
    if (!Array.isArray(arr)) return []
    const valid = arr.filter((v): v is number => typeof v === 'number' && Number.isInteger(v) && v > 0)
    return valid.slice(0, 1)
  } catch {
    return []
  }
}

export const useProjectContextStore = defineStore('projectContext', () => {
  const auth = useAuthStore()
  const selectedIds = ref<number[]>([])
  
  const lastPrunedCount = ref(0)

 /** Single-select consumer slot: the one picked project (null = honest absence).
  * Kept as selectedIds[0] so the five consumer pages stay untouched. */
  const primaryId = computed<number | null>(() => selectedIds.value[0] ?? null)

  /**
   * Single source for the ownership echo (T396b rule 1): the visible-domain name
   * of primaryId. Every create dialog renders this value instead of assembling its
   * own label (red line 6: no duplicated logic across pages).
   * Honest absence: an id that left the visible domain (deleted/archived, not yet
   * pruned) yields null - a stale name is never echoed.
   */
  const primaryName = computed<string | null>(() => {
    const id = primaryId.value
    if (id == null) return null
    return auth.projects.find((p) => p.projectId === id)?.projectName ?? null
  })

  function persist(): void {
    localStorage.setItem(ctxKey(auth.user?.userId), JSON.stringify(selectedIds.value))
  }

  /** Accept at most one id. Callers may still hand over an array (the
   *  five consumer pages and the page-wiring helper keep their shape); only
   *  the first valid entry survives. */
  function select(ids: number[]): void {
    const first = ids.find((id) => Number.isInteger(id) && id > 0)
    selectedIds.value = first == null ? [] : [first]
    persist()
  }

  
  function loadForUser(): void {
    const restored = parseStoredIds(localStorage.getItem(ctxKey(auth.user?.userId)))
    const same = restored.length === selectedIds.value.length
      && restored.every((v, i) => v === selectedIds.value[i])
    if (!same) selectedIds.value = restored
    pruneToVisible()
  }

  
  function clearOnLogout(): void {
    selectedIds.value = []
    lastPrunedCount.value = 0
    localStorage.removeItem(ctxKey(auth.user?.userId))
  }

  
  function doPrune(force: boolean): void {
    if (selectedIds.value.length === 0) return
    if (!force && auth.projects.length === 0) return
    const visible = new Set(auth.projects.map((p) => p.projectId))
    const kept = selectedIds.value.filter((id) => visible.has(id))
    const pruned = selectedIds.value.length - kept.length
    if (pruned > 0) {
      selectedIds.value = kept
      lastPrunedCount.value += pruned
      persist()
    }
  }

  function pruneToVisible(): void {
    doPrune(false)
  }

  
  function acknowledgePruning(): void {
    lastPrunedCount.value = 0
  }

  watch(() => auth.user?.userId, (next, prev) => {
    if (next == null) {
      selectedIds.value = []
      lastPrunedCount.value = 0
      localStorage.removeItem(ctxKey(prev))
    } else {
      loadForUser()
    }
  })
  watch(() => auth.projects, () => doPrune(true))

  return { selectedIds, primaryId, primaryName, lastPrunedCount, select, loadForUser, clearOnLogout, pruneToVisible, acknowledgePruning }
})
