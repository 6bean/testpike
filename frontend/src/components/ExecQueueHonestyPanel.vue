<script setup lang="ts">

import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  listAppautoEngineAvailability,
  type AppautoDevice,
  type AppautoEngineAvailability,
  type AppautoLeaseRun,
} from '@/api/appauto'
import { loadOccupancySnapshot } from '@/composables/useDeviceOccupancy'
import type { ExecTask } from '@/api/executor'

const props = defineProps<{ task: ExecTask | null }>()
const { t } = useI18n()


const isAppTask = computed(() => props.task?.schemaVersion === 'appauto-v1')

const inFlight = computed(() => props.task?.status === 1 || props.task?.status === 2)
const visible = computed(() => isAppTask.value && inFlight.value)

const devices = ref<AppautoDevice[]>([])
const engines = ref<AppautoEngineAvailability[]>([])
const activeLeases = ref<AppautoLeaseRun[]>([])
const loadFailed = ref(false)


const queueReasons = computed(() =>
  engines.value.filter((e) => !e.available).flatMap((e) => e.missingReasons))

async function load(): Promise<void> {
  loadFailed.value = false
  activeLeases.value = []
  const task = props.task
  try {
    engines.value = await listAppautoEngineAvailability()
    if (task == null) return
    const snap = await loadOccupancySnapshot(task.projectId)
    devices.value = snap.devices
    activeLeases.value = snap.activeLeases
    loadFailed.value = snap.loadFailed
  } catch {
    loadFailed.value = true
    engines.value = []
    devices.value = []
    activeLeases.value = []
  }
}


function deviceName(deviceId: number): string {
  return devices.value.find((d) => d.id === deviceId)?.name ?? `#${deviceId}`
}


function fmt(iso?: string | null): string {
  return iso ? iso.replace('T', ' ').slice(0, 19) : '-'
}

watch(() => (visible.value ? props.task?.id : undefined), (id) => {
  if (id != null) void load()
}, { immediate: true })
</script>

<template>
  <div v-if="visible" class="queue-honesty" data-test="queue-honesty">
    <h4 class="block-title">{{ t('exec.queueTitle') }}</h4>
    <div v-if="loadFailed" class="queue-unavailable" data-test="queue-unavailable">
      {{ t('exec.queueLoadFail') }}
    </div>
    <template v-else>
      
      <ul v-if="queueReasons.length" class="queue-reasons" data-test="queue-reasons">
        <li v-for="(r, i) in queueReasons" :key="i">{{ r }}</li>
      </ul>
      <div v-else class="queue-reason-ok" data-test="queue-reason-ok">{{ t('exec.queueCapable') }}</div>

      
      <div v-if="activeLeases.length" class="queue-leases" data-test="queue-leases">
        <div v-for="l in activeLeases" :key="l.id" class="lease-row">
          <span class="mono">{{ deviceName(l.deviceId) }}</span>
          <span>{{ t('exec.queueLeaseHolder', { executor: l.holderExecutorId || t('exec.queueUnknownExecutor') }) }}</span>
          <span>{{ t('exec.queueLeaseUntil', { time: fmt(l.expiresAt) }) }}</span>
        </div>
      </div>
      <div v-else class="queue-idle" data-test="queue-idle">{{ t('exec.queueIdle') }}</div>
    </template>
  </div>
</template>

<style scoped>
.queue-honesty { margin-top: 12px; padding: 10px 12px; border: 1px solid var(--tp-border); border-radius: 8px; }
.block-title { margin: 0 0 8px; font-size: 13px; color: var(--tp-text-primary); }
.queue-reasons { margin: 0 0 8px; padding-left: 18px; font-size: 12px; color: var(--tp-warning); line-height: 1.8; }
.queue-reason-ok { font-size: 12px; color: var(--tp-text-secondary); margin-bottom: 8px; }
.lease-row { font-size: 12px; color: var(--tp-text-primary); line-height: 1.9; display: flex; gap: 12px; flex-wrap: wrap; }
.queue-idle { font-size: 12px; color: var(--tp-text-secondary); }
.queue-unavailable { font-size: 12px; color: var(--tp-text-secondary); }
.mono { font-family: var(--tp-font-mono, monospace); }
</style>
