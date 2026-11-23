<!--
 Question-run history door : the standing
 entry that outlives the wizard. Pick a document, list its historical
 question-generation runs (time / terminal badge / frame count), and replay
 any run's process tail through the SHARED tail foundation — useTestRunTail
 driving TaskTailDrawer, the same base the survey and probe doors use (R40,
 no second replay chain).

 Behaviour contracts:
 - a run still in progress keeps following from its cursor (badge + live
 drawer, heartbeat/stall doors on);
 - a finished run replays the stored tail in full — frames are appended
 cursor-forward and nothing is dropped;
 - a run whose frames passed the retention window answers with the
 three-element pointer (reason + parameter key + settings path), never a
 blank and never a bare error (R63).
-->
<template>
  <el-dialog
    :model-value="modelValue"
    class="tp-dialog--md"
    :title="t('judge.evalset.qaGenRuns.title')"
    data-test="qagen-runs-dialog"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <div class="qagen-runs-picker">
      <el-select
        v-model="documentId"
        filterable
        remote
        reserve-keyword
        :remote-method="searchDocs"
        :loading="docsLoading"
        :placeholder="t('judge.evalset.qaGenRuns.docPlaceholder')"
        class="w-full"
        data-test="qagen-runs-doc"
      >
        <el-option v-for="d in docOptions" :key="d.id" :label="d.name" :value="d.id" />
      </el-select>
      <el-button
        :icon="Refresh"
        circle
        :disabled="documentId == null || loading"
        :title="t('judge.evalset.qaGenRuns.refreshBtn')"
        data-test="qagen-runs-refresh"
        @click="loadRuns"
      />
    </div>
    <div v-if="projectId == null" class="qagen-runs-hint" data-test="qagen-runs-noproject">
      {{ t('judge.evalset.qaGenRuns.noProject') }}
    </div>
    <div v-else-if="documentId == null" class="qagen-runs-hint" data-test="qagen-runs-nodoc">
      {{ t('judge.evalset.qaGenRuns.pickDoc') }}
    </div>
    <TableSkeleton v-else-if="loading" :rows="4" :cols="5" />
    <EmptyState
      v-else-if="!runs.length"
      :title="t('judge.evalset.qaGenRuns.emptyTitle')"
      :hint="t('judge.evalset.qaGenRuns.emptyHint')"
      data-test="qagen-runs-empty"
    />
    <el-table v-else :data="runs" size="small" border data-test="qagen-runs-table">
      <el-table-column :label="t('judge.evalset.qaGenRuns.colStart')" min-width="150">
        <template #default="{ row }"><TimeCell :value="row.firstAt" /></template>
      </el-table-column>
      <el-table-column :label="t('judge.evalset.qaGenRuns.colLast')" min-width="150">
        <template #default="{ row }"><TimeCell :value="row.lastAt" /></template>
      </el-table-column>
      <el-table-column :label="t('judge.evalset.qaGenRuns.colStatus')" width="96">
        <template #default="{ row }">
          <el-tag
            size="small"
            :type="row.status === 'running' ? 'warning' : row.status === 'failed' ? 'danger' : 'success'"
            :data-test="`qagen-run-status-${row.runToken}`"
          >{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('judge.evalset.qaGenRuns.colFrames')" width="92" align="right">
        <template #default="{ row }">{{ row.eventCount }}</template>
      </el-table-column>
      <el-table-column :label="t('judge.evalset.qaGenRuns.colAction')" width="112" align="right">
        <template #default="{ row }">
          <el-button
            size="small"
            :disabled="tailRunning"
            :data-test="`qagen-run-tail-${row.runToken}`"
            @click="openTail(row)"
          >{{ t('judge.evalset.qaGenRuns.replayBtn') }}</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- Replay window: the shared tail drawer over the same cursor channel.
         Height stays constant while frames grow (R62 windowing contract). -->
    <el-dialog
      v-model="tailOpen"
      class="tp-dialog--md"
      append-to-body
      :title="t('judge.evalset.qaGenRuns.tailTitle', { name: tailRunLabel })"
      data-test="qagen-runs-tail-dialog"
    >
      <TaskTailDrawer
        :events="tailEvents"
        :live="tailRunning"
        :expired="tailExpired"
        retention-param="ai.stream.event-retention-days"
        :stalled="tailRunning ? tailStalled : false"
        :interrupted="tailRunning ? tailInterrupted : false"
        :heartbeat-seconds="tailHeartbeatSeconds"
        :first-frame-ms="tailFirstFrameMs"
        class="qagen-runs-tail-window"
        data-test="qagen-runs-tail-window"
      />
    </el-dialog>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import TaskTailDrawer from '@/components/TaskTailDrawer.vue'
import TimeCell from '@/components/TimeCell.vue'
import { bizErrorMsg } from '@/utils/bizError'
import { listDocuments } from '@/api/document'
import type { DocumentView } from '@/api/document'
import { getQaGenEvents, listQaGenRuns } from '@/api/evalSet'
import type { QaGenRun } from '@/api/evalSet'
import { useTestRunTail } from '@/composables/useTestRunTail'
import { tailFirstFrameMs, tailHeartbeatSeconds } from '@/composables/tailWindowConfig'

const props = defineProps<{
  modelValue: boolean
  /** R30: the project segment rides the global header context. */
  projectId: number | null
}>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
const { t } = useI18n()

const documentId = ref<number | undefined>(undefined)
const docOptions = ref<DocumentView[]>([])
const docsLoading = ref(false)
const runs = ref<QaGenRun[]>([])
const loading = ref(false)

async function searchDocs(keyword: string) {
  if (props.projectId == null) return
  docsLoading.value = true
  try {
    const res = await listDocuments({ projectId: props.projectId, keyword: keyword || undefined, page: 1, size: 50 })
    docOptions.value = res.list ?? []
  } catch (err: any) {
    ElMessage.warning(err?.message || t('judge.evalset.qaGenRuns.docLoadFailed'))
  } finally {
    docsLoading.value = false
  }
}

async function loadRuns() {
  if (documentId.value == null) {
    runs.value = []
    return
  }
  loading.value = true
  try {
    runs.value = await listQaGenRuns(documentId.value)
  } catch (err: any) {
    ElMessage.error(bizErrorMsg(err, t('judge.evalset.qaGenRuns.loadFailed')))
  } finally {
    loading.value = false
  }
}

watch(documentId, () => { void loadRuns() })
watch(() => props.modelValue, (open) => {
  if (open && props.projectId != null && !docOptions.value.length) searchDocs('')
})

function statusText(status: string): string {
  if (status === 'running') return t('judge.evalset.qaGenRuns.statusRunning')
  if (status === 'failed') return t('judge.evalset.qaGenRuns.statusFailed')
  return t('judge.evalset.qaGenRuns.statusDone')
}

// ---------- replay over the shared cursor base (R40) ----------
const activeRun = ref<QaGenRun | null>(null)
const tailRunLabel = computed(() => activeRun.value?.runToken ?? '')

// One cursor channel for every run of this door: the fetcher reads the
// document captured at open time, so the base stays the stock (token,
// cursor) contract. A failed run seals on its error frame; a done run
// drains to stream end; a live run keeps following between frames.
// The grace budget is per open: a sealed history row stops after a few
// empty polls, a live row stays patient (the base reads the option at
// each openRun, so mutating this object before the call is the seam).
const tailWindowOptions = {
  terminal: (row: { level: string }) => row.level === 'error',
  emptyGracePolls: 3,
  maxPolls: 1200,
  pollIntervalMs: 600,
  heartbeatMs: () => tailHeartbeatSeconds.value * 1000,
}
const tailBase = useTestRunTail(
  (runToken, cursor) => fetchTailWindow(runToken, cursor),
  tailWindowOptions,
)
const {
  events: tailEvents, running: tailRunning, drawerOpen: tailOpen,
  stalled: tailStalled, interrupted: tailInterrupted, expired: tailExpired, openRun,
} = tailBase

async function fetchTailWindow(runToken: string, cursor: number) {
  const doc = documentId.value
  if (doc == null) return []
  return getQaGenEvents(doc, runToken, cursor, 50)
}

async function openTail(run: QaGenRun) {
  activeRun.value = run
  // live rows follow patiently (a quiet minute of frames is still a live
  // run); sealed rows stop after a short drain so the window settles.
  tailWindowOptions.emptyGracePolls = run.status === 'running' ? 100 : 3
  await openRun({ runToken: run.runToken })
  // Retention exit: the run history proves frames existed, the cursor
  // window came back empty — the sweeper retired them, so the drawer
  // swaps to the pointer sentence instead of an empty box.
  if (tailEvents.value.length === 0 && run.eventCount > 0) tailExpired.value = true
  // A live row that just sealed should not keep a stale badge on screen.
  if (run.status === 'running') void loadRuns()
}
</script>

<style scoped>
.qagen-runs-picker { display: flex; gap: 8px; align-items: center; margin-bottom: 12px; }
.w-full { width: 100%; }
</style>
