<script setup lang="ts">

import { computed, nextTick, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { QuestionFilled } from '@element-plus/icons-vue'
import ProjectOwnerEcho from '@/components/ProjectOwnerEcho.vue'
import { bizErrorMsg } from '@/utils/bizError'
// : the parameter editor mirrors the shared 04-contract vocabulary single
// source (assertionForm) — no second copy of the saveAs/status domains here.
import { SAVE_AS_NAME, STATUS_MAX, STATUS_MIN } from '@/utils/assertionForm'
import PerfGateLadder from '@/components/PerfGateLadder.vue'
import { derivePressurePreview, previewPath } from './pressurePreview'
import {
  JOURNEY_CARD_CAP,
  clearJourneyDeclaration,
  deriveJourneyGroups,
  enableJourneyDeclaration,
  journeySharePct,
  joinLastLink,
  stepForSave,
  type JourneyLink,
} from './journeyGroups'
import { getProjectEnvs, type ProjectEnv } from '@/api/project'
import { previewCron } from '@/api/schedule'
import { listCollections, listEffectiveBatch, listRequests, type ApiCollectionItem, type ApiRequestItem, type EffectivePreviewItem } from '@/api/apiTest'
import { useWindowedList } from '@/composables/useWindowedList'
// the index-shift remap helpers for step-table bulk operations
// live as pure functions (unit-tested) so no operation path re-implements
// the shift logic (red line 6).
import {
  duplicateStepRow,
  growStepAux,
  shrinkStepAux,
  stepUidOf,
  swapStepAux,
  type StepAux,
} from './stepTableOps'
import {
  allocPerfDatasetPolicy,
  applyPerfApproval,
  createPerfScenario,
  createPerfWhitelist,
  draftPerfScenario,
  findPerfDataset,
  listPerfEngines,
  listPerfTemplates,
  pagePerfDatasets,
  precheckPerfGate,
  updatePerfScenario,
  uploadPerfDataset,
  PERF_ISOLATION_MODES,
  type PerfAllocPolicy,
  type PerfEngineOption,
  type PerfDatasetItem,
  type PerfGateDecision,
  type PerfScenarioItem,
  type PerfTemplateItem,
  type PerfScenarioSavePayload,
} from '@/api/perf'

const props = defineProps<{ projectId: number | null }>()
const emit = defineEmits<{ (e: 'created'): void }>()

const { t } = useI18n()

const visible = ref(false)
const step = ref(0)
const submitting = ref(false)
const editingId = ref<number | null>(null)

/** Step-table row; the action whitelist mirrors core PerfStepActionGate (save-time plain-word rejection is the second guard). */
interface StepRow {
  action: string
  method: string
  url: string
  // GA-1: the auth flag is what the engine templates turn into the fixed
  // "auth" tag (executor perf_worker buckets those points out of the business
  // rps/p95/error lines). Headers/body/saveAs/expect stay optional so steps
  // authored elsewhere round-trip through a wizard edit without losing fields.
  auth?: boolean
  headers?: Record<string, string>
  body?: unknown
  saveAs?: string
  expect?: unknown
  // B-3 mix declaration : link name + positive-int link weight.
  // The whole-scenario all-or-none shape is judged by the server save gate
  // (PerfStepActionGate.mixStructureViolation); the U-4 card zone edits it.
  transaction?: string
  weight?: number
}

// A-11: the perf templates only compile method/url, so the wizard offers
// http.request alone (login = auth-flagged http.request step, not an action).
// Legacy scenarios holding other actions still render for inspection.
const ACTIONS = ['http.request']
const HTTP_METHODS = ['GET', 'POST', 'PUT', 'DELETE', 'PATCH']

const form = reactive({
  projectId: null as number | null,
  envId: null as number | null,
  name: '',
  steps: [] as StepRow[],
  pressureModel: 'CONSTANT_RPS',
  // W-d engine pin: null = default routing decides at dispatch (same posture
  // as the dataset strategy — the server value domain is the single source).
  engine: null as string | null,
  targetRps: 50,
  durationSeconds: 60,
  startRps: 10,
  peakRps: 200,
  rampSeconds: 30,
  holdSeconds: 60,
  baseRps: 20,
  // B-2 keys for the four new pressure models. Server-side semantics
  // (PerfScenarioServiceImpl REQUIRED_KEYS + validateModelSemantics) stay the
  // single validation source; the wizard only shapes what it sends.
  vu: 50,
  loops: null as number | null,
  thinkTimeMs: null as number | null,
  stepRps: 20,
  stepSeconds: 60,
  ladderSteps: 5,
  durationHours: 12,
  // BUSINESS_CURVE hour weights (percent of peakRps, 0..100); a null slot means
  // "hour not configured" and is dropped from the payload (all-zero = no-load
  // is rejected server-side, so we never bake silent zeros into the config).
  curveHours: Array<number | null>(24).fill(null) as (number | null)[],
  curveExpanded: false,
  loginStrategy: 'PER_VUSER',
  sessionN: 1,
  driverLimit: null as number | null,
  slaP95Ms: null as number | null,
  slaErrorRate: null as number | null,
  slaMinRps: null as number | null,
  slaBreakerErrorRate: null as number | null,
  isolationMode: 'SHADOW_TABLE',
  // data pool: bound CSV ledger row + engine-honoured row-allocation strategy
  // (null strategy = "let the server resolve the K8 default at dispatch").
  datasetId: null as number | null,
  rowAllocMode: null as string | null,
  // B-8 scheduled firing: cron expression + enable switch + pinned
  // launch environment. Server gate (PerfScenarioServiceImpl.validateSchedule)
  // is the single validation source — the wizard only shapes what it sends.
  scheduleCron: '',
  scheduleEnabled: false,
  scheduleEnvId: null as number | null,
})

// B-8: schedule preview state (server-returned next-fire instants).
const scheduleFires = ref<string[]>([])
const schedulePreviewing = ref(false)

const envs = ref<ProjectEnv[]>([])
const collections = ref<ApiCollectionItem[]>([])
/**
 * (cross-set import): requests cached PER selected set; the
 * candidate list is the flatMap over pick order (multi-select array order =
 * between-set step order, verdict GB-6), within a set the frozen order.
 */
const requestsByCollection = ref<Record<number, ApiRequestItem[]>>({})
const refCollectionIds = ref<number[]>([])

// ---------- data pool (dataset picker + allocation strategy + policy door) ----------

/** Picker window state: the ledger is paged server-side, never pulled whole (display-scale iron law). */
const datasetOptions = ref<PerfDatasetItem[]>([])
const datasetTotal = ref(0)
const datasetPage = ref(1)
const datasetLoading = ref(false)
const datasetUploading = ref(false)
const datasetFileInput = ref<HTMLInputElement | null>(null)
/** Engine × strategy matrix from the single policy door (null = not yet fetched). */
const allocPolicy = ref<PerfAllocPolicy | null>(null)
/** Header columns of the selected dataset = the {{VAR}} domain shown to the user. */
const datasetColumns = ref<string[]>([])
/** Display name of the bound pool (kept for the summary card even when the option window moves on). */
const datasetDisplayName = ref('')

const DATASET_PAGE_SIZE = 20

const datasetHasMore = computed(() => datasetOptions.value.length < datasetTotal.value)

/** Strategy chips = the full value domain from the policy door (unsupported stay visible, honestly disabled). */
const allocModeOptions = computed(() => allocPolicy.value?.allModes ?? [])

function isModeSupported(mode: string): boolean {
  return !!allocPolicy.value && allocPolicy.value.supportedModes.includes(mode)
}

/** Plain-words reason naming the engine and the config entry, shown on the disabled chip (honest-disable iron law). */
function allocUnsupportedReason(mode: string): string {
  // Only reachable while allocPolicy is loaded (the chip list comes from that door),
  // so engine is always a known key of perf.engineName.*.
  return t('perf.wizard.allocUnsupported', {
    engine: t(`perf.engineName.${allocPolicy.value?.engine ?? ''}`),
    mode: t(`perf.allocDesc.${mode}`),
  })
}

async function loadAllocPolicy() {
  if (allocPolicy.value) return
  try {
    allocPolicy.value = await allocPerfDatasetPolicy()
  } catch (e) {
    void bizErrorMsg(e, t('perf.loadFail'))
  }
}

/** First window (reset=true, on project change/open) or the next page appended by load-more. */
async function loadDatasets(reset: boolean) {
  if (!form.projectId) return
  datasetLoading.value = true
  try {
    const next = reset ? 1 : datasetPage.value + 1
    const res = await pagePerfDatasets(form.projectId, next, DATASET_PAGE_SIZE)
    datasetPage.value = res.page
    datasetTotal.value = res.total
    datasetOptions.value = reset ? res.list : datasetOptions.value.concat(res.list)
  } catch (e) {
    void bizErrorMsg(e, t('perf.loadFail'))
  } finally {
    datasetLoading.value = false
  }
}

/** Selection change → fetch the detail once: columns_json is the {{VAR}} domain reference. */
async function onDatasetChange(id: number | null) {
  datasetColumns.value = []
  datasetDisplayName.value = datasetOptions.value.find((d) => d.id === id)?.name ?? ''
  if (!id || !form.projectId) return
  try {
    const view = await findPerfDataset(form.projectId, id)
    datasetColumns.value = parseColumns(view.columns_json)
    datasetDisplayName.value = view.name
  } catch (e) {
    void bizErrorMsg(e, t('perf.loadFail'))
  }
}

/** columns_json arrives as JSON-array text (core validated header names). */
function parseColumns(raw: string): string[] {
  try {
    const arr = JSON.parse(raw) as unknown
    return Array.isArray(arr) ? arr.map(String) : []
  } catch {
    return []
  }
}

/** {{VAR}} reference chip text for one column (built in script: literal braces inside the template would break the mustache parser). */
function varToken(col: string): string {
  return '{{' + col + '}}'
}

function pickDatasetFile() {
  datasetFileInput.value?.click()
}

/** Upload straight from the wizard: name defaults to the file name stem, then the fresh row is auto-selected. */
async function onDatasetFilePicked(ev: Event) {
  const input = ev.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file || !form.projectId) return
  datasetUploading.value = true
  try {
    const name = file.name.replace(/\.[^.]+$/, '').slice(0, 200)
    const id = await uploadPerfDataset(form.projectId, name, file)
    ElMessage.success(t('perf.wizard.datasetUploaded', { name }))
    await loadDatasets(true)
    form.datasetId = id
    await onDatasetChange(id)
  } catch (e) {
    void bizErrorMsg(e, t('perf.opFail'))
  } finally {
    datasetUploading.value = false
  }
}


const pendingAssetRef = ref<{ collectionId: number; requestId: number } | null>(null)

const gate = ref<PerfGateDecision | null>(null)
const gateChecking = ref(false)

const wlDialog = ref(false)
const wlForm = reactive({ maxConcurrency: 100, allowedModes: ['SHADOW_TABLE'] as string[] })
const apDialog = ref(false)
const apForm = reactive({ concurrency: 100, isolationMode: 'SHADOW_TABLE', reason: '' })

/** Estimated machine time (seconds) — display-only projection of the declared
 * window; the scheduling authority is the core run leg. The seven-model switch
 * below keeps the same arithmetic as PerfBudgetPolicy.durationSecondsOf. */
const estimatedSeconds = computed(() => {
  switch (form.pressureModel) {
    case 'RAMP': return form.rampSeconds + form.holdSeconds
    case 'LADDER': return form.stepSeconds * form.ladderSteps
    case 'SOAK': return form.durationHours * 3600
    // FIXED_VU with loops only has no wall-clock claim (0 = honest "no window").
    case 'FIXED_VU': return form.durationSeconds
    default: return form.durationSeconds // CONSTANT_RPS / SPIKE / BUSINESS_CURVE
  }
})
const estimatedText = computed(() => {
  const s = estimatedSeconds.value
  // A loops-only FIXED_VU declares no wall-clock window — say so honestly
  // instead of rendering "0 seconds" (server budget keeps the same 0-claim).
  if (s <= 0) {
    return form.pressureModel === 'FIXED_VU' && form.loops && form.loops > 0
      ? t('perf.wizard.loopsOnlyEstimate', { loops: form.loops })
      : t('perf.durSeconds', { n: s })
  }
  const m = Math.floor(s / 60)
  const rest = s % 60
  return rest ? t('perf.durMinuteSeconds', { m, s: rest }) : t('perf.durMinutes', { m })
})

const peakConcurrency = computed(() => {
  switch (form.pressureModel) {
    case 'RAMP': return form.peakRps
    case 'SPIKE': return form.peakRps
    case 'FIXED_VU': return form.vu
    case 'LADDER': return form.startRps + form.stepRps * form.ladderSteps
    case 'BUSINESS_CURVE': return form.peakRps
    default: return form.targetRps // CONSTANT_RPS / SOAK
  }
})

// ---------- W-d engine catalogue (third engine wizard option) ----------
// The option list is data served by GET /perf/engines — its values are the
// core dispatch domain projection and each row carries the server's own
// rate-caliber caveat. Nothing here hard-codes an engine list or rewrites
// the disclosure wording (single-source rule).

/** Catalogue rows (null caveat = exact-schedule engine, honestly silent). */
const engineOptions = ref<PerfEngineOption[]>([])
const engineLoading = ref(false)

/** Caveat of the currently pinned engine ('' = none / default routing). */
const engineCaveat = computed(() =>
  engineOptions.value.find((o) => o.engine === form.engine)?.caveat ?? '')

async function loadEngines() {
  if (engineOptions.value.length || engineLoading.value) return
  engineLoading.value = true
  try {
    engineOptions.value = await listPerfEngines()
  } catch (e) {
    // Honest drop: without the catalogue the selector stays empty with a
    // toast; saving unset keeps the default routing — nothing is fabricated.
    void bizErrorMsg(e, t('perf.wizard.engineLoadFail'))
  } finally {
    engineLoading.value = false
  }
}

// ---------- U-2 pressure-curve preview ----------
// Display-only mirror of the server compilation arithmetic (see pressurePreview.ts)
// — the core run leg stays the single scheduling authority, this picture claims
// nothing beyond what step ② already declares.
const pressurePreview = computed(() => derivePressurePreview(form.pressureModel, {
  targetRps: form.targetRps,
  durationSeconds: form.durationSeconds,
  startRps: form.startRps,
  peakRps: form.peakRps,
  rampSeconds: form.rampSeconds,
  holdSeconds: form.holdSeconds,
  baseRps: form.baseRps,
  vu: form.vu,
  loops: form.loops,
  stepRps: form.stepRps,
  stepSeconds: form.stepSeconds,
  ladderSteps: form.ladderSteps,
  durationHours: form.durationHours,
  ...Object.fromEntries(form.curveHours.map((w, h) => [`curve${h}`, w])),
}))
const pressurePreviewD = computed(() => (
  pressurePreview.value ? previewPath(pressurePreview.value) : ''
))

// ---------- U-4 journey cards ----------
// The B-3 mix declaration gets its editing surface here: one card per
// pressure link (transaction group), chips list its member steps, a slider
// sets the link weight next to an honest share donut. Derivation mirrors
// the server grouping source (journeyGroups.ts) — validation stays wholly
// server-side (mixStructureViolation), this zone only makes the refused
// shapes unreachable from the UI. Worst scale (500 steps): at most
// JOURNEY_CARD_CAP cards render, the fold line names how many remain.
const journeyLinks = computed<JourneyLink[]>(() => deriveJourneyGroups(form.steps))
const journeyActive = computed(() => journeyLinks.value.length > 0)
const journeyCardLimit = JOURNEY_CARD_CAP
const journeyVisibleLinks = computed(() => journeyLinks.value.slice(0, journeyCardLimit))
const journeyHiddenCount = computed(() => Math.max(0, journeyLinks.value.length - journeyCardLimit))
/** Default link name for the enable switch = first unused letter label. */
function suggestLinkName(existing: JourneyLink[]): string {
  const used = new Set(existing.map((l) => l.name))
  for (let code = 65; code <= 90; code++) {
    const name = String.fromCharCode(code)
    if (!used.has(name)) return name
  }
  return `A${existing.length + 1}`
}
function toggleJourneyMode(on: boolean) {
  if (on) enableJourneyDeclaration(form.steps, suggestLinkName(journeyLinks.value))
  else clearJourneyDeclaration(form.steps)
}
function renameLink(link: JourneyLink, next: string) {
  link.indices.forEach((idx) => { form.steps[idx].transaction = next })
}
// Link-name editing: the card title input keeps a per-card draft (a purely
// controlled :model-value would bounce every keystroke back to the group
// name — E2E-caught) and commits on Enter/blur. Blank drafts revert to the
// current name (an empty link name has no declaration meaning server-side).
const journeyNameDraft = ref<Record<number, string>>({})
function journeyNameValue(link: JourneyLink): string {
  return journeyNameDraft.value[link.indices[0]] ?? link.name
}
function commitNameDraft(firstIndex: number) {
  const draft = (journeyNameDraft.value[firstIndex] ?? '').trim()
  const link = journeyLinks.value.find((l) => l.indices[0] === firstIndex)
  if (link && draft && draft !== link.name) renameLink(link, draft)
  delete journeyNameDraft.value[firstIndex]
}
function setLinkWeight(link: JourneyLink, w: number) {
  const v = Math.max(1, Math.round(w))
  link.indices.forEach((idx) => { form.steps[idx].weight = v })
}

// ---------- BUSINESS_CURVE hour-weight helpers (UI shaping only; the semantic
// rule "at least one hour > 0" is enforced server-side, no second copy here) ----------

/** Filled hour slots = the always-visible chip row (SE-2: no invisible filters). */
const filledCurveHours = computed(() =>
  form.curveHours.map((w, h) => (w !== null && w > 0 ? h : -1)).filter((h) => h >= 0))

const curveFilledCount = computed(() => filledCurveHours.value.length)

const curveChipText = computed(() =>
  t('perf.wizard.curveFilledChip', { hours: filledCurveHours.value.map((h) => `${h}`).join('·') }))

function clearCurveHours() {
  form.curveHours = Array<number | null>(24).fill(null)
}

function resetForm() {
  step.value = 0
  submitting.value = false
  // parameter-editor window state resets with the form (no stale folds/drafts).
  paramsOpen.value = []
  tokenTarget.value = null
  editingId.value = null
  gate.value = null
  refCollectionIds.value = []
  pendingAssetRef.value = null
  requestsByCollection.value = {}
  // : the schedule preview result belongs to the previous cron — drop it.
  scheduleFires.value = []
  // : the import panel window state resets with the form too.
  importOpen.value = false
  importKeyword.value = ''
  importSelected.value = new Set()
  effectiveRows.value = []
  importPreview.value = null
  undoSnapshot.value = null
  Object.assign(form, {
    projectId: props.projectId,
    envId: null,
    name: '',
    steps: [{ action: 'http.request', method: 'GET', url: '' }],
    pressureModel: 'CONSTANT_RPS',
    engine: null,
    targetRps: 50,
    durationSeconds: 60,
    startRps: 10,
    peakRps: 200,
    rampSeconds: 30,
    holdSeconds: 60,
    baseRps: 20,
    vu: 50,
    loops: null,
    thinkTimeMs: null,
    stepRps: 20,
    stepSeconds: 60,
    ladderSteps: 5,
    durationHours: 12,
    curveHours: Array<number | null>(24).fill(null),
    curveExpanded: false,
    loginStrategy: 'PER_VUSER',
    sessionN: 1,
    driverLimit: null,
    slaP95Ms: 800,
    slaErrorRate: 0.01,
    slaMinRps: null,
    slaBreakerErrorRate: 0.1,
    isolationMode: 'SHADOW_TABLE',
    datasetId: null,
    rowAllocMode: null,
    scheduleCron: '',
    scheduleEnabled: false,
    scheduleEnvId: null,
  })
  datasetOptions.value = []
  datasetTotal.value = 0
  datasetPage.value = 1
  datasetColumns.value = []
}

// ---------- CF-4 shipped scenario templates ----------

/** Catalogue rows (served by GET /perf/templates; never copied in code). */
const templateOptions = ref<PerfTemplateItem[]>([])
const templateLoading = ref(false)
/** Currently picked catalogue key; null keeps the select on "custom from scratch". */
const templateKey = ref<string | null>(null)

/**
 * Pull the catalogue once per wizard session — the config table is the single
 * source (a new playbook is a config row, hot-updated on next read), so the
 * picker only ever renders what the server judged valid for the save gate.
 */
async function loadTemplates() {
  if (templateOptions.value.length || templateLoading.value) return
  templateLoading.value = true
  try {
    templateOptions.value = await listPerfTemplates()
  } catch (e) {
    // Honest drop: a failed catalogue read leaves the picker empty with a
    // toast — the from-scratch path stays fully usable.
    void bizErrorMsg(e, t('perf.wizard.templateLoadFail'))
  } finally {
    templateLoading.value = false
  }
}

/** Shared pressure-config round-trip (edit hydration + template pre-fill ride this one copy). */
function applyPressureConfig(cfg: Record<string, unknown>, model?: string) {
  form.targetRps = numOr(cfg.targetRps, form.targetRps)
  form.durationSeconds = numOr(cfg.durationSeconds, form.durationSeconds)
  // A FIXED_VU scenario that ends by loops only declares no wall-clock window; the
  // form default must not leak in (it would render a dishonest one-minute
  // estimate and re-saving would add a durationSeconds key never declared).
  // Zero = honest "no window" (loops-only estimate).
  if (model === 'FIXED_VU' && cfg.durationSeconds == null) form.durationSeconds = 0
  form.startRps = numOr(cfg.startRps, form.startRps)
  form.peakRps = numOr(cfg.peakRps, form.peakRps)
  form.rampSeconds = numOr(cfg.rampSeconds, form.rampSeconds)
  form.holdSeconds = numOr(cfg.holdSeconds, form.holdSeconds)
  form.baseRps = numOr(cfg.baseRps, form.baseRps)
  // B-2 new-model round-trip: every pressure key rides back so the declared
  // shape never silently drops (null optionals stay defaults).
  form.vu = numOr(cfg.vu, form.vu)
  form.loops = numOrNull(cfg.loops)
  form.thinkTimeMs = numOrNull(cfg.thinkTimeMs)
  form.stepRps = numOr(cfg.stepRps, form.stepRps)
  form.stepSeconds = numOr(cfg.stepSeconds, form.stepSeconds)
  form.ladderSteps = numOr(cfg.steps, form.ladderSteps)
  form.durationHours = numOr(cfg.durationHours, form.durationHours)
  form.curveHours = Array.from({ length: 24 }, (_, h) => numOrNull(cfg[`curve${h}`]))
  form.curveExpanded = false
}

/** Shared SLA round-trip (same single copy for edit hydration and pre-fill). */
function applySla(sla: Record<string, unknown>) {
  form.slaP95Ms = numOrNull(sla.p95Ms)
  form.slaErrorRate = numOrNull(sla.errorRate)
  form.slaMinRps = numOrNull(sla.minRps)
  form.slaBreakerErrorRate = numOrNull(sla.breakerErrorRate)
}

/**
 * Template pick → whole-form pre-fill: pressure model + config keys + step
 * table + SLA. Steps ride normalizeHydratedSteps (the edit normalizer — JSON
 * bodies become editable text, optional expect/transaction/weight fields are
 * kept verbatim), so a pre-filled draft saves exactly like a hand-authored
 * one. Name stays untouched unless empty (a pick must not clobber what the
 * user already typed); the template's {{ENV:BASE}} urls resolve at dispatch.
 */
function applyTemplate(key: string) {
  const tpl = templateOptions.value.find((o) => o.templateKey === key)
  if (!tpl) return
  const sc = tpl.scenario
  form.pressureModel = sc.pressureModel
  applyPressureConfig(sc.pressureConfig ?? {}, sc.pressureModel)
  applySla(sc.slaJson ?? {})
  form.steps = normalizeHydratedSteps((sc.steps ?? []) as unknown as StepRow[])
  if (!form.steps.length) form.steps = [{ action: 'http.request', method: 'GET', url: '' }]
  paramsOpen.value = form.steps.map(() => false)
  resetStepBulkState()
  stepKeyword.value = ''
  if (!form.name.trim()) form.name = tpl.label.slice(0, 64)
}

/** Selecting a row pre-fills; clearing the select is a no-op (keeps edits). */
function onTemplatePick(key: string | null) {
  if (key) applyTemplate(key)
}

// B-11 AI drafting door. The one-sentence intent goes to the admin
// draft endpoint; the reply's steps ride the SAME hydration path a template
// pick uses, so an AI draft saves exactly like a hand-authored scenario.
// Core is the single judging point (step gate trims before replying) — the
// browser never re-validates and never silently drops: droppedReasons must
// surface per reason. Nothing persists here; saving stays behind the wizard.
const aiDraftText = ref('')
const aiDrafting = ref(false)

/** Plain reason why the AI door is currently unusable ('' = ready). */
const aiDraftBlockedReason = computed<string>(() => {
  if (!form.projectId) return t('perf.wizard.aiDraftBlockedNoProject')
  if (!aiDraftText.value.trim()) return t('perf.wizard.aiDraftBlockedNoText')
  return ''
})

async function runAiDraft() {
  if (aiDraftBlockedReason.value || aiDrafting.value) {
    if (aiDraftBlockedReason.value) ElMessage.warning(aiDraftBlockedReason.value)
    return
  }
  aiDrafting.value = true
  try {
    const draft = await draftPerfScenario(form.projectId as number, aiDraftText.value.trim())
    const rows = (draft?.steps ?? []) as unknown as StepRow[]
    if (!rows.length) {
      // Honest empty: the model produced nothing the save gate would accept.
      ElMessage.warning(t('perf.wizard.aiDraftEmpty'))
      return
    }
    form.steps = normalizeHydratedSteps(rows)
    paramsOpen.value = form.steps.map(() => false)
    resetStepBulkState()
    stepKeyword.value = ''
    if (!form.name.trim()) form.name = aiDraftText.value.trim().slice(0, 64)
    ElMessage.success(t('perf.wizard.aiDraftDone', { n: form.steps.length }))
    // Every trimmed step is named — a silent drop would hide a half-baked
    // scenario behind a green toast (red line: no silent failure).
    if (draft.droppedCount > 0) {
      const reasons = (draft.droppedReasons ?? []).join('; ')
      ElMessage.warning(t('perf.wizard.aiDraftDropped', { n: draft.droppedCount, reasons }))
    }
    aiDraftText.value = ''
  } catch (e) {
    // Switch-off refusals and upstream failures ride through untranslated from
    // core (management gate names the config key). The refusal must actually
    // reach the user: the HTTP 403 leg of the interceptor only rethrows a
    // BizError without a toast (E2E-verified), so the message is surfaced
    // verbatim here — the same pattern the mock docs view uses.
    ElMessage.error(bizErrorMsg(e, t('perf.wizard.aiDraftFail')))
  } finally {
    aiDrafting.value = false
  }
}

function openWizard() {
  resetForm()
  templateKey.value = null
  aiDraftText.value = ''
  visible.value = true
  void loadAllocPolicy()
  void loadTemplates()
  void loadEngines()
  if (form.projectId) void loadDatasets(true)
}


function openWithAssetRef(pid: number, collectionId: number, requestId: number) {
  resetForm()
  templateKey.value = null
  aiDraftText.value = ''
  form.projectId = pid
  pendingAssetRef.value = { collectionId, requestId }
  visible.value = true
  void loadAllocPolicy()
  void loadTemplates()
  void loadEngines()
  void loadDatasets(true)
}


watch(() => form.envId, (eid) => {
  const pending = pendingAssetRef.value
  if (!eid || !pending) return
  pendingAssetRef.value = null
  refCollectionIds.value = [pending.collectionId]
  void applyAssetRef(pending.collectionId, pending.requestId)
})

/** Upgrade entry consumption point: the source set enters the multi-select array (first item), request cache is keyed per set. */
async function applyAssetRef(collectionId: number, requestId: number) {
  try {
    requestsByCollection.value = { ...requestsByCollection.value, [collectionId]: await listRequests(collectionId) }
  } catch (e) {
    void bizErrorMsg(e, t('perf.loadFail'))
    return
  }
  const req = requestsByCollection.value[collectionId]?.find((r) => r.id === requestId)
  if (!req) return
  fillStepFromRequest(req)
  if (!form.name) form.name = req.name.slice(0, 64)
}

/** Raised while openWith back-fills an existing scenario (see projectId watch). */
const hydrating = ref(false)


function openWith(sc: PerfScenarioItem) {
  resetForm()
  templateKey.value = null
  aiDraftText.value = ''
  hydrating.value = true
  editingId.value = sc.id
  form.projectId = sc.project_id
  form.envId = sc.env_id
  form.name = sc.name
  form.pressureModel = sc.pressure_model
  form.loginStrategy = sc.login_strategy || 'PER_VUSER'
  // data-pool round-trip: the two binding columns ride back into the form
  // (core SELECT already returns them), so an edit never silently drops the pool.
  form.datasetId = sc.dataset_id ?? null
  form.rowAllocMode = sc.row_alloc_mode ?? null
  // B-8 schedule round-trip: same rule for the scheduled-firing trio —
  // an edit must never silently drop the cron, the switch or the pinned env.
  form.scheduleCron = sc.schedule_cron ?? ''
  form.scheduleEnabled = Number(sc.schedule_enabled ?? 0) === 1
  form.scheduleEnvId = sc.schedule_env_id ?? null
  if (form.loginStrategy.startsWith('PER_SESSION_')) {
    form.sessionN = Number(form.loginStrategy.slice('PER_SESSION_'.length)) || 1
  }
  form.driverLimit = sc.driver_limit
  // W-d engine round-trip: the persisted pin rides back so an edit never
  // silently downgrades a pinned engine to default routing.
  form.engine = sc.engine ?? null
  try {
    form.steps = normalizeHydratedSteps(JSON.parse(sc.steps_json || '[]') as StepRow[])
    if (!form.steps.length) form.steps = [{ action: 'http.request', method: 'GET', url: '' }]
  } catch {
    form.steps = [{ action: 'http.request', method: 'GET', url: '' }]
  }
  // : fresh edit always opens folded (scan-first), drafts/token target reset.
  paramsOpen.value = form.steps.map(() => false)
  // a replaced table is a new identity set — bulk selection and
  // the delete-undo snapshot of the previous table must not carry over.
  resetStepBulkState()
  stepKeyword.value = ''
  const cfg = safeParse(sc.pressure_config)
  // The edit lane rides the same shared hydration as template pre-fill, and it
  // must carry the stored model: a loops-only FIXED_VU declares no wall-clock
  // window, and without the model the honest zero-guard below cannot fire (the
  // form default leaked a fake one-minute estimate into the estimate line).
  applyPressureConfig(cfg, form.pressureModel)
  applySla(safeParse(sc.sla_json))
  visible.value = true
  void loadAllocPolicy()
  void loadTemplates()
  void loadEngines()
  void loadDatasets(true)
  // back-fill the {{VAR}} column chips for the bound pool (same detail door the picker uses)
  if (form.datasetId) void onDatasetChange(form.datasetId)
  // Release the hydration guard after the projectId watcher has flushed
  // (watcher jobs run before nextTick callbacks in the same scheduler flush).
  void nextTick(() => { hydrating.value = false })
}
defineExpose({ openWizard, openWith, openWithAssetRef })

function safeParse(raw: string | null): Record<string, unknown> {
  try {
    return (JSON.parse(raw || '{}') as Record<string, unknown>) ?? {}
  } catch {
    return {}
  }
}
function numOr(v: unknown, d: number): number {
  return typeof v === 'number' && Number.isFinite(v) ? v : d
}
function numOrNull(v: unknown): number | null {
  return typeof v === 'number' && Number.isFinite(v) ? v : null
}

watch(() => form.projectId, (pid) => {
  // Hydration guard: openWith assigns projectId and envId synchronously, but
  // this watcher flushes on the next microtask — without the flag the async
  // reset would wipe the env that edit mode just back-filled (every scenario
  // edit silently dropped its target environment). Manual project switches
  // (flag false) keep clearing envId as before.
  if (hydrating.value) {
    if (!pid) return
    void loadEnvs()
    void loadCollections()
    void loadDatasets(true)
    return
  }
  form.envId = null
  collections.value = []
  datasetOptions.value = []
  datasetTotal.value = 0
  datasetPage.value = 1
  datasetColumns.value = []
  if (!pid) return
  void loadEnvs()
  void loadCollections()
  void loadDatasets(true)
})

/** Strategy only makes sense with a bound pool — clearing the dataset clears the strategy back to "server default" (core save gate mirrors the same rule). */
watch(() => form.datasetId, (id) => {
  if (!id) form.rowAllocMode = null
})

async function loadEnvs() {
  if (!form.projectId) return
  try {
    envs.value = await getProjectEnvs(form.projectId)
  } catch (e) {
    void bizErrorMsg(e, t('perf.loadFail'))
  }
}

async function loadCollections() {
  if (!form.projectId) return
  try {
    collections.value = await listCollections(form.projectId)
  } catch (e) {
    void bizErrorMsg(e, t('perf.loadFail'))
  }
}

/**
 * the set picker is multi-select; the selection ARRAY order is
 * the between-set step order (verdict GB-6). Newly picked sets get their rows
 * cached on demand; dropped sets lose their rows and their ticked requests.
 */
async function onRefCollections(ids: number[]) {
  effectiveRows.value = []
  const stillPicked = new Set(ids)
  importSelected.value = new Set([...importSelected.value].filter((reqId) => importRows.value.some((r) => r.id === reqId && stillPicked.has(r.collectionId))))
  const missing = ids.filter((id) => !requestsByCollection.value[id])
  if (missing.length === 0) return
  try {
    const loaded = await Promise.all(missing.map((id) => listRequests(id)))
    const merged = { ...requestsByCollection.value }
    missing.forEach((id, i) => {
      merged[id] = loaded[i]
    })
    requestsByCollection.value = merged
  } catch (e) {
    void bizErrorMsg(e, t('perf.loadFail'))
  }
}

// ---------- interface-set import panel (GB-6) ----------
// The single-request dropdown only ever prefilled one step, while the real
// load target is a whole interface set. The panel lists the set's requests in
// the frozen (user-maintained, ) order with checkboxes, windowed for the
// worst scale (500 rows), and materialises the picked rows into steps ONCE —
// decoupled from the set afterwards (launch pinning discipline: an imported
// scenario never re-reads the source table).

/** Panel open state plus the one-shot merged-defaults fetch batch endpoint). */
const importOpen = ref(false)
const importLoading = ref(false)
const importBusy = ref(false)
/** Batch effective preview rows, keyed by requestId (single round trip, never per-row). */
const effectiveRows = ref<EffectivePreviewItem[]>([])
/** Search box inside the panel (method/path/name, client-side over the loaded set). */
const importKeyword = ref('')
/** Selection keyed by REQUEST ID — survives filtering and re-windowing (index keys are the trap). */
const importSelected = ref<Set<number>>(new Set())
/** Chunked generation: rows committed per frame so the panel never freezes. */
const IMPORT_CHUNK = 50
const importGenDone = ref(0)
const importGenTotal = ref(0)
/** One-step undo snapshot of a bulk import (session-scoped, cleared on panel close). */
const undoSnapshot = ref<StepRow[] | null>(null)

/**
 * Candidate rows across every picked set, concatenated in PICK order
 * (between-set order) and frozen order inside each set (GB-6 contract).
 */
const importRows = computed<ApiRequestItem[]>(() =>
  refCollectionIds.value.flatMap((cid) => requestsByCollection.value[cid] ?? []),
)

const filteredImportRows = computed<ApiRequestItem[]>(() => {
  const kw = importKeyword.value.trim().toLowerCase()
  if (!kw) return importRows.value
  return importRows.value.filter(
    (r) =>
      r.method.toLowerCase().includes(kw) ||
      r.urlPath.toLowerCase().includes(kw) ||
      r.name.toLowerCase().includes(kw),
  )
})

/** Windowed render (display-scale iron rule): ~20 DOM rows, never the whole set. */
const importListEl = ref<HTMLElement | null>(null)
const importWindow = useWindowedList(filteredImportRows, {
  scroller: () => importListEl.value,
  rowPitch: 40,
  buffer: 4,
})
const importVisible = importWindow.visible
const importPadTop = importWindow.padTop
const importPadBottom = importWindow.padBottom

function onImportListScroll(): void {
  importWindow.measurePitch()
  importWindow.sync()
}

watch(filteredImportRows, () => {
  importWindow.resetWindow()
  importWindow.sync()
})

/** Why the import door is shut (honest disabled reason, plain words). */
const importBlockedReason = computed<string>(() => {
  if (!form.envId) return t('perf.wizard.importNeedEnv')
  if (refCollectionIds.value.length === 0) return t('perf.wizard.importNeedCollection')
  return ''
})

/** Source-set name for one candidate row (cross-set provenance badge + preview column). */
function originNameOf(collectionId: number): string {
  return collections.value.find((c) => c.id === collectionId)?.name ?? ''
}

function toggleImportRow(reqId: number): void {
  const next = new Set(importSelected.value)
  if (next.has(reqId)) next.delete(reqId)
  else next.add(reqId)
  importSelected.value = next
}

/** Ctrl/Cmd+A = select the CURRENT FILTER RESULT, not the whole set (select-all stays scoped to what the user can see). */
function selectImportAllVisible(): void {
  const next = new Set(importSelected.value)
  for (const r of filteredImportRows.value) next.add(r.id)
  importSelected.value = next
}

function onImportKeydown(e: KeyboardEvent): void {
  if (e.key === 'Escape') {
    closeImportPanel()
    return
  }
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'a') {
    e.preventDefault()
    selectImportAllVisible()
    return
  }
  if (e.key === 'Enter' && importSelected.value.size > 0) {
    e.preventDefault()
    void requestImport()
    return
  }
  if (e.key === ' ' && importFocusId.value !== null) {
    e.preventDefault()
    toggleImportRow(importFocusId.value)
  }
}

/** Row under keyboard focus (Space toggles it; arrow-free keeps the panel lean). */
const importFocusId = ref<number | null>(null)

async function openImportPanel(): Promise<void> {
  if (importBlockedReason.value) {
    ElMessage.warning(importBlockedReason.value)
    return
  }
  importOpen.value = true
  const ids = [...refCollectionIds.value]
  if (ids.length === 0) return
  // Any picked set without a cached row list (e.g. picked before the panel
  // ever opened) is pulled now, in parallel — the list itself stays honest.
  const missing = ids.filter((id) => !requestsByCollection.value[id])
  if (missing.length > 0) {
    try {
      const loaded = await Promise.all(missing.map((id) => listRequests(id)))
      const merged = { ...requestsByCollection.value }
      missing.forEach((id, i) => {
        merged[id] = loaded[i]
      })
      requestsByCollection.value = merged
    } catch (e) {
      void bizErrorMsg(e, t('perf.loadFail'))
      return
    }
  }
  // The batch endpoint is authoritative for merge verdicts (core resolver
  // single source) — ONE round trip per selected set, merged client-side.
  importLoading.value = true
  try {
    const batches = await Promise.all(ids.map((id) => listEffectiveBatch(id)))
    effectiveRows.value = batches.flat()
  } catch (e) {
    // Honest degradation: without the merged view the import still works,
    // carrying only request-level headers/body — the toast says so in words.
    effectiveRows.value = []
    void bizErrorMsg(e, t('perf.wizard.importMergeFail'))
  } finally {
    importLoading.value = false
  }
}

function closeImportPanel(): void {
  importOpen.value = false
  importKeyword.value = ''
  importSelected.value = new Set()
  importFocusId.value = null
  importPreview.value = null
}

/** base+path splice single source (red line 6: the prefill and the import share it). */
function requestStepUrl(req: ApiRequestItem): string {
  const env = envs.value.find((e) => e.id === form.envId)
  const base = (env?.baseUrl ?? '').replace(/\/+$/, '')
  const path = req.urlPath.startsWith('/') ? req.urlPath : `/${req.urlPath}`
  return base + path
}

function effectiveOf(reqId: number): EffectivePreviewItem | undefined {
  return effectiveRows.value.find((p) => p.requestId === reqId)
}

/** Requests carrying real credentials at request or collection level — the
 *  import never bakes a token (login traffic = step login flag + scenario
 *  login strategy, D4/D5 semantics), so those rows are counted and disclosed. */
function authCarryingCount(ids: number[]): number {
  return ids.filter((id) => {
    const src = effectiveOf(id)?.authSource
    return src === 'request' || src === 'collection'
  }).length
}

/** One picked request → one materialised step (frozen defaults baked once). */
function materializeRows(reqs: ApiRequestItem[]): StepRow[] {
  return reqs.map((req) => {
    const row: StepRow = { action: 'http.request', method: req.method, url: requestStepUrl(req) }
    const eff = effectiveOf(req.id)
    if (eff && eff.headers.length > 0) {
      const headers: Record<string, string> = {}
      for (const h of eff.headers) headers[h.name] = h.value
      row.headers = headers
    }
    if (methodAllowsBody(req.method) && req.bodyText && req.bodyText.trim() !== '') {
      row.body = req.bodyText
    }
    return row
  })
}

/**
 * (cross-set merge preview): the panel footer no longer commits
 * steps directly — it opens a confirmation card listing exactly the steps
 * that will be generated, in final order (pick order between sets, frozen
 * order inside each set), each row carrying its source-set badge. Duplicated
 * method+path combinations are FLAGGED (never silently dropped — the user
 * decides). Only after confirmation does the chunked generation run.
 */
const importPreview = ref<ApiRequestItem[] | null>(null)

/** Duplicate = same method+path appearing more than once among the previewed rows. */
function duplicateKeyOf(req: ApiRequestItem): string {
  return `${req.method.toUpperCase()} ${req.urlPath}`
}

const duplicateKeys = computed<Set<string>>(() => {
  const seen = new Set<string>()
  const dup = new Set<string>()
  for (const r of importPreview.value ?? []) {
    const key = duplicateKeyOf(r)
    if (seen.has(key)) dup.add(key)
    else seen.add(key)
  }
  return dup
})

/** Preview list rides the windowed renderer too (500-row worst scale). */
const previewListEl = ref<HTMLElement | null>(null)
const previewWindow = useWindowedList(computed(() => importPreview.value ?? []), {
  scroller: () => previewListEl.value,
  rowPitch: 40,
  buffer: 4,
})
const previewVisible = previewWindow.visible
const previewPadTop = previewWindow.padTop
const previewPadBottom = previewWindow.padBottom
/** Ordinal offset so the preview numbers match the final step positions. */
const importPreviewStart = previewWindow.start
/** Two-way door for the preview dialog: closing it (X/Esc) keeps the selection. */
const importPreviewVisible = computed({
  get: () => importPreview.value !== null,
  set: (open: boolean) => {
    if (!open) importPreview.value = null
  },
})

watch(importPreview, (rows) => {
  if (rows) {
    previewWindow.resetWindow()
    previewWindow.sync()
  }
})

function onPreviewListScroll(): void {
  previewWindow.measurePitch()
  previewWindow.sync()
}

/** Footer door: freeze the picked rows in final order and show the preview card. */
function requestImport(): void {
  if (importBusy.value || importSelected.value.size === 0) return
  importPreview.value = importRows.value.filter((r) => importSelected.value.has(r.id))
}

/** Close the preview card without committing (selection is kept). */
function cancelImportPreview(): void {
  importPreview.value = null
}

/**
 * Commit the previewed rows: chunked generation (≤50 per frame, anti-freeze
 * iron rule) with a progress line, then the toast names the count and every
 * source set; the panel offers a one-step undo of the whole bulk import.
 */
async function confirmImport(): Promise<void> {
  if (importBusy.value) return
  const picked = importPreview.value ?? []
  if (picked.length === 0) return
  const rows = materializeRows(picked)
  importBusy.value = true
  undoSnapshot.value = JSON.parse(JSON.stringify(form.steps)) as StepRow[]
  const firstNewIndex = form.steps.length
  importGenTotal.value = rows.length
  importGenDone.value = 0
  try {
    for (let i = 0; i < rows.length; i += IMPORT_CHUNK) {
      for (const row of rows.slice(i, i + IMPORT_CHUNK)) {
        form.steps.push(row)
        joinLastLink(form.steps)
      }
      paramsOpen.value = form.steps.map(() => false)
      importGenDone.value = Math.min(i + IMPORT_CHUNK, rows.length)
      await nextTick()
    }
    const setNames = [...new Set(picked.map((r) => originNameOf(r.collectionId)).filter((n) => n !== ''))].join(', ')
    ElMessage.success(t('perf.wizard.imported', { n: rows.length, name: setNames }))
    const skipped = authCarryingCount(picked.map((r) => r.id))
    if (skipped > 0) ElMessage.info(t('perf.wizard.importAuthSkipped', { n: skipped }))
    importSelected.value = new Set()
    closeImportPanel()
    // Close-the-loop (10-23 operation-priority contract): the first imported
    // row scrolls into view and its checkbox takes focus.
    await revealStepRow(firstNewIndex)
  } finally {
    importBusy.value = false
  }
}

/** Undo the last bulk import: restore the pre-import step snapshot. */
function undoImport(): void {
  if (!undoSnapshot.value) return
  form.steps = undoSnapshot.value
  undoSnapshot.value = null
  paramsOpen.value = form.steps.map(() => false)
  resetStepBulkState()
  ElMessage.success(t('perf.wizard.importUndone'))
}

/** Double-click a row = import that one request directly (≤1 action path). */
function importSingle(req: ApiRequestItem): void {
  const row = materializeRows([req])[0]
  if (!row) return
  form.steps.push(row)
  joinLastLink(form.steps)
  paramsOpen.value = form.steps.map(() => false)
  ElMessage.success(t('perf.wizard.imported', { n: 1, name: req.name }))
}

/** First-step prefill from a referenced request (URL building rides requestStepUrl — red line 6, no second copy). */
function fillStepFromRequest(req: ApiRequestItem) {
  const first = form.steps[0]
  if (first && !first.url) {
    first.action = 'http.request'
    first.method = req.method
    first.url = requestStepUrl(req)
  } else {
    form.steps.push({ action: 'http.request', method: req.method, url: requestStepUrl(req) })
    joinLastLink(form.steps)
  }
}

function addStep() {
  form.steps.push({ action: 'http.request', method: 'GET', url: '' })
  // U-4: a declared mix covers the whole scenario (server gate refuses a
  // step without a link) — a grown step joins the last link automatically.
  joinLastLink(form.steps)
}
function removeStep(i: number) {
  const removed = form.steps[i]
  form.steps.splice(i, 1)
  // Drafts/folds/token target are index-keyed: remap them through the shared
  // shrink helper so a removed middle step never leaves the next row showing
  // the deleted row's half-typed header .
  const a = stepAux()
  shrinkStepAux(a, i)
  tokenTarget.value = a.target
  forgetStepSelection(removed)
}

// ---------- step-table windowed body + bulk operations ----------
// Worst scale 500 steps: only the scroll window renders (pads keep the
// scrollbar honest); selection keys by stable row uid, never by array index
// (the shift trap), and every movement remaps the aux state through the
// stepTableOps helpers.

/** One rendered row = stable uid + live position + row object. */
interface StepEntry {
  uid: number
  index: number
  row: StepRow
}

type TokenSlot = 'url' | 'body' | 'hvalue' | 'saveAs'

/** Wrap the three index-keyed side-state containers for the remap helpers. */
function stepAux(): StepAux<TokenSlot> {
  return { open: paramsOpen.value, drafts: headerDraft, target: tokenTarget.value }
}

const stepKeyword = ref('')

const filteredStepEntries = computed<StepEntry[]>(() => {
  const all = form.steps.map((row, index) => ({ uid: stepUidOf(row), index, row }))
  const kw = stepKeyword.value.trim().toLowerCase()
  if (!kw) return all
  return all.filter(
    (e) =>
      (e.row.method ?? '').toLowerCase().includes(kw) ||
      e.row.url.toLowerCase().includes(kw) ||
      e.row.action.toLowerCase().includes(kw) ||
      (e.row.transaction ?? '').toLowerCase().includes(kw),
  )
})

const stepListEl = ref<HTMLElement | null>(null)
const stepWindow = useWindowedList(filteredStepEntries, {
  scroller: () => stepListEl.value,
  rowPitch: 48,
  buffer: 4,
})
const stepVisible = stepWindow.visible
const stepPadTop = stepWindow.padTop
const stepPadBottom = stepWindow.padBottom

function onStepListScroll(): void {
  stepWindow.measurePitch()
  stepWindow.sync()
}

watch(stepKeyword, () => {
  stepWindow.resetWindow()
  stepWindow.sync()
})

/** Bulk selection keyed by row uid — survives filtering, inserts and moves. */
const stepSelected = ref<Set<number>>(new Set())
/** One-step undo of the last bulk delete (session-scoped like . */
const bulkUndoSnapshot = ref<StepRow[] | null>(null)

function toggleStepSelection(uid: number): void {
  const next = new Set(stepSelected.value)
  if (next.has(uid)) next.delete(uid)
  else next.add(uid)
  stepSelected.value = next
}

/** Drop a removed row's uid so a stale selection can never resurrect. */
function forgetStepSelection(row: StepRow | undefined): void {
  if (!row) return
  const uid = stepUidOf(row)
  if (stepSelected.value.has(uid)) {
    const next = new Set(stepSelected.value)
    next.delete(uid)
    stepSelected.value = next
  }
}

/** Select-all operates on the CURRENT FILTER RESULT (same rule as the import panel). */
function selectAllStepsFiltered(): void {
  const next = new Set(stepSelected.value)
  for (const e of filteredStepEntries.value) next.add(e.uid)
  stepSelected.value = next
}

/**
 * Bulk delete the checked steps: count-named confirmation first (R22:
 * consequences point at the object), snapshot for a one-step undo, then
 * remove positions back-to-front while shrinking the aux tables.
 */
async function bulkDeleteSteps(): Promise<void> {
  const positions = filteredStepEntries.value
    .filter((e) => stepSelected.value.has(e.uid))
    .map((e) => e.index)
    .sort((x, y) => y - x)
  if (positions.length === 0) return
  try {
    await ElMessageBox.confirm(
      t('perf.wizard.stepBulkDeleteConfirm', { n: positions.length }),
      t('perf.wizard.stepBulkDelete'),
      { type: 'warning' },
    )
  } catch {
    return
  }
  bulkUndoSnapshot.value = JSON.parse(JSON.stringify(form.steps)) as StepRow[]
  const a = stepAux()
  for (const p of positions) shrinkStepAux(a, p)
  tokenTarget.value = a.target
  for (const p of positions) form.steps.splice(p, 1)
  stepSelected.value = new Set()
  ElMessage.success(t('perf.wizard.stepBulkDeleted', { n: positions.length }))
}

/** Undo the last bulk delete: restore the snapshot (fresh rows, folded). */
function undoBulkDelete(): void {
  if (!bulkUndoSnapshot.value) return
  form.steps = bulkUndoSnapshot.value
  bulkUndoSnapshot.value = null
  paramsOpen.value = form.steps.map(() => false)
  for (const key of Object.keys(headerDraft)) delete headerDraft[Number(key)]
  tokenTarget.value = null
  stepSelected.value = new Set()
  ElMessage.success(t('perf.wizard.stepBulkUndone', { n: form.steps.length }))
}

/** Insert an empty step right after the row under the button. */
function insertStepAfter(i: number): void {
  form.steps.splice(i + 1, 0, { action: 'http.request', method: 'GET', url: '' })
  joinLastLink(form.steps)
  const a = stepAux()
  growStepAux(a, i + 1)
  tokenTarget.value = a.target
  stepWindow.sync()
}

/** Duplicate the row with its parameters (deep copy — no shared objects). */
function duplicateStep(i: number): void {
  const src = form.steps[i]
  if (!src) return
  form.steps.splice(i + 1, 0, duplicateStepRow(src))
  const a = stepAux()
  growStepAux(a, i + 1)
  tokenTarget.value = a.target
  stepWindow.sync()
}

/** Move a step up/down one position; folds and drafts follow their rows. */
function moveStep(i: number, delta: -1 | 1): void {
  const j = i + delta
  if (j < 0 || j >= form.steps.length) return
  const tmp = form.steps[i]
  form.steps[i] = form.steps[j]
  form.steps[j] = tmp
  const a = stepAux()
  swapStepAux(a, i, j)
  tokenTarget.value = a.target
  stepWindow.sync()
}

/** Clear bulk state when the whole table is replaced (hydration/undo paths). */
function resetStepBulkState(): void {
  stepSelected.value = new Set()
  bulkUndoSnapshot.value = null
  stepKeyword.value = ''
}

/**
 * Scroll the windowed body to a row position and focus its checkbox — the
 * close-the-loop gesture after a bulk insert (first new row on screen, the
 * user's next keystroke lands on the table).
 */
async function revealStepRow(index: number): Promise<void> {
  await nextTick()
  const el = stepListEl.value
  if (!el) return
  const maxTop = Math.max(0, form.steps.length * stepWindow.pitch.value - el.clientHeight)
  el.scrollTop = Math.min(Math.max(0, index * stepWindow.pitch.value - 8), maxTop)
  stepWindow.sync()
  el.querySelector<HTMLElement>(`[data-test="pw-step-row-${index}"] [data-test="pw-step-check"]`)?.focus()
}

// ---------- step-table parameter editor (GA-6) ----------
// The server save chain and both engine templates already compile
// headers/body/expect.status/saveAs; the wizard only lacked the settings
// surface. Value domains stay server-authoritative (PerfStepActionGate) —
// this editor mirrors them for UX, never re-judges on save (red line 6).

/** Per-row collapse state of the parameter area (default: all folded). */
const paramsOpen = ref<boolean[]>([])
/** Draft key/value pair for the "add header" row, per step index. */
const headerDraft = reactive<Record<number, { k: string; v: string }>>({})
/** Where a dataset {{VAR}} chip lands: the last focused slot (honest nudge when none). */
const tokenTarget = ref<{ i: number; slot: 'url' | 'body' | 'hvalue' | 'saveAs' } | null>(null)

const OPEN_PARAMS_CAP = 5

function isOpen(i: number): boolean {
  return !!paramsOpen.value[i]
}

function toggleParams(i: number) {
  if (paramsOpen.value[i]) {
    paramsOpen.value[i] = false
    return
  }
  // Folding is a scan aid over a long table; unbounded simultaneous open turns
  // the step list into a wall of inputs. Bulk expand-all stays available.
  if (paramsOpen.value.filter(Boolean).length >= OPEN_PARAMS_CAP) {
    ElMessage.info(t('perf.wizard.paramsCapNote', { cap: OPEN_PARAMS_CAP }))
    return
  }
  paramsOpen.value[i] = true
}

function expandAllParams() {
  paramsOpen.value = form.steps.map(() => true)
}
function collapseAllParams() {
  paramsOpen.value = form.steps.map(() => false)
}

/** http.request methods that can carry a body (GET/HEAD/OPTIONS cannot — the body slot says so in plain words). */
function methodAllowsBody(method: string): boolean {
  return !['GET', 'HEAD', 'OPTIONS'].includes(method)
}

function headerEntries(row: StepRow): [string, string][] {
  const h = row.headers
  return h && typeof h === 'object' && !Array.isArray(h)
    ? Object.entries(h as Record<string, string>)
    : []
}

function draftFor(i: number): { k: string; v: string } {
  if (!headerDraft[i]) headerDraft[i] = { k: '', v: '' }
  return headerDraft[i]
}

function addHeaderRow(i: number) {
  const draft = draftFor(i)
  const key = draft.k.trim()
  if (!key) {
    ElMessage.warning(t('perf.wizard.paramKeyRequired'))
    return
  }
  const row = form.steps[i]
  const headers = (row.headers as Record<string, string>) ?? {}
  if (Object.prototype.hasOwnProperty.call(headers, key)) {
    // Mirror of the server single-key domain; the fix is to edit the existing
    // value in place, not to shadow it with a second copy.
    ElMessage.warning(t('perf.wizard.paramDupKey', { key }))
    return
  }
  row.headers = { ...headers, [key]: draft.v }
  draft.k = ''
  draft.v = ''
}

function removeHeaderKey(i: number, key: string) {
  const row = form.steps[i]
  const headers = { ...((row.headers as Record<string, string>) ?? {}) }
  delete headers[key]
  row.headers = Object.keys(headers).length ? headers : undefined
}

function statusOf(row: StepRow): number | null {
  const e = row.expect as { status?: number } | undefined | null
  return e && typeof e === 'object' && typeof e.status === 'number' ? e.status : null
}

/** Write expect.status through the object (never fabricating a stray key when cleared). */
function setStatus(row: StepRow, v: number | null) {
  const prev = (row.expect && typeof row.expect === 'object' ? { ...(row.expect as Record<string, unknown>) } : {}) as Record<string, unknown>
  if (v === null || v === undefined) delete prev.status
  else prev.status = v
  row.expect = Object.keys(prev).length ? prev : undefined
}

/** saveAs domain mirror of NonUiActionContract.SAVE_AS_NAME (UX hint only; the save gate stays the authority). */
function saveAsInvalid(row: StepRow): boolean {
  const v = row.saveAs
  return typeof v === 'string' && v.trim() !== '' && !SAVE_AS_NAME.test(v.trim())
}

/** Dataset column chip click → append the {{VAR}} token into the focused slot. */
function insertVarToken(col: string) {
  const token = varToken(col)
  const target = tokenTarget.value
  if (!target) {
    ElMessage.info(t('perf.wizard.varChipNoTarget'))
    return
  }
  const row = form.steps[target.i]
  if (!row) return
  if (target.slot === 'url') row.url = `${row.url ?? ''}${token}`
  else if (target.slot === 'saveAs') row.saveAs = `${row.saveAs ?? ''}${token}`
  else if (target.slot === 'body') row.body = `${typeof row.body === 'string' ? row.body : ''}${token}`
  else draftFor(target.i).v = `${draftFor(target.i).v}${token}`
}

/** Legacy JSON bodies round-trip as editable text (the templates read the raw body value). */
function normalizeHydratedSteps(rows: StepRow[]): StepRow[] {
  return rows.map((r) => ({
    ...r,
    body: r.body !== null && typeof r.body === 'object' ? JSON.stringify(r.body, null, 2) : r.body,
  }))
}

function stepBlockReason(s: number): string {
  if (s === 0) {
    if (!form.projectId) return t('perf.wizard.needProject')
    if (!form.envId) return t('perf.wizard.needEnv')
    if (!form.name.trim()) return t('perf.wizard.needName')
    if (!form.steps.length) return t('perf.wizard.needSteps')
    for (const r of form.steps) {
      if (!r.action) return t('perf.wizard.needStepAction')
      if (r.action === 'http.request' && !r.url.trim()) return t('perf.wizard.needStepUrl')
    }
  }
  if (s === 1) {
    // FIXED_VU may end by loops only (no wall-clock claim) — same termination
    // rule the server gate enforces, kept here purely as step-flow guidance.
    if (form.pressureModel === 'FIXED_VU') {
      const noLoops = form.loops === null || form.loops <= 0
      if (noLoops && form.durationSeconds <= 0) return t('perf.wizard.needDuration')
    } else if (form.pressureModel === 'BUSINESS_CURVE') {
      if (!curveFilledCount.value) return t('perf.wizard.curveNeedOne')
    } else {
      const secs = estimatedSeconds.value
      if (secs <= 0) return t('perf.wizard.needDuration')
    }
  }
  if (s === 2) {
    // Server-rule mirror (PerfScenarioServiceImpl.validateSchedule): an
    // enabled switch without a cron would look on and never act — blocked
    // here so the user fixes it before submit instead of after a refused save.
    if (form.scheduleEnabled && !form.scheduleCron.trim()) return t('perf.wizard.scheduleNeedCron')
  }
  return ''
}

function firstBlockedStep(): number {
  for (let s = 0; s <= 2; s++) {
    if (stepBlockReason(s)) return s
  }
  return -1
}

function buildPressureConfig(): Record<string, number> {
  // Key sets mirror the server REQUIRED_KEYS per model (PerfScenarioServiceImpl);
  // optional keys are only sent when actually filled (no stray zeros).
  switch (form.pressureModel) {
    case 'CONSTANT_RPS':
      return { targetRps: form.targetRps, durationSeconds: form.durationSeconds }
    case 'RAMP':
      return { startRps: form.startRps, peakRps: form.peakRps, rampSeconds: form.rampSeconds, holdSeconds: form.holdSeconds }
    case 'SPIKE':
      return { baseRps: form.baseRps, peakRps: form.peakRps, durationSeconds: form.durationSeconds }
    case 'FIXED_VU': {
      const cfg: Record<string, number> = { vu: form.vu }
      if (form.loops !== null && form.loops > 0) cfg.loops = form.loops
      if (form.durationSeconds > 0) cfg.durationSeconds = form.durationSeconds
      if (form.thinkTimeMs !== null && form.thinkTimeMs > 0) cfg.thinkTimeMs = form.thinkTimeMs
      return cfg
    }
    case 'LADDER':
      return { startRps: form.startRps, stepRps: form.stepRps, stepSeconds: form.stepSeconds, steps: form.ladderSteps }
    case 'BUSINESS_CURVE': {
      const cfg: Record<string, number> = { peakRps: form.peakRps, durationSeconds: form.durationSeconds }
      form.curveHours.forEach((w, h) => {
        if (w !== null && w > 0) cfg[`curve${h}`] = w
      })
      return cfg
    }
    case 'SOAK':
      return { targetRps: form.targetRps, durationHours: form.durationHours }
    default:
      return { targetRps: form.targetRps, durationSeconds: form.durationSeconds }
  }
}

function buildSlaJson(): Record<string, number> | null {
  const out: Record<string, number> = {}
  const pairs: [string, number | null][] = [
    ['p95Ms', form.slaP95Ms],
    ['errorRate', form.slaErrorRate],
    ['minRps', form.slaMinRps],
    ['breakerErrorRate', form.slaBreakerErrorRate],
  ]
  for (const [k, v] of pairs) {
    if (v !== null && Number.isFinite(v)) out[k] = v
  }
  return Object.keys(out).length ? out : null
}

function effectiveLoginStrategy(): string {
  return form.loginStrategy === 'PER_SESSION_N' ? `PER_SESSION_${form.sessionN}` : form.loginStrategy
}

// ---------- U-5 pre-launch summary helpers (all authoritative numbers come from the
// server precheck response; the card only formats them, never recomputes) ----------

/** Human budget line from the server-computed budgetMs (0 = no claim → honest fallback note). */
const budgetText = computed(() => {
  const ms = gate.value?.budgetMs ?? 0
  const margin = gate.value?.budgetMarginMinutes ?? 0
  if (ms <= 0) return t('perf.wizard.budgetNone')
  const minutes = Math.round(ms / 60000)
  const base = minutes >= 60
    ? t('perf.durHourMinute', { h: Math.floor(minutes / 60), m: minutes % 60 })
    : t('perf.durMinutes', { m: minutes })
  return t('perf.wizard.budgetLine', { budget: base, margin })
})

/** Breaker row: declared rate as a percentage + what the breaker actually does when it trips. */
const breakerText = computed(() => {
  const v = form.slaBreakerErrorRate
  if (v === null || !Number.isFinite(v)) return t('perf.wizard.breakerNotDeclared')
  return t('perf.wizard.breakerDeclared', { pct: (v * 100).toFixed(1) })
})

/** SLA gate rows: one line per declared threshold, unfilled keys say "not set" (no silent gaps). */
const slaRows = computed(() => {
  const pairs: [string, number | null, boolean][] = [
    ['perf.wizard.slaP95', form.slaP95Ms, false],
    ['perf.wizard.slaErrorRate', form.slaErrorRate, true],
    ['perf.wizard.slaMinRps', form.slaMinRps, false],
  ]
  return pairs.map(([key, value, isRate]) => {
    if (value === null || !Number.isFinite(value)) return { label: t(key), value: t('perf.wizard.slaNotSet') }
    if (isRate) return { label: t(key), value: `${(value * 100).toFixed(1)}%` }
    return { label: t(key), value: String(value) }
  })
})

function buildPayload(): PerfScenarioSavePayload {
  return {
    projectId: form.projectId!,
    name: form.name.trim(),
    envId: form.envId,
    pressureModel: form.pressureModel,
    pressureConfig: buildPressureConfig(),
    loginStrategy: effectiveLoginStrategy(),
    driverLimit: form.driverLimit,
    // W-d: the declared engine pin (null = default routing; core re-judges
    // the value domain at save — the wizard never re-validates).
    engine: form.engine,
    // GA-1: carry the full edited step instead of a three-key whitelist —
    // the auth flag and the optional DSL fields used to vanish on save while
    // the engine templates happily compile them. Empty optionals are dropped
    // so a legacy scenario edited in the wizard never gains stray keys.
    // U-4 : same rule now covers the B-3 pair (transaction/weight)
    // — before this segment the wizard silently DROPPED the mix on every edit.
    steps: form.steps.map(stepForSave),
    slaJson: buildSlaJson(),
    // A-5: persist the declared isolation mode on the scenario so the launch-time safety
    // gate and the pinned snapshot consume the same truth the wizard's precheck showed.
    isolationMode: form.isolationMode,
    // data pool: empty strategy stays null (= server resolves the K8 default at dispatch).
    datasetId: form.datasetId,
    rowAllocMode: form.datasetId ? form.rowAllocMode : null,
    // B-8 scheduled firing: blank cron sends null (the "not scheduled"
    // state), the switch rides as declared — core refuses an enabled switch
    // without a cron, the same rule the step gate mirrors below.
    scheduleCron: form.scheduleCron.trim() || null,
    scheduleEnabled: form.scheduleEnabled,
    scheduleEnvId: form.scheduleEnvId,
  }
}

function nextStep() {
  const msg = stepBlockReason(step.value)
  if (msg) {
    ElMessage.warning(msg)
    return
  }
  // The precheck verdict (budget window included) must survive into the confirm
  // step: the summary card reads gate.* as the server single source. Clearing it
  // here would silently degrade every budget/precheck row to the fallback note.
  step.value += 1
}


async function runPrecheck() {
  if (!form.projectId || !form.envId) {
    ElMessage.warning(t('perf.wizard.needProjectEnv'))
    return
  }
  gateChecking.value = true
  try {
    gate.value = await precheckPerfGate({
      projectId: form.projectId,
      envId: form.envId,
      concurrency: peakConcurrency.value,
      isolationMode: form.isolationMode,
      // U-5: the budget window shown on the summary card is computed by the server
      // (PerfBudgetPolicy + hot margin); the wizard only declares its own pressure.
      pressureModel: form.pressureModel,
      pressureConfig: buildPressureConfig(),
    })
  } catch (e) {
    gate.value = null
    void bizErrorMsg(e, t('perf.precheckFail'))
  } finally {
    gateChecking.value = false
  }
}

async function saveWhitelist() {
  if (!form.projectId || !form.envId) {
    ElMessage.warning(t('perf.wizard.needProjectEnv'))
    return
  }
  try {
    await createPerfWhitelist({
      projectId: form.projectId,
      envId: form.envId,
      maxConcurrency: wlForm.maxConcurrency,
      allowedModes: wlForm.allowedModes,
    })
    ElMessage.success(t('perf.wl.saved'))
    wlDialog.value = false
    await runPrecheck()
  } catch (e) {
    void bizErrorMsg(e, t('perf.opFail'))
  }
}

async function applyApproval() {
  if (!form.projectId || !form.envId) {
    ElMessage.warning(t('perf.wizard.needProjectEnv'))
    return
  }
  try {
    await applyPerfApproval({
      projectId: form.projectId,
      envId: form.envId,
      requestedConcurrency: apForm.concurrency,
      isolationMode: apForm.isolationMode,
      reason: apForm.reason.trim(),
      // D8: link the persisted scenario when there is one (edit lane). A not-yet-saved
      // scenario has no id to link — the ticket stays a plain approval (honest absence,
      // never a dangling id).
      scenarioId: editingId.value ?? null,
    })
    ElMessage.success(t('perf.applied.saved'))
    apDialog.value = false
  } catch (e) {
    void bizErrorMsg(e, t('perf.opFail'))
  }
}

/**
 * B-8 scheduled firing preview: fetches the next trigger instants from
 * the server door (GET /api/v1/schedule/cron-preview — the same arithmetic
 * the firing scanner uses, no local cron parser). Failures surface the
 * backend's human-readable wording verbatim so an invalid cron explains
 * itself without a save round-trip.
 */
async function previewScheduleFires() {
  schedulePreviewing.value = true
  try {
    scheduleFires.value = await previewCron(form.scheduleCron.trim())
  } catch (e) {
    scheduleFires.value = []
    void bizErrorMsg(e, t('perf.wizard.schedulePreviewFail'))
  } finally {
    schedulePreviewing.value = false
  }
}

/** Local rendering of a server-returned ISO instant (browser locale timezone). */
function formatFireTime(iso: string): string {
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString()
}

async function submit() {
  const blocked = firstBlockedStep()
  if (blocked >= 0) {
    step.value = blocked
    ElMessage.warning(stepBlockReason(blocked))
    return
  }
  submitting.value = true
  try {
    const payload = buildPayload()
    if (editingId.value) {
      await updatePerfScenario(editingId.value, payload)
      ElMessage.success(t('perf.wizard.updatedOk'))
    } else {
      await createPerfScenario(payload)
      ElMessage.success(t('perf.wizard.createdOk'))
    }
    visible.value = false
    emit('created')
  } catch (e) {
    void bizErrorMsg(e, t('perf.wizard.createFail'))
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="editingId ? t('perf.wizard.titleEdit') : t('perf.wizard.title')"
    width="1040px"
    destroy-on-close
    data-test="perf-wizard"
  >
    <!-- ownership echo (iron rule 1): read-only, single-sourced from the header
         project context; the legacy in-wizard selector is retired — the wizard
         always inherits the page context (props.projectId) or the deep-linked
         upgrade source set by the workbench -->
    <ProjectOwnerEcho />
    <el-steps :active="step" finish="success" align-center class="pw__steps">
      <el-step :title="t('perf.wizard.step1')" />
      <el-step :title="t('perf.wizard.step2')" />
      <el-step :title="t('perf.wizard.step3')" />
      <el-step :title="t('perf.wizard.step4')" />
      <el-step :title="t('perf.wizard.step5')" />
    </el-steps>

    
    <div v-show="step === 0" class="pw__body" data-test="pw-step-target">
      <!-- CF-4: shipped playbooks as data — the catalogue endpoint is the
           only source (zero template copies here), picking one pre-fills
           model/config/steps/SLA through the shared hydration path. -->
      <div class="pw__field">
        <label for="pw-template-input">{{ t('perf.wizard.template') }}</label>
        <el-select
          v-model="templateKey"
          id="pw-template-input"
          clearable
          filterable
          :loading="templateLoading"
          :placeholder="t('perf.wizard.templatePh')"
          data-test="pw-template"
          class="pw__control"
          @change="onTemplatePick"
        >
          <el-option v-for="o in templateOptions" :key="o.templateKey" :label="o.label" :value="o.templateKey" />
        </el-select>
        <p class="pw__hint">{{ t('perf.wizard.templateHint') }}</p>
      </div>
      <!-- B-11: one sentence → a draft step table, behind the default-off AI
           switch (refusal wording comes from the server untranslated). The
           door is honest: without a project or typed text it sits disabled
           with the plain reason on the wrapping span (a disabled button
           hides its own title). Prefill rides the shared hydration path and
           never persists by itself — saving stays a human-confirmed step. -->
      <div class="pw__field">
        <label for="pw-ai-draft-input">{{ t('perf.wizard.aiDraft') }}</label>
        <div class="pw__ref-row pw__ref-row--import">
          <el-input
            v-model="aiDraftText"
            id="pw-ai-draft-input"
            :placeholder="t('perf.wizard.aiDraftPh')"
            maxlength="200"
            data-test="pw-ai-draft-input"
            class="pw__control"
            @keyup.enter="runAiDraft"
          />
          <span class="pw__imp-door" :title="aiDraftBlockedReason || undefined">
            <el-button
              type="primary"
              plain
              :loading="aiDrafting"
              :disabled="!form.projectId || !aiDraftText.trim() || aiDrafting"
              data-test="pw-ai-draft"
              @click="runAiDraft"
            >
              {{ t('perf.wizard.aiDraftGo') }}
            </el-button>
          </span>
        </div>
        <p class="pw__hint">{{ t('perf.wizard.aiDraftHint') }}</p>
      </div>
      <div class="pw__field">
        <label for="pw-env-input">{{ t('perf.wizard.env') }}</label>
        <el-select v-model="form.envId" id="pw-env-input" filterable :placeholder="t('perf.wizard.envPh')" data-test="pw-env" class="pw__control" popper-class="tp-pw-env-popper">
          <el-option v-for="e in envs" :key="e.id" :label="e.name" :value="e.id" />
        </el-select>
      </div>
      <div class="pw__field">
        <label for="pw-name-input">{{ t('perf.wizard.name') }}</label>
        <el-input v-model="form.name" id="pw-name-input" :placeholder="t('perf.wizard.namePh')" maxlength="64" data-test="pw-name" show-word-limit />
      </div>
      <div class="pw__field">
        <label for="pw-ref-collection-input">{{ t('perf.wizard.refAsset') }}</label>
        <div class="pw__ref-row pw__ref-row--import">
          <el-select v-model="refCollectionIds" id="pw-ref-collection-input" multiple collapse-tags collapse-tags-tooltip clearable filterable :placeholder="t('perf.wizard.refCollectionPh')" data-test="pw-ref-collection" @change="onRefCollections">
            <el-option v-for="c in collections" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
          <!-- : the single-request pick is retired — the import panel lists
 the whole set (frozen order) and materialises the checked rows.
 A disabled button carries its own title invisibly, so the plain
 reason lives on the wrapping span. -->
          <span class="pw__imp-door" :title="importBlockedReason || undefined">
            <el-button
              type="primary"
              plain
              data-test="pw-open-import"
              :disabled="importBlockedReason !== ''"
              @click="openImportPanel"
            >
              {{ t('perf.wizard.importOpen') }}
            </el-button>
          </span>
          <el-button v-if="undoSnapshot" link type="info" data-test="pw-import-undo" @click="undoImport()">
            {{ t('perf.wizard.importUndo') }}
          </el-button>
        </div>
        <p class="pw__hint">{{ t('perf.wizard.refHint') }}</p>
      </div>
      <div class="pw__field">
        <div class="pw__journey-head">
          <span id="pw-journey-label" class="pw__caption">{{ t('perf.wizard.journeyTitle') }}</span>
          <el-button v-if="!journeyActive" link type="primary" data-test="pw-journey-enable" @click="toggleJourneyMode(true)">
            {{ t('perf.wizard.journeyEnable') }}
          </el-button>
          <el-button v-else link type="info" data-test="pw-journey-clear" @click="toggleJourneyMode(false)">
            {{ t('perf.wizard.journeyDisable') }}
          </el-button>
        </div>
        <template v-if="journeyActive">
          <div
            v-for="link in journeyVisibleLinks"
            :key="link.indices[0]"
            class="pw__journey-card"
            data-test="pw-journey-card"
          >
            <div class="pw__journey-top">
              <el-input
                class="pw__journey-name"
                size="small"
                :model-value="journeyNameValue(link)"
                aria-labelledby="pw-journey-label"
                data-test="pw-journey-name"
                @update:model-value="(v: string) => { journeyNameDraft[link.indices[0]] = v }"
                @change="commitNameDraft(link.indices[0])"
                @keyup.enter="commitNameDraft(link.indices[0])"
              />
              <svg class="pw__journey-donut" width="34" height="34" viewBox="0 0 34 34" aria-hidden="true">
                <circle cx="17" cy="17" r="14" fill="none" stroke="var(--el-border-color)" stroke-width="5" />
                <circle
                  cx="17" cy="17" r="14" fill="none" stroke="var(--el-color-primary)" stroke-width="5"
                  :stroke-dasharray="`${journeySharePct(link, journeyLinks) * 0.8796} 100`"
                  transform="rotate(-90 17 17)"
                />
              </svg>
              <span class="pw__journey-share">{{ t('perf.wizard.journeyShare', { pct: journeySharePct(link, journeyLinks) }) }}</span>
            </div>
            <div class="pw__journey-members" data-test="pw-journey-members">
              <el-tag
                v-for="idx in link.indices.slice(0, 6)"
                :key="idx"
                size="small"
                type="info"
                class="pw__journey-chip"
              >{{ t('perf.wizard.journeyMembers', { n: idx + 1 }) }}</el-tag>
              <span v-if="link.indices.length > 6" class="pw__journey-more">
                {{ t('perf.wizard.journeyMoreMembers', { n: link.indices.length - 6 }) }}
              </span>
            </div>
            <div class="pw__journey-slider">
              <span :id="`pw-journey-weight-label-${link.name}`" class="pw__journey-slider-label">{{ t('perf.wizard.journeyWeight') }}</span>
              <el-slider
                :model-value="link.weight > 0 ? link.weight : 1"
                :min="1"
                :max="20"
                :step="1"
                :aria-labelledby="`pw-journey-weight-label-${link.name}`"
                data-test="pw-journey-weight"
                @input="(v: number) => setLinkWeight(link, v)"
              />
            </div>
          </div>
          <p v-if="journeyHiddenCount > 0" class="pw__hint pw__journey-fold" data-test="pw-journey-fold">
            {{ t('perf.wizard.journeyMoreLinks', { n: journeyHiddenCount }) }}
          </p>
        </template>
        <p class="pw__hint">{{ t('perf.wizard.journeyHint') }}</p>
      </div>
      <div class="pw__field">
        <!-- The field label points at the always-rendered search box: an id
             inside the windowed rows could dangle when filtered away. -->
        <label for="pw-step-search">{{ t('perf.wizard.steps') }}</label>
        <!-- Honest scale disclosure: how many steps this table carries before
 the fold scan aids . -->
        <p class="pw__steps-count" data-test="pw-steps-count">{{ t('perf.wizard.stepsCount', { n: form.steps.length }) }}</p>
        <!-- search + bulk bar above the windowed body. Select-all
 is scoped to the CURRENT FILTER RESULT; the bulk delete names its
 count in the confirmation and offers a one-step undo. -->
        <div class="pw__step-tools">
          <el-input
            id="pw-step-search"
            v-model="stepKeyword"
            :placeholder="t('perf.wizard.stepSearchPh')"
            clearable
            class="pw__step-search"
            data-test="pw-step-search"
          />
          <el-button link type="primary" data-test="pw-step-select-all" @click="selectAllStepsFiltered">{{ t('perf.wizard.stepSelectAll') }}</el-button>
          <el-button
            link
            type="danger"
            data-test="pw-step-bulk-delete"
            :disabled="stepSelected.size === 0"
            @click="bulkDeleteSteps"
          >{{ t('perf.wizard.stepBulkDelete') }}</el-button>
          <el-button v-if="bulkUndoSnapshot" link type="info" data-test="pw-step-bulk-undo" @click="undoBulkDelete">
            {{ t('perf.wizard.stepBulkUndo') }}
          </el-button>
        </div>
        <p v-if="stepSelected.size > 0" class="pw__steps-count" data-test="pw-step-selected-count">
          {{ t('perf.wizard.stepSelectedCount', { n: stepSelected.size }) }}
        </p>
        <div ref="stepListEl" class="pw__step-list" data-test="pw-step-list" @scroll.passive="onStepListScroll">
          <div v-if="stepPadTop > 0" class="pw__step-pad" :style="{ height: `${stepPadTop}px` }" aria-hidden="true" />
          <div
            v-for="entry in stepVisible"
            :key="entry.uid"
            class="pw__step-block"
            data-windowed-row
            :data-test="`pw-step-row-${entry.index}`"
          >
          <div class="pw__step-row">
            <el-checkbox
              :model-value="stepSelected.has(entry.uid)"
              :aria-label="`${entry.row.method ?? entry.row.action} ${entry.row.url ?? ''}`"
              class="pw__step-check"
              data-test="pw-step-check"
              @change="toggleStepSelection(entry.uid)"
            />
            <el-select v-model="entry.row.action" :id="`pw-step-action-${entry.index}`" class="pw__step-action" data-test="pw-step-action">
              <el-option v-for="a in ACTIONS" :key="a" :label="a" :value="a" />
            </el-select>
            <el-select v-if="entry.row.action === 'http.request'" v-model="entry.row.method" class="pw__step-method">
              <el-option v-for="m in HTTP_METHODS" :key="m" :label="m" :value="m" />
            </el-select>
            <el-input
              v-if="entry.row.action === 'http.request'"
              v-model="entry.row.url"
              placeholder="https://…/api/…"
              class="pw__step-url"
              data-test="pw-step-url"
              @focus="tokenTarget = { i: entry.index, slot: 'url' }"
            />
            <el-checkbox v-if="entry.row.action === 'http.request'" v-model="entry.row.auth" :id="`pw-step-auth-${entry.index}`" class="pw__step-auth" data-test="pw-step-auth">{{ t('perf.wizard.stepAuth') }}</el-checkbox>
            <span v-else class="pw__step-note">{{ t('perf.wizard.stepActionNote') }}</span>
            <!-- : inline parameter fold (collapsed by default; the server
 templates already compile these fields, GA-6 adds the entry). -->
            <el-button
              v-if="entry.row.action === 'http.request'"
              link
              type="primary"
              :id="`pw-step-params-toggle-${entry.index}`"
              :data-test="`pw-step-params-toggle-${entry.index}`"
              :aria-expanded="isOpen(entry.index)"
              :aria-controls="`pw-step-params-${entry.index}`"
              @click="toggleParams(entry.index)"
            >{{ isOpen(entry.index) ? t('perf.wizard.paramCollapse') : t('perf.wizard.paramExpand') }}</el-button>
            <!-- per-row operations: insert-after / duplicate with
 parameters / move up / move down / single delete. -->
            <el-button link type="primary" :icon="'Plus'" :aria-label="t('perf.wizard.stepInsertAfter')" data-test="pw-step-insert-after" @click="insertStepAfter(entry.index)" />
            <el-button link type="primary" :icon="'CopyDocument'" :aria-label="t('perf.wizard.stepDuplicate')" data-test="pw-step-duplicate" @click="duplicateStep(entry.index)" />
            <el-button link type="primary" :icon="'Top'" :disabled="entry.index === 0" :aria-label="t('perf.wizard.stepMoveUp')" data-test="pw-step-move-up" @click="moveStep(entry.index, -1)" />
            <el-button link type="primary" :icon="'Bottom'" :disabled="entry.index === form.steps.length - 1" :aria-label="t('perf.wizard.stepMoveDown')" data-test="pw-step-move-down" @click="moveStep(entry.index, 1)" />
            <el-button v-if="form.steps.length > 1" link type="danger" :icon="'Delete'" :aria-label="t('perf.wizard.stepDelete')" @click="removeStep(entry.index)" />
          </div>
          <div
            v-if="entry.row.action === 'http.request'"
            v-show="isOpen(entry.index)"
            :id="`pw-step-params-${entry.index}`"
            :data-test="`pw-step-params-${entry.index}`"
            class="pw__step-params"
          >
            <div class="pw__param-group">
              <span :id="`pw-param-headers-label-${entry.index}`" class="pw__caption">{{ t('perf.wizard.paramHeaders') }}</span>
              <div v-for="([hk, hv]) in headerEntries(entry.row)" :key="hk" class="pw__kv-row">
                <el-input :model-value="hk" readonly class="pw__kv-key" :aria-labelledby="`pw-param-headers-label-${entry.index}`" />
                <el-input
                  :model-value="hv"
                  class="pw__kv-value"
                  data-test="pw-param-hvalue"
                  :aria-label="hk"
                  @update:model-value="(v: string) => { entry.row.headers = { ...(entry.row.headers as Record<string, string>), [hk]: v } }"
                  @focus="tokenTarget = { i: entry.index, slot: 'hvalue' }"
                />
                <el-button link type="danger" :icon="'Delete'" :aria-label="t('perf.wizard.paramHeaderRemove', { key: hk })" @click="removeHeaderKey(entry.index, hk)" />
              </div>
              <div class="pw__kv-row">
                <el-input v-model="draftFor(entry.index).k" :placeholder="t('perf.wizard.paramKeyPh')" data-test="pw-param-hkey" class="pw__kv-key" :aria-label="t('perf.wizard.paramHeaders')" />
                <el-input v-model="draftFor(entry.index).v" :placeholder="t('perf.wizard.paramValuePh')" data-test="pw-param-hvalue" class="pw__kv-value" :aria-label="t('perf.wizard.paramValuePh')" @focus="tokenTarget = { i: entry.index, slot: 'hvalue' }" />
                <el-button link type="primary" data-test="pw-param-hadd" @click="addHeaderRow(entry.index)">{{ t('perf.wizard.paramHeaderAdd') }}</el-button>
              </div>
            </div>
            <div class="pw__param-group">
              <label :for="`pw-param-body-${entry.index}`">{{ t('perf.wizard.paramBody') }}</label>
              <el-input
                :id="`pw-param-body-${entry.index}`"
                :model-value="typeof entry.row.body === 'string' ? entry.row.body : ''"
                type="textarea"
                :rows="3"
                data-test="pw-param-body"
                :disabled="!methodAllowsBody(entry.row.method)"
                :placeholder="methodAllowsBody(entry.row.method) ? t('perf.wizard.paramBodyPh') : t('perf.wizard.paramBodyNaNote', { method: entry.row.method })"
                @update:model-value="(v: string) => (entry.row.body = v)"
                @focus="tokenTarget = { i: entry.index, slot: 'body' }"
              />
            </div>
            <div class="pw__param-pair">
              <div class="pw__param-group">
                <label :for="`pw-param-status-${entry.index}`">{{ t('perf.wizard.paramExpectStatus') }}</label>
                <el-input-number
                  :id="`pw-param-status-${entry.index}`"
                  :model-value="statusOf(entry.row)"
                  :min="STATUS_MIN"
                  :max="STATUS_MAX"
                  controls-position="right"
                  :placeholder="t('perf.wizard.paramExpectNa')"
                  data-test="pw-param-status"
                  class="pw__param-num"
                  @update:model-value="(v: number | undefined) => setStatus(entry.row, v ?? null)"
                />
              </div>
              <div class="pw__param-group">
                <label :for="`pw-param-saveas-${entry.index}`">{{ t('perf.wizard.paramSaveAs') }}</label>
                <el-input
                  :id="`pw-param-saveas-${entry.index}`"
                  v-model="entry.row.saveAs"
                  :class="{ 'pw__param-invalid': saveAsInvalid(entry.row) }"
                  :placeholder="t('perf.wizard.paramSaveAsPh')"
                  data-test="pw-param-saveas"
                  @focus="tokenTarget = { i: entry.index, slot: 'saveAs' }"
                />
                <p v-if="saveAsInvalid(entry.row)" class="pw__param-error">{{ t('perf.wizard.paramSaveAsInvalid') }}</p>
              </div>
            </div>
          </div>
          </div>
          <div v-if="stepPadBottom > 0" class="pw__step-pad" :style="{ height: `${stepPadBottom}px` }" aria-hidden="true" />
        </div>
        <p v-if="stepKeyword.trim() && filteredStepEntries.length === 0" class="pw__hint" data-test="pw-step-no-match">
          {{ t('perf.wizard.stepNoMatch', { n: form.steps.length }) }}
        </p>
        <div class="pw__step-toolbar">
          <el-button link type="primary" data-test="pw-step-add" @click="addStep">{{ t('perf.wizard.addStep') }}</el-button>
          <el-button link type="primary" data-test="pw-params-expand-all" @click="expandAllParams">{{ t('perf.wizard.paramsExpandAll') }}</el-button>
          <el-button link type="primary" data-test="pw-params-collapse-all" @click="collapseAllParams">{{ t('perf.wizard.paramsCollapseAll') }}</el-button>
        </div>
        <p class="pw__hint">{{ t('perf.wizard.stepsHint') }}</p>
      </div>
      <div class="pw__field">
        <label for="pw-dataset-input">{{ t('perf.wizard.dataset') }}</label>
        <div class="pw__ref-row pw__ref-row--dataset">
          <el-select
            v-model="form.datasetId"
            id="pw-dataset-input"
            filterable
            clearable
            :loading="datasetLoading"
            :placeholder="t('perf.wizard.datasetPh')"
            data-test="pw-dataset-select"
            class="pw__control"
            @change="onDatasetChange"
          >
            <el-option
              v-for="d in datasetOptions"
              :key="d.id"
              :label="`${d.name} (${t('perf.wizard.datasetRows', { n: d.row_count })})`"
              :value="d.id"
            >
              <span class="pw__opt-name">{{ d.name }}</span>
              <span class="pw__opt-meta">({{ t('perf.wizard.datasetRows', { n: d.row_count }) }})</span>
            </el-option>
            <template #empty>
              <el-empty :image-size="40" :description="t('common.noData')" />
            </template>
            <template v-if="datasetHasMore" #footer>
              <el-button link type="primary" :loading="datasetLoading" data-test="pw-dataset-loadmore" @click="loadDatasets(false)">
                {{ t('common.loadMore') }} ({{ datasetOptions.length }}/{{ datasetTotal }})
              </el-button>
            </template>
          </el-select>
          <el-button :loading="datasetUploading" data-test="pw-dataset-upload" @click="pickDatasetFile">{{ t('perf.wizard.datasetUpload') }}</el-button>
          <input ref="datasetFileInput" type="file" accept=".csv,text/csv" class="pw__file-hidden" data-test="pw-dataset-file" @change="onDatasetFilePicked" />
        </div>
        <p class="pw__hint">{{ t('perf.wizard.datasetHint') }}</p>
        <div v-if="datasetColumns.length" class="pw__var-chips" data-test="pw-dataset-columns">
          <span class="pw__var-chips-label">{{ t('perf.wizard.datasetVars') }}</span>
          <!-- : chips are click-to-insert token helpers (they land in the
 last focused parameter slot; the save gate keeps judging). -->
          <el-tag
            v-for="c in datasetColumns"
            :key="c"
            size="small"
            type="info"
            class="pw__var-chip"
            data-test="pw-var-chip"
            @click="insertVarToken(c)"
          >{{ varToken(c) }}</el-tag>
        </div>
      </div>
      <div v-if="form.datasetId" class="pw__field">
        <span id="pw-alloc-group-label" class="pw__caption">{{ t('perf.wizard.allocMode') }}</span>
        <el-radio-group v-model="form.rowAllocMode" data-test="pw-alloc-group" class="pw__alloc" aria-labelledby="pw-alloc-group-label">
          <el-radio :value="null">{{ t('perf.wizard.allocServerDefault', { mode: allocPolicy?.defaultMode ? t(`perf.allocDesc.${allocPolicy.defaultMode}`) : '—' }) }}</el-radio>
          <el-radio v-for="m in allocModeOptions" :key="m" :value="m" :disabled="!isModeSupported(m)" data-test="pw-alloc-option">
            {{ t(`perf.allocDesc.${m}`) }}
            <span v-if="!isModeSupported(m)" class="pw__alloc-reason">{{ allocUnsupportedReason(m) }}</span>
          </el-radio>
        </el-radio-group>
        <p class="pw__hint">{{ t('perf.wizard.allocHint') }}</p>
      </div>
    </div>

    <!-- Step 2: pick one of the seven pressure models (same value domain as
         PerfScenarioServiceImpl.PRESSURE_MODELS on the server — no smaller UI enum) -->
    <div v-show="step === 1" class="pw__body" data-test="pw-step-pressure">
      <!-- W-d engine pin: options are served by the catalogue door (values =
           core dispatch domain, caveat = the server's own caliber wording,
           rendered verbatim). Clearing the select falls back to default
           routing — the same null the server resolves at dispatch. -->
      <div class="pw__field">
        <label for="pw-engine-input">{{ t('perf.wizard.engine') }}</label>
        <el-select
          v-model="form.engine"
          id="pw-engine-input"
          clearable
          filterable
          :loading="engineLoading"
          :placeholder="t('perf.wizard.enginePh')"
          data-test="pw-engine"
          popper-class="tp-pw-engine-popper"
          class="pw__control"
        >
          <el-option
            v-for="o in engineOptions"
            :key="o.engine"
            :label="t(`perf.engineName.${o.engine}`)"
            :value="o.engine"
          />
        </el-select>
        <p v-if="engineCaveat" class="pw__hint pw__hint--block" data-test="pw-engine-caveat">{{ engineCaveat }}</p>
        <p v-else class="pw__hint">{{ t('perf.wizard.engineHint') }}</p>
      </div>
      <el-radio-group v-model="form.pressureModel" data-test="pw-model" class="pw__models">
        <el-radio value="CONSTANT_RPS">{{ t('perf.model.CONSTANT_RPS') }}</el-radio>
        <el-radio value="RAMP">{{ t('perf.model.RAMP') }}</el-radio>
        <el-radio value="SPIKE">{{ t('perf.model.SPIKE') }}</el-radio>
        <el-radio value="FIXED_VU" data-test="pw-model-fixed-vu">{{ t('perf.model.FIXED_VU') }}</el-radio>
        <el-radio value="LADDER" data-test="pw-model-ladder">{{ t('perf.model.LADDER') }}</el-radio>
        <el-radio value="BUSINESS_CURVE" data-test="pw-model-curve">{{ t('perf.model.BUSINESS_CURVE') }}</el-radio>
        <el-radio value="SOAK" data-test="pw-model-soak">{{ t('perf.model.SOAK') }}</el-radio>
      </el-radio-group>
      <p class="pw__hint pw__hint--block">{{ t(`perf.modelDesc.${form.pressureModel}`) }}</p>

      <template v-if="form.pressureModel === 'CONSTANT_RPS'">
        <div class="pw__field pw__field--pair">
          <div>
            <label for="pw-target-rps-input">{{ t('perf.wizard.targetRps') }}</label>
            <el-input-number v-model="form.targetRps" id="pw-target-rps-input" :min="1" :max="10000" controls-position="right" data-test="pw-target-rps" class="pw__control" />
          </div>
          <div>
            <label for="pw-duration-input">{{ t('perf.wizard.duration') }}</label>
            <el-input-number v-model="form.durationSeconds" id="pw-duration-input" :min="1" :max="86400" controls-position="right" data-test="pw-duration" class="pw__control" />
          </div>
        </div>
      </template>
      <template v-else-if="form.pressureModel === 'RAMP'">
        <div class="pw__field pw__field--quad">
          <div>
            <label for="pw-start-rps-input">{{ t('perf.wizard.startRps') }}</label>
            <el-input-number v-model="form.startRps" id="pw-start-rps-input" :min="1" controls-position="right" data-test="pw-start-rps" class="pw__control" />
          </div>
          <div>
            <label for="pw-peak-rps-input">{{ t('perf.wizard.peakRps') }}</label>
            <el-input-number v-model="form.peakRps" id="pw-peak-rps-input" :min="1" controls-position="right" data-test="pw-peak-rps" class="pw__control" />
          </div>
          <div>
            <label for="pw-ramp-seconds-input">{{ t('perf.wizard.rampSeconds') }}</label>
            <el-input-number v-model="form.rampSeconds" id="pw-ramp-seconds-input" :min="1" controls-position="right" class="pw__control" />
          </div>
          <div>
            <label for="pw-hold-seconds-input">{{ t('perf.wizard.holdSeconds') }}</label>
            <el-input-number v-model="form.holdSeconds" id="pw-hold-seconds-input" :min="1" controls-position="right" class="pw__control" />
          </div>
        </div>
      </template>
      <template v-else-if="form.pressureModel === 'SPIKE'">
        <div class="pw__field pw__field--triple">
          <div>
            <label for="pw-base-rps-input">{{ t('perf.wizard.baseRps') }}</label>
            <el-input-number v-model="form.baseRps" id="pw-base-rps-input" :min="1" controls-position="right" data-test="pw-base-rps" class="pw__control" />
          </div>
          <div>
            <label for="pw-peak-rps-input">{{ t('perf.wizard.peakRps') }}</label>
            <el-input-number v-model="form.peakRps" id="pw-peak-rps-input" :min="1" controls-position="right" data-test="pw-peak-rps" class="pw__control" />
          </div>
          <div>
            <label for="pw-duration-input">{{ t('perf.wizard.duration') }}</label>
            <el-input-number v-model="form.durationSeconds" id="pw-duration-input" :min="1" :max="86400" controls-position="right" data-test="pw-duration" class="pw__control" />
          </div>
        </div>
      </template>
      <template v-else-if="form.pressureModel === 'FIXED_VU'">
        <div class="pw__field pw__field--quad">
          <div>
            <label for="pw-vu-input">{{ t('perf.wizard.vu') }}</label>
            <el-input-number v-model="form.vu" id="pw-vu-input" :min="1" :max="10000" controls-position="right" data-test="pw-vu" class="pw__control" />
          </div>
          <div>
            <label for="pw-loops-input">{{ t('perf.wizard.loops') }}</label>
            <el-input-number v-model="form.loops" id="pw-loops-input" :min="1" controls-position="right" data-test="pw-loops" class="pw__control" />
          </div>
          <div>
            <label for="pw-fv-duration-input">{{ t('perf.wizard.duration') }}</label>
            <el-input-number v-model="form.durationSeconds" id="pw-fv-duration-input" :min="0" :max="86400" controls-position="right" data-test="pw-fv-duration" class="pw__control" />
          </div>
          <div>
            <label for="pw-think-input">{{ t('perf.wizard.thinkTimeMs') }}</label>
            <el-input-number v-model="form.thinkTimeMs" id="pw-think-input" :min="0" :step="100" controls-position="right" data-test="pw-think" class="pw__control" />
          </div>
        </div>
        <p class="pw__hint">{{ t('perf.wizard.loopsHint') }}</p>
      </template>
      <template v-else-if="form.pressureModel === 'LADDER'">
        <div class="pw__field pw__field--quad">
          <div>
            <label for="pw-ld-start-input">{{ t('perf.wizard.startRps') }}</label>
            <el-input-number v-model="form.startRps" id="pw-ld-start-input" :min="1" controls-position="right" data-test="pw-ladder-start" class="pw__control" />
          </div>
          <div>
            <label for="pw-ld-steprps-input">{{ t('perf.wizard.stepRps') }}</label>
            <el-input-number v-model="form.stepRps" id="pw-ld-steprps-input" :min="1" controls-position="right" data-test="pw-ladder-steprps" class="pw__control" />
          </div>
          <div>
            <label for="pw-ld-stepsec-input">{{ t('perf.wizard.stepSeconds') }}</label>
            <el-input-number v-model="form.stepSeconds" id="pw-ld-stepsec-input" :min="1" controls-position="right" data-test="pw-ladder-stepseconds" class="pw__control" />
          </div>
          <div>
            <label for="pw-ld-steps-input">{{ t('perf.wizard.ladderSteps') }}</label>
            <el-input-number v-model="form.ladderSteps" id="pw-ld-steps-input" :min="1" :max="100" controls-position="right" data-test="pw-ladder-steps" class="pw__control" />
          </div>
        </div>
      </template>
      <template v-else-if="form.pressureModel === 'SOAK'">
        <div class="pw__field pw__field--pair">
          <div>
            <label for="pw-soak-rps-input">{{ t('perf.wizard.targetRps') }}</label>
            <el-input-number v-model="form.targetRps" id="pw-soak-rps-input" :min="1" :max="10000" controls-position="right" data-test="pw-soak-rps" class="pw__control" />
          </div>
          <div>
            <label for="pw-soak-hours-input">{{ t('perf.wizard.durationHours') }}</label>
            <el-input-number v-model="form.durationHours" id="pw-soak-hours-input" :min="1" :max="720" controls-position="right" data-test="pw-soak-hours" class="pw__control" />
          </div>
        </div>
      </template>
      <template v-else-if="form.pressureModel === 'BUSINESS_CURVE'">
        <div class="pw__field pw__field--pair">
          <div>
            <label for="pw-bc-peak-input">{{ t('perf.wizard.peakRps') }}</label>
            <el-input-number v-model="form.peakRps" id="pw-bc-peak-input" :min="1" controls-position="right" data-test="pw-curve-peak" class="pw__control" />
          </div>
          <div>
            <label for="pw-bc-duration-input">{{ t('perf.wizard.duration') }}</label>
            <el-input-number v-model="form.durationSeconds" id="pw-bc-duration-input" :min="1" :max="86400" controls-position="right" data-test="pw-curve-duration" class="pw__control" />
          </div>
        </div>
        <!-- Hour weights live in a collapsed section by default (worst-scale iron
             law: 24 controls never sit naked on the main flow), while the filled
             hours stay visible as chips (SE-2: no invisible configured filters). -->
        <div class="pw__field">
          <el-button link type="primary" data-test="pw-curve-toggle" :aria-expanded="form.curveExpanded" @click="form.curveExpanded = !form.curveExpanded">
            <el-icon class="pw__curve-toggle-icon" :class="{ 'is-expanded': form.curveExpanded }"><ArrowRight /></el-icon>
            {{ form.curveExpanded
                ? t('perf.wizard.curveCollapse', { filled: curveFilledCount })
                : t('perf.wizard.curveToggle', { filled: curveFilledCount }) }}
          </el-button>
          <div v-show="curveFilledCount" class="pw__var-chips" data-test="pw-curve-chips">
            <el-tag size="small" type="info" class="pw__var-chip">{{ curveChipText }}</el-tag>
          </div>
          <div v-show="form.curveExpanded" class="pw__curve-grid" data-test="pw-curve-grid">
            <div v-for="(w, h) in form.curveHours" :key="h" class="pw__curve-slot">
              <label :for="`pw-curve-hour-${h}`">{{ t('perf.wizard.curveHour', { h }) }}</label>
              <el-input-number v-model="form.curveHours[h]" :id="`pw-curve-hour-${h}`" :min="0" :max="100" :step="5" controls-position="right" :data-test="`pw-curve-h${h}`" class="pw__control" />
            </div>
            <el-button link type="info" data-test="pw-curve-clear" class="pw__curve-clear" @click="clearCurveHours">{{ t('perf.wizard.curveClearAll') }}</el-button>
          </div>
        </div>
      </template>
      <!-- U-2 : SVG polyline preview derived from the same arithmetic
 the engine templates compile, plus the caliber correction line —
 authors see the shape and the axis unit before submitting. -->
      <div class="pw__preview" data-test="pw-pressure-preview">
        <p class="pw__preview-title">{{ t('perf.wizard.previewTitle') }}</p>
        <svg v-if="pressurePreviewD" class="pw__preview-svg" viewBox="0 0 320 96"
             role="img" :aria-label="t('perf.wizard.previewTitle')" data-test="pw-preview-svg">
          <!-- Vision close-out : a faint baseline anchors the polyline —
 without it a flat full-height line read as an empty box to the
 vision reviewer. Display-only reference, no scheduling claim. -->
          <line class="pw__preview-axis" x1="6" y1="90" x2="314" y2="90" role="presentation" />
          <path :d="pressurePreviewD" fill="none" stroke="currentColor" stroke-width="2" />
        </svg>
        <p v-else class="pw__hint" data-test="pw-preview-none">{{ t('perf.wizard.previewNone') }}</p>
        <p class="pw__hint pw__hint--block" data-test="pw-pressure-caliber">{{
            form.pressureModel === 'FIXED_VU'
              ? t('perf.wizard.caliberVu')
              : t('perf.wizard.caliberRps')
          }}</p>
      </div>
      <p class="pw__estimate" data-test="pw-estimate">
        {{ t('perf.wizard.estimate') }}：{{ estimatedText }}（{{
            form.pressureModel === 'FIXED_VU'
                ? t('perf.wizard.peakConcurrencyVu')
                : t('perf.wizard.peakConcurrency')
          }}≈{{ peakConcurrency }}）
      </p>
    </div>

    
    <div v-show="step === 2" class="pw__body" data-test="pw-step-sla">
      <div class="pw__field pw__field--pair">
        <div>
          <label for="pw-sla-p95-input">{{ t('perf.wizard.slaP95') }}</label>
          <el-input-number v-model="form.slaP95Ms" id="pw-sla-p95-input" :min="1" controls-position="right" data-test="pw-sla-p95" class="pw__control" />
        </div>
        <div>
          <label for="pw-sla-minrps-input">{{ t('perf.wizard.slaMinRps') }}</label>
          <el-input-number v-model="form.slaMinRps" id="pw-sla-minrps-input" :min="1" controls-position="right" data-test="pw-sla-minrps" class="pw__control" />
        </div>
      </div>
      <div class="pw__field pw__field--pair">
        <div>
          <label for="pw-sla-error-input">{{ t('perf.wizard.slaErrorRate') }}</label>
          <el-input-number v-model="form.slaErrorRate" id="pw-sla-error-input" :min="0" :max="1" :step="0.01" :precision="3" controls-position="right" data-test="pw-sla-error" class="pw__control" />
        </div>
        <div>
          <label for="pw-sla-breaker-input">{{ t('perf.wizard.slaBreaker') }}</label>
          <el-input-number v-model="form.slaBreakerErrorRate" id="pw-sla-breaker-input" :min="0" :max="1" :step="0.01" :precision="3" controls-position="right" data-test="pw-sla-breaker" class="pw__control" />
        </div>
      </div>
      <p class="pw__hint">{{ t('perf.wizard.rateHint') }}</p>
      <div class="pw__field">
        <span class="pw__caption">{{ t('perf.wizard.loginStrategy') }}</span>
        <el-radio-group v-model="form.loginStrategy" data-test="pw-login-strategy">
          <el-radio value="PER_VUSER">{{ t('perf.login.PER_VUSER') }}</el-radio>
          <el-radio value="PER_SESSION_N">{{ t('perf.login.PER_SESSION_N') }}</el-radio>
          <el-radio value="SHARED">{{ t('perf.login.SHARED') }}</el-radio>
        </el-radio-group>
        <el-input-number
          v-if="form.loginStrategy === 'PER_SESSION_N'"
          v-model="form.sessionN"
          :min="1"
          :max="1000"
          controls-position="right"
          data-test="pw-session-n"
        />
      </div>
      <div class="pw__field">
        <label for="pw-driver-limit-input">{{ t('perf.wizard.driverLimit') }}</label>
        <el-input-number v-model="form.driverLimit" id="pw-driver-limit-input" :min="1" :max="64" controls-position="right" data-test="pw-driver-limit" class="pw__control" />
        <p class="pw__hint">{{ t('perf.wizard.driverLimitHint') }}</p>
      </div>
      <!-- B-8 scheduled firing: enable + cron + pinned env, previewed by
 the server door (same arithmetic the firing scanner uses). -->
      <div class="pw__field" data-test="pw-schedule-section">
        <div class="pw__journey-head">
          <span id="pw-schedule-label" class="pw__caption">{{ t('perf.wizard.scheduleSection') }}</span>
          <el-switch
            v-model="form.scheduleEnabled"
            id="pw-schedule-switch"
            :aria-labelledby="'pw-schedule-label'"
            data-test="pw-schedule-switch"
          />
        </div>
        <div class="pw__ref-row pw__ref-row--schedule">
          <el-input
            v-model="form.scheduleCron"
            :placeholder="t('perf.wizard.scheduleCronPh')"
            :disabled="!form.scheduleEnabled"
            maxlength="100"
            aria-labelledby="pw-schedule-label"
            data-test="pw-schedule-cron"
            class="pw__control--grow"
          />
          <el-button
            :disabled="!form.scheduleEnabled || !form.scheduleCron.trim()"
            :loading="schedulePreviewing"
            data-test="pw-schedule-preview"
            @click="previewScheduleFires"
          >
            {{ t('perf.wizard.schedulePreview') }}
          </el-button>
        </div>
        <div class="pw__field">
          <label for="pw-schedule-env-input">{{ t('perf.wizard.scheduleEnv') }}</label>
          <el-select v-model="form.scheduleEnvId" id="pw-schedule-env-input" clearable filterable :placeholder="t('perf.wizard.envPh')" data-test="pw-schedule-env" class="pw__control">
            <el-option v-for="e in envs" :key="e.id" :label="e.name" :value="e.id" />
          </el-select>
          <p class="pw__hint">{{ t('perf.wizard.scheduleEnvHint') }}</p>
        </div>
        <div data-test="pw-schedule-fires">
          <p v-if="!scheduleFires.length" class="pw__hint">{{ t('perf.wizard.scheduleFiresEmpty') }}</p>
          <ul v-else class="pw__fire-list">
            <li v-for="(f, i) in scheduleFires" :key="f" class="pw__fire-row">{{ i + 1 }}. {{ formatFireTime(f) }}</li>
          </ul>
        </div>
      </div>
    </div>

    
    <div v-show="step === 3" class="pw__body" data-test="pw-step-gate">
      <div class="pw__field">
        <label for="pw-isolation-input">{{ t('perf.wizard.isolationMode') }}</label>
        <el-select v-model="form.isolationMode" id="pw-isolation-input" class="pw__control" data-test="pw-isolation">
          <el-option v-for="m in PERF_ISOLATION_MODES" :key="m" :label="t(`perf.isolation.${m}`)" :value="m" />
        </el-select>
        <p class="pw__hint">{{ t(`perf.isolationDesc.${form.isolationMode}`) }}</p>
      </div>
      <el-button type="primary" :loading="gateChecking" data-test="pw-precheck" @click="runPrecheck">
        {{ t('perf.wizard.precheck') }}
      </el-button>
      <div v-if="gate" class="pw__gate" :class="gate.passed ? 'pw__gate--ok' : 'pw__gate--blocked'" data-test="pw-gate-result">
        <span class="pw__gate-dot">{{ gate.passed ? '🟢' : '🔴' }}</span>
        <div class="pw__gate-main">
          <p class="pw__gate-gate">{{ t('perf.gate.' + gate.gate) }}</p>
          <p class="pw__gate-reason">{{ gate.reason }}</p>
          <!-- CF-8 explainable ladder: every door reports its own state, not only the first rejection. -->
          <PerfGateLadder :steps="gate.steps" />
        </div>
      </div>
      <p v-else-if="!gateChecking" class="pw__hint">{{ t('perf.wizard.precheckHint') }}</p>
      <div class="pw__gate-actions">
        <el-button link type="primary" data-test="pw-open-whitelist" @click="wlDialog = true">{{ t('perf.wizard.whitelistEntry') }}</el-button>
        <el-button link type="primary" data-test="pw-open-approval" @click="apDialog = true">{{ t('perf.wizard.approvalEntry') }}</el-button>
      </div>
    </div>

    <!-- ⑤Pre-launch summary card (U-5): peak / duration / server-side budget / breaker /
         SLA gates / isolation on one screen, no folding. Budget numbers come from the
         precheck response single source — the wizard never recomputes the margin. -->
    <div v-show="step === 4" class="pw__body" data-test="pw-step-confirm">
      <el-descriptions :column="1" border size="small" data-test="pw-summary-card">
        <el-descriptions-item :label="t('perf.wizard.name')">{{ form.name }}</el-descriptions-item>
        <el-descriptions-item :label="t('perf.wizard.env')">{{ envs.find((e) => e.id === form.envId)?.name ?? '—' }}</el-descriptions-item>
        <el-descriptions-item :label="t('perf.wizard.steps')">{{ form.steps.length }} {{ t('perf.wizard.stepsCountUnit') }}</el-descriptions-item>
        <el-descriptions-item :label="t('perf.wizard.model')">{{ t(`perf.model.${form.pressureModel}`) }}</el-descriptions-item>
        <el-descriptions-item :label="t('perf.wizard.estimate')">{{ estimatedText }}</el-descriptions-item>
        <el-descriptions-item :label="t('perf.wizard.peakRpsSummary')"><span data-test="pw-sum-peak">{{ peakConcurrency }} RPS</span></el-descriptions-item>
        <el-descriptions-item :label="t('perf.wizard.budgetRow')"><span data-test="pw-sum-budget">{{ budgetText }}</span></el-descriptions-item>
        <el-descriptions-item :label="t('perf.wizard.breakerRow')"><span data-test="pw-sum-breaker">{{ breakerText }}</span></el-descriptions-item>
        <el-descriptions-item :label="t('perf.wizard.slaSummary')">
          <ul class="pw__sum-list" data-test="pw-sum-sla">
            <li v-for="row in slaRows" :key="row.label">{{ row.label }}：{{ row.value }}</li>
          </ul>
        </el-descriptions-item>
        <el-descriptions-item :label="t('perf.wizard.isolationMode')">
          <span data-test="pw-sum-isolation">{{ form.isolationMode ? t(`perf.isolation.${form.isolationMode}`) : t('perf.wizard.isolationNotDeclared') }}</span>
        </el-descriptions-item>
        <el-descriptions-item :label="t('perf.wizard.dataset')">
          <span data-test="pw-sum-dataset">{{ form.datasetId ? datasetDisplayName : t('perf.wizard.datasetNone') }}</span>
        </el-descriptions-item>
        <el-descriptions-item :label="t('perf.wizard.allocMode')">
          <span data-test="pw-sum-alloc">{{ form.rowAllocMode ? t(`perf.allocDesc.${form.rowAllocMode}`) : t('perf.wizard.allocServerDefault', { mode: allocPolicy?.defaultMode ? t(`perf.allocDesc.${allocPolicy.defaultMode}`) : '—' }) }}</span>
        </el-descriptions-item>
        <el-descriptions-item :label="t('perf.wizard.loginStrategy')">{{ effectiveLoginStrategy() }}</el-descriptions-item>
        <el-descriptions-item :label="t('perf.wizard.precheckResult')">
          <span data-test="pw-sum-precheck">{{ gate ? (gate.passed ? t('perf.wizard.gatePass') : t('perf.wizard.gateBlock')) : t('perf.wizard.gateSkip') }}</span>
        </el-descriptions-item>
      </el-descriptions>
      <p class="pw__hint">{{ t('perf.wizard.confirmHint') }}</p>
    </div>

    <template #footer>
      <el-button v-if="step > 0" @click="step -= 1">{{ t('perf.wizard.prev') }}</el-button>
      <el-button v-if="step < 4" type="primary" data-test="pw-next" @click="nextStep">{{ t('perf.wizard.next') }}</el-button>
      <el-button v-if="step === 4" type="primary" :loading="submitting" data-test="pw-submit" @click="submit">
        {{ editingId ? t('perf.wizard.saveEdit') : t('perf.wizard.submit') }}
      </el-button>
    </template>

    
    <el-dialog v-model="wlDialog" :title="t('perf.wl.title')" width="480px" append-to-body data-test="pw-wl-dialog">
      <div class="pw__field">
        <label for="pw-wl-concurrency-input">
          {{ t('perf.wl.maxConcurrency') }}
          <el-tooltip :content="t('perf.wl.maxConcurrencyTip')" placement="top">
            <el-icon class="pw__tip-icon"><QuestionFilled /></el-icon>
          </el-tooltip>
        </label>
        <el-input-number v-model="wlForm.maxConcurrency" id="pw-wl-concurrency-input" :min="1" controls-position="right" class="pw__control" data-test="pw-wl-concurrency" />
      </div>
      <div class="pw__field">
        <label for="pw-wl-modes-input">{{ t('perf.wl.allowedModes') }}</label>
        <el-select v-model="wlForm.allowedModes" id="pw-wl-modes-input" multiple class="pw__control" data-test="pw-wl-modes">
          <el-option v-for="m in PERF_ISOLATION_MODES" :key="m" :label="t(`perf.isolation.${m}`)" :value="m" />
        </el-select>
      </div>
      <p class="pw__hint">{{ t('perf.wl.hint') }}</p>
      <template #footer>
        <el-button @click="wlDialog = false">{{ t('perf.wizard.prev') }}</el-button>
        <el-button type="primary" data-test="pw-wl-save" @click="saveWhitelist">{{ t('perf.wl.save') }}</el-button>
      </template>
    </el-dialog>

    
    <el-dialog v-model="apDialog" :title="t('perf.applied.title')" width="480px" append-to-body data-test="pw-ap-dialog">
      <div class="pw__field">
        <label for="pw-ap-concurrency-input">
          {{ t('perf.applied.concurrency') }}
          <el-tooltip :content="t('perf.applied.concurrencyTip')" placement="top">
            <el-icon class="pw__tip-icon"><QuestionFilled /></el-icon>
          </el-tooltip>
        </label>
        <el-input-number v-model="apForm.concurrency" id="pw-ap-concurrency-input" :min="1" controls-position="right" class="pw__control" data-test="pw-ap-concurrency" />
      </div>
      <div class="pw__field">
        <label for="pw-ap-isolation-input">{{ t('perf.wizard.isolationMode') }}</label>
        <el-select v-model="apForm.isolationMode" id="pw-ap-isolation-input" class="pw__control">
          <el-option v-for="m in PERF_ISOLATION_MODES" :key="m" :label="t(`perf.isolation.${m}`)" :value="m" />
        </el-select>
      </div>
      <div class="pw__field">
        <label for="pw-ap-reason-input">{{ t('perf.applied.reason') }}</label>
        <el-input v-model="apForm.reason" id="pw-ap-reason-input" type="textarea" :rows="2" maxlength="200" show-word-limit data-test="pw-ap-reason" />
        <p class="pw__hint">{{ t('perf.applied.reasonHint') }}</p>
      </div>
      <!-- D8: approval-scenario link shown as read-only when the scenario is already
           persisted (edit lane). A brand-new unsaved scenario has no id to link, so the
           row is honestly absent instead of carrying a dangling reference. -->
      <p v-if="editingId" class="pw__hint" data-test="pw-ap-scenario-link">
        {{ t('perf.applied.scenarioLink') }}
      </p>
      <template #footer>
        <el-button @click="apDialog = false">{{ t('perf.wizard.prev') }}</el-button>
        <el-button type="primary" data-test="pw-ap-save" @click="applyApproval">{{ t('perf.applied.save') }}</el-button>
      </template>
    </el-dialog>

    <!-- : interface-set import panel — frozen-order windowed rows, id-keyed
 selection across filtering, sticky action bar, chunked generation.
 Worst scale 500 requests: only the scroll window renders (pads keep
 the scrollbar honest); the action bar stays reachable without scroll. -->
    <el-dialog v-model="importOpen" :title="t('perf.wizard.importTitle')" width="760px" append-to-body data-test="pw-import-panel">
      <div class="pw__imp" tabindex="-1" @keydown="onImportKeydown">
        <div class="pw__imp-bar">
          <el-input v-model="importKeyword" :placeholder="t('perf.wizard.importSearchPh')" data-test="pw-import-search" clearable class="pw__imp-search" />
          <el-button link type="primary" data-test="pw-import-select-all" @click="selectImportAllVisible">{{ t('perf.wizard.importSelectAll') }}</el-button>
        </div>
        <ul
          v-show="filteredImportRows.length > 0"
          ref="importListEl"
          v-loading="importLoading"
          class="pw__imp-list"
          data-test="pw-import-rows"
          @scroll.passive="onImportListScroll"
        >
          <li v-if="importPadTop > 0" class="pw__imp-pad" :style="{ height: `${importPadTop}px` }" aria-hidden="true" />
          <li
            v-for="r in importVisible"
            :key="r.id"
            class="pw__imp-row"
            data-windowed-row
            tabindex="0"
            :data-test="`pw-import-row-${r.id}`"
            @focus="importFocusId = r.id"
            @dblclick="importSingle(r)"
          >
            <el-checkbox
              :model-value="importSelected.has(r.id)"
              :aria-label="`${r.method} ${r.name}`"
              data-test="pw-import-check"
              @change="toggleImportRow(r.id)"
            />
            <span class="pw__imp-method">{{ r.method }}</span>
            <span class="pw__imp-path" :title="r.urlPath">{{ r.urlPath }}</span>
            <span class="pw__imp-name">{{ r.name }}</span>
            <!-- cross-set provenance badge — every candidate row
 names the set it came from (pick order = between-set order). -->
            <el-tag v-if="refCollectionIds.length > 1" size="small" type="info" data-test="pw-import-origin">
              {{ originNameOf(r.collectionId) }}
            </el-tag>
            <el-tag v-if="effectiveOf(r.id)?.authSource === 'request' || effectiveOf(r.id)?.authSource === 'collection'" size="small" type="warning">
              {{ t('perf.wizard.importAuthTag') }}
            </el-tag>
          </li>
          <li v-if="importPadBottom > 0" class="pw__imp-pad" :style="{ height: `${importPadBottom}px` }" aria-hidden="true" />
        </ul>
        <p v-if="filteredImportRows.length === 0" class="pw__imp-empty" data-test="pw-import-empty">{{ t('perf.wizard.importEmpty') }}</p>
        <div class="pw__imp-foot">
          <span data-test="pw-import-count">{{ t('perf.wizard.importCount', { n: importSelected.size }) }}</span>
          <el-button
            type="primary"
            data-test="pw-import-run"
            :disabled="importSelected.size === 0 || importBusy"
            @click="requestImport"
          >
            {{ t('perf.wizard.importRun') }}
          </el-button>
        </div>
      </div>
    </el-dialog>

    <!-- cross-set merge preview — the exact step sequence that
 will be generated (pick order between sets, frozen order inside a
 set), each row badged with its source set; duplicate method+path
 rows are flagged, never silently dropped. Commit runs only from the
 confirm button; the chunked progress line lives here. -->
    <el-dialog v-model="importPreviewVisible" :title="t('perf.wizard.importPreviewTitle')" width="760px" append-to-body data-test="pw-import-preview">
      <p v-if="duplicateKeys.size > 0" class="pw__imp-duphint" data-test="pw-import-dup-hint">
        {{ t('perf.wizard.importDupHint', { n: duplicateKeys.size }) }}
      </p>
      <ul
        ref="previewListEl"
        class="pw__imp-list"
        data-test="pw-import-preview-rows"
        @scroll.passive="onPreviewListScroll"
      >
        <li v-if="previewPadTop > 0" class="pw__imp-pad" :style="{ height: `${previewPadTop}px` }" aria-hidden="true" />
        <li v-for="(r, i) in previewVisible" :key="`${r.id}-${i}`" class="pw__imp-row" data-windowed-row>
          <span class="pw__imp-ordinal">{{ importPreviewStart + i + 1 }}</span>
          <span class="pw__imp-method">{{ r.method }}</span>
          <span class="pw__imp-path" :title="r.urlPath">{{ r.urlPath }}</span>
          <span class="pw__imp-name">{{ r.name }}</span>
          <el-tag size="small" type="info" data-test="pw-import-origin">{{ originNameOf(r.collectionId) }}</el-tag>
          <el-tag v-if="duplicateKeys.has(duplicateKeyOf(r))" size="small" type="danger" data-test="pw-import-dup-tag">
            {{ t('perf.wizard.importDupTag') }}
          </el-tag>
        </li>
        <li v-if="previewPadBottom > 0" class="pw__imp-pad" :style="{ height: `${previewPadBottom}px` }" aria-hidden="true" />
      </ul>
      <template #footer>
        <span v-if="importBusy" class="pw__imp-progress" data-test="pw-import-progress" aria-live="polite">
          {{ t('perf.wizard.importProgress', { done: importGenDone, total: importGenTotal }) }}
        </span>
        <el-button :disabled="importBusy" data-test="pw-import-cancel" @click="cancelImportPreview">{{ t('perf.wizard.importCancel') }}</el-button>
        <el-button type="primary" data-test="pw-import-confirm" :disabled="importBusy" :loading="importBusy" @click="confirmImport">
          {{ t('perf.wizard.importPreviewConfirm', { n: importPreview?.length ?? 0 }) }}
        </el-button>
      </template>
    </el-dialog>
  </el-dialog>
</template>

<style scoped>
.pw__steps { margin-bottom: 20px; }
.pw__body { min-height: 240px; }
.pw__field { margin-bottom: 16px; }
.pw__field > label, .pw__field > .pw__caption,
.pw__field--pair > div > label, .pw__field--triple > div > label, .pw__field--quad > div > label {
  display: block; font-size: 13px; color: var(--el-text-color-secondary); margin-bottom: 6px;
}
.pw__control { width: 100%; }
.pw__field--pair { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.pw__field--triple { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 12px; }
.pw__field--quad { display: grid; grid-template-columns: 1fr 1fr 1fr 1fr; gap: 10px; }
/* BUSINESS_CURVE hour weights: compact 6×4 grid of hour slots, collapsed by default */
.pw__curve-grid { display: grid; grid-template-columns: repeat(6, 1fr); gap: 12px; margin-top: 8px; align-items: end; }
.pw__curve-slot { display: flex; flex-direction: column; gap: 2px; }
.pw__curve-slot label { font-size: 12px; color: var(--el-text-color-secondary); }
/* Collapse toggle reads as a state control: chevron turns when opened, and the
   link label switches to the collapse verb (R22: verbs name the consequence). */
.pw__curve-toggle-icon { margin-right: 4px; transition: transform .15s ease; }
.pw__curve-toggle-icon.is-expanded { transform: rotate(90deg); }
.pw__curve-clear { margin-top: 8px; }
.pw__ref-row { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
/* import door row: the set select keeps the free space, the buttons hug it. */
.pw__ref-row--import { grid-template-columns: 1fr auto auto; align-items: center; }
.pw__imp-door { display: inline-flex; }
/* import panel: worst-scale windowed list (row pitch stays a constant
 40px — the window math measures it once and trusts it, so method/path/name
 cells never wrap and the layout cannot jitter). */
.pw__imp { display: flex; flex-direction: column; gap: 8px; }
.pw__imp:focus { outline: none; }
.pw__imp-bar { display: flex; gap: 8px; align-items: center; }
.pw__imp-search { flex: 1; }
.pw__imp-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 0;
  max-height: 360px;
  overflow-y: auto;
}
.pw__imp-pad { flex: 0 0 auto; pointer-events: none; }
.pw__imp-row {
  flex: 0 0 var(--ds-table-row-height);
  height: var(--ds-table-row-height);
  display: flex;
  align-items: center;
  gap: 8px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  cursor: pointer;
}
.pw__imp-row:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: -2px; }
.pw__imp-method { flex: 0 0 64px; font-family: var(--el-font-family-mono, monospace); font-size: 12px; }
.pw__imp-path {
  flex: 0 0 220px;
  font-family: var(--el-font-family-mono, monospace);
  font-size: 12px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.pw__imp-name { flex: 1; min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.pw__imp-empty { color: var(--el-text-color-secondary); margin: 12px 0; }
/* preview ordinal (final step position) + duplicate hint band. */
.pw__imp-ordinal { flex: 0 0 44px; font-family: var(--el-font-family-mono, monospace); font-size: 12px; color: var(--el-text-color-secondary); }
.pw__imp-duphint { margin: 0; font-size: 12px; color: var(--el-color-danger); }
/* Sticky action bar: reachable without scrolling (operation-first: the import bar never hides behind the scroll). */
.pw__imp-foot {
  display: flex;
  align-items: center;
  gap: 12px;
  padding-top: 8px;
  border-top: 1px solid var(--el-border-color-lighter);
}
.pw__imp-progress { color: var(--el-text-color-secondary); font-size: 12px; }
/* dataset row: picker takes the free space, upload button hugs it */
.pw__ref-row--dataset { grid-template-columns: 1fr auto; align-items: center; }
/* B-8 scheduled-firing block: cron input grows, preview button hugs it;
 fire list is a quiet monospace ledger under the hint line. */
.pw__ref-row--schedule { grid-template-columns: 1fr auto; align-items: center; }
.pw__control--grow { flex: 1; }
.pw__fire-list { margin: 6px 0 0; padding: 0 0 0 18px; font-size: 12px; color: var(--el-text-color-secondary); }
.pw__fire-row { font-variant-numeric: tabular-nums; }
.pw__file-hidden { display: none; }
.pw__var-chips { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; margin-top: 8px; }
/* Option meta suffix (row count) is de-emphasized so the dataset name leads. */
.pw__opt-name { font-size: 14px; }
.pw__opt-meta { font-size: 12px; color: var(--el-text-color-secondary); margin-left: 4px; }
.pw__var-chips-label { font-size: 12px; color: var(--el-text-color-secondary); }
.pw__alloc { display: flex; flex-direction: column; gap: 6px; }
.pw__alloc-reason { font-size: 12px; color: var(--el-text-color-secondary); }
.pw__step-row { display: flex; gap: 8px; align-items: center; margin-bottom: 8px; }
/* the step table is its own scroll owner — worst scale 500
 steps renders only the window (pads keep the scrollbar honest). Height
 budget: ~8 rows visible; row pitch is measured live by the windowing. */
.pw__step-list { max-height: 432px; overflow-y: auto; padding-right: 4px; }
.pw__step-pad { flex: none; }
.pw__step-tools { display: flex; gap: 8px; align-items: center; margin-bottom: 8px; }
.pw__step-search { max-width: 320px; }
/* The dialog's step rows are flex containers whose only spacing source is
   `gap`; Element Plus checkboxes carry a 30px margin-right from the inline
   list use case, which double-counts here and starves the URL input. Zero
   the margin so the checkbox sits one gap away from the action select. */
.pw__step-check { flex: none; margin-right: 0; }
.pw__step-auth { flex: none; margin-right: 0; }
/* step parameter editor: folded area under each row. */
.pw__step-block { margin-bottom: 8px; }
.pw__step-params { border: 1px solid var(--el-border-color-lighter); border-radius: 6px; padding: 10px 12px; margin: -2px 0 8px; display: grid; gap: 10px; }
.pw__param-group label, .pw__param-group .pw__caption { display: block; font-size: 12px; color: var(--el-text-color-secondary); margin-bottom: 4px; }
.pw__param-pair { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.pw__param-num { width: 100%; }
.pw__kv-row { display: grid; grid-template-columns: minmax(120px, 1fr) minmax(160px, 2fr) auto; gap: 8px; align-items: center; margin-bottom: 6px; }
.pw__param-invalid :deep(.el-input__wrapper) { box-shadow: 0 0 0 1px var(--el-color-danger) inset; }
.pw__param-error { margin: 4px 0 0; font-size: 12px; color: var(--el-color-danger); }
/* 16px lead-in = the theme band-gap single source (R21): the action row
   separates from the step list the same way bands separate site-wide. */
.pw__step-toolbar { display: flex; gap: 12px; align-items: center; margin-top: 16px; }
.pw__steps-count { margin: 0 0 6px; font-size: 12px; color: var(--el-text-color-secondary); }
.pw__var-chip { cursor: pointer; }

/* U-4 journey cards: one card per pressure link, chips + slider + share
   donut (never a table inside a table). 4px grid, theme vars single source. */
.pw__journey-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }  /* 12px > 8px card pitch: the zone title heads the card group (vision T377u4 dim1) */
.pw__journey-card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 8px 12px;
  margin-bottom: 8px;
  background: var(--el-fill-color-lighter);
}
.pw__journey-top { display: flex; align-items: center; gap: 8px; }
.pw__journey-name { flex: 1; max-width: 220px; }
.pw__journey-donut { flex: none; }
.pw__journey-share { font-size: 12px; color: var(--el-text-color-secondary); white-space: nowrap; }
.pw__journey-members { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 8px; align-items: center; }
.pw__journey-chip { margin: 0; }
.pw__journey-more { font-size: 12px; color: var(--el-text-color-secondary); }
.pw__journey-slider { display: flex; align-items: center; gap: 12px; margin-top: 8px; }
.pw__journey-slider-label { font-size: 12px; color: var(--el-text-color-secondary); white-space: nowrap; }
/* Fold line = the zone's own statement, not the last card's content: a
   dashed top rule + 12px lead-in separates it from the card stack
   (vision T377u4 multi13 dim1). */
.pw__journey-fold { margin: 0; padding-top: 12px; border-top: 1px dashed var(--el-border-color-lighter); }
.pw__journey-slider .el-slider { flex: 1; }
.pw__step-action { width: 170px; flex: none; }
.pw__step-method { width: 110px; flex: none; }
.pw__step-url { flex: 1; }
.pw__step-note { font-size: 12px; color: var(--el-text-color-secondary); flex: 1; }
.pw__sum-list { margin: 0; padding-left: 18px; line-height: 1.7; }
.pw__hint { margin: 6px 0 0; font-size: 12px; color: var(--el-text-color-secondary); }
/* Pressure-tier tooltip icon: inline after the field label, secondary tone. */
.pw__tip-icon { vertical-align: -2px; margin-left: 4px; font-size: 14px; color: var(--el-text-color-secondary); cursor: help; }
.pw__hint--block { display: block; margin-bottom: 16px; }
.pw__models { margin-bottom: 6px; }
.pw__preview { margin: 16px 0 0; padding: 12px; border: 1px solid var(--el-border-color-lighter); border-radius: 8px; background: var(--el-fill-color-light); }
/* Vision close-out : the caliber line is the card's last row — drop
 the --block bottom margin so inner padding stays symmetric at 12px. */
.pw__preview .pw__hint--block { margin-bottom: 0; }
.pw__preview-title { margin: 0 0 8px; font-size: 13px; font-weight: 600; color: var(--el-text-color-secondary); }
/* currentColor on the primary token keeps the stroke theme-consistent in dark/light */
.pw__preview-svg { display: block; width: 100%; max-width: 320px; height: 96px; color: var(--el-color-primary); }
/* zero-value reference line under the polyline (theme single-source, faint) */
.pw__preview-svg .pw__preview-axis { stroke: var(--el-border-color); stroke-width: 1; }
.pw__estimate { margin: 16px 0 0; font-size: 13px; color: var(--el-color-primary); font-weight: 600; }
.pw__gate { display: flex; gap: 10px; align-items: flex-start; margin-top: 16px; padding: 12px; border-radius: 8px; border: 1px solid var(--el-border-color); }
.pw__gate--ok { border-color: var(--el-color-success); background: var(--el-color-success-light-9); }
.pw__gate--blocked { border-color: var(--el-color-danger); background: var(--el-color-danger-light-9); }
.pw__gate-gate { margin: 0; font-weight: 600; font-size: 13px; }
.pw__gate-reason { margin: 4px 0 0; font-size: 12px; color: var(--el-text-color-secondary); word-break: break-all; }
.pw__gate-actions { margin-top: 10px; display: flex; gap: 16px; }
</style>
