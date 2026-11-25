<!-- tp-owner-echo-exempt: read-only hit inspector — the detail drawer replays a
     captured request/response and the page hosts no create/write entry (stub
     editing lives on the spaces page); rule 2 is carried by the whole-page
     no-project empty block (mock-inspector-no-project). -->
<template>
  <div class="page-root mock-inspector-page" data-test="mock-inspector-page">
    <div :class="embedded ? 'mock-inspector-embedded' : 'page-card'">
      <!-- R23 stage-2: empty title shell removed — the page name rides the
           global topbar, and the retired title-side "?" now lives on the
           topbar help dot (data-test=header-help-btn). -->

      <div v-if="projectId && (spaces.length || !loadingSpaces)" class="filter-bar" data-test="mock-inspector-filter-bar">
        <el-select
          v-if="!embedded"
                    v-model="pickedSpace"
          :placeholder="t('mockInspector.selectSpace')"
          filterable
          style="width: 220px"
          data-test="mock-inspector-space-select"
          :disabled="!spaces.length"
          @change="syncSpaceToUrl()"
        >
          <el-option
            v-for="s in spaces"
            :key="s.id"
            :label="mockSpaceLabel(s)"
            :value="s.id"
          />
        </el-select>
        <el-radio-group v-model="matchedFilter" data-test="mock-inspector-filter" @change="reloadHits">
          <el-radio-button :value="''">{{ t('mockInspector.filterAll') }}</el-radio-button>
          <el-radio-button :value="true">{{ t('mockInspector.filterHit') }}</el-radio-button>
          <el-radio-button :value="false">{{ t('mockInspector.filterMiss') }}</el-radio-button>
        </el-radio-group>
        <el-checkbox v-model="livePolling" data-test="mock-inspector-live">{{ t('mockInspector.autoRefresh') }}</el-checkbox>
        <div class="filter-bar-actions" data-test="mock-inspector-filter-actions">
          <el-button :icon="Refresh" :loading="hitsLoading" data-test="mock-inspector-refresh" @click="loadAll">
            {{ t('mockInspector.refresh') }}
          </el-button>
        </div>
      </div>

      <!-- Load-state bar: the honest self-check of the serving plane. Never paints
           green over a failure — offline/stale/failures each name their own reason. -->
      <div v-if="spaceId && (loadState || loadOffline)" class="health-bar" :class="healthTone" data-test="mock-inspector-health">
        <el-icon class="health-icon"><component :is="healthIcon" /></el-icon>
        <span class="health-text">{{ healthText }}</span>
        <span v-if="ledgerText" class="health-sub" data-test="mock-inspector-ledger">{{ ledgerText }}</span>
      </div>

      <div v-if="!projectId" class="empty-block" data-test="mock-inspector-no-project">
        <EmptyState :title="t('mockInspector.noProject')" />
      </div>
      <div v-else-if="!loadingSpaces && !spaces.length" class="empty-block" data-test="mock-inspector-no-space">
        <EmptyState :title="t('mockInspector.noSpace')" />
      </div>

      <template v-else-if="spaceId">
        <!-- Skeleton on the first load of an empty ledger (platform baseline:
             TableSkeleton while loading, table once rows exist; v-loading keeps
             covering subsequent page loads that already have rows on screen). -->
        <TableSkeleton v-if="hitsLoading && !hits.length" :cols="6" data-test="mock-inspector-skeleton" />
        <el-table v-else border
          v-loading="hitsLoading"
          :data="hits"
          class="hit-table"
          data-test="mock-inspector-hits"
          @sort-change="onSortChange"
          @row-click="openDetail"
        >
          <el-table-column prop="hitAt" sortable="custom" :label="t('mockInspector.colTime')" width="180">
            <template #default="{ row }">{{ fmtTime(row.hitAt) }}</template>
          </el-table-column>
          <el-table-column prop="method" sortable="custom" :label="t('mockInspector.colMethod')" width="90">
            <template #default="{ row }">{{ snapText(row, 'method') }}</template>
          </el-table-column>
          <el-table-column prop="path" sortable="custom" :label="t('mockInspector.colPath')" min-width="220">
            <template #default="{ row }">
              <span class="mono">{{ snapText(row, 'path') }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="matched" sortable="custom" :label="t('mockInspector.colResult')" width="110">
            <template #default="{ row }">
              <el-tag :type="row.matched ? 'success' : 'danger'" size="small" data-test="mock-inspector-verdict">
                {{ row.matched ? t('mockInspector.resultHit') : t('mockInspector.resultMiss') }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="stubName" sortable="custom" :label="t('mockInspector.colStub')" min-width="160">
            <template #default="{ row }">
              <span v-if="stubNameOf(row)">{{ stubNameOf(row) }}</span>
              <span v-else class="muted">{{ t('mockInspector.missStubHint') }}</span>
              <el-tag v-if="row.faultApplied" type="warning" size="small" class="fault-tag">
                {{ t('mockInspector.faultTag', { f: row.faultApplied }) }}
              </el-tag>
              <span v-if="row.sessionId" class="muted session-tag">{{ t('mockInspector.sessionTag', { s: row.sessionId }) }}</span>
              <!-- provenance honesty label — an AI-generated
 answer (llm) or a real-dependency forward (proxy) must never
 look like a stubbed rule in the ledger. Plain stub rows carry
 no source (the rule name above already says it), so no tag. -->
              <el-tag v-if="sourceTone(row)" size="small"
                :type="sourceTone(row) === 'proxy' ? 'info' : 'primary'"
                class="source-tag" :class="`source-tag--${sourceTone(row)}`"
                data-test="mock-inspector-source-tag">
                {{ sourceLabel(row) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="latencyMs" sortable="custom" :label="t('mockInspector.colLatency')" width="100">
            <template #default="{ row }">{{ row.latencyMs == null ? '—' : `${row.latencyMs} ms` }}</template>
          </el-table-column>
          <template #empty>
            <span data-test="mock-inspector-empty">{{ t('mockInspector.emptyHits') }}</span>
          </template>
        </el-table>
        <!-- Hidden on an empty ledger: a disabled pager with "total 0" invites
             dead clicks; platform baseline is v-if="total > 0" (same as the
             requirement/audit lists). -->
        <div v-if="total > 0" class="pagination-bar" data-test="mock-inspector-pager">
          <el-pagination
            v-model:current-page="page"
            :page-size="pageSize"
            :total="total"
            layout="prev, pager, next, total"
            @current-change="loadHits"
          />
        </div>
      </template>

      <!-- Request snapshot & match trace (values arrive pre-masked from the pipeline;
           this panel renders them as-is — a second redaction step is deliberately absent). -->
      <el-drawer v-model="detailVisible" size="46%" :title="t('mockInspector.detailTitle')">
        <div v-if="detail" class="detail-body" data-test="mock-inspector-detail">
          <el-descriptions :column="1" border>
            <el-descriptions-item :label="t('mockInspector.snapshotMethod')">{{ snapText(detail, 'method') }}</el-descriptions-item>
            <el-descriptions-item :label="t('mockInspector.snapshotPath')">{{ snapText(detail, 'path') }}</el-descriptions-item>
            <el-descriptions-item :label="t('mockInspector.snapshotQuery')">{{ snapPretty(detail, 'query') }}</el-descriptions-item>
            <el-descriptions-item :label="t('mockInspector.snapshotHeaders')">{{ snapPretty(detail, 'headers') }}</el-descriptions-item>
            <el-descriptions-item :label="t('mockInspector.snapshotBody')">{{ snapPretty(detail, 'body') }}</el-descriptions-item>
          </el-descriptions>
          <h4 class="trace-title">{{ t('mockInspector.traceTitle') }}</h4>
          <el-table border v-if="detail.matchTrace?.length" :data="detail.matchTrace" size="small" data-test="mock-inspector-trace">
            <el-table-column :label="t('mockInspector.traceColCandidate')" min-width="140">
              <template #default="{ row }">{{ traceField(row, 'stubName') || traceField(row, 'candidate') || '—' }}</template>
            </el-table-column>
            <el-table-column :label="t('mockInspector.traceColVerdict')" width="110">
              <template #default="{ row }">{{ traceField(row, 'verdict') || traceField(row, 'matched') || '—' }}</template>
            </el-table-column>
            <el-table-column :label="t('mockInspector.traceColReason')" min-width="180">
              <template #default="{ row }">{{ traceField(row, 'reason') || traceField(row, 'detail') || '—' }}</template>
            </el-table-column>
          </el-table>
          <div v-else class="muted" data-test="mock-inspector-trace-none">{{ t('mockInspector.traceNone') }}</div>

          <!-- One-click promote : freeze THIS answered request as a
 stub rule. Disabled — with the reason shown — when the row carries
 no response plane (transport-failure miss): there is no recorded
 answer to promote and core refuses too. -->
          <div class="promote-block">
            <el-button
              type="primary"
              :loading="promoting"
              :disabled="!detailHasResponse"
              data-test="mock-inspector-promote"
              @click="promoteDetail"
            >{{ t('mockInspector.promoteBtn') }}</el-button>
            <span v-if="!detailHasResponse" class="muted" data-test="mock-inspector-promote-hint">
              {{ t('mockInspector.promoteNoResponse') }}
            </span>
            <span v-else class="muted">{{ t('mockInspector.promoteNote') }}</span>
          </div>
        </div>
      </el-drawer>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * AG batch : the request inspector page.
 * Journey: pick project -> pick mock space -> watch the serving plane's load-state
 * self-check and the live hit/miss ledger. The health bar never dresses a failure
 * up as healthy; 50301 (plane offline) is named in plain words. Snapshot columns
 * render pipeline-masked values as-is (no second redaction, no unmasking path).
 */
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Refresh, SuccessFilled, WarningFilled, CircleCloseFilled } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
// AK single-source posture : page-level project selector removed — the
// project rides on the global header context via useProjectPageContext.
import { useMockSpaceUrlPick } from '@/composables/useMockSpaceUrlPick'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import { bizErrorMsg } from '@/utils/bizError'
import {
  getLoadState,
  listHits,
  listSpaces,
  promoteHitToStub,
  type MockHitItem,
  type MockLoadState,
  type MockSpaceItem,
} from '@/api/mock'

const { t } = useI18n()
// AN-4 embedded contract: the space workbench hosts this page as a tab
// and injects the picked space id (props default off — standalone mounting
// stays possible while retired paths still redirect).
const props = withDefaults(defineProps<{ embedded?: boolean; spaceId?: number | null }>(), { embedded: false, spaceId: null })
const embedded = computed(() => props.embedded)
// AK single-source posture: page project state comes from the shared composable
// (URL priority at setup, two-way sync with the header context). No local copy.
const { projectId } = useProjectPageContext()

const pickedSpace = ref<number | null>(null)
// §12a URL pre-filter wiring (shared with the docs page — one impl).
const { pickFromQuery, syncSpaceToUrl } = useMockSpaceUrlPick(pickedSpace, projectId)
const spaces = ref<MockSpaceItem[]>([])
const loadingSpaces = ref(false)
const hits = ref<MockHitItem[]>([])
const hitsLoading = ref(false)
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
// '' = both spectra (never sent as a param); true/false = single spectrum.
const matchedFilter = ref<'' | boolean>('')
// Column prop -> core hit-ledger SORT_COLUMNS whitelist key (single source for
// the sortable decision and the request payload — columns outside the whitelist
// never emit sort params, no client-only fake sort).
const SORT_KEY_MAP: Record<string, string> = {
  hitAt: 'hit_at',
  method: 'method',
  path: 'path',
  matched: 'matched',
  stubName: 'stub_name',
  latencyMs: 'latency_ms',
}
// Server-side sort state (same shape as the endpoints/tag/user pages).
const query = reactive<{ sortBy: string; sortDir: string }>({ sortBy: '', sortDir: '' })

/** Header sort: forward the whitelisted key to the server (ORDER BY happens in
 * core SQL); clearing a sort falls back to the newest-first timeline; page
 * resets to 1 so paging windows stay consistent. The live poller keeps the
 * chosen order — loadHits below forwards query on every refresh. */
function onSortChange({ prop, order }: { prop: string; order: string | null }): void {
  if (order === null) {
    query.sortBy = ''
    query.sortDir = ''
    page.value = 1
    void loadHits()
    return
  }
  const key = SORT_KEY_MAP[prop]
  if (!key) {
    void loadHits()
    return
  }
  query.sortBy = key
  query.sortDir = order === 'ascending' ? 'asc' : 'desc'
  page.value = 1
  void loadHits()
}
const loadState = ref<MockLoadState | null>(null)
const loadOffline = ref<string | null>(null)
const detail = ref<MockHitItem | null>(null)
const detailVisible = ref(false)
const promoting = ref(false)
const livePolling = ref(true)

// : host-injected space id wins over the local pick (redirect-era fallback kept).
const spaceId = computed(() => props.spaceId ?? pickedSpace.value)

async function loadSpaces(): Promise<void> {
  if (!projectId.value) {
    spaces.value = []
    pickedSpace.value = null
    return
  }
  loadingSpaces.value = true
  try {
    spaces.value = await listSpaces(projectId.value)
    // §12a pre-fill: a spaceId riding the URL lands only if the loaded list
    // contains it (honest consumption — never a guessed id).
    const fromQuery = pickFromQuery(spaces.value.map((s) => s.id))
    if (fromQuery != null) pickedSpace.value = fromQuery
    else if (!spaces.value.some((s) => s.id === pickedSpace.value)) {
      pickedSpace.value = spaces.value[0]?.id ?? null
    }
    void syncSpaceToUrl()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('mockInspector.loadSpacesFailed')))
    spaces.value = []
  } finally {
    loadingSpaces.value = false
  }
}

async function loadHits(): Promise<void> {
  if (!spaceId.value) {
    hits.value = []
    total.value = 0
    return
  }
  hitsLoading.value = true
  try {
    const res = await listHits(spaceId.value, matchedFilter.value === '' ? undefined : matchedFilter.value, page.value, pageSize.value,
      { sortBy: query.sortBy || undefined, sortDir: query.sortDir || undefined })
    hits.value = res.list ?? []
    total.value = res.total ?? 0
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('mockInspector.loadHitsFailed')))
  } finally {
    hitsLoading.value = false
  }
}

async function loadLoadState(): Promise<void> {
  if (!spaceId.value) {
    loadState.value = null
    loadOffline.value = null
    return
  }
  try {
    loadState.value = await getLoadState(spaceId.value)
    loadOffline.value = null
  } catch (e) {
    // Plane offline (50301) or any gate error: honest "cannot read right now"
    // with the server's plain-word reason; the stale ledger stays on screen.
    loadState.value = null
    loadOffline.value = bizErrorMsg(e, String((e as Error)?.message ?? e))
  }
}

function reloadHits(): void {
  page.value = 1
  void loadHits()
}

async function loadAll(): Promise<void> {
  await Promise.all([loadHits(), loadLoadState()])
}

watch(projectId, () => {
  pickedSpace.value = null
  void loadSpaces()
})
watch(spaceId, () => {
  page.value = 1
  void loadAll()
}, {
  // Workbench-tab hosting passes a constant spaceId prop — the ledger must
  // load on mount, not wait for a change that never comes.
  immediate: true,
})

// Live polling: one interval owned by this component, cleared on unmount so a
// background tab cannot keep hammering the gates after navigation.
let timer: ReturnType<typeof setInterval> | null = null
function restartTimer(): void {
  if (timer) clearInterval(timer)
  timer = null
  if (livePolling.value) {
    timer = setInterval(() => {
      if (spaceId.value) void loadAll()
    }, 2000)
  }
}
// immediate: the checkbox starts checked, so the timer must be armed at
// setup — a plain watch never fires for the initial value and the "live"
// ledger would silently stay a single one-shot load (T362d E2E caught it:
// a hit landing 2s after page load never showed up).
watch(livePolling, restartTimer, { immediate: true })
onUnmounted(() => {
  if (timer) clearInterval(timer)
})

const healthTone = computed(() => {
  if (loadOffline.value) return 'tone-danger'
  const ls = loadState.value
  if (!ls) return 'tone-info'
  if (!ls.everLoaded) return 'tone-warning'
  if (ls.stale || ls.consecutiveFailures > 0 || ls.hitDegraded) return 'tone-warning'
  return 'tone-success'
})

const healthIcon = computed(() => {
  if (healthTone.value === 'tone-danger') return CircleCloseFilled
  if (healthTone.value === 'tone-warning') return WarningFilled
  if (healthTone.value === 'tone-success') return SuccessFilled
  return WarningFilled
})

const healthText = computed(() => {
  if (loadOffline.value) return t('mockInspector.healthOffline', { err: loadOffline.value })
  const ls = loadState.value
  if (!ls) return ''
  if (!ls.everLoaded) return t('mockInspector.healthNeverLoaded')
  if (ls.consecutiveFailures > 0) {
    return t('mockInspector.healthFailures', { n: ls.consecutiveFailures, err: ls.lastError ?? '—' })
  }
  if (ls.stale) return t('mockInspector.healthStale', { v: ls.pinnedVersion ?? '—' })
  return t('mockInspector.healthOk', {
    key: ls.pinnedSpaceKey ?? '—',
    v: ls.pinnedVersion ?? '—',
    at: fmtTime(ls.lastSuccessAt),
  })
})

const ledgerText = computed(() => {
  const ls = loadState.value
  if (!ls || (!ls.hitBuffered && !ls.hitDegraded && !ls.hitOverflowDrops)) return ''
  return t('mockInspector.hitLedgerNote', {
    n: ls.hitBuffered,
    degraded: ls.hitDegraded ? t('mockInspector.hitDegradedTag') : '',
    d: ls.hitOverflowDrops,
  })
})

function snapText(row: MockHitItem, field: string): string {
  const v = row.requestSnapshot?.[field]
  return v == null ? '—' : String(v)
}

function snapPretty(row: MockHitItem, field: string): string {
  const v = row.requestSnapshot?.[field]
  if (v == null) return '—'
  if (typeof v === 'string') return v
  try {
    return JSON.stringify(v, null, 2)
  } catch {
    return String(v)
  }
}

function stubNameOf(row: MockHitItem): string {
  const v = row.requestSnapshot?.stubName
  return v == null ? '' : String(v)
}

/**
 * Provenance tone of a ledger row . Only the two shapes that
 * deviate from "a stub rule answered this" get a label: llm (the paid AI
 * fallback rung generated the answer) and proxy (the real dependency answered
 * through the recording forward). Stub rows and legacy NULL rows return null —
 * the winner-rule name already tells that story, no second badge.
 */
function sourceTone(row: MockHitItem): 'llm' | 'proxy' | null {
  if (row.source === 'llm') return 'llm'
  if (row.source === 'proxy') return 'proxy'
  return null
}

function sourceLabel(row: MockHitItem): string {
  const tone = sourceTone(row)
  if (tone === 'llm') return t('mockInspector.sourceLlm')
  if (tone === 'proxy') return t('mockInspector.sourceProxy')
  return ''
}

function traceField(entry: Record<string, unknown>, key: string): string {
  const v = entry[key]
  if (v == null) return ''
  return typeof v === 'string' ? v : String(v)
}

function openDetail(row: MockHitItem): void {
  detail.value = row
  detailVisible.value = true
}

// Promote gate mirrors core's refusal rule: a row only becomes a draft when
// the plane recorded an answer for it (response status present). No response
// plane = nothing to freeze, so the button stays disabled with the reason
// shown instead of filing a call core would loudly reject.
const detailHasResponse = computed(() => {
  const snapshot = detail.value?.responseSnapshot
  return snapshot != null && snapshot.status != null && snapshot.status !== ''
})

async function promoteDetail(): Promise<void> {
  const row = detail.value
  if (!row || row.id == null || !spaceId.value || !detailHasResponse.value) return
  promoting.value = true
  try {
    const result = await promoteHitToStub(spaceId.value, row.id)
    if (result.createdStubIds.length > 0) {
      ElMessage.success(t('mockInspector.promoteCreated', {
        n: result.createdStubIds.length,
      }))
    } else {
      // Idempotent re-click: the space already serves this method+path.
      ElMessage.info(t('mockInspector.promoteSkipped'))
    }
    void reloadHits()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('mockInspector.promoteFailed')))
  } finally {
    promoting.value = false
  }
}

function fmtTime(raw: string | null | undefined): string {
  if (!raw) return '—'
  return raw.replace('T', ' ').slice(0, 19)
}

// mock-domain ledger: inspector hits are traffic logs no write kind
// touches, so this page only converges its space options when a Mock-space
// draft lands (silent re-read; hits stay whatever traffic produced).
import { useObjectSync } from '@/composables/useObjectSync'
import { mockSpaceLabel } from '@/utils/mockSpaceLabel'

useObjectSync({
  watch: ['MOCK_SPACE_WRITE'],
  onSync: () => void loadSpaces(),
})

void loadSpaces()
</script>

<style scoped>
.mock-inspector-page { padding: 16px 20px; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; flex-wrap: wrap; margin-bottom: 12px; }
.page-title { margin: 0; font-size: 18px; }
.page-subtitle { margin: 4px 0 0; font-size: 12px; color: var(--el-text-color-secondary); }
.header-actions { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.health-bar { display: flex; align-items: center; gap: 8px; padding: 8px 12px; border-radius: 6px; margin-bottom: 12px; font-size: 13px; }
.health-bar .health-icon { flex: none; }
.health-sub { color: var(--el-text-color-secondary); font-size: 12px; }
.tone-success { background: var(--el-color-success-light-9); color: var(--el-color-success); }
.tone-warning { background: var(--el-color-warning-light-9); color: var(--el-color-warning); }
.tone-danger { background: var(--el-color-danger-light-9); color: var(--el-color-danger); }
.tone-info { background: var(--el-color-info-light-9); color: var(--el-color-info); }
.mono { font-family: var(--el-font-family-mono, monospace); font-size: 12px; }
.muted { color: var(--el-text-color-secondary); }
.fault-tag, .session-tag { margin-left: 6px; }
.source-tag { margin-left: 6px; }
.source-tag--llm {
  /* AI-fallback provenance: purple halo (same token pair as the batch-report
     llm badge — one visual language for "an LLM answered this" platform-wide) */
  background: var(--tp-llm-weak);
  color: var(--tp-llm);
  border-color: transparent;
}
.empty-block { padding: 24px 0; }
.trace-title { margin: 16px 0 8px; font-size: 14px; }
.promote-block { display: flex; align-items: center; gap: 10px; margin-top: 16px; flex-wrap: wrap; }
.promote-block .muted { font-size: 12px; }
.detail-body :deep(.el-descriptions) { font-size: 12px; }
</style>
