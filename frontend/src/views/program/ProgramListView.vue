<!-- tp-sort-exempt: programs are an operator-provisioned governance set (a
     handful of rows) served in full by /api/v1/programs; there is no
     server-side sort channel to wire and row order carries no meaning. -->
<!-- tp-scope-exempt: mode-2 honest absence. The single column-settings piece
     is bound to the main program ledger; the drawer's mount/member tables are
     fixed detail views whose columns are hard-coded by the panel contract. -->
<template>
  <div class="page-root program-list-page" data-test="program-list-page">
    <div class="page-card">
      <!-- FB same-band merge (R21 three-fix): no row-level batch bar and the
 filter band carries two condition controls (<=4 threshold), so the
 page-level variant action (create) rides the .filter-bar-actions
 trailing slot on the same row as the view-state action (column
 settings), create first, never adjacent to the query affordance
 (this page is instant-filter: no submit button). The global
 governance page has no project segment: it deliberately does NOT
 consume useProjectPageContext (R30 exemption, same shape as the
 deleted-approval leaf) — programs group projects, they are not
 scoped by one. -->
      <div class="filter-bar" data-test="program-filter-bar">
        <div class="filter-bar-condition">
          <el-radio-group v-model="stateFilter" size="small" data-test="program-state-seg">
            <el-radio-button :value="1">{{ t('program.stateActive') }}</el-radio-button>
            <el-radio-button :value="0">{{ t('program.stateArchived') }}</el-radio-button>
            <el-radio-button :value="-1">{{ t('program.stateAll') }}</el-radio-button>
          </el-radio-group>
          <el-input
            v-model="keyword"
            :placeholder="t('program.searchPlaceholder')"
            :prefix-icon="Search"
            clearable
            class="condition-search"
            data-test="program-search"
          />
        </div>
        <div class="filter-bar-actions">
          <el-button type="primary" :icon="Plus" data-test="program-create-btn" @click="openCreate">
            {{ t('program.create') }}
          </el-button>
          <ColumnSettings
            :columns="listPane.settingsRows.value"
            :density="listPane.state.density"
            :disabled="listPane.settingsDisabled.value"
            :density-disabled="listPane.densityDisabled.value"
            :disabled-tooltip="listPane.disabledReason.value ? t(listPane.disabledReason.value) : ''"
            @toggle="listPane.toggle"
            @move="listPane.move"
            @density="listPane.setDensity"
            @reset="listPane.reset"
            @pin="listPane.toggleFixed"
          />
        </div>
      </div>

      <TableSkeleton v-if="loading" :cols="4" data-test="programlistview-skeleton" />
      <DataTablePage
        v-else
        :data="paged"
        data-test="program-table"
        class="data-table"
        row-key="id"
        :size="listPane.state.density"
        :column-config="listPane.cfg"
      >
        <el-table-column
          v-for="col in listPane.visibleColumns.value"
          :key="col.key"
          :prop="col.key"
          :fixed="col.fixed || false"
          :label="t(col.labelKey)"
          :width="col.width ?? undefined"
          :min-width="col.width ? undefined : FLEX_MIN[col.key]"
          :show-overflow-tooltip="col.key === 'name' || col.key === 'description'"
          :data-test="`program-col-${col.key}`"
        >
          <template #default="{ row }">
            <template v-if="col.key === 'state'">
              <el-tag :type="row.state === 1 ? 'success' : 'info'" size="small">
                {{ row.state === 1 ? t('program.stateActive') : t('program.stateArchived') }}
              </el-tag>
            </template>
            <template v-else-if="col.key === 'createdAt'">
              <TimeCell :iso="row.createdAt" />
            </template>
            <span v-else>{{ row[col.key] }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('program.actions')" width="300" fixed="right">
          <template #default="{ row }">
            <el-button size="small" data-test="program-projects-btn" @click="openDrawer(row, 'projects')">
              {{ t('program.manageProjects') }}
            </el-button>
            <el-button size="small" data-test="program-members-btn" @click="openDrawer(row, 'members')">
              {{ t('program.manageMembers') }}
            </el-button>
            <el-button size="small" data-test="program-edit-btn" @click="openEdit(row)">
              {{ t('program.edit') }}
            </el-button>
            <!-- Destructive lifecycle actions ride a dropdown so the row keeps
                 three direct affordances (vision 1.1 collapse rule); the
                 anchors the E2E chain targets stay on the menu items. -->
            <el-dropdown trigger="click" size="small" @command="(cmd: string) => runRowCommand(row, cmd)">
              <el-button size="small" data-test="program-more-btn">{{ t('program.more') }}</el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item
                    command="state"
                    :data-test="row.state === 1 ? 'program-archive-btn' : 'program-restore-btn'"
                  >
                    {{ row.state === 1 ? t('program.archive') : t('program.restore') }}
                  </el-dropdown-item>
                  <el-dropdown-item command="delete" divided data-test="program-delete-btn">
                    <span class="danger-text">{{ t('program.delete') }}</span>
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </el-table-column>

        <template #empty>
          <!-- Three-state honesty: a table emptied by the active filters must
               not offer creation as the only way out — name the condition and
               give the clear-filters escape first (true-empty keeps the
               original create guidance below). -->
          <EmptyState
            v-if="rows.length > 0 && filtered.length === 0"
            data-test="program-empty-filtered"
            :title="keyword.trim()
              ? t('program.noMatchTitleKw', { kw: keyword.trim() })
              : t('program.noMatchTitleTab')"
            :hint="t('program.noMatchHint')"
            :action-text="t('program.clearFilters')"
            @action="clearFilters"
          />
          <EmptyState
            v-else
            data-test="program-empty-create"
            :title="t('common.emptyNoData')"
            :hint="t('program.emptyHint')"
            :action-text="t('program.create')"
            @action="openCreate"
          />
        </template>
      </DataTablePage>

      <div v-if="showPager" class="pagination-bar" data-test="program-pagination">
        <el-pagination
          v-model:current-page="pageNo"
          :page-size="pageSize"
          :total="programTotal"
          layout="total, prev, pager, next"
          size="small"
          background
        />
      </div>

      <!-- Create / edit share one dialog: id==0 means create. -->
      <el-dialog
        v-model="formVisible"
        :title="form.id === 0 ? t('program.createTitle') : t('program.editTitle')"
        width="480px"
        append-to-body
        :data-test="form.id === 0 ? 'program-create-dialog' : 'program-edit-dialog'"
      >
        <el-form ref="formRef" :model="form" :rules="formRules" label-width="80px" scroll-to-error>
          <el-form-item :label="t('program.name')" prop="name">
            <el-input
              v-model="form.name"
              :placeholder="t('program.namePlaceholder')"
              maxlength="60"
              data-test="program-form-name"
            />
          </el-form-item>
          <el-form-item :label="t('program.description')">
            <el-input
              v-model="form.description"
              type="textarea"
              :rows="3"
              maxlength="200"
              :placeholder="t('program.descPlaceholder')"
              data-test="program-form-desc"
            />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="formVisible = false">{{ t('program.cancel') }}</el-button>
          <el-button type="primary" data-test="program-form-submit" @click="submitForm">
            {{ form.id === 0 ? t('program.createSubmit') : t('program.saveSubmit') }}
          </el-button>
        </template>
      </el-dialog>

      <!-- Mount / members ride one drawer with two tabs. R25 note: the drawer
           tables are fixed small read lists with no page-level view-state
           control at all (mode 2 honest absence) — the column-settings control
           above binds only to the main table, so there is no cross-container
           scope mismatch to guard. -->
      <el-drawer v-model="drawerVisible" :title="drawerTitle" size="56%" :data-test="`program-drawer-${drawerTab}`">
        <el-tabs v-model="drawerTab" data-test="program-drawer-tabs">
          <el-tab-pane :label="t('program.tabProjects')" name="projects">
            <div class="drawer-toolbar">
              <el-select
                v-model="attachPick"
                filterable
                :placeholder="t('program.attachPlaceholder')"
                class="drawer-picker"
                data-test="program-attach-select"
              >
                <el-option
                  v-for="p in attachableProjects"
                  :key="p.projectId"
                  :label="p.projectName"
                  :value="p.projectId"
                />
              </el-select>
              <el-button type="primary" :disabled="attachPick == null" data-test="program-attach-btn" @click="doAttach">
                {{ t('program.attach') }}
              </el-button>
            </div>
            <el-table v-loading="projectsLoading" :data="attachedRows" row-key="id" border :max-height="420" data-test="program-attached-table">
              <el-table-column prop="name" :label="t('program.projectName')" min-width="200" show-overflow-tooltip />
              <el-table-column :label="t('program.actions')" width="120" align="right">
                <template #default="{ row }">
                  <el-button size="small" type="danger" plain :data-test="`program-detach-${row.id}`" @click="doDetach(row)">
                    {{ t('program.detach') }}
                  </el-button>
                </template>
              </el-table-column>
              <template #empty>
                <EmptyState :title="t('common.emptyNoData')" :hint="t('program.attachEmptyHint')" />
              </template>
            </el-table>
          </el-tab-pane>

          <el-tab-pane :label="t('program.tabMembers')" name="members">
            <div class="drawer-toolbar">
              <el-select
                v-model="memberPick"
                filterable
                remote
                reserve-keyword
                :remote-method="searchUsers"
                :loading="usersSearching"
                :placeholder="t('program.memberSearchPlaceholder')"
                class="drawer-picker"
                data-test="program-member-select"
              >
                <el-option
                  v-for="u in memberCandidates"
                  :key="u.id"
                  :label="`${u.displayName || u.username}（${u.username}）`"
                  :value="u.id"
                />
              </el-select>
              <el-radio-group v-model="memberRole" size="small" data-test="program-member-role">
                <el-radio :value="1">{{ t('program.roleMember') }}</el-radio>
                <el-radio :value="2">{{ t('program.roleManager') }}</el-radio>
              </el-radio-group>
              <el-button type="primary" :disabled="memberPick == null" data-test="program-member-add-btn" @click="doAddMember">
                {{ t('program.addMember') }}
              </el-button>
            </div>
            <el-table v-loading="membersLoading" :data="memberRows" row-key="userId" border :max-height="420" data-test="program-member-table">
              <el-table-column prop="label" :label="t('program.memberName')" min-width="200" show-overflow-tooltip />
              <el-table-column :label="t('program.memberRole')" width="200">
                <template #default="{ row }">
                  <el-radio-group
                    :model-value="row.role"
                    size="small"
                    @update:model-value="(v: number) => doUpdateRole(row, v)"
                  >
                    <el-radio :value="1">{{ t('program.roleMember') }}</el-radio>
                    <el-radio :value="2">{{ t('program.roleManager') }}</el-radio>
                  </el-radio-group>
                </template>
              </el-table-column>
              <el-table-column :label="t('program.actions')" width="120" align="right">
                <template #default="{ row }">
                  <el-button size="small" type="danger" plain :data-test="`program-member-remove-${row.userId}`" @click="doRemoveMember(row)">
                    {{ t('program.removeMember') }}
                  </el-button>
                </template>
              </el-table-column>
              <template #empty>
                <EmptyState :title="t('common.emptyNoData')" :hint="t('program.memberEmptyHint')" />
              </template>
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </el-drawer>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { Plus, Search } from '@element-plus/icons-vue'
import ColumnSettings from '@/components/ColumnSettings.vue'
import EmptyState from '@/components/EmptyState.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import DataTablePage from '@/components/DataTablePage.vue'
import TimeCell from '@/components/TimeCell.vue'
import { useTabScopedSettings } from '@/composables/tabScopedColumnSettings'
import { useClientPagination } from '@/composables/useClientPagination'
import type { ColumnDef } from '@/composables/useColumnConfig'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import {
  addProgramMember,
  attachProgramProject,
  createProgram,
  deleteProgram,
  detachProgramProject,
  listProgramMembers,
  listProgramProjectIds,
  listPrograms,
  removeProgramMember,
  setProgramState,
  updateProgram,
  updateProgramMemberRole,
  type ProgramMemberView,
  type ProgramView,
} from '@/api/program'
import { listUsers, type UserView } from '@/api/user'
import { bizErrorMsg } from '@/utils/bizError'

const { t } = useI18n()
const auth = useAuthStore()

// ---------- list state ----------
const loading = ref(false)
const rows = ref<ProgramView[]>([])
/** 1=active (default per intake), 0=archived, -1=all. */
const stateFilter = ref<number>(1)
const keyword = ref('')

/** Instant client-side filter: search matches name + description, case-insensitive. */
const filtered = computed(() => {
  let out = rows.value
  if (stateFilter.value !== -1) out = out.filter((p) => p.state === stateFilter.value)
  const kw = keyword.value.trim().toLowerCase()
  if (kw) {
    out = out.filter(
      (p) => p.name.toLowerCase().includes(kw) || (p.description ?? '').toLowerCase().includes(kw),
    )
  }
  return out
})

// Client-side paging: /api/v1/programs serves the governance set in full, so
// the table keeps a visible paging affordance beyond the page size (worst-case
// scale rule). The window arithmetic is shared with the program dashboard
// through useClientPagination (one implementation for both), and a single-page
// result hides the control entirely (zero disturbance). Declared AFTER
// `filtered` because the hook's internal watch reads its getter eagerly.
const { pageNo, pageSize, paged, total: programTotal, showPager } =
  useClientPagination(() => filtered.value)

/** Filtered-empty escape hatch: drop the keyword and widen the status tab to all. */
function clearFilters(): void {
  keyword.value = ''
  stateFilter.value = -1
}

async function load(): Promise<void> {
  loading.value = true
  try {
    rows.value = await listPrograms()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('program.loadFailed')))
  } finally {
    loading.value = false
  }
}

onMounted(load)

// ---------- column settings (single-pane scoped view, R25 mode 1) ----------
const PROGRAM_COLUMNS: ColumnDef[] = [
  { key: 'name', labelKey: 'program.name', width: null },
  { key: 'description', labelKey: 'program.description', width: null },
  { key: 'state', labelKey: 'program.state', width: 110 },
  { key: 'createdAt', labelKey: 'program.createdAt', width: 160 },
]
const FLEX_MIN: Record<string, number> = { name: 180, description: 240 }
const paneTab = ref('list')
const settingsHost = useTabScopedSettings(paneTab)
const listPane = settingsHost.settingsFor('list', 'programs', PROGRAM_COLUMNS)

// ---------- create / edit ----------
const formVisible = ref(false)
const formRef = ref<FormInstance>()
const form = reactive({ id: 0, name: '', description: '' })
const formRules: FormRules = {
  name: [{ required: true, message: t('program.nameRequired'), trigger: 'blur' }],
}

function openCreate(): void {
  form.id = 0
  form.name = ''
  form.description = ''
  formVisible.value = true
}

function openEdit(row: ProgramView): void {
  form.id = row.id
  form.name = row.name
  form.description = row.description ?? ''
  formVisible.value = true
}

async function submitForm(): Promise<void> {
  const ok = await formRef.value?.validate().catch(() => false)
  if (!ok) return
  try {
    if (form.id === 0) {
      await createProgram(form.name, form.description || null)
      ElMessage.success(t('program.created', { name: form.name }))
    } else {
      await updateProgram(form.id, form.name, form.description || null)
      ElMessage.success(t('program.updated', { name: form.name }))
    }
    formVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('program.saveFailed')))
  }
}

// ---------- lifecycle ----------
async function doToggleState(row: ProgramView): Promise<void> {
  const next = row.state === 1 ? 0 : 1
  try {
    await ElMessageBox.confirm(
      next === 0 ? t('program.archiveConfirm', { name: row.name }) : t('program.restoreConfirm', { name: row.name }),
      t('program.confirmTitle'),
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await setProgramState(row.id, next)
    ElMessage.success(next === 0 ? t('program.archived', { name: row.name }) : t('program.restored', { name: row.name }))
    await load()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('program.saveFailed')))
  }
}

async function doDelete(row: ProgramView): Promise<void> {
  try {
    await ElMessageBox.confirm(t('program.deleteConfirm', { name: row.name }), t('program.confirmTitle'), {
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await deleteProgram(row.id)
    ElMessage.success(t('program.deleted', { name: row.name }))
    await load()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('program.deleteFailed')))
  }
}

/** Routes the collapsed lifecycle commands from the row "more" dropdown. */
function runRowCommand(row: ProgramView, cmd: string): void {
  if (cmd === 'state') void doToggleState(row)
  else if (cmd === 'delete') void doDelete(row)
}

// ---------- drawer: projects + members ----------
const drawerVisible = ref(false)
const drawerTab = ref<'projects' | 'members'>('projects')
const drawerProgram = ref<ProgramView | null>(null)
const drawerTitle = computed(() =>
  drawerProgram.value ? `${drawerProgram.value.name} · ${t('program.drawerTitle')}` : t('program.drawerTitle'),
)

const projectsLoading = ref(false)
const attachedIds = ref<number[]>([])
/** Attached projects resolved through the header-visible snapshot; ids the
 *  current user cannot see surface as #id rows (membership truth beats name). */
const attachedRows = computed(() =>
  attachedIds.value.map((id) => {
    // /users/me snapshot shape is ProjectBrief { projectId, projectName } —
    // not id/name (api/auth.ts). Matching on the wrong field left every
    // option value undefined and broke the dropdown render.
    const p = auth.projects.find((x) => x.projectId === id)
    return { id, name: p?.projectName ?? `#${id}` }
  }),
)
const attachPick = ref<number | null>(null)
const attachableProjects = computed(() => auth.projects.filter((p) => !attachedIds.value.includes(p.projectId)))

const membersLoading = ref(false)
interface MemberRow extends ProgramMemberView {
  label: string
}
const memberRows = ref<MemberRow[]>([])
const memberPick = ref<number | null>(null)
const memberRole = ref(1)
const memberCandidates = ref<UserView[]>([])
const usersSearching = ref(false)

function openDrawer(row: ProgramView, tab: 'projects' | 'members'): void {
  drawerProgram.value = row
  drawerTab.value = tab
  drawerVisible.value = true
  void loadAttached()
  void loadMembers()
}

async function loadAttached(): Promise<void> {
  const prog = drawerProgram.value
  if (!prog) return
  projectsLoading.value = true
  try {
    attachedIds.value = await listProgramProjectIds(prog.id)
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('program.loadFailed')))
  } finally {
    projectsLoading.value = false
  }
}

async function doAttach(): Promise<void> {
  const prog = drawerProgram.value
  if (!prog || attachPick.value == null) return
  try {
    await attachProgramProject(prog.id, attachPick.value)
    attachPick.value = null
    await loadAttached()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('program.attachFailed')))
  }
}

async function doDetach(row: { id: number; name: string }): Promise<void> {
  const prog = drawerProgram.value
  if (!prog) return
  try {
    await ElMessageBox.confirm(t('program.detachConfirm', { name: row.name, program: prog.name }), t('program.confirmTitle'), {
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await detachProgramProject(prog.id, row.id)
    await loadAttached()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('program.detachFailed')))
  }
}

async function loadMembers(): Promise<void> {
  const prog = drawerProgram.value
  if (!prog) return
  membersLoading.value = true
  try {
    const ms = await listProgramMembers(prog.id)
    const named = await Promise.all(
      ms.map(async (m) => ({ ...m, label: await resolveUserName(m.userId) })),
    )
    memberRows.value = named
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('program.loadFailed')))
  } finally {
    membersLoading.value = false
  }
}

/** Resolve one member id to a display name through the keyword search endpoint
 *  (no batch-by-ids route on the user API; exact username/display hits first). */
async function resolveUserName(userId: number): Promise<string> {
  const cached = memberCandidates.value.find((u) => u.id === userId)
  if (cached) return displayNameOf(cached)
  try {
    const res = await listUsers({ keyword: String(userId), page: 1, size: 20 })
    const hit = res.list.find((u) => u.id === userId)
    if (hit) return displayNameOf(hit)
  } catch {
    // fall through to the id label — never block the whole table on one name
  }
  return `#${userId}`
}

function displayNameOf(u: UserView): string {
  return u.displayName || u.username
}

async function searchUsers(kw: string): Promise<void> {
  if (!kw.trim()) {
    memberCandidates.value = []
    return
  }
  usersSearching.value = true
  try {
    const res = await listUsers({ keyword: kw, page: 1, size: 20 })
    memberCandidates.value = res.list
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('program.loadFailed')))
  } finally {
    usersSearching.value = false
  }
}

async function doAddMember(): Promise<void> {
  const prog = drawerProgram.value
  if (!prog || memberPick.value == null) return
  try {
    await addProgramMember(prog.id, memberPick.value, memberRole.value)
    memberPick.value = null
    await loadMembers()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('program.memberAddFailed')))
  }
}

async function doUpdateRole(row: MemberRow, role: number): Promise<void> {
  const prog = drawerProgram.value
  if (!prog || role === row.role) return
  try {
    await updateProgramMemberRole(prog.id, row.userId, role)
    await loadMembers()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('program.memberRoleFailed')))
  }
}

async function doRemoveMember(row: MemberRow): Promise<void> {
  const prog = drawerProgram.value
  if (!prog) return
  try {
    await ElMessageBox.confirm(
      t('program.memberRemoveConfirm', { name: row.label, program: prog.name }),
      t('program.confirmTitle'),
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await removeProgramMember(prog.id, row.userId)
    await loadMembers()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('program.memberRemoveFailed')))
  }
}

// Switching the drawer tab lazily refreshes the matching list so both views
// stay honest after writes on the other tab.
watch(drawerTab, (tab) => {
  if (!drawerVisible.value) return
  if (tab === 'projects') void loadAttached()
  else void loadMembers()
})
</script>

<style scoped>
.condition-search {
  width: 240px;
}
.danger-text {
  color: var(--el-color-danger);
}
.drawer-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}
.drawer-picker {
  flex: 1;
  min-width: 220px;
}
</style>
