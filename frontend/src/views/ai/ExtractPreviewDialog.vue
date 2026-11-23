<template>
  <el-dialog draggable
    :model-value="modelValue"
    :title="t('document.extractDialogTitle', { name: doc?.name || '' })"
    width="960px"
    class="extract-dialog"
    data-test="extract-dialog"
    @update:model-value="emit('update:modelValue', $event)"
    @open="onOpen"
    @closed="stopPolling"
  >
    <!-- In-progress face: poll hint + stage ledger (pitfall a: status is the task-state
 query surface, polled every 5s). shows which step landed last, its
 timestamp and a live activity window instead of a silent spinner.
 Worst-case scale (642-chunk documents land hundreds of stage rows): only the
 latest row is shown inline; the full list is collapsed until opened and capped
 by a scroll container. -->
    <div v-if="state === 3" class="extract-parsing" data-test="extract-parsing-hint">
      <div class="extract-parsing-line">
        <el-icon class="is-loading"><Loading /></el-icon>
        <span>{{ t('document.extractParsing') }}</span>
      </div>
      <!-- : explicit cancel stops a running extraction without waiting for the
 watchdog; write gate = canWrite (same door as starting it). -->
      <el-button
        v-if="canWrite"
        type="danger"
        plain
        size="small"
        :loading="canceling"
        class="extract-cancel-btn"
        data-test="extract-cancel-btn"
        @click="submitCancel"
      >{{ t('document.extractCancel') }}</el-button>
      <div v-if="latestStage" class="extract-stage-row" data-test="extract-stage-row">
        <el-tag type="info" size="small" data-test="extract-stage-label">{{ latestStage.label }}</el-tag>
        <span class="extract-stage-time" data-test="extract-stage-time">
          {{ t('document.extractStageAt', { time: formatLocalDateTime(stageActivityRaw) }) }}
          <template v-if="idleText"> · {{ t('document.extractStageAgo', { duration: idleText }) }}</template>
        </span>
        <span v-if="runDurationText" class="extract-stage-total" data-test="extract-stage-total">
          {{ t('document.extractStageTotal', { duration: runDurationText }) }}
        </span>
        <el-button
          v-if="stageLedger.length > 1"
          link
          type="primary"
          size="small"
          data-test="extract-stage-toggle"
          @click="stageHistoryOpen = !stageHistoryOpen"
        >{{ stageHistoryOpen ? t('document.extractStageHide') : t('document.extractStageShow', { count: stageLedger.length }) }}</el-button>
      </div>
      <ol v-if="stageHistoryOpen" class="extract-stage-history" data-test="extract-stage-history">
        <li v-for="(s, idx) in stageLedgerReversed" :key="`${s.code}-${s.at}-${idx}`" data-test="extract-stage-item">
          <span class="extract-stage-item-at">{{ formatLocalDateTime(s.at) }}</span>
          <span class="extract-stage-item-label">{{ s.label }}</span>
        </li>
      </ol>
      <div class="extract-tail-wrap" style="margin-top: 10px" data-test="extract-tail-wrap">
        <TaskTailDrawer :events="tailEvents" :live="true" can-stop @stop="submitCancel" />
      </div>
    </div>

    
    <div v-else-if="state === 1 && extractError" class="extract-failed" data-test="extract-error-block">
      <el-alert
        :title="t('document.extractFailedTitle')"
        type="error"
        :closable="false"
        show-icon
        :description="extractError || t('document.unknownFail')"
        data-test="extract-error-alert"
      />
      <el-button
        v-if="canWrite"
        type="primary"
        class="retry-btn"
        :loading="submitting"
        data-test="extract-retry-btn"
        @click="submitExtract()"
      >
        {{ t('document.extractRetry') }}
      </el-button>
    </div>

    
    <template v-else-if="state === 4">
      
      <el-alert
        v-if="uncoveredCount > 0"
        class="extract-gap-alert"
        type="warning"
        :closable="false"
        show-icon
        :title="t('document.extractGapBanner', { count: uncoveredCount })"
        data-test="extract-gap-banner"
      >
        <template #default>
          <span class="extract-gap-anchors">
            <el-button
              v-for="cid in uncoveredIdsShown"
              :key="cid"
              link
              type="primary"
              size="small"
              :title="gapAnchorTitle(cid)"
              :data-test="`extract-gap-anchor-${cid}`"
              @click="openSourceById(cid)"
            >#{{ cid }}</el-button>
            <span v-if="uncoveredIds.length > 8" class="extract-gap-more">…</span>
          </span>
        </template>
      </el-alert>
      
      <div v-if="dimensionSuggestions.length > 0" class="extract-suggest-row" data-test="extract-suggest-row">
        <span class="extract-suggest-label">{{ t('document.extractSuggestLabel') }}</span>
        <el-tooltip
          v-for="dim in dimensionSuggestions"
          :key="dim"
          :content="t('document.extractSuggestChipTip', { dim })"
          placement="top"
        >
          <el-tag
            type="info"
            size="small"
            class="extract-suggest-chip"
            data-test="extract-suggest-chip"
            role="button"
            tabindex="0"
            @click="pickDimension(dim)"
            @keyup.enter="pickDimension(dim)"
          >{{ dim }}</el-tag>
        </el-tooltip>
      </div>
      
      <div v-if="fidelity" class="extract-fidelity-row" data-test="extract-fidelity-row">
        <el-tag type="info" size="small" data-test="extract-fidelity-retention">
          {{ t('document.extractFidelityRetention', {
            percent: fidelity.retentionPercent,
            injected: tokenText(fidelity.injectedTokens),
            source: tokenText(fidelity.sourceTokens),
          }) }}
        </el-tag>
        <el-tag
          :type="fidelity.itemCount > 0 && fidelity.anchoredItemCount === fidelity.itemCount ? 'success' : 'warning'"
          size="small"
          data-test="extract-fidelity-anchor"
        >
          {{ t('document.extractFidelityAnchor', {
            percent: fidelity.anchorRatePercent,
            anchored: fidelity.anchoredItemCount,
            total: fidelity.itemCount,
          }) }}
        </el-tag>
        <el-tooltip :content="fidelityDetailText" placement="top">
          <el-tag type="info" size="small" data-test="extract-fidelity-cost">
            {{ t('document.extractFidelityCost', { tokens: tokenText(fidelity.billedTokensPerItem) }) }}
          </el-tag>
        </el-tooltip>
      </div>
      <el-table border
        v-loading="loading"
        :data="items"
        data-test="extract-table"
        class="extract-table"
        row-key="id"
        @selection-change="onSelectionChange"
      >
        <el-table-column type="selection" width="46" :selectable="isSelectable" />
        <el-table-column prop="title" :label="t('document.extractColTitle')" min-width="200" show-overflow-tooltip />
        <el-table-column prop="acceptanceCriteria" :label="t('document.extractColAc')" min-width="240" show-overflow-tooltip />
        <!-- remnant ⑧: the chapter label itself opens the owning chunk.
 Same single door as the source column (openSource -> one shared
 fetch); no second viewer channel. Unanchored rows keep the plain
 label — the honest disabled twin lives in the source column. -->
        <el-table-column prop="chapter" :label="t('document.extractColChapter')" width="160" show-overflow-tooltip>
          <template #default="{ row }">
            <el-tooltip
              v-if="row.chapter && row.chunkId != null"
              :content="t('document.extractChapterOpenTip')"
              placement="top"
            >
              <el-tag
                type="info"
                size="small"
                class="extract-chapter-open"
                role="button"
                tabindex="0"
                data-test="extract-chapter-open"
                @click="openSource(row)"
                @keyup.enter="openSource(row)"
              >{{ row.chapter }}</el-tag>
            </el-tooltip>
            <span v-else>{{ row.chapter || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('document.extractColConfidence')" width="120" align="center">
          <template #default="{ row }">
            <span v-if="row.confidence != null" data-test="extract-confidence">{{ (row.confidence * 100).toFixed(0) }}%
              <el-tooltip v-if="needsReview(row)" :content="reviewTipText" placement="top">
                <el-tag type="warning" size="small" data-test="extract-review-chip">
                  {{ t('document.extractReviewChip') }}
                </el-tag>
              </el-tooltip>
            </span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('document.extractColStored')" width="100" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.requirementId != null" type="success" size="small" data-test="extract-stored-tag">
              {{ t('document.extractStored') }}
            </el-tag>
            <!-- an unanchored preview item can never be stored
 (the server-side anchor gate skips it at confirm) — say so up
 front with the reason and the hot-updatable switch key, not a
 silent skip after the reviewer already clicked confirm. -->
            <el-tooltip
              v-else-if="row.chunkId == null"
              :content="t('document.extractAnchorPendingTip')"
              placement="top"
            >
              <el-tag type="warning" size="small" data-test="extract-anchor-pending">
                {{ t('document.extractAnchorPending') }}
              </el-tag>
            </el-tooltip>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <!-- Re-extract tri-state door: an explicit choice closes the row here;
             no choice keeps the server default = update-proposal draft card
             awaiting the one-click human confirmation inside the chat door.
             Unselected rows echo that default as a read-only tag so the
             reviewer sees the effective value before selecting anything. -->
        <el-table-column :label="t('document.extractColDisposition')" width="180" align="center">
          <template #default="{ row }">
            <el-select
              v-if="isRowSelected(row)"
              v-model="itemDispositions[row.id]"
              clearable
              size="small"
              :placeholder="t('document.dispositionDefault')"
              data-test="extract-disposition-select"
            >
              <el-option value="KEEP_AS_IS" :label="t('document.dispositionKeep')" />
              <el-option value="FORCE_NEW" :label="t('document.dispositionForceNew')" />
            </el-select>
            <el-tag
              v-else
              type="info"
              size="small"
              effect="plain"
              data-test="extract-disposition-default"
            >
              {{ t('document.dispositionDefault') }}
            </el-tag>
          </template>
        </el-table-column>
        <!-- AP-4 jump-back-to-source: chunkId present = open the owning chunk verbatim;
             chunkId null = honest disabled state with a readable reason (no silent no-op) -->
        <el-table-column :label="t('document.extractColSource')" width="110" align="center">
          <template #default="{ row }">
            <el-tooltip
              v-if="row.chunkId == null"
              :content="t('document.extractSourceMissing')"
              placement="top"
            >
              <span>
                <el-button link type="primary" disabled data-test="extract-source-btn-disabled">
                  {{ t('document.extractSourceView') }}
                </el-button>
              </span>
            </el-tooltip>
            <el-button v-else link type="primary" data-test="extract-source-btn" @click="openSource(row)">
              {{ t('document.extractSourceView') }}
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty :description="t('document.extractEmpty')" />
        </template>
      </el-table>
      <div class="pagination-bar">
        <el-pagination
          v-if="total > 0"
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          data-test="extract-pagination"
          @current-change="loadPreview()"
          @size-change="loadPreview(1)"
        />
      </div>
    </template>

    
    <div v-else class="extract-initial" data-test="extract-initial-block">
      <p class="extract-intro">{{ t('document.extractIntro') }}</p>
      <el-input
        v-model="modelHint"
        :placeholder="t('document.modelHintPlaceholder')"
        clearable
        style="width: 280px"
        data-test="extract-model-hint"
      />
    </div>

    <!-- AP-4 source viewer: the shared chunk viewer renders
 the verbatim text for this dialog and the requirement list alike, so one point
 always reads the same original through one door (R40 single source). -->
    <SourceChunkViewerDialog
      v-model="sourceVisible"
      v-model:chunk-id="sourceChunkId"
      :document-id="props.doc?.id ?? null"
      :gap-reason="sourceGapReasonText"
      :fallback-evidence="sourceEvidence"
      :can-supplement="canWrite"
      :supplementing="supplementing"
      @supplement="submitSupplement"
    />

    <template #footer>
      <el-button data-test="extract-close-btn" @click="emit('update:modelValue', false)">
        {{ t('document.close') }}
      </el-button>
      <el-button
        v-if="canWrite && state !== 3 && state !== 4 && !(state === 1 && extractError)"
        type="primary"
        :loading="submitting"
        data-test="extract-submit-btn"
        @click="submitExtract()"
      >
        {{ t('document.extractStart') }}
      </el-button>
      <el-button
        v-if="canWrite && state === 4"
        type="primary"
        :loading="submitting"
        :disabled="selectedItems.length === 0"
        data-test="extract-confirm-btn"
        @click="submitConfirm"
      >
        {{ t('document.extractConfirm') }}({{ selectedItems.length }})
      </el-button>
      <el-button
        v-if="canWrite && state === 4"
        type="primary"
        :loading="submitting"
        data-test="extract-confirm-all-btn"
        @click="submitConfirmAll"
      >
        {{ t('document.extractConfirmAll') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import { computed, onScopeDispose } from 'vue'
import { bizErrorMsg } from '@/utils/bizError'
import { useRouter } from 'vue-router'
import { buildAiGenerateDeepLink } from '@/utils/aiBatchLinkage'
import {
  cancelExtraction,
  confirmAllExtraction,
  confirmExtraction,
  extractDocument,
  getExtraction,
  type DocumentView,
  type ExtractionItem,
  type ExtractionDispositionName,
  type ExtractionGap,
  type ExtractionStage,
  type ExtractionFidelity,
} from '@/api/document'
import { formatLocalDateTime } from '@/utils/localDateTime'
import { openExtractionEventTail, mergeEvents, type ExtractionEvent, type TailHandle } from '@/api/taskEvents'
import TaskTailDrawer from '@/components/TaskTailDrawer.vue'
import SourceChunkViewerDialog from '@/components/SourceChunkViewerDialog.vue'


const props = defineProps<{
  modelValue: boolean
  doc: DocumentView | null
  canWrite: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'updated'): void
}>()

const { t } = useI18n()
/** Hand-off target after a successful confirm (the created ids ride the deep link). */
const router = useRouter()


const state = ref<number | null>(null)
const extractError = ref<string | null>(null)
const items = ref<ExtractionItem[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const submitting = ref(false)
const canceling = ref(false)
const modelHint = ref('')
const selectedItems = ref<ExtractionItem[]>([])

// Per-row disposition decisions (re-extract tri-state door): preview item id
// -> reviewer choice. Absent keeps the server default update-proposal path.
const itemDispositions = ref<Record<number, ExtractionDispositionName>>({})

// AP-4 jump-back-to-source viewer state (one chunk fetched on demand; stale ids
// surface the server's readable 404 message, no client-side re-check)
const sourceVisible = ref(false)

// Review-flag echo + coverage-gap ledger state. The chip verdict reads the
// server-echoed threshold (hot-updatable key), never a per-page constant; the
// shipped default below only covers backends that have not echoed the value yet.
const DEFAULT_REVIEW_FLAG_THRESHOLD = 0.7
const reviewThreshold = ref<number>(DEFAULT_REVIEW_FLAG_THRESHOLD)
const uncoveredCount = ref(0)
const uncoveredIds = ref<number[]>([])
// : per-chunk reason rows of the pending coverage steps (mirror of the
// ledger echo). Absent on backends without the ledger half — the banner then
// keeps its count and the viewer hides the reason card, never an invented why.
const uncoveredGaps = ref<ExtractionGap[]>([])
const dimensionSuggestions = ref<string[]>([])
const reviewPercentText = computed(() => Math.round(reviewThreshold.value * 100))
const reviewTipText = computed(() => t('document.extractReviewTip', { percent: reviewPercentText.value }))
/** Visible-id cap keeps a hundred-step gap from blowing the banner layout. */
const uncoveredIdsShown = computed(() => uncoveredIds.value.slice(0, 8))

function needsReview(row: ExtractionItem): boolean {
  return row.confidence != null && row.confidence < reviewThreshold.value
}

// stage-ledger face of the in-progress state. Rows refresh on the
// 5s preview poll; the activity window (idle since last step / total run time)
// rides a 1s ticker so the numbers never freeze between two polls. No fabricated
// ETA — only server-anchored, verifiable time facts are shown.
const stageLedger = ref<ExtractionStage[]>([])
const lastActivityAt = ref<string | null>(null)
const stageHistoryOpen = ref(false)
const nowTick = ref(Date.now())

// the run's fidelity account, echoed by the server at the
// single call seam. The client only formats numbers for the eye — retention,
// anchor fill and per-item coefficients are never recomputed here.
const fidelity = ref<ExtractionFidelity | null>(null)
function tokenText(n: number): string {
  return n.toLocaleString()
}
const fidelityDetailText = computed(() => {
  const f = fidelity.value
  if (!f) return ''
  return t('document.extractFidelityDetail', {
    nodes: f.nodeCount,
    chunks: f.chunkCount,
    chapters: f.distinctChapters,
    drift: f.estDriftPercent,
    summary: f.summaryCalls,
    reconcile: f.reconcileCalls,
  })
})

const latestStage = computed(() =>
  stageLedger.value.length > 0 ? stageLedger.value[stageLedger.value.length - 1] : null)
/** Newest-first so a hundred-step ledger opens on the live end, not on "start". */
const stageLedgerReversed = computed(() => [...stageLedger.value].reverse())
const stageActivityRaw = computed(() => lastActivityAt.value || latestStage.value?.at || '')

/** Local "YYYY-MM-DDTHH:mm:ss" (server writes local) -> epoch ms; unparsable = no anchor. */
function parseLocalMs(value: string | null | undefined): number | null {
  if (!value) return null
  const ms = new Date(value).getTime()
  return Number.isNaN(ms) ? null : ms
}

/** seconds -> "m:ss" / "h:mm:ss": locale-neutral digits, no unit words to translate. */
function durationClockText(seconds: number): string {
  const p = (n: number) => String(n).padStart(2, '0')
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = Math.floor(seconds % 60)
  return h > 0 ? `${h}:${p(m)}:${p(s)}` : `${m}:${p(s)}`
}

/** Idle since the last landed stage, clamped at 0 (clock skew must never read as negative). */
const idleSeconds = computed(() => {
  const ms = parseLocalMs(stageActivityRaw.value)
  if (ms === null) return 0
  return Math.max(0, Math.floor((nowTick.value - ms) / 1000))
})
/** Below 3s the idle line would flicker per second for no information — suppressed. */
const idleText = computed(() => (idleSeconds.value >= 3 ? durationClockText(idleSeconds.value) : ''))
/** Total run time from the start row; no start anchor yet = line hidden, never guessed. */
const runDurationText = computed(() => {
  const startMs = parseLocalMs(stageLedger.value.find((s) => s.code === 'start')?.at)
  if (startMs === null) return ''
  return durationClockText(Math.max(0, Math.floor((nowTick.value - startMs) / 1000)))
})

const sourceChunkId = ref<number | null>(null)
/** Verbatim excerpt stored on the preview row, rides along as the shared
 *  viewer's fallback answer when its chunk cannot be read. */
const sourceEvidence = ref('')

// viewer state: neighbours read expanded by default (the thin-chunk
// explanation is the whole point of opening a gap chunk); directed-supplement
// rides the same submitting semantics as the full run, kept separate so the
// footer button of the nested viewer does not toggle the outer dialog.
const supplementing = ref(false)
/** The ledger reason of the chunk currently open in the viewer ('' when the
 *  row came from an anchored preview item without a pending step). */
const sourceGapReasonText = computed<string>(() => {
  const cid = sourceChunkId.value
  if (cid == null) return ''
  const gap = uncoveredGaps.value.find(g => g.chunkId === cid)
  return gap ? gapReasonText(gap) : ''
})
/** Reason sentence = the ledger's own note verbatim (evidence as the second
 *  source, then an honest "no reason recorded") plus the size point that
 *  explains why the sliver reads small. */
function gapReasonText(gap: ExtractionGap): string {
  const why = gap.note?.trim() || gap.evidence?.trim() || t('document.extractGapNoReason')
  return `${why} ${t('document.extractGapThin', { chars: gap.contentChars })}`
}
/** Banner chip hover: the reason summary when the ledger row is in sight,
 *  the plain open hint otherwise (backends without the ledger half). */
function gapAnchorTitle(cid: number): string {
  const gap = uncoveredGaps.value.find(g => g.chunkId === cid)
  if (!gap) return t('document.extractGapAnchorHint')
  const why = gap.note?.trim() || gap.evidence?.trim()
  return why
    ? `${t('document.extractGapReasonTitle')}: ${why}`
    : t('document.extractGapAnchorHint')
}

const POLL_INTERVAL_MS = 5000
let pollTimer: ReturnType<typeof setTimeout> | null = null

function stopPolling(): void {
  if (pollTimer !== null) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
  stopTicker()
}

function schedulePoll(): void {
  stopPolling()
  pollTimer = setTimeout(() => {
    void loadPreview()
  }, POLL_INTERVAL_MS)
}

// 1s live ticker (: idle / total-run clocks keep counting between the 5s polls;
// stops as soon as the extraction leaves the in-progress state)
const TICK_INTERVAL_MS = 1000
let tickTimer: ReturnType<typeof setInterval> | null = null

function stopTicker(): void {
  if (tickTimer !== null) {
    clearInterval(tickTimer)
    tickTimer = null
  }
}

function scheduleTicker(active: boolean): void {
  nowTick.value = Date.now()
  if (!active) {
    stopTicker()
  } else if (tickTimer === null) {
    tickTimer = setInterval(() => {
      nowTick.value = Date.now()
    }, TICK_INTERVAL_MS)
  }
}

// process-event tail drawer. The SSE main road rides the
// document door; a dead wire degrades to the cursor-window poll inside the
// shared client — the dialog only merges batches into the ledger.
const tailEvents = ref<ExtractionEvent[]>([])
let tailHandle: TailHandle | null = null

function startTail(): void {
  stopTail()
  if (!props.doc) return
  tailEvents.value = []
  tailHandle = openExtractionEventTail(props.doc.id, 0, batch => {
    tailEvents.value = mergeEvents(tailEvents.value, batch)
  })
}

function stopTail(): void {
  if (tailHandle !== null) {
    tailHandle.close()
    tailHandle = null
  }
}

onScopeDispose(stopPolling)
onScopeDispose(stopTail)

function onOpen(): void {
  modelHint.value = ''
  state.value = props.doc ? props.doc.status : null
  extractError.value = null
  items.value = []
  total.value = 0
  selectedItems.value = []
  // Clear the echoed threshold and the gap ledger on every open,
  // a reopened dialog must never show the previous document's numbers
  reviewThreshold.value = DEFAULT_REVIEW_FLAG_THRESHOLD
  uncoveredCount.value = 0
  uncoveredIds.value = []
  uncoveredGaps.value = []
  dimensionSuggestions.value = []
  // Same rule for the stage ledger : drop the previous document's steps
  stageLedger.value = []
  lastActivityAt.value = null
  fidelity.value = null
  stageHistoryOpen.value = false
  if (state.value === 1 || state.value === 3 || state.value === 4) {
    void loadPreview()
  }
  // open the live tail whenever the dialog opens on a running
  // extraction; the shared client replays from cursor 0 (the ledger window)
  // and keeps the poll road alive after any wire break.
  if (state.value === 3) {
    startTail()
  }
}


async function loadPreview(p = page.value): Promise<void> {
  if (!props.doc) return
  page.value = p
  loading.value = true
  try {
    const res = await getExtraction(props.doc.id, page.value, size.value)
    state.value = res.status
    extractError.value = res.extractError
    items.value = res.items || []
    total.value = res.total || 0
    // server echo drives the chip verdict; null echo keeps the shipped default floor
    reviewThreshold.value = res.reviewFlagThreshold ?? DEFAULT_REVIEW_FLAG_THRESHOLD
    uncoveredCount.value = res.uncoveredChunkCount || 0
    uncoveredIds.value = res.uncoveredChunkIds || []
    uncoveredGaps.value = res.uncoveredChunks || []
    dimensionSuggestions.value = res.dimensionSuggestions || []
    // Stage ledger : replayed rows of this generation + server activity clock
    stageLedger.value = res.stageLedger || []
    lastActivityAt.value = res.lastActivityAt ?? null
    // Fidelity ledger : the run's own account, echoed verbatim;
    // null before the row lands = the line stays hidden, never a guessed ratio.
    fidelity.value = res.fidelity ?? null
    if (res.status === 3) {
      schedulePoll()
      scheduleTicker(true)
      // close-ticket: a dialog that opened on a pending document
      // and later observes the run start must attach the tail automatically —
      // the shared client replays from cursor 0, so a late attach loses nothing
      if (tailHandle === null) {
        startTail()
      }
    } else {
      stopPolling()
      // Leaving the running state also closes the tail road: the drawer
      // unmounts with it, an open handle would keep reconnecting for nothing
      stopTail()
      emit('updated')
    }
  } catch (e: unknown) {
    ElMessage.error(bizErrorMsg(e, t('document.extractLoadFail')))
    stopPolling()
  } finally {
    loading.value = false
  }
}

function isSelectable(row: ExtractionItem): boolean {
  return row.requirementId == null
}

function onSelectionChange(rows: ExtractionItem[]): void {
  const visible = new Set(items.value.map((i) => i.id))
  const kept = selectedItems.value.filter((i) => !visible.has(i.id))
  selectedItems.value = [...kept, ...rows]
}

/** Open the AP-4 source viewer: fetch one chunk verbatim through the single
 *  door. Both anchored preview rows and the coverage-gap ledger anchors ride
 *  it — ledger ids live in this door's key space (document chunk rows), not
 *  in the knowledge vector id space the /knowledge citation door reads. */
function openSourceById(chunkId: number | null | undefined, evidence = ''): void {
  if (!props.doc || chunkId == null) return
  // The shared viewer owns the read; this door only says which chunk to open
  // plus what excerpt to fall back on when that chunk is no longer readable.
  sourceChunkId.value = chunkId
  sourceEvidence.value = evidence
  sourceVisible.value = true
}

/** Row action: open the item's owning chunk (null ids render disabled). */
function openSource(row: ExtractionItem): void {
  void openSourceById(row.chunkId, row.evidence ?? '')
}


async function submitExtract(dimensionHint?: string): Promise<void> {
  if (!props.doc) return
  submitting.value = true
  try {
    const doc = await extractDocument(props.doc.id, modelHint.value || undefined, dimensionHint)
    state.value = doc.status
    emit('updated')
    ElMessage.success(t('document.extractSubmitted'))
    await loadPreview(1)
  } catch (e: unknown) {
    ElMessage.error(bizErrorMsg(e, t('document.extractFail')))
    await loadPreview(1)
  } finally {
    submitting.value = false
  }
}

/** directed supplement: one round over exactly the chunk open in the
 * viewer (POST extract with the chunkIds whitelist — the same pipeline and
 * the same write gate as a full run, no second extraction road). Starting
 * flips the dialog into the existing in-progress face (stage ledger + tail
 * drawer + 5s poll), and the residual banner reloads itself when the run
 * lands; a rejection (foreign chunk, run already in progress) comes back as
 * the server's own readable message. */
async function submitSupplement(): Promise<void> {
  if (!props.doc || sourceChunkId.value == null) return
  supplementing.value = true
  try {
    const doc = await extractDocument(props.doc.id, modelHint.value || undefined, undefined, [sourceChunkId.value])
    state.value = doc.status
    emit('updated')
    ElMessage.success(t('document.extractSupplementSubmitted'))
    sourceVisible.value = false
    await loadPreview(1)
  } catch (e: unknown) {
    // same recovery as submitExtract: a 409 means a run is already up, so
    // reload and let the in-progress face take over instead of dead-ending
    ElMessage.error(bizErrorMsg(e, t('document.extractFail')))
    await loadPreview(1)
  } finally {
    supplementing.value = false
  }
}

/** : explicit cancel of the running extraction. The server rotates the
 * generation and writes status=1 + a human-readable extractError back, so the
 * loadPreview(1) reload flips this dialog to the existing failure block
 * (pit c: status=1 with a non-empty extractError); nothing extra is rendered. */
async function submitCancel(): Promise<void> {
  if (!props.doc) return
  canceling.value = true
  try {
    await cancelExtraction(props.doc.id)
    ElMessage.success(t('document.extractCancelOk'))
    await loadPreview(1)
  } catch (e: unknown) {
    // 409 "nothing to cancel" comes back as the core's readable message
    ElMessage.error(bizErrorMsg(e, t('document.extractCancelFail')))
    await loadPreview(1)
  } finally {
    canceling.value = false
  }
}

/** -3b chip hand-off: one click asks for a targeted supplemental pass for
 * the dimension; the confirm keeps the human gate, storing stays per-item. */
async function pickDimension(dim: string): Promise<void> {
  try {
    await ElMessageBox.confirm(
      t('document.extractSuggestConfirmText', { dim }),
      t('document.extractSuggestConfirmTitle'),
    )
  } catch {
    return // dismissed = no request sent
  }
  await submitExtract(dim)
}

/** Selected rows carrying an explicit reviewer choice (the third state stays
 *  implicit: no entry = the server issues the update-proposal draft card). */
function buildDispositionPayload(): Record<number, ExtractionDispositionName> {
  const payload: Record<number, ExtractionDispositionName> = {}
  for (const row of selectedItems.value) {
    const value = itemDispositions.value[row.id]
    if (value === 'KEEP_AS_IS' || value === 'FORCE_NEW') {
      payload[row.id] = value
    }
  }
  return payload
}

function isRowSelected(row: ExtractionItem): boolean {
  return selectedItems.value.some((s) => s.id === row.id)
}

/** Confirm-store through the tri-state door; the toast names the drafted
 *  proposals so the reviewer knows cards await one-click confirmation. */
async function submitConfirm(): Promise<void> {
  if (!props.doc || selectedItems.value.length === 0) return
  submitting.value = true
  try {
    const res = await confirmExtraction(props.doc.id, selectedItems.value, buildDispositionPayload())
    ElMessage.success(t('document.confirmOk', { created: res.createdCount, skipped: res.skipped, drafted: res.drafted }))
    selectedItems.value = []
    itemDispositions.value = {}
    await loadPreview(1)
    emit('updated')
    // One-click hand-off: freshly stored requirement points can ride the ids
    // allow-list deep link straight into the batch generation page. Declining
    // the offer keeps the reviewer on this page (nothing is written either way).
    if (res.createdRequirementIds.length > 0 && props.doc.projectId) {
      try {
        await ElMessageBox.confirm(
          t('document.genNewTip', { n: res.createdRequirementIds.length }),
          t('document.genNewTitle'),
          { type: 'success' },
        )
      } catch {
        return
      }
      await router.push(buildAiGenerateDeepLink(props.doc.projectId, res.createdRequirementIds))
    }
  } catch (e: unknown) {
    ElMessage.error(bizErrorMsg(e, t('document.confirmFail')))
  } finally {
    submitting.value = false
  }
}

/** Full-store door : core lands the whole preview in one transaction —
 * a paged viewer can never cap the result — then every freshly created id
 * rides the existing deep-link hand-off into the batch generation page
 * (queue, rate limit and the draft-card confirm all stay single-sourced
 * there). The button click already states the intent, so no second prompt
 * sits between storing and the jump. */
async function submitConfirmAll(): Promise<void> {
  if (!props.doc) return
  submitting.value = true
  try {
    const res = await confirmAllExtraction(props.doc.id)
    ElMessage.success(t('document.confirmOk', { created: res.createdCount, skipped: res.skipped, drafted: res.drafted }))
    selectedItems.value = []
    itemDispositions.value = {}
    await loadPreview(1)
    emit('updated')
    if (res.createdRequirementIds.length > 0 && props.doc.projectId) {
      await router.push(buildAiGenerateDeepLink(props.doc.projectId, res.createdRequirementIds))
    }
  } catch (e: unknown) {
    ElMessage.error(bizErrorMsg(e, t('document.confirmFail')))
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.extract-gap-alert {
  margin-bottom: 12px;
}
.extract-suggest-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}
.extract-suggest-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
/* clickable suggestion chip (hand-off entry); focus ring stays global (R29) */
.extract-suggest-chip {
  cursor: pointer;
}
/* ⑧: the chapter tag opens the owning chunk through the same viewer door */
.extract-chapter-open {
  cursor: pointer;
  max-width: 100%;
}
.extract-chapter-open :deep(.el-tag__content) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* fidelity ledger info line : compact tag row above the table */
.extract-fidelity-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 10px;
}
.extract-parsing {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 24px 0;
  justify-content: center;
  color: var(--el-text-color-secondary);
  font-size: 14px;
}
.extract-parsing-line {
  display: flex;
  align-items: center;
  gap: 8px;
}
.extract-stage-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  justify-content: center;
  gap: 10px;
  max-width: 100%;
  color: var(--el-text-color-primary);
}
.extract-stage-time,
.extract-stage-total {
  font-variant-numeric: tabular-nums;
}
.extract-stage-history {
  /* Worst-case scale (642-chunk level: hundreds of landed steps): collapsed by
     default, scrolled when opened — never an unbounded inline wall. */
  margin: 0;
  padding: 8px 12px 8px 28px;
  max-height: 220px;
  overflow-y: auto;
  width: min(640px, 100%);
  text-align: left;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-blank);
  font-size: 13px;
  line-height: 1.7;
}
.extract-stage-item-at {
  font-variant-numeric: tabular-nums;
  color: var(--el-text-color-secondary);
  margin-right: 8px;
}
.extract-failed {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.retry-btn {
  align-self: flex-start;
}
.extract-intro {
  margin: 0 0 12px;
  color: var(--el-text-color-regular);
  font-size: 14px;
  line-height: 1.6;
}
/* reason card + neighbour context (theme tokens only; focus ring
 stays on the global :focus-visible, R29 — no page-local outline patches) */
</style>
