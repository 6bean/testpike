<template>
  <div class="page-root">
    <div class="page-card">
      <div class="toolbar">
        <div class="toolbar-right">
          <el-input
            v-model="keyword"
            :placeholder="t('project.keywordPlaceholder')"
            clearable
            style="width: 240px"
            :prefix-icon="Search"
            @keyup.enter="load(1)"
            @clear="load(1)"
            data-test="project-search"
          />
          <el-button type="primary" data-test="project-create" @click="openCreate">
            <el-icon><Plus /></el-icon>{{ t('project.create') }}
          </el-button>
 
          <el-button v-if="auth.isAdmin" :icon="Box" data-test="project-archived-open" @click="archivedVisible = true">
            {{ t('project.archiveViewBtn') }}
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

      <TableSkeleton v-if="loading" :cols="colCfg.visibleColumns.value.length + 1" data-test="projectlistview-skeleton" />
      <!-- Main project table sorts server-side (core whitelist: id/name/code/
           created_at). The archived-projects dialog talks to a different
           endpoint (GET /projects/archived), so it stays honestly unsorted. -->
      <el-table border :data="list" data-test="project-table" :size="colCfg.state.density" @sort-change="onSortChange" v-else>
        <template #empty>
          <EmptyState
            :filtered="hasActiveFilter"
            :title="hasActiveFilter ? t('project.emptyFiltered') : t('project.empty')"
            :hint="hasActiveFilter ? t('project.emptyFilteredSearchHint') : t('project.emptyGuide')"
            :action-text="t('project.create')"
            @action="openCreate"
            @clear-filter="clearProjectSearch"
          />
        </template>
        <el-table-column
          v-for="col in colCfg.visibleColumns.value"
          :key="col.key"
          :prop="col.key"
          :fixed="col.fixed || false"
          :label="t(col.labelKey)"
          :width="col.width ?? undefined"
          :min-width="col.width ? undefined : PROJECT_FLEX_MIN[col.key]"
          :sortable="col.key === 'description' ? false : 'custom'"
          :show-overflow-tooltip="col.key === 'description' || undefined"
          :data-test="`project-col-${col.key}`"
        >
          <template #default="{ row }">
            <template v-if="col.key === 'name'">
              <el-link type="primary" @click="router.push(`/projects/${row.id}`)">{{ row.name }}</el-link>
            </template>
            <template v-else-if="col.key === 'description'">
              <span v-if="row.description">{{ row.description }}</span>
              <span v-else class="text-secondary">—</span>
            </template>
            <template v-else-if="col.key === 'createdAt'">
              <TimeCell :iso="row.createdAt" />
            </template>
            <template v-else>{{ row.code }}</template>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.action')" width="220" fixed="right">
          <template #default="{ row }">
            <FavoriteButton entityType="project" :entity-id="row.id" size="small" @change="favSet.apply" />
            <RowActions :actions="projRowActions()" :max="2" @command="(k: string) => onProjRowCmd(k, row)" />
          </template>
        </el-table-column>
      </el-table>
      <div v-if="total > 0" class="pagination-bar">
        <el-pagination v-if="total > 0"
          v-model:current-page="page"
          :page-size="size"
          :total="total"
          layout="total, prev, pager, next"
          data-test="project-pagination"
          @current-change="load()"
          />
      </div>
    </div>

    
    <el-dialog
      v-model="dialogVisible"
      :title="editing ? t('project.edit') : t('project.create')"
      width="480px"
      destroy-on-close
      data-test="project-dialog"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px" scroll-to-error>
        <el-form-item :label="t('project.name')" prop="name">
          <el-input v-model="form.name" :placeholder="t('project.namePlaceholder')" data-test="project-name-input" />
        </el-form-item>
        <el-form-item :label="t('project.code')" prop="code">
          <el-input v-model="form.code" :placeholder="t('project.codePlaceholder')" :disabled="!!editing" data-test="project-code-input" />
        </el-form-item>
        <el-form-item :label="t('common.remark')" prop="description">
          <el-input v-model="form.description" type="textarea" :rows="3" :placeholder="t('project.descriptionPlaceholder')" />
        </el-form-item>
        
        <template v-if="!editing">
          <el-form-item>
            <template #label>
              <span>{{ t('project.templateLabel') }}</span>
              <el-tooltip :content="t('project.templateTip')" placement="top">
                <el-icon class="kb-gate-tip-icon" :size="14"><QuestionFilled /></el-icon>
              </el-tooltip>
            </template>
            <el-select v-model="form.templateId" clearable filterable style="width: 100%"
              :loading="templatesLoading" data-test="project-template-select"
              popper-class="tp-template-popper"
              :placeholder="t('project.templatePlaceholder')">
              <el-option v-for="tpl in templates" :key="tpl.id"
                :label="tpl.industry ? `${tpl.name}（${tpl.industry}）` : tpl.name" :value="tpl.id" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="selectedTemplate">
            <div class="template-preview" data-test="template-preview">
              <div class="template-preview-title">{{ t('project.templatePreviewTitle') }}</div>
              <div class="template-preview-row" data-test="template-preview-tags">
                <span class="template-preview-key">{{ t('project.templatePreviewTags') }}</span>
                <template v-if="selectedTemplate.content?.tags?.length">
                  <el-tag v-for="tag in selectedTemplate.content.tags" :key="tag.name" size="small" type="info">{{ tag.name }}</el-tag>
                </template>
                <span v-else class="text-secondary">{{ t('project.templatePreviewNone') }}</span>
              </div>
              <div class="template-preview-row" data-test="template-preview-envs">
                <span class="template-preview-key">{{ t('project.templatePreviewEnvs') }}</span>
                <template v-if="selectedTemplate.content?.envs?.length">
                  <el-tag v-for="env in selectedTemplate.content.envs" :key="env.name" size="small">{{ env.name }}</el-tag>
                </template>
                <span v-else class="text-secondary">{{ t('project.templatePreviewNone') }}</span>
              </div>
              <div class="template-preview-row" data-test="template-preview-skills">
                <span class="template-preview-key">{{ t('project.templatePreviewSkills') }}</span>
                <span>{{ selectedTemplate.content?.skillIds?.length
                  ? t('project.templatePreviewCount', { n: selectedTemplate.content.skillIds.length })
                  : t('project.templatePreviewNone') }}</span>
              </div>
              <div class="template-preview-row" data-test="template-preview-roles">
                <span class="template-preview-key">{{ t('project.templatePreviewRoles') }}</span>
                <span v-if="!selectedTemplate.content?.roleHints?.length" class="text-secondary">{{ t('project.templatePreviewNone') }}</span>
                <ul v-else class="template-preview-hints">
                  <li v-for="hint in selectedTemplate.content.roleHints" :key="hint">{{ hint }}</li>
                </ul>
              </div>
              <div class="template-preview-note">{{ t('project.templatePreviewNote') }}</div>
            </div>
          </el-form-item>
        </template>
 
        <template v-if="editing">
          <el-divider content-position="left">{{ t('project.attributesTitle') }}</el-divider>
          <el-form-item :label="t('project.infoJiraKey')" prop="jiraProjectId">
            <el-input v-model="form.jiraProjectId" clearable data-test="project-jira-input"
              :placeholder="t('project.jiraPlaceholder')" />
          </el-form-item>
          <el-form-item :label="t('project.infoTestOwner')" prop="testOwnerId">
            <el-select v-model="form.testOwnerId" clearable filterable style="width: 100%" data-test="project-test-owner-select"
              popper-class="tp-test-owner-popper" :placeholder="t('project.testOwnerPlaceholder')">
              <el-option v-for="m in ownerOptions" :key="`t${m.userId}`" :label="m.label" :value="m.userId" />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('project.infoDevOwner')" prop="devOwnerId">
            <el-select v-model="form.devOwnerId" clearable filterable style="width: 100%" data-test="project-dev-owner-select"
              popper-class="tp-dev-owner-popper" :placeholder="t('project.devOwnerPlaceholder')">
              <el-option v-for="m in ownerOptions" :key="`d${m.userId}`" :label="m.label" :value="m.userId" />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('project.infoScriptPath')" prop="scriptStoragePath">
            <el-input v-model="form.scriptStoragePath" maxlength="300" clearable data-test="project-script-path"
              :placeholder="t('project.scriptPathPlaceholder')" />
          </el-form-item>
          <el-form-item :label="t('project.infoRecordingPath')" prop="recordingStoragePath">
            <el-input v-model="form.recordingStoragePath" maxlength="300" clearable data-test="project-recording-path"
              :placeholder="t('project.recordingPathPlaceholder')" />
          </el-form-item>
          <el-form-item :label="t('project.infoStorageMode')" prop="storageMode">
            <!-- id="" prevents the form label for= from targeting the non-labelable radiogroup div -->
            <el-radio-group id="" v-model="form.storageMode" data-test="project-storage-mode">
              <el-radio-button :value="1">{{ t('project.infoSystemDefault') }}</el-radio-button>
              <el-radio-button :value="2" data-test="project-storage-local">{{ t('project.storageModeLocal') }}</el-radio-button>
              <el-radio-button :value="3" data-test="project-storage-cloud">{{ t('project.storageModeCloud') }}</el-radio-button>
            </el-radio-group>
          </el-form-item>
 
          <el-form-item>
            <template #label>
              <span>{{ t('project.kbGateLabel') }}</span>
              <el-tooltip :content="t('project.kbGateTip')" placement="top">
                <el-icon class="kb-gate-tip-icon" :size="14"><QuestionFilled /></el-icon>
              </el-tooltip>
            </template>
            <el-switch v-model="form.kbEnabled" :active-value="1" :inactive-value="0" data-test="project-kb-gate-switch" />
          </el-form-item>
 
          <el-form-item>
            <template #label>
              <span>{{ t('project.feishuGateLabel') }}</span>
              <el-tooltip :content="t('project.feishuGateTip')" placement="top">
                <el-icon class="kb-gate-tip-icon" :size="14"><QuestionFilled /></el-icon>
              </el-tooltip>
            </template>
            <el-switch v-model="form.notifyFeishu" :active-value="1" :inactive-value="0" data-test="project-feishu-gate-switch" />
          </el-form-item>
 
          <el-form-item>
            <template #label>
              <span>{{ t('project.poolQuotaLabel') }}</span>
              <el-tooltip :content="t('project.poolQuotaTip')" placement="top">
                <el-icon class="kb-gate-tip-icon" :size="14"><QuestionFilled /></el-icon>
              </el-tooltip>
            </template>
            <el-input-number v-model="form.poolQuotaPct" :min="1" :max="100" :value-on-clear="null"
              controls-position="right" data-test="project-pool-quota-input"
              :placeholder="t('project.poolQuotaPlaceholder')" />
          </el-form-item>
 
          <el-form-item>
            <template #label>
              <span>{{ t('project.inspectGateLabel') }}</span>
              <el-tooltip :content="t('project.inspectGateTip')" placement="top">
                <el-icon class="kb-gate-tip-icon" :size="14"><QuestionFilled /></el-icon>
              </el-tooltip>
            </template>
            <el-switch v-model="form.pageInspectEnabled" :active-value="1" :inactive-value="0" data-test="project-inspect-gate-switch" />
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" data-test="project-submit" @click="onSave">{{ t('common.confirm') }}</el-button>
      </template>
    </el-dialog>

 
    <ArchivedProjectDialog v-model="archivedVisible" @restored="load()" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue"
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Box, QuestionFilled, Search } from '@element-plus/icons-vue'
import * as projectApi from '@/api/project'
import type { ProjectTemplate } from '@/api/project'
import { checkProjectDelete } from '@/api/deletecheck'
import { guardedDelete } from '@/utils/deleteGuard'
import { useAuthStore } from '@/stores/auth'
import FavoriteButton from '@/components/FavoriteButton.vue'
import ColumnSettings from '@/components/ColumnSettings.vue'
import { useFavoriteSet } from '@/composables/useFavoriteSet'
import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'
import ArchivedProjectDialog from './ArchivedProjectDialog.vue'
import type { Project } from '@/api/project'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()


const favSet = useFavoriteSet('project')

/** Configurable data columns of the project table (the locked action column stays outside). */
const PROJECT_COLUMNS: ColumnDef[] = [
  { key: 'name', labelKey: 'common.name', width: null },
  { key: 'code', labelKey: 'project.code', width: 140 },
  { key: 'description', labelKey: 'common.remark', width: null },
  { key: 'createdAt', labelKey: 'common.createdAt', width: 170 },
]
/** Flexible columns fall back to min-width when no explicit width is set. */
const PROJECT_FLEX_MIN: Record<string, number> = { name: 200, description: 220 }
const colCfg = useColumnConfig('project', PROJECT_COLUMNS)
/** Popover rows (column state merged with labelKey for rendering). */
const settingsRows = computed(() =>
  colCfg.state.columns.map((c) => ({ ...c, labelKey: PROJECT_COLUMNS.find((d) => d.key === c.key)!.labelKey })),
)

const loading = ref(false)
const saving = ref(false)
const list = ref<Project[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const keyword = ref('')

const dialogVisible = ref(false)

const archivedVisible = ref(false)
const editing = ref<Project | null>(null)
const formRef = ref<FormInstance>()

const form = reactive({
  name: '', code: '', description: '',
  jiraProjectId: '', testOwnerId: undefined as number | undefined,
  devOwnerId: undefined as number | undefined,
  scriptStoragePath: '', recordingStoragePath: '', storageMode: 1,
 
  kbEnabled: 1,
 
  notifyFeishu: 0,
 
  poolQuotaPct: null as number | null,
 
  pageInspectEnabled: 0,
  
  templateId: null as number | null,
})


const templates = ref<ProjectTemplate[]>([])
const templatesLoading = ref(false)
const selectedTemplate = computed<ProjectTemplate | null>(
  () => templates.value.find((tpl) => tpl.id === form.templateId) ?? null)

async function loadTemplates(): Promise<void> {
  templatesLoading.value = true
  try {
    templates.value = await projectApi.getProjectTemplates() || []
  } catch {
    templates.value = []
  } finally {
    templatesLoading.value = false
  }
}


const JIRA_KEY_PATTERN = /^[A-Z][A-Z0-9]{1,9}$/

const rules: FormRules = {
  name: [{ required: true, message: t('project.nameRequired'), trigger: 'blur' }],
  code: [{ required: true, message: t('project.codeRequired'), trigger: 'blur' }],
  jiraProjectId: [{
    validator: (_r, value: string, cb) => {
      const v = (value || '').trim()
      if (v && !JIRA_KEY_PATTERN.test(v)) {
        cb(new Error(t('project.jiraKeyInvalid')))
        return
      }
      cb()
    },
    trigger: 'blur',
  }],
}


const ownerOptions = ref<{ userId: number; label: string }[]>([])

// Column prop -> core project SORT_COLUMNS whitelist key (single source for
// the sortable decision and the request payload; . Sorting
// happens server-side over the whole visible window, not the loaded pageful.
const SORT_KEY_MAP: Record<string, string> = {
  name: 'name',
  code: 'code',
  createdAt: 'created_at',
}
const query = reactive<{ sortBy: string; sortDir: string }>({ sortBy: '', sortDir: '' })

/** Header sort: forward the whitelisted key to core; clearing a sort falls
 * back to the historical created_at DESC default; page resets to 1. */
function onSortChange({ prop, order }: { prop: string; order: string | null }): void {
  page.value = 1
  if (order === null) {
    query.sortBy = ''
    query.sortDir = ''
  } else {
    const key = SORT_KEY_MAP[prop]
    if (key) {
      query.sortBy = key
      query.sortDir = order === 'ascending' ? 'asc' : 'desc'
    }
  }
  void load(1)
}

async function load(p?: number): Promise<void> {
  if (p) page.value = p
  loading.value = true
  try {
    const result = await projectApi.getProjects({
      page: page.value, size: size.value, keyword: keyword.value || undefined, status: 1,
      sortBy: query.sortBy || undefined, sortDir: query.sortDir || undefined,
    })
    list.value = result.list || []
    total.value = result.total || 0
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  editing.value = null
  form.name = ''
  form.code = ''
  form.description = ''
  resetAttributeFields()
  void loadTemplates()
  dialogVisible.value = true
}


function resetAttributeFields(): void {
  form.jiraProjectId = ''
  form.testOwnerId = undefined
  form.devOwnerId = undefined
  form.scriptStoragePath = ''
  form.recordingStoragePath = ''
  form.storageMode = 1
  form.kbEnabled = 1
  form.notifyFeishu = 0
  form.poolQuotaPct = null
  form.pageInspectEnabled = 0
  form.templateId = null
  ownerOptions.value = []
}

function projRowActions() {
  return [
    { key: "manage", label: t('project.manageLabel') },
    { key: "edit", label: t("common.edit") },
    { key: "archive", label: t('project.archive'), type: "warning" as const },
    { key: "purge", label: t('project.purge'), type: "danger" as const },
  ]
}
function onProjRowCmd(key: string, row: Project): void {
  if (key === "manage") router.push(`/projects/${row.id}`)
  else if (key === "edit") openEdit(row)
  else if (key === "archive") void onArchive(row)
  else if (key === "purge") void onPurge(row)
}

function openEdit(row: Project): void {
  editing.value = row
  form.name = row.name
  form.code = row.code
  form.description = row.description || ''
  form.jiraProjectId = row.jiraProjectId || ''
  form.testOwnerId = row.testOwnerId ?? undefined
  form.devOwnerId = row.devOwnerId ?? undefined
  form.scriptStoragePath = row.scriptStoragePath || ''
  form.recordingStoragePath = row.recordingStoragePath || ''
  form.storageMode = row.storageMode ?? 1
  form.kbEnabled = row.kbEnabled ?? 1
  form.notifyFeishu = row.notifyFeishu ?? 0
  form.poolQuotaPct = row.poolQuotaPct ?? null
  form.pageInspectEnabled = row.pageInspectEnabled ?? 0
  void loadOwnerOptions(row.id)
  dialogVisible.value = true
}


async function loadOwnerOptions(projectId: number): Promise<void> {
  try {
    const members = await projectApi.getProjectMembers(projectId)
    ownerOptions.value = (members || []).map((m) => ({
      userId: m.userId,
      label: m.nickName ? `${m.nickName}（${m.username}）` : m.username,
    }))
  } catch {
    ownerOptions.value = []
  }
}


function blankToNull(v: string): string | null {
  const s = v.trim()
  return s === '' ? null : s
}

async function onSave(): Promise<void> {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    if (editing.value) {
      await projectApi.updateProject(editing.value.id, {
        name: form.name,
        jiraProjectId: blankToNull(form.jiraProjectId),
        testOwnerId: form.testOwnerId ?? null,
        devOwnerId: form.devOwnerId ?? null,
        scriptStoragePath: blankToNull(form.scriptStoragePath),
        recordingStoragePath: blankToNull(form.recordingStoragePath),
        storageMode: form.storageMode,
        kbEnabled: form.kbEnabled,
        notifyFeishu: form.notifyFeishu,
        poolQuotaPct: form.poolQuotaPct,
        pageInspectEnabled: form.pageInspectEnabled,
      })
      ElMessage.success(t('project.updateSuccess'))
    } else {
      await projectApi.createProject({
        name: form.name, code: form.code, description: form.description,
        templateId: form.templateId ?? undefined,
      })
      ElMessage.success(t('project.createSuccess'))
    }
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function onArchive(row: Project): Promise<void> {
  await ElMessageBox.confirm(
    t('project.archiveConfirm', { name: row.name }),
    t('common.warning'),
    { confirmButtonText: t('project.archive'), cancelButtonText: t('common.cancel'), type: 'warning' },
  )
  await projectApi.deleteProject(row.id)
  ElMessage.success(t('project.archiveSuccess'))
  await load()
}


async function onPurge(row: Project): Promise<void> {
  const ok = await guardedDelete({
    check: () => checkProjectDelete(row.id),
    doDelete: (force) => projectApi.purgeProject(row.id, force),
    entityName: t('project.entityName', { name: row.name }),
    forceHint: t('project.deleteForceHint'),
  })
  if (ok) {
    ElMessage.success(t('project.purgeSuccess'))
    await load()
  }
}

onMounted(() => {
  load()
  void favSet.reload()
  if (route.query.create === '1') {
    openCreate()
  }
})

const hasActiveFilter = computed(() => keyword.value.trim() !== '')
function clearProjectSearch(): void { keyword.value = ''; load(1) }
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

.toolbar-right {
  display: flex;
  align-items: center;
  gap: 12px;
}


.template-preview {
  width: 100%;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 10px 12px;
  font-size: 13px;
  line-height: 1.6;
}
.template-preview-title {
  font-weight: 600;
  margin-bottom: 4px;
}
.template-preview-row {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 4px;
}
.template-preview-key {
  color: var(--el-text-color-secondary);
  min-width: 5em;
}
.template-preview-hints {
  margin: 0;
  padding-left: 18px;
}
.template-preview-note {
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

</style>
