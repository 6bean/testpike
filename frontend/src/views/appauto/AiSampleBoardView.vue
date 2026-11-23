<!-- tp-sort-exempt: the board reads a server-capped segment (limit 100 per case) reviewed to terminal state; rows are bounded by the review window, not an accumulating ledger. -->
<template>
  <div class="page-root ai-sample-board-page" data-test="ai-sample-board-page">
    <div class="page-card">
      <!-- R25 mode declaration (scope-aware single control, TB group): this page
           carries no tabs and exactly one page-surface table, so the page-level
           column-settings popover equals the table scope: show/hide, order,
           pinning and density persist per user through the shared
           useColumnConfig piece (viewKey ai-sample-board).
           R21 bands (FB group): the header band stays dropped (page name rides
           the global top bar per R23); this page has no page-level variant
           action, so the trailing slot carries the settings control ahead of
           refresh, and the case screening select stays in the condition flow. -->
      <div class="filter-bar" data-test="ai-samples-filter-bar">
        <el-select
          v-model="caseId"
          filterable
          clearable
          :loading="casesLoading"
          :placeholder="t('appauto.samples.selectCase')"
          style="width: 240px"
          data-test="ai-samples-case-select"
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
          <el-button :icon="Refresh" :loading="loading" data-test="ai-samples-refresh" @click="load">
            {{ t('appauto.samples.refresh') }}
          </el-button>
        </div>
      </div>

      <div v-if="!loading && !projectId" class="empty-box" data-test="ai-samples-empty-no-project">
        <EmptyState
          :title="t('appauto.samples.noProjectTitle')"
          :hint="t('appauto.samples.noProjectHint')"
        />
      </div>

      <div v-else-if="!loading && !caseId" class="empty-box" data-test="ai-samples-empty-no-case">
        <EmptyState
          :title="t('appauto.samples.noCaseTitle')"
          :hint="t('appauto.samples.noCaseHint')"
        />
      </div>

      <template v-else>
        
        <div class="stats-row" data-test="ai-samples-stats">
          <div class="stat-card" data-test="ai-samples-stat-total">
            <div class="stat-value">{{ stats?.total ?? '-' }}</div>
            <div class="stat-label">{{ t('appauto.samples.statTotal') }}</div>
          </div>
          <div class="stat-card" data-test="ai-samples-stat-confirmed">
            <div class="stat-value confirmed">{{ stats?.confirmed ?? '-' }}</div>
            <div class="stat-label">{{ t('appauto.samples.statConfirmed') }}</div>
          </div>
          <div class="stat-card" data-test="ai-samples-stat-rejected">
            <div class="stat-value rejected">{{ stats?.rejected ?? '-' }}</div>
            <div class="stat-label">{{ t('appauto.samples.statRejected') }}</div>
          </div>
          <div class="stat-card" data-test="ai-samples-stat-rate">
            <div class="stat-value">{{ ratePercent }}</div>
            <div class="stat-label">{{ t('appauto.samples.statRate') }}</div>
          </div>
        </div>

        <TableSkeleton v-if="loading" :cols="colCfg.visibleColumns.value.length" data-test="ai-samples-skeleton" />
        <el-table v-else :data="rows" :size="colCfg.state.density" border row-key="sampleId" class="data-table" data-test="ai-samples-table">
          <template #empty>
            <EmptyState
              :title="t('appauto.samples.emptyTitle')"
              :hint="t('appauto.samples.emptyHint')"
            />
          </template>
          <el-table-column
            v-for="col in colCfg.visibleColumns.value"
            :key="col.key"
            :prop="col.key"
            :fixed="col.fixed || false"
            :label="t(col.labelKey)"
            :width="col.width ?? undefined"
            :min-width="col.width ? undefined : AI_SAMPLE_BOARD_FLEX_MIN[col.key]"
            :show-overflow-tooltip="col.key === 'anchor'"
            :data-test="`ai-samples-col-${col.key}`"
          >
            <template #default="{ row }">
              <template v-if="col.key === 'step'">
                <span data-test="ai-samples-step-no">{{ row.stepNo ?? '-' }}</span>
              </template>
              <template v-else-if="col.key === 'anchor'">{{ row.anchor || '-' }}</template>
              <template v-else-if="col.key === 'hitSource'">
                <span data-test="ai-samples-hit-source">{{ SUGGESTION_SOURCE_LABEL[row.hitSource] || row.hitSource }}</span>
              </template>
              <template v-else-if="col.key === 'review'">
                <el-tag
                  :type="row.reviewResult === 'confirmed' ? 'success' : 'info'"
                  disable-transitions
                  data-test="ai-samples-review-tag"
                >
                  {{ SAMPLE_REVIEW_LABEL[row.reviewResult] || row.reviewResult }}
                </el-tag>
              </template>
              <template v-else-if="col.key === 'coord'">
                <span v-if="row.x !== null && row.x !== undefined" data-test="ai-samples-coord">
                  {{ t('appauto.samples.coord', { x: row.x, y: row.y ?? 0 }) }}
                </span>
                <span v-else>-</span>
              </template>
              <template v-else-if="col.key === 'device'">{{ row.deviceId ?? '-' }}</template>
              <template v-else-if="col.key === 'createdAt'"><TimeCell :iso="row.createdAt" /></template>
            </template>
          </el-table-column>
        </el-table>

        <p class="caliber-note">{{ t('appauto.samples.caliber') }}</p>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import TimeCell from '@/components/TimeCell.vue'
import ColumnSettings from '@/components/ColumnSettings.vue'
import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'
import { bizErrorMsg } from '@/utils/bizError'
import {
  SUGGESTION_SOURCE_LABEL,
  SAMPLE_REVIEW_LABEL,
  getAiSampleHitRate,
  listAiSamples,
  type AiSample,
  type AiSampleHitRate,
} from '@/api/appauto'
import { listCases, type CaseItem } from '@/api/case'

const { t } = useI18n()

// Column-configuration wiring (N-3 repayment, shared useColumnConfig piece):
// all seven data columns of the sample table flow through the visibleColumns
// loop so show/hide, order, pinning and density persist per user under
// viewKey 'ai-sample-board'. This board has no locked action slot, so the
// skeleton mirrors the visible data count exactly. The feed is server-capped
// (limit 100 per case), so no per-column sort is declared (tp-sort-exempt
// header).
const AI_SAMPLE_BOARD_COLUMNS: ColumnDef[] = [
  { key: 'step', labelKey: 'appauto.samples.colStep', width: 90 },
  { key: 'anchor', labelKey: 'appauto.samples.colAnchor', width: null },
  { key: 'hitSource', labelKey: 'appauto.samples.colHitSource', width: 120 },
  { key: 'review', labelKey: 'appauto.samples.colReview', width: 110 },
  { key: 'coord', labelKey: 'appauto.samples.colCoord', width: 130 },
  { key: 'device', labelKey: 'appauto.samples.colDevice', width: 110 },
  { key: 'createdAt', labelKey: 'appauto.samples.colCreatedAt', width: 160 },
]
// anchor is the flexible column (long locator text cells)
const AI_SAMPLE_BOARD_FLEX_MIN: Record<string, number> = { anchor: 200 }
const colCfg = useColumnConfig('ai-sample-board', AI_SAMPLE_BOARD_COLUMNS)
const settingsRows = computed(() =>
  colCfg.state.columns.map((c) => ({ ...c, labelKey: AI_SAMPLE_BOARD_COLUMNS.find((d) => d.key === c.key)!.labelKey })),
)

// : the project segment rides the global header context (the page-level
// selector is gone); an empty context keeps the honest "pick a project"
// empty state, deep links and header switches both land through the composable.
const { projectId, followTick } = useProjectPageContext()
const caseId = ref<number | null>(null)
const loading = ref(false)
const casesLoading = ref(false)
const rows = ref<AiSample[]>([])
const stats = ref<AiSampleHitRate | null>(null)
const caseOptions = ref<CaseItem[]>([])


const ratePercent = computed(() => {
  if (!stats.value) return '-'
  return `${Math.round(stats.value.confirmRate * 1000) / 10}%`
})

async function load() {
  if (!projectId.value || !caseId.value) {
    rows.value = []
    stats.value = null
    return
  }
  loading.value = true
  try {
    const [samples, hit] = await Promise.all([
      listAiSamples(projectId.value, caseId.value),
      getAiSampleHitRate(projectId.value, caseId.value),
    ])
    rows.value = samples
    stats.value = hit
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('appauto.samples.loadFailed')))
    rows.value = []
    stats.value = null
  } finally {
    loading.value = false
  }
}

async function onProjectChange() {
  caseId.value = null
  rows.value = []
  stats.value = null
  caseOptions.value = []
  if (!projectId.value) return
  casesLoading.value = true
  try {
    const r = await listCases({ projectId: projectId.value, page: 1, size: 200 })
    caseOptions.value = r.list || []
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('appauto.samples.loadFailed')))
  } finally {
    casesLoading.value = false
  }
}

// : a header context switch reloads from the new segment (in-page
// switches reload through their own controls, so followTick only fires
// on context-driven changes and never double-loads).
watch(followTick, () => {
  // same posture as the retired selector @change: reset downstream screening
  void onProjectChange()
})

onMounted(() => {
  if (projectId.value) void onProjectChange()
})

</script>

<style scoped>
.empty-box {
  padding: 24px 0;
}
.stats-row {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 16px;
}
.stat-card {
  flex: 1 1 140px;
  padding: 12px 16px;
  border: 1px solid var(--tp-border-color, #303030);
  border-radius: 8px;
  background: var(--tp-bg-color, #141414);
}
.stat-value {
  font-size: 22px;
  font-weight: 600;
  line-height: 1.3;
}
.stat-value.confirmed {
  color: var(--el-color-success);
}
.stat-value.rejected {
  color: var(--el-color-info);
}
.stat-label {
  margin-top: 4px;
  font-size: 12px;
  color: var(--tp-text-secondary);
}
.caliber-note {
  margin-top: 12px;
  font-size: 12px;
  color: var(--tp-text-secondary);
  line-height: 1.6;
}
</style>
