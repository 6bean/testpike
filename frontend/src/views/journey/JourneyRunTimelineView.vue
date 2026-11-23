<!-- tp-sort-exempt: the row order IS the journey pipeline (segments run strictly
     by seqNo) and the whole segment list of one run is served in full; offering
     a sort control would invite re-ordering the execution narrative without any
     operational action behind it.
     tp-column-config-exempt: every column is light-critical — sequence, owning
     project, traffic light, the batch jump and the failure reason together form
     the single reading surface of a run; hiding the light or the batch link
     removes the page's purpose (bounded fixed-column aggregate, same basis as
     the report boards on record).
     Header shape: the run head carries the standard page-header layout token
     (title + status line left, actions right); entity name is dynamic per run,
     which is the detail-class exemption R23 allows. -->
<template>
  <div class="page-root" data-test="journey-timeline-page">
    <div class="page-card">
      <!-- Run timeline (detail page, no filter band): actions ride the page
           header per R21; the sign-off door is owner+terminal gated here for
           honesty and re-checked server-side (the disabled button points at
           the rule, it is not the authority). -->
      <div class="run-head page-header">
        <div>
          <h2 class="run-title" data-test="journey-run-title">{{ headerTitle }}</h2>
          <div class="run-sub">
            <el-tag size="small" :type="statusTagType(run?.status ?? 0)" data-test="journey-run-status">
              {{ statusLabel(run?.status ?? 0) }}
            </el-tag>
            <span v-if="run?.signedBy" class="signed-line" data-test="journey-run-signed">
              {{ t('journeys.signedLine', { id: run.signedBy, at: formatTime(run.signedAt) }) }}
            </span>
            <span v-else class="unsigned-line" data-test="journey-run-unsigned">{{ t('journeys.unsignedLine') }}</span>
            <span v-if="runFollowing" class="unsigned-line" data-test="journey-run-following">{{ t('journeys.runFollowing') }}</span>
          </div>
        </div>
        <div class="head-actions">
          <el-button data-test="journey-run-refresh" @click="loadRun()">
            <el-icon><Refresh /></el-icon>{{ t('journeys.refresh') }}
          </el-button>
          <el-tooltip :content="signOffHint" placement="top" :disabled="canSign">
            <span>
              <el-button
                type="primary"
                :disabled="!canSign"
                :loading="signing"
                data-test="journey-signoff"
                @click="signOff"
              >{{ t('journeys.signOff') }}</el-button>
            </span>
          </el-tooltip>
        </div>
      </div>

      <TableSkeleton v-if="loading" :cols="6" data-test="journey-timeline-skeleton" />
      <DataTablePage v-else :data="runSegments" row-key="id" :row-class-name="rowClassName" data-test="journey-run-segment-table">
        <el-table-column prop="seqNo" :label="t('journeys.colSeq')" width="80" align="center" />
        <el-table-column :label="t('journeys.colProject')" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ projectName(row.projectId) }}</template>
        </el-table-column>
        <el-table-column :label="t('journeys.colLight')" width="110" align="center">
          <template #default="{ row }">
            <span class="light" :class="`light-${row.status}`" :data-test="`journey-light-${row.id}`" />
            <el-tag size="small" :type="statusTagType(row.status)">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('journeys.colBatch')" min-width="140" align="center">
          <template #default="{ row }">
            <el-button
              v-if="row.batchId"
              link
              type="primary"
              :data-test="`journey-batch-${row.id}`"
              @click="router.push(`/batches/${row.batchId}`)"
            >{{ t('journeys.batchLink', { id: row.batchId }) }}</el-button>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('journeys.colFailure')" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="failureLabel(row)" class="failure-cell" :data-test="`journey-failure-${row.id}`">
              {{ failureLabel(row) }}
            </span>
            <span v-else>—</span>
          </template>
        </el-table-column>
      </DataTablePage>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * Journey run timeline : per-segment traffic lights over
 * the expanded batches. A failed segment names its reason (PARAMS_MISSING /
 * LAUNCH_FAILED / batch failure) and links straight into the batch detail of
 * the owning project. The exit sign-off is reserved to the journey owner and
 * only possible once the run reached a terminal state and stays unsigned —
 * core enforces all three and the sentences come back through the shared
 * error surface.
 */
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import DataTablePage from '@/components/DataTablePage.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import { useAuthStore } from '@/stores/auth'
import { useProjectNameLookup } from '@/composables/useProjectNameLookup'
import { getJourney, getJourneyRun, signOffJourneyRun, type JourneyRunView, type JourneyView } from '@/api/journey'
import { useListAutoFollow } from '@/composables/useListAutoFollow'
import { listAutoFollowMs } from '@/composables/tailWindowConfig'

/**
 * R62 auto-follow: while the run is not in a terminal state the timeline
 * re-reads itself on the SHARED list beat (ui.list.auto-follow-ms, one loop
 * implementation for every panel, R40) — statuses move on screen without the
 * user touching the refresh button. The beat self-stops when the run reaches
 * a terminal state and the timer is cleared on scope dispose (R57).
 */
const TERMINAL = new Set([2, 3, 4])
const runFollow = useListAutoFollow<JourneyRunView>({
  active: () => true,
  rows: () => (run.value ? [run.value] : []),
  isPending: (row) => !TERMINAL.has(row.status),
  reload: async () => { await loadRun(true) },
  intervalMs: () => listAutoFollowMs.value,
})
const runFollowing = runFollow.following

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const runId = Number(route.params.runId)
const run = ref<JourneyRunView | null>(null)
const journey = ref<JourneyView | null>(null)
const { projectName, loadProjectNames } = useProjectNameLookup()
const loading = ref(true)
const signing = ref(false)

const runSegments = computed(() => run.value?.segments ?? [])
const headerTitle = computed(() =>
  run.value ? t('journeys.runTitle', { name: journey.value?.name ?? String(run.value.journeyId), id: run.value.id }) : '—',
)

const canSign = computed(
  () => !!run.value && TERMINAL.has(run.value.status) && run.value.signedBy === null
    && auth.user?.userId === journey.value?.ownerUserId,
)
/** Known failure codes get a sentence; anything else shows the server message as-is (never fabricate a label). */
// Every code core can stamp on a segment maps to a human sentence; an
// unmapped code would land on screen as the raw token (vision review
// finding, 2026-11-05), so the set is kept in sync with the writer side.
const KNOWN_FAILURE = new Set(['PARAMS_MISSING', 'LAUNCH_FAILED', 'BATCH_FAILED', 'SKIPPED_UPSTREAM'])
/** Whole-row tint for failed/skipped segments — a light dot alone made red
 *  segments expensive to scan on the dark theme (vision review, 2026-11-05). */
function rowClassName({ row }: { row: { status: number } }): string {
  if (row.status === 3) return 'journey-row-failed'
  if (row.status === 4) return 'journey-row-skipped'
  return ''
}
function failureLabel(row: { status: number; failureCode: string | null; failureMessage: string | null }): string {
  const code = row.failureCode
  const head = code ? (KNOWN_FAILURE.has(code) ? t(`journeys.failure.${code}`) : code) : row.status === 3 ? t('journeys.batchFailed') : ''
  if (!head) return ''
  return row.failureMessage ? `${head} · ${row.failureMessage}` : head
}
const signOffHint = computed(() => {
  if (!run.value) return t('journeys.signOffLoading')
  if (run.value.signedBy) return t('journeys.signOffAlready')
  if (!TERMINAL.has(run.value.status)) return t('journeys.signOffNotTerminal')
  if (auth.user?.userId !== journey.value?.ownerUserId) return t('journeys.signOffNotOwner', { id: journey.value?.ownerUserId ?? 0 })
  return ''
})

function statusLabel(status: number): string {
  return t(`journeys.runStatus.${status}`)
}

function statusTagType(status: number): 'info' | 'primary' | 'success' | 'danger' | 'warning' {
  if (status === 2) return 'success'
  if (status === 3) return 'danger'
  if (status === 4) return 'warning'
  if (status === 1) return 'primary'
  return 'info'
}

function formatTime(value: string | null): string {
  if (!value) return '—'
  return value.replace('T', ' ').slice(0, 19)
}

async function loadRun(silent = false) {
  // Silent beats (auto-follow re-reads) keep the previous snapshot on
  // screen: no skeleton, no toast — statuses just move.
  if (!silent) loading.value = true
  try {
    run.value = await getJourneyRun(runId)
  } finally {
    if (!silent) loading.value = false
  }
}

async function signOff() {
  signing.value = true
  try {
    run.value = await signOffJourneyRun(runId)
    ElMessage.success(t('journeys.signedMsg', { id: runId }))
  } finally {
    signing.value = false
  }
}

onMounted(async () => {
  await loadRun()
  runFollow.start()
  const journeyId = run.value?.journeyId ?? 0
  await loadProjectNames()
  if (journeyId) journey.value = await getJourney(journeyId)
})
</script>

<style scoped>
.run-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}
.run-title {
  margin: 0 0 4px;
  font-size: 18px;
}
.run-sub {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.light {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  margin-right: 6px;
  vertical-align: middle;
}
.light-0 { background: var(--el-color-info); }
.light-1 { background: var(--el-color-primary); }
.light-2 { background: var(--el-color-success); }
.light-3 { background: var(--el-color-danger); }
.light-4 { background: var(--el-color-warning); }
.failure-cell {
  color: var(--el-color-danger);
}
/* Whole-row tint (vision review 2026-11-05): the tr carries the class, so the
   override targets cells and stays applied while hovering. */
:deep(.el-table__body tr.journey-row-failed > td.el-table__cell),
:deep(.el-table__body tr.journey-row-failed:hover > td.el-table__cell) {
  background: var(--el-color-danger-light-9);
}
:deep(.el-table__body tr.journey-row-skipped > td.el-table__cell),
:deep(.el-table__body tr.journey-row-skipped:hover > td.el-table__cell) {
  background: var(--el-color-warning-light-9);
}
</style>
