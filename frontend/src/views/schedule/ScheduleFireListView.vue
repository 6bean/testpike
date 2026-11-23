<template>
  <div class="page-root schedule-fire-page" data-test="schedule-fire-page">
    <!-- Embedded mode : hosted by the batches page tab, whose own
 page-card already frames the content, so the inner card is dropped to
 avoid a frame-in-frame. Standalone classes kept for redirect-era
 tooling (E2E anchors on schedule-fire-page either way). -->
    <div :class="embedded ? 'schedule-fires-embedded' : 'page-card'">
      <!-- (R21 bands): the header band carried only the refresh
 button — a view-state action — so the whole header row is dropped
 (page name rides the global top bar per R23) and refresh moved into
 the filter-bar trailing slot. -->
      <div class="filter-bar" data-test="schedule-fire-filter-bar">
          <el-select v-model="filters.kind" :placeholder="t('schedule.kindAll')" clearable
            style="width: 150px" data-test="schedule-filter-kind" @change="load(1)">
            <el-option :label="t('schedule.kindSuite')" value="suite" />
            <el-option :label="t('schedule.kindTemplate')" value="template" />
          </el-select>
          <el-select v-model="filters.status" :placeholder="t('schedule.statusAll')" clearable
            style="width: 140px" data-test="schedule-filter-status" @change="load(1)">
            <el-option :label="t('schedule.statusSuccess')" value="success" />
            <el-option :label="t('schedule.statusFailed')" value="failed" />
            <el-option :label="t('schedule.statusFired')" value="fired" />
          </el-select>
          <div class="filter-bar-actions">
            <el-button :icon="Refresh" :disabled="!projectId" data-test="schedule-reload-btn" @click="load()">
              {{ t('schedule.refresh') }}
            </el-button>
          </div>
        </div>

      <p class="page-hint" data-test="schedule-page-hint">{{ t('schedule.pageHint') }}</p>

      <TableSkeleton v-if="loading" :cols="6" data-test="schedulefirelistview-skeleton" />
      <el-table border :data="rows" data-test="schedule-fire-table" class="data-table" row-key="id"
        @sort-change="onSortChange" v-else>
        <el-table-column prop="fireTime" :label="t('schedule.fireTime')" width="170" sortable="custom">
          <template #default="{ row }"><TimeCell :iso="row.fireTime" /></template>
        </el-table-column>
        <el-table-column prop="kind" :label="t('schedule.kind')" width="120" sortable="custom">
          <template #default="{ row }">
            <el-tag size="small" :type="row.kind === 'suite' ? 'primary' : 'warning'" data-test="schedule-kind-tag">
              {{ KIND_LABEL[row.kind] ?? row.kind }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="refId" :label="t('schedule.ref')" width="130" align="center" sortable="custom">
          <template #default="{ row }">#{{ row.refId }}</template>
        </el-table-column>
        <el-table-column prop="status" :label="t('schedule.status')" width="110" sortable="custom">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small" data-test="schedule-status-tag">
              {{ STATUS_LABEL[row.status] ?? row.status }}
            </el-tag>
          </template>
        </el-table-column>
        
        <el-table-column :label="t('schedule.message')" min-width="320">
          <template #default="{ row }">
            <span class="message-cell" data-test="schedule-message-cell">{{ row.message || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" :label="t('schedule.createdAt')" width="170" sortable="custom">
          <template #default="{ row }"><TimeCell :iso="row.createdAt" /></template>
        </el-table-column>
        <template #empty>
          <el-empty :description="t('schedule.emptyNoData')" />
        </template>
      </el-table>

      <div class="pagination-bar">
        <el-pagination v-if="total > 0"
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          data-test="schedule-pagination"
          @current-change="load()"
          @size-change="load(1)"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { Refresh } from '@element-plus/icons-vue'
import { useKeepAliveList } from '@/composables/useKeepAliveList'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import TimeCell from '@/components/TimeCell.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import { listScheduleFires, type ScheduleFireView } from '@/api/schedule'

defineOptions({ name: 'ScheduleFireList' })

/** half-B merge host flag: rendered inside the batches page tab when
 * true (inner frame card suppressed — the host card already frames it; its
 * own table/pagination/sort stay the single source). Default false keeps
 * standalone semantics for any leftover link. */
const props = withDefaults(defineProps<{ embedded?: boolean }>(), { embedded: false })
const embedded = computed(() => props.embedded)

const { t } = useI18n()

const { projectId, followTick } = useProjectPageContext()
const rows = ref<ScheduleFireView[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const filters = reactive<{ kind: string | null; status: string | null }>({ kind: null, status: null })
// Column prop -> core schedule-fire SORT_COLUMNS whitelist key (single source
// for the sortable decision and the request payload — columns outside the
// whitelist never emit sort params, no client-only fake sort). The message
// column stays unsorted: it has no sortable meaning and no whitelist entry.
const SORT_KEY_MAP: Record<string, string> = {
  fireTime: 'fire_time',
  kind: 'kind',
  refId: 'ref_id',
  status: 'status',
  createdAt: 'created_at',
}
// Server-side sort state (same shape as the mock scenario/suite pages).
const query = reactive<{ sortBy: string; sortDir: string }>({ sortBy: '', sortDir: '' })

/** Header sort: forward the whitelisted key to the server (ORDER BY happens
 * in core SQL); clearing a sort falls back to the historical fire_time DESC
 * order; page resets to 1 so paging windows stay consistent. */
function onSortChange({ prop, order }: { prop: string; order: string | null }): void {
  if (order === null) {
    query.sortBy = ''
    query.sortDir = ''
    page.value = 1
    void load()
    return
  }
  const key = SORT_KEY_MAP[prop]
  if (!key) return
  query.sortBy = key
  query.sortDir = order === 'ascending' ? 'asc' : 'desc'
  page.value = 1
  void load()
}

const KIND_LABEL = computed<Record<string, string>>(() => ({
  suite: t('schedule.kindSuite'),
  template: t('schedule.kindTemplate'),
}))
const STATUS_LABEL = computed<Record<string, string>>(() => ({
  fired: t('schedule.statusFired'),
  success: t('schedule.statusSuccess'),
  failed: t('schedule.statusFailed'),
}))

function statusTag(status: string): 'info' | 'success' | 'danger' | 'primary' {
  if (status === 'success') return 'success'
  if (status === 'failed') return 'danger'
  return 'info'
}

async function load(p = page.value): Promise<void> {
  if (!projectId.value) return
  page.value = p
  loading.value = true
  try {
    const res = await listScheduleFires({
      projectId: projectId.value, kind: filters.kind || undefined,
      status: filters.status || undefined, page: page.value, size: size.value,
      sortBy: query.sortBy || undefined, sortDir: query.sortDir || undefined,
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


onMounted(() => {
  if (projectId.value) load(1)
})

watch(followTick, () => {
  if (projectId.value) void load(1)
})

useKeepAliveList(async () => {
  if (projectId.value) await load()
})
</script>

<style scoped>
.page-root {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.page-hint {
  margin: 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.message-cell {
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
