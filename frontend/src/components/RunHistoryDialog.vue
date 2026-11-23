<!--
  Run-history door (R67 revisit entry): a standing entry that outlives the
  current run. Given a loader of aggregated run rows (the core run-history
  door, newest first) this dialog lists them (start / last activity / badge /
  frame count) and replays any run's process tail through the SHARED tail
  foundation — useTestRunTail driving TaskTailDrawer, the same base the survey
  and question-generation doors use (R40, no second replay chain).

  Behaviour contracts:
  - a fresh (possibly still running) row keeps following from its cursor with
    the patient grace budget; heartbeat/stall doors stay on;
  - a sealed row drains to stream end without dropping frames;
  - a row whose frames passed the retention window answers with the
    three-element pointer (reason + parameter key + settings path), never a
    blank and never a bare error (R63).
-->
<template>
  <el-dialog
    :model-value="modelValue"
    class="tp-dialog--md"
    :title="t('runHistory.title')"
    data-test="run-history-dialog"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <div class="run-history-toolbar">
      <el-button
        :icon="Refresh"
        circle
        :disabled="loading"
        :title="t('runHistory.refreshBtn')"
        data-test="run-history-refresh"
        @click="loadRuns"
      />
    </div>
    <TableSkeleton v-if="loading" :rows="4" :cols="5" />
    <EmptyState
      v-else-if="!runs.length"
      :title="t('runHistory.emptyTitle')"
      :hint="t('runHistory.emptyHint')"
      data-test="run-history-empty"
    />
    <el-table v-else :data="runs" size="small" border data-test="run-history-table">
      <el-table-column :label="t('runHistory.colStart')" min-width="150">
        <template #default="{ row }"><TimeCell :value="row.firstAt" /></template>
      </el-table-column>
      <el-table-column :label="t('runHistory.colLast')" min-width="150">
        <template #default="{ row }"><TimeCell :value="row.lastAt" /></template>
      </el-table-column>
      <el-table-column :label="t('runHistory.colStatus')" width="96">
        <template #default="{ row }">
          <el-tag
            size="small"
            :type="row.hasError ? 'danger' : 'success'"
            :data-test="`run-history-status-${row.runToken}`"
          >{{ row.hasError ? t('runHistory.statusFailed') : t('runHistory.statusDone') }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('runHistory.colFrames')" width="92" align="right">
        <template #default="{ row }">{{ row.eventCount }}</template>
      </el-table-column>
      <el-table-column :label="t('runHistory.colAction')" width="112" align="right">
        <template #default="{ row }">
          <el-button
            size="small"
            :disabled="tailRunning"
            :data-test="`run-history-replay-${row.runToken}`"
            @click="openTail(row)"
          >{{ t('runHistory.replayBtn') }}</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- Replay window: the shared tail drawer over the same cursor channel.
         Height stays constant while frames grow (R62 windowing contract). -->
    <el-dialog
      v-model="tailOpen"
      class="tp-dialog--md"
      append-to-body
      :title="t('runHistory.tailTitle', { name: tailRunLabel })"
      data-test="run-history-tail-dialog"
    >
      <TaskTailDrawer
        :events="tailEvents"
        :live="tailRunning"
        :doors="doors"
        :expired="tailExpired"
        :retention-param="retentionParam"
        :stalled="tailRunning ? tailStalled : false"
        :interrupted="tailRunning ? tailInterrupted : false"
        :heartbeat-seconds="tailHeartbeatSeconds"
        :first-frame-ms="tailFirstFrameMs"
        class="run-history-tail-window"
        data-test="run-history-tail-window"
      />
    </el-dialog>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import TaskTailDrawer from '@/components/TaskTailDrawer.vue'
import TimeCell from '@/components/TimeCell.vue'
import { bizErrorMsg } from '@/utils/bizError'
import type { TrailDoorApi } from '@/api/taskEvents'
import { useTestRunTail, type TestRunEvent } from '@/composables/useTestRunTail'
import { tailFirstFrameMs, tailHeartbeatSeconds } from '@/composables/tailWindowConfig'

/** One aggregated run row as returned by the core run-history door. */
export interface RunHistoryRow {
  runToken: string
  eventCount: number
  hasError: boolean
  firstAt: string
  lastAt: string
}

const props = defineProps<{
  modelValue: boolean
  /** Loads the run rows of one object through the shared read door. */
  loadRuns: () => Promise<RunHistoryRow[]>
  /** Cursor-forward frame window of one run (the existing event door). */
  fetchFrames: (runToken: string, cursor: number) => Promise<TestRunEvent[]>
  /** Gated read doors over this object's trail (reveal / full text). */
  doors?: TrailDoorApi
  /** Literal parameter key named in the expiry pointer (R32/R63). */
  retentionParam: string
}>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
const { t } = useI18n()

const runs = ref<RunHistoryRow[]>([])
const loading = ref(false)

async function loadRuns() {
  loading.value = true
  try {
    runs.value = await props.loadRuns()
  } catch (err: any) {
    ElMessage.error(bizErrorMsg(err, t('runHistory.loadFailed')))
  } finally {
    loading.value = false
  }
}

watch(() => props.modelValue, (open) => {
  if (open) void loadRuns()
})

// ---------- replay over the shared cursor base (R40) ----------
const tailRunLabel = ref('')
// A row whose last frame landed within the freshness window may still be
// running: keep the patient grace so the tail follows instead of sealing
// early. Sealed rows stop after a short drain so the window settles.
const FRESH_MS = 90_000
const tailWindowOptions = {
  terminal: (row: { level: string }) => row.level === 'error',
  emptyGracePolls: 3,
  maxPolls: 1200,
  pollIntervalMs: 600,
  heartbeatMs: () => tailHeartbeatSeconds.value * 1000,
}
const tailBase = useTestRunTail(
  (runToken, cursor) => props.fetchFrames(runToken, cursor),
  tailWindowOptions,
)
const {
  events: tailEvents, running: tailRunning, drawerOpen: tailOpen,
  stalled: tailStalled, interrupted: tailInterrupted, expired: tailExpired, openRun,
} = tailBase

async function openTail(row: RunHistoryRow) {
  tailRunLabel.value = row.runToken
  const fresh = row.lastAt ? Date.now() - new Date(row.lastAt.replace(' ', 'T')).getTime() < FRESH_MS : false
  tailWindowOptions.emptyGracePolls = fresh ? 100 : 3
  await openRun({ runToken: row.runToken })
  // Retention exit: the run history proves frames existed, the cursor
  // window came back empty — the sweeper retired them, so the drawer swaps
  // to the three-element pointer instead of an empty box (R63).
  if (tailEvents.value.length === 0 && row.eventCount > 0) tailExpired.value = true
}
</script>

<style scoped>
.run-history-toolbar { display: flex; justify-content: flex-end; margin-bottom: 8px; }
</style>
