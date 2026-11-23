<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessageBox } from 'element-plus'
import {
  applySpecPreview,
  extractSpecFromDocument,
  fetchApiSpecByUrl,
  generateSpecPreview,
  getSpecPreviewByDocument,
  listApiSpecs,
  uploadApiSpec,
  type ApiSpecDraftItemView,
  type ApiSpecPreviewView,
  type ApiSpecView,
  type SpecExtractEstimate,
  type SpecPreviewApplyView,
  isSpecExtractTerminalRow,
  specExtractFailureReason,
} from '@/api/apiSpec'
import { fetchSpecExtractionEvents } from '@/api/taskEvents'
import { useTestRunTail } from '@/composables/useTestRunTail'
import TaskTailDrawer from '@/components/TaskTailDrawer.vue'
import {
  loadTailWindowConfig, surveyTailCap, tailHeartbeatSeconds, tailFirstFrameMs, tailTerminalDwellMs,
} from '@/composables/tailWindowConfig'
import { listDocuments, type DocumentView } from '@/api/document'
import { bizErrorMsg } from '@/utils/bizError'



const props = defineProps<{
  modelValue: boolean
  projectId: number | null
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'imported', result: SpecPreviewApplyView): void
}>()

const { t } = useI18n()

const step = ref(0)
const sourceTab = ref<'existing' | 'paste' | 'file' | 'document' | 'url'>('existing')

const specs = ref<ApiSpecView[]>([])
const specsLoading = ref(false)
const pickedSpecId = ref<number | null>(null)
const pasteText = ref('')
const urlText = ref('')
const docName = ref('')
const fileName = ref('')
const fileRef = ref<File | null>(null)
const parsing = ref(false)
const step1Error = ref('')
const fileInputEl = ref<HTMLInputElement | null>(null)

async function loadSpecs(): Promise<void> {
  if (props.projectId === null) return
  specsLoading.value = true
  try {
    const page = await listApiSpecs({ projectId: props.projectId, page: 1, size: 50 })
    specs.value = page.list
  } catch (e) {
    step1Error.value = bizErrorMsg(e, t('apitest.specImport.loadSpecsFail'))
  } finally {
    specsLoading.value = false
  }
}


function safeParse<T>(text: string, fallback: T): T {
  if (!text) return fallback
  try {
    return JSON.parse(text) as T
  } catch {
    return fallback
  }
}

function onPickFile(ev: Event): void {
  const input = ev.target as HTMLInputElement
  const f = input.files?.[0] ?? null
  fileRef.value = f
  fileName.value = f?.name ?? ''
}

function onDropFile(ev: DragEvent): void {
  const f = ev.dataTransfer?.files?.[0] ?? null
  if (!f) return
  fileRef.value = f
  fileName.value = f.name
}

function fileSuffix(name: string): string {
  const i = name.lastIndexOf('.')
  return i < 0 ? '' : name.slice(i).toLowerCase()
}


async function parseFromUpload(): Promise<number | null> {
  if (props.projectId === null) return null
  if (sourceTab.value === 'url') {
    const url = urlText.value.trim()
    if (!url) {
      step1Error.value = t('apitest.specImport.urlEmpty')
      return null
    }
    try {
      const fetched = await fetchApiSpecByUrl(props.projectId, docName.value.trim() || 'url-spec', url)
      return fetched.id
    } catch (e) {
      step1Error.value = bizErrorMsg(e, t('apitest.specImport.fetchFail'))
      return null
    }
  }
  let file: File
  let name: string
  if (sourceTab.value === 'paste') {
    if (!pasteText.value.trim()) {
      step1Error.value = t('apitest.specImport.pasteEmpty')
      return null
    }
    name = docName.value.trim() || 'pasted-spec'
    const suffix = fileSuffix(name) || '.json'
    file = new File([pasteText.value], name.endsWith(suffix) ? name : name + suffix, { type: 'application/json' })
  } else {
    if (!fileRef.value) {
      step1Error.value = t('apitest.specImport.fileEmpty')
      return null
    }
    name = docName.value.trim() || fileName.value.replace(/\.[^.]+$/, '')
    file = fileRef.value
  }
  const uploaded = await uploadApiSpec(props.projectId, name, file)
  return uploaded.id
}

const docs = ref<DocumentView[]>([])
const docsLoading = ref(false)
const pickedDocId = ref<number | null>(null)
const modelHint = ref('')
const dispatching = ref(false)
const extracting = ref(false)
const extractNote = ref('')

/**
 * R62 C-face : the extraction process rides the SHARED tail base —
 * one cursor channel, windowed frames with a hot cap, terminal auto-seal
 * (no second window implementation, R40). The dispatch POST already queued
 * the run server-side, so the window opens right after the ack; the cost
 * dialog is only the human intent to watch — cancelling never stops the
 * observation, and no copy ever asks the user to refresh or reopen.
 */
void loadTailWindowConfig()
const specTail = useTestRunTail(
  (runToken, cursor) => fetchSpecExtractionEvents(Number(runToken), cursor),
  {
    terminal: isSpecExtractTerminalRow,
    emptyGracePolls: 600,
    maxPolls: 2400,
    closeOnTerminal: true,
    terminalDwellMs: () => tailTerminalDwellMs.value,
    cap: () => surveyTailCap.value,
    heartbeatMs: () => tailHeartbeatSeconds.value * 1000,
  },
)
const {
  events: specTailEvents, running: specTailRunning, drawerOpen: specTailOpen,
  stalled: specTailStalled, interrupted: specTailInterrupted,
} = specTail
/** Bumped on wizard reset / scope dispose so a stale watch round never
 *  advances the steps of a fresh session. */
let specTailEpoch = 0

function closeSpecTail(): void {
  specTailEpoch += 1
  specTail.close()
  extracting.value = false
}

onScopeDispose(closeSpecTail)

/**
 * Watch one dispatched extraction to its end and consume the outcome without
 * any human refresh: the done frame opens the preview automatically, the
 * failure frame shows the server's human reason inline, and a window that
 * exhausts without a terminal frame degrades honestly (R62②, R63).
 */
function watchSpecExtraction(documentId: number): void {
  const myEpoch = ++specTailEpoch
  void specTail.openRun({ runToken: String(documentId) }).then(async () => {
    if (myEpoch !== specTailEpoch) return
    extracting.value = false
    const terminalRow = [...specTail.events.value]
      .reverse()
      .find((row) => isSpecExtractTerminalRow(row))
    if (!terminalRow) {
      step1Error.value = t('apitest.specImport.extractInterrupted')
      return
    }
    const failure = specExtractFailureReason(terminalRow)
    if (failure !== null) {
      step1Error.value = t('apitest.specImport.extractFailed', { reason: failure })
      return
    }
    try {
      const preview = await getSpecPreviewByDocument(documentId)
      previewData.value = preview
      detectedNote.value = t('apitest.specImport.detectedAi', { n: preview.items.length })
      initSelection(preview.items)
      step.value = 1
    } catch (e: unknown) {
      step1Error.value = bizErrorMsg(e, t('apitest.specImport.extractFail'))
    }
  })
}


const extractableDocs = computed(() =>
  docs.value.filter((d) => (d.status === 1 || d.status === 4) && d.category !== 'DDL_DICT'))

async function loadDocs(): Promise<void> {
  if (props.projectId === null) return
  docsLoading.value = true
  try {
    const page = await listDocuments({ projectId: props.projectId, page: 1, size: 50 })
    docs.value = page.list
  } catch (e) {
    step1Error.value = bizErrorMsg(e, t('apitest.specImport.loadDocsFail'))
  } finally {
    docsLoading.value = false
  }
}

function formatTokens(n: number): string {
  return n >= 10000 ? `${(n / 1000).toFixed(1)}k` : String(n)
}

/**
 * Kick off a channel-B extraction: the dispatch POST is atomic server-side
 * (guard + cost gate + MQ enqueue) and answers with the token estimate shown
 * on the cost dialog (02 contract). The run is already queued at the ack, so
 * the tail window opens before the dialog is even answered — cancelling the
 * dialog only declines the intent to watch; the dispatched run keeps
 * streaming to the automatic preview (R62).
 */
async function onExtract(): Promise<void> {
  if (props.projectId === null) {
    step1Error.value = t('apitest.pickProjectFirst')
    return
  }
  if (pickedDocId.value === null) {
    step1Error.value = t('apitest.specImport.pickDocFirst')
    return
  }
  step1Error.value = ''
  extractNote.value = ''
  dispatching.value = true
  const documentId = pickedDocId.value
  specTail.beginPending()
  let est: SpecExtractEstimate
  try {
    est = await extractSpecFromDocument(documentId, modelHint.value.trim() || null)
  } catch (e) {
    specTail.cancelPending()
    step1Error.value = bizErrorMsg(e, t('apitest.specImport.extractFail'))
    return
  } finally {
    dispatching.value = false
  }
  extracting.value = true
  extractNote.value = t('apitest.specImport.extractStarted')
  watchSpecExtraction(documentId)
  try {
    await ElMessageBox.confirm(
      t('apitest.specImport.costBody', {
        chunks: est.chunks,
        tokens: formatTokens(est.estimatedTokens),
        max: est.maxChunks,
      }),
      t('apitest.specImport.costTitle'),
      {
        confirmButtonText: t('apitest.specImport.costConfirm'),
        cancelButtonText: t('apitest.specImport.costCancel'),
        type: 'warning',
      },
    )
  } catch {
    extractNote.value = t('apitest.specImport.extractQueuedNote')
  }
}

async function onParse(): Promise<void> {
  if (props.projectId === null) {
    step1Error.value = t('apitest.pickProjectFirst')
    return
  }
  step1Error.value = ''
  parsing.value = true
  try {
    let specId: number | null = null
    let endpointCount = 0
    let specFormat = ''
    if (sourceTab.value === 'existing') {
      specId = pickedSpecId.value
      if (specId === null) {
        step1Error.value = t('apitest.specImport.pickSpecFirst')
        return
      }
      const row = specs.value.find((s) => s.id === specId)
      endpointCount = row?.endpoints?.length ?? 0
      specFormat = row?.specFormat ?? ''
    } else {
      specId = await parseFromUpload()
      if (specId === null) return
      endpointCount = 0
      specFormat = ''
    }
    const preview = await generateSpecPreview(specId)
    previewData.value = preview
    detectedNote.value = t('apitest.specImport.detected', {
      format: specFormat || 'OpenAPI 3',
      n: endpointCount > 0 ? endpointCount : preview.items.length,
    })
    initSelection(preview.items)
    step.value = 1
  } catch (e) {
    step1Error.value = bizErrorMsg(e, t('apitest.specImport.generateFail'))
  } finally {
    parsing.value = false
  }
}

const previewData = ref<ApiSpecPreviewView | null>(null)
const detectedNote = ref('')
const selected = ref<Set<string>>(new Set())
const keyword = ref('')
const expandedKey = ref<string | null>(null)


function initSelection(items: ApiSpecDraftItemView[]): void {
  const next = new Set<string>()
  for (const it of items) {
    if (it.confidence !== 'low') next.add(it.endpointKey)
  }
  selected.value = next
}

function toggleItem(key: string, on: boolean): void {
  const next = new Set(selected.value)
  if (on) next.add(key)
  else next.delete(key)
  selected.value = next
}

const allChecked = computed(() =>
  filteredGroups.value.some((g) => g.items.length > 0)
  && filteredGroups.value.every((g) => g.items.every((it) => selected.value.has(it.endpointKey))))

function toggleAll(): void {
  const items = filteredGroups.value.flatMap((g) => g.items)
  if (allChecked.value) {
    for (const it of items) toggleItem(it.endpointKey, false)
  } else {
    for (const it of items) toggleItem(it.endpointKey, true)
  }
}


function matches(it: ApiSpecDraftItemView, kw: string): boolean {
  if (!kw) return true
  const hay = `${it.method} ${it.path} ${it.title} ${it.description ?? ''}`.toLowerCase()
  return hay.includes(kw.toLowerCase())
}

const groups = computed(() => {
  const byTag = new Map<string, ApiSpecDraftItemView[]>()
  for (const it of previewData.value?.items ?? []) {
    const key = it.tags?.[0]?.trim() || t('apitest.specImport.groupUngrouped')
    const list = byTag.get(key) ?? []
    list.push(it)
    byTag.set(key, list)
  }
  return [...byTag.entries()].map(([tag, items]) => ({ tag, items }))
})

const filteredGroups = computed(() => {
  const kw = keyword.value.trim()
  return groups.value
    .map((g) => ({ tag: g.tag, items: g.items.filter((it) => matches(it, kw)) }))
    .filter((g) => g.items.length > 0)
})

const lowCount = computed(() =>
  (previewData.value?.items ?? []).filter((it) => it.confidence === 'low').length)

const checkedCount = computed(() => selected.value.size)

function assertionSummary(it: ApiSpecDraftItemView): string {
  const rows = safeParse<Array<Record<string, unknown>>>(it.assertionsJson, [])
  return rows.map((r) => `${r.type ?? '?'}${r.expect !== undefined ? ' → ' + String(r.expect) : ''}`).join('；')
}

function applyDisabledReason(): string {
  if (previewData.value === null) return t('apitest.specImport.emptyItems')
  if (previewData.value.items.length === 0) return t('apitest.specImport.emptyItems')
  if (checkedCount.value === 0) return t('apitest.specImport.pickItemsFirst')
  return ''
}

const applying = ref(false)
const applyResult = ref<SpecPreviewApplyView | null>(null)
const step2Error = ref('')

async function onApply(): Promise<void> {
  if (previewData.value === null) return
  step2Error.value = ''
  applying.value = true
  try {
    const keys = (previewData.value.items ?? [])
      .filter((it) => selected.value.has(it.endpointKey))
      .map((it) => it.endpointKey)
    const result = await applySpecPreview(previewData.value.id, keys)
    applyResult.value = result
    step.value = 2
    emit('imported', result)
  } catch (e) {
    step2Error.value = bizErrorMsg(e, t('apitest.specImport.applyFail'))
  } finally {
    applying.value = false
  }
}

function closeWizard(): void {
  emit('update:modelValue', false)
}


watch(sourceTab, (tab) => {
  if (tab === 'document' && docs.value.length === 0) void loadDocs()
})
watch(() => props.modelValue, (open) => {
  if (!open) return
  step.value = 0
  sourceTab.value = 'existing'
  pickedSpecId.value = null
  pasteText.value = ''
  urlText.value = ''
  docName.value = ''
  fileName.value = ''
  fileRef.value = null
  step1Error.value = ''
  step2Error.value = ''
  previewData.value = null
  detectedNote.value = ''
  selected.value = new Set()
  keyword.value = ''
  expandedKey.value = null
  applyResult.value = null
  closeSpecTail()
  dispatching.value = false
  extractNote.value = ''
  pickedDocId.value = null
  modelHint.value = ''
  void loadSpecs()
})
</script>

<template>
  <el-dialog
    :model-value="props.modelValue"
    :title="t('apitest.specImport.title')"
    width="960"
    draggable
    data-test="spec-import-dialog"
    @update:model-value="closeWizard"
  >
    <el-steps :active="step" finish-status="success" simple>
      <el-step :title="t('apitest.specImport.step1')" />
      <el-step :title="t('apitest.specImport.step2')" />
      <el-step :title="t('apitest.specImport.step3')" />
    </el-steps>

    
    <div v-if="step === 0" class="si-body">
      <!-- R62 C-face: live process window of the dispatched extraction
           (shared drawer component, fixed height, terminal auto-collapse) -->
      <TaskTailDrawer
        v-if="specTailOpen"
        :events="specTailEvents"
        :live="specTailRunning"
        :stalled="specTailStalled"
        :interrupted="specTailInterrupted"
        :heartbeat-seconds="tailHeartbeatSeconds"
        :first-frame-ms="tailFirstFrameMs"
        data-test="spec-import-tail"
      />
      <el-tabs v-model="sourceTab" data-test="spec-import-tabs">
        <el-tab-pane :label="t('apitest.specImport.tabExisting')" name="existing">
          <el-table border
            v-if="specs.length > 0"
            :data="specs"
            size="small"
            max-height="320"
            data-test="spec-import-spec-table"
            @current-change="(row: ApiSpecView | null) => { pickedSpecId = row ? row.id : null }"
            @row-click="(row: ApiSpecView) => { pickedSpecId = row.id }"
          >
            <el-table-column width="46">
              <template #default="{ row }">
                <el-radio :model-value="pickedSpecId ?? -1" :value="row.id">
                  <span />
                </el-radio>
              </template>
            </el-table-column>
            <el-table-column prop="name" :label="t('apitest.specImport.colName')" min-width="180" />
            <el-table-column prop="specVersion" :label="t('apitest.specImport.colVersion')" width="80">
              <template #default="{ row }">v{{ row.specVersion }}</template>
            </el-table-column>
            <el-table-column :label="t('apitest.specImport.colEndpoints')" width="100">
              <template #default="{ row }">{{ row.endpoints?.length ?? 0 }}</template>
            </el-table-column>
            <el-table-column prop="specFormat" :label="t('apitest.specImport.colFormat')" width="140" />
          </el-table>
          <p v-else class="si-hint">{{ t('apitest.specImport.noSpecs') }}</p>
        </el-tab-pane>
        <el-tab-pane :label="t('apitest.specImport.tabPaste')" name="paste">
          <el-input
            v-model="pasteText"
            type="textarea"
            :rows="8"
            data-test="spec-import-paste"
            :placeholder="t('apitest.specImport.pastePh')"
          />
        </el-tab-pane>
        <el-tab-pane :label="t('apitest.specImport.tabFile')" name="file">
          <div class="si-drop" data-test="spec-import-file-drop" @dragover.prevent @drop.prevent="onDropFile">
            <p>{{ fileName || t('apitest.specImport.fileDropHint') }}</p>
            <el-button size="small" data-test="spec-import-file-btn" @click="() => fileInputEl?.click()">
              {{ t('apitest.specImport.fileChoose') }}
            </el-button>
            <input
              ref="fileInputEl"
              type="file"
              accept=".json,.yaml,.yml,.txt"
              style="display: none"
              data-test="spec-import-file-input"
              @change="onPickFile"
            />
          </div>
        </el-tab-pane>
        <el-tab-pane :label="t('apitest.specImport.tabDocument')" name="document">
          <p class="si-hint" data-test="spec-import-doc-hint">{{ t('apitest.specImport.docTabHint') }}</p>
          <el-table border
            v-if="extractableDocs.length > 0"
            v-loading="docsLoading"
            :data="extractableDocs"
            size="small"
            max-height="280"
            data-test="spec-import-doc-table"
            @current-change="(row: DocumentView | null) => { pickedDocId = row ? row.id : null }"
            @row-click="(row: DocumentView) => { pickedDocId = row.id }"
          >
            <el-table-column width="46">
              <template #default="{ row }">
                <el-radio :model-value="pickedDocId ?? -1" :value="row.id">
                  <span />
                </el-radio>
              </template>
            </el-table-column>
            <el-table-column prop="name" :label="t('apitest.specImport.colDocName')" min-width="200" />
            <el-table-column :label="t('apitest.specImport.colDocChunks')" width="90">
              <template #default="{ row }">{{ row.chunkCount }}</template>
            </el-table-column>
            <el-table-column :label="t('apitest.specImport.colDocStatus')" width="110">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'">
                  {{ row.status === 1 ? t('document.statusDone') : t('document.statusExtracted') }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
          <p v-else class="si-hint" data-test="spec-import-doc-empty">{{ t('apitest.specImport.noDocs') }}</p>
          <div class="si-field">
            <!-- label must bind the native input (Issues panel: a bare <label>
                 is a form-association blocker — for/id pair, not visual text) -->
            <label for="spec-import-model-hint-input">{{ t('apitest.specImport.modelHint') }}</label>
            <el-input
              v-model="modelHint"
              id="spec-import-model-hint-input"
              data-test="spec-import-model-hint"
              :placeholder="t('apitest.specImport.modelHintPh')"
              style="max-width: 260px"
              clearable
            />
          </div>
          <div class="si-actions">
            <el-button
              type="primary"
              :loading="dispatching"
              :disabled="extracting"
              data-test="spec-import-extract-btn"
              @click="onExtract"
            >
              {{ t('apitest.specImport.extractBtn') }}
            </el-button>
          </div>
        </el-tab-pane>
        <el-tab-pane :label="t('apitest.specImport.tabUrl')" name="url">
          <p class="si-hint" data-test="spec-import-url-hint">{{ t('apitest.specImport.urlTabHint') }}</p>
          <el-input
            v-model="urlText"
            data-test="spec-import-url"
            :placeholder="t('apitest.specImport.urlPh')"
            clearable
          />
        </el-tab-pane>
      </el-tabs>
      <p v-if="extractNote" class="si-hint" data-test="spec-import-extract-note">{{ extractNote }}</p>
      <div v-if="sourceTab !== 'document'" class="si-field">
        <label for="spec-import-doc-name-input">{{ t('apitest.specImport.docName') }}</label>
        <el-input
          v-model="docName"
          id="spec-import-doc-name-input"
          data-test="spec-import-doc-name"
          :placeholder="t('apitest.specImport.docNamePh')"
          style="max-width: 420px"
        />
      </div>
      <p v-if="step1Error" class="si-error" data-test="spec-import-step1-error">{{ step1Error }}</p>
      <div v-if="sourceTab !== 'document'" class="si-actions">
        <el-button
          type="primary"
          :loading="parsing"
          data-test="spec-import-parse-btn"
          @click="onParse"
        >
          {{ t('apitest.specImport.parseBtn') }}
        </el-button>
      </div>
    </div>

    
    <div v-else-if="step === 1" class="si-body">
      <p class="si-hint" data-test="spec-import-detected">{{ detectedNote }}</p>
      <div class="si-toolbar">
        <el-checkbox
          :model-value="allChecked"
          data-test="spec-import-check-all"
          @change="toggleAll"
        >
          {{ t('apitest.specImport.checkAll') }}
        </el-checkbox>
        <el-input
          v-model="keyword"
          data-test="spec-import-search"
          :placeholder="t('apitest.specImport.searchPh')"
          clearable
          style="width: 240px"
        />
        <span class="si-stat" data-test="spec-import-stat">
          {{ t('apitest.specImport.statTotal', { n: previewData?.items?.length ?? 0 }) }}
          · {{ t('apitest.specImport.statChecked', { n: checkedCount }) }}
          <template v-if="lowCount > 0"> · {{ t('apitest.specImport.statLow', { n: lowCount }) }}</template>
        </span>
      </div>
      <div class="si-cards">
        <div v-for="g in filteredGroups" :key="g.tag" class="si-group">
          <h4 class="si-group-tag">{{ g.tag }}</h4>
          <div v-for="it in g.items" :key="it.endpointKey" class="si-card" data-test="spec-import-card">
            <div class="si-card-head">
              <el-checkbox
                :model-value="selected.has(it.endpointKey)"
                :data-test="`spec-import-check-${it.endpointKey.replace(/[^a-zA-Z0-9]/g, '-')}`"
                @change="(v: boolean) => toggleItem(it.endpointKey, v)"
              />
              <span class="si-method">{{ it.method }}</span>
              <span class="si-path">{{ it.path }}</span>
              <span class="si-title">{{ it.title }}</span>
              <el-tag v-if="it.confidence === 'low'" type="warning" size="small" data-test="spec-import-low-tag">
                {{ t('apitest.specImport.confidenceLowTag') }}
              </el-tag>
              <el-button link size="small" data-test="spec-import-expand-btn" @click="expandedKey = expandedKey === it.endpointKey ? null : it.endpointKey">
                {{ expandedKey === it.endpointKey ? t('apitest.specImport.collapseDetail') : t('apitest.specImport.expandDetail') }}
              </el-button>
            </div>
            <div v-if="expandedKey === it.endpointKey" class="si-card-detail">
              <p><b>{{ t('apitest.specImport.paramsTitle') }}</b>
                {{ (safeParse<unknown[]>(it.paramsSkeletonJson, []).length) }}
                {{ t('apitest.specImport.paramsUnit') }}</p>
              <p><b>{{ t('apitest.specImport.assertsTitle') }}</b> {{ assertionSummary(it) || '—' }}</p>
              <p v-if="safeParse<unknown[]>(it.negativeAssertionsJson, []).length > 0">
                <b>{{ t('apitest.specImport.negativesTitle') }}</b>
                {{ safeParse<Array<Record<string, unknown>>>(it.negativeAssertionsJson, []).map((r) => String(r.expect ?? '')).join('/') }}
              </p>
              <p v-if="it.requestContentType"><b>{{ t('apitest.specImport.bodyTitle') }}</b> {{ it.bodySkeleton }}</p>
              <p v-if="it.securityRefs?.length"><b>{{ t('apitest.specImport.authTitle') }}</b> {{ it.securityRefs.join(', ') }}</p>
            </div>
          </div>
        </div>
        <p v-if="filteredGroups.length === 0" class="si-hint">{{ t('apitest.specImport.noMatch') }}</p>
      </div>
      <p v-if="step2Error" class="si-error" data-test="spec-import-step2-error">{{ step2Error }}</p>
      <div class="si-actions">
        <el-button data-test="spec-import-back-btn" @click="step = 0">{{ t('apitest.specImport.back') }}</el-button>
        <el-tooltip
          :content="applyDisabledReason()"
          :disabled="!applyDisabledReason()"
        >
          <el-button
            type="primary"
            :loading="applying"
            :disabled="!!applyDisabledReason()"
            data-test="spec-import-apply-btn"
            @click="onApply"
          >
            {{ t('apitest.specImport.applyBtn', { n: checkedCount }) }}
          </el-button>
        </el-tooltip>
      </div>
    </div>

    
    <div v-else class="si-body si-done">
      <p data-test="spec-import-done-created">
        {{ t('apitest.specImport.doneCreated', { n: applyResult?.createdRequests ?? 0 }) }}
      </p>
      <p v-if="(applyResult?.skipped ?? 0) > 0" data-test="spec-import-done-skipped">
        {{ t('apitest.specImport.doneSkipped', { n: applyResult?.skipped }) }}
      </p>
      <p class="si-hint">{{ t('apitest.specImport.doneRefresh') }}</p>
      <p class="si-hint">{{ t('apitest.specImport.upgradeNote') }}</p>
      <div class="si-actions">
        <el-button type="primary" data-test="spec-import-close-btn" @click="closeWizard">
          {{ t('apitest.specImport.backToList') }}
        </el-button>
      </div>
    </div>
  </el-dialog>
</template>

<style scoped>
.si-body {
  margin-top: 16px;
}

.si-hint {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.si-error {
  font-size: 13px;
  color: var(--el-color-danger);
}

.si-field {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
}

.si-field label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  white-space: nowrap;
}

.si-drop {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 24px;
  border: 1px dashed var(--el-border-color);
  border-radius: 8px;
}

.si-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}

.si-stat {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.si-cards {
  max-height: 420px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.si-group-tag {
  margin: 4px 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.si-card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 8px 12px;
}

.si-card-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.si-method {
  font-weight: 600;
  font-size: 13px;
}

.si-path {
  font-size: 13px;
  font-family: monospace;
}

.si-title {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.si-card-detail {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px dashed var(--el-border-color-lighter);
  font-size: 12px;
}

.si-card-detail p {
  margin: 4px 0;
}

.si-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 16px;
}
</style>
