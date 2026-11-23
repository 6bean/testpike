<template>
  <div class="page-root defect-list-page" data-test="defect-list-page">
    <div class="page-card">

      <!-- IA fix: screening lives in the shared filter-bar row; the
 project rides the global header context (page selector removed). -->
      <div class="filter-bar" data-test="defect-list-filter-bar">
          <el-select
            v-model="filters.status"
            :placeholder="t('defect.statusAll')"
            clearable
            style="width: 130px"
            data-test="defect-filter-status"
            @change="load(1)"
          >
            <el-option v-for="(label, s) in DEFECT_STATUS_LABEL" :key="s" :label="label" :value="Number(s)" />
          </el-select>
          <el-select
            v-model="filters.severity"
            :placeholder="t('defect.severityAll')"
            clearable
            style="width: 140px"
            data-test="defect-filter-severity"
            @change="load(1)"
          >
            <el-option v-for="(label, s) in DEFECT_SEVERITY_LABEL" :key="s" :label="label" :value="Number(s)" />
          </el-select>
          <el-input
            v-model="filters.keyword"
            :placeholder="t('defect.keywordPlaceholder')"
            clearable
            style="width: 200px"
            data-test="defect-filter-keyword"
            @keyup.enter="load(1)"
            @clear="load(1)"
          />
        <div class="filter-bar-actions">
          <el-tooltip
            :content="t('components.ownerEcho.pickInHeader')"
            placement="bottom"
            :disabled="!!projectId"
          >
            <span>
              <el-button type="primary" :icon="Plus" :disabled="!projectId" data-test="defect-create-btn" @click="openCreate">
                {{ t('defect.create') }}
              </el-button>
            </span>
          </el-tooltip>
   
          <el-dropdown v-if="projectId" trigger="click" data-test="defect-export-dropdown" @command="onExport">
            <el-button :icon="Download" :loading="exporting" data-test="defect-export-btn">
              {{ t('defect.export') }}<el-icon class="el-icon--right"><ArrowDown /></el-icon>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="ids" :disabled="selection.length === 0" data-test="defect-export-ids">
                  {{ t('defect.exportIds', { n: selection.length }) }}
                </el-dropdown-item>
                <el-dropdown-item command="filter" data-test="defect-export-filter">{{ t('defect.exportFilter') }}</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <!-- Column show/hide, order, pinning, density and reset ride the shared
               useColumnConfig piece; the control sits in the filter-bar trailing
               slot (view-state actions never live in a header, R21). -->
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

      <div v-if="!loading && projects.length === 0" class="empty-box" data-test="defect-empty-no-project">
        <el-empty :description="t('defect.noProject')">
          <el-button type="primary" plain data-test="defect-empty-no-project-go" @click="router.push('/projects')">{{ t('case.emptyGoProject') }}</el-button>
        </el-empty>
      </div>

      <TableSkeleton v-else-if="loading" :cols="11" data-test="defectlistview-skeleton" />
      <DataTablePage
        v-else
        :data="rows"
        data-test="defect-table"
        class="data-table"
        row-key="id"
        :size="colCfg.state.density"
        :column-config="colCfg"
        @selection-change="onSelectionChange"
        @sort-change="onSortChange"
      >
        <el-table-column type="selection" width="42" />
        <el-table-column
          v-for="col in colCfg.visibleColumns.value"
          :key="col.key"
          :prop="col.key === 'jiraSync' ? undefined : col.key"
          :fixed="col.fixed || false"
          :label="t(col.labelKey)"
          :width="col.width ?? undefined"
          :min-width="col.width ? undefined : DEFECT_FLEX_MIN[col.key]"
          :sortable="DEFECT_SORTABLE.includes(col.key) ? 'custom' : false"
          :show-overflow-tooltip="col.key === 'title'"
          :data-test="`defect-col-${col.key}`"
        >
          <template #default="{ row }">
            <template v-if="col.key === 'title'">
              <el-link type="primary" data-test="defect-title-link" @click="openDetail(row.id)">
                {{ row.title }}
              </el-link>
              <el-tag v-if="row.source === 2" size="small" type="warning" class="auto-defect-tag"
                data-test="defect-auto-tag">{{ t('defect.autoCreated') }}</el-tag>
            </template>
            <el-tag v-else-if="col.key === 'severity'" :type="severityTag(row.severity)" size="small" data-test="defect-severity-tag">
              {{ DEFECT_SEVERITY_LABEL[row.severity] || row.severity }}
            </el-tag>
            <el-tag v-else-if="col.key === 'status'" :type="DEFECT_STATUS_TAG[row.status]" size="small" data-test="defect-status-tag">
              {{ DEFECT_STATUS_LABEL[row.status] || row.status }}
            </el-tag>
            <el-tag v-else-if="col.key === 'jiraSync'" :type="jiraSyncTag(row.jiraSyncStatus)" size="small" data-test="defect-jira-sync-tag">
              {{ jiraSyncLabel(row.jiraSyncStatus) }}
            </el-tag>
            <TimeCell v-else-if="col.key === 'createdAt'" :iso="row.createdAt" />
            <span v-else>{{ row[col.key] || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('defect.operation')" width="250" fixed="right">
          <template #default="{ row }">
            <FavoriteButton entityType="defect" :entity-id="row.id" size="small" @change="favSet.apply" />
            <el-button size="small" data-test="defect-detail-btn" @click="openDetail(row.id)">
              {{ t('defect.detail') }}
            </el-button>
            <el-button
              v-if="row.status !== 5"
              size="small"
              type="primary" link
              data-test="defect-edit-btn"
              @click="openEdit(row)"
            >
              {{ t('defect.edit') }}
            </el-button>
            <el-button
              v-if="nextActions(row.status).length > 0"
              size="small"
              type="warning"
              data-test="defect-transition-btn"
              @click="openTransition(row)"
            >
              {{ t('defect.transition') }}
            </el-button>
          </template>
        </el-table-column>
    
        <template #empty>
          <EmptyState
            data-test="defect-empty-create"
            :filtered="emptyCopy.filtered"
            :title="emptyCopy.title"
            :hint="emptyCopy.hint || (hasActiveFilter ? t('common.emptyFilteredHint') : t('defect.emptyCreateHint'))"
            :action-text="t('defect.register')"
            @action="openCreate"
            @clear-filter="clearDefectFilters"
          />
        </template>
      </DataTablePage>

      <div class="pagination-bar">
        <el-pagination v-if="total > 0"
          v-model:current-page="page"
          :page-size="size"
          :total="total"
          layout="total, prev, pager, next"
          data-test="defect-pagination"
          @current-change="load"
            />
      </div>

   
      <el-dialog draggable v-model="createVisible" :title="t('defect.createTitle')" width="640px" :before-close="defectCreateGuard.beforeClose" data-test="defect-create-dialog">
        <!-- ownership echo (iron rule 1): read-only, single-sourced from the
 header project context - the record lands on the visible segment -->
        <ProjectOwnerEcho />
        <el-form ref="formRef" :model="form" :rules="rules" label-width="110px" class="defect-form" scroll-to-error>
          <el-form-item :label="t('defect.title')" prop="title">
            <el-input v-model="form.title" :maxlength="200" data-test="defect-form-title" />
          </el-form-item>
          <el-form-item :label="t('defect.severity')" prop="severity">
            <el-select v-model="form.severity" style="width: 200px" data-test="defect-form-severity">
              <el-option v-for="(label, s) in DEFECT_SEVERITY_LABEL" :key="s" :label="label" :value="Number(s)" />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('defect.assignee')">
            <el-select v-model="form.assigneeId" clearable filterable style="width: 240px" data-test="defect-form-assignee">
              <el-option v-for="m in members" :key="m.userId" :label="m.nickName || m.username" :value="m.userId" />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('defect.case')">
            <el-select v-model="form.caseId" clearable filterable style="width: 320px" data-test="defect-form-case">
              <el-option v-for="c in cases" :key="c.id" :label="c.caseNo + ' ' + c.title" :value="c.id" />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('defect.plan')">
            <el-select v-model="form.planId" clearable filterable style="width: 320px" data-test="defect-form-plan">
              <el-option v-for="p in plans" :key="p.id" :label="p.name" :value="p.id" />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('defect.externalKey')">
            <el-input v-model="form.externalKey" :maxlength="50" :placeholder="t('defect.externalKeyPlaceholder')" data-test="defect-form-external" />
          </el-form-item>
          <el-form-item :label="t('defect.description')">
            <el-input v-model="form.description" type="textarea" :rows="3" data-test="defect-form-desc" />
          </el-form-item>
          <el-form-item :label="t('defect.steps')">
            <el-input v-model="form.stepsJson" type="textarea" :rows="2" :placeholder="t('defect.stepsPlaceholder')" data-test="defect-form-steps" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button data-test="defect-form-cancel" @click="createVisible = false">{{ t('defect.cancel') }}</el-button>
          <el-button type="primary" :loading="saving" data-test="defect-form-submit" @click="submitCreate">
            {{ t('defect.confirmCreate') }}
          </el-button>
        </template>
      </el-dialog>

   
      <el-dialog draggable v-model="editVisible" :title="t('defect.editTitle')" width="640px" :before-close="defectEditGuard.beforeClose" data-test="defect-edit-dialog">
        <el-form ref="editFormRef" :model="editForm" :rules="rules" label-width="110px" class="defect-form" scroll-to-error>
          <el-form-item :label="t('defect.title')" prop="title">
            <el-input v-model="editForm.title" :maxlength="200" data-test="defect-edit-title" />
          </el-form-item>
          <el-form-item :label="t('defect.severity')" prop="severity">
            <el-select v-model="editForm.severity" style="width: 200px" data-test="defect-edit-severity">
              <el-option v-for="(label, s) in DEFECT_SEVERITY_LABEL" :key="s" :label="label" :value="Number(s)" />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('defect.assignee')">
            <el-select v-model="editForm.assigneeId" clearable filterable style="width: 240px" data-test="defect-edit-assignee">
              <el-option v-for="m in members" :key="m.userId" :label="m.nickName || m.username" :value="m.userId" />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('defect.externalKey')">
            <el-input v-model="editForm.externalKey" :maxlength="50" data-test="defect-edit-external" />
          </el-form-item>
          <el-form-item :label="t('defect.description')">
            <el-input v-model="editForm.description" type="textarea" :rows="3" data-test="defect-edit-desc" />
          </el-form-item>
          <el-form-item :label="t('defect.steps')">
            <el-input v-model="editForm.stepsJson" type="textarea" :rows="2" data-test="defect-edit-steps" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button data-test="defect-edit-cancel" @click="editVisible = false">{{ t('defect.cancel') }}</el-button>
          <el-button type="primary" :loading="saving" data-test="defect-edit-submit" @click="submitEdit">
            {{ t('defect.confirmEdit') }}
          </el-button>
        </template>
      </el-dialog>

      
      <el-dialog draggable v-model="transitionVisible" :title="t('defect.transitionTitle')" width="480px" destroy-on-close>
        <div class="transition-row">
          <span class="label">{{ t('defect.targetStatus') }}：</span>
          <el-select v-model="transitionTarget" style="width: 220px" data-test="defect-transition-select"
            data-se1-exempt="fixed state-machine transition subset, at most 2 target statuses">
            <el-option
              v-for="s in nextActions(current?.status || 1)"
              :key="s"
              :label="DEFECT_STATUS_LABEL[s]"
              :value="s"
            />
          </el-select>
        </div>
        <div v-if="needReason(transitionTarget)" class="transition-row reason-row">
          <span class="label">{{ t('defect.closedReason') }}：</span>
          <el-input v-model="transitionReason" type="textarea" :rows="3" :placeholder="t('defect.closedReasonPlaceholder')" data-test="defect-transition-reason" />
        </div>
        <template #footer>
          <el-button data-test="defect-transition-cancel" @click="transitionVisible = false">{{ t('defect.cancel') }}</el-button>
          <el-button type="primary" :loading="saving" data-test="defect-transition-submit" @click="submitTransition">
            {{ t('defect.confirmTransition') }}
          </el-button>
        </template>
      </el-dialog>

      
      <el-drawer draggable v-model="detailVisible" :title="t('defect.detailTitle')" size="640px" data-test="defect-detail-drawer">
        <template v-if="current">
          <el-descriptions :column="1" border class="defect-desc">
            <el-descriptions-item :label="t('defect.title')">
              <span data-test="defect-detail-title">{{ current.title }}</span>
            </el-descriptions-item>
            <el-descriptions-item :label="t('defect.severity')">
              <el-tag :type="severityTag(current.severity)" size="small">{{ DEFECT_SEVERITY_LABEL[current.severity] }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item :label="t('defect.status')">
              <el-tag :type="DEFECT_STATUS_TAG[current.status]" size="small" data-test="defect-detail-status">
                {{ DEFECT_STATUS_LABEL[current.status] }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item :label="t('defect.assignee')">{{ current.assigneeName || '-' }}</el-descriptions-item>
            <el-descriptions-item :label="t('defect.reporter')">{{ current.reporterName || '-' }}</el-descriptions-item>
            <el-descriptions-item :label="t('defect.case')">{{ current.caseId || '-' }}</el-descriptions-item>
            <el-descriptions-item :label="t('defect.plan')">{{ current.planId || '-' }}</el-descriptions-item>
            <el-descriptions-item :label="t('defect.externalKey')">{{ current.externalKey || '-' }}</el-descriptions-item>
            <el-descriptions-item :label="t('defect.source')">
              <el-tag v-if="current.source === 2" size="small" type="warning" data-test="defect-detail-auto-tag">
                {{ t('defect.autoCreated') }}
              </el-tag>
              <span v-else>{{ t('defect.manualCreated') }}</span>
            </el-descriptions-item>
            <el-descriptions-item :label="t('defect.jiraSync')">
              <el-tooltip v-if="current.jiraSyncStatus === 2 && current.jiraSyncError"
                :content="current.jiraSyncError" placement="top">
                <el-tag size="small" :type="jiraSyncTag(current.jiraSyncStatus)" data-test="defect-detail-jira-tag">
                  {{ jiraSyncLabel(current.jiraSyncStatus) }}
                </el-tag>
              </el-tooltip>
              <el-tag v-else size="small" :type="jiraSyncTag(current.jiraSyncStatus)" data-test="defect-detail-jira-tag">
                {{ jiraSyncLabel(current.jiraSyncStatus) }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item :label="t('defect.closedReason')">{{ current.closedReason || '-' }}</el-descriptions-item>
            <el-descriptions-item :label="t('defect.description')">{{ current.description || '-' }}</el-descriptions-item>
            <el-descriptions-item :label="t('defect.steps')">
              <pre class="steps-pre">{{ current.stepsJson || '-' }}</pre>
            </el-descriptions-item>
            <el-descriptions-item :label="t('defect.createdAt')">{{ current.createdAt }}</el-descriptions-item>
            <el-descriptions-item :label="t('defect.updatedAt')">{{ current.updatedAt }}</el-descriptions-item>
          </el-descriptions>
          <div class="drawer-actions">
            <el-button
              v-for="s in nextActions(current.status)"
              :key="s"
              type="warning"
              data-test="defect-drawer-transition"
              @click="openTransition(current)"
            >
              {{ t('defect.toStatus', { label: DEFECT_STATUS_LABEL[s] }) }}
            </el-button>
          </div>
          <CommentPanel host-type="defect" :host-id="current?.id ?? null" />
        </template>
      </el-drawer>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useDraftGuard } from '@/composables/useDraftGuard'
import { useKeepAliveList } from '@/composables/useKeepAliveList'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import ColumnSettings from '@/components/ColumnSettings.vue'
import DataTablePage from '@/components/DataTablePage.vue'
import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'
import ProjectOwnerEcho from '@/components/ProjectOwnerEcho.vue'
import { ArrowDown, Download, Plus } from '@element-plus/icons-vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { hasQueryScope, lensEmptyCopy, listProjectScope, programIdParam, watchProgramLens } from '@/composables/useProgramLens'
import CommentPanel from '@/components/CommentPanel.vue'
import { getProjectMembers, type ProjectMember } from '@/api/project'
import { listCases } from '@/api/case'
import { listPlans } from '@/api/plan'
import { useObjectSync } from '@/composables/useObjectSync'

// : a landed defect write silently re-reads this list as-is —
// load(p = page.value) defaults to the current page (iron rule 2).
useObjectSync({
  watch: ['DEFECT_WRITE'],
  onSync: () => void load(),
})
import { bizErrorMsg } from '@/utils/bizError'
import { downloadBase64Xlsx } from '@/utils/exportDownload'
import {
  DEFECT_SEVERITY_LABEL,
  DEFECT_STATUS_LABEL,
  DEFECT_STATUS_TAG,
  createDefect,
  exportDefects,
  listDefectLibrary,
  transitionDefect,
  updateDefect,
  type DefectLibraryView,
  type DefectTransitionPayload,
} from '@/api/defectLibrary'
import FavoriteButton from '@/components/FavoriteButton.vue'
import { useFavoriteSet } from '@/composables/useFavoriteSet'
import { recordVisit } from '@/composables/useRecentVisits'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
// MP4-b header program lens (R30 set-dimension projection): the page
// reads the single source and NEVER owns a second program picker; the param
// and flip-watch fragments are single sourced in useProgramLens (R40).

/** Column defs of the defect library table (locked selection/row-actions
 *  columns stay out of the user-configurable set). */
const DEFECT_COLUMNS: ColumnDef[] = [
  { key: 'title', labelKey: 'defect.title', width: null },
  { key: 'severity', labelKey: 'defect.severity', width: 110 },
  { key: 'status', labelKey: 'defect.status', width: 100 },
  { key: 'assigneeName', labelKey: 'defect.assignee', width: 110 },
  { key: 'reporterName', labelKey: 'defect.reporter', width: 110 },
  { key: 'externalKey', labelKey: 'defect.externalKey', width: 160 },
  { key: 'jiraSync', labelKey: 'defect.jiraSync', width: 110 },
  { key: 'createdAt', labelKey: 'defect.createdAt', width: 160 },
]
/** Flexible columns fall back to min-width when no explicit width is set. */
const DEFECT_FLEX_MIN: Record<string, number> = { title: 200 }
/** Header-sortable columns: same set as the server-side SORT_KEY_MAP whitelist. */
const DEFECT_SORTABLE = ['title', 'severity', 'status', 'createdAt']
const colCfg = useColumnConfig('defect', DEFECT_COLUMNS)
/** Popover rows (column state merged with labelKey for rendering). */
const settingsRows = computed(() =>
  colCfg.state.columns.map((c) => ({ ...c, labelKey: DEFECT_COLUMNS.find((d) => d.key === c.key)!.labelKey })),
)

defineOptions({ name: 'DefectLibrary' })

const projects = computed(() => auth.projects)
const { projectId, followTick } = useProjectPageContext()
const rows = ref<DefectLibraryView[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const members = ref<ProjectMember[]>([])
const cases = ref<{ id: number; caseNo: string; title: string }[]>([])
const plans = ref<{ id: number; name: string }[]>([])
const saving = ref(false)

const filters = reactive<{ status: number | null; severity: number | null; keyword: string }>({
  status: null,
  severity: null,
  keyword: '',
})

// Column prop -> core SORT_COLUMNS whitelist key.
// Single source for the sortable-column decision and the request payload —
// columns outside the whitelist never emit sort params (no client-only fake sort).
const SORT_KEY_MAP: Record<string, string> = {
  title: 'title',
  severity: 'severity',
  status: 'status',
  createdAt: 'created_at',
}
const query = reactive<{ sortBy: string; sortDir: string }>({ sortBy: '', sortDir: '' })

const selection = ref<DefectLibraryView[]>([])
const exporting = ref(false)

function onSelectionChange(rowsSel: DefectLibraryView[]): void {
  selection.value = rowsSel
}


async function onExport(mode: 'ids' | 'filter'): Promise<void> {
  if (!projectId.value || exporting.value) return
  if (mode === 'ids' && selection.value.length === 0) {
    ElMessage.warning(t('defect.exportNoSelection'))
    return
  }
  exporting.value = true
  try {
    const result = await exportDefects(mode === 'ids'
      ? { projectId: projectId.value, ids: selection.value.map((r) => r.id) }
      : {
          projectId: projectId.value,
          status: filters.status,
          severity: filters.severity,
          keyword: filters.keyword || undefined,
        })
    if (result.async) {
      ElMessage.success(t('defect.exportAsyncStarted', { n: result.total }))
    } else if (result.content) {
      downloadBase64Xlsx(result.content, result.fileName || t('defect.exportFile'))
      ElMessage.success(result.removed > 0
        ? t('defect.exportDoneRemoved', { total: result.total, removed: result.removed })
        : t('defect.exportDone', { n: result.total }))
    }
  } catch (e: unknown) {
    ElMessage.error(bizErrorMsg(e, t('defect.exportFailed')))
  } finally {
    exporting.value = false
  }
}

const createVisible = ref(false)
const editVisible = ref(false)
const transitionVisible = ref(false)
const detailVisible = ref(false)
const current = ref<DefectLibraryView | null>(null)
const transitionTarget = ref<number>(2)
const transitionReason = ref('')


const favSet = useFavoriteSet('defect')

const form = reactive<{
  title: string
  severity: number
  assigneeId: number | null
  caseId: number | null
  planId: number | null
  externalKey: string
  description: string
  stepsJson: string
}>({ title: '', severity: 2, assigneeId: null, caseId: null, planId: null, externalKey: '', description: '', stepsJson: '' })

const editForm = reactive<{
  title: string
  severity: number
  assigneeId: number | null
  externalKey: string
  description: string
  stepsJson: string
}>({ title: '', severity: 2, assigneeId: null, externalKey: '', description: '', stepsJson: '' })

const formRef = ref<FormInstance>()
const editFormRef = ref<FormInstance>()


const defectCreateGuard = useDraftGuard(form as unknown as Record<string, unknown>, () => {
  Object.assign(form, { title: '', severity: 2, assigneeId: null, caseId: null, planId: null, externalKey: '', description: '', stepsJson: '' })
})
const defectEditGuard = useDraftGuard(editForm as unknown as Record<string, unknown>, () => {
  Object.assign(editForm, { title: '', severity: 2, assigneeId: null, externalKey: '', description: '', stepsJson: '' })
})
const rules: FormRules = {
  title: [{ required: true, message: t('defect.titleRequired'), trigger: 'blur' }],
  severity: [{ required: true, message: t('defect.severityRequired'), trigger: 'change' }],
}


function nextActions(status: number): number[] {
  const map: Record<number, number[]> = { 1: [2], 2: [3], 3: [4], 4: [5, 6], 5: [], 6: [2] }
  return map[status] || []
}

function needReason(to: number): boolean {
  return to === 5 || to === 6
}

function severityTag(severity: number): 'danger' | 'warning' | 'primary' | 'info' {
  return severity === 1 ? 'danger' : severity === 2 ? 'warning' : severity === 3 ? 'primary' : 'info'
}


function jiraSyncTag(status: number): 'info' | 'success' | 'danger' {
  return status === 1 ? 'success' : status === 2 ? 'danger' : 'info'
}

function jiraSyncLabel(status: number): string {
  return status === 1 ? t('defect.jiraSyncSuccess') : status === 2 ? t('defect.jiraSyncFailed') : t('defect.jiraSyncNone')
}

async function load(p = page.value): Promise<void> {
  const scope = listProjectScope(projectId.value)
  if (scope == null) return
  page.value = p
  loading.value = true
  try {
    const res = await listDefectLibrary({
      projectId: scope,
      status: filters.status,
      severity: filters.severity,
      keyword: filters.keyword || null,
      page: page.value,
      size: size.value,
      sortBy: query.sortBy || undefined,
      sortDir: query.sortDir || undefined,
      // Lens on → programId conjuncts the explicit projectId server-side;
      // lens off → the key is dropped entirely (MP3b-2 lens contracts).
      programId: programIdParam(),
    })
    rows.value = res.list || []
    total.value = res.total || 0
  } catch {
    rows.value = []
    total.value = 0
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

async function loadOptions(): Promise<void> {
  if (!projectId.value) return
  members.value = (await getProjectMembers(projectId.value)) as ProjectMember[]
  const caseRes = await listCases({ projectId: projectId.value, page: 1, size: 50 })
  cases.value = (caseRes.list || []).map((c) => ({ id: c.id, caseNo: c.caseNo || String(c.id), title: c.title }))
  const planRes = await listPlans({ projectId: projectId.value, page: 1, size: 50 })
  plans.value = (planRes.list || []).map((p) => ({ id: p.id, name: p.name }))
}


function openCreate(): void {
  createVisible.value = true
  defectCreateGuard.snapshot()
}

async function submitCreate(): Promise<void> {
  if (!projectId.value || !formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    await createDefect({
      projectId: projectId.value,
      title: form.title,
      severity: form.severity,
      assigneeId: form.assigneeId,
      caseId: form.caseId,
      planId: form.planId,
      externalKey: form.externalKey || null,
      description: form.description || null,
      stepsJson: form.stepsJson || null,
    })
    ElMessage.success(t('defect.createOk'))
    createVisible.value = false
    defectCreateGuard.discard()
    load(1)
  } catch (e: unknown) {
    ElMessage.error((e as { msg?: string })?.msg || t('defect.createFail'))
  } finally {
    saving.value = false
  }
}

async function openEdit(row: DefectLibraryView): Promise<void> {
  const detail = row.id ? await getDetail(row.id) : row
  Object.assign(editForm, {
    title: detail.title,
    severity: detail.severity,
    assigneeId: detail.assigneeId,
    externalKey: detail.externalKey || '',
    description: detail.description || '',
    stepsJson: detail.stepsJson || '',
  })
  current.value = detail
  editVisible.value = true
  defectEditGuard.snapshot()
}

async function submitEdit(): Promise<void> {
  if (!editFormRef.value || !current.value) return
  const valid = await editFormRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    await updateDefect(current.value.id, {
      title: editForm.title,
      severity: editForm.severity,
      assigneeId: editForm.assigneeId,
      externalKey: editForm.externalKey || null,
      description: editForm.description || null,
      stepsJson: editForm.stepsJson || null,
      version: current.value.version,
    })
    ElMessage.success(t('defect.editOk'))
    editVisible.value = false
    defectEditGuard.discard()
    load()
  } catch (e: unknown) {
    ElMessage.error((e as { msg?: string })?.msg || t('defect.editFail'))
  } finally {
    saving.value = false
  }
}

async function getDetail(id: number): Promise<DefectLibraryView> {
  const { getDefect } = await import('@/api/defectLibrary')
  return getDefect(id)
}

function openDetail(id: number): void {
  void getDetail(id).then((d) => {
    current.value = d
    detailVisible.value = true
    if (d) recordVisit('defect', id, d.title)
  })
}

function openTransition(row: DefectLibraryView): void {
  current.value = row
  transitionTarget.value = nextActions(row.status)[0] || 2
  transitionReason.value = ''
  transitionVisible.value = true
}

async function submitTransition(): Promise<void> {
  if (!current.value) return
  if (needReason(transitionTarget.value) && !transitionReason.value.trim()) {
    ElMessage.warning(t('defect.closedReasonRequired'))
    return
  }
  saving.value = true
  try {
    const payload: DefectTransitionPayload = {
      toStatus: transitionTarget.value,
      version: current.value.version,
    }
    if (needReason(transitionTarget.value)) {
      payload.closedReason = transitionReason.value.trim()
    }
    await transitionDefect(current.value.id, payload)
    ElMessage.success(t('defect.transitionOk'))
    transitionVisible.value = false
    detailVisible.value = false
    load()
  } catch (e: unknown) {
    ElMessage.error((e as { msg?: string })?.msg || t('defect.transitionFail'))
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  if (typeof route.query.keyword === 'string' && route.query.keyword) {
    filters.keyword = route.query.keyword
  }
  if (projectId.value) await loadOptions()
  // A lens-only landing (deep link with the project segment cleared) still
  // resolves the program-wide anchor, so the list fetch uses the shared
  // scope predicate instead of a bare project test (R40 single source).
  if (hasQueryScope(projectId.value)) await load(1)
  void favSet.reload()
})

watch(followTick, () => {
  if (!hasQueryScope(projectId.value)) return
  if (projectId.value) void Promise.all([loadOptions(), favSet.reload()])
  void load(1)
})

// Header lens flip (pick / switch / detach) → refetch the visible segment from
// page 1, same posture as the project-header followTick reload above.
watchProgramLens(() => void load(1), () => hasQueryScope(projectId.value))

useKeepAliveList(async () => {
  if (typeof route.query.keyword === 'string' && route.query.keyword) {
    filters.keyword = route.query.keyword
  }
  if (!hasQueryScope(projectId.value)) return
  if (projectId.value) await Promise.all([loadOptions(), load(), favSet.reload()])
  else await load()
})

const hasActiveFilter = computed(() => filters.status !== null || filters.severity !== null || filters.keyword.trim() !== '')

// Empty-state copy : the three branches live once in useProgramLens.
const emptyCopy = computed(() =>
  lensEmptyCopy({ translate: t, titleKey: 'defect.lensEmptyTitle', emptyKey: 'common.emptyNoData', filtered: hasActiveFilter.value }),
)
function clearDefectFilters(): void { filters.status = null; filters.severity = null; filters.keyword = ''; load(1) }
</script>

<style scoped>
.defect-form .el-form-item {
  margin-bottom: 16px;
}
.transition-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 12px;
}
.transition-row .label {
  width: 90px;
  flex-shrink: 0;
  line-height: 32px;
  color: var(--el-text-color-secondary);
}
.reason-row .label {
  line-height: 22px;
}
.steps-pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 13px;
  color: var(--el-text-color-regular);
}
.drawer-actions {
  margin-top: 16px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
</style>
