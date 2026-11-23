<!-- tp-owner-echo-exempt: the generate dialog's project field is the report
     SCOPE itself (project-scope vs global-scope radio, admin-only entry) — an
     explicit by-contract selector, not a second channel over header-context
     ownership (same family as the skill/tool exemption). Opening the dialog
     pre-fills scopeId from the effective context when present. -->
<template>
  <div class="page-root" data-test="report-center-page">
    <!-- Embedded mode : hosted by the reports page export tab —
 the host card already frames the content, so the inner page-card
 drops (no frame-in-frame). Standalone class kept for the redirect
 era tooling (E2E anchors on rc-* survive either way). -->
    <div :class="embedded ? 'report-center-embedded' : 'page-card'">
      <div class="toolbar">
        <div class="toolbar-right">
          <el-select v-model="filters.reportType" clearable :placeholder="t('reportCenter.reportType')" style="width: 120px" data-test="rc-type" @change="reload">
            <el-option :label="t('reportCenter.weekly')" :value="1" />
            <el-option :label="t('reportCenter.monthly')" :value="2" />
          </el-select>
          <el-select v-if="authStore.isAdmin" v-model="filters.scopeType" clearable :placeholder="t('reportCenter.scopeType')" style="width: 120px" data-test="rc-scope-type" @change="reload">
            <el-option :label="t('reportCenter.projectScope')" :value="1" />
            <el-option :label="t('reportCenter.globalScope')" :value="2" />
            <el-option :label="t('reportCenter.programScope')" :value="3" />
          </el-select>
          <el-date-picker
            v-model="periodRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            :start-placeholder="t('reportCenter.periodFrom')"
            :end-placeholder="t('reportCenter.periodTo')"
            style="width: 240px"
            :id="['rc-period-start', 'rc-period-end']"
            data-test="rc-period"
            @change="reload"
          />
          <el-button v-if="authStore.isAdmin" type="primary" data-test="rc-generate-open" @click="openGenerate">
            {{ t('reportCenter.generate') }}
          </el-button>
        </div>
      </div>

      <TableSkeleton v-if="loading" :cols="7" data-test="reportcenterview-skeleton" />
      <!-- Main ledger table: header sort is server-side —
 sortable columns carry a SORT_KEY_MAP key and the ORDER BY happens in
 core SQL over the whole filtered window. The scope column stays
 honestly unsorted: it renders a project NAME, so ordering by
 scope_id would sort by something the reader does not see. -->
      <el-table border v-else :data="rows" data-test="rc-table" @sort-change="onSortChange">
        <el-table-column prop="reportType" :label="t('reportCenter.reportType')" width="120"
          sortable="custom">
          <template #default="{ row }">{{ row.reportType === 2 ? t('reportCenter.monthly') : t('reportCenter.weekly') }}</template>
        </el-table-column>
        <el-table-column :label="t('reportCenter.scope')" min-width="160">
          <template #default="{ row }">
            <span data-test="rc-row-scope">{{ scopeLabel(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="periodStart" :label="t('reportCenter.period')" min-width="200"
          sortable="custom">
          <template #default="{ row }">{{ fmtDate(row.periodStart) }} ~ {{ fmtDate(row.periodEnd) }}</template>
        </el-table-column>
        <el-table-column prop="generatedAt" :label="t('reportCenter.generatedAt')" width="160"
          sortable="custom">
          <template #default="{ row }">{{ fmtTime(row.generatedAt) }}</template>
        </el-table-column>
        <el-table-column :label="t('common.action')" width="260">
          <template #default="{ row }">
            <el-button size="small" type="primary" link data-test="rc-detail-btn" @click="openDetail(row.id)">
              {{ t('reportCenter.detail') }}
            </el-button>
            <el-button size="small" data-test="rc-export-csv" @click="doExport(row.id, 'csv')">CSV</el-button>
            <el-button size="small" data-test="rc-export-pdf" @click="doExport(row.id, 'pdf')">PDF</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <EmptyState :title="reportEmptyCopy.title" :hint="reportEmptyCopy.hint || t('reportCenter.periodHint')" />
        </template>
      </el-table>

      <div class="pagination-bar">
        <el-pagination
          v-if="total > 0"
          v-model:current-page="page"
          :page-size="size"
          :total="total"
          layout="total, prev, pager, next"
          data-test="rc-pagination"
          @current-change="reload"
        />
      </div>
    </div>

 
    <el-drawer v-model="detailVisible" size="640px" data-test="rc-detail-drawer" :title="t('reportCenter.detailTitle')">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small" class="detail-meta" data-test="rc-detail-meta">
          <el-descriptions-item :label="t('reportCenter.reportType')">{{ detail.reportType === 2 ? t('reportCenter.monthly') : t('reportCenter.weekly') }}</el-descriptions-item>
          <el-descriptions-item :label="t('reportCenter.scope')">{{ scopeLabel(detail) }}</el-descriptions-item>
          <el-descriptions-item :label="t('reportCenter.period')">{{ fmtDate(detail.periodStart) }} ~ {{ fmtDate(detail.periodEnd) }}</el-descriptions-item>
          <el-descriptions-item :label="t('reportCenter.generatedAt')">{{ fmtTime(detail.generatedAt) }}</el-descriptions-item>
        </el-descriptions>
        <div class="detail-title">{{ t('reportCenter.caliberBlock') }}</div>
        <el-table border :data="caliberRows" size="small" data-test="rc-caliber-table">
          <el-table-column :label="t('reportCenter.caliber')" min-width="170">
            <template #default="{ row }">
 
              <CaliberTooltip :code="row.code" :override-text="row.snapshotHover">{{ row.displayName }}</CaliberTooltip>
            </template>
          </el-table-column>
          <el-table-column :label="t('reportCenter.version')" width="80">
            <template #default="{ row }"><span data-test="rc-caliber-version">{{ row.version }}</span></template>
          </el-table-column>
          <el-table-column :label="t('reportCenter.value')" min-width="120" align="right">
            <template #default="{ row }"><span data-test="rc-caliber-value">{{ row.valueText }}</span></template>
          </el-table-column>
        </el-table>
        <template v-if="distributionRows.length > 0">
          <div class="detail-title">{{ t('reportCenter.distributionBlock') }}</div>
          <el-table border :data="distributionRows" size="small" data-test="rc-dist-table">
            <el-table-column :label="t('reportCenter.caliber')" min-width="140">
              <template #default="{ row }">
                <CaliberTooltip :code="row.code" :override-text="row.snapshotHover">{{ row.displayName }}</CaliberTooltip>
              </template>
            </el-table-column>
            <el-table-column prop="bucket" :label="t('reportCenter.rejectTypeBucket')" min-width="140" show-overflow-tooltip />
            <el-table-column prop="count" :label="t('reportCenter.count')" width="90" align="right" />
          </el-table>
        </template>
        
        <div class="detail-title">{{ t('reportCenter.aiQualityBlock') }}</div>
        <el-descriptions :column="1" border size="small" data-test="rc-ai-quality">
          <el-descriptions-item :label="t('reportCenter.fixAdoptRate')">
            <CaliberTooltip :code="'fix-adopt-rate'" :override-text="snapshotHover('fix-adopt-rate')">
              <span data-test="rc-fix-adopt">{{ scalarText('fix-adopt-rate') }}</span>
            </CaliberTooltip>
          </el-descriptions-item>
          <el-descriptions-item :label="t('reportCenter.rejectTypeDist')">
            <span data-test="rc-reject-type-summary">{{ rejectTypeSummary }}</span>
          </el-descriptions-item>
        </el-descriptions>
      </template>
    </el-drawer>

 
    <el-dialog v-model="genVisible" :title="t('reportCenter.generateTitle')" width="480px" data-test="rc-generate-dialog" draggable>
      <el-form label-width="90px">
        <el-form-item :label="t('reportCenter.reportType')">
          <!-- id="" prevents the form label for= from targeting the non-labelable radiogroup div -->
          <el-radio-group id="" v-model="genForm.reportType" data-test="rc-gen-type">
            <el-radio :value="1">{{ t('reportCenter.weekly') }}</el-radio>
            <el-radio :value="2">{{ t('reportCenter.monthly') }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="t('reportCenter.scope')">
          <!-- id="" prevents the form label for= from targeting the non-labelable radiogroup div -->
          <el-radio-group id="" v-model="genForm.scopeType" data-test="rc-gen-scope-type" @change="onGenScopeTypeChange">
            <el-radio :value="1">{{ t('reportCenter.projectScope') }}</el-radio>
            <el-radio :value="2">{{ t('reportCenter.globalScope') }}</el-radio>
            <el-radio :value="3">{{ t('reportCenter.programScope') }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="genForm.scopeType === 1" :label="t('reportCenter.project')">
          <ProjectSelect
            v-model="genForm.scopeId"
            :placeholder="t('reportCenter.selectProject')"
            width="100%"
            data-test="rc-gen-project"
          />
        </el-form-item>
        <el-form-item v-if="genForm.scopeType === 3" :label="t('reportCenter.programScope')">
          <el-select
            v-model="genForm.scopeId"
            filterable
            :placeholder="t('reportCenter.selectProgram')"
            style="width: 100%"
            data-test="rc-gen-program"
          >
            <el-option v-for="p in programOptions" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('reportCenter.period')">
          <el-date-picker
            v-model="genForm.period"
            type="daterange"
            value-format="YYYY-MM-DD"
            :start-placeholder="t('reportCenter.periodFrom')"
            :end-placeholder="t('reportCenter.periodTo')"
            style="width: 100%"
            :id="['rc-gen-period-start', 'rc-gen-period-end']"
            data-test="rc-gen-period"
          />
          <div class="form-hint">{{ t('reportCenter.periodHint') }}</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="genVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="generating" data-test="rc-generate-submit" @click="submitGenerate">
          {{ t('reportCenter.generateSubmit') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">

import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import ProjectSelect from '@/components/ProjectSelect.vue'
import CaliberTooltip from '@/components/CaliberTooltip.vue'
import { useAuthStore } from '@/stores/auth'
import { useProjectContextStore } from '@/stores/projectContext'
import { lensEmptyCopy, programIdParam, watchProgramLens } from '@/composables/useProgramLens'
import { listPrograms } from '@/api/program'
import { BizError } from '@/api/request'
import { getProjects } from '@/api/project'
import {
  listReportCenter,
  getReportCenterDetail,
  generateReportCenter,
  downloadReportCenterFile,
  parseReportPayload,
  type ReportCenterListItem,
  type ReportCenterDetail,
  type ReportPayload,
} from '@/api/reportCenter'
import { getCalibers } from '@/api/report'

const { t } = useI18n()
// Embedded mode : rendered inside the reports page tab, which
// already provides the framing card; standalone route usage keeps its own.
const props = withDefaults(defineProps<{ embedded?: boolean }>(), { embedded: false })
const embedded = computed(() => props.embedded)
const authStore = useAuthStore()
const projectCtxStore = useProjectContextStore()
if (authStore.user?.userId != null && projectCtxStore.selectedIds.length === 0) projectCtxStore.loadForUser()

const rows = ref<ReportCenterListItem[]>([])
const total = ref(0)
const page = ref(1)
const size = 20
const loading = ref(false)
const filters = ref<{ reportType?: number | null; scopeType?: number | null }>({})
const periodRange = ref<[string, string] | null>(null)

// Server-side sort state : the whitelisted core key
// pair forwarded into every list request; empty = backend default order.
const query = reactive<{ sortBy: string; sortDir: 'asc' | 'desc' | '' }>({ sortBy: '', sortDir: '' })

// Column prop -> core report-center SORT_COLUMNS whitelist key (single source
// for the sortable decision and the request payload). Sorting happens in SQL
// over the whole filtered window, not the loaded pageful. The scope column is
// deliberately absent: it renders a project name while the column is an id —
// ranking by an invisible key would mislead the reader.
const SORT_KEY_MAP: Record<string, string> = {
  reportType: 'report_type',
  periodStart: 'period_start',
  generatedAt: 'generated_at',
}

/** Header sort: forward the whitelisted key to core; clearing a sort falls
 * back to the historical period_start DESC default order; page resets to 1. */
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
  void reload()
}


// R30: the report scope follows the header global context — no page-level
// project picker. Cleared header: ADMIN sees the all-projects view, members
// get nothing to fetch (their list would 403 anyway; honest empty).
const effectiveScopeId = computed<number | null | undefined>(() => {
  const pid = projectCtxStore.primaryId
  if (pid != null) return pid
  return authStore.isAdmin ? undefined : null
})

async function reload(): Promise<void> {
  if (!authStore.isAdmin && !effectiveScopeId.value && programIdParam() == null) {
    rows.value = []
    total.value = 0
    return
  }
  loading.value = true
  try {
    const data = await listReportCenter({
      page: page.value,
      size,
      reportType: filters.value.reportType ?? undefined,
      scopeType: filters.value.scopeType ?? undefined,
      scopeId: effectiveScopeId.value ?? undefined,
      periodFrom: periodRange.value?.[0] ? `${periodRange.value[0]}T00:00` : undefined,
      periodTo: periodRange.value?.[1] ? `${periodRange.value[1]}T00:00` : undefined,
      sortBy: query.sortBy || undefined,
      sortDir: query.sortDir ? (query.sortDir as 'asc' | 'desc') : undefined,
      // Header program lens : single-sourced read, absent when the lens
      // is off so the request keeps its pre-feature shape (legacy zero drift).
      programId: programIdParam(),
    })
    rows.value = data.list || []
    total.value = data.total || 0
  } catch (e: any) {
    ElMessage.error(e instanceof BizError ? e.message : t('reportCenter.loadFailed'))
  } finally {
    loading.value = false
  }
}

onMounted(reload)
watch(() => projectCtxStore.primaryId, () => void reload())
// Header lens flip (pick / switch / detach) → refetch the narrowed ledger,
// resetting to page 1 like the project-header follow does (useProgramLens is
// the single source for this posture across all lens pages, R40).
watchProgramLens(() => {
  page.value = 1
  void reload()
})

// Empty-state copy : lens ON → the ledger line names the
// program segment and how to leave it; lens OFF keeps the legacy line.
const reportEmptyCopy = computed(() => lensEmptyCopy({
  translate: t,
  titleKey: 'reportCenter.lensEmptyTitle',
  emptyKey: 'reportCenter.emptyNoReport',
}))

function projectName(id: number): string {
  return projectNameCache.value[id] ?? authStore.projects.find((p) => p.projectId === id)?.projectName ?? `#${id}`
}


const projectNameCache = ref<Record<number, string>>({})
getProjects({ page: 1, size: 200 })
  .then((pageData) => {
    projectNameCache.value = Object.fromEntries((pageData.list || []).map((p) => [p.id, p.name]))
  })
  .catch(() => {  })


const programNameCache = ref<Record<number, string>>({})
const programOptions = ref<{ id: number; name: string }[]>([])
listPrograms()
  .then((rowsData) => {
    programOptions.value = rowsData.map((p) => ({ id: p.id, name: p.name }))
    programNameCache.value = Object.fromEntries(rowsData.map((p) => [p.id, p.name]))
  })
  .catch(() => {  })

function scopeLabel(row: { scopeType: number; scopeId: number }): string {
  if (row.scopeType === 2) return t('reportCenter.globalScope')
  if (row.scopeType === 3) return programNameCache.value[row.scopeId] ?? `#${row.scopeId}`
  return projectName(row.scopeId)
}

function fmtDate(iso: string): string {
  return (iso || '').slice(0, 10)
}

function fmtTime(iso: string): string {
  return (iso || '').replace('T', ' ').slice(0, 19)
}

const detailVisible = ref(false)
const detail = ref<ReportCenterDetail | null>(null)
const payload = ref<ReportPayload | null>(null)

async function openDetail(id: number): Promise<void> {
  try {
    detail.value = await getReportCenterDetail(id)
    payload.value = parseReportPayload(detail.value.payloadJson)
    detailVisible.value = true
  } catch (e: any) {
    ElMessage.error(e instanceof BizError ? e.message : t('reportCenter.detailFailed'))
  }
}


const caliberRows = computed(() => {
  const calibers = payload.value?.calibers || {}
  return Object.entries(calibers)
    .filter(([, block]) => block.rows === undefined)
    .map(([code, block]) => ({
      code,
      displayName: block.display_name,
      version: block.version,
      snapshotHover: hoverOf(block),
      valueText: scalarText(code),
    }))
})


const distributionRows = computed(() => {
  const out: { code: string; displayName: string; version: string; snapshotHover: string; bucket: string; count: string }[] = []
  const calibers = payload.value?.calibers || {}
  for (const [code, block] of Object.entries(calibers)) {
    for (const item of block.rows || []) {
      out.push({ code, displayName: block.display_name, version: block.version, snapshotHover: hoverOf(block), bucket: item.value, count: item.extra })
    }
  }
  return out
})


function hoverOf(block: { display_name: string; version: string }): string {
  return `${block.display_name} ${block.version}=${registryDescription(block.display_name)}`
}

function snapshotHover(code: string): string {
  const block = payload.value?.calibers?.[code]
  return block ? hoverOf(block) : ''
}


function scalarText(code: string): string {
  const block = payload.value?.calibers?.[code]
  if (!block) return '—'
  return block.value === null || block.value === undefined ? '—' : String(block.value)
}

const rejectTypeSummary = computed(() => {
  const items = distributionRows.value.filter((r) => r.code === 'feedback-reject-type')
  if (items.length === 0) return t('common.noData')
  return items.map((r) => `${r.bucket}×${r.count}`).join('、')
})

const registry = ref<Record<string, string>>({})
getCalibers()
  .then((list) => { registry.value = Object.fromEntries(list.map((c) => [c.displayName, c.description])) })
  .catch(() => {  })

function registryDescription(displayName: string): string {
  return registry.value[displayName] || ''
}

async function doExport(id: number, format: 'csv' | 'pdf'): Promise<void> {
  try {
    await downloadReportCenterFile(id, format)
  } catch (e: any) {
    ElMessage.error(e instanceof BizError ? e.message : t('reportCenter.exportFailed'))
  }
}

const genVisible = ref(false)
const generating = ref(false)
const genForm = ref<{ reportType: number; scopeType: number; scopeId: number | null; period: [string, string] | null }>(
  { reportType: 1, scopeType: 1, scopeId: null, period: null },
)

function openGenerate(): void {
  genForm.value = { reportType: 1, scopeType: 1, scopeId: effectiveScopeId.value ?? null, period: null }
  genVisible.value = true
}


function onGenScopeTypeChange(): void {
  genForm.value.scopeId = genForm.value.scopeType === 1 ? (effectiveScopeId.value ?? null) : null
}

async function submitGenerate(): Promise<void> {
  if (!genForm.value.period) {
    ElMessage.warning(t('reportCenter.periodRequired'))
    return
  }
  generating.value = true
  try {
    const v = await generateReportCenter({
      reportType: genForm.value.reportType,
      scopeType: genForm.value.scopeType,
      scopeId: genForm.value.scopeType === 2 ? 0 : genForm.value.scopeId,
      periodStart: `${genForm.value.period[0]}T00:00`,
      periodEnd: nextDay(genForm.value.period[1]),
    })
    ElMessage.success(t('reportCenter.generateDone', { id: v.id }))
    genVisible.value = false
    await reload()
  } catch (e: any) {
    ElMessage.error(e instanceof BizError ? e.message : t('reportCenter.generateFailed'))
  } finally {
    generating.value = false
  }
}

function nextDay(dateStr: string): string {
  const d = new Date(`${dateStr}T00:00:00`)
  d.setDate(d.getDate() + 1)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}T00:00`
}
</script>

<style scoped>
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 16px;
}
.toolbar-left {
  display: flex;
  flex-direction: column;
}
.toolbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.page-title {
  font-size: 18px;
}
.page-subtitle {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
}
.detail-meta {
  margin-bottom: 16px;
}
.detail-title {
  margin: 12px 0 8px;
  font-weight: 600;
}
.form-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}
</style>
