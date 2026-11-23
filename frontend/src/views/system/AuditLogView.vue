<template>
  <div class="page-root" data-test="audit-page">
    <div class="page-card">
      <div class="toolbar">
        <div class="toolbar-right">
          <el-select
            v-model="filters.objectType"
            :placeholder="t('audit.phObjectType')"
            clearable
            filterable
            style="width: 170px"
            data-test="audit-object-type"
            @change="load(1)"
          >
            <el-option v-for="opt in AUDIT_OBJECT_TYPES" :key="opt" :label="t('audit.obj.' + opt)" :value="opt" />
          </el-select>
          <el-input
            v-model="filters.action"
            :placeholder="t('audit.phAction')"
            clearable
            style="width: 160px"
            data-test="audit-action"
            @keyup.enter="load(1)"
            @clear="load(1)"
          />
          <el-input
            v-model="filters.objectId"
            :placeholder="t('audit.phObjectId')"
            clearable
            style="width: 130px"
            data-test="audit-object-id"
            @keyup.enter="load(1)"
            @clear="load(1)"
          />
          <el-button type="primary" data-test="audit-search" @click="load(1)">
            <el-icon><Search /></el-icon>{{ t('audit.search') }}
          </el-button>
          <el-button data-test="audit-export" @click="exportCsv">
            <el-icon><Download /></el-icon>{{ t('audit.exportCsv') }}
          </el-button>
          <el-button
            type="warning"
            plain
            :loading="verifying"
            data-test="audit-chain-verify"
            @click="runChainVerify"
          >
            <el-icon><CircleCheck /></el-icon>{{ t('audit.chainVerify') }}
          </el-button>
          <!-- shared column-settings popover (single piece, no fork) -->
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

 
      <el-alert
        v-if="verifyBanner === 'ok'"
        type="success"
        show-icon
        :closable="false"
        class="verify-banner"
        data-test="audit-verify-ok"
        :title="t('audit.verifyOk', { n: verifyResult?.checkedCount ?? 0, at: verifyResult?.checkedAt ?? '' })"
      />
      <el-alert
        v-else-if="verifyBanner === 'broken'"
        type="error"
        show-icon
        :closable="false"
        class="verify-banner"
        data-test="audit-verify-broken"
        :title="t('audit.verifyBroken', { id: verifyResult?.brokenAtId ?? '?' })"
      />
      <el-alert
        v-else-if="verifyBanner === 'running'"
        type="info"
        show-icon
        :closable="false"
        class="verify-banner"
        data-test="audit-verify-running"
        :title="t('audit.verifyRunning')"
      />
      <el-alert
        v-else-if="verifyBanner === 'failed'"
        type="error"
        show-icon
        :closable="false"
        class="verify-banner"
        data-test="audit-verify-failed"
        :title="t('audit.verifyFailed')"
      />

      <TableSkeleton v-if="loading" :cols="7" data-test="auditlogview-skeleton" />
      <DataTablePage
        :data="list"
        data-test="audit-table"
        row-key="id"
        :size="colCfg.state.density"
        :column-config="colCfg"
        v-else
        class="audit-row-clickable"
        @row-click="openDetail"
        @sort-change="onSortChange"
      >
        <!-- configurable data columns (show/hide, order, width,
 density persist through the shared useColumnConfig piece). -->
        <el-table-column
          v-for="col in colCfg.visibleColumns.value"
          :key="col.key"
          :prop="col.key"
          :fixed="col.fixed || false"
          :label="t(col.labelKey)"
          :width="col.width ?? undefined"
          :min-width="col.width ? undefined : AUDIT_FLEX_MIN[col.key]"
          :sortable="isColSortable(col.key) ? 'custom' : false"
          :show-overflow-tooltip="col.key === 'objectId'"
          :data-test="`audit-col-${col.key}`"
        >
          <template #default="{ row }">
            <span v-if="col.key === 'operator'">{{ operatorName(row.operatorId) }}</span>
            <el-tag v-else-if="col.key === 'objectType'" size="small">{{ objectTypeLabel(row.objectType) }}</el-tag>
            <template v-else-if="col.key === 'auditAction'">
              <span>{{ auditActionLabel(row.action) }}</span>
              <code class="action-code">{{ row.action }}</code>
            </template>
            <span v-else>{{ row[col.key] }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.action')" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" data-test="audit-detail" @click="openDetail(row)">
              {{ t('audit.detail') }}
            </el-button>
          </template>
        </el-table-column>
      </DataTablePage>

      <div class="pagination-bar">
        <el-pagination v-if="total > 0"
          v-model:current-page="page"
          :page-size="size"
          :total="total"
          layout="total, prev, pager, next"
          data-test="audit-pager"
          @current-change="load"
          />
      </div>
    </div>

    
    <el-dialog draggable
      v-model="detailVisible"
      :title="t('audit.detailTitle', { id: detail?.id ?? '' })"
      width="800px"
      destroy-on-close
      data-test="audit-detail-dialog"
    >
      <template v-if="detail">
        <el-descriptions :column="2" border size="small" class="detail-meta">
          <el-descriptions-item :label="t('audit.colOperator')">{{ operatorName(detail.operatorId) }}</el-descriptions-item>
          <el-descriptions-item :label="t('audit.colCreatedAt')">{{ detail.createdAt }}</el-descriptions-item>
          <el-descriptions-item :label="t('audit.colObjectType')">{{ objectTypeLabel(detail.objectType) }}</el-descriptions-item>
          <el-descriptions-item :label="t('audit.colAction')">
            {{ auditActionLabel(detail.action) }}
            <code class="action-code">{{ detail.action }}</code>
          </el-descriptions-item>
          <el-descriptions-item :label="t('audit.colObjectId')">{{ detail.objectId }}</el-descriptions-item>
        </el-descriptions>

        <h4 class="diff-title">{{ t('audit.diffTitle') }}</h4>
        
        <div v-if="noSnapshot" class="diff-empty-card" data-test="audit-diff-empty">
          <el-icon class="diff-empty-icon"><InfoFilled /></el-icon>
          <div class="diff-empty-text">
            <p class="diff-empty-lead">{{ t('audit.diffNoSnapshot') }}</p>
            <p class="diff-empty-summary">
              {{ auditActionLabel(detail.action) }}
              <code class="action-code">{{ detail.action }}</code>
              · {{ objectTypeLabel(detail.objectType) }} · {{ detail.objectId }}
            </p>
          </div>
        </div>
        <template v-else>
        <DataTablePage :data="diffRows" size="small" border data-test="audit-diff-table">
          <el-table-column :label="t('audit.colField')" min-width="140">
            <template #default="{ row }">
              <span :class="{ 'diff-changed': row.changed }">{{ row.field }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('audit.colBefore')" min-width="200">
            <template #default="{ row }">
              <span :class="{ 'diff-removed': row.changed && row.after === null }">{{ formatValue(row.before) }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('audit.colAfter')" min-width="200">
            <template #default="{ row }">
              <span :class="{ 'diff-added': row.changed && row.before === null }">{{ formatValue(row.after) }}</span>
            </template>
          </el-table-column>
        </DataTablePage>
        <el-empty v-if="diffRows.length === 0" :description="t('audit.diffEmpty')" :image-size="60" />
        </template>
      </template>
      <template #footer>
        <el-button @click="detailVisible = false">{{ t('common.close') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Search, Download, CircleCheck, InfoFilled } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import ColumnSettings from '@/components/ColumnSettings.vue'
import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'
import { buildAuditDiffRows, hasNoSnapshot, type AuditDiffRow } from '@/utils/auditDiff'
import {
  AUDIT_OBJECT_TYPES,
  auditActionLabel,
  getChainVerifyTask,
  listAuditLogs,
  verifyAuditChain,
  type AuditLogView,
  type ChainVerifyResult,
} from '@/api/audit'
import { listUsers } from '@/api/user'

const { t, te } = useI18n()

const list = ref<AuditLogView[]>([])
const total = ref(0)
const page = ref(1)
const size = 20
const loading = ref(false)

const filters = ref<{ objectType?: string; action?: string; objectId?: string }>({})

// Column key -> core audit SORT_COLUMNS whitelist key (single source for the
// sortable decision and the request payload; . Sorting happens
// server-side over the whole audit window, not the loaded pageful.
const SORT_KEY_MAP: Record<string, string> = {
  id: 'id',
  operator: 'operator_id',
  objectType: 'object_type',
  objectId: 'object_id',
  auditAction: 'action',
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

// ---------- column configuration (shared useColumnConfig piece) ----------
/** Configurable data columns (the fixed-right action column stays locked out). */
const AUDIT_COLUMNS: ColumnDef[] = [
  { key: 'id', labelKey: 'audit.colId', width: 80 },
  { key: 'operator', labelKey: 'audit.colOperator', width: null },
  { key: 'objectType', labelKey: 'audit.colObjectType', width: null },
  { key: 'objectId', labelKey: 'audit.colObjectId', width: null },
  { key: 'auditAction', labelKey: 'audit.colAction', width: null },
  { key: 'createdAt', labelKey: 'audit.colCreatedAt', width: 170 },
]
/** Flexible columns fall back to min-width when no explicit width is set. */
const AUDIT_FLEX_MIN: Record<string, number> = { operator: 120, objectType: 130, objectId: 100, auditAction: 150 }
const colCfg = useColumnConfig('audit', AUDIT_COLUMNS)
/** Popover rows (column state merged with labelKey for rendering). */
const settingsRows = computed(() =>
  colCfg.state.columns.map((c) => ({ ...c, labelKey: AUDIT_COLUMNS.find((d) => d.key === c.key)!.labelKey })),
)

const verifying = ref(false)
const verifyBanner = ref<'' | 'ok' | 'broken' | 'running' | 'failed'>('')
const verifyResult = ref<ChainVerifyResult | null>(null)
let pollTimer: ReturnType<typeof setTimeout> | null = null

function stopPoll() {
  if (pollTimer !== null) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
}

function applyVerifyResult(r: ChainVerifyResult) {
  verifyResult.value = r
  verifying.value = false
  if (r.status === 1) {
    verifyBanner.value = 'running'
    return
  }
  if (r.status === 3) {
    verifyBanner.value = 'failed'
    return
  }
  verifyBanner.value = r.valid ? 'ok' : 'broken'
}


function pollChainVerifyTask(taskId: number) {
  pollTimer = setTimeout(async () => {
    try {
      const r = await getChainVerifyTask(taskId)
      if (r.status === 1) {
        pollChainVerifyTask(taskId)
        return
      }
      applyVerifyResult(r)
    } catch (e) {
      verifying.value = false
      verifyBanner.value = 'failed'
      ElMessage.error((e as Error)?.message || t('audit.pollFailed'))
    }
  }, 2000)
}

async function runChainVerify() {
  if (verifying.value) return
  verifying.value = true
  stopPoll()
  try {
    const r = await verifyAuditChain()
    applyVerifyResult(r)
    if (r.async && r.status === 1) {
      pollChainVerifyTask(r.taskId)
    }
  } catch (e) {
    verifying.value = false
    ElMessage.error((e as Error)?.message || t('audit.verifyReqFailed'))
  }
}

onBeforeUnmount(stopPoll)


const userNames = ref<Record<number, string>>({})
async function loadUsers() {
  try {
    for (let p = 1; p <= 3; p++) {
      const r = await listUsers({ page: p, size: 200 })
      r.list.forEach((u) => {
        userNames.value[u.id] = u.displayName || u.username
      })
      if (r.list.length < 200) break
    }
  } catch {
  }
}

function operatorName(id: number | null): string {
  if (id == null) return t('audit.system')
  return userNames.value[id] || t('audit.userHash', { id })
}

function objectTypeLabel(type: string): string {
  const key = `audit.obj.${type}`
  return te(key) ? t(key) : type
}

async function load(p: number = page.value) {
  loading.value = true
  try {
    const r = await listAuditLogs({
      objectType: filters.value.objectType || undefined,
      action: filters.value.action?.trim() || undefined,
      objectId: filters.value.objectId?.trim() || undefined,
      page: p,
      size,
      sortBy: query.sortBy || undefined,
      sortDir: query.sortDir || undefined,
    })
    list.value = r.list
    total.value = r.total
    page.value = p
  } catch (e) {
    ElMessage.error((e as Error)?.message || t('audit.loadFailed'))
  } finally {
    loading.value = false
  }
}

const detailVisible = ref(false)
const detail = ref<AuditLogView | null>(null)

const noSnapshot = computed(() =>
  detail.value ? hasNoSnapshot(detail.value.beforeJson, detail.value.afterJson) : false)

const diffRows = computed<AuditDiffRow[]>(() => {
  const d = detail.value
  if (!d) return []
  return buildAuditDiffRows(d.beforeJson, d.afterJson, t('audit.snapshotField'))
})

function formatValue(v: unknown): string {
  if (v === null || v === undefined) return '—'
  if (typeof v === 'object') return JSON.stringify(v)
  return String(v)
}

function openDetail(row: AuditLogView) {
  detail.value = row
  detailVisible.value = true
}

async function exportCsv() {
  try {
    const sizeStep = 200
    const first = await listAuditLogs({
      objectType: filters.value.objectType || undefined,
      action: filters.value.action?.trim() || undefined,
      objectId: filters.value.objectId?.trim() || undefined,
      page: 1,
      size: sizeStep,
    })
    const cap = Math.min(first.total, 5000)
    const rows = [...first.list]
    for (let p = 2; rows.length < cap; p++) {
      const r = await listAuditLogs({
        objectType: filters.value.objectType || undefined,
        action: filters.value.action?.trim() || undefined,
        objectId: filters.value.objectId?.trim() || undefined,
        page: p,
        size: sizeStep,
      })
      rows.push(...r.list)
      if (r.list.length === 0) break
    }
    const esc = (v: unknown): string => {
      const s = v === null || v === undefined ? '' : String(v)
      return `"${s.replace(/"/g, '""')}"`
    }
    const header = ['ID', t('audit.csvOperator'), t('audit.csvObjectType'), t('audit.csvObjectId'), t('audit.csvAction'), t('audit.csvCreatedAt'), t('audit.csvBefore'), t('audit.csvAfter')]
    const lines = [header.join(',')]
    for (const row of rows) {
      lines.push(
        [
          row.id,
          operatorName(row.operatorId),
          objectTypeLabel(row.objectType),
          row.objectId,
          row.action,
          row.createdAt,
          row.beforeJson ?? '',
          row.afterJson ?? '',
        ]
          .map(esc)
          .join(','),
      )
    }
    const blob = new Blob(['\uFEFF' + lines.join('\n')], { type: 'text/csv;charset=utf-8;' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${t('audit.csvFileName')}_${new Date().toISOString().slice(0, 10)}.csv`
    a.click()
    URL.revokeObjectURL(url)
    ElMessage.success(t('audit.exported', { n: rows.length }))
  } catch (e) {
    ElMessage.error((e as Error)?.message || t('audit.exportFailed'))
  }
}

onMounted(() => {
  loadUsers()
  load(1)
})
</script>

<style scoped>
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}
.toolbar-left {
  display: flex;
  align-items: center;
  gap: 10px;
}
.toolbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.page-subtitle {
  font-size: 12px;
  opacity: 0.65;
}
.action-code {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
  background: rgba(125, 125, 125, 0.12);
  padding: 2px 6px;
  border-radius: 4px;
}
.detail-meta {
  margin-bottom: 12px;
}
.diff-title {
  margin: 6px 0 10px;
  font-size: 14px;
}
.diff-changed {
  font-weight: 600;
  color: var(--el-color-warning);
}
.diff-removed {
  color: var(--el-color-danger);
}
.diff-added {
  color: var(--el-color-success);
}
.verify-banner {
  margin-bottom: 12px;
}

.audit-row-clickable :deep(.el-table__body tr) {
  cursor: pointer;
}
.diff-empty-card {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 16px;
  border: 1px dashed var(--el-border-color);
  border-radius: 8px;
  background: var(--el-fill-color-lighter);
}
.diff-empty-icon {
  font-size: 20px;
  color: var(--el-color-info);
  margin-top: 2px;
}
.diff-empty-lead {
  margin: 0 0 4px;
  font-size: 13px;
}
.diff-empty-summary {
  margin: 0;
  font-size: 12px;
  opacity: 0.75;
}
</style>
