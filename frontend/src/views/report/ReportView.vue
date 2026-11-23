<!-- tp-sort-exempt: every table here is a server-computed aggregate (team board, chapter coverage) over a bounded group window, not a row ledger.
     tp-column-config-exempt (N-3): same bounded-aggregate basis — the team board is one row per project reviewer (7 fixed metrics) and the chapter coverage is one row per requirement chapter (3–4 fixed metrics); hiding, reordering or pinning any of these columns takes away the story the table tells, so column settings carry zero operational value here. A single page-level settings control could not honestly scope to the active inner tab either (R25 mode 2, honest absence, declared here). -->

<template>
  <div class="page-root" data-test="report-page">
    <div class="page-card">
      <!-- AN-3 half A (report-domain merge): the standalone coverage
 matrix page lives on this page as a tab (the coverage-rate board
 and the requirement×case blind spots are one story). Tab state
 rides the URL query (?tab=coverage) so deep links and the retired
 /coverage redirect land on the right tab; keep-alive-safe pages
 re-read the query on activation is NOT needed here (Report is not
 on the CACHED_LIST_VIEWS whitelist — fresh setup per mount). -->
      <el-tabs v-model="pageTab" data-test="report-page-tabs" class="report-page-tabs">
        <el-tab-pane name="board">
          <template #label><span data-test="report-tab-board">{{ t('report.pageTabBoard') }}</span></template>
        </el-tab-pane>
        <el-tab-pane name="coverage">
          <template #label><span data-test="report-tab-coverage">{{ t('report.pageTabCoverage') }}</span></template>
        </el-tab-pane>
        <el-tab-pane name="export">
          <template #label><span data-test="report-tab-export">{{ t('report.pageTabExport') }}</span></template>
        </el-tab-pane>
      </el-tabs>

      <!-- Coverage-matrix tab = CoverageMatrixView reused as-is (no markup
           copy; its filters/matrix/click-through stay the single source). -->
      <CoverageMatrixView v-if="pageTab === 'coverage'" embedded />

      <!-- Export/snapshot tab = ReportCenterView reused as-is
 (the snapshot ledger, detail drawer and CSV/PDF exports stay the
 single source; no perm gate on the tab — the route carried none
 before the merge, backend guards are the single point). -->
      <ReportCenterView v-else-if="pageTab === 'export'" embedded />

      <template v-else>
      
      <div class="toolbar">
        <div class="toolbar-left">
          <el-checkbox v-if="projectId" v-model="masked" data-test="report-masked-checkbox">
            {{ t('report.maskedExport') }}
          </el-checkbox>
        </div>
        <div class="toolbar-right">
          <el-dropdown v-if="projectId" trigger="click" data-test="report-export-dropdown" @command="onExport">
            <el-button :icon="'Download'" :loading="exporting" data-test="report-export-btn">
              {{ t('report.export') }}
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item v-for="(label, type) in REPORT_TYPE_LABEL" :key="type" :command="type">
                  {{ label }}
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </div>

      
      <div v-if="!loading && projects.length === 0" class="empty-box" data-test="report-empty-no-project">
        <EmptyState :title="t('report.noProject')" :action-text="t('report.goProjects')" @action="router.push('/projects')" />
      </div>

      <template v-else-if="projectId">
        
        <el-tabs v-model="activeTab" class="report-tabs" data-test="report-tabs" @tab-change="onTabChange">
          <el-tab-pane :label="t('report.tabPersonal')" name="personal_review">
            <div v-if="personal" class="stat-grid" data-test="personal-stats">
              <div class="stat-card"><div class="stat-num" data-test="pr-reviewed">{{ personal.reviewedCount }}</div><div class="stat-label">{{ t('report.reviewed') }}</div></div>
              <div class="stat-card"><div class="stat-num" data-test="pr-pass">{{ personal.passCount }}</div><div class="stat-label">{{ t('report.pass') }}</div></div>
              <div class="stat-card"><div class="stat-num" data-test="pr-change">{{ personal.needChangeCount }}</div><div class="stat-label">{{ t('report.needChange') }}</div></div>
              <div class="stat-card"><div class="stat-num" data-test="pr-reject">{{ personal.rejectCount }}</div><div class="stat-label">{{ t('report.reject') }}</div></div>
              <div class="stat-card"><div class="stat-num">{{ fmtSeconds(personal.avgReviewSeconds) }}</div><div class="stat-label">{{ t('report.avgReviewTime') }}</div></div>
            </div>
            <EmptyState v-else-if="!loading" :title="t('report.emptyPersonal')" :action-text="t('layout.review')" @action="router.push('/review')" />
          </el-tab-pane>

          <el-tab-pane v-if="canViewTeamBoard" :label="t('report.tabTeam')" name="team_board">
            <DataTablePage :empty-title="t('report.emptyTeam')" :data="team" :loading="loading" data-test="team-board-table">
              <el-table-column prop="username" :label="t('report.account')" min-width="120" />
              <el-table-column prop="nickName" :label="t('report.nickName')" min-width="120" />
              <el-table-column prop="reviewedCount" :label="t('report.reviewed')" width="100" />
              <el-table-column prop="passCount" :label="t('report.pass')" width="90" />
              <el-table-column prop="needChangeCount" :label="t('report.needChange')" width="100" />
              <el-table-column prop="rejectCount" :label="t('report.reject')" width="90" />
              <el-table-column prop="backlogCount" :label="t('report.backlog')" width="90">
                <template #default="{ row }">
                  <el-tag :type="row.backlogCount > 0 ? 'warning' : 'success'" size="small">{{ row.backlogCount }}</el-tag>
                </template>
              </el-table-column>
            </DataTablePage>
          </el-tab-pane>

          <el-tab-pane :label="t('report.tabAiQuality')" name="ai_quality">
            <div v-if="ai" class="stat-grid" data-test="ai-stats">
              <div class="stat-card"><div class="stat-num" data-test="ai-total">{{ ai.totalReviewed }}</div><div class="stat-label">{{ t('report.reviewedTotal') }}</div></div>
              <div class="stat-card"><div class="stat-num" data-test="ai-first-pass"><CaliberTooltip code="ai-accept-rate">{{ pct(ai.firstPassRate) }}%</CaliberTooltip></div><div class="stat-label">{{ t('report.firstPassRate') }}（{{ ai.firstPassCount }}）</div></div>
              <div class="stat-card"><div class="stat-num" data-test="ai-modify">{{ pct(ai.modifyRate) }}%</div><div class="stat-label">{{ t('report.modifyRate') }}（{{ ai.modifyCount }}）</div></div>
              <div class="stat-card"><div class="stat-num" data-test="ai-reject">{{ pct(ai.rejectRate) }}%</div><div class="stat-label">{{ t('report.rejectRate') }}（{{ ai.rejectCount }}）</div></div>
            </div>
            <div v-if="ai && ai.statusDist.length > 0" class="status-dist" data-test="ai-status-dist">
              <el-tag v-for="s in ai.statusDist" :key="s.status" class="status-tag" :type="statusTagType(s.status)">
                {{ s.label }}：{{ s.count }}
              </el-tag>
            </div>
            <div v-if="phaseDist.length > 0" class="status-dist" style="margin-top: 12px" data-test="ai-phase-dist">
              <span class="text-secondary phase-label">{{ t('report.phaseDistLabel') }}：</span>
              <el-tag v-for="p in phaseDist" :key="p.phase" class="status-tag" :type="phaseTagType(p.phase)">
                {{ p.label }}：{{ p.count }}
              </el-tag>
            </div>
            <EmptyState v-else-if="!loading && !ai" :title="t('report.emptyAi')" :action-text="t('report.goRequirements')" @action="router.push('/requirements')" />
          </el-tab-pane>

          <el-tab-pane :label="t('report.tabCoverage')" name="coverage">
            <div v-if="cov" class="stat-grid" data-test="coverage-stats">
              <div class="stat-card"><div class="stat-num" data-test="cov-total">{{ cov.totalRequirements }}</div><div class="stat-label">{{ t('report.requirementTotal') }}</div></div>
              <div class="stat-card"><div class="stat-num" data-test="cov-covered">{{ cov.coveredRequirements }}</div><div class="stat-label">{{ t('report.coveredReqs') }}</div></div>
              <div class="stat-card"><div class="stat-num" data-test="cov-rate"><CaliberTooltip code="req-coverage">{{ pct(cov.coverageRate) }}%</CaliberTooltip></div><div class="stat-label">{{ t('report.coverageRate') }}</div></div>
            </div>
            <DataTablePage v-if="cov && cov.byChapter.length > 0" :data="cov.byChapter" :loading="loading" data-test="coverage-chapter-table">
              <el-table-column prop="chapter" :label="t('report.chapter')" min-width="180" />
              <el-table-column prop="total" :label="t('report.requirementTotal')" width="120" />
              <el-table-column prop="covered" :label="t('report.coveredReqs')" width="120" />
              <el-table-column :label="t('report.coverageRate')" width="200">
                <template #default="{ row }">
                  <el-progress :percentage="Math.round(row.rate * 100)" :stroke-width="12" />
                </template>
              </el-table-column>
            </DataTablePage>
            <EmptyState v-else-if="!loading && !cov" :title="t('report.emptyCoverage')" :action-text="t('report.goRequirements')" @action="router.push('/requirements')" />
          </el-tab-pane>

          <el-tab-pane :label="t('report.tabExecution')" name="execution_stat">
            <div class="exec-filter" data-test="exec-plan-filter">
              <el-select
                v-model="execPlanId"
                clearable
                :placeholder="t('report.planFilterPlaceholder')"
                style="width: 240px"
                data-test="exec-plan-select"
                filterable
                @change="loadExecStat"
              >
                <el-option v-for="p in execPlans" :key="p.id" :label="p.name" :value="p.id" />
              </el-select>
              <span class="exec-filter-tip">{{ t('report.planFilterTip') }}</span>
            </div>
            <div v-if="exec" class="stat-grid" data-test="exec-stats">
              <div class="stat-card">
                <div class="stat-num" data-test="exec-pass">{{ exec.trend.reduce((s, p) => s + p.passCount, 0) }}</div>
                <div class="stat-label">{{ t('report.pass') }}</div>
              </div>
              <div class="stat-card">
                <div class="stat-num" data-test="exec-fail">{{ exec.trend.reduce((s, p) => s + p.failCount, 0) }}</div>
                <div class="stat-label">{{ t('report.fail') }}</div>
              </div>
              <div class="stat-card">
                <div class="stat-num" data-test="exec-timeout">{{ exec.trend.reduce((s, p) => s + p.timeoutCount, 0) }}</div>
                <div class="stat-label">{{ t('report.timeout') }}</div>
              </div>
            </div>
            <div v-if="exec && exec.failDist.length > 0" class="status-dist" data-test="exec-fail-dist">
              <el-tag v-for="f in exec.failDist" :key="f.errorType" class="status-tag" type="danger">
                {{ f.label }}：{{ f.count }}
              </el-tag>
            </div>
                        <EmptyState v-else-if="!loading && !exec" :title="t('report.emptyExec')" :action-text="t('report.goExecution')" @action="router.push('/execution')" />
          </el-tab-pane>

 
          <el-tab-pane :label="t('report.tabCompare')" name="compare">
            <div class="compare-toolbar" data-test="compare-toolbar">
              <el-select
                v-model="compareIds"
                multiple
                filterable
                collapse-tags
                collapse-tags-tooltip
                :multiple-limit="COMPARE_MAX"
                :placeholder="t('report.compareSelectPlaceholder')"
                style="width: 420px"
                data-test="compare-project-select"
              >
                <el-option
                  v-for="p in allProjects"
                  :key="p.id"
                  :label="p.archivedLabel ? `${p.name}（${p.archivedLabel}）` : p.name"
                  :value="p.id"
                />
              </el-select>
              <el-button
                type="primary"
                :loading="compareLoading"
                :disabled="compareIds.length < 2"
                data-test="compare-run-btn"
                @click="runCompare"
              >
                {{ t('report.compareRun') }}
              </el-button>
              <span class="compare-tip">{{ t('report.compareTip') }}</span>
            </div>

            <div v-if="compareError" class="compare-error" data-test="compare-error">
              <el-alert :title="compareError" type="warning" show-icon :closable="false" />
            </div>

            <DataTablePage
              v-if="compareItems.length > 0"
              :data="compareItems"
             
              data-test="compare-table"
            >
              <el-table-column :label="t('report.project')" min-width="180">
                <template #default="{ row }">
                  <span data-test="compare-project-name">{{ row.projectName }}</span>
                  <el-tag v-if="row.archived" size="small" type="info" style="margin-left: 6px" data-test="compare-archived-tag">
                    {{ t('report.archivedTag') }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column :label="t('report.coverageRate')" width="140">
                <template #default="{ row }">
                  <span data-test="compare-cov-rate"><CaliberTooltip code="req-coverage">{{ pct(row.coverage.rate) }}%</CaliberTooltip></span>
                  <div class="compare-sub">{{ row.coverage.covered }} / {{ row.coverage.total }}</div>
                </template>
              </el-table-column>
              <el-table-column :label="t('report.firstPassRate')" width="130">
                <template #default="{ row }">
                  <span data-test="compare-first-pass"><CaliberTooltip code="ai-accept-rate">{{ pct(row.aiQuality.firstPassRate) }}%</CaliberTooltip></span>
                  <div class="compare-sub">{{ row.aiQuality.firstPassCount }} / {{ row.aiQuality.totalReviewed }}</div>
                </template>
              </el-table-column>
              <el-table-column :label="t('report.modifyRate')" width="110">
                <template #default="{ row }">{{ pct(row.aiQuality.modifyRate) }}%</template>
              </el-table-column>
              <el-table-column :label="t('report.rejectRate')" width="110">
                <template #default="{ row }">{{ pct(row.aiQuality.rejectRate) }}%</template>
              </el-table-column>
              <el-table-column :label="t('report.execStatCol')" width="170">
                <template #default="{ row }">
                  <span data-test="compare-exec">
                    <span class="cell-pass">{{ t('report.pass') }} {{ row.exec.pass }}</span>
                    <span class="cell-fail">{{ t('report.fail') }} {{ row.exec.fail }}</span>
                    <span class="cell-skip">{{ t('report.skip') }} {{ row.exec.skip }}</span>
                  </span>
                </template>
              </el-table-column>
              <el-table-column :label="t('report.flakyCol')" width="110">
                <template #default="{ row }">
                  <el-tag :type="row.flaky > 0 ? 'warning' : 'success'" size="small" data-test="compare-flaky">
                    {{ row.flaky }}
                  </el-tag>
                </template>
              </el-table-column>
            </DataTablePage>
            <EmptyState v-else-if="!compareLoading && !compareError" :title="t('report.compareNoData')" :hint="t('report.compareTip')" />
          </el-tab-pane>
        </el-tabs>

        
        <div class="export-tasks" data-test="export-tasks">
          <div class="export-tasks-head">
            <h3 class="section-title">{{ t('report.exportTasks') }}</h3>
            <span v-if="exportFollowing" class="follow-badge" data-test="report-export-following">{{
              t('report.exportFollowing') }}</span>
          </div>
          <DataTablePage v-if="exportTasks.length > 0" :data="exportTasks" :loading="loading">
            <el-table-column :label="t('report.reportType')" min-width="130">
              <template #default="{ row }">{{ REPORT_TYPE_LABEL[row.reportType as ReportType] || row.reportType }}</template>
            </el-table-column>
            <el-table-column :label="t('report.rowCount')" width="100">
              <template #default="{ row }">{{ row.rowCount ?? '-' }}</template>
            </el-table-column>
            <el-table-column :label="t('report.status')" width="120">
              <template #default="{ row }">
                <el-tag :type="row.status === 2 ? 'success' : row.status === 3 ? 'danger' : 'warning'" size="small">
                  {{ row.status === 2 ? t('report.done') : row.status === 3 ? t('report.failed') : t('report.processing') }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="createdAt" :label="t('report.createdAt')" min-width="160" >
              <template #default="{ row }"><TimeCell :iso="row.createdAt" /></template>
            </el-table-column>
            <el-table-column :label="t('report.operation')" width="120">
              <template #default="{ row }">
                <el-button v-if="row.status === 2" link type="primary" size="small" data-test="export-task-download"
                  @click="downloadTask(row.id)">
                  {{ t('report.download') }}
                </el-button>
              </template>
            </el-table-column>
          </DataTablePage>
          <EmptyState v-else-if="!loading" :title="t('report.noExportTasks')" :hint="t('report.exportTasks')" />
        </div>
      </template>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import { useListAutoFollow } from '@/composables/useListAutoFollow'
import { listAutoFollowMs } from '@/composables/tailWindowConfig'
import CoverageMatrixView from '@/views/coverage/CoverageMatrixView.vue'
import ReportCenterView from '@/views/report-center/ReportCenterView.vue'
import { getProjects, type Project } from '@/api/project'
import { listPlans, type PlanView } from '@/api/plan'
import { downloadBase64Xlsx } from '@/utils/exportDownload'
import {
  getPersonalReview,
  getTeamBoard,
  getAiQuality,
  getCoverage,
  getExecutionStat,
  getPhaseDist,
  getReportCompare,
  exportReport,
  listExportTasks,
  getExportTaskDownload,
  REPORT_TYPE_LABEL,
  type ReportType,
  type PersonalReviewView,
  type TeamMemberStatView,
  type AiQualityView,
  type CoverageView,
  type ExecutionStatView,
  type ExportTaskView,
  type PhaseCount,
  type CompareItemView,
} from '@/api/report'
import CaliberTooltip from '@/components/CaliberTooltip.vue'


const { t } = useI18n()
const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()

// ---------- half A/B: page-level tab (board / coverage / export), URL-query synced ----------
/** Query value wins on entry; an unknown key falls back to the reports board. */
type ReportPageTab = 'board' | 'coverage' | 'export'
function pageTabFromQuery(): ReportPageTab {
  const tab = route.query.tab
  return tab === 'coverage' || tab === 'export' ? tab : 'board'
}
const pageTab = ref<ReportPageTab>(pageTabFromQuery())

// Switching tabs writes the query back (replace = no history spam), so any
// tab state is shareable/refresh-stable per the AN tab-in-URL rule.
watch(pageTab, (tab) => {
  void router.replace({ query: { ...route.query, tab: tab === 'board' ? undefined : tab } })
})
// External navigations into this page carrying ?tab=coverage|export (redirect
// from the retired pages, ⌘K deep link) must move the visible tab, not just
// the URL.
watch(() => route.query.tab, () => {
  pageTab.value = pageTabFromQuery()
})

const loading = ref(true)
const projects = ref<Project[]>([])

const { projectId, followTick } = useProjectPageContext()
const activeTab = ref<ReportType | 'compare'>('personal_review')
// Team board gate mirrors the single backend authority (admin ProjectAccessGuard
// .assertCanViewReport "team_board"): system ADMIN or project owner (role>=2),
// never DEVELOPER. Without this pre-gate a plain member selecting a project
// fired a request that is 403 by design, and the all-or-nothing Promise.all
// blanked every other stat block plus raised an error toast (report-center
// permission-legs E2E red, 2026-11-04). Honest absence instead: the tab is
// not rendered for users who cannot view it.
const canViewTeamBoard = computed(() => {
  const roles = (authStore.user?.roles ?? []).map((r) => String(r).toUpperCase())
  if (roles.includes('DEVELOPER')) return false
  if (roles.includes('ADMIN')) return true
  const selected = projects.value.find((p) => p.id === projectId.value)
  return (selected?.role ?? 0) >= 2
})
const masked = ref(false)
const exporting = ref(false)

const personal = ref<PersonalReviewView | null>(null)
const team = ref<TeamMemberStatView[]>([])
const ai = ref<AiQualityView | null>(null)
const cov = ref<CoverageView | null>(null)
const exec = ref<ExecutionStatView | null>(null)
const phaseDist = ref<PhaseCount[]>([])
const exportTasks = ref<ExportTaskView[]>([])

// R62: the export-task panel is kept fresh by the SHARED follow beat (one
// loop implementation for every list panel, R40). The old submit-side
// 60x2s poll loop and the 10s mount interval are both retired: the
// panel settles on its own once rows reach a terminal state, and the user
// never needs a manual refresh. Pending is the complement of the terminal
// states stamped by the core writer side (ExportTaskSupport: 2 done, 3 failed).
const EXPORT_TERMINAL = new Set([2, 3])
const exportFollow = useListAutoFollow<ExportTaskView>({
  active: () => pageTab.value === 'board' && !!projectId.value,
  rows: () => exportTasks.value,
  isPending: (row) => !EXPORT_TERMINAL.has(row.status),
  reload: async () => { await refreshTasks() },
  intervalMs: () => listAutoFollowMs.value,
})
const exportFollowing = exportFollow.following
const execPlans = ref<PlanView[]>([])
const execPlanId = ref<number | null>(null)

const COMPARE_MAX = 20
const compareIds = ref<number[]>([])
const compareItems = ref<CompareItemView[]>([])
const compareLoading = ref(false)
const compareError = ref('')

const allProjects = ref<(Project & { archivedLabel?: string })[]>([])

async function loadAllProjects(): Promise<void> {
  if (allProjects.value.length > 0) return
  try {
    const [active, archived] = await Promise.all([
      getProjects({ page: 1, size: 100, status: 1 }),
      getProjects({ page: 1, size: 100, status: 2 }),
    ])
    allProjects.value = [
      ...(active.list || []),
      ...(archived.list || []).map((p) => ({ ...p, archivedLabel: t('report.archivedTag') })),
    ]
  } catch {
    allProjects.value = []
  }
}

async function runCompare(): Promise<void> {
  compareError.value = ''
  if (compareIds.value.length < 2) return
  if (compareIds.value.length > COMPARE_MAX) {
    compareError.value = t('report.compareTooMany')
    return
  }
  compareLoading.value = true
  try {
    compareItems.value = await getReportCompare(compareIds.value)
  } catch (e: any) {
    compareItems.value = []
    compareError.value = e?.message || t('report.compareFailed')
  } finally {
    compareLoading.value = false
  }
}

async function loadProjects(): Promise<void> {
  try {
    const page = await getProjects({ page: 1, size: 100, status: 1 })
    projects.value = page.list || []
    if (projectId.value) await loadAll()
  } finally {
    loading.value = false
  }
}

async function loadExecPlans(): Promise<void> {
  if (!projectId.value) return
  try {
    const res = await listPlans({ projectId: projectId.value, page: 1, size: 100 })
    execPlans.value = res.list || []
  } catch {
    execPlans.value = []
  }
}

async function loadExecStat(): Promise<void> {
  if (!projectId.value) return
  try {
    exec.value = await getExecutionStat(projectId.value, execPlanId.value || undefined)
  } catch {
    exec.value = null
  }
}

async function loadAll(): Promise<void> {
  if (!projectId.value) return
  loading.value = true
  try {
    const [p, q, cv, es, tasks] = await Promise.all([
      getPersonalReview(projectId.value),
      getAiQuality(projectId.value),
      getCoverage(projectId.value),
      getExecutionStat(projectId.value, execPlanId.value || undefined),
      listExportTasks(projectId.value),
    ])
    personal.value = p
    ai.value = q
    cov.value = cv
    exec.value = es
    exportTasks.value = tasks
    // Team board is owner/admin-only (gate above); fetched standalone so a
    // failure there never blanks the other stats (same isolation pattern as
    // getPhaseDist below). Deep links to ?tab=team_board fall back to the
    // first tab for users without visibility.
    if (canViewTeamBoard.value) {
      try {
        team.value = await getTeamBoard(projectId.value)
      } catch {
        team.value = []
      }
    } else {
      team.value = []
      if (activeTab.value === 'team_board') activeTab.value = 'personal_review'
    }
    await loadExecPlans()
    try {
      phaseDist.value = await getPhaseDist(projectId.value)
    } catch {
      phaseDist.value = []
    }
  } catch (e: any) {
    ElMessage.error(e?.message || t('report.loadFailed'))
  } finally {
    loading.value = false
  }
}


watch(followTick, () => {
  if (projectId.value) void loadAll()
})

function onTabChange(name: string | number): void {
  if (exportTasks.value.some((x) => x.status === 1)) {
    void refreshTasks()
  }
  if (name === 'compare') {
    void loadAllProjects()
  }
}

async function refreshTasks(): Promise<void> {
  if (!projectId.value) return
  try {
    exportTasks.value = await listExportTasks(projectId.value)
  } catch {
    
  }
}

async function onExport(type: ReportType): Promise<void> {
  if (!projectId.value) return
  exporting.value = true
  try {
    const result = await exportReport(projectId.value, type, masked.value)
    if (result.async) {
      ElMessage.success(t('report.exportAsyncStarted'))
      // Re-read once so the processing row lands on screen immediately,
      // then hand freshness to the shared follow beat (no second channel).
      await refreshTasks()
      exportFollow.start()
    } else if (result.content) {
      downloadBase64Xlsx(result.content, result.fileName || `${type}.xlsx`)
      ElMessage.success(t('report.exportDone'))
    }
  } catch (e: any) {
    ElMessage.error(e?.message || t('report.exportFailed'))
  } finally {
    exporting.value = false
  }
}

async function downloadTask(taskId: number): Promise<void> {
  if (!projectId.value) return
  try {
    const view = await getExportTaskDownload(projectId.value, taskId)
    const win = window.open(view.presignedUrl, '_blank')
    if (!win) {
      ElMessage.warning(t('report.downloadBlocked'))
    }
  } catch (e: any) {
    ElMessage.error(e?.message || t('report.downloadFailed'))
  }
}


function pct(rate: number): number {
  return Math.round((rate || 0) * 1000) / 10
}

function fmtSeconds(sec: number): string {
  if (!sec) return '0s'
  if (sec < 60) return `${Math.round(sec)}s`
  return `${Math.round(sec / 60)}m`
}

function statusTagType(status: number): 'success' | 'warning' | 'danger' | 'primary' {
  return status === 3 ? 'success' : status === 5 ? 'danger' : status === 4 ? 'warning' : 'primary'
}


function phaseTagType(phase: number): 'info' | 'success' | 'warning' | 'danger' | 'primary' {
  return phase === 1 ? 'danger' : phase === 2 ? 'primary' : phase === 3 ? 'warning' : phase === 4 ? 'success' : 'info'
}

onMounted(async () => {
  await loadProjects()
  // Returning to the page with rows still processing resumes following;
  // start() self-checks active + pending, so it is a no-op otherwise.
  exportFollow.start()
})
</script>

<style scoped>
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  flex-wrap: wrap;
  gap: 8px;
}
.toolbar-left {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.toolbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
}
.status-dist {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 8px;
}
.exec-filter {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}
.exec-filter-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.compare-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.compare-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.compare-error {
  margin-bottom: 12px;
}
.compare-sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.cell-pass {
  color: var(--el-color-success);
  margin-right: 6px;
}
.cell-fail {
  color: var(--el-color-danger);
  margin-right: 6px;
}
.cell-skip {
  color: var(--el-text-color-secondary);
}
.section-title {
  font-size: 16px;
  margin: 20px 0 12px;
}
/* R62 follow-badge sits on the section-title baseline: it is a status hint,
   not a second title, and keeps the panel height fixed while following. */
.export-tasks-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.export-tasks {
  margin-top: 8px;
}


.phase-label { font-size: 12px; margin-right: 8px; }
</style>
