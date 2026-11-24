<template>
  <div class="page-root reservation-calendar-page" data-test="reservation-calendar-page">
    <div class="page-card">
      <!-- R23 stage-2: the title shell row is gone — the page name rides the
           global topbar and the retired help entry now lives on the topbar
           help dot (data-test=header-help-btn, leaf helpDomain X resolves the
           same chapter anchor). The pending badge and the create entry moved
           into the filter-bar leading slot (R23 law 5: actions + status
           badge left, screening center, view-state trailing); anchors kept. -->
      <div class="filter-bar" data-test="reservation-filter-bar">
        <el-badge
          v-if="pendingCount > 0"
          :value="pendingCount"
          type="warning"
          class="pending-badge"
          data-test="reservation-pending-badge"
        >
          <span class="pending-badge-anchor">{{ t('appauto.reservation.pendingBadge') }}</span>
        </el-badge>
        <el-tooltip
          :content="t('components.ownerEcho.pickInHeader')"
          placement="bottom"
          :disabled="!!projectId"
        >
          <span>
            <el-button
              type="primary"
              :icon="Plus"
              :disabled="!projectId || !canCreate"
              data-test="reservation-create-btn"
              @click="openCreateDialog(null)"
            >
              {{ t('appauto.reservation.create') }}
            </el-button>
          </span>
        </el-tooltip>

        <!-- EP date-picker does not forward attrs (same measured posture); the
             range control is not consumed by the test surface yet, so the
             data-test anchor lives on the wrapper to stay locatable -->
        <div class="range-wrap" data-test="reservation-calendar-range">
          <el-date-picker
            v-model="range"
            :id="['reservation-range-start', 'reservation-range-end']"
            type="datetimerange"
            value-format="YYYY-MM-DDTHH:mm:ss"
            :start-placeholder="t('appauto.reservation.rangeStart')"
            :end-placeholder="t('appauto.reservation.rangeEnd')"
            @change="load"
          />
        </div>
        <div class="filter-bar-actions">
          <!-- (R21 bands): refresh moved out of the header band. -->
          <el-button :icon="Refresh" :loading="loading" data-test="reservation-calendar-refresh" @click="load">
            {{ t('appauto.reservation.refresh') }}
          </el-button>
        </div>
      </div>

      <p v-if="projectId && canCreate" class="drag-hint" data-test="reservation-drag-hint">
        {{ t('appauto.reservation.dragHint') }}
      </p>

      <div v-if="!loading && !projectId" class="empty-box" data-test="reservation-calendar-empty-no-project">
        <EmptyState
          :title="t('appauto.reservation.noProjectTitle')"
          :hint="t('appauto.reservation.noProjectHint')"
        />
      </div>

      <TableSkeleton v-else-if="loading" :cols="6" data-test="reservation-calendar-skeleton" />

      <div v-else-if="!lanes.length" class="empty-box" data-test="reservation-calendar-empty-lanes">
        <EmptyState
          :title="t('appauto.reservation.emptyLanesTitle')"
          :hint="t('appauto.reservation.emptyLanesHint')"
        />
      </div>

      <div v-else class="calendar-shell" data-test="reservation-calendar-shell">
        <div class="timeline-head">
          <div class="lane-label-spacer" />
          <div class="axis" :style="{ height: `${laneHeight}px` }">
            <span
              v-for="tick in axisTicks"
              :key="tick.at"
              class="axis-tick"
              :style="{ left: `${tick.pct}%` }"
            >{{ tick.label }}</span>
          </div>
        </div>
        <div class="lanes">
          <div v-for="lane in lanes" :key="lane.deviceId" class="lane" data-test="reservation-lane">
            <div class="lane-label">
              <div class="lane-name" :title="lane.name">{{ lane.name }}</div>
              <div class="lane-model">{{ lane.model || '-' }}</div>
            </div>
            <div
              class="lane-track"
              :style="{ height: `${laneHeight}px` }"
              @pointerdown="onLanePointerDown($event, lane)"
            >
              <div
                v-if="lane.activeLease"
                class="lease-bar"
                :style="blockStyle(leaseStart(lane), leaseEnd(lane))"
                data-test="reservation-lease-bar"
              >
                <el-tooltip
                  :content="t('appauto.reservation.leaseTooltip', {
                    executor: lane.activeLease.holderExecutorId || t('appauto.reservation.unknownExecutor'),
                    eta: fmtAbs(lane.activeLease.expiresAt),
                  })"
                  placement="top"
                >
                  <span class="lease-bar-text">{{ t('appauto.reservation.leaseRunning') }}</span>
                </el-tooltip>
              </div>
              <div
                v-for="r in lane.reservations"
                :key="r.id"
                class="res-block"
                :class="`state-${r.state.toLowerCase()}`"
                :style="blockStyle(r.startsAt, r.endsAt)"
                data-test="reservation-block"
                @pointerup.stop
                @click.stop="openDetail(r)"
              >
                <span class="res-block-name">{{ r.holderName }}</span>
                <span class="res-block-time">{{ fmtHM(r.startsAt) }}-{{ fmtHM(r.endsAt) }}</span>
                <el-tag
                  v-if="r.state === 'PENDING_APPROVAL'"
                  size="small"
                  type="warning"
                  disable-transitions
                  class="res-block-tag"
                >{{ RESERVATION_STATE_LABEL.PENDING_APPROVAL }}</el-tag>
              </div>
              <div
                v-if="nowPct !== null"
                class="now-line"
                :style="{ left: `${nowPct}%` }"
                data-test="reservation-now-line"
              />
              <div
                v-if="drag && drag.lane.deviceId === lane.deviceId"
                class="drag-preview"
                :style="blockStyle(isoOf(drag.a), isoOf(drag.b))"
                data-test="reservation-drag-preview"
              />
            </div>
          </div>
        </div>
      </div>

      <el-card v-if="projectId && myReservations.length" class="my-card" data-test="reservation-my-card">
        <template #header>{{ t('appauto.reservation.myTitle') }}</template>
        <div
          v-for="r in myReservations"
          :key="r.id"
          class="my-row"
          data-test="reservation-my-row"
          @click="openDetail(r)"
        >
          <span class="my-device">{{ deviceName(r.deviceId) }}</span>
          <span class="my-time">{{ fmtAbs(r.startsAt) }} → {{ fmtAbs(r.endsAt) }}</span>
          <el-tag size="small" disable-transitions>{{ RESERVATION_STATE_LABEL[r.state] || r.state }}</el-tag>
        </div>
      </el-card>

      
      <el-dialog
        v-model="createVisible"
        :title="t('appauto.reservation.createTitle')"
        width="640px"
        data-test="reservation-create-dialog"
      >
        <!-- ownership echo (iron rule 1): read-only, single-sourced from the
 header project context - the record lands on the visible segment -->
        <ProjectOwnerEcho />
        <el-form label-position="top">
          <el-form-item :label="t('appauto.reservation.fieldDevice')">
            <el-select
              v-model="createForm.deviceId"
              filterable
              data-test="reservation-form-device"
              :placeholder="t('appauto.reservation.pickDevice')"
            >
              <el-option
                v-for="lane in lanes"
                :key="lane.deviceId"
                :label="lane.name"
                :value="lane.deviceId"
              />
            </el-select>
          </el-form-item>
          
          <el-form-item :label="t('appauto.reservation.fieldStart')" data-test="reservation-form-start">
            <el-date-picker
              v-model="createForm.startsAt"
              type="datetime"
              value-format="YYYY-MM-DDTHH:mm:ss"
            />
          </el-form-item>
          <el-form-item :label="t('appauto.reservation.fieldEnd')" data-test="reservation-form-end">
            <el-date-picker
              v-model="createForm.endsAt"
              type="datetime"
              value-format="YYYY-MM-DDTHH:mm:ss"
            />
          </el-form-item>
          <el-form-item :label="t('appauto.reservation.fieldReason')">
            <el-input
              v-model="createForm.reason"
              type="textarea"
              :rows="2"
              :placeholder="t('appauto.reservation.reasonPlaceholder')"
              data-test="reservation-form-reason"
            />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button data-test="reservation-create-cancel" @click="createVisible = false">
            {{ t('common.cancel') }}
          </el-button>
          <el-button
            type="primary"
            :loading="creating"
            data-test="reservation-create-submit"
            @click="submitCreate"
          >
            {{ t('common.confirm') }}
          </el-button>
        </template>
      </el-dialog>

      
      <el-drawer v-model="detailVisible" size="480px" data-test="reservation-detail-drawer">
        <template #header>{{ t('appauto.reservation.detailTitle') }}</template>
        <el-descriptions v-if="detail" :column="1" border data-test="reservation-detail-body">
          <el-descriptions-item :label="t('appauto.reservation.fieldDevice')">
            {{ deviceName(detail.deviceId) }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('appauto.reservation.fieldHolder')">{{ detail.holderName }}</el-descriptions-item>
          <el-descriptions-item :label="t('appauto.reservation.fieldState')">
            {{ RESERVATION_STATE_LABEL[detail.state] || detail.state }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('appauto.reservation.fieldWindow')">
            {{ fmtAbs(detail.startsAt) }} → {{ fmtAbs(detail.endsAt) }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('appauto.reservation.fieldReason')">{{ detail.reason || '-' }}</el-descriptions-item>
          <el-descriptions-item :label="t('appauto.reservation.fieldBatch')">
            <template v-if="detail.batchId">
              <router-link :to="`/batches/${detail.batchId}`" data-test="reservation-detail-batch-link">
                #{{ detail.batchId }}
              </router-link>
            </template>
            <span v-else>{{ t('appauto.reservation.batchUnbound') }}</span>
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.releaseReason" :label="t('appauto.reservation.fieldReleaseReason')">
            {{ detail.releaseReason }}
          </el-descriptions-item>
        </el-descriptions>
        <div v-if="detail" class="detail-actions">
          <template v-if="detail.state === 'PENDING_APPROVAL' && canApprove">
            <el-button
              type="success"
              :loading="acting"
              data-test="reservation-approve-btn"
              @click="doApprove"
            >{{ t('appauto.reservation.approve') }}</el-button>
            <el-button
              type="danger"
              :loading="acting"
              data-test="reservation-reject-btn"
              @click="doReject"
            >{{ t('appauto.reservation.reject') }}</el-button>
          </template>
          <template v-if="isActiveState(detail.state) && canCreate">
            <el-button
              :loading="acting"
              data-test="reservation-cancel-btn"
              @click="doCancel"
            >{{ t('appauto.reservation.cancelReserve') }}</el-button>
            <el-button
              v-if="detail.state === 'ACTIVE'"
              type="primary"
              :loading="acting"
              data-test="reservation-release-btn"
              @click="doRelease"
            >{{ t('appauto.reservation.release') }}</el-button>
          </template>
        </div>
      </el-drawer>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import ProjectOwnerEcho from '@/components/ProjectOwnerEcho.vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import { useAuthStore } from '@/stores/auth'
import { bizErrorMsg } from '@/utils/bizError'
import {
  RESERVATION_STATE_LABEL,
  approveReservation,
  cancelReservation,
  createReservation,
  getReservationCalendar,
  rejectReservation,
  releaseReservation,
  type AppautoReservation,
  type ReservationCalendarLane,
} from '@/api/appauto'

defineOptions({ name: 'DeviceReservationCalendarView' })

const { t } = useI18n()
const auth = useAuthStore()

const canCreate = computed(() => auth.hasPerm('perm:appauto:reservation:create'))
const canApprove = computed(() => auth.hasPerm('perm:appauto:reservation:approve'))

// : the project segment rides the global header context (the page-level
// selector is gone); the legacy single-project auto-pick is retired with it.
const { projectId, followTick } = useProjectPageContext()
const loading = ref(false)
const lanes = ref<ReservationCalendarLane[]>([])
const pendingCount = ref(0)


const range = ref<[string, string] | null>(null)
const viewWindow = ref<{ from: number; to: number }>({ from: 0, to: 1 })

const laneHeight = 56

const fromMs = computed(() => viewWindow.value.from)
const toMs = computed(() => viewWindow.value.to)

function parseIso(iso?: string | null): number {
  if (!iso) return Number.NaN
  return new Date(iso.replace('T', ' ').replace(/-/g, '/')).getTime()
}

function isoOf(ms: number): string {
  const d = new Date(ms)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

function blockStyle(startIso: string, endIso: string) {
  const a = parseIso(startIso)
  const b = parseIso(endIso)
  const span = toMs.value - fromMs.value
  const left = Math.max(0, ((a - fromMs.value) / span) * 100)
  const right = Math.min(100, ((b - fromMs.value) / span) * 100)
  return { left: `${left}%`, width: `${Math.max(0.6, right - left)}%` }
}

function leaseStart(lane: ReservationCalendarLane): string {
  return lane.activeLease?.acquiredAt || isoOf(fromMs.value)
}

function leaseEnd(lane: ReservationCalendarLane): string {
  return lane.activeLease?.expiresAt || isoOf(toMs.value)
}


const nowPct = computed(() => {
  const now = Date.now()
  const span = toMs.value - fromMs.value
  if (span <= 0 || now < fromMs.value || now > toMs.value) return null
  return ((now - fromMs.value) / span) * 100
})


const axisTicks = computed(() => {
  const spanH = (toMs.value - fromMs.value) / 3600000
  const stepH = spanH <= 12 ? 1 : spanH <= 26 ? 2 : spanH <= 60 ? 4 : 6
  const ticks: { at: string; pct: number; label: string }[] = []
  const first = Math.ceil(fromMs.value / (stepH * 3600000)) * stepH * 3600000
  for (let ms = first; ms <= toMs.value; ms += stepH * 3600000) {
    const d = new Date(ms)
    ticks.push({
      at: isoOf(ms),
      pct: ((ms - fromMs.value) / (toMs.value - fromMs.value)) * 100,
      label: `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:00`,
    })
  }
  return ticks
})


const myReservations = computed(() => {
  const uid = auth.user?.userId
  if (!uid) return []
  return lanes.value.flatMap((l) => l.reservations).filter((r) => r.holderUserId === uid)
})

function deviceName(deviceId: number): string {
  return lanes.value.find((l) => l.deviceId === deviceId)?.name || `#${deviceId}`
}

function fmtAbs(iso?: string | null): string {
  return iso ? iso.replace('T', ' ').slice(0, 19) : '-'
}

function fmtHM(iso?: string | null): string {
  return iso ? iso.slice(11, 16) : '-'
}

function isActiveState(state: string): boolean {
  return state === 'ACTIVE' || state === 'PENDING_APPROVAL'
}

async function load() {
  if (!projectId.value) {
    lanes.value = []
    pendingCount.value = 0
    return
  }
  loading.value = true
  try {
    const view = await getReservationCalendar(
      projectId.value,
      range.value?.[0],
      range.value?.[1],
    )
    lanes.value = view.lanes
    pendingCount.value = view.pendingApprovalCount
    viewWindow.value = { from: parseIso(view.from), to: parseIso(view.to) }
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('appauto.reservation.loadFail')))
  } finally {
    loading.value = false
  }
}

const SNAP_MS = 15 * 60 * 1000
const drag = ref<{ lane: ReservationCalendarLane; a: number; b: number } | null>(null)
const dragMoved = ref(false)

function msAt(laneEl: HTMLElement, clientX: number): number {
  const rect = laneEl.getBoundingClientRect()
  const ratio = Math.min(1, Math.max(0, (clientX - rect.left) / rect.width))
  const raw = fromMs.value + ratio * (toMs.value - fromMs.value)
  return Math.round(raw / SNAP_MS) * SNAP_MS
}

function onLanePointerDown(ev: PointerEvent, lane: ReservationCalendarLane) {
  if (!canCreate.value || ev.button !== 0) return
  const el = ev.currentTarget as HTMLElement
  const start = msAt(el, ev.clientX)
  drag.value = { lane, a: start, b: start }
  dragMoved.value = false
  const move = (e: PointerEvent) => {
    if (!drag.value) return
    drag.value.b = msAt(el, e.clientX)
    if (Math.abs(drag.value.b - drag.value.a) > SNAP_MS / 2) dragMoved.value = true
  }
  const up = () => {
    window.removeEventListener('pointermove', move)
    window.removeEventListener('pointerup', up)
    const d = drag.value
    drag.value = null
    if (!d) return
    const lo = Math.min(d.a, d.b)
    const hi = Math.max(d.a, d.b)
    if (dragMoved.value && hi - lo >= SNAP_MS) {
      openCreateDialog(lane, isoOf(lo), isoOf(hi))
    } else if (!dragMoved.value && d.a >= fromMs.value && d.a <= toMs.value) {
      openCreateDialog(lane, isoOf(d.a), isoOf(Math.min(d.a + 3600000, toMs.value)))
    }
  }
  window.addEventListener('pointermove', move)
  window.addEventListener('pointerup', up)
}

const createVisible = ref(false)
const creating = ref(false)
const createForm = ref<{ deviceId: number | null; startsAt: string; endsAt: string; reason: string }>({
  deviceId: null,
  startsAt: '',
  endsAt: '',
  reason: '',
})

function openCreateDialog(lane: ReservationCalendarLane | null, startsAt?: string, endsAt?: string) {
  createForm.value = {
    deviceId: lane?.deviceId ?? null,
    startsAt: startsAt || isoOf(Date.now()),
    endsAt: endsAt || isoOf(Date.now() + 3600000),
    reason: '',
  }
  createVisible.value = true
}

async function submitCreate() {
  if (!projectId.value) return
  const f = createForm.value
  if (!f.deviceId || !f.startsAt || !f.endsAt) {
    ElMessage.warning(t('appauto.reservation.formIncomplete'))
    return
  }
  creating.value = true
  try {
    await createReservation({
      projectId: projectId.value,
      deviceId: f.deviceId,
      startsAt: f.startsAt,
      endsAt: f.endsAt,
      reason: f.reason || null,
    })
    ElMessage.success(t('appauto.reservation.createOk'))
    createVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('appauto.reservation.createFail')))
  } finally {
    creating.value = false
  }
}

const detailVisible = ref(false)
const detail = ref<AppautoReservation | null>(null)
const acting = ref(false)

function openDetail(r: AppautoReservation) {
  detail.value = r
  detailVisible.value = true
}


async function afterAction(updated: AppautoReservation) {
  await load()
  const still = lanes.value
    .flatMap((l) => l.reservations)
    .find((r) => r.id === updated.id)
  if (still) detail.value = still
  else detailVisible.value = false
}

async function doApprove() {
  if (!detail.value) return
  acting.value = true
  try {
    await afterAction(await approveReservation(detail.value.id))
    ElMessage.success(t('appauto.reservation.approveOk'))
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('appauto.reservation.actionFail')))
  } finally {
    acting.value = false
  }
}

async function doReject() {
  if (!detail.value) return
  try {
    const { value } = await ElMessageBox.prompt(
      t('appauto.reservation.rejectPrompt'),
      t('appauto.reservation.reject'),
      { inputPlaceholder: t('appauto.reservation.rejectPlaceholder'), inputValidator: (v: string) => (v && v.trim() ? true : t('appauto.reservation.rejectRequired')) },
    )
    acting.value = true
    await afterAction(await rejectReservation(detail.value.id, value))
    ElMessage.success(t('appauto.reservation.rejectOk'))
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') ElMessage.error(bizErrorMsg(e, t('appauto.reservation.actionFail')))
  } finally {
    acting.value = false
  }
}

async function doRelease() {
  if (!detail.value) return
  acting.value = true
  try {
    await afterAction(await releaseReservation(detail.value.id))
    ElMessage.success(t('appauto.reservation.releaseOk'))
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('appauto.reservation.actionFail')))
  } finally {
    acting.value = false
  }
}

async function doCancel() {
  if (!detail.value) return
  try {
    await ElMessageBox.confirm(t('appauto.reservation.cancelConfirm'), t('appauto.reservation.cancelReserve'), { type: 'warning' })
  } catch {
    return
  }
  acting.value = true
  try {
    await afterAction(await cancelReservation(detail.value.id))
    ElMessage.success(t('appauto.reservation.cancelOk'))
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('appauto.reservation.actionFail')))
  } finally {
    acting.value = false
  }
}

onMounted(() => {
  load()
})
onUnmounted(() => {
  drag.value = null
})

// : a header context switch reloads from the new segment (in-page
// switches reload through their own controls, so followTick only fires
// on context-driven changes and never double-loads).
watch(followTick, () => {
  void load()
})

</script>

<style scoped>
.drag-hint {
  margin: 0 0 8px;
  color: var(--tp-text-secondary);
  font-size: 12px;
}
.empty-box {
  padding: 24px 0;
}
.pending-badge {
  margin-left: 12px;
  vertical-align: middle;
}
.pending-badge-anchor {
  font-size: 12px;
  font-weight: normal;
  color: var(--tp-text-secondary);
}
.calendar-shell {
  border: 1px solid var(--tp-border-color, #333);
  border-radius: 8px;
  overflow: hidden;
}
.timeline-head {
  display: flex;
  border-bottom: 1px solid var(--tp-border-color, #333);
}
.lane-label-spacer {
  flex: 0 0 180px;
  border-right: 1px solid var(--tp-border-color, #333);
}
.axis {
  position: relative;
  flex: 1;
  min-width: 0;
}
.axis-tick {
  position: absolute;
  top: 50%;
  transform: translate(-50%, -50%);
  font-size: 11px;
  color: var(--tp-text-secondary);
  white-space: nowrap;
}
.lanes {
  overflow-x: hidden;
}
.lane {
  display: flex;
  border-bottom: 1px solid var(--tp-border-color, #333);
}
.lane:last-child {
  border-bottom: none;
}
.lane-label {
  flex: 0 0 180px;
  padding: 8px 10px;
  border-right: 1px solid var(--tp-border-color, #333);
  overflow: hidden;
}
.lane-name {
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.lane-model {
  font-size: 11px;
  color: var(--tp-text-secondary);
}
.lane-track {
  position: relative;
  flex: 1;
  min-width: 0;
  cursor: crosshair;
  user-select: none;
}
.res-block {
  position: absolute;
  top: 6px;
  height: 42px;
  border-radius: 6px;
  padding: 4px 6px;
  font-size: 11px;
  line-height: 1.4;
  overflow: hidden;
  cursor: pointer;
  border: 1px solid transparent;
}
.res-block.state-active {
  background: rgba(64, 158, 255, 0.22);
  border-color: var(--el-color-primary, #409eff);
}
.res-block.state-pending_approval {
  background: rgba(230, 162, 60, 0.22);
  border-color: var(--el-color-warning, #e6a23c);
}
.res-block-name {
  display: block;
  font-weight: 600;
}
.res-block-time {
  display: block;
  color: var(--tp-text-secondary);
}
.res-block-tag {
  position: absolute;
  right: 4px;
  top: 4px;
  transform: scale(0.8);
}
.lease-bar {
  position: absolute;
  bottom: 4px;
  height: 8px;
  border-radius: 4px;
  background: var(--el-color-danger, #f56c6c);
  opacity: 0.85;
  min-width: 4px;
}
.lease-bar-text {
  position: absolute;
  top: -16px;
  left: 0;
  font-size: 11px;
  color: var(--el-color-danger, #f56c6c);
  white-space: nowrap;
}
.now-line {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 2px;
  background: var(--el-color-success, #67c23a);
  pointer-events: none;
}
.drag-preview {
  position: absolute;
  top: 6px;
  height: 42px;
  border-radius: 6px;
  background: rgba(64, 158, 255, 0.14);
  border: 1px dashed var(--el-color-primary, #409eff);
  pointer-events: none;
}
.my-card {
  margin-top: 16px;
}
.my-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 6px 4px;
  cursor: pointer;
  border-radius: 6px;
}
.my-row:hover {
  background: var(--tp-bg-hover, rgba(255, 255, 255, 0.04));
}
.my-device {
  font-weight: 600;
}
.my-time {
  color: var(--tp-text-secondary);
  font-size: 12px;
}
.detail-actions {
  margin-top: 16px;
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
</style>
