<template>
  <div class="page-root plan-detail-page" data-test="plan-detail-page">
    <div class="page-card">
      <div class="page-header">
        <div class="header-left">
          <el-button link type="primary" :icon="ArrowLeft" data-test="plan-detail-back" @click="goBack">{{ t('common.back') }}</el-button>
          <h2 class="page-title" data-test="plan-detail-name">{{ plan?.name || t('plan.loading') }}</h2>
          <el-tag v-if="plan" :type="planStatusTag(plan.status)" size="small" data-test="plan-detail-status">{{ plan.statusLabel }}</el-tag>
        </div>
        <div class="header-actions">
          <el-button v-if="plan && plan.status === 1" type="primary" data-test="plan-detail-publish" @click="publish">
            {{ t('plan.publish') }}
          </el-button>
          <el-button v-if="plan && plan.status === 2" type="warning" data-test="plan-detail-genbatch" @click="generateBatches">
            {{ t('plan.genBatches') }}
          </el-button>
          <el-button v-if="plan && plan.status === 3" data-test="plan-detail-completion" @click="completionCheck">
            {{ t('plan.completionCheck') }}
          </el-button>
          <el-button
            v-if="plan && (plan.status === 1 || plan.status === 2 || plan.status === 3)"
            type="danger"
            plain
            data-test="plan-detail-cancel"
            @click="cancel"
          >
            {{ t('plan.cancel') }}
          </el-button>
        </div>
      </div>

      <div v-if="plan" class="info-cards">
        <el-card shadow="never" class="info-card">
          <div class="info-item"><span class="label">{{ t('plan.targetVersion') }}</span><span data-test="plan-detail-version">{{ plan.targetVersion || '-' }}</span></div>
          <div class="info-item"><span class="label">{{ t('plan.period') }}</span><span>{{ plan.planStart || '-' }} ~ {{ plan.planEnd || '-' }}</span></div>
          <div class="info-item"><span class="label">{{ t('plan.caseCount') }}</span><span data-test="plan-detail-casecount">{{ plan.caseCount }}</span></div>
          <div class="info-item">
            <span class="label">{{ t('plan.lockMode') }}</span>
            <span data-test="plan-detail-lockmode">
              <el-tag v-if="plan.lockMode === 2" type="danger" size="small">{{ t('plan.lockStrict') }}</el-tag>
              <el-tag v-else type="info" size="small">{{ t('plan.lockLoose') }}</el-tag>
            </span>
          </div>
        </el-card>
        <el-card shadow="never" class="info-card">
          <div class="info-item"><span class="label">{{ t('plan.envs') }}</span><span>{{ envNames.join('、') || '-' }}</span></div>
          <div class="info-item"><span class="label">{{ t('plan.requireSmoke') }}</span><span>{{ plan.entryCriteria?.requireSmokePass ? t('plan.yes') : t('plan.no') }}</span></div>
          <div class="info-item"><span class="label">{{ t('plan.minPassRate') }}</span><span>{{ (plan.exitCriteria?.minPassRate ?? 0) * 100 }}%</span></div>
        </el-card>
        <el-card shadow="never" class="info-card">
          <div class="info-item"><span class="label">{{ t('plan.scopePhases') }}</span><span>{{ phaseLabels.join('、') || '-' }}</span></div>
          <div class="info-item"><span class="label">{{ t('plan.scopePriorities') }}</span><span>{{ priorityLabels.join('、') || '-' }}</span></div>
          <div class="info-item"><span class="label">{{ t('plan.noOpenDefects') }}</span><span>{{ plan.exitCriteria?.noOpenDefects ? t('plan.yes') : t('plan.no') }}</span></div>
        </el-card>
      </div>

      
      <el-card v-if="plan && plan.status >= 2" shadow="never" class="progress-card" data-test="plan-progress-card">
        <template #header>
          <div class="card-header">
            <span>{{ t('plan.progress') }}</span>
            <span class="progress-summary" data-test="plan-progress-summary">
              {{ t('plan.progressSummary', { total: progress.total, passed: progress.passed, failed: progress.failed, notRun: progress.notRun }) }}
              （{{ (progress.passRate * 100).toFixed(1) }}%）
            </span>
          </div>
        </template>
        <div class="progress-dims">
          <div v-for="dim in progressDims" :key="dim.key" class="dim-block">
            <div class="dim-title">{{ dim.title }}</div>
            <DataTablePage :data="dim.rows" size="small" class="dim-table">
              <el-table-column prop="label" :label="t('plan.dimLabel')" min-width="110" show-overflow-tooltip />
              <el-table-column prop="passed" :label="t('plan.passed')" width="70" align="center" />
              <el-table-column prop="failed" :label="t('plan.failed')" width="70" align="center" />
              <el-table-column prop="notRun" :label="t('plan.notRun')" width="80" align="center" />
            </DataTablePage>
          </div>
        </div>
      </el-card>

      
      <el-card v-if="plan" shadow="never" class="deviation-card" data-test="plan-deviation-card">
        <template #header>
          <div class="card-header">
            <span>{{ t('plan.deviations') }}</span>
            <div class="header-actions">
              <el-button v-if="plan.lockMode === 2" size="small" type="primary" plain data-test="plan-deviation-add" @click="registerVisible = true">
                {{ t('plan.registerDeviation') }}
              </el-button>
            </div>
          </div>
        </template>
        <DataTablePage :data="deviations" size="small" data-test="plan-deviation-table" :empty-title="t('plan.noDeviations')">
          <el-table-column :label="t('plan.devType')" width="130">
            <template #default="{ row }">
              <el-tag :type="row.deviationType === 2 ? 'warning' : 'primary'" size="small">{{ row.deviationTypeLabel }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="caseNo" :label="t('plan.devCaseNo')" width="120" show-overflow-tooltip />
          <el-table-column prop="caseTitle" :label="t('plan.devCaseTitle')" min-width="160" show-overflow-tooltip />
          <el-table-column prop="reason" :label="t('plan.devReason')" min-width="220" show-overflow-tooltip />
          <el-table-column prop="createdByName" :label="t('plan.devCreator')" width="110" show-overflow-tooltip />
          <el-table-column prop="createdAt" :label="t('plan.devTime')" width="150">
              <template #default="{ row }"><TimeCell :iso="row.createdAt" /></template>
            </el-table-column>
        </DataTablePage>
      </el-card>

      
      <el-dialog v-model="registerVisible" :title="t('plan.registerDeviation')" width="640px" destroy-on-close>
        <el-form label-width="96px">
          <el-form-item :label="t('plan.devType')" required>
            <!-- id="" prevents the form label for= from targeting the non-labelable radiogroup div -->
            <el-radio-group id="" v-model="regForm.deviationType" data-test="deviation-type">
              <el-radio :value="1">{{ t('plan.devOutOfScope') }}</el-radio>
              <el-radio :value="2">{{ t('plan.devScopeChange') }}</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item :label="t('plan.devCase')" required>
            <el-select
              v-model="regForm.caseId"
              filterable
              style="width: 100%"
              data-test="deviation-case"
              :placeholder="t('plan.devCasePlaceholder')"
            >
              <el-option v-for="c in planCases" :key="c.caseId" :label="`${c.caseNo} ${c.title}`" :value="c.caseId" />
              <el-option v-for="c in outOfScopeCases" :key="c.id" :label="`${c.caseNo} ${c.title}（${t('plan.devOutOfScope')}）`" :value="c.id" />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('plan.devReason')" required>
            <el-input
              v-model="regForm.reason"
              type="textarea"
              :rows="3"
              maxlength="500"
              show-word-limit
              data-test="deviation-reason"
              :placeholder="t('plan.devReasonPlaceholder')"
            />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="registerVisible = false">{{ t('plan.cancel') }}</el-button>
          <el-button type="primary" :loading="savingDev" data-test="deviation-submit" @click="submitDeviation">
            {{ t('plan.confirm') }}
          </el-button>
        </template>
      </el-dialog>

      
      <el-card shadow="never" class="cases-card">
        <template #header>
          <div class="card-header">
            <span>{{ t('plan.caseList') }}</span>
            <div class="header-actions">
              <el-button
                v-if="plan && (plan.status === 1 || plan.status === 2)"
                size="small"
                type="primary" link
                data-test="plan-detail-assign"
                @click="assignVisible = true"
              >
                {{ t('plan.assign') }}
              </el-button>
            </div>
          </div>
        </template>
        <TableSkeleton v-if="loading" :cols="6" data-test="plandetailview-skeleton" />
        <DataTablePage :data="planCases" data-test="plan-case-table" row-key="id" size="small" v-else :empty-title="t('plan.caseListEmpty')" :empty-hint="t('plan.caseListEmptyHint')">
          <el-table-column prop="caseNo" :label="t('plan.caseNo')" width="160" />
          <el-table-column prop="title" :label="t('plan.caseTitle')" min-width="200" show-overflow-tooltip />
          <el-table-column prop="phase" :label="t('plan.phase')" width="80" align="center">
            <template #default="{ row }">{{ phaseLabel(row.phase) }}</template>
          </el-table-column>
          <el-table-column prop="assigneeName" :label="t('plan.assignee')" width="120">
            <template #default="{ row }">{{ row.assigneeName || '-' }}</template>
          </el-table-column>
          <el-table-column prop="status" :label="t('plan.caseStatus')" width="100">
            <template #default="{ row }">
              <el-tag :type="caseStatusTag(row.status)" size="small">{{ row.statusLabel }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="batchCaseRef" :label="t('plan.batchRef')" width="90" align="center">
            <template #default="{ row }">{{ row.batchCaseRef || '-' }}</template>
          </el-table-column>
        </DataTablePage>
      </el-card>

      
      <el-dialog v-model="assignVisible" :title="t('plan.assignTitle')" width="640px" destroy-on-close>
        <div class="assign-row">
          <span class="label">{{ t('plan.assignee') }}：</span>
          <el-select v-model="assigneeId" filterable style="width: 220px" data-test="plan-assign-select" :placeholder="t('plan.assigneePlaceholder')">
            <el-option v-for="m in members" :key="m.userId" :label="m.nickName || m.username" :value="m.userId" />
          </el-select>
        </div>
        <DataTablePage
          ref="assignTableRef"
          :data="assignableCases"
          data-test="plan-assign-table"
          size="small"
          row-key="id"
          class="assign-table"
          @selection-change="(sel: PlanCaseRow[]) => (selectedCaseIds = sel.map((s) => s.caseId))"
        >
          <el-table-column type="selection" width="44" />
          <el-table-column prop="caseNo" :label="t('plan.caseNo')" width="160" />
          <el-table-column prop="title" :label="t('plan.caseTitle')" min-width="200" show-overflow-tooltip />
        </DataTablePage>
        <template #footer>
          <el-button data-test="plan-assign-cancel" @click="assignVisible = false">{{ t('plan.cancelBtn') }}</el-button>
          <el-button type="primary" :disabled="selectedCaseIds.length === 0 || !assigneeId" data-test="plan-assign-submit" @click="doAssign">
            {{ t('plan.assignOk') }}
          </el-button>
        </template>
      </el-dialog>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { useGoBack } from '@/composables/useGoBack'
import { setBreadcrumbTitle } from '@/composables/useBreadcrumb'
import { useObjectSync } from '@/composables/useObjectSync'
import { ArrowLeft } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { bizErrorMsg } from '@/utils/bizError'
import { getProjectEnvs, getProjectMembers, type ProjectMember } from '@/api/project'
import {
  assignPlanCases,
  cancelPlan,
  checkPlanCompletion,
  generatePlanBatches,
  getPlan,
  getPlanProgress,
  listPlanCases,
  listPlanDeviations,
  planStatusTag,
  publishPlan,
  registerPlanDeviation,
  type PlanCaseView,
  type PlanDeviationView,
  type PlanView,
  type ProgressDimRow,
} from '@/api/plan'
import { listCases, PHASE_LABEL, PRIORITY_LABEL } from '@/api/case'

const { t } = useI18n()
const route = useRoute()
const planId = Number(route.params.id)


const goBack = useGoBack('/plans')

const plan = ref<PlanView | null>(null)
const planCases = ref<PlanCaseView[]>([])
const progress = ref<{
  total: number
  passed: number
  failed: number
  notRun: number
  running: number
  skipped: number
  passRate: number
  byAssignee: ProgressDimRow[]
  byPhase: ProgressDimRow[]
  byEnv: ProgressDimRow[]
}>({ total: 0, passed: 0, failed: 0, notRun: 0, running: 0, skipped: 0, passRate: 0, byAssignee: [], byPhase: [], byEnv: [] })
const envs = ref<{ id: number; name: string; envType: number; healthStatus?: number; healthMsg?: string | null }[]>([])
const members = ref<ProjectMember[]>([])
const loading = ref(false)

const deviations = ref<PlanDeviationView[]>([])
const registerVisible = ref(false)
const savingDev = ref(false)
const regForm = reactive<{ caseId: number | null; deviationType: number; reason: string }>({
  caseId: null,
  deviationType: 1,
  reason: '',
})
const outOfScopeCases = ref<{ id: number; caseNo: string; title: string }[]>([])

watch(registerVisible, async (visible) => {
  if (visible && plan.value) {
    const res = await listCases({ projectId: plan.value.projectId, page: 1, size: 200 })
    const inPlan = new Set(planCases.value.map((c) => c.caseId))
    outOfScopeCases.value = (res.list || []).filter((c) => !inPlan.has(c.id))
  }
})

const assignVisible = ref(false)
const assigneeId = ref<number | null>(null)
const selectedCaseIds = ref<number[]>([])
const assignableCases = computed(() => planCases.value.filter((c) => !c.assigneeId))

interface PlanCaseRow extends PlanCaseView {}

const envNames = computed(() => envs.value.filter((e) => plan.value?.envIds.includes(e.id)).map((e) => e.name))
const phaseLabels = computed(() => (plan.value?.caseScope?.phases || []).map((p) => phaseLabel(p)))
const priorityLabels = computed(() => (plan.value?.caseScope?.priorities || []).map((p) => priorityLabel(p)))

const progressDims = computed(() => [
  { key: 'byAssignee', title: t('plan.dimAssignee'), rows: progress.value.byAssignee || [] },
  { key: 'byPhase', title: t('plan.dimPhase'), rows: progress.value.byPhase || [] },
  { key: 'byEnv', title: t('plan.dimEnv'), rows: progress.value.byEnv || [] },
])

function phaseLabel(p: number | null): string {
  return PHASE_LABEL[p ?? -1] ?? '-'
}
function priorityLabel(p: number): string {
  return PRIORITY_LABEL[p] ?? String(p)
}
function caseStatusTag(s: number): 'info' | 'warning' | 'success' | 'danger' | 'primary' {
  return ({ 1: 'info', 2: 'warning', 3: 'success', 4: 'danger', 5: 'primary' } as Record<number, 'info' | 'warning' | 'success' | 'danger' | 'primary'>)[s] || 'info'
}

async function loadAll(): Promise<void> {
  loading.value = true
  try {
    plan.value = await getPlan(planId)
    setBreadcrumbTitle('PlanDetail', plan.value?.name ?? null)
    planCases.value = await listPlanCases(planId)
    envs.value = (await getProjectEnvs(plan.value.projectId)) as { id: number; name: string; envType: number; healthStatus?: number; healthMsg?: string | null }[]
    members.value = (await getProjectMembers(plan.value.projectId)) as ProjectMember[]
    if (plan.value.status >= 2) {
      progress.value = await getPlanProgress(planId)
    }
    deviations.value = await listPlanDeviations(planId)
  } finally {
    loading.value = false
  }
}

async function submitDeviation(): Promise<void> {
  if (!regForm.caseId) {
    ElMessage.warning(t('plan.devCaseRequired'))
    return
  }
  if (!regForm.reason || !regForm.reason.trim()) {
    ElMessage.warning(t('plan.devReasonRequired'))
    return
  }
  savingDev.value = true
  try {
    await registerPlanDeviation(planId, {
      caseId: regForm.caseId,
      deviationType: regForm.deviationType,
      reason: regForm.reason.trim(),
    })
    ElMessage.success(t('plan.devRegOk'))
    registerVisible.value = false
    regForm.caseId = null
    regForm.reason = ''
    await loadAll()
  } finally {
    savingDev.value = false
  }
}

function publish(): void {
  ElMessageBox.confirm(t('plan.publishConfirm', { name: plan.value?.name }), t('plan.publish'), { type: 'warning' })
    .then(async () => {
      await publishPlan(planId)
      ElMessage.success(t('plan.publishOk'))
      loadAll()
    })
    .catch(() => {})
}

function generateBatches(): void {
  ElMessageBox.confirm(t('plan.genBatchConfirm', { name: plan.value?.name }), t('plan.genBatches'), { type: 'warning' })
    .then(async () => {
      const badEnvs = envs.value.filter(
        (e) => plan.value?.envIds.includes(e.id) && e.healthStatus === 2,
      )
      if (badEnvs.length) {
        const reasons = badEnvs
          .map((e) => t('plan.envHealthAbnormal', { name: e.name, msg: e.healthMsg || t('plan.envHealthNoReason') }))
          .join('；')
        await ElMessageBox.confirm(t('plan.envHealthConfirm', { reasons }), t('plan.envHealthTitle'), {
          type: 'warning',
          confirmButtonText: t('plan.runAnyway'),
          cancelButtonText: t('common.cancel'),
        })
      }
      const res = await generatePlanBatches(planId)
      if (res.envHealthWarning) {
        ElMessage.warning(res.envHealthWarning)
      }
      if (res.skipCount > 0 && res.skippedCases?.length) {
        const flakyReason = '\u5df2\u9694\u79bb\uff08\u4e0d\u7a33\u5b9a\uff09'
        const flakyLabel = t('case.flakyIsolatedReason')
        const lines = res.skippedCases.map(
          (c) => `[${c.caseNo || '-'}] ${c.title}（${c.reason === flakyReason ? flakyLabel : caseStatusText(c.status)}）`,
        )
        const flakyN = res.skippedCases.filter((c) => c.reason === flakyReason).length
        const n6N = res.skipCount - flakyN
        const head = [n6N > 0 ? t('plan.skipNoReview', { n: n6N }) : '', flakyN > 0 ? t('plan.skipFlakyIsolated', { n: flakyN }) : '']
          .filter(Boolean).join('，')
        await ElMessageBox.alert(lines.join('\n'), t('plan.skipAlertTitle', { action: t('plan.genBatches'), head }), {
          type: 'warning',
          customStyle: { whiteSpace: 'pre-line', maxHeight: '50vh', overflow: 'auto' },
        })
      } else {
        ElMessage.success(res.message || t('plan.genBatchOk'))
      }
      loadAll()
    })
    .catch((e: unknown) => {
      ElMessage.error(bizErrorMsg(e, t('plan.genBatchFail')))
      loadAll()
    })
}


function caseStatusText(status: number): string {
  if (status === 1) return t('plan.statusReview')
  if (status === 2) return t('plan.statusReviewing')
  return t('plan.statusFallback', { status })
}

async function completionCheck(): Promise<void> {
  const cc = await checkPlanCompletion(planId)
  if (cc.passed) {
    ElMessage.success(cc.message)
  } else {
    ElMessage.warning(cc.message)
  }
  loadAll()
}

function cancel(): void {
  ElMessageBox.confirm(t('plan.cancelConfirm', { name: plan.value?.name }), t('plan.cancel'), { type: 'warning' })
    .then(async () => {
      await cancelPlan(planId)
      ElMessage.success(t('plan.cancelOk'))
      loadAll()
    })
    .catch(() => {})
}

async function doAssign(): Promise<void> {
  if (!assigneeId.value) return
  const count = await assignPlanCases(planId, selectedCaseIds.value, assigneeId.value)
  ElMessage.success(t('plan.assignOkMsg', { count }))
  assignVisible.value = false
  loadAll()
}

// : a landed plan write anchored to THIS plan re-reads the whole
// detail face silently; announcements of other plans are ignored (anchor).
useObjectSync({
  watch: ['PLAN_WRITE'],
  anchor: (e) => e.objectId === planId,
  onSync: () => void loadAll(),
})

onMounted(loadAll)
onBeforeUnmount(() => {
  setBreadcrumbTitle('PlanDetail', null)
})
</script>

<style scoped>
.page-root {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.header-left {
  display: flex;
  align-items: center;
  gap: 10px;
}
.info-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 12px;
}
.info-card :deep(.el-card__body) {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.info-item {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  font-size: 13px;
}
.info-item .label {
  color: var(--el-text-color-secondary);
  flex-shrink: 0;
}
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.progress-summary {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.progress-dims {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 12px;
}
.dim-title {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  margin-bottom: 6px;
}
.assign-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}
.assign-table {
  margin-top: 6px;
}
</style>
