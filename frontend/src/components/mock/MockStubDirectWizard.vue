<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import {
  importStubsFromSpec,
  listSpaces,
  previewStubDrafts,
  type MockSpaceItem,
  type MockStubDraftItem,
  type MockStubImportResult,
} from '@/api/mock'
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
 * Three steps over the two admin doors: pick contract -> GET stub-drafts
 * preview (a proposal, persists nothing) with per-endpoint checkbox
 * selection -> POST import-from-spec (each draft goes through the normal
 * stub save chain; same method+path already covered comes back skipped, so
 * re-import is idempotent and step 3 reports both counts). The wizard never
 * rewrites a proposal.
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

// ---------- step 1: contract + destination space ----------
const specs = ref<ApiSpecView[]>([])
const specsLoading = ref(false)
const pickedSpecId = ref<number | null>(null)
const spaces = ref<MockSpaceItem[]>([])
const spacesLoading = ref(false)
const pickedSpaceId = ref<number | null>(null)
const step1Error = ref('')
const previewing = ref(false)

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
const drafts = ref<MockStubDraftItem[]>([])
const selected = ref<Set<string>>(new Set())
const keyword = ref('')
const step2Error = ref('')
const applying = ref(false)

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

const checkedCount = computed(() => selected.value.size)
const allChecked = computed(
  () => filteredDrafts.value.length > 0 && filteredDrafts.value.every((d) => selected.value.has(d.endpointKey)),
)

async function onPreview(): Promise<void> {
  if (pickedSpecId.value === null || pickedSpaceId.value === null) return
  previewing.value = true
  step1Error.value = ''
  try {
    drafts.value = await previewStubDrafts(pickedSpecId.value)
    // Default = everything checked: the contract is the trusted source here;
    // the checkbox gate still means nothing writes without a human click.
    selected.value = new Set(drafts.value.map((d) => d.endpointKey))
    step2Error.value = ''
    step.value = 1
  } catch (e) {
    step1Error.value = bizErrorMsg(e, t('apitest.stubImport.previewFail'))
  } finally {
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
    step.value = 2
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
    <el-steps :active="step === 2 ? 3 : step" finish-status="success" simple>
      <el-step :title="t('apitest.stubImport.step1')" />
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
      </div>
    </div>

    <!-- step 2: preview + checkbox selection (nothing written yet) -->
    <div v-else-if="step === 1" class="sd-body">
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
      </div>
      <div class="sd-cards">
        <div v-for="d in filteredDrafts" :key="d.endpointKey" class="sd-card" data-test="stub-direct-card">
          <div class="sd-card-head">
            <el-checkbox
              :model-value="selected.has(d.endpointKey)"
              :data-test="`stub-direct-check-${d.endpointKey.replace(/[^a-zA-Z0-9]/g, '-')}`"
              @change="(v: any) => toggleItem(d.endpointKey, Boolean(v))"
            />
            <span class="sd-method">{{ d.method }}</span>
            <span class="sd-path">{{ d.pathPattern }}</span>
            <span class="sd-name">{{ d.name }}</span>
            <el-tag size="small" type="info" data-test="stub-direct-source-tag">
              {{ t('apitest.stubImport.sourceTag') }}
            </el-tag>
          </div>
        </div>
        <p v-if="filteredDrafts.length === 0" class="sd-hint">{{ t('apitest.stubImport.noMatch') }}</p>
      </div>
      <p v-if="step2Error" class="sd-error" data-test="stub-direct-step2-error">{{ step2Error }}</p>
      <div class="sd-actions">
        <el-button data-test="stub-direct-back-btn" @click="step = 0">{{ t('apitest.specImport.back') }}</el-button>
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

    <!-- step 3: outcome in plain words -->
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
.sd-cards { display: flex; flex-direction: column; gap: 8px; max-height: 320px; overflow-y: auto; }
.sd-card { border: 1px solid var(--el-border-color-lighter); border-radius: 8px; padding: 8px 12px; }
.sd-card-head { display: flex; align-items: center; gap: 10px; }
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
