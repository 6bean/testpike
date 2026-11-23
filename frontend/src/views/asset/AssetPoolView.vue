<template>
  <div class="page-root" data-test="asset-pool-page">
    <div class="page-card">
      <!-- Filter band: the pool is global (cross-project discoverability is the
           product rule), so the only server-side filter is the asset kind.
           Worst-case scale is handled by server paging, not inline rendering.
           View-state action (column settings) sits in the band tail slot. -->
      <div class="filter-bar">
        <el-select
          v-model="query.kind"
          :placeholder="t('asset.kindLabel')"
          clearable
          style="width: 160px"
          popper-class="tp-asset-kind-popper"
          data-test="asset-kind-select"
          @change="reload"
        >
          <el-option v-for="opt in KIND_OPTIONS" :key="opt.value" :label="t(opt.labelKey)" :value="opt.value" />
        </el-select>
        <el-button data-test="asset-search" @click="reload">
          <el-icon><Search /></el-icon>{{ t('asset.query') }}
        </el-button>
        <div class="band-tail">
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

      <TableSkeleton v-if="loading" :cols="5" data-test="asset-pool-skeleton" />
      <DataTablePage
        v-else
        :data="rows"
        row-key="id"
        data-test="asset-table"
        :size="colCfg.state.density"
        :column-config="colCfg"
        @sort-change="onSortChange"
      >
        <!-- Configurable data columns via the shared useColumnConfig piece;
             the action column is locked and stays out of the configuration. -->
        <el-table-column
          v-for="col in colCfg.visibleColumns.value"
          :key="col.key"
          :prop="col.key"
          :fixed="col.fixed || false"
          :label="t(col.labelKey)"
          :width="col.width ?? undefined"
          :min-width="col.width ? undefined : ASSET_FLEX_MIN[col.key]"
          :sortable="isColSortable(col.key) ? 'custom' : false"
          :show-overflow-tooltip="col.key === 'name' || col.key === 'ownerProjectName'"
          :align="col.key === 'assetVersion' || col.key === 'refCount' ? 'center' : undefined"
          :data-test="`asset-col-${col.key}`"
        >
          <template #default="{ row }">
            <el-tag v-if="col.key === 'kind'" size="small" data-test="asset-kind-tag">{{ t(kindLabelKey(row.kind)) }}</el-tag>
            <span v-else-if="col.key === 'assetVersion'">v{{ row.assetVersion }}</span>
            <!-- landing point 1: chain counter, clickable into the same
 references drawer the action column opens; >0 renders a link,
 0 stays plain text (nothing to drill into). -->
            <el-button
              v-else-if="col.key === 'refCount' && row.refCount > 0"
              link
              type="primary"
              data-test="asset-col-ref-count"
              @click="openRefs(row)"
            >{{ row.refCount }}</el-button>
            <span v-else-if="col.key === 'refCount'" data-test="asset-col-ref-count">0</span>
            <span v-else>{{ row[col.key] }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('asset.colAction')" width="190" align="center">
          <template #default="{ row }">
            <el-button link type="primary" data-test="asset-refs-open" @click="openRefs(row)">
              {{ t('asset.viewRefs') }}
            </el-button>
            <!-- Scope honesty (R25): withdrawal writes into the owner project,
 so outside that project segment the door is visibly disabled and
 points at the header switch instead of failing silently. -->
            <el-tooltip
              v-if="!isOwner(row)"
              :content="t('asset.withdrawNeedOwner', { owner: row.ownerProjectName })"
              placement="top"
            >
              <span>
                <el-button link type="danger" disabled data-test="asset-withdraw-disabled">
                  {{ t('asset.withdraw') }}
                </el-button>
              </span>
            </el-tooltip>
            <el-button v-else link type="danger" data-test="asset-withdraw-open" @click="openWithdraw(row)">
              {{ t('asset.withdraw') }}
            </el-button>
          </template>
        </el-table-column>
      </DataTablePage>
      <div class="pagination-bar" data-test="asset-pool-pagination">
        <el-pagination
          v-model:current-page="query.page"
          :page-size="query.size"
          :total="total"
          layout="total, prev, pager, next"
          background
          @current-change="loadList"
        />
      </div>
    </div>

    <!-- Reference roster drawer: "who uses me" for one whole version chain,
         paged through the same bidirectional query single source. The count
         line is the chain-wide pin total from the paged envelope. -->
    <el-drawer v-model="refsVisible" size="680px" :title="t('asset.refsDrawerTitle')" data-test="asset-refs-drawer">
      <div class="refs-head" data-test="asset-refs-head">
        {{ activeRow?.name }} · {{ activeRow?.ownerProjectName }} · v{{ activeRow?.assetVersion }}
      </div>
      <div class="refs-count" data-test="asset-ref-count">{{ t('asset.refCount', { n: refsTotal }) }}</div>
      <el-table v-loading="refsLoading" border size="small" :data="refRows" data-test="asset-refs-table">
        <el-table-column prop="referrerProjectName" :label="t('asset.refColProject')" min-width="140" show-overflow-tooltip />
        <el-table-column :label="t('asset.refColHost')" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ t('asset.hostFormat', { kind: row.referrerKind, id: row.referrerId }) }}</template>
        </el-table-column>
        <el-table-column :label="t('asset.refColPinned')" width="140" align="center">
          <template #default="{ row }">
            <span data-test="asset-pin-version">v{{ row.pinnedVersion }}</span>
            <el-tag v-if="row.stale" size="small" type="warning" class="stale-tag" data-test="asset-stale-tag">
              {{ t('asset.staleTag', { latest: row.headVersion }) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" :label="t('asset.refColCreatedAt')" width="170" />
      </el-table>
      <div class="pagination-bar" data-test="asset-refs-pagination">
        <el-pagination
          v-model:current-page="refsQuery.page"
          :page-size="refsQuery.size"
          :total="refsTotal"
          layout="total, prev, pager, next"
          background
          @current-change="loadRefs"
        />
      </div>
      <!-- Version timeline: how many pins each exact version row carries.
           Counts and the head marker come from core verbatim (no client math). -->
      <h4 class="timeline-title" data-test="asset-timeline-title">{{ t('asset.timelineTitle') }}</h4>
      <el-table
        v-loading="timelineLoading"
        border
        size="small"
        :data="timelineRows"
        :empty-text="t('asset.timelineEmpty')"
        data-test="asset-timeline-table"
      >
        <el-table-column :label="t('asset.timelineColVersion')" width="110" align="center">
          <template #default="{ row }">v{{ row.assetVersion }}</template>
        </el-table-column>
        <el-table-column :label="t('asset.timelineColHead')" width="110" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.head" size="small" type="success" data-test="asset-timeline-head-tag">
              {{ t('asset.timelineHeadTag') }}
            </el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('asset.timelineColPinned')" min-width="120" align="center">
          <template #default="{ row }">
            <span data-test="asset-timeline-pinned">{{ t('asset.timelinePinnedCount', { n: row.pinnedCount }) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="updatedAt" :label="t('asset.timelineColPublishedAt')" min-width="170" />
      </el-table>
    </el-drawer>

    <!-- Withdraw dialog: fail-closed semantics stay visible — the server names
         the active referrers on the first attempt; the force checkbox is the
         declared escape hatch (notify-all + invalidation cascade). -->
    <el-dialog v-model="withdrawVisible" :title="t('asset.withdrawTitle')" width="480px" data-test="asset-withdraw-dialog">
      <p class="withdraw-text">{{ t('asset.withdrawText', { name: activeRow?.name ?? '' }) }}</p>
      <el-checkbox v-model="withdrawForce" data-test="asset-withdraw-force">{{ t('asset.forceAck') }}</el-checkbox>
      <template #footer>
        <el-button data-test="asset-withdraw-cancel" @click="withdrawVisible = false">{{ t('asset.cancel') }}</el-button>
        <el-button type="danger" :loading="withdrawing" data-test="asset-withdraw-confirm" @click="doWithdraw">
          {{ t('asset.withdraw') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
/**
 * Shared asset pool page : the cross-project discovery surface
 * of the publish-reference system. Lists globally published asset heads with
 * source project and version, opens a per-chain reference roster drawer and
 * gates chain withdrawal behind the owner project (server re-checks the fence;
 * the disabled door here is an honest hint, not the authority).
 * Publishing entry points live in the source editors (step editor overlay,
 * — this page never fabricates a publish form without its payload
 * source contract.
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'
import ColumnSettings from '@/components/ColumnSettings.vue'
import DataTablePage from '@/components/DataTablePage.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import {
  listSharedAssets,
  referencesToChain,
  chainVersions,
  withdrawSharedAsset,
  type SharedAssetView,
  type AssetReferenceView,
  type AssetVersionTimelineView,
} from '@/api/sharedAsset'

const { t } = useI18n()
const { projectId } = useProjectPageContext()

const KIND_OPTIONS = [
  { value: 'case_fragment', labelKey: 'asset.kind.case_fragment' },
  { value: 'action_component', labelKey: 'asset.kind.action_component' },
  { value: 'login_recipe', labelKey: 'asset.kind.login_recipe' },
  { value: 'gen_function', labelKey: 'asset.kind.gen_function' },
]

// ---------- column configuration (shared useColumnConfig piece, UserListView form) ----------
/** Configurable data columns; the fixed action column stays locked out. */
const ASSET_COLUMNS: ColumnDef[] = [
  { key: 'name', labelKey: 'asset.colName', width: null },
  { key: 'kind', labelKey: 'asset.colKind', width: 130 },
  { key: 'ownerProjectName', labelKey: 'asset.colOwner', width: null },
  { key: 'assetVersion', labelKey: 'asset.colVersion', width: 90 },
  { key: 'refCount', labelKey: 'asset.colRefCount', width: 110 },
]
/** Flexible columns fall back to min-width when no explicit width is set. */
const ASSET_FLEX_MIN: Record<string, number> = { name: 220, ownerProjectName: 160 }
// Column prop -> core SORT_COLUMNS whitelist key. Single source for both the
// sortable-column decision and the request payload — columns outside the
// whitelist never emit sort params (no client-only fake sort).
const SORT_KEY_MAP: Record<string, string> = {
  name: 'name',
  kind: 'kind',
  ownerProjectName: 'owner_project_name',
  assetVersion: 'asset_version',
}
/** Whether this column has a server-side sort key (shared decision source). */
function isColSortable(key: string): boolean {
  return key in SORT_KEY_MAP
}
const colCfg = useColumnConfig('asset-pool', ASSET_COLUMNS)
/** Popover rows (column state merged with labelKey for rendering). */
const settingsRows = computed(() =>
  colCfg.state.columns.map((c) => ({ ...c, labelKey: ASSET_COLUMNS.find((d) => d.key === c.key)!.labelKey })),
)

const loading = ref(false)
const rows = ref<SharedAssetView[]>([])
const total = ref(0)
const query = reactive<{
  kind?: string
  page: number
  size: number
  sortBy: string
  sortDir: string
}>({ kind: undefined, page: 1, size: 20, sortBy: '', sortDir: '' })

function kindLabelKey(kind: string): string {
  const hit = KIND_OPTIONS.find((o) => o.value === kind)
  return hit ? hit.labelKey : 'asset.kind.unknown'
}

function isOwner(row: SharedAssetView): boolean {
  return projectId.value != null && projectId.value === row.projectId
}

async function loadList(): Promise<void> {
  loading.value = true
  try {
    const res = await listSharedAssets({
      kind: query.kind || undefined,
      page: query.page,
      size: query.size,
      sortBy: query.sortBy || undefined,
      sortDir: query.sortDir || undefined,
    })
    rows.value = res.list ?? []
    total.value = res.total ?? 0
  } catch {
    // the axios layer already toasts the server's plain-language error
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/** Header sort: forward the whitelisted key to core (ORDER BY happens in SQL);
 * clearing a sort falls back to the historical updated_at DESC order; page resets to 1. */
function onSortChange({ prop, order }: { prop: string; order: string | null }): void {
  query.page = 1
  if (order === null) {
    query.sortBy = ''
    query.sortDir = ''
    void loadList()
    return
  }
  const key = SORT_KEY_MAP[prop]
  if (!key) {
    void loadList()
    return
  }
  query.sortBy = key
  query.sortDir = order === 'ascending' ? 'asc' : 'desc'
  void loadList()
}

function reload(): void {
  query.page = 1
  void loadList()
}

// ---- reference roster drawer ----
const refsVisible = ref(false)
const refsLoading = ref(false)
const refRows = ref<AssetReferenceView[]>([])
const refsTotal = ref(0)
const refsQuery = reactive({ page: 1, size: 20 })
const activeRow = ref<SharedAssetView | null>(null)
// Version timeline under the roster: per-version pin counts, core single-source.
const timelineLoading = ref(false)
const timelineRows = ref<AssetVersionTimelineView[]>([])

async function loadTimeline(): Promise<void> {
  const row = activeRow.value
  if (!row) return
  timelineLoading.value = true
  try {
    timelineRows.value = (await chainVersions({
      chainProjectId: row.projectId,
      kind: row.kind,
      name: row.name,
    })) ?? []
  } catch {
    // silent degradation to the empty state; the guide text names the retry path
    timelineRows.value = []
  } finally {
    timelineLoading.value = false
  }
}

async function loadRefs(): Promise<void> {
  const row = activeRow.value
  if (!row) return
  refsLoading.value = true
  try {
    const res = await referencesToChain({
      chainProjectId: row.projectId,
      kind: row.kind,
      name: row.name,
      page: refsQuery.page,
      size: refsQuery.size,
    })
    refRows.value = res.list ?? []
    refsTotal.value = res.total ?? 0
  } catch {
    refRows.value = []
    refsTotal.value = 0
  } finally {
    refsLoading.value = false
  }
}

function openRefs(row: SharedAssetView): void {
  activeRow.value = row
  refsQuery.page = 1
  refRows.value = []
  refsTotal.value = 0
  timelineRows.value = []
  refsVisible.value = true
  // roster and timeline are independent reads of the same chain anchor;
  // fire both without awaiting so one slow query never blocks the other
  void loadRefs()
  void loadTimeline()
}

// ---- withdraw flow ----
const withdrawVisible = ref(false)
const withdrawForce = ref(false)
const withdrawing = ref(false)

function openWithdraw(row: SharedAssetView): void {
  activeRow.value = row
  withdrawForce.value = false
  withdrawVisible.value = true
}

async function doWithdraw(): Promise<void> {
  const row = activeRow.value
  if (!row) return
  withdrawing.value = true
  try {
    await withdrawSharedAsset(row.projectId, row.id, withdrawForce.value)
    withdrawVisible.value = false
    ElMessage.success(t('asset.withdrawDone'))
    void loadList()
  } catch {
    // fail-closed path: the server error message names the active referrers
    // and the dialog stays open so force can be acknowledged deliberately
  } finally {
    withdrawing.value = false
  }
}

onMounted(() => void loadList())
</script>

<style scoped>
.band-tail {
  margin-left: auto;
  display: flex;
  align-items: center;
}
.pagination-bar {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
.refs-head {
  font-weight: 600;
  margin-bottom: 4px;
}
.refs-count {
  color: var(--el-text-color-secondary);
  margin-bottom: 12px;
}
.stale-tag {
  margin-left: 6px;
}
.timeline-title {
  margin: 16px 0 8px;
  font-size: 14px;
  font-weight: 600;
}
.withdraw-text {
  margin: 0 0 12px;
  line-height: 1.6;
}
</style>
