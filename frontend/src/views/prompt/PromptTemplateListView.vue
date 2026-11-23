<!-- tp-sort-exempt: prompt template families and within-family versions are shipped packs plus a few customs (tens), served in full. -->
<template>
  <div class="page-root" data-test="prompt-tpl-page">
    <div class="page-card">
      <!-- R25 mode declaration (scope-aware single control, TB group): this page
           carries two panes (family enumeration first screen → within-family
           version view). The single page-level column-settings panel follows the
           ACTIVE pane: each pane owns its column set and its own useColumnConfig
           viewKey ('prompt-template-family' / 'prompt-template'), so show/hide,
           order, pinning and density persist per pane through the shared
           useColumnConfig piece — never a settings control that looks page-wide
           but acts on an off-screen table.
           R21 same-band merge (FB group): no row-level batch action bar and two
           screening controls, so the page-level variant action "save new
           version" rides the filter-bar trailing slot (variant first, then
           settings) and the now-empty header band is dropped — the page name
           rides the global top bar per R23. The search submit button stays
           inline at the end of the condition flow. Anchors kept. -->
      <div class="filter-bar" data-test="prompt-tpl-filter-bar">
        <el-input
          v-model="query.name"
          :placeholder="t('promptTpl.namePh')"
          clearable
          style="width: 220px"
          data-test="prompt-tpl-name-input"
          @keyup.enter="search"
        />
        <el-input
          v-model="query.domain"
          :placeholder="t('promptTpl.domainPh')"
          clearable
          style="width: 200px"
          data-test="prompt-tpl-domain-input"
          @keyup.enter="search"
        />
        <el-button data-test="prompt-tpl-search" @click="search">
          <el-icon><Search /></el-icon>{{ t('promptTpl.query') }}
        </el-button>
        <div class="filter-bar-actions">
          <el-button type="primary" data-test="prompt-tpl-new" @click="openSaveDialog">
            <el-icon><Plus /></el-icon>{{ t('promptTpl.newBtn') }}
          </el-button>
          <ColumnSettings
            :columns="settingsRows"
            :density="activeColCfg.state.density"
            @toggle="activeColCfg.toggle"
            @move="activeColCfg.move"
            @density="activeColCfg.setDensity"
            @reset="activeColCfg.reset"
            @pin="activeColCfg.toggleFixed"
          />
        </div>
      </div>

      
      <template v-if="pane === 'family'">
        <TableSkeleton v-if="familyLoading" :cols="famColCfg.visibleColumns.value.length + 1" data-test="prompttpl-familyview-skeleton" />
        <el-table
          v-else
          border
          :data="filteredFamilies"
          :size="famColCfg.state.density"
          :row-key="(f: PromptTemplateFamilyItem) => `${f.domain}\u0000${f.name}`"
          data-test="prompt-tpl-family-table"
        >
          <el-table-column
            v-for="col in famColCfg.visibleColumns.value"
            :key="col.key"
            :prop="col.key"
            :fixed="col.fixed || false"
            :label="t(col.labelKey)"
            :width="col.width ?? undefined"
            :min-width="col.width ? undefined : PROMPT_TEMPLATE_FAMILY_FLEX_MIN[col.key]"
            :align="PROMPT_TEMPLATE_FAMILY_CENTER.includes(col.key) ? 'center' : 'left'"
            :data-test="`prompt-tpl-famcol-${col.key}`"
          >
            <template #default="{ row }">
              <template v-if="col.key === 'latestVersion'">v{{ row.latestVersion }}</template>
              <template v-else-if="col.key === 'latestStatus'">
                <el-tag size="small" :type="statusTagType(row.latestStatus)" data-test="prompt-tpl-fam-status-tag">
                  {{ statusLabel(row.latestStatus) }}
                </el-tag>
              </template>
              <template v-else>{{ row[col.key] }}</template>
            </template>
          </el-table-column>
          <el-table-column :label="t('promptTpl.colAction')" :width="120" align="center">
            <template #default="{ row }">
              <el-button link type="primary" data-test="prompt-tpl-open-family" @click="openFamily(row)">
                {{ t('promptTpl.openFamilyBtn') }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <EmptyState
          v-if="!familyLoading && filteredFamilies.length === 0"
          :title="t('promptTpl.familyEmptyTitle')"
          :hint="t('promptTpl.familyEmptyHint')"
        />
      </template>

      
      <template v-else>
        <div class="family-context-bar" data-test="prompt-tpl-family-context">
          <el-button link type="primary" data-test="prompt-tpl-back-family" @click="backToFamily">
            <el-icon><Back /></el-icon>{{ t('promptTpl.backFamilyBtn') }}
          </el-button>
          <span class="family-context-label">
            {{ t('promptTpl.familyContext', { name: query.name, domain: query.domain }) }}
          </span>
        </div>
        <TableSkeleton v-if="loading" :cols="colCfg.visibleColumns.value.length + 1" data-test="prompttpl-listview-skeleton" />
        <el-table border v-else :data="rows" :size="colCfg.state.density" row-key="id" data-test="prompt-tpl-table">
          <el-table-column
            v-for="col in colCfg.visibleColumns.value"
            :key="col.key"
            :prop="col.key"
            :fixed="col.fixed || false"
            :label="t(col.labelKey)"
            :width="col.width ?? undefined"
            :min-width="col.width ? undefined : PROMPT_TEMPLATE_FLEX_MIN[col.key]"
            :align="PROMPT_TEMPLATE_CENTER.includes(col.key) ? 'center' : 'left'"
            :data-test="`prompt-tpl-col-${col.key}`"
          >
            <template #default="{ row }">
              <template v-if="col.key === 'id'">{{ row.id }}</template>
              <template v-else-if="col.key === 'name'">{{ row.name }}</template>
              <template v-else-if="col.key === 'domain'">{{ row.domain }}</template>
              <template v-else-if="col.key === 'version'">v{{ row.version }}</template>
              <template v-else-if="col.key === 'status'">
                <el-tag size="small" :type="statusTagType(row.status)" data-test="prompt-tpl-status-tag">
                  {{ statusLabel(row.status) }}
                </el-tag>
              </template>
              <template v-else-if="col.key === 'rollbackTo'">{{ row.rollbackTo ?? '—' }}</template>
              <template v-else-if="col.key === 'createdAt'">{{ row.createdAt }}</template>
            </template>
          </el-table-column>
          <el-table-column :label="t('promptTpl.colAction')" width="210" align="center">
            <template #default="{ row }">
              <el-button link type="primary" data-test="prompt-tpl-view" @click="openDetail(row)">
                {{ t('promptTpl.viewBtn') }}
              </el-button>
              <el-button
                v-if="row.status === STATUS_GRAY"
                link
                type="success"
                data-test="prompt-tpl-promote"
                @click="promote(row)"
              >
                {{ t('promptTpl.promoteBtn') }}
              </el-button>
              <el-button link type="warning" data-test="prompt-tpl-rollback" @click="rollback(row)">
                {{ t('promptTpl.rollbackBtn') }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <EmptyState
          v-if="!loading && rows.length === 0"
          :title="t('promptTpl.emptyTitle')"
          :hint="t('promptTpl.emptyHint')"
        />
      </template>
    </div>

    
    <el-drawer
      v-model="detailVisible"
      :title="t('promptTpl.detailTitle', { name: detail?.name ?? '', version: detail?.version ?? '' })"
      size="680px"
      destroy-on-close
      data-test="prompt-tpl-detail-drawer"
    >
      <template v-if="detail">
        <el-descriptions :column="2" border size="small" data-test="prompt-tpl-detail-meta">
          <el-descriptions-item :label="t('promptTpl.colName')">{{ detail.name }}</el-descriptions-item>
          <el-descriptions-item :label="t('promptTpl.colDomain')">{{ detail.domain }}</el-descriptions-item>
          <el-descriptions-item :label="t('promptTpl.colStatus')">
            <el-tag size="small" :type="statusTagType(detail.status)">{{ statusLabel(detail.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item :label="t('promptTpl.colRollbackTo')">{{ detail.rollbackTo ?? '—' }}</el-descriptions-item>
          <el-descriptions-item :label="t('promptTpl.colCreatedBy')">{{ detail.createdBy ?? '—' }}</el-descriptions-item>
          <el-descriptions-item :label="t('promptTpl.colCreatedAt')">{{ detail.createdAt }}</el-descriptions-item>
        </el-descriptions>
        <h4 class="tpl-detail-section">{{ t('promptTpl.contentSection') }}</h4>
        <pre class="tpl-detail-content" data-test="prompt-tpl-detail-content">{{ detail.content }}</pre>
      </template>
    </el-drawer>

    
    <el-dialog
      v-model="saveVisible"
      :title="t('promptTpl.saveTitle')"
      width="720px"
      draggable
      destroy-on-close
      data-test="prompt-tpl-save-dialog"
    >
      <el-form label-width="90px">
        <el-form-item :label="t('promptTpl.colName')" required>
          <el-input
            v-model="saveForm.name"
            :placeholder="t('promptTpl.nameFormPh')"
            data-test="prompt-tpl-save-name"
          />
          <div class="form-hint">{{ t('promptTpl.nameHint') }}</div>
        </el-form-item>
        <el-form-item :label="t('promptTpl.colDomain')" required>
          <el-input
            v-model="saveForm.domain"
            :placeholder="t('promptTpl.domainFormPh')"
            data-test="prompt-tpl-save-domain"
          />
          <div class="form-hint">{{ t('promptTpl.domainHint') }}</div>
        </el-form-item>
        <el-form-item :label="t('promptTpl.contentLabel')" required>
          <el-input
            v-model="saveForm.content"
            type="textarea"
            :rows="12"
            :placeholder="t('promptTpl.contentPh')"
            data-test="prompt-tpl-save-content"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button data-test="prompt-tpl-save-cancel" @click="saveVisible = false">
          {{ t('promptTpl.cancelBtn') }}
        </el-button>
        <el-button
          type="primary"
          :loading="saving"
          data-test="prompt-tpl-save-submit"
          @click="submitSave"
        >
          {{ t('promptTpl.saveSubmitBtn') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">

import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Back, Plus, Search } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import ColumnSettings from '@/components/ColumnSettings.vue'
import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'
import { useObjectSync } from '@/composables/useObjectSync'
import {
  TEMPLATE_STATUS_KEYS,
  listPromptTemplateFamilies,
  listPromptTemplateVersions,
  promotePromptTemplate,
  rollbackPromptTemplate,
  savePromptTemplate,
  type PromptTemplateFamilyItem,
  type PromptTemplateItem,
} from '@/api/promptTemplate'

const { t } = useI18n()


const STATUS_GRAY = 1

// Column-configuration wiring (N-3 repayment, shared useColumnConfig piece):
// every data column of both panes flows through its own visibleColumns loop so
// show/hide, order, pinning and density persist per pane per user (viewKeys
// 'prompt-template' for the version table and 'prompt-template-family' for the
// family enumeration); each table's locked action column is the only slot
// outside the loop, so the skeleton mirrors the visible data count plus one.
// R25: the single ColumnSettings panel is scope-aware — it reads and writes the
// ACTIVE pane's config, never an off-screen table's.
const PROMPT_TEMPLATE_FAMILY_COLUMNS: ColumnDef[] = [
  { key: 'domain', labelKey: 'promptTpl.colDomain', width: 140 },
  { key: 'name', labelKey: 'promptTpl.colName', width: null },
  { key: 'latestVersion', labelKey: 'promptTpl.colLatestVersion', width: 90 },
  { key: 'latestStatus', labelKey: 'promptTpl.colStatus', width: 100 },
  { key: 'versionCount', labelKey: 'promptTpl.colVersionCount', width: 90 },
  { key: 'updatedAt', labelKey: 'promptTpl.colUpdatedAt', width: 170 },
]
// template name is the single flexible column of the family table too
const PROMPT_TEMPLATE_FAMILY_FLEX_MIN: Record<string, number> = { name: 180 }
// composed cells (version badge, status tag, version count) stay centered
const PROMPT_TEMPLATE_FAMILY_CENTER = ['latestVersion', 'latestStatus', 'versionCount']
const famColCfg = useColumnConfig('prompt-template-family', PROMPT_TEMPLATE_FAMILY_COLUMNS)

const PROMPT_TEMPLATE_COLUMNS: ColumnDef[] = [
  { key: 'id', labelKey: 'promptTpl.colId', width: 70 },
  { key: 'name', labelKey: 'promptTpl.colName', width: null },
  { key: 'domain', labelKey: 'promptTpl.colDomain', width: 120 },
  { key: 'version', labelKey: 'promptTpl.colVersion', width: 90 },
  { key: 'status', labelKey: 'promptTpl.colStatus', width: 100 },
  { key: 'rollbackTo', labelKey: 'promptTpl.colRollbackTo', width: 110 },
  { key: 'createdAt', labelKey: 'promptTpl.colCreatedAt', width: 170 },
]
// template name is the single flexible column
const PROMPT_TEMPLATE_FLEX_MIN: Record<string, number> = { name: 160 }
// composed cells (version badge, status tag, rollback source) stay centered
const PROMPT_TEMPLATE_CENTER = ['version', 'status', 'rollbackTo']
const colCfg = useColumnConfig('prompt-template', PROMPT_TEMPLATE_COLUMNS)

const pane = ref<'family' | 'versions'>('family')
// scope-aware settings: the panel always binds the pane the user is looking at
const activeColCfg = computed(() => (pane.value === 'family' ? famColCfg : colCfg))
const settingsRows = computed(() => {
  const cfg = activeColCfg.value
  const defs = pane.value === 'family' ? PROMPT_TEMPLATE_FAMILY_COLUMNS : PROMPT_TEMPLATE_COLUMNS
  return cfg.state.columns.map((c) => ({ ...c, labelKey: defs.find((d) => d.key === c.key)!.labelKey }))
})

const familyLoading = ref(false)
const families = ref<PromptTemplateFamilyItem[]>([])
const loading = ref(false)
const rows = ref<PromptTemplateItem[]>([])
const query = reactive({ name: '', domain: '' })

// live screening of the family enumeration (worst-scale rule: instant search
// over tens of families, no submit round-trip needed)
const filteredFamilies = computed(() => {
  const kwName = query.name.trim().toLowerCase()
  const kwDomain = query.domain.trim().toLowerCase()
  return families.value.filter(
    (f) => (!kwName || f.name.toLowerCase().includes(kwName)) && (!kwDomain || f.domain.toLowerCase().includes(kwDomain)),
  )
})

function statusLabel(v: number): string {
  const key = TEMPLATE_STATUS_KEYS[v]
  return key ? t(key) : String(v)
}

function statusTagType(v: number): 'info' | 'warning' | 'success' {
  if (v === STATUS_GRAY) return 'warning'
  return v === 2 ? 'success' : 'info'
}

async function loadFamilies(): Promise<void> {
  familyLoading.value = true
  try {
    families.value = await listPromptTemplateFamilies()
  } catch (e) {
    ElMessage.error((e as Error).message || t('promptTpl.familyLoadFailed'))
  } finally {
    familyLoading.value = false
  }
}

function openFamily(f: PromptTemplateFamilyItem): void {
  query.name = f.name
  query.domain = f.domain
  void search()
}

function backToFamily(): void {
  pane.value = 'family'
  void loadFamilies()
}

async function search(): Promise<void> {
  const name = query.name.trim()
  const domain = query.domain.trim()
  if (!name || !domain) {
    ElMessage.warning(t('promptTpl.queryNeedBoth'))
    return
  }
  pane.value = 'versions'
  loading.value = true
  try {
    rows.value = await listPromptTemplateVersions(name, domain)
  } catch (e) {
    ElMessage.error((e as Error).message || t('promptTpl.listLoadFailed'))
  } finally {
    loading.value = false
  }
}

// : a landed prompt-template write converges both panes silently —
// family enumeration always re-reads; the version pane re-runs its CURRENT
// name+domain query only when it is on screen (empty query never fires, so
// the landing chain can no more raise the input guard than reload an
// off-screen pane; iron rule 2 = zero toast/confirm/jump).
useObjectSync({
  watch: ['PROMPT_TPL_WRITE'],
  onSync: () => {
    void loadFamilies()
    if (pane.value === 'versions' && query.name.trim() && query.domain.trim()) void search()
  },
})

const detailVisible = ref(false)
const detail = ref<PromptTemplateItem | null>(null)

function openDetail(row: PromptTemplateItem): void {
  detail.value = row
  detailVisible.value = true
}

const saveVisible = ref(false)
const saving = ref(false)
const saveForm = reactive({ name: '', domain: '', content: '' })

function openSaveDialog(): void {
  saveForm.name = query.name.trim()
  saveForm.domain = query.domain.trim()
  saveForm.content = ''
  saveVisible.value = true
}

async function submitSave(): Promise<void> {
  if (!saveForm.name.trim() || !saveForm.domain.trim() || !saveForm.content.trim()) {
    ElMessage.warning(t('promptTpl.saveRequired'))
    return
  }
  saving.value = true
  try {
    const created = await savePromptTemplate({
      name: saveForm.name.trim(),
      domain: saveForm.domain.trim(),
      content: saveForm.content,
    })
    ElMessage.success(t('promptTpl.saved', { name: created.name, version: created.version }))
    saveVisible.value = false
    query.name = created.name
    query.domain = created.domain
    await search()
    void loadFamilies()
  } catch (e) {
    ElMessage.error((e as Error).message || t('promptTpl.saveFailed'))
  } finally {
    saving.value = false
  }
}

async function rollback(row: PromptTemplateItem): Promise<void> {
  try {
    await ElMessageBox.confirm(
      t('promptTpl.rollbackConfirm', { name: row.name, version: row.version }),
      t('promptTpl.rollbackBtn'),
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    const created = await rollbackPromptTemplate(row.id)
    ElMessage.success(t('promptTpl.rolledBack', { version: created.version }))
    await search()
    void loadFamilies()
  } catch (e) {
    ElMessage.error((e as Error).message || t('promptTpl.rollbackFailed'))
  }
}

async function promote(row: PromptTemplateItem): Promise<void> {
  try {
    await ElMessageBox.confirm(
      t('promptTpl.promoteConfirm', { name: row.name, version: row.version }),
      t('promptTpl.promoteBtn'),
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await promotePromptTemplate(row.id)
    ElMessage.success(t('promptTpl.promoted'))
    await search()
    void loadFamilies()
  } catch (e) {
    ElMessage.error((e as Error).message || t('promptTpl.promoteFailed'))
  }
}

onMounted(() => {
  // first screen = live family enumeration ; no hardcoded seed family
  void loadFamilies()
})
</script>

<style scoped>
.family-context-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}
.family-context-label {
  font-size: 13px;
  color: var(--el-text-color-regular);
}
.tpl-detail-section {
  margin: 16px 0 8px;
  font-size: 14px;
}
.tpl-detail-content {
  margin: 0;
  padding: 10px 12px;
  background: var(--tp-bg-elevated, rgba(125, 125, 125, 0.08));
  border: 1px solid var(--tp-border-color, var(--el-border-color));
  border-radius: 6px;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 13px;
  line-height: 1.6;
  max-height: 480px;
  overflow: auto;
}
.form-hint {
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}
</style>
