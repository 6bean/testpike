<!-- tp-sort-exempt: the wall shows the physically attached device pool (emulators/ADB/USB of this farm); row count is hardware-bounded. -->
<template>
  <div class="page-root device-wall-page" data-test="device-wall-page">
    <div class="page-card">
      <!-- R21 same-band merge (FB group): the page carries no bulk-action bar and exactly
           one screening control, so the page-level variant actions (register/onboard) ride
           the filter-bar trailing slot ahead of the view-state actions and the emptied
           header band is removed (create first; the settings control sits ahead of refresh). -->
      <div class="filter-bar" data-test="device-wall-filter-bar">
        <el-input
          v-model="labelFilter"
          :placeholder="t('appauto.wall.labelFilter')"
          clearable
          style="width: 150px"
          data-test="device-wall-label-filter"
          @change="load"
          @clear="load"
        />
        <div class="filter-bar-actions">
          <el-button
            type="primary"
            :icon="Plus"
            :disabled="!projectId"
            data-test="device-wall-create-btn"
            @click="openCreate"
          >
            {{ t('appauto.wall.register') }}
          </el-button>
          <el-button
            :icon="Guide"
            :disabled="!projectId"
            data-test="device-wall-onboard-btn"
            @click="openOnboard"
          >
            {{ t('appauto.wall.onboard') }}
          </el-button>
          <!-- R25 mode declaration (scope-aware single control, TB group): the page has no
               tabs and exactly one page-surface table (the device wall below); the lease
               history table rides a per-row drawer and stays honest-absent from this
               popover, so the page-level column-settings control equals the wall scope.
               show/hide, order, pinning and density persist per user via the shared piece. -->
          <ColumnSettings
            :columns="settingsRows"
            :density="colCfg.state.density"
            @toggle="colCfg.toggle"
            @move="colCfg.move"
            @density="colCfg.setDensity"
            @reset="colCfg.reset"
            @pin="colCfg.toggleFixed"
          />
          <el-button :icon="Refresh" :loading="loading" data-test="device-wall-refresh" @click="load">
            {{ t('appauto.wall.refresh') }}
          </el-button>
        </div>
      </div>

      <div v-if="!loading && !projectId" class="empty-box" data-test="device-wall-empty-no-project">
        <EmptyState
          :title="t('appauto.wall.noProjectTitle')"
          :hint="t('appauto.wall.noProjectHint')"
        />
      </div>

      <TableSkeleton v-else-if="loading" :cols="colCfg.visibleColumns.value.length + 1" data-test="device-wall-skeleton" />
      <el-table v-else :data="rows" border row-key="id" :size="colCfg.state.density" class="data-table" data-test="device-wall-table">
        <template #empty>
          <EmptyState
            :filtered="!!labelFilter"
            :title="labelFilter ? t('common.emptyFilteredResult') : t('appauto.wall.emptyTitle')"
            :hint="labelFilter ? t('common.emptyFilteredHint') : t('appauto.wall.emptyCreateHint')"
          />
        </template>
        <el-table-column
          v-for="col in colCfg.visibleColumns.value"
          :key="col.key"
          :prop="col.key"
          :fixed="col.fixed || false"
          :label="t(col.labelKey)"
          :width="col.width ?? undefined"
          :min-width="col.width ? undefined : DEVICE_WALL_FLEX_MIN[col.key]"
          :show-overflow-tooltip="DEVICE_WALL_TOOLTIP.has(col.key)"
          :data-test="`device-col-${col.key}`"
        >
          <template #default="{ row }">
            <template v-if="col.key === 'owner'">
              <el-tag
                v-if="row.projectId == null"
                type="primary"
                disable-transitions
                data-test="device-wall-shared-badge"
              >{{ t('appauto.wall.sharedBadge') }}</el-tag>
              <span v-else>{{ t('appauto.wall.ownerProject') }}</span>
            </template>
            <template v-else-if="col.key === 'type'">{{ deviceTypeLabel(row.deviceType) }}</template>
            <template v-else-if="col.key === 'model'">
              {{ [row.model, row.osVersion].filter(Boolean).join(' · ') || '-' }}
            </template>
            <template v-else-if="col.key === 'onlineState'">
              <el-tag :type="healthTagType(row.healthState)" data-test="device-wall-health-tag" disable-transitions>
                {{ HEALTH_STATE_LABEL[row.healthState] || row.healthState }}
              </el-tag>
              <el-tooltip
                v-if="row.lastOverheatAt"
                :content="t('appauto.wall.overheatHint', { time: fmtAbs(row.lastOverheatAt) })"
                placement="top"
              >
                <el-tag type="danger" class="overheat-badge" data-test="device-wall-overheat-badge" disable-transitions>
                  {{ t('appauto.wall.overheatBadge') }}
                </el-tag>
              </el-tooltip>
            </template>
            <template v-else-if="col.key === 'live'">
              <img
                v-if="thumbImage(row.id)"
                :src="thumbImage(row.id)"
                class="wall-thumb"
                :alt="t('appauto.wall.thumbAlt', { name: row.name })"
                data-test="device-wall-thumb"
              />
              <span v-else-if="thumbFailed(row.id)" class="wall-thumb-empty" data-test="device-wall-thumb-failed">
                {{ t('appauto.wall.thumbFailed') }}
              </span>
              <span v-else-if="thumbReason(row.id)" class="wall-thumb-empty" data-test="device-wall-thumb-idle">{{ thumbReason(row.id) }}</span>
              <span v-else class="wall-thumb-empty" data-test="device-wall-thumb-pending">…</span>
            </template>
            <template v-else-if="col.key === 'occupancy'">
              <span v-if="occupancy[row.id]" data-test="device-wall-occupancy">
                {{ t('appauto.wall.occupiedBy', { executor: occupancy[row.id].holderExecutorId || t('appauto.wall.unknownExecutor') }) }}
                <span class="occupancy-eta">
                  {{ t('appauto.wall.etaRelease', { time: fmtExpires(occupancy[row.id].expiresAt) }) }}
                </span>
              </span>
              <span v-else class="occupancy-free" data-test="device-wall-occupancy-free">{{ t('appauto.wall.idle') }}</span>
            </template>
            <template v-else-if="col.key === 'failCount'">
              <el-tooltip
                v-if="row.consecutiveFailures > 0"
                :content="t('appauto.wall.failHint', { count: row.consecutiveFailures })"
                placement="top"
              >
                <el-tag type="danger" disable-transitions>{{ row.consecutiveFailures }}</el-tag>
              </el-tooltip>
              <span v-else>0</span>
            </template>
            <template v-else-if="col.key === 'labels'">
              <el-tag
                v-for="lb in (row.labels || [])"
                :key="lb"
                size="small"
                class="label-chip"
                disable-transitions
              >{{ lb }}</el-tag>
              <span v-if="!(row.labels && row.labels.length)">-</span>
            </template>
            <template v-else-if="col.key === 'lastSeen'"><TimeCell :iso="row.lastSeenAt" /></template>
            <template v-else>{{ row[col.key] }}</template>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.action')" width="210" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" data-test="device-wall-lease-btn" @click="openLeaseDrawer(row)">
              {{ t('appauto.wall.leaseHistory') }}
            </el-button>
            <el-button
              v-if="!isSharedWriteLocked(row)"
              link
              type="primary"
              data-test="device-wall-edit-btn"
              @click="openEdit(row)"
            >
              {{ t('common.edit') }}
            </el-button>
            <el-button
              v-if="row.healthState === 'QUARANTINED' && !isSharedWriteLocked(row)"
              link
              type="warning"
              data-test="device-wall-unquarantine-btn"
              @click="unquarantine(row)"
            >
              {{ t('appauto.wall.unquarantine') }}
            </el-button>
            <el-button
              v-if="!isSharedWriteLocked(row)"
              link
              type="danger"
              data-test="device-wall-delete-btn"
              @click="remove(row)"
            >
              {{ t('common.delete') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      
      <el-dialog
        v-model="dialogVisible"
        :title="editing ? t('appauto.wall.editTitle') : t('appauto.wall.register')"
        width="640px"
        draggable
        data-test="device-wall-dialog"
      >
        <el-form :model="form" label-width="110px">
          <el-form-item :label="t('appauto.wall.formName')" required>
            <el-input v-model="form.name" data-test="device-wall-form-name" maxlength="100" />
          </el-form-item>
          <el-form-item :label="t('appauto.wall.formSerial')" required>
            <el-input
              v-model="form.serial"
              data-test="device-wall-form-serial"
              maxlength="120"
              :disabled="!!editing"
              :placeholder="t('appauto.wall.serialHint')"
            />
          </el-form-item>
          <el-form-item :label="t('appauto.wall.formType')" required>
            <el-select v-model="form.deviceType" data-test="device-wall-form-type" style="width: 100%">
              <el-option
                v-for="ty in DEVICE_TYPES"
                :key="ty"
                :label="deviceTypeLabel(ty)"
                :value="ty"
              />
            </el-select>
          </el-form-item>
          <el-form-item v-if="form.deviceType === 'ADB'" :label="t('appauto.wall.formAdbAddress')" required>
            <el-input v-model="form.adbAddress" data-test="device-wall-form-adb" placeholder="192.168.1.10:5555" />
            <div class="form-note">{{ t('appauto.wall.adbNote') }}</div>
          </el-form-item>
   
          <el-form-item v-if="auth.isAdmin" :label="t('appauto.wall.formShared')">
            <el-switch
              v-model="sharedForm"
              data-test="device-wall-form-shared"
              :disabled="!!editing"
            />
            <div class="form-note">{{ editing && editing.projectId == null ? t('appauto.wall.sharedEditLocked') : t('appauto.wall.sharedSwitchHint') }}</div>
          </el-form-item>
          <el-form-item :label="t('appauto.wall.formModel')">
            <el-input v-model="form.model" data-test="device-wall-form-model" maxlength="80" />
          </el-form-item>
          <el-form-item :label="t('appauto.wall.formOsVersion')">
            <el-input v-model="form.osVersion" data-test="device-wall-form-os" maxlength="60" />
          </el-form-item>
          <el-form-item :label="t('appauto.wall.formResolution')">
            <el-input v-model="form.resolution" data-test="device-wall-form-resolution" placeholder="1080x2400" maxlength="40" />
          </el-form-item>
          <el-form-item :label="t('appauto.wall.formDpi')">
            <el-input-number v-model="form.dpi" :min="1" data-test="device-wall-form-dpi" />
            <span class="form-note-inline">{{ t('appauto.wall.dpiNote') }}</span>
          </el-form-item>
          <el-form-item :label="t('appauto.wall.formLabels')">
            <el-select
              v-model="form.labels"
              multiple
              filterable
              allow-create
              default-first-option
              data-test="device-wall-form-labels"
              :placeholder="t('appauto.wall.labelsPlaceholder')"
              style="width: 100%"
            />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button data-test="device-wall-dialog-cancel" @click="dialogVisible = false">{{ t('common.cancel') }}</el-button>
          <el-button type="primary" :loading="saving" data-test="device-wall-dialog-save" @click="save">
            {{ t('common.save') }}
          </el-button>
        </template>
      </el-dialog>

      
      <el-drawer
        v-model="leaseDrawerVisible"
        :title="t('appauto.wall.leaseDrawerTitle', { name: leaseDeviceName })"
        size="480px"
        data-test="device-wall-lease-drawer"
      >
        <!-- R25 (TB group): the lease-history table rides this per-row drawer and is not
             a page-surface view, so it is honest-absent from the page-level column
             settings popover and keeps its frozen four-column detail layout. -->
        <TableSkeleton v-if="leaseLoading" :cols="4" />
        <el-table border v-else :data="leaseRuns" data-test="device-wall-lease-table">
          <template #empty>
            <EmptyState :title="t('appauto.wall.leaseEmpty')" />
          </template>
          <el-table-column :label="t('appauto.wall.leaseState')" width="100">
            <template #default="{ row }">
              <el-tag :type="row.state === 'ACTIVE' ? 'warning' : row.state === 'EXPIRED' ? 'danger' : 'success'" disable-transitions>
                {{ LEASE_STATE_LABEL[row.state] || row.state }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('appauto.wall.leaseExecutor')" prop="holderExecutorId" min-width="120" show-overflow-tooltip />
          <el-table-column :label="t('appauto.wall.leaseAcquired')" width="150">
            <template #default="{ row }">{{ fmtAbs(row.acquiredAt) }}</template>
          </el-table-column>
          <el-table-column :label="t('appauto.wall.leaseRelease')" min-width="170">
            <template #default="{ row }">
              <div>{{ row.state === 'ACTIVE' ? t('appauto.wall.leaseUntilExpire', { time: fmtAbs(row.expiresAt) }) : fmtAbs(row.releasedAt) }}</div>
              <div v-if="row.releaseReason" class="lease-reason">{{ row.releaseReason }}</div>
            </template>
          </el-table-column>
        </el-table>
        <p class="lease-caliber">{{ t('appauto.wall.leaseCaliber') }}</p>
      </el-drawer>

   
      <AppautoOnboardWizard
        ref="onboardWizard"
        :project-id="projectId"
        @finished="load"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'
import ColumnSettings from '@/components/ColumnSettings.vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Guide, Plus, Refresh } from '@element-plus/icons-vue'
import AppautoOnboardWizard from './AppautoOnboardWizard.vue'
import EmptyState from '@/components/EmptyState.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import TimeCell from '@/components/TimeCell.vue'
import { useAuthStore } from '@/stores/auth'
import { bizErrorMsg } from '@/utils/bizError'
import { useDeviceWallThumbnails } from '@/composables/useDeviceWallThumbnails'
import {
  DEVICE_TYPES,
  HEALTH_STATE_LABEL,
  LEASE_STATE_LABEL,
  deleteAppautoDevice,
  listAppautoDevices,
  listDeviceLeaseRuns,
  registerAppautoDevice,
  unquarantineAppautoDevice,
  updateAppautoDevice,
  type AppautoDevice,
  type AppautoLeaseRun,
  type DeviceSavePayload,
} from '@/api/appauto'

const { t } = useI18n()
const auth = useAuthStore()

// : the project segment rides the global header context (the page-level
// selector is gone); an empty context keeps the honest "pick a project"
// empty state, deep links and header switches both land through the composable.
const { projectId, followTick } = useProjectPageContext()
const labelFilter = ref('')
const loading = ref(false)
const saving = ref(false)
const rows = ref<AppautoDevice[]>([])

const occupancy = ref<Record<number, AppautoLeaseRun>>({})

// Live thumbnails (P1 wall live face): the page owns ONE 1fps round loop and
// fans per-device reads through the shared runBounded piece (single-source
// throttle — a second limiter is the measured 32-wide burst pathology).
// Cell truth stays server-sourced (LIVE frame / honest IDLE-NO_FRAME reason /
// backed-off fetch failure); this layer never re-derives state.
const thumbPoll = useDeviceWallThumbnails(() => rows.value.map((r) => r.id))

// Column-configuration wiring (N-3 repayment, shared useColumnConfig piece):
// the wall data columns flow through the visibleColumns loop so show/hide,
// order, pinning and density persist per user under tp-colcfg-device-wall; the
// locked action column stays outside the loop. Widths/min-widths mirror the
// frozen layout; the wall is a hardware-bounded device pool (see the
// sort-exempt declaration on line 1), so no sortable rides in.
const DEVICE_WALL_COLUMNS: ColumnDef[] = [
  { key: 'name', labelKey: 'appauto.wall.colName', width: null },
  { key: 'serial', labelKey: 'appauto.wall.colSerial', width: null },
  { key: 'owner', labelKey: 'appauto.wall.colOwner', width: 110 },
  { key: 'type', labelKey: 'appauto.wall.colType', width: 110 },
  { key: 'model', labelKey: 'appauto.wall.colModel', width: null },
  { key: 'onlineState', labelKey: 'appauto.wall.colOnlineState', width: 150 },
  { key: 'live', labelKey: 'appauto.wall.colLive', width: 150 },
  { key: 'occupancy', labelKey: 'appauto.wall.colOccupancy', width: null },
  { key: 'failCount', labelKey: 'appauto.wall.colFailCount', width: 120 },
  { key: 'labels', labelKey: 'appauto.wall.colLabels', width: null },
  { key: 'lastSeen', labelKey: 'appauto.wall.colLastSeen', width: 130 },
]
/** min-width fallbacks for the flexible (width-null) columns. */
const DEVICE_WALL_FLEX_MIN: Record<string, number> = { name: 140, serial: 130, model: 150, occupancy: 200, labels: 140 }
/** Text columns whose long values clip into a tooltip (frozen show-overflow set). */
const DEVICE_WALL_TOOLTIP = new Set(['name', 'serial', 'model'])
const colCfg = useColumnConfig('device-wall', DEVICE_WALL_COLUMNS)
/** Popover rows (column state merged with labelKey for rendering). */
const settingsRows = computed(() =>
  colCfg.state.columns.map((c) => ({ ...c, labelKey: DEVICE_WALL_COLUMNS.find((d) => d.key === c.key)!.labelKey })),
)

const dialogVisible = ref(false)
const editing = ref<AppautoDevice | null>(null)
const form = ref<DeviceSavePayload>(emptyForm())

const sharedForm = ref(false)

const leaseDrawerVisible = ref(false)
const leaseLoading = ref(false)
const leaseRuns = ref<AppautoLeaseRun[]>([])
const leaseDeviceName = ref('')

function emptyForm(): DeviceSavePayload {
  return {
    projectId: projectId.value ?? 0,
    name: '',
    serial: '',
    deviceType: 'ADB',
    adbAddress: '',
    model: '',
    osVersion: '',
    resolution: '',
    dpi: undefined,
    labels: [],
  }
}

function deviceTypeLabel(ty: string): string {
  if (ty === 'EMULATOR') return t('appauto.wall.typeEmulator')
  if (ty === 'ADB') return t('appauto.wall.typeAdb')
  if (ty === 'USB') return t('appauto.wall.typeUsb')
  return ty
}

function healthTagType(state: string): 'success' | 'warning' | 'danger' | 'info' {
  if (state === 'GREEN') return 'success'
  if (state === 'YELLOW') return 'warning'
  if (state === 'RED' || state === 'QUARANTINED') return 'danger'
  return 'info'
}

function fmtAbs(iso?: string | null): string {
  return iso ? iso.replace('T', ' ').slice(0, 19) : '-'
}

function fmtExpires(iso?: string | null): string {
  return fmtAbs(iso)
}

/** LIVE frame as a data URI ('' for non-live or missing payload — the cell
 *  branch then falls through to the honest empty state). */
function thumbImage(id: number): string {
  const view = thumbPoll.cells.value[id]?.view
  if (!view || view.state !== 'LIVE' || !view.imageBase64) return ''
  return `data:image/jpeg;base64,${view.imageBase64}`
}

/** Plain reason for a non-live cell, passed through from the server. */
function thumbReason(id: number): string {
  const cell = thumbPoll.cells.value[id]
  if (!cell || cell.failed || !cell.view || cell.view.state === 'LIVE') return ''
  return cell.view.reason || t('appauto.wall.thumbNoFrame')
}

function thumbFailed(id: number): boolean {
  return !!thumbPoll.cells.value[id]?.failed
}

async function load() {
  if (!projectId.value) {
    rows.value = []
    occupancy.value = {}
    return
  }
  loading.value = true
  try {
    const list = await listAppautoDevices(projectId.value, labelFilter.value || undefined)
    rows.value = list
    occupancy.value = {}
    await Promise.allSettled(list.map(async (d) => {
      try {
        const runs = await listDeviceLeaseRuns(d.id)
        const active = runs.filter((r) => r.state === 'ACTIVE')
        if (active.length) {
          occupancy.value[d.id] = active.sort((a, b) => (a.acquiredAt ?? '').localeCompare(b.acquiredAt ?? ''))[active.length - 1]
        }
      } catch {
      }
    }))
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('appauto.wall.loadFailed')))
  } finally {
    loading.value = false
  }
}


function openCreate() {
  editing.value = null
  sharedForm.value = false
  form.value = emptyForm()
  dialogVisible.value = true
}

function openEdit(row: AppautoDevice) {
  editing.value = row
  sharedForm.value = row.projectId == null
  form.value = {
    projectId: row.projectId,
    name: row.name,
    serial: row.serial,
    deviceType: row.deviceType,
    adbAddress: row.adbAddress ?? '',
    model: row.model ?? '',
    osVersion: row.osVersion ?? '',
    resolution: row.resolution ?? '',
    dpi: row.dpi ?? undefined,
    labels: [...(row.labels ?? [])],
  }
  dialogVisible.value = true
}


function isSharedWriteLocked(row: AppautoDevice): boolean {
  return row.projectId == null && !auth.isAdmin
}

async function save() {
  if (!projectId.value) return
  if (!form.value.name.trim()) {
    ElMessage.warning(t('appauto.wall.nameRequired'))
    return
  }
  if (!form.value.serial.trim()) {
    ElMessage.warning(t('appauto.wall.serialRequired'))
    return
  }
  if (form.value.deviceType === 'ADB' && !(form.value.adbAddress ?? '').trim()) {
    ElMessage.warning(t('appauto.wall.adbRequired'))
    return
  }
  saving.value = true
  try {
    const ownerProject = sharedForm.value && !editing.value ? null : projectId.value
    const payload: DeviceSavePayload = { ...form.value, projectId: ownerProject, name: form.value.name.trim(), serial: form.value.serial.trim() }
    if (editing.value) {
      await updateAppautoDevice(editing.value.id, payload)
      ElMessage.success(t('appauto.wall.updated'))
    } else {
      await registerAppautoDevice(payload)
      ElMessage.success(t('appauto.wall.registered'))
    }
    dialogVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('appauto.wall.saveFailed')))
  } finally {
    saving.value = false
  }
}

async function unquarantine(row: AppautoDevice) {
  try {
    await unquarantineAppautoDevice(row.id)
    ElMessage.success(t('appauto.wall.unquarantined'))
    await load()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('appauto.wall.opFailed')))
  }
}

async function remove(row: AppautoDevice) {
  try {
    await ElMessageBox.confirm(
      t('appauto.wall.deleteConfirm', { name: row.name }),
      t('common.delete'),
      { type: 'warning', confirmButtonText: t('common.delete'), cancelButtonText: t('common.cancel') },
    )
  } catch {
    return
  }
  try {
    await deleteAppautoDevice(row.id)
    ElMessage.success(t('appauto.wall.deleted'))
    await load()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('appauto.wall.opFailed')))
  }
}


const onboardWizard = ref<InstanceType<typeof AppautoOnboardWizard> | null>(null)

function openOnboard() {
  onboardWizard.value?.openWizard()
}

async function openLeaseDrawer(row: AppautoDevice) {
  leaseDeviceName.value = row.name
  leaseDrawerVisible.value = true
  leaseLoading.value = true
  leaseRuns.value = []
  try {
    const runs = await listDeviceLeaseRuns(row.id)
    leaseRuns.value = [...runs].sort((a, b) => (b.acquiredAt ?? '').localeCompare(a.acquiredAt ?? ''))
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('appauto.wall.loadFailed')))
  } finally {
    leaseLoading.value = false
  }
}

onMounted(() => {
  load()
  thumbPoll.start()
})

// : a header context switch rewrites the project segment — reset in-page
// screening where the removed selector used to and reload from scratch.
watch(followTick, () => {
  labelFilter.value = ''
  load()
})

</script>

<style scoped>
.empty-box {
  padding: 24px 0;
}
.occupancy-eta {
  margin-left: 8px;
  color: var(--tp-text-secondary);
  font-size: 12px;
}
.occupancy-free {
  color: var(--tp-text-secondary);
}
.label-chip {
  margin-right: 4px;
}
.overheat-badge {
  margin-left: 4px;
}
.wall-thumb {
  display: block;
  height: 64px;
  max-width: 100%;
  object-fit: contain;
  border-radius: 4px;
  border: 1px solid var(--el-border-color);
}
.wall-thumb-empty {
  color: var(--tp-text-secondary);
  font-size: 12px;
}
.form-note {
  font-size: 12px;
  color: var(--tp-text-secondary);
  line-height: 1.6;
}
.form-note-inline {
  margin-left: 10px;
  font-size: 12px;
  color: var(--tp-text-secondary);
}
.lease-reason {
  font-size: 12px;
  color: var(--tp-text-secondary);
  line-height: 1.5;
}
.lease-caliber {
  margin-top: 12px;
  font-size: 12px;
  color: var(--tp-text-secondary);
}
</style>
