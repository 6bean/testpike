<!-- tp-sort-exempt: matrix rows = requirement chapters of one plan bounded by the source document, aggregated server-side into a single view object. -->
<template>
  <div class="page-root" data-test="coverage-page">
    <!-- Embedded mode : hosted by the reports page tab, whose own
 page-card already frames the content, so the inner card is dropped to
 avoid a frame-in-frame. Standalone classes kept for redirect-era
 tooling (E2E anchors on the toolbar either way). -->
    <div :class="embedded ? 'coverage-matrix-embedded' : 'page-card'">
      
      <div class="toolbar">
        <div class="toolbar-left">
          <el-select
            v-model="chapterFilter"
            clearable
            :placeholder="t('coverageMatrix.filterChapter')"
            style="width: 160px"
            data-test="matrix-chapter-filter"
            filterable
            @change="onFilterChange"
          >
            <el-option v-for="ch in chapterOptions" :key="ch" :label="ch" :value="ch" />
          </el-select>
          <el-select
            v-model="priorityFilter"
            clearable
            :placeholder="t('coverageMatrix.filterPriority')"
            style="width: 140px"
            data-test="matrix-priority-filter"
            @change="onFilterChange"
          >
            <el-option v-for="p in PRIORITY_OPTIONS" :key="p.value" :label="p.label" :value="p.value" />
          </el-select>
          <el-select
            v-model="endTypeFilter"
            clearable
            :placeholder="t('coverageMatrix.filterEndType')"
            style="width: 140px"
            data-test="matrix-endtype-filter"
            @change="onFilterChange"
          >
            <el-option v-for="e in ENDTYPE_OPTIONS" :key="e.value" :label="e.label" :value="e.value" />
          </el-select>
        </div>
        <div class="toolbar-right">
          <el-button data-test="matrix-expand-all" @click="toggleExpandAll">
            {{ expandAll ? t('coverageMatrix.collapseAll') : t('coverageMatrix.expandAll') }}
          </el-button>
        </div>
      </div>

      
      <div v-if="!loading && projects.length === 0" class="empty-box" data-test="matrix-empty-no-project">
        <EmptyState :title="t('report.noProject')" :action-text="t('report.goProjects')" @action="router.push('/projects')" />
      </div>

      <template v-else-if="projectId">
        
        <div class="stat-grid">
          <div class="stat-card">
            <div class="stat-num" data-test="matrix-total">{{ view?.totalRequirements ?? 0 }}</div>
            <div class="stat-label">{{ t('coverageMatrix.total') }}</div>
          </div>
          <div class="stat-card">
            <div class="stat-num" data-test="matrix-covered">{{ view?.coveredRequirements ?? 0 }}</div>
            <div class="stat-label">{{ t('coverageMatrix.covered') }}</div>
          </div>
          <div class="stat-card">
            <div class="stat-num" data-test="matrix-uncovered">{{ uncoveredCount }}</div>
            <div class="stat-label">{{ t('coverageMatrix.uncovered') }}</div>
          </div>
          <div class="stat-card">
            <div class="stat-num" data-test="matrix-rate">{{ coverageRate }}%</div>
            <div class="stat-label">{{ t('coverageMatrix.rate') }}</div>
          </div>
        </div>

        
        <div class="matrix-legend" data-test="matrix-legend">
          <span class="legend-item"><span class="legend-chip legend-check">✔</span>{{ t('coverageMatrix.legendCovered') }}</span>
          <span class="legend-item"><span class="legend-chip legend-uncovered">+</span>{{ t('coverageMatrix.legendUncovered') }}</span>
          <span class="legend-item">{{ t('coverageMatrix.legendColumnHint') }}</span>
        </div>

        
        <TableSkeleton v-if="loading" :cols="3" data-test="coveragematrixview-skeleton" />
        <el-table border
          :data="visibleRows"
          height="560"
          class="matrix-table"
          data-test="matrix-table"
          :row-class-name="rowClassName"
         v-else>
          <el-table-column :label="t('coverageMatrix.chapterAndRequirement')" min-width="280">
            <template #default="{ row }">
              <el-button
                v-if="row.isGroup"
                link
                class="matrix-group-toggle"
                data-test="matrix-group-toggle"
                @click="toggleGroup(row.groupKey)"
              >
                <el-icon class="matrix-toggle-icon">
                  <ArrowDown v-if="!collapsed.has(row.groupKey)" />
                  <ArrowRight v-else />
                </el-icon>
                <span class="matrix-chapter-cell">
                  {{ row.chapter ?? t('coverageMatrix.ungrouped') }}
                </span>
              </el-button>
              <a
                v-else
                class="matrix-req-title matrix-req-link"
                data-test="matrix-req-link"
                :title="row.title"
                :href="requirementHref(row)"
                @click.prevent="goRequirementList(row)"
              >{{ row.title }}</a>
            </template>
          </el-table-column>
          <el-table-column
            v-for="col in view?.cases ?? []"
            :key="col.id"
            :label="col.caseNo"
            :title="col.title"
            min-width="90"
            align="center"
            label-class-name="matrix-case-header"
            class-name="matrix-case-cell"
          >
            <template #default="{ row }">
              <a
                v-if="!row.isGroup && row.caseIds.includes(col.id)"
                class="matrix-cell-check"
                data-test="matrix-cell-check"
                :href="cellHref(col)"
                :title="t('coverageMatrix.cellTitleHint', { caseNo: col.caseNo, title: col.title })"
                @click.prevent="goCaseListByCase(col)"
              >✔</a>
              
              <a
                v-else-if="!row.isGroup && !row.covered"
                class="matrix-cell-generate"
                data-test="matrix-cell-generate"
                :href="generateHref(row)"
                :title="t('coverageMatrix.redCellTip')"
                @click.prevent="goGenerate(row)"
              >+</a>
            </template>
          </el-table-column>
          <el-table-column :label="t('coverageMatrix.operation')" width="130" align="center" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="!row.isGroup && !row.covered"
                link
                type="warning"
                size="small"
                data-test="matrix-generate-btn"
                @click="goGenerate(row)"
              >
                {{ t('coverageMatrix.generate') }}
              </el-button>
              <el-tag v-else-if="!row.isGroup && row.covered" type="success" size="small">
                {{ t('coverageMatrix.coveredTag') }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>

        <div v-if="!loading && (!view || view.totalRequirements === 0)" class="empty-box" data-test="matrix-empty">
          <EmptyState :title="t('coverageMatrix.noData')" :hint="t('coverageMatrix.emptyGuide')" :action-text="t('coverageMatrix.goRequirements')" @action="router.push('/requirements')" />
        </div>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { getProjects, type Project } from '@/api/project'
import { getCoverageMatrix, type CoverageMatrixView, type MatrixCaseColumn } from '@/api/coverage'
import { ArrowDown, ArrowRight } from '@element-plus/icons-vue'
import { useProjectPageContext } from '@/composables/useProjectPageContext'

// Embedded mode : hosted by the reports page coverage tab —
// the host card already frames the content, so the inner page-card drops.
// Standalone rendering stays intact for anything still resolving through
// the redirect era tooling (E2E anchors survive either way).
const props = withDefaults(defineProps<{ embedded?: boolean }>(), { embedded: false })
const embedded = computed(() => props.embedded)


const { t } = useI18n()
const router = useRouter()

const PRIORITY_OPTIONS = [
  { value: 0, label: 'P0' },
  { value: 1, label: 'P1' },
  { value: 2, label: 'P2' },
]
const ENDTYPE_OPTIONS = [
  { value: 'web', label: 'Web' },
  { value: 'mobile', label: 'Mobile' },
  { value: 'pos', label: 'Pos' },
  { value: 'multi', label: 'Multi' },
]

const loading = ref(true)
const projects = ref<Project[]>([])
const { projectId, followTick } = useProjectPageContext()
const view = ref<CoverageMatrixView | null>(null)
const chapterFilter = ref<string>('')
const priorityFilter = ref<number | null>(null)
const endTypeFilter = ref<string>('')
const expandAll = ref(true)

const collapsed = ref<Set<string>>(new Set())


const chapterOptions = computed(() => {
  const seen = new Set<string>()
  const list: string[] = []
  for (const ch of view.value?.chapters ?? []) {
    const name = ch.chapter ?? t('coverageMatrix.ungrouped')
    if (!seen.has(name)) {
      seen.add(name)
      list.push(name)
    }
  }
  return list
})

const uncoveredCount = computed(() => view.value?.uncoveredRequirementIds?.length ?? 0)

const coverageRate = computed(() => {
  const total = view.value?.totalRequirements ?? 0
  if (total === 0) return 0
  return Math.round(((view.value?.coveredRequirements ?? 0) / total) * 1000) / 10
})


interface TreeRow {
  id: string | number
  isGroup: boolean
  groupKey?: string
  chapter?: string | null
  title?: string
  covered?: boolean
  caseIds?: number[]
}


const visibleRows = computed<TreeRow[]>(() => {
  const rows: TreeRow[] = []
  ;(view.value?.chapters ?? []).forEach((ch, idx) => {
    const key = `ch-${idx}`
    rows.push({ id: key, isGroup: true, groupKey: key, chapter: ch.chapter })
    if (!collapsed.value.has(key)) {
      for (const r of ch.requirements ?? []) {
        rows.push({
          id: r.id,
          isGroup: false,
          title: r.title,
          covered: r.covered,
          caseIds: r.caseIds ?? [],
        })
      }
    }
  })
  return rows
})


function toggleGroup(key: string | undefined): void {
  if (!key) return
  const next = new Set(collapsed.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  collapsed.value = next
}


function rowClassName({ row }: { row: TreeRow }): string {
  if (!row.isGroup && row.covered === false) return 'matrix-row-uncovered'
  return ''
}

async function loadProjects(): Promise<void> {
  try {
    const page = await getProjects({ page: 1, size: 100, status: 1 })
    projects.value = page.list || []
  } finally {
    loading.value = false
  }
}

async function loadMatrix(): Promise<void> {
  if (!projectId.value) return
  loading.value = true
  try {
    view.value = await getCoverageMatrix(projectId.value, {
      chapter: chapterFilter.value || undefined,
      priority: priorityFilter.value ?? undefined,
      endType: endTypeFilter.value || undefined,
    })
  } catch (e: any) {
    ElMessage.error(e?.message || t('coverageMatrix.loadFailed'))
  } finally {
    loading.value = false
  }
}


function onFilterChange(): void {
  void loadMatrix()
}


function toggleExpandAll(): void {
  expandAll.value = !expandAll.value
  if (expandAll.value) {
    collapsed.value = new Set()
  } else {
    const all = new Set<string>()
    ;(view.value?.chapters ?? []).forEach((_, idx) => all.add(`ch-${idx}`))
    collapsed.value = all
  }
}


function goGenerate(row: TreeRow): void {
  router.push({
    path: '/ai/generate',
    query: {
      projectId: String(projectId.value),
      requirementId: String(row.id),
      requirement: row.title ?? '',
    },
  })
}


function goCaseListByCase(col: MatrixCaseColumn): void {
  router.push({
    path: '/cases',
    query: { projectId: String(projectId.value), keyword: col.caseNo },
  })
}


function goRequirementList(row: TreeRow): void {
  router.push({
    path: '/requirements',
    query: { projectId: String(projectId.value), keyword: row.title ?? '' },
  })
}


function cellHref(col: MatrixCaseColumn): string {
  return `#/cases?projectId=${projectId.value}&keyword=${encodeURIComponent(col.caseNo)}`
}
function generateHref(row: TreeRow): string {
  return `#/ai/generate?projectId=${projectId.value}&requirementId=${row.id}&requirement=${encodeURIComponent(row.title ?? '')}`
}
function requirementHref(row: TreeRow): string {
  return `#/requirements?projectId=${projectId.value}&keyword=${encodeURIComponent(row.title ?? '')}`
}

onMounted(() => {
  void loadProjects()
  if (projectId.value) void loadMatrix()
})

watch(followTick, () => {
  if (projectId.value) void loadMatrix()
})
</script>

<style scoped>
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  flex-wrap: wrap;
  gap: 8px;
}
.toolbar-left {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.toolbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
}
.matrix-chapter-cell {
  font-weight: 600;
  color: var(--tp-text-primary);
}
.matrix-toggle-icon {
  margin-right: 4px;
  color: var(--tp-text-secondary);
}
.matrix-req-title {
  color: var(--tp-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 320px;
  display: inline-block;
  vertical-align: bottom;
}

.matrix-req-link {
  cursor: pointer;
  &:hover {
    color: var(--tp-primary);
    text-decoration: underline;
  }
}
.empty-box {
  margin-top: 16px;
}
.matrix-legend {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  margin: 0 0 10px;
  font-size: 12px;
  color: var(--tp-text-secondary);

  .legend-item {
    display: inline-flex;
    align-items: center;
    gap: 6px;
  }

  .legend-chip {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 18px;
    height: 18px;
    border-radius: 4px;
    font-size: 11px;
  }

  .legend-check {
    color: var(--tp-success);
    background: rgba(16, 185, 129, 0.14);
  }

  .legend-uncovered {
    color: var(--tp-warning);
    background: rgba(245, 158, 11, 0.18);
    font-size: 11px;
  }
}

</style>
