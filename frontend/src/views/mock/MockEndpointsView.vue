<!-- tp-owner-echo-exempt: rule-2 form = whole-page no-project empty block
 (mock-endpoints-no-project covers the missing-context case); the editor
 drawer opens only from row-level edit actions inside that project segment,
 so there is no page-level create button to gate. Echo wiring for the
 drawer itself is pinned on MockSpacesView / MockDocsView consumers. -->
<template>
  <div class="page-root mock-endpoints-page" data-test="mock-endpoints-page">
    <div class="page-card">
      <!-- R23 stage-2: empty title shell removed — the page name rides the
           global topbar, and the retired title-side "?" now lives on the
           topbar help dot (data-test=header-help-btn). -->

      <!-- screening is server-side (worst-case scale: the project may carry
           hundreds of rules across dozens of spaces; a window-only search
           would screen a lie about what exists) -->
      <div v-if="projectId" class="filter-bar" data-test="mock-endpoints-filter-bar">
        <el-select
          v-model="pickedStatus"
          :placeholder="t('mockEndpoints.filterStatus')"
          clearable
          style="width: 150px"
          data-test="mock-endpoints-filter-status"
          @change="applyFilter"
        >
          <el-option :label="t('mockEndpoints.statusDraft')" :value="0" />
          <el-option :label="t('mockEndpoints.statusPublished')" :value="1" />
          <el-option :label="t('mockEndpoints.statusRetired')" :value="2" />
        </el-select>
        <el-select
          v-model="pickedMethod"
          :placeholder="t('mockEndpoints.filterMethod')"
          clearable
          filterable
          style="width: 130px"
          data-test="mock-endpoints-filter-method"
          @change="applyFilter"
        >
          <el-option v-for="m in METHOD_OPTIONS" :key="m" :label="m" :value="m" />
        </el-select>
        <el-input
          v-model="keyword"
          :placeholder="t('mockEndpoints.searchPh')"
          :prefix-icon="Search"
          clearable
          style="width: 240px"
          data-test="mock-endpoints-search"
          @keyup.enter="applyFilter"
          @clear="applyFilter"
        />
        <el-button data-test="mock-endpoints-search-go" @click="applyFilter">{{ t('mockEndpoints.search') }}</el-button>
        <div class="filter-bar-actions">
          <!-- R25 mode declaration (scope-aware single control, TB group): this page carries no
               tabs and exactly one table, so the page-level column-settings popover equals the
               table scope; show/hide, order, pinning and density persist per user via the piece. -->
          <ColumnSettings
            :columns="settingsRows"
            :density="colCfg.state.density"
            @toggle="colCfg.toggle"
            @move="colCfg.move"
            @density="colCfg.setDensity"
            @reset="colCfg.reset"
            @pin="colCfg.toggleFixed"
          />
          <!-- migration batch (R21 bands): refresh moved here from the header band
 (the query submit above already sits inline at the group end). -->
          <el-button :icon="Refresh" :loading="loading" data-test="mock-endpoints-refresh" @click="reload">
            {{ t('mockEndpoints.refresh') }}
          </el-button>
        </div>
      </div>

      <div v-if="!projectId" class="empty-block" data-test="mock-endpoints-no-project">
        <EmptyState :title="t('mockEndpoints.noProject')" />
      </div>

      <template v-else>
        <TableSkeleton v-if="loading && !rows.length" :cols="colCfg.visibleColumns.value.length + (canWrite ? 1 : 0)" data-test="mock-endpoints-skeleton" />
        <div v-else-if="!rows.length && !loading" class="empty-block" data-test="mock-endpoints-empty">
          <EmptyState :title="t('mockEndpoints.empty')" :hint="t('mockEndpoints.emptyHint')" />
        </div>

        <template v-else>
          <el-table border :data="rows" data-test="mock-endpoints-table" row-key="stubId"
            :size="colCfg.state.density"
            @sort-change="onSortChange"
          >
            <el-table-column
              v-for="col in colCfg.visibleColumns.value"
              :key="col.key"
              :prop="col.key"
              :fixed="col.fixed || false"
              :label="t(col.labelKey)"
              :width="col.width ?? undefined"
              :min-width="col.width ? undefined : ENDPOINT_FLEX_MIN[col.key]"
              :align="col.key === 'hits7d' ? 'center' : undefined"
              sortable="custom"
              :data-test="`mock-col-${col.key}`"
            >
              <template #default="{ row }">
                <template v-if="col.key === 'pathPattern'">
                  <div class="endpoint-cell">
                    <span class="mono method-tag" :class="`m-${row.method.toLowerCase()}`">{{ row.method }}</span>
                    <span class="mono path-text">{{ row.pathPattern }}</span>
                    <el-tag size="small" type="info">{{ pathTypeLabel(row.pathMatchType) }}</el-tag>
                  </div>
                  <div class="url-line">
                    <span class="mono muted url-text" data-test="mock-endpoints-url">{{ endpointUrl(row) || '—' }}</span>
                    <el-button
                      v-if="endpointUrl(row)"
                      link
                      type="primary"
                      data-test="mock-endpoints-copy-curl"
                      @click="copyCurl(row)"
                    >
                      {{ t('mockEndpoints.actCopyCurl') }}
                    </el-button>
                    <el-button
                      v-if="row.method === 'GET' && row.spaceStatus === 1 && row.enabled"
                      link
                      type="primary"
                      data-test="mock-endpoints-try"
                      @click="tryIt(row)"
                    >
                      {{ t('mockEndpoints.actTry') }}
                    </el-button>
                  </div>
                </template>
                <template v-else-if="col.key === 'spaceName'">
                  <el-button
                    link
                    type="primary"
                    class="space-link"
                    data-test="mock-endpoints-space-jump"
                    @click="gotoSpaceDocs(row)"
                  >
                    {{ row.spaceName }}
                  </el-button>
                  <div class="mono muted">/mocks/{{ row.spaceKey }}</div>
                </template>
                <template v-else-if="col.key === 'spaceStatus'">
                  <el-tag size="small" :type="statusTagType(row.spaceStatus)">{{ statusLabel(row.spaceStatus) }}</el-tag>
                  <el-tag v-if="row.enabled === false" size="small" type="warning" class="state-gap">
                    {{ t('mockEndpoints.disabledTag') }}
                  </el-tag>
                  <el-tooltip v-if="row.contractStale" :content="t('mockEndpoints.staleTip')" placement="top">
                    <el-tag size="small" type="danger" class="state-gap" data-test="mock-endpoints-stale-tag">
                      {{ t('mockEndpoints.staleTag') }}
                    </el-tag>
                  </el-tooltip>
                </template>
                <template v-else-if="col.key === 'hits7d'">
                  <span v-if="row.spaceStatus === 1 && row.enabled !== false" data-test="mock-endpoints-hits">
                    <span class="hit-num" :class="{ 'hit-zero': !row.hits7d }">{{ row.hits7d ?? 0 }}</span>
                    <span v-if="!row.hits7d" class="muted no-traffic"> {{ t('mockEndpoints.noTraffic') }}</span>
                  </span>
                  <span v-else class="muted">—</span>
                </template>
                <span v-else class="muted">{{ shortTime(row.updatedAt) }}</span>
              </template>
            </el-table-column>
            <!-- V240 follow-up (user ruling "act where you find"): the write
                 doors live HERE too — same shared drawer, same core doors, same
                 label dictionary as the docs page (zero fork). Fixed-right
                 column: hover blend is theme.scss's single-point rule. -->
            <el-table-column
              v-if="canWrite"
              :label="t('mockEndpoints.colActions')"
              width="200"
              fixed="right"
            >
              <template #default="{ row }">
                <el-button link type="primary" data-test="mock-endpoints-act-edit" @click="openEdit(row)">
                  {{ t('mockDocs.actEdit') }}
                </el-button>
                <el-button link type="primary" data-test="mock-endpoints-act-toggle" @click="toggleEnabled(row)">
                  {{ row.enabled === false ? t('mockDocs.actEnable') : t('mockDocs.actDisable') }}
                </el-button>
                <el-button link type="danger" data-test="mock-endpoints-act-delete" @click="confirmDelete(row)">
                  {{ t('mockDocs.actDelete') }}
                </el-button>
              </template>
            </el-table-column>
          </el-table>

          <div class="pagination-bar" data-test="mock-endpoints-pager">
            <el-pagination
              v-model:current-page="page"
              v-model:page-size="pageSize"
              :total="total"
              :page-sizes="[20, 50, 100]"
              layout="total, sizes, prev, pager, next"
              background
              data-test="mock-endpoints-pagination"
              @current-change="loadRows"
              @size-change="applyFilter"
            />
          </div>
        </template>
      </template>

      <!-- one editor, two doors: the SAME component the docs page mounts -->
      <MockStubEditorDrawer
        ref="editorRef"
        :space-id="null"
        :space-published="editPublished"
        @saved="onEditorSaved"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * V240 endpoint inventory (menu: Mock Endpoint Inventory) — the project-wide
 * flat list of every mock endpoint across every space, drafts and retired
 * included (the per-space doc pages structurally cannot
 * answer "what can I call right now, anywhere in this project"). Rows travel
 * in routing order (space priority, then within-space evaluation order) with
 * the 7-day matched-serve count attached: a published rule nobody calls is
 * shown honestly, not hidden.
 *
 * The page is read + copy oriented: URL composition reuses the shared
 * mockEndpointUrl util (docs page twin — one function owns the shape), the
 * space column jumps into that space's doc page carrying §12a context, and
 * editing stays where the rule lives (the doc page owns the edit door).
 */
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import ColumnSettings from '@/components/ColumnSettings.vue'
import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'
import MockStubEditorDrawer from '@/components/MockStubEditorDrawer.vue'
import { useAuthStore } from '@/stores/auth'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import { bizErrorMsg } from '@/utils/bizError'
import { mockEndpointCurl, mockEndpointUrl } from '@/utils/mockServingUrl'
import {
  deleteStub,
  getServingUrl,
  pageServingEndpoints,
  recentStubHits,
  updateStub,
  type MockServingEndpointItem,
  type MockServingUrl,
  type MockStubItem,
} from '@/api/mock'

const { t } = useI18n()
const router = useRouter()
const route = useRoute()
const { projectId } = useProjectPageContext()

const METHOD_OPTIONS = ['ANY', 'GET', 'POST', 'PUT', 'DELETE', 'PATCH', 'HEAD', 'OPTIONS']

/**
 * Inventory table column definitions for the shared column-config piece (N-3 repayment).
 * Widths mirror the previous hard-coded el-table-column attributes so the default
 * layout is unchanged; show/hide, order, pinning and density then persist per user
 * through the piece. The trailing action column stays pinned outside the loop
 * (locked column, not configurable).
 */
const MOCK_ENDPOINT_COLUMNS: ColumnDef[] = [
  { key: 'pathPattern', labelKey: 'mockEndpoints.colEndpoint', width: null },
  { key: 'spaceName', labelKey: 'mockEndpoints.colSpace', width: null },
  { key: 'spaceStatus', labelKey: 'mockEndpoints.colState', width: 150 },
  { key: 'hits7d', labelKey: 'mockEndpoints.colHits', width: 120 },
  { key: 'updatedAt', labelKey: 'mockEndpoints.colUpdated', width: 150 },
]
/** Flexible columns fall back to min-width when no explicit width is set. */
const ENDPOINT_FLEX_MIN: Record<string, number> = { pathPattern: 280, spaceName: 170 }
const colCfg = useColumnConfig('mock-endpoints', MOCK_ENDPOINT_COLUMNS)
/** Popover rows (column state merged with labelKey for rendering). */
const settingsRows = computed(() =>
  colCfg.state.columns.map((c) => ({ ...c, labelKey: MOCK_ENDPOINT_COLUMNS.find((d) => d.key === c.key)!.labelKey })),
)

const rows = ref<MockServingEndpointItem[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
// Boot-guarded skeleton: with the flag starting false the first frame falls
// through to the custom empty block before the fetch mounts (same defect class,
// 2026-11-04 reclassification).
const loading = ref(true)
const servingUrl = ref<MockServingUrl | null>(null)

const pickedStatus = ref<number | null>(null)
const pickedMethod = ref<string | null>(null)
// Deep-link landing (inventory badge jump): ?keyword= prefilters the same
// server-side keyword the filter box owns — one ledger, no shadow filter.
const keyword = ref(
  typeof route.query.keyword === 'string' ? route.query.keyword : '',
)

// Column prop -> core SORT_COLUMNS whitelist key (single source for the
// sortable decision and the request payload — columns outside the whitelist
// never emit sort params, no client-only fake sort). Composed url / action
// columns stay unsorted by design.
const SORT_KEY_MAP: Record<string, string> = {
  pathPattern: 'path_pattern',
  spaceName: 'space_name',
  spaceStatus: 'space_status',
  hits7d: 'hits_7d',
  updatedAt: 'updated_at',
}
// Server-side sort state (same shape as the tag/user/requirement pages).
const query = reactive<{ sortBy: string; sortDir: string }>({ sortBy: '', sortDir: '' })

/** Header sort: forward the whitelisted key to the server (ORDER BY happens
 * in core SQL); clearing a sort falls back to the routing-first default
 * order; page resets to 1 so paging windows stay consistent. */
function onSortChange({ prop, order }: { prop: string; order: string | null }): void {
  if (order === null) {
    query.sortBy = ''
    query.sortDir = ''
    page.value = 1
    void loadRows()
    return
  }
  const key = SORT_KEY_MAP[prop]
  if (!key) {
    void loadRows()
    return
  }
  query.sortBy = key
  query.sortDir = order === 'ascending' ? 'asc' : 'desc'
  page.value = 1
  void loadRows()
}

async function loadRows(): Promise<void> {
  if (!projectId.value) {
    rows.value = []
    total.value = 0
    return
  }
  loading.value = true
  try {
    const res = await pageServingEndpoints(
      projectId.value,
      page.value,
      pageSize.value,
      pickedStatus.value,
      pickedMethod.value,
      keyword.value.trim() || null,
      { sortBy: query.sortBy || undefined, sortDir: query.sortDir || undefined },
    )
    rows.value = res.list
    total.value = res.total
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('mockEndpoints.loadFailed')))
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function applyFilter(): void {
  page.value = 1
  void loadRows()
}

async function reload(): Promise<void> {
  servingUrl.value = await getServingUrl().catch(() => null)
  await loadRows()
}

/** The composed URL (serving root + handle + path) — '' until the root reads. */
function endpointUrl(row: MockServingEndpointItem): string {
  return mockEndpointUrl(servingUrl.value, row.spaceKey, row.pathPattern)
}

async function copyCurl(row: MockServingEndpointItem): Promise<void> {
  const url = endpointUrl(row)
  if (!url) {
    ElMessage.warning(t('mockEndpoints.servingUnavailable'))
    void reload()
    return
  }
  try {
    await navigator.clipboard.writeText(mockEndpointCurl(row.method, url))
    ElMessage.success(t('mockEndpoints.curlCopied'))
  } catch {
    ElMessage.warning(t('mockEndpoints.copyDenied'))
  }
}

function tryIt(row: MockServingEndpointItem): void {
  const url = endpointUrl(row)
  if (!url) {
    ElMessage.warning(t('mockEndpoints.servingUnavailable'))
    void reload()
    return
  }
  window.open(url, '_blank', 'noopener')
}

// ---- V240 follow-up (user ruling: act where you find) — the same three
// write doors the docs page card carries, wired to the SAME shared editor
// and the SAME core update/delete doors. Inventory rows carry the full
// mutable payload (scenario columns ride on the V240 view), so an edit from
// this page round-trips everything the drawer does not own.
const auth = useAuthStore()
const canWrite = computed(() => auth.hasPerm('perm:mock:write'))
const editorRef = ref<InstanceType<typeof MockStubEditorDrawer> | null>(null)
const editPublished = ref(false)

function toEditorRow(row: MockServingEndpointItem): MockStubItem {
  return {
    id: row.stubId,
    spaceId: row.spaceId,
    name: row.ruleName,
    priority: row.priority,
    method: row.method,
    pathMatchType: row.pathMatchType,
    pathPattern: row.pathPattern,
    matchersJson: row.matchersJson,
    responseJson: row.responseJson,
    scenarioName: row.scenarioName,
    requiredState: row.requiredState,
    newState: row.newState,
    source: row.source,
    sourceRef: row.sourceRef,
    enabled: row.enabled,
    version: row.version,
    createdAt: row.updatedAt,
    updatedAt: row.updatedAt,
  }
}

function openEdit(row: MockServingEndpointItem): void {
  editPublished.value = row.spaceStatus === 1
  editorRef.value?.open(toEditorRow(row))
}

async function onEditorSaved(): Promise<void> {
  await loadRows()
}

/** One-click serving switch (identical semantics to the docs-page card). */
async function toggleEnabled(row: MockServingEndpointItem): Promise<void> {
  const nextEnabled = row.enabled === false
  try {
    await updateStub(row.stubId, {
      name: row.ruleName,
      priority: row.priority,
      method: row.method,
      pathMatchType: row.pathMatchType,
      pathPattern: row.pathPattern,
      matchersJson: row.matchersJson,
      responseJson: row.responseJson,
      scenarioName: row.scenarioName,
      requiredState: row.requiredState,
      newState: row.newState,
      enabled: nextEnabled,
      expectedVersion: row.version ?? 1,
    })
    ElMessage.success(nextEnabled ? t('mockDocs.enabledOk') : t('mockDocs.disabledOk'))
    await loadRows()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('mockDocs.updateFailed')))
    await loadRows()
  }
}

/** Delete = the loud door with the real 7-day impact quoted first (same
 *  wording family as the docs page; live = published space + enabled rule). */
async function confirmDelete(row: MockServingEndpointItem): Promise<void> {
  let hits: number | null = null
  try {
    hits = await recentStubHits(row.stubId)
  } catch {
    hits = null
  }
  const live = row.spaceStatus === 1 && row.enabled !== false
  const detail = hits == null
    ? (live ? t('mockDocs.deleteConfirmLiveUnknown') : t('mockDocs.deleteConfirmQuiet'))
    : (live && hits > 0
        ? t('mockDocs.deleteConfirmLive', { hits })
        : t('mockDocs.deleteConfirmQuietHits', { hits }))
  try {
    await ElMessageBox.confirm(detail, t('mockDocs.deleteConfirmTitle', { name: row.ruleName }), {
      type: live ? 'warning' : 'info',
      confirmButtonText: t('mockDocs.deleteConfirmOk'),
      cancelButtonText: t('common.cancel'),
    })
  } catch {
    return // dismissed
  }
  try {
    await deleteStub(row.stubId)
    ElMessage.success(t('mockDocs.deletedOk'))
    await loadRows()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('mockDocs.deleteFailed')))
  }
}

/** §12a jump: land on that space's workbench docs tab . */
function gotoSpaceDocs(row: MockServingEndpointItem): void {
  void router.push({
    path: `/mock/spaces/${row.spaceId}`, query: { projectId: String(projectId.value ?? ''), tab: 'docs' },
  })
}

function statusLabel(status: number | null): string {
  if (status === 1) return t('mockEndpoints.statusPublished')
  if (status === 2) return t('mockEndpoints.statusRetired')
  return t('mockEndpoints.statusDraft')
}

function statusTagType(status: number | null): 'success' | 'warning' | 'info' {
  if (status === 1) return 'success'
  if (status === 2) return 'info'
  return 'warning'
}

function pathTypeLabel(type: string): string {
  const map: Record<string, string> = {
    exact: 'mockEndpoints.pathTypeExact',
    prefix: 'mockEndpoints.pathTypePrefix',
    regex: 'mockEndpoints.pathTypeRegex',
    template: 'mockEndpoints.pathTypeTemplate',
  }
  return map[type] ? t(map[type]) : type
}

function shortTime(raw: string | null): string {
  if (!raw) return '—'
  return raw.replace('T', ' ').slice(0, 16)
}

// mock-domain ledger: this page lists the project's serving stub
// rules across spaces (pageServingEndpoints), so a created or edited rule
// landed from the dialog must surface here without any user action.
// Re-read keeps the current filter/page/sort exactly as-is (iron rules 2-3);
// space landings create unpublished spaces with no served rows and change no
// column here, so MOCK_SPACE_WRITE is intentionally NOT watched on this page.
import { useObjectSync } from '@/composables/useObjectSync'

useObjectSync({
  watch: ['MOCK_STUB_WRITE'],
  onSync: () => void loadRows(),
})

watch(projectId, () => applyFilter())
void reload()
</script>

<style scoped>
.mock-endpoints-page { padding: 16px 20px; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; flex-wrap: wrap; margin-bottom: 12px; }
.page-title { margin: 0; font-size: 18px; }
.page-subtitle { margin: 4px 0 0; font-size: 12px; color: var(--el-text-color-secondary); }
.header-actions { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.filter-bar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 12px; }
.mono { font-family: var(--el-font-family-mono, monospace); font-size: 12px; }
.muted { color: var(--el-text-color-secondary); }
.empty-block { padding: 24px 0; }
.endpoint-cell { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.path-text { font-size: 13px; font-weight: 600; }
.url-line { display: flex; align-items: center; gap: 8px; margin-top: 4px; flex-wrap: wrap; }
.url-text { word-break: break-all; }
.method-tag { padding: 2px 8px; border-radius: 4px; background: var(--el-color-info-light-8); font-weight: 600; }
.method-tag.m-get { background: var(--el-color-success-light-8); color: var(--el-color-success); }
.method-tag.m-post { background: var(--el-color-warning-light-8); color: var(--el-color-warning); }
.method-tag.m-put { background: var(--el-color-primary-light-8); color: var(--el-color-primary); }
.method-tag.m-delete { background: var(--el-color-danger-light-8); color: var(--el-color-danger); }
.space-link { font-size: 13px; }
.state-gap { margin-left: 6px; }
.hit-num { font-weight: 600; }
.hit-zero { color: var(--el-text-color-secondary); }
.no-traffic { font-size: 12px; margin-left: 4px; }
</style>
