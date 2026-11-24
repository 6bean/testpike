import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { addFavorite, removeFavorite, type FavoriteEntityType } from '@/api/favorite'
import { BizError } from '@/api/request'

/**
 * Single source for the favorite add/remove round-trip.
 *
 * The star button on list rows and the "favorite" entry folded into the row
 * "more" menu are two faces of one action; keeping the api call, the busy
 * latch and the toasts here means a behaviour change (message, error
 * handling) lands once instead of drifting between copies.
 */
export function useFavoriteToggle() {
  const busy = ref(false)
  const { t } = useI18n()

  /** Returns the resulting state, or null when the call failed (caller keeps
   *  its own optimistic set untouched in that case). */
  async function toggle(entityType: FavoriteEntityType, entityId: number, favorited: boolean): Promise<boolean | null> {
    if (busy.value || !entityId) return null
    busy.value = true
    try {
      if (favorited) {
        await removeFavorite(entityType, entityId)
        ElMessage.success(t('favorite.removedToast'))
        return false
      }
      await addFavorite(entityType, entityId)
      ElMessage.success(t('favorite.addedToast'))
      return true
    } catch (e) {
      if (e instanceof BizError) ElMessage.error(e.message || t('favorite.failedFallback'))
      return null
    } finally {
      busy.value = false
    }
  }

  return { busy, toggle }
}
