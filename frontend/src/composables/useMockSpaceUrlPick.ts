import { useRoute, useRouter } from 'vue-router'
import type { Ref } from 'vue'
import { resolveSpaceIdFromQuery, withMockJumpQuery } from '@/utils/mockJump'

/**
 * AG batch shared URL wiring for the two mock pages that pick
 * a space (docs page + traffic inspector). One implementation of the §12a
 * pre-filter contract (red line: reuse >=2 must be extracted, no per-page copy):
 * a spaceId riding the URL lands only if it exists in the freshly loaded
 * space list (honest consumption, never a guessed id); every pick writes the
 * query back so reload / back navigation keeps the context.
 */
export function useMockSpaceUrlPick(pickedSpace: Ref<number | null>, projectId: Ref<number | null>) {
  const route = useRoute()
  const router = useRouter()

  /** Apply a URL-carried spaceId to the loaded list; returns the resolved id or null. */
  function pickFromQuery(ids: number[]): number | null {
    return resolveSpaceIdFromQuery(route.query.spaceId, ids)
  }

  /** Write the current project/space pick back into the URL query. */
  async function syncSpaceToUrl(): Promise<void> {
    await router.replace(
      withMockJumpQuery(route.query, { projectId: projectId.value, spaceId: pickedSpace.value }),
    )
  }

  return { pickFromQuery, syncSpaceToUrl }
}
