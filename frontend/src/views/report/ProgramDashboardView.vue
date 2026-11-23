<script setup lang="ts">
/**
 * Program-set quality dashboard .
 *
 * Data source: GET /report-center/program-overview (live feed, server-owned
 * window). The program lens switch lives ONLY in the header (R30) — this page
 * reads useProgramContextStore.activeProgramId and never renders a second
 * program picker. Without a lens the page honestly says where to switch.
 * Every number is quoted straight from the feed (rates/burn/lights/peaks) and
 * the caliber footnotes are read from the server registry verbatim — the UI
 * recomputes nothing (the caliber registry is the only definition source).
 *
 * heavy charts, no per-row state machines); the feed carries every visible
 * project at once, so the table pages client-side through the shared
 * useClientPagination window and the caliber footnotes start collapsed.
 * Drilldown rides the programId lens param onto the six list pages already
 * wired for it (MP4).
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElDatePicker } from 'element-plus'
import { ArrowRight, Refresh } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import { useClientPagination } from '@/composables/useClientPagination'
import { watchProgramLens } from '@/composables/useProgramLens'
import { useProgramContextStore } from '@/stores/programContext'
import { useAuthStore } from '@/stores/auth'
import {
  getProgramOverview,
  type ProgramOverviewView,
} from '@/api/reportCenter'

const { t } = useI18n()
const lens = useProgramContextStore()
const authStore = useAuthStore()

const feed = ref<ProgramOverviewView | null>(null)
const loading = ref(false)
const loadError = ref('')
const period = ref<[string, string] | null>(null)
/** Caliber footnotes are reference material: collapsed until asked for. */
const calibersOpen = ref(false)

function projectName(projectId: number): string {
  return authStore.projects.find((p) => p.projectId === projectId)?.projectName ?? `#${projectId}`
}

async function load(): Promise<void> {
  const programId = lens.activeProgramId
  if (programId == null) {
    feed.value = null
    return
  }
  loading.value = true
  loadError.value = ''
  try {
    feed.value = await getProgramOverview(
      programId,
      period.value?.[0] ?? undefined,
      period.value?.[1] ?? undefined,
    )
    // A fresh window replaces the whole segment: land on page 1 rather than
    // keeping a page number that belonged to the previous result set.
    resetPage()
  } catch (err) {
    loadError.value = err instanceof Error ? err.message : String(err)
    feed.value = null
  } finally {
    loading.value = false
  }
}

/**
 * One row per project: the four per-project blocks ride the same feed window,
 * keyed by projectId (server guarantees an entry per visible project —
 * journey/perf always emit a row, pass-rate may carry a null reading).
 */
const rows = computed(() => {
  const f = feed.value
  if (!f) return []
  const burn = new Map(f.defectBurn.map((b) => [b.projectId, b]))
  const journey = new Map(f.journeyTrafficLight.map((j) => [j.projectId, j]))
  const perf = new Map(f.perfOverview.map((p) => [p.projectId, p]))
  return f.projects.map((p) => ({
    projectId: p.projectId,
    name: projectName(p.projectId),
    passRate: p.passRate,
    burn: burn.get(p.projectId) ?? null,
    journey: journey.get(p.projectId) ?? null,
    perf: perf.get(p.projectId) ?? null,
  }))
})

const { pageNo, pageSize, pageSizes, paged: pagedRows, total: rowCount, showPager, resetPage } =
  useClientPagination(() => rows.value)

const lensActive = computed(() => lens.activeProgramId != null)

const drilldowns = computed(() => [
  { key: 'cases', path: '/cases' },
  { key: 'apiTest', path: '/api-test' },
  { key: 'perf', path: '/perf' },
  { key: 'batches', path: '/batches' },
  { key: 'defects', path: '/defects' },
  { key: 'reports', path: '/report' },
])

function lensQuery(): Record<string, string | number> {
  return { programId: lens.activeProgramId as number }
}

onMounted(() => {
  load()
  // Header lens flip (pick / switch / detach) reloads the same segment — single
  // source watcher shared with the six list pages (R40), no page-local copy.
  watchProgramLens(() => load())
})

// Period change refetches immediately (single control, inline query posture).
watch(period, () => load())
</script>

<template>
  <div class="page-root" data-test="program-dashboard-page">
    <div class="page-card">
      <p class="lens-note" data-test="dashboard-lens-note">{{ t('programDashboard.lensNote') }}</p>

      <EmptyState
        v-if="!lensActive"
        :title="t('programDashboard.noLensTitle')"
        :hint="t('programDashboard.noLensHint')"
        data-test="dashboard-no-lens"
      />

      <template v-else>
        <div class="filter-bar" data-test="dashboard-filter-bar">
          <span class="period-label">{{ t('programDashboard.periodLabel') }}</span>
          <el-date-picker
            v-model="period"
            type="daterange"
            value-format="YYYY-MM-DDTHH:mm:ss"
            :start-placeholder="t('programDashboard.periodFrom')"
            :end-placeholder="t('programDashboard.periodTo')"
            data-test="dashboard-period"
          />
          <span v-if="feed" class="window-echo" data-test="dashboard-window">
            {{ t('programDashboard.window', { from: feed.periodFrom.slice(0, 10), to: feed.periodTo.slice(0, 10) }) }}
          </span>
          <div class="filter-bar-actions">
            <el-button
              :icon="Refresh"
              :loading="loading"
              data-test="dashboard-refresh"
              @click="load"
            >
              {{ t('programDashboard.refresh') }}
            </el-button>
          </div>
        </div>

        <div v-if="loadError" class="load-error" data-test="dashboard-error">
          <el-alert type="error" :closable="false" show-icon :title="loadError" />
          <el-button data-test="dashboard-retry" @click="load">{{ t('programDashboard.retry') }}</el-button>
        </div>

        <el-skeleton v-else-if="loading && !feed" :rows="6" animated data-test="dashboard-skeleton" />

        <template v-else-if="feed">
          <el-table :data="pagedRows" border data-test="dashboard-project-table">
            <el-table-column :label="t('programDashboard.colProject')" min-width="160">
              <template #default="{ row }">
                <span :data-test="`dashboard-project-${row.projectId}`">{{ row.name }}</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('programDashboard.colPassRate')" width="120">
              <template #default="{ row }">
                <span v-if="row.passRate != null">{{ (row.passRate * 100).toFixed(1) }}%</span>
                <span v-else class="muted" data-test="dashboard-pass-rate-na">{{ t('programDashboard.passRateNa') }}</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('programDashboard.colDefect')" min-width="220">
              <template #default="{ row }">
                <span v-if="row.burn" :data-test="`dashboard-burn-${row.projectId}`">
                  {{ t('programDashboard.defectBurn', {
                    start: row.burn.openAtStart,
                    created: row.burn.createdWithin,
                    closed: row.burn.closedWithin,
                    end: row.burn.openAtEnd,
                  }) }}
                </span>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('programDashboard.colJourney')" min-width="180">
              <template #default="{ row }">
                <span v-if="row.journey" class="light-cell" :data-test="`dashboard-journey-${row.projectId}`">
                  <span class="light-dot" :class="`light-dot--${row.journey.light}`" />
                  {{ t(`programDashboard.light.${row.journey.light}`) }}
                  <span class="muted">{{ t('programDashboard.journeyRuns', { count: row.journey.runsInWindow }) }}</span>
                </span>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('programDashboard.colPerf')" min-width="240">
              <template #default="{ row }">
                <span v-if="row.perf" class="light-cell" :data-test="`dashboard-perf-${row.projectId}`">
                  <span class="light-dot" :class="`light-dot--${row.perf.light}`" />
                  {{ t(`programDashboard.light.${row.perf.light}`) }}
                  <span v-if="row.perf.peakRps != null" class="muted">
                    {{ t('programDashboard.perfPeaks', { rps: row.perf.peakRps, p95: row.perf.peakP95Ms, error: row.perf.peakErrorRate }) }}
                  </span>
                </span>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('programDashboard.colDrilldown')" min-width="260">
              <template #default>
                <span class="drilldown-links" data-test="dashboard-drilldown">
                  <RouterLink
                    v-for="d in drilldowns"
                    :key="d.key"
                    :to="{ path: d.path, query: lensQuery() }"
                    :data-test="`dashboard-drill-${d.key}`"
                  >
                    {{ t(`programDashboard.drill.${d.key}`) }}
                  </RouterLink>
                </span>
              </template>
            </el-table-column>
          </el-table>

          <p v-if="rowCount === 0" class="muted" data-test="dashboard-empty-rows">
            {{ t('programDashboard.noProjects') }}
          </p>

          <div v-if="showPager" class="pagination-bar" data-test="dashboard-pagination">
            <el-pagination
              v-model:current-page="pageNo"
              v-model:page-size="pageSize"
              :page-sizes="pageSizes"
              :total="rowCount"
              layout="total, sizes, prev, pager, next"
              size="small"
              background
            />
          </div>

          <div class="caliber-block" data-test="dashboard-calibers">
            <div class="caliber-head">
              <p class="caliber-title">{{ t('programDashboard.caliberTitle') }}</p>
              <button
                type="button"
                class="caliber-toggle"
                :aria-expanded="calibersOpen ? 'true' : 'false'"
                aria-controls="dashboard-caliber-list"
                data-test="dashboard-caliber-fold"
                @click="calibersOpen = !calibersOpen"
              >
                <el-icon class="caliber-toggle-icon" :class="{ 'is-open': calibersOpen }">
                  <ArrowRight />
                </el-icon>
                {{ calibersOpen
                  ? t('programDashboard.caliberHide', { count: feed.caliberRegistry.length })
                  : t('programDashboard.caliberShow', { count: feed.caliberRegistry.length }) }}
              </button>
            </div>
            <template v-if="calibersOpen">
              <ul id="dashboard-caliber-list">
                <li v-for="c in feed.caliberRegistry" :key="c.code" :data-test="`dashboard-caliber-${c.code}`">
                  {{ c.displayName }}（{{ c.code }}） {{ t('programDashboard.caliberVersion', { version: c.version }) }}
                </li>
              </ul>
              <p class="caliber-source">{{ t('programDashboard.caliberSource') }}</p>
            </template>
          </div>
        </template>
      </template>
    </div>
  </div>
</template>

<style scoped>
.lens-note {
  margin: 0 0 12px;
  color: var(--tp-text-secondary);
  font-size: 13px;
}
.period-label {
  color: var(--tp-text-secondary);
  font-size: 13px;
}
.window-echo {
  color: var(--tp-text-secondary);
  font-size: 13px;
}
.load-error {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}
.muted {
  color: var(--tp-text-muted, var(--tp-text-secondary));
  font-size: 12px;
}
.light-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.light-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  display: inline-block;
}
.light-dot--green { background: var(--tp-success, #2fae60); }
.light-dot--amber { background: var(--tp-warning, #e6a23c); }
.light-dot--red { background: var(--tp-danger, #d54941); }
.light-dot--grey { background: var(--tp-text-muted, #909399); }
.drilldown-links {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 10px;
}
.caliber-block {
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid var(--tp-border-color);
}
/* Title on the left, the fold control on the right (view-state action trailing). */
.caliber-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.caliber-title {
  margin: 0 0 6px;
  font-weight: 600;
  font-size: 13px;
}
.caliber-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 24px;
  padding: 4px 8px;
  border: 1px solid var(--tp-border-color);
  border-radius: 4px;
  background: transparent;
  color: var(--tp-primary);
  font-size: 12px;
  cursor: pointer;
}
.caliber-toggle-icon {
  transition: transform 0.2s;
}
.caliber-toggle-icon.is-open {
  transform: rotate(90deg);
}
.caliber-block ul {
  margin: 0;
  padding-left: 18px;
  font-size: 12px;
  color: var(--tp-text-secondary);
}
.caliber-source {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--tp-text-muted, var(--tp-text-secondary));
}
</style>
