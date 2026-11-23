
<template>
  <div class="monitor-window" data-test="exec-monitor-window">
    <div class="monitor-head">
      <h4 class="monitor-title">{{ t('exec.monitorTitle') }}</h4>
      <el-tag size="small" type="info" data-test="monitor-mode-badge">{{ t('exec.monitorPollingBadge') }}</el-tag>
      <el-tag v-if="stepTag != null" size="small" data-test="monitor-step-tag">
        {{ t('exec.monitorStepTag', { stepNo: stepTag }) }}
      </el-tag>
      <el-button
        size="small"
        :type="cropMode ? 'warning' : 'default'"
        :disabled="!canCrop"
        data-test="crop-toggle-btn"
        @click="toggleCropMode"
      >{{ cropMode ? t('exec.cropExitBtn') : t('exec.cropBtn') }}</el-button>
      <span v-if="!canCrop && cropUnavailableHintKey" class="crop-hint" data-test="crop-unavailable-hint">{{ t(cropUnavailableHintKey) }}</span>
      <span v-else-if="cropMode" class="crop-hint" data-test="crop-mode-hint">{{ t('exec.cropHint') }}</span>
    </div>

    <el-alert
      v-if="notice"
      type="warning"
      :closable="false"
      show-icon
      class="monitor-notice"
      data-test="monitor-degrade-notice"
      :title="notice === 'dropped' ? t('exec.monitorDropped') : t('exec.monitorTrimmed')"
    />

    <div
      ref="stageEl"
      class="monitor-stage"
      data-test="monitor-stage"
      tabindex="0"
      @mousedown="onStageDown"
      @mousemove="onStageMove"
      @mouseup="onStageUp"
      @keydown="onStageKey"
    >
      <!-- Chromium starts a native image-drag as soon as the pointer moves with
           the button down over an <img>, which swallows mousemove/mouseup: the
           crop rectangle would never settle and the registration card could never
           open. Dragging and text selection are suppressed on the frame itself so
           the crop gesture owns the pointer. -->
      <img v-if="view.latest?.image" ref="imgEl" :src="frameSrc(view.latest.image)" class="monitor-img" data-test="monitor-frame" :alt="t('exec.monitorTitle')" draggable="false" @dragstart.prevent>
      <div v-else class="monitor-waiting" data-test="monitor-waiting">
        <el-icon :size="22"><VideoCamera /></el-icon>
        <span>{{ t('exec.monitorWaiting') }}</span>
      </div>
      <div v-if="selectionStyle" class="crop-selection" data-test="crop-selection" :style="selectionStyle" />
      <div v-if="view.pausedBySensitive" class="monitor-mask" data-test="monitor-sensitive-mask">
        <el-icon :size="20"><Lock /></el-icon>
        <span>{{ t('exec.monitorSensitiveMask') }}</span>
      </div>
    </div>

 
    <div v-if="device" class="takeover-zone" data-test="takeover-zone">
      
      <div v-if="!canTakeover" class="takeover-perm" data-test="takeover-perm-hint">
        {{ t('appauto.takeoverPermHint') }}
      </div>

      <template v-else>
        
        <div v-if="!pendingState && !activeState" class="takeover-idle-row">
          <el-input
            v-model="reasonDraft"
            size="small"
            class="takeover-reason"
            data-test="takeover-reason-input"
            :placeholder="t('appauto.takeoverReasonPlaceholder')"
            maxlength="200"
          />
          <el-button size="small" type="primary" data-test="takeover-request-btn" :loading="busy" @click="doRequest">
            {{ t('appauto.takeoverRequestBtn') }}
          </el-button>
        </div>

        
        <div v-else-if="pendingState" class="takeover-pending-row">
          <span class="takeover-status" data-test="takeover-pending-wait">{{ t('appauto.takeoverPendingWait') }}</span>
          <template v-if="isOccupier">
            <el-button size="small" type="success" data-test="takeover-agree-btn" :loading="busy" @click="doDecide(agreeTakeover)">
              {{ t('appauto.takeoverAgree') }}
            </el-button>
            <el-button size="small" data-test="takeover-reject-btn" :loading="busy" @click="doDecide(rejectTakeover)">
              {{ t('appauto.takeoverReject') }}
            </el-button>
          </template>
          <span v-if="isOccupier" class="takeover-ask">{{ t('appauto.takeoverOccupierAsk') }}</span>
          <el-button v-if="isAdmin" size="small" type="warning" data-test="takeover-force-btn" :loading="busy" @click="doForce">
            {{ t('appauto.takeoverForce') }}
          </el-button>
        </div>

        
        <template v-else-if="activeState">
          <div class="takeover-active-row">
            <span class="takeover-status" data-test="takeover-active-hint">{{ t('appauto.takeoverActiveHint') }}</span>
            <el-button size="small" data-test="takeover-end-btn" :loading="busy" @click="doEnd">
              {{ t('appauto.takeoverEnd') }}
            </el-button>
          </div>
          <div v-if="view.pausedBySensitive" class="takeover-paused" data-test="takeover-sensitive-paused">
            {{ t('appauto.takeoverSensitivePaused') }}
          </div>
          <div v-if="gates.grayReasonKey" class="takeover-gray" data-test="takeover-gray-reason">
            {{ t(gates.grayReasonKey) }}
          </div>
          <div class="takeover-ops" :class="{ disabled: !gates.verbs.length }">
            <span class="takeover-stage-hint">{{ t('appauto.takeoverStageHint') }}</span>
            <el-button size="small" data-test="takeover-key-back-btn" :disabled="!gates.verbs.includes('key')" @click="sendKey('Back')">
              {{ t('appauto.takeoverKeyBack') }}
            </el-button>
            <el-button size="small" data-test="takeover-key-home-btn" :disabled="!gates.verbs.includes('key')" @click="sendKey('Home')">
              {{ t('appauto.takeoverKeyHome') }}
            </el-button>
            <el-input
              v-model="textDraft"
              size="small"
              class="takeover-text"
              data-test="takeover-text-input"
              :placeholder="t('appauto.takeoverTextPlaceholder')"
            />
            <el-button size="small" type="primary" data-test="takeover-text-send" :disabled="!gates.verbs.includes('text')" @click="sendText">
              {{ t('appauto.takeoverSend') }}
            </el-button>
          </div>
        </template>
      </template>
    </div>

    
    <el-dialog
      v-model="captureDialogVisible"
      :title="t('exec.cropDialogTitle')"
      width="420px"
      data-test="crop-dialog"
      @closed="resetCaptureDraft"
    >
      <div class="capture-field">
        <span class="capture-label">{{ t('exec.cropRegionLabel') }}</span>
        <span class="capture-region" data-test="crop-region-echo">
          {{ cropRegion ? `${cropRegion.x}, ${cropRegion.y} · ${cropRegion.width}×${cropRegion.height}` : '-' }}
        </span>
      </div>
      <div class="capture-field">
        <label class="capture-label" for="crop-name-input">{{ t('exec.cropNameLabel') }}</label>
        <el-input
          id="crop-name-input"
          v-model="captureNameDraft"
          size="small"
          maxlength="100"
          data-test="crop-name-input"
          :placeholder="t('exec.cropNamePlaceholder')"
        />
      </div>
      <div class="capture-field">
        <span class="capture-label">{{ t('exec.cropScaleLabel') }}</span>
        <el-checkbox-group v-model="captureScalesDraft" data-test="crop-scale-group">
          <el-checkbox v-for="s in CROP_SCALE_PRESETS" :key="s" :value="s">{{ s }}×</el-checkbox>
        </el-checkbox-group>
        <span class="capture-sub">{{ t('exec.cropScaleHint') }}</span>
      </div>
      <div class="capture-field">
        <label class="capture-label" for="crop-confidence-input">{{ t('exec.cropConfidenceLabel') }}</label>
        <el-input-number
          id="crop-confidence-input"
          v-model="captureConfidenceDraft"
          size="small"
          :min="0"
          :max="1"
          :step="0.05"
          :precision="2"
          data-test="crop-confidence-input"
        />
        <span class="capture-sub">{{ t('exec.cropConfidenceHint') }}</span>
      </div>
      <template #footer>
        <el-button size="small" data-test="crop-cancel-btn" @click="captureDialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button
          size="small"
          type="primary"
          data-test="crop-save-btn"
          :loading="captureBusy"
          :disabled="captureNameDraft.trim() === ''"
          @click="doCapture"
        >{{ t('exec.cropSaveBtn') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">

import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { Lock, VideoCamera } from '@element-plus/icons-vue'
import { getTaskMonitorFrames } from '@/api/executor'
import {
  agreeTakeover,
  captureElementFromFrame,
  endTakeover,
  enqueueTakeoverInput,
  forceTakeover,
  getTakeover,
  rejectTakeover,
  requestTakeover,
  type AppautoDevice,
  type AppautoTakeover,
} from '@/api/appauto'
import { CROP_SCALE_PRESETS, toFrameRegion, type DragPoint, type FrameRegion } from '@/utils/frameCropCapture'
import { useProjectContextStore } from '@/stores/projectContext'
import { applyMonitorFrames, degradeNotice, summarizeMonitorFrames } from '@/utils/monitorFrames'
import type { MonitorViewState } from '@/utils/monitorFrames'
import {
  buildKeyCommand,
  buildSwipeCommand,
  buildTapCommand,
  buildTextCommand,
  takeoverPlatformGates,
} from '@/utils/takeoverInput'
import { useAuthStore } from '@/stores/auth'
import { ElMessage } from 'element-plus'

const props = defineProps<{
  taskId: number
  active: boolean
 
  device?: AppautoDevice | null
 
  takeover?: AppautoTakeover | null
}>()
const emit = defineEmits<{ (e: 'step', stepNo: number | null): void }>()
const { t } = useI18n()
const auth = useAuthStore()

const view = ref<MonitorViewState>({ ...applyMonitorFrames(null, []) })
const notice = ref<'dropped' | 'trimmed' | null>(null)
const stepTag = computed(() => view.value.latestStepNo)

const TAKEOVER_KEY = 'appauto:watch:takeover'

const SWIPE_MIN_PX = 12

const canTakeover = computed(() => auth.hasPerm(TAKEOVER_KEY))
const isAdmin = computed(() => auth.isAdmin)
const localTk = ref<AppautoTakeover | null>(props.takeover ?? null)
const reasonDraft = ref('')
const textDraft = ref('')
const busy = ref(false)

watch(() => props.takeover, (tk) => { localTk.value = tk ?? null })

const pendingState = computed(() => localTk.value?.state === 'pending')
const activeState = computed(() => localTk.value?.state === 'active' || localTk.value?.state === 'forced')
const isOccupier = computed(() =>
  localTk.value != null && localTk.value.occupierId === auth.user?.userId)
const gates = computed(() => takeoverPlatformGates(props.device?.platform))
const devCtx = computed(() => (props.device ? { platform: props.device.platform, serial: props.device.serial } : null))


const imgEl = ref<HTMLImageElement | null>(null)

function stageRect() {
  const el = imgEl.value
  if (el == null) return null
  const r = el.getBoundingClientRect()
  return { left: r.left, top: r.top, width: r.width, height: r.height }
}

async function enqueue(cmd: ReturnType<typeof buildTapCommand>): Promise<void> {
  const tk = localTk.value
  if (cmd == null || tk == null) return
  if (view.value.pausedBySensitive) {
    ElMessage.info(t('appauto.takeoverSensitivePaused'))
    return
  }
  try {
    await enqueueTakeoverInput(tk.id, [cmd])
  } catch {
  }
}

const ctxStore = useProjectContextStore()

const captureProjectId = computed(() => props.device?.projectId ?? ctxStore.primaryId)
const canCrop = computed(() => view.value.latest?.image != null && !view.value.pausedBySensitive)

const cropUnavailableHintKey = computed<string | null>(() => {
  if (view.value.latest?.image == null) return 'exec.cropNoFrame'
  if (view.value.pausedBySensitive) return 'exec.cropMasked'
  if (captureProjectId.value == null) return 'exec.cropNeedProject'
  return null
})

const cropMode = ref(false)
const stageEl = ref<HTMLElement | null>(null)
const cropDrag = ref<{ from: DragPoint; to: DragPoint } | null>(null)
const cropRegion = ref<FrameRegion | null>(null)
const captureDialogVisible = ref(false)
const captureNameDraft = ref('')
const captureScalesDraft = ref<number[]>([])
const captureConfidenceDraft = ref<number | null>(null)
const captureBusy = ref(false)

function toggleCropMode(): void {
  cropMode.value = !cropMode.value
  cropDrag.value = null
}


const selectionStyle = computed<Record<string, string> | null>(() => {
  const drag = cropDrag.value
  const stage = stageEl.value
  if (drag == null || stage == null) return null
  const sr = stage.getBoundingClientRect()
  const x = Math.min(drag.from.clientX, drag.to.clientX) - sr.left
  const y = Math.min(drag.from.clientY, drag.to.clientY) - sr.top
  return {
    left: `${Math.max(0, x)}px`,
    top: `${Math.max(0, y)}px`,
    width: `${Math.abs(drag.to.clientX - drag.from.clientX)}px`,
    height: `${Math.abs(drag.to.clientY - drag.from.clientY)}px`,
  }
})

function resetCaptureDraft(): void {
  cropRegion.value = null
  cropDrag.value = null
  captureNameDraft.value = ''
  captureScalesDraft.value = []
  captureConfidenceDraft.value = null
}


function finishCrop(e: MouseEvent): void {
  const drag = cropDrag.value
  cropDrag.value = null
  const img = imgEl.value
  if (drag == null || img == null) return
  const region = toFrameRegion(drag.from, { clientX: e.clientX, clientY: e.clientY },
    img.getBoundingClientRect(), img.naturalWidth, img.naturalHeight)
  if (region == null) {
    ElMessage.info(t('exec.cropTooSmall'))
    return
  }
  cropRegion.value = region
  cropMode.value = false
  captureDialogVisible.value = true
}

async function doCapture(): Promise<void> {
  const region = cropRegion.value
  const projectId = captureProjectId.value
  if (region == null || projectId == null) return
  captureBusy.value = true
  try {
    await captureElementFromFrame(projectId, {
      taskId: props.taskId,
      frameIndex: null,
      name: captureNameDraft.value.trim(),
      ...region,
      scales: captureScalesDraft.value.length ? captureScalesDraft.value : null,
      confidenceThreshold: captureConfidenceDraft.value,
    })
    captureDialogVisible.value = false
    ElMessage.success(t('exec.cropSaved', { name: captureNameDraft.value.trim() }))
  } catch {
  } finally {
    captureBusy.value = false
  }
}

let downPoint: { clientX: number; clientY: number } | null = null

function onStageDown(e: MouseEvent): void {
  if (e.button !== 0) return
  if (cropMode.value) {
    cropDrag.value = { from: { clientX: e.clientX, clientY: e.clientY }, to: { clientX: e.clientX, clientY: e.clientY } }
    return
  }
  if (!activeState.value) return
  downPoint = { clientX: e.clientX, clientY: e.clientY }
}

function onStageMove(e: MouseEvent): void {
  if (cropDrag.value != null) {
    cropDrag.value = { ...cropDrag.value, to: { clientX: e.clientX, clientY: e.clientY } }
  }
}

async function onStageUp(e: MouseEvent): Promise<void> {
  if (cropDrag.value != null) {
    finishCrop(e)
    return
  }
  if (!activeState.value || downPoint == null || !devCtx.value) {
    downPoint = null
    return
  }
  const rect = stageRect()
  const dev = devCtx.value
  const start = downPoint
  downPoint = null
  if (rect == null) return
  const dist = Math.hypot(e.clientX - start.clientX, e.clientY - start.clientY)
  const cmd = dist >= SWIPE_MIN_PX
    ? buildSwipeCommand(dev, rect, start, { clientX: e.clientX, clientY: e.clientY })
    : buildTapCommand(dev, rect, { clientX: e.clientX, clientY: e.clientY })
  await enqueue(cmd)
}

async function onStageKey(e: KeyboardEvent): Promise<void> {
  if (!activeState.value || !devCtx.value) return
  const target = e.target as HTMLElement | null
  if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA')) return
  await enqueue(buildKeyCommand(devCtx.value, e.key))
}

async function sendKey(key: string): Promise<void> {
  if (!devCtx.value) return
  await enqueue(buildKeyCommand(devCtx.value, key))
}

async function sendText(): Promise<void> {
  if (!devCtx.value) return
  await enqueue(buildTextCommand(devCtx.value, textDraft.value))
}

async function doRequest(): Promise<void> {
  const dev = props.device
  if (dev == null) return
  busy.value = true
  try {
    localTk.value = await requestTakeover({
      deviceId: dev.id, taskId: props.taskId, reason: reasonDraft.value || null,
    })
  } catch {
  } finally {
    busy.value = false
  }
}

async function doDecide(fn: (id: number) => Promise<AppautoTakeover>): Promise<void> {
  const tk = localTk.value
  if (tk == null) return
  busy.value = true
  try {
    localTk.value = await fn(tk.id)
  } catch {  } finally {
    busy.value = false
  }
}

async function doForce(): Promise<void> {
  const tk = localTk.value
  if (tk == null) return
  busy.value = true
  try {
    localTk.value = await forceTakeover(tk.id)
  } catch {  } finally {
    busy.value = false
  }
}

async function doEnd(): Promise<void> {
  const tk = localTk.value
  if (tk == null) return
  busy.value = true
  try {
    localTk.value = await endTakeover(tk.id)
  } catch {  } finally {
    busy.value = false
  }
}


function frameSrc(image: string): string {
  return `data:image/jpeg;base64,${image}`
}

async function pollOnce(): Promise<void> {
  if (!props.taskId) return
  try {
    const summary = summarizeMonitorFrames(await getTaskMonitorFrames(props.taskId))
    view.value = applyMonitorFrames(view.value, summary.frames)
    notice.value = degradeNotice(summary.dropped, summary.trimmed)
    emit('step', view.value.latestStepNo)
  } catch {
  }
  if (localTk.value != null && (pendingState.value || activeState.value)) {
    try {
      localTk.value = await getTakeover(localTk.value.id)
    } catch {
    }
  }
}

let timer: ReturnType<typeof setInterval> | null = null

function restartTimer(): void {
  if (timer) clearInterval(timer)
  timer = null
  if (props.active) {
    void pollOnce()
    timer = setInterval(() => void pollOnce(), 2000)
  }
}

watch(() => [props.taskId, props.active] as const, restartTimer)
onMounted(restartTimer)
onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
  timer = null
})
</script>

<style scoped>
.monitor-window {
  border: 1px solid var(--tp-border);
  border-radius: 8px;
  padding: 10px 12px;
  margin-bottom: 16px;
}
.monitor-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.monitor-title {
  margin: 0;
  font-size: 14px;
}
.monitor-notice {
  margin-top: 8px;
}
.monitor-stage {
  position: relative;
  margin-top: 8px;
  user-select: none;
  display: flex;
  justify-content: center;
  background: #1c1f26;
  border-radius: 8px;
  min-height: 120px;
  overflow: hidden;
}
.monitor-img {
  max-width: 100%;
  max-height: 360px;
  object-fit: contain;
}
.monitor-waiting {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  color: var(--tp-text-secondary);
  font-size: 13px;
  padding: 32px 0;
}
.monitor-mask {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  background: rgba(12, 14, 18, 0.92);
  color: var(--tp-text-secondary);
  font-size: 13px;
}
.takeover-zone {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed var(--tp-border);
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 12px;
}
.takeover-perm {
  color: var(--tp-warning);
  font-size: 12px;
}
.takeover-idle-row,
.takeover-pending-row,
.takeover-active-row,
.takeover-ops {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.takeover-reason {
  max-width: 320px;
}
.takeover-text {
  max-width: 260px;
}
.takeover-status {
  color: var(--tp-text-secondary);
  line-height: 1.7;
}
.takeover-ask {
  color: var(--tp-text-secondary);
}
.takeover-paused {
  color: var(--tp-warning);
}
.takeover-gray {
  color: var(--tp-warning);
  line-height: 1.7;
}
.takeover-stage-hint {
  color: var(--tp-text-secondary);
}
.takeover-ops.disabled {
  opacity: 0.6;
}
.crop-hint {
  color: var(--tp-text-secondary);
  font-size: 12px;
}
.crop-selection {
  position: absolute;
  border: 1px dashed var(--tp-primary, #4f8bff);
  background: rgba(79, 139, 255, 0.18);
  pointer-events: none;
}
.capture-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 10px;
}
.capture-label {
  font-size: 12px;
  color: var(--tp-text-secondary);
}
.capture-region {
  font-size: 13px;
}
.capture-sub {
  font-size: 12px;
  color: var(--tp-text-secondary);
}
</style>
