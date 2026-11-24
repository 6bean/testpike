<template>
  <div class="page-root tag-list-page" data-test="tag-list-page">
    <div class="page-card">
      <!-- FB same-band merge (10-22 ruling): the page has no row-level batch bar and
           zero screening conditions, so the page-level variant action (create) rides
           the .filter-bar-actions trailing slot on the same row as the view-state
           action (column settings), create first; the emptied page-header row is
           removed as a whole shell. The create entry keeps its ownership gate: the
           button is disabled while the header context is empty and the tooltip
           points the user at the header instead of a late form error. -->
      <div class="filter-bar" data-test="tag-filter-bar">
        <div class="filter-bar-actions">
          <el-tooltip :content="t('components.ownerEcho.pickInHeader')" placement="bottom" :disabled="!!projectId">
            <span>
              <el-button type="primary" :icon="Plus" :disabled="!projectId" data-test="tag-create-btn" @click="openCreate">
                {{ t('tag.create') }}
              </el-button>
            </span>
          </el-tooltip>
          <ColumnSettings
            :columns="currentPane.settingsRows.value"
            :density="currentPane.state.density"
            :disabled="currentPane.settingsDisabled.value"
            :density-disabled="currentPane.densityDisabled.value"
            :disabled-tooltip="currentPane.disabledReason.value ? t(currentPane.disabledReason.value) : ''"
            @toggle="currentPane.toggle"
            @move="currentPane.move"
            @density="currentPane.setDensity"
            @reset="currentPane.reset"
            @pin="currentPane.toggleFixed"
          />
        </div>
      </div>

      
      <div v-if="!loading && projects.length === 0" class="empty-box" data-test="tag-empty-no-project">
        <EmptyState :title="t('tag.noProject')" :hint="t('tag.noProjectHint')" :action-text="t('tag.goProjects')" @action="router.push('/projects')" />
      </div>

      <div v-else-if="!loading && !projectId" class="empty-box" data-test="tag-empty-select-project">
        <EmptyState :title="t('tag.selectProject')" :hint="t('tag.selectProjectHint')" />
      </div>

      <el-tabs v-else v-model="activeTab" class="tag-tabs" data-test="tag-tabs">
        <el-tab-pane :label="t('tag.tabTags')" name="tags">
          <TableSkeleton v-if="loading" :cols="3" data-test="taglistview-skeleton" />
          <DataTablePage
            :data="rows"
            data-test="tag-table"
            class="data-table"
            row-key="id"
            :size="tagPane.state.density"
            :column-config="tagPane.cfg"
            @sort-change="onSortChange"
            v-else
          >
            <!-- configurable data columns (show/hide, order, width,
 density persist through the shared useColumnConfig piece).
 (R25 mode 1): the scoped view is pane-bound - the page-level
 popover below outfits to whichever tab is active. -->
            <el-table-column
              v-for="col in tagPane.visibleColumns.value"
              :key="col.key"
              :prop="col.key"
              :fixed="col.fixed || false"
              :label="t(col.labelKey)"
              :width="col.width ?? undefined"
              :min-width="col.width ? undefined : TAG_FLEX_MIN[col.key]"
              :sortable="isColSortable(col.key) ? 'custom' : false"
              :align="col.key === 'caseCount' ? 'center' : undefined"
              :show-overflow-tooltip="col.key === 'name'"
              :data-test="`tag-col-${col.key}`"
            >
              <template #default="{ row }">
                <template v-if="col.key === 'name'">
                  <el-tag :type="tagTypeTag(row.type)" size="small">{{ tagTypeLabel(row.type) }}</el-tag>
                  <span class="tag-name" :data-test="`tag-name-${row.id}`">{{ row.name }}</span>
                </template>
                <span v-else>{{ row[col.key] }}</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('tag.actions')" width="260" fixed="right">
              <template #default="{ row }">
                <el-button size="small" data-test="tag-rename-btn" @click="openRename(row)">{{ t('tag.rename') }}</el-button>
                <el-button size="small" type="warning" plain data-test="tag-merge-btn" @click="openMerge(row)">
                  {{ t('tag.merge') }}
                </el-button>
                <el-button size="small" type="danger" plain data-test="tag-delete-btn" @click="doDelete(row)">
                  {{ t('tag.delete') }}
                </el-button>
              </template>
            </el-table-column>

            <template #empty>
              <EmptyState
                data-test="tag-empty-create"
                :title="t('common.emptyNoData')"
                :hint="t('tag.emptyCreateHint')"
                :action-text="t('tag.create')"
                @action="openCreate"
              />
            </template>
          </DataTablePage>
        </el-tab-pane>

        <el-tab-pane :label="t('tag.tabStats')" name="stats">
          <!-- tp-scope-exempt tp-pane-fixed-view: the stats pane is a four-column
 read-only aggregate (tag / cases / passes / pass-rate progress bar);
 no user value in hiding or reordering ratio columns, so the page
 keeps the pane a fixed view and the scoped button names the reason
 instead of acting dead on this table (R25 mode 1, ). -->
          <TableSkeleton v-if="statsLoading" :cols="4" data-test="taglistview-skeleton" />
          <DataTablePage :data="stats" data-test="tag-stats-table" class="data-table" row-key="tagId" v-else :empty-title="t('tag.statsEmpty')" :empty-hint="t('tag.statsEmptyHint')">
            <el-table-column prop="name" :label="t('tag.name')" min-width="180" show-overflow-tooltip />
            <el-table-column prop="caseCount" :label="t('tag.statsCaseCount')" width="100" align="center" />
            <el-table-column prop="passCount" :label="t('tag.statsPassCount')" width="100" align="center" />
            <el-table-column :label="t('tag.statsPassRate')" min-width="180">
              <template #default="{ row }">
                <el-progress :percentage="row.passRate" :stroke-width="12" />
              </template>
            </el-table-column>
          </DataTablePage>
        </el-tab-pane>
      </el-tabs>

      
      <el-dialog v-model="createVisible" :title="t('tag.createTitle')" width="480px" append-to-body data-test="tag-create-dialog">
        <!-- ownership echo (iron rule 1): read-only, single-sourced from the
 header project context — the tag lands on the visible segment -->
        <ProjectOwnerEcho />
        <el-form ref="createFormRef" :model="createForm" :rules="nameRules" label-width="80px" scroll-to-error>
          <el-form-item :label="t('tag.name')" prop="name">
            <el-input v-model="createForm.name" :placeholder="t('tag.namePlaceholder')" data-test="tag-create-name" maxlength="50" />
          </el-form-item>
          <el-form-item :label="t('tag.type')">
            <el-select v-model="createForm.type" style="width: 100%" data-test="tag-create-type">
              <el-option v-for="(label, ty) in TAG_TYPE_LABEL" :key="ty" :label="label" :value="Number(ty)" />
            </el-select>
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button data-test="tag-create-cancel" @click="createVisible = false">{{ t('tag.cancel') }}</el-button>
          <el-button type="primary" data-test="tag-create-submit" @click="submitCreate">{{ t('tag.confirm') }}</el-button>
        </template>
      </el-dialog>

      
      <el-dialog v-model="renameVisible" :title="t('tag.renameTitle')" width="480px" append-to-body>
        <el-form ref="renameFormRef" :model="renameForm" :rules="nameRules" label-width="80px" scroll-to-error>
          <el-form-item :label="t('tag.name')" prop="newName">
            <el-input v-model="renameForm.newName" data-test="tag-rename-input" maxlength="50" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="renameVisible = false">{{ t('tag.cancel') }}</el-button>
          <el-button type="primary" data-test="tag-rename-submit" @click="submitRename">{{ t('tag.confirm') }}</el-button>
        </template>
      </el-dialog>

      
      <el-dialog v-model="mergeVisible" :title="t('tag.mergeTitle')" width="480px" append-to-body>
        <el-alert type="info" :closable="false" class="merge-hint" :title="t('tag.mergeHint')" />
        <el-form label-width="80px">
          <el-form-item :label="t('tag.fromTag')">
            <el-input :model-value="mergeForm.fromName" disabled data-test="tag-merge-from" />
          </el-form-item>
          <el-form-item :label="t('tag.toTag')">
            <el-select v-model="mergeForm.toTagId" filterable style="width: 100%" data-test="tag-merge-to">
              <el-option
                v-for="row in rows"
                :key="row.id"
                :label="row.name"
                :value="row.id"
                :disabled="row.id === mergeForm.fromTagId"
              />
            </el-select>
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="mergeVisible = false">{{ t('tag.cancel') }}</el-button>
          <el-button type="primary" data-test="tag-merge-submit" @click="submitMerge">{{ t('tag.confirm') }}</el-button>
        </template>
      </el-dialog>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { Plus } from '@element-plus/icons-vue'
import ProjectOwnerEcho from '@/components/ProjectOwnerEcho.vue'
import ColumnSettings from '@/components/ColumnSettings.vue'
import { useTabScopedSettings } from '@/composables/tabScopedColumnSettings'
import type { ColumnDef } from '@/composables/useColumnConfig'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { createTag, deleteTag, listTags, listTagStats, mergeTags, renameTag, type TagItem, type TagStatsItem } from '@/api/tags'
import { checkTagDelete } from '@/api/deletecheck'
import { useObjectSync } from '@/composables/useObjectSync'

// : a landed tag write silently re-reads this page as-is (iron rule 2 —
// no toast, no jump). Stats ride along so the usage counters stay coherent.
useObjectSync({
  watch: ['TAG_WRITE'],
  onSync: () => {
    void load()
    void loadStats()
  },
})
import { guardedDelete } from '@/utils/deleteGuard'
import { bizErrorMsg } from '@/utils/bizError'
import { withProjectQuery } from '@/utils/projectQuery'
import { useProjectPageContext } from '@/composables/useProjectPageContext'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()


const TAG_TYPE_LABEL: Record<number, string> = {
  get 1() { return t('tag.types.module') },
  get 2() { return t('tag.types.feature') },
  get 3() { return t('tag.types.priority') },
  get 4() { return t('tag.types.free') },
  get 7() { return t('tag.types.pool') },
}

function tagTypeLabel(type: number): string {
  return TAG_TYPE_LABEL[type] ?? String(type)
}

function tagTypeTag(type: number): 'primary' | 'success' | 'warning' | 'info' {
  return type === 1 ? 'primary' : type === 2 ? 'success' : type === 3 ? 'warning' : 'info'
}

const projects = computed(() => auth.projects)

const { projectId } = useProjectPageContext()
const loading = ref(false)
const statsLoading = ref(false)
const rows = ref<TagItem[]>([])
const stats = ref<TagStatsItem[]>([])
const activeTab = ref('tags')
// Server-side sort state (same shape as the user/requirement pages). The tag
// list contract has no paging (full project segment), so there is no page
// counter to reset — sorting just re-requests with the whitelisted key.
const query = reactive<{ sortBy: string; sortDir: string }>({ sortBy: '', sortDir: '' })

const createVisible = ref(false)
const createFormRef = ref<FormInstance>()
const createForm = reactive({ name: '', type: 4 })
const renameVisible = ref(false)
const renameFormRef = ref<FormInstance>()
const renameForm = reactive({ id: 0, newName: '' })
const mergeVisible = ref(false)
const mergeForm = reactive({ fromTagId: 0, fromName: '', toTagId: 0 })

const nameRules: FormRules = {
  name: [{ required: true, message: t('tag.nameRequired'), trigger: 'blur' }],
  newName: [{ required: true, message: t('tag.nameRequired'), trigger: 'blur' }],
}

// ---------- column configuration (shared useColumnConfig piece) ----------
/** Configurable data columns (the fixed-right action column stays locked out). */
const TAG_COLUMNS: ColumnDef[] = [
  { key: 'name', labelKey: 'tag.name', width: null },
  // Single-line header budget (AN visual gate ): five CJK glyphs plus the
  // sort icon need ~120px inside the cell padding — 100 wrapped the header to
  // two lines under the 100-row worst-case fixture (measured h=46px).
  { key: 'caseCount', labelKey: 'tag.caseCount', width: 128 },
]
/** Flexible columns fall back to min-width when no explicit width is set. */
const TAG_FLEX_MIN: Record<string, number> = { name: 180 }
// Column prop -> core SORT_COLUMNS whitelist key.
// Single source for both the sortable-column decision and the request payload —
// columns outside the whitelist never emit sort params (no client-only fake sort).
// The tag page renders name/caseCount columns only; type/created_at stay
// server-whitelist members without dead client-side map entries.
const SORT_KEY_MAP: Record<string, string> = {
  name: 'name',
  caseCount: 'case_count',
}
/** Whether this column has a server-side sort key (shared decision source). */
function isColSortable(key: string): boolean {
  return key in SORT_KEY_MAP
}
/**
 * (TB group R25, mode 1 = scope-aware single settings piece): the
 * page-level popover outfits to the active tab instead of silently acting on
 * one tab's table. Each pane gets its own viewKey (tp-colcfg-tags keeps the
 * historical key for the list pane; tp-colcfg-tagStats is the new pane key)
 * so the two tables never share persisted state.
 *
 * The stats pane stays a fixed view (tp-scope-exempt: four read-only
 * aggregate columns - tag/cases/passes/pass-rate - where the pass-rate cell is
 * a progress bar; hiding or reordering one column breaks the ratio reading and
 * there is no user value in configuring it, so the honest answer is a disabled
 * button that names the reason rather than a dead panel).
 */
const settingsHost = useTabScopedSettings(activeTab)
const tagPane = settingsHost.settingsFor('tags', 'tags', TAG_COLUMNS)
const statsPane = settingsHost.settingsFor('stats', 'tagStats', null, {
  disabledReasonKey: 'tag.statsFixedViewHint',
})
const currentPane = settingsHost.currentPane

/**
 * Data-load signature for this page: the current project (header context
 * switches and in-page switching both surface as a signature change).
 * Single driving source - never trigger duplicate requests from several places.
 */
const loadSignature = computed(() => (projectId.value == null ? '' : String(projectId.value)))

watch(loadSignature, (next, prev) => {
  if (next && next !== prev) void Promise.all([load(), loadStats()])
})

async function load(): Promise<void> {
  if (!projectId.value) return
  loading.value = true
  try {
    rows.value = await listTags(projectId.value, {
      sortBy: query.sortBy || undefined,
      sortDir: query.sortDir || undefined,
    })
  } finally {
    loading.value = false
  }
}

/** Header sort: forward the whitelisted key to the server (ORDER BY happens in SQL);
 * clearing a sort falls back to the backend default order (id ASC). */
function onSortChange({ prop, order }: { prop: string; order: string | null }): void {
  if (order === null) {
    query.sortBy = ''
    query.sortDir = ''
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
  void load()
}

async function loadStats(): Promise<void> {
  if (!projectId.value) return
  statsLoading.value = true
  try {
    stats.value = await listTagStats(projectId.value)
  } finally {
    statsLoading.value = false
  }
}

function openCreate(): void {
  createForm.name = ''
  createForm.type = 4
  createVisible.value = true
}

async function submitCreate(): Promise<void> {
  if (!projectId.value) return
  await createFormRef.value?.validate()
  try {
    await createTag({ projectId: projectId.value, name: createForm.name.trim(), type: createForm.type })
    ElMessage.success(t('tag.createOk'))
    createVisible.value = false
    await Promise.all([load(), loadStats()])
  } catch (e: any) {
    ElMessage.error(bizErrorMsg(e, t('tag.createFail')))
  }
}

function openRename(row: TagItem): void {
  renameForm.id = row.id
  renameForm.newName = row.name
  renameVisible.value = true
}

async function submitRename(): Promise<void> {
  await renameFormRef.value?.validate()
  try {
    await renameTag(renameForm.id, renameForm.newName.trim())
    ElMessage.success(t('tag.renameOk'))
    renameVisible.value = false
    await Promise.all([load(), loadStats()])
  } catch (e: any) {
    ElMessage.error(bizErrorMsg(e, t('tag.renameFail')))
  }
}

function openMerge(row: TagItem): void {
  mergeForm.fromTagId = row.id
  mergeForm.fromName = row.name
  mergeForm.toTagId = 0
  mergeVisible.value = true
}

async function submitMerge(): Promise<void> {
  if (!mergeForm.toTagId) {
    ElMessage.warning(t('tag.mergeNeedTarget'))
    return
  }
  try {
    await mergeTags(mergeForm.fromTagId, mergeForm.toTagId)
    ElMessage.success(t('tag.mergeOk'))
    mergeVisible.value = false
    await Promise.all([load(), loadStats()])
  } catch (e: any) {
    ElMessage.error(bizErrorMsg(e, t('tag.mergeFail')))
  }
}

async function doDelete(row: TagItem): Promise<void> {
  const ok = await guardedDelete({
    check: () => checkTagDelete(row.id),
    doDelete: (force) => deleteTag(row.id),
    entityName: t('tag.entityName', { name: row.name }),
    blocked: true,
  })
  if (ok) {
    ElMessage.success(t('tag.deleteOk'))
    await Promise.all([load(), loadStats()])
  }
}

onMounted(async () => {
  loading.value = true
  try {
    await auth.loadMe()
  } catch {
  } finally {
    loading.value = false
  }
  if (projectId.value && !projects.value.some((p) => p.projectId === projectId.value)) {
    projectId.value = null
    void router.replace(withProjectQuery(route.query, null))
  }
  // Init = URL priority chain -> global context primaryId (dual-empty = honest
  // absence, page empty state points the way)
  if (projectId.value) {
    await Promise.all([load(), loadStats()])
  }
})
</script>

<style scoped>
.tag-name {
  margin-left: 8px;
}
.merge-hint {
  margin-bottom: 16px;
}
</style>
