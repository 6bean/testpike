<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  deleteExploreSession,
  getExploreSessions,
  postExploreSession,
  type ExploreCreateInput,
  type ExploreEstimate,
  type ExploreSession,
} from '@/api/explore'
import { envTypeLabel, getProjectEnvs, type ProjectEnv } from '@/api/project'
import { listAppPackages, listCredentials, type AppautoApp, type AppautoCredential } from '@/api/appauto'
import { listSessionStates, type LoginStateRow } from '@/api/sessionState'
import { bizErrorMsg } from '@/utils/bizError'
import { useAuthStore } from '@/stores/auth'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import { useObjectSync } from '@/composables/useObjectSync'
import ProjectOwnerEcho from '@/components/ProjectOwnerEcho.vue'
import { exploreCanCancel, exploreCancelLabel, exploreStatusOf, fmtExploreCost } from './exploreMeta'
import EmptyState from '@/components/EmptyState.vue'
import ColumnSettings from '@/components/ColumnSettings.vue'
import DataTablePage from '@/components/DataTablePage.vue'
import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'



const { t } = useI18n()
const router = useRouter()
const auth = useAuthStore()

const { projectId, followTick } = useProjectPageContext()
const page = ref(1)
const size = ref(20)
const rows = ref<ExploreSession[]>([])
const total = ref(0)
const loading = ref(false)

const canCreate = computed(() => auth.hasPerm('perm:script:explore'))


const canCancel = (row: ExploreSession) => exploreCanCancel(row, auth)
const statusOf = (row: ExploreSession) => exploreStatusOf(row.status)
const fmtCost = fmtExploreCost

/** Column defs of the exploration-session table. The row-action column stays
 *  outside the user-configurable set (fixed right, always rendered). */
const EXPLORE_COLUMNS: ColumnDef[] = [
  { key: 'id', labelKey: 'explore.colId', width: 70 },
  { key: 'goalText', labelKey: 'explore.colGoal', width: null },
  { key: 'startUrl', labelKey: 'explore.colStartUrl', width: null },
  { key: 'status', labelKey: 'explore.colStatus', width: 110 },
  { key: 'stepsUsed', labelKey: 'explore.colSteps', width: 120 },
  { key: 'costCents', labelKey: 'explore.colCost', width: 90 },
]
/** Flexible columns fall back to min-width when no explicit width is set. */
const EXPLORE_FLEX_MIN: Record<string, number> = { goalText: 220, startUrl: 200 }
/** Header-sortable columns: the exact server-side SORT_KEY_MAP whitelist. */
const EXPLORE_SORTABLE = ['id', 'goalText', 'startUrl', 'status', 'stepsUsed', 'costCents']
const colCfg = useColumnConfig('explore', EXPLORE_COLUMNS)
/** Popover rows (column state merged with labelKey for rendering). */
const settingsRows = computed(() =>
  colCfg.state.columns.map((c) => ({ ...c, labelKey: EXPLORE_COLUMNS.find((d) => d.key === c.key)!.labelKey })),
)

async function load() {
  if (!projectId.value) {
    rows.value = []
    total.value = 0
    return
  }
  loading.value = true
  try {
    const data = await getExploreSessions(projectId.value, page.value, size.value, {
      sortBy: query.sortBy || undefined,
      sortDir: query.sortDir || undefined,
    })
    rows.value = data.list
    total.value = data.total
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('explore.loadListFailed')))
  } finally {
    loading.value = false
  }
}

// : a landed EXPLORE_CREATE card files a new exploration
// session into the same project ledger this list renders — re-read the
// current page and sort as-is so the new row surfaces without any user
// action (BG iron rules 2-3). The projectId anchor keeps landings of other
// projects off this page.
useObjectSync({
  watch: ['EXPLORE_CREATE'],
  anchor: (event) => event.projectId !== null && event.projectId === projectId.value,
  onSync: () => void load(),
})

// Column prop -> core SORT_COLUMNS whitelist key (single source for the
// sortable decision and the request payload; . Sorting
// happens server-side over the whole project window, not the loaded page.
const SORT_KEY_MAP: Record<string, string> = {
  id: 'id',
  goalText: 'goal_text',
  startUrl: 'start_url',
  status: 'status',
  stepsUsed: 'steps_used',
  costCents: 'cost_cents',
}
const query = reactive<{ sortBy: string; sortDir: string }>({ sortBy: '', sortDir: '' })

/** Header sort: forward the whitelisted key to core; clearing a sort falls
 * back to the historical id DESC order; page resets to 1. */
function onSortChange({ prop, order }: { prop: string; order: string | null }): void {
  page.value = 1
  if (order === null) {
    query.sortBy = ''
    query.sortDir = ''
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
  void load()
}

watch(projectId, () => {
  page.value = 1
  load()
})
watch([page, size], load)



onMounted(() => {
  if (projectId.value) load()
})

watch(followTick, () => {
  if (projectId.value) load()
})

const createOpen = ref(false)
const creating = ref(false)
const estimating = ref(false)
const estimate = ref<ExploreEstimate | null>(null)
const form = reactive({
  goalText: '',
  startUrl: '',
  kind: 'web' as 'web' | 'app',
  appPackage: '',
  envId: null as number | null,
  credRef: null as string | null,
  loginStateName: null as string | null,
})


const envs = ref<ProjectEnv[]>([])
const credentials = ref<AppautoCredential[]>([])
// Web-shape option source: login states registered for the current project
// (handle = state_name; resolved server-side at claim time, never here).
const loginStates = ref<LoginStateRow[]>([])
const appPackages = ref<AppautoApp[]>([])
const explorables = computed(() => envs.value.filter((e) => e.envType === 1 || e.envType === 2))

async function loadCreateMeta() {
  if (!projectId.value) {
    envs.value = []
    credentials.value = []
    loginStates.value = []
    appPackages.value = []
    return
  }
  try {
    const [envList, credList, stateList, pkgList] = await Promise.all([
      getProjectEnvs(projectId.value),
      listCredentials(projectId.value, true),
      listSessionStates(projectId.value, true),
      listAppPackages(projectId.value),
    ])
    envs.value = envList
    credentials.value = credList
    loginStates.value = stateList
    appPackages.value = pkgList
  } catch (e) {
    ElMessage.warning(bizErrorMsg(e, t('explore.metaLoadFailed')))
  }
}

function openCreate() {
  form.goalText = ''
  form.startUrl = ''
  form.kind = 'web'
  form.appPackage = ''
  form.envId = null
  form.credRef = null
  form.loginStateName = null
  estimate.value = null
  createOpen.value = true
  loadCreateMeta()
}


function validateForm(): boolean {
  if (!form.goalText.trim()) {
    ElMessage.warning(t('explore.fillGoal'))
    return false
  }
  if (form.kind === 'web' && !form.startUrl.trim()) {
    ElMessage.warning(t('explore.fillUrl'))
    return false
  }
  if (form.kind === 'app') {
    if (!form.appPackage.trim()) {
      ElMessage.warning(t('explore.fillAppPackage'))
      return false
    }
    if (form.envId == null) {
      ElMessage.warning(t('explore.pickEnv'))
      return false
    }
  }
  return true
}


// Login-state handle is declared on the web shape only; the two identity
// namespaces (vault credential vs login-state ledger) never cross-feed.
function buildPayload(confirm: boolean): ExploreCreateInput {
  const mobile = form.kind === 'app'
  return {
    projectId: projectId.value as number,
    goalText: form.goalText.trim(),
    startUrl: mobile ? `app://${form.appPackage.trim()}` : form.startUrl.trim(),
    confirm,
    envId: mobile ? form.envId : null,
    credRef: mobile && form.credRef ? form.credRef : null,
    loginStateName: !mobile && form.loginStateName ? form.loginStateName : null,
  }
}

/** Option label = state name plus its replay effect sentence (SE5 rule:
 *  every option states what choosing it does; kind sentences reuse the
 *  login-state page locale keys, no second vocabulary). */
function loginStateOptionLabel(s: LoginStateRow): string {
  const kindKey = s.kind === 'token'
    ? 'sessionState.kindOptionToken'
    : s.kind === 'cookie_set'
      ? 'sessionState.kindOptionCookieSet'
      : 'sessionState.kindOptionStorageState'
  return `${s.stateName}（${t(kindKey)}）`
}

async function previewEstimate() {
  if (!projectId.value || !validateForm()) {
    return
  }
  estimating.value = true
  try {
    const r = await postExploreSession(buildPayload(false))
    estimate.value = r.estimate
    if (!r.estimate) ElMessage.warning(t('explore.estimateEmpty'))
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('explore.estimateFailed')))
  } finally {
    estimating.value = false
  }
}

async function submitCreate() {
  if (!projectId.value || !estimate.value) {
    ElMessage.warning(t('explore.estimateFirst'))
    return
  }
  creating.value = true
  try {
    const r = await postExploreSession(buildPayload(true))
    if (r.session) {
      ElMessage.success(t('explore.created'))
      createOpen.value = false
      router.push(`/explore/${r.session.id}`)
    } else {
      ElMessage.error(t('explore.createNoSession'))
    }
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('explore.createFailed')))
  } finally {
    creating.value = false
  }
}

async function onCancel(row: ExploreSession) {
  const label = exploreCancelLabel(row.status, t)
  try {
    await ElMessageBox.confirm(t('explore.cancelConfirm', { id: row.id, label }), t('explore.cancelTitle'), {
      type: 'warning',
      confirmButtonText: t('explore.confirm'),
      cancelButtonText: t('explore.cancel'),
    })
  } catch {
    return
  }
  try {
    const r = await deleteExploreSession(row.id)
    ElMessage.success(r.action === 'terminated' ? t('explore.terminated') : t('explore.deleted'))
    load()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('explore.cancelFailed')))
  }
}
</script>

<template>
  <div class="page-root" data-test="explore-list-page">
    <div class="page-card">
      <div class="toolbar">
        <div class="toolbar-right">
          <el-tooltip
            :content="t('components.ownerEcho.pickInHeader')"
            placement="bottom"
            :disabled="!!projectId"
          >
            <span>
              <el-button v-if="canCreate" type="primary" :disabled="!projectId" data-test="explore-create" @click="openCreate">{{ t('explore.create') }}</el-button>
            </span>
          </el-tooltip>
          <el-button data-test="explore-refresh" @click="load">{{ t('explore.refresh') }}</el-button>
          <!-- Column show/hide, order, pinning, density and reset ride the shared
               useColumnConfig piece; view-state actions sit beside refresh in the
               page action band (no filter conditions on this page, AuditLogView
               precedent). -->
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

      <TableSkeleton v-if="loading" :cols="7" data-test="explorelistview-skeleton" />
      <DataTablePage
        v-else
        :data="rows"
        data-test="explore-table"
        row-key="id"
        :size="colCfg.state.density"
        :column-config="colCfg"
        @sort-change="onSortChange"
      >
        <el-table-column
          v-for="col in colCfg.visibleColumns.value"
          :key="col.key"
          :prop="col.key"
          :fixed="col.fixed || false"
          :label="t(col.labelKey)"
          :width="col.width ?? undefined"
          :min-width="col.width ? undefined : EXPLORE_FLEX_MIN[col.key]"
          :sortable="EXPLORE_SORTABLE.includes(col.key) ? 'custom' : false"
          :show-overflow-tooltip="col.key === 'goalText' || col.key === 'startUrl'"
          :data-test="`explore-col-${col.key}`"
        >
          <template #default="{ row }">
            <el-tag v-if="col.key === 'status'" :type="statusOf(row).type" size="small" :data-test="`explore-status-${row.id}`">{{ t(statusOf(row).key) }}</el-tag>
            <span v-else-if="col.key === 'stepsUsed'">{{ row.stepsUsed }} / {{ row.pagesVisited }}</span>
            <span v-else-if="col.key === 'costCents'">{{ fmtCost(row.costCents) }}</span>
            <span v-else>{{ row[col.key] }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('explore.colActions')" width="180" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :data-test="`explore-open-${row.id}`" @click="router.push(`/explore/${row.id}`)">{{ t('explore.detail') }}</el-button>
            <el-button v-if="canCancel(row)" link type="danger" :data-test="`explore-cancel-${row.id}`" @click="onCancel(row)">
              {{ row.status === 1 ? t('explore.terminate') : t('explore.remove') }}
            </el-button>
          </template>
        </el-table-column>

        <template #empty>
          <EmptyState :title="t('explore.empty')" :action-text="t('explore.create')" @action="openCreate" />
        </template>
      </DataTablePage>

      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="total"
        :page-sizes="[10, 20, 50]"
        :layout="t('explore.pagerLayout')"
        class="pagination-bar"
        data-test="explore-pager"
      />

   
      <el-dialog v-model="createOpen" :title="t('explore.createTitle')" width="640px" data-test="explore-create-dialog" draggable>
        <!-- ownership echo (iron rule 1): read-only, single-sourced from the header context -->
        <ProjectOwnerEcho />
        <el-form label-width="90px">
          <el-form-item :label="t('explore.colKind')">
            
            <!-- id="" prevents the form label for= from targeting the non-labelable radiogroup div -->
            <el-radio-group id="" v-model="form.kind" data-test="explore-kind">
              <el-radio-button value="web">{{ t('explore.kindWeb') }}</el-radio-button>
              <el-radio-button value="app">{{ t('explore.kindApp') }}</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item :label="t('explore.colGoal')" required>
            <el-input v-model="form.goalText" type="textarea" :rows="3" maxlength="500" show-word-limit
              :placeholder="form.kind === 'app' ? t('explore.goalPlaceholderApp') : t('explore.goalPlaceholder')"
              data-test="explore-goal" />
          </el-form-item>

          <template v-if="form.kind === 'web'">
            <el-form-item :label="t('explore.colStartUrl')" required>
              <el-input v-model="form.startUrl" :placeholder="t('explore.urlPlaceholder')" data-test="explore-start-url" />
            </el-form-item>
            <el-form-item :label="t('explore.colLoginState')">
              <el-select v-model="form.loginStateName" clearable filterable
                :placeholder="t('explore.loginStatePlaceholder')" data-test="explore-login-state" class="w-full">
                <el-option v-for="s in loginStates" :key="s.id" :label="loginStateOptionLabel(s)" :value="s.stateName" />
              </el-select>
              <div class="form-hint">{{ t('explore.loginStateHint') }}</div>
            </el-form-item>
          </template>

          <template v-else>
            <el-form-item :label="t('explore.colAppPackage')" required>
              <el-select v-model="form.appPackage" filterable allow-create default-first-option
                :placeholder="t('explore.appPackagePlaceholder')" data-test="explore-app-package" class="w-full">
                <el-option v-for="p in appPackages" :key="p.id" :label="`${p.packageName}（${p.name}）`" :value="p.packageName" />
              </el-select>
              <div class="form-hint">{{ t('explore.appPackageHint') }}</div>
            </el-form-item>
            <el-form-item :label="t('explore.colEnv')" required>
              <el-select v-model="form.envId" :placeholder="t('explore.envPlaceholder')" data-test="explore-env" filterable class="w-full">
                <el-option v-for="e in explorables" :key="e.id" :label="`${e.name}（${envTypeLabel(e.envType)}）`" :value="e.id" />
              </el-select>
              <div v-if="!explorables.length" class="form-hint warn">{{ t('explore.envEmpty') }}</div>
              <div v-else class="form-hint">{{ t('explore.envHint') }}</div>
            </el-form-item>
            <el-form-item :label="t('explore.colCred')">
              <el-select v-model="form.credRef" clearable :placeholder="t('explore.credPlaceholder')"
                data-test="explore-cred" filterable class="w-full">
                <el-option v-for="c in credentials" :key="c.id" :label="`${c.name}（${c.credKey}）`" :value="c.credKey" />
              </el-select>
              <div class="form-hint">{{ t('explore.credHint') }}</div>
            </el-form-item>
          </template>
        </el-form>
        <el-alert v-if="estimate" type="info" :closable="false" data-test="explore-estimate">
          <template #title>
            {{ t('explore.estimateHint', {
              tokens: estimate.estimatedTokens,
              cost: fmtCost(estimate.estimatedCostCents),
              maxSteps: estimate.maxSteps,
            }) }}
          </template>
        </el-alert>
        <template #footer>
          <el-button :loading="estimating" data-test="explore-estimate-btn" @click="previewEstimate">{{ t('explore.estimateBtn') }}</el-button>
          <el-button type="primary" :loading="creating" :disabled="!estimate" data-test="explore-confirm-btn" @click="submitCreate">
            {{ t('explore.confirmStart') }}
          </el-button>
        </template>
      </el-dialog>
    </div>
  </div>
</template>

<style scoped>
.page-root {
  padding: 16px;
}
.page-title {
  font-size: 16px;
  font-weight: 600;
}
.w-full {
  width: 100%;
}
.form-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}
.form-hint.warn {
  color: var(--el-color-warning);
}
</style>
