<template>
  <div class="pref-panel" data-test="pref-panel">
    <div class="pref-toolbar">
      <span class="page-subtitle">
        {{ t('notification.prefToolbarTip') }}
      </span>
      <div class="pref-toolbar-right">
        <el-tag v-if="dirtyCount > 0" type="warning" size="small" data-test="pref-dirty-tag">
          {{ t('notification.unsavedCount', { n: dirtyCount }) }}
        </el-tag>
        <el-button type="primary" :disabled="dirtyCount === 0" :loading="saving" data-test="pref-save-btn" @click="save">
          <el-icon><Check /></el-icon>{{ t('notification.saveBtn') }}
        </el-button>
      </div>
    </div>

    <div v-loading="loading" class="pref-groups">
      <div v-for="group in groups" :key="group.key" class="pref-group">
        <div class="pref-group-head">
          <span class="pref-group-title">{{ t(group.labelKey) }}</span>
          <span v-if="group.hintKey" class="pref-group-hint">{{ t(group.hintKey) }}</span>
        </div>
        <div class="pref-items">
          <div v-for="item in group.items" :key="item.eventType" class="pref-item">
            <div class="pref-item-main">
              <span class="pref-item-label" :data-test="`pref-label-${item.eventType}`">
                {{ eventLabel(item.eventType) }}
                <el-tag v-if="item.forced" size="small" type="danger">{{ t('notification.forcedTag') }}</el-tag>
                <el-tag v-else-if="item.defaultOff" size="small" type="info">{{ t('notification.defaultOffTag') }}</el-tag>
              </span>
              <span class="pref-item-code">{{ item.eventType }}</span>
            </div>
            <el-tooltip
              :content="t('notification.forcedTooltip')"
              :disabled="!item.forced"
              placement="top"
            >
              <el-switch
                :model-value="item.enabled"
                :disabled="item.forced"
                :data-test="`pref-switch-${item.eventType}`"
                @update:model-value="(v: any) => toggle(item, Boolean(v))"
              />
            </el-tooltip>
          </div>
        </div>
      </div>
      <el-empty v-if="!loading && groups.length === 0" :description="t('notification.emptyEvents')" :image-size="80" />
    </div>
  </div>
</template>

<script setup lang="ts">

import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Check } from '@element-plus/icons-vue'
import { eventLabel } from '@/api/notification'
import {
  PREF_EVENT_GROUPS,
  PREF_GROUPS,
  diffPrefToggles,
  listNotificationPrefs,
  updateNotificationPrefs,
  type NotificationPrefItem,
} from '@/api/notificationPref'

const { t } = useI18n()

const loading = ref(false)
const saving = ref(false)
const items = ref<NotificationPrefItem[]>([])

const baseline = ref<NotificationPrefItem[]>([])

const groups = computed(() => {
  const byKey = new Map<string, NotificationPrefItem[]>()
  for (const item of items.value) {
    const key = PREF_EVENT_GROUPS[item.eventType] ?? 'other'
    if (!byKey.has(key)) byKey.set(key, [])
    byKey.get(key)!.push(item)
  }
  return PREF_GROUPS.filter((g) => (byKey.get(g.key)?.length ?? 0) > 0)
    .map((g) => ({ ...g, items: byKey.get(g.key)! }))
})

const dirtyCount = computed(
  () => diffPrefToggles(baseline.value, items.value).length,
)

async function load(): Promise<void> {
  loading.value = true
  try {
    const data = await listNotificationPrefs()
    items.value = data
    baseline.value = data.map((i) => ({ ...i }))
  } catch (e) {
    ElMessage.error((e as Error)?.message || t('notification.prefLoadFail'))
  } finally {
    loading.value = false
  }
}

function toggle(item: NotificationPrefItem, value: boolean): void {
  item.enabled = value
}

async function save(): Promise<void> {
  const toggles = diffPrefToggles(baseline.value, items.value)
  if (toggles.length === 0) return
  saving.value = true
  try {
    await updateNotificationPrefs(toggles)
    ElMessage.success(t('notification.prefSaveOk'))
    baseline.value = items.value.map((i) => ({ ...i }))
  } catch (e) {
    ElMessage.error((e as Error)?.message || t('notification.prefSaveFail'))
    await load()
  } finally {
    saving.value = false
  }
}

onMounted(() => void load())

defineExpose({ load })
</script>

<style scoped>
.pref-panel {
  padding-top: 4px;
}
.pref-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 16px;
}
.pref-toolbar-right {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
}
.pref-groups {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-height: 120px;
}
.pref-group {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 12px 16px;
}
.pref-group-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 8px;
}
.pref-group-title {
  font-weight: 600;
  font-size: 14px;
}
.pref-group-hint {
  font-size: 12px;
  color: var(--tp-text-secondary);
}
.pref-items {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 4px 24px;
}
.pref-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 0;
}
.pref-item-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.pref-item-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
}
.pref-item-code {
  font-size: 11px;
  color: var(--tp-text-secondary);
}
</style>
