<!-- tp-subtitle-exempt: the subtitle carries the ownership-rule echo "this queue is the current project segment" (rule G clause 3) — segment semantics, not a restatement of the page name. Kept by the stage-3 exemption ledger, item 2. -->
<!-- tp-owner-echo-exempt: this page is read + disposition only; every write entry
     (disposition dialog) carries ProjectOwnerEcho, and no create-dialog exists here
     (queue rows are produced by contract version diffs, not by user creation). -->
<!-- tp-sort-exempt: the queue travels in server-side order (core list = ORDER BY
     id DESC, newest analysis first); the bridge exposes no sort whitelist and a
     client-only fake sort over one paged window would screen a lie about the queue. -->
<!-- tp-column-config-exempt: every column is disposition-critical (endpoint,
     change type, impact counts, status, action); hiding or reordering any of
     them breaks the review semantics of the queue row, so the fixed layout is
     the contract here — no per-user column surface. -->
<template>
  <div class="page-root spec-change-page" data-test="spec-change-page">
    <div class="page-card">
      <!-- R23 stage-2: the empty title shell row is gone — the page name rides
           the global topbar only. The project-slice echo (dynamic data state,
           exemption class) stays as the first line of the card. -->
      <p class="page-subtitle" data-test="spec-change-subtitle">
        <template v-if="ctx.primaryName">
          {{ t('specChange.pageSubtitle', { project: ctx.primaryName }) }}
        </template>
        <template v-else>{{ t('specChange.pageSubtitleNoProject') }}</template>
      </p>

      <div v-if="!projectId" class="empty-block" data-test="spec-change-no-project">
        <EmptyState :title="t('specChange.noProject')" :hint="t('specChange.noProjectHint')" />
      </div>

      <template v-else>
        <div class="filter-bar" data-test="spec-change-filter-bar">
          <!-- SE rule: status filter is the server-side segment (core list takes
 projectId/status/page/size — never a client-side filter over one
 page window, which would screen a lie about the total). -->
          <el-select
            v-model="statusFilter"
            :placeholder="t('specChange.filterStatus')"
            filterable
            clearable
            style="width: 220px"
            data-test="spec-change-filter-status"
            @change="load(1)"
          >
            <!-- "all" uses the empty-string sentinel: ElOption's value prop only
                 accepts String|Number|Boolean|Object, so null AND undefined both
                 trip a console warning (strict-testing §5b); '' is mapped to
                 "no status filter" at request build time. -->
            <el-option :label="t('specChange.statusAll')" value="" />
            <el-option :label="t('specChange.statusPending')" :value="0" />
            <el-option :label="t('specChange.statusHandled')" :value="1" />
          </el-select>
          <div class="filter-bar-actions">
            <!-- Batch disposition is the page's only write surface; it lives at the
 row end of the filter bar , disabled until rows are
 checked with a plain-language why (rule 2: gate the entry, don't
 reject after the user fills the form). -->
            <el-tooltip
              :disabled="checkedRows.length > 0"
              :content="t('specChange.batchNeedSelection')"
              placement="top"
            >
              <span>
                <el-button
                  type="primary"
                  :disabled="checkedRows.length === 0"
                  data-test="spec-change-batch-btn"
                  @click="openDisposition(null)"
                >
                  {{ t('specChange.batchHandle', { n: checkedRows.length }) }}
                </el-button>
              </span>
            </el-tooltip>
            <!-- migration batch (R21 bands): refresh joined this existing trailing
 slot instead of opening a second one (anchor kept). -->
            <el-button :icon="Refresh" :loading="loading" data-test="spec-change-refresh" @click="load()">
              {{ t('specChange.refresh') }}
            </el-button>
          </div>
        </div>

        <el-alert
          v-if="pendingTotal > 0"
          type="warning"
          :closable="false"
          class="pending-alert"
          data-test="spec-change-pending-alert"
          :title="t('specChange.pendingAlert', { n: pendingTotal })"
        />

        <TableSkeleton v-if="loading && !rows.length" :cols="7" data-test="spec-change-skeleton" />

        <el-table
          v-else
          border
          :data="rows"
          row-key="id"
          class="data-table"
          data-test="spec-change-table"
          @selection-change="(v: ApiSpecChangeAnalysisView[]) => (checkedRows = v)"
        >
          <el-table-column type="expand">
            <template #default="{ row }">
              <!-- endpoint-level diff rows; impact lists are already closed in core,
                   an endpoint that matches nothing still lists with empty impacts
                   (state facts, never fake a closure) -->
              <div class="change-detail" data-test="spec-change-detail">
                <el-table
                  border
                  :data="row.changes"
                  size="small"
                  data-test="spec-change-items"
                >
                  <el-table-column prop="endpointKey" :label="t('specChange.colEndpoint')" min-width="260" show-overflow-tooltip>
                    <template #default="{ row: item }">
                      <span class="mono" data-test="spec-change-endpoint">{{ item.endpointKey }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column :label="t('specChange.colChangeType')" width="110">
                    <template #default="{ row: item }">
                      <el-tag
                        size="small"
                        :type="item.changeType === 'add' ? 'success' : item.changeType === 'removed' ? 'danger' : 'warning'"
                        data-test="spec-change-type"
                      >
                        {{ specChangeTypeLabel(item.changeType) }}
                      </el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column :label="t('specChange.colRequests')" width="120">
                    <template #default="{ row: item }">
                      <span data-test="spec-change-reqcount">{{ item.requestIds?.length || 0 }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column :label="t('specChange.colCases')" width="120">
                    <template #default="{ row: item }">
                      <span data-test="spec-change-casecount">{{ item.caseIds?.length || 0 }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column :label="t('specChange.colMocks')" width="120">
                    <template #default="{ row: item }">
                      <span data-test="spec-change-mockcount">{{ item.mockStubIds?.length || 0 }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column :label="t('specChange.colSuggestion')" min-width="220">
                    <template #default="{ row: item }">
                      <!-- added endpoints without a request yet = the pending-build
 suggestion list the W-c3 ruling asks for; one jump to the
 inventory page (project segment carries via URL rule) -->
                      <el-button
                        v-if="item.changeType === 'add' && !(item.requestIds?.length)"
                        link
                        type="primary"
                        data-test="spec-change-goto-inventory"
                        @click="gotoInventory"
                      >
                        {{ t('specChange.suggestAdd') }}
                      </el-button>
                      <span v-else-if="item.changeType === 'removed' && (item.caseIds?.length || item.mockStubIds?.length)" class="removed-hint" data-test="spec-change-removed-hint">
                        {{ t('specChange.suggestRemoved') }}
                        <!-- the removed-case follow-up is a human-picked batch: this
                             jump lands on the case list pre-filtered by the endpoint
                             path (?projectId + ?keyword = AG §12a-4 grammar, no new
                             query language); selection and batch creation stay manual -->
                        <el-button
                          link
                          type="primary"
                          data-test="spec-change-goto-cases"
                          @click="gotoCasePicker(item.endpointKey)"
                        >{{ t('specChange.gotoCasePicker') }}</el-button>
                      </span>
                      <span v-else class="dim">—</span>
                    </template>
                  </el-table-column>
                </el-table>
              </div>
            </template>
          </el-table-column>
          <el-table-column type="selection" width="46" :selectable="(row: ApiSpecChangeAnalysisView) => row.status === 0" />
          <el-table-column :label="t('specChange.colVersionPair')" min-width="180">
            <template #default="{ row }">
              <span data-test="spec-change-specs">#{{ row.oldSpecId }} → #{{ row.newSpecId }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('specChange.colChangeCount')" width="100">
            <template #default="{ row }">
              <span data-test="spec-change-count">{{ row.changes?.length || 0 }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('specChange.colDetectedSource')" width="120">
            <template #default="{ row }">
              <span data-test="spec-change-source">{{ sourceLabel(row.detectedSource) }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('specChange.colStatus')" width="110">
            <template #default="{ row }">
              <el-tag :type="row.status === 0 ? 'warning' : 'success'" size="small" data-test="spec-change-status">
                {{ row.statusLabel }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="createdAt" :label="t('specChange.colCreatedAt')" width="170" />
          <el-table-column :label="t('specChange.colHandled')" min-width="180">
            <template #default="{ row }">
              <template v-if="row.status === 1">
                <div class="handled-line" data-test="spec-change-handled">
                  {{ t('specChange.handledBy', { at: row.handledAt || '-' }) }}
                </div>
                <div class="handled-note" data-test="spec-change-note">{{ row.handleNote || '—' }}</div>
              </template>
              <span v-else class="dim" data-test="spec-change-unhandled">{{ t('specChange.unhandled') }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('specChange.colAction')" width="110" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="row.status === 0"
                link
                type="primary"
                data-test="spec-change-handle-btn"
                @click="openDisposition(row)"
              >
                {{ t('specChange.handle') }}
              </el-button>
              <span v-else class="dim">—</span>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty :description="t('specChange.empty')" data-test="spec-change-empty" />
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
            data-test="spec-change-pagination"
            @current-change="load()"
            @size-change="load(1)"
          />
        </div>
      </template>

      <!-- One disposition dialog for both the single row and the batch (no second
           form copy); note is REQUIRED here — core rejects a blank disposition
           with a plain-language 400 and names the original handler on a
           duplicate disposition. Ownership echo = header context single source. -->
      <el-dialog
        v-model="dispositionVisible"
        :title="dispositionRow ? t('specChange.dialogTitleRow', { id: dispositionRow.id }) : t('specChange.dialogTitleBatch', { n: checkedRows.length })"
        width="520px"
        data-test="spec-change-dialog"
      >
        <ProjectOwnerEcho />
        <div class="dialog-impact" data-test="spec-change-dialog-impact">
          {{ t('specChange.dialogImpactNote') }}
        </div>
        <el-input
          v-model="dispositionNote"
          type="textarea"
          :rows="4"
          maxlength="500"
          show-word-limit
          :placeholder="t('specChange.notePlaceholder')"
          data-test="spec-change-note-input"
        />
        <template #footer>
          <el-button data-test="spec-change-dialog-cancel" @click="dispositionVisible = false">
            {{ t('specChange.cancel') }}
          </el-button>
          <el-button
            type="primary"
            :disabled="!dispositionNote.trim()"
            :loading="dispositionBusy"
            data-test="spec-change-dialog-confirm"
            @click="submitDisposition"
          >
            {{ t('specChange.dialogConfirm') }}
          </el-button>
        </template>
      </el-dialog>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * Contract change-analysis review queue — the review
 * surface for every OpenAPI version diff that changed endpoints: each row is
 * one old→new version pair with its endpoint-level change items (add/changed/
 * removed, impacts already closed in core), pending until a reviewer leaves a
 * disposition trace (note required, batch = one trace for all checked rows).
 *
 * What it does: read the project's queue (status filter = server-side segment),
 * expand a row to the per-endpoint diff, disposition single or batch. It never
 * auto-creates regression batches — the removed-case follow-up jumps back to
 * the case-selection flow where a human confirms (W-c3 ruling).
 *
 * Single-source rules: project segment = AK global header context ( posture,
 * no in-page selector); statusLabel/human times are core-derived and rendered
 * straight through (bridge passes fields, page re-maps nothing); queue state
 * rules (blank-note 400, duplicate 400 naming the handler, all-or-nothing
 * batch) live in core only — the page only hides what would be rejected.
 */
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import ProjectOwnerEcho from '@/components/ProjectOwnerEcho.vue'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import { useObjectSync } from '@/composables/useObjectSync'
import { useProjectContextStore } from '@/stores/projectContext'
import { bizErrorMsg } from '@/utils/bizError'
import {
  handleSpecChangeAnalysis,
  handleSpecChangeAnalysisBatch,
  listSpecChangeAnalyses,
  specChangeTypeLabel,
  type ApiSpecChangeAnalysisView,
} from '@/api/specChangeAnalysis'

const { t } = useI18n()
const router = useRouter()
const { projectId } = useProjectPageContext()
const ctx = useProjectContextStore()

const rows = ref<ApiSpecChangeAnalysisView[]>([])
const total = ref(0)
const pendingTotal = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const statusFilter = ref<number | ''>('')
const checkedRows = ref<ApiSpecChangeAnalysisView[]>([])

/** detectedSource is a raw enum served by core (upload/fetch/poll); the human
 *  wording is presentation-only i18n, not a second judgment ledger. */
function sourceLabel(v: string): string {
  const map: Record<string, string> = {
    upload: t('specChange.sourceUpload'),
    fetch: t('specChange.sourceFetch'),
    poll: t('specChange.sourcePoll'),
  }
  return map[v] ?? v
}

async function load(p = page.value): Promise<void> {
  if (!projectId.value) return
  page.value = p
  loading.value = true
  try {
    const res = await listSpecChangeAnalyses({
      projectId: projectId.value,
      // '' = "all" sentinel from the dropdown; the wire contract omits the param
      status: statusFilter.value === '' ? undefined : statusFilter.value,
      page: page.value,
      size: size.value,
    })
    rows.value = res.list || []
    total.value = res.total || 0
    await refreshPendingTotal()
  } catch (e: unknown) {
    rows.value = []
    total.value = 0
    ElMessage.error(bizErrorMsg(e, t('specChange.loadFailed')))
  } finally {
    loading.value = false
  }
}

/** The alert counts the WHOLE pending queue (server-side status=0 segment,
 * size=1 — only the total is read), never just the current page window. */
async function refreshPendingTotal(): Promise<void> {
  if (!projectId.value) return
  try {
    const res = await listSpecChangeAnalyses({ projectId: projectId.value, status: 0, page: 1, size: 1 })
    pendingTotal.value = res.total || 0
  } catch {
    pendingTotal.value = 0
  }
}

watch(projectId, (v) => {
  if (v != null) {
    page.value = 1
    statusFilter.value = ''
    void load(1)
  }
}, { immediate: true })

// ── Disposition (single row and batch share ONE dialog + ONE submit path) ──
const dispositionVisible = ref(false)
const dispositionBusy = ref(false)
const dispositionNote = ref('')
const dispositionRow = ref<ApiSpecChangeAnalysisView | null>(null)

function openDisposition(row: ApiSpecChangeAnalysisView | null): void {
  dispositionRow.value = row
  dispositionNote.value = ''
  dispositionVisible.value = true
}

async function submitDisposition(): Promise<void> {
  const note = dispositionNote.value.trim()
  if (!note) return
  dispositionBusy.value = true
  try {
    if (dispositionRow.value) {
      await handleSpecChangeAnalysis(dispositionRow.value.id, note)
      ElMessage.success(t('specChange.handleOk'))
    } else {
      const ids = checkedRows.value.map((r) => r.id)
      await handleSpecChangeAnalysisBatch(ids, note)
      ElMessage.success(t('specChange.batchHandleOk', { n: ids.length }))
    }
    dispositionVisible.value = false
    checkedRows.value = []
    await load()
  } catch (e: unknown) {
    // core plain-language rejections pass through (blank note 400 / duplicate
    // 400 naming the original handler / batch all-or-nothing)
    ElMessage.error(bizErrorMsg(e, t('specChange.handleFail')))
  } finally {
    dispositionBusy.value = false
  }
}

/** Added-endpoint suggestion jump: inventory page of the same project segment
 * (URL projectId is consumed by useProjectPageContext's priority chain, so
 * the jump lands with the right segment instead of a white jump). */
function gotoInventory(): void {
  if (!projectId.value) return
  void router.push({ path: '/api-inventory', query: { projectId: projectId.value } })
}

/** Removed-endpoint follow-up: jump to the case list pre-filtered by the
 *  endpoint path (inventory jumpToAsset posture: keyword = the raw path so
 *  substring search lands honestly — endpointKey carries a leading METHOD).
 *  Selection + batch creation stay fully manual (W-c3: never auto-batch). */
function gotoCasePicker(endpointKey: string): void {
  if (!projectId.value) return
  const path = endpointKey.replace(/^[A-Z]+\s+/, '')
  void router.push({ path: '/cases', query: { projectId: String(projectId.value), keyword: path } })
}

// (apitest segment): a landed contract version with a real endpoint
// diff auto-queues its review row server-side (analyze() inside the apply
// transaction) — this queue converges in place, same page/filter, silently.
useObjectSync({
  watch: ['APISPEC_WRITE'],
  onSync: () => void load(),
})
</script>

<style scoped>
.spec-change-page .pending-alert {
  margin-bottom: 12px;
}
.change-detail {
  padding: 8px 24px;
  /* display-scale guard (vision dim4): an expanded diff list can carry dozens
     of endpoint rows — fold it into a scroll window (same 320px pattern as the
     run-set step drawer) instead of letting one row stretch the page */
  max-height: 320px;
  overflow-y: auto;
}
.mono {
  font-family: var(--tp-font-mono, ui-monospace, monospace);
}
.dim {
  color: var(--tp-text-secondary, #98a2b3);
}
.handled-line {
  font-size: 12px;
  color: var(--tp-text-secondary, #98a2b3);
}
.handled-note {
  font-size: 12px;
  margin-top: 2px;
  color: var(--tp-text-primary, #e6edf3);
}
.removed-hint {
  font-size: 12px;
  color: var(--el-color-warning, #e6a23c);
}
.dialog-impact {
  margin: 8px 0;
  font-size: 13px;
  color: var(--tp-text-secondary, #98a2b3);
}
</style>
