<template>
  <div class="page-root" data-test="review-page">
    <div class="page-card">
      
      <div class="toolbar">
        <div class="toolbar-left">
          <!-- R23: the page name is carried solely by the top bar; the
 duplicate in-page h2 was retired . -->
        </div>
        <div class="toolbar-right">
          <el-button
            type="success"
            plain
            :icon="Select"
            :disabled="batchPassTargets.length === 0"
            data-test="review-batch-pass"
            @click="openBatchPass"
          >
            {{ t('review.batchPass') }}<template v-if="batchPassTargets.length">（{{ batchPassTargets.length }}）</template>
          </el-button>
          <el-tooltip :content="t('case.quickReviewHint')" placement="bottom">
            <el-button
              :type="quick.quickMode.value ? 'warning' : 'default'"
              :plain="!quick.quickMode.value"
              :icon="quick.quickMode.value ? 'SwitchButton' : 'Operation'"
              data-test="review-quick-toggle"
              @click="quick.toggleQuickMode()"
            >
              {{ quick.quickMode.value ? t('case.exitQuickReview') : t('case.quickReview') }}
            </el-button>
          </el-tooltip>
        </div>
      </div>

      
      <div v-if="projectId" class="stat-row" data-test="review-stats">
        <div class="stat-card">
          <el-icon :size="30" color="var(--tp-warning)"><Clock /></el-icon>
          <div>
            <div class="stat-num" data-test="stat-todo">{{ todoCount }}</div>
            <div class="stat-label">{{ t('review.todo') }} · {{ t('review.todoDesc') }}</div>
          </div>
        </div>
        <div class="stat-card">
          <el-icon :size="30" color="var(--tp-success)"><CircleCheck /></el-icon>
          <div>
            <div class="stat-num" data-test="stat-done">{{ doneCount }}</div>
            <div class="stat-label">{{ t('review.done') }} · {{ t('review.doneDesc') }}</div>
          </div>
        </div>
        <div class="stat-card stat-progress-card">
          <div class="progress-main">
            <div class="progress-title">
              <span data-test="stat-progress-text">{{ t('review.progressText', { done: doneCount, total: assignedTotal }) }}</span>
              <el-tag size="small" :type="rateTagType" data-test="stat-rate">{{ ratePercent }}%</el-tag>
            </div>
            <el-progress :percentage="ratePercent" :stroke-width="10" :show-text="false" data-test="stat-progress-bar" />
            <div class="stat-label">{{ t('review.progressDesc') }}</div>
          </div>
        </div>
      </div>

      
      <div v-if="projectId && lowConfidenceCount > 0" class="low-confidence-hint" data-test="low-confidence-hint">
        <el-icon color="var(--tp-danger)"><WarningFilled /></el-icon>
        <span>{{ t('review.lowConfidence', { n: lowConfidenceCount }) }}</span>
      </div>

      
      <div v-if="projectId && unassignedCount > 0" class="quick-hint" data-test="review-unassigned-hint">
        <el-icon color="var(--tp-warning)"><WarningFilled /></el-icon>
        <span>{{ t('case.autoAssignRowHint', { n: unassignedCount }) }}</span>
        <el-button v-if="canAssign" size="small" type="primary" plain data-test="review-auto-assign" @click="autoAssignVisible = true">
          {{ t('case.autoAssign') }}
        </el-button>
      </div>

      <AutoAssignDialog v-model="autoAssignVisible" :project-id="projectId" @done="onAutoAssignDone" />

      
      <div v-if="!loading && projects.length === 0" class="empty-box" data-test="review-empty-no-project">
        <EmptyState :title="t('review.noProject')" :action-text="t('review.goProjects')" @action="router.push('/projects')" />
      </div>

      <template v-else-if="projectId">
        
        <div v-if="quick.quickMode.value" class="quick-hint" data-test="review-quick-hint">
          <el-icon color="var(--tp-warning)"><Operation /></el-icon>
          <span>{{ t('case.quickReviewHint') }}</span>
          <el-tag v-if="quick.activeRow.value" type="warning" size="small" data-test="review-quick-active">
            {{ quick.activeRow.value.caseNo }}
          </el-tag>
        </div>

        <!-- Filter bar. R25 mode declaration (scope-aware single
             control, TB group): this page carries no tabs and exactly one
             page-surface table, so the page-level column-settings popover
             equals the table scope: show/hide, order, pinning and density
             persist per user through the shared useColumnConfig piece
             (viewKey 'review-workbench'). R21 bands (FB group): the settings
             control rides the filter-bar trailing slot; the screening selects
             and reset stay in the condition flow; batch pass and quick-review
             ride the toolbar band as page-level actions. -->
        <div class="filter-bar">
          <el-select v-model="filters.status" :placeholder="t('case.statusAll')" clearable style="width: 130px" data-test="review-filter-status" @change="load(1)">
            <el-option v-for="(v, k) in CASE_STATUS" :key="k" :label="v.label" :value="Number(k)" />
          </el-select>
          <el-select v-model="filters.priority" :placeholder="t('case.priorityAll')" clearable style="width: 120px" data-test="review-filter-priority" @change="load(1)">
            <el-option v-for="i in [0, 1, 2]" :key="i" :label="PRIORITY_LABEL[i]" :value="i" />
          </el-select>
          <el-select v-model="filters.confidence" :placeholder="t('case.confidenceAll')" clearable style="width: 120px" data-test="review-filter-confidence" @change="load(1)">
            <el-option v-for="i in [1, 2, 3]" :key="i" :label="CONFIDENCE_LABEL[i]" :value="i" />
          </el-select>
          <el-button :icon="Refresh" data-test="review-reset" @click="onReset">{{ t('case.reset') }}</el-button>
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
          </div>
        </div>

        
        <div class="workbench-split">
          <div class="split-main">
        <TableSkeleton v-if="loading" :cols="colCfg.visibleColumns.value.length + 2" data-test="reviewworkbenchview-skeleton" />
        <el-table border
          :size="colCfg.state.density"
          :data="list"
          row-key="id"
          data-test="review-table"
          :default-sort="{ prop: 'priority', order: 'ascending' }"
          @sort-change="onSortChange"
          @row-click="onRowClick"
          @selection-change="onSelectionChange"
          :row-class-name="rowClassName"
         v-else>
          <template #empty>
            <div class="empty-box" data-test="review-empty">
              <EmptyState :title="t('review.empty')" :hint="t('review.emptyGuide')" />
            </div>
          </template>
          <el-table-column type="selection" width="46" :selectable="(row: CaseItem) => row.status === 1 || row.status === 2" />
          <el-table-column
            v-for="col in colCfg.visibleColumns.value"
            :key="col.key"
            :prop="col.key"
            :fixed="col.fixed || false"
            :label="t(col.labelKey)"
            :width="col.width ?? undefined"
            :min-width="col.width ? undefined : REVIEW_WORKBENCH_FLEX_MIN[col.key]"
            :sortable="REVIEW_WORKBENCH_SORT_BY[col.key] ? 'custom' : false"
            :sort-by="REVIEW_WORKBENCH_SORT_BY[col.key]"
            :show-overflow-tooltip="col.key === 'title'"
            :data-test="`review-col-${col.key}`"
          >
            <template #default="{ row }">
              <template v-if="col.key === 'caseNo'">
                <span class="mono" :data-test="`review-case-no-${row.id}`">{{ row.caseNo }}</span>
              </template>
              <template v-else-if="col.key === 'title'">
                <span class="case-title">{{ row.title }}</span>
                <!-- dual-dimension display: the reviewer sees the same two
 server-computed provenance fields as the case list, read
 from the api/case single source (no page-local vocabulary). -->
                <el-tag v-if="row.sourceFacet" size="small" :type="FACET_TYPE[row.sourceFacet] ?? 'info'" data-test="review-facet-badge" style="margin-left: 6px">
                  {{ FACET_LABEL[row.sourceFacet] ?? row.sourceFacet }}
                </el-tag>
                <el-tag v-if="row.evidenceGrade !== undefined && row.evidenceGrade !== null" size="small" :type="EVIDENCE_GRADE_TYPE[row.evidenceGrade] ?? 'info'" data-test="review-evidence-badge" style="margin-left: 6px">
                  {{ EVIDENCE_GRADE_LABEL[row.evidenceGrade] }}
                </el-tag>
              </template>
              <template v-else-if="col.key === 'priority'">
                <el-tag :type="PRIORITY_TYPE[row.priority] ?? 'info'" size="small">{{ PRIORITY_LABEL[row.priority] }}</el-tag>
              </template>
              <template v-else-if="col.key === 'confidence'">
                <el-tag :type="CONFIDENCE_TYPE[row.confidence] ?? 'info'" size="small">
                  {{ CONFIDENCE_LABEL[row.confidence] }}
                </el-tag>
              </template>
              <template v-else-if="col.key === 'status'">
                <el-tag :type="CASE_STATUS[row.status]?.type ?? 'info'" size="small">{{ CASE_STATUS[row.status]?.label ?? row.status }}</el-tag>
              </template>
              <template v-else-if="col.key === 'updatedAt'"><TimeCell :iso="row.updatedAt" /></template>
            </template>
          </el-table-column>
          <el-table-column :label="t('case.action')" width="90" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" :data-test="`review-detail-${row.id}`" @click="router.push(`/cases/${row.id}`)">
                {{ t('case.detail') }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>



        <div v-if="total > 0" class="pagination-bar">
          <el-pagination v-if="total > 0"
            v-model:current-page="page"
            :page-size="size"
            :total="total"
            layout="total, prev, pager, next"
            data-test="review-pagination"
            @current-change="load()"
          />
        </div>
          </div>
          <aside class="split-side">
            <ReviewContextPanel ref="ctxPanel" :case-id="activeCaseId" :case-no="activeCaseNo" />
            <CommentPanel host-type="review" :host-id="activeCaseId" />
          </aside>
        </div>
      </template>
    </div>

    
    <el-dialog draggable v-model="reasonVisible" :title="reasonAction === 'need_change' ? t('case.needChangeTitle') : t('case.rejectTitle')" width="480px" destroy-on-close data-test="review-reason-dialog">
      <el-input
        v-model="reasonText"
        type="textarea"
        :rows="3"
        :placeholder="reasonAction === 'need_change' ? t('case.needChangePlaceholder') : t('case.rejectPlaceholder')"
        maxlength="500"
        data-test="review-reason-input"
      />
      <ProblemTypeSelect v-model="reasonTypes" />
      <template #footer>
        <el-button @click="reasonVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="reviewing" data-test="review-reason-submit" @click="submitReason">{{ t('common.confirm') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Clock, CircleCheck, Operation, Refresh, Select, WarningFilled } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import { useQuickReview } from '@/composables/useQuickReview'
import { useBatchReview } from '@/composables/useBatchReview'
import {
  CASE_STATUS, CONFIDENCE_LABEL, CONFIDENCE_TYPE, PRIORITY_LABEL, PRIORITY_TYPE,
  REVIEW_ACTION_LABEL, listCases, reviewCase,
  FACET_LABEL, FACET_TYPE, EVIDENCE_GRADE_LABEL, EVIDENCE_GRADE_TYPE,
  type CaseItem, type ReviewAction,
} from '@/api/case'
import AutoAssignDialog from '@/views/case/AutoAssignDialog.vue'
import ReviewContextPanel from './ReviewContextPanel.vue'
import ProblemTypeSelect from '@/components/ProblemTypeSelect.vue'
import CommentPanel from '@/components/CommentPanel.vue'
import EmptyState from '@/components/EmptyState.vue'
import ColumnSettings from '@/components/ColumnSettings.vue'
import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'
import { useObjectSync } from '@/composables/useObjectSync'

const { t } = useI18n()

// Column-configuration wiring (N-3 repayment, shared useColumnConfig piece):
// all six data columns of the review queue flow through the visibleColumns
// loop so show/hide, order, pinning and density persist per user under
// viewKey 'review-workbench'. The selection checkbox and the fixed detail
// action column stay locked outside the loop; server-driven sorting rides a
// shared key map so hiding or reordering never drops the custom-sort hook,
// and the quick-review keyboard flow keeps reading rows through the same
// filtered list the table renders.
const REVIEW_WORKBENCH_COLUMNS: ColumnDef[] = [
  { key: 'caseNo', labelKey: 'case.caseNo', width: 170 },
  { key: 'title', labelKey: 'case.targetTitle', width: null },
  { key: 'priority', labelKey: 'case.priority', width: 110 },
  { key: 'confidence', labelKey: 'case.confidence', width: 100 },
  { key: 'status', labelKey: 'case.status', width: 100 },
  { key: 'updatedAt', labelKey: 'case.updatedAt', width: 160 },
]
// title is the flexible column (case titles carry the long text)
const REVIEW_WORKBENCH_FLEX_MIN: Record<string, number> = { title: 240 }
const REVIEW_WORKBENCH_SORT_BY: Record<string, string> = {
  caseNo: 'case_no',
  priority: 'priority',
  confidence: 'confidence',
  updatedAt: 'updated_at',
}
const colCfg = useColumnConfig('review-workbench', REVIEW_WORKBENCH_COLUMNS)
const settingsRows = computed(() =>
  colCfg.state.columns.map((c) => ({ ...c, labelKey: REVIEW_WORKBENCH_COLUMNS.find((d) => d.key === c.key)!.labelKey })),
)
const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const projects = computed(() => auth.projects)
const myUserId = computed(() => auth.user?.userId ?? 0)

const loading = ref(false)
const reviewing = ref(false)
const list = ref<CaseItem[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
// R30: project switch is the header global context (wiring piece = URL-first
// chain + follow-header). In-page picker and first-project fallback retired.
const { projectId, followTick } = useProjectPageContext()
watch(followTick, () => {
  if (projectId.value) {
    void load(1)
    void refreshStats()
  }
})

const filters = reactive<{ status?: number; priority?: number; confidence?: number }>({
  status: undefined,
  priority: undefined,
  confidence: undefined,
})

const todoCount = ref(0)
const doneCount = ref(0)
const assignedTotal = computed(() => todoCount.value + doneCount.value)
const ratePercent = computed(() => (assignedTotal.value ? Math.round((doneCount.value / assignedTotal.value) * 100) : 0))
const rateTagType = computed(() => (ratePercent.value >= 80 ? 'success' : ratePercent.value >= 50 ? 'primary' : 'warning'))
const lowConfidenceCount = ref(0)

const canAssign = computed(() => {
  if (auth.isAdmin) return true
  const p = auth.projects.find((x) => x.projectId === projectId.value)
  return p?.role === 2
})
const autoAssignVisible = ref(false)
const unassignedCount = ref(0)

async function loadUnassignedCount(): Promise<void> {
  if (!projectId.value) return
  try {
    const r = await listCases({ projectId: projectId.value, status: 1, assigneeId: 0, page: 1, size: 1 })
    unassignedCount.value = r.total || 0
  } catch {
    unassignedCount.value = 0
  }
}

function onAutoAssignDone(): void {
  void load(1)
  void refreshStats()
  void loadUnassignedCount()
}

const quick = useQuickReview(() => list.value, {
  onPass: (row) => void doReview(row, 'pass'),
  onNeedChange: (row) => openReasonDialog(row, 'need_change'),
  onReject: (row) => openReasonDialog(row, 'reject'),
  onBatch: () => void openBatchPass(),
})

const batchReviewActions = useBatchReview(t)
const selection = ref<CaseItem[]>([])
const batchPassTargets = computed(() => selection.value)

function onSelectionChange(rows: CaseItem[]): void {
  selection.value = rows
}

async function openBatchPass(): Promise<void> {
  if (!selection.value.length) {
    ElMessage.warning(t('case.noSelection'))
    return
  }
  if (await batchReviewActions.confirmBatchPass(selection.value)) {
    reviewing.value = true
    try {
      const result = await batchReviewActions.submitBatch(selection.value, 'pass', '')
      if (result) {
        selection.value = []
        await load()
        await refreshStats()
        ctxPanel.value?.refresh()
      }
    } finally {
      reviewing.value = false
    }
  }
}

const ctxPanel = ref<InstanceType<typeof ReviewContextPanel> | null>(null)
const ctxRow = ref<CaseItem | null>(null)
const activeCaseId = computed(() => quick.activeRow.value?.id ?? ctxRow.value?.id ?? null)
const activeCaseNo = computed(() => quick.activeRow.value?.caseNo ?? ctxRow.value?.caseNo ?? '')

function onRowClick(row: CaseItem): void {
  quick.onRowClick(row)
  ctxRow.value = row
}

// Deep-link focus : arriving with ?caseId= from the
// chat dock drives the same focus flow as a row click — the quick-review
// active case and the context sidebar both show that case. The parameter is
// consumed exactly once on the first list load, so later project switches or
// filter reloads never re-warn about a stale link. When the case is not in
// the current page segment (assigned to another reviewer, or beyond page 1)
// the user gets an honest message instead of a silent no-op.
let deepLinkCaseHandled = false
async function focusCaseFromQuery(): Promise<void> {
  if (deepLinkCaseHandled) return
  const raw = route.query.caseId
  const caseId = Array.isArray(raw) ? raw[0] : raw
  if (!caseId) return
  deepLinkCaseHandled = true
  let target = list.value.find((r) => String(r.id) === String(caseId))
  if (!target) target = (await jumpToDeepLinkPage(caseId)) ?? undefined
  if (!target) {
    ElMessage.warning(t('review.deepLinkCaseMissing'))
    return
  }
  if (!quick.quickMode.value) quick.toggleQuickMode()
  quick.onRowClick(target)
  ctxRow.value = target
  requestAnimationFrame(() => {
    document.querySelector('.quick-active-row')?.scrollIntoView({ block: 'nearest' })
  })
}

// Deep-link locate: the queue is server-paginated and the
// backend orders ties by id DESC, so a pinned case created first sits on the
// LAST page at worst scale — the first segment can never find it. Pull the whole
// ordered set once under the current filters/sort (capped at the documented
// worst scale; the backend size clamp allows 1000), find the case rank, jump
// the visible page to it and re-find inside that segment. A case genuinely
// outside the reviewer's set still ends in the honest warning above.
const DEEP_LINK_LOCATE_MAX = 500
async function jumpToDeepLinkPage(caseId: string): Promise<CaseItem | null> {
  if (!projectId.value) return null
  const scope = Math.min(total.value, DEEP_LINK_LOCATE_MAX)
  if (scope <= list.value.length) return null
  try {
    const probe = await listCases(queueQuery(projectId.value, 1, scope))
    const rank = (probe.list || []).findIndex((r) => String(r.id) === String(caseId))
    if (rank < 0) return null
    page.value = Math.floor(rank / size.value) + 1
    await load()
    return list.value.find((r) => String(r.id) === String(caseId)) ?? null
  } catch {
    return null
  }
}

function rowClassName({ row }: { row: CaseItem }): string {
  const base = quick.rowClassName({ row })
  if (row.confidence === 3) return base ? `${base} low-confidence-row` : 'low-confidence-row'
  return base
}

async function loadStats(): Promise<void> {
  if (!projectId.value) return
  try {
    const [s1, s2, s3, s4, s5] = await Promise.all([
      listCases({ projectId: projectId.value, status: 1, assigneeId: myUserId.value, page: 1, size: 1 }),
      listCases({ projectId: projectId.value, status: 2, assigneeId: myUserId.value, page: 1, size: 1 }),
      listCases({ projectId: projectId.value, status: 3, assigneeId: myUserId.value, page: 1, size: 1 }),
      listCases({ projectId: projectId.value, status: 4, assigneeId: myUserId.value, page: 1, size: 1 }),
      listCases({ projectId: projectId.value, status: 5, assigneeId: myUserId.value, page: 1, size: 1 }),
    ])
    todoCount.value = (s1.total || 0) + (s2.total || 0)
    doneCount.value = (s3.total || 0) + (s4.total || 0) + (s5.total || 0)
  } catch {
    todoCount.value = 0
    doneCount.value = 0
  }
}

async function loadLowConfidenceCount(): Promise<void> {
  if (!projectId.value) return
  try {
    const r = await listCases({ projectId: projectId.value, confidence: 3, assigneeId: myUserId.value, page: 1, size: 1 })
    lowConfidenceCount.value = r.total || 0
  } catch {
    lowConfidenceCount.value = 0
  }
}

/** Single query source for the review queue segment: load and the deep-link
 * locator must see exactly the same filtered/sorted set, never a second copy
 * of the parameter list (R40). Callers pass the already-guarded project id. */
function queueQuery(pid: number, p: number, s: number) {
  return {
    projectId: pid,
    status: filters.status,
    priority: filters.priority,
    confidence: filters.confidence,
    assigneeId: myUserId.value,
    sortBy: sortBy.value,
    sortDir: sortDir.value,
    page: p,
    size: s,
  }
}

async function load(p?: number): Promise<void> {
  if (!projectId.value) return
  if (p) page.value = p
  loading.value = true
  try {
    const result = await listCases(queueQuery(projectId.value, page.value, size.value))
    list.value = result.list || []
    total.value = result.total || 0
    quick.afterLoad()
  } catch (e) {
    ElMessage.error(t('case.loadFailed', { msg: (e as Error).message || t('components.networkError') }))
  } finally {
    loading.value = false
  }
}

async function refreshStats(): Promise<void> {
  await Promise.all([loadStats(), loadLowConfidenceCount(), loadUnassignedCount()])
}

// : a landed CASE_REVIEW decision writes the case status and
// appends its review trace, CASE_WRITE edits the row fields this queue
// renders, and CASE_ROLLBACK moves a reviewed case back — the queue page,
// the stat cards and the unassigned hint line all shift together. Re-read
// the current page/filters as-is and refresh the counters silently (BG iron
// rules 2-3: no toast, no confirm, no page jump).
useObjectSync({
  watch: ['CASE_REVIEW', 'CASE_WRITE', 'CASE_ROLLBACK'],
  onSync: () => {
    void load()
    void refreshStats()
  },
})


const SORT_KEY_MAP: Record<string, string> = {
  caseNo: 'case_no',
  priority: 'priority',
  confidence: 'confidence',
  createdAt: 'created_at',
  updatedAt: 'updated_at',
}

const sortBy = ref('priority')
const sortDir = ref('asc')

function onSortChange({ prop, order }: { prop: string; order: string | null }): void {
  const key = SORT_KEY_MAP[prop]
  if (!key) {
    ElMessage.warning(t('review.sortUnsupported'))
    void load()
    return
  }
  sortBy.value = key
  sortDir.value = order === 'descending' ? 'desc' : 'asc'
  void load(1)
}

function onReset(): void {
  filters.status = undefined
  filters.priority = undefined
  filters.confidence = undefined
  sortBy.value = 'priority'
  sortDir.value = 'asc'
  void load(1)
}

async function doReview(row: CaseItem, action: ReviewAction): Promise<void> {
  if (action === 'pass') {
    try {
      await ElMessageBox.confirm(
        t('case.approveConfirmMsg', { caseNo: row.caseNo }),
        t('case.approveConfirmTitle'),
        { confirmButtonText: t('case.approve'), cancelButtonText: t('common.cancel'), type: 'warning' },
      )
    } catch {
      return
    }
  } else {
    openReasonDialog(row, action)
    return
  }
  await submitReview(row, action, '')
}

function openReasonDialog(row: CaseItem, action: ReviewAction): void {
  pendingReview = { row, action }
  reasonText.value = ''
  reasonTypes.value = []
  reasonAction.value = action
  reasonVisible.value = true
}

const reasonVisible = ref(false)
const reasonText = ref('')
const reasonTypes = ref<string[]>([])
const reasonAction = ref<ReviewAction>('need_change')
let pendingReview: { row: CaseItem; action: ReviewAction } | null = null

async function submitReason(): Promise<void> {
  if (!reasonText.value.trim()) {
    ElMessage.warning(t('case.remarkRequired'))
    return
  }
  if (!reasonTypes.value.length) {
    ElMessage.warning(t('case.problemTypesRequired'))
    return
  }
  if (pendingReview) {
    await submitReview(pendingReview.row, pendingReview.action, reasonText.value.trim(), reasonTypes.value)
  }
}

async function submitReview(row: CaseItem, action: ReviewAction, remark: string, feedbackTypes?: string[]): Promise<void> {
  reviewing.value = true
  try {
    await reviewCase(row.id, { action, version: row.version, remark: remark || undefined, feedbackTypes }, `wb-${row.id}-${Date.now()}`)
    ElMessage.success(t('case.reviewSuccess', { caseNo: row.caseNo, action: REVIEW_ACTION_LABEL[action] }))
    reasonVisible.value = false
    pendingReview = null
    await load()
    await refreshStats()
    ctxPanel.value?.refresh()
  } catch (e) {
    const msg = (e as Error).message || ''
    if (msg.includes('409') || msg.includes('\u7248\u672c') || msg.includes('\u72b6\u6001')) {
      ElMessage.warning(t('case.reviewFail409'))
      await load()
      await refreshStats()
    } else {
      ElMessage.error(t('case.reviewFail', { msg }))
    }
  } finally {
    reviewing.value = false
  }
}

onMounted(async () => {
  if (projectId.value) {
    await load()
    await focusCaseFromQuery()
    void refreshStats()
  }
  window.addEventListener('keydown', quick.onKeydown)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', quick.onKeydown)
})
</script>

<style scoped lang="scss">
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--tp-band-gap);
  flex-wrap: wrap;
  gap: 12px;
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.toolbar-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.stat-row {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  margin-bottom: var(--tp-band-gap);

  .stat-card {
    background: var(--tp-bg-hover, rgba(127, 127, 127, 0.06));
    border: 1px solid var(--tp-border);
    border-radius: 8px;
    padding: 12px 16px;
    display: flex;
    align-items: center;
    gap: 12px;
  }
}

.stat-num {
  font-size: 28px;
  font-weight: 600;
  line-height: 1.3;
}

.stat-label {
  font-size: 12px;
  color: var(--tp-text-secondary);
  margin-top: 4px;
}

.stat-progress-card {
  display: flex;
  align-items: center;

  .progress-main {
    width: 100%;
  }

  .progress-title {
    display: flex;
    align-items: center;
    gap: 10px;
    font-size: 14px;
    font-weight: 600;
    margin-bottom: 8px;
  }
}

.low-confidence-hint {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  margin-bottom: var(--tp-band-gap);
  border-radius: 6px;
  background: rgba(239, 68, 68, 0.08);
  border: 1px dashed var(--tp-danger);
  color: var(--tp-text);
  font-size: 13px;
}

.quick-hint {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  margin-bottom: var(--tp-band-gap);
  border-radius: 6px;
  background: var(--tp-bg-hover);
  border: 1px dashed var(--tp-warning);
  color: var(--tp-text);
  font-size: 13px;
}

.filter-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: var(--tp-band-gap);
}

.case-title {
  font-weight: 500;
}

.mono {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 13px;
}



.workbench-split {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}

.split-main {
  flex: 1;
  min-width: 0;
}

.split-side {
  width: 320px;
  flex-shrink: 0;
  border: 1px solid var(--tp-border);
  border-radius: 8px;
  padding: 12px 16px;
  background: var(--tp-bg-hover, rgba(127, 127, 127, 0.04));
  max-height: calc(100vh - 260px);
  position: sticky;
  top: 76px;
}

@media (max-width: 1200px) {
  .workbench-split {
    flex-direction: column;
  }

  .split-side {
    width: 100%;
    position: static;
    max-height: none;
  }
}

.empty-box {
  padding-top: 10px;
}


.empty-guide { max-width: 460px; margin: 0 auto 12px; }
</style>
