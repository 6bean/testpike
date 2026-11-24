<!-- tp-sort-exempt: roles are the predefined set plus a few customs, a full resident list. -->
<template>
  <div class="page-root" data-test="role-page">
    <div class="page-card">
      <div class="toolbar">
        <div class="toolbar-right">
          <el-button type="primary" data-test="role-create" @click="openCreate">
            <el-icon><Plus /></el-icon>{{ t('rbac.createRole') }}
          </el-button>
          <!-- R25 mode declaration (scope-aware single control, TB group): this page carries no
               tabs and exactly one table, so the page-level column-settings popover equals the
               table scope; show/hide, order, pinning and density persist per user via the piece. -->
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

      <TableSkeleton v-if="loading" :cols="colCfg.visibleColumns.value.length + 1" data-test="rolelistview-skeleton" />
      <el-table border :data="list" data-test="role-table" row-key="id" :size="colCfg.state.density" v-else>
        <el-table-column
          v-for="col in colCfg.visibleColumns.value"
          :key="col.key"
          :prop="col.key"
          :fixed="col.fixed || false"
          :label="t(col.labelKey)"
          :width="col.width ?? undefined"
          :min-width="col.width ? undefined : ROLE_FLEX_MIN[col.key]"
          :show-overflow-tooltip="col.key === 'description'"
          :data-test="`role-col-${col.key}`"
        >
          <template #default="{ row }">
            <template v-if="col.key === 'name'">
              <span>{{ row.name }}</span>
              <el-tag v-if="row.builtin === 1" size="small" type="info" style="margin-left: 6px">{{ t('rbac.builtin') }}</el-tag>
            </template>
            <code v-else-if="col.key === 'code'" class="role-code">{{ row.code }}</code>
            <span v-else-if="col.key === 'dataScope'">{{ dataScopeLabel(row.dataScope) }}</span>
            <el-tag v-else-if="col.key === 'status'" :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? t('common.enabled') : t('common.disabled') }}
            </el-tag>
            <TimeCell v-else-if="col.key === 'createdAt'" :iso="row.createdAt" />
            <span v-else>{{ row.description }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.action')" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" data-test="role-perms" @click="openPerms(row)">{{ t('rbac.permConfig') }}</el-button>
            <el-button link type="primary" data-test="role-edit" :disabled="row.builtin === 1" @click="openEdit(row)">{{ t('common.edit') }}</el-button>
            <el-button link type="danger" data-test="role-delete" :disabled="row.builtin === 1" @click="onDelete(row)">{{ t('common.delete') }}</el-button>
          </template>
        </el-table-column>
      
      <template #empty>
        <el-empty :description="t('common.emptyNoData')">
          <el-button type="primary" plain data-test="rbac-empty-create" @click="openCreate">{{ t('rbac.createRole') }}</el-button>
        </el-empty>
      </template>
    </el-table>
    </div>

    
    <el-dialog
      v-model="dialogVisible"
      :title="editing ? t('rbac.editRole') : t('rbac.createRole')"
      width="480px"
      destroy-on-close
      data-test="role-dialog"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px" scroll-to-error>
        <el-form-item :label="t('rbac.roleName')" prop="name">
          <el-input v-model="form.name" :placeholder="t('rbac.roleNamePlaceholder')" data-test="role-name-input" />
        </el-form-item>
        <el-form-item :label="t('rbac.roleCode')" prop="code">
          <el-input
            v-model="form.code"
            :placeholder="t('rbac.roleCodePlaceholder')"
            :disabled="!!editing"
            data-test="role-code-input"
          />
        </el-form-item>
        <el-form-item :label="t('rbac.dataScope')" prop="dataScope">
          <el-select v-model="form.dataScope" style="width: 100%" data-test="role-datascope">
            <el-option v-for="(key, val) in DATA_SCOPE_KEYS" :key="val" :label="t(key)" :value="Number(val)" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('common.status')" prop="status">
          <el-switch
            v-model="form.status"
            :active-value="1"
            :inactive-value="0"
            :active-text="t('common.enabled')"
            :inactive-text="t('common.disabled')"
          />
        </el-form-item>
        <el-form-item :label="t('common.remark')" prop="description">
          <el-input v-model="form.description" type="textarea" :rows="3" :placeholder="t('rbac.roleDescPlaceholder')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" data-test="role-submit" @click="onSave">{{ t('common.confirm') }}</el-button>
      </template>
    </el-dialog>

    
    <el-dialog
      v-model="permsVisible"
      :title="`${t('rbac.permConfig')} · ${permsTarget?.name ?? ''}`"
      width="760px"
      destroy-on-close
      data-test="role-perms-dialog"
    >
      <div class="perm-tip">{{ t('rbac.permTip') }}</div>
      <TableSkeleton v-if="loadingPerms" :cols="3" />
      <PermOverrideTree v-else v-model="permEffects" :groups="permGroups" />
      <template #footer>
        <el-button
          v-if="overrideCount > 0"
          data-test="role-perms-clear"
          :disabled="savingPerms"
          @click="onClearOverrides"
        >{{ t('rbac.permClearOverrides') }}</el-button>
        <el-button data-test="role-perms-close" @click="permsVisible = false">{{ t('common.close') }}</el-button>
        <el-button type="primary" :loading="savingPerms" data-test="role-perms-submit" @click="onSavePerms">{{ t('common.save') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import * as rbacApi from '@/api/rbac'
import { checkRoleDelete } from '@/api/deletecheck'
import { guardedDelete } from '@/utils/deleteGuard'
import PermOverrideTree from './PermOverrideTree.vue'
import ColumnSettings from '@/components/ColumnSettings.vue'
import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'
import type { PermGroupView, RoleView } from '@/api/rbac'
import { PERM_EFFECT } from '@/api/rbac'

const { t } = useI18n()

const DATA_SCOPE_KEYS: Record<number, string> = { 1: 'rbac.scopeAll', 2: 'rbac.scopeProject', 3: 'rbac.scopeSelf' }

/**
 * Role table column definitions for the shared column-config piece (N-3 repayment).
 * Widths mirror the previous hard-coded el-table-column attributes so the default
 * layout is unchanged; hide/reorder/pin/density then persist per user via the piece.
 * The trailing action column stays pinned outside the loop (locked column, not configurable).
 */
const ROLE_COLUMNS: ColumnDef[] = [
  { key: 'name', labelKey: 'common.name', width: null },
  { key: 'code', labelKey: 'rbac.roleCode', width: 150 },
  { key: 'dataScope', labelKey: 'rbac.dataScope', width: 110 },
  { key: 'description', labelKey: 'common.remark', width: null },
  { key: 'status', labelKey: 'common.status', width: 90 },
  { key: 'createdAt', labelKey: 'common.createdAt', width: 150 },
]
/** Flexible columns fall back to min-width when no explicit width is set. */
const ROLE_FLEX_MIN: Record<string, number> = { name: 140, description: 180 }
const colCfg = useColumnConfig('role', ROLE_COLUMNS)
/** Popover rows (column state merged with labelKey for rendering). */
const settingsRows = computed(() =>
  colCfg.state.columns.map((c) => ({ ...c, labelKey: ROLE_COLUMNS.find((d) => d.key === c.key)!.labelKey })),
)

function dataScopeLabel(scope: number): string {
  const key = DATA_SCOPE_KEYS[scope]
  return key ? t(key) : t('rbac.unknownScope', { scope })
}

const loading = ref(false)
const saving = ref(false)
const savingPerms = ref(false)
const loadingPerms = ref(false)
const list = ref<RoleView[]>([])

const dialogVisible = ref(false)
const editing = ref<RoleView | null>(null)
const formRef = ref<FormInstance>()
const form = reactive({ name: '', code: '', description: '', dataScope: 2, status: 1 })

const rules: FormRules = {
  name: [{ required: true, message: t('rbac.roleNameRequired'), trigger: 'blur' }],
  code: [{ required: true, message: t('rbac.roleCodeRequired'), trigger: 'blur' }],
}

const permsVisible = ref(false)
const permsTarget = ref<RoleView | null>(null)
const permGroups = ref<PermGroupView[]>([])

const permEffects = ref<Record<string, number>>({})
const overrideCount = computed(() => Object.keys(permEffects.value).length)

async function load(): Promise<void> {
  loading.value = true
  try {
    list.value = await rbacApi.listRoles()
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  editing.value = null
  form.name = ''
  form.code = ''
  form.description = ''
  form.dataScope = 2
  form.status = 1
  dialogVisible.value = true
}

function openEdit(row: RoleView): void {
  editing.value = row
  form.name = row.name
  form.code = row.code
  form.description = row.description || ''
  form.dataScope = row.dataScope
  form.status = row.status
  dialogVisible.value = true
}

async function onSave(): Promise<void> {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    const payload = {
      name: form.name,
      code: form.code,
      description: form.description || null,
      dataScope: form.dataScope,
      status: form.status,
    }
    if (editing.value) {
      await rbacApi.updateRole(editing.value.id, payload)
      ElMessage.success(t('rbac.updateRoleSuccess'))
    } else {
      await rbacApi.createRole(payload)
      ElMessage.success(t('rbac.createRoleSuccess'))
    }
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function onDelete(row: RoleView): Promise<void> {
  const ok = await guardedDelete({
    check: () => checkRoleDelete(row.id),
    doDelete: (force) => rbacApi.deleteRole(row.id),
    entityName: t('rbac.roleEntity', { name: row.name }),
    blocked: true,
  })
  if (ok) {
    ElMessage.success(t('rbac.deleteRoleSuccess'))
    await load()
  }
}

async function openPerms(row: RoleView): Promise<void> {
  permsTarget.value = row
  permEffects.value = {}
  loadingPerms.value = true
  try {
    if (permGroups.value.length === 0) {
      permGroups.value = await rbacApi.listPermissionCatalog()
    }
    const overrides = await rbacApi.getRolePermissions(row.id)
    const map: Record<string, number> = {}
    for (const o of overrides) {
      map[o.permKey] = o.effect
    }
    permEffects.value = map
    permsVisible.value = true
  } finally {
    loadingPerms.value = false
  }
}

async function onSavePerms(): Promise<void> {
  if (!permsTarget.value) return
  savingPerms.value = true
  try {
    const items = Object.entries(permEffects.value)
      .filter(([, eff]) => eff !== PERM_EFFECT.INHERIT)
      .map(([permKey, effect]) => ({ permKey, effect }))
    const hasOverrides = items.length > 0
    await rbacApi.saveRolePermissions(permsTarget.value.id, items, !hasOverrides)
    ElMessage.success(t('rbac.permSaveSuccess'))
    permsVisible.value = false
  } finally {
    savingPerms.value = false
  }
}

async function onClearOverrides(): Promise<void> {
  if (!permsTarget.value) return
  try {
    await ElMessageBox.confirm(
      t('rbac.permClearOverridesConfirm'),
      t('common.warning'),
      { type: 'warning', confirmButtonText: t('common.confirm'), cancelButtonText: t('common.cancel') },
    )
  } catch { return }
  savingPerms.value = true
  try {
    await rbacApi.saveRolePermissions(permsTarget.value.id, [], true)
    ElMessage.success(t('rbac.permSaveSuccess'))
    permsVisible.value = false
    permEffects.value = {}
  } finally {
    savingPerms.value = false
  }
}

onMounted(() => {
  load()
})
</script>

<style scoped lang="scss">
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
  flex-wrap: wrap;
  gap: 12px;
}

.page-subtitle {
  margin-left: 12px;
  font-size: 12px;
  color: var(--tp-text-secondary, #8b9bb4);
}

.role-code {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
  color: var(--tp-primary, #38bdf8);
}

.perm-tip {
  margin-bottom: 12px;
  font-size: 12px;
  color: var(--tp-text-secondary, #8b9bb4);
}
</style>
