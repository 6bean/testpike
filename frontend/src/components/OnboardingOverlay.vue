<template>
  <el-dialog
    v-model="visible"
    class="onboarding-dialog"
    :title="t('journey.overlayTitle')"
    width="560px"
    draggable
    :modal="false"
    modal-penetrable
    :lock-scroll="false"
    :close-on-click-modal="false"
    :show-close="true"
    data-test="onboarding-overlay"
    @closed="dismiss"
  >
    <div class="ob-intro text-secondary">{{ t('journey.overlayIntro') }}</div>
    <div class="ob-journeys">
      <div
        v-for="(j, idx) in quickJourneys"
        :key="j.id"
        class="ob-item"
        :data-test="`onboarding-item-${j.id}`"
        @click="startJourney(j)"
      >
        <span class="ob-index">{{ idx + 1 }}</span>
        <el-icon :size="18" color="var(--tp-primary)"><component :is="j.icon" /></el-icon>
        <span class="ob-title">{{ t(j.titleKey) }}</span>
        
        <em
          v-if="j.steps.some((s) => s.requireHardware)"
          class="ob-need-device"
          :data-test="`onboarding-need-device-${j.id}`"
        >{{ t('journey.stepNeedDevice') }}</em>
        <span class="ob-steps text-secondary">{{ j.steps.map((s) => t(s.labelKey)).join(' → ') }}</span>
      </div>
    </div>
    <template #footer>
      <el-button type="primary" data-test="onboarding-start" @click="startJourney(quickJourneys[0])">
        {{ t('journey.overlayStart') }}
      </el-button>
      <el-button data-test="onboarding-dismiss" @click="visible = false">
        {{ t('journey.overlayDismiss') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">

import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { quickJourneys, ONBOARDING_DISMISS_KEY, type QuickJourney } from '@/config/journeys'

const router = useRouter()
const route = useRoute()
const { t } = useI18n()
const visible = ref(false)

watch(
  () => route.fullPath,
  () => {
    if (visible.value) visible.value = false
  }
)

function dismissed(): boolean {
  try {
    return localStorage.getItem(ONBOARDING_DISMISS_KEY) === '1'
  } catch {
    return false
  }
}

function dismiss(): void {
  try {
    localStorage.setItem(ONBOARDING_DISMISS_KEY, '1')
  } catch {
  }
}

function startJourney(j: QuickJourney): void {
  visible.value = false
  router.push(j.targetPath)
}

onMounted(() => {
  if (!dismissed()) visible.value = true
})
</script>

<style scoped lang="scss">
.ob-intro {
  font-size: 13px;
  margin-bottom: 12px;
  line-height: 1.6;
}

.ob-journeys {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.ob-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid var(--tp-border, rgb(128 128 128 / 18%));
  border-radius: 10px;
  cursor: pointer;
  transition: border-color 0.15s ease;

  &:hover {
    border-color: var(--tp-primary);
  }
}

.ob-index {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: var(--tp-primary-weak, rgb(56 189 248 / 18%));
  color: var(--tp-primary);
  font-size: 12px;
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.ob-title {
  font-size: 13px;
  font-weight: 600;
  flex-shrink: 0;
}

.ob-steps {
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
