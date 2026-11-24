<script setup lang="ts">
import { computed, inject, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { Star, StarFilled } from '@element-plus/icons-vue'
import { listFavorites, type FavoriteEntityType } from '@/api/favorite'
import { FAVORITE_SET_INJECT_KEY, type FavoriteSetInject } from '@/composables/useFavoriteSet'
import { useFavoriteToggle } from '@/composables/useFavoriteToggle'

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
const { busy, toggle: runToggle } = useFavoriteToggle()


async function refresh(): Promise<void> {
  if (sharedIds.value || props.preset !== undefined) return
  if (!props.entityId) return
  try {
    const items = await listFavorites(props.entityType)
    favorited.value = items.some((i) => i.entityId === props.entityId)
  } catch {
  }
}

async function toggleSelf(): Promise<void> {
  const next = await runToggle(props.entityType, props.entityId, favorited.value)
  if (next === null) return
  favorited.value = next
  emit('change', props.entityId, next)
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
    @click.stop="toggleSelf"
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
