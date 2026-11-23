<template>
  <div class="project-memory-panel" data-test="project-memory-panel">
    <!-- The ledger is strictly project-scoped: with no header project context
 the panel is honestly absent (R30 — the segment comes from the global
 header only, no in-page project picker is born here). -->
    <EmptyState
      v-if="projectId == null"
      :title="t('skill.memoryNoContextTitle')"
      :hint="t('skill.memoryNoContextHint')"
      data-test="memory-no-context"
    />

    <template v-else>
      <!-- R21 merged band (conditions ≤ 4, no batch bar): page-level actions
           live in the trailing slot, the query submit rides inline right after
           the last condition control. -->
      <div class="filter-bar" data-test="memory-filter-bar">
        <el-input
          v-model="query.keyword"
          :placeholder="t('skill.memorySearchPh')"
          clearable
          style="width: 220px"
          data-test="memory-keyword"
          @keyup.enter="reload"
          @clear="reload"
        />
        <el-select
          v-model="query.injectStatus"
          :placeholder="t('skill.memoryStatePh')"
          clearable
          style="width: 130px"
          popper-class="tp-memory-status-popper"
          data-test="memory-status-select"
          @change="reload"
        >
          <el-option :label="t('skill.memoryStateActive')" :value="1" />
          <el-option :label="t('skill.memoryStateDisabled')" :value="0" />
          <el-option :label="t('skill.memoryStatePending')" :value="2" />
          <el-option :label="t('skill.memoryStateRejected')" :value="3" />
        </el-select>
        <el-button data-test="memory-search" @click="reload">
          <el-icon><Search /></el-icon>{{ t('skill.query') }}
        </el-button>
        <div class="filter-bar-actions">
          <!-- Missing-context guidance is the panel-level empty state above
               (buttons live inside the context-present branch only). -->
          <el-button type="primary" :icon="Plus" data-test="memory-create-btn" @click="openCreate">
            {{ t('skill.memoryNew') }}
          </el-button>
          <el-button :icon="Delete" data-test="memory-recycle-open" @click="recycleVisible = true">
            {{ t('skill.memoryRecycle') }}
          </el-button>
        </div>
      </div>

      <TableSkeleton v-if="loading" :cols="5" data-test="memory-skeleton" />
      <el-table v-else border :data="rows" row-key="id" data-test="memory-table">
        <el-table-column prop="content" :label="t('skill.memoryColContent')" min-width="280" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="memory-content" data-test="memory-content-cell">{{ row.content }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('skill.memoryColSource')" width="180">
          <template #default="{ row }">
            <el-tag
              v-for="(a, i) in parseAnchors(row.sourceRef)"
              :key="i"
              size="small"
              type="info"
              effect="light"
              class="memory-anchor"
              data-test="memory-source-tag"
            >{{ a }}</el-tag>
            <span v-if="parseAnchors(row.sourceRef).length === 0" class="text-secondary">—</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('skill.memoryColInject')" width="110" align="center">
          <template #default="{ row }">
            <!-- A pending row without an open card has no confirmation door
                 here — the tag carries the honest guidance (a disabled button
                 cannot surface a tooltip on hover). -->
            <el-tooltip
              v-if="row.injectStatus === 2 && row.draftId == null"
              :content="t('skill.memoryNoDraftTip')"
              placement="top"
            >
              <el-tag :type="stateTagType(row.injectStatus)" size="small" effect="light" data-test="memory-state-tag">
                {{ stateLabel(row.injectStatus) }}
              </el-tag>
            </el-tooltip>
            <el-tag v-else :type="stateTagType(row.injectStatus)" size="small" effect="light" data-test="memory-state-tag">
              {{ stateLabel(row.injectStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="hitCount" :label="t('skill.memoryColHit')" width="110" align="center" data-test="memory-col-hit">
          <template #header>
            <span class="col-header-help">
              {{ t('skill.memoryColHit') }}
              <el-tooltip :content="t('skill.memoryColHitHelp')" placement="top">
                <el-icon class="col-header-help__icon" data-test="memory-hit-help"><QuestionFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="updatedAt" :label="t('common.updatedAt')" width="170" />
        <el-table-column :label="t('common.action')" width="150" fixed="right">
          <template #default="{ row }">
            <RowActions :actions="rowActions(row)" :max="2" @command="(k: string) => onRowCmd(k, row)" />
          </template>
        </el-table-column>
        <template #empty>
          <EmptyState :title="t('skill.memoryEmptyTitle')" :hint="t('skill.memoryEmptyHint')" />
        </template>
      </el-table>

      <div class="pagination-bar">
        <el-pagination
          v-if="total > 0"
          v-model:current-page="query.page"
          :page-size="query.size"
          :total="total"
          layout="total, prev, pager, next"
          data-test="memory-pagination"
          @current-change="load"
        />
      </div>
    </template>

    <!-- Manual fixed-injection entry. The ownership echo is read-only and
         single-sourced from the header context (ownership-echo iron rule: no
         second project selector in the dialog). -->
    <el-dialog
      draggable
      v-model="formVisible"
      :title="t('skill.memoryCreateTitle')"
      width="640px"
      destroy-on-close
      data-test="memory-form-dialog"
    >
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="110px" scroll-to-error>
        <el-form-item :label="t('skill.projectLabel')">
          <span class="owner-echo" data-test="memory-owner-echo">
            {{ t('components.ownerEcho.label') }}{{ projectName }}{{ t('components.ownerEcho.switchHint') }}
          </span>
        </el-form-item>
        <el-form-item :label="t('skill.memoryContentLabel')" prop="content">
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="6"
            :placeholder="t('skill.memoryContentPh')"
            maxlength="2000"
            show-word-limit
            data-test="memory-form-content"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" data-test="memory-form-submit" @click="submitForm">
          {{ t('skill.memoryCreateAction') }}
        </el-button>
      </template>
    </el-dialog>

    <ProjectMemoryRecycleDrawer v-model="recycleVisible" :project-id="projectId" @changed="load()" />
  </div>
</template>

<script setup lang="ts">
/**
 * Project-memory ledger tab .
 * <p>Columns = content + traceability anchors + injection state + hit count
 * (ledger row contract). Governance actions: manual create (lands effective),
 * disable/enable (a disabled entry is absent from the next assembly — same
 * consumption door, no second injection chain), soft-delete into the recycle
 * bin with restore. Every refusal message (cross-project, blank content,
 * unknown project) is core single-source; the frontend copies zero checks —
 * write buttons stay visible and a 403 surfaces the backend human text.
 * Worst-scale posture: server paging + keyword + state filter, so 50~200+
 * entries stay behind ≤2-click reachable controls.
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { Delete, Plus, QuestionFilled, Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import RowActions from '@/components/RowActions.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import ProjectMemoryRecycleDrawer from './ProjectMemoryRecycleDrawer.vue'
import { useAuthStore } from '@/stores/auth'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import {
  createProjectMemory,
  deleteProjectMemory,
  listProjectMemory,
  rejectProjectMemory,
  setProjectMemoryStatus,
  type ProjectMemoryItem,
} from '@/api/projectMemory'
import { applyRepairDraft, requestDraftConfirmTicket } from '@/api/aiChat'
import { useObjectSync } from '@/composables/useObjectSync'

const { t } = useI18n()
const auth = useAuthStore()
// R30: the project segment rides the global header context only.
const { projectId, followTick } = useProjectPageContext()

/** Header project display name for the read-only ownership echo. */
const projectName = computed(() => {
  const hit = auth.projects.find((p) => p.projectId === projectId.value)
  return hit?.projectName ?? '—'
})

const loading = ref(false)
const rows = ref<ProjectMemoryItem[]>([])
const total = ref(0)
const query = reactive({
  page: 1,
  size: 20,
  keyword: '',
  injectStatus: null as number | null,
})

/** Traceability anchors arrive as a JSON array [{type,id}]; render compact tags, never raw JSON. */
function parseAnchors(sourceRef: string): string[] {
  try {
    const arr = JSON.parse(sourceRef) as { type?: string; id?: number | string }[]
    if (!Array.isArray(arr)) return []
    return arr.map((a) => {
      const kind = a.type === 'manual' ? t('skill.memorySourceManual') : (a.type ?? '—')
      return a.id != null ? `${kind} #${a.id}` : kind
    })
  } catch {
    return []
  }
}

async function load(): Promise<void> {
  if (projectId.value == null) return
  loading.value = true
  try {
    const page = await listProjectMemory({
      projectId: projectId.value,
      keyword: query.keyword || undefined,
      injectStatus: query.injectStatus ?? undefined,
      page: query.page,
      size: query.size,
    })
    rows.value = page.list || []
    total.value = page.total || 0
  } catch (e) {
    ElMessage.error((e as Error).message || t('skill.memoryLoadFailed'))
  } finally {
    loading.value = false
  }
}

// : confirming a memory card in the chat dock promotes the
// row's injection state (2 -> 1), which is a column this panel renders —
// re-read the current filter/page silently (iron rules 2-3). The wire name
// rides the DraftObjectType enum (ctx_memory, lowercase by design).
useObjectSync({
  watch: ['ctx_memory'],
  onSync: () => void load(),
})

function reload(): void {
  query.page = 1
  void load()
}

// Header switches and deep-link follow-ups must re-slice the ledger (the same
// watch discipline as the manage tab — context-driven ticks only).
watch(followTick, () => reload())
watch(projectId, () => reload())

onMounted(() => void load())

// ---------- row governance actions ----------
/** Four-state label of the injection column . */
function stateLabel(status: number): string {
  if (status === 1) return t('skill.memoryStateActive')
  if (status === 2) return t('skill.memoryStatePending')
  if (status === 3) return t('skill.memoryStateRejected')
  return t('skill.memoryStateDisabled')
}

function stateTagType(status: number): 'success' | 'info' | 'warning' {
  if (status === 1) return 'success'
  if (status === 2) return 'warning'
  return 'info'
}

/**
 * State machine mirrored for rendering only (the backend refuses any other
 * combination — no check is copied here, a wrong click surfaces core's text):
 * active → disable; disabled → enable; pending → confirm/reject (confirm
 * rides the open card id, disabled with tag guidance when no card is anchored);
 * rejected → delete only (a rejected entry has no enable semantic);
 * every state → soft-delete.
 */
function rowActions(row: ProjectMemoryItem) {
  const acts: { key: string; label: string; type?: 'primary' | 'warning' | 'danger' | 'info' | 'success'; confirm?: string; disabled?: boolean }[] = []
  if (row.injectStatus === 1) {
    acts.push({ key: 'disable', label: t('skill.memoryDisable'), type: 'warning', confirm: t('skill.memoryConfirmDisable') })
  } else if (row.injectStatus === 0) {
    acts.push({ key: 'enable', label: t('skill.memoryEnable'), type: 'success', confirm: t('skill.memoryConfirmEnable') })
  } else if (row.injectStatus === 2) {
    acts.push({
      key: 'adopt',
      label: t('skill.memoryAdopt'),
      type: 'success',
      confirm: t('skill.memoryConfirmAdopt'),
      disabled: row.draftId == null,
    })
    acts.push({ key: 'reject', label: t('skill.memoryReject'), type: 'warning', confirm: t('skill.memoryConfirmReject') })
  }
  acts.push({ key: 'remove', label: t('common.delete'), type: 'danger', confirm: t('skill.memoryConfirmDelete') })
  return acts
}

/**
 * Adopt rides the SHARED draft confirmation door (same two-call protocol as
 * the dialog card: confirm-ticket → apply with the one-shot ticket). The
 * memory row never learns a second protocol; rejections (used ticket, foreign
 * session, no-longer-pending) surface as the backend human text.
 */
async function adoptPending(row: ProjectMemoryItem): Promise<void> {
  if (row.draftId == null) return
  const ticket = await requestDraftConfirmTicket(row.draftId)
  await applyRepairDraft(row.draftId, ticket.token)
  ElMessage.success(t('skill.memoryAdoptedOk'))
}

async function onRowCmd(key: string, row: ProjectMemoryItem): Promise<void> {
  if (projectId.value == null) return
  try {
    if (key === 'disable' || key === 'enable') {
      await setProjectMemoryStatus(row.id, projectId.value, key === 'enable' ? 1 : 0)
      ElMessage.success(key === 'enable' ? t('skill.memoryEnabledOk') : t('skill.memoryDisabledOk'))
    } else if (key === 'adopt') {
      await adoptPending(row)
    } else if (key === 'reject') {
      await rejectProjectMemory(row.id, projectId.value)
      ElMessage.success(t('skill.memoryRejectedOk'))
    } else if (key === 'remove') {
      await deleteProjectMemory(row.id, projectId.value)
      ElMessage.success(t('skill.memoryDeletedOk'))
    }
    void load()
  } catch (e) {
    ElMessage.error((e as Error).message || t('skill.memoryActionFailed'))
  }
}

/** Recycle bin drawer visibility (soft-deleted ledger entries + restore). */
const recycleVisible = ref(false)

// ---------- manual create ----------
const formVisible = ref(false)
const saving = ref(false)
const formRef = ref<FormInstance>()
const form = reactive({ content: '' })
const formRules: FormRules = {
  content: [{ required: true, message: t('skill.memoryContentRequired'), trigger: 'blur' }],
}

function openCreate(): void {
  form.content = ''
  formVisible.value = true
}

async function submitForm(): Promise<void> {
  if (projectId.value == null) return
  const ok = await formRef.value?.validate().catch(() => false)
  if (!ok) return
  saving.value = true
  try {
    await createProjectMemory({ projectId: projectId.value, content: form.content })
    ElMessage.success(t('skill.memoryCreatedOk'))
    formVisible.value = false
    void load()
  } catch (e) {
    ElMessage.error((e as Error).message || t('skill.memoryActionFailed'))
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.project-memory-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.memory-content {
  display: block;
  white-space: pre-line;
}
.memory-anchor {
  margin-right: 6px;
}
/* Column-header help affordance: caption + inline icon, site pattern
   (el-tooltip over QuestionFilled) — the number alone must explain itself
   (0 on a fresh entry means "not matched yet", not "broken"). */
.col-header-help {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.col-header-help__icon {
  color: var(--tp-text-secondary);
  cursor: help;
}
.owner-echo {
  color: var(--tp-text-secondary);
}
</style>
