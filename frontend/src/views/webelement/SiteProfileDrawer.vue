<!--
 Site profile drawer: the read + CRUD face for whole-site survey profiles,
 the one-click dispatch door, and the permanent revisit entry for survey
 records (R67). Mounted from the element ledger trailing slot (no new menu
 leaf). The project segment comes from the header context (passed as prop),
 so the create door stays disabled with a pointer until a project is
 picked. Saving without changes is blocked on the client (dirty signature)
 and the server replays an unchanged set as a zero write.
 Replay form (11-22 order): a running row continues live following while
 the dialog is open; a finished row replays the stored tail with cursor
 lazy-loading; a purged row answers with the retention pointer instead of
 a blank pane.
-->
<template>
  <el-drawer
    :model-value="modelValue"
    size="720px"
    :title="t('siteProfile.drawerTitle')"
    @update:model-value="emit('update:modelValue', $event as boolean)"
  >
    <div class="sp-toolbar" data-test="profile-toolbar">
      <el-tooltip :content="t('components.ownerEcho.pickInHeader')" placement="bottom" :disabled="!!projectId">
        <span>
          <el-button type="primary" :icon="Plus" data-test="profile-create-btn" :disabled="!projectId"
            @click="openCreate">
            {{ t('siteProfile.create') }}
          </el-button>
        </span>
      </el-tooltip>
      <el-button :icon="Refresh" :loading="loading" data-test="profile-refresh-btn" @click="refreshAll">
        {{ t('siteProfile.refresh') }}
      </el-button>
    </div>

    <el-table v-if="rows.length > 0" v-loading="loading" :data="rows" border size="small" data-test="profile-table">
      <el-table-column prop="name" :label="t('siteProfile.colName')" min-width="130" show-overflow-tooltip />
      <el-table-column prop="scopePrefix" :label="t('siteProfile.colScope')" min-width="96" show-overflow-tooltip />
      <el-table-column :label="t('siteProfile.colLatestRun')" min-width="112">
        <template #default="{ row }">
          <el-tag v-if="latestRunOf(row.id)" size="small" effect="light" :type="statusTagType(latestRunOf(row.id)!.status)">
            {{ statusLabel(latestRunOf(row.id)!.status) }}
          </el-tag>
          <span v-else>—</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('siteProfile.colCoverage')" width="92">
        <template #default="{ row }">{{ latestRunOf(row.id)?.ledgerUpserted ?? '—' }}</template>
      </el-table-column>
      <el-table-column :label="t('siteProfile.colLoginState')" min-width="110" show-overflow-tooltip>
        <template #default="{ row }">{{ row.loginStateName || '—' }}</template>
      </el-table-column>
      <el-table-column :label="t('siteProfile.colEnabled')" width="88">
        <template #default="{ row }">
          <el-tag size="small" :type="row.enabled ? 'success' : 'info'" effect="light">
            {{ row.enabled ? t('siteProfile.enabledOn') : t('siteProfile.enabledOff') }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('siteProfile.colUpdatedAt')" width="150">
        <template #default="{ row }"><TimeCell :value="row.updatedAt" /></template>
      </el-table-column>
      <el-table-column :label="t('siteProfile.colActions')" width="186" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" :icon="Compass" data-test="profile-survey-btn"
            :loading="dispatchingId === row.id" @click="dispatch(row)">
            {{ t('siteProfile.surveyAction') }}
          </el-button>
          <el-button link type="primary" :icon="EditPen" data-test="profile-edit-btn" @click="openEdit(row)">
            {{ t('siteProfile.edit') }}
          </el-button>
          <el-button link type="danger" :icon="Delete" data-test="profile-delete-btn" @click="removeRow(row)">
            {{ t('siteProfile.delete') }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <div v-else-if="!loading" class="sp-empty" data-test="profile-empty">{{ t('siteProfile.emptyList') }}</div>

    <h4 class="sp-runs-title" data-test="runs-title">{{ t('siteProfile.runsTitle') }}</h4>
    <el-table v-if="runs.length > 0" v-loading="runsLoading" :data="runs" border size="small"
      data-test="runs-table">
      <el-table-column prop="profileName" :label="t('siteProfile.runColProfile')" min-width="120" show-overflow-tooltip />
      <el-table-column :label="t('siteProfile.runColStatus')" width="96">
        <template #default="{ row }">
          <el-tag size="small" effect="light" :type="statusTagType(row.status)">
            {{ statusLabel(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('siteProfile.runColPages')" width="104">
        <template #default="{ row }">{{ row.pagesOk }}/{{ row.pagesTotal }}</template>
      </el-table-column>
      <el-table-column :label="t('siteProfile.runColLedger')" width="84" prop="ledgerUpserted" />
      <el-table-column :label="t('siteProfile.runColDegraded')" width="96" prop="namingDegraded" />
      <el-table-column :label="t('siteProfile.runColStarted')" width="150">
        <template #default="{ row }"><TimeCell :value="row.startedAt" /></template>
      </el-table-column>
      <el-table-column :label="t('siteProfile.runColActions')" width="104" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" data-test="run-detail-btn" @click="openDetail(row)">
            {{ t('siteProfile.runDetail') }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <div v-else-if="!runsLoading" class="sp-empty" data-test="runs-empty">{{ t('siteProfile.runsEmpty') }}</div>

    <PageWriteRiskPanel ref="riskPanelRef" :project-id="props.projectId" />

    <el-dialog v-model="formVisible" class="tp-dialog--lg" append-to-body
      :title="editing ? t('siteProfile.editTitle') : t('siteProfile.createTitle')" data-test="profile-form">
      <el-form label-position="top" @submit.prevent>
        <!-- Two-column field grid: a single long column made the dialog
             taller than its own width comfort band; short fields pair up. -->
        <div class="sp-form-grid">
          <el-form-item :label="t('siteProfile.formName')" required>
            <el-input v-model="form.name" maxlength="120" :placeholder="t('siteProfile.formNamePlaceholder')"
              data-test="profile-field-name" />
          </el-form-item>
          <el-form-item :label="t('siteProfile.formLoginState')">
            <el-select v-model="form.loginStateName" clearable filterable class="w-full"
              :placeholder="t('siteProfile.loginStatePlaceholder')" data-test="profile-field-login">
              <el-option v-for="s in loginStates" :key="s.id" :label="s.stateName" :value="s.stateName" />
            </el-select>
            <div class="sp-hint">{{ t('siteProfile.loginStateHint') }}</div>
          </el-form-item>
        </div>
        <el-form-item :label="t('siteProfile.formStartUrl')" required>
          <el-input v-model="form.startUrl" :placeholder="t('siteProfile.formStartUrlPlaceholder')"
            data-test="profile-field-start-url" />
          <div class="sp-hint">{{ t('siteProfile.startUrlHint') }}</div>
        </el-form-item>
        <div class="sp-form-grid">
          <el-form-item :label="t('siteProfile.formExcludeGlobs')">
            <el-input v-model="form.excludeGlobsText" type="textarea" :rows="3" data-test="profile-field-exclude" />
            <div class="sp-hint">{{ t('siteProfile.excludeHint') }}</div>
          </el-form-item>
          <el-form-item :label="t('siteProfile.formIgnoreParams')">
            <el-select v-model="form.ignoreParams" multiple filterable allow-create default-first-option
              class="w-full" data-test="profile-field-ignore">
              <el-option v-for="p in ignoreParamOptions" :key="p" :label="p" :value="p" />
            </el-select>
            <div class="sp-hint">{{ t('siteProfile.ignoreHint') }}</div>
          </el-form-item>
        </div>
        <div class="sp-form-grid">
          <el-form-item :label="t('siteProfile.formVerbsGuard')">
            <el-switch v-model="form.verbsGuardEnabled" data-test="profile-field-verbs-guard" />
            <div class="sp-hint">{{ t('siteProfile.verbsGuardHint') }}</div>
          </el-form-item>
          <el-form-item :label="t('siteProfile.enabledField')">
            <el-switch v-model="form.enabled" data-test="profile-field-enabled" />
          </el-form-item>
        </div>
        <el-collapse v-model="budgetPanel" class="sp-budget">
          <el-collapse-item :title="t('siteProfile.formBudget')" name="budget" data-test="profile-budget-panel">
            <div class="sp-budget-grid">
              <el-form-item v-for="f in BUDGET_FIELDS" :key="f.key" :label="t(f.labelKey)">
                <el-input-number v-model="form.budget[f.key]" :min="0" :value-on-clear="null"
                  :placeholder="String(f.def)" class="w-full" :data-test="`profile-budget-${f.key}`" />
              </el-form-item>
            </div>
            <div class="sp-hint">{{ t('siteProfile.budgetHint') }}</div>
          </el-collapse-item>
        </el-collapse>
      </el-form>
      <template #footer>
        <el-button data-test="profile-form-cancel" @click="formVisible = false">{{ t('siteProfile.cancel') }}</el-button>
        <el-tooltip :content="t('siteProfile.dirtyGuard')" placement="top" :disabled="dirty">
          <span>
            <el-button type="primary" :loading="saving" :disabled="!dirty" data-test="profile-save-btn"
              @click="submit">
              {{ t('siteProfile.save') }}
            </el-button>
          </span>
        </el-tooltip>
      </template>
    </el-dialog>

    <!-- Replay door (R67): same tail component as every live tail. A live
         run keeps following from its cursor; a finished run replays stored
         frames with a forward-only lazy load; an expired one answers with
         the retention pointer. Height stays constant while frames grow —
         the tail window scrolls, the dialog never stretches. -->
    <el-dialog v-model="tailVisible" class="tp-dialog--md" append-to-body
      :title="t('siteProfile.runTailTitle', { name: tailRun?.profileName ?? '' })"
      data-test="run-tail-dialog" @closed="onTailClosed">
      <div v-if="tailRun && tailRun.exploreSessionId === null" class="sp-tail-note" data-test="run-tail-no-session">
        {{ t('siteProfile.tailNoSession') }}
      </div>
      <template v-else-if="tailRun">
        <TaskTailDrawer :events="tailState?.events ?? []" :live="!!tailState?.live"
          :expired="!!tailState?.expiredHint"
          retention-param="element.survey-site.event-retention-days"
          :heartbeat-seconds="tailHeartbeatSeconds" :first-frame-ms="tailFirstFrameMs"
          class="sp-tail-window" data-test="run-tail-window" />
        <div class="sp-tail-more">
          <el-button v-if="!tailState?.expiredHint && !tailState?.exhausted" size="small" :loading="tailState?.loading"
            data-test="run-tail-more" @click="tailLoadMore">
            {{ t('siteProfile.tailLoadMore') }}
          </el-button>
        </div>
      </template>
    </el-dialog>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Compass, Delete, EditPen, Plus, Refresh } from '@element-plus/icons-vue'
import TimeCell from '@/components/TimeCell.vue'
import TaskTailDrawer from '@/components/TaskTailDrawer.vue'
import PageWriteRiskPanel from './PageWriteRiskPanel.vue'
import { bizErrorMsg } from '@/utils/bizError'
import { listSessionStates, type LoginStateRow } from '@/api/sessionState'
import { tailFirstFrameMs, tailHeartbeatSeconds } from '@/composables/tailWindowConfig'
import {
  getRunTail,
  loadRunTailPage,
  startRunTailFollow,
  stopRunTailFollow,
} from '@/composables/siteSurveyTailReplay'
import {
  createSiteProfile,
  deleteSiteProfile,
  dispatchSiteSurveyRun,
  listSiteProfiles,
  listSiteSurveyRuns,
  updateSiteProfile,
  type SiteProfilePayload,
  type SiteProfileRow,
  type SiteSurveyRunRow,
} from '@/api/siteProfiles'

const props = defineProps<{
  modelValue: boolean
  projectId: number | null
  /** deep-link door: when set, the drawer opens this run's replay */
  initialRunId?: number | null
}>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
const { t } = useI18n()

/** Factory preset of the ignore-parameter list (mirrors the system default). */
const DEFAULT_IGNORE_PARAMS = ['page', 'pageNum', 'pageNo', 'size', 'limit', 'offset', 'timestamp', '_t', 'rand']

/** The six numeric budget knobs a profile may override; defaults shown as
 *  placeholders keep the shipped values honest without writing them back. */
const BUDGET_FIELDS: { key: string; labelKey: string; def: number }[] = [
  { key: 'max-pages', labelKey: 'siteProfile.budgetMaxPages', def: 100 },
  { key: 'max-depth', labelKey: 'siteProfile.budgetMaxDepth', def: 6 },
  { key: 'interval-ms', labelKey: 'siteProfile.budgetIntervalMs', def: 800 },
  { key: 'deadline-seconds', labelKey: 'siteProfile.budgetDeadlineSeconds', def: 1800 },
  { key: 'max-links-per-page', labelKey: 'siteProfile.budgetMaxLinksPerPage', def: 60 },
  { key: 'per-template-cap', labelKey: 'siteProfile.budgetPerTemplateCap', def: 1 },
]

const loading = ref(false)
const saving = ref(false)
const rows = ref<SiteProfileRow[]>([])
const loginStates = ref<LoginStateRow[]>([])
const formVisible = ref(false)
const editing = ref<SiteProfileRow | null>(null)
const budgetPanel = ref<string[]>([])

/* Survey-record ledger of this project segment (revisit door, R67). */
const runs = ref<SiteSurveyRunRow[]>([])
const runsLoading = ref(false)
const riskPanelRef = ref<InstanceType<typeof PageWriteRiskPanel> | null>(null)
const dispatchingId = ref<number | null>(null)
const tailVisible = ref(false)
const tailRun = ref<SiteSurveyRunRow | null>(null)

/** Reactive view of the open run's read window (module buffer survives
 *  closes so the cursor stays where the viewer left it). */
const tailState = computed(() => (tailRun.value ? getRunTail(tailRun.value.runId) : null))

const STATUS_TAG: Record<string, 'primary' | 'success' | 'warning' | 'danger' | 'info'> = {
  running: 'warning', completed: 'success', partial: 'primary',
  failed: 'danger', cancelled: 'info',
}
function statusTagType(status: string) {
  return STATUS_TAG[status] ?? 'info'
}
function statusLabel(status: string): string {
  const key = `siteProfile.status${status.charAt(0).toUpperCase()}${status.slice(1)}`
  const label = t(key)
  return label === key ? status : label
}

/** Newest run of one profile (runs are listed newest first). */
function latestRunOf(profileId: number): SiteSurveyRunRow | undefined {
  return runs.value.find((r) => r.profileId === profileId)
}

const form = reactive({
  name: '',
  startUrl: '',
  excludeGlobsText: '',
  ignoreParams: [] as string[],
  loginStateName: null as string | null,
  verbsGuardEnabled: true,
  enabled: true,
  budget: {} as Record<string, number | null>,
})

function budgetVector(): (number | null)[] {
  return BUDGET_FIELDS.map((f) => form.budget[f.key] ?? null)
}

function signature(): string {
  return JSON.stringify({
    name: form.name.trim(),
    startUrl: form.startUrl.trim(),
    excludeGlobsText: form.excludeGlobsText.trim(),
    ignoreParams: [...form.ignoreParams].sort(),
    loginStateName: form.loginStateName || null,
    verbsGuardEnabled: form.verbsGuardEnabled,
    enabled: form.enabled,
    budget: budgetVector(),
  })
}

/** Persistence baseline: captured when the form opens or an edit loads. */
let baseline = signature()
const dirty = computed(() => signature() !== baseline)

const ignoreParamOptions = computed(() => {
  const set = new Set<string>([...DEFAULT_IGNORE_PARAMS, ...form.ignoreParams])
  return [...set]
})

function resetForm(row: SiteProfileRow | null) {
  form.name = row?.name ?? ''
  form.startUrl = row?.startUrl ?? ''
  form.excludeGlobsText = (row?.excludeGlobs ?? []).join('\n')
  form.ignoreParams = [...(row?.ignoreParams && row.ignoreParams.length > 0 ? row.ignoreParams : DEFAULT_IGNORE_PARAMS)]
  form.loginStateName = row?.loginStateName ?? null
  form.verbsGuardEnabled = row ? row.verbsGuardEnabled : true
  form.enabled = row ? row.enabled : true
  form.budget = {}
  for (const f of BUDGET_FIELDS) {
    const raw = row?.budgetOverrides?.[f.key]
    form.budget[f.key] = typeof raw === 'number' ? raw : null
  }
  editing.value = row
  budgetPanel.value = []
  baseline = signature()
}

async function load() {
  if (!props.projectId) {
    rows.value = []
    return
  }
  loading.value = true
  try {
    rows.value = await listSiteProfiles(props.projectId)
  } catch (e) {
    rows.value = []
    ElMessage.error(bizErrorMsg(e, t('siteProfile.loadFail')))
  } finally {
    loading.value = false
  }
}

async function loadRuns() {
  if (!props.projectId) {
    runs.value = []
    return
  }
  runsLoading.value = true
  try {
    runs.value = await listSiteSurveyRuns(props.projectId)
  } catch (e) {
    runs.value = []
    ElMessage.error(bizErrorMsg(e, t('siteProfile.loadFail')))
  } finally {
    runsLoading.value = false
  }
}

/** One click re-reads every face the drawer shows (profiles, records, risk). */
function refreshAll() {
  void load()
  void loadRuns()
  riskPanelRef.value?.reload()
}

async function loadLoginStates() {
  if (!props.projectId) return
  try {
    loginStates.value = await listSessionStates(props.projectId, true)
  } catch (e) {
    loginStates.value = []
    ElMessage.warning(bizErrorMsg(e, t('siteProfile.loginStateLoadFail')))
  }
}

async function onOpen() {
  await load()
  void loadLoginStates()
  await loadRuns()
  // Deep-link door (?surveyRun=<id> from the ledger page): open straight
  // onto that record's replay so a pasted link lands on the process tail.
  if (props.initialRunId !== undefined && props.initialRunId !== null) {
    const hit = runs.value.find((r) => r.runId === props.initialRunId)
    if (hit) await openDetail(hit)
  }
}

// The read door is keyed on modelValue, not el-drawer's @open: a deep-link
// landing (?surveyRun) mounts the drawer already open, and @open never fires
// without a false->true transition — both faces then stayed empty and the
// replay window never opened (browser-proved by the full-journey E2E).
watch(() => props.modelValue, (v) => { if (v) void onOpen() })
if (props.modelValue) void onOpen()

/** One click starts a whole-site crawl (read-only door; elements still
 *  pass the human approval panel before they are filed). */
async function dispatch(row: SiteProfileRow) {
  dispatchingId.value = row.id
  try {
    const runId = await dispatchSiteSurveyRun(row.id)
    ElMessage.success(t('siteProfile.dispatchOk', { name: row.name }))
    await loadRuns()
    const run = runs.value.find((r) => r.runId === runId)
    if (run) await openDetail(run)
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('siteProfile.dispatchFail')))
  } finally {
    dispatchingId.value = null
  }
}

/** Open the replay of one run: live rows keep following, finished rows
 *  replay from the stored tail (the buffer's cursor only moves forward). */
async function openDetail(run: SiteSurveyRunRow) {
  tailRun.value = run
  const st = getRunTail(run.runId)
  st.live = run.status === 'running'
  tailVisible.value = true
  try {
    await loadRunTailPage(run)
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('siteProfile.dispatchFail')))
  }
  if (st.live) startRunTailFollow(run, () => void loadRuns())
}

async function tailLoadMore() {
  if (!tailRun.value) return
  try {
    await loadRunTailPage(tailRun.value)
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('siteProfile.dispatchFail')))
  }
}

function onTailClosed() {
  // Closing only stops the client window from following; the run keeps
  // crawling and the record stays queryable — visibility is not destroyed.
  if (tailRun.value) stopRunTailFollow(tailRun.value.runId)
  tailRun.value = null
}

// The drawer may sit open while a crawl finishes: any terminal transition
// observed in the replay silently refreshes the record list (no manual
// refresh instruction anywhere on this face).
watch(
  () => tailState.value?.live,
  (live, prev) => {
    if (prev === true && live === false) void loadRuns()
  },
)

function openCreate() {
  resetForm(null)
  formVisible.value = true
}

function openEdit(row: SiteProfileRow) {
  resetForm(row)
  formVisible.value = true
}

function buildPayload(): SiteProfilePayload {
  const budget: Record<string, number> = {}
  for (const f of BUDGET_FIELDS) {
    const v = form.budget[f.key]
    if (typeof v === 'number') budget[f.key] = v
  }
  return {
    projectId: props.projectId as number,
    name: form.name.trim(),
    startUrl: form.startUrl.trim(),
    excludeGlobs: form.excludeGlobsText.split('\n').map((s) => s.trim()).filter(Boolean),
    ignoreParams: [...new Set(form.ignoreParams.map((s) => s.trim()).filter(Boolean))],
    verbsGuardEnabled: form.verbsGuardEnabled,
    budgetOverrides: budget,
    loginStateName: form.loginStateName || null,
    enabled: form.enabled,
    revision: editing.value?.revision,
  }
}

async function submit() {
  if (!props.projectId || !dirty.value) return
  if (!form.name.trim()) {
    ElMessage.warning(t('siteProfile.needName'))
    return
  }
  if (!form.startUrl.trim()) {
    ElMessage.warning(t('siteProfile.needStartUrl'))
    return
  }
  saving.value = true
  try {
    const payload = buildPayload()
    const saved = editing.value
      ? await updateSiteProfile(editing.value.id, payload)
      : await createSiteProfile(payload)
    formVisible.value = false
    ElMessage.success(t('siteProfile.savedOk', { name: saved.name }))
    await load()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('siteProfile.saveFail')))
  } finally {
    saving.value = false
  }
}

async function removeRow(row: SiteProfileRow) {
  try {
    await ElMessageBox.confirm(t('siteProfile.deleteConfirm', { name: row.name }), t('siteProfile.delete'), {
      type: 'warning',
      confirmButtonText: t('siteProfile.deleteConfirmOk'),
      cancelButtonText: t('siteProfile.cancel'),
    })
  } catch {
    return
  }
  try {
    await deleteSiteProfile(row.id)
    ElMessage.success(t('siteProfile.deletedOk', { name: row.name }))
    await load()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('siteProfile.deleteFail')))
  }
}
</script>

<style scoped>
.sp-toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.sp-empty {
  padding: 32px 0;
  text-align: center;
  font-size: 13px;
  opacity: 0.75;
}
.sp-hint {
  font-size: 12px;
  line-height: 1.6;
  opacity: 0.7;
}
.sp-budget {
  margin-bottom: 14px;
}
.sp-budget-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  column-gap: 16px;
}
.sp-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  column-gap: 16px;
}
.sp-runs-title {
  margin: 18px 0 10px;
  font-size: 14px;
}
.sp-tail-note {
  padding: 18px 4px;
  font-size: 13px;
  line-height: 1.8;
}
.sp-tail-more {
  display: flex;
  justify-content: center;
  padding-top: 8px;
}
</style>
