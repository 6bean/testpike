<template>
  <div class="page-root" data-test="mcp-page">
 
    <el-tabs v-model="activeTab" data-test="mcp-tabs">
      <el-tab-pane :label="t('mcp.tabServers')" name="servers" data-test="mcp-tab-servers">
        <div :class="embedded ? 'mcp-embedded' : 'page-card'">
      <div class="toolbar">
        <div class="toolbar-right">
          <el-input
            v-model="query.keyword"
            :placeholder="t('mcp.searchPh')"
            clearable
            style="width: 200px"
            data-test="mcp-keyword"
            @keyup.enter="reload"
            @clear="reload"
          />
          <el-button data-test="mcp-search" @click="reload">
            <el-icon><Search /></el-icon>{{ t('mcp.query') }}
          </el-button>
          <el-button type="primary" data-test="mcp-create-open" @click="openCreate">
            <el-icon><Plus /></el-icon>{{ t('mcp.createBtn') }}
          </el-button>
        </div>
      </div>

      <TableSkeleton v-if="loading" :cols="7" data-test="mcpserverview-skeleton" />
      <el-table border v-else :data="rows" row-key="id"  data-test="mcp-table" @sort-change="onSortChange">
        <el-table-column prop="name" :label="t('mcp.colName')" min-width="150" sortable="custom" />
        <el-table-column prop="endpoint" :label="t('mcp.colEndpoint')" min-width="220" sortable="custom">
          <template #default="{ row }">
            <code class="mcp-endpoint">{{ row.endpoint }}</code>
          </template>
        </el-table-column>
        <el-table-column prop="transportLabel" :label="t('mcp.colTransport')" width="150" sortable="custom" />
        <el-table-column :label="t('mcp.colAuth')" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.authMasked" size="small">{{ t('mcp.authConfigured') }}</el-tag>
            <span v-else class="mcp-muted">{{ t('mcp.authNone') }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('mcp.colHealth')" min-width="170">
          <template #default="{ row }">
            <el-tag :type="healthTag(row.healthStatus)" size="small" data-test="mcp-health-tag">
              {{ row.healthLabel }}
            </el-tag>
            <div v-if="row.healthMessage" class="mcp-health-msg" data-test="mcp-health-message">
              {{ row.healthMessage }}
            </div>
            <div v-if="row.lastProbeAt" class="mcp-probe-at">{{ t('mcp.lastProbeAt', { at: row.lastProbeAt }) }}</div>
          </template>
        </el-table-column>
        <el-table-column :label="t('mcp.colEnabled')" width="80" align="center">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabled === 1"
              :loading="togglingId === row.id"
              data-test="mcp-enabled-switch"
              @change="(v: boolean) => onToggle(row, v)"
            />
          </template>
        </el-table-column>
        <el-table-column :label="t('common.action')" width="140" fixed="right">
          <template #default="{ row }">
            <RowActions :actions="rowActions()" :max="2" @command="(k: string) => onRowCmd(k, row)" />
          </template>
        </el-table-column>
      </el-table>
      <EmptyState v-if="!loading && rows.length === 0" :title="t('mcp.emptyTitle')" />

      <div class="pagination-bar" v-if="total > query.size">
        <el-pagination
          v-model:current-page="query.page"
          :page-size="query.size"
          :total="total"
          layout="total, prev, pager, next"
          data-test="mcp-pagination"
          @current-change="load"
        />
      </div>
        </div>
      </el-tab-pane>
      <el-tab-pane :label="t('mcp.tabTemplates')" name="templates" lazy data-test="mcp-tab-templates">
        <div :class="embedded ? 'mcp-embedded' : 'page-card'">
          <McpTemplatesPanel />
        </div>
      </el-tab-pane>
    </el-tabs>

    
    <el-dialog
      draggable
      v-model="formVisible"
      :title="editingId ? t('mcp.editTitle') : t('mcp.createBtn')"
      width="640px"
      destroy-on-close
      data-test="mcp-form-dialog"
    >
      <el-form :model="form" label-width="100px">
        <el-form-item :label="t('mcp.colName')" required>
          <el-input
            v-model="form.name"
            maxlength="64"
            show-word-limit
            :placeholder="t('mcp.formNamePh')"
            data-test="mcp-form-name"
          />
        </el-form-item>
        <el-form-item :label="t('mcp.colEndpoint')" required>
          <el-input
            v-model="form.endpoint"
            maxlength="500"
            :placeholder="t('mcp.formEndpointPh')"
            data-test="mcp-form-endpoint"
          />
        </el-form-item>
        <el-form-item :label="t('mcp.formTransportLabel')" required>
          <el-select v-model="form.transport" style="width: 100%" popper-class="tp-mcp-form-transport-popper" data-test="mcp-form-transport">
            <el-option v-for="opt in MCP_TRANSPORTS" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('mcp.formAuthLabel')">
          <el-input
            v-model="form.auth"
            type="password"
            show-password
            :placeholder="editingId ? t('mcp.formAuthPhEdit') : t('mcp.formAuthPhCreate')"
            data-test="mcp-form-auth"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" data-test="mcp-form-submit" @click="onSubmit">
          {{ editingId ? t('common.save') : t('mcp.submitRegister') }}
        </el-button>
      </template>
    </el-dialog>

    
    <el-dialog
      draggable
      v-model="toolsDialogVisible"
      :title="probeMode ? t('mcp.probeTitle') : t('mcp.toolSelectTitle')"
      width="640px"
      data-test="mcp-probe-dialog"
    >
      <el-alert
        v-if="probeMode && probe"
        :type="probe.success ? 'success' : 'error'"
        :closable="false"
        show-icon
        :title="probe.success ? t('mcp.probeOk') : t('mcp.probeFail')"
        :description="probe.message"
        data-test="mcp-probe-message"
      />
      <template v-if="dialogTools.length > 0">
        <div class="probe-tools-title">{{ t('mcp.snapshotToolsTitle') }}</div>
        <el-checkbox-group v-model="selectedToolNames" data-test="mcp-tool-select">
          <el-checkbox
            v-for="t in dialogTools"
            :key="t.name"
            :value="t.name"
            class="probe-tool-chip"
            data-test="mcp-tool-check"
          >
            {{ t.name }}
          </el-checkbox>
        </el-checkbox-group>
      </template>
      <template #footer>
        <el-button
          v-if="dialogTools.length > 0"
          type="primary"
          :loading="toolsSaving"
          data-test="mcp-tools-save"
          @click="onSaveTools"
        >
          {{ t('mcp.saveSelection') }}
        </el-button>
        <el-button data-test="mcp-probe-close" @click="toolsDialogVisible = false">{{ t('common.close') }}</el-button>
      </template>
    </el-dialog>

    
    <KnowledgeSyncDrawer v-model="syncVisible" :server="syncServer" />
  </div>
</template>

<script setup lang="ts">

import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Search } from '@element-plus/icons-vue'
import RowActions, { type RowAction } from '@/components/RowActions.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import McpTemplatesPanel from './McpTemplatesPanel.vue'
import KnowledgeSyncDrawer from './KnowledgeSyncDrawer.vue'
import {
  MCP_TRANSPORTS,
  createMcpServer,
  deleteMcpServer,
  listMcpServers,
  parseSnapshotTools,
  testMcpServer,
  toggleMcpServer,
  updateMcpServer,
  updateMcpTools,
  type McpProbeResult,
  type McpServerItem,
} from '@/api/mcpServer'

/** AN-5 embedded flag: renders inside the /extensions tab host when
 * true (both panel cards drop to a bare block; the host frames the tab
 * content). Default false keeps standalone semantics. */
const props = withDefaults(defineProps<{ embedded?: boolean }>(), { embedded: false })
const embedded = computed(() => props.embedded)

const { t } = useI18n()


const activeTab = ref<'servers' | 'templates'>('servers')

const loading = ref(false)
const rows = ref<McpServerItem[]>([])
const total = ref(0)
const query = reactive<{ keyword: string; page: number; size: number; sortBy: string; sortDir: string }>({
  keyword: '',
  page: 1,
  size: 20,
  sortBy: '',
  sortDir: '',
})

/** Column prop -> core SORT_COLUMNS whitelist key (only mapped columns render sortable). */
const SORT_KEY_MAP: Record<string, string> = {
  name: 'name',
  endpoint: 'endpoint',
  transportLabel: 'transport',
}

const togglingId = ref<number | null>(null)

const formVisible = ref(false)
const saving = ref(false)
const editingId = ref<number | null>(null)
const form = reactive<{ name: string; endpoint: string; transport: number; auth: string }>({
  name: '',
  endpoint: '',
  transport: 2,
  auth: '',
})

const toolsDialogVisible = ref(false)

const probeMode = ref(false)
const probe = ref<McpProbeResult | null>(null)
const selectRowId = ref<number | null>(null)

const dialogTools = computed(() =>
  parseSnapshotTools(
    probeMode.value
      ? probe.value?.server?.toolsJson ?? null
      : rows.value.find((r) => r.id === selectRowId.value)?.toolsJson ?? null,
  ),
)

const selectedToolNames = ref<string[]>([])
const toolsSaving = ref(false)

/** Knowledge sync drawer (AO face): the row whose snapshot tools feed the
 *  picker; opened from the row action, zero new menu leaf. */
const syncVisible = ref(false)
const syncServer = ref<McpServerItem | null>(null)

function openKnowledgeSync(row: McpServerItem): void {
  syncServer.value = row
  syncVisible.value = true
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const pageData = await listMcpServers({
      keyword: query.keyword || undefined,
      sortBy: query.sortBy || undefined,
      sortDir: query.sortDir || undefined,
      page: query.page,
      size: query.size,
    })
    rows.value = pageData.list || []
    total.value = Number(pageData.total) || 0
  } catch (e) {
    ElMessage.error((e as Error).message || t('mcp.listLoadFailed'))
  } finally {
    loading.value = false
  }
}

function reload(): void {
  query.page = 1
  load()
}

/** Header sort: forward the whitelisted key to the server (ORDER BY in SQL); cancel = backend default order. */
function onSortChange({ prop, order }: { prop: string; order: string | null }): void {
  if (order === null) {
    query.sortBy = ''
    query.sortDir = ''
    query.page = 1
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
  query.page = 1
  void load()
}

function upsert(row: McpServerItem): void {
  const idx = rows.value.findIndex((r) => r.id === row.id)
  if (idx >= 0) rows.value.splice(idx, 1, row)
}

function openCreate(): void {
  editingId.value = null
  form.name = ''
  form.endpoint = ''
  form.transport = 2
  form.auth = ''
  formVisible.value = true
}

function openEdit(row: McpServerItem): void {
  editingId.value = row.id
  form.name = row.name
  form.endpoint = row.endpoint
  form.transport = row.transport
  form.auth = ''
  formVisible.value = true
}

async function onSubmit(): Promise<void> {
  if (!form.name.trim()) {
    ElMessage.warning(t('mcp.nameRequired'))
    return
  }
  if (!form.endpoint.trim()) {
    ElMessage.warning(t('mcp.endpointRequired'))
    return
  }
  saving.value = true
  try {
    if (editingId.value == null) {
      await createMcpServer({
        name: form.name.trim(),
        endpoint: form.endpoint.trim(),
        transport: form.transport,
        auth: form.auth.trim() || null,
      })
      ElMessage.success(t('mcp.registered'))
    } else {
      await updateMcpServer(editingId.value, {
        name: form.name.trim(),
        endpoint: form.endpoint.trim(),
        transport: form.transport,
        auth: form.auth.trim() || null,
      })
      ElMessage.success(t('mcp.saved'))
    }
    formVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message || t('mcp.saveFailed'))
  } finally {
    saving.value = false
  }
}

async function onToggle(row: McpServerItem, next: boolean): Promise<void> {
  togglingId.value = row.id
  try {
    const updated = await toggleMcpServer(row.id, next ? 1 : 0)
    upsert(updated)
    ElMessage.success(next ? t('mcp.enabledOk') : t('mcp.disabledOk'))
  } catch (e) {
    ElMessage.error((e as Error).message || t('mcp.toggleFailed'))
  } finally {
    togglingId.value = null
  }
}

async function onTest(row: McpServerItem): Promise<void> {
  try {
    probe.value = await testMcpServer(row.id)
    probeMode.value = true
    selectRowId.value = row.id
    selectedToolNames.value = dialogTools.value.filter((t) => t.enabled).map((t) => t.name)
    toolsDialogVisible.value = true
    upsert(probe.value.server)
  } catch (e) {
    ElMessage.error((e as Error).message || t('mcp.probeFailed'))
  }
}


function openToolSelect(row: McpServerItem): void {
  if (!row.toolsJson) {
    ElMessage.warning(t('mcp.noSnapshotYet'))
    return
  }
  probeMode.value = false
  selectRowId.value = row.id
  selectedToolNames.value = dialogTools.value.filter((t) => t.enabled).map((t) => t.name)
  toolsDialogVisible.value = true
}


async function onSaveTools(): Promise<void> {
  const serverId = selectRowId.value
  if (serverId == null) return
  toolsSaving.value = true
  try {
    const updated = await updateMcpTools(serverId, selectedToolNames.value)
    if (probeMode.value && probe.value) {
      probe.value = { ...probe.value, server: updated }
    }
    upsert(updated)
    ElMessage.success(t('mcp.selectionSaved'))
  } catch (e) {
    ElMessage.error((e as Error).message || t('mcp.selectionSaveFailed'))
  } finally {
    toolsSaving.value = false
  }
}

async function onDelete(row: McpServerItem): Promise<void> {
  try {
    await ElMessageBox.confirm(
      t('mcp.deleteConfirm', { name: row.name }),
      t('mcp.deleteTitle'),
      { type: 'warning', confirmButtonText: t('common.delete'), cancelButtonText: t('common.cancel') },
    )
  } catch {
    return
  }
  try {
    await deleteMcpServer(row.id)
    ElMessage.success(t('mcp.deleted'))
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message || t('mcp.deleteFailed'))
  }
}

function rowActions(): RowAction[] {
  return [
    { key: 'test', label: t('mcp.actProbe') },
    { key: 'sync', label: t('mcp.actKnowledgeSync') },
    { key: 'edit', label: t('common.edit') },
    { key: 'tools', label: t('mcp.actToolSelect') },
    { key: 'delete', label: t('common.delete'), type: 'danger' },
  ]
}

function onRowCmd(cmd: string, row: McpServerItem): void {
  if (cmd === 'test') onTest(row)
  else if (cmd === 'sync') openKnowledgeSync(row)
  else if (cmd === 'edit') openEdit(row)
  else if (cmd === 'tools') openToolSelect(row)
  else if (cmd === 'delete') onDelete(row)
}

function healthTag(status: number): 'success' | 'danger' | 'info' {
  if (status === 1) return 'success'
  if (status === 2) return 'danger'
  return 'info'
}

onMounted(load)
</script>

<style scoped>
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 12px;
  gap: 12px;
  flex-wrap: wrap;
}
.toolbar-left {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.toolbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
}
.mcp-endpoint {
  font-family: var(--el-font-family-mono, monospace);
  font-size: 12px;
  word-break: break-all;
}
.mcp-muted {
  color: var(--el-text-color-secondary);
}
.mcp-health-msg {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 2px;
}
.mcp-probe-at {
  font-size: 11px;
  color: var(--el-text-color-placeholder);
  margin-top: 2px;
}
.probe-tools-title {
  margin: 16px 0 8px;
  font-weight: 600;
}
.probe-tool-chip {
  margin: 0 6px 6px 0;
}
</style>
