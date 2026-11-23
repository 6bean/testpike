<template>
  <div class="page-root test-report-page" data-test="test-report-page">
    <div class="page-card">
      <div class="page-header">
        <div class="header-actions">
          <el-tooltip :content="t('components.ownerEcho.pickInHeader')" placement="bottom" :disabled="!!projectId">
            <span>
              <el-button type="primary" :icon="Plus" :disabled="!projectId" data-test="report-generate-btn" @click="openGenerate">
                {{ t('testReport.generate') }}
              </el-button>
            </span>
          </el-tooltip>
        </div>
      </div>

      <!-- IA fix: screening lives in the shared filter-bar row; the
 project rides the global header context (page selector removed). -->
      <div class="filter-bar" data-test="test-report-filter-bar">
          <el-input
            v-model="filters.keyword"
            :placeholder="t('testReport.keywordPlaceholder')"
            clearable
            style="width: 200px"
            data-test="report-filter-keyword"
            @keyup.enter="load(1)"
            @clear="load(1)"
          />
          <div class="filter-bar-actions">
            <!-- migration batch (R21 bands): column settings is a view-state action —
 moved out of the header band into the trailing slot. -->
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

      <div v-if="!loading && projects.length === 0" class="empty-box" data-test="report-empty-no-project">
        <el-empty :description="t('testReport.noProject')" />
      </div>

      <TableSkeleton v-else-if="loading" :cols="9" data-test="testreportlistview-skeleton" />
      <!-- Main ledger table: header sort is server-side —
 sortable columns come from the one SORT_KEY_MAP below and the ORDER BY
 happens in core SQL over the whole filtered project window. rangeLabel
 stays honestly unsorted: it is a composed label string, not a column. -->
      <DataTablePage v-else :data="rows" data-test="report-table" class="data-table" row-key="id" :size="colCfg.state.density" :column-config="colCfg"
        @sort-change="onSortChange">
        <!-- configurable data columns (show/hide, order, width,
 density persist through the shared useColumnConfig piece). -->
        <el-table-column
          v-for="col in colCfg.visibleColumns.value"
          :key="col.key"
          :prop="col.key"
          :fixed="col.fixed || false"
          :label="t(col.labelKey)"
          :width="col.width ?? undefined"
          :min-width="col.width ? undefined : REPORT_FLEX_MIN[col.key]"
          :show-overflow-tooltip="col.key === 'title' || col.key === 'rangeLabel'"
          :align="col.key === 'rangeType' ? 'center' : undefined"
          :sortable="isColSortable(col.key) ? 'custom' : false"
          :data-test="`report-col-${col.key}`"
        >
          <template #default="{ row }">
            <el-link v-if="col.key === 'title'" type="primary" data-test="report-title-link" @click="openDetail(row.id)">
              {{ row.title }}
            </el-link>
            <el-tag v-else-if="col.key === 'rangeType'" :type="row.rangeType === 1 ? 'success' : 'info'" size="small">
              {{ row.rangeType === 1 ? t('testReport.byPlan') : t('testReport.byRange') }}
            </el-tag>
            <TimeCell v-else-if="col.key === 'createdAt'" :iso="row.createdAt" />
            <span v-else>{{ row[col.key] }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('testReport.operation')" width="220" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link data-test="report-detail-btn" @click="openDetail(row.id)">
              {{ t('testReport.detail') }}
            </el-button>
            <el-button size="small" data-test="report-export-xlsx" @click="doExport(row.id, 'xlsx')">
              {{ t('testReport.exportXlsx') }}
            </el-button>
            <el-button size="small" data-test="report-export-pdf" @click="doExport(row.id, 'pdf')">
              {{ t('testReport.exportPdf') }}
            </el-button>
          </template>
        </el-table-column>
    
        <template #empty>
          <el-empty :description="t('common.emptyNoData')">
            <el-button type="primary" plain data-test="report-empty-create" @click="openGenerate">{{ t('testReport.generate') }}</el-button>
          </el-empty>
        </template>
      </DataTablePage>

      <div class="pagination-bar">
        <el-pagination v-if="total > 0"
          v-model:current-page="page"
          :page-size="size"
          :total="total"
          layout="total, prev, pager, next"
          data-test="report-pagination"
          @current-change="load"
            />
      </div>

      
      <el-dialog draggable v-model="genVisible" :title="t('testReport.generate')" width="640px" data-test="report-gen-dialog">
        <!-- ownership echo (iron rule 1): the generated report belongs to the
             header-context project; read-only, single-sourced -->
        <ProjectOwnerEcho />
        <el-form label-width="96px">
          <el-form-item :label="t('testReport.rangeType')">
            <!-- id="" prevents the form label for= from targeting the non-labelable radiogroup div -->
            <el-radio-group id="" v-model="genForm.rangeType" data-test="report-gen-type">
              <el-radio :value="1">{{ t('testReport.byPlan') }}</el-radio>
              <el-radio :value="2">{{ t('testReport.byRange') }}</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item v-if="genForm.rangeType === 1" :label="t('testReport.plan')">
            <el-select
              v-model="genForm.planId"
              filterable
              style="width: 100%"
              :placeholder="t('testReport.selectPlan')"
              data-test="report-gen-plan"
            >
              <el-option v-for="p in planOptions" :key="p.id" :label="`${p.name}（${p.statusLabel}）`" :value="p.id" />
            </el-select>
          </el-form-item>
          <el-form-item v-else :label="t('testReport.timeRange')">
            <el-date-picker
              v-model="genForm.range"
              type="daterange"
              value-format="YYYY-MM-DD"
              :start-placeholder="t('common.startDate')"
              :end-placeholder="t('common.endDate')"
              style="width: 100%"
              :id="['report-gen-range-start', 'report-gen-range-end']"
              data-test="report-gen-range"
            />
          </el-form-item>
          <el-form-item :label="t('testReport.title')">
            <el-input v-model="genForm.title" :placeholder="t('testReport.titlePlaceholder')" data-test="report-gen-title" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="genVisible = false">{{ t('testReport.cancel') }}</el-button>
          <el-button type="primary" :loading="generating" data-test="report-gen-submit" @click="submitGenerate">
            {{ t('testReport.confirm') }}
          </el-button>
        </template>
      </el-dialog>

      
      <el-drawer draggable v-model="detailVisible" size="640px" :title="t('testReport.detailTitle')" data-test="report-detail-drawer">
        <template v-if="detail">
          <h3 class="report-section-title">{{ t('testReport.sectionScope') }}</h3>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item :label="t('testReport.rangeLabel')">{{ detail.rangeLabel }}</el-descriptions-item>
            <el-descriptions-item :label="t('testReport.targetVersion')">
              {{ detail.scope.targetVersion || '-' }}
            </el-descriptions-item>
            <el-descriptions-item :label="t('testReport.caseTotal')">{{ detail.scope.caseTotal }}</el-descriptions-item>
            <el-descriptions-item :label="t('testReport.planCount')">{{ detail.scope.planCount }}</el-descriptions-item>
          </el-descriptions>

          <h3 class="report-section-title">{{ t('testReport.sectionResults') }}</h3>
          <div class="result-stats" data-test="report-result-stats">
            <div class="stat-card"><span class="stat-num">{{ detail.results.total }}</span><span class="stat-label">{{ t('testReport.statTotal') }}</span></div>
            <div class="stat-card"><span class="stat-num pass">{{ detail.results.passed }}</span><span class="stat-label">{{ t('testReport.passed') }}</span></div>
            <div class="stat-card"><span class="stat-num fail">{{ detail.results.failed }}</span><span class="stat-label">{{ t('testReport.failed') }}</span></div>
            <div class="stat-card"><span class="stat-num">{{ detail.results.notRun }}</span><span class="stat-label">{{ t('testReport.statNotRun') }}</span></div>
            <div class="stat-card"><span class="stat-num">{{ detail.results.skipped }}</span><span class="stat-label">{{ t('testReport.statSkipped') }}</span></div>
   
            <div class="stat-card"><span class="stat-num" data-test="report-unstable-skipped">{{ detail.results.unstableSkipped }}</span><span class="stat-label">
              {{ t('testReport.statUnstableSkipped') }}
            </span></div>
            <div class="stat-card highlight">
              <span class="stat-num">{{ detail.results.passRate }}%</span><span class="stat-label">{{ t('testReport.statPassRate') }}</span>
            </div>
          </div>

          <h3 class="report-section-title">{{ t('testReport.sectionDefects') }}</h3>
          <DataTablePage :data="severityRows" size="small" data-test="report-defect-table" border>
            <el-table-column prop="label" :label="t('testReport.severity')" width="120" />
            <el-table-column prop="count" :label="t('testReport.count')" />
          </DataTablePage>
          <div class="defect-summary" data-test="report-defect-summary">
            {{ t('testReport.defectTotal') }}：{{ detail.defectDist.total }} ｜
            {{ t('testReport.openDefects') }}：<span :class="detail.defectDist.openCount > 0 ? 'fail' : 'pass'">
              {{ detail.defectDist.openCount }}
            </span>
          </div>

          <h3 class="report-section-title">{{ t('testReport.sectionEnvs') }}</h3>
          <DataTablePage :data="detail.envVersions" size="small" data-test="report-env-table" border>
            <el-table-column prop="envName" :label="t('testReport.env')" min-width="140" />
            <el-table-column prop="version" :label="t('testReport.version')" min-width="100">
              <template #default="{ row }">{{ row.version || '-' }}</template>
            </el-table-column>
            <el-table-column prop="batchCount" :label="t('testReport.batchCount')" width="70" align="center" />
            <el-table-column prop="passed" :label="t('testReport.passed')" width="70" align="center" />
            <el-table-column prop="failed" :label="t('testReport.failed')" width="70" align="center" />
          </DataTablePage>

          <h3 class="report-section-title">{{ t('testReport.sectionConclusion') }}</h3>
          <el-alert
            :type="detail.conclusion.pass ? 'success' : 'warning'"
            :closable="false"
            show-icon
            data-test="report-conclusion"
            :title="detail.conclusion.pass ? t('testReport.passed') : t('testReport.notPassed')"
          >
            <template #default>
              <div class="conclusion-text">{{ detail.conclusion.summary }}</div>
            </template>
          </el-alert>
        </template>
      </el-drawer>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import { useObjectSync } from '@/composables/useObjectSync'
import ColumnSettings from '@/components/ColumnSettings.vue'
import ProjectOwnerEcho from '@/components/ProjectOwnerEcho.vue'
import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'
import { listPlans, type PlanView } from '@/api/plan'
import {
  downloadReportFile,
  generateReport,
  getReport,
  listReports,
  type ReportDetailView,
  type ReportRecordView,
} from '@/api/testReport'

const { t } = useI18n()

// ---------- column configuration (shared useColumnConfig piece) ----------
/** Configurable data columns (the fixed-right action column stays locked out). */
const REPORT_COLUMNS: ColumnDef[] = [
  { key: 'title', labelKey: 'testReport.title', width: null },
  { key: 'rangeType', labelKey: 'testReport.rangeType', width: 120 },
  { key: 'rangeLabel', labelKey: 'testReport.rangeLabel', width: null },
  { key: 'createdByName', labelKey: 'testReport.createdBy', width: 120 },
  { key: 'createdAt', labelKey: 'testReport.createdAt', width: 150 },
]
/** Flexible columns fall back to min-width when no explicit width is set. */
const REPORT_FLEX_MIN: Record<string, number> = { title: 200, rangeLabel: 220 }
const colCfg = useColumnConfig('testReports', REPORT_COLUMNS)
/** Popover rows (column state merged with labelKey for rendering). */
const settingsRows = computed(() =>
  colCfg.state.columns.map((c) => ({ ...c, labelKey: REPORT_COLUMNS.find((d) => d.key === c.key)!.labelKey })),
)
const auth = useAuthStore()

const { projectId, followTick } = useProjectPageContext()
const projects = computed(() => auth.projects)
const rows = ref<ReportRecordView[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const filters = reactive<{ keyword: string }>({ keyword: '' })

// Server-side sort state : the whitelisted core key
// pair forwarded into every list request; empty = backend default id DESC.
const query = reactive<{ sortBy: string; sortDir: 'asc' | 'desc' | '' }>({ sortBy: '', sortDir: '' })

// Column key -> core testreport SORT_COLUMNS whitelist key (single source for
// both the sortable-column decision and the request payload). rangeLabel is
// deliberately absent: it is a composed display label, ordering by it would
// rank reports by prose, not by anything the reader means to rank.
const SORT_KEY_MAP: Record<string, string> = {
  title: 'title',
  rangeType: 'range_type',
  createdByName: 'created_by_name',
  createdAt: 'created_at',
}

function isColSortable(key: string): boolean {
  return key in SORT_KEY_MAP
}

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
  void load(1)
}

const genVisible = ref(false)
const generating = ref(false)
const planOptions = ref<PlanView[]>([])
const genForm = reactive<{
  rangeType: number
  planId: number | null
  range: [string, string] | null
  title: string
}>({ rangeType: 1, planId: null, range: null, title: '' })

const detailVisible = ref(false)
const detail = ref<ReportDetailView | null>(null)

const severityRows = computed(() => detail.value?.defectDist.bySeverity ?? [])

onMounted(() => {
  if (projectId.value) load(1)
})

watch(followTick, () => {
  if (projectId.value) load(1)
})

// : a landed REPORT_WRITE draft adds a report row to this very list —
// re-read the current page in place (same filters, same page number), with no
// toast, no confirmation and no page jump (BG iron rule 2). The page is
// already a project segment, so like the case/batch lists the announcement is
// not anchored to an id; a fresh row simply shows up under the standing order.
useObjectSync({
  watch: ['REPORT_WRITE'],
  onSync: () => void load(page.value),
})

watch(genVisible, (v) => {
  if (v && projectId.value) {
    planOptions.value = []
    listPlans({ projectId: projectId.value, page: 1, size: 100 })
      .then((r) => {
        planOptions.value = r.list
      })
      .catch(() => ElMessage.error(t('testReport.loadPlanFailed')))
  }
})


async function load(p: number) {
  if (!projectId.value) return
  loading.value = true
  try {
    const r = await listReports({
      projectId: projectId.value, keyword: filters.keyword || undefined, page: p, size: size.value,
      sortBy: query.sortBy || undefined,
      sortDir: query.sortDir ? (query.sortDir as 'asc' | 'desc') : undefined,
    })
    rows.value = r.list
    total.value = r.total
    page.value = r.page || p
  } finally {
    loading.value = false
  }
}

function openGenerate() {
  genForm.rangeType = 1
  genForm.planId = null
  genForm.range = null
  genForm.title = ''
  genVisible.value = true
}

async function submitGenerate() {
  if (genForm.rangeType === 1 && !genForm.planId) {
    ElMessage.warning(t('testReport.selectPlanRequired'))
    return
  }
  if (genForm.rangeType === 2 && (!genForm.range || genForm.range.length !== 2)) {
    ElMessage.warning(t('testReport.selectRangeRequired'))
    return
  }
  if (!projectId.value) return
  generating.value = true
  try {
    const startTime = genForm.rangeType === 2 && genForm.range ? genForm.range[0] : null
    const endTime = genForm.rangeType === 2 && genForm.range ? genForm.range[1] : null
    const r = await generateReport({
      projectId: projectId.value,
      rangeType: genForm.rangeType,
      planId: genForm.rangeType === 1 ? genForm.planId : null,
      startTime,
      endTime,
      title: genForm.title || null,
    })
    genVisible.value = false
    ElMessage.success(t('testReport.generated'))
    await load(1)
    openDetail(r.id)
  } catch (e: unknown) {
    ElMessage.error((e as Error).message || t('testReport.generateFailed'))
  } finally {
    generating.value = false
  }
}

async function openDetail(id: number) {
  try {
    detail.value = await getReport(id)
    detailVisible.value = true
  } catch (e: unknown) {
    ElMessage.error((e as Error).message || t('testReport.loadFailed'))
  }
}

async function doExport(id: number, format: 'xlsx' | 'pdf') {
  try {
    await downloadReportFile(id, format)
  } catch (e: unknown) {
    ElMessage.error((e as Error).message || t('testReport.exportFailed'))
  }
}
</script>

<style scoped>
.report-section-title {
  font-size: 14px;
  margin: 16px 0 8px;
  padding-left: 8px;
  border-left: 3px solid var(--el-color-primary);
}
.result-stats {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 8px;
}
.stat-card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 10px 6px;
  text-align: center;
}
.stat-card .stat-num {
  display: block;
  font-size: 20px;
  font-weight: 600;
}
.stat-card .stat-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.stat-card .pass {
  color: var(--el-color-success);
}
.stat-card .fail {
  color: var(--el-color-danger);
}
.stat-card.highlight {
  background: var(--el-color-primary-light-9);
}
.defect-summary {
  margin-top: 8px;
  font-size: 13px;
}
.conclusion-text {
  margin-top: 6px;
  line-height: 1.6;
  white-space: pre-wrap;
}
</style>
