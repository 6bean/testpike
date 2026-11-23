<template>
  <div class="page-root plan-list-page" data-test="plan-list-page">
    <div class="page-card">
      <!-- IA fix: screening lives in the shared filter-bar row; the
 project rides the global header context (page selector removed).
 R25 mode declaration (scope-aware single control, TB group): this page
 carries no tabs and exactly one table, so the page-level column-settings
 popover equals the table scope; show/hide, order, pinning and density
 persist per user via the shared piece. FB/R21 same-band merge: no
 row-level batch bar and one filter condition (<= the themed 4-control
 threshold), so the create variant action rides the trailing slot ahead
 of the settings control and the emptied header band is gone. -->
      <div class="filter-bar" data-test="plan-list-filter-bar">
          <el-select
            v-model="filters.status"
            :placeholder="t('plan.statusAll')"
            clearable
            style="width: 130px"
            data-test="plan-filter-status"
            data-se1-exempt="plan status options are a fixed 5-value enum local to this file; never grows with remote data"
            @change="load(1)"
          >
            <el-option v-for="(label, s) in statusOptions" :key="s" :label="label" :value="Number(s)" />
          </el-select>
          <div class="filter-bar-actions" data-test="plan-list-filter-actions">
            <el-tooltip
              :content="t('components.ownerEcho.pickInHeader')"
              placement="bottom"
              :disabled="!!projectId"
            >
              <span>
                <el-button type="primary" :icon="Plus" :disabled="!projectId" data-test="plan-create-btn" @click="openWizard">
                  {{ t('plan.create') }}
                </el-button>
              </span>
            </el-tooltip>
            <ColumnSettings
              :columns="settingsRows"
              :density="colCfg.state.density"
              @toggle="colCfg.toggle"
              @move="colCfg.move"
              @density="colCfg.setDensity"
              @reset="colCfg.reset"
              @pin="colCfg.toggleFixed"
            />
          </div>
        </div>

      <div v-if="!loading && projects.length === 0" class="empty-box" data-test="plan-empty-no-project">
        <el-empty :description="t('plan.noProject')">
          <el-button type="primary" plain data-test="plan-empty-no-project-go" @click="router.push('/projects')">{{ t('case.emptyGoProject') }}</el-button>
        </el-empty>
      </div>

      <TableSkeleton v-else-if="loading" :cols="colCfg.visibleColumns.value.length + 1" data-test="planlistview-skeleton" />
      <el-table border v-else :data="rows" :size="colCfg.state.density" data-test="plan-table" class="data-table" row-key="id" @sort-change="onSortChange">
        <el-table-column
          v-for="col in colCfg.visibleColumns.value"
          :key="col.key"
          :prop="col.key"
          :fixed="col.fixed || false"
          :label="t(col.labelKey)"
          :width="col.width ?? undefined"
          :min-width="col.width ? undefined : PLAN_FLEX_MIN[col.key]"
          :align="col.key === 'caseCount' || col.key === 'assigneeCount' ? 'center' : undefined"
          :show-overflow-tooltip="col.key === 'name' || undefined"
          :sortable="SORT_KEY_MAP[col.key] ? 'custom' : false"
          :data-test="`plan-col-${col.key}`"
        >
          <template #default="{ row }">
            <template v-if="col.key === 'name'">
              <el-link type="primary" data-test="plan-name-link" @click="goDetail(row.id)">
                {{ row.name }}
              </el-link>
            </template>
            <template v-else-if="col.key === 'targetVersion'">{{ row.targetVersion || '-' }}</template>
            <template v-else-if="col.key === 'status'">
              <el-tag :type="planStatusTag(row.status)" size="small" data-test="plan-status-tag">{{ row.statusLabel }}</el-tag>
            </template>
            <template v-else-if="col.key === 'period'">{{ row.planStart || '-' }} ~ {{ row.planEnd || '-' }}</template>
            <template v-else-if="col.key === 'createdAt'"><TimeCell :iso="row.createdAt" /></template>
            <template v-else>{{ row[col.key] }}</template>
          </template>
        </el-table-column>
        <el-table-column :label="t('plan.operation')" width="230" fixed="right">
          <template #default="{ row }">
            <RowActions :actions="planRowActions(row)" :max="3" @command="(k: string) => onPlanRowCmd(k, row)" />
          </template>
        </el-table-column>
        <template #empty>
          <el-empty :description="t('common.emptyNoData')">
            <el-button type="primary" plain data-test="plan-empty-create" @click="openWizard">{{ t('plan.create') }}</el-button>
          </el-empty>
        </template>
      </el-table>

      <div class="pagination-bar">
        <el-pagination v-if="total > 0"
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          data-test="plan-pagination"
          @current-change="load()"
          @size-change="load(1)"
            />
      </div>

   
      <el-dialog draggable v-model="wizardVisible" :title="t('plan.createTitle')" width="640px" :before-close="wizardDraftGuard.beforeClose" data-test="plan-wizard-dialog">
        <!-- ownership echo (iron rule 1): read-only, single-sourced from the
 header project context - the record lands on the visible segment -->
        <ProjectOwnerEcho />
        <el-steps :active="wizardStep" finish-status="success" align-center class="wizard-steps">
          <el-step :title="t('plan.stepBase')" />
          <el-step :title="t('plan.stepScope')" />
          <el-step :title="t('plan.stepCriteria')" />
        </el-steps>

        <el-form v-if="wizardStep === 0" ref="baseFormRef" :model="form" :rules="baseRules" label-width="96px" class="wizard-form" scroll-to-error>
          <el-form-item :label="t('plan.name')" prop="name">
            <el-input v-model="form.name" data-test="plan-wizard-name" maxlength="200" :placeholder="t('plan.namePlaceholder')" />
          </el-form-item>
          <el-form-item :label="t('plan.targetVersion')">
            <el-input v-model="form.targetVersion" data-test="plan-wizard-version" maxlength="50" :placeholder="t('plan.versionPlaceholder')" />
          </el-form-item>
          <el-form-item :label="t('plan.period')">
            <el-date-picker
              v-model="period"
              type="daterange"
              value-format="YYYY-MM-DD"
              :start-placeholder="t('common.startDate')"
              :end-placeholder="t('common.endDate')"
              style="width: 100%"
              :id="['plan-wizard-period-start', 'plan-wizard-period-end']"
              data-test="plan-wizard-period"
              @change="onPeriodChange"
            />
          </el-form-item>
        </el-form>

        <el-form v-else-if="wizardStep === 1" label-width="96px" class="wizard-form">
          <el-form-item :label="t('plan.envs')" required>
            <el-select v-model="form.envIds" multiple filterable style="width: 100%" data-test="plan-wizard-envs" :placeholder="t('plan.envPlaceholder')">
              <el-option v-for="e in envs" :key="e.id" :label="`${e.name}（${envTypeLabel(e.envType)}）`" :value="e.id" />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('plan.phase')" required>
            <el-select v-model="form.caseScope.phases" multiple style="width: 100%" data-test="plan-wizard-phases" data-se1-exempt="case phase scope options are a fixed 4-value enum local to this file; never grows with remote data" :placeholder="t('plan.phasePlaceholder')">
              <el-option v-for="(label, ph) in phaseOptions" :key="ph" :label="label" :value="Number(ph)" />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('plan.priority')" required>
            <el-select v-model="form.caseScope.priorities" multiple style="width: 100%" data-test="plan-wizard-priorities" data-se1-exempt="case priority options are a fixed 3-value enum local to this file; never grows with remote data" :placeholder="t('plan.priorityPlaceholder')">
              <el-option v-for="(label, pr) in priorityOptions" :key="pr" :label="label" :value="Number(pr)" />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('plan.keyword')">
            <el-input v-model="form.caseScope.keyword" data-test="plan-wizard-keyword" :placeholder="t('plan.keywordPlaceholder')" clearable />
          </el-form-item>
          <el-form-item :label="t('plan.tags')">
            <el-select
              v-model="form.caseScope.tagIds"
              multiple
              clearable
              filterable
              style="width: 100%"
              data-test="plan-wizard-tags"
              :placeholder="t('plan.tagsPlaceholder')"
            >
              <el-option v-for="tg in tags" :key="tg.id" :label="tg.name" :value="tg.id" />
            </el-select>
          </el-form-item>
        </el-form>

        <el-form v-else label-width="150px" class="wizard-form">
          <el-form-item :label="t('plan.requireSmoke')">
            <el-switch v-model="form.entryCriteria.requireSmokePass" data-test="plan-wizard-smoke" />
          </el-form-item>
          <el-form-item :label="t('plan.minPassRate')">
            <el-input-number v-model="form.exitCriteria.minPassRate" :min="0" :max="1" :step="0.05" :precision="2" data-test="plan-wizard-rate" />
          </el-form-item>
          <el-form-item :label="t('plan.noOpenDefects')">
            <el-switch v-model="form.exitCriteria.noOpenDefects" data-test="plan-wizard-nodefect" />
          </el-form-item>
          <el-form-item :label="t('plan.lockMode')">
            <!-- id="" prevents the form label for= from targeting the non-labelable radiogroup div -->
            <el-radio-group id="" v-model="form.lockMode" data-test="plan-wizard-lockmode">
              <el-radio :value="1">{{ t('plan.lockLoose') }}</el-radio>
              <el-radio :value="2">{{ t('plan.lockStrict') }}</el-radio>
            </el-radio-group>
            <div class="form-tip">{{ t('plan.lockTip') }}</div>
          </el-form-item>
        </el-form>

        <template #footer>
          <el-button v-if="wizardStep > 0" data-test="plan-wizard-prev" @click="wizardStep--">{{ t('plan.prev') }}</el-button>
          <el-button v-if="wizardStep < 2" type="primary" data-test="plan-wizard-next" @click="nextStep">{{ t('plan.next') }}</el-button>
          <el-button v-else type="primary" :loading="saving" data-test="plan-wizard-submit" @click="submitCreate">{{ t('plan.confirmCreate') }}</el-button>
        </template>
      </el-dialog>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useDraftGuard } from '@/composables/useDraftGuard'
import { useKeepAliveList } from '@/composables/useKeepAliveList'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'
import { useObjectSync } from '@/composables/useObjectSync'
import ColumnSettings from '@/components/ColumnSettings.vue'
import ProjectOwnerEcho from '@/components/ProjectOwnerEcho.vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { bizErrorMsg } from '@/utils/bizError'
import { checkPlanDelete } from '@/api/deletecheck'
import { guardedDelete } from '@/utils/deleteGuard'
import { useAuthStore } from '@/stores/auth'
import { getProjectEnvs, envTypeLabel } from '@/api/project'
import {
  createPlan,
  deletePlan,
  generatePlanBatches,
  listPlans,
  planStatusTag,
  publishPlan,
  type PlanCreatePayload,
  type PlanView,
} from '@/api/plan'
import { listTags, type TagItem } from '@/api/tags'
import { PHASE_LABEL, PRIORITY_LABEL } from '@/api/case'
import { td } from '@/utils/dictLabel'

defineOptions({ name: 'PlanList' })

const { t } = useI18n()
const router = useRouter()
const auth = useAuthStore()

const projects = computed(() => auth.projects)

const { projectId, followTick } = useProjectPageContext()
const rows = ref<PlanView[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const envs = ref<{ id: number; name: string; envType: number }[]>([])
const tags = ref<TagItem[]>([])

const statusOptions = computed(() => {
  const s = (k: string) => td(`dict.plan.status.${k}`)
  return { 1: s('draft'), 2: s('published'), 3: s('executing'), 4: s('done'), 5: s('canceled') }
})
const phaseOptions = computed(() => ({ 0: PHASE_LABEL[0], 1: PHASE_LABEL[1], 2: PHASE_LABEL[2], 3: PHASE_LABEL[3] }))
const priorityOptions = computed(() => PRIORITY_LABEL)
const filters = reactive<{ status: number | null }>({ status: null })
// Column prop -> core SORT_COLUMNS whitelist key.
// Single source for the sortable-column decision and the request payload —
// columns outside the whitelist never emit sort params (no client-only fake sort).
const SORT_KEY_MAP: Record<string, string> = {
  name: 'name',
  targetVersion: 'target_version',
  status: 'status',
  caseCount: 'case_count',
  assigneeCount: 'assignee_count',
  createdAt: 'created_at',
}
// Ledger data columns for the shared column-config piece; the locked action
// column stays outside the loop. The composed period column has no server
// sort key, so it honestly renders unsortable via SORT_KEY_MAP above.
const PLAN_COLUMNS: ColumnDef[] = [
  { key: 'name', labelKey: 'plan.name', width: null },
  { key: 'targetVersion', labelKey: 'plan.targetVersion', width: 110 },
  { key: 'status', labelKey: 'plan.status', width: 100 },
  { key: 'caseCount', labelKey: 'plan.caseCount', width: 110 },
  { key: 'assigneeCount', labelKey: 'plan.assigneeCount', width: 110 },
  { key: 'period', labelKey: 'plan.period', width: 200 },
  { key: 'createdAt', labelKey: 'plan.createdAt', width: 160 },
]
/** Flexible columns fall back to min-width when no explicit width is set. */
const PLAN_FLEX_MIN: Record<string, number> = { name: 180 }
const colCfg = useColumnConfig('plan', PLAN_COLUMNS)
/** Popover rows (column state merged with labelKey for rendering). */
const settingsRows = computed(() =>
  colCfg.state.columns.map((c) => ({ ...c, labelKey: PLAN_COLUMNS.find((d) => d.key === c.key)!.labelKey })),
)
const query = reactive<{ sortBy: string; sortDir: string }>({ sortBy: '', sortDir: '' })

async function load(p = page.value): Promise<void> {
  if (!projectId.value) return
  page.value = p
  loading.value = true
  try {
    const res = await listPlans({ projectId: projectId.value, status: filters.status, page: page.value, size: size.value,
      sortBy: query.sortBy || undefined, sortDir: query.sortDir || undefined })
    rows.value = res.list || []
    total.value = res.total || 0
  } catch {
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/** : a landed plan write re-reads this list on its current page and
 * sort as-is — silent convergence, no toast/confirm/jump (iron rules 2-3). */
useObjectSync({
  watch: ['PLAN_WRITE'],
  onSync: () => void load(),
})

/** Header sort: forward the whitelisted key to the server (ORDER BY happens in SQL);
 * clearing a sort falls back to the backend default order; page resets to 1. */
function onSortChange({ prop, order }: { prop: string; order: string | null }): void {
  if (order === null) {
    query.sortBy = ''
    query.sortDir = ''
    page.value = 1
    void load()
    return
  }
  const key = SORT_KEY_MAP[prop]
  if (!key) {
    void load()
    return
  }
  query.sortBy = key
  query.sortDir = order === 'ascending' ? 'asc' : 'desc'
  page.value = 1
  void load()
}

async function loadEnvs(): Promise<void> {
  if (!projectId.value) return
  envs.value = (await getProjectEnvs(projectId.value)) as { id: number; name: string; envType: number }[]
}

async function loadTags(): Promise<void> {
  if (!projectId.value) return
  tags.value = (await listTags(projectId.value)) || []
}


function goDetail(id: number): void {
  router.push({ path: `/plans/${id}` })
}

function planRowActions(row: PlanView) {
  return [
    { key: "view", label: t("plan.detail") },
    { key: "publish", label: t("plan.publish"), disabled: row.status !== 1 },
    { key: "genbatch", label: t("plan.genBatches"), disabled: row.status !== 2 },
    { key: "delete", label: t("plan.delete"), type: "danger" as const, disabled: !(row.status === 1 || row.status === 5) },
  ]
}
function onPlanRowCmd(key: string, row: PlanView): void {
  if (key === "view") goDetail(row.id)
  else if (key === "publish") publish(row)
  else if (key === "genbatch") generateBatches(row)
  else if (key === "delete") remove(row)
}

function publish(row: PlanView): void {
  ElMessageBox.confirm(t('plan.publishConfirm', { name: row.name }), t('plan.publish'), { type: 'warning' })
    .then(async () => {
      await publishPlan(row.id)
      ElMessage.success(t('plan.publishOk'))
      load()
    })
    .catch(() => {})
}

function generateBatches(row: PlanView): void {
  ElMessageBox.confirm(t('plan.genBatchConfirm', { name: row.name }), t('plan.genBatches'), { type: 'warning' })
    .then(async () => {
      const res = await generatePlanBatches(row.id)
      ElMessage.success(res.message || t('plan.genBatchOk'))
      load()
    })
    .catch((e: unknown) => {
      ElMessage.error(bizErrorMsg(e, t('plan.genBatchFail')))
      load()
    })
}

function remove(row: PlanView): void {
  void guardedDelete({
    check: () => checkPlanDelete(row.id),
    doDelete: (force) => deletePlan(row.id, force),
    entityName: t('plan.entityName', { name: row.name }),
    forceHint: t('plan.deleteForceHint'),
  }).then((ok) => {
    if (ok) {
      ElMessage.success(t('plan.deleteOk'))
      load()
    }
  })
}

const wizardVisible = ref(false)
const wizardStep = ref(0)
const saving = ref(false)
const baseFormRef = ref<FormInstance>()
const period = ref<[string, string] | null>(null)

const form = reactive<PlanCreatePayload>({
  projectId: 0,
  name: '',
  targetVersion: null,
  ownerId: null,
  envIds: [],
  caseScope: { phases: [], priorities: [], keyword: null, tagIds: [] },
  entryCriteria: { requireSmokePass: true },
  exitCriteria: { minPassRate: 0.95, noOpenDefects: true },
  planStart: null,
  planEnd: null,
  lockMode: 1,
})

const baseRules: FormRules = {
  name: [{ required: true, message: t('plan.nameRequired'), trigger: 'blur' }],
}


function resetWizardForm(): void {
  Object.assign(form, {
    projectId: projectId.value,
    name: '',
    targetVersion: null,
    ownerId: auth.user?.userId ?? null,
    envIds: [],
    caseScope: { phases: [], priorities: [], keyword: null, tagIds: [] },
    entryCriteria: { requireSmokePass: true },
    exitCriteria: { minPassRate: 0.95, noOpenDefects: true },
    planStart: null,
    planEnd: null,
    lockMode: 1,
  })
  period.value = null
  wizardStep.value = 0
}


const wizardDraftGuard = useDraftGuard(form as unknown as Record<string, unknown>, resetWizardForm)


function onPeriodChange(val: [string, string] | null): void {
  form.planStart = val ? val[0] : null
  form.planEnd = val ? val[1] : null
}

async function openWizard(): Promise<void> {
  if (form.projectId !== projectId.value) wizardDraftGuard.discard()
  wizardVisible.value = true
  wizardDraftGuard.snapshot()
  
  await loadEnvs()
 
  await loadTags()
}

async function nextStep(): Promise<void> {
  if (wizardStep.value === 0) {
    const ok = await baseFormRef.value?.validate().catch(() => false)
    if (!ok) return
    
    await loadEnvs()
  }
  if (wizardStep.value === 1) {
    if (form.envIds.length === 0) {
      ElMessage.warning(t('plan.envRequired'))
      return
    }
    if (form.caseScope.phases.length === 0 && form.caseScope.priorities.length === 0
        && !form.caseScope.keyword && form.caseScope.tagIds.length === 0) {
      ElMessage.warning(t('plan.scopeRequired'))
      return
    }
  }
  wizardStep.value++
}

async function submitCreate(): Promise<void> {
  saving.value = true
  try {
    form.planStart = period.value ? period.value[0] : null
    form.planEnd = period.value ? period.value[1] : null
    const created = await createPlan(form)
    ElMessage.success(t('plan.createOk', { count: created.caseCount }))
    wizardVisible.value = false
    wizardDraftGuard.discard()
    load()
    router.push({ path: `/plans/${created.id}` })
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  if (projectId.value) {
    load()
  }
})

watch(followTick, () => {
  if (!projectId.value) return
  void Promise.all([loadEnvs(), loadTags(), load()])
})

useKeepAliveList(async () => {
  if (!projectId.value) return
  await Promise.all([loadEnvs(), loadTags(), load()])
})
</script>

<style scoped>
.page-root {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.wizard-steps {
  margin-bottom: 24px;
}
.wizard-form {
  margin-top: 12px;
}
.empty-box {
  padding: 40px 0;
}
</style>
