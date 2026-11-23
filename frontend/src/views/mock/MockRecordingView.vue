<template>
  <div class="page-root mock-recording-page" data-test="mock-recording-page">
    <div :class="embedded ? 'mock-recording-embedded' : 'page-card'">
      <!-- R23 stage-2: the title shell row is gone — the page name rides the
 global topbar and the retired title-side "?" now lives on the topbar
 help dot (data-test=header-help-btn). The round 3c posture is
 kept:
 screening (space) lives in the filter-bar; the page write action
 moved to the leading slot of the same row (R23 law 5) and carries
 NO selector there — the project rides the global header context
 (AK/ layout law). -->
      <!-- segment visibility (ownership iron rule 3); the standalone page shows
 it as the first line of the card (the embedded tab host already
 echoes the space segment on the workbench header) -->
      <p v-if="!embedded" class="page-subtitle">
        {{ t('mockRecording.pageSubtitle', { project: ctx.primaryName || '—' }) }}
      </p>

      <div
        v-if="projectId && (spaces.length || !loadingSpaces)"
        class="filter-bar"
        data-test="mock-recording-filter-bar"
      >
        <!-- iron rule 2: without a space picked the entry stays disabled with
             the plain-word tooltip; without any header project the whole row
             is hidden and the page-level no-project empty block below carries
             the honest guidance (scanner-recognised hidden-entry form) -->
        <el-tooltip
          v-if="canWrite"
          :content="!projectId ? t('components.ownerEcho.pickInHeader') : t('mockRecording.pickSpaceFirst')"
          placement="bottom"
          :disabled="!!projectId && !!spaceId"
        >
          <span>
            <el-button
              type="primary"
              :icon="VideoCamera"
              :disabled="!projectId || !spaceId"
              data-test="mock-recording-open"
              @click="openStartDialog"
            >
              {{ t('mockRecording.openBox') }}
            </el-button>
          </span>
        </el-tooltip>
        <el-select
          v-if="!embedded"
                    v-model="pickedSpace"
          :placeholder="t('mockRecording.selectSpace')"
          filterable
          style="width: 220px"
          data-test="mock-recording-space-select"
          :disabled="!spaces.length"
          @change="onSpaceChange"
        >
          <el-option v-for="s in spaces" :key="s.id" :label="spaceLabel(s)" :value="s.id" />
        </el-select>
        <div class="filter-bar-actions" data-test="mock-recording-filter-actions">
          <el-button :icon="Refresh" :loading="loadingBoxes" data-test="mock-recording-refresh" @click="loadBoxes">
            {{ t('mockRecording.refresh') }}
          </el-button>
        </div>
      </div>

      <div v-if="!projectId" class="empty-block" data-test="mock-recording-no-project">
        <EmptyState :title="t('mockRecording.noProject')" />
      </div>
      <div v-else-if="!loadingSpaces && !spaces.length" class="empty-block" data-test="mock-recording-no-space">
        <EmptyState :title="t('mockRecording.noSpace')" :hint="t('mockRecording.noSpaceHint')" />
      </div>

      <template v-else-if="spaceId">
        <TableSkeleton v-if="loadingBoxes && !boxes.length" :cols="6" data-test="mock-recording-skeleton" />
        <el-table border
          v-else
          :data="boxes"
          data-test="mock-recording-table"
          @sort-change="onSortChange"
        >
          <el-table-column :label="t('mockRecording.colTarget')" min-width="260" prop="targetBaseUrl" sortable="custom">
            <template #default="{ row }">
              <span class="mono" data-test="mock-recording-row-target">{{ row.targetBaseUrl }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('mockRecording.colStatus')" width="120" prop="status" sortable="custom">
            <template #default="{ row }">
              <el-tag :type="statusTagType(row.status)" size="small" data-test="mock-recording-status">
                {{ statusText(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('mockRecording.colPromoted')" width="110" prop="promotedCount" sortable="custom">
            <template #default="{ row }">
              <span data-test="mock-recording-promoted">{{ row.promotedCount ?? 0 }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('mockRecording.colStarted')" width="170" prop="startedAt" sortable="custom">
            <template #default="{ row }">{{ timeText(row.startedAt) }}</template>
          </el-table-column>
          <el-table-column :label="t('mockRecording.colEnded')" width="170" prop="endedAt" sortable="custom">
            <template #default="{ row }">{{ timeText(row.endedAt) }}</template>
          </el-table-column>
          <el-table-column :label="t('mockRecording.colActions')" width="220">
            <template #default="{ row }">
              <el-button
                v-if="row.status === 'recording' && canWrite"
                link
                type="warning"
                data-test="mock-recording-seal"
                :loading="sealingId === row.id"
                @click="sealBox(row)"
              >
                {{ t('mockRecording.seal') }}
              </el-button>
              <el-button
                v-if="row.status !== 'recording'"
                link
                type="primary"
                data-test="mock-recording-preview"
                @click="openPreviewDrawer(row)"
              >
                {{ t('mockRecording.previewReplay') }}
              </el-button>
            </template>
          </el-table-column>
          <template #empty>
            <span data-test="mock-recording-empty">{{ t('mockRecording.empty') }}</span>
          </template>
        </el-table>

        <!-- The window is bounded server-side (display-scale iron law,
 ; the pager stays hidden on an empty ledger so it never
 invites dead clicks. -->
        <div v-if="total > 0" class="pagination-bar" data-test="mock-recording-pager">
          <el-pagination
            v-model:current-page="page"
            :page-size="pageSize"
            :total="total"
            layout="prev, pager, next, total"
            @current-change="loadBoxes"
          />
        </div>

        <!-- Open-box dialog. Legality (allowlist ladder) is core's single save
             gate — this page never pre-checks; a refusal shows core's wording. -->
        <el-dialog
          v-model="startVisible"
          :title="t('mockRecording.startTitle')"
          width="560px"
          data-test="mock-recording-start-dialog"
        >
          <!-- ownership echo (iron rule 1): read-only, header context single source -->
          <ProjectOwnerEcho />
          <el-form label-position="top">
            <el-form-item :label="t('mockRecording.startTarget')" required>
              <el-input
                v-model="startTarget"
                maxlength="300"
                :placeholder="t('mockRecording.startTargetPh')"
                data-test="mock-recording-f-target"
              />
            </el-form-item>
          </el-form>
          <p class="start-note">{{ t('mockRecording.startNote') }}</p>
          <div v-if="startError" class="curl-error" data-test="mock-recording-start-error">{{ startError }}</div>
          <template #footer>
            <el-button data-test="mock-recording-start-cancel" @click="startVisible = false">
              {{ t('mockRecording.cancel') }}
            </el-button>
            <el-button
              type="primary"
              :loading="starting"
              :disabled="!startTarget.trim()"
              data-test="mock-recording-start-confirm"
              @click="confirmStart"
            >
              {{ t('mockRecording.openBox') }}
            </el-button>
          </template>
        </el-dialog>

        <!-- Replay preview drawer: the box's frames mapped into save-ready drafts
             under strict|fuzzy. Preview persists nothing; the footer button is
             the write act. Draft rows scroll inside a bounded body so a big
             cassette never pushes the action out of view. -->
        <el-drawer
          v-model="previewVisible"
          :title="t('mockRecording.previewTitle')"
          size="620px"
          data-test="mock-recording-preview-drawer"
        >
          <div class="preview-bar" data-test="mock-recording-preview-bar">
            <el-radio-group v-model="previewMode" data-test="mock-recording-mode" @change="loadPreview">
              <el-radio-button value="strict">{{ t('mockRecording.modeStrict') }}</el-radio-button>
              <el-radio-button value="fuzzy">{{ t('mockRecording.modeFuzzy') }}</el-radio-button>
            </el-radio-group>
            <span class="muted">{{ t('mockRecording.modeHint', { mode: t(previewMode === 'strict' ? 'mockRecording.modeStrict' : 'mockRecording.modeFuzzy') }) }}</span>
          </div>
          <div v-if="previewLoading" class="preview-body" data-test="mock-recording-preview-loading">
            <TableSkeleton :cols="3" />
          </div>
          <div v-else-if="!drafts.length" class="preview-body" data-test="mock-recording-preview-empty">
            <EmptyState :title="t('mockRecording.previewEmpty')" :hint="t('mockRecording.previewEmptyHint')" />
          </div>
          <div v-else class="preview-body" data-test="mock-recording-draft-list">
            <div v-for="d in drafts" :key="d.endpointKey" class="draft-row">
              <div class="draft-line">
                <el-tag size="small" :type="methodTagType(d.method)">{{ d.method }}</el-tag>
                <span class="mono draft-path">{{ d.pathPattern }}</span>
                <el-tag size="small" type="info" data-test="mock-recording-draft-source">{{ draftSourceText(d.source) }}</el-tag>
              </div>
              <div class="draft-name muted">{{ d.name }}</div>
            </div>
          </div>
          <template #footer>
            <span v-if="lastResult" class="muted" data-test="mock-recording-last-result">{{ lastResult }}</span>
            <el-button data-test="mock-recording-preview-close" @click="previewVisible = false">
              {{ t('mockRecording.cancel') }}
            </el-button>
            <el-button
              v-if="canWrite"
              type="primary"
              :loading="importing"
              :disabled="!drafts.length"
              data-test="mock-recording-confirm-import"
              @click="confirmImport"
            >
              {{ t('mockRecording.confirmImport', { n: drafts.length }) }}
            </el-button>
          </template>
        </el-drawer>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * AG batch the cassette recording surface. One row per box
 * (open / seal / replay preview + confirm-and-file). Honest boundaries:
 * - legality (allowlist ladder, target shape, box↔space consistency) is the
 * core save gate's single point — the page forwards and shows refusals
 * untouched, it never claims validity itself;
 * - preview persists nothing (read gate); only the confirm button files
 * rules, through the admin bridge into core's single import channel —
 * re-running the same box skips already-covered keys and the promoted
 * counter accumulates (idempotence wording comes back from core).
 */
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Refresh, VideoCamera } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import ProjectOwnerEcho from '@/components/ProjectOwnerEcho.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import { useMockSpaceUrlPick } from '@/composables/useMockSpaceUrlPick'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import { useProjectContextStore } from '@/stores/projectContext'
import { useAuthStore } from '@/stores/auth'
import { bizErrorMsg } from '@/utils/bizError'
import {
  listSpaces,
  pageRecordings,
  playRecordingBack,
  previewPlayback,
  startRecording,
  stopRecording,
  type MockRecordingItem,
  type MockSpaceItem,
  type MockStubDraftItem,
} from '@/api/mock'

const { t } = useI18n()
const auth = useAuthStore()
// AN-4 embedded contract: the space workbench hosts this page as a tab
// and injects the picked space id (props default off — standalone mounting
// stays possible while retired paths still redirect).
const props = withDefaults(defineProps<{ embedded?: boolean; spaceId?: number | null }>(), { embedded: false, spaceId: null })
const embedded = computed(() => props.embedded)
const ctx = useProjectContextStore()
const { projectId } = useProjectPageContext()

const canWrite = computed(() => auth.hasPerm('perm:mock:write'))

const spaces = ref<MockSpaceItem[]>([])
const loadingSpaces = ref(false)
const pickedSpace = ref<number | null>(null)
const { pickFromQuery, syncSpaceToUrl } = useMockSpaceUrlPick(pickedSpace, projectId)
// : host-injected space id wins over the local pick (redirect-era fallback kept).
const spaceId = computed(() => props.spaceId ?? pickedSpace.value)

const boxes = ref<MockRecordingItem[]>([])
const loadingBoxes = ref(false)
const sealingId = ref<number | null>(null)
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
// Column prop -> core recording SORT_COLUMNS whitelist key (single source for
// the sortable decision and the request payload — columns outside the
// whitelist never emit sort params, no client-only fake sort).
const SORT_KEY_MAP: Record<string, string> = {
  targetBaseUrl: 'target_base_url',
  status: 'status',
  promotedCount: 'promoted_count',
  startedAt: 'started_at',
  endedAt: 'ended_at',
}
// Server-side sort state (same shape as the scenario/endpoints/inspector pages).
const query = reactive<{ sortBy: string; sortDir: string }>({ sortBy: '', sortDir: '' })

/** Header sort: forward the whitelisted key to the server (ORDER BY happens in
 * core SQL); clearing a sort falls back to the historical newest-first order;
 * page resets to 1 so paging windows stay consistent. */
function onSortChange({ prop, order }: { prop: string; order: string | null }): void {
  if (order === null) {
    query.sortBy = ''
    query.sortDir = ''
    page.value = 1
    void loadBoxes()
    return
  }
  const key = SORT_KEY_MAP[prop]
  if (!key) {
    void loadBoxes()
    return
  }
  query.sortBy = key
  query.sortDir = order === 'ascending' ? 'asc' : 'desc'
  page.value = 1
  void loadBoxes()
}

function spaceLabel(s: MockSpaceItem): string {
  const alias = s.alias ? `（${s.alias}）` : ''
  return `${s.name}${alias}`
}

function timeText(v: string | null): string {
  return v ? v.replace('T', ' ').slice(0, 19) : '—'
}

/** Status in words; unknown spectrum values pass through raw (never blank). */
function statusText(status: string): string {
  const key = `mockRecording.status.${status}`
  const label = t(key)
  return label === key ? status : label
}

function statusTagType(status: string): 'primary' | 'success' | 'warning' | 'danger' | 'info' {
  if (status === 'recording') return 'primary'
  if (status === 'stopped') return 'warning'
  if (status === 'promoted') return 'success'
  if (status === 'failed') return 'danger'
  return 'info'
}

function methodTagType(method: string): 'success' | 'info' | 'warning' | 'danger' {
  if (method === 'GET') return 'success'
  if (method === 'POST') return 'warning'
  if (method === 'DELETE') return 'danger'
  return 'info'
}

/** Draft provenance badge (record = cassette family, openapi = contract). */
function draftSourceText(source: string): string {
  const key = `mockRecording.source.${source}`
  const label = t(key)
  return label === key ? source : label
}

async function loadSpaces(): Promise<void> {
  if (!projectId.value) {
    spaces.value = []
    pickedSpace.value = null
    boxes.value = []
    return
  }
  loadingSpaces.value = true
  try {
    spaces.value = await listSpaces(projectId.value)
    const fromQuery = pickFromQuery(spaces.value.map((s) => s.id))
    if (fromQuery != null) pickedSpace.value = fromQuery
    else if (!spaces.value.some((s) => s.id === pickedSpace.value)) {
      pickedSpace.value = spaces.value[0]?.id ?? null
    }
    void syncSpaceToUrl()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('mockRecording.loadSpacesFailed')))
    spaces.value = []
  } finally {
    loadingSpaces.value = false
  }
}

/** Paged box ledger of the picked space (server orders and windows — . */
async function loadBoxes(): Promise<void> {
  if (!spaceId.value) {
    boxes.value = []
    total.value = 0
    return
  }
  loadingBoxes.value = true
  try {
    const res = await pageRecordings(spaceId.value, page.value, pageSize.value, {
      sortBy: query.sortBy, sortDir: query.sortDir,
    })
    boxes.value = res.list
    total.value = Number(res.total)
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('mockRecording.loadFailed')))
    boxes.value = []
    total.value = 0
  } finally {
    loadingBoxes.value = false
  }
}

function onSpaceChange(): void {
  void syncSpaceToUrl()
}

watch(projectId, () => {
  pickedSpace.value = null
  void loadSpaces()
})
watch(spaceId, () => {
  page.value = 1
  void loadBoxes()
}, {
  // Workbench-tab hosting passes a constant spaceId prop — the box list must
  // load on mount, not wait for a change that never comes.
  immediate: true,
})

// ---- open-box dialog ----------------------------------------------------------
const startVisible = ref(false)
const starting = ref(false)
const startError = ref('')
const startTarget = ref('')

function openStartDialog(): void {
  startTarget.value = ''
  startError.value = ''
  startVisible.value = true
}

async function confirmStart(): Promise<void> {
  if (!spaceId.value) return
  startError.value = ''
  starting.value = true
  try {
    await startRecording(spaceId.value, startTarget.value.trim())
    ElMessage.success(t('mockRecording.started'))
    startVisible.value = false
    await loadBoxes()
  } catch (e) {
    // core's allowlist refusal stays the user-facing wording (single point)
    startError.value = bizErrorMsg(e, t('mockRecording.startFailed'))
  } finally {
    starting.value = false
  }
}

async function sealBox(row: MockRecordingItem): Promise<void> {
  if (!spaceId.value) return
  sealingId.value = row.id
  try {
    await stopRecording(spaceId.value, row.id)
    ElMessage.success(t('mockRecording.sealed'))
    await loadBoxes()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('mockRecording.sealFailed')))
  } finally {
    sealingId.value = null
  }
}

// ---- replay preview drawer ----------------------------------------------------
const previewVisible = ref(false)
const previewLoading = ref(false)
const previewMode = ref<'strict' | 'fuzzy'>('strict')
const previewBox = ref<MockRecordingItem | null>(null)
const drafts = ref<MockStubDraftItem[]>([])
const importing = ref(false)
const lastResult = ref('')

function openPreviewDrawer(row: MockRecordingItem): void {
  previewBox.value = row
  drafts.value = []
  lastResult.value = ''
  previewMode.value = 'strict'
  previewVisible.value = true
  void loadPreview()
}

async function loadPreview(): Promise<void> {
  if (!spaceId.value || !previewBox.value) return
  previewLoading.value = true
  try {
    drafts.value = await previewPlayback(spaceId.value, previewBox.value.id, previewMode.value)
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('mockRecording.previewFailed')))
    drafts.value = []
  } finally {
    previewLoading.value = false
  }
}

/**
 * Confirm-and-file. The plain-word summary (created / skipped / accumulated)
 * is the user-facing wording rule (human language, nothing truncated) —
 * no JSON dump.
 */
async function confirmImport(): Promise<void> {
  if (!spaceId.value || !previewBox.value) return
  importing.value = true
  try {
    const res = await playRecordingBack(spaceId.value, previewBox.value.id, previewMode.value)
    lastResult.value = t('mockRecording.importSummary', {
      created: res.createdStubIds.length,
      skipped: res.skippedEndpointKeys.length,
      promoted: res.promotedCount,
    })
    ElMessage.success(lastResult.value)
    await loadBoxes()
    await loadPreview()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('mockRecording.importFailed')))
  } finally {
    importing.value = false
  }
}

// mock-domain ledger: recording boxes are written by traffic, never
// by a dialog draft, so only the space picker this page shows has to converge
// when a Mock-space draft lands (silent option re-read, no prompts).
import { useObjectSync } from '@/composables/useObjectSync'

useObjectSync({
  watch: ['MOCK_SPACE_WRITE'],
  onSync: () => void loadSpaces(),
})

void loadSpaces()
</script>

<style scoped>
.mock-recording-page { padding: 16px 20px; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; flex-wrap: wrap; margin-bottom: 12px; }
.page-title { margin: 0; font-size: 18px; }
.page-subtitle { margin: 4px 0 0; font-size: 12px; color: var(--el-text-color-secondary); }
.mono { font-family: var(--el-font-family-mono, monospace); font-size: 12px; }
/* Long proxy target URLs must wrap instead of overflowing the column. */
.mock-recording-page .mono { word-break: break-all; overflow-wrap: anywhere; }
.muted { color: var(--el-text-color-secondary); }
.empty-block { padding: 24px 0; }
.curl-error { color: var(--el-color-danger); font-size: 12px; }
.start-note { margin: 8px 0 0; font-size: 12px; color: var(--el-text-color-secondary); }
.preview-bar { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin: 6px 0 12px; }
.preview-body { max-height: calc(100vh - 260px); overflow-y: auto; }
.draft-row { padding: 8px 0; border-bottom: 1px solid var(--el-border-color-lighter); }
.draft-line { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
/* Fixed-width method badge so path text shares one left baseline across rows. */
.draft-line > .el-tag:first-child { min-width: 58px; justify-content: center; }
.draft-path { word-break: break-all; }
.draft-name { margin-top: 2px; font-size: 12px; }
</style>
