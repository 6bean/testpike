<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import {
  fetchSpecUpgradePlan,
  importStubsFromSpec,
  listSpaces,
  markRemovedUpgradePlan,
  previewStubDraftsAdvised,
  type MockSpaceItem,
  type MockSpecUpgradePlan,
  type MockStubDraftAdviceItem,
  type MockStubImportResult,
} from '@/api/mock'
import {
  DRAFT_UNIT_HEIGHT_PX,
  DRAFT_VISIBLE_UNITS,
  draftWindowRange,
  flattenDraftUnits,
} from '@/composables/mockDraftWindow'
import { listApiSpecs, uploadApiSpec, type ApiSpecView } from '@/api/apiSpec'
import { bizErrorMsg } from '@/utils/bizError'
import { mockSpaceLabel } from '@/utils/mockSpaceLabel'

/**
 * Contract-direct stub wizard — channel A: an OpenAPI contract becomes mock
 * rules with zero LLM tokens through the core deterministic mapper.
 * Shared component with two mount points (API-test page + mock space
 * workbench docs tab) — one file, no fork (mount contract test in
 * tests/ui/mockStubWizardShared.test.ts).
 * Step 1 offers both contract sources: upload a new .json/.yaml document
 * (lands in tp_api_spec through the spec-domain upload single source, so the
 * version row + audit + later diff surfaces all keep working) or pick an
 * existing contract version row. When mounted from a space workbench the
 * destination space arrives via presetSpaceId and the space picker collapses
 * to a read-only echo — the user picks the contract, the space is where they
 * already are.
 * Steps over the admin doors: pick contract -> GET stub-drafts preview (a
 * proposal, persists nothing) read together with GET upgrade-plan; when the
 * space already has rules from a previous contract version a difference
 * step is inserted (three named sections + the disable/stale-flag door),
 * otherwise the wizard jumps straight to per-endpoint checkbox selection ->
 * POST import-from-spec (each draft goes through the normal stub save
 * chain; same method+path already covered comes back skipped, so re-import
 * is idempotent and the receipt step reports both counts). The wizard never
 * rewrites a proposal and never overwrites a rule body.
 */

const props = withDefaults(defineProps<{
  modelValue: boolean
  projectId: number | null
  presetSpaceId?: number | null
  presetSpaceName?: string
}>(), { presetSpaceId: null, presetSpaceName: '' })

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
}>()

const { t } = useI18n()
const router = useRouter()

const step = ref(0)

import { createLateFrameNotice, loadPreviewFirstFrameConfig, previewFirstFrameMs } from '@/composables/previewFirstFrameNotice'

// ---------- step 1: contract + destination space ----------
const specs = ref<ApiSpecView[]>([])
const specsLoading = ref(false)
const pickedSpecId = ref<number | null>(null)
const spaces = ref<MockSpaceItem[]>([])
const spacesLoading = ref(false)
const pickedSpaceId = ref<number | null>(null)
const step1Error = ref('')
const previewing = ref(false)
// honest waiting line when a large contract preview passes the budget
const firstFrameNotice = createLateFrameNotice(() => previewFirstFrameMs.value)

// ---------- step 1a: contract source choice (upload vs existing) ----------
// Upload runs through the spec-domain single source (same door the contract
// library uses): the version row lands in tp_api_spec, so history, audit and
// the later upgrade-diff surface all keep working — no second parser here.
const sourceMode = ref<'existing' | 'upload'>('existing')
const fileInput = ref<HTMLInputElement | null>(null)
const pickedFile = ref<File | null>(null)
const uploadName = ref('')
const uploading = ref(false)
const uploadError = ref('')
const uploadDone = ref('')

// A manual source switch voids the previous upload receipt, so the persisted
// hint never goes stale against a fresh file choice. Programmatic switches
// (the auto-jump after a successful upload) do not emit this event.
function onSourceModeChange(): void {
  uploadDone.value = ''
}

function onFileChange(ev: Event): void {
  const f = (ev.target as HTMLInputElement).files?.[0] ?? null
  pickedFile.value = f
  uploadError.value = ''
  uploadDone.value = ''
  if (f && !uploadName.value.trim()) {
    uploadName.value = f.name.replace(/\.(json|ya?ml)$/i, '')
  }
}

async function onUploadSpec(): Promise<void> {
  if (props.projectId === null || !pickedFile.value) return
  uploading.value = true
  uploadError.value = ''
  uploadDone.value = ''
  try {
    const saved = await uploadApiSpec(
      props.projectId,
      uploadName.value.trim() || pickedFile.value.name,
      pickedFile.value,
    )
    // The new version row shows up pre-selected in the existing list — the
    // upload is not a side channel, it joins the audited contract list.
    specs.value = [saved, ...specs.value.filter((s) => s.id !== saved.id)]
    pickedSpecId.value = saved.id
    sourceMode.value = 'existing'
    uploadDone.value = t('apitest.stubImport.uploadedPicked', { n: saved.endpoints?.length ?? 0 })
    pickedFile.value = null
  } catch (e) {
    uploadError.value = bizErrorMsg(e, t('apitest.stubImport.uploadFail'))
  } finally {
    uploading.value = false
  }
}

async function loadStep1(): Promise<void> {
  step1Error.value = ''
  if (props.projectId === null) return
  specsLoading.value = true
  spacesLoading.value = true
  try {
    const page = await listApiSpecs({ projectId: props.projectId, page: 1, size: 50 })
    specs.value = page.list
  } catch (e) {
    step1Error.value = bizErrorMsg(e, t('apitest.stubImport.loadSpecsFail'))
  } finally {
    specsLoading.value = false
  }
  try {
    spaces.value = await listSpaces(props.projectId)
  } catch (e) {
    step1Error.value = bizErrorMsg(e, t('apitest.stubImport.loadSpacesFail'))
  } finally {
    spacesLoading.value = false
  }
}

/**
 * Destination space name for the read-only echo in steps 2/3: the host-provided
 * space wins (workbench mount), otherwise the label of the row picked in step 1.
 * The write target must stay visible at the confirm and receipt steps — hiding
 * it there forces the user to remember a choice made one step back.
 */
const targetSpaceName = computed(() => {
  if (props.presetSpaceName) return props.presetSpaceName
  const s = spaces.value.find((x) => x.id === pickedSpaceId.value)
  return s ? mockSpaceLabel(s) : ''
})

/** Space status in plain words — only a published space answers real traffic. */
function spaceStatusLabel(status: number | null): string {
  if (status === 1) return t('apitest.stubImport.spacePublished')
  if (status === 2) return t('apitest.stubImport.spaceRetired')
  return t('apitest.stubImport.spaceDraft')
}

/** Honest reason the preview button stays disabled (shown as tooltip text). */
function step1DisabledReason(): string {
  if (sourceMode.value === 'upload' && !pickedSpecId.value) return t('apitest.stubImport.uploadSpecFirst')
  if (pickedSpecId.value === null) return t('apitest.stubImport.pickSpecFirst')
  if (props.presetSpaceId === null && pickedSpaceId.value === null) return t('apitest.stubImport.pickSpaceFirst')
  return ''
}

// ---------- step 2: preview + checkbox selection ----------
const drafts = ref<MockStubDraftAdviceItem[]>([])
const selected = ref<Set<string>>(new Set())
const keyword = ref('')
const step2Error = ref('')
const applying = ref(false)

// Worst-scale posture (500-endpoint contract): the card area is a fixed
// viewport and only a window of units is mounted, so the DOM never grows
// with the draft count. Group collapse removes a bucket's rows from the
// unit stream; the unchecked review list is opt-in and capped.
const collapsedGroups = ref<Set<string>>(new Set())
const cardsScrollPx = ref(0)
const uncheckedShown = ref(false)
const UNCHECKED_REVIEW_CAP = 200
const cardsViewportPx = DRAFT_VISIBLE_UNITS * DRAFT_UNIT_HEIGHT_PX

const filteredDrafts = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return drafts.value
  return drafts.value.filter(
    (d) =>
      d.name.toLowerCase().includes(kw) ||
      d.pathPattern.toLowerCase().includes(kw) ||
      d.method.toLowerCase().includes(kw),
  )
})

const draftUnits = computed(() => flattenDraftUnits(filteredDrafts.value, collapsedGroups.value))
const cardsWindow = computed(() => draftWindowRange(cardsScrollPx.value, draftUnits.value.length))
const windowedUnits = computed(() => {
  const w = cardsWindow.value
  return draftUnits.value.slice(w.start, w.end).map((u) =>
    u.kind === 'group'
      ? { kind: 'group' as const, key: u.key, count: u.count, collapsed: collapsedGroups.value.has(u.key) }
      : { kind: 'draft' as const, key: u.row.endpointKey, row: u.row },
  )
})

function onCardsScroll(e: Event): void {
  cardsScrollPx.value = (e.target as HTMLElement).scrollTop
}

function toggleGroup(key: string): void {
  const next = new Set(collapsedGroups.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  collapsedGroups.value = next
  cardsScrollPx.value = 0
}

const checkedCount = computed(() => selected.value.size)
const uncheckedKeys = computed(() => drafts.value.filter((d) => !selected.value.has(d.endpointKey)).map((d) => d.endpointKey))
const uncheckedShownKeys = computed(() => uncheckedKeys.value.slice(0, UNCHECKED_REVIEW_CAP))
const allChecked = computed(
  () => filteredDrafts.value.length > 0 && filteredDrafts.value.every((d) => selected.value.has(d.endpointKey)),
)

// ---------- difference step (contract upgrade): plan beside the preview ----------
const plan = ref<MockSpecUpgradePlan | null>(null)
const marking = ref(false)
const markReceipt = ref('')
const markError = ref('')

const addedRows = computed(() => plan.value?.added ?? [])
const changedRows = computed(() => plan.value?.changed ?? [])
const removedRows = computed(() => plan.value?.removed ?? [])

/**
 * The difference step earns its place only when there IS a previous
 * contract version AND the plan names at least one row — a first-version
 * contract, or a re-import whose diff is empty, jumps straight to the
 * selection step so the rail never shows a hollow step.
 */
const diffShown = computed(() =>
  plan.value !== null &&
  plan.value.hasPrevious &&
  addedRows.value.length + changedRows.value.length + removedRows.value.length > 0,
)

function applyDraftSelection(): void {
  // Default = everything checked: the contract is the trusted source here;
  // the checkbox gate still means nothing writes without a human click.
  selected.value = new Set(drafts.value.map((d) => d.endpointKey))
}

async function onMarkRemoved(): Promise<void> {
  if (pickedSpaceId.value === null || pickedSpecId.value === null) return
  marking.value = true
  markError.value = ''
  try {
    const n = await markRemovedUpgradePlan(pickedSpaceId.value, pickedSpecId.value)
    markReceipt.value = n > 0
      ? t('apitest.stubImport.markReceiptDone', { n })
      : t('apitest.stubImport.markReceiptNone')
  } catch (e) {
    markError.value = bizErrorMsg(e, t('apitest.stubImport.markRemovedFail'))
  } finally {
    marking.value = false
  }
}

async function onPreview(): Promise<void> {
  if (pickedSpecId.value === null || pickedSpaceId.value === null) return
  previewing.value = true
  void loadPreviewFirstFrameConfig()
  firstFrameNotice.arm()
  step1Error.value = ''
  try {
    const [d, p] = await Promise.all([
      previewStubDraftsAdvised(pickedSpecId.value, pickedSpaceId.value),
      fetchSpecUpgradePlan(pickedSpaceId.value, pickedSpecId.value),
    ])
    drafts.value = d
    plan.value = p
    collapsedGroups.value = new Set()
    uncheckedShown.value = false
    cardsScrollPx.value = 0
    applyDraftSelection()
    markReceipt.value = ''
    markError.value = ''
    step2Error.value = ''
    step.value = diffShown.value ? 1 : 2
  } catch (e) {
    step1Error.value = bizErrorMsg(e, t('apitest.stubImport.previewFail'))
  } finally {
    firstFrameNotice.disarm()
    previewing.value = false
  }
}

function toggleAll(v: boolean): void {
  const next = new Set(selected.value)
  for (const d of filteredDrafts.value) {
    if (v) next.add(d.endpointKey)
    else next.delete(d.endpointKey)
  }
  selected.value = next
}

function toggleItem(key: string, v: boolean): void {
  const next = new Set(selected.value)
  if (v) next.add(key)
  else next.delete(key)
  selected.value = next
}

/**
 * Selection = the rows core's pre-adjudication flagged (defaultChecked),
 * within the current filter. The flag is read, never re-decided here; rows
 * served without advice simply do not join the AI-only set.
 */
function selectOnlyAi(): void {
  const next = new Set(selected.value)
  for (const d of filteredDrafts.value) {
    if (d.defaultChecked === true) next.add(d.endpointKey)
    else next.delete(d.endpointKey)
  }
  selected.value = next
}

/** Invert within the current filter; rows outside the filter keep their state. */
function invertSelection(): void {
  const next = new Set(selected.value)
  for (const d of filteredDrafts.value) {
    if (next.has(d.endpointKey)) next.delete(d.endpointKey)
    else next.add(d.endpointKey)
  }
  selected.value = next
}

function step2DisabledReason(): string {
  if (checkedCount.value === 0) return t('apitest.stubImport.pickDraftsFirst')
  return ''
}

// ---------- step 3: outcome (receipt counts: created / skipped / failed) ----------
const result = ref<MockStubImportResult | null>(null)
// Drafts sent to the import door; the third receipt number is the honest
// remainder (returned created + skipped never exceeds what was sent).
const importedTotal = ref(0)

async function onImport(): Promise<void> {
  if (pickedSpaceId.value === null || pickedSpecId.value === null) return
  const chosen = drafts.value.filter((d) => selected.value.has(d.endpointKey))
  if (chosen.length === 0) return
  applying.value = true
  step2Error.value = ''
  try {
    importedTotal.value = chosen.length
    result.value = await importStubsFromSpec(pickedSpaceId.value, pickedSpecId.value, chosen)
    step.value = 3
  } catch (e) {
    step2Error.value = bizErrorMsg(e, t('apitest.stubImport.importFail'))
  } finally {
    applying.value = false
  }
}

const failedCount = computed(() => {
  if (result.value === null) return 0
  const created = result.value.createdStubIds?.length ?? 0
  const skipped = result.value.skippedEndpointKeys?.length ?? 0
  return Math.max(0, importedTotal.value - created - skipped)
})

// The door files created ids in the sent-draft order, so the i-th accepted
// (not-skipped) draft maps to the i-th created id — the first browser-tryable
// rule is the first GET in that sequence. A POST/PUT import honestly shows no
// try button instead of a dead one (the docs page guards GET-only tries).
const firstTryableStubId = computed<number | null>(() => {
  const ids = result.value?.createdStubIds ?? []
  if (ids.length === 0) return null
  const skipped = new Set(result.value?.skippedEndpointKeys ?? [])
  const accepted = drafts.value.filter(
    (d) => selected.value.has(d.endpointKey) && !skipped.has(d.endpointKey),
  )
  const i = accepted.findIndex((d) => d.method.toUpperCase() === 'GET')
  return i >= 0 && i < ids.length ? ids[i] : null
})

/** Open the endpoint list with the first tryable rule queued for a live try. */
function tryFirstStub(): void {
  if (firstTryableStubId.value === null || pickedSpaceId.value === null || props.projectId === null) return
  void router.push({
    path: `/mock/spaces/${pickedSpaceId.value}`,
    query: { projectId: props.projectId, tab: 'docs', tryStub: String(firstTryableStubId.value) },
  })
  closeWizard()
}

/** Return to the space workbench docs tab with the destination space in hand. */
function viewInDocs(): void {
  if (props.projectId === null || pickedSpaceId.value === null) return
  void router.push({
    path: `/mock/spaces/${pickedSpaceId.value}`, query: { projectId: props.projectId, tab: 'docs' },
  })
  closeWizard()
}

function closeWizard(): void {
  emit('update:modelValue', false)
}

// Fresh state on every open; step 1 lists are re-read (a contract or space
// may have been added since the last visit).
watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    step.value = 0
    drafts.value = []
    selected.value = new Set()
    result.value = null
    importedTotal.value = 0
    plan.value = null
    markReceipt.value = ''
    markError.value = ''
    keyword.value = ''
    pickedSpecId.value = null
    sourceMode.value = 'existing'
    pickedFile.value = null
    uploadName.value = ''
    uploadError.value = ''
    uploadDone.value = ''
    // Host-provided space (workbench mount) is the destination from the start.
    pickedSpaceId.value = props.presetSpaceId ?? null
    void loadStep1()
  },
)
</script>

<template>
  <el-dialog
    :model-value="props.modelValue"
    :title="t('apitest.stubImport.title')"
    :class="step >= 1 ? 'tp-dialog--lg' : 'tp-dialog--md'"
    data-test="stub-direct-dialog"
    @update:model-value="(v: boolean) => { if (!v) closeWizard() }"
  >
    <!-- The receipt step is shown only after the import finished, so the rail
         closes at active=3: every step takes its done state instead of the
         last circle staying hollow while the text reports completion. -->
    <el-steps :active="step === 3 ? (diffShown ? 4 : 3) : step" finish-status="success" simple>
      <el-step :title="t('apitest.stubImport.step1')" />
      <el-step v-if="diffShown" :title="t('apitest.stubImport.diffStep')" />
      <el-step :title="t('apitest.stubImport.step2')" />
      <el-step :title="t('apitest.stubImport.step3')" />
    </el-steps>

    <!-- step 1: contract + destination space -->
    <div v-if="step === 0" class="sd-body">
      <p class="sd-hint" data-test="stub-direct-intro">{{ t('apitest.stubImport.intro') }}</p>
      <h4 class="sd-block-title">{{ t('apitest.stubImport.sourceBlock') }}</h4>
      <el-radio-group v-model="sourceMode" data-test="stub-direct-source-radio" @change="onSourceModeChange">
        <el-radio value="existing">{{ t('apitest.stubImport.sourceExisting') }}</el-radio>
        <el-radio value="upload">{{ t('apitest.stubImport.sourceUpload') }}</el-radio>
      </el-radio-group>
      <div v-if="sourceMode === 'upload'" class="sd-upload" data-test="stub-direct-upload-panel">
        <p class="sd-hint">{{ t('apitest.stubImport.uploadHint') }}</p>
        <div class="sd-upload-row">
          <!-- The native file widget labels itself in the browser's own
               language, so the app draws the pick button instead and keeps
               the real input hidden in the DOM — automation (and the button
               click) still feed it directly. -->
          <input
            ref="fileInput"
            type="file"
            accept=".json,.yaml,.yml"
            class="sd-file-hidden"
            data-test="stub-direct-file-input"
            @change="onFileChange"
          >
          <el-button data-test="stub-direct-pick-file" @click="fileInput?.click()">
            {{ t('apitest.stubImport.pickFileBtn') }}
          </el-button>
          <span v-if="pickedFile" class="sd-file-name" data-test="stub-direct-file-name">
            {{ t('apitest.stubImport.filePicked', { name: pickedFile.name }) }}
          </span>
          <span v-else class="sd-hint" data-test="stub-direct-file-none">
            {{ t('apitest.stubImport.noFileHint') }}
          </span>
          <el-input
            v-model="uploadName"
            :placeholder="t('apitest.stubImport.uploadNamePh')"
            style="width: 220px"
            data-test="stub-direct-upload-name"
          />
          <el-button
            type="primary"
            plain
            :loading="uploading"
            :disabled="!pickedFile"
            data-test="stub-direct-upload-btn"
            @click="onUploadSpec"
          >
            {{ t('apitest.stubImport.uploadBtn') }}
          </el-button>
        </div>
        <p v-if="uploadError" class="sd-error" data-test="stub-direct-upload-error">{{ uploadError }}</p>
      </div>
      <!-- Success receipt lives OUTSIDE the mode-conditional panel: a finished
           upload auto-jumps back to the existing-contract view, so a hint kept
           inside the upload panel would be unmounted the moment it is set and
           the user would get no visible feedback for the write they triggered. -->
      <p v-if="uploadDone" class="sd-hint" data-test="stub-direct-upload-done">{{ uploadDone }}</p>
      <h4 v-if="sourceMode === 'existing'" class="sd-block-title">{{ t('apitest.stubImport.specBlock') }}</h4>
      <el-table border
        v-if="sourceMode === 'existing' && specs.length > 0"
        v-loading="specsLoading"
        :data="specs"
        size="small"
        max-height="240"
        data-test="stub-direct-spec-table"
        @row-click="(row: ApiSpecView) => { pickedSpecId = row.id }"
      >
        <el-table-column width="46">
          <template #default="{ row }">
            <el-radio :model-value="pickedSpecId ?? -1" :value="row.id" label="">
              <span />
            </el-radio>
          </template>
        </el-table-column>
        <el-table-column prop="name" :label="t('apitest.specImport.colName')" />
        <el-table-column :label="t('apitest.specImport.colVersion')" width="90">
          <template #default="{ row }">v{{ row.specVersion }}</template>
        </el-table-column>
        <el-table-column :label="t('apitest.specImport.colEndpoints')" width="90">
          <template #default="{ row }">{{ row.endpoints?.length ?? 0 }}</template>
        </el-table-column>
        <el-table-column :label="t('apitest.specImport.colFormat')" width="110">
          <template #default="{ row }">{{ row.specFormat }}</template>
        </el-table-column>
      </el-table>
      <p v-else-if="sourceMode === 'existing' && !specsLoading" class="sd-hint" data-test="stub-direct-no-specs">
        {{ t('apitest.stubImport.noSpecs') }}
      </p>

      <template v-if="presetSpaceId === null">
        <h4 class="sd-block-title">{{ t('apitest.stubImport.spaceBlock') }}</h4>
        <el-select
          v-model="pickedSpaceId"
          filterable
          :placeholder="t('apitest.stubImport.spacePlaceholder')"
          :loading="spacesLoading"
          style="width: 320px"
          data-test="stub-direct-space-select"
        >
          <el-option v-for="s in spaces" :key="s.id" :value="s.id" :label="mockSpaceLabel(s)">
            <span class="sd-option-line">
              <span>{{ mockSpaceLabel(s) }}</span>
              <span class="sd-option-status">{{ spaceStatusLabel(s.status) }}</span>
            </span>
          </el-option>
        </el-select>
        <p v-if="pickedSpaceId !== null && spaces.find((s) => s.id === pickedSpaceId)?.status !== 1"
           class="sd-hint" data-test="stub-direct-space-note">
          {{ t('apitest.stubImport.draftSpaceNote') }}
        </p>
      </template>
      <p v-else class="sd-hint" data-test="stub-direct-fixed-space">
        {{ t('apitest.stubImport.fixedSpaceNote', { name: presetSpaceName }) }}
      </p>
      <p v-if="step1Error" class="sd-error" data-test="stub-direct-step1-error">{{ step1Error }}</p>
      <div class="sd-actions">
        <el-tooltip :content="step1DisabledReason()" :disabled="!step1DisabledReason()">
          <el-button
            type="primary"
            :loading="previewing"
            :disabled="!!step1DisabledReason()"
            data-test="stub-direct-preview-btn"
            @click="onPreview"
          >
            {{ t('apitest.stubImport.previewBtn') }}
          </el-button>
        </el-tooltip>
        <p
          v-if="firstFrameNotice.late.value"
          data-test="stub-preview-late-notice"
          class="sd-hint"
        >
          {{ t('apitest.stubImport.previewLateNotice') }}
        </p>
      </div>
    </div>

    <!-- difference step (contract upgrade, read-only plan): three named
         sections — added rows are import-ready proposals, changed rows
         point at the endpoint list for manual edits (rule bodies are never
         overwritten here), removed rows offer the disable + stale-flag
         door. Nothing writes until a human clicks. -->
    <div v-else-if="step === 1" class="sd-body" data-test="stub-direct-diff-step">
      <p class="sd-hint">
        {{ t('apitest.stubImport.diffIntro', { a: addedRows.length, c: changedRows.length, r: removedRows.length }) }}
      </p>
      <template v-if="addedRows.length > 0">
        <h4 class="sd-block-title">{{ t('apitest.stubImport.diffAdded') }}</h4>
        <div data-test="stub-direct-diff-added">
          <div v-for="a in addedRows" :key="a.endpointKey" class="sd-card">
            <div class="sd-card-head">
              <span class="sd-method">{{ a.method }}</span>
              <span class="sd-path">{{ a.pathPattern }}</span>
              <span class="sd-name">{{ a.name }}</span>
              <el-tag size="small" type="success">{{ t('apitest.stubImport.advCreate') }}</el-tag>
            </div>
          </div>
        </div>
      </template>
      <template v-if="changedRows.length > 0">
        <h4 class="sd-block-title">{{ t('apitest.stubImport.diffChanged') }}</h4>
        <div data-test="stub-direct-diff-changed">
          <div v-for="c in changedRows" :key="c.stubId" class="sd-card">
            <div class="sd-card-head">
              <span class="sd-method">{{ c.method }}</span>
              <span class="sd-path">{{ c.pathPattern }}</span>
              <span class="sd-name">{{ c.name }}</span>
              <el-tag size="small" type="warning">{{ t('apitest.stubImport.advUpdateBody') }}</el-tag>
            </div>
          </div>
          <p class="sd-hint">{{ t('apitest.stubImport.changedHint') }}</p>
        </div>
      </template>
      <template v-if="removedRows.length > 0">
        <h4 class="sd-block-title">{{ t('apitest.stubImport.diffRemoved') }}</h4>
        <div data-test="stub-direct-diff-removed">
          <div v-for="r in removedRows" :key="r.stubId" class="sd-card">
            <div class="sd-card-head">
              <span class="sd-method">{{ r.method }}</span>
              <span class="sd-path">{{ r.pathPattern }}</span>
              <span class="sd-name">{{ r.name }}</span>
              <el-tag size="small" type="danger">{{ t('mockEndpoints.staleTag') }}</el-tag>
              <el-tag size="small" type="info">{{ t('apitest.stubImport.advDisable') }}</el-tag>
            </div>
          </div>
        </div>
        <div class="sd-actions">
          <el-button
            :loading="marking"
            data-test="stub-direct-mark-removed"
            @click="onMarkRemoved"
          >
            {{ t('apitest.stubImport.markRemovedBtn') }}
          </el-button>
        </div>
        <p v-if="markReceipt" class="sd-echo" data-test="stub-direct-mark-removed-receipt">{{ markReceipt }}</p>
        <p v-if="markError" class="sd-error" data-test="stub-direct-mark-removed-error">{{ markError }}</p>
      </template>
      <div class="sd-actions">
        <el-button data-test="stub-direct-diff-back" @click="step = 0">{{ t('apitest.specImport.back') }}</el-button>
        <el-button
          type="primary"
          data-test="stub-direct-diff-continue"
          @click="applyDraftSelection(); step = 2"
        >
          {{ t('apitest.stubImport.diffContinue') }}
        </el-button>
      </div>
    </div>

    <!-- selection step: preview + checkbox selection (nothing written yet) -->
    <div v-else-if="step === 2" class="sd-body">
      <p class="sd-hint" data-test="stub-direct-detected">
        {{ t('apitest.stubImport.detected', { n: drafts.length }) }}
      </p>
      <p v-if="targetSpaceName" class="sd-echo" data-test="stub-direct-target-space-echo">
        {{ t('apitest.stubImport.targetSpaceEcho', { name: targetSpaceName }) }}
      </p>
      <div class="sd-toolbar">
        <el-checkbox
          :model-value="allChecked"
          data-test="stub-direct-check-all"
          @change="(v: any) => toggleAll(Boolean(v))"
        >
          {{ t('apitest.stubImport.checkAll') }}
        </el-checkbox>
        <el-input
          v-model="keyword"
          data-test="stub-direct-search"
          :placeholder="t('apitest.stubImport.searchPh')"
          clearable
          style="width: 240px"
        />
        <span class="sd-stat" data-test="stub-direct-stat">
          {{ t('apitest.stubImport.statChecked', { total: drafts.length, checked: checkedCount }) }}
        </span>
        <el-button size="small" data-test="stub-direct-only-ai" @click="selectOnlyAi">
          {{ t('apitest.stubImport.onlyAi') }}
        </el-button>
        <el-button size="small" data-test="stub-direct-invert" @click="invertSelection">
          {{ t('apitest.stubImport.invert') }}
        </el-button>
        <el-button
          link
          type="primary"
          size="small"
          data-test="stub-direct-unchecked-toggle"
          @click="uncheckedShown = !uncheckedShown"
        >
          {{ t('apitest.stubImport.uncheckedToggle', { n: uncheckedKeys.length }) }}
        </el-button>
      </div>
      <div v-if="uncheckedShown" class="sd-unchecked" data-test="stub-direct-unchecked-list">
        <p v-if="uncheckedKeys.length === 0" class="sd-hint">{{ t('apitest.stubImport.uncheckedEmpty') }}</p>
        <ul v-else class="sd-unchecked-ul">
          <li v-for="k in uncheckedShownKeys" :key="k" class="sd-unchecked-item">{{ k }}</li>
        </ul>
      </div>
      <div
        class="sd-cards"
        data-test="stub-direct-window"
        :style="{ height: cardsViewportPx + 'px' }"
        @scroll.passive="onCardsScroll"
      >
        <div :style="{ height: cardsWindow.padTopPx + 'px' }"></div>
        <template v-for="u in windowedUnits" :key="u.kind === 'group' ? 'g:' + u.key : u.row.endpointKey">
          <div
            v-if="u.kind === 'group'"
            class="sd-group-head"
            data-test="stub-direct-group-toggle"
            @click="toggleGroup(u.key)"
          >
            <span class="sd-group-name">{{ u.key || t('apitest.stubImport.groupRoot') }}</span>
            <span class="sd-group-count">{{ t('apitest.stubImport.groupCount', { n: u.count }) }}</span>
            <span class="sd-group-caret">{{ u.collapsed ? '▸' : '▾' }}</span>
          </div>
          <div v-else class="sd-card" data-test="stub-direct-card">
            <div class="sd-card-head">
              <el-checkbox
                :model-value="selected.has(u.row.endpointKey)"
                :data-test="`stub-direct-check-${u.row.endpointKey.replace(/[^a-zA-Z0-9]/g, '-')}`"
                @change="(v: any) => toggleItem(u.row.endpointKey, Boolean(v))"
              />
              <span class="sd-method">{{ u.row.method }}</span>
              <span class="sd-path">{{ u.row.pathPattern }}</span>
              <span class="sd-name">{{ u.row.name }}</span>
              <el-tag size="small" type="info" data-test="stub-direct-source-tag">
                {{ t('apitest.stubImport.sourceTag') }}
              </el-tag>
            </div>
          </div>
        </template>
        <div :style="{ height: cardsWindow.padBottomPx + 'px' }"></div>
        <p v-if="filteredDrafts.length === 0" class="sd-hint">{{ t('apitest.stubImport.noMatch') }}</p>
      </div>
      <p v-if="step2Error" class="sd-error" data-test="stub-direct-step2-error">{{ step2Error }}</p>
      <div class="sd-actions">
        <el-button data-test="stub-direct-back-btn" @click="step = diffShown ? 1 : 0">{{ t('apitest.specImport.back') }}</el-button>
        <el-tooltip :content="step2DisabledReason()" :disabled="!step2DisabledReason()">
          <el-button
            type="primary"
            :loading="applying"
            :disabled="!!step2DisabledReason()"
            data-test="stub-direct-import-btn"
            @click="onImport"
          >
            {{ t('apitest.stubImport.importBtn', { n: checkedCount }) }}
          </el-button>
        </el-tooltip>
      </div>
    </div>

    <!-- receipt step: outcome in plain words -->
    <div v-else class="sd-body sd-done">
      <p data-test="stub-direct-done-created">
        {{ t('apitest.stubImport.doneCreated', { n: result?.createdStubIds?.length ?? 0 }) }}
      </p>
      <p v-if="(result?.skippedEndpointKeys?.length ?? 0) > 0" data-test="stub-direct-done-skipped">
        {{ t('apitest.stubImport.doneSkipped', { n: result?.skippedEndpointKeys?.length ?? 0 }) }}
      </p>
      <p v-if="failedCount > 0" class="sd-error" data-test="stub-direct-done-failed">
        {{ t('apitest.stubImport.doneFailed', { n: failedCount }) }}
      </p>
      <p class="sd-hint" data-test="stub-direct-done-note">{{ t('apitest.stubImport.doneNote') }}</p>
      <p v-if="targetSpaceName" class="sd-echo" data-test="stub-direct-target-space-echo">
        {{ t('apitest.stubImport.targetSpaceEcho', { name: targetSpaceName }) }}
      </p>
      <div class="sd-actions">
        <el-button
          type="primary"
          :disabled="pickedSpaceId === null"
          data-test="stub-direct-view-docs"
          @click="viewInDocs"
        >
          {{ t('apitest.stubImport.viewDocs') }}
        </el-button>
        <el-button
          v-if="firstTryableStubId !== null"
          data-test="stub-direct-try-first"
          @click="tryFirstStub"
        >
          {{ t('apitest.stubImport.tryFirst') }}
        </el-button>
        <el-button data-test="stub-direct-close-btn" @click="closeWizard">{{ t('common.close') }}</el-button>
      </div>
    </div>
  </el-dialog>
</template>

<style scoped>
.sd-body { margin-top: 14px; display: flex; flex-direction: column; gap: 10px; }
.sd-block-title { margin: 6px 0 0; font-size: 13px; }
.sd-hint { margin: 0; font-size: 12px; color: var(--el-text-color-secondary); }
/* Read-only destination echo: slightly stronger than a hint so the write
   target is visible at a glance in the confirm and receipt steps. */
.sd-echo { margin: 0; font-size: 13px; font-weight: 600; color: var(--el-text-color-primary); }
.sd-upload { display: flex; flex-direction: column; gap: 8px; padding: 10px 12px;
  border: 1px dashed var(--el-border-color); border-radius: 8px; }
.sd-upload-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
/* Hidden-but-present file input: keeps set_input_files paths working while the
   visible affordance is the localized button above. */
.sd-file-hidden { position: absolute; width: 1px; height: 1px; opacity: 0; pointer-events: none; }
.sd-file-name { font-size: 12px; color: var(--el-text-color-primary); }
.sd-error { margin: 0; font-size: 12px; color: var(--el-color-danger); }
.sd-toolbar { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.sd-stat { font-size: 12px; color: var(--el-text-color-secondary); }
/* Windowed list: fixed viewport height comes from the inline style bound to
   DRAFT_VISIBLE_UNITS * DRAFT_UNIT_HEIGHT_PX, and each unit occupies exactly
   that band so the scroll math matches the DOM (no flex gap drift). */
.sd-cards { display: flex; flex-direction: column; overflow-y: auto; }
.sd-card { border: 1px solid var(--el-border-color-lighter); border-radius: 8px; padding: 8px 12px; height: 44px; box-sizing: border-box; }
.sd-card-head { display: flex; align-items: center; gap: 10px; }
.sd-group-head { display: flex; align-items: center; gap: 8px; height: 44px; box-sizing: border-box; cursor: pointer; font-size: 12px; font-weight: 600; color: var(--el-text-color-secondary); border-bottom: 1px dashed var(--el-border-color-lighter); }
.sd-group-name { min-width: 80px; }
.sd-group-count { font-weight: 400; }
.sd-unchecked { margin: 4px 0; padding: 8px 12px; border: 1px dashed var(--el-border-color-lighter); border-radius: 8px; }
.sd-unchecked-ul { margin: 0; padding-left: 18px; max-height: 160px; overflow-y: auto; }
.sd-unchecked-item { font-size: 12px; color: var(--el-text-color-secondary); }
.sd-method { font-size: 12px; font-weight: 700; min-width: 56px; }
.sd-path { font-size: 13px; font-weight: 600; }
.sd-name { font-size: 12px; color: var(--el-text-color-secondary); }
/* Vision rubric fix : the action row separated from the content
 block by the same dashed footer rule as the SpecImportWizard baseline — the
 10px stack gap alone made buttons visually cling to the last control. */
.sd-actions { display: flex; gap: 8px; margin-top: 8px; padding-top: 12px;
  border-top: 1px dashed var(--el-border-color-lighter); }
.sd-option-line { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.sd-option-status { font-size: 12px; color: var(--el-text-color-secondary); }
.sd-done p { margin: 0; font-size: 13px; }
</style>
