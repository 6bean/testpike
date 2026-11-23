<template>
  <div class="page-root" data-test="delete-request-page">
    <div class="page-card">
      <div class="toolbar">
        <div class="toolbar-right">
          <el-select
            v-model="statusFilter"
            :placeholder="t('deleteRequest.statusAll')"
            clearable
            style="width: 130px"
            data-test="delreq-status-filter"
            @change="load(1)"
          >
            <el-option :label="t('deleteRequest.stPending')" :value="2" />
            <el-option :label="t('deleteRequest.stApproved')" :value="3" />
            <el-option :label="t('deleteRequest.stRejected')" :value="4" />
          </el-select>
          <el-button type="primary" :disabled="!projectId" data-test="delreq-search" @click="load(1)">
            <el-icon><Search /></el-icon>{{ t('deleteRequest.searchBtn') }}
          </el-button>
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
        </div>
      </div>

      <div v-if="!loading && projects.length === 0" class="empty-box" data-test="delreq-empty-no-project">
        <el-empty :description="t('deleteRequest.noProject')" />
      </div>

      <TableSkeleton v-else-if="loading" :cols="colCfg.visibleColumns.value.length + 1" data-test="delreqview-skeleton" />
      <!-- Main ledger table: header sort is server-side — every sortable column below is decided
           by the SORT_KEY_MAP whitelist and the ORDER BY happens in core SQL over the whole
           filtered project window. reason and reject_reason stay honestly unsorted: free text
           the reviewer reads. Data columns render through the shared column-config loop so
           show/hide, order and pinning take effect; the action column stays locked outside. -->
      <el-table border v-else :data="list" :size="colCfg.state.density" data-test="delreq-table" row-key="id"
        @sort-change="onSortChange">
        <el-table-column
          v-for="col in colCfg.visibleColumns.value"
          :key="col.key"
          :prop="col.key"
          :fixed="col.fixed || false"
          :label="t(col.labelKey)"
          :width="col.width ?? undefined"
          :min-width="col.width ? undefined : DELREQ_FLEX_MIN[col.key]"
          :sortable="SORT_KEY_MAP[col.key] ? 'custom' : false"
          :show-overflow-tooltip="col.key === 'reason' || col.key === 'rejectReason' || undefined"
          :data-test="`delreq-col-${col.key}`"
        >
          <template #default="{ row }">
            <template v-if="col.key === 'targetSummary'">
              <el-tag size="small">{{ row.targetTypeLabel }}</el-tag>
              <span class="delreq-target" :data-test="`delreq-target-${row.id}`">{{ row.targetSummary }}</span>
            </template>
            <template v-else-if="col.key === 'status'">
              <el-tag :type="statusTagType(row.status)" size="small">{{ row.statusLabel }}</el-tag>
            </template>
            <template v-else-if="col.key === 'rejectReason'">{{ row.rejectReason || '—' }}</template>
            <template v-else-if="col.key === 'createdAt'"><TimeCell :iso="row.createdAt" /></template>
            <template v-else-if="col.key === 'handledAt'">
              <TimeCell v-if="row.handledAt" :iso="row.handledAt" />
              <span v-else>—</span>
            </template>
            <template v-else>{{ row.reason }}</template>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.action')" width="170" fixed="right">
          <template #default="{ row }">
            <RowActions
              v-if="canApprove && row.status === 2"
              :actions="[{ key: 'approve', label: t('deleteRequest.approve'), type: 'success' }, { key: 'reject', label: t('deleteRequest.reject'), type: 'danger' }]"
              :max="2"
              @command="(k: string) => onRowCmd(k, row)"
            />
            <span v-else class="text-secondary">{{ row.status === 2 ? t('deleteRequest.waitingHandler') : t('deleteRequest.handled') }}</span>
          </template>
        </el-table-column>
        <template #empty>
          <EmptyState
            data-test="delreq-empty"
            :filtered="statusFilter !== null"
            :title="statusFilter !== null ? t('deleteRequest.emptyFiltered') : t('deleteRequest.emptyAll')"
            :hint="t('deleteRequest.emptyHint')"
          />
        </template>
      </el-table>

      <div class="pagination-bar">
        <el-pagination
          v-if="total > 0"
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          data-test="delreq-pager"
          @current-change="load()"
          @size-change="load(1)"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">

import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import ColumnSettings from '@/components/ColumnSettings.vue'
import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'
import { bizErrorMsg } from '@/utils/bizError'
import {
  approveDeleteRequest, listDeleteRequests, rejectDeleteRequest,
  type DeleteRequestItem,
} from '@/api/deleteRequest'

const { t } = useI18n()

const auth = useAuthStore()

const { projectId, followTick } = useProjectPageContext()
const statusFilter = ref<number | null>(2)
const list = ref<DeleteRequestItem[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)

// Server-side sort state : the whitelisted core key
// pair forwarded into every list request; empty = backend default id DESC.
const query = reactive<{ sortBy: string; sortDir: string }>({ sortBy: '', sortDir: '' })

// Column prop -> core delreq SORT_COLUMNS whitelist key (single source for
// the sortable decision and the request payload). Sorting happens server-side
// over the whole filtered project window, not the loaded pageful; reason and
// reject_reason render no sortable column (free text), so the map carries
// exactly the ranked columns and no dead entries.
const SORT_KEY_MAP: Record<string, string> = {
  targetSummary: 'target_summary',
  status: 'status',
  createdAt: 'created_at',
  handledAt: 'handled_at',
}

/** Configurable data columns of the ledger table (the locked action column stays outside). */
const DELREQ_COLUMNS: ColumnDef[] = [
  { key: 'targetSummary', labelKey: 'deleteRequest.colTarget', width: null },
  { key: 'reason', labelKey: 'deleteRequest.colReason', width: null },
  { key: 'status', labelKey: 'common.status', width: 100 },
  { key: 'rejectReason', labelKey: 'deleteRequest.colRejectReason', width: null },
  { key: 'createdAt', labelKey: 'deleteRequest.colCreatedAt', width: 150 },
  { key: 'handledAt', labelKey: 'deleteRequest.colHandledAt', width: 150 },
]
/** Flexible columns fall back to min-width when no explicit width is set. */
const DELREQ_FLEX_MIN: Record<string, number> = { targetSummary: 220, reason: 200, rejectReason: 150 }
const colCfg = useColumnConfig('delreq', DELREQ_COLUMNS)
/** Popover rows (column state merged with labelKey for rendering). */
const settingsRows = computed(() =>
  colCfg.state.columns.map((c) => ({ ...c, labelKey: DELREQ_COLUMNS.find((d) => d.key === c.key)!.labelKey })),
)

/** Header sort: forward the whitelisted key to core; clearing a sort falls
 * back to the historical id DESC default order; page resets to 1. */
function onSortChange({ prop, order }: { prop: string; order: string | null }): void {
  page.value = 1
  if (order === null) {
    query.sortBy = ''
    query.sortDir = ''
  } else {
    const key = SORT_KEY_MAP[prop]
    if (key) {
      query.sortBy = key
      query.sortDir = order === 'ascending' ? 'asc' : 'desc'
    }
  }
  void load()
}

const projects = computed(() => auth.projects)


const canApprove = computed(() => {
  if (auth.isAdmin) return true
  const p = auth.projects.find((x) => x.projectId === projectId.value)
  return p?.role === 2
})

function statusTagType(status: number): 'warning' | 'success' | 'danger' {
  if (status === 3) return 'success'
  if (status === 4) return 'danger'
  return 'warning'
}

async function load(p?: number): Promise<void> {
  if (!projectId.value) return
  if (p) page.value = p
  loading.value = true
  try {
    const r = await listDeleteRequests({
      projectId: projectId.value,
      status: statusFilter.value ?? undefined,
      page: page.value,
      size: size.value,
      sortBy: query.sortBy || undefined,
      sortDir: query.sortDir || undefined,
    })
    list.value = r.list
    total.value = Number(r.total)
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('deleteRequest.loadFail')))
  } finally {
    loading.value = false
  }
}

function onRowCmd(key: string, row: DeleteRequestItem): void {
  if (key === 'approve') void onApprove(row)
  else if (key === 'reject') void onReject(row)
}

async function onApprove(row: DeleteRequestItem): Promise<void> {
  try {
    await ElMessageBox.confirm(
      t('deleteRequest.approveConfirm', { target: row.targetSummary }),
      t('deleteRequest.approveTitle'),
      { confirmButtonText: t('deleteRequest.approve'), cancelButtonText: t('common.cancel'), type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await approveDeleteRequest(row.id)
    ElMessage.success(t('deleteRequest.approveOk'))
    await load()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('deleteRequest.approveFail')))
  }
}

async function onReject(row: DeleteRequestItem): Promise<void> {
  let reason: string
  try {
    const r = await ElMessageBox.prompt(
      t('deleteRequest.rejectPrompt', { target: row.targetSummary }),
      t('deleteRequest.rejectTitle'),
      {
        confirmButtonText: t('deleteRequest.reject'),
        cancelButtonText: t('common.cancel'),
        inputPlaceholder: t('deleteRequest.rejectReasonPlaceholder'),
        inputValidator: (v: string) => (v && v.trim() ? true : t('deleteRequest.rejectReasonRequired')),
      },
    )
    reason = (r.value || '').trim()
  } catch {
    return
  }
  try {
    await rejectDeleteRequest(row.id, reason)
    ElMessage.success(t('deleteRequest.rejectOk'))
    await load()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('deleteRequest.rejectFail')))
  }
}


onMounted(() => {
  if (projectId.value) void load(1)
})

watch(followTick, () => {
  if (projectId.value) void load(1)
})
</script>

<style scoped>
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 16px;
}
.toolbar-left {
  display: flex;
  align-items: baseline;
  gap: 10px;
}
.toolbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
}
.delreq-target {
  margin-left: 6px;
}
.text-secondary {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
