<!--
  tp-sort-exempt: the run ledger follows the core query contract — rows are
  newest-first by creation time and the bridge has no sort lane; wiring
  sort-change would require a second decision source (presentation-side red
  line). Re-check at the batch verification package if core grows a sort
  contract.
  tp-column-config-exempt: seven light presentation columns (id / rubric /
  evalset / target / status / created / finished) all carry the same load
  for the ledger reading; column settings deferred to the batch verification
  package (registered in the chain progress carrier).
-->
<!--
  Run-history door (AW4 of the judge write-chain facade).
  - Creates evaluation runs over the three pinned elements — a published
    evaluation-set version, a rubric version and the target under test —
    consuming the admin bridge /api/v1/judge-runs family. Every decision
    (anchor existence, snapshot match, dispatch gates, status machine)
    stays in core; this page only echoes rows and rejections (red line:
    zero second computation on the presentation side).
  - Worst-case-size form (S group): server-side paging + server-side
    filters (set name / status), so a long run ledger stays navigable.
  - Page state (filters / page) lives in the URL query — reload, back and
    share restore the same view (F group).
  - Row click opens the report deep link (?runId=) owned by
    JudgeReportView — the same slot the K2 notification jump uses.
  - Layout follows the three-band rule: condition flow with the search
    button inline at the group end; page-level action (Start Evaluation)
    plus view-state refresh in the filter-bar trailing slot (merge tier:
    no batch action bar, condition controls <= 4).
-->
<template>
  <div class="page-root" data-test="judge-runs-page">
    <div class="page-card">
      <div class="filter-bar">
        <el-select
          v-model="filterName"
          filterable
          clearable
          :placeholder="t('judge.runs.namePlaceholder')"
          data-test="judge-runs-name-filter"
          class="w-filter"
        >
          <el-option
            v-for="n in setNameOptions"
            :key="n"
            :label="n"
            :value="n"
          />
        </el-select>
        <el-select
          v-model="filterStatus"
          clearable
          :placeholder="t('judge.runs.statusPlaceholder')"
          data-test="judge-runs-status-filter"
          class="w-status"
        >
          <el-option :label="t('judge.runs.statusAll')" :value="''" />
          <el-option :label="t('judge.runs.statusPending')" :value="0" />
          <el-option :label="t('judge.runs.statusRunning')" :value="1" />
          <el-option :label="t('judge.runs.statusDone')" :value="2" />
          <el-option :label="t('judge.runs.statusFailed')" :value="3" />
        </el-select>
        <el-button
          type="primary"
          :icon="Search"
          :loading="loading"
          data-test="judge-runs-search-btn"
          @click="searchRuns"
        >
          {{ t('judge.runs.searchBtn') }}
        </el-button>
        <div class="filter-bar-actions">
          <el-button
            type="primary"
            :icon="Plus"
            v-permission="'perm:judge:run:create'"
            data-test="judge-runs-create-btn"
            @click="openCreate"
          >
            {{ t('judge.runs.createBtn') }}
          </el-button>
          <el-button
            :icon="Refresh"
            circle
            :disabled="loading"
            data-test="judge-runs-refresh-btn"
            :title="t('judge.runs.refreshBtn')"
            @click="loadRuns"
          />
        </div>
      </div>

      <div class="result-meta">
        <span v-if="hasLoaded" class="total-chip" data-test="judge-runs-total-chip">
          {{ t('judge.runs.totalLabel', { n: total }) }}
        </span>
      </div>

      <TableSkeleton v-if="loading" :rows="6" :cols="7" />
      <EmptyState
        v-else-if="!rows.length"
        :title="t('judge.runs.emptyTitle')"
        :hint="t('judge.runs.emptyHint')"
        :filtered="hasFiltered"
        data-test="judge-runs-empty"
      />
      <template v-else>
        <el-table
          :data="rows"
          border
          data-test="judge-runs-table"
          @row-click="openReport"
        >
          <el-table-column prop="id" :label="t('judge.runs.colId')" width="92" />
          <el-table-column :label="t('judge.runs.colRubric')" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">
              {{ row.rubricName ?? '-' }}<span v-if="row.rubricVersion != null"> · v{{ row.rubricVersion }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('judge.runs.colEvalset')" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">
              {{ row.evalsetName ?? '-' }}<span v-if="row.evalsetVersion != null"> · v{{ row.evalsetVersion }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('judge.runs.colTarget')" min-width="170" show-overflow-tooltip>
            <template #default="{ row }">{{ targetLabel(row) }}</template>
          </el-table-column>
          <el-table-column :label="t('judge.runs.colStatus')" width="110">
            <template #default="{ row }">
              <span class="status-cell" :data-test="`judge-run-status-${row.id}`">
                <span class="status-dot" :class="`status-dot--${statusKey(row.status)}`" />
                {{ statusLabel(row.status) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column prop="createdAt" :label="t('judge.runs.colCreatedAt')" width="170" />
          <el-table-column prop="finishedAt" :label="t('judge.runs.colFinishedAt')" width="170" />
          <el-table-column :label="t('judge.runs.colActions')" width="180" fixed="right">
            <template #default="{ row }">
              <el-button
                text
                type="primary"
                size="small"
                data-test="judge-runs-report-btn"
                @click.stop="openReport(row)"
              >
                {{ t('judge.runs.openReportBtn') }}
              </el-button>
              <el-button
                v-if="row.status === 0"
                v-permission="'perm:judge:run:create'"
                text
                type="primary"
                size="small"
                :loading="dispatchingId === row.id"
                data-test="judge-runs-dispatch-btn"
                @click.stop="dispatchRun(row)"
              >
                {{ t('judge.runs.dispatchBtn') }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="pagination-bar">
          <el-pagination
            v-if="total > pageSize"
            layout="total, prev, pager, next"
            :total="total"
            :page-size="pageSize"
            :current-page="page"
            data-test="judge-runs-pagination"
            @current-change="(p: number) => { page = p; syncQuery(); loadRuns() }"
          />
        </div>
      </template>
    </div>

    <!-- Run-creation door: the three elements are picked, never typed —
         published set versions and rubric families come from the picker
         bridges; core freezes the anchors at creation. The submit button
         stays disabled with an on-screen hint until all three are set
         (R28 guard shape) and locks while a request is in flight (F5). -->
    <el-dialog
      v-model="createVisible"
      :title="t('judge.runs.createTitle')"
      class="tp-dialog--md"
      append-to-body
      data-test="judge-runs-create-dialog"
    >
      <el-form label-position="top">
        <el-form-item :label="t('judge.runs.createEvalsetLabel')">
          <el-select
            v-model="pickedVersionKey"
            filterable
            :loading="pickerLoading"
            :placeholder="t('judge.runs.createEvalsetPlaceholder')"
            class="w-full"
            data-test="judge-run-create-evalset"
          >
            <el-option
              v-for="o in evalsetOptions"
              :key="`${o.setName}|${o.version}`"
              :label="t('judge.runs.evalsetOptionLabel', { name: o.setName, version: o.version, count: o.sampleCount ?? '-' })"
              :value="`${o.setName}|${o.version}`"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('judge.runs.createRubricLabel')">
          <el-select
            v-model="pickedRubricId"
            filterable
            :loading="pickerLoading"
            :placeholder="t('judge.runs.createRubricPlaceholder')"
            class="w-full"
            data-test="judge-run-create-rubric"
          >
            <el-option
              v-for="r in rubricOptions"
              :key="r.id"
              :label="t('judge.runs.rubricOptionLabel', { name: r.name, version: r.version ?? '-', domain: r.domain ?? '-' })"
              :value="r.id"
            />
          </el-select>
        </el-form-item>
        <!-- Radio groups are not labelable targets, so this block must not wire
             an el-form-item label `for` at any render tick: a transient
             for=<not-yet-mounted id> is flagged by the browser as a form
             association error. Native fieldset/legend names the group with no
             `for` wiring; legend carries the el-form-item__label class so the
             caption keeps the same look as the sibling items. -->
        <fieldset class="create-type-group">
          <legend class="el-form-item__label">{{ t('judge.runs.createTypeLabel') }}</legend>
          <el-radio-group v-model="targetType" data-test="judge-run-create-type">
            <el-radio value="pipeline">{{ t('judge.runs.targetPipeline') }}</el-radio>
            <el-radio value="external">{{ t('judge.runs.targetExternal') }}</el-radio>
          </el-radio-group>
        </fieldset>
        <el-form-item :label="t('judge.runs.createRefLabel')">
          <el-input
            v-model="targetRef"
            :placeholder="targetType === 'pipeline'
              ? t('judge.runs.createRefHintPipeline')
              : t('judge.runs.createRefHintExternal')"
            data-test="judge-run-create-ref"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <span v-if="!canSubmit" class="guard-hint" data-test="judge-run-create-guard">
          {{ t('judge.runs.createGuardHint') }}
        </span>
        <el-button data-test="judge-run-create-cancel" @click="createVisible = false">
          {{ t('common.cancel') }}
        </el-button>
        <el-button
          type="primary"
          :disabled="!canSubmit"
          :loading="creating"
          data-test="judge-run-create-submit"
          @click="submitCreate"
        >
          {{ t('judge.runs.createSubmitBtn') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Search, Refresh, Plus } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import {
  createJudgeRun,
  dispatchJudgeRun,
  listEvalsetVersions,
  listJudgeRuns,
  listRubricOptions,
} from '@/api/evalSet'
import type {
  EvalsetVersionOption,
  JudgeRunRow,
  RubricOption,
} from '@/api/evalSet'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()

// ---- list plane (server-side paging + filters) --------------------------
const filterName = ref('')
const filterStatus = ref<number | ''>('')
const rows = ref<JudgeRunRow[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = 20
const loading = ref(false)
const hasLoaded = ref(false)

const hasFiltered = computed(() => filterName.value !== '' || filterStatus.value !== '')

const STATUS_KEYS: Record<number, string> = { 0: 'pending', 1: 'running', 2: 'done', 3: 'failed' }
const STATUS_LABEL_KEYS: Record<number, string> = {
  0: 'judge.runs.statusPending',
  1: 'judge.runs.statusRunning',
  2: 'judge.runs.statusDone',
  3: 'judge.runs.statusFailed',
}
function statusKey(status?: number | null): string {
  return STATUS_KEYS[status ?? -1] ?? 'pending'
}
function statusLabel(status?: number | null): string {
  const key = STATUS_LABEL_KEYS[status ?? -1]
  return key ? t(key) : String(status ?? '-')
}
function targetLabel(row: JudgeRunRow): string {
  if (!row.targetType) return '-'
  const type = row.targetType === 'pipeline'
    ? t('judge.runs.targetPipeline')
    : row.targetType === 'external'
      ? t('judge.runs.targetExternal')
      : row.targetType
  return `${type} · ${row.targetRef ?? '-'}`
}

function syncQuery() {
  router.replace({
    query: {
      ...(filterName.value ? { setName: filterName.value } : {}),
      ...(filterStatus.value !== '' ? { status: String(filterStatus.value) } : {}),
      ...(page.value > 1 ? { page: String(page.value) } : {}),
    },
  })
}

async function loadRuns() {
  loading.value = true
  try {
    const res = await listJudgeRuns(page.value, pageSize, {
      ...(filterName.value ? { evalsetName: filterName.value } : {}),
      ...(filterStatus.value !== '' ? { status: filterStatus.value } : {}),
    })
    rows.value = res.runs ?? []
    total.value = res.total ?? 0
    hasLoaded.value = true
  } catch (err: any) {
    ElMessage.error(err?.message || t('judge.runs.loadFailed'))
  } finally {
    loading.value = false
  }
}

function searchRuns() {
  page.value = 1
  syncQuery()
  loadRuns()
}

// Set-name candidates for the filter come from published versions (the
// same picker source the create form uses), so the dropdown always shows
// sets that can actually be evaluated.
const setNameOptions = ref<string[]>([])

function openReport(row: JudgeRunRow) {
  router.push({ path: '/judge/report', query: { runId: String(row.id) } })
}

// ---- dispatch door (pending rows only; gates live in core) --------------
const dispatchingId = ref<number | null>(null)
async function dispatchRun(row: JudgeRunRow) {
  dispatchingId.value = row.id
  try {
    const receipt = await dispatchJudgeRun(row.id)
    ElMessage.success(t('judge.runs.dispatchSuccess', {
      n: receipt.dispatched,
      provider: receipt.judgeProvider ?? '-',
    }))
    await loadRuns()
  } catch (err: any) {
    // Core rejections are forwarded by the bridge with their own reason —
    // shown as-is; the row stays pending so the user can fix and retry.
    ElMessage.error(err?.message || t('judge.runs.dispatchFailed'))
  } finally {
    dispatchingId.value = null
  }
}

// ---- creation door -------------------------------------------------------
const createVisible = ref(false)
const creating = ref(false)
const pickerLoading = ref(false)
const evalsetOptions = ref<EvalsetVersionOption[]>([])
const rubricOptions = ref<RubricOption[]>([])
const pickedVersionKey = ref('')
const pickedRubricId = ref<number | undefined>(undefined)
const targetType = ref<'pipeline' | 'external'>('pipeline')
const targetRef = ref('')

const canSubmit = computed(() =>
  pickedVersionKey.value !== ''
  && pickedRubricId.value != null
  && targetRef.value.trim() !== ''
  && !creating.value,
)

async function openCreate() {
  createVisible.value = true
  if (evalsetOptions.value.length || rubricOptions.value.length) return
  pickerLoading.value = true
  try {
    const [sets, rubrics] = await Promise.all([listEvalsetVersions(), listRubricOptions()])
    evalsetOptions.value = sets ?? []
    rubricOptions.value = rubrics ?? []
    setNameOptions.value = [...new Set(evalsetOptions.value.map((o) => o.setName))].sort()
  } catch (err: any) {
    ElMessage.error(err?.message || t('judge.runs.pickerLoadFailed'))
  } finally {
    pickerLoading.value = false
  }
}

async function submitCreate() {
  if (!canSubmit.value) return
  const picked = evalsetOptions.value.find((o) => `${o.setName}|${o.version}` === pickedVersionKey.value)
  const rubric = rubricOptions.value.find((r) => r.id === pickedRubricId.value)
  if (!picked || !rubric) return
  creating.value = true
  try {
    const run = await createJudgeRun({
      rubricId: rubric.id,
      evalsetName: picked.setName,
      evalsetVersion: picked.version,
      evalsetDigest: picked.snapshotDigest,
      targetType: targetType.value,
      targetRef: targetRef.value.trim(),
    })
    createVisible.value = false
    ElMessage.success(t('judge.runs.createSuccess', {
      id: run.id,
      evalset: `${picked.setName} v${picked.version}`,
      rubric: `${rubric.name} v${rubric.version ?? '-'}`,
    }))
    page.value = 1
    syncQuery()
    await loadRuns()
  } catch (err: any) {
    ElMessage.error(err?.message || t('judge.runs.createFailed'))
  } finally {
    creating.value = false
  }
}

onMounted(() => {
  // URL first (deep link / reload), then the server-filtered page loads.
  const q = route.query
  filterName.value = typeof q.setName === 'string' ? q.setName : ''
  filterStatus.value = typeof q.status === 'string' && /^[0-3]$/.test(q.status) ? Number(q.status) : ''
  page.value = typeof q.page === 'string' && Number(q.page) > 0 ? Number(q.page) : 1
  loadRuns()
})
</script>

<style scoped>
.w-filter { width: 240px; }
.w-status { width: 160px; }
.result-meta { margin-bottom: 8px; }
.total-chip { font-size: 12px; opacity: 0.75; }
.w-full { width: 100%; }
.guard-hint { margin-right: 12px; font-size: 12px; color: var(--el-color-warning); }
/* Group caption keeps the el-form-item rhythm: 18px block gap, label line
   22px + 8px gap, same 14px size as the sibling el-form-item captions. */
.create-type-group {
  border: 0;
  margin: 0 0 18px;
  padding: 0;
  min-width: 0;
}
.create-type-group .el-form-item__label {
  display: block;
  width: fit-content;
  height: auto;
  margin-bottom: 8px;
  padding-right: 0;
  line-height: 22px;
  font-size: var(--el-form-label-font-size, 14px);
}
.status-cell { display: inline-flex; align-items: center; gap: 6px; }
.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--el-color-info);
}
.status-dot--pending { background: var(--el-color-info); }
.status-dot--running { background: var(--el-color-warning); }
.status-dot--done { background: var(--el-color-success); }
.status-dot--failed { background: var(--el-color-danger); }
</style>
