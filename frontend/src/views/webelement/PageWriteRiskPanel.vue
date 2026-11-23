<!--
 Page write-risk ledger panel: the read face of the survey guard's durable
 per-page risk book . Rows are pages that fired write-type requests
 outside their load baseline, plus the HTTP failures attributed to the probe
 window; the snapshot badge marks the sticky conservative downgrade a later
 probe must honor. Mounted inside the site-profile drawer below the survey
 records. Server-side paging + inline keyword keep the worst scale honest
 (S-group); the door is read-only — risk facts are probe observations.
-->
<template>
  <section class="risk-panel" data-test="write-risk-panel">
    <h4 class="risk-title">{{ t('siteProfile.riskTitle') }}</h4>
    <div class="risk-filter" data-test="risk-filter-bar">
      <el-input v-model="keyword" clearable :placeholder="t('siteProfile.riskKeywordPlaceholder')"
        class="risk-keyword" data-test="risk-keyword" @keyup.enter="search" @clear="search" />
      <el-button type="primary" :loading="loading" data-test="risk-search-btn" @click="search">
        {{ t('siteProfile.riskSearch') }}
      </el-button>
    </div>
    <el-table v-if="rows.length > 0" v-loading="loading" :data="rows" border size="small" data-test="risk-table">
      <el-table-column type="expand">
        <template #default="{ row }">
          <div class="risk-health" data-test="risk-health-card">
            <p class="risk-health-head">{{ t('siteProfile.riskFailHead') }}</p>
            <ul v-if="row.netFailures.length" class="risk-fail-list">
              <li v-for="(f, i) in row.netFailures" :key="i" class="risk-fail-row">
                <span class="risk-fail-req">{{ f.method }} {{ f.url }}</span>
                <span v-if="f.status" class="risk-fail-status">→ {{ f.status }}</span>
                <el-tag size="small" :type="f.errorType === 3 ? 'info' : 'danger'" effect="light">
                  {{ f.errorType === 3 ? t('siteProfile.riskTypeEnv') : t('siteProfile.riskTypeProduct') }}
                </el-tag>
                <span v-if="f.failureText" class="risk-fail-text">{{ f.failureText }}</span>
                <el-button size="small" link type="primary" data-test="risk-to-draft-btn"
                  :loading="draftBusy === draftKey(row.id, i)" :disabled="draftBusy !== null"
                  @click="toDraft(row, i)">
                  {{ t('siteProfile.riskToDraft') }}
                </el-button>
              </li>
            </ul>
            <p v-else class="risk-fail-none" data-test="risk-fail-none">{{ t('siteProfile.riskFailNone') }}</p>
            <p class="risk-health-foot">{{ t('siteProfile.riskHealthFoot') }}</p>
          </div>
        </template>
      </el-table-column>
      <el-table-column :label="t('siteProfile.riskColPage')" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">{{ row.pageUrl || row.pageKey }}</template>
      </el-table-column>
      <el-table-column :label="t('siteProfile.riskColSurface')" min-width="120">
        <template #default="{ row }">
          <el-tag v-for="verb in row.writeSurface" :key="verb" size="small" type="warning" effect="light"
            class="risk-verb">
            {{ verb }}
          </el-tag>
          <span v-if="row.writeSurface.length === 0">—</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('siteProfile.riskColEvents')" width="86">
        <template #default="{ row }">{{ row.writeEvents.length }}</template>
      </el-table-column>
      <el-table-column :label="t('siteProfile.riskColFailures')" width="86">
        <template #default="{ row }">{{ row.netFailures.length }}</template>
      </el-table-column>
      <el-table-column :label="t('siteProfile.riskColGuard')" width="104">
        <template #default="{ row }">
          <el-tag size="small" :type="row.snapshotOnly ? 'danger' : 'success'" effect="light"
            data-test="risk-snapshot-tag">
            {{ row.snapshotOnly ? t('siteProfile.riskSnapshotOn') : t('siteProfile.riskSnapshotOff') }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('siteProfile.riskColLastSeen')" width="150">
        <template #default="{ row }"><TimeCell :value="row.lastSeenAt" /></template>
      </el-table-column>
    </el-table>
    <div v-else-if="!loading" class="risk-empty" data-test="risk-empty">{{ t('siteProfile.riskEmpty') }}</div>
    <el-pagination v-if="total > PAGE_SIZE" v-model:current-page="page" :page-size="PAGE_SIZE" :total="total"
      layout="total, prev, pager, next" size="small" class="pagination-bar risk-pager" data-test="risk-pager"
      @current-change="load" />
  </section>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import TimeCell from '@/components/TimeCell.vue'
import { bizErrorMsg } from '@/utils/bizError'
import { listPageWriteProfiles, toCaseDraftFromFailure, type PageWriteProfileRow } from '@/api/pageWriteProfiles'

const props = defineProps<{ projectId: number | null }>()
const { t } = useI18n()

const PAGE_SIZE = 10
const rows = ref<PageWriteProfileRow[]>([])
const total = ref(0)
const page = ref(1)
const keyword = ref('')
const loading = ref(false)

/** One server round-trip; the ledger stays newest-activity-first server-side. */
async function load() {
  if (!props.projectId) {
    rows.value = []
    total.value = 0
    return
  }
  loading.value = true
  try {
    const res = await listPageWriteProfiles({
      projectId: props.projectId,
      page: page.value,
      pageSize: PAGE_SIZE,
      keyword: keyword.value.trim() || undefined,
    })
    rows.value = res.list
    total.value = res.total
  } catch (err) {
    ElMessage.error(bizErrorMsg(err, t('siteProfile.riskLoadFailed')))
  } finally {
    loading.value = false
  }
}

/** Keyword change restarts at page one so a filter never lands on a dead page. */
function search() {
  page.value = 1
  void load()
}

/** Single-flight conversion: one draft filing in flight at a time, keyed row:id. */
const draftBusy = ref<string | null>(null)
const draftKey = (profileId: number, index: number) => `${profileId}:${index}`

/**
 * File this booked failure as a pending case draft. The server refuses with
 * a three-part reason (value + rule + where to retry); a filed card lands
 * nowhere until confirmed in the pinned pending group, so the receipt points
 * there by its screen literal.
 */
async function toDraft(row: PageWriteProfileRow, index: number) {
  if (!props.projectId || draftBusy.value !== null) return
  draftBusy.value = draftKey(row.id, index)
  try {
    await toCaseDraftFromFailure({
      projectId: props.projectId,
      profileId: row.id,
      failureIndex: index,
    })
    ElMessage.success(t('siteProfile.riskToDraftOk', { n: index + 1 }))
  } catch (err) {
    ElMessage.error(bizErrorMsg(err, t('siteProfile.riskToDraftFailed')))
  } finally {
    draftBusy.value = null
  }
}

watch(() => props.projectId, () => search())

// The drawer lazy-renders its body, so the first mount is the first open;
// an initial read plus the project watch cover both that and header switches.
void load()
defineExpose({ reload: load })
</script>

<style scoped>
.risk-panel {
  margin-top: 18px;
}
.risk-title {
  margin: 0 0 10px;
  font-size: 14px;
  color: var(--el-text-color-primary);
}
.risk-filter {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}
.risk-keyword {
  max-width: 320px;
}
.risk-verb {
  margin-right: 4px;
}
.risk-empty {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  padding: 12px 0;
}
.risk-pager {
  margin-top: 10px;
  justify-content: flex-end;
}
.risk-health {
  padding: 6px 14px 10px 44px;
}
.risk-health-head {
  margin: 0 0 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.risk-fail-list {
  margin: 0;
  padding: 0;
  list-style: none;
}
.risk-fail-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  padding: 3px 0;
}
.risk-fail-req {
  font-family: var(--el-font-family-mono, monospace);
  word-break: break-all;
}
.risk-fail-status {
  color: var(--el-color-danger);
  font-weight: 600;
}
.risk-fail-text {
  color: var(--el-text-color-secondary);
}
.risk-fail-none {
  margin: 0 0 6px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.risk-health-foot {
  margin: 6px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
