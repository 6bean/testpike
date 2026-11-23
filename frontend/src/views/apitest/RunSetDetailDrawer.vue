<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import {
  compareRuns,
  getRunSetDetail,
  listRunSets,
  previewRunSet,
  type ResponseCompareMode,
  type ResponseCompareView,
  type ResponseDiffKind,
  type RunSetDetail,
  type RunSetItem,
  type RunSetPreviewStep,
  type RunSetStepItem,
} from '@/api/apiTest'
import { createDefect, DEFECT_SEVERITY_LABEL } from '@/api/defectLibrary'
import { bizErrorMsg } from '@/utils/bizError'

/**
 * Collection-run result drawer : live traffic lights per hop +
 * run history for the collection, one drawer, addressable by run-set id.
 * Live status = short polling of the detail read model while the ledger is
 * open (0/1); the advancer advances one hop per executor callback, so the
 * drawer simply re-reads (3s interval is server-progress polling outside
 * Playwright auto-waiting — legitimate hard-interval use).
 * Variable flow: per-hop extract/reference key names come from the preview
 * read model (same single source as the launch card); merged values live on
 * the collection pool row and are intentionally NOT re-derived here.
 */

const props = defineProps<{
  modelValue: boolean
  collectionId: number | null
  /** Owning project of the collection segment — header-context single source,
 * consumed only by the defect exit payload (never a second picker). */
  projectId: number | null
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
}>()

const { t } = useI18n()

const history = ref<RunSetItem[]>([])
const historyLoading = ref(false)
const activeId = ref<number | null>(null)
const detail = ref<RunSetDetail | null>(null)
const detailLoading = ref(false)
const previewByRequestId = ref<Map<number, RunSetPreviewStep>>(new Map())

const SET_STATUS_LABEL: Record<number, string> = {
  0: 'apitest.runSet.statusQueued',
  1: 'apitest.runSet.statusRunning',
  2: 'apitest.runSet.statusPassed',
  3: 'apitest.runSet.statusFailed',
  4: 'apitest.runSet.statusInterrupted',
}

const STEP_STATUS_LABEL: Record<number, string> = {
  0: 'apitest.runSet.stepPending',
  1: 'apitest.runSet.stepPassed',
  2: 'apitest.runSet.stepAssertFail',
  3: 'apitest.runSet.stepExecErr',
  4: 'apitest.runSet.stepSkipped',
}

const STEP_STATUS_TAG: Record<number, 'info' | 'success' | 'danger' | 'warning'> = {
  0: 'info',
  1: 'success',
  2: 'danger',
  3: 'danger',
  4: 'warning',
}

/**
 * Honest attribution for a passed step : the panel must never
 * claim "assertions passed" for a row that had none configured. The display
 * vocabulary is single-sourced here — protocol + landed assertion count pick
 * one of four lines (http with/without asserts, ws with/without asserts).
 * Non-passed rows return '' (their status tag already tells the truth).
 */
function stepAttribution(s: RunSetStepItem): string {
  if (s.status !== 1) return ''
  if (s.protocol === 'ws') {
    return s.assertCount > 0
      ? t('apitest.runSet.stepAttrWsPassedWithAsserts', { count: s.assertCount })
      : t('apitest.runSet.stepAttrWsConnected')
  }
  return s.assertCount > 0
    ? t('apitest.runSet.stepAttrHttpPassed', { count: s.assertCount })
    : t('apitest.runSet.stepAttrHttpNoAssert')
}

/**
 * Run-history response compare (W-c5): pick exactly two child runs, diff the
 * recorded response bodies through the guarded admin bridge. Core owns the
 * tri-state and the four-color vocabulary — the UI only classifies colors.
 */
const compareIds = ref<number[]>([])
const compareView = ref<ResponseCompareView | null>(null)
const compareLoading = ref(false)
const compareError = ref('')

const KIND_TAG: Record<ResponseDiffKind, 'success' | 'danger' | 'warning' | 'primary'> = {
  ADDED: 'success',
  REMOVED: 'danger',
  TYPE_CHANGED: 'warning',
  VALUE_CHANGED: 'primary',
}

const MODE_LABEL_KEY: Record<ResponseCompareMode, string> = {
  JSON: 'apitest.runSet.modeJson',
  TEXT: 'apitest.runSet.modeText',
  BINARY: 'apitest.runSet.modeBinary',
}

/** Four-color vocabulary labels (explicit literals — greppable contract). */
const KIND_LABEL_KEY: Record<ResponseDiffKind, string> = {
  ADDED: 'apitest.runSet.kindADDED',
  REMOVED: 'apitest.runSet.kindREMOVED',
  TYPE_CHANGED: 'apitest.runSet.kindTYPE_CHANGED',
  VALUE_CHANGED: 'apitest.runSet.kindVALUE_CHANGED',
}

/** Add/remove a compare pick; a third pick replaces nothing — it is refused
 *  with a plain-word hint (two-row compare stays honest, never implicit drop). */
function toggleCompare(runId: number): void {
  const cur = compareIds.value
  if (cur.includes(runId)) {
    compareIds.value = cur.filter((id) => id !== runId)
    return
  }
  if (cur.length >= 2) {
    ElMessage.info(t('apitest.runSet.comparePick'))
    return
  }
  compareIds.value = [...cur, runId]
}

async function runCompare(): Promise<void> {
  if (compareIds.value.length !== 2) return
  compareLoading.value = true
  compareError.value = ''
  try {
    compareView.value = await compareRuns(compareIds.value[0], compareIds.value[1])
  } catch (e) {
    compareView.value = null
    compareError.value = bizErrorMsg(e, t('apitest.runSet.compareFail'))
  } finally {
    compareLoading.value = false
  }
}

/** One digest line shortened for display (full hex stays in the title attr). */
function shortDigest(hex: string): string {
  return hex.length > 16 ? `${hex.slice(0, 16)}…` : hex
}

const identical = computed(() =>
  compareView.value !== null &&
  compareView.value.entries.length === 0 &&
  compareView.value.leftSha256 === compareView.value.rightSha256)

/** Defect exit: reuse the built-in defect-library chain (no parallel system).
 *  The draft is the compare verdict in plain words — severity defaults P2. */
const defectDialogOpen = ref(false)
const defectSaving = ref(false)
const defectTitle = ref('')
const defectDraft = ref('')
const defectSeverity = ref(3)

function openDefectDraft(): void {
  if (!detail.value || compareIds.value.length !== 2) return
  const entryCount = compareView.value?.entries.length ?? 0
  defectTitle.value = t('apitest.runSet.defectDialogTitle', {
    name: detail.value.ledger.name || t('apitest.runSet.unnamed'),
    left: compareIds.value[0],
    right: compareIds.value[1],
  })
  defectDraft.value = buildDefectDraft(entryCount)
  defectDialogOpen.value = true
}

/** Verdict text for the description: mode + counts, honest about truncation. */
function buildDefectDraft(entryCount: number): string {
  const v = compareView.value
  const lines: string[] = []
  lines.push(t('apitest.runSet.compareTitle'))
  lines.push(`run #${compareIds.value[0]} vs run #${compareIds.value[1]}`)
  if (v) {
    lines.push(`${t(MODE_LABEL_KEY[v.mode])}: ${v.mode === 'BINARY' ? `${v.leftSha256} / ${v.rightSha256}` : `${entryCount}`}`)
    if (v.leftTruncated || v.rightTruncated) lines.push(t('apitest.runSet.compareTruncatedWarn'))
    for (const e of v.entries.slice(0, 40)) {
      lines.push(`${e.path} [${t(KIND_LABEL_KEY[e.kind])}] ${e.leftSummary ?? '-'} -> ${e.rightSummary ?? '-'}`)
    }
    if (v.entries.length > 40) lines.push(`... (${v.entries.length - 40})`)
  }
  return lines.join('\n')
}

async function submitDefect(): Promise<void> {
  if (props.projectId === null || !detail.value) return
  defectSaving.value = true
  try {
    await createDefect({
      projectId: props.projectId,
      title: defectTitle.value,
      description: defectDraft.value,
      severity: defectSeverity.value,
    })
    ElMessage.success(t('apitest.runSet.defectOk'))
    defectDialogOpen.value = false
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('apitest.runSet.defectFail')))
  } finally {
    defectSaving.value = false
  }
}

function setStatusLabel(status: number): string {
  return t(SET_STATUS_LABEL[status] ?? 'apitest.runSet.statusQueued')
}

const isOpen = computed(() => detail.value !== null && (detail.value.ledger.status === 0 || detail.value.ledger.status === 1))

let timer: ReturnType<typeof setInterval> | null = null

function stopPolling(): void {
  if (timer !== null) {
    clearInterval(timer)
    timer = null
  }
}

async function loadDetail(): Promise<void> {
  if (activeId.value === null) return
  detailLoading.value = true
  try {
    detail.value = await getRunSetDetail(activeId.value)
    if (isOpen.value && timer === null) {
      // Server-side progress: re-read until the ledger closes (interval, not a
      // Playwright wait — the condition is a backend state machine advance).
      timer = setInterval(() => { void loadDetail() }, 3000)
    } else if (!isOpen.value) {
      stopPolling()
    }
  } catch (e) {
    bizErrorMsg(e, t('apitest.runSet.detailLoadFail'))
    stopPolling()
  } finally {
    detailLoading.value = false
  }
}

/** History window (display-scale iron law): newest-first pages of 10 + pager. */
const HISTORY_PAGE_SIZE = 10
const historyPage = ref(1)
const historyTotal = ref(0)

async function loadHistory(): Promise<void> {
  if (props.collectionId === null) return
  historyLoading.value = true
  try {
    const win = await listRunSets(props.collectionId, historyPage.value, HISTORY_PAGE_SIZE)
    history.value = win.list
    historyTotal.value = win.total
  } catch (e) {
    history.value = []
    historyTotal.value = 0
    bizErrorMsg(e, t('apitest.runSet.historyLoadFail'))
  } finally {
    historyLoading.value = false
  }
}

async function changeHistoryPage(p: number): Promise<void> {
  historyPage.value = p
  await loadHistory()
}

async function openDetail(id: number): Promise<void> {
  activeId.value = id
  // Compare picks belong to one run-set's child rows — switching the ledger
  // clears the selection so a stale pair can never be compared across sets.
  compareIds.value = []
  compareView.value = null
  compareError.value = ''
  await loadDetail()
}

/** Key-name flow overlay for one hop (preview read model, never re-parsed). */
function previewOf(requestId: number): RunSetPreviewStep | undefined {
  return previewByRequestId.value.get(requestId)
}

/** Per-hop expanded rows (default collapsed — worst-case scale posture). */
const flowOpen = ref<Set<number>>(new Set())

function toggleFlow(runId: number): void {
  const next = new Set(flowOpen.value)
  if (next.has(runId)) next.delete(runId)
  else next.add(runId)
  flowOpen.value = next
}

watch(() => props.modelValue, async (open) => {
  if (!open) {
    stopPolling()
    return
  }
  detail.value = null
  activeId.value = null
  historyPage.value = 1
  previewByRequestId.value = new Map()
  flowOpen.value = new Set()
  compareIds.value = []
  compareView.value = null
  compareError.value = ''
  await loadHistory()
  if (props.collectionId !== null) {
    try {
      const rows = await previewRunSet(props.collectionId)
      previewByRequestId.value = new Map(rows.map((r) => [r.requestId, r]))
    } catch {
      // preview overlay is additive — detail rows stay honest without key names
    }
  }
  if (history.value.length > 0) await openDetail(history.value[0].id)
})

onUnmounted(stopPolling)
</script>

<template>
  <el-drawer
    :modelValue="props.modelValue"
    :title="t('apitest.runSet.drawerTitle')"
    size="680px"
    data-test="apitest-runset-drawer"
    @update:modelValue="(v: boolean) => emit('update:modelValue', v)"
  >
    <div v-loading="historyLoading">
      <p v-if="history.length === 0 && !historyLoading" class="rsd-empty" data-test="apitest-runset-history-empty">
        {{ t('apitest.runSet.historyEmpty') }}
      </p>
      <div v-else class="rsd-history">
        <button
          v-for="h in history"
          :key="h.id"
          class="rsd-history-row"
          :class="{ 'is-active': h.id === activeId }"
          type="button"
          data-test="apitest-runset-history-row"
          @click="openDetail(h.id)"
        >
          <span class="mono">#{{ h.id }}</span>
          <span class="rsd-history-name">{{ h.name || t('apitest.runSet.unnamed') }}</span>
          <el-tag size="small" :type="h.status === 2 ? 'success' : (h.status === 1 || h.status === 0) ? 'info' : 'danger'">
            {{ setStatusLabel(h.status) }}
          </el-tag>
          <span class="rsd-history-count">{{ t('apitest.runSet.countLine', { passed: h.passedCount, failed: h.failedCount, skipped: h.skippedCount, total: h.totalSteps }) }}</span>
        </button>
      </div>
      <div
        v-if="historyTotal > HISTORY_PAGE_SIZE"
        class="pagination-bar rsd-history-pager"
        data-test="apitest-runset-history-pager"
      >
        <el-pagination
          :current-page="historyPage"
          :page-size="HISTORY_PAGE_SIZE"
          :total="historyTotal"
          layout="prev, pager, next, total"
          @current-change="changeHistoryPage"
        />
      </div>
    </div>

    <div v-if="detail" v-loading="detailLoading" class="rsd-detail" data-test="apitest-runset-detail">
      <div class="rsd-summary">
        <el-tag :type="detail.ledger.status === 2 ? 'success' : (detail.ledger.status === 1 || detail.ledger.status === 0) ? 'info' : 'danger'" data-test="apitest-runset-status-tag">
          {{ setStatusLabel(detail.ledger.status) }}
        </el-tag>
        <span class="rsd-mode">{{ t('apitest.runSet.modePrefix') }}{{ detail.ledger.interruptionMode }}</span>
        <el-popover placement="bottom" :width="320" trigger="click">
          <template #reference>
            <el-button link type="primary" data-test="apitest-runset-seed-vars-btn">{{ t('apitest.runSet.seedVars') }}</el-button>
          </template>
          <pre class="rsd-json mono">{{ detail.ledger.initialVarsJson }}</pre>
        </el-popover>
      </div>

      <div class="rsd-steps" data-test="apitest-runset-steps">
        <template v-for="s in detail.steps" :key="s.runId">
          <div class="rsd-step">
            <el-checkbox
              :model-value="compareIds.includes(s.runId)"
              size="small"
              :aria-label="t('apitest.runSet.comparePick')"
              data-test="apitest-runset-compare-check"
              @click.stop
              @change="toggleCompare(s.runId)"
            />
            <span class="rsd-light" :class="`is-${s.status}`" data-test="apitest-runset-light" />
            <span class="rsd-seq">#{{ s.seqNo }}</span>
            <span class="mono rsd-method">{{ s.method }}</span>
            <span class="rsd-name">{{ s.name }}</span>
            <el-tag size="small" :type="STEP_STATUS_TAG[s.status] ?? 'info'">{{ t(STEP_STATUS_LABEL[s.status] ?? 'apitest.runSet.stepPending') }}</el-tag>
            <span v-if="stepAttribution(s)" class="rsd-attr" data-test="apitest-runset-attribution">{{ stepAttribution(s) }}</span>
            <span v-if="s.durationMs !== null" class="rsd-dur">{{ s.durationMs }} ms</span>
            <el-button
              v-if="previewOf(s.requestId) || s.errorMsg"
              link
              type="primary"
              size="small"
              :aria-expanded="flowOpen.has(s.runId)"
              data-test="apitest-runset-flow-toggle"
              @click="toggleFlow(s.runId)"
            >{{ t('apitest.runSet.varFlow') }}</el-button>
          </div>
          <div v-if="flowOpen.has(s.runId)" class="rsd-flow" data-test="apitest-runset-flow">
            <p v-if="previewOf(s.requestId)?.references.length" class="rsd-flow-line">
              {{ t('apitest.runSet.consumes') }}:
              <el-tag v-for="k in previewOf(s.requestId)!.references" :key="`c-${k}`" size="small" effect="plain">{{ k }}</el-tag>
            </p>
            <p v-if="previewOf(s.requestId)?.extracts.length" class="rsd-flow-line">
              {{ t('apitest.runSet.produces') }}:
              <el-tag v-for="k in previewOf(s.requestId)!.extracts" :key="`p-${k}`" size="small" effect="plain">{{ k }}</el-tag>
            </p>
            <p v-if="s.errorMsg" class="rsd-err">{{ s.errorMsg }}</p>
          </div>
        </template>
      </div>

      <div class="rsd-compare-bar" data-test="apitest-runset-compare-bar">
        <span class="rsd-compare-hint">{{ t('apitest.runSet.comparePick') }}</span>
        <el-button
          size="small"
          type="primary"
          plain
          data-test="apitest-runset-compare-btn"
          :disabled="compareIds.length !== 2"
          :loading="compareLoading"
          @click="runCompare"
        >{{ t('apitest.runSet.compareBtn') }}</el-button>
        <el-button
          size="small"
          plain
          :disabled="compareView === null"
          data-test="apitest-runset-defect-btn"
          @click="openDefectDraft"
        >{{ t('apitest.runSet.defectEntry') }}</el-button>
      </div>

      <p v-if="compareError" class="rsd-err" data-test="apitest-runset-compare-error">{{ compareError }}</p>

      <div v-if="compareView" class="rsd-compare" v-loading="compareLoading" data-test="apitest-runset-compare-panel">
        <div class="rsd-compare-head">
          <el-tag size="small" effect="plain">{{ t(MODE_LABEL_KEY[compareView.mode]) }}</el-tag>
          <span class="rsd-compare-pair mono">#{{ compareIds[0] }} ↔ #{{ compareIds[1] }}</span>
        </div>
        <el-alert
          v-if="compareView.leftTruncated || compareView.rightTruncated"
          type="warning"
          :closable="false"
          show-icon
          data-test="apitest-runset-truncated-warn"
          :title="t('apitest.runSet.compareTruncatedWarn')"
        />
        <p v-if="identical" class="rsd-compare-identical" data-test="apitest-runset-identical">
          {{ t('apitest.runSet.compareIdentical') }}
        </p>
        <template v-else-if="compareView.mode === 'BINARY'">
          <p class="rsd-compare-binary" data-test="apitest-runset-binary-note">{{ t('apitest.runSet.compareBinaryNote') }}</p>
          <div class="rsd-digest mono">
            <span>#{{ compareIds[0] }}</span>
            <el-tag size="small" :type="compareView.leftSha256 === compareView.rightSha256 ? 'success' : 'danger'" data-test="apitest-runset-digest-left">
              <span :title="compareView.leftSha256">{{ shortDigest(compareView.leftSha256) }}</span>
            </el-tag>
            <span>#{{ compareIds[1] }}</span>
            <el-tag size="small" :type="compareView.leftSha256 === compareView.rightSha256 ? 'success' : 'danger'" data-test="apitest-runset-digest-right">
              <span :title="compareView.rightSha256">{{ shortDigest(compareView.rightSha256) }}</span>
            </el-tag>
          </div>
          <p class="rsd-compare-digests">
            {{ compareView.leftSha256 === compareView.rightSha256
              ? t('apitest.runSet.compareSameDigest')
              : t('apitest.runSet.compareDiffDigest') }}
          </p>
        </template>
        <el-table
          v-else-if="compareView.mode === 'JSON' || compareView.mode === 'TEXT'"
          :data="compareView.entries"
          border
          size="small"
          max-height="260"
          class="rsd-diff-table"
          data-test="apitest-runset-diff-table"
          :empty-text="t('apitest.runSet.compareIdentical')"
        >
          <el-table-column prop="path" :label="t('apitest.runSet.colPath')" min-width="140" show-overflow-tooltip>
            <template #default="{ row }"><span class="mono">{{ row.path }}</span></template>
          </el-table-column>
          <el-table-column :label="t('apitest.runSet.colKind')" width="92">
            <template #default="{ row }">
              <el-tag size="small" :type="KIND_TAG[row.kind as ResponseDiffKind]">
                {{ t(KIND_LABEL_KEY[row.kind as ResponseDiffKind]) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('apitest.runSet.colLeft')" min-width="120" show-overflow-tooltip>
            <template #default="{ row }"><span class="mono">{{ row.leftSummary ?? '-' }}</span></template>
          </el-table-column>
          <el-table-column :label="t('apitest.runSet.colRight')" min-width="120" show-overflow-tooltip>
            <template #default="{ row }"><span class="mono">{{ row.rightSummary ?? '-' }}</span></template>
          </el-table-column>
        </el-table>
      </div>
    </div>
    <p v-else-if="!historyLoading" class="rsd-empty">{{ t('apitest.runSet.detailEmpty') }}</p>

    <el-dialog
      v-model="defectDialogOpen"
      draggable
      width="480px"
      :title="t('apitest.runSet.defectEntry')"
      data-test="apitest-runset-defect-dialog"
      append-to-body
    >
      <div class="rsd-defect-field">
        <label for="apitest-runset-defect-title">{{ t('apitest.runSet.defectTitlePh') }}</label>
        <el-input
          id="apitest-runset-defect-title"
          v-model="defectTitle"
          data-test="apitest-runset-defect-title"
          maxlength="200"
        />
      </div>
      <div class="rsd-defect-field">
        <label for="apitest-runset-defect-sev">{{ t('apitest.runSet.defectSevLabel') }}</label>
        <el-select
          id="apitest-runset-defect-sev"
          v-model="defectSeverity"
          data-test="apitest-runset-defect-sev"
          style="width: 160px"
        >
          <el-option v-for="(label, sev) in DEFECT_SEVERITY_LABEL" :key="sev" :label="label" :value="Number(sev)" />
        </el-select>
      </div>
      <div class="rsd-defect-field">
        <label for="apitest-runset-defect-desc">{{ t('apitest.runSet.defectDescLabel') }}</label>
        <el-input
          id="apitest-runset-defect-desc"
          v-model="defectDraft"
          data-test="apitest-runset-defect-desc"
          type="textarea"
          :rows="8"
        />
      </div>
      <template #footer>
        <el-button size="small" @click="defectDialogOpen = false">{{ t('common.cancel') }}</el-button>
        <el-button
          size="small"
          type="primary"
          :loading="defectSaving"
          :disabled="defectTitle.trim() === ''"
          data-test="apitest-runset-defect-submit"
          @click="submitDefect"
        >{{ t('common.confirm') }}</el-button>
      </template>
    </el-dialog>
  </el-drawer>
</template>

<style scoped>
.rsd-empty {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.rsd-history {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 12px;
  max-height: 160px;
  overflow-y: auto;
}

.rsd-history-pager {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 12px;
}

.rsd-history-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: transparent;
  cursor: pointer;
  font-size: 13px;
  text-align: left;
  min-width: 0;
}

.rsd-history-row.is-active {
  border-color: var(--el-color-primary);
  /* Vision closing: a border-only active state is barely readable in the
     dark theme — pair the accent border with a fill so the selected history
     row stands out at a glance. */
  background: var(--el-fill-color-light);
}

.rsd-history-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}

.rsd-history-count {
  margin-left: auto;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  flex-shrink: 0;
}

.mono {
  font-family: var(--tp-font-mono, monospace);
}

.rsd-summary {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 8px 0;
  font-size: 13px;
}

.rsd-mode {
  color: var(--el-text-color-secondary);
}

.rsd-json {
  max-height: 200px;
  overflow: auto;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0;
}

.rsd-steps {
  border-top: 1px solid var(--el-border-color-lighter);
  /* Vision closing (display-scale iron law): a collection run can carry a
     long step list — keep the detail window scroll-contained instead of
     letting steps grow the drawer body without bound. */
  max-height: 320px;
  overflow-y: auto;
}

.rsd-step {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  min-width: 0;
  border-bottom: 1px dashed var(--el-border-color-lighter);
}

.rsd-light {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
  background: var(--el-border-color);
}

.rsd-light.is-0 { background: var(--el-color-info-light-5); }
.rsd-light.is-1 { background: var(--el-color-success); }
.rsd-light.is-2,
.rsd-light.is-3 { background: var(--el-color-danger); }
.rsd-light.is-4 { background: var(--el-color-warning); }

.rsd-seq {
  color: var(--el-text-color-secondary);
  flex-shrink: 0;
}

.rsd-method {
  width: 56px;
  flex-shrink: 0;
}

.rsd-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}

.rsd-attr {
  margin-left: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  flex-shrink: 0;
}

.rsd-dur {
  margin-left: auto;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  flex-shrink: 0;
}

.rsd-flow {
  width: 100%;
}

.rsd-flow-line {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
  font-size: 12px;
  margin: 4px 0;
}

.rsd-err {
  color: var(--el-color-danger);
  font-size: 12px;
}

.rsd-compare-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 10px 0 4px;
}

.rsd-compare-hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.rsd-compare {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 8px;
  background: var(--el-fill-color-lighter);
}

.rsd-compare-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
  font-size: 12px;
}

.rsd-compare-pair {
  color: var(--el-text-color-secondary);
}

.rsd-compare-identical,
.rsd-compare-binary,
.rsd-compare-digests {
  font-size: 12px;
  margin: 4px 0;
  color: var(--el-text-color-secondary);
}

.rsd-digest {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
}

.rsd-diff-table {
  width: 100%;
}

.rsd-defect-field {
  margin-bottom: 10px;
}

.rsd-defect-field label {
  display: block;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 4px;
}
</style>
