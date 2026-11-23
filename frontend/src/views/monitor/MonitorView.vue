<script setup lang="ts">

import { computed, ref, watch, watchEffect } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CaretRight } from '@element-plus/icons-vue'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import EmptyState from '@/components/EmptyState.vue'
import TargetCreateWizard from './TargetCreateWizard.vue'
import { bizErrorMsg } from '@/utils/bizError'
import { envTypeLabel, getProjectEnvs, type ProjectEnv } from '@/api/project'
import {
  clearMonitorSilence,
  deleteMonitorTarget,
  getLinkedAssets,
  getOpenEpisode,
  listMonitorCheckLogs,
  pageMonitorTargets,
  setMonitorTargetEnabled,
  silenceMonitorTarget,
  type MonitorCheckLogItem,
  type MonitorLinkedAssets,
  type MonitorTargetItem,
} from '@/api/monitor'

const { t } = useI18n()

// R30: the page no longer hosts its own project picker — the header global
// context is the single switch. Wiring piece = URL-first chain + follow-header.
const { projectId } = useProjectPageContext()
const route = useRoute()
const router = useRouter()
const envs = ref<ProjectEnv[]>([])
const targets = ref<MonitorTargetItem[]>([])
const total = ref(0)
const loading = ref(false)

const DEFAULT_PAGE_SIZE = 200
const keywordInput = ref(strOf(route.query.keyword))

function strOf(v: unknown): string {
  return typeof v === 'string' ? v : ''
}
function intOf(v: unknown, d: number): number {
  const n = typeof v === 'string' ? Number.parseInt(v, 10) : NaN
  return Number.isFinite(n) && n > 0 ? n : d
}


const wallQuery = computed(() => ({
  status: strOf(route.query.status),
  targetType: strOf(route.query.type),
  keyword: strOf(route.query.keyword),
  page: intOf(route.query.page, 1),
  pageSize: intOf(route.query.size, DEFAULT_PAGE_SIZE),
}))
const page = computed(() => wallQuery.value.page)
const pageSize = computed(() => wallQuery.value.pageSize)
const hasFilters = computed(() =>
  !!(wallQuery.value.status || wallQuery.value.targetType || wallQuery.value.keyword),
)


function gotoWindow(sel: { status: string; type: string; keyword: string }, nextPage: number, nextSize: number) {
  void router.replace({
    query: {
      ...route.query,
      status: sel.status || undefined,
      type: sel.type || undefined,
      keyword: sel.keyword || undefined,
      page: nextPage > 1 ? String(nextPage) : undefined,
      size: nextSize !== DEFAULT_PAGE_SIZE ? String(nextSize) : undefined,
    },
  })
}
const statusFilter = computed({
  get: () => wallQuery.value.status,
  set: (v: string) => gotoWindow({ status: v || '', type: wallQuery.value.targetType, keyword: wallQuery.value.keyword }, 1, pageSize.value),
})
const typeFilter = computed({
  get: () => wallQuery.value.targetType,
  set: (v: string) => gotoWindow({ status: wallQuery.value.status, type: v || '', keyword: wallQuery.value.keyword }, 1, pageSize.value),
})
function applySearch() {
  gotoWindow({ status: wallQuery.value.status, type: wallQuery.value.targetType, keyword: keywordInput.value.trim() }, 1, pageSize.value)
}
function clearFilters() {
  keywordInput.value = ''
  gotoWindow({ status: '', type: '', keyword: '' }, 1, pageSize.value)
}
watch(() => route.query.keyword, (v) => { keywordInput.value = strOf(v) })

const STATUS_OPTIONS = computed(() => [
  { value: 'DISABLED', label: t('monitor.stateDisabled') },
  { value: 'SILENCED', label: t('monitor.stateSilenced') },
  { value: 'UP', label: t('monitor.state.UP') },
  { value: 'DOWN', label: t('monitor.state.DOWN') },
  { value: 'FLAPPING', label: t('monitor.state.FLAPPING') },
  { value: 'RECOVERING', label: t('monitor.state.RECOVERING') },
  { value: 'UNKNOWN', label: t('monitor.state.UNKNOWN') },
])
const TYPE_OPTIONS = ['HTTP', 'LOGIN', 'ONLINE', 'DB', 'CERT']

const drawerOpen = ref(false)
const current = ref<MonitorTargetItem | null>(null)
const checkLogs = ref<MonitorCheckLogItem[]>([])
const logsLoading = ref(false)
const openRed = ref(false)
const linkedAssets = ref<MonitorLinkedAssets | null>(null)
const assetsLoading = ref(false)


const groups = computed(() => {
  const byEnv = new Map<number, { env: ProjectEnv | null; items: MonitorTargetItem[] }>()
  const envById = new Map(envs.value.map((e) => [e.id, e]))
  for (const tg of targets.value) {
    const g = byEnv.get(tg.envId) ?? { env: envById.get(tg.envId) ?? null, items: [] }
    g.items.push(tg)
    byEnv.set(tg.envId, g)
  }
  return [...byEnv.entries()].map(([envId, g]) => ({ envId, env: g.env, items: g.items }))
})


const collapsedEnvs = ref<Set<number>>(new Set())
function isCollapsed(envId: number): boolean {
  return collapsedEnvs.value.has(envId)
}
function toggleGroup(envId: number) {
  const next = new Set(collapsedEnvs.value)
  if (next.has(envId)) next.delete(envId)
  else next.add(envId)
  collapsedEnvs.value = next
}


function tileClass(tg: MonitorTargetItem): string {
  if (!tg.enabled) return 'tile tile--disabled'
  if (tg.silenceUntil) return 'tile tile--silenced'
  if (tg.lastState === 'DOWN') return 'tile tile--down'
  if (tg.lastState === 'FLAPPING') return 'tile tile--flapping'
  if (tg.lastState === 'RECOVERING') return 'tile tile--recovering'
  if (tg.lastState === 'UP') return 'tile tile--up'
  return 'tile tile--unknown'
}

function stateLabel(tg: MonitorTargetItem): string {
  if (!tg.enabled) return t('monitor.stateDisabled')
  if (tg.silenceUntil) return t('monitor.stateSilenced')
  const s = tg.lastState || 'UNKNOWN'
  return t(`monitor.state.${s}`)
}

const TYPE_LABELS: Record<string, string> = {
  HTTP: 'HTTP', LOGIN: 'LOGIN', ONLINE: 'ONLINE', DB: 'DB', CERT: 'SSL',
}
function typeLabel(tt: string): string {
  return TYPE_LABELS[tt] ?? tt
}

async function loadAll() {
  if (!projectId.value) {
    targets.value = []
    envs.value = []
    total.value = 0
    return
  }
  loading.value = true
  const q = wallQuery.value
  try {
    const [envList, pageView] = await Promise.all([
      getProjectEnvs(projectId.value),
      pageMonitorTargets(projectId.value, {
        page: q.page,
        pageSize: q.pageSize,
        status: q.status || undefined,
        targetType: q.targetType || undefined,
        keyword: q.keyword || undefined,
      }),
    ])
    envs.value = envList
    targets.value = pageView.list
    total.value = pageView.total
  } catch (e) {
    targets.value = []
    envs.value = []
    total.value = 0
    void bizErrorMsg(e, t('monitor.loadFail'))
  } finally {
    loading.value = false
  }
}

async function openDetail(tg: MonitorTargetItem) {
  current.value = tg
  drawerOpen.value = true
  checkLogs.value = []
  openRed.value = false
  linkedAssets.value = null
  logsLoading.value = true
  try {
    const [logs, episode] = await Promise.all([
      listMonitorCheckLogs(tg.id, 200),
      getOpenEpisode(tg.projectId, tg.envId),
    ])
    checkLogs.value = logs
    openRed.value = !!episode
  } catch (e) {
    void bizErrorMsg(e, t('monitor.detailLoadFail'))
  } finally {
    logsLoading.value = false
  }
}


async function onAssetsHover() {
  const tg = current.value
  if (!tg || linkedAssets.value || assetsLoading.value) return
  assetsLoading.value = true
  try {
    linkedAssets.value = await getLinkedAssets(tg.projectId, tg.envId)
  } catch (e) {
    void bizErrorMsg(e, t('monitor.assetsLoadFail'))
  } finally {
    assetsLoading.value = false
  }
}

async function toggleEnabled(tg: MonitorTargetItem) {
  try {
    await setMonitorTargetEnabled(tg.id, !tg.enabled)
    ElMessage.success(tg.enabled ? t('monitor.pausedOk') : t('monitor.resumedOk'))
    await loadAll()
  } catch (e) {
    void bizErrorMsg(e, t('monitor.opFail'))
  }
}

async function silence(tg: MonitorTargetItem) {
  try {
    const { value } = await ElMessageBox.prompt(t('monitor.silencePrompt'), t('monitor.silenceTitle'), {
      inputPlaceholder: t('monitor.silenceReasonPh'),
      inputValidator: (v: string) => (v && v.trim() ? true : t('monitor.silenceReasonRequired')),
    })
    await silenceMonitorTarget(tg.id, { minutes: 60, reason: value.trim() })
    ElMessage.success(t('monitor.silenceOk'))
    await loadAll()
  } catch (e) {
    if (e === 'cancel' || e === 'close') return
    void bizErrorMsg(e, t('monitor.opFail'))
  }
}

async function clearSilence(tg: MonitorTargetItem) {
  try {
    await clearMonitorSilence(tg.id)
    ElMessage.success(t('monitor.silenceCleared'))
    await loadAll()
  } catch (e) {
    void bizErrorMsg(e, t('monitor.opFail'))
  }
}

async function removeTarget(tg: MonitorTargetItem) {
  try {
    await ElMessageBox.confirm(t('monitor.deleteConfirm', { name: tg.name }), t('monitor.deleteTitle'), {
      type: 'warning',
    })
    await deleteMonitorTarget(tg.id)
    ElMessage.success(t('monitor.deleteOk'))
    if (current.value?.id === tg.id) drawerOpen.value = false
    await loadAll()
  } catch (e) {
    if (e === 'cancel' || e === 'close') return
    void bizErrorMsg(e, t('monitor.opFail'))
  }
}

function fmtTime(v: string | null): string {
  return v ? v.replace('T', ' ').slice(0, 19) : '—'
}


const trendBars = computed(() =>
  [...checkLogs.value]
    .slice(0, 60)
    .reverse()
    .map((l) => ({
      key: l.checkedAt,
      cls: l.outcome === 'ok' ? 'bar bar--ok' : l.outcome === 'fail' ? 'bar bar--fail' : 'bar bar--deferred',
      tip: `${fmtTime(l.checkedAt)} · ${t(`monitor.outcome.${l.outcome}`)}${l.latencyMs != null ? ` ${l.latencyMs}ms` : ''}${l.detail ? ` · ${l.detail}` : ''}`,
    })),
)

const recentFail = computed(() => checkLogs.value.find((l) => l.outcome === 'fail') ?? null)

// R30 header context + URL page state drive the segment: refetch whenever the
// active project or any window parameter changes (initial mount included).
watchEffect(() => {
  if (projectId.value != null) void loadAll()
})

const wizardRef = ref<InstanceType<typeof TargetCreateWizard> | null>(null)
function openWizard() {
  wizardRef.value?.openWizard()
}
</script>

<template>
  <!-- tp-page-root-exempt: root carries its own 16px padding (live-measured
       1440x900), content edge gap present without the theme .page-root. -->
  <div class="monitor-page" data-test="monitor-page">
    <div class="monitor-page__header">
      <div>
        <!-- stage-3: the page-name h2 retired (topbar is the single
 source); the tile-legend description line stays. -->
        <p class="monitor-page__desc">{{ t('monitor.pageDesc') }}</p>
      </div>
    </div>

    
    <div class="filter-bar" data-test="monitor-filter-bar">
      <el-select
        v-model="statusFilter"
        :placeholder="t('monitor.statusFilterPh')"
        clearable
        filterable
        style="width: 150px"
        data-test="monitor-status-filter"
      >
        <el-option v-for="o in STATUS_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
      </el-select>
      <el-select
        v-model="typeFilter"
        :placeholder="t('monitor.typeFilterPh')"
        clearable
        filterable
        style="width: 140px"
        data-test="monitor-type-filter"
      >
        <el-option v-for="o in TYPE_OPTIONS" :key="o" :label="typeLabel(o)" :value="o" />
      </el-select>
      <el-input
        v-model="keywordInput"
        :placeholder="t('monitor.keywordPh')"
        clearable
        style="width: 220px"
        data-test="monitor-keyword-input"
        @keyup.enter="applySearch"
        @clear="applySearch"
      />
      <el-button data-test="monitor-query-btn" @click="applySearch">{{ t('monitor.query') }}</el-button>
      <div class="filter-bar-actions">
        <el-button type="primary" :disabled="!projectId" data-test="monitor-create-btn" @click="openWizard">
          {{ t('monitor.createTarget') }}
        </el-button>
      </div>
    </div>

    <el-skeleton v-if="loading" :rows="4" animated />

    <EmptyState
      v-else-if="!projectId"
      icon="Monitor"
      :title="t('monitor.noProject')"
      :hint="t('monitor.noProjectHint')"
    />
    <EmptyState
      v-else-if="!targets.length"
      :filtered="hasFilters"
      icon="Monitor"
      :title="hasFilters ? t('common.emptyFilteredResult') : t('monitor.empty')"
      :hint="hasFilters ? t('common.emptyFilteredHint') : t('monitor.emptyHint')"
      @clear-filter="clearFilters"
    />

    <div v-else class="monitor-matrix" data-test="monitor-matrix">
      <section v-for="g in groups" :key="g.envId" class="monitor-group" data-test="monitor-group">
        <header
          class="monitor-group__head"
          role="button"
          tabindex="0"
          :aria-expanded="!isCollapsed(g.envId)"
          :aria-label="isCollapsed(g.envId) ? t('monitor.expandGroup') : t('monitor.collapseGroup')"
          data-test="monitor-group-toggle"
          @click="toggleGroup(g.envId)"
          @keydown.enter.prevent="toggleGroup(g.envId)"
        >
          <el-icon class="monitor-group__caret" :class="{ 'is-collapsed': isCollapsed(g.envId) }">
            <CaretRight />
          </el-icon>
          <span class="monitor-group__env">{{ g.env ? g.env.name : `#${g.envId}` }}</span>
          <el-tag v-if="g.env" size="small" effect="plain">{{ envTypeLabel(g.env.envType) }}</el-tag>
          <span class="monitor-group__count">{{ g.items.length }}</span>
        </header>
        <div v-if="!isCollapsed(g.envId)" class="monitor-group__tiles">
          <div
            v-for="tg in g.items"
            :key="tg.id"
            :class="tileClass(tg)"
            data-test="monitor-tile"
            role="button"
            tabindex="0"
            @click="openDetail(tg)"
            @keydown.enter="openDetail(tg)"
          >
            <div class="tile__top">
              <span class="tile__name">{{ tg.name }}</span>
              <span class="tile__type">{{ typeLabel(tg.targetType) }}</span>
            </div>
            <div class="tile__state">{{ stateLabel(tg) }}</div>
            <div class="tile__meta">
              <span>{{ t('monitor.interval') }} {{ tg.checkIntervalSeconds }}s</span>
              <span v-if="tg.consecutiveFailures" class="tile__fail">{{ t('monitor.consecutiveFail') }} {{ tg.consecutiveFailures }}</span>
            </div>
            <div class="tile__actions" @click.stop>
              <el-button link size="small" data-test="tile-toggle" @click="toggleEnabled(tg)">
                {{ tg.enabled ? t('monitor.pause') : t('monitor.resume') }}
              </el-button>
              <el-button v-if="tg.silenceUntil" link size="small" data-test="tile-unsilence" @click="clearSilence(tg)">
                {{ t('monitor.unsilence') }}
              </el-button>
              <el-button v-else-if="tg.enabled" link size="small" data-test="tile-silence" @click="silence(tg)">
                {{ t('monitor.silence') }}
              </el-button>
              <el-button link size="small" type="danger" data-test="tile-delete" @click="removeTarget(tg)">
                {{ t('monitor.delete') }}
              </el-button>
            </div>
          </div>
        </div>
      </section>
    </div>

    <el-pagination
      v-if="total > 0"
      class="pagination-bar monitor-pager"
      data-test="monitor-pager"
      :current-page="page"
      :page-size="pageSize"
      :total="total"
      :page-sizes="[20, 50, 100, 200]"
      layout="total, sizes, prev, pager, next"
      @current-change="(p: number) => gotoWindow({ status: wallQuery.status, type: wallQuery.targetType, keyword: wallQuery.keyword }, p, pageSize)"
      @size-change="(s: number) => gotoWindow({ status: wallQuery.status, type: wallQuery.targetType, keyword: wallQuery.keyword }, 1, s)"
    />

    <el-drawer v-model="drawerOpen" size="480px" :title="current?.name || t('monitor.detailTitle')" data-test="monitor-drawer">
      <template v-if="current">
        <el-alert
          v-if="openRed"
          type="error"
          :closable="false"
          class="detail__red"
          :title="t('monitor.openRedBanner')"
          data-test="detail-open-red"
        />
        <el-descriptions :column="1" border size="small" class="detail__desc">
          <el-descriptions-item :label="t('monitor.field.type')">{{ typeLabel(current.targetType) }}</el-descriptions-item>
          <el-descriptions-item :label="t('monitor.field.state')">{{ stateLabel(current) }}</el-descriptions-item>
          <el-descriptions-item :label="t('monitor.field.lastCheck')">{{ fmtTime(current.lastCheckAt) }}</el-descriptions-item>
          <el-descriptions-item v-if="current.lastCause" :label="t('monitor.field.lastCause')">{{ current.lastCause }}</el-descriptions-item>
          <el-descriptions-item :label="t('monitor.field.interval')">{{ current.checkIntervalSeconds }}s</el-descriptions-item>
          <el-descriptions-item v-if="current.backoffLevel" :label="t('monitor.field.backoff')">{{ current.backoffLevel }}</el-descriptions-item>
          <el-descriptions-item v-if="current.silenceUntil" :label="t('monitor.field.silenceUntil')">
            {{ fmtTime(current.silenceUntil) }}<template v-if="current.silenceReason">（{{ current.silenceReason }}）</template>
          </el-descriptions-item>
        </el-descriptions>

        <h4 class="detail__section">{{ t('monitor.trendTitle') }}</h4>
        <el-skeleton v-if="logsLoading" :rows="2" animated />
        <template v-else>
          <div v-if="trendBars.length" class="detail__trend" data-test="detail-trend">
            <el-tooltip v-for="b in trendBars" :key="b.key" :content="b.tip" placement="top">
              <span :class="b.cls" />
            </el-tooltip>
          </div>
          <p v-else class="detail__trend-empty">{{ t('monitor.trendEmpty') }}</p>
          <div v-if="recentFail" class="detail__fail-snapshot" data-test="detail-recent-fail">
            <span class="detail__fail-time">{{ fmtTime(recentFail.checkedAt) }}</span>
            <span>{{ recentFail.detail || t('monitor.noDetail') }}</span>
          </div>
        </template>

        <el-popover placement="bottom" :width="380" trigger="hover" @before-enter="onAssetsHover">
          <template #reference>
            <el-button class="detail__assets-btn" :loading="assetsLoading" data-test="detail-assets-btn">
              {{ t('monitor.linkedAssets') }}
            </el-button>
          </template>
          <div data-test="detail-assets-panel">
            <template v-if="linkedAssets">
              <p class="assets__row">{{ t('monitor.assets.plans') }}：{{ linkedAssets.planCount }}<span v-if="linkedAssets.planNames.length">（{{ linkedAssets.planNames.join('、') }}）</span></p>
              <p class="assets__row">{{ t('monitor.assets.suites') }}：{{ linkedAssets.suiteCount }}<span v-if="linkedAssets.suiteNames.length">（{{ linkedAssets.suiteNames.join('、') }}）</span></p>
              <p class="assets__row">{{ t('monitor.assets.factories') }}：{{ linkedAssets.factoryCount }}<span v-if="linkedAssets.factoryNames.length">（{{ linkedAssets.factoryNames.join('、') }}）</span></p>
              <p class="assets__row">{{ t('monitor.assets.apiRequests') }}：{{ linkedAssets.apiRequestCount }}<span v-if="linkedAssets.apiRequestNames.length">（{{ linkedAssets.apiRequestNames.join('、') }}）</span></p>
            </template>
            <p v-else>{{ t('monitor.assetsLoadFail') }}</p>
          </div>
        </el-popover>
      </template>
    </el-drawer>

    <TargetCreateWizard ref="wizardRef" :project-id="projectId" @created="loadAll" />
  </div>
</template>

<style scoped>
.monitor-page { padding: 16px; }
.monitor-page__header { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; margin-bottom: 16px; }
.monitor-page__desc { margin: 0; color: var(--el-text-color-secondary); font-size: 13px; max-width: 720px; }
.monitor-pager { margin-top: 16px; justify-content: flex-end; }
.monitor-group { margin-bottom: 20px; }
.monitor-group__head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; cursor: pointer; }
.monitor-group__caret { color: var(--el-text-color-secondary); transition: transform 0.15s ease; transform: rotate(90deg); }
.monitor-group__caret.is-collapsed { transform: rotate(0deg); }
.monitor-group__env { font-weight: 600; font-size: 14px; }
.monitor-group__count { color: var(--el-text-color-secondary); font-size: 12px; }
.monitor-group__tiles { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 12px; }
.tile { border: 1px solid var(--el-border-color); border-radius: 8px; padding: 12px; cursor: pointer; background: var(--el-bg-color); }
.tile:hover { box-shadow: var(--el-box-shadow-light); }
.tile--up { border-left: 4px solid var(--el-color-success); }
.tile--down { border-left: 4px solid var(--el-color-danger); }
.tile--flapping { border-left: 4px solid var(--el-color-warning); }
.tile--recovering { border-left: 4px solid var(--el-color-primary); }
.tile--unknown { border-left: 4px solid var(--el-border-color); }
.tile--silenced { border-left: 4px solid var(--el-color-info); opacity: .8; }
.tile--disabled { opacity: .55; }
.tile__top { display: flex; justify-content: space-between; gap: 8px; }
.tile__name { font-weight: 600; font-size: 14px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tile__type { font-size: 11px; color: var(--el-text-color-secondary); border: 1px solid var(--el-border-color-lighter); border-radius: 4px; padding: 0 6px; height: fit-content; }
.tile__state { font-size: 13px; margin: 6px 0 4px; }
.tile__meta { display: flex; justify-content: space-between; font-size: 12px; color: var(--el-text-color-secondary); }
.tile__fail { color: var(--el-color-danger); }
.tile__actions { display: flex; gap: 4px; margin-top: 8px; flex-wrap: wrap; }
.detail__red { margin-bottom: 12px; }
.detail__desc { margin-bottom: 16px; }
.detail__section { margin: 0 0 8px; font-size: 14px; }
.detail__trend { display: flex; align-items: flex-end; gap: 2px; height: 48px; margin-bottom: 8px; }
.bar { width: 8px; border-radius: 2px; height: 100%; }
.bar--ok { background: var(--el-color-success); }
.bar--fail { background: var(--el-color-danger); }
.bar--deferred { background: var(--el-border-color); }
.detail__trend-empty { color: var(--el-text-color-secondary); font-size: 13px; }
.detail__fail-snapshot { display: flex; gap: 8px; font-size: 12px; color: var(--el-text-color-secondary); background: var(--el-color-danger-light-9); border-radius: 6px; padding: 8px; margin-bottom: 16px; }
.detail__assets-btn { margin-top: 8px; }
.assets__row { margin: 4px 0; font-size: 13px; }
</style>
