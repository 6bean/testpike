<template>
  <div class="page-root suite-list-page" data-test="suite-list-page">
    <div :class="embedded ? 'suite-list-embedded' : 'page-card'">
      <div class="page-header">
        <div class="header-actions">
          <el-tooltip
            :content="t('components.ownerEcho.pickInHeader')"
            placement="bottom"
            :disabled="!!projectId"
          >
            <span>
              <el-button type="primary" :icon="Plus" :disabled="!projectId" data-test="suite-create-btn" @click="openCreate">
                {{ t('suite.create') }}
              </el-button>
            </span>
          </el-tooltip>
        </div>
      </div>

      <!-- IA fix: screening lives in the shared filter-bar row; the
 project rides the global header context (page selector removed). -->
      <div class="filter-bar" data-test="suite-list-filter-bar">
          <el-select
            v-model="filters.status"
            :placeholder="t('suite.statusAll')"
            clearable
            style="width: 120px"
            data-test="suite-filter-status"
            @change="load(1)"
          >
            <el-option v-for="(label, s) in SUITE_STATUS_LABEL" :key="s" :label="label" :value="Number(s)" />
          </el-select>
          <el-input
            v-model="filters.keyword"
            :placeholder="t('suite.keywordPlaceholder')"
            clearable
            style="width: 200px"
            data-test="suite-filter-keyword"
            @keyup.enter="load(1)"
            @clear="load(1)"
          />
        </div>

      <div v-if="!loading && projects.length === 0" class="empty-box" data-test="suite-empty-no-project">
        <el-empty :description="t('suite.noProject')">
          <el-button type="primary" plain data-test="suite-empty-no-project-go" @click="router.push('/projects')">{{ t('case.emptyGoProject') }}</el-button>
        </el-empty>
      </div>

      <TableSkeleton v-else-if="loading" :cols="9" data-test="suitelistview-skeleton" />
      <el-table border v-else :data="rows" data-test="suite-table" class="data-table" row-key="id" @sort-change="onSortChange">
        <el-table-column prop="name" :label="t('suite.name')" min-width="180" show-overflow-tooltip sortable="custom">
          <template #default="{ row }">
            <el-link type="primary" data-test="suite-name-link" @click="openDetail(row.id)">
              {{ row.name }}
            </el-link>
          </template>
        </el-table-column>
        <el-table-column prop="caseCount" :label="t('suite.caseCount')" width="110" align="center" sortable="custom" />
        <el-table-column :label="t('suite.envs')" min-width="140">
          <template #default="{ row }">{{ envNames(row.envIds) || '-' }}</template>
        </el-table-column>
        <el-table-column :label="t('suite.assignees')" min-width="120">
          <template #default="{ row }">{{ assigneeNames(row.defaultAssigneeIds) || '-' }}</template>
        </el-table-column>
        <el-table-column prop="cronExpr" :label="t('suite.cronExpr')" width="110">
          <template #default="{ row }">{{ row.cronExpr || '-' }}</template>
        </el-table-column>
        <el-table-column prop="lastRunAt" :label="t('suite.lastRunAt')" width="150" sortable="custom">
          <template #default="{ row }"><TimeCell :iso="row.lastRunAt" /></template>
        </el-table-column>
        <el-table-column prop="status" :label="t('suite.status')" width="90" sortable="custom">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small" data-test="suite-status-tag">
              {{ SUITE_STATUS_LABEL[row.status] || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('suite.operation')" width="230" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link data-test="suite-run-btn" @click="confirmRun(row)">
              {{ t('suite.run') }}
            </el-button>
            <el-button size="small" data-test="suite-edit-btn" @click="openEdit(row)">
              {{ t('suite.edit') }}
            </el-button>
            <el-button size="small" type="danger" data-test="suite-delete-btn" @click="confirmDelete(row)">
              {{ t('suite.delete') }}
            </el-button>
          </template>
        </el-table-column>
    
        <template #empty>
          <EmptyState
            data-test="suite-empty-create"
            :filtered="hasActiveFilter"
            :title="hasActiveFilter ? t('common.emptyFilteredResult') : t('common.emptyNoData')"
            :hint="hasActiveFilter ? t('common.emptyFilteredHint') : t('suite.emptyCreateHint')"
            :action-text="t('suite.create')"
            @action="openCreate"
            @clear-filter="clearSuiteFilters"
          />
        </template>
      </el-table>

      <div class="pagination-bar">
        <el-pagination v-if="total > 0"
          v-model:current-page="page"
          :page-size="size"
          :total="total"
          layout="total, prev, pager, next"
          data-test="suite-pagination"
          @current-change="load"
            />
      </div>

   
      <el-dialog draggable
        v-model="dialogVisible"
        :title="editing ? t('suite.editTitle') : t('suite.createTitle')"
        width="640px"
        :before-close="suiteDraftGuard.beforeClose"
        data-test="suite-dialog"
      >
        <!-- ownership echo (iron rule 1): read-only, single-sourced from the header context -->
        <ProjectOwnerEcho />
        <el-form ref="formRef" :model="form" :rules="rules" label-width="110px" scroll-to-error>
          <el-form-item :label="t('suite.name')" prop="name">
            <el-input v-model="form.name" maxlength="100" data-test="suite-form-name" />
          </el-form-item>
          <el-form-item :label="t('suite.cases')" prop="caseIds">
            <CaseSelect
              v-model="form.caseIds"
              :project-id="projectId"
              multiple
              data-test="suite-form-cases"
              :placeholder="t('suite.casesPlaceholder')"
            />
          </el-form-item>
          <el-form-item :label="t('suite.envs')" prop="envIds">
            <el-select v-model="form.envIds" multiple filterable style="width: 100%" data-test="suite-form-envs">
              <el-option v-for="e in envs" :key="e.id" :label="`${e.name}（${envTypeLabel(e.envType)}）`" :value="e.id" />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('suite.assignees')">
            <el-select
              v-model="form.defaultAssigneeIds"
              multiple
              clearable
              filterable
              style="width: 100%"
              data-test="suite-form-assignees"
              :placeholder="t('suite.assigneesPlaceholder')"
            >
              <el-option
                v-for="m in executableMembers"
                :key="m.userId"
                :label="`${m.nickName || m.username}（${m.username}）`"
                :value="m.userId"
              />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('suite.cronExpr')">
            <el-input v-model="form.cronExpr" data-test="suite-form-cron" :placeholder="t('suite.cronPlaceholder')" />
          </el-form-item>
          <!-- declarative mock binding: shared picker, unbindable spaces
 taught up front (mirrors the core MockSpaceBindingGate). -->
          <el-form-item :label="t('suite.mockSpaceLabel')" data-test="suite-form-mock-space-item">
            <MockSpacePicker v-model="form.mockSpaceId" :project-id="projectId ?? 0" />
            <span class="form-tip">{{ t('suite.mockSpaceTip') }}</span>
          </el-form-item>
          <!-- for="" group pattern: label[for] cannot target the radio-group DIV
               (Blink flags it) — see the same note in CaseDetailView. -->
          <el-form-item :label="t('suite.status')" for="">
            <!-- id="" prevents the form label for= from targeting the non-labelable radiogroup div -->
            <el-radio-group id="" v-model="form.status" data-test="suite-form-status">
              <el-radio :value="1">{{ t('suite.enabled') }}</el-radio>
              <el-radio :value="2">{{ t('suite.disabled') }}</el-radio>
            </el-radio-group>
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="dialogVisible = false">{{ t('suite.cancel') }}</el-button>
          <el-button type="primary" :loading="saving" data-test="suite-form-submit" @click="submit">
            {{ t('suite.save') }}
          </el-button>
        </template>
      </el-dialog>

      
      <el-drawer draggable v-model="detailVisible" size="480px" data-test="suite-detail-drawer" :title="t('suite.detailTitle')">
        <template v-if="current">
          <el-descriptions :column="1" border>
            <el-descriptions-item :label="t('suite.name')">{{ current.name }}</el-descriptions-item>
            <el-descriptions-item :label="t('suite.caseCount')">{{ current.caseCount }}</el-descriptions-item>
            <el-descriptions-item :label="t('suite.envs')">{{ envNames(current.envIds) || '-' }}</el-descriptions-item>
            <el-descriptions-item :label="t('suite.assignees')">
              {{ assigneeNames(current.defaultAssigneeIds) || '-' }}
            </el-descriptions-item>
            <el-descriptions-item :label="t('suite.cronExpr')">{{ current.cronExpr || '-' }}</el-descriptions-item>
            <el-descriptions-item :label="t('suite.lastRunAt')">{{ current.lastRunAt || '-' }}</el-descriptions-item>
            <el-descriptions-item :label="t('suite.status')">{{ current.statusLabel }}</el-descriptions-item>
            <el-descriptions-item :label="t('suite.createdBy')">{{ current.createdByName || '-' }}</el-descriptions-item>
            <el-descriptions-item :label="t('suite.createdAt')">{{ current.createdAt }}</el-descriptions-item>
          </el-descriptions>
        </template>
      </el-drawer>
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
import { Plus } from '@element-plus/icons-vue'
import ProjectOwnerEcho from '@/components/ProjectOwnerEcho.vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { envTypeLabel, getProjectEnvs, getProjectMembers, type ProjectMember } from '@/api/project'
import { bizErrorMsg } from '@/utils/bizError'
import CaseSelect from '@/components/CaseSelect.vue'
import MockSpacePicker from '@/components/MockSpacePicker.vue'
import {
  SUITE_STATUS_LABEL,
  createSuite,
  deleteSuite,
  listSuites,
  runSuite,
  updateSuite,
  type SuiteView,
} from '@/api/suites'

defineOptions({ name: 'RegressionSuite' })

/** AN-5 embedded flag: renders inside the /cases tab host when true
 * (outer card dropped — the host frames the tab content). Default false
 * keeps standalone semantics for the redirect era tooling. */
const props = withDefaults(defineProps<{ embedded?: boolean }>(), { embedded: false })
const embedded = computed(() => props.embedded)

const { t } = useI18n()
const router = useRouter()
const auth = useAuthStore()

const projects = computed(() => auth.projects)

const { projectId, followTick } = useProjectPageContext()
const rows = ref<SuiteView[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const saving = ref(false)

const envs = ref<{ id: number; name: string; envType: number }[]>([])
const members = ref<ProjectMember[]>([])

const filters = reactive<{ status: number | null; keyword: string }>({ status: null, keyword: '' })

// Column prop -> core SORT_COLUMNS whitelist key.
// Single source for the sortable-column decision and the request payload —
// columns outside the whitelist never emit sort params (no client-only fake sort).
const SORT_KEY_MAP: Record<string, string> = {
  name: 'name',
  caseCount: 'case_count',
  lastRunAt: 'last_run_at',
  status: 'status',
}
const query = reactive<{ sortBy: string; sortDir: string }>({ sortBy: '', sortDir: '' })

const dialogVisible = ref(false)
const editing = ref(false)
const editingId = ref(0)
const detailVisible = ref(false)
const current = ref<SuiteView | null>(null)

const form = reactive<{
  name: string
  caseIds: number[]
  envIds: number[]
  defaultAssigneeIds: number[]
  cronExpr: string
  status: number
  /** Declared mock binding : picker value, null = no mock */
  mockSpaceId: number | null
}>({ name: '', caseIds: [], envIds: [], defaultAssigneeIds: [], cronExpr: '', status: 1, mockSpaceId: null })


const suiteDraftGuard = useDraftGuard(form as unknown as Record<string, unknown>, () => {
  editing.value = false
  editingId.value = 0
  Object.assign(form, { name: '', caseIds: [], envIds: [], defaultAssigneeIds: [], cronExpr: '', status: 1, mockSpaceId: null })
})

const formRef = ref<FormInstance>()
const rules: FormRules = {
  name: [{ required: true, message: t('suite.nameRequired'), trigger: 'blur' }],
  caseIds: [{ required: true, type: 'array', min: 1, message: t('suite.casesRequired'), trigger: 'change' }],
  envIds: [{ required: true, type: 'array', min: 1, message: t('suite.envsRequired'), trigger: 'change' }],
}


const executableMembers = computed(() =>
  members.value.filter((m) => (m.roles || []).some((r) => r === 'tester' || r === 'test_lead')),
)

function envNames(ids: number[]): string {
  return envs.value.filter((e) => ids.includes(e.id)).map((e) => e.name).join('、')
}

function assigneeNames(ids: number[]): string {
  return members.value
    .filter((m) => ids.includes(m.userId))
    .map((m) => m.nickName || m.username)
    .join('、')
}

async function loadProjectOptions(): Promise<void> {
  if (!projectId.value) return
  const [envRes, memberRes] = await Promise.all([
    getProjectEnvs(projectId.value),
    getProjectMembers(projectId.value),
  ])
  envs.value = envRes as { id: number; name: string; envType: number }[]
  members.value = memberRes as ProjectMember[]
}


async function load(p = page.value): Promise<void> {
  if (!projectId.value) return
  page.value = p
  loading.value = true
  try {
    const res = await listSuites({
      projectId: projectId.value,
      keyword: filters.keyword || undefined,
      status: filters.status || undefined,
      page: page.value,
      size: size.value,
      sortBy: query.sortBy || undefined,
      sortDir: query.sortDir || undefined,
    })
    rows.value = res.list || []
    total.value = res.total || 0
  } catch {
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

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

function openCreate(): void {
  if (editing.value) suiteDraftGuard.discard()
  dialogVisible.value = true
  suiteDraftGuard.snapshot()
}

function openEdit(row: SuiteView): void {
  editing.value = true
  editingId.value = row.id
  Object.assign(form, {
    name: row.name,
    caseIds: [...row.caseIds],
    envIds: [...row.envIds],
    defaultAssigneeIds: [...row.defaultAssigneeIds],
    cronExpr: row.cronExpr || '',
    status: row.status,
    mockSpaceId: row.mockSpaceId ?? null,
  })
  dialogVisible.value = true
  suiteDraftGuard.snapshot()
}

async function submit(): Promise<void> {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  if (!projectId.value) return
  saving.value = true
  try {
    if (editing.value) {
      await updateSuite(editingId.value, {
        name: form.name.trim(),
        caseIds: form.caseIds,
        envIds: form.envIds,
        defaultAssigneeIds: form.defaultAssigneeIds,
        cronExpr: form.cronExpr || null,
        status: form.status,
        mockSpaceId: form.mockSpaceId ?? 0,
      })
      ElMessage.success(t('suite.updated'))
    } else {
      await createSuite({
        projectId: projectId.value,
        name: form.name.trim(),
        caseIds: form.caseIds,
        envIds: form.envIds,
        defaultAssigneeIds: form.defaultAssigneeIds,
        cronExpr: form.cronExpr || null,
        status: form.status,
        mockSpaceId: form.mockSpaceId ?? 0,
      })
      ElMessage.success(t('suite.created'))
    }
    dialogVisible.value = false
    suiteDraftGuard.discard()
    await load(1)
  } finally {
    saving.value = false
  }
}

async function confirmRun(row: SuiteView): Promise<void> {
  try {
    await ElMessageBox.confirm(t('suite.runConfirm', { name: row.name }), t('suite.runTitle'), {
      type: 'warning',
      confirmButtonText: t('suite.run'),
      cancelButtonText: t('suite.cancel'),
    })
  } catch {
    return
  }
  try {
    const res = await runSuite(row.id)
    const skippedHint = res.skippedArchived > 0 ? t('suite.runSkippedArchived', { n: res.skippedArchived }) : ''
    ElMessage.success(
      t('suite.runSuccess', { plan: res.planName, batch: res.batchCount, task: res.taskCount }) + skippedHint,
    )
    await load(page.value)
  } catch (e: any) {
    ElMessage.error(bizErrorMsg(e, t('suite.runFailed')))
  }
}

async function confirmDelete(row: SuiteView): Promise<void> {
  try {
    await ElMessageBox.confirm(t('suite.deleteConfirm', { name: row.name }), t('suite.deleteTitle'), {
      type: 'warning',
      confirmButtonText: t('suite.delete'),
      cancelButtonText: t('suite.cancel'),
    })
  } catch {
    return
  }
  await deleteSuite(row.id)
  ElMessage.success(t('suite.deleted'))
  await load(page.value)
}

async function openDetail(id: number): Promise<void> {
  current.value = rows.value.find((r) => r.id === id) || null
  detailVisible.value = true
}

onMounted(async () => {
  if (projectId.value) {
    await loadProjectOptions()
    await load(1)
  }
})

watch(followTick, () => {
  if (!projectId.value) return
  void Promise.all([loadProjectOptions(), load(1)])
})

useKeepAliveList(async () => {
  if (!projectId.value) return
  await Promise.all([loadProjectOptions(), load()])
})

const hasActiveFilter = computed(() => filters.status !== null || filters.keyword.trim() !== '')
function clearSuiteFilters(): void { filters.status = null; filters.keyword = ''; load(1) }
</script>
