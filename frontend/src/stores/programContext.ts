import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'
import { useAuthStore } from './auth'

/**
 * Header program lens : pick a program once, the project switcher
 * and every project-scoped page only show that program's projects.
 * - Zero-touch contract: with no programs (or none picked) the lens is OFF —
 * scopedProjectIds stays null and inScope answers true, so users without
 * programs keep exactly the pre-feature behaviour.
 * - Persistence = localStorage key tp_program_ctx_<userId> (no cross-account
 * bleed), cleared on logout; only positive integers are trusted from disk.
 * - Silent relax: auth.programs is the snapshot (replaced wholesale by
 * loadMe). When the active id vanishes from it the lens drops to null and
 * persists immediately — no toast, no dialog (switcher zero-disturbance).
 * - This store is the single owner of the program lens value; consumers read
 * inScope/activeProgramId and never keep a second copy.
 * - Context = filter state, NOT an authorization credential: every request is
 * still guarded server-side; this store only remembers what was picked.
 */
export const PROGRAM_CTX_KEY_PREFIX = 'tp_program_ctx_'
/** Logged-out bucket (shared-browser anonymous state never merges into a user key) */
const ANON_BUCKET = 'anon'

function ctxKey(userId: number | null | undefined): string {
  return `${PROGRAM_CTX_KEY_PREFIX}${userId ?? ANON_BUCKET}`
}

/** Defensive on-disk parsing: only positive integers are trusted. */
function parseStoredId(raw: string | null): number | null {
  if (!raw) return null
  try {
    const v: unknown = JSON.parse(raw)
    return typeof v === 'number' && Number.isInteger(v) && v > 0 ? v : null
  } catch {
    return null
  }
}

export const useProgramContextStore = defineStore('programContext', () => {
  const auth = useAuthStore()
  const activeProgramId = ref<number | null>(null)

  /** Switcher option source = login snapshot (zero remote calls, same contract family as the project header). */
  const programOptions = computed(() =>
    auth.programs.map((p) => ({ programId: p.programId, name: p.name })),
  )

  /** null = lens off (no filtering); a Set = only these project ids are in scope.
   *  A dangling active id (left the snapshot, prune watcher still pending) also
   *  reads as off — the UI must never flicker a half-stale scope. */
  const scopedProjectIds = computed<Set<number> | null>(() => {
    const id = activeProgramId.value
    if (id == null) return null
    const prog = auth.programs.find((p) => p.programId === id)
    if (!prog) return null
    return new Set(prog.projectIds)
  })

  function inScope(projectId: number): boolean {
    const set = scopedProjectIds.value
    return set === null ? true : set.has(projectId)
  }

  function persist(): void {
    localStorage.setItem(ctxKey(auth.user?.userId), JSON.stringify(activeProgramId.value))
  }

  /** Pick a program (positive integer) or switch the lens off with null. */
  function select(id: number | null): void {
    activeProgramId.value = id != null && Number.isInteger(id) && id > 0 ? id : null
    persist()
  }

  /** Active id vanished from the snapshot = relax to off, disk follows. */
  function relaxIfStale(): void {
    const id = activeProgramId.value
    if (id == null) return
    if (!auth.programs.some((p) => p.programId === id)) {
      activeProgramId.value = null
      persist()
    }
  }

  /** Restore this user's lens. Deliberately NO relax here: the snapshot may
   *  not have arrived yet (hydration before loadMe), and relaxing would
   *  overwrite the disk with 'null' — permanent loss of a still-valid pick.
   *  A dangling id reads as OFF through scopedProjectIds until the snapshot
   *  watcher confirms it vanished; idempotent law: equal scalar wakes no watchers. */
  function loadForUser(): void {
    activeProgramId.value = parseStoredId(localStorage.getItem(ctxKey(auth.user?.userId)))
  }

  /** Logout clears memory + this user's on-disk key (personal lens never survives accounts). */
  function clearOnLogout(): void {
    activeProgramId.value = null
    localStorage.removeItem(ctxKey(auth.user?.userId))
  }

  // User swap (incl. login landing) = load that user's lens; logout (user null) =
  // clear memory + the previous user's key (watch carries prev, covers token-expiry
  // redirects where no explicit logout() call ever happens).
  watch(() => auth.user?.userId, (next, prev) => {
    if (next == null) {
      activeProgramId.value = null
      localStorage.removeItem(ctxKey(prev))
    } else {
      loadForUser()
    }
  })
  // Snapshot replaced wholesale (loadMe) = silent relax of a dangling pick.
  watch(() => auth.programs, () => relaxIfStale())

  return { activeProgramId, programOptions, scopedProjectIds, inScope, select, loadForUser, clearOnLogout }
})
