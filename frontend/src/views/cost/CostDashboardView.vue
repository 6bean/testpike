<template>
  <div class="page-root" data-test="cost-dashboard-page">
    <div class="page-card">
      <!-- AN-6 (cost-domain merge): the Token calibration page lives on
 this page as a gated tab (money spent + how the estimates deviate
 from provider usage are one story). Tab state rides the URL query
 (?tab=calibration) so deep links and the retired /usage-calibration
 redirect land on the right tab; the tab mirrors the retired route's
 perm key (perm:system:system-config) — R24 face ④: permission domain
 does not narrow. CostDashboard is not on the keep-alive whitelist,
 so fresh setup per mount re-reads the query (no activation hook). -->
      <el-tabs v-model="pageTab" data-test="cost-page-tabs" class="cost-page-tabs">
        <el-tab-pane name="board">
          <template #label><span data-test="cost-tab-board">{{ t('cost.pageTabBoard') }}</span></template>
        </el-tab-pane>
        <el-tab-pane v-if="canCalibration" name="calibration">
          <template #label><span data-test="cost-tab-calibration">{{ t('cost.pageTabCalibration') }}</span></template>
        </el-tab-pane>
      </el-tabs>

      <!-- Calibration tab = UsageCalibrationView reused as-is (aggregate
           table, sample ledger, paging/sorting stay the single source). -->
      <UsageCalibrationView v-if="pageTab === 'calibration' && canCalibration" embedded />

      <template v-else>
      <div class="toolbar">
        <div class="toolbar-right">
          <el-date-picker
            v-model="month"
            type="month"
            format="YYYY-MM"
            value-format="YYYY-MM"
            :clearable="false"
            style="width: 130px"
            data-test="cost-month"
            @change="reload"
          />
          <el-button
            :disabled="!projectId"
            data-test="cost-budget-open"
            @click="openBudgetDialog"
          >{{ t('cost.editBudget') }}</el-button>
        </div>
      </div>

      
      <el-alert
        v-if="banner"
        :type="banner.type"
        :closable="false"
        show-icon
        class="water-banner"
        data-test="cost-banner"
      >
        <template #title>
          <span :data-test="`cost-banner-${banner.kind}`">{{ banner.text }}</span>
          <el-button
            v-if="banner.kind === 'break' && authStore.isAdmin"
            type="warning"
            size="small"
            link
            data-test="cost-override-open"
            @click="openOverrideDialog"
          >{{ t('cost.overrideAction') }}</el-button>
        </template>
      </el-alert>

      
      <el-row :gutter="16" class="stat-row equal-height">
        <el-col :span="8">
          <div class="stat-card">
            <div class="stat-num" data-test="cost-total">
              <CaliberTooltip code="token-cost-month">¥{{ fmt(overview?.total.amount) }}</CaliberTooltip>
            </div>
            <div class="stat-label">{{ t('cost.monthTotal') }}（{{ t('cost.calls', { n: overview?.total.calls ?? 0 }) }}）</div>
          </div>
        </el-col>
        <el-col :span="8">
          <div class="stat-card">
            <div class="stat-num" data-test="cost-budget">{{ budgetText }}</div>
            <div class="stat-label">{{ t('cost.monthBudget') }}{{ budget?.overridden ? '' : `（${t('cost.followGlobal')}）` }}</div>
          </div>
        </el-col>
        <el-col :span="8">
          <div class="stat-card">
            <div class="stat-num" :class="momClass" data-test="cost-mom">{{ momText }}</div>
            <div class="stat-label">{{ t('cost.momVsPrev') }} ¥{{ fmt(prevTotal) }}</div>
          </div>
        </el-col>
      </el-row>

      
      <el-row :gutter="16" class="dim-row">
        <el-col :span="12">
          <div class="detail-title">{{ t('cost.byProject') }}</div>
          <el-table border :data="topN(overview?.byProject)" v-loading="loading" data-test="cost-dim-project">
            <el-table-column prop="value" :label="t('cost.dimProject')" min-width="140" show-overflow-tooltip />
            <el-table-column :label="t('cost.amount')" width="110" align="right">
              <template #default="{ row }"><CaliberTooltip code="token-cost-project">¥{{ fmt(row.amount) }}</CaliberTooltip></template>
            </el-table-column>
            <el-table-column prop="calls" :label="t('cost.callsCol')" width="96" align="right" />
          </el-table>
        </el-col>
        <el-col :span="12">
          <div class="detail-title">{{ t('cost.byModel') }}</div>
          <el-table border :data="topN(overview?.byModel)" v-loading="loading" size="small" data-test="cost-dim-model">
            <el-table-column prop="value" :label="t('cost.dimModel')" min-width="140" show-overflow-tooltip />
            <el-table-column :label="t('cost.amount')" width="110" align="right">
              <template #default="{ row }"><CaliberTooltip code="token-cost-model">¥{{ fmt(row.amount) }}</CaliberTooltip></template>
            </el-table-column>
            <el-table-column prop="calls" :label="t('cost.callsCol')" width="96" align="right" />
          </el-table>
        </el-col>
      </el-row>
      <el-row :gutter="16" class="dim-row">
        <el-col :span="12">
          <div class="detail-title">{{ t('cost.byCaller') }}</div>
          <el-table border :data="topN(overview?.byCaller)" v-loading="loading" size="small" data-test="cost-dim-caller">
            <el-table-column prop="value" :label="t('cost.dimCaller')" min-width="140" show-overflow-tooltip />
            <el-table-column :label="t('cost.amount')" width="110" align="right">
              <template #default="{ row }"><CaliberTooltip code="token-cost-caller">¥{{ fmt(row.amount) }}</CaliberTooltip></template>
            </el-table-column>
            <el-table-column prop="calls" :label="t('cost.callsCol')" width="96" align="right" />
          </el-table>
        </el-col>
        <el-col :span="12">
          <div class="detail-title">{{ t('cost.byDay') }}</div>
          <el-table border :data="overview?.byDay || []" v-loading="loading" size="small" max-height="300" data-test="cost-dim-day">
            <el-table-column prop="value" :label="t('cost.dimDay')" width="120" />
            <el-table-column :label="t('cost.amount')" width="110" align="right">
              <template #default="{ row }"><CaliberTooltip code="token-cost-day">¥{{ fmt(row.amount) }}</CaliberTooltip></template>
            </el-table-column>
            <el-table-column prop="calls" :label="t('cost.callsCol')" width="96" align="right" />
          </el-table>
        </el-col>
      </el-row>

      <!-- E9 session attribution : which conversations burned
 tokens this month. Server-side paging (S-group worst case), drift
 pair columns side by side — windowTokens recomputed from the message
 ledger, sessionTotalTokens the lifetime ledger. -->
      <div class="detail-title session-title">
        {{ t('cost.bySession') }}
        <el-tooltip :content="t('cost.sessionWindowHint')" placement="top">
          <span class="session-hint" data-test="cost-session-hint">ⓘ</span>
        </el-tooltip>
      </div>
      <el-table border :data="sessionRows" v-loading="sessionLoading" size="small" data-test="cost-dim-session">
        <el-table-column prop="title" :label="t('cost.dimSession')" min-width="200" show-overflow-tooltip />
        <el-table-column prop="projectName" :label="t('cost.dimProject')" width="150" show-overflow-tooltip />
        <el-table-column prop="userName" :label="t('cost.dimInitiator')" width="120" show-overflow-tooltip />
        <el-table-column prop="assistantTurns" :label="t('cost.colTurns')" width="80" align="right" />
        <el-table-column :label="t('cost.colWindowTokens')" width="130" align="right">
          <template #default="{ row }"><span data-test="cost-session-window">{{ row.windowTokens }}</span></template>
        </el-table-column>
        <el-table-column :label="t('cost.colLifetimeTokens')" width="140" align="right">
          <template #default="{ row }">
            <span data-test="cost-session-lifetime">{{ row.sessionTotalTokens }}</span>
            <span v-if="row.sessionTotalTokens !== row.windowTokens" class="drift-mark">Δ</span>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="sessionTotal > SESSION_PAGE_SIZE" class="pagination-bar">
        <el-pagination
          layout="total, prev, pager, next"
          :total="sessionTotal"
          :page-size="SESSION_PAGE_SIZE"
          :current-page="sessionPage"
          data-test="cost-session-pager"
          @current-change="onSessionPageChange"
        />
      </div>
      </template>
    </div>

    
    <el-dialog v-model="budgetDialog" :title="t('cost.budgetDialog')" width="480px" data-test="cost-budget-dialog" draggable>
      <el-form label-width="130px">
        <el-form-item :label="t('cost.budgetYuan')">
          <el-input-number v-model="budgetFormYuan" :min="0" :precision="2" :step="10" style="width: 100%" data-test="cost-budget-yuan" />
          <div class="form-hint">{{ t('cost.budgetZeroHint') }}</div>
        </el-form-item>
        <el-form-item :label="t('cost.alertPct')">
          <el-input-number v-model="budgetForm.alertPct" :min="1" :max="100" style="width: 100%" data-test="cost-budget-alert" />
          <div class="form-hint">{{ t('cost.alertPctHint') }}</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="budgetDialog = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" data-test="cost-budget-submit" @click="submitBudget">{{ t('common.confirm') }}</el-button>
      </template>
    </el-dialog>

 
    <el-dialog v-model="overrideDialog" :title="t('cost.overrideDialog')" width="480px" data-test="cost-override-dialog" draggable>
      <el-alert type="warning" :closable="false" show-icon :title="t('cost.overrideWarning')" class="override-warning" />
      <el-form label-width="90px">
        <el-form-item :label="t('cost.overrideReason')" required>
          <el-input
            v-model="overrideForm.reason"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            :placeholder="t('cost.overrideReasonPlaceholder')"
            data-test="cost-override-reason"
          />
        </el-form-item>
        <el-form-item :label="t('cost.overrideHours')">
          <el-input-number v-model="overrideForm.hours" :min="1" :max="72" style="width: 100%" data-test="cost-override-hours" />
          <div class="form-hint">{{ t('cost.overrideHoursHint') }}</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="overrideDialog = false">{{ t('common.cancel') }}</el-button>
        <el-button type="warning" :loading="overriding" data-test="cost-override-submit" @click="submitOverride">{{ t('cost.overrideSubmit') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">

import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import CaliberTooltip from '@/components/CaliberTooltip.vue'
import UsageCalibrationView from '@/views/system/UsageCalibrationView.vue'
import { useAuthStore } from '@/stores/auth'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import { BizError } from '@/api/request'
import {
  getCostOverview,
  getCostSessionUsage,
  getCostBudget,
  putCostBudget,
  postBreakOverride,
  centsToYuan,
  type CostOverviewView,
  type CostDimItem,
  type BudgetView,
  type SessionUsageItem,
} from '@/api/cost'

const { t } = useI18n()
const authStore = useAuthStore()
const route = useRoute()
const router = useRouter()

/** Page tabs : board = the cost dashboard, calibration = the
 * retired /usage-calibration page riding as a gated tab. */
type CostPageTab = 'board' | 'calibration'

// Tab-gated permission mirror of the retired route guard (R24 face ④):
// system-config holders get the calibration tab, everyone keeps the board.
const canCalibration = computed(() => authStore.hasPerm('perm:system:system-config'))

function tabFromQuery(): CostPageTab {
  // An unauthorized (or stale) ?tab=calibration falls back to the board —
  // fail-closed, never a blank page (mock workbench inspector precedent).
  return route.query.tab === 'calibration' && canCalibration.value ? 'calibration' : 'board'
}

const pageTab = ref<CostPageTab>(tabFromQuery())

// Switching tabs writes the query back (replace = no history spam) so tab
// state is refresh/share stable (AN tab-in-URL rule).
watch(pageTab, (tab) => {
  void router.replace({ query: { ...route.query, tab: tab === 'board' ? undefined : tab } })
})
// External navigations carrying ?tab=calibration (retired-path redirect, ⌘K
// deep link) must move the visible tab, not just the URL.
watch(() => route.query.tab, () => {
  pageTab.value = tabFromQuery()
})

const TOP_N = 10

function currentMonth(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

function prevMonthOf(month: string): string {
  const [y, m] = month.split('-').map(Number)
  const d = new Date(y, m - 2, 1)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

const month = ref(currentMonth())
// R30: project switch = header global context; empty header keeps the global
// (all-projects) scope, so this page reads the wired context instead of a picker.
const { projectId, followTick } = useProjectPageContext()
const loading = ref(false)
const overview = ref<CostOverviewView | null>(null)
const prevTotal = ref<number | null>(null)
const budget = ref<BudgetView | null>(null)


function yuanToCents(yuan: number | null | undefined): number {
  return Math.round((yuan ?? 0) * 100)
}

function fmt(v: number | null | undefined): string {
  return (v ?? 0).toFixed(2)
}

function topN(items: CostDimItem[] | undefined): CostDimItem[] {
  return (items || []).slice(0, TOP_N)
}

async function reload(): Promise<void> {
  loading.value = true
  overview.value = null
  try {
    const tasks: Promise<unknown>[] = [
      getCostOverview(month.value, projectId.value).then((v) => { overview.value = v }),
      loadPrevTotal(),
    ]
    budget.value = null
    if (projectId.value) {
      tasks.push(getCostBudget(projectId.value).then((v) => { budget.value = v }))
    }
    const results = await Promise.allSettled(tasks as Promise<unknown>[])
    const failed = results.find((r) => r.status === 'rejected')
    if (failed && failed.status === 'rejected') {
      const e = failed.reason
      ElMessage.error(e instanceof BizError ? e.message : t('cost.loadFailed'))
      overview.value = null
    }
    sessionPage.value = 1
    await loadSessions()
  } finally {
    loading.value = false
  }
}

async function loadPrevTotal(): Promise<void> {
  try {
    const prev = await getCostOverview(prevMonthOf(month.value), projectId.value)
    prevTotal.value = prev.total?.amount ?? 0
  } catch {
    prevTotal.value = null
  }
}

// E9 session attribution table : server-paged, month + project
// segment ride the same params as the board (R30 header context, no second
// picker). The two token columns travel together so a cross-month drift reads
// on the row — the page never re-derives either number (red line ⑥).
const SESSION_PAGE_SIZE = 20
const sessionRows = ref<SessionUsageItem[]>([])
const sessionTotal = ref(0)
const sessionPage = ref(1)
const sessionLoading = ref(false)

async function loadSessions(): Promise<void> {
  sessionLoading.value = true
  try {
    const page = await getCostSessionUsage(month.value, projectId.value, sessionPage.value, SESSION_PAGE_SIZE)
    sessionRows.value = page.list || []
    sessionTotal.value = page.total || 0
  } catch (e) {
    // Board errors already surface through reload()'s allSettled path; the
    // attribution lane fails quietly to its empty state but keeps the message.
    sessionRows.value = []
    sessionTotal.value = 0
    ElMessage.error(e instanceof BizError ? e.message : t('cost.loadFailed'))
  } finally {
    sessionLoading.value = false
  }
}

function onSessionPageChange(p: number): void {
  sessionPage.value = p
  void loadSessions()
}

onMounted(reload)
watch(followTick, () => void reload())

const budgetText = computed(() => {
  if (!projectId.value) return '—'
  if (!budget.value) return '—'
  return budget.value.budgetCents === 0 ? t('cost.unlimited') : `¥${centsToYuan(budget.value.budgetCents)}`
})

const momText = computed(() => {
  const cur = overview.value?.total.amount ?? null
  if (cur === null || prevTotal.value === null || prevTotal.value === 0) return '—'
  const pct = ((cur - prevTotal.value) / prevTotal.value) * 100
  return `${pct > 0 ? '+' : ''}${pct.toFixed(1)}%`
})

const momClass = computed(() => {
  const cur = overview.value?.total.amount ?? null
  if (cur === null || prevTotal.value === null || prevTotal.value === 0) return ''
  return cur > prevTotal.value ? 'mom-up' : 'mom-down'
})


const banner = computed(() => {
  if (!projectId.value || !budget.value || !overview.value) return null
  const b = budget.value
  if (b.budgetCents === 0) return null
  const cents = yuanToCents(overview.value.total.amount)
  const pct = (cents / b.budgetCents) * 100
  if (pct >= 100) {
    return {
      kind: 'break' as const,
      type: 'error' as const,
      text: t('cost.bannerBreak', { used: `¥${fmt(overview.value.total.amount)}`, budget: `¥${centsToYuan(b.budgetCents)}` }),
    }
  }
  if (pct >= b.alertPct) {
    return {
      kind: 'alert' as const,
      type: 'warning' as const,
      text: t('cost.bannerAlert', { pct: pct.toFixed(1), alert: b.alertPct, used: `¥${fmt(overview.value.total.amount)}`, budget: `¥${centsToYuan(b.budgetCents)}` }),
    }
  }
  return null
})

const budgetDialog = ref(false)
const saving = ref(false)
const budgetFormYuan = ref(0)
const budgetForm = ref({ alertPct: 80 })

function openBudgetDialog(): void {
  budgetFormYuan.value = budget.value ? budget.value.budgetCents / 100 : 0
  budgetForm.value = { alertPct: budget.value?.alertPct ?? 80 }
  budgetDialog.value = true
}

async function submitBudget(): Promise<void> {
  if (!projectId.value) return
  saving.value = true
  try {
    budget.value = await putCostBudget(projectId.value, {
      budgetCents: Math.round(budgetFormYuan.value * 100),
      alertPct: budgetForm.value.alertPct,
    })
    ElMessage.success(t('cost.budgetSaved'))
    budgetDialog.value = false
    await reload()
  } catch (e: any) {
    ElMessage.error(e?.message || t('cost.budgetSaveFailed'))
  } finally {
    saving.value = false
  }
}

const overrideDialog = ref(false)
const overriding = ref(false)
const overrideForm = ref({ reason: '', hours: 24 })

function openOverrideDialog(): void {
  overrideForm.value = { reason: '', hours: 24 }
  overrideDialog.value = true
}

async function submitOverride(): Promise<void> {
  if (!projectId.value) return
  if (!overrideForm.value.reason.trim()) {
    ElMessage.warning(t('cost.overrideReasonRequired'))
    return
  }
  if (overrideForm.value.hours < 1 || overrideForm.value.hours > 72) {
    ElMessage.warning(t('cost.overrideHoursInvalid'))
    return
  }
  overriding.value = true
  try {
    const v = await postBreakOverride(projectId.value, {
      reason: overrideForm.value.reason.trim(),
      hours: overrideForm.value.hours,
    })
    ElMessage.success(t('cost.overrideDone', { until: v.overrideUntil?.replace('T', ' ') ?? '' }))
    overrideDialog.value = false
    await reload()
  } catch (e: any) {
    ElMessage.error(e?.message || t('cost.overrideFailed'))
  } finally {
    overriding.value = false
  }
}

</script>

<style scoped>
.water-banner {
  margin-bottom: 16px;
}
.stat-row {
  margin-top: 4px;
  margin-bottom: 16px;
  clear: both;
}
.dim-row {
  margin-top: 8px;
}
.stat-card {
  padding: 16px;
  border-radius: 8px;
  background: var(--el-fill-color-light);
  height: 100%;
  box-sizing: border-box;
}
.stat-num {
  font-size: 24px;
  font-weight: 600;
}
.stat-label {
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.mom-up { color: #c00000; }
.mom-down { color: #2e7d32; }
.detail-title {
  margin: 10px 0 8px;
  font-weight: 600;
}
.session-title {
  margin-top: 24px;
}
.session-hint {
  margin-left: 6px;
  font-size: 12px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
  cursor: help;
}
.drift-mark {
  margin-left: 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.form-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}
.override-warning {
  margin-bottom: 12px;
}
.equal-height {
  align-items: stretch;
}
</style>
