<!--
  tp-sort-exempt: the sample table renders one frozen evaluation-set version
  (core /samples has no sort lane — row order is the stable insertion order of
  that snapshot). The page answers scale with instant keyword filtering plus
  pagination instead of server-side sort; re-check at the batch verification
  package if core ever grows a sort contract.
  tp-column-config-exempt: six light presentation columns (id / input /
  expected / source / sensitive / createdAt) all carry the same load for the
  snapshot reading; column settings deferred to the batch verification
  package (registered in the chain progress carrier).
-->
<!--
  Evaluation-set management page (AW1 read door of the judge write-chain
  facade; write doors — raise promotions, publish, question-gen wizard —
  attach to this same filter band in the follow-up packages of the batch).
  - Consumes the admin bridge /api/v1/eval-sets/samples: the bridge forwards
    core directly, so this page renders version-frozen snapshots with zero
    second computation (presentation-side red line).
  - Worst-case-size form (S group): the bridge returns one version's full
    sample list; the page pages it client-side with an instant keyword
    filter, so a 200-sample version stays navigable.
  - Page state (set name / version / keyword / page) lives in the URL query
    so reload, back and share restore the same view (F group).
-->
<template>
  <div class="page-root" data-test="judge-evalset-page">
    <div class="page-card">
      <div class="filter-bar">
        <el-select
          v-model="setName"
          filterable
          allow-create
          default-first-option
          :placeholder="t('judge.evalset.setNamePlaceholder')"
          data-test="evalset-name-select"
          class="w-setname"
          @change="onSetChange"
        >
          <el-option v-for="n in setNameOptions" :key="n" :label="n" :value="n" />
        </el-select>
        <span class="version-caption">{{ t('judge.evalset.versionCaption') }}</span>
        <el-input-number
          v-model="version"
          :min="1"
          controls-position="right"
          :aria-label="t('judge.evalset.versionCaption')"
          data-test="evalset-version-input"
          class="w-version"
        />
        <el-input
          v-model="keyword"
          :placeholder="t('judge.evalset.searchPlaceholder')"
          clearable
          data-test="evalset-keyword-input"
          class="w-keyword"
        />
        <el-button
          type="primary"
          :icon="Search"
          :loading="loading"
          :disabled="!setName || !version || loading"
          data-test="evalset-search-btn"
          @click="loadSamples"
        >
          {{ t('judge.evalset.searchBtn') }}
        </el-button>
        <div class="filter-bar-actions">
          <el-button
            type="primary"
            :icon="Plus"
            v-permission="'perm:judge:evalset:manage'"
            data-test="evalset-manual-btn"
            @click="openWrite('manual')"
          >
            {{ t('judge.evalset.manualBtn') }}
          </el-button>
          <!-- The three promotion channels share one target dialog, so they
              fold into a single menu instead of three flat buttons (vision
              V3: trailing-slot overload). -->
          <el-dropdown
            trigger="click"
            v-permission="'perm:judge:evalset:manage'"
            data-test="evalset-promote-btn"
            @command="(m: string) => openWrite(m as 'adopted' | 'training' | 'batch')"
          >
            <el-button data-test="evalset-promote-trigger">
              {{ t('judge.evalset.promoteMenu') }}
              <el-icon class="promote-caret"><ArrowDown /></el-icon>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="adopted" data-test="evalset-adopted-item">
                  {{ t('judge.evalset.adoptedBtn') }}
                </el-dropdown-item>
                <el-dropdown-item command="training" data-test="evalset-training-item">
                  {{ t('judge.evalset.trainingBtn') }}
                </el-dropdown-item>
                <el-dropdown-item command="batch" data-test="evalset-batch-item">
                  {{ t('judge.evalset.batchBtn') }}
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <el-button
            v-permission="'perm:judge:qagen:run'"
            data-test="evalset-qagen-btn"
            @click="qagenVisible = true"
          >
            {{ t('judge.evalset.qagenBtn') }}
          </el-button>
          <!-- R67 revisit door : the run history outlives the
 wizard — every past question-generation run stays replayable
 from here, so closing the wizard never loses the tail. -->
          <el-button
            v-permission="'perm:judge:qagen:run'"
            data-test="evalset-qaruns-btn"
            @click="qaRunsVisible = true"
          >
            {{ t('judge.evalset.qaGenRuns.btn') }}
          </el-button>
          <el-button
            :icon="Refresh"
            circle
            :disabled="!hasLoaded || loading"
            data-test="evalset-refresh-btn"
            :title="t('judge.evalset.refreshBtn')"
            @click="loadSamples"
          />
        </div>
      </div>

      <div class="result-meta">
        <span v-if="hasLoaded" class="total-chip" data-test="evalset-total-chip">
          {{ t('judge.evalset.totalLabel', { n: filteredRows.length }) }}
        </span>
      </div>

      <TableSkeleton v-if="loading" :rows="6" :cols="6" />
      <EmptyState
        v-else-if="!filteredRows.length"
        :title="hasLoaded ? t('judge.evalset.emptyTitle') : t('judge.evalset.noSelection')"
        :hint="t('judge.evalset.emptyHint')"
        :filtered="hasLoaded && rows.length > 0"
        data-test="evalset-empty"
      />
      <template v-else>
        <el-table :data="pagedRows" border data-test="evalset-sample-table">
          <el-table-column prop="id" :label="t('judge.evalset.colId')" width="96" />
          <el-table-column prop="input" :label="t('judge.evalset.colInput')" min-width="240" show-overflow-tooltip />
          <el-table-column prop="expected" :label="t('judge.evalset.colExpected')" min-width="240" show-overflow-tooltip />
          <el-table-column :label="t('judge.evalset.colSource')" width="140">
            <template #default="{ row }">{{ sourceLabel(row.source) }}</template>
          </el-table-column>
          <el-table-column :label="t('judge.evalset.colSensitive')" width="90">
            <template #default="{ row }">
              {{ row.sensitive === 1 ? t('judge.evalset.sensitiveYes') : t('judge.evalset.sensitiveNo') }}
            </template>
          </el-table-column>
          <el-table-column prop="createdAt" :label="t('judge.evalset.colCreatedAt')" width="170" />
        </el-table>
        <div class="pagination-bar">
          <el-pagination
            v-if="filteredRows.length > pageSize"
            layout="total, prev, pager, next"
            :total="filteredRows.length"
            :page-size="pageSize"
            :current-page="page"
            data-test="evalset-pagination"
            @current-change="(p: number) => { page = p; syncQuery() }"
          />
        </div>
      </template>
    </div>

    <!-- Single write door for all four AW2 sources (one dialog, mode
         switch — no per-channel dialogs copying the same publish flow).
         The confirm button never submits an empty payload (R28): it stays
         disabled with an on-screen hint until rows/samples are provided. -->
    <el-dialog
      v-model="writeVisible"
      :title="writeTitle"
      width="720px"
      append-to-body
      data-test="evalset-write-dialog"
    >
      <el-form label-position="top">
        <el-form-item :label="t('judge.evalset.targetSetName')">
          <el-input v-model="writeSetName" data-test="evalset-write-name" />
        </el-form-item>
        <template v-if="writeMode === 'manual'">
          <p v-if="rows.length" class="mode-hint">
            {{ t('judge.evalset.manualCarryHint', { n: rows.length }) }}
          </p>
          <div v-for="(r, i) in draftRows" :key="i" class="draft-row">
            <el-input v-model="r.input" :placeholder="t('judge.evalset.rowInput')" class="draft-cell" />
            <el-input v-model="r.expected" :placeholder="t('judge.evalset.rowExpected')" class="draft-cell" />
            <el-checkbox v-model="r.sensitive">{{ t('judge.evalset.rowSensitive') }}</el-checkbox>
            <el-button
              :icon="Delete"
              circle
              text
              :title="t('judge.evalset.rowRemoveBtn')"
              :data-test="`evalset-row-remove-${i}`"
              @click="draftRows.splice(i, 1)"
            />
          </div>
          <el-button :icon="Plus" data-test="evalset-row-add" @click="addDraftRow">
            {{ t('judge.evalset.rowAddBtn') }}
          </el-button>
        </template>
        <template v-else-if="writeMode === 'adopted'">
          <p class="mode-hint">{{ t('judge.evalset.adoptedCasesHint') }}</p>
          <CaseSelect
            v-model="adoptedCaseIds"
            :project-id="projectId"
            multiple
            data-test="evalset-adopted-select"
            :placeholder="t('judge.evalset.adoptedCasesHint')"
          />
        </template>
        <template v-else-if="writeMode === 'batch'">
          <el-form-item :label="t('judge.evalset.batchSelectLabel')">
            <el-select
              v-model="sourceBatchId"
              filterable
              :loading="batchLoading"
              :placeholder="t('judge.evalset.batchSelectPlaceholder')"
              class="w-full"
              data-test="evalset-batch-select"
              @change="loadBatchTasks"
            >
              <el-option
                v-for="b in batchOptions"
                :key="b.id"
                :label="`${b.name} (${b.batchNo} · ${b.statusLabel})`"
                :value="b.id"
              />
            </el-select>
          </el-form-item>
          <p v-if="sourceBatchId && !batchTasksLoading && !batchTasks.length" class="mode-hint" data-test="evalset-batch-empty">
            {{ t('judge.evalset.batchTasksEmpty') }}
          </p>
          <template v-if="sourceBatchId">
            <p v-if="batchTasks.length" class="mode-hint">{{ t('judge.evalset.batchTasksHint') }}</p>
            <el-table
              v-show="batchTasks.length"
              v-loading="batchTasksLoading"
              :data="batchTasks"
              border
              max-height="280"
              row-key="id"
              data-test="evalset-batch-task-table"
              @selection-change="(sel: ExecTask[]) => (batchTaskIds = sel.map((s) => s.id))"
            >
              <el-table-column type="selection" width="44" />
              <el-table-column prop="id" :label="t('judge.evalset.taskColId')" width="90" />
              <el-table-column :label="t('judge.evalset.taskColStatus')" width="90">
                <template #default="{ row }">{{ row.status === 4 ? t('judge.evalset.statusFailed') : t('judge.evalset.statusTimeout') }}</template>
              </el-table-column>
              <el-table-column prop="errorDetail" :label="t('judge.evalset.taskColError')" min-width="220" show-overflow-tooltip />
            </el-table>
          </template>
        </template>
        <template v-else>
          <p class="mode-hint">{{ t('judge.evalset.trainingCandidatesHint') }}</p>
          <el-select
            v-model="trainingSampleIds"
            multiple
            filterable
            :loading="trainingLoading"
            class="w-full"
            data-test="evalset-training-select"
          >
            <el-option
              v-for="s in trainingOptions"
              :key="s.id"
              :label="trainingOptionLabel(s)"
              :value="s.id"
            />
          </el-select>
        </template>
      </el-form>
      <template #footer>
        <span v-if="!writeReady" class="guard-hint" data-test="evalset-write-guard">{{ writeGuardText }}</span>
        <el-button @click="writeVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button
          type="primary"
          :disabled="!writeReady"
          :loading="writeBusy"
          data-test="evalset-write-confirm-btn"
          @click="submitWrite"
        >
          {{ t('judge.evalset.confirmBtn') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- AW3 question-generation wizard (the AI source door of the same
         four-channel entry set; publish rides the shared door above). -->
    <QaGenWizardDialog
      v-model="qagenVisible"
      :project-id="projectId"
      :default-set-name="setName"
      :existing-samples="rows"
      @published="applyPublished"
    />

    <!-- segment b (R67): standing question-run history door with the
 shared cursor replay — the wizard's tail, kept reachable after
 the wizard itself is gone. -->
    <QaGenRunsDialog v-model="qaRunsVisible" :project-id="projectId" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Search, Refresh, Plus, Delete, ArrowDown } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import CaseSelect from '@/components/CaseSelect.vue'
import QaGenWizardDialog from './QaGenWizardDialog.vue'
import QaGenRunsDialog from './QaGenRunsDialog.vue'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import { getEvalSamples, listJudgeRuns, publishEvalSet, raiseFromAdoptedCases, raiseFromBatch, raiseFromTrainingSamples, sampleToPublishInput } from '@/api/evalSet'
import type { EvalSample, EvalPublishSampleInput, EvalSetVersionView } from '@/api/evalSet'
import { listTrainingSamples } from '@/api/trainingSample'
import type { TrainingSampleItem } from '@/api/trainingSample'
import { listBatches } from '@/api/batch'
import type { BatchView } from '@/api/batch'
import { listExecTasks } from '@/api/executor'
import type { ExecTask } from '@/api/executor'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
// R30: the project segment rides the global header context (no in-page
// project selector); the adopted-case picker scopes to it automatically.
const { projectId } = useProjectPageContext()

const setName = ref('')
const version = ref<number | undefined>(undefined)
const keyword = ref('')
const rows = ref<EvalSample[]>([])
const setNameOptions = ref<string[]>([])
const loading = ref(false)
const hasLoaded = ref(false)
const page = ref(1)
const pageSize = 20

// Promotion channels recorded by core (value domain single-source in core:
// manual / batch-export / training-raise / ai-generated); unknown raw
// values are shown as-is instead of being silently relabelled (honest
// display, red line 6 mirror).
const SOURCE_KEYS: Record<string, string> = {
  MANUAL: 'judge.evalset.sourceManual',
  'BATCH-EXPORT': 'judge.evalset.sourceBatch',
  'TRAINING-RAISE': 'judge.evalset.sourceTraining',
  'AI-GENERATED': 'judge.evalset.sourceQaGen',
}
function sourceLabel(raw: string | null): string {
  if (!raw) return '-'
  const key = SOURCE_KEYS[raw.toUpperCase()]
  return key ? t(key) : raw
}

const filteredRows = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return rows.value
  return rows.value.filter((r) =>
    (r.input ?? '').toLowerCase().includes(kw)
    || (r.expected ?? '').toLowerCase().includes(kw))
})

const pagedRows = computed(() => {
  const start = (page.value - 1) * pageSize
  return filteredRows.value.slice(start, start + pageSize)
})

watch(keyword, () => { page.value = 1; syncQuery() })

function syncQuery() {
  router.replace({
    query: {
      ...(setName.value ? { setName: setName.value } : {}),
      ...(version.value ? { version: String(version.value) } : {}),
      ...(keyword.value ? { keyword: keyword.value } : {}),
      ...(page.value > 1 ? { page: String(page.value) } : {}),
    },
  })
}

function onSetChange() {
  // Default the version to the newest value seen for this set in run
  // history; the user can still type any published version.
  if (!version.value) version.value = 1
  syncQuery()
}

async function loadSamples() {
  if (!setName.value || !version.value) {
    ElMessage.warning(t('judge.evalset.noSelection'))
    return
  }
  loading.value = true
  try {
    rows.value = await getEvalSamples(setName.value, version.value)
    hasLoaded.value = true
    page.value = 1
    syncQuery()
  } catch (err: any) {
    ElMessage.error(err?.message || t('judge.evalset.loadFailed'))
  } finally {
    loading.value = false
  }
}

// ---- AW2 write doors (single dialog, three sources) ---------------------
type WriteMode = 'manual' | 'adopted' | 'training' | 'batch'
const writeVisible = ref(false)
const qagenVisible = ref(false)
// segment b revisit door (history list of question-generation runs).
const qaRunsVisible = ref(false)
const writeMode = ref<WriteMode>('manual')
const writeBusy = ref(false)
const writeSetName = ref('')
const draftRows = ref<{ input: string; expected: string; sensitive: boolean }[]>([])
const adoptedCaseIds = ref<number[]>([])
const trainingSampleIds = ref<number[]>([])
const trainingOptions = ref<TrainingSampleItem[]>([])
const trainingLoading = ref(false)
const batchOptions = ref<BatchView[]>([])
const batchLoading = ref(false)
const sourceBatchId = ref<number | undefined>(undefined)
const batchTasks = ref<ExecTask[]>([])
const batchTaskIds = ref<number[]>([])
const batchTasksLoading = ref(false)

const writeTitle = computed(() => t(
  writeMode.value === 'manual' ? 'judge.evalset.writeTitleManual'
    : writeMode.value === 'adopted' ? 'judge.evalset.writeTitleAdopted'
      : writeMode.value === 'training' ? 'judge.evalset.writeTitleTraining'
        : 'judge.evalset.writeTitleBatch',
))

const writeGuardText = computed(() => t(
  writeMode.value === 'manual' ? 'judge.evalset.guardManual' : 'judge.evalset.guardSelection',
))

// R28 guard: an empty submission never reaches the server — manual needs
// at least one fully filled row, the two raise channels need a selection.
const writeReady = computed(() => {
  if (writeBusy.value) return false
  if (writeMode.value === 'manual') {
    return draftRows.value.length > 0
      && draftRows.value.every((r) => r.input.trim() !== '' && r.expected.trim() !== '')
  }
  if (writeMode.value === 'adopted') return adoptedCaseIds.value.length > 0
  if (writeMode.value === 'training') return trainingSampleIds.value.length > 0
  return sourceBatchId.value !== undefined && batchTaskIds.value.length > 0
})

function addDraftRow() {
  draftRows.value.push({ input: '', expected: '', sensitive: false })
}

function trainingOptionLabel(s: TrainingSampleItem): string {
  const head = `#${s.id}${s.caseNo ? ` ${s.caseNo}` : ''} · ${s.sampleType}`
  const body = (s.sampleJson ?? '').replace(/\s+/g, ' ').slice(0, 60)
  return body ? `${head} · ${body}` : head
}

async function openWrite(mode: WriteMode) {
  writeMode.value = mode
  writeSetName.value = setName.value
  draftRows.value = []
  adoptedCaseIds.value = []
  trainingSampleIds.value = []
  sourceBatchId.value = undefined
  batchTasks.value = []
  batchTaskIds.value = []
  writeVisible.value = true
  if (mode === 'manual') addDraftRow()
  if (mode === 'training' && !trainingOptions.value.length) {
    trainingLoading.value = true
    try {
      const res = await listTrainingSamples({ page: 1, size: 200 })
      trainingOptions.value = res.list ?? []
    } catch (err: any) {
      ElMessage.warning(err?.message || t('judge.evalset.trainingLoadFailed'))
    } finally {
      trainingLoading.value = false
    }
  }
  if (mode === 'batch' && !batchOptions.value.length) {
    const pid = projectId.value
    if (pid == null) {
      // The batch lane is project-scoped: without the header segment there is
      // no honest candidate list, so say so instead of firing a blind query.
      ElMessage.warning(t('judge.evalset.batchNoProject'))
    } else {
    batchLoading.value = true
    try {
      const res = await listBatches({ projectId: pid, page: 1, size: 200 })
      batchOptions.value = res.list ?? []
    } catch (err: any) {
      ElMessage.warning(err?.message || t('judge.evalset.batchLoadFailed'))
    } finally {
      batchLoading.value = false
    }
    }
  }
}

// Failed/timeout rows only: core re-checks every id's terminal status and
// rejects the whole request when a task slipped out of the 4/5 window, so
// pre-filtering here keeps the on-screen choice honest (never a guaranteed
// rejection entry point).
async function loadBatchTasks(batchId: number) {
  const pid = projectId.value
  if (pid == null) {
    batchTasks.value = []
    batchTaskIds.value = []
    ElMessage.warning(t('judge.evalset.batchNoProject'))
    return
  }
  batchTasks.value = []
  batchTaskIds.value = []
  batchTasksLoading.value = true
  try {
    const res = await listExecTasks({ projectId: pid, batchId, statusIn: '4,5', page: 1, size: 200 })
    batchTasks.value = res.list ?? []
  } catch (err: any) {
    ElMessage.warning(err?.message || t('judge.evalset.batchTasksLoadFailed'))
  } finally {
    batchTasksLoading.value = false
  }
}

// EvalSample -> publish input mapping is single-sourced in
// '@/api/evalSet' (sampleToPublishInput) and shared with the
// question-generation wizard (R40: one implementation per repo).

// Shared post-publish refresh path (single source for every write door,
// including the question-generation wizard): announce the frozen version,
// adopt it as the current view and reload the sample list.
async function applyPublished(res: EvalSetVersionView) {
  ElMessage.success(t('judge.evalset.publishSuccess',
    { set: res.setName, version: res.version, count: res.sampleCount }))
  if (!setNameOptions.value.includes(res.setName)) {
    setNameOptions.value = [...setNameOptions.value, res.setName].sort()
  }
  setName.value = res.setName
  version.value = res.version
  syncQuery()
  await loadSamples()
}

async function submitWrite() {
  if (!writeReady.value || writeSetName.value.trim() === '') return
  writeBusy.value = true
  try {
    let res: EvalSetVersionView
    if (writeMode.value === 'manual') {
      // Publish = the currently loaded version's full sample list plus the
      // new rows, frozen as a NEW version (immutable lineage, never an
      // overwrite; core re-checks values and rejects with its own reason).
      const samples: EvalPublishSampleInput[] = [
        ...rows.value.map(sampleToPublishInput),
        ...draftRows.value.map((r) => ({
          input: r.input.trim(),
          expected: r.expected.trim(),
          referenceDocs: null,
          source: 'manual',
          sensitive: r.sensitive ? 1 : 0,
        })),
      ]
      res = await publishEvalSet(writeSetName.value.trim(), samples)
    } else if (writeMode.value === 'adopted') {
      res = await raiseFromAdoptedCases(writeSetName.value.trim(), adoptedCaseIds.value)
    } else if (writeMode.value === 'training') {
      res = await raiseFromTrainingSamples(writeSetName.value.trim(), trainingSampleIds.value)
    } else {
      res = await raiseFromBatch(writeSetName.value.trim(), batchTaskIds.value)
    }
    writeVisible.value = false
    await applyPublished(res)
  } catch (err: any) {
    // Core rejections are forwarded by the bridge with their own message —
    // shown as-is, the dialog stays open so the user can fix and retry.
    ElMessage.error(err?.message || t('judge.evalset.publishFailed'))
  } finally {
    writeBusy.value = false
  }
}

onMounted(async () => {
  // URL first (deep link / reload), then the newest observed set+version
  // from run history as the friendly default.
  const q = route.query
  setName.value = typeof q.setName === 'string' ? q.setName : ''
  version.value = typeof q.version === 'string' && Number(q.version) > 0 ? Number(q.version) : undefined
  keyword.value = typeof q.keyword === 'string' ? q.keyword : ''
  page.value = typeof q.page === 'string' && Number(q.page) > 0 ? Number(q.page) : 1
  try {
    const history = await listJudgeRuns(1, 50)
    const names = new Set<string>()
    let bestName = ''
    let bestVersion = 0
    for (const run of history.runs ?? []) {
      if (!run.evalsetName) continue
      names.add(run.evalsetName)
      const v = run.evalsetVersion ?? 0
      if (bestName === '' || (run.evalsetName === bestName && v > bestVersion)) {
        bestName = run.evalsetName
        bestVersion = v
      }
    }
    setNameOptions.value = [...names].sort()
    if (!setName.value && setNameOptions.value.length) {
      setName.value = bestName
      version.value = bestVersion || 1
    }
    if (setName.value && version.value) await loadSamples()
  } catch {
    // Candidates are a convenience only; the page stays usable by typing
    // a known set name. Honest empty state carries the guidance.
  }
})
</script>

<style scoped>
.w-setname { width: 260px; }
.w-version { width: 150px; }
.version-caption { font-size: 13px; color: var(--el-text-color-regular); }
.promote-caret { margin-left: 4px; }
.w-keyword { width: 260px; }
.result-meta { margin-bottom: 8px; }
.total-chip { font-size: 12px; opacity: 0.75; }
.w-full { width: 100%; }
.mode-hint { margin: 0 0 8px; font-size: 12px; opacity: 0.75; }
.draft-row {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}
.draft-cell { flex: 1; }
.guard-hint { margin-right: 12px; font-size: 12px; color: var(--el-color-warning); }
</style>
