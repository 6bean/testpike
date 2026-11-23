<!--
 Two-step question-generation wizard (AW3 door of the judge write-chain
 facade): ① pick one source document of the current project segment and ask
 ai-service for a QA preview, ② review the preview against the cost card
 (chunk count / input tokens / output tokens / answering model) and publish
 the checked subset as a NEW immutable version through the same publish
 door the manual lane uses.

 Contract notes:
 - The sync-vs-background split is decided single-source by ai-service
 (bridge receipt mode sync/accepted); the page never re-derives the cap.
 Beyond the cap the wizard polls the job face (status + cumulative
 groups) and can tail the run's process events cursor-wise.
 - Publishing carries the currently loaded version's samples forward plus
 the checked items (append, never overwrite — lineage is core's call).
 - R28: with nothing checked the publish door stays disabled with an
 on-screen hint; an empty submission never reaches the server.
 - Worst-case size (S group): preview items page client-side (20/page)
 with cross-page checked rows preserved, so a 200-item run stays
 navigable.
-->
<template>
  <el-dialog
    :model-value="modelValue"
    :title="t('judge.qagen.title')"
    class="tp-dialog--lg"
    append-to-body
    data-test="qagen-wizard-dialog"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
    @closed="resetAll"
  >
    <el-steps :active="step" align-center class="qagen-steps">
      <el-step :title="t('judge.qagen.step1')" />
      <el-step :title="t('judge.qagen.step2')" />
    </el-steps>

    <!-- Step 1: source document + optional model pin + preview submit -->
    <template v-if="step === 0">
      <el-form label-position="top">
        <el-form-item :label="t('judge.qagen.docLabel')">
          <el-select
            v-model="documentId"
            filterable
            remote
            :remote-method="searchDocs"
            :loading="docsLoading"
            :placeholder="t('judge.qagen.docPlaceholder')"
            class="w-full"
            data-test="qagen-doc-select"
          >
            <el-option
              v-for="d in docOptions"
              :key="d.id"
              :label="docOptionLabel(d)"
              :value="d.id"
            />
          </el-select>
          <div v-if="!docsLoading && docOptions.length === 0" class="field-hint" data-test="qagen-doc-empty">
            {{ t('judge.qagen.docEmpty') }}
          </div>
        </el-form-item>
        <el-form-item :label="t('judge.qagen.modelHintLabel')">
          <el-input
            v-model="modelHint"
            clearable
            :placeholder="t('judge.qagen.modelHintPlaceholder')"
            data-test="qagen-model-hint"
          />
        </el-form-item>
      </el-form>

      <!-- Background job face (receipt mode=accepted) -->
      <div v-if="job && job.status !== 'done'" class="job-panel" data-test="qagen-job-panel">
        <p v-if="job.status === 'running'" class="mode-hint">
          {{ t('judge.qagen.jobRunning', { done: job.doneGroups, total: job.totalGroups, items: job.items.length }) }}
        </p>
        <p v-else class="mode-hint job-failed" data-test="qagen-job-failed">
          {{ t('judge.qagen.jobFailed', { msg: job.errorMsg || '-' }) }}
        </p>
        <el-button
          v-if="job.runToken"
          size="small"
          :loading="eventsLoading"
          data-test="qagen-events-btn"
          @click="tailEvents"
        >
          {{ t('judge.qagen.loadEventsBtn') }}
        </el-button>
        <ul v-if="events.length" class="event-tail" data-test="qagen-event-tail">
          <li v-for="e in events" :key="e.cursor" :class="`ev-${e.level}`">
            {{ e.summary }}
          </li>
        </ul>
      </div>

      <div class="wizard-actions">
        <el-button @click="close">{{ t('common.cancel') }}</el-button>
        <el-button
          type="primary"
          :loading="previewing"
          :disabled="projectId == null || !documentId || previewing"
          data-test="qagen-preview-btn"
          @click="startPreview"
        >
          {{ t('judge.qagen.previewBtn') }}
        </el-button>
      </div>
      <p v-if="projectId == null" class="mode-hint">{{ t('judge.qagen.noProject') }}</p>
    </template>

    <!-- Step 2: cost card + checked preview + publish -->
    <template v-else>
      <el-descriptions
        :column="4"
        border
        size="small"
        class="cost-card"
        data-test="qagen-cost-card"
      >
        <el-descriptions-item :label="t('judge.qagen.costChunks')">{{ cost.chunkCount }}</el-descriptions-item>
        <el-descriptions-item :label="t('judge.qagen.costPromptTokens')">{{ cost.promptTokens }}</el-descriptions-item>
        <el-descriptions-item :label="t('judge.qagen.costCompletionTokens')">{{ cost.completionTokens }}</el-descriptions-item>
        <el-descriptions-item :label="t('judge.qagen.costModel')">{{ cost.model || '-' }}</el-descriptions-item>
      </el-descriptions>

      <el-table
        :data="pagedItems"
        border
        max-height="300"
        row-key="_idx"
        data-test="qagen-item-table"
        @selection-change="(sel: PreviewRow[]) => (selected = sel)"
      >
        <el-table-column type="selection" width="44" :reserve-selection="true" />
        <el-table-column prop="question" :label="t('judge.qagen.colQuestion')" min-width="220" show-overflow-tooltip />
        <el-table-column prop="answer" :label="t('judge.qagen.colAnswer')" min-width="220" show-overflow-tooltip />
        <el-table-column prop="sourceAnchor" :label="t('judge.qagen.colAnchor')" width="160" show-overflow-tooltip />
      </el-table>
      <div v-if="rows.length > pageSize" class="pagination-bar">
        <el-pagination
          layout="total, prev, pager, next"
          :total="rows.length"
          :page-size="pageSize"
          :current-page="page"
          data-test="qagen-items-pagination"
          @current-change="(p: number) => (page = p)"
        />
      </div>

      <el-form label-position="top" class="publish-form">
        <el-form-item :label="t('judge.qagen.targetSet')">
          <el-input v-model="targetSetName" data-test="qagen-target-set" />
        </el-form-item>
        <p v-if="carryCount > 0" class="mode-hint" data-test="qagen-carry-hint">
          {{ t('judge.qagen.carryHint', { n: carryCount, m: selected.length }) }}
        </p>
      </el-form>

      <div class="wizard-actions">
        <el-button data-test="qagen-back-btn" @click="backToDoc">
          {{ t('judge.qagen.backBtn') }}
        </el-button>
        <span v-if="!publishReady" class="guard-hint" data-test="qagen-publish-guard">{{ guardText }}</span>
        <el-button
          type="primary"
          :disabled="!publishReady"
          :loading="publishing"
          data-test="qagen-publish-btn"
          @click="submitPublish"
        >
          {{ t('judge.qagen.publishBtn') }}
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { listDocuments } from '@/api/document'
import type { DocumentView } from '@/api/document'
import {
  getQaGenEvents, getQaGenJob, publishEvalSet, qaPreview,
  qaItemToPublishSample, sampleToPublishInput,
} from '@/api/evalSet'
import type { EvalSample, EvalSetVersionView, QaGenEvent, QaGenJob } from '@/api/evalSet'

const props = defineProps<{
  modelValue: boolean
  projectId: number | null | undefined
  defaultSetName: string
  /** Samples of the currently loaded version, carried forward on publish
   * when the target set is the same one (append, never overwrite). */
  existingSamples: EvalSample[]
}>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'published', res: EvalSetVersionView): void
}>()

const { t } = useI18n()

const step = ref(0)
const docOptions = ref<DocumentView[]>([])
const docsLoading = ref(false)
const documentId = ref<number | undefined>(undefined)
const modelHint = ref('')
const previewing = ref(false)
const job = ref<QaGenJob | null>(null)
const events = ref<QaGenEvent[]>([])
const eventsLoading = ref(false)

/** Preview rows with a stable client key for cross-page checked rows. */
interface PreviewRow {
  _idx: number
  question: string
  answer: string
  sourceAnchor: string
}
const rows = ref<PreviewRow[]>([])
const cost = ref({ chunkCount: 0, promptTokens: 0, completionTokens: 0, model: '' })
const selected = ref<PreviewRow[]>([])
const page = ref(1)
const pageSize = 20
const targetSetName = ref('')
const publishing = ref(false)
let pollTimer: ReturnType<typeof setInterval> | null = null

const pagedItems = computed(() => {
  const start = (page.value - 1) * pageSize
  return rows.value.slice(start, start + pageSize)
})

// Carry the loaded version forward only while publishing into the very
// same set (a different target name starts its own lineage at v1).
const carryCount = computed(() =>
  targetSetName.value.trim() !== '' && targetSetName.value.trim() === props.defaultSetName.trim()
    ? props.existingSamples.length
    : 0,
)

const publishReady = computed(() =>
  !publishing.value && selected.value.length > 0 && targetSetName.value.trim() !== '',
)

const guardText = computed(() => t(
  targetSetName.value.trim() === '' ? 'judge.qagen.guardName' : 'judge.qagen.guardSelect',
))

function docOptionLabel(d: DocumentView): string {
  return d.chunkCount > 0 ? `${d.name} · ${d.chunkCount}` : d.name
}

async function searchDocs(keyword: string) {
  if (props.projectId == null) return
  docsLoading.value = true
  try {
    const res = await listDocuments({ projectId: props.projectId, keyword: keyword || undefined, page: 1, size: 50 })
    docOptions.value = res.list ?? []
  } catch (err: any) {
    ElMessage.warning(err?.message || t('judge.qagen.docLoadFailed'))
  } finally {
    docsLoading.value = false
  }
}

function stopPoll() {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
}

function fillPreview(items: Record<string, unknown>[], c: { chunkCount: number; promptTokens: number; completionTokens: number; model: string }) {
  rows.value = items.map((it, i) => ({
    _idx: i,
    question: String((it as any).question ?? ''),
    answer: String((it as any).answer ?? ''),
    sourceAnchor: String((it as any).sourceAnchor ?? ''),
  }))
  cost.value = c
  page.value = 1
  selected.value = []
  step.value = 1
  if (!rows.value.length) ElMessage.warning(t('judge.qagen.emptyItems'))
}

async function startPreview() {
  if (props.projectId == null || !documentId.value) return
  previewing.value = true
  try {
    const receipt = await qaPreview(props.projectId, documentId.value, modelHint.value.trim() || undefined)
    if (receipt.mode === 'sync' && receipt.preview) {
      const p = receipt.preview
      fillPreview(p.items ?? [], {
        chunkCount: p.chunkCount, promptTokens: p.promptTokens,
        completionTokens: p.completionTokens, model: p.model,
      })
    } else {
      job.value = {
        documentId: documentId.value, runToken: receipt.runToken ?? '',
        status: 'running', totalGroups: 0, doneGroups: 0, items: [],
        promptTokens: 0, completionTokens: 0, model: '', errorMsg: null,
      }
      startPoll()
    }
  } catch (err: any) {
    ElMessage.error(err?.message || t('judge.qagen.previewFailed'))
  } finally {
    previewing.value = false
  }
}

function startPoll() {
  stopPoll()
  // Poll cadence is display-side only; the run's own timeout budget stays
  // single-source on the server (ai.judge.qa-gen.* keys) — closing the
  // dialog stops the poll, never the job.
  pollTimer = setInterval(async () => {
    if (!documentId.value) return
    try {
      const j = await getQaGenJob(documentId.value)
      job.value = j
      if (j.status === 'done') {
        stopPoll()
        // The job face reports sealed groups (chunk batches walked), not a
        // preview chunkCount — totalGroups is its honest equivalent here.
        fillPreview(j.items ?? [], {
          chunkCount: j.totalGroups,
          promptTokens: j.promptTokens,
          completionTokens: j.completionTokens,
          model: j.model,
        })
      } else if (j.status === 'failed') {
        stopPoll()
      }
    } catch {
      // A transient poll miss is not a failure verdict — the next tick
      // retries; only the server-side row decides the run's fate.
    }
  }, 2500)
}

async function tailEvents() {
  if (!documentId.value || !job.value?.runToken) return
  eventsLoading.value = true
  try {
    const cursor = events.value.length ? events.value[events.value.length - 1].cursor : 0
    const win = await getQaGenEvents(documentId.value, job.value.runToken, cursor, 50)
    if (win.length) events.value = [...events.value, ...win]
  } catch (err: any) {
    ElMessage.warning(err?.message || t('judge.qagen.previewFailed'))
  } finally {
    eventsLoading.value = false
  }
}

function backToDoc() {
  step.value = 0
  stopPoll()
  job.value = null
  events.value = []
}

async function submitPublish() {
  if (!publishReady.value) return
  publishing.value = true
  try {
    const carried = carryCount.value > 0 ? props.existingSamples.map(sampleToPublishInput) : []
    const fresh = selected.value.map((r) =>
      qaItemToPublishSample({ question: r.question, answer: r.answer, sourceAnchor: r.sourceAnchor }))
    const res = await publishEvalSet(targetSetName.value.trim(), [...carried, ...fresh])
    emit('published', res)
    close()
  } catch (err: any) {
    // Core rejections arrive with their own message — shown as-is, the
    // wizard stays open so the choice can be fixed and retried.
    ElMessage.error(err?.message || t('judge.evalset.publishFailed'))
  } finally {
    publishing.value = false
  }
}

function close() {
  emit('update:modelValue', false)
}

function resetAll() {
  stopPoll()
  step.value = 0
  documentId.value = undefined
  modelHint.value = ''
  job.value = null
  events.value = []
  rows.value = []
  selected.value = []
  cost.value = { chunkCount: 0, promptTokens: 0, completionTokens: 0, model: '' }
  page.value = 1
  targetSetName.value = props.defaultSetName
}

watch(() => props.modelValue, (open) => {
  if (open) {
    targetSetName.value = props.defaultSetName
    if (props.projectId != null && !docOptions.value.length) searchDocs('')
  }
})

onBeforeUnmount(stopPoll)
</script>

<style scoped>
.qagen-steps { margin-bottom: 16px; }
.w-full { width: 100%; }
.mode-hint { margin: 0 0 8px; font-size: 12px; opacity: 0.75; }
.job-failed { color: var(--el-color-danger); }
.field-hint { font-size: 12px; opacity: 0.7; margin-top: 4px; }
.cost-card { margin-bottom: 12px; }
.pagination-bar { margin-top: 8px; display: flex; justify-content: flex-end; }
.publish-form { margin-top: 12px; }
.wizard-actions { display: flex; justify-content: flex-end; align-items: center; gap: 8px; margin-top: 12px; }
.event-tail { margin: 8px 0 0; padding-left: 16px; max-height: 140px; overflow: auto; font-size: 12px; opacity: 0.85; }
.ev-error { color: var(--el-color-danger); }
</style>
