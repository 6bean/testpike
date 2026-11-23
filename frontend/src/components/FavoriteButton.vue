<script setup lang="ts">
import { computed, inject, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Star, StarFilled } from '@element-plus/icons-vue'
import {
  addFavorite, listFavorites, removeFavorite,
  type FavoriteEntityType,
} from '@/api/favorite'
import { FAVORITE_SET_INJECT_KEY, type FavoriteSetInject } from '@/composables/useFavoriteSet'
import { BizError } from '@/api/request'

const props = withDefaults(defineProps<{
  entityType: FavoriteEntityType
  entityId: number
  
  preset?: boolean | null
  size?: 'small' | 'default'
}>(), { preset: undefined })

const emit = defineEmits<{ change: [entityId: number, favorited: boolean] }>()
const { t } = useI18n()

const injected = inject<FavoriteSetInject | null>(FAVORITE_SET_INJECT_KEY, null)

const sharedIds = computed(() =>
  injected && injected.entityType === props.entityType ? injected.ids.value : null,
)

const favorited = ref(
  sharedIds.value ? sharedIds.value.has(props.entityId) : (props.preset ?? false),
)
const busy = ref(false)


async function refresh(): Promise<void> {
  if (sharedIds.value || props.preset !== undefined) return
  if (!props.entityId) return
  try {
    const items = await listFavorites(props.entityType)
    favorited.value = items.some((i) => i.entityId === props.entityId)
  } catch {
  }
}

async function toggle(): Promise<void> {
  if (busy.value || !props.entityId) return
  busy.value = true
  try {
    if (favorited.value) {
      await removeFavorite(props.entityType, props.entityId)
      favorited.value = false
      ElMessage.success(t('favorite.removedToast'))
    } else {
      await addFavorite(props.entityType, props.entityId)
      favorited.value = true
      ElMessage.success(t('favorite.addedToast'))
    }
    emit('change', props.entityId, favorited.value)
  } catch (e) {
    if (e instanceof BizError) ElMessage.error(e.message || t('favorite.failedFallback'))
  } finally {
    busy.value = false
  }
}

watch(
  () => (sharedIds.value ? sharedIds.value.has(props.entityId) : props.preset),
  (v) => {
    if (v !== null && v !== undefined) favorited.value = v
  },
)

onMounted(() => void refresh())

defineExpose({ refresh })
</script>

<template>
  <el-button
    link
    :size="size ?? 'default'"
    :loading="busy"
    class="fav-btn"
    :class="{ 'fav-on': favorited }"
    :data-test="`favorite-${entityType}-${entityId}`"
    :title="favorited ? t('favorite.unfavAria') : t('favorite.favAria')"
    @click.stop="toggle"
  >
    <el-icon :size="18">
      <StarFilled v-if="favorited" data-test="favorite-star-on" />
      <Star v-else data-test="favorite-star-off" />
    </el-icon>
  </el-button>
</template>

<style scoped lang="scss">
.fav-btn {
  padding: 2px 4px;

  .el-icon {
    color: var(--tp-text-secondary);
  }

  &.fav-on .el-icon {
    color: var(--tp-warning);   
  }

  &:hover .el-icon {
    color: var(--tp-warning);
  }
}
</style>
