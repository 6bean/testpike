<template>
  <div class="page-root" data-test="user-page">
    <div class="page-card">
      <div class="toolbar">
        <div class="toolbar-right">
          <el-input
            v-model="keyword"
            :placeholder="t('user.keywordPlaceholder')"
            clearable
            style="width: 240px"
            :prefix-icon="Search"
            data-test="user-search"
            @keyup.enter="load(1)"
            @clear="load(1)"
          />
          <el-select v-model="statusFilter" clearable :placeholder="t('common.status')" style="width: 130px" data-test="user-status-filter" @change="load(1)">
            <el-option :label="t('common.enabled')" :value="1" />
            <el-option :label="t('common.disabled')" :value="0" />
          </el-select>
          <ColumnSettings
            :columns="settingsRows"
            :density="colCfg.state.density"
            @toggle="colCfg.toggle"
            @move="colCfg.move"
            @density="colCfg.setDensity"
            @reset="colCfg.reset"
            @pin="colCfg.toggleFixed"
          />
          <el-button type="primary" plain data-test="user-import-btn" @click="importVisible = true">
            <el-icon style="vertical-align: -2px; margin-right: 4px"><Upload /></el-icon>{{ t('user.import') }}
          </el-button>
          <el-button type="primary" data-test="user-create-btn" @click="openCreate">
            <el-icon style="vertical-align: -2px; margin-right: 4px"><Plus /></el-icon>{{ t('user.create') }}
          </el-button>
        </div>
      </div>

      <TableSkeleton v-if="loading" :cols="8" data-test="userlistview-skeleton" />
      <DataTablePage
        :data="list"
        data-test="user-table"
        row-key="id"
        :size="colCfg.state.density"
        :column-config="colCfg"
        @sort-change="onSortChange"
        v-else
      >
        <template #empty>
          <EmptyState
            :filtered="hasActiveFilter"
            :title="hasActiveFilter ? t('user.emptyFiltered') : t('user.emptyTitle')"
            :hint="hasActiveFilter ? t('user.emptyHint') : t('user.emptyInviteHint')"
            :action-text="t('user.inviteAction')"
            @action="openCreate"
            @clear-filter="clearUserSearch"
          />
        </template>
        <!-- configurable data columns via the shared useColumnConfig piece -->
        <el-table-column
          v-for="col in colCfg.visibleColumns.value"
          :key="col.key"
          :prop="col.key === 'lockStatus' ? undefined : col.key"
          :fixed="col.fixed || false"
          :label="t(col.labelKey)"
          :width="col.width ?? undefined"
          :min-width="col.width ? undefined : USER_FLEX_MIN[col.key]"
          :sortable="isColSortable(col.key) ? 'custom' : false"
          :show-overflow-tooltip="col.key === 'email'"
          :data-test="`user-col-${col.key}`"
        >
          <template #default="{ row }">
            <span v-if="col.key === 'role'">{{ roleLabel(row.role) }}</span>
            <el-tag v-else-if="col.key === 'status'" :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? t('common.enabled') : t('common.disabled') }}
            </el-tag>
            <template v-else-if="col.key === 'lockStatus'">
              <el-tag v-if="isLocked(row)" type="danger" size="small" data-test="user-locked-tag">
                {{ t('user.locked') }}
              </el-tag>
              <span v-else class="lock-free">—</span>
            </template>
            <TimeCell v-else-if="col.key === 'createdAt'" :iso="row.createdAt" />
            <span v-else>{{ row[col.key] }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.action')" width="230" fixed="right">
          <template #default="{ row }">
            <RowActions :actions="userRowActions(row)" :max="3" @command="(k: string) => onUserRowCmd(k, row)" />
          </template>
        </el-table-column>
      </DataTablePage>
      <div v-if="total > 0" class="pagination-bar">
        <el-pagination v-if="total > 0"
          v-model:current-page="page"
          :page-size="size"
          :total="total"
          layout="total, prev, pager, next"
          data-test="user-pagination"
          @current-change="load()"
          />
      </div>
    </div>

    
    <el-dialog
      v-model="assignVisible"
      :title="`${t('user.assignRoles')} · ${assignTarget?.displayName ?? assignTarget?.username ?? ''}`"
      width="480px"
      destroy-on-close
      data-test="user-roles-dialog"
    >
      <div class="assign-tip">{{ t('user.assignTip') }}</div>
      <el-checkbox-group v-model="assignedRoleIds" class="assign-roles-group">
        <el-checkbox v-for="r in roleOptions" :key="r.id" :value="r.id" :label="`${r.name}（${r.code}）`" />
      </el-checkbox-group>
      <template #footer>
        <el-button @click="assignVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" data-test="user-roles-submit" @click="onSaveAssign">{{ t('common.confirm') }}</el-button>
      </template>
    </el-dialog>

    
    <el-dialog
      v-model="editVisible"
      :title="editingId ? t('user.edit') : t('user.create')"
      width="640px"
      destroy-on-close
      data-test="user-edit-dialog"
    >
      <el-form :model="editForm" label-width="90px" @submit.prevent>
        <el-form-item :label="t('user.username')" required>
          <el-input v-model="editForm.username" :disabled="!!editingId" data-test="user-edit-username" :placeholder="t('user.usernamePlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('user.displayName')">
          <el-input v-model="editForm.displayName" data-test="user-edit-displayname" :placeholder="t('user.displayNamePlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('user.email')">
          <el-input v-model="editForm.email" data-test="user-edit-email" placeholder="name@example.com" />
        </el-form-item>
        <el-form-item :label="t('user.phone')">
          <el-input v-model="editForm.phone" data-test="user-edit-phone" placeholder="13800000000" />
        </el-form-item>
        <el-form-item :label="t('user.role')" required>
          <el-select v-model="editForm.role" style="width: 100%" data-test="user-edit-role">
            <el-option v-for="(key, val) in ROLE_LABEL_KEYS" :key="val" :label="t(key)" :value="Number(val)" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="!editingId" :label="t('user.initPassword')" required>
          <el-input v-model="editForm.password" type="password" show-password data-test="user-edit-password" :placeholder="t('user.passwordRule')" />
        </el-form-item>
        <el-form-item v-if="editingId" :label="t('common.status')">
          <el-switch v-model="editForm.status" :active-value="1" :inactive-value="0" data-test="user-edit-status"
            :active-text="t('common.enabled')" :inactive-text="t('common.disabled')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" data-test="user-edit-submit" @click="onSaveEdit">{{ t('common.confirm') }}</el-button>
      </template>
    </el-dialog>

    
    <el-dialog
      v-model="resetVisible"
      :title="`${t('user.resetPassword')} · ${resetTarget?.displayName ?? resetTarget?.username ?? ''}`"
      width="480px"
      destroy-on-close
      data-test="user-reset-dialog"
    >
      <div class="assign-tip">{{ t('user.resetTip') }}</div>
      <el-input v-model="resetPasswordValue" type="password" show-password data-test="user-reset-input" :placeholder="t('user.passwordRule')" />
      <template #footer>
        <el-button @click="resetVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" data-test="user-reset-submit" @click="onSaveReset">{{ t('common.confirm') }}</el-button>
      </template>
    </el-dialog>

    
    <el-dialog v-model="importVisible" :title="t('user.import')" width="640px" destroy-on-close data-test="user-import-dialog">
      <div class="import-tip">
        {{ t('user.importTip') }}
        <el-link type="primary" data-test="user-import-template" @click="downloadTemplate">{{ t('user.downloadTemplate') }}</el-link>
      </div>
      <el-upload
        drag
        :auto-upload="false"
        :limit="1"
        accept=".xlsx"
        data-test="user-import-upload"
        :on-change="onFileChange"
        :on-remove="() => (importFile = null)"
      >
        <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
        <div class="el-upload__text">{{ t('user.importDropHint') }}</div>
        <template #tip>
          <div class="el-upload__tip">{{ t('user.importFormatHint') }}</div>
        </template>
      </el-upload>
      <div v-if="importing" class="import-progress">
        <el-progress :percentage="importPercent" />
      </div>
      
      <div v-if="importResult" class="import-result" data-test="user-import-result">
        <div class="import-result-summary">
          <el-tag type="success" data-test="import-success-count">{{ t('user.importOkCount', { n: importResult.successCount }) }}</el-tag>
          <el-tag type="danger" data-test="import-fail-count">{{ t('user.importFailCount', { n: importResult.failCount }) }}</el-tag>
        </div>
        <DataTablePage v-if="importResult.failCount > 0" :data="failedItems" size="small" max-height="220" data-test="import-fail-table">
          <el-table-column prop="rowNumber" :label="t('user.importColRow')" width="90" />
          <el-table-column prop="reason" :label="t('user.importColReason')" show-overflow-tooltip />
        </DataTablePage>
        <div v-if="importResult.failCount > 0" class="import-retry-row">
          <el-button type="primary" size="small" :loading="importing" data-test="user-import-retry" @click="onRetryImport">
            {{ t('user.retryFailed') }}（{{ failedItems.length }}）
          </el-button>
          <span class="import-retry-tip">{{ t('user.retryTip') }}</span>
        </div>
      </div>
      <template #footer>
        <el-button @click="importVisible = false">{{ t('common.close') }}</el-button>
        <el-button type="primary" :loading="importing" data-test="user-import-submit" :disabled="!importFile" @click="onSubmitImport">
          {{ t('user.startImport') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref, computed, reactive } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Search, Upload, UploadFilled } from '@element-plus/icons-vue'
import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'
import ColumnSettings from '@/components/ColumnSettings.vue'
import * as userApi from '@/api/user'
import * as rbacApi from '@/api/rbac'
import type { RoleView } from '@/api/rbac'
import type { UserView } from '@/api/user'

const { t } = useI18n()

// ---------- column configuration (shared useColumnConfig piece) ----------
/** Configurable data columns; the fixed-right action column stays locked out. */
const USER_COLUMNS: ColumnDef[] = [
  { key: 'username', labelKey: 'user.username', width: 140 },
  { key: 'displayName', labelKey: 'user.displayName', width: null },
  { key: 'email', labelKey: 'user.email', width: null },
  { key: 'role', labelKey: 'user.role', width: 130 },
  { key: 'status', labelKey: 'common.status', width: 90 },
  { key: 'lockStatus', labelKey: 'user.lockStatus', width: 100 },
  { key: 'createdAt', labelKey: 'common.createdAt', width: 150 },
]
/** Flexible columns fall back to min-width when no explicit width is set. */
const USER_FLEX_MIN: Record<string, number> = { displayName: 120, email: 180 }
// Column prop -> core SORT_COLUMNS whitelist key.
// Single source for both the sortable-column decision and the request payload —
// columns outside the whitelist never emit sort params (no client-only fake sort).
// lockStatus is derived from locked_until (no physical column) and stays unsorted.
const SORT_KEY_MAP: Record<string, string> = {
  username: 'username',
  displayName: 'display_name',
  email: 'email',
  role: 'role',
  status: 'status',
  createdAt: 'created_at',
}
/** Whether this column has a server-side sort key (shared decision source). */
function isColSortable(key: string): boolean {
  return key in SORT_KEY_MAP
}
const colCfg = useColumnConfig('users', USER_COLUMNS)
/** Popover rows (column state merged with labelKey for rendering). */
const settingsRows = computed(() =>
  colCfg.state.columns.map((c) => ({ ...c, labelKey: USER_COLUMNS.find((d) => d.key === c.key)!.labelKey })),
)

const ROLE_LABEL_KEYS: Record<number, string> = { 1: 'user.roleTester', 2: 'user.roleTestLead', 3: 'user.roleDev', 4: 'user.roleAdmin' }

function roleLabel(role: number): string {
  const key = ROLE_LABEL_KEYS[role]
  return key ? t(key) : t('user.unknownRole', { role })
}


function isLocked(row: UserView): boolean {
  if (!row.lockedUntil) return false
  return new Date(row.lockedUntil).getTime() > Date.now()
}

function userRowActions(row: UserView) {
  const acts: { key: string; label: string; type?: "primary" | "warning" | "danger" }[] = []
  if (isLocked(row)) acts.push({ key: "unlock", label: t("user.unlock"), type: "warning" })
  acts.push({ key: "roles", label: t("user.assignRoles") })
  acts.push({ key: "edit", label: t("common.edit") })
  acts.push({ key: "reset-pwd", label: t("user.resetPassword"), type: "warning" })
  acts.push({ key: "delete", label: t("common.delete"), type: "danger" })
  return acts
}
function onUserRowCmd(key: string, row: UserView): void {
  if (key === "unlock") void onUnlock(row)
  else if (key === "roles") void openAssignRoles(row)
  else if (key === "edit") openEdit(row)
  else if (key === "reset-pwd") openResetPassword(row)
  else if (key === "delete") void onDelete(row)
}

async function onUnlock(row: UserView): Promise<void> {
  try {
    await ElMessageBox.confirm(t('user.unlockConfirm', { name: row.displayName || row.username }), t('common.warning'), {
      type: 'warning',
      confirmButtonText: t('common.confirm'),
      cancelButtonText: t('common.cancel'),
    })
  } catch {
    return
  }
  await userApi.unlockUser(row.id)
  ElMessage.success(t('user.unlockSuccess'))
  load()
}

const loading = ref(false)
const saving = ref(false)
const list = ref<UserView[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const statusFilter = ref<number | undefined>(undefined)
// Server-side sort state (same shape as the batch/plan/requirement pages).
const query = reactive<{ sortBy: string; sortDir: string }>({ sortBy: '', sortDir: '' })

async function load(p?: number): Promise<void> {
  if (p) page.value = p
  loading.value = true
  try {
    const result = await userApi.listUsers({
      keyword: keyword.value || undefined,
      status: statusFilter.value,
      page: page.value,
      size: size.value,
      sortBy: query.sortBy || undefined,
      sortDir: query.sortDir || undefined,
    })
    list.value = result.list || []
    total.value = result.total || 0
  } finally {
    loading.value = false
  }
}

/** Header sort: forward the whitelisted key to the server (ORDER BY happens in SQL);
 * clearing a sort falls back to the backend default order; page resets to 1. */
function onSortChange({ prop, order }: { prop: string; order: string | null }): void {
  if (order === null) {
    query.sortBy = ''
    query.sortDir = ''
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
  page.value = 1
  void load()
}

const assignVisible = ref(false)
const assignTarget = ref<UserView | null>(null)
const assignedRoleIds = ref<number[]>([])
const roleOptions = ref<RoleView[]>([])

async function openAssignRoles(row: UserView): Promise<void> {
  assignTarget.value = row
  assignedRoleIds.value = []
  if (roleOptions.value.length === 0) {
    roleOptions.value = await rbacApi.listRoles()
  }
  const roles = await rbacApi.listUserRoles(row.id)
  assignedRoleIds.value = roles.map((r) => r.id)
  assignVisible.value = true
}

async function onSaveAssign(): Promise<void> {
  if (!assignTarget.value) return
  saving.value = true
  try {
    await rbacApi.assignUserRoles(assignTarget.value.id, assignedRoleIds.value)
    ElMessage.success(t('user.assignSuccess'))
    assignVisible.value = false
  } finally {
    saving.value = false
  }
}

const editVisible = ref(false)
const editingId = ref<number | null>(null)
const editForm = ref({
  username: '',
  displayName: '',
  email: '',
  phone: '',
  role: 1,
  password: '',
  status: 1,
})

function openCreate(): void {
  editingId.value = null
  editForm.value = { username: '', displayName: '', email: '', phone: '', role: 1, password: '', status: 1 }
  editVisible.value = true
}

function openEdit(row: UserView): void {
  editingId.value = row.id
  editForm.value = {
    username: row.username,
    displayName: row.displayName || '',
    email: row.email || '',
    phone: row.phone || '',
    role: row.role,
    password: '',
    status: row.status,
  }
  editVisible.value = true
}

async function onSaveEdit(): Promise<void> {
  if (!editForm.value.username.trim()) {
    ElMessage.warning(t('user.usernameRequired'))
    return
  }
  saving.value = true
  try {
    if (editingId.value) {
      await userApi.updateUser(editingId.value, {
        displayName: editForm.value.displayName || undefined,
        email: editForm.value.email || undefined,
        phone: editForm.value.phone || undefined,
        role: editForm.value.role,
        status: editForm.value.status,
      })
      ElMessage.success(t('user.updateSuccess'))
    } else {
      await userApi.createUser({
        username: editForm.value.username.trim(),
        password: editForm.value.password,
        displayName: editForm.value.displayName || undefined,
        email: editForm.value.email || undefined,
        phone: editForm.value.phone || undefined,
        role: editForm.value.role,
      })
      ElMessage.success(t('user.createSuccess'))
    }
    editVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

async function onDelete(row: UserView): Promise<void> {
  try {
    await ElMessageBox.confirm(t('user.deleteConfirm', { name: row.displayName || row.username }), t('common.warning'), {
      type: 'warning',
      confirmButtonText: t('common.confirm'),
      cancelButtonText: t('common.cancel'),
    })
  } catch {
    return
  }
  await userApi.deleteUser(row.id)
  ElMessage.success(t('user.deleteSuccess'))
  load()
}

const resetVisible = ref(false)
const resetTarget = ref<UserView | null>(null)
const resetPasswordValue = ref('')

function openResetPassword(row: UserView): void {
  resetTarget.value = row
  resetPasswordValue.value = ''
  resetVisible.value = true
}

async function onSaveReset(): Promise<void> {
  if (!resetTarget.value) return
  saving.value = true
  try {
    await userApi.resetPassword(resetTarget.value.id, resetPasswordValue.value)
    ElMessage.success(t('user.resetSuccess'))
    resetVisible.value = false
  } finally {
    saving.value = false
  }
}

const importVisible = ref(false)
const importFile = ref<File | null>(null)
const importing = ref(false)
const importPercent = ref(0)
const importResult = ref<userApi.ImportResult | null>(null)

const failedItems = computed(() => (importResult.value?.items ?? []).filter((i) => !i.success))

function onFileChange(file: { raw?: File }): void {
  importFile.value = file.raw ?? null
}

async function downloadTemplate(): Promise<void> {
  try {
    await userApi.downloadUserTemplate()
  } catch (e) {
    ElMessage.error((e as Error).message || t('user.downloadFail'))
  }
}

async function onSubmitImport(): Promise<void> {
  if (!importFile.value) return
  importing.value = true
  importPercent.value = 0
  importResult.value = null
  try {
    importResult.value = await userApi.importUsers(importFile.value, (p) => (importPercent.value = p))
    importPercent.value = 100
    if (importResult.value.failCount === 0) {
      ElMessage.success(t('user.importSuccess'))
      load()
    }
  } finally {
    importing.value = false
  }
}

async function onRetryImport(): Promise<void> {
  if (!importFile.value) return
  importing.value = true
  try {
    importResult.value = await userApi.retryFailedUsers(
      importFile.value,
      failedItems.value.map((i) => i.rowNumber),
    )
    if (importResult.value.failCount === 0) {
      ElMessage.success(t('user.retrySuccess'))
      load()
    }
  } finally {
    importing.value = false
  }
}

onMounted(() => {
  load()
})

const hasActiveFilter = computed(() => keyword.value.trim() !== '')
function clearUserSearch(): void { keyword.value = ''; load(1) }
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

.assign-tip {
  margin-bottom: 12px;
  font-size: 12px;
  color: var(--tp-text-secondary, #8b9bb4);
}

.assign-roles-group {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.import-tip {
  margin-bottom: 12px;
  font-size: 13px;
  line-height: 1.6;
}

.import-progress {
  margin-top: 12px;
}

.import-result {
  margin-top: 12px;
  border: 1px solid var(--tp-border, #2a3a55);
  border-radius: 6px;
  padding: 12px;
}

.import-result-summary {
  display: flex;
  gap: 10px;
  margin-bottom: 10px;
}

.import-retry-row {
  margin-top: 10px;
  display: flex;
  align-items: center;
  gap: 10px;
}

.import-retry-tip {
  font-size: 12px;
  color: var(--tp-text-secondary, #8b9bb4);
}
</style>
