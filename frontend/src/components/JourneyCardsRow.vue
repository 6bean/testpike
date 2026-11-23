<template>
  <div class="page-card journey-row" data-test="journey-cards-row">
    <div class="journey-head">
      <span class="journey-title">{{ t('journey.sectionTitle') }}</span>
      <span class="journey-hint text-secondary">{{ t('journey.sectionHint') }}</span>
    </div>
    <div class="journey-cards">
      <div
        v-for="j in quickJourneys"
        :key="j.id"
        class="journey-card clickable"
        :data-test="`journey-card-${j.id}`"
        @click="goJourney(j)"
      >
        <div class="journey-card-top">
          <el-icon :size="20" color="var(--tp-primary)"><component :is="j.icon" /></el-icon>
          <span class="journey-card-title">{{ t(j.titleKey) }}</span>
        </div>
        <div class="journey-steps">
          <span
            v-for="s in j.steps"
            :key="s.probeKey"
            class="journey-step"
            :class="stepClass(j, s.probeKey)"
            :data-test="`journey-step-${j.id}-${s.probeKey}`"
          >
            <el-icon v-if="stepDone(j, s.probeKey)" :size="12"><Select /></el-icon>
            {{ t(s.labelKey) }}
            <em v-if="stepUnreachable(j, s.probeKey)" class="step-unreachable">（{{ t('journey.unreachable') }}）</em>
            <em v-else-if="stepNeedDevice(j, s.probeKey)" class="step-need-device-mark" :data-test="`journey-step-need-device-${j.id}-${s.probeKey}`">（{{ t('journey.stepNeedDevice') }}）</em>
          </span>
        </div>
        <el-progress
          :percentage="progressFor(j).percent"
          :stroke-width="6"
          :status="progressStatus(j)"
          :show-text="false"
          data-test="journey-progress"
        />
        <div class="journey-card-foot text-secondary">{{ footText(j) }}</div>
        
        <div v-if="hasDeviceGuide(j)" class="journey-device-guide" data-test="journey-device-guide">
          <p class="guide-reason">{{ t('journey.deviceGuideReason') }}</p>
          <p class="guide-path">{{ t('journey.deviceGuidePath') }}</p>
          <p class="guide-ayout text-secondary">{{ t('journey.deviceGuideWayOut') }}</p>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">

import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { Select } from '@element-plus/icons-vue'
import { quickJourneys, computeJourneyProgress, missingHardwareSteps, type JourneyProgress, type QuickJourney } from '@/config/journeys'
import { runJourneyProbes } from '@/utils/journeyProbes'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const { t } = useI18n()
const authStore = useAuthStore()

const outcomes = ref<Record<string, { ok: boolean; count: number }>>({})


const probeProjectId = computed(() => authStore.projects[0]?.projectId ?? null)

const progressCache = computed(() => {
  const map = new Map<string, JourneyProgress>()
  for (const j of quickJourneys) map.set(j.id, computeJourneyProgress(j, outcomes.value))
  return map
})

function progressFor(j: QuickJourney): JourneyProgress {
  return progressCache.value.get(j.id) ?? { doneSteps: 0, totalSteps: j.steps.length, percent: 0, unreachable: false }
}

function stepOutcome(probeKey: string) {
  return outcomes.value[probeKey]
}

function stepDone(j: QuickJourney, probeKey: string): boolean {
  const o = stepOutcome(probeKey)
  return !!o && o.ok && o.count > 0
}

function stepUnreachable(j: QuickJourney, probeKey: string): boolean {
  const o = stepOutcome(probeKey)
  return !!o && !o.ok
}


function stepNeedDevice(j: QuickJourney, probeKey: string): boolean {
  return missingHardwareSteps(j, outcomes.value).includes(probeKey)
}


function hasDeviceGuide(j: QuickJourney): boolean {
  return missingHardwareSteps(j, outcomes.value).length > 0
}

function stepClass(j: QuickJourney, probeKey: string): string {
  if (stepUnreachable(j, probeKey)) return 'step-bad'
  if (stepNeedDevice(j, probeKey)) return 'step-need-device'
  if (stepDone(j, probeKey)) return 'step-done'
  return 'step-todo'
}

function progressStatus(j: QuickJourney): 'success' | 'warning' | undefined {
  const p = progressFor(j)
  if (p.percent === 100) return 'success'
  if (p.unreachable) return 'warning'
  return undefined
}

function footText(j: QuickJourney): string {
  const p = progressFor(j)
  if (p.unreachable) return t('journey.unreachable')
  if (p.percent === 100) return t('journey.doneMark')
  return `${p.doneSteps}/${p.totalSteps} · ${t('journey.openGo')}`
}

function goJourney(j: QuickJourney): void {
  router.push(j.targetPath)
}

onMounted(async () => {
  try {
    await authStore.loadMe()
  } catch {
  }
  await refreshProbes()
  document.addEventListener('visibilitychange', onVisibility)
})

onBeforeUnmount(() => {
  document.removeEventListener('visibilitychange', onVisibility)
  if (hwTimer) {
    clearInterval(hwTimer)
    hwTimer = null
  }
})


async function refreshProbes(): Promise<void> {
  const keys = quickJourneys.flatMap((j) => j.steps.map((s) => s.probeKey))
  const gate = { hasPerm: (code: string) => authStore.hasPerm(code), projectIds: authStore.projects.map((p) => p.projectId) }
  outcomes.value = await runJourneyProbes(keys, probeProjectId.value, gate)
  syncHwPolling()
}


const HW_POLL_MS = 60_000
let hwTimer: ReturnType<typeof setInterval> | null = null

function hwPending(): boolean {
  return quickJourneys.some((j) => missingHardwareSteps(j, outcomes.value).length > 0)
}

function syncHwPolling(): void {
  if (hwPending() && !hwTimer) hwTimer = setInterval(() => void refreshProbes(), HW_POLL_MS)
  else if (!hwPending() && hwTimer) {
    clearInterval(hwTimer)
    hwTimer = null
  }
}

function onVisibility(): void {
  if (document.visibilityState === 'visible') void refreshProbes()
}
</script>

<style scoped lang="scss">
.journey-row {
  .journey-head {
    display: flex;
    align-items: baseline;
    gap: 10px;
    margin-bottom: 12px;
  }

  .journey-title {
    font-size: 16px;
    font-weight: 600;
  }

  .journey-hint {
    font-size: 12px;
  }
}

.journey-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 12px;
}

.journey-card {
  border: 1px solid var(--tp-border, rgb(128 128 128 / 18%));
  border-radius: 10px;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  transition: transform 0.15s ease;

  &:hover {
    transform: translateY(-2px);
    box-shadow: 0 6px 18px rgb(0 0 0 / 18%);
  }
}

.journey-card-top {
  display: flex;
  align-items: center;
  gap: 8px;
}

.journey-card-title {
  font-size: 13px;
  font-weight: 600;
}

.journey-steps {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 12px;
}

.journey-step {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  border-radius: 10px;
  background: var(--tp-bg-elevated, rgb(128 128 128 / 12%));
  color: var(--tp-text-secondary);

  &.step-done {
    color: var(--tp-success);
  }

  &.step-bad {
    color: var(--tp-warning);
  }

  &.step-need-device {
    color: var(--tp-warning);
    border: 1px dashed var(--tp-warning);
  }

  .step-unreachable,
  .step-need-device-mark {
    font-style: normal;
    font-size: 11px;
  }
}

.journey-card-foot {
  font-size: 11px;
}

.journey-device-guide {
  margin-top: 8px;
  padding: 6px 8px;
  border-left: 2px solid var(--tp-warning);
  border-radius: 4px;
  background: var(--tp-bg-elevated, rgb(128 128 128 / 10%));

  p {
    margin: 0 0 2px;
    font-size: 11px;
    line-height: 1.5;
  }

  .guide-reason {
    color: var(--tp-warning);
    font-weight: 600;
  }
}
</style>
