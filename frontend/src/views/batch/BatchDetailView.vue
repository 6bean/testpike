<template>
  <div v-loading="loading" class="page-root batch-detail-page" data-test="batch-detail-page">
    <div class="page-card">
      <div class="page-header">
        <div class="header-left">
          <el-button link type="primary" :icon="ArrowLeft" data-test="batch-back-btn" @click="goBack">{{ t('common.back') }}</el-button>
          <h2 class="page-title" data-test="batch-detail-title">{{ batch?.name || '-' }}</h2>
          <el-tag v-if="batch" :type="batchStatusTag(batch.status)" data-test="batch-detail-status">{{ batch.statusLabel }}</el-tag>
          <!-- R62①: live badge while the process window follows the run, gated
               on the backend row state (not the front loop state) -->
          <el-tag v-if="batch && POLL_STATUSES.includes(batch.status) && (tailRunning || tailPending)"
            type="primary" effect="dark" size="small" data-test="batch-detail-tail-badge">
            {{ t('batch.tailBadge') }} · {{ tailEvents.length }}
          </el-tag>
        </div>
        <div class="header-actions">
          <el-button v-if="isDraft" type="primary" data-test="batch-add-case-btn" @click="openAddCases">
            {{ t('batch.addCases') }}
          </el-button>
          <el-button v-if="isDraft" data-test="batch-edit-name-btn" @click="editName">{{ t('batch.rename') }}</el-button>
          <el-button v-if="batch && batch.caseCount > 0" type="success" plain data-test="batch-save-template-btn" @click="saveAsTemplate">
            {{ t('batch.saveTemplate') }}
          </el-button>
          <el-button v-if="isDraft" type="warning" data-test="batch-start-btn" :loading="starting" @click="startBatchFlow">
            {{ t('batch.start') }}
          </el-button>
   
          <el-button v-if="canPause" data-test="batch-pause-btn" :loading="consoleBusy" @click="pauseFlow">
            {{ t('batch.pause') }}
          </el-button>
          <el-button v-if="canResume" type="warning" data-test="batch-resume-btn" :loading="consoleBusy" @click="resumeFlow">
            {{ t('batch.resume') }}
          </el-button>
   
          <el-button v-if="canRecover" type="danger" data-test="batch-recover-btn" :loading="consoleBusy" @click="recoverFlow">
            {{ t('batch.recover') }}
          </el-button>
          <el-button v-if="canCancel" type="danger" plain data-test="batch-cancel-btn" :loading="consoleBusy" @click="cancelFlow">
            {{ t('batch.cancelBatch') }}
          </el-button>
          <el-button v-if="canSkip" data-test="batch-skip-case-btn" :loading="consoleBusy" @click="skipCaseFlow">
            {{ t('batch.skipCase') }}
          </el-button>
          <el-button v-if="canRerun" type="primary" plain data-test="batch-rerun-failed-btn" :loading="consoleBusy" @click="rerunFailedFlow">
            {{ t('batch.rerunFailed') }}
          </el-button>
        </div>
      </div>

      <el-descriptions v-if="batch" :column="4" border class="meta-box" data-test="batch-meta">
        <el-descriptions-item :label="t('batch.batchNo')">{{ batch.batchNo }}</el-descriptions-item>
        <el-descriptions-item :label="t('batch.env')">{{ batch.envName || '-' }}</el-descriptions-item>
        <el-descriptions-item :label="t('batch.browserLabel')">{{ batch.browser || t('batch.browserUnset') }}</el-descriptions-item>
        
        <el-descriptions-item :label="t('batch.cleanGateLabel')">
          <el-switch v-if="isDraft" v-model="cleanFlag" :active-value="1" :inactive-value="0"
            data-test="batch-clean-flag-switch" @change="toggleCleanFlag" />
          <span v-else data-test="batch-clean-flag-readonly">{{ cleanFlag ? t('batch.cleanOn') : t('batch.cleanOff') }}</span>
        </el-descriptions-item>
        <el-descriptions-item :label="t('batch.caseCount')">{{ batch.caseCount }}</el-descriptions-item>
        <el-descriptions-item :label="t('batch.createdBy')">{{ batch.createdBy ?? '-' }}</el-descriptions-item>
        <el-descriptions-item :label="t('batch.createdAt')">{{ batch.createdAt || '-' }}</el-descriptions-item>
        <el-descriptions-item :label="t('batch.startedAt')">{{ batch.startedAt || '-' }}</el-descriptions-item>
        <el-descriptions-item :label="t('batch.finishedAt')">{{ batch.finishedAt || '-' }}</el-descriptions-item>
        <el-descriptions-item :label="t('batch.queuePos')">
          {{ batch.queuePos ? t('batch.queueNth', { n: batch.queuePos }) : '-' }}
        </el-descriptions-item>
   
        <el-descriptions-item :label="t('batch.progress')">
          <span data-test="batch-progress">{{ progressText }}</span>
        </el-descriptions-item>
        <el-descriptions-item :label="t('batch.elapsed')">
          <span data-test="batch-elapsed">{{ elapsedText }}</span>
        </el-descriptions-item>
      </el-descriptions>

      <el-alert
        v-if="batch && batch.runtimeChangeCount && batch.runtimeChangeCount > 0"
        type="warning"
        :closable="false"
        show-icon
        class="draft-tip"
        data-test="batch-runtime-change-tip"
        :title="t('batch.runtimeChangeTip', { n: batch.runtimeChangeCount })"
      />

      <el-alert
        v-if="!isDraft"
        type="info"
        :closable="false"
        show-icon
        class="draft-tip"
        data-test="batch-draft-tip"
        :title="t('batch.editLockedTip')"
      />

      <!-- B-face (R62): the process tail window — constant height from the
 shared drawer, scroll-up pauses the follow, the sealed/cancelled terminal
 frame collapses it and the rows re-read silently (no manual refresh) -->
      <TaskTailDrawer
        v-if="tailOpen"
        :events="tailEvents"
        :live="tailRunning"
        :stalled="tailStalled"
        :interrupted="tailInterrupted"
        :expired="tailExpired"
        retention-param="ai.stream.event-retention-days"
        :heartbeat-seconds="tailHeartbeatSeconds"
        :first-frame-ms="tailFirstFrameMs"
        data-test="batch-detail-tail"
      />

      <TableSkeleton v-if="caseLoading" :cols="8" data-test="batchdetailview-skeleton" />
      <el-table border :data="cases" data-test="batch-case-list" class="data-table" row-key="id" v-else>
        <el-table-column prop="sortIndex" label="#" width="56" align="center" />
        <!-- Case codes can be long; a fixed 140px column wrapped them onto two
             lines. min-width lets the column grow with available space and
             show-overflow-tooltip keeps a long code on one line with the full
             value readable on hover (same pattern as the title columns above). -->
        <el-table-column prop="caseNo" :label="t('batch.caseNo')" min-width="140" show-overflow-tooltip />
        <el-table-column prop="title" :label="t('batch.caseTitle')" min-width="240" show-overflow-tooltip />
        <el-table-column :label="t('batch.endType')" width="90">
          <template #default="{ row }">{{ END_TYPE_LABEL[row.endType] ?? row.endType }}</template>
        </el-table-column>
        <el-table-column :label="t('batch.caseVersion')" width="80" align="center">
          <template #default="{ row }">v{{ row.caseVersion }}</template>
        </el-table-column>
        <el-table-column prop="result" :label="t('batch.result')" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="batchResultTag(row.result)" size="small" data-test="batch-case-result">{{ row.resultLabel }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('batch.flaky')" width="80" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.flaky === 1" type="warning" size="small">{{ t('batch.flakyIsolatedTag') }}</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('batch.operation')" width="100" fixed="right">
          <template #default="{ row }">
            <el-button v-if="isDraft" size="small" type="danger" plain :data-test="`batch-remove-${row.caseId}`" @click="removeCase(row)">
              {{ t('batch.remove') }}
            </el-button>
            <!-- P5 time-travel replay capsule: one click on an executed
 row opens the dock with the task pointer and a prefilled
 question (never auto-sent). -->
            <el-button v-else-if="!isDraft" size="small" link type="primary"
              :data-test="`batch-replay-${row.caseId}`" @click="openReplayAsk(row)">
              {{ t('batch.replayAsk') }}
            </el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty :description="t('batch.noCases')" :image-size="70" /></template>
      </el-table>

   
      <el-dialog v-model="addVisible" :title="t('batch.addCasesTitle')" width="800px" data-test="batch-add-dialog">
        <div class="picker-toolbar">
          <el-input v-model="caseKeyword" size="small" style="width: 220px" data-test="batch-add-keyword"
            :placeholder="t('batch.caseSearchPlaceholder')" clearable @keyup.enter="loadCasesToAdd(1)" />
          <el-button size="small" data-test="batch-add-search" @click="loadCasesToAdd(1)">{{ t('batch.search') }}</el-button>
          <span class="picked-hint" data-test="batch-add-picked-count">{{ t('batch.pickedCount', { n: addSelectedIds.length }) }}</span>
        </div>
        <DataTablePage ref="addTableRef" v-loading="addLoading" :data="caseToAddRows" size="small" height="320" row-key="id"
          data-test="batch-add-table" @select="onAddSelect" @select-all="onAddSelectAll" :loading="addLoading">
          <el-table-column type="selection" width="42" reserve-selection />
          <el-table-column prop="caseNo" :label="t('batch.colCaseNo')" width="130" />
          <el-table-column prop="title" :label="t('batch.colTitle')" min-width="220" show-overflow-tooltip />
          <el-table-column :label="t('batch.endType')" width="90">
            <template #default="{ row }">{{ END_TYPE_LABEL[row.endType] ?? row.endType }}</template>
          </el-table-column>
        </DataTablePage>
        <div class="pagination-bar">
          <el-pagination v-if="caseToAddTotal > 0" v-model:current-page="caseToAddPage" :page-size="20"
            :total="caseToAddTotal" layout="prev, pager, next" small @current-change="loadCasesToAdd()" />
        </div>
        <template #footer>
          <el-button @click="addVisible = false">{{ t('batch.cancel') }}</el-button>
          <el-button type="primary" :loading="saving" data-test="batch-add-submit" @click="submitAdd">
            {{ t('batch.confirmAdd') }}
          </el-button>
        </template>
      </el-dialog>

   
      <el-dialog v-model="startResultVisible" :title="t('batch.startResultTitle')" width="640px" data-test="batch-start-result-dialog">
        <p class="start-message" data-test="batch-start-message">{{ startResult?.message || t('batch.startOk') }}</p>
        <el-descriptions v-if="startResult" :column="2" border class="start-meta">
          <el-descriptions-item :label="t('batch.status')">{{ startResult.statusLabel }}</el-descriptions-item>
          <el-descriptions-item :label="t('batch.queuePos')">
            {{ startResult.queuePos ? t('batch.queueNth', { n: startResult.queuePos }) : '-' }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('batch.estimatedStartAt')">{{ startResult.estimatedStartAt || '-' }}</el-descriptions-item>
          <el-descriptions-item :label="t('batch.skipCount')">
            {{ startResult.skipCases.length ? t('batch.skipN', { n: startResult.skipCases.length }) : t('batch.none') }}
          </el-descriptions-item>
        </el-descriptions>
        <DataTablePage v-if="startResult && startResult.skipCases.length > 0" :data="startResult.skipCases" size="small"
          class="skip-table" data-test="batch-skip-table">
          <el-table-column prop="caseNo" :label="t('batch.caseNo')" width="130" />
          <el-table-column prop="title" :label="t('batch.caseTitle')" min-width="200" show-overflow-tooltip />
          <el-table-column prop="reason" :label="t('batch.skipReason')" min-width="180" show-overflow-tooltip />
        </DataTablePage>
        <template #footer>
          <el-button type="primary" data-test="batch-start-result-close" @click="startResultVisible = false">
            {{ t('batch.gotIt') }}
          </el-button>
        </template>
      </el-dialog>
      
      <el-dialog v-model="recoverVisible" :title="t('batch.recoverTitle')" width="640px" data-test="batch-recover-dialog">
        <p class="start-message" data-test="batch-recover-message">{{ recoverResult?.message || '-' }}</p>
        <el-descriptions v-if="recoverResult" :column="2" border class="start-meta">
          <el-descriptions-item :label="t('batch.status')">{{ recoverResult.batchStatusLabel }}</el-descriptions-item>
          <el-descriptions-item :label="t('batch.recoveredCount')">{{ recoverResult.recoveredCount }}</el-descriptions-item>
          <el-descriptions-item :label="t('batch.recoverSweepCount')">{{ recoverResult.sweepReclaimedCount }}</el-descriptions-item>
          <el-descriptions-item :label="t('batch.recoverRunningCount')">{{ recoverResult.runningCount }}</el-descriptions-item>
          <el-descriptions-item :label="t('batch.recoverPendingCount')">{{ recoverResult.pendingCount }}</el-descriptions-item>
          <el-descriptions-item :label="t('batch.recoverTerminalCount')">{{ recoverResult.terminalCount }}</el-descriptions-item>
        </el-descriptions>
        <template #footer>
          <el-button type="primary" data-test="batch-recover-close" @click="recoverVisible = false">
            {{ t('batch.gotIt') }}
          </el-button>
        </template>
      </el-dialog>
   
      <el-card v-if="report" shadow="never" class="report-card" data-test="batch-report-card">
        <template #header>
          <div class="report-header-row">
            <span class="report-title" data-test="batch-report-title">{{ t('batch.reportTitle') }}</span>
            <span class="report-header-badges">
            <!-- ⑤c mock honesty badge: N tasks passed under mocks, jump to hit ledger (§12a pre-filter) -->
            <el-link
              v-if="report.mockTaskCount > 0"
              type="warning"
              underline="never"
              class="report-mock-badge"
              data-test="batch-report-mock-badge"
              @click="goMockInspector"
            >
              {{ t('batch.reportMockBadge', { n: report.mockTaskCount }) }} · {{ t('batch.reportMockBadgeHint') }}
            </el-link>
            <!-- AI-fallback honesty badge: N tasks answered by the paid LLM rung
                 (a subset of the mock count) — purple halo, same jump target -->
            <el-link
              v-if="report.llmTaskCount > 0"
              underline="never"
              class="report-llm-badge"
              data-test="batch-report-llm-badge"
              @click="goMockInspector"
            >
              {{ t('batch.reportLlmBadge', { n: report.llmTaskCount }) }} · {{ t('batch.reportLlmBadgeHint') }}
            </el-link>
            </span>
          </div>
        </template>
        <el-descriptions :column="4" border class="start-meta">
          <el-descriptions-item :label="t('batch.reportTotal')">
            <span data-test="batch-report-total">{{ report.total }}</span>
          </el-descriptions-item>
          <el-descriptions-item :label="t('batch.reportPass')">
            <span data-test="batch-report-pass">{{ report.passCount }}</span>
          </el-descriptions-item>
          <el-descriptions-item :label="t('batch.reportFail')">
            <span data-test="batch-report-fail">{{ report.failCount }}</span>
          </el-descriptions-item>
          <el-descriptions-item :label="t('batch.reportTimeout')">
            <span data-test="batch-report-timeout">{{ report.timeoutCount }}</span>
          </el-descriptions-item>
          <el-descriptions-item :label="t('batch.reportSkip')">
            <span data-test="batch-report-skip">{{ report.skipCount }}</span>
          </el-descriptions-item>
          <el-descriptions-item :label="t('batch.reportPassRate')">
            <span data-test="batch-report-rate">{{ report.passRate === null ? '-' : (report.passRate * 100).toFixed(1) + '%' }}</span>
          </el-descriptions-item>
          <el-descriptions-item :label="t('batch.reportDuration')">
            <span data-test="batch-report-duration">{{ report.durationMs === null ? '-' : fmtDuration(report.durationMs) }}</span>
          </el-descriptions-item>
          <el-descriptions-item :label="t('batch.reportEnvVersion')">
            <span data-test="batch-report-envver">{{ report.envVersionSnapshot || '-' }}</span>
          </el-descriptions-item>
        </el-descriptions>
        <div class="report-dist-row">
          <div class="report-dist">
            <div class="report-dist-title">{{ t('batch.reportFailDist') }}</div>
            <p v-if="!failDistItems.length" class="report-dist-empty" data-test="batch-report-faildist-empty">{{ t('batch.reportDistEmpty') }}</p>
            <ul v-else data-test="batch-report-faildist">
              <li v-for="item in failDistItems" :key="item.key">{{ item.label }}：{{ item.count }}</li>
            </ul>
          </div>
          <div class="report-dist">
            <div class="report-dist-title">{{ t('batch.reportAttr') }}</div>
            <p v-if="!attrItems.length" class="report-dist-empty" data-test="batch-report-attr-empty">{{ t('batch.reportDistEmpty') }}</p>
            <ul v-else data-test="batch-report-attr">
              <li v-for="item in attrItems" :key="item.key">{{ item.label }}：{{ attrPercent(item.count) }}</li>
            </ul>
          </div>
        </div>
        
        <div v-if="mobilePerfRows.length" class="mobile-perf-block" data-test="batch-mobile-perf">
          <div class="report-dist-title">{{ t('batch.mobilePerfTitle') }}</div>
          <el-table
            :data="mobilePerfRows"
            border
            size="small"
            class="mobile-perf-table"
            data-test="batch-mobile-perf-table"
          >
            <el-table-column prop="label" :label="t('batch.mobilePerfMetricCol')" min-width="130" />
            <el-table-column :label="t('batch.mobilePerfMedianCol')" min-width="110">
              <template #default="{ row }">
                <span data-test="mobile-perf-median">{{ row.medianText }}</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('batch.mobilePerfPrevCol')" min-width="120">
              <template #default="{ row }">
                <span data-test="mobile-perf-prev">{{ row.prevText }}</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('batch.mobilePerfDeltaCol')" min-width="120">
              <template #default="{ row }">
                <span :data-test="`mobile-perf-delta-${row.key}`" :class="`perf-tone-${row.tone}`">{{ row.deltaText }}</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('batch.mobilePerfSampleCol')" min-width="90">
              <template #default="{ row }">
                <span data-test="mobile-perf-taskcount">{{ row.taskCount ?? '-' }}</span>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-card>
   <!-- AO-7 stage 3: failure root-cause insight card (direct read of the core
 landing row, zero recompute here; data=null = honest absence — all-pass batch,
 gate off, or clustering pending renders no card. Halo background style:
 no dialog, no accent stripe, per the AD presentation ruling) -->
      <el-card v-if="insight" shadow="never" class="insight-card" data-test="batch-insight-card">
        <template #header>
          <div class="report-header-row">
            <span class="report-title" data-test="batch-insight-title">{{ t('batch.insightTitle') }}</span>
            <el-button
              type="primary"
              link
              data-test="batch-insight-ask"
              @click="goAskInsight"
            >{{ t('batch.insightAsk') }}</el-button>
          </div>
        </template>
        <p v-if="insight.summary" class="insight-summary" data-test="batch-insight-summary">{{ insight.summary }}</p>
        <ul class="insight-clusters" data-test="batch-insight-clusters">
          <li v-for="(c, i) in insight.clusters" :key="`${c.class ?? 'x'}-${i}`" class="insight-cluster-row">
            <span class="insight-cluster-label">{{ c.label }}</span>
            <span class="insight-cluster-count">{{ c.count }}</span>
          </li>
        </ul>
        <p v-if="insight.unattributedCount > 0" class="insight-unattributed" data-test="batch-insight-unattributed">
          {{ t('batch.insightUnattributed', { n: insight.unattributedCount }) }}
        </p>
      </el-card>
   
      <BindReservationCard v-if="batch" :batch-id="batchId" :project-id="batch.projectId" />
      <CommentPanel host-type="batch" :host-id="batchId" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ArrowLeft } from '@element-plus/icons-vue'
import { useGoBack } from '@/composables/useGoBack'
import { ElMessage, ElMessageBox, type TableInstance } from 'element-plus'
import { getCaseExecHistory } from '@/api/executor'
import { requestReplayAsk } from '@/composables/useChatDock'
import { bizErrorMsg } from '@/utils/bizError'
import { useObjectSync } from '@/composables/useObjectSync'
import CommentPanel from '@/components/CommentPanel.vue'
import TaskTailDrawer from '@/components/TaskTailDrawer.vue'
import { useTestRunTail } from '@/composables/useTestRunTail'
import {
  loadTailWindowConfig,
  surveyTailCap,
  tailFirstFrameMs,
  tailHeartbeatSeconds,
  tailTerminalDwellMs,
} from '@/composables/tailWindowConfig'
import BindReservationCard from './BindReservationCard.vue'
import { listCases, END_TYPE_LABEL, type CaseItem } from '@/api/case'
import {
  getBatch,
  getBatchInsight,
  getBatchReport,
  listBatchCases,
  pauseBatch,
  recoverBatch,
  resumeBatch,
  cancelBatch,
  saveBatchAsTemplate,
  skipBatchCase,
  startBatch,
  updateBatch,
  batchStatusTag,
  batchResultTag,
  rerunFailedBatch,
  fetchBatchProcessEvents,
  fetchBatchProcessRuns,
  isBatchTerminalRow,
  type BatchCaseView,
  type BatchInsightResult,
  type BatchRecoveryResult,
  type BatchReportResult,
  type BatchStartResult,
  type BatchView,
} from '@/api/batch'
import { projectMobilePerfRows } from '@/utils/mobilePerfDisplay'

defineOptions({ name: 'BatchDetail' })

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const goBack = useGoBack('/batches')

const batchId = Number(route.params.id || 0)
const batch = ref<BatchView | null>(null)
const cases = ref<BatchCaseView[]>([])
const loading = ref(false)
const caseLoading = ref(false)
const saving = ref(false)
const starting = ref(false)
const consoleBusy = ref(false)
const startResultVisible = ref(false)
const startResult = ref<BatchStartResult | null>(null)
const recoverVisible = ref(false)
const recoverResult = ref<BatchRecoveryResult | null>(null)
const report = ref<BatchReportResult | null>(null)
// AO-7 stage 3: insight card state (terminal batches only; null = honest absence)
const insight = ref<BatchInsightResult | null>(null)


const FAIL_DIST_LABELS: Record<string, string> = {
  '0': 'batch.reportDistUnclassified',
  '1': 'batch.reportDistDefect',
  '2': 'batch.reportDistLocate',
  '3': 'batch.reportDistEnv',
  '4': 'batch.reportDistDep',
}

const ATTR_LABELS: Record<string, string> = {
  '1': 'batch.reportAttrDefect',
  '2': 'batch.reportAttrLocate',
  '3': 'batch.reportAttrEnv',
  unclassified: 'batch.reportAttrUnclassified',
}

const failDistItems = computed(() =>
  Object.entries(report.value?.failDist ?? {})
    .sort((a, b) => a[0].localeCompare(b[0]))
    .map(([key, count]) => ({ key, label: t(FAIL_DIST_LABELS[key] ?? 'batch.reportDistUnclassified'), count })))

const attrItems = computed(() =>
  Object.entries(report.value?.attr ?? {})
    .sort((a, b) => a[0].localeCompare(b[0]))
    .map(([key, count]) => ({ key, label: t(ATTR_LABELS[key] ?? 'batch.reportAttrUnclassified'), count })))

const mobilePerfRows = computed(() => projectMobilePerfRows(report.value?.mobilePerf, t))

function attrPercent(count: number): string {
  const total = attrItems.value.reduce((s, i) => s + i.count, 0)
  return total === 0 ? '-' : `${count}（${((count / total) * 100).toFixed(1)}%）`
}
async function loadReport(): Promise<void> {
  if (!batch.value || ![6, 7].includes(batch.value.status)) {
    report.value = null
    return
  }
  try {
    report.value = await getBatchReport(batchId)
  } catch {
    report.value = null
  }
}

/** AO-7 stage 3: insight card snapshot (terminal batches only; null/4xx = honest absence). */
async function loadInsight(): Promise<void> {
  if (!batch.value || ![6, 7].includes(batch.value.status)) {
    insight.value = null
    return
  }
  try {
    insight.value = await getBatchInsight(batchId)
  } catch {
    insight.value = null
  }
}

/**
 * AO-7 stage 3: the "ask root cause" deep link — jumps to the chat page
 * with the question pre-filled (?ask=, consumed by ChatPanel into the composer; never
 * auto-sent) and batchId riding the URL (context whitelist already carries
 * batchId → the session lands with this batch as its object pointer).
 */
function goAskInsight(): void {
  void router.push({
    path: '/ai/chat',
    query: { ask: t('batch.insightAskText', { batchId }), batchId: String(batchId) },
  })
}

/**
 * P5 replay capsule: resolve the case's latest run inside THIS batch
 * (execution history filtered by batch id), park the task pointer on the
 * dock's existing badge channel and prefill the replay question — nothing
 * sends until the human presses send. A batch with no run for the row is an
 * honest door, never a silent button.
 */
async function openReplayAsk(row: BatchCaseView): Promise<void> {
  const projectId = batch.value?.projectId
  if (projectId == null) return
  try {
    const page = await getCaseExecHistory(row.caseId, projectId, 1, 10)
    const hit = page.list.find((item) => item.batchId === batchId)
    if (!hit) {
      ElMessage.info(t('batch.replayAskEmpty'))
      return
    }
    requestReplayAsk(hit.id, window.location.hash || '/')
  } catch {
    ElMessage.info(t('batch.replayAskEmpty'))
  }
}

/**
 * ⑤c: the mock badge jumps to the hit ledger pre-filtered by this batch's
 * project (§12a query contract — projectId rides the URL, the inspector lands on
 * its own honest pick otherwise).
 */
function goMockInspector(): void {
  const projectId = batch.value?.projectId
  void router.push({
    path: '/mock/inspector',
    query: projectId != null ? { projectId } : {},
  })
}


const isDraft = computed(() => batch.value?.status === 1)

const cleanFlag = ref(0)
watch(batch, (b) => { cleanFlag.value = b?.cleanAppData ?? 0 }, { immediate: true })

async function toggleCleanFlag(val: string | number | boolean): Promise<void> {
  try {
    await updateBatch(batchId, { cleanAppData: Number(val) })
    ElMessage.success(val ? t('batch.cleanOnOk') : t('batch.cleanOffOk'))
    await loadBatch(true)
  } catch (e: unknown) {
    ElMessage.error(bizErrorMsg(e, t('batch.cleanToggleFail')))
    await loadBatch(true)
  }
}


const canPause = computed(() => batch.value?.status === 4)

const canResume = computed(() => batch.value?.status === 5)

const canRecover = computed(() => [4, 5].includes(batch.value?.status ?? -1))

const canCancel = computed(() => [2, 3, 4, 5].includes(batch.value?.status ?? -1))

const canSkip = computed(() => [4, 5].includes(batch.value?.status ?? -1))

const canRerun = computed(() => [6, 7].includes(batch.value?.status ?? -1))


const progressText = computed(() => {
  if (!batch.value) return '-'
  const done = cases.value.filter((c) => c.result !== 4).length
  return `${done}/${batch.value.caseCount}`
})

const tickTs = ref(Date.now())
function fmtDuration(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000))
  const pad = (n: number) => String(n).padStart(2, '0')
  if (s < 3600) return `${pad(Math.floor(s / 60))}:${pad(s % 60)}`
  return `${Math.floor(s / 3600)}:${pad(Math.floor((s % 3600) / 60))}:${pad(s % 60)}`
}
const elapsedText = computed(() => {
  if (!batch.value?.startedAt) return '-'
  const start = Date.parse(batch.value.startedAt.replace(' ', 'T'))
  if (Number.isNaN(start)) return '-'
  const end = batch.value.finishedAt
    ? Date.parse(batch.value.finishedAt.replace(' ', 'T'))
    : tickTs.value
  return fmtDuration(end - start)
})


const POLL_STATUSES = [2, 3, 4, 5]
let pollTimer: ReturnType<typeof setInterval> | null = null
function startPoll(): void {
  if (pollTimer) return
  pollTimer = setInterval(() => {
    if (consoleBusy.value || startResultVisible.value || recoverVisible.value || addVisible.value) return
    if (!batch.value || !POLL_STATUSES.includes(batch.value.status)) return
    tickTs.value = Date.now()
    loadBatch(true)
    loadCases(true)
  }, 5000)
}
function stopPoll(): void {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}
watch(consoleBusy, (busy) => {
  if (busy) stopPoll()
  else startPoll()
})

// ---------- R62 B-face : process tail for the batch execution ----------
// Consume the SHARED tail base + drawer (/ExploreDetailView precedent,
// zero touch to the foundation files, R40 single source): while the batch
// sits in the running window the cursor stream opens on its own, frames
// tail-trim to the hot cap, the sealed/cancelled terminal frame collapses the
// drawer and the detail + case rows re-read silently — the run progress no
// longer depends on the human noticing the 5s list poll.
void loadTailWindowConfig()
const batchTail = useTestRunTail(
  (runToken, cursor) => fetchBatchProcessEvents(Number(runToken), cursor),
  {
    terminal: isBatchTerminalRow,
    emptyGracePolls: 600,
    maxPolls: 2400,
    closeOnTerminal: true,
    terminalDwellMs: () => tailTerminalDwellMs.value,
    cap: () => surveyTailCap.value,
    heartbeatMs: () => tailHeartbeatSeconds.value * 1000,
  },
)
const {
  events: tailEvents, running: tailRunning, drawerOpen: tailOpen,
  stalled: tailStalled, interrupted: tailInterrupted, pending: tailPending,
  expired: tailExpired,
} = batchTail
/** Bumped whenever a run is superseded (batch switch under keep-alive), so a
 *  stale cursor loop never re-reads or re-arms the wrong batch. */
let tailRunEpoch = 0

function ensureTailRun(id: number, status: number): void {
  if (!POLL_STATUSES.includes(status) || batchTail.running.value || batchTail.pending.value) return
  const myEpoch = ++tailRunEpoch
  void batchTail.openRun({ runToken: String(id) }).then(() => {
    if (myEpoch !== tailRunEpoch) return
    // R67 expiry verdict: an empty window with runs behind it means the
    // frames aged out of retention, not "nothing happened" — the shared
    // noteReplayExpiry formula (single source, no per-page re-derivation)
    // swaps the drawer to the three-element pointer instead of a blank box.
    if (batchTail.events.value.length === 0) {
      void fetchBatchProcessRuns(id).then((runs) => {
        if (myEpoch !== tailRunEpoch) return
        batchTail.noteReplayExpiry(runs.reduce((sum, run) => sum + run.eventCount, 0))
      })
    }
    // Terminal collapse (or the honest interrupted degrade): re-read the
    // detail face in place, then re-arm the window if this batch is still
    // streaming — the reconnect path stays automatic, no user action.
    void Promise.all([loadBatch(true), loadCases(true)]).then(() =>
      ensureTailRun(batchId, batch.value?.status ?? 0))
  })
}

async function loadBatch(silent = false): Promise<void> {
  if (!silent) loading.value = true
  try {
    batch.value = await getBatch(batchId)
    loadReport()
    loadInsight()
    ensureTailRun(batchId, batch.value.status)
  } catch (e: unknown) {
    ElMessage.error(bizErrorMsg(e, t('batch.loadFail')))
  } finally {
    if (!silent) loading.value = false
  }
}

async function loadCases(silent = false): Promise<void> {
  if (!silent) caseLoading.value = true
  try {
    cases.value = (await listBatchCases(batchId)) || []
  } catch {
    if (!silent) cases.value = []
  } finally {
    if (!silent) caseLoading.value = false
  }
}

// : a landed batch write anchored to THIS batch silently
// re-reads the detail face (batch card + case rows; report/insight ride
// along inside loadBatch). Announcements of other batches are ignored.
useObjectSync({
  watch: ['BATCH_WRITE'],
  anchor: (e) => e.objectId === batchId,
  onSync: () => {
    void loadBatch(true)
    void loadCases(true)
  },
})

function editName(): void {
  if (!batch.value) return
  ElMessageBox.prompt(t('batch.renamePrompt'), t('batch.rename'), {
    inputValue: batch.value.name,
    inputValidator: (v: string) => (v && v.trim() ? true : t('batch.nameRequired')),
  })
    .then(async ({ value }) => {
      await updateBatch(batchId, { name: value.trim() })
      ElMessage.success(t('batch.renameOk'))
      loadBatch()
    })
    .catch((e: unknown) => {
      if (e !== 'cancel' && typeof e === 'object') ElMessage.error(bizErrorMsg(e, t('batch.renameFail')))
    })
}

function saveAsTemplate(): void {
  if (!batch.value) return
  ElMessageBox.prompt(t('batch.saveTemplatePrompt', { name: batch.value.name }), t('batch.saveTemplate'), {
    inputValue: t('batch.templateNameSuffix', { name: batch.value.name }),
    inputValidator: (v: string) => (v && v.trim() ? true : t('batch.templateNameRequired')),
  })
    .then(async ({ value }) => {
      await saveBatchAsTemplate(batchId, value.trim())
      ElMessage.success(t('batch.saveTemplateOk'))
    })
    .catch((e: unknown) => {
      if (e !== 'cancel' && typeof e === 'object') ElMessage.error(bizErrorMsg(e, t('batch.saveTemplateFail')))
    })
}

function removeCase(row: BatchCaseView): void {
  ElMessageBox.confirm(t('batch.removeConfirm', { title: row.title }), t('batch.remove'), { type: 'warning' })
    .then(async () => {
      await updateBatch(batchId, { removeCaseIds: [row.caseId] })
      ElMessage.success(t('batch.removeOk'))
      await Promise.all([loadBatch(), loadCases()])
    })
    .catch((e: unknown) => {
      if (e !== 'cancel' && typeof e === 'object') ElMessage.error(bizErrorMsg(e, t('batch.removeFail')))
    })
}


function startBatchFlow(): void {
  if (!batch.value) return
  ElMessageBox.confirm(
    t('batch.startConfirm', { name: batch.value.name, count: batch.value.caseCount }),
    t('batch.start'),
    { type: 'warning', confirmButtonText: t('batch.start'), cancelButtonText: t('batch.cancel') },
  )
    .then(async () => {
      starting.value = true
      try {
        startResult.value = await startBatch(batchId)
        startResultVisible.value = true
        ElMessage.success(t('batch.startOk'))
        await Promise.all([loadBatch(), loadCases()])
      } catch (e: unknown) {
        ElMessage.error(bizErrorMsg(e, t('batch.startFail')))
      } finally {
        starting.value = false
      }
    })
    .catch(() => {  })
}


function consoleAction(opts: {
  confirmKey: string
  confirmArgs?: Record<string, string | number>
  titleKey: string
  failKey: string
  run: () => Promise<string>
}): void {
  const proceed = opts.confirmKey
    ? ElMessageBox.confirm(t(opts.confirmKey, opts.confirmArgs ?? {}), t(opts.titleKey), {
        type: 'warning',
        confirmButtonText: t(opts.titleKey),
        cancelButtonText: t('batch.cancel'),
      })
    : Promise.resolve()
  proceed
    .then(async () => {
      consoleBusy.value = true
      try {
        ElMessage.success(await opts.run())
        await Promise.all([loadBatch(), loadCases()])
      } catch (e: unknown) {
        ElMessage.error(bizErrorMsg(e, t(opts.failKey)))
      } finally {
        consoleBusy.value = false
      }
    })
    .catch(() => {  })
}


function pauseFlow(): void {
  consoleAction({
    confirmKey: 'batch.pauseConfirm',
    confirmArgs: { name: batch.value?.name ?? '' },
    titleKey: 'batch.pause',
    failKey: 'batch.pauseFail',
    run: async () => (await pauseBatch(batchId)).message ?? t('batch.pauseOk'),
  })
}


function resumeFlow(): void {
  consoleAction({
    confirmKey: 'batch.resumeConfirm',
    confirmArgs: { name: batch.value?.name ?? '' },
    titleKey: 'batch.resume',
    failKey: 'batch.resumeFail',
    run: async () => (await resumeBatch(batchId)).message ?? t('batch.resumeOk'),
  })
}


function recoverFlow(): void {
  ElMessageBox.confirm(t('batch.recoverConfirm', { name: batch.value?.name ?? '' }), t('batch.recover'), {
    type: 'warning',
  })
    .then(async () => {
      consoleBusy.value = true
      try {
        recoverResult.value = await recoverBatch(batchId)
        recoverVisible.value = true
        await Promise.all([loadBatch(), loadCases()])
      } catch (e: unknown) {
        ElMessage.error(bizErrorMsg(e, t('batch.recoverFail')))
      } finally {
        consoleBusy.value = false
      }
    })
    .catch(() => {  })
}


function cancelFlow(): void {
  consoleAction({
    confirmKey: 'batch.cancelBatchConfirm',
    confirmArgs: { name: batch.value?.name ?? '' },
    titleKey: 'batch.cancelBatch',
    failKey: 'batch.cancelBatchFail',
    run: async () => (await cancelBatch(batchId)).message ?? t('batch.cancelBatchOk'),
  })
}


function skipCaseFlow(): void {
  consoleAction({
    confirmKey: 'batch.skipCaseConfirm',
    titleKey: 'batch.skipCase',
    failKey: 'batch.skipCaseFail',
    run: async () => (await skipBatchCase(batchId)) || t('batch.skipCaseOk'),
  })
}


function rerunFailedFlow(): void {
  consoleAction({
    confirmKey: 'batch.rerunConfirm',
    confirmArgs: { name: batch.value?.name ?? '' },
    titleKey: 'batch.rerunFailed',
    failKey: 'batch.rerunFail',
    run: async () => {
      const created = await rerunFailedBatch(batchId)
      return t('batch.rerunOk', { count: created })
    },
  })
}

const addVisible = ref(false)
const addTableRef = ref<TableInstance>()
const caseToAddRows = ref<CaseItem[]>([])
const caseToAddTotal = ref(0)
const caseToAddPage = ref(1)
const addLoading = ref(false)
const caseKeyword = ref('')
const addSelectedIds = ref<number[]>([])

async function loadCasesToAdd(p = caseToAddPage.value): Promise<void> {
  if (!batch.value) return
  caseToAddPage.value = p
  addLoading.value = true
  try {
    const res = await listCases({
      projectId: batch.value.projectId, keyword: caseKeyword.value || undefined,
      page: caseToAddPage.value, size: 20,
    })
    caseToAddRows.value = res.list || []
    caseToAddTotal.value = res.total || 0
  } finally {
    addLoading.value = false
  }
}

function onAddSelect(selection: CaseItem[], row: CaseItem): void {
  const picked = selection.some((s) => s.id === row.id)
  addSelectedIds.value = picked
    ? [...new Set([...addSelectedIds.value, row.id])]
    : addSelectedIds.value.filter((id) => id !== row.id)
}

function onAddSelectAll(selection: CaseItem[]): void {
  const pageIds = caseToAddRows.value.map((c) => c.id)
  if (selection.length > 0) {
    addSelectedIds.value = [...new Set([...addSelectedIds.value, ...pageIds])]
  } else {
    addSelectedIds.value = addSelectedIds.value.filter((id) => !pageIds.includes(id))
  }
}

function openAddCases(): void {
  addSelectedIds.value = []
  caseKeyword.value = ''
  addTableRef.value?.clearSelection()
  addVisible.value = true
  loadCasesToAdd(1)
}

async function submitAdd(): Promise<void> {
  if (addSelectedIds.value.length === 0) {
    ElMessage.warning(t('batch.addCaseRequired'))
    return
  }
  saving.value = true
  try {
    await updateBatch(batchId, { addCaseIds: addSelectedIds.value })
    ElMessage.success(t('batch.addOk', { count: addSelectedIds.value.length }))
    addVisible.value = false
    await Promise.all([loadBatch(), loadCases()])
  } catch (e: unknown) {
    ElMessage.error(bizErrorMsg(e, t('batch.addFail')))
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  if (!batchId) {
    ElMessage.error(t('batch.badId'))
    return
  }
  await Promise.all([loadBatch(), loadCases()])
  startPoll()
})

onActivated(() => {
  if (batch.value) {
    loadBatch(true)
    loadCases(true)
  }
  startPoll()
})
onDeactivated(() => stopPoll())
onUnmounted(() => stopPoll())
</script>

<style scoped>
.page-root {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.header-left {
  display: flex;
  align-items: center;
  gap: 10px;
}
.picker-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.picked-hint {
  margin-left: auto;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.draft-tip {
  margin-bottom: 0;
}
.start-message {
  margin: 0 0 12px;
  font-size: 14px;
  line-height: 1.6;
  color: var(--el-text-color-primary);
}
.start-meta {
  margin-bottom: 12px;
}
.skip-table {
  margin-bottom: 4px;
}


.report-card {
  margin-top: 16px;
}
.report-title {
  font-weight: 600;
}
.report-header-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.report-header-badges {
  display: inline-flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  justify-content: flex-end;
}
.report-mock-badge {
  font-size: 13px;
  white-space: nowrap;
}
.report-llm-badge {
  /* AI-fallback honesty badge: purple tinted background (halo style — no side
     bar, no dialog), readable in both themes via the paired llm tokens */
  font-size: 13px;
  white-space: nowrap;
  padding: 2px 10px;
  border-radius: 6px;
  background: var(--tp-llm-weak);
  color: var(--tp-llm);
}
.mobile-perf-block {
  margin-top: 12px;
}
.perf-tone-worse {
  color: var(--el-color-danger);
}
.perf-tone-better {
  color: var(--el-color-success);
}
.report-dist-row {
  display: flex;
  gap: 32px;
  margin-top: 12px;
}
.report-dist {
  flex: 1;
  min-width: 0;
}
.report-dist-title {
  font-weight: 600;
  margin-bottom: 6px;
}
.report-dist ul {
  margin: 0;
  padding-left: 18px;
}
.report-dist-empty {
  margin: 0;
  color: var(--el-text-color-secondary);
}
/* AO-7 stage 3 insight card: halo tinted background (both-theme tokens),
 no dialog, no accent stripe — AD presentation ruling */
.insight-card {
  margin-top: 16px;
  background: var(--tp-primary-weak);
}
.insight-summary {
  margin: 0 0 8px;
  font-weight: 600;
}
.insight-clusters {
  margin: 0;
  padding: 0;
  list-style: none;
}
.insight-cluster-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 4px 0;
}
.insight-cluster-label {
  min-width: 0;
  overflow-wrap: anywhere;
}
.insight-cluster-count {
  font-weight: 600;
  white-space: nowrap;
}
.insight-unattributed {
  margin: 8px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
