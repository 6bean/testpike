<!-- tp-sort-exempt: element profiles are served newest-first (creation order is the
     review order for freshly captured locators); column sorting needs a server-side
     sort parameter that is registered as its own follow-up micro-package. -->
<template>
  <div class="page-root element-library-page" data-test="element-library-page">
    <div class="page-card">
      <!-- R21 bands + FB-3 same-band merge: exactly two condition controls and no
           row-level batch bar, so the page-level variant action (create) rides the
           trailing slot of the filter bar ahead of the view-state actions, and the
           emptied header row is removed as a shell (R23: the page name rides the
           global top bar). The query button stays inline at the end of the
           condition flow (R21 second amendment). Create keeps its ownership gate:
           tooltip-wrapped, disabled while no project context. -->
      <div class="filter-bar" data-test="element-filter-bar">
        <el-input
          v-model="keyword"
          class="cond-keyword"
          clearable
          :placeholder="t('appauto.elementLibrary.searchPlaceholder')"
          data-test="element-keyword"
          @keyup.enter="search"
          @clear="search"
        />
        <el-select
          v-model="locatorType"
          class="cond-type"
          clearable
          filterable
          :placeholder="t('appauto.elementLibrary.filterTypePlaceholder')"
          data-test="element-type-filter"
          @change="search"
        >
          <el-option v-for="lt in ELEMENT_LOCATOR_TYPES" :key="lt" :label="lt" :value="lt" />
        </el-select>
        <el-button type="primary" :icon="Search" :loading="loading" data-test="element-query-btn" @click="search">
          {{ t('appauto.elementLibrary.query') }}
        </el-button>
        <el-button :icon="RefreshLeft" data-test="element-reset-btn" @click="resetFilters">
          {{ t('appauto.elementLibrary.reset') }}
        </el-button>
        <div class="filter-bar-actions">
          <el-tooltip :content="t('components.ownerEcho.pickInHeader')" placement="bottom" :disabled="!!projectId">
            <span>
              <el-button
                type="primary"
                :icon="Plus"
                :disabled="!projectId"
                data-test="element-create-btn"
                @click="openCreate"
              >
                {{ t('appauto.elementLibrary.create') }}
              </el-button>
            </span>
          </el-tooltip>
          <!-- R25 (TB group): no tabs and exactly one table, so the page-level
               column-settings popover equals the table scope; state persists per
               user under tp-colcfg-element-library. -->
          <ColumnSettings
            :columns="settingsRows"
            :density="colCfg.state.density"
            @toggle="colCfg.toggle"
            @move="colCfg.move"
            @density="colCfg.setDensity"
            @reset="colCfg.reset"
            @pin="colCfg.toggleFixed"
          />
          <el-button :icon="Refresh" :loading="loading" data-test="element-refresh-btn" @click="load">
            {{ t('appauto.elementLibrary.refresh') }}
          </el-button>
        </div>
      </div>

      <el-alert type="info" show-icon :closable="false" class="usage-alert" data-test="element-usage-alert">
        <template #default>{{ t('appauto.elementLibrary.usageHint') }}</template>
      </el-alert>

      <div v-if="!loading && !projectId" class="empty-box" data-test="element-empty-no-project">
        <EmptyState
          :title="t('appauto.elementLibrary.noProjectTitle')"
          :hint="t('appauto.elementLibrary.noProjectHint')"
        />
      </div>

      <TableSkeleton v-else-if="loading" :cols="colCfg.visibleColumns.value.length + 1" data-test="element-skeleton" />
      <el-table v-else :data="rows" border row-key="id" :size="colCfg.state.density" class="data-table" data-test="element-table">
        <template #empty>
          <EmptyState :title="t('appauto.elementLibrary.emptyTitle')" :hint="t('appauto.elementLibrary.emptyHint')" />
        </template>
        <el-table-column
          v-for="col in colCfg.visibleColumns.value"
          :key="col.key"
          :prop="col.key"
          :fixed="col.fixed || false"
          :label="t(col.labelKey)"
          :width="col.width ?? undefined"
          :min-width="col.width ? undefined : ELEMENT_FLEX_MIN[col.key]"
          :show-overflow-tooltip="ELEMENT_TOOLTIP.has(col.key)"
          :data-test="`element-col-${col.key}`"
        >
          <template #default="{ row }">
            <template v-if="col.key === 'name'">
              <span data-test="element-name">{{ row.name }}</span>
            </template>
            <template v-else-if="col.key === 'locatorType'">
              <el-tag size="small" data-test="element-type">{{ row.locatorType }}</el-tag>
              <el-tooltip v-if="row.locatorConfig" :content="row.locatorConfig" placement="top">
                <span class="config-hint" data-test="element-config-hint">{{ t('appauto.elementLibrary.colConfig') }}</span>
              </el-tooltip>
            </template>
            <template v-else-if="col.key === 'confidence'">
              <span data-test="element-confidence">{{ row.confidenceThreshold ?? '-' }}</span>
            </template>
            <template v-else-if="col.key === 'variants'">
              <span data-test="element-variants">{{ variantCount(row) }}</span>
            </template>
            <template v-else-if="col.key === 'updated'"><TimeCell :iso="row.updatedAt" /></template>
          </template>
        </el-table-column>
        <el-table-column :label="t('appauto.elementLibrary.colActions')" width="170" fixed="right">
          <template #default="{ row }">
            <el-button size="small" link :icon="EditPen" data-test="element-edit-btn" @click="openEdit(row)">
              {{ t('common.edit') }}
            </el-button>
            <el-button size="small" link type="danger" :icon="Delete" data-test="element-delete-btn" @click="remove(row)">
              {{ t('common.delete') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- Server-side paging per the display-scale iron rule (50~200 rows stay reachable) -->
      <div v-if="total > 0" class="pagination-bar" data-test="element-pagination-bar">
        <el-pagination
          layout="total, sizes, prev, pager, next"
          :total="total"
          :current-page="page"
          :page-size="pageSize"
          :page-sizes="[20, 50, 100]"
          data-test="element-pagination"
          @current-change="onPageChange"
          @size-change="onSizeChange"
        />
      </div>

      <!-- Create/edit dialog. Locator resolution itself is executor-side
           (element_candidates.py single source): this form only stores the
           profile; every semantic check (value set, threshold range, unique
           name) runs in core and its rejection text travels back unmutated. -->
      <el-dialog
        v-model="dialogVisible"
        :title="editing ? t('appauto.elementLibrary.editTitle') : t('appauto.elementLibrary.createTitle')"
        width="560px"
        data-test="element-form-dialog"
      >
        <!-- ownership echo (iron rule): read-only, single-sourced from the
 header project context — the record lands on the visible segment -->
        <ProjectOwnerEcho v-if="!editing" />
        <el-form :model="form" label-position="top">
          <el-form-item :label="t('appauto.elementLibrary.formName')" required>
            <el-input
              v-model="form.name"
              maxlength="128"
              :placeholder="t('appauto.elementLibrary.formNamePlaceholder')"
              data-test="element-form-name"
            />
          </el-form-item>
          <el-form-item :label="t('appauto.elementLibrary.formLocatorType')" required>
            <el-select v-model="form.locatorType" style="width: 100%" filterable data-test="element-form-type">
              <el-option v-for="lt in ELEMENT_LOCATOR_TYPES" :key="lt" :label="lt" :value="lt" />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('appauto.elementLibrary.formLocatorConfig')" required>
            <el-input
              v-model="form.locatorConfigText"
              type="textarea"
              :rows="3"
              data-test="element-form-config"
            />
            <div class="form-hint">{{ t('appauto.elementLibrary.formLocatorConfigHint') }}</div>
          </el-form-item>
          <el-form-item :label="t('appauto.elementLibrary.formConfidence')">
            <el-input-number
              v-model="form.confidenceThreshold"
              :min="0"
              :max="1"
              :step="0.05"
              :precision="2"
              controls-position="right"
              data-test="element-form-confidence"
            />
            <div class="form-hint">{{ t('appauto.elementLibrary.formConfidenceHint') }}</div>
          </el-form-item>
          <el-form-item :label="t('appauto.elementLibrary.formVariants')">
            <div v-for="(v, idx) in form.variants" :key="idx" class="variant-row" data-test="element-variant-row">
              <el-input v-model="v.resolution" :placeholder="t('appauto.elementLibrary.variantResolution')" data-test="element-variant-resolution" />
              <el-input v-model="v.artifactKey" :placeholder="t('appauto.elementLibrary.variantArtifact')" data-test="element-variant-artifact" />
              <el-button size="small" link type="danger" :icon="Delete" :data-test="`element-variant-remove-${idx}`" @click="form.variants.splice(idx, 1)" />
            </div>
            <el-button size="small" :icon="Plus" data-test="element-variant-add" @click="form.variants.push({ resolution: '', artifactKey: '' })">
              {{ t('appauto.elementLibrary.variantAdd') }}
            </el-button>
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button data-test="element-form-cancel" @click="dialogVisible = false">{{ t('common.cancel') }}</el-button>
          <el-button type="primary" :loading="saving" data-test="element-form-save" @click="save">
            {{ t('common.save') }}
          </el-button>
        </template>
      </el-dialog>
    </div>
  </div>
</template>

<script setup lang="ts">
// Element library page (W5 app automation phase two, P1 segment ①c).
// Profile CRUD only: the admin bridge (/api/v1/appauto/elements) forwards to
// core where every check lives (locator-type value set, confidence range,
// unique name per project, missing-row 404 wording). Locator resolution and
// candidate fallback strategy stay executor-side (driver/element_candidates.py
// single source) — nothing here re-implements matching semantics.
// Worst-scale caliber (50~200 rows): server-side paging 20/50/100, keyword +
// type filters, per-user column settings; page state mirrors into the URL so
// refresh/back/share restore the same view (R22 F group).
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'
import ColumnSettings from '@/components/ColumnSettings.vue'
import ProjectOwnerEcho from '@/components/ProjectOwnerEcho.vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, RefreshLeft, Plus, Search, EditPen, Delete } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import TimeCell from '@/components/TimeCell.vue'
import { bizErrorMsg } from '@/utils/bizError'
import {
  pageElements,
  createElement,
  updateElement,
  deleteElement,
  ELEMENT_LOCATOR_TYPES,
  type AppautoElement,
} from '@/api/appauto'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()

// The project segment rides the global header context (R30): deep links win,
// otherwise the header primary project is consumed and written back.
const { projectId, followTick } = useProjectPageContext()

const loading = ref(false)
const saving = ref(false)
const rows = ref<AppautoElement[]>([])
const total = ref(0)
const keyword = ref('')
const locatorType = ref('')
const page = ref(1)
const pageSize = ref(20)

const ELEMENT_COLUMNS: ColumnDef[] = [
  { key: 'name', labelKey: 'appauto.elementLibrary.colName', width: null },
  { key: 'locatorType', labelKey: 'appauto.elementLibrary.colLocatorType', width: 180 },
  { key: 'confidence', labelKey: 'appauto.elementLibrary.colConfidence', width: 110 },
  { key: 'variants', labelKey: 'appauto.elementLibrary.colVariants', width: 100 },
  { key: 'updated', labelKey: 'appauto.elementLibrary.colUpdated', width: 160 },
]
const ELEMENT_FLEX_MIN: Record<string, number> = { name: 160 }
const ELEMENT_TOOLTIP = new Set(['name'])
const colCfg = useColumnConfig('element-library', ELEMENT_COLUMNS)
const settingsRows = computed(() =>
  colCfg.state.columns.map((c) => ({ ...c, labelKey: ELEMENT_COLUMNS.find((d) => d.key === c.key)!.labelKey })),
)

const dialogVisible = ref(false)
const editing = ref<AppautoElement | null>(null)
const form = ref<{
  name: string
  locatorType: string
  locatorConfigText: string
  confidenceThreshold: number | null
  variants: Array<{ resolution: string; artifactKey: string }>
}>({ name: '', locatorType: 'IMAGE', locatorConfigText: '', confidenceThreshold: null, variants: [] })

function variantCount(row: AppautoElement): string {
  const n = parseVariants(row.variants).length
  return n ? t('appauto.elementLibrary.variantsCount', { n }) : t('appauto.elementLibrary.variantsNone')
}

function parseVariants(text?: string | null): Array<{ resolution: string; artifactKey: string }> {
  if (!text) return []
  try {
    const arr = JSON.parse(text)
    return Array.isArray(arr) ? arr.map((v: Record<string, unknown>) => ({
      resolution: String(v.resolution ?? ''),
      artifactKey: String(v.artifactKey ?? ''),
    })) : []
  } catch {
    return []
  }
}

function syncUrl() {
  void router.replace({
    query: {
      ...(keyword.value ? { keyword: keyword.value } : {}),
      ...(locatorType.value ? { locatorType: locatorType.value } : {}),
      ...(page.value > 1 ? { page: String(page.value) } : {}),
      ...(pageSize.value !== 20 ? { pageSize: String(pageSize.value) } : {}),
    },
  })
}

/** Parse the address bar into page-state values (single source for both the
 *  mount-time read and the same-route query watcher below). */
function urlState() {
  const q = route.query
  const p = Number(q.page)
  const s = Number(q.pageSize)
  return {
    keyword: typeof q.keyword === 'string' ? q.keyword : '',
    locatorType: typeof q.locatorType === 'string' ? q.locatorType : '',
    page: Number.isFinite(p) && p > 0 ? p : 1,
    pageSize: [20, 50, 100].includes(s) ? s : 20,
  }
}

function readUrl() {
  const u = urlState()
  keyword.value = u.keyword
  locatorType.value = u.locatorType
  page.value = u.page
  pageSize.value = u.pageSize
}

// Browser back/forward or a pasted deep link changes only the query while this
// component stays mounted, so onMounted's readUrl would never run again and the
// table would keep showing stale filters. Re-adopt the URL whenever it diverges
// from the current state; our own syncUrl writes are derived from that state,
// so they compare equal and never trigger a second load.
watch(() => route.query, () => {
  const u = urlState()
  if (u.keyword === keyword.value && u.locatorType === locatorType.value
    && u.page === page.value && u.pageSize === pageSize.value) return
  readUrl()
  void load()
})

async function load() {
  if (!projectId.value) {
    rows.value = []
    total.value = 0
    return
  }
  loading.value = true
  try {
    const res = await pageElements({
      projectId: projectId.value,
      keyword: keyword.value.trim() || undefined,
      locatorType: locatorType.value || undefined,
      page: page.value,
      pageSize: pageSize.value,
    })
    rows.value = res.list
    total.value = res.total
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('appauto.elementLibrary.loadFailed')))
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  syncUrl()
  void load()
}

function resetFilters() {
  keyword.value = ''
  locatorType.value = ''
  page.value = 1
  syncUrl()
  void load()
}

function onPageChange(p: number) {
  page.value = p
  syncUrl()
  void load()
}

function onSizeChange(s: number) {
  pageSize.value = s
  page.value = 1
  syncUrl()
  void load()
}

function openCreate() {
  editing.value = null
  form.value = { name: '', locatorType: 'IMAGE', locatorConfigText: '', confidenceThreshold: null, variants: [] }
  dialogVisible.value = true
}

function openEdit(row: AppautoElement) {
  editing.value = row
  form.value = {
    name: row.name,
    locatorType: row.locatorType,
    locatorConfigText: row.locatorConfig || '',
    confidenceThreshold: row.confidenceThreshold ?? null,
    variants: parseVariants(row.variants),
  }
  dialogVisible.value = true
}

interface BuildResult {
  ok: boolean
  payload?: Parameters<typeof createElement>[1]
}

function buildPayload(): BuildResult {
  let config: Record<string, unknown> | null = null
  try {
    const parsed = JSON.parse(form.value.locatorConfigText || 'null')
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) return { ok: false }
    config = parsed as Record<string, unknown>
  } catch {
    return { ok: false }
  }
  const variants = form.value.variants
    .filter((v) => v.resolution.trim() && v.artifactKey.trim())
    .map((v) => ({ resolution: v.resolution.trim(), artifactKey: v.artifactKey.trim() }))
  return {
    ok: true,
    payload: {
      name: form.value.name.trim(),
      locatorType: form.value.locatorType,
      locatorConfig: config,
      variants: variants.length ? variants : null,
      confidenceThreshold: form.value.confidenceThreshold,
    },
  }
}

async function save() {
  if (!projectId.value) return
  if (!form.value.name.trim() || !form.value.locatorType) {
    ElMessage.warning(t('appauto.elementLibrary.jsonInvalid'))
    return
  }
  const built = buildPayload()
  if (!built.ok || !built.payload) {
    ElMessage.warning(t('appauto.elementLibrary.jsonInvalid'))
    return
  }
  saving.value = true
  try {
    if (editing.value) {
      await updateElement(editing.value.id, built.payload)
      ElMessage.success(t('appauto.elementLibrary.updated'))
    } else {
      await createElement(projectId.value, built.payload)
      ElMessage.success(t('appauto.elementLibrary.created'))
    }
    dialogVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('appauto.elementLibrary.saveFailed')))
  } finally {
    saving.value = false
  }
}

async function remove(row: AppautoElement) {
  try {
    await ElMessageBox.confirm(
      t('appauto.elementLibrary.deleteConfirm', { name: row.name }),
      t('appauto.elementLibrary.deleteTitle'),
      { type: 'warning', confirmButtonText: t('common.delete'), cancelButtonText: t('common.cancel') },
    )
  } catch {
    // user cancelled = no operation
    return
  }
  try {
    await deleteElement(row.id)
    ElMessage.success(t('appauto.elementLibrary.deleted'))
    await load()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('appauto.elementLibrary.deleteFailed')))
  }
}

// A header context switch rewrites the project segment — reload from page one.
watch(followTick, () => {
  page.value = 1
  void load()
})

onMounted(() => {
  readUrl()
  void load()
})
</script>

<style scoped>
.element-library-page {
  padding: 16px;
}
.usage-alert {
  margin-bottom: 12px;
}
.empty-box {
  padding: 32px 0;
}
.cond-keyword {
  width: 220px;
}
.cond-type {
  width: 160px;
}
.config-hint {
  margin-left: 8px;
  font-size: 12px;
  opacity: 0.7;
}
.form-hint {
  font-size: 12px;
  opacity: 0.75;
  margin-top: 4px;
}
.variant-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
  width: 100%;
}
</style>
