<template>
  <div class="page-root" data-test="sample-page">
    <div class="page-card">

      <div class="filter-bar">
        <el-select
          v-model="query.sampleType"
          :placeholder="t('sample.typeLabel')"
          clearable
          style="width: 150px"
          popper-class="tp-sample-type-popper"
          data-test="sample-type-select"
          data-se1-exempt="closed enum SAMPLE_TYPE_KEYS, only 2 sample types"
          @change="reload"
        >
          <el-option v-for="opt in TYPE_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
        <el-button data-test="sample-search" @click="reload">
          <el-icon><Search /></el-icon>{{ t('sample.query') }}
        </el-button>
        <!-- FB/R21 trailing slot: this ledger is read-only (no create/import
             variant action), so the column-settings control rides the band
             tail alone. R25 mode declaration (scope-aware single control, TB
             group): no tabs and exactly one table, so the page-level settings
             popover equals the table scope; show/hide, order, pinning and
             density persist per user via the shared piece. -->
        <div class="filter-bar-actions" data-test="sample-filter-actions">
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

      <TableSkeleton v-if="loading" :cols="colCfg.visibleColumns.value.length + 1" data-test="trainingsamplelistview-skeleton" />
      <!-- Main ledger table: header sort is server-side —
 every sortable column below carries a SORT_KEY_MAP key and the ORDER BY
 happens in core SQL over the whole filtered window. The detail drawer
 shows one already-loaded row (no second query), so it stays outside the
 sort contract ( exemption note). -->
      <el-table border v-else :data="rows" :size="colCfg.state.density" row-key="id" data-test="sample-table"
        @sort-change="onSortChange">
        <el-table-column
          v-for="col in colCfg.visibleColumns.value"
          :key="col.key"
          :prop="col.key"
          :fixed="col.fixed || false"
          :label="t(col.labelKey)"
          :width="col.width ?? undefined"
          :min-width="col.width ? undefined : SAMPLE_FLEX_MIN[col.key]"
          :align="col.key === 'sourceVersion' ? 'center' : undefined"
          :sortable="SORT_KEY_MAP[col.key] ? 'custom' : false"
          :data-test="`sample-col-${col.key}`"
        >
          <template #default="{ row }">
            <template v-if="col.key === 'caseNo'">{{ row.caseNo || '—' }}</template>
            <template v-else-if="col.key === 'projectId'">{{ row.projectId ?? '—' }}</template>
            <template v-else-if="col.key === 'sampleType'">
              <el-tag size="small" :type="typeTag(row.sampleType)" data-test="sample-type-tag">
                {{ typeLabel(row.sampleType) }}
              </el-tag>
            </template>
            <template v-else-if="col.key === 'sourceVersion'">{{ row.sourceVersion ?? '—' }}</template>
            <template v-else>{{ row[col.key] }}</template>
          </template>
        </el-table-column>
        <el-table-column :label="t('sample.colAction')" width="90" align="center">
          <template #default="{ row }">
            <el-button link type="primary" data-test="sample-detail-open" @click="openDetail(row)">
              {{ t('sample.viewBtn') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <EmptyState
        v-if="!loading && rows.length === 0"
        :title="t('sample.emptyTitle')"
        :hint="t('sample.emptyHint')"
      />

      <div class="pagination-bar">
        <el-pagination
          v-if="total > 0"
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          data-test="sample-pager"
          @current-change="load"
          @size-change="load(1)"
        />
      </div>
    </div>

 
    <el-drawer
      v-model="detailVisible"
      :title="t('sample.detailTitle', { id: detail?.id ?? '' })"
      size="640px"
      destroy-on-close
      data-test="sample-detail-drawer"
    >
      <template v-if="detail">
        <el-descriptions :column="2" border size="small" data-test="sample-detail-meta">
          <el-descriptions-item :label="t('sample.colCaseNo')">{{ detail.caseNo || '—' }}</el-descriptions-item>
          <el-descriptions-item :label="t('sample.colProject')">{{ detail.projectId ?? '—' }}</el-descriptions-item>
          <el-descriptions-item :label="t('sample.colType')">
            <el-tag size="small" :type="typeTag(detail.sampleType)">{{ typeLabel(detail.sampleType) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item :label="t('sample.colSourceVersion')">{{ detail.sourceVersion ?? '—' }}</el-descriptions-item>
          <el-descriptions-item :label="t('sample.metaCaseId')">{{ detail.caseId ?? '—' }}</el-descriptions-item>
          <el-descriptions-item :label="t('sample.colCreatedAt')">{{ detail.createdAt }}</el-descriptions-item>
        </el-descriptions>
        <h4 class="sample-detail-section">{{ t('sample.jsonSection') }}</h4>
        <pre class="sample-detail-json" data-test="sample-detail-json">{{ prettyJson(detail.sampleJson) }}</pre>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">

import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import EmptyState from '@/components/EmptyState.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'
import ColumnSettings from '@/components/ColumnSettings.vue'
import {
  SAMPLE_TYPE_KEYS,
  listTrainingSamples,
  type TrainingSampleItem,
} from '@/api/trainingSample'

const { t } = useI18n()
// R30: project filter follows the header global context (cleared header keeps
// the all-projects view). The page no longer carries its own picker.
const { projectId: sampleProjectId } = useProjectPageContext()
const loading = ref(false)
const rows = ref<TrainingSampleItem[]>([])
const total = ref(0)
const query = reactive<{
  sampleType: string
  page: number
  size: number
  sortBy: string
  sortDir: string
}>({
  sampleType: '',
  page: 1,
  size: 20,
  sortBy: '',
  sortDir: '',
})

// Column prop -> core sample SORT_COLUMNS whitelist key (single source for
// the sortable decision and the request payload; page 17).
// Sorting happens server-side over the whole filtered window, not the loaded
// pageful; sample_json is only shown in the drawer and stays unsorted.
const SORT_KEY_MAP: Record<string, string> = {
  id: 'id',
  caseNo: 'case_no',
  projectId: 'project_id',
  sampleType: 'sample_type',
  sourceVersion: 'source_version',
  createdAt: 'created_at',
}

// Ledger data columns for the shared column-config piece; the locked action
// column stays outside the loop. Every sortable column is whitelisted in
// SORT_KEY_MAP above, so the loop derives the sortable flag from one source.
const SAMPLE_COLUMNS: ColumnDef[] = [
  { key: 'id', labelKey: 'sample.colId', width: 70 },
  { key: 'caseNo', labelKey: 'sample.colCaseNo', width: null },
  { key: 'projectId', labelKey: 'sample.colProject', width: 100 },
  { key: 'sampleType', labelKey: 'sample.colType', width: 120 },
  { key: 'sourceVersion', labelKey: 'sample.colSourceVersion', width: 120 },
  { key: 'createdAt', labelKey: 'sample.colCreatedAt', width: 170 },
]
/** Flexible columns fall back to min-width when no explicit width is set. */
const SAMPLE_FLEX_MIN: Record<string, number> = { caseNo: 160 }
const colCfg = useColumnConfig('sample', SAMPLE_COLUMNS)
/** Popover rows (column state merged with labelKey for rendering). */
const settingsRows = computed(() =>
  colCfg.state.columns.map((c) => ({ ...c, labelKey: SAMPLE_COLUMNS.find((d) => d.key === c.key)!.labelKey })),
)

/** Header sort: forward the whitelisted key to core; clearing a sort falls
 * back to the historical id DESC default order; page resets to 1. */
function onSortChange({ prop, order }: { prop: string; order: string | null }): void {
  query.page = 1
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
  void load()
}


const TYPE_OPTIONS = computed(() =>
  Object.keys(SAMPLE_TYPE_KEYS).map((value) => ({ value, label: t(SAMPLE_TYPE_KEYS[value]) })),
)

function typeLabel(v: string): string {
  const key = SAMPLE_TYPE_KEYS[v]
  return key ? t(key) : v
}


function typeTag(v: string): 'danger' | 'warning' {
  return v === '\u9a73\u56de' ? 'danger' : 'warning'
}

const detailVisible = ref(false)
const detail = ref<TrainingSampleItem | null>(null)

function openDetail(row: TrainingSampleItem): void {
  detail.value = row
  detailVisible.value = true
}


function prettyJson(raw: string): string {
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}

async function load(toPage?: number): Promise<void> {
  if (toPage) query.page = toPage
  loading.value = true
  try {
    const pageData = await listTrainingSamples({
      projectId: sampleProjectId.value,
      sampleType: query.sampleType || undefined,
      page: query.page,
      size: query.size,
      sortBy: query.sortBy || undefined,
      sortDir: query.sortDir || undefined,
    })
    rows.value = pageData.list || []
    total.value = Number(pageData.total) || 0
  } catch (e) {
    ElMessage.error((e as Error).message || t('sample.listLoadFailed'))
  } finally {
    loading.value = false
  }
}

// R30: project filter follows the header global context (cleared header =
// all-projects view, legit null). No in-page picker anymore.
watch(sampleProjectId, () => void reload())

function reload(): void {
  query.page = 1
  load()
}

onMounted(load)
</script>

<style scoped>
.sample-detail-section {
  margin: 18px 0 8px;
  font-size: 14px;
}
.sample-detail-json {
  margin: 0;
  padding: 10px 12px;
  background: var(--tp-bg-elevated, rgba(125, 125, 125, 0.08));
  border: 1px solid var(--tp-border-color, var(--el-border-color));
  border-radius: 6px;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 13px;
  line-height: 1.7;
  max-height: 480px;
  overflow: auto;
}
</style>
