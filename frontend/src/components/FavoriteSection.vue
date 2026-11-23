<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ArrowRight, Clock, Star, StarFilled } from '@element-plus/icons-vue'
import {
  favoriteTarget, listFavorites,
  FAVORITE_TYPE_LABEL, type FavoriteItem,
} from '@/api/favorite'
import { getRecentVisits } from '@/composables/useRecentVisits'

const router = useRouter()
const { t } = useI18n()

const favorites = ref<FavoriteItem[]>([])
const recent = ref<FavoriteItem[]>([])
const loading = ref(true)

async function load(): Promise<void> {
  loading.value = true
  try {
    favorites.value = await listFavorites()
  } catch {
    favorites.value = []
  } finally {
    loading.value = false
  }
  recent.value = getRecentVisits()
}

function go(item: FavoriteItem): void {
  void router.push(favoriteTarget(item))
}

onMounted(() => void load())
defineExpose({ load })
</script>

<template>
  <el-row :gutter="16" class="fav-row" data-test="favorite-section">
    
    <el-col :xs="24" :sm="14">
      <div class="page-card section-card">
        <div class="section-title">
          <el-icon color="var(--tp-warning)"><StarFilled /></el-icon>
          {{ t('favorite.myFavorites') }}
        </div>
        <div v-if="loading" class="fav-empty text-secondary">{{ t('common.loading') }}</div>
        <div v-else-if="favorites.length === 0" class="fav-empty text-secondary" data-test="favorite-empty">
          {{ t('favorite.noFavoriteHint') }}
        </div>
        <div
          v-for="item in favorites"
          :key="`f-${item.entityType}-${item.entityId}`"
          class="fav-item"
          data-test="favorite-item"
          @click="go(item)"
        >
          <el-tag size="small">{{ FAVORITE_TYPE_LABEL[item.entityType] }}</el-tag>
          <span class="fav-title" :title="item.title">{{ item.title }}</span>
          <span v-if="item.subtitle" class="fav-sub mono text-secondary">{{ item.subtitle }}</span>
          <el-icon class="fav-go"><ArrowRight /></el-icon>
        </div>
      </div>
    </el-col>
    
    <el-col :xs="24" :sm="10">
      <div class="page-card section-card">
        <div class="section-title">
          <el-icon><Clock /></el-icon>
          {{ t('favorite.recentVisits') }}
        </div>
        <div v-if="recent.length === 0" class="fav-empty text-secondary" data-test="recent-empty">
          {{ t('favorite.noRecentHint') }}
        </div>
        <div
          v-for="item in recent"
          :key="`r-${item.entityType}-${item.entityId}`"
          class="fav-item"
          data-test="recent-item"
          @click="go(item)"
        >
          <el-icon class="fav-star-off"><Star /></el-icon>
          <span class="fav-title" :title="item.title">{{ item.title }}</span>
          <span class="fav-time text-secondary">{{ item.favoritedAt }}</span>
        </div>
      </div>
    </el-col>
  </el-row>
</template>

<style scoped lang="scss">
.fav-row {
  & + .bottom-row,
  .section-card {
    min-height: 180px;
  }
}

.section-card {
  .section-title {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 16px;
    font-weight: 600;
    margin-bottom: 12px;
  }
}

.fav-empty {
  padding: 24px 0;
  text-align: center;
  font-size: 13px;
}

.fav-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 4px;
  border-bottom: 1px solid var(--tp-border, rgb(128 128 128 / 15%));
  cursor: pointer;

  &:last-child {
    border-bottom: none;
  }

  &:hover .fav-title {
    color: var(--tp-primary, #38bdf8);
  }
}

.fav-title {
  flex: 1;
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.fav-sub {
  font-size: 12px;
  flex-shrink: 0;
}

.fav-time {
  font-size: 12px;
  flex-shrink: 0;
}

.fav-go {
  flex-shrink: 0;
  color: var(--tp-text-secondary);
}

.fav-star-off .el-icon {
  color: var(--tp-text-secondary);
}
</style>
