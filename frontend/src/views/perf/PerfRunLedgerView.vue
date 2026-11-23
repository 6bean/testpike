<!-- tp-subtitle-exempt: the subtitle carries the ownership-rule echo "this list is the current project segment" (rule G clause 3) — segment semantics the top bar (project name only) cannot express; the ledger table has no owner column because every row is the segment by definition. -->
<template>
  <div class="page-root" data-test="perf-run-ledger-page">
    <div class="page-card">
      <!-- segment visibility (ownership-echo iron law 3): the ledger is a project
 window — the subtitle names the segment it is reading. Page name itself
 rides the header single source (R23: no in-page title row). -->
      <p class="page-subtitle" data-test="perf-ledger-subtitle">
        {{ projectId
          ? t('perf.ledger.subtitle', { project: projectName })
          : t('perf.ledger.noProject') }}
      </p>

      <!-- Filter band (R21 bands): status/mark narrowing filters lead the
           band (query submit stays implicit on change — server-paged window),
           view-state actions (column settings, refresh) park in the trailing
           slot, column settings ahead of refresh. -->
      <div class="filter-bar" data-test="perf-ledger-filter-bar">
        <el-select
          v-model="statusFilter"
          clearable
          :placeholder="t('perf.ledger.filterStatus')"
          style="width: 160px"
          data-test="perf-ledger-filter-status"
          @change="load(1)"
        >
          <el-option
            v-for="(entry, code) in PERF_RUN_STATUS"
            :key="code"
            :value="Number(code)"
            :label="td(entry.label)"
          />
        </el-select>
        <el-select
          v-model="markFilter"
          clearable
          :placeholder="t('perf.ledger.filterMark')"
          style="width: 160px"
          data-test="perf-ledger-filter-mark"
          @change="load(1)"
        >
          <el-option v-for="m in LEDGER_MARK_OPTIONS" :key="m" :value="m" :label="t(`perf.mark.${m}`)" />
        </el-select>
        <div class="filter-bar-actions">
          <ColumnSettings
            :columns="settingsRows"
            :density="colCfg.state.density"
            @toggle="colCfg.toggle"
            @move="colCfg.move"
            @density="colCfg.setDensity"
            @reset="colCfg.reset"
            @pin="colCfg.toggleFixed"
          />
          <el-button :icon="Refresh" :loading="loading" data-test="perf-ledger-refresh" @click="load()">
            {{ t('common.refresh') }}
          </el-button>
        </div>
      </div>

      <TableSkeleton v-if="loading" :cols="8" data-test="perf-ledger-skeleton" />
      <!-- Server-side sort: every sortable header maps to the core whitelist
 (status/mark/started_at/finished_at/created_at); scenario_name is an
 annotated read-model field and stays unsortable (core would refuse).
 Worst-case scale: runs grow unbounded per project — the window is
 server-paged, never re-paginated client-side. Columns are configurable
 through the shared useColumnConfig piece . -->
      <el-table
        v-else-if="rows.length > 0"
        border
        :data="rows"
        row-key="id"
        :size="colCfg.state.density === 'small' ? 'small' : 'default'"
        data-test="perf-ledger-table"
        @sort-change="onSortChange"
        @header-dragend="onHeaderDragEnd"
      >
        <el-table-column
          v-for="col in colCfg.visibleColumns.value"
          :key="col.key"
          :prop="col.key"
          :fixed="col.fixed || false"
          :label="t(col.labelKey)"
          :width="col.width ?? undefined"
          :min-width="col.width ? undefined : LEDGER_FLEX_MIN[col.key]"
          :sortable="LEDGER_SORT_MAP[col.key] ? 'custom' : false"
          :show-overflow-tooltip="col.key === 'scenario_name' || undefined"
          :data-test="`perf-ledger-col-${col.key}`"
        >
          <template #default="{ row }">
            <span v-if="col.key === 'scenario_name'" class="mono">{{ row.scenario_name || '—' }}</span>
            <el-tag v-else-if="col.key === 'status'" size="small" :type="runStatusTag(row.status).type"
              disable-transitions data-test="perf-ledger-status-tag">
              {{ runStatusTag(row.status).label }}
            </el-tag>
            <template v-else-if="col.key === 'mark'">
              <el-tag v-if="markTag(row.mark)" size="small" :type="markTag(row.mark)!.type" disable-transitions
                data-test="perf-ledger-mark-tag">
                {{ markTag(row.mark)!.label }}
              </el-tag>
              <span v-else>—</span>
            </template>
            <span v-else-if="col.key === 'pressure_snapshot'">{{ pressureLabel(row.pressure_snapshot) }}</span>
            <span v-else-if="col.key === 'started_at'">{{ fmtTime(row.started_at) }}</span>
            <span v-else-if="col.key === 'finished_at'">{{ fmtTime(row.finished_at) }}</span>
            <span v-else-if="col.key === 'created_at'">{{ fmtTime(row.created_at) }}</span>
            <span v-else>{{ row[col.key] ?? '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.action')" width="90" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" data-test="perf-ledger-open-detail" @click="openRun(row)">
              {{ t('perf.ledger.openRun') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <EmptyState
        v-if="!loading && rows.length === 0"
        :title="t('perf.ledger.emptyTitle')"
        :hint="projectId ? t('perf.ledger.emptyHint') : t('perf.ledger.noProjectHint')"
      />

      <div v-if="total > 0" class="pagination-bar">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="pageSize"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          data-test="perf-ledger-pager"
          @current-change="load()"
          @size-change="load(1)"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * Project-wide load-run ledger .
 * <p>Read door: admin GET /perf/runs/project-page (member-visible guard, zero
 * new permission keys). Unlike the workbench run drawer (scenario-keyed, so a
 * run whose scenario was deleted disappears), this ledger lists every run of
 * the header project segment — decoupled history stays visible with the name it
 * actually ran under (launch-snapshot fallback, server-annotated). Row click
 * opens the existing run-detail page (curves + report); the ledger itself is
 * strictly read-only, no write doors here.
 * <p>Iron laws honored: R30 segment switch = the header only (no in-page
 * selector), F15 page state (page/size/sort) rides the URL query and restores
 * on reload/deep-link, R21 refresh in the filter-bar trailing slot, R23 no
 * in-page title row, display-scale: server-paged window with the core clamp.
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import { useAuthStore } from '@/stores/auth'
import EmptyState from '@/components/EmptyState.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import ColumnSettings from '@/components/ColumnSettings.vue'
import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'
import { td } from '@/utils/dictLabel'
import { runStatusTag, fmtTime } from './chartUtils'
import { useObjectSync } from '@/composables/useObjectSync'
import { pageProjectPerfRuns, PERF_RUN_STATUS, type PerfRunItem } from '@/api/perf'

const { t, te } = useI18n()
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
// R30: the segment is switched only from the header (URL deep link wins on
// arrival, then follows header changes through followTick).
const { projectId, followTick } = useProjectPageContext()

const loading = ref(false)
const rows = ref<PerfRunItem[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const sortBy = ref('')
const sortDir = ref('')
// Server-side narrowing filters (blank = unfiltered; core counts the narrowed
// total so the pager stays truthful while a filter is on).
const statusFilter = ref<number | undefined>(undefined)
const markFilter = ref<string>('')
/** Run marks the ledger can filter on — the same three-value family the
 *  marking door writes (core PerfRunService), kept in sync there. */
const LEDGER_MARK_OPTIONS = ['GOLDEN', 'CANDIDATE', 'VOID'] as const

const projectName = computed(() => {
  const hit = auth.projects.find((p) => p.projectId === projectId.value)
  return hit?.projectName ?? String(projectId.value ?? '')
})

/** Column prop -> core ledger whitelist key (status/mark/started_at/finished_at/
 *  created_at; scenario_name deliberately absent — annotation field, the server
 *  refuses an off-list key fail-closed so the map is the only source here). */
const LEDGER_SORT_MAP: Record<string, string> = {
  status: 'status',
  mark: 'mark',
  started_at: 'started_at',
  finished_at: 'finished_at',
  created_at: 'created_at',
}

// ---------- column configuration (shared useColumnConfig piece, nine-set) ----------
const LEDGER_COLUMNS: ColumnDef[] = [
  { key: 'id', labelKey: 'perf.ledger.colId', width: 90 },
  { key: 'scenario_name', labelKey: 'perf.ledger.colScenario', width: null },
  { key: 'status', labelKey: 'perf.ledger.colStatus', width: 110 },
  { key: 'mark', labelKey: 'perf.ledger.colMark', width: 100 },
  { key: 'pressure_snapshot', labelKey: 'perf.ledger.colPressure', width: null },
  { key: 'started_at', labelKey: 'perf.ledger.colStarted', width: 170 },
  { key: 'finished_at', labelKey: 'perf.ledger.colFinished', width: 170 },
  { key: 'created_at', labelKey: 'perf.ledger.colCreated', width: 170 },
]
/** Elastic-column floor widths (only applied when the user never set an explicit width). */
const LEDGER_FLEX_MIN: Record<string, number> = {
  scenario_name: 200,
  pressure_snapshot: 160,
}
const colCfg = useColumnConfig('perf-run-ledger', LEDGER_COLUMNS)
const settingsRows = computed(() =>
  colCfg.state.columns.map((c) => ({ ...c, labelKey: LEDGER_COLUMNS.find((d) => d.key === c.key)!.labelKey })),
)

/** Header drag writes the real width back (border mode activates EP dragging). */
function onHeaderDragEnd(newWidth: number, _old: number, column: { property?: string }): void {
  if (column?.property) colCfg.updateWidth(column.property, Math.round(newWidth))
}

/** F15: page state (page/size/sort) serializes into the query; defaults drop
 *  their keys so the bare URL stays the default view. Sync is replace-only —
 *  the ledger never stacks history entries while sorting. */
function syncUrl() {
  const q: Record<string, string> = {}
  if (page.value !== 1) q.page = String(page.value)
  if (pageSize.value !== 20) q.size = String(pageSize.value)
  if (sortBy.value) {
    q.sortBy = sortBy.value
    q.sortDir = sortDir.value
  }
  if (statusFilter.value != null) q.status = String(statusFilter.value)
  if (markFilter.value) q.mark = markFilter.value
  if (projectId.value != null) q.projectId = String(projectId.value)
  void router.replace({ query: q })
}

function readUrl() {
  const p = Number(route.query.page)
  if (Number.isFinite(p) && p > 0) page.value = p
  const s = Number(route.query.size)
  if (s === 10 || s === 20 || s === 50) pageSize.value = s
  const key = String(route.query.sortBy ?? '')
  if (LEDGER_SORT_MAP[key]) {
    sortBy.value = LEDGER_SORT_MAP[key]
    sortDir.value = route.query.sortDir === 'asc' ? 'asc' : 'desc'
  }
  const st = Number(route.query.status)
  if (st >= 1 && st <= 4) statusFilter.value = st
  const mk = String(route.query.mark ?? '')
  if ((LEDGER_MARK_OPTIONS as readonly string[]).includes(mk)) markFilter.value = mk
}

async function load(resetPage?: number): Promise<void> {
  if (resetPage != null) page.value = resetPage
  if (projectId.value == null) {
    rows.value = []
    total.value = 0
    return
  }
  loading.value = true
  try {
    const res = await pageProjectPerfRuns(
      projectId.value,
      page.value,
      pageSize.value,
      sortBy.value || undefined,
      sortDir.value || undefined,
      statusFilter.value ?? undefined,
      markFilter.value || undefined,
    )
    rows.value = res.list
    total.value = res.total
  } catch {
    rows.value = []
    total.value = 0
    ElMessage.error(t('perf.ledger.loadFail'))
  } finally {
    loading.value = false
    syncUrl()
  }
}

// perf-domain segment: PERF_LAUNCH lands a new run row for this
// project, and this ledger lists exactly the project's run window — the
// landed row must surface without any user action. load() re-reads the
// current page/filter/sort as-is (BG iron rule 2: silent, no page jump; the
// new row simply appears inside whatever window is on screen). Landing of
// another project's run is filtered out by the anchor, same posture as the
// explore ledger precedent.
useObjectSync({
  watch: ['PERF_LAUNCH'],
  anchor: (event) => event.projectId !== null && event.projectId === projectId.value,
  onSync: () => void load(),
})

function onSortChange({ prop, order }: { prop: string; order: string | null }): void {
  page.value = 1
  if (order === null) {
    sortBy.value = ''
    sortDir.value = ''
  } else {
    const key = LEDGER_SORT_MAP[prop]
    if (key) {
      sortBy.value = key
      sortDir.value = order === 'ascending' ? 'asc' : 'desc'
    }
  }
  void load()
}

/** Run marking tag: same three-value key family as the workbench trend
 *  (perf.mark.*), unknown values render no tag (null mark = unmarked). */
function markTag(mark: string | null | undefined): { label: string; type: 'primary' | 'warning' | 'info' } | null {
  if (mark !== 'GOLDEN' && mark !== 'CANDIDATE' && mark !== 'VOID') return null
  const key = `perf.mark.${mark}`
  if (!te(key)) return null
  return { label: t(key), type: mark === 'GOLDEN' ? 'primary' : mark === 'CANDIDATE' ? 'warning' : 'info' }
}

/** Pressure snapshot is a stored JSON string; the ledger shows a one-line
 *  summary (model + target), full shapes belong to the detail page (no raw
 *  JSON dump in a list cell — report-readability iron law). */
function pressureLabel(raw: string | null): string {
  if (!raw) return '—'
  try {
    const obj = JSON.parse(raw) as Record<string, unknown>
    const bits: string[] = []
    if (typeof obj.model === 'string') bits.push(obj.model)
    if (typeof obj.targetRps === 'number') bits.push(`${obj.targetRps} RPS`)
    else if (typeof obj.vus === 'number') bits.push(`${obj.vus} VU`)
    return bits.length ? bits.join(' · ') : '—'
  } catch {
    return '—'
  }
}

/** Deep link into the existing run-detail door, carrying the segment (same
 * posture as the workbench jump: /perf/run/:id?projectId=…). */
function openRun(row: PerfRunItem): void {
  router.push({ path: `/perf/run/${row.id}`, query: { projectId: String(projectId.value ?? '') } })
}

onMounted(() => {
  readUrl()
  void load()
})

// Header switch / late hydration / pruning: refetch the new segment from page 1
// (the segment changed, so stale paging would lie about the window).
watch([projectId, followTick], () => {
  page.value = 1
  void load()
})
</script>

<style scoped>
.page-subtitle {
  margin: 0 0 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
