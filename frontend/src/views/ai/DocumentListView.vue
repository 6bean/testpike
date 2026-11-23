<template>
  <div class="page-root doc-list-page" data-test="doc-list-page">
    <div class="page-card">
      <!-- IA fix (R21 merge form, vision round): the upload action
 rides the filter-bar trailing slot (page-level variant action, ahead
 of the view-state refresh button, never adjacent to the query submit);
 the emptied page-header row is removed entirely (R23 shell strip). the
 project rides the global header context (page selector removed). -->
      <div class="filter-bar" data-test="documentlistview-filter-bar">
        <el-select
          v-model="filters.status"
          :placeholder="t('document.statusAll')"
          clearable
          filterable
          style="width: 130px"
          data-test="doc-filter-status"
          @change="load(1)"
        >
          <el-option v-for="(label, s) in statusOptions" :key="s" :label="label" :value="Number(s)" />
        </el-select>
        <el-select
          v-model="filters.category"
          :placeholder="t('document.categoryAll')"
          clearable
          filterable
          style="width: 130px"
          data-test="doc-filter-category"
          @change="load(1)"
        >
          <DocumentCategoryOptions />
        </el-select>
        <el-input
          v-model="filters.keyword"
          :placeholder="t('document.searchPlaceholder')"
          clearable
          style="width: 180px"
          data-test="doc-filter-keyword"
          @keyup.enter="load(1)"
          @clear="load(1)"
        />
        <!-- migration batch (R21 revision 2): the query submit belongs inline at the
 end of the filter group, not in the header band; refresh is a
 view-state action and rides the trailing slot. Anchors unchanged. -->
        <el-button :icon="Search" data-test="doc-search-btn" @click="load(1)">{{ t('document.search') }}</el-button>
        <div class="filter-bar-actions">
          <el-tooltip :content="t('components.ownerEcho.pickInHeader')" placement="bottom" :disabled="!!projectId">
            <span>
              <el-button
                v-if="canWrite"
                type="primary"
                plain
                :icon="Upload"
                :disabled="!projectId"
                data-test="doc-upload-btn"
                @click="openUpload"
              >
                {{ t('document.upload') }}
              </el-button>
            </span>
          </el-tooltip>
          <el-button :icon="Refresh" data-test="doc-refresh-btn" @click="load()">{{ t('document.refresh') }}</el-button>
        </div>
      </div>

      <el-alert
        v-if="!canWrite && projectId"
        type="info"
        :closable="false"
        class="readonly-hint"
        data-test="doc-readonly-hint"
        :title="t('document.readonlyHint')"
      />

      <TableSkeleton v-if="loading" :cols="10" data-test="documentlistview-skeleton" />
      <!-- Main doc list: header sort is server-side — every
 sortable column below carries a SORT_KEY_MAP key and the ORDER BY happens
 in core SQL over the whole project window. The secondary table
 (doc-change-table) talks to a different endpoint and stays
 outside this sort contract ( exemption). -->
<!-- tp-column-config-exempt: the document ledger table pairs each row with a
      change-diff expandable sub-table; a hideable-columns panel would let users
      strip the lineage/diff affordance that keys this surface (AL batch note). -->
      <el-table border :data="rows" data-test="doc-table" class="data-table" row-key="id"
        @sort-change="onSortChange" v-else>
        <el-table-column prop="name" :label="t('document.name')" min-width="220" show-overflow-tooltip
          sortable="custom" />
        <!-- Lineage column: versions of one logical document share a group id;
             the ordinal and the group size are computed server-side and only
             rendered here (no client re-derivation, no cross-page guessing). -->
        <el-table-column prop="lineageSeq" :label="t('document.lineage')" width="132" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.lineageSize > 1" size="small" type="warning">
              {{ t('document.lineageVersion', { n: row.lineageSeq, total: row.lineageSize }) }}
            </el-tag>
            <el-tag v-else-if="row.lineageSize === 1" size="small">
              {{ t('document.lineageSingle') }}
            </el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="fileType" :label="t('document.fileType')" width="100" align="center"
          sortable="custom">
          <template #default="{ row }">
            <el-tag size="small">{{ row.fileType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="sizeBytes" :label="t('document.size')" width="110" align="right"
          sortable="custom">
          <template #default="{ row }">{{ fmtDocSize(row.sizeBytes) }}</template>
        </el-table-column>
        <el-table-column prop="chunkCount" :label="t('document.chunkCount')" width="110" align="center"
          sortable="custom" />
        <el-table-column prop="category" :label="t('document.category')" width="150" align="center"
          sortable="custom">
          <template #default="{ row }">
            <!-- Inline category edit for writers: optimistic lock decision lives
                 server-side only (stale = 40901 with a readable message naming
                 the current version); readers keep the plain tag. -->
            <el-select
              v-if="canWrite"
              :model-value="row.category || 'TEST_SPEC'"
              size="small"
              filterable
              data-test="doc-category-edit"
              @change="(val: string) => changeCategory(row, val)"
            >
              <DocumentCategoryOptions />
            </el-select>
            <el-tag v-else :type="docCategoryTagType(row.category)" size="small" data-test="doc-category-tag">
              {{ docCategoryLabel(row.category) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('document.usage')" width="260" align="center">
          <template #default="{ row }">
            <!-- Per-document usage switches (row-scoped): all three values go in
                 one call; disabled for non-writers, effect explained on hover. -->
            <div class="doc-usage-cell" data-test="doc-usage-cell">
              <el-checkbox
                :model-value="row.useForExtraction"
                :disabled="!canWrite"
                data-test="doc-usage-extract"
                @change="(v: boolean) => changeUsage(row, 'useForExtraction', v)"
              >
                <el-tooltip :content="t('document.usageExtractTip')" placement="top">
                  <span class="doc-usage-label">{{ t('document.usageExtract') }}</span>
                </el-tooltip>
              </el-checkbox>
              <el-checkbox
                :model-value="row.useInCasePrompt"
                :disabled="!canWrite"
                data-test="doc-usage-prompt"
                @change="(v: boolean) => changeUsage(row, 'useInCasePrompt', v)"
              >
                <el-tooltip :content="t('document.usagePromptTip')" placement="top">
                  <span class="doc-usage-label">{{ t('document.usagePrompt') }}</span>
                </el-tooltip>
              </el-checkbox>
              <el-checkbox
                :model-value="row.useInGrounding"
                :disabled="!canWrite"
                data-test="doc-usage-grounding"
                @change="(v: boolean) => changeUsage(row, 'useInGrounding', v)"
              >
                <el-tooltip :content="t('document.usageGroundingTip')" placement="top">
                  <span class="doc-usage-label">{{ t('document.usageGrounding') }}</span>
                </el-tooltip>
              </el-checkbox>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="status" :label="t('document.status')" width="120" align="center"
          sortable="custom">
          <template #default="{ row }">
            <el-tooltip v-if="row.status === 2 && row.errorMsg" :content="row.errorMsg" placement="top">
              <el-tag :type="docStatusTag(row.status)" size="small" data-test="doc-status-tag">
                {{ statusLabel(row.status) }}
              </el-tag>
            </el-tooltip>
            <el-tag v-else :type="docStatusTag(row.status)" size="small" data-test="doc-status-tag">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('document.failReason')" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.status === 2" class="error-text" data-test="doc-error-msg">{{ row.errorMsg || '-' }}</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="fileHash" :label="t('document.fileHash')" width="130" sortable="custom">
          <template #default="{ row }">
            <el-tooltip :content="row.fileHash" placement="top">
              <span class="hash-text">{{ row.fileHash ? row.fileHash.slice(0, 10) : '-' }}</span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column :label="t('document.operation')" width="250" fixed="right">
          <template #default="{ row }">
            <RowActions
              v-if="docRowActions(row).length"
              :actions="docRowActions(row)"
              @command="(k: string) => onDocRowCmd(k, row)"
            />
            <span v-else>-</span>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty :description="t('document.emptyNoData')">
            <el-button v-if="canWrite" type="primary" plain data-test="doc-empty-upload" :disabled="!projectId" @click="openUpload">
              {{ t('document.upload') }}
            </el-button>
          </el-empty>
        </template>
      </el-table>

      <div class="pagination-bar">
        <el-pagination v-if="total > 0"
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          data-test="doc-pagination"
          @current-change="load()"
          @size-change="load(1)"
        />
      </div>

   
      <div v-if="projectId" class="change-panel" data-test="doc-change-panel">
        <div class="change-panel-header">
          <h3 class="change-panel-title">{{ t('document.changePanelTitle') }}</h3>
          <el-select
            v-model="changeFilters.status"
            :placeholder="t('document.changeStatusAll')"
            clearable
            style="width: 130px"
            data-test="doc-change-filter-status"
            @change="loadChanges(1)"
          >
            <el-option :label="t('document.changeStatusPending')" :value="0" />
            <el-option :label="t('document.changeStatusHandled')" :value="1" />
          </el-select>
          <el-button :icon="Refresh" size="small" data-test="doc-change-refresh-btn" @click="loadChanges()">
            {{ t('document.refresh') }}
          </el-button>
        </div>
        <el-alert
          v-if="changePendingCount > 0"
          type="warning"
          :closable="false"
          class="change-pending-alert"
          data-test="doc-change-pending-alert"
          :title="t('document.changePendingTip', { n: changePendingCount })"
        />
        <el-table border :data="changeRows" data-test="doc-change-table" class="data-table" row-key="id" @expand-change="onChangeRowExpand">
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="change-detail" data-test="doc-change-detail">
                <div v-if="!row.chapterLocatable" class="change-fulltext" data-test="doc-change-fulltext-tip">
                  {{ t('document.changeFulltextTip') }}
                </div>
                <el-table border :data="row.changes" size="small" data-test="doc-change-items">
                  <el-table-column :label="t('document.changeChapter')" min-width="160">
                    <template #default="{ row: item }">
                      <span data-test="doc-change-chapter">{{ item.chapter || t('document.changeChapterNone') }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column :label="t('document.changeType')" width="100" align="center">
                    <template #default="{ row: item }">
                      <el-tag :type="item.changeType === 'delete' ? 'danger' : item.changeType === 'add' ? 'success' : 'warning'" size="small" data-test="doc-change-type">
                        {{ changeTypeLabel(item.changeType) }}
                      </el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column :label="t('document.changeReqCount')" width="110" align="center">
                    <template #default="{ row: item }">
                      <span data-test="doc-change-reqcount">{{ item.requirementIds?.length || 0 }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column :label="t('document.changeCaseCount')" width="110" align="center">
                    <template #default="{ row: item }">
                      <span data-test="doc-change-casecount">{{ item.caseIds?.length || 0 }}</span>
                    </template>
                  </el-table-column>
                </el-table>
                <!-- Affected-case extension (resolved lazily on expand, bounded;
                     bare ids only would not answer "which case do I re-check") -->
                <div v-if="affectedCasesOf(row).length" class="change-affected" data-test="doc-change-affected">
                  <span class="change-affected-title">{{ t('document.affectedCasesTitle') }}</span>
                  <el-tag
                    v-for="c in affectedCasesOf(row)"
                    :key="c.id"
                    size="small"
                    type="info"
                    class="change-case-chip"
                    data-test="doc-change-case-chip"
                  >{{ c.title || `#${c.id} ${t('document.affectedCaseMissing')}` }}</el-tag>
                </div>
                <!-- Pre-generated revision-draft receipt (counts + one-click
                     door into the conversation holding the pending drafts;
                     nothing applies itself — confirmation stays human) -->
                <el-alert
                  v-if="draftReceipts[row.id]"
                  type="success"
                  :closable="false"
                  class="change-draft-receipt"
                  data-test="doc-change-draft-receipt"
                  :title="draftReceiptText(draftReceipts[row.id])"
                >
                  <el-button
                    v-if="draftReceipts[row.id].sessionId"
                    size="small"
                    type="primary"
                    plain
                    data-test="doc-change-draft-go"
                    @click="goDraftSession(draftReceipts[row.id].sessionId)"
                  >{{ t('document.changePregenGo') }}</el-button>
                </el-alert>
              </div>
            </template>
          </el-table-column>
          <el-table-column :label="t('document.changeDocs')" min-width="200">
            <template #default="{ row }">
              <span data-test="doc-change-docs">#{{ row.oldDocumentId }} → #{{ row.newDocumentId }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('document.changeCountCol')" width="100" align="center">
            <template #default="{ row }">
              <span data-test="doc-change-count">{{ row.changes?.length || 0 }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('document.status')" width="110" align="center">
            <template #default="{ row }">
              <el-tag :type="row.status === 0 ? 'warning' : 'success'" size="small" data-test="doc-change-status">
                {{ row.statusLabel }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="createdAt" :label="t('document.changeCreatedAt')" width="170" />
          <el-table-column :label="t('document.changeHandledAt')" width="170">
            <template #default="{ row }">
              <span data-test="doc-change-handledat">{{ row.handledAt || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('document.changeNoteCol')" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">
              <span data-test="doc-change-note">{{ row.handleNote || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('document.operation')" width="230" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="canWrite && row.status === 0"
                size="small"
                plain
                data-test="doc-change-pregen-btn"
                :loading="pregenBusyId === row.id"
                @click="handlePregenRow(row)"
              >{{ t('document.changePregen') }}</el-button>
              <el-button
                v-if="canWrite && row.status === 0"
                size="small"
                type="primary"
                plain
                data-test="doc-change-handle-btn"
                @click="handleChangeRow(row)"
              >
                {{ t('document.changeHandle') }}
              </el-button>
              <span v-else-if="row.status === 1">-</span>
              <span v-else>-</span>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty :description="t('document.changeEmpty')" data-test="doc-change-empty" />
          </template>
        </el-table>
        <div class="pagination-bar">
          <el-pagination
            v-if="changeTotal > 0"
            v-model:current-page="changePage"
            v-model:page-size="changeSize"
            :total="changeTotal"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next"
            data-test="doc-change-pagination"
            @current-change="loadChanges()"
            @size-change="loadChanges(1)"
          />
        </div>
      </div>

   <!-- The in-page contract panel retired here (its full entry set — upload,
           URL pull, endpoint ledger, version compare, generate suggestion — now
           lives on the interface inventory page, one surface, no twins). Keep an
           honest guidance line so the moved entrance stays findable. -->
      <div v-if="projectId" class="contract-moved-hint" data-test="doc-contract-moved-hint">
        <span class="contract-moved-text">{{ t('document.specPanelGone') }}</span>
        <el-button size="small" text type="primary" data-test="doc-contract-goto-btn" @click="goInventory">
          {{ t('document.specPanelGoneBtn') }}
        </el-button>
      </div>

      <FileUploadDialog
        v-model="uploadDialogVisible"
        :title="t('document.upload')"
        :accept="DOC_ACCEPT"
        :max-size-mb="DOC_MAX_SIZE_MB"
        :tip="t('uploadDialog.docTip')"
        :submit-text="t('document.upload')"
        :upload-fn="doUpload"
        @uploaded="onUploaded"
      >
        <!-- IA fix: the upload category belongs to the upload action, so it
 lives inside the dialog instead of competing in the page header. -->
        <template #extra>
          <!-- ownership echo (iron rule 1): read-only, single-sourced from the
 header context; uploads land on the visible project segment -->
          <ProjectOwnerEcho />
          <div class="upload-category-row" data-test="doc-upload-category-row">
            <span class="upload-category-label">{{ t('document.category') }}</span>
            <el-select
              v-model="uploadCategory"
              :placeholder="t('document.category')"
              style="width: 160px"
              filterable
              data-test="doc-category-select"
            >
              <DocumentCategoryOptions />
            </el-select>
          </div>
        </template>
      </FileUploadDialog>

      <ExtractPreviewDialog
        v-model="extractVisible"
        :doc="extractDoc"
        :can-write="canWrite"
        @updated="load()"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import { useKeepAliveList } from '@/composables/useKeepAliveList'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import { Refresh, Search, Upload } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { bizErrorMsg } from '@/utils/bizError'
import FileUploadDialog from '@/components/FileUploadDialog.vue'
import ProjectOwnerEcho from '@/components/ProjectOwnerEcho.vue'
import DocumentCategoryOptions from '@/components/DocumentCategoryOptions.vue'
import ExtractPreviewDialog from '@/views/ai/ExtractPreviewDialog.vue'
import RowActions, { type RowAction } from '@/components/RowActions.vue'
import {
  changeTypeLabel,
  handleDocChangeAnalysis,
  listDocChangeAnalyses,
  reanalyzeDocument,
  type DocChangeAnalysisView,
  pregenDrafts,
  type RevisionDraftPregenView,
} from '@/api/docChangeAnalysis'
import { getCase } from '@/api/case'
import {
  DOC_ACCEPT,
  DOC_MAX_SIZE_MB,
  deleteDocument,
  docCategoryLabel,
  docCategoryTagType,
  docStatusTag,
  fmtDocSize,
  listDocuments,
  updateDocumentCategory,
  updateDocumentUsageSwitches,
  uploadDocument,
  type DocumentView,
} from '@/api/document'

defineOptions({ name: 'DocumentList' })

const { t } = useI18n()
const router = useRouter()
const auth = useAuthStore()

// AK: single-source wiring to the global project context (URL priority chain ->
// primaryId; header switches are followed through followTick).
const { projectId, followTick } = useProjectPageContext()
const rows = ref<DocumentView[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const uploadCategory = ref<string>('TEST_SPEC')
const filters = reactive<{ status: number | null; category: string | null; keyword: string }>({
  status: null, category: null, keyword: '',
})

// Column prop -> core document SORT_COLUMNS whitelist key (single source for
// the sortable decision and the request payload; . Sorting
// happens server-side over the whole project window, not the loaded pageful.
const SORT_KEY_MAP: Record<string, string> = {
  name: 'name',
  fileType: 'file_type',
  sizeBytes: 'size_bytes',
  chunkCount: 'chunk_count',
  category: 'doc_category',
  status: 'status',
  fileHash: 'file_hash',
}
const query = reactive<{ sortBy: string; sortDir: string }>({ sortBy: '', sortDir: '' })

/** Header sort: forward the whitelisted key to core; clearing a sort falls
 * back to the historical id DESC default order; page resets to 1. */
function onSortChange({ prop, order }: { prop: string; order: string | null }): void {
  page.value = 1
  if (order === null) {
    query.sortBy = ''
    query.sortDir = ''
  } else {
    const key = SORT_KEY_MAP[prop]
    if (key) {
      query.sortBy = key
      query.sortDir = order === 'ascending' ? 'asc' : 'desc'
    }
  }
  void load(1)
}

const statusOptions = computed<Record<number, string>>(() => ({
  0: t('document.statusParsing'),
  1: t('document.statusDone'),
  2: t('document.statusFailed'),
  3: t('document.statusExtracting'),
  4: t('document.statusExtracted'),
}))

function statusLabel(s: number): string {
  return statusOptions.value[s] || String(s)
}


const canWrite = computed(() => {
  if (auth.isAdmin) return true
  const p = auth.projects.find((x) => x.projectId === projectId.value)
  return p?.role === 2
})

/**
 * Inline category edit. The optimistic-lock decision lives server-side only:
 * a stale revision is refused with 40901 whose readable message names the
 * current version (no duplicated check here). Success overwrites the row
 * with the fresh view returned by the server; failure shows that server
 * message verbatim and reloads the list to resync.
 */
async function changeCategory(row: DocumentView, category: string): Promise<void> {
  try {
    const fresh = await updateDocumentCategory(row.id, category, row.revision)
    Object.assign(row, fresh)
    ElMessage.success(t('document.categoryChanged', { name: row.name, label: docCategoryLabel(fresh.category) }))
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('document.editFail')))
    void load()
  }
}

type UsageSwitchKey = 'useForExtraction' | 'useInCasePrompt' | 'useInGrounding'

/**
 * Toggle one usage switch. The endpoint expects all three values in one
 * call (a missing field is refused with a readable 40001 naming it), so the
 * payload merges the toggled value with the row's current ones; the fresh
 * row view advances the revision.
 */
async function changeUsage(row: DocumentView, key: UsageSwitchKey, value: boolean): Promise<void> {
  const switches = {
    useForExtraction: key === 'useForExtraction' ? value : row.useForExtraction,
    useInCasePrompt: key === 'useInCasePrompt' ? value : row.useInCasePrompt,
    useInGrounding: key === 'useInGrounding' ? value : row.useInGrounding,
  }
  try {
    const fresh = await updateDocumentUsageSwitches(row.id, switches, row.revision)
    Object.assign(row, fresh)
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('document.editFail')))
    void load()
  }
}

async function load(p = page.value): Promise<void> {
  if (!projectId.value) return
  page.value = p
  loading.value = true
  try {
    const res = await listDocuments({
      projectId: projectId.value, status: filters.status,
      category: filters.category || undefined,
      keyword: filters.keyword || undefined, page: page.value, size: size.value,
      sortBy: query.sortBy || undefined, sortDir: query.sortDir || undefined,
    })
    rows.value = res.list || []
    total.value = res.total || 0
  } catch {
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

const uploadDialogVisible = ref(false)

function openUpload(): void {
  uploadDialogVisible.value = true
}

async function doUpload(file: File, onProgress: (p: number) => void): Promise<DocumentView> {
  if (!projectId.value) throw new Error(t('common.selectProjectPlaceholder'))
  return uploadDocument(projectId.value, file, uploadCategory.value, onProgress)
}

function onUploaded(res: unknown): void {
  const doc = res as DocumentView
  if (doc.status === 1) {
    ElMessage.success(t('document.uploadOk', { name: doc.name, chunks: doc.chunkCount }))
  } else {
    ElMessage.error(t('document.uploadFailed', { msg: doc.errorMsg || t('document.unknownFail') }))
  }
  void load(1)
  // A document tagged REQUIREMENT_DOC is the input for downstream requirement points
  // and cases, so the upload lands the user straight on that row's extraction entry.
  // Starting the extraction is still one explicit click inside the dialog — nothing
  // is fired automatically here.
  if (doc.status === 1 && doc.category === 'REQUIREMENT_DOC') openExtract(doc)
}

const extractVisible = ref(false)
const extractDoc = ref<DocumentView | null>(null)

function openExtract(row: DocumentView): void {
  extractDoc.value = row
  extractVisible.value = true
}

const changeRows = ref<DocChangeAnalysisView[]>([])
const changeTotal = ref(0)
const changePage = ref(1)
const changeSize = ref(10)
const changeFilters = reactive<{ status: number | null }>({ status: null })

const changePendingCount = computed(() => changeRows.value.filter((r) => r.status === 0).length)

async function loadChanges(p = changePage.value): Promise<void> {
  if (!projectId.value) return
  changePage.value = p
  try {
    const res = await listDocChangeAnalyses({
      projectId: projectId.value, status: changeFilters.status,
      page: changePage.value, size: changeSize.value,
    })
    changeRows.value = res.list || []
    changeTotal.value = res.total || 0
  } catch {
    changeRows.value = []
    changeTotal.value = 0
  }
}


async function triggerReanalyze(row: DocumentView): Promise<void> {
  try {
    const view = await reanalyzeDocument(row.id)
    ElMessage.success(t('document.reanalyzeOk', { n: view.changes?.length || 0 }))
    await loadChanges(1)
  } catch (e: unknown) {
    ElMessage.error(bizErrorMsg(e, t('document.reanalyzeFail')))
  }
}


function handleChangeRow(row: DocChangeAnalysisView): void {
  ElMessageBox.prompt(
    t('document.changeHandleConfirm', { id: row.id }),
    t('document.changeHandle'),
    {
      confirmButtonText: t('document.changeHandleConfirmBtn'),
      cancelButtonText: t('document.cancel'),
      inputPlaceholder: t('document.changeNotePlaceholder'),
      inputValidator: (v: string) => v.length <= 500 || t('document.changeNoteTooLong'),
    },
  )
    .then(async ({ value }) => {
      try {
        await handleDocChangeAnalysis(row.id, value || undefined)
        ElMessage.success(t('document.changeHandleOk'))
        await loadChanges()
      } catch (e: unknown) {
        ElMessage.error(bizErrorMsg(e, t('document.changeHandleFail')))
      }
    })
    .catch(() => {  })
}

// ── Revision-draft receipts + affected-case extension (disposition upgrade) ──
/** Receipts keyed by analysis id (field names ride the bridge DTO, no second mapping). */
const draftReceipts = ref<Record<number, RevisionDraftPregenView>>({})
const pregenBusyId = ref<number | null>(null)
/** Case titles resolved lazily when a review row expands; bounded — the queue
 *  card stays light at worst-case scale (S group). */
const caseTitles = ref<Record<number, string | null>>({})
const CASE_TITLE_CAP = 30

function affectedCasesOf(row: DocChangeAnalysisView): { id: number; title: string | null }[] {
  const ids = Array.from(new Set((row.changes || []).flatMap((c) => c.caseIds || [])))
  return ids.slice(0, CASE_TITLE_CAP).map((id) => ({ id, title: caseTitles.value[id] }))
}

async function resolveAffectedCases(row: DocChangeAnalysisView): Promise<void> {
  const ids = Array.from(new Set((row.changes || []).flatMap((c) => c.caseIds || [])))
    .filter((id) => !(id in caseTitles.value))
    .slice(0, CASE_TITLE_CAP)
  if (!ids.length) return
  const settled = await Promise.allSettled(ids.map((id) => getCase(id)))
  ids.forEach((id, i) => {
    const r = settled[i]
    caseTitles.value[id] = r.status === 'fulfilled' && r.value ? r.value.title : null
  })
}

/** el-table expand hook: resolve names only for the row being opened. */
function onChangeRowExpand(row: DocChangeAnalysisView, expanded: unknown): void {
  const open = Array.isArray(expanded)
    ? expanded.some((r: DocChangeAnalysisView) => r.id === row.id)
    : Boolean(expanded)
  if (open) void resolveAffectedCases(row)
}

function draftReceiptText(v: RevisionDraftPregenView): string {
  if (!v.dispatched) return t('document.changePregenNone')
  const skipped = v.skippedExisting + v.skippedForeign + v.skippedMissing + v.skippedNoContent
  return t('document.changePregenOk', { n: v.dispatched, s: skipped })
}

async function handlePregenRow(row: DocChangeAnalysisView): Promise<void> {
  pregenBusyId.value = row.id
  try {
    const receipt = await pregenDrafts(row.id)
    draftReceipts.value = { ...draftReceipts.value, [row.id]: receipt }
    ElMessage.success(draftReceiptText(receipt))
  } catch (e: unknown) {
    ElMessage.error(bizErrorMsg(e, t('document.changePregenFail')))
  } finally {
    pregenBusyId.value = null
  }
}

function goDraftSession(sessionId: number | null): void {
  if (!sessionId || !projectId.value) return
  void router.push({ path: '/ai/chat', query: { session: String(sessionId), projectId: String(projectId.value) } })
}

// ── Guidance jump target for the retired contract panel (the action surface
//    now lives on the interface inventory page, one source, no twins) ──
function goInventory(): void {
  if (!projectId.value) return
  void router.push({ path: '/api-inventory', query: { projectId: String(projectId.value) } })
}

/**
 * Row-action set for the operation column, rendered through the shared
 * RowActions component (site standard: link posture + overflow collapses
 * into a "more" dropdown instead of hand-rolled plain buttons).
 * Status 3 opens the live stage ledger : a running extraction
 * must always have an entry to watch its progress, with or without write
 * permission — the dialog itself is read-only until the run lands.
 */
function docRowActions(row: DocumentView): RowAction[] {
  const acts: RowAction[] = []
  if (row.status === 3) {
    acts.push({ key: 'extract', label: t('document.extractProgress') })
  } else if (canWrite.value && (row.status === 1 || row.status === 4)) {
    acts.push({ key: 'extract', label: row.status === 4 ? t('document.extractView') : t('document.extract') })
    acts.push({ key: 'reanalyze', label: t('document.reanalyze'), type: 'warning' })
  }
  if (canWrite.value) {
    acts.push({ key: 'delete', label: t('document.delete'), type: 'danger' })
  }
  return acts
}
function onDocRowCmd(key: string, row: DocumentView): void {
  if (key === 'extract') openExtract(row)
  else if (key === 'reanalyze') void triggerReanalyze(row)
  else if (key === 'delete') removeDoc(row)
}
function removeDoc(row: DocumentView): void {
  ElMessageBox.confirm(
    t('document.deleteConfirm', { name: row.name, chunks: row.chunkCount }),
    t('document.delete'),
    { type: 'warning', confirmButtonText: t('document.confirmDelete'), cancelButtonText: t('document.cancel') },
  )
    .then(async () => {
      try {
        await deleteDocument(row.id)
        ElMessage.success(t('document.deleteOk'))
        await load()
      } catch (e: unknown) {
        ElMessage.error(bizErrorMsg(e, t('document.deleteFail')))
      }
    })
    .catch(() => {  })
}

onMounted(() => {
  if (projectId.value) {
    load()
    void loadChanges(1)
  }
})

watch(followTick, () => {
  if (!projectId.value) return
  void load(1)
  void loadChanges(1)
})

useKeepAliveList(async () => {
  if (projectId.value) {
    await load()
    void loadChanges()
  }
})
</script>

<style scoped>
.page-root {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.hidden-file {
  display: none;
}
.readonly-hint {
  margin-bottom: 0;
}
.upload-category-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
}
.upload-category-label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.error-text {
  color: var(--el-color-danger);
  font-size: 13px;
}
.hash-text {
  font-family: monospace;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.doc-usage-cell {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
}
.doc-usage-label {
  font-size: 13px;
}
.change-panel {
  margin-top: 8px;
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}
.change-panel-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}
.change-panel-title {
  margin: 0;
  font-size: 15px;
}
.change-pending-alert {
  margin-bottom: 8px;
}
.change-detail {
  padding: 8px 16px;
}
.change-affected {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}
.change-affected-title {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.change-draft-receipt {
  margin-top: 8px;
}
.change-fulltext {
  margin-bottom: 6px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.contract-moved-hint {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
  padding: 10px 12px;
  border: 1px dashed var(--el-border-color);
  border-radius: 8px;
}
.contract-moved-text {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
</style>
