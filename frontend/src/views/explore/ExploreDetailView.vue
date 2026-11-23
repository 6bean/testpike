<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft } from '@element-plus/icons-vue'
import { useGoBack } from '@/composables/useGoBack'
import {
  deleteExploreSession,
  fetchExploreProcessEvents,
  fetchExploreProcessRuns,
  getExploreDetail,
  getExploreScreenshotUrl,
  isExploreTerminalRow,
  postExploreToCases,
  type ExploreSession,
  type ExploreStep,
} from '@/api/explore'
import { envTypeLabel, getProjectEnvs } from '@/api/project'
import { bizErrorMsg } from '@/utils/bizError'
import { useAuthStore } from '@/stores/auth'
import TaskTailDrawer from '@/components/TaskTailDrawer.vue'
import { useTestRunTail } from '@/composables/useTestRunTail'
import {
  loadTailWindowConfig,
  surveyTailCap,
  tailFirstFrameMs,
  tailHeartbeatSeconds,
  tailTerminalDwellMs,
} from '@/composables/tailWindowConfig'
import { exploreCanCancel, exploreCancelLabel, exploreStatusOf, fmtExploreCost } from './exploreMeta'



const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const goBack = useGoBack('/explore')
const auth = useAuthStore()

const sessionId = computed(() => Number(route.params.id))
const session = ref<ExploreSession | null>(null)
const steps = ref<ExploreStep[]>([])
const loading = ref(false)
const selectedSeqs = ref<number[]>([])


const canToCases = computed(() => auth.hasPerm('perm:script:explore'))

const canCancel = computed(() => (session.value ? exploreCanCancel(session.value, auth) : false))

const statusOf = (s: ExploreSession) => exploreStatusOf(s.status)
const fmtCost = fmtExploreCost

// ---------- (R62): process tail for the exploration run ----------
// Consume the SHARED tail base + drawer ( precedent, zero touch to the
// foundation files, R40 single source): while the session is running the
// cursor window opens on its own, frames tail-trim to the hot cap, the
// terminal frame collapses the drawer and the detail re-reads silently —
// the manual-refresh button that used to sit here is gone by design.
void loadTailWindowConfig()
const exploreTail = useTestRunTail(
  (runToken, cursor) => fetchExploreProcessEvents(Number(runToken), cursor),
  {
    terminal: isExploreTerminalRow,
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
} = exploreTail
/** Bumped whenever a run is superseded (session switch / operator cancel), so
 *  a stale cursor loop never re-reads or re-arms the wrong session. */
let tailRunEpoch = 0

function ensureTailRun(id: number, status: number): void {
  if (status !== 1 || exploreTail.running.value || exploreTail.pending.value) return
  const myEpoch = ++tailRunEpoch
  void exploreTail.openRun({ runToken: String(id) }).then(() => {
    if (myEpoch !== tailRunEpoch) return
    // R67 expiry verdict: an empty window with runs behind it means the
    // frames aged out of retention, not "nothing happened" — the shared
    // noteReplayExpiry formula (single source, no per-page re-derivation)
    // swaps the drawer to the three-element pointer instead of a blank box.
    if (exploreTail.events.value.length === 0) {
      void fetchExploreProcessRuns(id).then((runs) => {
        if (myEpoch !== tailRunEpoch) return
        exploreTail.noteReplayExpiry(runs.reduce((sum, run) => sum + run.eventCount, 0))
      })
    }
    // Terminal collapse (or the honest interrupted degrade): re-read the
    // detail in place, then re-arm the window if this session is still
    // streaming — the reconnect path stays automatic, no user action.
    void load().then(() => ensureTailRun(sessionId.value, session.value?.status ?? 0))
  })
}


function stepOk(step: ExploreStep): boolean {
  if (!step.stepResult) return false
  try {
    const parsed = JSON.parse(step.stepResult) as { ok?: boolean }
    return parsed.ok === true
  } catch {
    return false
  }
}

function actionSummary(step: ExploreStep): string {
  if (!step.stepAction) return '-'
  try {
    const parsed = JSON.parse(step.stepAction) as { name?: string; args?: Record<string, unknown> }
    const target = (parsed.args?.url || parsed.args?.selector || '') as string
    return `${parsed.name ?? '?'}${target ? ` → ${target}` : ''}`
  } catch {
    return step.stepAction.length > 80 ? `${step.stepAction.slice(0, 80)}…` : step.stepAction
  }
}

async function load() {
  if (!Number.isFinite(sessionId.value) || sessionId.value <= 0) return
  loading.value = true
  try {
    const data = await getExploreDetail(sessionId.value)
    session.value = data.session
    steps.value = data.steps
    selectedSeqs.value = []
    loadEnvName(data.session)
    ensureTailRun(data.session.id, data.session.status)
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('explore.detailLoadFailed')))
  } finally {
    loading.value = false
  }
}

const envNameCache = ref<Record<number, string>>({})

function envNameOf(envId: number): string {
  return envNameCache.value[envId] ?? `#${envId}`
}

async function loadEnvName(s: ExploreSession) {
  if (s.envId == null || envNameCache.value[s.envId]) return
  try {
    const envs = await getProjectEnvs(s.projectId)
    const hit = envs.find((e) => e.id === s.envId)
    if (hit) envNameCache.value = { ...envNameCache.value, [hit.id]: `${hit.name}（${envTypeLabel(hit.envType)}）` }
  } catch {
    
  }
}

onMounted(load)
watch(sessionId, load)

const shotUrls = ref<Record<number, string>>({})
const shotLoading = ref<Record<number, boolean>>({})

async function showScreenshot(step: ExploreStep) {
  if (shotUrls.value[step.seq]) {
    shotUrls.value = { ...shotUrls.value, [step.seq]: '' }
    return
  }
  shotLoading.value = { ...shotLoading.value, [step.seq]: true }
  try {
    shotUrls.value = { ...shotUrls.value, [step.seq]: await getExploreScreenshotUrl(sessionId.value, step.seq) }
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('explore.screenshotFailed')))
  } finally {
    shotLoading.value = { ...shotLoading.value, [step.seq]: false }
  }
}

function onSelectionChange(rows: ExploreStep[]) {
  selectedSeqs.value = rows.map((r) => r.seq)
}

async function toCases() {
  if (!selectedSeqs.value.length) {
    ElMessage.warning(t('explore.pickStepsFirst'))
    return
  }
  try {
    await ElMessageBox.confirm(
      t('explore.toCasesConfirm', { n: selectedSeqs.value.length }),
      t('explore.toCasesTitle'),
      { type: 'warning', confirmButtonText: t('explore.confirm'), cancelButtonText: t('explore.cancel') },
    )
  } catch {
    return
  }
  try {
    const ids = await postExploreToCases(sessionId.value, selectedSeqs.value)
    ElMessage.success(t('explore.toCasesDone', { n: ids.length }))
    selectedSeqs.value = []
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('explore.toCasesFailed')))
  }
}

async function onCancel() {
  if (!session.value) return
  const running = session.value.status === 1
  const label = exploreCancelLabel(session.value.status, t)
  try {
    await ElMessageBox.confirm(t('explore.cancelConfirm', { id: session.value.id, label }), t('explore.cancelTitle'), {
      type: 'warning',
      confirmButtonText: t('explore.confirm'),
      cancelButtonText: t('explore.cancel'),
    })
  } catch {
    return
  }
  try {
    const r = await deleteExploreSession(sessionId.value)
    ElMessage.success(r.action === 'terminated' ? t('explore.terminated') : t('explore.deleted'))
    if (!running) {
      router.push('/explore')
    } else {
      // Operator stop closes the window honestly (R62③): the tail stream
      // ends here, the detail re-reads once the terminal status lands.
      exploreTail.close()
      tailRunEpoch += 1
      load()
    }
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('explore.cancelFailed')))
  }
}
</script>

<template>
  <div class="page-root" data-test="explore-detail-page" v-loading="loading">
    <div class="page-card">
      <div class="page-toolbar">
        <div class="toolbar-left">
          <el-button link type="primary" :icon="ArrowLeft" data-test="explore-back" @click="goBack">{{ t('common.back') }}</el-button>
          <span v-if="session" class="page-title">#{{ session.id }} {{ session.goalText }}</span>
          <el-tag v-if="session" :type="statusOf(session).type" size="small" data-test="explore-detail-status">{{ t(statusOf(session).key) }}</el-tag>
          <!-- R62①: live badge while the process window follows the run -->
          <el-tag v-if="session && session.status === 1 && (tailRunning || tailPending)" type="primary" effect="dark" size="small" data-test="explore-detail-tail-badge">
            {{ t('explore.tailBadge') }} · {{ tailEvents.length }}
          </el-tag>
        </div>
        <div class="toolbar-right">
          <el-button v-if="session && canCancel" type="danger" plain data-test="explore-detail-cancel" @click="onCancel">
            {{ session.status === 1 ? t('explore.terminate') : t('explore.remove') }}
          </el-button>
        </div>
      </div>

      <el-descriptions v-if="session" :column="4" border class="meta" data-test="explore-meta">
        <el-descriptions-item :label="t('explore.colStartUrl')" :span="2">{{ session.startUrl }}</el-descriptions-item>
        <el-descriptions-item :label="t('explore.colSteps')">{{ session.stepsUsed }} / {{ session.pagesVisited }}</el-descriptions-item>
        <el-descriptions-item :label="t('explore.colCost')">{{ fmtCost(session.costCents) }}</el-descriptions-item>
   
        <el-descriptions-item v-if="session.envId != null" :label="t('explore.colEnv')" :span="2">
          {{ envNameOf(session.envId) }}
        </el-descriptions-item>
        <el-descriptions-item v-if="session.credRef" :label="t('explore.colCred')" :span="2">
          {{ session.credRef }}
        </el-descriptions-item>
        <!-- data-test must sit on the inner real element: el-descriptions-item
             renders no DOM root, so attributes bound to it never reach the page. -->
        <el-descriptions-item v-if="session.loginStateName" :label="t('explore.colLoginState')" :span="2">
          <span data-test="explore-detail-login-state">{{ session.loginStateName }}</span>
        </el-descriptions-item>
        <el-descriptions-item :label="t('explore.budgetSnapshot')" :span="4">
          <code class="mono">{{ session.budgetSnapshot || '-' }}</code>
        </el-descriptions-item>
      </el-descriptions>

      <!-- (R62): the process tail window — constant height from
 the shared drawer, scroll-up pauses the follow, terminal frame
 collapses it and the detail re-reads silently -->
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
        data-test="explore-detail-tail"
      />

      <div class="section-head">
        <span class="section-title">{{ t('explore.stepsTitle') }}</span>
        <el-button v-if="canToCases" type="primary" :disabled="!selectedSeqs.length" data-test="explore-to-cases" @click="toCases">
          {{ t('explore.toCases') }}
        </el-button>
      </div>

      <el-table border v-if="steps.length" :data="steps" row-key="seq" size="small" @selection-change="onSelectionChange" data-test="explore-steps-table">
        <el-table-column type="selection" width="46" :selectable="(row: ExploreStep) => stepOk(row)" />
        <el-table-column prop="seq" :label="t('explore.colSeq')" width="60" />
        <el-table-column :label="t('explore.colAction')" min-width="240">
          <template #default="{ row }"><span class="mono">{{ actionSummary(row) }}</span></template>
        </el-table-column>
        <el-table-column :label="t('explore.colStepStatus')" width="90">
          <template #default="{ row }">
            <el-tag :type="stepOk(row) ? 'success' : 'info'" size="small" :data-test="`explore-step-ok-${row.seq}`">
              {{ stepOk(row) ? t('explore.stepOk') : t('explore.stepNg') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('explore.colEvidence')" width="150">
          <template #default="{ row }">
            <el-button
              v-if="row.screenshotKey"
              link
              type="primary"
              :loading="shotLoading[row.seq]"
              :data-test="`explore-shot-${row.seq}`"
              @click="showScreenshot(row)"
            >
              {{ shotUrls[row.seq] ? t('explore.hideShot') : t('explore.showShot') }}
            </el-button>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column type="expand" label="" width="40">
          <template #default="{ row }">
            <div class="expand-body">
              <div v-if="row.llmThinkMd" class="expand-block">
                <div class="expand-title">{{ t('explore.thinkTitle') }}</div>
                <pre class="mono">{{ row.llmThinkMd }}</pre>
              </div>
              <div class="expand-block">
                <div class="expand-title">{{ t('explore.actionTitle') }}</div>
                <pre class="mono">{{ row.stepAction || '-' }}</pre>
              </div>
              <div class="expand-block">
                <div class="expand-title">{{ t('explore.resultTitle') }}</div>
                <pre class="mono">{{ row.stepResult || '-' }}</pre>
              </div>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-else-if="!loading" :description="t('explore.stepsEmpty')" />

      
      <el-timeline v-if="Object.keys(shotUrls).filter((k) => shotUrls[Number(k)]).length" class="shots">
        <el-timeline-item
          v-for="seq in Object.keys(shotUrls).filter((k) => shotUrls[Number(k)]).map(Number).sort((a, b) => a - b)"
          :key="seq"
          :timestamp="t('explore.stepSeq', { seq })"
          placement="top"
        >
          <img :src="shotUrls[seq]" class="shot-img" :alt="t('explore.shotAlt')" :data-test="`explore-shot-img-${seq}`" />
        </el-timeline-item>
      </el-timeline>

      <div v-if="session?.reportMd" class="report" data-test="explore-report">
        <div class="section-title">{{ t('explore.reportTitle') }}</div>
        <pre class="mono report-pre">{{ session.reportMd }}</pre>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page-root {
  padding: 16px;
}
.page-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  gap: 12px;
  flex-wrap: wrap;
}
.toolbar-left {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.page-title {
  font-size: 16px;
  font-weight: 600;
}
.meta {
  margin-bottom: 16px;
}
.section-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin: 12px 0 8px;
}
.section-title {
  font-size: 14px;
  font-weight: 600;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
}
.muted {
  color: var(--el-text-color-secondary);
}
.expand-body {
  padding: 8px 16px;
}
.expand-block {
  margin-bottom: 8px;
}
.expand-title {
  font-weight: 600;
  margin-bottom: 4px;
}
.expand-body pre {
  max-height: 220px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0;
}
.shots {
  margin-top: 16px;
}
.shot-img {
  max-width: 680px;
  width: 100%;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
}
.report {
  margin-top: 20px;
}
.report-pre {
  white-space: pre-wrap;
  word-break: break-word;
  background: var(--el-fill-color-light);
  padding: 12px;
  border-radius: 6px;
  max-height: 480px;
  overflow: auto;
}
</style>
