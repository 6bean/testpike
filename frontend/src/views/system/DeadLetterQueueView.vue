<template>
  <div class="page-root dead-letter-page" data-test="dead-letter-page">
    <div class="page-card">
      <!-- R21 three-band / view-state actions in the trailing slot; page name rides the
           global top bar (R23), so no header band here. Screening controls ride the
           filter flow, the submit button sits inline at the end of the group. -->
      <!-- : ledger surface with server-fixed order (newest first, by booked
 time); the three read doors carry no sort params, so column sorting is
 honestly absent until a server-side sort key lands with . -->
      <!-- tp-sort-exempt: dead-letter ledger rows are served in booking-time order
           from core (newest first); client sort would only reorder one page of a
           server-paged window, so no sort-change wiring until the doors grow keys. -->
      <div class="filter-bar" data-test="dead-letter-filter-bar">
        <el-select
          v-model="filters.status"
          :placeholder="t('deadLetter.phStatus')"
          clearable
          style="width: 150px"
          data-test="dead-letter-status-filter"
          @change="reloadAll"
        >
          <el-option
            v-for="s in DEAD_LETTER_STATUSES"
            :key="s"
            :label="t(`deadLetter.statusTag.${s}`)"
            :value="s"
          />
        </el-select>
        <el-select
          v-model="filters.queueName"
          :placeholder="t('deadLetter.phQueue')"
          clearable
          filterable
          style="width: 240px"
          data-test="dead-letter-queue-filter"
          @change="reloadAll"
        >
          <el-option v-for="q in queueOptions" :key="q" :label="q" :value="q" />
        </el-select>
        <el-select
          v-model="filters.failureClass"
          :placeholder="t('deadLetter.phClass')"
          clearable
          style="width: 190px"
          data-test="dead-letter-class-filter"
          @change="reloadAll"
        >
          <el-option
            v-for="c in DEAD_LETTER_FAILURE_CLASSES"
            :key="c"
            :label="t(`deadLetter.cls.${c}`)"
            :value="c"
          />
        </el-select>
        <el-button type="primary" data-test="dead-letter-query" @click="reloadAll">
          <el-icon><Search /></el-icon>{{ t('deadLetter.query') }}
        </el-button>
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
          <el-button :icon="Refresh" data-test="dead-letter-refresh" @click="reloadAll">
            {{ t('deadLetter.refresh') }}
          </el-button>
        </div>
      </div>

      
      <div
        v-if="backlogHalo"
        class="backlog-halo"
        data-test="dead-letter-backlog-halo"
        role="alert"
      >
        <el-icon class="halo-icon"><WarningFilled /></el-icon>
        <span>{{ t('deadLetter.haloOverThreshold', { n: pendingTotal, threshold: BACKLOG_HALO_THRESHOLD }) }}</span>
      </div>

      <div class="triage-grid">
        
        <div class="triage-panel" data-test="dead-letter-queue-panel">
          <div class="panel-title">{{ t('deadLetter.queueBacklogTitle') }}</div>
          <el-table
            border
            :data="queueRows"
            size="small"
            :show-header="true"
            data-test="dead-letter-queue-table"
            class="data-table"
            row-key="queueName"
            @row-click="pickQueue"
          >
            <el-table-column prop="queueName" :label="t('deadLetter.queue')" min-width="180" show-overflow-tooltip />
            <el-table-column prop="pendingCount" :label="t('deadLetter.count')" width="90" align="right" />
            <el-table-column v-if="canReplay" :label="t('deadLetter.actions')" width="90" align="center">
              <template #default="{ row }">
                <el-button
                  link
                  type="primary"
                  data-test="dead-letter-queue-replay-open"
                  :title="t('deadLetter.replayTip')"
                  :data-queue="row.queueName"
                  @click="openQueueBatch(row, $event)"
                >
                  {{ t('deadLetter.replay') }}
                </el-button>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty :description="t('deadLetter.emptyNone')" :image-size="60" />
            </template>
          </el-table>
        </div>
        
        <div class="triage-panel" data-test="dead-letter-class-panel">
          <div class="panel-title">{{ t('deadLetter.classDistTitle') }}</div>
          <div class="basis-note" data-test="dead-letter-basis-note">{{ t('deadLetter.basisNote') }}</div>
          <div v-for="row in classRows" :key="row.failureClass" class="class-row" data-test="dead-letter-class-row">
            <span class="class-name" :class="{ 'class-replay-forbidden': row.failureClass === DEAD_LETTER_REPLAY_FORBIDDEN_CLASS }">
              {{ t(`deadLetter.cls.${row.failureClass}`, row.failureClass) }}
            </span>
            <span class="class-count">{{ row.count }}</span>
            <span v-if="row.failureClass === DEAD_LETTER_REPLAY_FORBIDDEN_CLASS" class="class-ban" data-test="dead-letter-replay-ban-note">
              {{ t('deadLetter.replayForbiddenTag') }}
            </span>
            <el-button
              v-else-if="canReplay"
              link
              type="primary"
              class="class-replay-btn"
              data-test="dead-letter-class-replay-open"
              :disabled="!filters.queueName"
              :title="filters.queueName ? '' : t('deadLetter.classReplayNeedQueue')"
              @click="openClassBatch(row)"
            >
              {{ t('deadLetter.replay') }}
            </el-button>
          </div>
          <el-empty v-if="classRows.length === 0" :description="t('deadLetter.emptyNone')" :image-size="60" />
        </div>
      </div>

      <TableSkeleton v-if="loading" :cols="colCfg.visibleColumns.value.length" data-test="dead-letter-skeleton" />
      <el-table v-else border :data="rows" :size="colCfg.state.density" data-test="dead-letter-table" class="data-table" row-key="id">
        <!-- The payload open button pins to the right zone together with the row
             action column: a scrollable cell can land the focus entirely under the
             fixed strip (ux gate X-2 focus obscuration), a sticky home keeps it
             always visible and hittable. -->
        <el-table-column
          v-for="col in colCfg.visibleColumns.value"
          :key="col.key"
          :prop="col.key"
          :fixed="col.key === 'payloadJson' ? 'right' : col.fixed || false"
          :label="t(col.labelKey)"
          :width="col.width ?? undefined"
          :min-width="col.width ? undefined : DEAD_LETTER_FLEX_MIN[col.key]"
          :show-overflow-tooltip="col.key === 'queueName' || col.key === 'messageFingerprint'"
          :data-test="`dead-letter-col-${col.key}`"
        >
          <template #default="{ row }">
            <template v-if="col.key === 'failureClass'">
              <el-tag size="small" :type="row.failureClass === DEAD_LETTER_REPLAY_FORBIDDEN_CLASS ? 'info' : 'danger'" data-test="dead-letter-class-tag">
                {{ t(`deadLetter.cls.${row.failureClass}`, row.failureClass) }}
              </el-tag>
            </template>
            <template v-else-if="col.key === 'status'">
              <el-tag size="small" :type="statusTagType(row.status)">{{ t(`deadLetter.statusTag.${row.status}`, row.status) }}</el-tag>
            </template>
            <template v-else-if="col.key === 'failureReason'">
              <span class="reason-cell" data-test="dead-letter-reason">{{ row.failureReason || '—' }}</span>
            </template>
            <template v-else-if="col.key === 'createdAtUtc'"><TimeCell v-if="row.createdAtUtc" :iso="row.createdAtUtc" /><span v-else>—</span></template>
            <template v-else-if="col.key === 'updatedAtUtc'"><TimeCell v-if="row.updatedAtUtc" :iso="row.updatedAtUtc" /><span v-else>—</span></template>
            <template v-else-if="col.key === 'payloadJson'">
              <el-button v-if="row.payloadJson" link type="primary" data-test="dead-letter-payload-open" @click="openPayload(row)">
                {{ t('deadLetter.viewPayload') }}
              </el-button>
              <span v-else>—</span>
            </template>
            <template v-else>{{ row[col.key] ?? '—' }}</template>
          </template>
        </el-table-column>
        
        <el-table-column
          v-if="canReplay || canVoid"
          :label="t('deadLetter.actions')"
          width="150"
          fixed="right"
          align="center"
        >
          <!-- el-table-column renders no element of its own, so the column anchor
               lives on the header cell: the browser specs assert against real DOM. -->
          <template #header>
            <span data-test="dead-letter-col-actions">{{ t('deadLetter.actions') }}</span>
          </template>
          <template #default="{ row }">
            <el-tooltip
              v-if="canReplay"
              :disabled="rowReplayable(row)"
              :content="rowReplayDisabledHint(row)"
              placement="top"
            >
              <span>
                <el-button
                  link
                  type="primary"
                  data-test="dead-letter-row-replay"
                  :title="rowReplayable(row) ? t('deadLetter.replayTip') : ''"
                  :disabled="!rowReplayable(row) || replayBusy"
                  @click="onReplayRow(row)"
                >
                  {{ t('deadLetter.replay') }}
                </el-button>
              </span>
            </el-tooltip>
            <el-button
              v-if="canVoid"
              link
              type="danger"
              data-test="dead-letter-row-void"
              :title="t('deadLetter.voidTip')"
              :disabled="row.status !== 'pending' || voidBusy"
              @click="openVoidDialog(row)"
            >
              {{ t('deadLetter.void') }}
            </el-button>
          </template>
        </el-table-column>
        <!-- #empty must be a direct slot of el-table: a copy nested inside
 el-table-column is silently ignored by Element Plus (the column
 component exposes no empty slot), so the table fell back to the
 default text. Caught by the first-E2E window. -->
        <template #empty>
          <el-empty :description="t('deadLetter.emptyNone')" />
        </template>
      </el-table>

      <div class="pagination-bar">
        <el-pagination
          v-if="total > 0"
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          data-test="dead-letter-pagination"
          @current-change="loadBrowse"
          @size-change="reloadAll"
        />
      </div>

      
      <el-dialog v-model="payloadVisible" :title="t('deadLetter.payloadTitle')" width="720px" data-test="dead-letter-payload-dialog">
        <pre class="payload-pre" data-test="dead-letter-payload-body">{{ payloadText }}</pre>
        <template #footer>
          <el-button type="primary" data-test="dead-letter-payload-close" @click="payloadVisible = false">
            {{ t('deadLetter.close') }}
          </el-button>
        </template>
      </el-dialog>

      
      <el-dialog v-model="staleDialogVisible" :title="t('deadLetter.staleTitle')" width="520px" data-test="dead-letter-stale-dialog">
        <p class="confirm-lead">{{ t('deadLetter.staleLead', { fp: staleRow ? shortFp(staleRow.messageFingerprint) : '', hours: DEAD_LETTER_STALE_HOURS }) }}</p>
        <p class="confirm-note">{{ t('deadLetter.staleNote') }}</p>
        <template #footer>
          <el-button data-test="dead-letter-stale-cancel" @click="staleDialogVisible = false">{{ t('deadLetter.cancel') }}</el-button>
          <el-button type="primary" :loading="replayBusy" data-test="dead-letter-stale-confirm" @click="confirmStaleReplay">
            {{ t('deadLetter.replay') }}
          </el-button>
        </template>
      </el-dialog>

      
      <el-dialog v-model="batchDialogVisible" :title="batchMode === 'queue' ? t('deadLetter.batchQueueTitle') : t('deadLetter.batchClassTitle')" width="560px" data-test="dead-letter-batch-dialog">
        <p class="confirm-lead">
          <span>{{ t('deadLetter.batchScopeLabel') }}</span>
          <el-tag size="small" class="scope-tag">{{ batchQueue }}</el-tag>
          <el-tag v-if="batchMode === 'class'" size="small" class="scope-tag">{{ t(`deadLetter.cls.${batchClass}`, batchClass) }}</el-tag>
        </p>
        <p class="confirm-note">{{ t('deadLetter.batchCountNote', { n: batchCount }) }}</p>
        <el-checkbox v-model="batchIncludeStale" data-test="dead-letter-batch-include-stale">
          {{ t('deadLetter.batchIncludeStale', { hours: DEAD_LETTER_STALE_HOURS }) }}
        </el-checkbox>
        <p v-if="batchMode === 'queue'" class="confirm-subnote">{{ t('deadLetter.batchEntityGoneNote') }}</p>
        <template #footer>
          <el-button data-test="dead-letter-batch-cancel" @click="batchDialogVisible = false">{{ t('deadLetter.cancel') }}</el-button>
          <el-button type="primary" :loading="batchBusy" data-test="dead-letter-batch-confirm" @click="submitBatchReplay">
            {{ t('deadLetter.replay') }}
          </el-button>
        </template>
      </el-dialog>

      
      <el-dialog v-model="voidDialogVisible" :title="t('deadLetter.voidTitle')" width="520px" data-test="dead-letter-void-dialog">
        <p class="confirm-lead">{{ t('deadLetter.voidLead', { fp: voidRow ? shortFp(voidRow.messageFingerprint) : '' }) }}</p>
        <el-form label-position="top">
          <el-form-item :label="t('deadLetter.voidReasonLabel')" required>
            <el-input
              v-model="voidReason"
              type="textarea"
              :rows="3"
              maxlength="200"
              show-word-limit
              :placeholder="t('deadLetter.voidReasonPh')"
              data-test="dead-letter-void-reason"
            />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button data-test="dead-letter-void-cancel" @click="voidDialogVisible = false">{{ t('deadLetter.cancel') }}</el-button>
          <el-button type="danger" :loading="voidBusy" :disabled="!voidReason.trim()" data-test="dead-letter-void-confirm" @click="submitVoid">
            {{ t('deadLetter.void') }}
          </el-button>
        </template>
      </el-dialog>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Refresh, Search, WarningFilled } from '@element-plus/icons-vue'
import TimeCell from '@/components/TimeCell.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import ColumnSettings from '@/components/ColumnSettings.vue'
import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'
import { useAuthStore } from '@/stores/auth'
import {
  browseDeadLetters,
  listClassDistribution,
  listQueueBacklog,
  replayDeadLetterByClass,
  replayDeadLetterOne,
  replayDeadLetterQueue,
  voidDeadLetter,
  isStaleDeadLetterRow,
  DEAD_LETTER_FAILURE_CLASSES,
  DEAD_LETTER_REPLAY_FORBIDDEN_CLASS,
  DEAD_LETTER_STALE_HOURS,
  DEAD_LETTER_STATUSES,
  type DeadLetterClassCount,
  type DeadLetterLedgerRow,
  type DeadLetterQueueCount,
} from '@/api/deadletter'

defineOptions({ name: 'DeadLetterQueueView' })

const { t } = useI18n()
const auth = useAuthStore()

const canReplay = computed(() => auth.hasPerm('perm:system:dead-letter-replay'))
const canVoid = computed(() => auth.hasPerm('perm:system:dead-letter-void'))

const BACKLOG_HALO_THRESHOLD = 500

const BROWSE_COLUMNS: ColumnDef[] = [
  { key: 'queueName', labelKey: 'deadLetter.queue', width: null },
  { key: 'failureClass', labelKey: 'deadLetter.class', width: 150 },
  { key: 'failureReason', labelKey: 'deadLetter.reason', width: null },
  { key: 'status', labelKey: 'deadLetter.status', width: 100 },
  { key: 'replayRound', labelKey: 'deadLetter.replayRound', width: 90 },
  { key: 'messageFingerprint', labelKey: 'deadLetter.fingerprint', width: 190 },
  { key: 'origExchange', labelKey: 'deadLetter.origExchange', width: 170 },
  { key: 'origRoutingKey', labelKey: 'deadLetter.origRoutingKey', width: 170 },
  { key: 'voidReason', labelKey: 'deadLetter.voidReason', width: 150 },
  { key: 'operator', labelKey: 'deadLetter.operator', width: 130 },
  { key: 'payloadJson', labelKey: 'deadLetter.payload', width: 110 },
  { key: 'createdAtUtc', labelKey: 'deadLetter.created', width: 170 },
  { key: 'updatedAtUtc', labelKey: 'deadLetter.updated', width: 170 },
]
const DEAD_LETTER_FLEX_MIN: Record<string, number> = { queueName: 190, failureReason: 260 }
const colCfg = useColumnConfig('dead-letter-browse', BROWSE_COLUMNS)
const settingsRows = computed(() =>
  colCfg.state.columns.map((c) => ({ ...c, labelKey: BROWSE_COLUMNS.find((d) => d.key === c.key)!.labelKey })),
)

const filters = reactive<{ status: string; queueName: string; failureClass: string }>({
  status: '',
  queueName: '',
  failureClass: '',
})

const queueRows = ref<DeadLetterQueueCount[]>([])
const classRows = ref<DeadLetterClassCount[]>([])
const rows = ref<DeadLetterLedgerRow[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)

const queueOptions = computed(() => queueRows.value.map((r) => r.queueName))
const pendingTotal = computed(() => queueRows.value.reduce((sum, r) => sum + r.pendingCount, 0))
const backlogHalo = computed(() => (filters.status === '' || filters.status === 'pending') && pendingTotal.value > BACKLOG_HALO_THRESHOLD)

function statusTagType(status: string): 'success' | 'info' | 'warning' {
  if (status === 'replayed') return 'success'
  if (status === 'voided') return 'info'
  return 'warning'
}

function scopedParams() {
  return {
    ...(filters.queueName ? { queueName: filters.queueName } : {}),
    ...(filters.status ? { status: filters.status } : {}),
  }
}

async function loadCounters(): Promise<void> {
  const [qs, cs] = await Promise.all([
    listQueueBacklog(filters.status || undefined),
    listClassDistribution(filters.queueName || undefined, filters.status || undefined),
  ])
  queueRows.value = qs
  classRows.value = cs
}

async function loadBrowse(): Promise<void> {
  loading.value = true
  try {
    const result = await browseDeadLetters({
      ...scopedParams(),
      ...(filters.failureClass ? { failureClass: filters.failureClass } : {}),
      page: page.value,
      size: size.value,
    })
    rows.value = result.list
    total.value = result.total
  } finally {
    loading.value = false
  }
}

async function reloadAll(): Promise<void> {
  page.value = 1
  await Promise.all([loadCounters(), loadBrowse()])
}


function pickQueue(row: DeadLetterQueueCount): void {
  filters.queueName = row.queueName
  void reloadAll()
}



function rowReplayable(row: DeadLetterLedgerRow): boolean {
  return row.status === 'pending' && row.failureClass !== DEAD_LETTER_REPLAY_FORBIDDEN_CLASS
}


function rowReplayDisabledHint(row: DeadLetterLedgerRow): string {
  if (row.failureClass === DEAD_LETTER_REPLAY_FORBIDDEN_CLASS) return t('deadLetter.replayHintGone')
  if (row.status === 'voided') return t('deadLetter.replayHintVoided')
  return t('deadLetter.replayHintReplayed')
}

const replayBusy = ref(false)
const voidBusy = ref(false)
const batchBusy = ref(false)

const staleDialogVisible = ref(false)
const staleRow = ref<DeadLetterLedgerRow | null>(null)

const batchDialogVisible = ref(false)
const batchMode = ref<'queue' | 'class'>('queue')
const batchQueue = ref('')
const batchClass = ref('')
const batchCount = ref(0)
const batchIncludeStale = ref(false)

const voidDialogVisible = ref(false)
const voidRow = ref<DeadLetterLedgerRow | null>(null)
const voidReason = ref('')

async function doReplayOne(row: DeadLetterLedgerRow, confirmReexecute: boolean): Promise<void> {
  replayBusy.value = true
  try {
    const receipt = await replayDeadLetterOne(row.id, confirmReexecute)
    ElMessage.success(t('deadLetter.replayDone', { fp: shortFp(row.messageFingerprint), round: receipt.replayRound }))
    staleDialogVisible.value = false
    await reloadAll()
  } finally {
    replayBusy.value = false
  }
}


function onReplayRow(row: DeadLetterLedgerRow): void {
  if (isStaleDeadLetterRow(row)) {
    staleRow.value = row
    staleDialogVisible.value = true
    return
  }
  void doReplayOne(row, false)
}

async function confirmStaleReplay(): Promise<void> {
  const row = staleRow.value
  if (!row) return
  await doReplayOne(row, true)
}


function openQueueBatch(row: DeadLetterQueueCount, event: MouseEvent): void {
  event.stopPropagation()
  batchMode.value = 'queue'
  batchQueue.value = row.queueName
  batchClass.value = ''
  batchCount.value = row.pendingCount
  batchIncludeStale.value = false
  batchDialogVisible.value = true
}


function openClassBatch(row: DeadLetterClassCount): void {
  if (!filters.queueName) return
  batchMode.value = 'class'
  batchQueue.value = filters.queueName
  batchClass.value = row.failureClass
  batchCount.value = row.count
  batchIncludeStale.value = false
  batchDialogVisible.value = true
}

async function submitBatchReplay(): Promise<void> {
  batchBusy.value = true
  try {
    const n =
      batchMode.value === 'queue'
        ? await replayDeadLetterQueue(batchQueue.value, batchIncludeStale.value)
        : await replayDeadLetterByClass(batchQueue.value, batchClass.value, batchIncludeStale.value)
    ElMessage.success(t('deadLetter.batchReplayDone', { n }))
    batchDialogVisible.value = false
    await reloadAll()
  } finally {
    batchBusy.value = false
  }
}

function openVoidDialog(row: DeadLetterLedgerRow): void {
  voidRow.value = row
  voidReason.value = ''
  voidDialogVisible.value = true
}

async function submitVoid(): Promise<void> {
  const row = voidRow.value
  const reason = voidReason.value.trim()
  if (!row || !reason) return
  voidBusy.value = true
  try {
    await voidDeadLetter(row.id, reason)
    ElMessage.success(t('deadLetter.voidDone', { fp: shortFp(row.messageFingerprint) }))
    voidDialogVisible.value = false
    await reloadAll()
  } finally {
    voidBusy.value = false
  }
}


function shortFp(fp: string): string {
  const i = fp.indexOf(':')
  return i >= 0 ? fp.slice(i + 1, i + 9) : fp.slice(0, 8)
}

const payloadVisible = ref(false)
const payloadText = ref('')

function openPayload(row: DeadLetterLedgerRow): void {
  let raw = row.payloadJson ?? ''
  try {
    raw = JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
  }
  payloadText.value = raw
  payloadVisible.value = true
}

onMounted(() => {
  void reloadAll()
})
</script>

<style scoped>
.triage-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 340px);
  gap: 16px;
  margin-bottom: 16px;
}
.triage-panel {
  min-width: 0;
}
.panel-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--tp-text-secondary);
  margin-bottom: 8px;
}
.basis-note {
  font-size: 12px;
  color: var(--tp-text-secondary);
  margin: -2px 0 8px;
}
.class-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 8px;
  border-bottom: 1px solid var(--tp-border);
  font-size: 13px;
}
.class-name {
  flex: 1;
  min-width: 0;
}
.class-replay-forbidden {
  color: var(--tp-text-secondary);
}
.class-count {
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}
.class-ban {
  color: var(--tp-danger);
  font-size: 12px;
}
.class-replay-btn {
  flex: none;
}
.confirm-lead {
  margin: 0 0 8px;
  font-size: 13px;
  color: var(--tp-text-primary);
}
.confirm-note {
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--tp-text-secondary);
  line-height: 1.6;
}
.confirm-subnote {
  margin: 10px 0 0;
  font-size: 12px;
  color: var(--tp-warning, var(--tp-danger));
}
.scope-tag {
  margin-left: 6px;
}

.backlog-halo {
  display: flex;
  align-items: center;
  gap: 8px;
  background: var(--tp-danger-weak);
  border-radius: var(--tp-radius-sm);
  padding: 8px 12px;
  margin-bottom: 16px;
  font-size: 13px;
  color: var(--tp-text-primary);
}
.halo-icon {
  color: var(--tp-danger);
}
.reason-cell {
  word-break: break-all;
}
/* The payload-open link sits in a narrow column: two CJK glyphs must not
   break onto separate lines (vision claim confirmed by DOM probe — 34px
   button rendering 24px tall across two line boxes). */
[data-test='dead-letter-payload-open'] {
  white-space: nowrap;
}
.payload-pre {
  max-height: 420px;
  overflow: auto;
  background: var(--tp-bg);
  border: 1px solid var(--tp-border);
  border-radius: var(--tp-radius-sm);
  padding: 12px;
  font-family: 'JetBrains Mono', 'Cascadia Code', Consolas, monospace;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0;
}
</style>
