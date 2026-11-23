import { computed, provide, ref } from 'vue'
import type { ComputedRef } from 'vue'
import { listFavorites, type FavoriteEntityType, type FavoriteItem } from '@/api/favorite'




export interface FavoriteSetInject {
  entityType: FavoriteEntityType
  ids: ComputedRef<Set<number>>
}

/** provide/inject key */
export const FAVORITE_SET_INJECT_KEY = 'tpFavoriteSet'

export function useFavoriteSet(entityType: FavoriteEntityType) {
  const rawIds = ref<Set<number>>(new Set())
  const items = ref<FavoriteItem[]>([])

  async function reload(): Promise<void> {
    try {
      items.value = await listFavorites(entityType)
      rawIds.value = new Set(items.value.map((i) => i.entityId))
    } catch {
      rawIds.value = new Set()
      items.value = []
    }
  }

  
  function apply(entityId: number, favorited: boolean): void {
    const next = new Set(rawIds.value)
    if (favorited) next.add(entityId)
    else next.delete(entityId)
    rawIds.value = next
  }

  const ids = computed(() => rawIds.value)
  provide(FAVORITE_SET_INJECT_KEY, { entityType, ids } as FavoriteSetInject)

  return { ids, items, reload, apply }
}
