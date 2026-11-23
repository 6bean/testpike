<template>
  <div class="page-root" data-test="notif-center-page">
    <div class="page-card">
      <el-tabs v-model="activeTab" data-test="notif-tabs">
        <el-tab-pane :label="t('notification.tabList')" name="list">
      <div class="toolbar">
        <div class="toolbar-right">
          <el-select
            v-model="filters.eventType"
            :placeholder="t('notification.typePlaceholder')"
            clearable
            style="width: 170px"
            data-test="notif-type-filter"
            filterable
            @change="load(1)"
          >
            <el-option v-for="opt in EVENT_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
          <el-select
            v-model="filters.readFlag"
            :placeholder="t('notification.allStatus')"
            clearable
            style="width: 130px"
            data-test="notif-read-filter"
            @change="load(1)"
          >
            <el-option :label="t('notification.stUnread')" :value="0" />
            <el-option :label="t('notification.stRead')" :value="1" />
          </el-select>
          <el-button type="primary" data-test="notif-search" @click="load(1)">
            <el-icon><Search /></el-icon>{{ t('notification.searchBtn') }}
          </el-button>
          <el-button
            type="success"
            plain
            :disabled="unreadTotal === 0"
            data-test="notif-read-all-btn"
            @click="readAll"
          >
            <el-icon><Check /></el-icon>{{ t('notification.readAll') }}<template v-if="unreadTotal > 0">（{{ unreadTotal }}）</template>
          </el-button>
          <!-- R25 mode declaration (control placement, TB group): this page carries two tabs and
               the prefs tab has no table, so the column-settings popover rides the list tab's own
               toolbar — control container equals effective scope, instead of a page-level control
               that would silently change nothing while the prefs tab is open. -->
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

      <TableSkeleton v-if="loading" :cols="colCfg.visibleColumns.value.length + 1" data-test="notificationcenterview-skeleton" />
      <el-table border :data="list" data-test="notif-table" row-key="id" :row-class-name="rowClass"
        :size="colCfg.state.density"
        @sort-change="onSortChange" v-else>
        <el-table-column
          v-for="col in colCfg.visibleColumns.value"
          :key="col.key"
          :prop="col.key"
          :fixed="col.fixed || false"
          :label="t(col.labelKey)"
          :width="col.width ?? undefined"
          :min-width="col.width ? undefined : NOTIF_FLEX_MIN[col.key]"
          :sortable="NOTIF_SORTABLE.has(col.key) ? 'custom' : false"
          :show-overflow-tooltip="col.key === 'content'"
          :data-test="`notif-col-${col.key}`"
        >
          <template #default="{ row }">
            <el-tag v-if="col.key === 'eventType'" :type="eventTagType(row.eventType)" size="small">
              {{ eventLabel(row.eventType) }}
            </el-tag>
            <template v-else-if="col.key === 'title'">
              <span class="notif-title" :data-test="`notif-row-${row.id}`">{{ row.title }}</span>
              <span v-if="row.readFlag === 0" class="unread-dot" data-test="notif-unread-dot" />
            </template>
            <span v-else-if="col.key === 'readFlag'" :class="row.readFlag === 0 ? 'state-unread' : 'state-read'">
              {{ row.readFlag === 0 ? t('notification.stUnread') : t('notification.stRead') }}
            </span>
            <span v-else-if="col.key === 'createdAt'" :title="row.createdAt">{{ timeAgo(row.createdAt) }}</span>
            <span v-else>{{ row.content }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.action')" width="200" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.readFlag === 0"
              link
              type="primary"
              size="small"
              :data-test="`notif-mark-read-${row.id}`"
              @click="markOne(row)"
            >
              {{ t('notification.markRead') }}
            </el-button>
            <el-button
              v-if="notificationTarget(row)"
              link
              type="primary"
              size="small"
              :data-test="`notif-goto-${row.id}`"
              @click="goto(row)"
            >
              {{ t('notification.gotoSource') }}
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <EmptyState
            :filtered="hasActiveFilter"
            :title="hasActiveFilter ? t('notification.emptyFiltered') : t('notification.emptyTitle')"
            :hint="hasActiveFilter ? t('notification.emptyFilteredHint') : t('notification.emptyHint')"
            @clear-filter="clearNotifFilters"
          />
        </template>
      </el-table>

      <div class="pagination-bar">
        <el-pagination v-if="total > 0"
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          data-test="notif-pager"
          @current-change="load()"
          @size-change="load(1)"
          />
      </div>
        </el-tab-pane>
        <el-tab-pane :label="t('notification.tabPrefs')" name="prefs" data-test="notif-tab-prefs">
          <SubscriptionSettingsPanel />
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<script setup lang="ts">

import { computed, onMounted, reactive, ref, watch } from "vue"
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Check } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import { timeAgo } from '@/utils/timeAgo'
import ColumnSettings from '@/components/ColumnSettings.vue'
import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'
import SubscriptionSettingsPanel from './SubscriptionSettingsPanel.vue'
import {
  eventLabel,
  eventTagType,
  NOTIFICATION_EVENT_KEYS,
  listNotifications,
  markAllNotificationsRead,
  markNotificationRead,
  notificationTarget,
  type NotificationItem,
} from '@/api/notification'

const { t } = useI18n()

const router = useRouter()


const activeTab = ref<'list' | 'prefs'>('list')

/**
 * Inbox table column definitions for the shared column-config piece (N-3 repayment).
 * Widths mirror the previous hard-coded el-table-column attributes so the default
 * layout is unchanged; hide/reorder/pin/density then persist per user via the piece.
 * The trailing action column stays pinned outside the loop (locked column, not configurable).
 */
const NOTIF_COLUMNS: ColumnDef[] = [
  { key: 'eventType', labelKey: 'notification.colType', width: 150 },
  { key: 'title', labelKey: 'notification.colTitle', width: null },
  { key: 'content', labelKey: 'notification.colContent', width: null },
  { key: 'readFlag', labelKey: 'common.status', width: 90 },
  { key: 'createdAt', labelKey: 'notification.colTime', width: 170 },
]
/** Flexible columns fall back to min-width when no explicit width is set. */
const NOTIF_FLEX_MIN: Record<string, number> = { title: 220, content: 280 }
/** Columns whose ordering is resolved server-side (sortable="custom" + @sort-change). */
const NOTIF_SORTABLE = new Set(['eventType', 'title', 'readFlag', 'createdAt'])
const colCfg = useColumnConfig('notification', NOTIF_COLUMNS)
/** Popover rows (column state merged with labelKey for rendering). */
const settingsRows = computed(() =>
  colCfg.state.columns.map((c) => ({ ...c, labelKey: NOTIF_COLUMNS.find((d) => d.key === c.key)!.labelKey })),
)

const list = ref<NotificationItem[]>([])
const total = ref(0)
const unreadTotal = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const filters = ref<{ eventType?: string; readFlag?: number }>({})

// Column prop -> core notification SORT_COLUMNS whitelist key (single source
// for the sortable decision and the request payload; the free-text content
// column and the composed action column honestly stay unsorted). Sorting
// happens server-side over the whole scoped window, not the loaded page.
const SORT_KEY_MAP: Record<string, string> = {
  eventType: 'event_type',
  title: 'title',
  readFlag: 'read_flag',
  createdAt: 'created_at',
}
const query = reactive<{ sortBy: string; sortDir: string }>({ sortBy: '', sortDir: '' })

/** Header sort: forward the whitelisted key to core; clearing a sort falls
 * back to the historical newest-first order; page resets to 1. */
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


const EVENT_OPTIONS = computed(() => Object.keys(NOTIFICATION_EVENT_KEYS)
  .map((value) => ({ value, label: t(NOTIFICATION_EVENT_KEYS[value]) })))

function rowClass({ row }: { row: NotificationItem }): string {
  return row.readFlag === 0 ? 'row-unread' : ''
}


function emitChanged(): void {
  window.dispatchEvent(new Event('tp-notification-changed'))
}

async function load(p: number = page.value): Promise<void> {
  loading.value = true
  try {
    const [r, unread] = await Promise.all([
      listNotifications({
        eventType: filters.value.eventType || undefined,
        readFlag: filters.value.readFlag,
        page: p,
        size: size.value,
        sortBy: query.sortBy || undefined,
        sortDir: query.sortDir || undefined,
      }),
      listNotifications({ readFlag: 0, page: 1, size: 1 }),
    ])
    list.value = r.list
    total.value = r.total
    unreadTotal.value = unread.total
    page.value = p
  } catch (e) {
    ElMessage.error((e as Error)?.message || t('notification.loadFail'))
  } finally {
    loading.value = false
  }
}

async function markOne(row: NotificationItem): Promise<void> {
  try {
    await markNotificationRead(row.id)
    row.readFlag = 1
    unreadTotal.value = Math.max(0, unreadTotal.value - 1)
    emitChanged()
  } catch (e) {
    ElMessage.error((e as Error)?.message || t('notification.markReadFail'))
  }
}

async function readAll(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      t('notification.readAllConfirm', { n: unreadTotal.value }),
      t('notification.readAll'), { type: 'warning', confirmButtonText: t('notification.readAll'), cancelButtonText: t('common.cancel') })
  } catch {
    return
  }
  try {
    const { affected } = await markAllNotificationsRead()
    list.value.forEach((n) => { n.readFlag = 1 })
    unreadTotal.value = 0
    ElMessage.success(affected > 0 ? t('notification.readAllDone', { n: affected }) : t('notification.readAllNone'))
    emitChanged()
    void load()
  } catch (e) {
    ElMessage.error((e as Error)?.message || t('notification.readAllFail'))
  }
}

function goto(row: NotificationItem): void {
  const target = notificationTarget(row)
  if (!target) {
    ElMessage.info(t('notification.noSource'))
    return
  }
  if (row.readFlag === 0) void markOne(row)
  void router.push(target)
}

onMounted(() => void load(1))


watch(activeTab, (tab) => {
  if (tab === 'list') void load(1)
})

const hasActiveFilter = computed(() => filters.value.eventType !== undefined || filters.value.readFlag !== undefined)
function clearNotifFilters(): void { filters.value = {}; load() }
</script>

<style scoped>
.notif-title {
  font-weight: 500;
}
.unread-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  margin-left: 6px;
  vertical-align: middle;
  background: var(--el-color-danger);
  border-radius: 50%;
}
.state-unread {
  color: var(--el-color-danger);
}
.state-read {
  color: var(--tp-text-secondary);
}
:deep(.row-unread) {
  --el-table-tr-bg-color: var(--el-fill-color-lighter);
}
</style>
