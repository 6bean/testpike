<template>
  <div class="page-root" data-test="usage-calibration-page">
    <!-- AN-6: hosted inside the /cost card as a tab when `embedded` —
 the host already provides the page-card, so only the outer wrapper
 class switches (filters/tables/paging stay the single source). -->
    <div :class="embedded ? 'uc-embedded' : 'page-card'">
      <div class="toolbar">
        <div class="toolbar-right">
          <el-select v-model="days" style="width: 130px" data-test="usage-days" @change="loadAll">
            <el-option :label="t('usage.last7d')" :value="7" />
            <el-option :label="t('usage.last30d')" :value="30" />
            <el-option :label="t('usage.last90d')" :value="90" />
          </el-select>
          <el-select v-model="chain" clearable :placeholder="t('usage.chain')" style="width: 130px" data-test="usage-chain" @change="loadDetail(1)">
            <el-option :label="t('usage.chainExtract')" value="extract" />
            <el-option :label="t('usage.chainSummary')" value="summary" />
          </el-select>
          <el-input
            v-model="keyword"
            :placeholder="t('usage.keywordPlaceholder')"
            clearable
            style="width: 170px"
            data-test="usage-keyword"
            @keyup.enter="loadDetail(1)"
            @clear="loadDetail(1)"
          />
          <el-button type="primary" data-test="usage-search" @click="loadAll">
            <el-icon><Search /></el-icon>{{ t('common.search') }}
          </el-button>
        </div>
      </div>

      
      <TableSkeleton v-if="loadingAgg" :cols="6" data-test="usagecalibrationview-skeleton-agg" />
      <el-table border v-else :data="calibrations" data-test="usage-agg-table" style="width: 100%">
        <el-table-column prop="model" :label="t('usage.model')" min-width="150" show-overflow-tooltip />
        <el-table-column :label="t('usage.providerTag')" width="150">
          <template #default="{ row }">
            <el-tag size="small" :type="row.provider === 'mock' ? 'info' : 'primary'">
              {{ row.provider === 'mock' ? t('usage.mockProviderTag') : row.provider }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="sampleCount" :label="t('usage.samples')" width="90" align="right" />
        <el-table-column :label="t('usage.avgDeviation')" width="110" align="right">
          <template #default="{ row }">{{ pct(row.avgDeviationPct) }}</template>
        </el-table-column>
        <el-table-column :label="t('usage.p95Deviation')" width="110" align="right">
          <template #default="{ row }">
            <span :class="p95Class(row.p95AbsDeviationPct)" data-test="usage-p95">{{ pctAbs(row.p95AbsDeviationPct) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('usage.avgEst')" width="100" align="right">
          <template #default="{ row }">{{ fmtNum(row.avgEstTokens) }}</template>
        </el-table-column>
        <el-table-column :label="t('usage.avgUsage')" width="100" align="right">
          <template #default="{ row }">{{ fmtNum(row.avgUsageTokens) }}</template>
        </el-table-column>
        <el-table-column prop="lastSeenAt" :label="t('usage.lastSeen')" min-width="150">
          <template #default="{ row }"><TimeCell :iso="row.lastSeenAt" /></template>
        </el-table-column>
      </el-table>
      <EmptyState v-if="!loadingAgg && calibrations.length === 0" :title="t('usage.empty')" data-test="usage-empty" />

      <!--
 Server-side sorting runs over the whole sample window through the
 core whitelist (not a pageful reshuffle). The aggregate table above
 is a full-window GROUP BY ranking without a paging contract, so it
 stays honestly unsorted ( exemption; the sort contract lives
 on this detail table). -->
      <div class="detail-title">{{ t('usage.detailTitle') }}</div>
      <!-- The detail table must stay MOUNTED across refreshes: toggling it out
           of the DOM on every load (skeleton v-if / table v-else) destroyed the
           header sort store, so the 2nd header click restarted the ascending
           cycle and descending/clear were unreachable (probe evidence: th class
           prefix incremented per click, all requests carried sortDir=asc).
           Skeleton shows on the first load only; later refreshes keep the
           mounted table and use the built-in loading mask instead. -->
      <TableSkeleton v-if="loadingList && samples.length === 0" :cols="6" data-test="usagecalibrationview-skeleton-sample" />
      <el-table border v-show="!(loadingList && samples.length === 0)" :data="samples" data-test="usage-sample-table"
        style="width: 100%" v-loading="loadingList && samples.length > 0"
        @sort-change="onSortChange">
        <el-table-column prop="createdAt" :label="t('usage.time')" width="170" sortable="custom">
          <template #default="{ row }"><TimeCell :iso="row.createdAt" /></template>
        </el-table-column>
        <el-table-column prop="model" :label="t('usage.model')" min-width="130" show-overflow-tooltip sortable="custom" />
        <el-table-column prop="provider" :label="t('usage.provider')" width="100" sortable="custom" />
        <el-table-column prop="chain" :label="t('usage.chain')" width="90" sortable="custom">
          <template #default="{ row }">{{ chainLabel(row.chain) }}</template>
        </el-table-column>
        <el-table-column prop="projectId" :label="t('usage.project')" width="80" align="right" sortable="custom">
          <template #default="{ row }">{{ row.projectId ?? '—' }}</template>
        </el-table-column>
        <el-table-column prop="estTokens" :label="t('usage.est')" width="90" align="right" sortable="custom" />
        <el-table-column prop="usagePromptTokens" :label="t('usage.usageTrue')" width="90" align="right" sortable="custom" />
        <el-table-column prop="deviationRate" :label="t('usage.deviation')" width="110" align="right" sortable="custom">
          <template #default="{ row }">{{ ratePct(row.deviationRate) }}</template>
        </el-table-column>
        <!-- E9 two-state reading: 0 (no hit / provider silent) renders —,
             a hit shows the cached prompt-token count as-is -->
        <el-table-column prop="cachedTokens" :label="t('usage.cached')" width="120" align="right" sortable="custom" data-test="usage-cached-col">
          <template #default="{ row }">{{ row.cachedTokens > 0 ? fmtNum(row.cachedTokens) : '—' }}</template>
        </el-table-column>
        <el-table-column prop="latencyMs" :label="t('usage.latency')" width="90" align="right" sortable="custom">
          <template #default="{ row }">{{ row.latencyMs }} ms</template>
        </el-table-column>
      </el-table>
      <div v-if="total > 0" class="pagination-bar">
        <el-pagination
          v-model:current-page="page"
          :page-size="size"
          :total="total"
          layout="total, prev, pager, next"
          data-test="usage-pager"
          @current-change="loadDetail"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { Search } from '@element-plus/icons-vue'
import {
  getLlmUsageCalibration,
  getLlmUsageSamples,
  type UsageCalibrationView,
  type UsageSampleView,
} from '@/api/llmUsage'
import EmptyState from '@/components/EmptyState.vue'

const { t } = useI18n()

// embedded=true renders without its own page-card (host tab, merge).
const props = withDefaults(defineProps<{ embedded?: boolean }>(), { embedded: false })
const embedded = computed(() => props.embedded)

const days = ref(7)
const chain = ref<string | undefined>(undefined)
const keyword = ref('')
const loadingAgg = ref(false)
const loadingList = ref(false)
const calibrations = ref<UsageCalibrationView[]>([])
const samples = ref<UsageSampleView[]>([])
const page = ref(1)
const size = 20
const total = ref(0)

// Column prop -> core usage-sample SORT_COLUMNS whitelist key (single source
// for the sortable decision and the request payload; . Sorting
// happens server-side over the whole sample window, not the loaded pageful.
const SORT_KEY_MAP: Record<string, string> = {
  createdAt: 'created_at',
  model: 'model',
  provider: 'provider',
  chain: 'chain',
  projectId: 'project_id',
  estTokens: 'est_tokens',
  usagePromptTokens: 'usage_prompt_tokens',
  deviationRate: 'deviation_rate',
  latencyMs: 'latency_ms',
  cachedTokens: 'cached_tokens',
}
const query = reactive<{ sortBy: string; sortDir: string }>({ sortBy: '', sortDir: '' })

/** Header sort: forward the whitelisted key to core; clearing a sort falls
 * back to the historical id DESC default order; page resets to 1. */
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
  void loadDetail(1)
}

function chainLabel(v: string) {
  return v === 'summary' ? t('usage.chainSummaryShort') : v === 'extract' ? t('usage.chainExtractShort') : v
}

function pct(v: number | null | undefined) {
  if (v === null || v === undefined) return '—'
  return `${v > 0 ? '+' : ''}${v.toFixed(1)}%`
}

function pctAbs(v: number | null | undefined) {
  return v === null || v === undefined ? '—' : `${v.toFixed(1)}%`
}

function ratePct(v: number | null | undefined) {
  return v === null || v === undefined ? '—' : pct(v * 100)
}

function fmtNum(v: number | null | undefined) {
  return v === null || v === undefined ? '—' : Math.round(v).toLocaleString()
}


function p95Class(v: number | null | undefined) {
  if (v === null || v === undefined) return ''
  if (v < 10) return 'dev-green'
  if (v < 20) return 'dev-yellow'
  return 'dev-red'
}

async function loadAgg() {
  loadingAgg.value = true
  try {
    calibrations.value = await getLlmUsageCalibration(days.value)
  } finally {
    loadingAgg.value = false
  }
}

async function loadDetail(to = 1) {
  page.value = to
  loadingList.value = true
  try {
    const resp = await getLlmUsageSamples({
      chain: chain.value || undefined,
      keyword: keyword.value.trim() || undefined,
      page: to,
      size,
      sortBy: query.sortBy || undefined,
      sortDir: query.sortDir || undefined,
    })
    samples.value = resp.list
    total.value = resp.total
  } finally {
    loadingList.value = false
  }
}

function loadAll() {
  loadAgg()
  loadDetail(1)
}

onMounted(loadAll)
</script>

<style scoped>
.detail-title {
  margin: 16px 0 8px;
  font-weight: 600;
}
.dev-green { color: #2e7d32; font-weight: 600; }
.dev-yellow { color: #b8860b; font-weight: 600; }
.dev-red { color: #c00000; font-weight: 600; }

</style>
