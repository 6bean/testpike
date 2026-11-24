<template>
  <div class="mcp-auth-panel" data-test="mcp-auth-panel">
    
    <div class="panel-toolbar">
      <div class="toolbar-left">
        <span class="panel-subtitle">{{ t('mcp.authPanelSubtitle') }}</span>
      </div>
      <div class="toolbar-right">
        <el-button type="primary" data-test="mcp-tpl-create-open" @click="openCreate">
          <el-icon><Plus /></el-icon>{{ t('mcp.authCreateBtn') }}
        </el-button>
      </div>
    </div>

    <TableSkeleton v-if="loading" :cols="6" data-test="mcptemplatespanel-skeleton" />
    <el-table border v-else :data="templates" row-key="id" data-test="mcp-tpl-table">
      <el-table-column prop="name" :label="t('mcp.authColName')" min-width="140" />
      <el-table-column :label="t('mcp.authColScope')" width="120">
        <template #default="{ row }">
          <el-tag :type="row.scope === 2 ? 'warning' : 'info'" size="small">
            {{ row.scope === 2 ? t('mcp.authScopeProject') : t('mcp.authScopeSystem') }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('mcp.authColTools')" min-width="260">
        <template #default="{ row }">
          <el-tag
            v-for="name in row.tools"
            :key="name"
            size="small"
            class="tool-tag"
          >{{ name }}</el-tag>
          <span v-if="row.tools.length === 0" class="mcp-muted">{{ t('mcp.authToolsEmpty') }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="remark" :label="t('mcp.authColRemark')" min-width="140">
        <template #default="{ row }">
          <span :class="{ 'mcp-muted': !row.remark }">{{ row.remark || t('mcp.authNoneRemark') }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="updatedAt" :label="t('mcp.authColUpdatedAt')" width="170" />
      <el-table-column :label="t('common.action')" width="180" fixed="right">
        <template #default="{ row }">
          <RowActions :actions="rowActions()" :max="3" @command="(k: string) => onRowCmd(k, row)" />
        </template>
      </el-table-column>
    </el-table>
    <EmptyState v-if="!loading && templates.length === 0" :title="t('mcp.authEmptyTitle')" />

    
    <div class="inherit-card" data-test="mcp-inherit-card">
      <div class="inherit-title">{{ t('mcp.authInheritTitle') }}</div>
      <div class="inherit-desc">{{ t('mcp.authInheritDesc') }}</div>
      <div class="inherit-form">
        <el-select
          v-model="inheritProjectId"
          filterable
          :placeholder="t('mcp.authPickProject')"
          style="width: 220px"
          data-test="mcp-inherit-project"
          @change="loadInherit"
        >
          <el-option v-for="p in projects" :key="p.id" :label="p.name" :value="p.id" />
        </el-select>
        <span class="inherit-state" data-test="mcp-inherit-state">
            <template v-if="inherit === undefined">{{ t('mcp.authPickHint') }}</template>
            <template v-else-if="inherit === null">{{ t('mcp.authNotConfigured') }}</template>
            <template v-else>
              {{ t('mcp.authCurrentTemplate', { name: inherit.templateName }) }}
              ·
              <el-tag :type="inherit.enabled === 1 ? 'success' : 'danger'" size="small">
                {{ inherit.enabled === 1 ? t('mcp.authEnabledOn') : t('mcp.authEnabledOff') }}
              </el-tag>
            </template>
        </span>
        <template v-if="inherit !== undefined && inherit !== null">
          <el-switch
            :model-value="inherit.enabled === 1"
            :loading="inheritSaving"
            data-test="mcp-inherit-switch"
            @change="(v: boolean) => onToggleInherit(v)"
          />
          <el-button data-test="mcp-inherit-edit" @click="openInheritEdit">{{ t('mcp.authChangeTemplate') }}</el-button>
        </template>
        <el-button
          v-else-if="inherit === null"
          type="primary"
          data-test="mcp-inherit-setup"
          @click="openInheritEdit"
        >{{ t('mcp.authSetupInherit') }}</el-button>
      </div>
    </div>

    
    <el-drawer
      v-model="formVisible"
      :title="editingId ? t('mcp.authEditTitle') : t('mcp.authCreateBtn')"
      size="520px"
      destroy-on-close
      data-test="mcp-tpl-form-drawer"
    >
      <el-form :model="form" label-width="90px">
        <el-form-item :label="t('mcp.authColName')" required>
          <el-input v-model="form.name" maxlength="64" show-word-limit :placeholder="t('mcp.authNamePh')" data-test="mcp-tpl-form-name" />
        </el-form-item>
        <el-form-item :label="t('mcp.authColScope')">
          <!-- id="" prevents the form label for= from targeting the non-labelable radiogroup div -->
          <el-radio-group id="" v-model="form.scope" data-test="mcp-tpl-form-scope">
            <el-radio :value="1">{{ t('mcp.authScopeSystem') }}</el-radio>
            <el-radio :value="2">{{ t('mcp.authScopeProject') }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.scope === 2" :label="t('mcp.authColProject')" required>
          <el-select v-model="form.projectId" filterable :placeholder="t('mcp.authPickProject')" style="width: 100%" data-test="mcp-tpl-form-project">
            <el-option v-for="p in projects" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('mcp.authColTools')" required>
          <el-select
            v-model="form.tools"
            multiple
            filterable
            allow-create
            default-first-option
            :reserve-keyword="false"
            :placeholder="t('mcp.authToolsPh')"
            style="width: 100%"
            data-test="mcp-tpl-form-tools"
          >
            <el-option v-for="name in knownToolNames" :key="name" :label="name" :value="name" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('mcp.authColRemark')">
          <el-input v-model="form.remark" maxlength="200" :placeholder="t('mcp.authRemarkPh')" data-test="mcp-tpl-form-remark" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" data-test="mcp-tpl-form-submit" @click="onSubmit">{{ t('common.save') }}</el-button>
      </template>
    </el-drawer>

    
    <el-dialog
      v-model="applyVisible"
      :title="t('mcp.authApplyTitle', { name: applyTarget?.name || '' })"
      width="560px"
      destroy-on-close
      data-test="mcp-apply-dialog"
    >
      <div class="apply-desc">{{ t('mcp.authApplyDesc') }}</div>
      <el-checkbox-group v-if="applyResult === null" v-model="applyServerIds" data-test="mcp-apply-servers" class="apply-server-list">
        <el-checkbox v-for="s in servers" :key="s.id" :value="s.id" class="apply-server-item">
          {{ s.name }}<span class="mcp-muted">（{{ s.endpoint }}）</span>
        </el-checkbox>
      </el-checkbox-group>
      <template v-else>
        <el-alert
          :type="applyResult.updatedServers > 0 ? 'success' : 'info'"
          :closable="false"
          show-icon
          :title="t('mcp.authApplySummary', { count: applyResult.updatedServers })"
          data-test="mcp-apply-summary"
        />
        <ul class="apply-details" data-test="mcp-apply-details">
          <li v-for="(d, i) in applyResult.details" :key="i">{{ d }}</li>
        </ul>
      </template>
      <template #footer>
        <el-button data-test="mcp-apply-close" @click="applyVisible = false">{{ t('common.close') }}</el-button>
        <el-button
          v-if="applyResult === null"
          type="primary"
          :loading="applying"
          :disabled="applyServerIds.length === 0"
          data-test="mcp-apply-submit"
          @click="onApply"
        >{{ t('mcp.authApplyBtn') }}</el-button>
      </template>
    </el-dialog>

    
    <el-dialog
      v-model="inheritEditVisible"
      :title="t('mcp.authInheritEditTitle')"
      width="460px"
      destroy-on-close
      data-test="mcp-inherit-dialog"
    >
      <el-form label-width="90px">
        <el-form-item :label="t('mcp.authColTemplate')" required>
          <el-select v-model="inheritForm.templateId" style="width: 100%" popper-class="tp-mcp-inherit-template-popper" data-test="mcp-inherit-template"
            filterable>
            <el-option v-for="tp in templates" :key="tp.id" :label="tp.name" :value="tp.id" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('mcp.authEnabledLabel')">
          <el-switch v-model="inheritForm.enabled" data-test="mcp-inherit-enabled" />
          <span class="mcp-muted inherit-enabled-hint">{{ t('mcp.authEnabledHint') }}</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="inheritEditVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="inheritSaving" data-test="mcp-inherit-save" @click="onSaveInherit">{{ t('common.save') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">

import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import RowActions, { type RowAction } from '@/components/RowActions.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import {
  applyMcpAuthTemplate,
  createMcpAuthTemplate,
  deleteMcpAuthTemplate,
  getProjectMcpAuth,
  listMcpAuthTemplates,
  putProjectMcpAuth,
  updateMcpAuthTemplate,
  type McpAuthApplyResult,
  type McpAuthTemplate,
  type ProjectMcpAuth,
} from '@/api/mcpAuth'
import { listMcpServers, parseSnapshotTools, type McpServerItem } from '@/api/mcpServer'
import { getProjects, type Project } from '@/api/project'

const { t } = useI18n()

const loading = ref(false)
const templates = ref<McpAuthTemplate[]>([])
const servers = ref<McpServerItem[]>([])
const projects = ref<Project[]>([])


const knownToolNames = computed<string[]>(() => {
  const seen = new Set<string>()
  for (const s of servers.value) {
    for (const tool of parseSnapshotTools(s.toolsJson)) seen.add(tool.name)
  }
  return [...seen]
})

async function load(): Promise<void> {
  loading.value = true
  try {
    const [tpls, page, projPage] = await Promise.all([
      listMcpAuthTemplates(),
      listMcpServers({ page: 1, size: 100 }),
      getProjects({ page: 1, size: 100 }),
    ])
    templates.value = tpls || []
    servers.value = page.list || []
    projects.value = projPage.list || []
  } catch (e) {
    ElMessage.error((e as Error).message || t('mcp.authListLoadFailed'))
  } finally {
    loading.value = false
  }
}

const formVisible = ref(false)
const saving = ref(false)
const editingId = ref<number | null>(null)
const form = reactive<{ name: string; tools: string[]; remark: string; scope: number; projectId: number | null }>({
  name: '',
  tools: [],
  remark: '',
  scope: 1,
  projectId: null,
})

function openCreate(): void {
  editingId.value = null
  form.name = ''
  form.tools = []
  form.remark = ''
  form.scope = 1
  form.projectId = null
  formVisible.value = true
}

function openEdit(row: McpAuthTemplate): void {
  editingId.value = row.id
  form.name = row.name
  form.tools = [...row.tools]
  form.remark = row.remark || ''
  form.scope = row.scope
  form.projectId = row.projectId
  formVisible.value = true
}

async function onSubmit(): Promise<void> {
  if (!form.name.trim()) {
    ElMessage.warning(t('mcp.authNameRequired'))
    return
  }
  if (form.tools.length === 0) {
    ElMessage.warning(t('mcp.authToolsRequired'))
    return
  }
  if (form.scope === 2 && form.projectId == null) {
    ElMessage.warning(t('mcp.authProjectRequired'))
    return
  }
  saving.value = true
  try {
    const payload = {
      name: form.name.trim(),
      tools: form.tools,
      remark: form.remark.trim() || null,
      scope: form.scope,
      projectId: form.scope === 2 ? form.projectId : null,
    }
    if (editingId.value == null) {
      await createMcpAuthTemplate(payload)
      ElMessage.success(t('mcp.authCreated'))
    } else {
      await updateMcpAuthTemplate(editingId.value, payload)
      ElMessage.success(t('mcp.authSaved'))
    }
    formVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message || t('mcp.authSaveFailed'))
  } finally {
    saving.value = false
  }
}

async function onDelete(row: McpAuthTemplate): Promise<void> {
  try {
    await ElMessageBox.confirm(
      t('mcp.authDeleteConfirm', { name: row.name }),
      t('mcp.authDeleteTitle'),
      { type: 'warning', confirmButtonText: t('common.delete'), cancelButtonText: t('common.cancel') },
    )
  } catch {
    return
  }
  try {
    await deleteMcpAuthTemplate(row.id)
    ElMessage.success(t('mcp.authDeleted'))
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message || t('mcp.authDeleteFailed'))
  }
}

const applyVisible = ref(false)
const applying = ref(false)
const applyTarget = ref<McpAuthTemplate | null>(null)
const applyServerIds = ref<number[]>([])
const applyResult = ref<McpAuthApplyResult | null>(null)

function openApply(row: McpAuthTemplate): void {
  applyTarget.value = row
  applyServerIds.value = []
  applyResult.value = null
  applyVisible.value = true
}

async function onApply(): Promise<void> {
  const target = applyTarget.value
  if (target == null) return
  applying.value = true
  try {
    applyResult.value = await applyMcpAuthTemplate(target.id, applyServerIds.value)
    ElMessage.success(t('mcp.authApplied'))
  } catch (e) {
    ElMessage.error((e as Error).message || t('mcp.authApplyFailed'))
  } finally {
    applying.value = false
  }
}


const inherit = ref<ProjectMcpAuth | null | undefined>(undefined)
const inheritProjectId = ref<number | null>(null)
const inheritSaving = ref(false)
const inheritEditVisible = ref(false)
const inheritForm = reactive<{ templateId: number | null; enabled: boolean }>({
  templateId: null,
  enabled: true,
})

async function loadInherit(): Promise<void> {
  if (inheritProjectId.value == null) {
    inherit.value = undefined
    return
  }
  try {
    inherit.value = await getProjectMcpAuth(inheritProjectId.value)
  } catch (e) {
    inherit.value = undefined
    ElMessage.error((e as Error).message || t('mcp.authInheritLoadFailed'))
  }
}

function openInheritEdit(): void {
  inheritForm.templateId = inherit.value?.templateId ?? templates.value[0]?.id ?? null
  inheritForm.enabled = inherit.value ? inherit.value.enabled === 1 : true
  inheritEditVisible.value = true
}

async function onSaveInherit(): Promise<void> {
  if (inheritProjectId.value == null) return
  if (inheritForm.templateId == null) {
    ElMessage.warning(t('mcp.authTemplateRequired'))
    return
  }
  inheritSaving.value = true
  try {
    inherit.value = await putProjectMcpAuth(inheritProjectId.value, {
      templateId: inheritForm.templateId,
      enabled: inheritForm.enabled ? 1 : 0,
    })
    ElMessage.success(t('mcp.authInheritSaved'))
    inheritEditVisible.value = false
  } catch (e) {
    ElMessage.error((e as Error).message || t('mcp.authInheritSaveFailed'))
  } finally {
    inheritSaving.value = false
  }
}


async function onToggleInherit(next: boolean): Promise<void> {
  const current = inherit.value
  if (inheritProjectId.value == null || current == null) return
  inheritSaving.value = true
  try {
    inherit.value = await putProjectMcpAuth(inheritProjectId.value, {
      templateId: current.templateId,
      enabled: next ? 1 : 0,
    })
    ElMessage.success(next ? t('mcp.authInheritOn') : t('mcp.authInheritOff'))
  } catch (e) {
    ElMessage.error((e as Error).message || t('mcp.authInheritSaveFailed'))
  } finally {
    inheritSaving.value = false
  }
}

function rowActions(): RowAction[] {
  return [
    { key: 'apply', label: t('mcp.authActApply') },
    { key: 'edit', label: t('common.edit') },
    { key: 'delete', label: t('common.delete'), type: 'danger' },
  ]
}

function onRowCmd(cmd: string, row: McpAuthTemplate): void {
  if (cmd === 'apply') openApply(row)
  else if (cmd === 'edit') openEdit(row)
  else if (cmd === 'delete') onDelete(row)
}

onMounted(load)
</script>

<style scoped>
.panel-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  gap: 12px;
  flex-wrap: wrap;
}
.panel-subtitle {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.tool-tag {
  margin: 0 4px 4px 0;
}
.mcp-muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.inherit-card {
  margin-top: 20px;
  padding: 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}
.inherit-title {
  font-weight: 600;
  margin-bottom: 4px;
}
.inherit-desc {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin-bottom: 12px;
}
.inherit-form {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.inherit-state {
  font-size: 13px;
}
.apply-desc {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin-bottom: 8px;
}
.apply-server-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 260px;
  overflow-y: auto;
}
.apply-details {
  margin: 8px 0 0;
  padding-left: 18px;
  font-size: 13px;
}
.inherit-enabled-hint {
  margin-left: 8px;
}
</style>
