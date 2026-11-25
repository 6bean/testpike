<!-- tp-owner-echo-exempt: skills are global assets — the dialog-side
     ProjectSelect encodes the "optional owner" contract (projectId null =
     system-level skill shared across projects), not a second-channel project
     picker, so the ownership-echo iron rule (rules 1/2) does not apply here.
     Ruling = T396b package-1 inventory entries #13. -->
<template>
  <div class="page-root skill-list-page" data-test="skill-list-page">
    <div class="page-card">
      <!-- AN-5 the SKILL market leaf merged into this page as a
 tab (old /skill-market path redirects here keeping ?tab=market; the
 tab state rides the URL so deep links/refresh/back resolve the same
 view, BatchListView precedent). -->
      <el-tabs v-model="pageTab" data-test="skill-page-tabs" class="skill-page-tabs">
        <el-tab-pane name="manage">
          <template #label><span data-test="skill-tab-manage">{{ t('skill.pageTabManage') }}</span></template>
        </el-tab-pane>
        <el-tab-pane name="market">
          <template #label><span data-test="skill-tab-market">{{ t('skill.pageTabMarket') }}</span></template>
        </el-tab-pane>
        <!-- project-memory ledger tab, mounted by the VIEW
 key (perm:aichat:project-memory, /users/me/permissions sourced);
 key-less users see an honest absence, not a dead tab. -->
        <el-tab-pane v-if="auth.hasPerm('perm:aichat:project-memory')" name="memory">
          <template #label><span data-test="skill-tab-memory">{{ t('skill.pageTabMemory') }}</span></template>
        </el-tab-pane>
      </el-tabs>

      <!-- Market tab reuses SkillMarketView as-is (embedded: its own frame is
           swapped off so the host card stays the single border). -->
      <SkillMarketView embedded v-if="pageTab === 'market'" />

      <ProjectMemoryPanel v-else-if="pageTab === 'memory'" />

      <template v-else>
      <!-- N-3 repayment + R21 same-band merge (FB group, revision 3): the manage
           band has no row-level batch bar and exactly 3 condition controls (at or
           under the 4-control threshold), so the page-level variant actions — the
           recycle-bin entry (permission gate unchanged: perm:tool:tool-market, no
           key no render; backend 403 double-gate stays authoritative: system view
           needs ADMIN, project view needs the owner) and create — ride the
           .filter-bar-actions trailing slot together with the shared
           column-settings control (variant first, then settings), and the emptied
           page-header row is removed as a whole shell. The page name rides the
           global top bar per R23. Query submit stays inline at the end of the
           condition flow; data-test anchors unchanged. -->

      <!-- IA fix: screening controls live in the shared filter-bar row;
 the project segment rides the global header context. -->
      <div class="filter-bar" data-test="skilllistview-filter-bar">
        <el-input
          v-model="query.keyword"
          :placeholder="t('skill.searchPh')"
          clearable
          style="width: 220px"
          data-test="skill-keyword"
          @keyup.enter="reload"
          @clear="reload"
        />
        <el-select
          v-model="query.type"
          :placeholder="t('skill.type')"
          clearable
          style="width: 140px"
          popper-class="tp-skill-type-popper"
          data-test="skill-type-select"
          data-se1-exempt="skill type options are a fixed 5-value enum in api/skillAdmin; never grows with remote data"
          @change="reload"
        >
          <el-option v-for="opt in SKILL_TYPES" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
        <el-select
          v-model="query.status"
          :placeholder="t('common.status')"
          clearable
          style="width: 130px"
          popper-class="tp-skill-status-popper"
          data-test="skill-status-select"
          data-se1-exempt="skill status options are a fixed 5-value enum in api/skillAdmin; never grows with remote data"
          @change="reload"
        >
          <el-option v-for="opt in SKILL_STATUSES" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
        <!-- (R21 revision 2): the query submit belongs inline at the
 end of the filter group (eye-hand continuity ≤ 1 click from the last
 control), not pushed to the trailing slot. data-test anchor rides
 unchanged (nine E2E files click it). -->
        <el-button data-test="skill-search" @click="reload">
          <el-icon><Search /></el-icon>{{ t('skill.query') }}
        </el-button>
        <!-- R25 mode declaration (TB group, honest absence / mode 2): this
             trailing slot lives inside the manage-only section below the tabs,
             so the settings control unmounts while the market/memory views show
             — no global-looking control ever acts on an invisible table.
             N-3: column visibility/order/pin/density persist per user through
             the shared useColumnConfig piece (viewKey 'skill'). -->
        <div class="filter-bar-actions" data-test="skill-filter-actions">
          <el-button v-if="auth.hasPerm('perm:tool:tool-market')" :icon="Delete" data-test="skill-recycle-open" @click="recycleVisible = true">
            {{ t('skill.recycle') }}
          </el-button>
          <el-button type="primary" :icon="Plus" data-test="skill-create-btn" @click="openCreate">{{ t('skill.createTitle') }}</el-button>
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

      <TableSkeleton v-if="loading" :cols="colCfg.visibleColumns.value.length + 1" data-test="skilllistview-skeleton" />
      <el-table border v-else :data="rows" :size="colCfg.state.density" row-key="id"  data-test="skill-table" @sort-change="onSortChange">
        <!-- N-3 wiring: every data column renders through the shared
             column-config loop (hide/order/pin take effect, density rides
             :size); the locked action column stays outside the loop. Header
             sort is server-side and single-sourced: SORT_KEY_MAP (already the
             request whitelist) decides the per-column sortable flag. -->
        <el-table-column
          v-for="col in colCfg.visibleColumns.value"
          :key="col.key"
          :prop="col.key"
          :fixed="col.fixed || false"
          :label="t(col.labelKey)"
          :width="col.width ?? undefined"
          :min-width="col.width ? undefined : SKILL_FLEX_MIN[col.key]"
          :align="col.key === 'currentVersion' ? 'center' : undefined"
          :sortable="SORT_KEY_MAP[col.key] ? 'custom' : false"
          :data-test="`skill-col-${col.key}`"
        >
          <template #default="{ row }">
            <template v-if="col.key === 'name'">
              <span class="skill-name">{{ row.name }}</span>
              <el-tag v-if="row.projectId == null" size="small" type="warning" style="margin-left: 6px">
                {{ t('skill.systemLevel') }}
              </el-tag>
              <div class="skill-preview" data-test="skill-preview">{{ row.bodyPreview || '—' }}</div>
            </template>
            <template v-else-if="col.key === 'status'">
              <el-tag :type="statusTag(row.status)" size="small" effect="light" data-test="skill-status-tag">
                {{ row.statusLabel }}
              </el-tag>
              <div v-if="row.status === 4 && row.rejectReason" class="reject-reason" data-test="skill-reject-reason">
                {{ row.rejectReason }}
              </div>
            </template>
            <template v-else-if="col.key === 'currentVersion'">v{{ row.currentVersion }}</template>
            <template v-else>{{ row[col.key] }}</template>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.action')" width="150" fixed="right">
          <template #default="{ row }">
            <RowActions :actions="rowActions(row)" :max="2" @command="(k: string) => onRowCmd(k, row)" />
          </template>
        </el-table-column>
        <template #empty>
          <EmptyState :title="t('skill.emptyTitle')" :hint="t('skill.emptyHint')" />
        </template>
      </el-table>

      <div class="pagination-bar">
        <el-pagination
          v-if="total > 0"
          v-model:current-page="query.page"
          :page-size="query.size"
          :total="total"
          layout="total, prev, pager, next"
          data-test="skill-pagination"
          @current-change="load"
        />
      </div>
      </template>

      
      <el-dialog
        v-model="formVisible"
        :title="editingId ? t('skill.editTitle') : t('skill.createTitle')"
        width="640px"
        destroy-on-close
        data-test="skill-form-dialog"
      >
        <el-form ref="formRef" :model="form" :rules="formRules" label-width="110px" scroll-to-error>
          <el-form-item :label="t('common.name')" prop="name">
            <el-input v-model="form.name" :placeholder="t('skill.formNamePh')" maxlength="128" data-test="skill-form-name" />
          </el-form-item>
          <el-form-item :label="t('skill.type')" prop="type">
            <el-select v-model="form.type" style="width: 100%" popper-class="tp-skill-form-type-popper" data-test="skill-form-type" data-se1-exempt="skill type options are a fixed 5-value enum in api/skillAdmin; never grows with remote data">
              <el-option v-for="opt in SKILL_TYPES" :key="opt.value" :label="opt.label" :value="opt.value" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="!editingId" :label="t('skill.projectLabel')">
            <ProjectSelect
              v-model="form.projectId"
              :placeholder="t('skill.projectPh')"
              width="100%"
              data-test="skill-form-project"
            />
          </el-form-item>
          <el-form-item :label="t('skill.bodyLabel')" prop="body">
            <el-input
              v-model="form.body"
              type="textarea"
              :rows="8"
              :placeholder="t('skill.bodyPh')"
              data-test="skill-form-body"
            />
          </el-form-item>
          <el-form-item label="Frontmatter">
            <el-input
              v-model="form.frontmatterJson"
              type="textarea"
              :rows="3"
              :placeholder="t('skill.fmPh')"
              data-test="skill-form-frontmatter"
            />
          </el-form-item>
          <el-form-item v-if="!editingId" :label="t('skill.submitNowLabel')">
            <el-switch v-model="form.submitNow" data-test="skill-form-submit-now" />
          </el-form-item>
          <FormSideEffectHint
            v-if="editingId"
            :text="t('skill.editHint')"
            test-id="skill-form-edit-hint"
          />
        </el-form>
        <template #footer>
          <el-button @click="formVisible = false">{{ t('common.cancel') }}</el-button>
          <el-button type="primary" :loading="saving" data-test="skill-form-submit" @click="submitForm">
            {{ editingId ? t('common.save') : t('skill.createAction') }}
          </el-button>
        </template>
      </el-dialog>

      
      <el-drawer v-model="verVisible" :title="t('skill.verDrawerTitle', { name: verSkillName })" size="620px" data-test="skill-version-drawer">
        <el-table v-loading="verLoading" :data="verRows" row-key="version" border data-test="skill-version-table">
          <el-table-column :label="t('skill.colVersion')" width="80" align="center">
            <template #default="{ row }">v{{ row.version }}</template>
          </el-table-column>
          <el-table-column :label="t('skill.colSnapshotStatus')" width="100">
            <template #default="{ row }">
              <el-tag size="small">{{ row.statusLabel }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="reason" :label="t('skill.colReason')" min-width="120" show-overflow-tooltip />
          <el-table-column prop="createdAt" :label="t('skill.colTime')" width="170" />
          <el-table-column :label="t('common.action')" width="90">
            <template #default="{ row }">
              <el-button link type="primary" :data-test="`skill-ver-view-${row.version}`" @click="viewVersion(row.version)">
                {{ t('skill.view') }}
              </el-button>
            </template>
          </el-table-column>
          <template #empty>
            <EmptyState :title="t('skill.verEmpty')" />
          </template>
        </el-table>
        <div v-if="verDetail" class="ver-detail" data-test="skill-version-detail">
          <div class="ver-detail-head">
            <span class="skill-name">{{ t('skill.snapshotHead', { ver: verDetail.version }) }}</span>
            <span class="text-secondary">{{ verDetail.createdAt }}</span>
          </div>
          <pre class="ver-body">{{ verDetail.body }}</pre>
          <div v-if="verDetail.frontmatterJson" class="ver-fm" data-test="skill-version-frontmatter">
            <span class="text-secondary">{{ t('skill.frontmatterPrefix') }}</span>{{ verDetail.frontmatterJson }}
          </div>
        </div>
      </el-drawer>

      
      <SkillRecycleDrawer v-model="recycleVisible" @changed="load()" />
    </div>
  </div>
</template>

<script setup lang="ts">

import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { Delete, Plus, Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import RowActions from '@/components/RowActions.vue'
import ProjectSelect from '@/components/ProjectSelect.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import ColumnSettings from '@/components/ColumnSettings.vue'
import FormSideEffectHint from '@/components/FormSideEffectHint.vue'
import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'
import { useObjectSync } from '@/composables/useObjectSync'
import SkillMarketView from './SkillMarketView.vue'
import SkillRecycleDrawer from './SkillRecycleDrawer.vue'
import ProjectMemoryPanel from './ProjectMemoryPanel.vue'
import { useAuthStore } from '@/stores/auth'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import { checkJsonObject } from '@/utils/jsonForm'
import { approvalStatusTag } from '@/utils/approvalTag'
import {
  approveSkill,
  createSkill,
  deleteSkill,
  getSkillVersion,
  listSkills,
  listSkillVersions,
  rejectSkill,
  retireSkill,
  submitSkill,
  updateSkill,
  SKILL_STATUS_KEYS,
  SKILL_TYPE_KEYS,
  type SkillAdminItem,
  type SkillVersionDetail,
  type SkillVersionItem,
} from '@/api/skillAdmin'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

// ---------- AN-5 page tab (manage / market), URL-query synced ----------
/** Query value wins on entry; an unknown key falls back to the manage tab. */
function tabFromQuery(): 'manage' | 'market' | 'memory' {
  const tab = route.query.tab
  return tab === 'market' || tab === 'memory' ? tab : 'manage'
}
const pageTab = ref<'manage' | 'market' | 'memory'>(tabFromQuery())

// Switching tabs writes the query back (replace = no history spam), so any tab
// state is shareable/refresh-stable per the AN tab-in-URL rule.
watch(pageTab, (tab) => {
  void router.replace({ query: { ...route.query, tab: tab === 'manage' ? undefined : tab } })
})
// In-app navigations into this page carrying ?tab=market (redirect from
// /skill-market, ⌘K deep link) must move the visible tab, not just the URL.
// The page is not keep-alive cached, but the route stays mounted when only the
// query changes, so setup never re-runs — the watcher is mandatory.
watch(() => route.query.tab, () => {
  pageTab.value = tabFromQuery()
})
// AK/: the list segment follows the global header project context; an empty
// context honestly means the system-wide view (backend: null projectId = no
// project filter, system-level rows included).
const { projectId, followTick } = useProjectPageContext()

const recycleVisible = ref(false)


const SKILL_TYPES = computed(() => Object.entries(SKILL_TYPE_KEYS).map(([value, key]) => ({ value: Number(value), label: t(key) })))

const SKILL_STATUSES = computed(() => Object.entries(SKILL_STATUS_KEYS).map(([value, key]) => ({ value: Number(value), label: t(key) })))

const loading = ref(false)
const rows = ref<SkillAdminItem[]>([])
const total = ref(0)
const query = reactive({
  page: 1,
  size: 20,
  keyword: '',
  type: null as number | null,
  status: null as number | null,
  sortBy: '',
  sortDir: '',
})

/** Column prop -> core SORT_COLUMNS whitelist key (only mapped columns render sortable). */
const SORT_KEY_MAP: Record<string, string> = {
  name: 'name',
  typeLabel: 'type',
  status: 'status',
  updatedAt: 'updated_at',
}

/** N-3 (R25 scope note): data columns of the manage ledger, driven through the
 * shared useColumnConfig piece — hide/order/pin/density persist per user under
 * the 'skill' viewKey; the locked action column stays outside the config. */
const SKILL_COLUMNS: ColumnDef[] = [
  { key: 'name', labelKey: 'skill.colName', width: null },
  { key: 'typeLabel', labelKey: 'skill.type', width: 120 },
  { key: 'status', labelKey: 'common.status', width: 120 },
  { key: 'currentVersion', labelKey: 'skill.colEffectiveVer', width: 90 },
  { key: 'updatedAt', labelKey: 'common.updatedAt', width: 170 },
]
/** Flexible columns fall back to min-width when no fixed width is set. */
const SKILL_FLEX_MIN: Record<string, number> = { name: 240 }
const colCfg = useColumnConfig('skill', SKILL_COLUMNS)
/** Popover rows (column state merged with labelKey for rendering). */
const settingsRows = computed(() =>
  colCfg.state.columns.map((c) => ({ ...c, labelKey: SKILL_COLUMNS.find((d) => d.key === c.key)!.labelKey })),
)

// Approval-chain tag color lifted to utils/approvalTag (shared with the tool
// domain and the project-detail view doors — no second mapping per page).
const statusTag = approvalStatusTag

async function load(): Promise<void> {
  loading.value = true
  try {
    const page = await listSkills({
      page: query.page,
      size: query.size,
      keyword: query.keyword || undefined,
      type: query.type ?? undefined,
      status: query.status ?? undefined,
      projectId: projectId.value ?? undefined,
      sortBy: query.sortBy || undefined,
      sortDir: (query.sortDir || undefined) as 'asc' | 'desc' | undefined,
    })
    rows.value = page.list || []
    total.value = page.total || 0
  } catch (e) {
    ElMessage.error((e as Error).message || t('skill.listLoadFailed'))
  } finally {
    loading.value = false
  }
}

// : a landed skill write (create-then-approve flow) re-reads
// this ledger on its current page/filter/sort as-is — silent convergence,
// no toast/confirm/jump (iron rules 2-3).
useObjectSync({
  watch: ['SKILL_WRITE'],
  onSync: () => void load(),
})

function reload(): void {
  query.page = 1
  void load()
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

function rowActions(row: SkillAdminItem) {
  const acts: { key: string; label: string; type?: 'primary' | 'warning' | 'danger' | 'info' | 'success'; confirm?: string; perm?: string }[] = []
  if (row.status === 1 || row.status === 4 || row.status === 3) {
    acts.push({ key: 'edit', label: t('common.edit') })
  }
  if (row.status === 1 || row.status === 4) {
    acts.push({ key: 'submit', label: t('skill.submitApproval'), confirm: t('skill.confirmSubmit', { name: row.name }) })
  }
  if (row.status === 2) {
    acts.push({ key: 'approve', label: t('skill.approve'), type: 'success', perm: 'perm:skill:approve', confirm: t('skill.confirmApprove', { name: row.name }) })
    acts.push({ key: 'reject', label: t('skill.reject'), type: 'danger', perm: 'perm:skill:approve' })
  }
  if (row.status === 3) {
    acts.push({ key: 'retire', label: t('skill.retire'), type: 'warning', confirm: t('skill.confirmRetire', { name: row.name }) })
  }
  if (row.status !== 2) {
    acts.push({ key: 'versions', label: t('skill.versionHistory') })
  }
  if (row.status !== 3) {
    acts.push({ key: 'remove', label: t('common.delete'), type: 'danger', confirm: t('skill.confirmDelete', { name: row.name }) })
  }
  return acts
}

async function onRowCmd(key: string, row: SkillAdminItem): Promise<void> {
  try {
    if (key === 'edit') {
      openEdit(row)
      return
    }
    if (key === 'versions') {
      openVersions(row)
      return
    }
    if (key === 'submit') await submitSkill(row.id, row.version)
    else if (key === 'approve') await approveSkill(row.id, row.version)
    else if (key === 'reject') {
      const r = await ElMessageBox.prompt(t('skill.rejectPrompt', { name: row.name }), t('skill.rejectTitle'), {
        inputValidator: (v: string) => (v && v.trim() ? true : t('skill.rejectReasonRequired')),
        confirmButtonText: t('skill.confirmReject'),
        cancelButtonText: t('common.cancel'),
        type: 'warning',
      })
      await rejectSkill(row.id, row.version, r.value.trim())
    } else if (key === 'retire') await retireSkill(row.id, row.version)
    else if (key === 'remove') await deleteSkill(row.id, row.version)
    ElMessage.success(t('common.success'))
    await load()
  } catch (e) {
    if (e === 'cancel' || e === 'close') return
    ElMessage.error((e as Error).message || t('common.failed'))
  }
}

const formVisible = ref(false)
const saving = ref(false)
const formRef = ref<FormInstance>()
const editingId = ref<number | null>(null)
const editingVersion = ref(0)
const form = reactive({
  name: '',
  type: 1,
  projectId: null as number | null,
  body: '',
  frontmatterJson: '',
  submitNow: false,
})

const formRules: FormRules = {
  name: [{ required: true, message: t('skill.nameRequired'), trigger: 'blur' }],
  type: [{ required: true, message: t('skill.typeRequired'), trigger: 'change' }],
  body: [{ required: true, message: t('skill.bodyRequired'), trigger: 'blur' }],
}

function openCreate(): void {
  editingId.value = null
  form.name = ''
  form.type = 1
  // Form-slot prefill : the
  // create dialog's project field starts at the header context; switching it
  // to "system level" (null) stays an explicit authoring decision, not a
  // second screening selector.
  form.projectId = projectId.value
  form.body = ''
  form.frontmatterJson = ''
  form.submitNow = false
  formVisible.value = true
}

function openEdit(row: SkillAdminItem): void {
  editingId.value = row.id
  editingVersion.value = row.version
  form.name = row.name
  form.type = row.type
  form.projectId = row.projectId
  form.body = ''
  form.frontmatterJson = row.frontmatterJson || ''
  void prefillBodyFromSnapshot(row)
  formVisible.value = true
}


async function prefillBodyFromSnapshot(row: SkillAdminItem): Promise<void> {
  try {
    const snap = await getSkillVersion(row.id, row.currentVersion)
    if (!form.body.trim()) {
      form.body = snap.body ?? ''
    }
    if (!form.frontmatterJson.trim()) {
      form.frontmatterJson = snap.frontmatterJson ?? ''
    }
  } catch {
    
  }
}

async function submitForm(): Promise<void> {
  await formRef.value?.validate()
  const fm = checkJsonObject(form.frontmatterJson)
  if (!fm.ok) {
    ElMessage.warning(t('skill.fmInvalid'))
    return
  }
  saving.value = true
  try {
    if (editingId.value) {
      await updateSkill(editingId.value, {
        expectedVersion: editingVersion.value,
        name: form.name.trim(),
        type: form.type,
        body: form.body.trim() ? form.body : null,
        frontmatterJson: fm.value,
      })
      ElMessage.success(t('skill.savedRepublish'))
    } else {
      await createSkill({
        projectId: form.projectId,
        name: form.name.trim(),
        type: form.type,
        body: form.body,
        frontmatterJson: fm.value,
        submitNow: form.submitNow,
      })
      ElMessage.success(form.submitNow ? t('skill.createdSubmitted') : t('skill.createdDraft'))
    }
    formVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message || t('skill.saveFailed'))
  } finally {
    saving.value = false
  }
}

const verVisible = ref(false)
const verLoading = ref(false)
const verSkillId = ref<number | null>(null)
const verSkillName = ref('')
const verRows = ref<SkillVersionItem[]>([])
const verDetail = ref<SkillVersionDetail | null>(null)

function openVersions(row: SkillAdminItem): void {
  verSkillId.value = row.id
  verSkillName.value = row.name
  verDetail.value = null
  verVisible.value = true
  void loadVersions()
}

async function loadVersions(): Promise<void> {
  if (verSkillId.value == null) return
  verLoading.value = true
  try {
    verRows.value = (await listSkillVersions(verSkillId.value)) || []
  } catch (e) {
    ElMessage.error((e as Error).message || t('skill.verLoadFailed'))
  } finally {
    verLoading.value = false
  }
}

async function viewVersion(version: number): Promise<void> {
  if (verSkillId.value == null) return
  try {
    verDetail.value = await getSkillVersion(verSkillId.value, version)
  } catch (e) {
    ElMessage.error((e as Error).message || t('skill.snapshotLoadFailed'))
  }
}

onMounted(() => {
  void load()
})

// : a header context switch rewrites the project segment — follow the new
// primaryId and reload from page 1 (in-page screening changes never bump
// followTick, so no double load).
watch(followTick, () => {
  reload()
})
</script>

<style scoped>
.skill-name {
  font-weight: 600;
}
.skill-preview {
  color: var(--tp-text-secondary);
  font-size: 12px;
  margin-top: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.reject-reason {
  color: var(--el-color-danger);
  font-size: 12px;
  margin-top: 2px;
}
.text-secondary {
  color: var(--tp-text-secondary);
}
.ver-detail {
  margin-top: 16px;
}
.ver-detail-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.ver-body {
  max-height: 420px;
  overflow: auto;
  padding: 10px 12px;
  border: 1px solid var(--tp-border-color);
  border-radius: 6px;
  background: var(--tp-fill-color-light, rgba(127, 127, 127, 0.06));
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0;
}
.ver-fm {
  margin-top: 8px;
  font-size: 12px;
  word-break: break-all;
}
</style>
