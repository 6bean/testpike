<template>
  <div class="page-root" data-test="req-page">
    <div class="page-card">
      <!-- Filter band (R21 merge tier, user order 2026-11-22): the page-level
 variant actions (create / import) ride the trailing slot together
 with the view-state column-settings popover, right-aligned. The
 conditional batch hand-off bar is a selection state, not a resident
 header, so it does not block the merge; the emptied header band row
 was removed entirely (R23 no shell rows, precedent). -->
      <div class="filter-bar">
        <el-select v-model="filters.status" :placeholder="t('requirement.statusAll')" clearable style="width: 140px" data-test="req-filter-status" @change="load(1)">
          <el-option v-for="(v, k) in REQUIREMENT_STATUS" :key="k" :label="v.label" :value="Number(k)" />
        </el-select>
        <el-input
          v-model="filters.keyword"
          :placeholder="t('requirement.keywordPlaceholder')"
          clearable
          style="width: 280px"
          :prefix-icon="Search"
          data-test="req-filter-keyword"
          @keyup.enter="load(1)"
          @clear="load(1)"
        />
        <el-input
          v-model="filters.ids"
          :placeholder="t('requirement.filterIdsPlaceholder')"
          clearable
          style="width: 170px"
          data-test="req-filter-ids"
          @keyup.enter="load(1)"
          @clear="load(1)"
        />
        <el-input
          v-model="filters.fingerprint"
          :placeholder="t('requirement.filterFingerprintPlaceholder')"
          clearable
          style="width: 230px"
          data-test="req-filter-fingerprint"
          @keyup.enter="load(1)"
          @clear="load(1)"
        />
        <el-button :icon="Search" data-test="req-search" @click="load(1)">{{ t('common.search') }}</el-button>
        <el-button :icon="Refresh" data-test="req-reset" @click="onReset">{{ t('common.reset') }}</el-button>
        <!-- R21 trailing slot: variant actions first (create primary / import),
 view-state actions after .
 Query submit (search/reset) stays inline at the condition-flow end
 — the slot never carries a submit button (FB-2), and create never
 sits adjacent to it (auto push-right + >=24px theme divider). -->
        <div class="filter-bar-actions">
          <el-tooltip
            :content="t('components.ownerEcho.pickInHeader')"
            placement="bottom"
            :disabled="!!projectId"
          >
            <span>
              <el-button v-if="canWrite" type="primary" :icon="Plus" :disabled="!projectId" data-test="req-create" @click="openCreate">
                {{ t('requirement.create') }}
              </el-button>
            </span>
          </el-tooltip>
          <el-button :icon="Upload" :disabled="!projectId" data-test="req-import" @click="importVisible = true">
            {{ t('importDialog.import') }}
          </el-button>
          <ColumnSettings
            :columns="settingsRows"
            :density="colCfg.state.density"
            @toggle="colCfg.toggle"
            @move="colCfg.move"
            @density="colCfg.setDensity"
            @reset="colCfg.reset"
            @pin="colCfg.toggleFixed"
          />
        </div>
      </div>

      
      <div v-if="!loading && projects.length === 0" class="empty-box" data-test="req-empty-no-project">
        <el-empty :description="t('requirement.emptyNoProject')">
          <el-button type="primary" plain @click="router.push('/projects')">{{ t('case.emptyGoProject') }}</el-button>
        </el-empty>
      </div>

      <template v-else>
        <!-- Batch hand-off bar (appears while rows are checked): the selected
             requirement points ride the ids allow-list deep link into the AI
             batch generation page. Navigation only — no write happens here. -->
        <div v-if="reqSelection.length > 0" class="batch-bar" data-test="req-batch-bar">
          <span class="text-secondary">{{ t('case.selectedCount', { n: reqSelection.length }) }}</span>
          <el-button type="primary" size="small" :icon="MagicStick" data-test="req-batch-generate" @click="handoffToAiGenerate">
            {{ t('requirement.batchGenerate') }}
          </el-button>
        </div>
        <TableSkeleton v-if="loading" :cols="7" data-test="requirementlistview-skeleton" />
        <DataTablePage :data="list" row-key="id" data-test="req-table" :size="colCfg.state.density" :column-config="colCfg" :default-sort="activeSort" @selection-change="onReqSelectionChange" @sort-change="onSortChange" v-else>
        <template #empty>
          <EmptyState
            :filtered="hasActiveFilter"
            :title="hasActiveFilter ? t('common.emptyFilteredResult') : t('requirement.emptyItemTitle')"
            :hint="hasActiveFilter ? t('common.emptyFilteredHint') : t('requirement.emptyItemHint')"
            :action-text="t('requirement.emptyItemAction')"
            @action="openCreate"
            @clear-filter="clearReqFilters"
          />
        </template>
          <!-- Row selection (batch hand-off source; the shared deep-link builder
               caps the ids at the server-side allow-list limit). -->
          <el-table-column type="selection" width="42" />
          <!-- configurable data columns (show/hide, order, width,
 density persist through the shared useColumnConfig piece). -->
          <el-table-column
            v-for="col in colCfg.visibleColumns.value"
            :key="col.key"
            :prop="col.key"
            :fixed="col.fixed || false"
            :label="t(col.labelKey)"
            :width="col.width ?? undefined"
            :min-width="col.width ? undefined : REQ_FLEX_MIN[col.key]"
            :sortable="isColSortable(col.key) ? 'custom' : false"
            :show-overflow-tooltip="col.key === 'title'"
          >
            <template #default="{ row }">
              <span v-if="col.key === 'title'" class="req-title" :data-test="`req-title-${row.id}`">{{ row.title }}</span>
              <template v-else-if="col.key === 'chapter'">
                <span v-if="row.chapter" class="mono">{{ row.chapter }}</span>
                <span v-else class="text-secondary">-</span>
              </template>
              <el-tag v-else-if="col.key === 'source'" :type="REQUIREMENT_SOURCE[row.source]?.tag ?? 'info'" size="small">
                {{ REQUIREMENT_SOURCE[row.source]?.label ?? row.source }}
              </el-tag>
              <template v-else-if="col.key === 'sourceId'">
                <span v-if="row.sourceId" class="mono">{{ row.sourceId }}</span>
                <span v-else class="text-secondary">—</span>
              </template>
              <template v-else-if="col.key === 'id' || col.key === 'fingerprint'">
                <span v-if="row[col.key] != null" class="mono" :data-test="`req-val-${col.key}-${row.id}`">{{ row[col.key] }}</span>
                <el-button
                  v-if="row[col.key] != null"
                  link
                  type="primary"
                  :icon="DocumentCopy"
                  :title="t('requirement.copyValue')"
                  :aria-label="t('requirement.copyValue')"
                  data-test="req-copy-value"
                  @click.stop="copyValue(row[col.key])"
                />
                <span v-else class="text-secondary">—</span>
              </template>
              <template v-else-if="col.key === 'status'">
                <el-tag :type="REQUIREMENT_STATUS[row.status]?.tag ?? 'info'" size="small">
                  {{ REQUIREMENT_STATUS[row.status]?.label ?? row.status }}
                </el-tag>
                
                <el-tag
                  v-if="genLiveMap[row.id]"
                  type="warning"
                  size="small"
                  class="req-gen-live-tag"
                  :data-test="`req-gen-live-${row.id}`"
                  :title="t('requirement.genLiveTip', { taskId: genLiveMap[row.id].taskId })"
                  @click="gotoGenerationQueue(genLiveMap[row.id])"
                >{{ genLiveMap[row.id].status === 2 ? t('requirement.genLiveRunning') : t('requirement.genLiveQueued') }}</el-tag>
              </template>
              <TimeCell v-else-if="col.key === 'updatedAt'" :iso="row.updatedAt" />
              <span v-else>{{ row[col.key] }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('common.action')" width="320" fixed="right">
            <template #default="{ row }">
              <FavoriteButton entityType="requirement" :entity-id="row.id" size="small" @change="favSet.apply" />
              <el-tooltip
                :content="sourceDisabledTip(row)"
                :disabled="hasSourceAnchor(row)"
                placement="top"
              >
                <span class="req-source-host">
                  <el-button
                    link
                    type="primary"
                    :disabled="!hasSourceAnchor(row)"
                    data-test="req-view-source"
                    @click="openSourceText(row)"
                  >{{ t('requirement.viewSource') }}</el-button>
                </span>
              </el-tooltip>
              <RowActions :actions="reqRowActions()" :max="2" @command="(k: string) => onReqRowCmd(k, row)" />
            </template>
          </el-table-column>
        </DataTablePage>

        <div v-if="total > 0" class="pagination-bar">
          <el-pagination v-if="total > 0"
            v-model:current-page="page"
            v-model:page-size="size"
            :page-sizes="[10, 20, 50, 100, 1000]"
            :total="total"
            layout="total, sizes, prev, pager, next"
            data-test="req-pagination"
            @size-change="onSizeChange"
            @current-change="load()"
          />
        </div>
      </template>
    </div>

 
    <el-dialog draggable v-model="editVisible" class="tp-dialog--md" :show-close="false" :before-close="reqDraftGuard.beforeClose" data-test="req-edit-dialog">
      <template #header="{ close }">
        <div class="dialog-header">
          <span class="el-dialog__title">{{ editForm.id ? t('requirement.editTitle') : t('requirement.createTitle') }}</span>
 
          <el-icon
            class="req-help-icon"
            :size="15"
            data-test="req-help-icon"
            :title="t('requirement.helpTitle')"
            @click.stop="openHelp('A')"
          >
            <QuestionFilled />
          </el-icon>
          
          <button class="el-dialog__headerbtn" type="button" aria-label="close" data-test="req-dialog-close" @click="close">
            <el-icon><Close /></el-icon>
          </button>
        </div>
      </template>
      <!-- ownership echo (iron rule 1): read-only, single-sourced from the header context -->
      <ProjectOwnerEcho />
      <el-form ref="editFormRef" :model="editForm" :rules="editRules" label-width="110px" scroll-to-error>
        <el-form-item :label="t('requirement.source')" prop="source">
          <!-- id="" suppresses the auto-generated group id: a <label for=...> may only
               target labelable elements, pointing at the radiogroup DIV trips the
               Chromium FormLabelForMatchesNonExistingIdError audit (iron rule 5c). -->
          <el-radio-group id="" v-model="editForm.source" data-test="req-source">
            <el-radio-button v-for="(v, k) in REQUIREMENT_SOURCE" :key="k" :value="Number(k)">{{ v.label }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="t('requirement.sourceId')">
          <el-input v-model="editForm.sourceId" :placeholder="t('requirement.sourceIdPlaceholder')" maxlength="64" data-test="req-source-id" />
        </el-form-item>
        <el-form-item :label="t('requirement.title')" prop="title">
          <el-input v-model="editForm.title" maxlength="300" show-word-limit data-test="req-title" />
        </el-form-item>
        <el-form-item :label="t('requirement.acceptanceCriteria')">
          <el-input v-model="editForm.acceptanceCriteria" type="textarea" :rows="3" :placeholder="t('requirement.acceptancePlaceholder')" data-test="req-acceptance" />
        </el-form-item>
        <el-form-item :label="t('requirement.chapter')">
          <el-input v-model="editForm.chapter" :placeholder="t('requirement.chapterPlaceholder')" maxlength="64" data-test="req-chapter" />
        </el-form-item>
        <el-form-item v-if="editForm.id" :label="t('requirement.status')" prop="status">
          <el-select v-model="editForm.status" style="width: 200px" data-test="req-status">
            <el-option v-for="(v, k) in REQUIREMENT_STATUS" :key="k" :label="v.label" :value="Number(k)" />
          </el-select>
          <span v-if="editForm.status === 3" class="req-status-hint">{{ t('requirement.statusDownHint') }}</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" data-test="req-submit" @click="onSubmit">{{ t('common.confirm') }}</el-button>
      </template>
    </el-dialog>

    
    <el-dialog draggable v-model="linkVisible" :title="`${t('requirement.linkCases')}：${linkTarget?.title ?? ''}`" class="tp-dialog--lg" destroy-on-close data-test="req-link-dialog">
      <div v-loading="linkLoading">
        
        <div class="link-section">
          <div class="link-section-title">
            {{ t('requirement.linkedCases') }}
            <el-tag size="small" type="success" data-test="req-linked-count">{{ linkedCases.length }}</el-tag>
          </div>
          <div v-if="linkedCases.length === 0" class="text-secondary link-empty" data-test="req-linked-empty">
            {{ t('requirement.linkedEmpty') }}
          </div>
          <div v-for="c in linkedCases" :key="c.id" class="linked-row" :data-test="`req-linked-${c.id}`">
            <span class="mono">{{ c.caseNo }}</span>
            <span class="link-case-title">{{ c.title }}</span>
            <el-tag :type="CASE_STATUS[c.status]?.type ?? 'info'" size="small">{{ CASE_STATUS[c.status]?.label ?? c.status }}</el-tag>
            <el-button v-if="canLink" link type="danger" size="small" data-test="req-unlink" @click="onUnlink(c)">
              {{ t('requirement.unlink') }}
            </el-button>
          </div>
        </div>

        
        <div class="link-section">
          <div class="link-section-title">
            {{ t('requirement.candidateCases') }}
            <el-input
              v-model="candidateKeyword"
              :placeholder="t('requirement.candidateSearch')"
              clearable
              size="small"
              style="width: 220px"
              data-test="req-candidate-search"
              @input="filterCandidates"
            />
          </div>
          <DataTablePage :data="candidateRows" size="small" max-height="260" row-key="id" data-test="req-candidate-table" @selection-change="onCandidateSelect">
            <el-table-column type="selection" width="40" :selectable="(row: RequirementCaseBrief) => !isLinked(row.id)" />
            <el-table-column prop="caseNo" :label="t('requirement.colCaseNo')" width="150">
              <template #default="{ row }"><span class="mono">{{ row.caseNo }}</span></template>
            </el-table-column>
            <el-table-column prop="title" :label="t('requirement.title')" min-width="200" show-overflow-tooltip />
            <el-table-column :label="t('requirement.status')" width="90">
              <template #default="{ row }">
                <el-tag :type="CASE_STATUS[row.status]?.type ?? 'info'" size="small">{{ CASE_STATUS[row.status]?.label ?? row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column :label="t('requirement.colPriority')" width="70">
              <template #default="{ row }">
                <el-tag :type="PRIORITY_TYPE[row.priority] ?? 'info'" size="small">{{ PRIORITY_LABEL[row.priority] }}</el-tag>
              </template>
            </el-table-column>
          </DataTablePage>
          <div v-if="candidateRows.length === 0" class="text-secondary link-empty" data-test="req-candidate-empty">
            {{ t('requirement.candidateEmpty') }}
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="linkVisible = false">{{ t('common.close') }}</el-button>
        <el-button v-if="canLink" type="primary" :loading="linking" :disabled="selectedCandidates.length === 0" data-test="req-link-submit" @click="onLinkSubmit">
          {{ t('requirement.linkSelected', { n: selectedCandidates.length }) }}
        </el-button>
      </template>
    </el-dialog>

    <!-- AL batch: requirement version chain dialog (read only; corrupted snapshots stay listed and flagged) -->
    <el-dialog draggable v-model="versionVisible" :title="t('requirement.versionHistory')" class="tp-dialog--lg" destroy-on-close data-test="req-version-dialog">
      <div v-loading="versionLoading">
        <div class="version-target text-secondary" data-test="req-version-target">{{ versionTarget?.title ?? '' }}</div>
        <div v-if="!versionLoading && versionRows.length === 0" class="text-secondary" data-test="req-version-empty">
          {{ t('requirement.versionEmpty') }}
        </div>
        <el-table v-else border :data="versionRows" size="small" data-test="req-version-table">
          <el-table-column prop="version" :label="t('requirement.versionColVersion')" width="90" />
          <el-table-column :label="t('requirement.versionColChangeType')" width="120">
            <template #default="{ row }">
              <el-tag :type="REQ_CHANGE_TYPE[row.changeType]?.type ?? 'info'" size="small">
                {{ REQ_CHANGE_TYPE[row.changeType]?.label ?? row.changeType }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('requirement.versionColOperator')" width="140">
            <template #default="{ row }">{{ row.operatorName || '-' }}</template>
          </el-table-column>
          <el-table-column prop="createdAt" :label="t('requirement.versionColTime')" width="180" />
          <el-table-column :label="t('requirement.versionColFields')" width="120">
            <template #default="{ row }">{{ row.changedFieldCount ?? '-' }}</template>
          </el-table-column>
          <el-table-column :label="t('requirement.versionCorrupted')">
            <template #default="{ row }">
              <el-tag v-if="row.corrupted" type="danger" size="small" data-test="req-version-corrupted">{{ t('requirement.versionCorrupted') }}</el-tag>
              <span v-else>-</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <template #footer>
        <el-button @click="versionVisible = false">{{ t('common.close') }}</el-button>
      </template>
    </el-dialog>

 
    <ImportDialog v-model="importVisible" :project-id="projectId" import-type="requirement" @imported="onImported" />

 
    <el-dialog draggable v-model="traceVisible" :title="t('requirement.traceChainTitle')" class="tp-dialog--md" destroy-on-close>
      <TraceChainPanel :chain="traceChain" :loading="traceLoading" />
    </el-dialog>
  </div>
    <!-- View-original door: the same shared chunk viewer the extraction preview
         dialog uses, so both entries show byte-identical source text. -->
    <SourceChunkViewerDialog
      v-model="sourceVisible"
      :document-id="sourceDocId"
      :chunk-id="sourceChunkId"
      :fallback-evidence="sourceEvidence"
    />
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter, type LocationQueryRaw } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { useDraftGuard } from '@/composables/useDraftGuard'
import { openHelp } from '@/composables/useHelpDrawer'
import { Close, DocumentCopy, MagicStick, Plus, QuestionFilled, Refresh, Search, Upload } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import ImportDialog from '@/components/ImportDialog.vue'
import ColumnSettings from '@/components/ColumnSettings.vue'
import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'
import { listCases, CASE_STATUS, PRIORITY_LABEL, PRIORITY_TYPE, type CaseItem } from '@/api/case'
import {
  REQUIREMENT_SOURCE, REQUIREMENT_STATUS, createRequirement, deleteRequirement, linkCases,
  listLinkedCases, listRequirements, listRequirementVersions, unlinkCase, updateRequirement,
  type RequirementCaseBrief, type RequirementItem, type RequirementVersionItem,
} from '@/api/requirement'
import { getRequirementTraceChain, type TraceChainResult } from '@/api/trace'
import { useObjectSync } from '@/composables/useObjectSync'

// : a landed requirement write re-reads this page with the current
// filters and page number untouched (load() keeps page.value when called
// without an explicit page). Silent per iron rule 2.
useObjectSync({
  watch: ['REQUIREMENT_WRITE'],
  onSync: () => void load(),
})
import TraceChainPanel from '@/components/TraceChainPanel.vue'
import SourceChunkViewerDialog from '@/components/SourceChunkViewerDialog.vue'
import ProjectOwnerEcho from '@/components/ProjectOwnerEcho.vue'
import FavoriteButton from '@/components/FavoriteButton.vue'
import { listLiveRequirementGenerations, type AiRequirementLive } from '@/api/ai'


const genLiveMap = ref<Record<number, AiRequirementLive>>({})

let genLiveTimer: number | null = null

function stopGenLiveTimer(): void {
  if (genLiveTimer !== null) {
    window.clearInterval(genLiveTimer)
    genLiveTimer = null
  }
}

async function refreshGenLive(): Promise<void> {
  const pid = projectId.value
  const ids = list.value.map((r) => r.id)
  if (!pid || ids.length === 0) {
    genLiveMap.value = {}
    stopGenLiveTimer()
    return
  }
  try {
    const rows = await listLiveRequirementGenerations(pid, ids.slice(0, 200))
    const map: Record<number, AiRequirementLive> = {}
    for (const r of rows) map[r.requirementId] = r
    genLiveMap.value = map
    if (rows.length > 0 && genLiveTimer === null) {
      genLiveTimer = window.setInterval(() => void refreshGenLive(), 5000)
    } else if (rows.length === 0) {
      stopGenLiveTimer()
    }
  } catch {
    genLiveMap.value = {}
    stopGenLiveTimer()
  }
}


function gotoGenerationQueue(live: AiRequirementLive): void {
  const q: Record<string, string> = {}
  if (projectId.value) q.projectId = String(projectId.value)
  if (live.batchId) q.batchId = String(live.batchId)
  else q.task = String(live.taskId)
  void router.push({ path: '/ai/generate', query: q })
}

onUnmounted(stopGenLiveTimer)
import { useFavoriteSet } from '@/composables/useFavoriteSet'
import { useKeepAliveList } from '@/composables/useKeepAliveList'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import { confirmStaleReload } from '@/utils/staleConflict'
import { AI_GENERATE_LINK_MAX_IDS, buildAiGenerateDeepLink } from '@/utils/aiBatchLinkage'

defineOptions({ name: 'RequirementList' })

const { t } = useI18n()

// ---------- column configuration (shared useColumnConfig piece) ----------
/** Configurable data columns (the fixed-right action column stays locked out). */
const REQUIREMENT_COLUMNS: ColumnDef[] = [
  { key: 'id', labelKey: 'requirement.colId', width: 80 },
  { key: 'title', labelKey: 'requirement.title', width: null },
  { key: 'chapter', labelKey: 'requirement.chapter', width: 110 },
  { key: 'source', labelKey: 'requirement.source', width: 110 },
  { key: 'sourceId', labelKey: 'requirement.sourceId', width: 130 },
  { key: 'fingerprint', labelKey: 'requirement.colFingerprint', width: 140 },
  { key: 'status', labelKey: 'requirement.status', width: 100 },
  { key: 'updatedAt', labelKey: 'common.updatedAt', width: 160 },
]
/** Flexible columns fall back to min-width when no explicit width is set. */
const REQ_FLEX_MIN: Record<string, number> = { title: 240 }
// Column prop -> core SORT_COLUMNS whitelist key.
// Single source for both the sortable-column decision and the request payload —
// columns outside the whitelist never emit sort params (no client-only fake sort).
const SORT_KEY_MAP: Record<string, string> = {
  title: 'title',
  source: 'source',
  sourceId: 'source_id',
  status: 'status',
  updatedAt: 'updated_at',
}
/** Whether this column has a server-side sort key (shared decision source). */
function isColSortable(key: string): boolean {
  return key in SORT_KEY_MAP
}
const colCfg = useColumnConfig('requirements', REQUIREMENT_COLUMNS)
/** Popover rows (column state merged with labelKey for rendering). */
const settingsRows = computed(() =>
  colCfg.state.columns.map((c) => ({ ...c, labelKey: REQUIREMENT_COLUMNS.find((d) => d.key === c.key)!.labelKey })),
)
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const projects = computed(() => auth.projects)

const importVisible = ref(false)

const traceVisible = ref(false)
const traceLoading = ref(false)
const traceChain = ref<TraceChainResult | null>(null)

async function openTraceChain(row: RequirementItem): Promise<void> {
  traceVisible.value = true
  traceLoading.value = true
  traceChain.value = null
  try {
    traceChain.value = await getRequirementTraceChain(row.id)
  } catch (e) {
    ElMessage.error(t('requirement.traceChainFailed', { msg: (e as Error).message }))
  } finally {
    traceLoading.value = false
  }
}


function onImported(): void {
  void load(1)
}

const loading = ref(false)
const saving = ref(false)
const list = ref<RequirementItem[]>([])
const total = ref(0)
const page = ref(1)
/** Baseline pagination kit (same size ladder as CaseListView): remembered per user. */
const REQ_SIZE_KEY = 'tp-reqs-page-size'
const REQ_SIZES = [10, 20, 50, 100, 1000]
const savedSize = Number(localStorage.getItem(REQ_SIZE_KEY))
const size = ref(REQ_SIZES.includes(savedSize) ? savedSize : 10)

const { projectId, followTick } = useProjectPageContext()

/** Checked rows of the main table (source for the batch hand-off bar). */
const reqSelection = ref<RequirementItem[]>([])

function onReqSelectionChange(rows: RequirementItem[]): void {
  reqSelection.value = rows
}

/**
 * One-click hand-off: the checked ids ride the shared allow-list deep link into
 * the AI batch generation page (the builder caps at the server-side limit; the
 * warning names the outcome instead of silently dropping rows). This navigates
 * only — every generation gate stays on the target page.
 */
function handoffToAiGenerate(): void {
  if (!projectId.value || reqSelection.value.length === 0) return
  if (reqSelection.value.length > AI_GENERATE_LINK_MAX_IDS) {
    ElMessage.warning(t('requirement.batchCapWarn', { max: AI_GENERATE_LINK_MAX_IDS }))
  }
  void router.push(buildAiGenerateDeepLink(projectId.value, reqSelection.value.map((r) => r.id)))
}


const favSet = useFavoriteSet('requirement')

const filters = reactive<{ status?: number; keyword: string; ids: string; fingerprint: string }>({ status: undefined, keyword: '', ids: '', fingerprint: '' })
// Server-side sort state (same shape as the batch/mcp/skill/tool pages).
const query = reactive<{ sortBy: string; sortDir: string }>({ sortBy: '', sortDir: '' })
/** Column prop behind the active server sort — replayed through default-sort
 *  because the skeleton/table v-if swap remounts the table on every load
 *  (LoginStateListView precedent: without it the caret cycle restarts). */
const sortProp = ref('')
const activeSort = computed(() =>
  sortProp.value && (query.sortDir === 'asc' || query.sortDir === 'desc')
    ? { prop: sortProp.value, order: query.sortDir === 'asc' ? 'ascending' : 'descending' }
    : {},
)


const canWrite = computed(() => {
  if (auth.isAdmin) return true
  const p = auth.projects.find((x) => x.projectId === projectId.value)
  return p?.role === 2
})

async function load(p?: number): Promise<void> {
  if (!projectId.value) return
  if (p) page.value = p
  syncUrl()
  loading.value = true
  try {
    const result = await listRequirements({
      projectId: projectId.value,
      status: filters.status,
      keyword: filters.keyword || undefined,
      page: page.value,
      size: size.value,
      sortBy: query.sortBy || undefined,
      sortDir: query.sortDir || undefined,
      // exact lookup (phase B): raw text crosses to the server, which owns the
      // parsing (comma-separated ids fail closed; fingerprint normalizes case/dash).
      ids: filters.ids.trim() || undefined,
      fingerprint: filters.fingerprint.trim() || undefined,
    })
    list.value = result.list || []
    total.value = result.total || 0
  } catch (e) {
    ElMessage.error(t('requirement.loadFailed', { msg: (e as Error).message || t('components.networkError') }))
  } finally {
    loading.value = false
  }
  void refreshGenLive()
}

function onSizeChange(s: number): void {
  size.value = s
  localStorage.setItem(REQ_SIZE_KEY, String(s))
  void load(1)
}

/** F1 page-state URL sync (LoginStateListView pattern): filters/sort/page/size
 * ride the query so refresh, back and a shared link restore the same segment;
 * defaults drop out of the URL, foreign keys (deep-link projectId) are kept.
 * The single call point is load — every state transition goes through it. */
function syncUrl(): void {
  const q: LocationQueryRaw = { ...route.query }
  const put = (key: string, value: string): void => {
    if (value) q[key] = value
    else delete q[key]
  }
  put('page', page.value > 1 ? String(page.value) : '')
  put('size', size.value !== 10 ? String(size.value) : '')
  put('status', filters.status === undefined ? '' : String(filters.status))
  put('keyword', filters.keyword.trim())
  put('ids', filters.ids.trim())
  put('fingerprint', filters.fingerprint.trim())
  put('sortBy', query.sortBy)
  put('sortDir', query.sortBy ? query.sortDir : '')
  void router.replace({ query: q })
}

/** Rebuild the whole list state from the URL (F1: the URL is the single source,
 *  component refs are re-derived from it). Unknown or out-of-ladder values fall
 *  back to defaults instead of rejecting the deep link. */
function applyUrlState(q: Record<string, unknown>): void {
  filters.keyword = typeof q.keyword === 'string' ? q.keyword : ''
  filters.ids = typeof q.ids === 'string' ? q.ids : ''
  filters.fingerprint = typeof q.fingerprint === 'string' ? q.fingerprint : ''
  const statusRaw = typeof q.status === 'string' && q.status !== '' ? Number(q.status) : NaN
  filters.status = Number.isInteger(statusRaw) && statusRaw in REQUIREMENT_STATUS ? statusRaw : undefined
  const sizeRaw = Number(q.size)
  if (REQ_SIZES.includes(sizeRaw)) size.value = sizeRaw
  const sortDir = q.sortDir === 'asc' || q.sortDir === 'desc' ? q.sortDir : 'asc'
  if (typeof q.sortBy === 'string' && Object.values(SORT_KEY_MAP).includes(q.sortBy)) {
    query.sortBy = q.sortBy
    query.sortDir = sortDir
    sortProp.value = Object.keys(SORT_KEY_MAP).find((k) => SORT_KEY_MAP[k] === q.sortBy) ?? ''
  } else {
    query.sortBy = ''
    query.sortDir = ''
    sortProp.value = ''
  }
  const pageRaw = Number(q.page)
  page.value = Number.isInteger(pageRaw) && pageRaw > 1 ? pageRaw : 1
}


/** Header sort: forward the whitelisted key to the server (ORDER BY happens in SQL);
 * clearing a sort falls back to the backend default order; page resets to 1. */
function onSortChange({ prop, order }: { prop: string; order: string | null }): void {
  if (order === null) {
    query.sortBy = ''
    query.sortDir = ''
    sortProp.value = ''
    page.value = 1
    void load()
    return
  }
  const key = SORT_KEY_MAP[prop]
  if (!key) {
    void load()
    return
  }
  query.sortBy = key
  query.sortDir = order === 'ascending' ? 'asc' : 'desc'
  sortProp.value = prop
  page.value = 1
  void load()
}

watch(followTick, () => {
  if (projectId.value) void load(1)
})

function onReset(): void {
  filters.status = undefined
  filters.keyword = ''
  filters.ids = ''
  filters.fingerprint = ''
  void load(1)
}

const editVisible = ref(false)
/** Baseline revision carried into the edit dialog (optimistic lock anchor; admin RequirementView.version echo). */
const editingVersion = ref(0)
const editFormRef = ref<FormInstance>()
const editForm = reactive<{
  id?: number
  source: number
  sourceId: string
  title: string
  acceptanceCriteria: string
  chapter: string
  status: number
}>({ source: 3, sourceId: '', title: '', acceptanceCriteria: '', chapter: '', status: 1 })

const editRules: FormRules = {
  title: [{ required: true, message: t('requirement.titleRequired'), trigger: 'blur' }],
}

function resetForm(): void {
  editForm.id = undefined
  editForm.source = 3
  editForm.sourceId = ''
  editForm.title = ''
  editForm.acceptanceCriteria = ''
  editForm.chapter = ''
  editForm.status = 1
}


const reqDraftGuard = useDraftGuard(editForm as unknown as Record<string, unknown>, resetForm)

function openCreate(): void {
  if (editForm.id !== undefined) reqDraftGuard.discard()
  editVisible.value = true
  reqDraftGuard.snapshot()
}

function openEdit(row: RequirementItem): void {
  editForm.id = row.id
  editForm.source = row.source
  editForm.sourceId = row.sourceId ?? ''
  editForm.title = row.title
  editForm.acceptanceCriteria = row.acceptanceCriteria ?? ''
  editForm.chapter = row.chapter ?? ''
  editForm.status = row.status
  // Optimistic lock: remember the row revision so the PUT carries it as expectedVersion
  editingVersion.value = row.version ?? 0
  editVisible.value = true
  reqDraftGuard.snapshot()
}

async function onSubmit(): Promise<void> {
  if (!editFormRef.value) return
  const valid = await editFormRef.value.validate().catch(() => false)
  if (!valid) return
  if (!projectId.value) return
  saving.value = true
  try {
    if (editForm.id) {
      await updateRequirement(projectId.value, editForm.id, {
        title: editForm.title,
        source: editForm.source,
        sourceId: editForm.sourceId || undefined,
        acceptanceCriteria: editForm.acceptanceCriteria || undefined,
        chapter: editForm.chapter || undefined,
        status: editForm.status,
        expectedVersion: editingVersion.value,
      })
      ElMessage.success(t('requirement.updateSuccess'))
    } else {
      await createRequirement(projectId.value, {
        source: editForm.source,
        sourceId: editForm.sourceId || undefined,
        title: editForm.title,
        acceptanceCriteria: editForm.acceptanceCriteria || undefined,
        chapter: editForm.chapter || undefined,
      })
      ElMessage.success(t('requirement.createSuccess'))
    }
    editVisible.value = false
    reqDraftGuard.discard()
    await load()
  } catch (e) {
    // 40901/409 stale baseline -> shared confirmStaleReload piece (the human line naming
    // current vs submitted revision comes from core; reload refreshes the list underneath).
    const handled = await confirmStaleReload(e, t('requirement.staleConflictTitle'), async () => {
      await load()
    }, t('requirement.staleConflictConsequence'))
    if (!handled) ElMessage.error((e as Error).message || t('common.failed'))
  } finally {
    saving.value = false
  }
}

async function onDelete(row: RequirementItem): Promise<void> {
  if (!projectId.value) return
  try {
    await ElMessageBox.confirm(t('requirement.deleteConfirm', { title: row.title }), t('common.delete'), {
      type: 'warning',
      confirmButtonText: t('common.confirm'),
      cancelButtonText: t('common.cancel'),
    })
  } catch {
    return
  }
  try {
    await deleteRequirement(projectId.value, row.id)
    ElMessage.success(t('requirement.deleteSuccess'))
    await load()
  } catch (e) {
    const msg = (e as Error).message || ''
    if (msg.includes('\u5df2') && msg.includes('\u5173\u8054')) {
      ElMessage.warning(msg)
    } else {
      ElMessage.error(msg || t('common.failed'))
    }
  }
}

const linkVisible = ref(false)

// AL batch version-chain history: read surface of GET /requirements/{id}/versions
// (server sends version descending; change_type 1=edit 2=status 3=delete).
// Rollback is not in this segment — the ledger dialog shows the chain, nothing writes.
const REQ_CHANGE_TYPE: Record<number, { type: 'info' | 'warning' | 'danger'; label: string }> = {
  1: { type: 'info', label: t('requirement.changeTypeEdit') },
  2: { type: 'warning', label: t('requirement.changeTypeStatus') },
  3: { type: 'danger', label: t('requirement.changeTypeDelete') },
}
const versionVisible = ref(false)
const versionLoading = ref(false)
const versionRows = ref<RequirementVersionItem[]>([])
const versionTarget = ref<RequirementItem | null>(null)
async function openVersionHistory(row: RequirementItem): Promise<void> {
  versionTarget.value = row
  versionVisible.value = true
  versionLoading.value = true
  try {
    versionRows.value = await listRequirementVersions(row.id)
  } finally {
    versionLoading.value = false
  }
}
const linkLoading = ref(false)
const linking = ref(false)
const linkTarget = ref<RequirementItem | null>(null)
const linkedCases = ref<RequirementCaseBrief[]>([])
const allCandidates = ref<CaseItem[]>([])
const candidateRows = ref<CaseItem[]>([])
const candidateKeyword = ref('')
const selectedCandidates = ref<CaseItem[]>([])


const canLink = computed(() => {
  if (auth.isAdmin) return true
  const p = auth.projects.find((x) => x.projectId === projectId.value)
  return p != null && p.role !== 3
})

function isLinked(caseId: number): boolean {
  return linkedCases.value.some((c) => c.id === caseId)
}

function onCandidateSelect(rows: CaseItem[]): void {
  selectedCandidates.value = rows
}

function filterCandidates(): void {
  const kw = candidateKeyword.value.trim().toLowerCase()
  candidateRows.value = kw
    ? allCandidates.value.filter(
        (c) => c.title.toLowerCase().includes(kw) || c.caseNo.toLowerCase().includes(kw),
      )
    : [...allCandidates.value]
}

async function openLinkDialog(row: RequirementItem): Promise<void> {
  linkTarget.value = row
  linkVisible.value = true
  linkLoading.value = true
  linkedCases.value = []
  allCandidates.value = []
  candidateRows.value = []
  selectedCandidates.value = []
  candidateKeyword.value = ''
  try {
    const [linked, cases] = await Promise.all([
      listLinkedCases(row.id),
      listCases({ projectId: row.projectId, page: 1, size: 100 }),
    ])
    linkedCases.value = linked || []
    allCandidates.value = cases.list || []
    filterCandidates()
  } catch (e) {
    ElMessage.error(t('requirement.loadLinkedFailed', { msg: (e as Error).message || t('components.networkError') }))
  } finally {
    linkLoading.value = false
  }
}

async function onUnlink(c: RequirementCaseBrief): Promise<void> {
  if (!linkTarget.value) return
  try {
    await unlinkCase(linkTarget.value.id, c.id)
    ElMessage.success(t('requirement.unlinkSuccess', { caseNo: c.caseNo }))
    linkedCases.value = linkedCases.value.filter((x) => x.id !== c.id)
    filterCandidates()
  } catch (e) {
    ElMessage.error((e as Error).message || t('common.failed'))
  }
}

async function onLinkSubmit(): Promise<void> {
  if (!linkTarget.value || selectedCandidates.value.length === 0) return
  linking.value = true
  try {
    const result = await linkCases(linkTarget.value.id, selectedCandidates.value.map((c) => c.id))
    const linkedCount = result.linkedCount ?? selectedCandidates.value.length
    ElMessage.success(
      linkedCount > 0
        ? t('requirement.linkSuccess', { n: linkedCount })
        : t('requirement.linkAlready'),
    )
    const [linked, cases] = await Promise.all([
      listLinkedCases(linkTarget.value.id),
      listCases({ projectId: linkTarget.value.projectId, page: 1, size: 100 }),
    ])
    linkedCases.value = linked || []
    allCandidates.value = cases.list || []
    selectedCandidates.value = []
    filterCandidates()
  } catch (e) {
    ElMessage.error((e as Error).message || t('common.failed'))
  } finally {
    linking.value = false
  }
}

/** Copy an id/fingerprint cell. Clipboard rejection is a visible outcome with
 *  a manual path, never a silent no-op. */
async function copyValue(value: unknown): Promise<void> {
  try {
    await navigator.clipboard.writeText(String(value))
    ElMessage.success(t('requirement.copyValueDone'))
  } catch {
    ElMessage.warning(t('requirement.copyValueFailed'))
  }
}
onMounted(() => {
  applyUrlState(route.query)
  if (projectId.value) void load()
  void favSet.reload()
})

useKeepAliveList(async () => {
  applyUrlState(route.query)
  if (!projectId.value) return
  await Promise.all([load(), favSet.reload()])
})

const hasActiveFilter = computed(() =>
  filters.status !== undefined || filters.keyword.trim() !== ''
    || filters.ids.trim() !== '' || filters.fingerprint.trim() !== '')
function clearReqFilters(): void { filters.status = undefined; filters.keyword = ''; filters.ids = ''; filters.fingerprint = ''; load(1) }
const reqRowActions = () => {
  const acts: { key: string; label: string; type?: "primary" | "warning" | "danger" | "info" }[] = []
  if (canWrite.value) acts.push({ key: "edit", label: t("common.edit") })
  acts.push({ key: "link", label: t("requirement.linkCases"), type: "warning" })
  acts.push({ key: "versions", label: t("requirement.versionHistory"), type: "info" })
  acts.push({ key: "trace", label: t("requirement.traceChain"), type: "info" })
  if (canWrite.value) acts.push({ key: "delete", label: t("common.delete"), type: "danger" })
  return acts
}
function onReqRowCmd(key: string, row: RequirementItem): void {
  if (key === "edit") openEdit(row)
  else if (key === "link") void openLinkDialog(row)
  else if (key === "versions") void openVersionHistory(row)
  else if (key === "trace") void openTraceChain(row)
  else if (key === "delete") void onDelete(row)
}
// first item: view-original. A point adopted from a document extraction
// carries its provenance anchor (document id + chunk ids, plus the verbatim
// excerpt stored at adoption time); a point with nothing recorded has nothing
// to show, so the door is disabled and the tooltip states why.
const sourceVisible = ref(false)
const sourceDocId = ref<number | null>(null)
const sourceChunkId = ref<number | null>(null)
const sourceEvidence = ref('')

/** Either half of an answer opens the door: a readable chunk (needs its
 *  document) or the stored verbatim excerpt. Only a fully empty anchor
 *  disables - the excerpt alone is still the original text recorded at
 *  adoption time, shown with the honest note inside the shared viewer. */
function hasSourceAnchor(row: RequirementItem): boolean {
  const anchor = row.sourceAnchor
  if (anchor == null) return false
  const chunkReachable = anchor.documentId != null && (anchor.chunkIds?.length ?? 0) > 0
  return chunkReachable || Boolean(anchor.evidence && anchor.evidence.trim())
}

/** Disabled-state sentence naming the empty fields and where a source comes
 *  from; empty while the door works (no tooltip on a live action). */
function sourceDisabledTip(row: RequirementItem): string {
  return hasSourceAnchor(row) ? '' : t('requirement.viewSourceNoAnchor')
}

/** Open the shared viewer door: the recorded chunk when its document is
 *  known, otherwise the stored excerpt through the viewer's fallback face. */
function openSourceText(row: RequirementItem): void {
  const anchor = row.sourceAnchor
  if (!hasSourceAnchor(row) || anchor == null) return
  sourceDocId.value = anchor.documentId ?? null
  sourceChunkId.value = anchor.documentId != null ? anchor.chunkIds?.[0] ?? null : null
  sourceEvidence.value = anchor.evidence ?? ''
  sourceVisible.value = true
}
</script>

<style scoped lang="scss">
.req-source-host {
  display: inline-flex;
  align-items: center;
  /* The bare span aligned on the text baseline while every EP sibling button
     uses vertical-align:middle — the view-source action sat 2px above the
     edit/link actions (live rect: top 289 vs 291, A13). Same alignment
     scheme = same line. */
  vertical-align: middle;
}
.req-gen-live-tag {
  margin-left: 6px;
  cursor: pointer;
}

.req-title {
  font-weight: 500;
}

.version-target {
  margin-bottom: 8px;
}


.dialog-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-right: 28px;

  .el-dialog__title {
    flex: 1;
  }

  .req-help-icon {
    cursor: pointer;
    color: var(--tp-primary);
    transition: opacity 0.2s;

    &:hover {
      opacity: 0.75;
    }
  }
}

.req-status-hint {
  margin-left: 10px;
  font-size: 12px;
  color: var(--tp-warning);
}

.link-section {
  margin-bottom: 16px;
}

.link-section-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  font-weight: 600;
}

.link-empty {
  padding: 8px 0;
  font-size: 13px;
}

.linked-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 8px;
  border-radius: 6px;
  background: var(--tp-fill);
  margin-bottom: 6px;

  .link-case-title {
    flex: 1;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}
</style>
