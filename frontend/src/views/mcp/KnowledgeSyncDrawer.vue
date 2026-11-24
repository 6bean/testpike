<template>
  <el-drawer
    v-model="visible"
    :title="t('mcp.syncDrawerTitle', { name: server?.name ?? '' })"
    size="880px"
    data-test="ksync-drawer"
  >
    <div class="ksync-toolbar">
      <span class="ksync-muted" data-test="ksync-hint">{{ t('mcp.syncHint') }}</span>
      <el-tooltip :disabled="hasProjectContext" :content="t('mcp.syncPickProjectFirst')" placement="top">
        <span>
          <el-button
            type="primary"
            :disabled="!hasProjectContext"
            data-test="ksync-create-open"
            @click="openCreate"
          >
            <el-icon><Plus /></el-icon>{{ t('mcp.syncCreateBtn') }}
          </el-button>
        </span>
      </el-tooltip>
    </div>

    <TableSkeleton v-if="loading" :cols="7" data-test="ksync-skeleton" />
    <el-table v-else border :data="rows" row-key="id" data-test="ksync-table">
      <el-table-column :label="t('mcp.syncColName')" prop="name" min-width="140" show-overflow-tooltip />
      <el-table-column :label="t('mcp.syncColTool')" prop="toolName" min-width="130">
        <template #default="{ row }">
          <el-tooltip :disabled="!hasMapping(row)" :content="mappingSummary(row)" placement="top">
            <code class="ksync-tool">{{ row.toolName }}</code>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column :label="t('mcp.syncColInterval')" width="130">
        <template #default="{ row }">
          <span>{{ intervalLabel(row) }}</span>
          <el-tag v-if="row.autoActivate" size="small" type="warning" class="ksync-auto-tag" data-test="ksync-auto-tag">
            {{ t('mcp.syncAutoOn') }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('mcp.syncColTarget')" min-width="120">
        <template #default="{ row }">{{ targetLabel(row) }}</template>
      </el-table-column>
      <el-table-column :label="t('mcp.syncColState')" width="84" align="center">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'info'" size="small" data-test="ksync-state-tag">
            {{ row.enabled ? t('mcp.syncStateRunning') : t('mcp.syncStatePaused') }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('mcp.syncColLast')" min-width="150">
        <template #default="{ row }">
          <div v-if="row.lastSyncAt">{{ row.lastSyncAt }}</div>
          <div v-else class="ksync-muted">{{ t('mcp.syncNeverRun') }}</div>
        </template>
      </el-table-column>
      <el-table-column :label="t('mcp.syncColResult')" min-width="150">
        <template #default="{ row }">
          <el-tag v-if="row.lastStatus === 'updated'" type="success" size="small">{{ t('mcp.syncStatusUpdated') }}</el-tag>
          <el-tag v-else-if="row.lastStatus === 'unchanged'" type="info" size="small">{{ t('mcp.syncStatusUnchanged') }}</el-tag>
          <el-tooltip v-else-if="row.lastStatus === 'failed'" :content="row.lastError || t('mcp.syncUnknownFail')" placement="top">
            <el-tag type="danger" size="small" data-test="ksync-failed-tag">
              {{ t('mcp.syncStatusFailed') }}
              <template v-if="row.consecutiveFailures && row.consecutiveFailures > 1">×{{ row.consecutiveFailures }}</template>
            </el-tag>
          </el-tooltip>
          <span v-else class="ksync-muted" data-test="ksync-no-round">—</span>
          <div v-if="row.lastStatus === 'updated' || row.lastStatus === 'unchanged'" class="ksync-counts" data-test="ksync-counts">
            {{ t('mcp.syncCounts', { added: row.lastNewCount ?? 0, skipped: row.lastSkippedCount ?? 0 }) }}
          </div>
        </template>
      </el-table-column>
      <el-table-column :label="t('common.action')" width="110" fixed="right">
        <template #default="{ row }">
          <RowActions :actions="syncRowActions(row)" :max="1" @command="(k: string) => onRowCmd(k, row)" />
        </template>
      </el-table-column>
    </el-table>
    <EmptyState v-if="!loading && rows.length === 0" :title="t('mcp.syncEmptyTitle')" />

    <div class="pagination-bar" v-if="total > query.size">
      <el-pagination
        v-model:current-page="query.page"
        :page-size="query.size"
        :total="total"
        layout="total, prev, pager, next"
        data-test="ksync-pagination"
        @current-change="load"
      />
    </div>

    <!-- Register/edit form. Every guard (name uniqueness, read-only whitelist,
         interval range, target existence) stays single-sourced in the core
         service; 40001 plain messages ride BizError to the toast and the
         dialog stays open for correction. -->
    <el-dialog
      v-model="formVisible"
      :title="editingId ? t('mcp.syncFormTitleEdit') : t('mcp.syncFormTitleCreate')"
      width="640px"
      destroy-on-close
      data-test="ksync-form-dialog"
    >
      <el-form :model="form" label-width="110px">
        <el-form-item :label="t('mcp.syncFormOwner')">
          <ProjectOwnerEcho />
        </el-form-item>
        <el-form-item :label="t('mcp.syncColName')" required>
          <el-input
            v-model="form.name"
            maxlength="64"
            show-word-limit
            :placeholder="t('mcp.syncNamePh')"
            data-test="ksync-form-name"
          />
        </el-form-item>
        <el-form-item :label="t('mcp.syncColTool')" required>
          <el-select
            v-model="form.toolName"
            filterable
            :placeholder="t('mcp.syncToolPh')"
            popper-class="tp-ksync-tool-popper"
            style="width: 100%"
            data-test="ksync-form-tool"
          >
            <el-option v-for="n in readonlyToolNames" :key="n" :label="n" :value="n" />
          </el-select>
          <div v-if="readonlyToolNames.length === 0" class="ksync-muted" data-test="ksync-tool-hint">
            {{ t('mcp.syncNoReadonlyTools') }}
          </div>
        </el-form-item>
        <el-form-item :label="t('mcp.syncFormParams')">
          <div class="ksync-params">
            <div v-for="(p, i) in form.params" :key="i" class="ksync-param-row">
              <el-input v-model="p.key" :placeholder="t('mcp.syncParamKeyPh')" data-test="ksync-param-key" />
              <el-input v-model="p.value" :placeholder="t('mcp.syncParamValuePh')" data-test="ksync-param-value" />
              <el-button
                text
                :icon="Delete"
                :aria-label="t('mcp.syncParamRemove')"
                data-test="ksync-param-remove"
                @click="form.params.splice(i, 1)"
              />
            </div>
            <el-button text type="primary" data-test="ksync-param-add" @click="addParam">
              {{ t('mcp.syncParamAdd') }}
            </el-button>
          </div>
        </el-form-item>
        <el-form-item :label="t('mcp.syncFormInterval')">
          <el-input-number v-model="form.interval" :min="1" :max="10080" data-test="ksync-form-interval" />
          <div class="ksync-muted ksync-interval-hint">{{ t('mcp.syncIntervalPh') }}</div>
        </el-form-item>
        <el-form-item :label="t('mcp.syncFormTarget')">
          <el-select
            v-model="form.targetProjectId"
            filterable
            popper-class="tp-ksync-target-popper"
            style="width: 100%"
            data-test="ksync-form-target"
          >
            <el-option :label="t('mcp.syncTargetGlobal')" :value="0" />
            <el-option v-for="p in auth.projects" :key="p.projectId" :label="p.projectName" :value="p.projectId" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('mcp.syncFormAuto')">
          <el-switch v-model="form.autoActivate" data-test="ksync-form-auto" />
          <div class="ksync-muted ksync-auto-tip" data-test="ksync-auto-tip">{{ t('mcp.syncAutoTip') }}</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button data-test="ksync-form-cancel" @click="formVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button
          type="primary"
          :loading="saving"
          :disabled="readonlyToolNames.length === 0"
          data-test="ksync-form-submit"
          @click="onSubmit"
        >
          {{ editingId ? t('common.save') : t('mcp.syncSubmitRegister') }}
        </el-button>
      </template>
    </el-dialog>
  </el-drawer>
</template>

<script setup lang="ts">
/**
 * Knowledge sync subscription drawer ( AO face, opened from the MCP
 * server row action; zero new menu leaf).
 * <p>What it does: register/edit periodic syncs that pull a read-only tool of
 * this server into a knowledge bucket, watch each round's bookkeeping (last
 * sync time, added/skipped counts, typed failure), and offer pause/resume and
 * an immediate run. The tool picker only lists snapshot tools that are both
 * enabled and read-only — the authoritative re-check lives in the core
 * service, this face mirrors the same snapshot field (annotations.readOnlyHint).
 * <p>Ownership follows the header project context (single switch, R30); the
 * landing bucket selector is a write-target control (registered exemption).
 * auto_activate defaults off: entries land as pending activation unless the
 * user explicitly turns it on.
 */
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Plus } from '@element-plus/icons-vue'
import RowActions, { type RowAction } from '@/components/RowActions.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import ProjectOwnerEcho from '@/components/ProjectOwnerEcho.vue'
import { useAuthStore } from '@/stores/auth'
import { useProjectContextStore } from '@/stores/projectContext'
import { parseSnapshotTools, type McpServerItem } from '@/api/mcpServer'
import {
  createKnowledgeSync,
  deleteKnowledgeSync,
  listKnowledgeSyncs,
  pauseKnowledgeSync,
  resumeKnowledgeSync,
  runKnowledgeSyncNow,
  updateKnowledgeSync,
  type KnowledgeSyncItem,
  type KnowledgeSyncUpsertPayload,
} from '@/api/mcpKnowledgeSync'

const props = defineProps<{ modelValue: boolean; server: McpServerItem | null }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void }>()

const { t } = useI18n()
const auth = useAuthStore()
const ctx = useProjectContextStore()

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})

const hasProjectContext = computed(() => ctx.primaryId != null)

const loading = ref(false)
const rows = ref<KnowledgeSyncItem[]>([])
const total = ref(0)
const query = reactive<{ page: number; size: number }>({ page: 1, size: 10 })

/** Snapshot tools that are enabled AND read-only (sync-safe pickable set). */
const readonlyToolNames = computed(() =>
  parseSnapshotTools(props.server?.toolsJson ?? null)
    .filter((tool) => tool.enabled && tool.readOnly)
    .map((tool) => tool.name),
)

async function load(): Promise<void> {
  if (!props.server) return
  loading.value = true
  try {
    const pageData = await listKnowledgeSyncs(props.server.id, { page: query.page, size: query.size })
    rows.value = pageData.list || []
    total.value = Number(pageData.total) || 0
  } catch (e) {
    ElMessage.error((e as Error).message || t('mcp.syncLoadFailed'))
  } finally {
    loading.value = false
  }
}

watch(visible, (open) => {
  if (open) {
    query.page = 1
    void load()
  }
})

function hasMapping(row: KnowledgeSyncItem): boolean {
  return !!row.paramMapping && Object.keys(row.paramMapping).length > 0
}

function mappingSummary(row: KnowledgeSyncItem): string {
  return Object.entries(row.paramMapping || {})
    .map(([k, v]) => `${k}=${String(v)}`)
    .join('; ')
}

function intervalLabel(row: KnowledgeSyncItem): string {
  return row.syncIntervalMinutes != null
    ? t('mcp.syncIntervalMinutes', { n: row.syncIntervalMinutes })
    : t('mcp.syncIntervalDefault')
}

function targetLabel(row: KnowledgeSyncItem): string {
  if (row.targetProjectId === 0) return t('mcp.syncTargetGlobal')
  return auth.projects.find((p) => p.projectId === row.targetProjectId)?.projectName ?? `#${row.targetProjectId}`
}

const formVisible = ref(false)
const saving = ref(false)
const editingId = ref<number | null>(null)
const editingItem = ref<KnowledgeSyncItem | null>(null)
const form = reactive<{
  name: string
  toolName: string
  params: { key: string; value: string }[]
  interval?: number
  targetProjectId: number
  autoActivate: boolean
}>({
  name: '',
  toolName: '',
  params: [],
  interval: undefined,
  targetProjectId: 0,
  autoActivate: false,
})

function resetForm(): void {
  form.name = ''
  form.toolName = ''
  form.params = []
  form.interval = undefined
  form.targetProjectId = 0
  form.autoActivate = false
}

function openCreate(): void {
  editingId.value = null
  editingItem.value = null
  resetForm()
  formVisible.value = true
}

function openEdit(row: KnowledgeSyncItem): void {
  editingId.value = row.id
  editingItem.value = row
  form.name = row.name
  form.toolName = row.toolName
  form.params = Object.entries(row.paramMapping || {}).map(([key, value]) => ({ key, value: String(value) }))
  form.interval = row.syncIntervalMinutes ?? undefined
  form.targetProjectId = row.targetProjectId
  form.autoActivate = row.autoActivate
  formVisible.value = true
}

function addParam(): void {
  form.params.push({ key: '', value: '' })
}

function buildPayload(): KnowledgeSyncUpsertPayload {
  const mapping: Record<string, unknown> = {}
  for (const p of form.params) {
    const key = p.key.trim()
    if (key) mapping[key] = p.value
  }
  // Ownership: a new subscription belongs to the header project; an edit keeps
  // the row's original owner (the header switch must not move a record).
  const projectId = editingId.value != null
    ? editingItem.value?.projectId ?? ctx.primaryId ?? 0
    : ctx.primaryId ?? 0
  return {
    projectId,
    name: form.name.trim(),
    toolName: form.toolName,
    paramMapping: mapping,
    syncIntervalMinutes: form.interval ?? null,
    targetProjectId: form.targetProjectId,
    targetPackId: 0,
    autoActivate: form.autoActivate,
  }
}

async function onSubmit(): Promise<void> {
  if (!props.server) return
  if (!form.name.trim()) {
    ElMessage.warning(t('mcp.syncNameRequired'))
    return
  }
  if (!form.toolName) {
    ElMessage.warning(t('mcp.syncToolRequired'))
    return
  }
  saving.value = true
  try {
    if (editingId.value == null) {
      await createKnowledgeSync(props.server.id, buildPayload())
      ElMessage.success(t('mcp.syncCreated'))
      // The ledger orders by id DESC: jump back to page 1 so the row just
      // created is on screen instead of hidden behind a stale page. Updates
      // keep the current page (the edited row was already visible there).
      query.page = 1
    } else {
      await updateKnowledgeSync(props.server.id, editingId.value, buildPayload())
      ElMessage.success(t('mcp.syncSaved'))
    }
    formVisible.value = false
    await load()
  } catch (e) {
    // core-side guard messages (40001) pass through verbatim; the dialog
    // stays open so the user can correct the field and retry
    ElMessage.error((e as Error).message || t('mcp.syncSaveFailed'))
  } finally {
    saving.value = false
  }
}

async function onPause(row: KnowledgeSyncItem): Promise<void> {
  try {
    await pauseKnowledgeSync(props.server!.id, row.id)
    ElMessage.success(t('mcp.syncPaused'))
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message || t('mcp.syncOpFailed'))
  }
}

async function onResume(row: KnowledgeSyncItem): Promise<void> {
  try {
    await resumeKnowledgeSync(props.server!.id, row.id)
    ElMessage.success(t('mcp.syncResumed'))
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message || t('mcp.syncOpFailed'))
  }
}

async function onRunNow(row: KnowledgeSyncItem): Promise<void> {
  try {
    const round = await runKnowledgeSyncNow(props.server!.id, row.id)
    if (round.status === 'updated') {
      ElMessage.success(t('mcp.syncRoundUpdated', { added: round.newCount, skipped: round.skippedCount }))
    } else if (round.status === 'unchanged') {
      ElMessage.info(t('mcp.syncRoundUnchanged', { skipped: round.skippedCount }))
    } else {
      ElMessage.error(t('mcp.syncRoundFailed', { reason: round.error || t('mcp.syncUnknownFail') }))
    }
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message || t('mcp.syncOpFailed'))
  }
}

async function onDelete(row: KnowledgeSyncItem): Promise<void> {
  try {
    await ElMessageBox.confirm(
      t('mcp.syncDeleteConfirm', { name: row.name }),
      t('mcp.syncColName'),
      { type: 'warning', confirmButtonText: t('common.delete'), cancelButtonText: t('common.cancel') },
    )
  } catch {
    return
  }
  try {
    await deleteKnowledgeSync(props.server!.id, row.id)
    ElMessage.success(t('mcp.syncDeleted'))
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message || t('mcp.syncDeleteFailed'))
  }
}

function syncRowActions(row: KnowledgeSyncItem): RowAction[] {
  return [
    { key: 'run', label: t('mcp.syncActRun') },
    { key: row.enabled ? 'pause' : 'resume', label: row.enabled ? t('mcp.syncActPause') : t('mcp.syncActResume') },
    { key: 'edit', label: t('common.edit') },
    { key: 'delete', label: t('common.delete'), type: 'danger' },
  ]
}

function onRowCmd(cmd: string, row: KnowledgeSyncItem): void {
  if (cmd === 'run') onRunNow(row)
  else if (cmd === 'pause') onPause(row)
  else if (cmd === 'resume') onResume(row)
  else if (cmd === 'edit') openEdit(row)
  else if (cmd === 'delete') onDelete(row)
}
</script>

<style scoped>
.ksync-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 12px;
}
.ksync-muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.ksync-tool {
  font-family: var(--el-font-family-mono, monospace);
  font-size: 12px;
  word-break: break-all;
}
.ksync-auto-tag {
  margin-left: 6px;
}
.ksync-counts {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 2px;
}
.ksync-params {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.ksync-param-row {
  display: flex;
  gap: 8px;
  align-items: center;
}
.ksync-interval-hint,
.ksync-auto-tip {
  margin-top: 4px;
  line-height: 1.5;
}
</style>
