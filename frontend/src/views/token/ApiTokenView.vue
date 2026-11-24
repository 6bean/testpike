<!-- tp-sort-exempt: API tokens are per-user issued keys (tens), served as a full resident set. -->
<template>
  <div class="page-root" data-test="api-token-page">
    <div class="page-card">
      <div class="toolbar">
        <div class="toolbar-right">
          <el-button type="primary" data-test="token-create-open" @click="openCreate">
            <el-icon><Plus /></el-icon>{{ t('apiToken.create') }}
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

      <TableSkeleton v-if="loading" :cols="colCfg.visibleColumns.value.length + 1" data-test="apitokenview-skeleton" />
      <el-table border v-else :data="list" row-key="id" :size="colCfg.state.density" data-test="token-table">
        <el-table-column
          v-for="col in colCfg.visibleColumns.value"
          :key="col.key"
          :prop="col.key"
          :fixed="col.fixed || false"
          :label="t(col.labelKey)"
          :width="col.width ?? undefined"
          :min-width="col.width ? undefined : TOKEN_FLEX_MIN[col.key]"
          :data-test="`token-col-${col.key}`"
        >
          <template #default="{ row }">
            <template v-if="col.key === 'fingerprint'">
              <code class="token-fp">{{ row.fingerprint }}</code>
            </template>
            <template v-else-if="col.key === 'scopes'">
              <el-tag v-for="s in splitScopes(row.scopes)" :key="s" size="small" class="scope-chip">{{ s }}</el-tag>
            </template>
            <template v-else-if="col.key === 'projectScope'">
              <el-tag v-if="scopeIdsOf(row.projectScope).length === 0" size="small" type="info" data-test="token-scope-unlimited">
                {{ t('apiToken.projectScopeUnlimited') }}
              </el-tag>
              <el-tag v-for="pid in scopeIdsOf(row.projectScope)" v-else :key="pid" size="small" class="scope-chip">
                {{ projectNameOf(pid) }}
              </el-tag>
            </template>
            <template v-else-if="col.key === 'status'">
              <el-tag v-if="row.revoked === 1" size="small" type="danger">{{ t('apiToken.statusRevoked') }}</el-tag>
              <el-tag v-else-if="isExpired(row)" size="small" type="warning">{{ t('apiToken.statusExpired') }}</el-tag>
              <el-tag v-else size="small" type="success">{{ t('apiToken.statusActive') }}</el-tag>
            </template>
            <template v-else-if="col.key === 'lastUsedAt'">{{ row.lastUsedAt || t('apiToken.neverUsed') }}</template>
            <template v-else-if="col.key === 'expiresAt'">{{ row.expiresAt || t('apiToken.permanent') }}</template>
            <template v-else>{{ row.name }}</template>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.action')" width="100" fixed="right">
          <template #default="{ row }">
            <RowActions :actions="rowActions(row)" :max="1" @command="(k: string) => onRowCmd(k, row)" />
          </template>
        </el-table-column>
      </el-table>
      <EmptyState v-if="!loading && list.length === 0" :title="t('apiToken.emptyTitle')" />
    </div>

    <!-- create dialog: name + scope multi (allow-create) + GA1 project pin +
         optional expiry; validation authority is core, the form only hints shape -->
    <el-dialog v-model="createVisible" :title="t('apiToken.createDialogTitle')" width="640px" destroy-on-close data-test="token-create-dialog">
      <el-form :model="form" label-width="100px">
        <el-form-item :label="t('apiToken.colName')" required>
          <el-input
            v-model="form.name"
            maxlength="64"
            show-word-limit
            :placeholder="t('apiToken.namePh')"
            data-test="token-create-name"
          />
        </el-form-item>
        <el-form-item :label="t('apiToken.colScopes')" required>
          <el-select
            v-model="form.scopes"
            ref="scopeSelectRef"
            multiple
            filterable
            allow-create
            default-first-option
            :placeholder="t('apiToken.scopesPh')"
            style="width: 100%"
            popper-class="tp-token-scope-popper"
            data-test="token-create-scopes"
            @change="collapseScopeDropdown"
          >
            <el-option v-for="s in API_TOKEN_SCOPE_PRESETS" :key="s" :label="s" :value="s" />
          </el-select>
          <!-- scope legend: what each scope grants + default-unchecked guidance -->
          <div class="scope-help" data-test="token-scope-help">
            <p class="scope-help__hint">{{ t('apiToken.scopeHint') }}</p>
            <dl class="scope-help__grid">
              <template v-for="s in API_TOKEN_SCOPE_PRESETS" :key="s">
                <dt class="scope-help__name">{{ s }}</dt>
                <dd class="scope-help__desc">{{ t(API_TOKEN_SCOPE_DESC_KEYS[s]) }}</dd>
              </template>
            </dl>
          </div>
        </el-form-item>
        <el-form-item :label="t('apiToken.colProjectScope')">
          <el-select
            v-model="form.projectIds"
            ref="projectSelectRef"
            multiple
            filterable
            :placeholder="t('apiToken.projectScopePh')"
            style="width: 100%"
            data-test="token-create-project-scope"
            popper-class="tp-token-project-scope-popper"
            @change="collapseProjectDropdown"
          >
            <el-option v-for="p in auth.projects" :key="p.projectId" :label="p.projectName" :value="p.projectId" />
          </el-select>
          <!-- GA1 unset guidance carries both R32 elements (menu path literal +
               parameter name); the set-state hint names the consequence instead. -->
          <div class="scope-help" data-test="token-project-scope-hint">
            <p class="scope-help__hint">
              {{ form.projectIds.length === 0 ? t('apiToken.projectScopeHintUnset') : t('apiToken.projectScopeHintSet', { n: form.projectIds.length }) }}
            </p>
          </div>
        </el-form-item>
        <el-form-item :label="t('apiToken.colExpiresAt')">
          <el-date-picker
            v-model="form.expiresAt"
            type="datetime"
            :placeholder="t('apiToken.expiresPh')"
            value-format="YYYY-MM-DDTHH:mm:ss"
            style="width: 100%"
            data-test="token-create-expires"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="creating" data-test="token-create-submit" @click="onCreate">{{ t('apiToken.createBtn') }}</el-button>
      </template>
    </el-dialog>

    
    <el-dialog
      v-model="plainVisible"
      :title="t('apiToken.plainTitle')"
      width="640px"
      :close-on-click-modal="false"
      :show-close="false"
      data-test="token-plaintext-dialog">
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        :title="t('apiToken.plainWarnTitle')"
        :description="t('apiToken.plainWarnDesc')"
      />
      <div class="plain-box">
        <code class="plain-text" data-test="token-plaintext">{{ plaintext }}</code>
        <el-button type="primary" plain data-test="token-copy" @click="copyPlain">
          <el-icon><DocumentCopy /></el-icon>{{ copied ? t('apiToken.copied') : t('apiToken.copy') }}
        </el-button>
      </div>
      <template #footer>
        <el-button type="primary" data-test="token-plain-done" @click="closePlain">{{ t('apiToken.plainDone') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
/**
 * <p>GA1 project pin: the create dialog can pin a token to a subset of the
 * owner's visible projects (option source = login snapshot auth.projects, zero
 * remote request, R30 shape); leaving it empty pins nothing (pre-GA1 behavior,
 * zero drift). Normalization (dedupe + ascending) and out-of-scope 403 all
 * live in core/gateway single source; the page only joins ids ascending before
 * submit and echoes project names in the list.
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { DocumentCopy, Plus } from '@element-plus/icons-vue'
import ColumnSettings from '@/components/ColumnSettings.vue'
import RowActions, { type RowAction } from '@/components/RowActions.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'
import { useAuthStore } from '@/stores/auth'
import {
  API_TOKEN_SCOPE_DESC_KEYS,
  API_TOKEN_SCOPE_PRESETS,
  createApiToken,
  listApiTokens,
  revokeApiToken,
  type ApiTokenItem,
} from '@/api/apiToken'

const { t } = useI18n()
// Visible-project snapshot from the login payload: the picker must issue no
// remote request (projectContextHeader contract family, R30 single switch).
const auth = useAuthStore()

/** Configurable data columns of the token table (the locked action column stays outside). */
const API_TOKEN_COLUMNS: ColumnDef[] = [
  { key: 'name', labelKey: 'apiToken.colName', width: null },
  { key: 'fingerprint', labelKey: 'apiToken.colFingerprint', width: 120 },
  { key: 'scopes', labelKey: 'apiToken.colScopes', width: null },
  { key: 'projectScope', labelKey: 'apiToken.colProjectScope', width: null },
  { key: 'status', labelKey: 'apiToken.colStatus', width: 110 },
  { key: 'lastUsedAt', labelKey: 'apiToken.colLastUsed', width: 160 },
  { key: 'expiresAt', labelKey: 'apiToken.colExpiresAt', width: 160 },
]
/** Flexible columns fall back to min-width when no explicit width is set. */
const TOKEN_FLEX_MIN: Record<string, number> = { name: 160, scopes: 180, projectScope: 160 }
const colCfg = useColumnConfig('api-token', API_TOKEN_COLUMNS)
/** Popover rows (column state merged with labelKey for rendering). */
const settingsRows = computed(() =>
  colCfg.state.columns.map((c) => ({ ...c, labelKey: API_TOKEN_COLUMNS.find((d) => d.key === c.key)!.labelKey })),
)

const loading = ref(false)
const list = ref<ApiTokenItem[]>([])

const createVisible = ref(false)
const creating = ref(false)
const form = reactive<{ name: string; scopes: string[]; projectIds: number[]; expiresAt: string | null }>({
  name: '',
  scopes: [],
  projectIds: [],
  expiresAt: null,
})

const plainVisible = ref(false)
const plaintext = ref('')
const copied = ref(false)

async function load(): Promise<void> {
  loading.value = true
  try {
    list.value = (await listApiTokens()) || []
  } catch (e) {
    ElMessage.error((e as Error).message || t('apiToken.loadFailed'))
  } finally {
    loading.value = false
  }
}

function splitScopes(scopes: string): string[] {
  return (scopes || '').split(',').map((s) => s.trim()).filter(Boolean)
}

/** GA1 pin cell: parse the stored "3,12" form; null/blank = unrestricted. */
function scopeIdsOf(projectScope: string | null): number[] {
  return splitScopes(projectScope ?? '')
    .map((s) => Number(s))
    .filter((n) => Number.isFinite(n) && n > 0)
}

/** Resolve a pinned id to its display name; ids outside the current visible
 *  snapshot render as #id honestly instead of being dropped or guessed. */
function projectNameOf(pid: number): string {
  return auth.projects.find((p) => p.projectId === pid)?.projectName ?? `#${pid}`
}

function isExpired(row: ApiTokenItem): boolean {
  if (!row.expiresAt) return false
  const d = new Date(row.expiresAt)
  return !Number.isNaN(d.getTime()) && d.getTime() < Date.now()
}

function openCreate(): void {
  form.name = ''
  form.scopes = []
  form.projectIds = []
  form.expiresAt = null
  createVisible.value = true
}

// Popper-avoidance (E2E t169: after picking the second scope the dropdown
// stays open and intercepts pointer events on the footer submit button —
// the popper overlays the dialog footer). Element Plus keeps a multiple+
// filterable select expanded after choosing an option so users can chain
// picks; here the option list is a 3-item preset set, so collapsing on
// change is both safe and the expected UX. blur() is the component's own
// collapse-then-unfocus entry (single source, no manual popper state).
const scopeSelectRef = ref<{ blur: () => void } | null>(null)
function collapseScopeDropdown(): void {
  scopeSelectRef.value?.blur()
}

// Same popper-avoidance entry for the GA1 project picker (multiple +
// filterable, footer-intercepting popper risk is identical).
const projectSelectRef = ref<{ blur: () => void } | null>(null)
function collapseProjectDropdown(): void {
  projectSelectRef.value?.blur()
}

async function onCreate(): Promise<void> {
  if (!form.name.trim()) {
    ElMessage.warning(t('apiToken.needName'))
    return
  }
  if (form.scopes.length === 0) {
    ElMessage.warning(t('apiToken.needScopes'))
    return
  }
  creating.value = true
  try {
    // Ascending de-duplicated join mirrors the stored normalization so the
    // list echo reads identically; core still owns validation authority.
    const ids = Array.from(new Set(form.projectIds)).sort((a, b) => a - b)
    const created = await createApiToken({
      name: form.name.trim(),
      scopes: form.scopes.join(','),
      expiresAt: form.expiresAt || null,
      projectScope: ids.length > 0 ? ids.join(',') : null,
    })
    createVisible.value = false
    plaintext.value = created.plaintext
    copied.value = false
    plainVisible.value = true
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message || t('apiToken.createFailed'))
  } finally {
    creating.value = false
  }
}

async function copyPlain(): Promise<void> {
  try {
    await navigator.clipboard.writeText(plaintext.value)
    copied.value = true
  } catch {
    ElMessage.warning(t('apiToken.clipboardDenied'))
  }
}


function closePlain(): void {
  plainVisible.value = false
  plaintext.value = ''
}

function rowActions(row: ApiTokenItem): RowAction[] {
  if (row.revoked === 1) return [{ key: 'revoked', label: t('apiToken.statusRevoked'), disabled: true }]
  return [{ key: 'revoke', label: t('apiToken.revoke'), type: 'danger' }]
}

async function onRowCmd(cmd: string, row: ApiTokenItem): Promise<void> {
  if (cmd !== 'revoke') return
  try {
    await ElMessageBox.confirm(
      t('apiToken.revokeConfirm', { name: row.name, fp: row.fingerprint }),
      t('apiToken.revokeTitle'),
      { type: 'warning', confirmButtonText: t('apiToken.revoke'), cancelButtonText: t('common.cancel') },
    )
  } catch {
    return
  }
  try {
    await revokeApiToken(row.id)
    ElMessage.success(t('apiToken.revokedDone'))
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message || t('apiToken.revokeFailed'))
  }
}

onMounted(load)
</script>

<style scoped>
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 12px;
}
.toolbar-left {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.token-fp {
  font-family: var(--el-font-family-mono, monospace);
  font-size: 12px;
}
.scope-chip {
  margin-right: 4px;
}
.plain-box {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 16px;
  padding: 10px 12px;
  border: 1px dashed var(--el-border-color);
  border-radius: 6px;
  background: var(--el-fill-color-lighter);
}
.plain-text {
  flex: 1;
  word-break: break-all;
  font-family: var(--el-font-family-mono, monospace);
  font-size: 13px;
  user-select: all;
}
.scope-help {
  margin-top: 8px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}
.scope-help__hint {
  margin: 0 0 6px;
}
.scope-help__grid {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 2px 10px;
  margin: 0;
}
.scope-help__name {
  font-family: var(--el-font-family-mono, monospace);
  color: var(--el-text-color-regular);
}
.scope-help__desc {
  margin: 0;
}
</style>
