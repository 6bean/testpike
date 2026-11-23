<template>
  <div class="exec-task-result" data-test="exec-task-result">
    <template v-if="task">
      
      <div class="result-header">
        <el-tag :type="EXEC_TASK_STATUS[task.status]?.type ?? 'info'" size="small" data-test="exec-task-status">
          {{ EXEC_TASK_STATUS[task.status]?.label ?? task.status }}
        </el-tag>
        <span class="mono">{{ t('aiGenerate.taskNo', { id: task.id }) }}</span>
        <el-tag v-if="task.schemaVersion" size="small" type="info">schema {{ task.schemaVersion }}</el-tag>
        <span v-if="task.durationMs != null" class="duration">
          {{ t('exec.durationMs', { ms: task.durationMs }) }}
        </span>
      </div>
      <div class="result-times">
        <span>{{ t('exec.createdAt') }}：{{ fmt(task.createdAt) }}</span>
        <span v-if="task.startedAt">{{ t('exec.stepStart') }}：{{ fmt(task.startedAt) }}</span>
        <span v-if="task.finishedAt">{{ t('exec.stepEnd') }}：{{ fmt(task.finishedAt) }}</span>
      </div>

      
      <div v-if="task.status === 4 || task.status === 5" class="error-block" data-test="exec-error-block">
        <div class="error-title">
          <el-icon color="var(--tp-danger)"><WarningFilled /></el-icon>
          <span>{{ t('exec.errorDetail') }}</span>
          <el-tag v-if="task.errorType" size="small" type="danger">
            {{ t(`exec.errorType${task.errorType}`) }}
          </el-tag>
        </div>
        <div class="error-detail">{{ task.errorDetail || t('exec.noError') }}</div>
        <div class="error-scene-row">
          <el-button link type="primary" size="small" data-test="exec-error-scene-link" @click="revealErrorScene">
            {{ t('exec.errorSceneView') }}
          </el-button>
        </div>
        <div v-if="task.aiFailureClass" class="ai-class-line" data-test="exec-ai-class">
          <el-tag :type="AI_FAILURE_CLASS[task.aiFailureClass]?.type ?? 'info'" size="small">
            {{ AI_FAILURE_CLASS[task.aiFailureClass]?.label ?? task.aiFailureClass }}
          </el-tag>
          <span v-if="task.aiFailureNote" class="ai-class-note">{{ task.aiFailureNote }}</span>
        </div>
      </div>

 
      <ExecMonitorWindow
        :task-id="task.id"
        :active="task.status === 1 || task.status === 2"
        :device="takeoverDevice"
        @step="onMonitorStep"
      />

 
      <ExecQueueHonestyPanel :task="task" />

 
      <div v-if="timeline.length" class="timeline-block" data-test="exec-timeline">
        <h4 class="block-title">{{ t('exec.stepsTimeline') }}</h4>
        
        <div v-if="healSummary" class="heal-summary" data-test="exec-heal-summary">
          {{ t('exec.healSummary', {
            rate: formatHealRate(healSummary.healRate),
            healed: healSummary.healedStepCount,
            total: healSummary.ledgerStepTotal,
            mismatch: healSummary.assertionMismatchCount,
          }) }}
        </div>
        <DataTablePage :data="timeline" size="small" border :row-class-name="timelineRowClass">
          <el-table-column :label="t('exec.stepNo')" prop="stepNo" width="70" />
          <el-table-column :label="t('exec.stepAction')" prop="action" min-width="110" />
          <el-table-column :label="t('exec.stepStart')" width="175">
            <template #default="{ row }">{{ fmt(row.startAt) }}</template>
          </el-table-column>
          <el-table-column :label="t('exec.stepEnd')" width="175">
            <template #default="{ row }">{{ fmt(row.endAt) }}</template>
          </el-table-column>
          <el-table-column :label="t('exec.stepStatus')" width="90">
            <template #default="{ row }">
              <el-tag :type="row.status === 'ok' ? 'success' : 'danger'" size="small">
                {{ row.status === 'ok' ? t('exec.stepOk') : t('exec.stepFail') }}
              </el-tag>
            </template>
          </el-table-column>
 
          <el-table-column :label="t('exec.stepAiLocate')" width="120">
            <template #default="{ row, $index }">
              <el-tooltip
                v-if="row.aiLocate"
                :content="t('exec.stepAiLocateTip', { score: fmtScore(row.aiLocate.score) })"
                placement="top"
              >
                <el-tag
                  :type="row.aiLocate.tier === 'direct' ? 'success' : 'warning'"
                  :effect="row.aiLocate.tier === 'direct' ? 'dark' : 'plain'"
                  size="small"
                  :data-test="`step-ai-badge-${$index}`"
                >
                  {{ row.aiLocate.tier === 'direct'
                    ? t('exec.stepAiLocateDirect')
                    : row.aiLocate.tier === 'selfcheck'
                      ? t('exec.stepAiLocateSelfcheck')
                      : row.aiLocate.tier }}
                </el-tag>
              </el-tooltip>
              <span v-else class="shot-na">—</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('exec.stepHealBadge')" width="100">
            <template #default="{ row }">
              <el-button
                v-if="healRowsOf(row.stepNo).length"
                link
                type="warning"
                size="small"
                :data-test="`step-heal-badge-${row.stepNo}`"
                @click="toggleHealExpand(row.stepNo)"
              >
                {{ t('exec.stepHealBadge') }}
              </el-button>
              <span v-else class="shot-na">—</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('exec.stepDetail')" prop="detail" min-width="160" show-overflow-tooltip />
          <el-table-column :label="t('exec.stepShot')" width="80">
            <template #default="{ row, $index }">
              <el-button
                v-if="findStepScreenshot(shots, row)"
                link
                type="primary"
                size="small"
                :data-test="`step-shot-link-${$index}`"
                @click="openStepShot($index)"
              >
                {{ t('exec.stepShotView') }}
              </el-button>
              <span v-else class="shot-na">—</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('exec.stepLog')" width="80">
            <template #default="{ row, $index }">
              
              <el-button
                v-if="row.status !== 'ok'"
                link
                type="primary"
                size="small"
                :data-test="`step-log-link-${$index}`"
                @click="scrollToLog"
              >
                {{ t('exec.stepLogView') }}
              </el-button>
              <span v-else class="shot-na">—</span>
            </template>
          </el-table-column>
        </DataTablePage>
        
        <div v-if="expandedHealItems.length" class="heal-diff-block" data-test="exec-heal-diff">
          <div
            v-for="item in expandedHealItems"
            :key="item.stepNo"
            class="heal-diff-row"
            :data-test="`heal-diff-step-${item.stepNo}`"
          >
            <div
              v-for="row in item.rows"
              :key="row.id"
              class="heal-diff-detail"
            >
              <span class="heal-anchor">{{ healAnchorDiffText(row) }}</span>
              <span>{{ t('exec.healSimilarity', { score: fmtScore(row.similarityScore) }) }}</span>
              <span>{{ t('exec.healViewport', { viewport: row.viewport || '-' }) }}</span>
              <span>{{ t('exec.healRevision', { revision: row.ledgerRevision ?? '-' }) }}</span>
              <span>{{ t('exec.healElement', { element: row.elementId ?? '-' }) }}</span>
              <span>{{ fmt(row.createdAt) }}</span>
            </div>
          </div>
          <div v-if="healOrphanRows.length" class="heal-diff-detail" data-test="heal-diff-orphan">
            {{ t('exec.healOrphanNote', { count: healOrphanRows.length }) }}
          </div>
        </div>
      </div>

 
      <ExecStepEvidencePanel ref="evidencePanel" :task="task" />

 
      <div v-if="locateEvidenceRows.length" class="evidence-block" data-test="exec-locate-evidence">
        <h4 class="block-title">{{ t('exec.locateEvidenceTitle') }}</h4>
        <div
          v-for="(row, ri) in locateEvidenceRows"
          :key="`locate-ev-${ri}`"
          class="evidence-section"
          :data-test="`locate-evidence-step-${row.stepNo}`"
        >
          <h5 class="section-title">
            {{ t('exec.locateEvidenceStep', { stepNo: row.stepNo, action: row.action || '-' }) }}
            
            <el-tag type="warning" size="small">
              {{ t('exec.locateEvidenceScore', { score: fmtScore(row.locateEvidence?.score) }) }}
            </el-tag>
          </h5>
          <el-alert
            type="warning"
            :closable="false"
            show-icon
            :title="t('exec.locateEvidenceHint')"
            data-test="locate-evidence-hint"
          />
          <DataTablePage :data="candidatesOf(row)" size="small" border class="locate-cand-table">
            <el-table-column :label="t('exec.locateEvidenceSource')" width="110">
              <template #default="{ row: c }">
                <el-tag size="small" :type="c.source === 'vision' ? 'primary' : 'info'">
                  {{ LOCATE_SOURCE_LABEL[c.source as string] ? t(LOCATE_SOURCE_LABEL[c.source as string]) : (c.source ?? '-') }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column :label="t('exec.locateEvidenceLabel')" min-width="160">
              <template #default="{ row: c }">{{ c.label ?? '-' }}</template>
            </el-table-column>
            <el-table-column :label="t('exec.locateEvidencePoint')" width="120">
              <template #default="{ row: c }">{{ pointText(c) }}</template>
            </el-table-column>
            <el-table-column :label="t('exec.locateEvidenceConfidence')" width="100">
              <template #default="{ row: c }">{{ c.confidence == null ? '-' : fmtScore(c.confidence) }}</template>
            </el-table-column>
          </DataTablePage>
        </div>
      </div>

 
      <div v-if="hasEvidence" class="evidence-block" data-test="exec-evidence">
        <h4 class="block-title">{{ t('exec.evidenceTitle') }}</h4>

        <div v-if="evidence.consoleViolations.length" class="evidence-section" data-test="evidence-console">
          <h5 class="section-title">{{ t('exec.evidenceConsole') }}</h5>
          <DataTablePage :data="evidence.consoleViolations" size="small" border>
            <el-table-column :label="t('exec.stepNo')" prop="stepNo" width="70" />
            <el-table-column :label="t('exec.evidenceLevel')" width="90">
              <template #default="{ row }">
                <el-tag :type="row.level === 'error' ? 'danger' : 'warning'" size="small">
                  {{ row.level }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column :label="t('exec.evidenceText')" prop="text" min-width="280" />
          </DataTablePage>
        </div>

        <div v-if="evidence.netHits.length" class="evidence-section" data-test="evidence-net">
          <h5 class="section-title">{{ t('exec.evidenceNet') }}</h5>
          <DataTablePage :data="evidence.netHits" size="small" border>
            <el-table-column :label="t('exec.evidenceUrl')" prop="url" min-width="240" />
            <el-table-column :label="t('exec.evidenceStatus')" width="90">
              <template #default="{ row }">
                <el-tag :type="row.status >= 400 ? 'danger' : 'success'" size="small">
                  {{ row.status }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column :label="t('exec.evidenceDuration')" width="110">
              <template #default="{ row }">
                {{ row.durationMs == null ? '-' : t('exec.durationMs', { ms: row.durationMs }) }}
              </template>
            </el-table-column>
          </DataTablePage>
        </div>

        <div v-if="evidence.dbDigest.length" class="evidence-section" data-test="evidence-db">
          <h5 class="section-title">{{ t('exec.evidenceDb') }}</h5>
          <ul class="db-digest mono">
            <li v-for="(line, i) in evidence.dbDigest" :key="i">{{ line }}</li>
          </ul>
        </div>
      </div>

 
      <div v-if="snapshots.length" class="snapshot-block" data-test="exec-snapshots">
 
        <el-alert
          v-if="hasInsecureSnapshot"
          type="warning"
          :closable="false"
          show-icon
          class="snap-alert"
          data-test="snapshot-insecure-hint"
          :title="t('exec.snapshotInsecureHint')"
        />
        <h4 class="block-title">{{ t('exec.snapshotTitle') }}</h4>
        <el-tabs type="border-card" data-test="snapshot-step-tabs">
          <el-tab-pane
            v-for="snap in snapshots"
            :key="snap.stepNo"
            :label="t('exec.snapshotStepTab', { stepNo: snap.stepNo, kind: snap.kind })"
            :data-test="`snapshot-tab-${snap.stepNo}`"
          >
            <el-alert
              v-if="snap.truncated"
              type="warning"
              :closable="false"
              show-icon
              class="snap-alert"
              data-test="snapshot-truncated-hint"
              :title="t('exec.snapshotTruncatedHint', { size: fmtBytes(snap.rawBytes) })"
            />
            <div class="snap-cols">
              <div v-for="col in snapshotColumns(snap)" :key="col.label" class="snap-col" :data-test="`snapshot-col-${col.key}`">
                <div class="snap-col-head">
                  <span class="snap-col-title">{{ col.label }}</span>
                  <el-button
                    v-if="col.text"
                    link
                    type="primary"
                    size="small"
                    :data-test="`snapshot-copy-${col.key}-${snap.stepNo}`"
                    @click="copySnapshot(col.text)"
                  >
                    {{ t('exec.snapshotCopy') }}
                  </el-button>
                </div>
                <pre v-if="col.text" class="snap-body mono">{{ prettyJson(col.text) }}</pre>
                <div v-else class="snap-na">{{ t('exec.snapshotEmptyCol') }}</div>
              </div>
            </div>
          </el-tab-pane>
        </el-tabs>
      </div>

      
      <div class="screenshot-block" data-test="exec-screenshots">
        <h4 class="block-title">{{ t('exec.screenshots') }}</h4>
        <template v-if="shotsLoading">
          <el-skeleton :rows="2" animated />
        </template>
        <div v-else-if="shots.length" class="shot-list">
          <div v-for="(s, i) in shots" :key="s.objectKey" class="shot-item">
            <el-image
              :src="s.url"
              :preview-src-list="shots.map((x) => x.url)"
              :initial-index="i"
              fit="contain"
              class="shot-img"
              :alt="t('exec.screenshotNo', { n: i + 1 })"
            />
            <div class="shot-name">{{ s.objectKey.split('/').pop() }}</div>
          </div>
        </div>
        <div v-else class="shots-empty">
          <el-icon :size="16"><Picture /></el-icon>
          <span>{{ t('exec.screenshotsEmpty') }}</span>
        </div>
      </div>

      
      <div ref="logBlock" class="log-block" data-test="exec-console-log">
        <h4 class="block-title">{{ t('exec.consoleLog') }}</h4>
        <div v-if="consoleLog" class="console-log mono">{{ consoleLog }}</div>
        <div v-else class="shots-empty">
          <el-icon :size="16"><Document /></el-icon>
          <span>{{ t('exec.consoleLogEmpty') }}</span>
        </div>
      </div>

      
      <el-image-viewer
        v-if="viewerUrl"
        :url-list="[viewerUrl]"
        hide-on-click-modal
        teleported
        @close="viewerUrl = null"
      />
    </template>
    <el-skeleton v-else :rows="4" animated />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { Document, Picture, WarningFilled } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import {
  AI_FAILURE_CLASS, EXEC_TASK_STATUS, findStepScreenshot, getExecTaskLog, getExecTaskScreenshots,
  getExecTaskStepSnapshots,
  parseResultEvidence, parseScreenshots, parseStepsTimeline,
  type ExecStepSnapshot, type ExecTask, type ScreenshotView, type StepTimelineItem,
} from '@/api/executor'
import ExecMonitorWindow from '@/components/ExecMonitorWindow.vue'
import ExecQueueHonestyPanel from '@/components/ExecQueueHonestyPanel.vue'
import ExecStepEvidencePanel from '@/components/ExecStepEvidencePanel.vue'
import { webElementHealEvidenceByTask, type StepHealEvidenceRow } from '@/api/webElements'
import {
  buildHealBadgeItems, buildHealSummary, formatHealRate, healAnchorDiffText,
} from '@/utils/webElementHealReport'
import { flashRevealElement } from '@/composables/sourceAnchorJump'
import { loadOccupancySnapshot } from '@/composables/useDeviceOccupancy'
import type { AppautoDevice } from '@/api/appauto'

const props = defineProps<{ task: ExecTask | null }>()
const { t } = useI18n()

const takeoverDevice = ref<AppautoDevice | null>(null)
watch(() => [props.task?.id, props.task?.executorId] as const, async ([taskId, execId]) => {
  takeoverDevice.value = null
  const task = props.task
  if (taskId == null || execId == null || task?.schemaVersion !== 'appauto-v1') return
  if (task.status !== 1 && task.status !== 2) return
  const snap = await loadOccupancySnapshot(task.projectId)
  if (snap.loadFailed) return
  const lease = snap.activeLeases.find((l) => String(l.holderExecutorId ?? '') === String(execId))
  takeoverDevice.value = lease ? snap.devices.find((d) => d.id === lease.deviceId) ?? null : null
}, { immediate: true })

const monitorStep = ref<number | null>(null)
function onMonitorStep(stepNo: number | null): void {
  monitorStep.value = stepNo
}
function timelineRowClass({ row }: { row: { stepNo?: number } }): string {
  return monitorStep.value != null && row.stepNo === monitorStep.value ? 'monitor-current-row' : ''
}

const timeline = computed(() => parseStepsTimeline(props.task))

const healRows = ref<StepHealEvidenceRow[]>([])
const healExpanded = ref<Set<number>>(new Set())
const healBadges = computed(() => buildHealBadgeItems(timeline.value, healRows.value))
const healSummary = computed(() =>
  timeline.value.length ? buildHealSummary(timeline.value, healRows.value) : null)
const healOrphanRows = computed(() => healBadges.value.orphanRows)
const expandedHealItems = computed(() =>
  healBadges.value.items.filter((item) => healExpanded.value.has(item.stepNo)))
function healRowsOf(stepNo: number | null | undefined): StepHealEvidenceRow[] {
  return healBadges.value.items.find((item) => item.stepNo === stepNo)?.rows ?? []
}
function toggleHealExpand(stepNo: number | null | undefined): void {
  if (stepNo == null) return
  const next = new Set(healExpanded.value)
  if (next.has(stepNo)) next.delete(stepNo)
  else next.add(stepNo)
  healExpanded.value = next
}
async function loadHealEvidence(): Promise<void> {
  healRows.value = []
  healExpanded.value = new Set()
  if (!props.task) return
  try {
    healRows.value = (await webElementHealEvidenceByTask(props.task.id)).items
  } catch {
    healRows.value = []
  }
}
const locateEvidenceRows = computed(() => timeline.value.filter((s) => s.locateEvidence != null))

const LOCATE_SOURCE_LABEL: Record<string, string> = {
  vision: 'exec.locateEvidenceSourceVision',
  a11y: 'exec.locateEvidenceSourceA11y',
  cache: 'exec.locateEvidenceSourceCache',
}
function candidatesOf(row: StepTimelineItem): Array<Record<string, unknown>> {
  return row.locateEvidence?.candidates ?? []
}
function fmtScore(v: number | null | undefined): string {
  return v == null ? '-' : String(Math.round(v * 100) / 100)
}

function pointText(c: Record<string, unknown>): string {
  const x = c.x
  const y = c.y
  return typeof x === 'number' && typeof y === 'number' ? `(${x}, ${y})` : '-'
}
const evidence = computed(() => parseResultEvidence(props.task))
const hasEvidence = computed(() =>
  evidence.value.consoleViolations.length + evidence.value.netHits.length + evidence.value.dbDigest.length > 0)
const shots = ref<ScreenshotView[]>([])
const shotsLoading = ref(false)
const consoleLog = ref<string | null>(null)

const viewerUrl = ref<string | null>(null)
const logBlock = ref<HTMLElement | null>(null)
const evidencePanel = ref<InstanceType<typeof ExecStepEvidencePanel> | null>(null)


function openStepShot(index: number): void {
  const shot = findStepScreenshot(shots.value, timeline.value[index])
  if (!shot) {
    ElMessage.info(t('exec.stepShotNone'))
    return
  }
  viewerUrl.value = shot.url
}


function scrollToLog(): void {
  flashRevealElement(logBlock.value, 'log-flash')
}

const firstFailedStepNo = computed(() =>
  timeline.value.find((s) => typeof s.status === 'string' && s.status !== 'ok')?.stepNo ?? null)

/** Execution anchor: the failure conclusion (this block) jumps in-page to
 *  the failed step inside the evidence panel through the panel's single
 *  reveal door; no scene on record answers a readable message, never a
 *  silent no-op. */
function revealErrorScene(): void {
  const hit = firstFailedStepNo.value != null
    && evidencePanel.value?.revealStep(firstFailedStepNo.value) === true
  if (!hit) ElMessage.info(t('exec.errorSceneMissing'))
}

function fmt(v?: string | null): string {
  if (!v) return '-'
  return v.replace('T', ' ').slice(0, 19)
}

async function loadShots(): Promise<void> {
  if (!props.task || !parseScreenshots(props.task).length) return
  shotsLoading.value = true
  try {
    shots.value = await getExecTaskScreenshots(props.task.id)
  } catch {
    shots.value = []
  } finally {
    shotsLoading.value = false
  }
}

async function loadLog(): Promise<void> {
  const task = props.task
  if (!task) return
  if (task.log) {
    consoleLog.value = task.log
    return
  }
  try {
    const data = await getExecTaskLog(task.id)
    consoleLog.value = data.log || null
  } catch {
    consoleLog.value = null
  }
}

const snapshots = ref<ExecStepSnapshot[]>([])


const hasInsecureSnapshot = computed(() => snapshots.value.some((s) => s.tlsMode === 'insecure'))

async function loadSnapshots(): Promise<void> {
  snapshots.value = []
  if (!props.task) return
  try {
    snapshots.value = await getExecTaskStepSnapshots(props.task.id)
  } catch {
    snapshots.value = []
  }
}


function snapshotColumns(snap: ExecStepSnapshot): { key: string; label: string; text: string | null }[] {
  return [
    { key: 'request', label: t('exec.snapshotColRequest'), text: snap.request },
    { key: 'response', label: t('exec.snapshotColResponse'), text: snap.response },
    { key: 'assertions', label: t('exec.snapshotColAssertions'), text: snap.assertions },
  ]
}


function prettyJson(text: string): string {
  try {
    return JSON.stringify(JSON.parse(text), null, 2)
  } catch {
    return text
  }
}


function fmtBytes(bytes: number): string {
  if (bytes >= 1048576) return `${(bytes / 1048576).toFixed(1)} MB`
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${bytes} B`
}


async function copySnapshot(text: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(t('exec.snapshotCopied'))
  } catch {
    ElMessage.warning(t('exec.snapshotCopyFail'))
  }
}

watch(
  () => props.task,
  () => {
    shots.value = []
    consoleLog.value = null
    monitorStep.value = null
    void loadShots()
    void loadLog()
    void loadSnapshots()
    void loadHealEvidence()
  },
)

onMounted(() => {
  void loadShots()
  void loadLog()
  void loadSnapshots()
  void loadHealEvidence()
})
</script>

<style scoped lang="scss">
.exec-task-result {
  .result-header {
    display: flex;
    align-items: center;
    gap: 10px;
    flex-wrap: wrap;
    margin-bottom: 6px;

    .duration {
      color: var(--tp-text-secondary);
      font-size: 12px;
    }
  }

  .result-times {
    display: flex;
    gap: 16px;
    flex-wrap: wrap;
    color: var(--tp-text-secondary);
    font-size: 12px;
    margin-bottom: 12px;
  }

  .error-block {
    background: var(--tp-bg-danger, rgba(245, 108, 108, 0.08));
    border: 1px solid rgba(245, 108, 108, 0.35);
    border-radius: 8px;
    padding: 12px 14px;
    margin-bottom: 16px;

    .error-title {
      display: flex;
      align-items: center;
      gap: 6px;
      font-weight: 600;
      margin-bottom: 6px;
    }

    .error-detail {
      color: var(--tp-text);
      line-height: 1.6;
      word-break: break-all;
      white-space: pre-wrap;
    }

    .ai-class-line {
      display: flex;
      align-items: center;
      gap: 6px;
      margin-top: 6px;

      .ai-class-note {
        color: var(--tp-text-secondary, var(--tp-text));
        line-height: 1.6;
        word-break: break-all;
      }
    }
  }

  .block-title {
    margin: 12px 0 10px;
    font-size: 14px;
  }

  
  :deep(.monitor-current-row) {
    --el-table-tr-bg-color: var(--tp-bg-hover, rgba(64, 158, 255, 0.12));
  }

  .evidence-block {
    .section-title {
      margin: 10px 0 8px;
      font-size: 13px;
      color: var(--tp-text-secondary);
    }

    .db-digest {
      margin: 0;
      padding: 10px 14px;
      list-style: none;
      background: var(--tp-bg-code, rgba(0, 0, 0, 0.06));
      border: 1px solid var(--tp-border);
      border-radius: 8px;
      font-size: 12px;
      line-height: 1.6;
      white-space: pre-wrap;
      word-break: break-all;
      color: var(--tp-text);
    }
  }

  .console-log {
    background: var(--tp-bg-code, rgba(0, 0, 0, 0.06));
    border: 1px solid var(--tp-border);
    border-radius: 8px;
    padding: 12px 14px;
    font-size: 12px;
    line-height: 1.6;
    white-space: pre-wrap;
    word-break: break-all;
    max-height: 260px;
    overflow-y: auto;
    color: var(--tp-text);
  }

  .log-block {
    scroll-margin-top: 12px;

    &.log-flash {
      box-shadow: 0 0 0 2px var(--tp-primary, #409eff);
    }
  }

  .shot-na {
    color: var(--tp-text-secondary);
  }

  
  .heal-summary {
    margin: 0 0 8px;
    font-size: 12px;
    color: var(--tp-text-secondary);
  }

  .heal-diff-block {
    margin-top: 8px;
    border: 1px solid var(--tp-border);
    border-radius: 8px;
    padding: 8px 12px;

    .heal-diff-detail {
      display: flex;
      flex-wrap: wrap;
      gap: 12px;
      font-size: 12px;
      line-height: 1.8;
      color: var(--tp-text-secondary);

      .heal-anchor {
        font-family: var(--tp-font-mono, monospace);
        color: var(--tp-text);
        word-break: break-all;
      }
    }
  }

  .snapshot-block {
    .snap-alert {
      margin-bottom: 10px;
    }

    .snap-cols {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 12px;
      align-items: start;
    }

    .snap-col {
      border: 1px solid var(--tp-border);
      border-radius: 8px;
      overflow: hidden;

      .snap-col-head {
        display: flex;
        align-items: center;
        justify-content: space-between;
        padding: 6px 10px;
        background: var(--tp-bg-hover, rgba(128, 128, 128, 0.08));
        border-bottom: 1px solid var(--tp-border);
      }

      .snap-col-title {
        font-size: 12px;
        font-weight: 600;
        color: var(--tp-text-secondary);
      }

      .snap-body {
        margin: 0;
        padding: 10px 12px;
        font-size: 12px;
        line-height: 1.6;
        white-space: pre-wrap;
        word-break: break-all;
        max-height: 300px;
        overflow-y: auto;
        background: var(--tp-bg-code, rgba(0, 0, 0, 0.06));
        color: var(--tp-text);
      }

      .snap-na {
        padding: 10px 12px;
        font-size: 12px;
        color: var(--tp-text-secondary);
      }
    }
  }

  .screenshot-block {
    .shot-list {
      display: flex;
      flex-wrap: wrap;
      gap: 12px;

      .shot-item {
        width: 220px;

        .shot-img {
          width: 220px;
          height: 140px;
          border: 1px solid var(--tp-border);
          border-radius: 6px;
          background: #1c1f26;
        }

        .shot-name {
          margin-top: 4px;
          font-size: 12px;
          color: var(--tp-text-secondary);
          text-align: center;
          word-break: break-all;
        }
      }
    }

    .shots-empty {
      display: flex;
      align-items: center;
      gap: 6px;
      color: var(--tp-text-secondary);
      font-size: 13px;
      padding: 8px 0;
    }
  }
}
</style>
