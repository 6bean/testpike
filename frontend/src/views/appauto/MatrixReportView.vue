<!-- tp-sort-exempt: the matrix is per-case: rows = the model x OS grid of one case, rebuilt on selection and bounded by the device matrix, never accumulated. -->
<template>
  <div class="page-root matrix-report-page" data-test="matrix-report-page">
    <div class="page-card">
      <!-- R25 mode declaration (scope-aware single control, TB group): this page
           carries no tabs and exactly one page-surface table (the model x OS
           heat table below; the failure-step drill-down lives inside expand
           rows and stays honestly un-configurable), so the page-level
           column-settings popover equals that scope: show/hide, order, pinning
           and density persist per user through the shared useColumnConfig piece.
           The header band was already dropped (page name rides the global top
           bar per R23); settings joins the trailing slot ahead of refresh. -->
      <div class="filter-bar" data-test="matrix-filter-bar">
        <el-select
          v-model="caseId"
          filterable
          clearable
          :loading="casesLoading"
          :placeholder="t('appauto.matrixReport.selectCase')"
          style="width: 240px"
          data-test="matrix-report-case-select"
          @change="load"
        >
          <el-option
            v-for="c in caseOptions"
            :key="c.id"
            :label="`${c.caseNo} · ${c.title}`"
            :value="c.id"
          />
        </el-select>
        <div class="filter-bar-actions">
          <ColumnSettings
            :columns="settingsRows"
            :density="colCfg.state.density"
            @toggle="colCfg.toggle"
            @move="colCfg.move"
            @density="colCfg.setDensity"
            @reset="colCfg.reset"
            @pin="colCfg.toggleFixed"
          />
          <!-- refresh stays a view-state action in the same trailing slot, behind settings -->
          <el-button :icon="Refresh" :loading="loading" data-test="matrix-report-refresh" @click="load">
            {{ t('appauto.matrixReport.refresh') }}
          </el-button>
        </div>
      </div>

      <el-alert
        v-if="loadError"
        type="error"
        show-icon
        :closable="true"
        class="report-error-alert"
        data-test="matrix-report-error"
        @close="loadError = ''"
      >
        <template #title>{{ loadError }}</template>
      </el-alert>

      <div v-if="!projectId || !caseId" class="report-empty-hint" data-test="matrix-report-idle">
        {{ t('appauto.matrixReport.idleHint') }}
      </div>

      <template v-else>
   
        <TableSkeleton v-if="loading && !rows.length" :cols="colCfg.visibleColumns.value.length + 1" data-test="matrix-report-skeleton" />
        
        <div v-if="rows.length" class="report-summary" data-test="matrix-report-summary">
          <el-tag type="info">{{ t('appauto.matrixReport.modelCount', { n: rows.length }) }}</el-tag>
          <el-tag type="success">{{ t('appauto.matrixReport.passSamples', { n: totalPass }) }}</el-tag>
          <el-tag type="danger">{{ t('appauto.matrixReport.failSamples', { n: totalFail }) }}</el-tag>
          <el-tag type="warning">{{ t('appauto.matrixReport.sampleCount', { n: totalSamples }) }}</el-tag>
        </div>

        
        <el-table
          v-if="rows.length"
          v-loading="loading"
          :data="rows"
          border
          :size="colCfg.state.density"
          row-key="modelKey"
          class="report-table"
          data-test="matrix-report-table"
        >
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="expand-body">
                <div v-if="row.stepFailures.length" data-test="matrix-report-step-failures">
                  <div class="expand-title">{{ t('appauto.matrixReport.failureSteps') }}</div>
                  <el-table :data="row.stepFailures" size="small" border data-test="matrix-report-steps-table">
                    <el-table-column :label="t('appauto.matrixReport.colStepNo')" prop="stepNo" width="90" />
                    <el-table-column :label="t('appauto.matrixReport.colAction')" prop="action" min-width="160">
                      <template #default="{ row: s }"><span class="mono">{{ s.action }}</span></template>
                    </el-table-column>
                    <el-table-column :label="t('appauto.matrixReport.colFailCount')" prop="failCount" width="100" />
                    <el-table-column :label="t('appauto.matrixReport.colSampleDetail')" min-width="260">
                      <template #default="{ row: s }">
                        <span class="sample-detail" :data-test="`matrix-report-sample-${s.stepNo}`">{{ s.sampleDetail || '-' }}</span>
                      </template>
                    </el-table-column>
                  </el-table>
                </div>
                <div v-else class="expand-title" data-test="matrix-report-no-failure-steps">
                  {{ t('appauto.matrixReport.noFailureSteps') }}
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column
            v-for="col in colCfg.visibleColumns.value"
            :key="col.key"
            :prop="col.key"
            :fixed="col.fixed || false"
            :label="t(col.labelKey)"
            :width="col.width ?? undefined"
            :min-width="col.width ? undefined : MATRIX_REPORT_FLEX_MIN[col.key]"
            :data-test="`matrix-report-col-${col.key}`"
          >
            <template #default="{ row }">
              <template v-if="col.key === 'model'">
                <span data-test="matrix-report-model">{{ row.model }}</span>
              </template>
              <template v-else-if="col.key === 'osVersion'">
                <span>{{ row.osVersion || '-' }}</span>
              </template>
              <template v-else-if="col.key === 'samples'">
                <span>{{ row.samples }}</span>
              </template>
              <template v-else-if="col.key === 'passRate'">
                <div class="heat-cell" :style="heatStyle(row.passRate)" :data-test="`matrix-report-heat-${row.modelKey}`">
                  <span class="heat-text">{{ heatText(row) }}</span>
                </div>
              </template>
              <template v-else-if="col.key === 'message'">
                <span class="diff-message" data-test="matrix-report-diff-message">{{ row.message }}</span>
              </template>
            </template>
          </el-table-column>
        </el-table>

        <div v-else-if="!loading && !loadError" class="report-empty-hint" data-test="matrix-report-empty">
          {{ t('appauto.matrixReport.emptyHint') }}
        </div>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">

import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import { Refresh } from '@element-plus/icons-vue'
import { getMatrixModelReport, type MatrixModelRow } from '@/api/appauto'
import { listCases, type CaseItem } from '@/api/case'
import { bizErrorMsg } from '@/utils/bizError'
import TableSkeleton from '@/components/TableSkeleton.vue'
import ColumnSettings from '@/components/ColumnSettings.vue'
import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'

interface MatrixReportRow extends MatrixModelRow {
  modelKey: string
}

const { t } = useI18n()
const route = useRoute()

// : the project segment rides the global header context (the page-level
// selector is gone); ?projectId deep links land through the composable, the
// legacy in-component query read stays only for the caseId pairing.
const { projectId, followTick } = useProjectPageContext()

// Column-configuration wiring (N-3 repayment, shared useColumnConfig piece):
// the five heat-table data columns flow through the visibleColumns loop so
// show/hide, order, pinning and density persist per user under viewKey
// 'matrix-report'; the expand slot stays outside the loop (the skeleton adds
// one fixed slot).
const MATRIX_REPORT_COLUMNS: ColumnDef[] = [
  { key: 'model', labelKey: 'appauto.matrixReport.colModel', width: null },
  { key: 'osVersion', labelKey: 'appauto.matrixReport.colOs', width: 110 },
  { key: 'samples', labelKey: 'appauto.matrixReport.colSamples', width: 90 },
  { key: 'passRate', labelKey: 'appauto.matrixReport.colPassRate', width: 220 },
  { key: 'message', labelKey: 'appauto.matrixReport.colDiffMessage', width: null },
]
// model and the diff message are the two flexible columns; the message keeps
// its honest full sentence (no tooltip ellipsis, per the page contract)
const MATRIX_REPORT_FLEX_MIN: Record<string, number> = { model: 150, message: 320 }
const colCfg = useColumnConfig('matrix-report', MATRIX_REPORT_COLUMNS)
const settingsRows = computed(() =>
  colCfg.state.columns.map((c) => ({ ...c, labelKey: MATRIX_REPORT_COLUMNS.find((d) => d.key === c.key)!.labelKey })),
)
const caseId = ref<number | null>(null)
const caseOptions = ref<CaseItem[]>([])
const casesLoading = ref(false)
const loading = ref(false)
const rows = ref<MatrixReportRow[]>([])
const loadError = ref('')

const totalSamples = computed(() => rows.value.reduce((acc, r) => acc + r.samples, 0))
const totalPass = computed(() => rows.value.reduce((acc, r) => acc + r.passSamples, 0))
const totalFail = computed(() => rows.value.reduce((acc, r) => acc + r.failSamples, 0))

function heatText(row: MatrixReportRow): string {
  if (row.samples > 0 && row.failSamples === 0) return t('appauto.matrixReport.allPassed')
  return `${Math.round(row.passRate * 100)}%（${row.passSamples}/${row.samples}）`
}


function heatStyle(passRate: number): Record<string, string> {
  const alpha = Math.max(0.12, Math.min(0.9, 0.12 + passRate * 0.78))
  return {
    background: passRate >= 0.5
      ? `color-mix(in srgb, var(--el-color-success) ${Math.round(alpha * 100)}%, transparent)`
      : `color-mix(in srgb, var(--el-color-danger) ${Math.round(alpha * 100)}%, transparent)`,
    color: passRate >= 0.5 ? 'var(--el-color-success)' : 'var(--el-color-danger)',
  }
}

async function load(): Promise<void> {
  if (!projectId.value || !caseId.value) {
    rows.value = []
    return
  }
  loading.value = true
  loadError.value = ''
  try {
    const data = await getMatrixModelReport(projectId.value, caseId.value)
    rows.value = data.map((r) => ({ ...r, modelKey: `${r.model}__${r.osVersion ?? ''}` }))
  } catch (e) {
    rows.value = []
    loadError.value = bizErrorMsg(e, t('appauto.matrixReport.loadFail'))
  } finally {
    loading.value = false
  }
}

async function onProjectChange(): Promise<void> {
  caseId.value = null
  rows.value = []
  caseOptions.value = []
  if (!projectId.value) return
  casesLoading.value = true
  try {
    const r = await listCases({ projectId: projectId.value, page: 1, size: 200 })
    caseOptions.value = (r.list || []).filter((c) => (c.automationTarget || 'web') === 'app')
  } catch (e) {
    loadError.value = bizErrorMsg(e, t('appauto.matrixReport.loadCasesFail'))
  } finally {
    casesLoading.value = false
  }
}

onMounted(async () => {
  const qProject = Number(route.query.projectId)
  const qCase = Number(route.query.caseId)
  if (qProject > 0 && qCase > 0) {
    caseId.value = qCase
    await onProjectCasesOnly(qProject)
    await load()
    return
  }
  if (projectId.value) await onProjectChange()
})


async function onProjectCasesOnly(pid: number): Promise<void> {
  casesLoading.value = true
  try {
    const r = await listCases({ projectId: pid, page: 1, size: 200 })
    caseOptions.value = (r.list || []).filter((c) => (c.automationTarget || 'web') === 'app')
  } finally {
    casesLoading.value = false
  }
}

// : a header context switch reloads from the new segment (in-page
// switches reload through their own controls, so followTick only fires
// on context-driven changes and never double-loads).
watch(followTick, () => {
  void onProjectChange()
})

</script>

<style scoped>
.report-error-alert {
  margin-bottom: 10px;
}
.report-empty-hint {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  padding: 24px 0;
}
.report-summary {
  display: flex;
  gap: 8px;
  margin-bottom: 10px;
  flex-wrap: wrap;
}
.heat-cell {
  border-radius: 4px;
  padding: 2px 8px;
  text-align: center;
  font-weight: 600;
  font-size: 13px;
}
.diff-message {
  font-size: 13px;
  word-break: break-all;
}
.expand-body {
  padding: 8px 16px;
}
.expand-title {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  margin-bottom: 6px;
}
.mono {
  font-family: var(--el-font-family-mono, monospace);
}
.sample-detail {
  font-size: 12px;
  word-break: break-all;
}
</style>
