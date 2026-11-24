<template>
  <div class="page-root datafactory-list-page" data-test="datafactory-list-page">
    <div class="page-card">
      <!-- IA fix: screening lives in the shared filter-bar row; the
 project rides the global header context (page selector removed).
 FB-3 same-band merge (10-22 ruling): no row-level batch bar and 2
 condition controls (<= threshold), so the page-level variant action
 (create) rides the .filter-bar-actions trailing slot ahead of the
 view-state action (column settings); the emptied page-header row is
 removed as a whole shell (R23 - the page name rides the global top
 bar). The create entry keeps its ownership gate unchanged. -->
      <div class="filter-bar" data-test="datafactory-list-filter-bar">
          <el-select
            v-model="filters.type"
            :placeholder="t('dataFactory.typeAll')"
            clearable
            style="width: 130px"
            data-test="datafactory-filter-type"
            @change="load(1)"
          >
            <el-option v-for="(label, ty) in FACTORY_TYPE_LABEL" :key="ty" :label="label" :value="Number(ty)" />
          </el-select>
          <el-input
            v-model="filters.keyword"
            :placeholder="t('dataFactory.keywordPlaceholder')"
            clearable
            style="width: 200px"
            data-test="datafactory-filter-keyword"
            @keyup.enter="load(1)"
            @clear="load(1)"
          />
          <!-- (R21 bands): column settings is a view-state action —
 moved out of the header band into the trailing slot. FB-3 merge
 : the create entry rides the same slot ahead of it. -->
          <div class="filter-bar-actions">
            <el-tooltip
              :content="t('components.ownerEcho.pickInHeader')"
              placement="bottom"
              :disabled="!!projectId"
            >
              <span>
                <el-button type="primary" :icon="Plus" :disabled="!projectId" data-test="datafactory-create-btn" @click="openCreate">
                  {{ t('dataFactory.create') }}
                </el-button>
              </span>
            </el-tooltip>
            <ColumnSettings
              :columns="currentPane.settingsRows.value"
              :density="currentPane.state.density"
              :disabled="currentPane.settingsDisabled.value"
              :density-disabled="currentPane.densityDisabled.value"
              :disabled-tooltip="currentPane.disabledReason.value ? t(currentPane.disabledReason.value) : ''"
              @toggle="currentPane.toggle"
              @move="currentPane.move"
              @density="currentPane.setDensity"
              @reset="currentPane.reset"
              @pin="currentPane.toggleFixed"
            />
          </div>
        </div>

      <el-tabs v-model="activeTab" class="datafactory-tabs" data-test="datafactory-tabs">
        <el-tab-pane :label="t('dataFactory.factoryTab')" name="factory">
          <div v-if="!loading && projects.length === 0" class="empty-box" data-test="datafactory-empty-no-project">
            <EmptyState :title="t('dataFactory.noProject')" :action-text="t('report.goProjects')" @action="router.push('/projects')" />
          </div>

          <TableSkeleton v-else-if="loading" :cols="8" data-test="datafactorylistview-skeleton" />
          <DataTablePage v-else :data="rows" data-test="datafactory-table" class="data-table" row-key="id" :size="factoryPane.state.density" :column-config="factoryPane.cfg" @sort-change="onSortChange">
          <template #empty>
            <EmptyState
              :filtered="hasActiveFilter"
              :title="hasActiveFilter ? t('common.emptyFilteredResult') : t('dataFactory.emptyTitle')"
              :hint="hasActiveFilter ? t('common.emptyFilteredHint') : t('dataFactory.emptyCreateHint')"
              :action-text="t('dataFactory.create')"
              @action="openCreate"
              @clear-filter="clearFactoryFilters"
            />
          </template>
        <!-- configurable data columns (show/hide, order, width,
 density persist through the shared useColumnConfig piece;
 R25 mode 1: pane-bound scoped view - see settingsHost above). -->
        <el-table-column
          v-for="col in factoryPane.visibleColumns.value"
          :key="col.key"
          :prop="col.key"
          :fixed="col.fixed || false"
          :label="t(col.labelKey)"
          :width="col.width ?? undefined"
          :min-width="col.width ? undefined : DF_FLEX_MIN[col.key]"
          :show-overflow-tooltip="col.key === 'name' || col.key === 'envName'"
          :align="col.key === 'type' || col.key === 'status' ? 'center' : undefined"
          :sortable="isColSortable(col.key) ? 'custom' : false"
          :data-test="`df-col-${col.key}`"
        >
          <template #default="{ row }">
            <el-link v-if="col.key === 'name'" type="primary" data-test="datafactory-name-link" @click="openDetail(row)">{{ row.name }}</el-link>
            <el-tag v-else-if="col.key === 'type'" size="small" :type="row.type === 1 ? 'primary' : row.type === 2 ? 'warning' : 'success'" data-test="datafactory-type-tag">
              {{ FACTORY_TYPE_LABEL[row.type] || row.type }}
            </el-tag>
            <el-tag v-else-if="col.key === 'status'" :type="row.status === 1 ? 'success' : 'info'" size="small" data-test="datafactory-status-tag">
              {{ FACTORY_STATUS_LABEL[row.status] || row.status }}
            </el-tag>
            <TimeCell v-else-if="col.key === 'createdAt'" :iso="row.createdAt" />
            <span v-else>{{ row[col.key] || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('dataFactory.operation')" width="230" fixed="right">
          <template #default="{ row }">
            <RowActions :actions="dfRowActions(row)" :max="3" @command="(k: string) => onDfRowCmd(k, row)" />
          </template>
        </el-table-column>
      </DataTablePage>

      <div class="pagination-bar">
        <el-pagination v-if="total > 0"
          v-model:current-page="page"
          :page-size="size"
          :total="total"
          layout="total, prev, pager, next"
          data-test="datafactory-pagination"
          @current-change="load"
            />
      </div>
        </el-tab-pane>

        <el-tab-pane :label="t('dataFactory.poolTab')" name="pool">
          <div class="pool-filters">
            <el-select v-model="poolFilters.envId" clearable :placeholder="t('dataFactory.poolEnv')" style="width: 160px" data-test="pool-filter-env" filterable @change="loadPool(1)">
              <el-option v-for="e in envs" :key="e.id" :label="e.name" :value="e.id" />
            </el-select>
            <el-select v-model="poolFilters.type" clearable :placeholder="t('dataFactory.poolType')" style="width: 140px" data-test="pool-filter-type" @change="loadPool(1)">
              <el-option v-for="ty in POOL_TYPES" :key="ty" :label="poolTypeLabel(ty)" :value="ty" />
            </el-select>
            <el-select v-model="poolFilters.status" clearable :placeholder="t('dataFactory.poolStatus')" style="width: 130px" data-test="pool-filter-status" @change="loadPool(1)">
              <el-option v-for="(label, st) in POOL_STATUS_LABEL" :key="st" :label="label" :value="Number(st)" />
            </el-select>
            <span class="pool-tip">{{ t('dataFactory.poolPlaceholder') }}</span>
          </div>
          <TableSkeleton v-if="poolLoading" :cols="6" data-test="datafactorylistview-skeleton" />
          <DataTablePage v-else :data="poolRows" data-test="pool-table" class="data-table" row-key="id" :size="poolPane.state.density" :column-config="poolPane.cfg" :empty-title="t('dataFactory.noPool')" :empty-hint="t('dataFactory.poolTip')">
            <!-- (R25 mode 1): the pool pane carries its own scoped column
 view (tp-colcfg-dataPool) so the page-level popover outfits here
 after a tab switch instead of acting dead on this table.
 Screening/sorting for pool rows stays server-side-free (fixed
 record list), only the column set is user-configurable. -->
            <el-table-column
              v-for="col in poolPane.visibleColumns.value"
              :key="col.key"
              :prop="col.key"
              :fixed="col.fixed || false"
              :label="t(col.labelKey)"
              :width="col.width ?? undefined"
              :min-width="col.width ? undefined : DF_FLEX_MIN[col.key]"
              :show-overflow-tooltip="col.key === 'value' || col.key === 'itemKey' || col.key === 'envName'"
              :align="col.key === 'type' || col.key === 'status' ? 'center' : undefined"
              :data-test="`pool-col-${col.key}`"
            >
              <template #default="{ row }">
                <span v-if="col.key === 'value'" data-test="pool-value">{{ row.value }}</span>
                <span v-else-if="col.key === 'type'">{{ row.type }}</span>
                <template v-else-if="col.key === 'itemKey'">
                  <span v-if="row.name" data-test="pool-key">{{ row.name }}</span>
                  <el-text v-else size="small" type="warning" data-test="pool-key-missing">{{ t('dataFactory.poolKeyMissing') }}</el-text>
                </template>
                <template v-else-if="col.key === 'status'">
                  <el-tag :type="row.status === 1 ? 'success' : row.status === 3 ? 'danger' : 'info'" size="small" data-test="pool-status-tag">
                    {{ row.statusLabel }}
                  </el-tag>
                </template>
                <TimeCell v-else-if="col.key === 'createdAt'" :iso="row.createdAt" />
                <span v-else>{{ row[col.key] }}</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('dataFactory.operation')" width="230" fixed="right">
              <template #default="{ row }">
                <el-button v-if="row.status !== 1" size="small" type="success" data-test="pool-recycle-btn" @click="setPoolStatus(row, 1)">
                  {{ t('dataFactory.poolRecycle') }}
                </el-button>
                <el-button v-if="row.status !== 3" size="small" type="warning" data-test="pool-abnormal-btn" @click="setPoolStatus(row, 3)">
                  {{ t('dataFactory.poolAbnormal') }}
                </el-button>
                <el-button size="small" type="danger" data-test="pool-delete-btn" @click="confirmDeletePool(row)">
                  {{ t('dataFactory.delete') }}
                </el-button>
              </template>
            </el-table-column>
          </DataTablePage>
          <div class="pagination-bar">
            <el-pagination v-if="poolTotal > 0"
              v-model:current-page="poolPage"
              :page-size="poolSize"
              :total="poolTotal"
              layout="total, prev, pager, next"
              data-test="pool-pagination"
              @current-change="loadPool"
            />
          </div>
        </el-tab-pane>
      </el-tabs>

   
      <el-dialog
        v-model="dialogVisible"
        :title="editing ? t('dataFactory.editTitle') : t('dataFactory.createTitle')"
        width="640px"
        :before-close="factoryDraftGuard.beforeClose"
        data-test="datafactory-dialog"
      >
        <!-- ownership echo (iron rule 1): read-only, single-sourced from the
 header project context - the record lands on the visible segment -->
        <ProjectOwnerEcho />
        <el-form ref="formRef" :model="form" :rules="rules" label-width="110px" scroll-to-error>
          <el-form-item :label="t('dataFactory.name')" prop="name">
            <el-input v-model="form.name" maxlength="100" data-test="datafactory-form-name" :placeholder="t('dataFactory.namePlaceholder')" />
          </el-form-item>
          <!-- for="" = EP labelled-group mode (UpgradeCenterView precedent):
               radio groups are not labelable, a generated `for` pointing at the
               wrapper div trips the Chromium form-association issue audit -->
          <el-form-item :label="t('dataFactory.type')" prop="type" for="">
            <!-- id="" prevents the form label for= from targeting the non-labelable radiogroup div -->
            <el-radio-group id="" v-model="form.type" data-test="datafactory-form-type" :disabled="editing">
              <el-radio v-for="(label, ty) in FACTORY_TYPE_LABEL" :key="ty" :value="Number(ty)">{{ label }}</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item :label="t('dataFactory.env')" prop="envId">
            <el-select v-model="form.envId" style="width: 100%" data-test="datafactory-form-env" filterable :placeholder="t('dataFactory.envPlaceholder')">
              <el-option v-for="e in envs" :key="e.id" :label="`${e.name}（${envTypeLabel(e.envType)}）`" :value="e.id" />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('dataFactory.content')" prop="content">
            <el-input
              v-model="form.content"
              type="textarea"
              :rows="8"
              data-test="datafactory-form-content"
              :placeholder="contentPlaceholder"
            />
          </el-form-item>
   
          <el-form-item :label="t('dataFactory.poolKey')" prop="poolKey">
            <el-input
              v-model="form.poolKey"
              maxlength="64"
              clearable
              data-test="datafactory-form-poolkey"
              :placeholder="t('dataFactory.poolKeyPlaceholder')"
            />
          </el-form-item>
          <el-form-item :label="t('dataFactory.status')" v-if="editing" for="">
            <!-- id="" prevents the form label for= from targeting the non-labelable radiogroup div -->
            <el-radio-group id="" v-model="form.status" data-test="datafactory-form-status">
              <el-radio :value="1">{{ t('dataFactory.enabled') }}</el-radio>
              <el-radio :value="2">{{ t('dataFactory.disabled') }}</el-radio>
            </el-radio-group>
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="dialogVisible = false">{{ t('dataFactory.cancel') }}</el-button>
          <el-button type="primary" :loading="saving" data-test="datafactory-form-submit" @click="submit">
            {{ t('dataFactory.save') }}
          </el-button>
        </template>
      </el-dialog>

      
      <el-drawer draggable v-model="detailVisible" size="640px" data-test="datafactory-detail-drawer" :title="t('dataFactory.detailTitle')">
        <template v-if="current">
          <el-descriptions :column="1" border>
            <el-descriptions-item :label="t('dataFactory.name')">{{ current.name }}</el-descriptions-item>
            <el-descriptions-item :label="t('dataFactory.type')">{{ FACTORY_TYPE_LABEL[current.type] }}</el-descriptions-item>
            <el-descriptions-item :label="t('dataFactory.env')">{{ current.envName || '-' }}</el-descriptions-item>
            <el-descriptions-item :label="t('dataFactory.status')">
              {{ FACTORY_STATUS_LABEL[current.status] || current.status }}
            </el-descriptions-item>
            <el-descriptions-item :label="t('dataFactory.createdBy')">{{ current.createdByName || '-' }}</el-descriptions-item>
            <el-descriptions-item :label="t('dataFactory.createdAt')">{{ current.createdAt }}</el-descriptions-item>
          </el-descriptions>
          <h4 class="content-heading">{{ t('dataFactory.contentPreview') }}</h4>
          <pre class="content-preview" data-test="datafactory-content-preview">{{ prettyContent }}</pre>
        </template>
      </el-drawer>

      
      <el-drawer draggable v-model="historyVisible" size="640px" data-test="datafactory-history-drawer" :title="t('dataFactory.historyTitle')">
        <template v-if="historyRows.length > 0">
          <DataTablePage :data="historyRows" size="small" data-test="datafactory-history-table">
            <el-table-column :label="t('dataFactory.runStatus')" width="80">
              <template #default="{ row }">
                <el-tag :type="row.status === 2 ? 'success' : row.status === 3 ? 'danger' : 'warning'" size="small">
                  {{ RUN_STATUS_LABEL[row.status] || row.status }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="resultSummary" :label="t('dataFactory.runSummary')" min-width="180" show-overflow-tooltip />
            <el-table-column prop="createdByName" :label="t('dataFactory.createdBy')" width="110" />
            <el-table-column prop="createdAt" :label="t('dataFactory.createdAt')" width="150" >
              <template #default="{ row }"><TimeCell :iso="row.createdAt" /></template>
            </el-table-column>
          </DataTablePage>
        </template>
        <EmptyState v-else :title="t('dataFactory.noHistory')" :hint="t('dataFactory.historyEmptyHint')" />
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
import { useObjectSync } from '@/composables/useObjectSync'
import ProjectOwnerEcho from '@/components/ProjectOwnerEcho.vue'
import ColumnSettings from '@/components/ColumnSettings.vue'
import { useTabScopedSettings } from '@/composables/tabScopedColumnSettings'
import type { ColumnDef } from '@/composables/useColumnConfig'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { envTypeLabel, getProjectEnvs } from '@/api/project'
import { bizErrorMsg } from '@/utils/bizError'
import { applyPoolKey, extractPoolKey } from '@/utils/factoryPoolKey'
import { validateDataKey } from '@/utils/dataRef'
import {
  FACTORY_STATUS_LABEL,
  FACTORY_TYPE_LABEL,
  POOL_STATUS_LABEL,
  RUN_STATUS_LABEL,
  createDataFactory,
  deleteDataFactory,
  deletePoolItem,
  listDataFactories,
  listFactoryRuns,
  listPoolItems,
  runDataFactory,
  updateDataFactory,
  updatePoolItemStatus,
  type DataFactoryRunView,
  type DataFactoryView,
  type DataPoolItemView,
} from '@/api/dataFactory'

const { t } = useI18n()

// ---------- column configuration (shared useColumnConfig piece) ----------
/** Configurable data columns (the fixed-right action column stays locked out). */
const FACTORY_COLUMNS: ColumnDef[] = [
  { key: 'name', labelKey: 'dataFactory.name', width: null },
  { key: 'type', labelKey: 'dataFactory.type', width: 110 },
  { key: 'envName', labelKey: 'dataFactory.env', width: 140 },
  { key: 'status', labelKey: 'dataFactory.status', width: 90 },
  { key: 'createdByName', labelKey: 'dataFactory.createdBy', width: 120 },
  { key: 'createdAt', labelKey: 'dataFactory.createdAt', width: 160 },
]
/** Pool-pane data columns : six columns with real screening behind them,
 * so the pool table gets a genuine per-pane column config (not a fixed view) —
 * hiding envName/status or widening the value column has real user value when
 * the pool rows run long. Keys mirror the rendered field; prop stays only on
 * columns whose row field name equals the key (width write-back anchor). */
const POOL_COLUMNS: ColumnDef[] = [
  { key: 'value', labelKey: 'dataFactory.poolValue', width: null },
  { key: 'type', labelKey: 'dataFactory.poolType', width: 100 },
  { key: 'itemKey', labelKey: 'dataFactory.poolItemKey', width: 130 },
  { key: 'envName', labelKey: 'dataFactory.poolEnv', width: 130 },
  { key: 'status', labelKey: 'dataFactory.poolStatus', width: 90 },
  { key: 'createdAt', labelKey: 'dataFactory.poolCreatedAt', width: 160 },
]
/** Flexible columns fall back to min-width when no explicit width is set. */
const DF_FLEX_MIN: Record<string, number> = { name: 170, value: 200 }

const activeTab = ref('factory')

/**
 * (TB group R25, mode 1 = scope-aware single settings piece): the
 * trailing-slot popover outfits to the active tab instead of silently acting
 * on the factory table while the pool table is what the user sees. Each pane
 * owns its viewKey (tp-colcfg-dataFactories keeps the historical key;
 * tp-colcfg-dataPool is new) so the two tables never share persisted state.
 * Both panes are real configurable views: factory = 6 join-enriched columns,
 * pool = 6 record columns with independent filters — column value justifies
 * the config on both, so no disabled fixed-view pane is needed here.
 */
const settingsHost = useTabScopedSettings(activeTab)
const factoryPane = settingsHost.settingsFor('factory', 'dataFactories', FACTORY_COLUMNS)
const poolPane = settingsHost.settingsFor('pool', 'dataPool', POOL_COLUMNS)
const currentPane = settingsHost.currentPane


// Column key -> core data-factory SORT_COLUMNS whitelist key (single source for
// the sortable decision and the request payload; . Sorting
// happens server-side over the whole project window, not the loaded pageful.
// envName/createdByName stay out: join-enriched display columns, not base
// columns. The pool table (listPoolItems) and history drawer (recent-runs)
// talk to different endpoints and stay outside this sort contract.
const SORT_KEY_MAP: Record<string, string> = {
  name: 'name',
  type: 'type',
  status: 'status',
  createdAt: 'created_at',
}
/** Whether this column has a server-side sort key (shared decision source). */
function isColSortable(key: string): boolean {
  return key in SORT_KEY_MAP
}
const query = reactive<{ sortBy: string; sortDir: string }>({ sortBy: '', sortDir: '' })

/** Header sort: forward the whitelisted key to core; clearing a sort falls
 * back to the historical id DESC default order; page resets to 1. */
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
const router = useRouter()
const auth = useAuthStore()

defineOptions({ name: 'DataFactoryList' })

const projects = computed(() => auth.projects)
const { projectId, followTick } = useProjectPageContext()
const rows = ref<DataFactoryView[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const saving = ref(false)

const envs = ref<{ id: number; name: string; envType: number }[]>([])

const filters = reactive<{ type: number | null; keyword: string }>({ type: null, keyword: '' })

// (activeTab + the pane-scoped settings host moved above, next to the column defs)
const POOL_TYPES = ['\u8d26\u53f7', '\u8ba2\u5355', '\u5238']
const POOL_TYPE_KEYS: Record<string, string> = {
  '\u8d26\u53f7': 'dataFactory.poolTypes.account',
  '\u8ba2\u5355': 'dataFactory.poolTypes.order',
  '\u5238': 'dataFactory.poolTypes.coupon',
}
function poolTypeLabel(ty: string): string {
  const key = POOL_TYPE_KEYS[ty]
  return key ? t(key) : ty
}
const poolFilters = reactive<{ envId: number | null; type: string | null; status: number | null }>({
  envId: null,
  type: null,
  status: null,
})
const poolRows = ref<DataPoolItemView[]>([])
const poolTotal = ref(0)
const poolPage = ref(1)
const poolSize = ref(20)
const poolLoading = ref(false)

const dialogVisible = ref(false)
const editing = ref(false)
const editingId = ref(0)
const detailVisible = ref(false)
const current = ref<DataFactoryView | null>(null)
const historyVisible = ref(false)
const historyRows = ref<DataFactoryRunView[]>([])

const form = reactive<{ name: string; type: number; envId: number; content: string; poolKey: string; status: number }>({
  name: '',
  type: 1,
  envId: 0,
  content: '',
  poolKey: '',
  status: 1,
})


const factoryDraftGuard = useDraftGuard(form as unknown as Record<string, unknown>, () => {
  editing.value = false
  editingId.value = 0
  Object.assign(form, { name: '', type: 1, envId: 0, content: '', poolKey: '', status: 1 })
})

const formRef = ref<FormInstance>()
const rules: FormRules = {
  name: [{ required: true, message: t('dataFactory.nameRequired'), trigger: 'blur' }],
  type: [{ required: true, message: t('dataFactory.typeRequired'), trigger: 'change' }],
  envId: [{ required: true, type: 'number', min: 1, message: t('dataFactory.envRequired'), trigger: 'change' }],
  content: [{ required: true, message: t('dataFactory.contentRequired'), trigger: 'blur' }],
  poolKey: [{
    validator: (_r, value: string, cb) => {
      const v = String(value ?? '').trim()
      if (!v) return cb()
      const err = validateDataKey(v)
      return err ? cb(new Error(err)) : cb()
    },
    trigger: 'blur',
  }],
}

const contentPlaceholder = computed(() => {
  if (form.type === 1) {
    return t('dataFactory.contentApiPlaceholder')
  }
  if (form.type === 2) {
    return t('dataFactory.contentSqlPlaceholder')
  }
  return t('dataFactory.contentScriptPlaceholder')
})

const prettyContent = computed(() => {
  if (!current.value) return ''
  try {
    return JSON.stringify(JSON.parse(current.value.content), null, 2)
  } catch {
    return current.value.content
  }
})

async function loadProjectOptions(): Promise<void> {
  if (!projectId.value) return
  const res = await getProjectEnvs(projectId.value)
  envs.value = (res as { id: number; name: string; envType: number }[]) || []
}


async function load(p = page.value): Promise<void> {
  if (!projectId.value) return
  page.value = p
  loading.value = true
  try {
    const res = await listDataFactories({
      projectId: projectId.value,
      keyword: filters.keyword || undefined,
      type: filters.type || undefined,
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

function openCreate(): void {
  if (editing.value) factoryDraftGuard.discard()
  dialogVisible.value = true
  factoryDraftGuard.snapshot()
}

function dfRowActions(row: DataFactoryView) {
  return [
    { key: "run", label: t("dataFactory.run"), disabled: row.status !== 1 },
    { key: "history", label: t("dataFactory.history") },
    { key: "edit", label: t("dataFactory.edit") },
    { key: "delete", label: t("dataFactory.delete"), type: "danger" as const },
  ]
}
function onDfRowCmd(key: string, row: DataFactoryView): void {
  if (key === "run") void confirmRun(row)
  else if (key === "history") void openHistory(row)
  else if (key === "edit") openEdit(row)
  else if (key === "delete") void confirmDelete(row)
}

function openEdit(row: DataFactoryView): void {
  editing.value = true
  editingId.value = row.id
  Object.assign(form, {
    name: row.name,
    type: row.type,
    envId: row.envId,
    content: row.content,
    poolKey: extractPoolKey(row.type, row.content) ?? '',
    status: row.status,
  })
  dialogVisible.value = true
  factoryDraftGuard.snapshot()
}

async function submit(): Promise<void> {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  let content: string
  try {
    content = applyPoolKey(form.type, form.content, form.poolKey)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : t('dataFactory.poolKeyWriteFail'))
    return
  }
  if (!projectId.value) return
  saving.value = true
  try {
    if (editing.value) {
      await updateDataFactory(editingId.value, {
        name: form.name.trim(),
        content,
        status: form.status,
      })
      ElMessage.success(t('dataFactory.updated'))
    } else {
      await createDataFactory({
        projectId: projectId.value,
        envId: form.envId,
        type: form.type,
        name: form.name.trim(),
        content,
      })
      ElMessage.success(t('dataFactory.created'))
    }
    dialogVisible.value = false
    factoryDraftGuard.discard()
    await load(1)
  } finally {
    saving.value = false
  }
}

async function confirmRun(row: DataFactoryView): Promise<void> {
  try {
    await ElMessageBox.confirm(t('dataFactory.runConfirm', { name: row.name }), t('dataFactory.runTitle'), {
      type: 'warning',
      confirmButtonText: t('dataFactory.run'),
      cancelButtonText: t('dataFactory.cancel'),
    })
  } catch {
    return
  }
  try {
    const res = await runDataFactory(row.id)
    if (res.status === 2) {
      ElMessage.success(t('dataFactory.runSuccess', { count: res.createdCount, pool: res.poolCount }))
    } else {
      ElMessage.error(res.message || t('dataFactory.runFailed'))
    }
    await load(page.value)
  } catch (e: any) {
    ElMessage.error(bizErrorMsg(e, t('dataFactory.runFailed')))
  }
}

async function confirmDelete(row: DataFactoryView): Promise<void> {
  try {
    await ElMessageBox.confirm(t('dataFactory.deleteConfirm', { name: row.name }), t('dataFactory.deleteTitle'), {
      type: 'warning',
      confirmButtonText: t('dataFactory.delete'),
      cancelButtonText: t('dataFactory.cancel'),
    })
  } catch {
    return
  }
  await deleteDataFactory(row.id)
  ElMessage.success(t('dataFactory.deleted'))
  await load(page.value)
}

async function openDetail(row: DataFactoryView): Promise<void> {
  current.value = row
  detailVisible.value = true
}

async function openHistory(row: DataFactoryView): Promise<void> {
  historyVisible.value = true
  historyRows.value = []
  try {
    const res = await listFactoryRuns({ id: row.id, projectId: row.projectId, page: 1, size: 20 })
    historyRows.value = res.list || []
  } catch {
    historyRows.value = []
  }
}


async function loadPool(p = poolPage.value): Promise<void> {
  if (!projectId.value) return
  poolPage.value = p
  poolLoading.value = true
  try {
    const res = await listPoolItems({
      projectId: projectId.value,
      envId: poolFilters.envId || undefined,
      type: poolFilters.type || undefined,
      status: poolFilters.status || undefined,
      page: poolPage.value,
      size: poolSize.value,
    })
    poolRows.value = res.list || []
    poolTotal.value = res.total || 0
  } catch {
    poolRows.value = []
    poolTotal.value = 0
  } finally {
    poolLoading.value = false
  }
}

async function setPoolStatus(row: DataPoolItemView, status: number): Promise<void> {
  if (!projectId.value) return
  try {
    await updatePoolItemStatus(row.id, projectId.value, status)
    ElMessage.success(status === 1 ? t('dataFactory.poolRecycled') : t('dataFactory.poolMarked'))
    await loadPool(poolPage.value)
  } catch (e: any) {
    ElMessage.error(bizErrorMsg(e, t('dataFactory.runFailed')))
  }
}

async function confirmDeletePool(row: DataPoolItemView): Promise<void> {
  try {
    await ElMessageBox.confirm(t('dataFactory.poolDeleteConfirm', { value: row.value }), t('dataFactory.poolDeleteTitle'), {
      type: 'warning',
      confirmButtonText: t('dataFactory.delete'),
      cancelButtonText: t('dataFactory.cancel'),
    })
  } catch {
    return
  }
  if (!projectId.value) return
  try {
    await deletePoolItem(row.id, projectId.value)
    ElMessage.success(t('dataFactory.poolDeleted'))
    await loadPool(poolPage.value)
  } catch (e: any) {
    ElMessage.error(bizErrorMsg(e, t('dataFactory.runFailed')))
  }
}

onMounted(async () => {
  if (projectId.value) {
    await loadProjectOptions()
    await load(1)
    await loadPool(1)
  }
})

watch(followTick, () => {
  if (!projectId.value) return
  void Promise.all([loadProjectOptions(), load(1), loadPool(1)])
})

// : DATA_FACTORY_WRITE landings create or edit a script row — exactly
// what this list renders. Re-read the current page in place (load() defaults
// to page.value, filters preserved), silently (BG iron rule 2); an edit
// updates its row and a create joins the standing order. The data-pool tab is
// NOT re-read here: landing saves only the script definition, pool rows change
// when a human runs the script, which stays outside the draft door.
useObjectSync({
  watch: ['DATA_FACTORY_WRITE'],
  onSync: () => void load(),
})

useKeepAliveList(async () => {
  if (!projectId.value) return
  await Promise.all([loadProjectOptions(), load(), loadPool()])
})

const hasActiveFilter = computed(() => filters.type !== null || filters.keyword.trim() !== '')
function clearFactoryFilters(): void { filters.type = null; filters.keyword = ''; load(1) }
</script>

<style scoped>
.content-heading {
  margin: 16px 0 8px;
  font-size: 14px;
}

.pool-filters {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.pool-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.content-preview {
  margin: 0;
  padding: 12px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 320px;
  overflow: auto;
}
</style>
