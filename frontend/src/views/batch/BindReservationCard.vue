<template>
  <el-card shadow="never" class="bind-card" data-test="batch-bind-reservation-card">
    <template #header>
      <div class="bind-head">
        <span>{{ t('batch.bindReservationTitle') }}</span>
        <router-link to="/appauto/reservations" data-test="batch-bind-calendar-link">
          {{ t('batch.bindReservationCalendar') }}
        </router-link>
      </div>
    </template>
    <p v-if="loading" class="bind-loading">{{ t('batch.bindReservationLoading') }}</p>
    <template v-else>
      <p v-if="!bound.length" class="bind-empty" data-test="batch-bind-empty">
        {{ t('batch.bindReservationEmpty') }}
      </p>
      <div v-for="r in bound" :key="r.id" class="bind-row" data-test="batch-bind-row">
        <span class="bind-device">{{ deviceLabel(r.deviceId) }}</span>
        <span class="bind-window">{{ fmtAbs(r.startsAt) }} → {{ fmtAbs(r.endsAt) }}</span>
        <el-tag size="small" disable-transitions>{{ RESERVATION_STATE_LABEL[r.state] || r.state }}</el-tag>
        <span class="bind-holder">{{ r.holderName }}</span>
        <el-button
          link
          type="danger"
          :loading="actingId === r.id"
          data-test="batch-bind-unbind-btn"
          @click="unbind(r)"
        >
          {{ t('batch.bindReservationUnbind') }}
        </el-button>
      </div>
      <div class="bind-add-row">
        <el-select
          v-model="pickId"
          clearable
          filterable
          class="bind-pick"
          :placeholder="t('batch.bindReservationPick')"
          data-test="batch-bind-pick"
        >
          <el-option
            v-for="opt in candidates"
            :key="opt.id"
            :label="`${deviceLabel(opt.deviceId)} · ${fmtAbs(opt.startsAt)} → ${fmtAbs(opt.endsAt)} · ${opt.holderName}`"
            :value="opt.id"
          />
        </el-select>
        <el-button
          type="primary"
          :disabled="!pickId"
          :loading="binding"
          data-test="batch-bind-submit"
          @click="bind"
        >
          {{ t('batch.bindReservationBind') }}
        </el-button>
      </div>
    </template>
  </el-card>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { bizErrorMsg } from '@/utils/bizError'
import {
  RESERVATION_STATE_LABEL,
  bindReservationBatch,
  listAppautoDevices,
  listReservations,
  type AppautoReservation,
} from '@/api/appauto'
import { useObjectSync } from '@/composables/useObjectSync'


defineOptions({ name: 'BindReservationCard' })

const props = defineProps<{ batchId: number; projectId: number | null }>()

const { t } = useI18n()
const loading = ref(false)
const binding = ref(false)
const actingId = ref<number | null>(null)
const pickId = ref<number | null>(null)

const all = ref<AppautoReservation[]>([])
const deviceNames = ref<Record<number, string>>({})

const bound = computed(() => all.value.filter((r) => r.batchId === props.batchId))

const candidates = computed(() => all.value.filter((r) => r.batchId == null))

function deviceLabel(deviceId: number): string {
  return deviceNames.value[deviceId] || `#${deviceId}`
}

function fmtAbs(iso?: string | null): string {
  return iso ? iso.replace('T', ' ').slice(0, 19) : '-'
}

async function load() {
  if (props.projectId == null) {
    all.value = []
    return
  }
  loading.value = true
  try {
    const [rows, devices] = await Promise.all([
      listReservations({ projectId: props.projectId }),
      Object.keys(deviceNames.value).length
        ? Promise.resolve(null)
        : listAppautoDevices(props.projectId),
    ])
    all.value = rows
    if (devices) {
      deviceNames.value = Object.fromEntries(devices.map((d) => [d.id, d.name]))
    }
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('batch.bindReservationLoadFail')))
  } finally {
    loading.value = false
  }
}

async function bind() {
  if (!pickId.value) return
  binding.value = true
  try {
    await bindReservationBatch(pickId.value, props.batchId)
    ElMessage.success(t('batch.bindReservationBindOk'))
    pickId.value = null
    await load()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('batch.bindReservationActionFail')))
  } finally {
    binding.value = false
  }
}

async function unbind(r: AppautoReservation) {
  actingId.value = r.id
  try {
    await bindReservationBatch(r.id, null)
    ElMessage.success(t('batch.bindReservationUnbindOk'))
    await load()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('batch.bindReservationActionFail')))
  } finally {
    actingId.value = null
  }
}

// ruling: this card renders the project's reservation
// windows, so a landed reservation write inside the SAME project re-reads
// the panel itself (anchor = projectId; per-row ids are covered by the
// re-fetch). Silent by iron rule 2 — the normal load path is reused.
useObjectSync({
  watch: ['RESERVATION_WRITE'],
  anchor: (e) => e.projectId === props.projectId,
  onSync: () => void load(),
})

watch(() => [props.batchId, props.projectId], load, { immediate: true })
</script>

<style scoped>
.bind-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.bind-empty,
.bind-loading {
  color: var(--tp-text-secondary);
  font-size: 12px;
  margin: 0;
}
.bind-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 4px 0;
  flex-wrap: wrap;
}
.bind-device {
  font-weight: 600;
}
.bind-window,
.bind-holder {
  color: var(--tp-text-secondary);
  font-size: 12px;
}
.bind-add-row {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}
.bind-pick {
  flex: 1;
  min-width: 0;
}
</style>
