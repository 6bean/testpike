
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { resolveProjectRouteParam } from './useProjectContextRoute'
import { useAuthStore } from '@/stores/auth'
import { useProjectContextStore } from '@/stores/projectContext'
import { withProjectQuery } from '@/utils/projectQuery'

export function useProjectPageContext() {
  const route = useRoute()
  const router = useRouter()
  const auth = useAuthStore()
  const ctx = useProjectContextStore()
  const projectId = ref<number | null>(null)

  if (auth.user?.userId != null && ctx.selectedIds.length === 0) ctx.loadForUser()

  const resolved = resolveProjectRouteParam(
    route.query.projectId as string | (string | null)[] | undefined,
    ctx.selectedIds,
  )
  projectId.value = resolved.projectId
  if (resolved.writeBack && resolved.projectId != null) {
    void router.replace(withProjectQuery(route.query, resolved.projectId))
  }

  // Deep-linked project missing from the locally-hydrated visible domain (a
  // project created after the last /users/me snapshot — SPA hash navigation
  // never re-fetches it): refresh the visible domain once through the existing
  // loadMe single source before the context decides. Without this the forced
  // prune (watch auth.projects -> pruneToVisible) drops a perfectly valid
  // ?projectId and the page lies "no visible projects" ( door-2 E2E root
  // cause, browser-reproved). A still-invalid id (deleted/not a member) stays
  // pruned — honest absence, no privilege widening (H1 guard unchanged
  // server-side; this only refreshes the read-only visibility snapshot).
  if (resolved.projectId != null && !auth.projects.some((p) => p.projectId === resolved.projectId)) {
    void auth
      .loadMe()
      .catch(() => {
        /* network failure keeps the stale snapshot; the prune path stays honest */
      })
  }

  /** Page-level (re)selection: write the store single source + sync the query.
   *  A single id always lands FIRST in the selection = the "in-page switch syncs
   *  the global context" ruling of the AK batch (primaryId consumer posture of
   *  single-source pages stays deterministic under a multi-select header). */
  function syncToContext(): void {
    if (projectId.value != null) ctx.select([projectId.value])
    void router.replace(withProjectQuery(route.query, projectId.value))
  }

  // deep-link landing: an explicit ?projectId consumed by rule 1 never
  // touches the store (the priority chain keeps it local). Without this one-way
  // sync the header summary would stay "no project" while the page already
  // works — the ruling says a deep link lands in the global context too.
  // Idempotent: ctx.select([v]) is a no-op when primaryId already equals v.
  watch(projectId, (v) => {
    if (v != null && ctx.selectedIds[0] !== v) ctx.select([v])
  }, { immediate: true })

  const followTick = ref(0)
  watch(
    () => ctx.selectedIds,
    (ids) => {
      if (ids.length === 0) return
      if (projectId.value == null || !ids.includes(projectId.value)) {
        projectId.value = ids[0]
        void router.replace(withProjectQuery(route.query, projectId.value))
        followTick.value++
      }
    },
  )

  return { projectId, syncToContext, followTick }
}
