<!-- tp-sort-exempt: engine/platform registries are hand-provisioned sets (a dozen rows) served in full. -->
<!-- tp-owner-echo-exempt: the engine/platform drawers edit GLOBAL registry rows
     (payload keyed by engine code, no project ownership) — same global-asset
     family as the skill/tool exemption. Header-context-bound actions on this
     page already carry :disabled="!projectId". -->
<template>
  <div class="page-root engine-center-page" data-test="engine-center-page">
    <div class="page-card">
      <div class="page-header">
        <div class="header-actions">
          <el-button
            :icon="DataAnalysis"
            :disabled="!projectId"
            data-test="engine-support-btn"
            @click="openSupport"
          >
            {{ t('appauto.engine.supportBtn') }}
          </el-button>
          <el-checkbox
            v-if="canManage"
            v-model="includeDisabled"
            data-test="engine-center-show-disabled"
            @change="load"
          >
            {{ t('appauto.engine.showDisabled') }}
          </el-checkbox>
          <el-button :icon="FirstAidKit" :loading="healthLoading" data-test="engine-center-health-btn" @click="toggleHealth">
            {{ t('appauto.engine.healthBtn') }}
          </el-button>
          <el-button :icon="QuestionFilled" data-test="engine-center-help-btn" @click="openHelp('X', t('layout.leafHelpAnchor.engineCenter'))">
            {{ t('appauto.engine.help') }}
          </el-button>
        </div>
      </div>

      <!-- R21 bands: refresh is a view-state action — it lives in the filter-bar
           trailing slot together with the column-settings control (the page has no
           other screening controls, so the slot row carries view-state actions only). -->
      <div class="filter-bar" data-test="engine-center-filter-bar">
        <div class="filter-bar-actions">
          <!-- R25 mode declaration (scope-aware single control, TB group): this page carries no
               tabs and exactly one configurable table (the engine section renders as cards), so
               the page-level column-settings popover equals the platform-table scope;
               show/hide, order, pinning and density persist per user via the shared piece. -->
          <ColumnSettings
            :columns="settingsRows"
            :density="colCfg.state.density"
            @toggle="colCfg.toggle"
            @move="colCfg.move"
            @density="colCfg.setDensity"
            @reset="colCfg.reset"
            @pin="colCfg.toggleFixed"
          />
          <el-button :icon="Refresh" :loading="loading" data-test="engine-center-refresh" @click="load">
            {{ t('appauto.engine.refresh') }}
          </el-button>
        </div>
      </div>

   
      <div v-if="healthVisible" class="health-panel" data-test="engine-health-panel">
        <div class="health-head">
          <h3 class="section-title health-title">{{ t('appauto.engine.healthTitle') }}</h3>
          <span class="health-summary" data-test="engine-health-summary">{{ healthSummaryText }}</span>
        </div>
        <el-alert
          type="info"
          :closable="false"
          show-icon
          data-test="engine-health-ai-note"
          :title="healthAiOff ? t('appauto.engine.healthAiOff') : t('appauto.engine.healthAiOn')"
        />
        <div v-if="!healthRows.length" class="health-empty" data-test="engine-health-empty">
          {{ t('appauto.engine.healthEmpty') }}
        </div>
        <div v-else class="health-list">
          <div v-for="h in healthRows" :key="h.code" class="health-item" :data-test="`engine-health-row-${h.code}`">
            <div class="health-item-head">
              <el-icon class="health-icon" :class="healthState(h)">
                <SuccessFilled v-if="healthState(h) === 'ready'" />
                <WarningFilled v-else-if="healthState(h) === 'blocked'" />
                <CircleCloseFilled v-else />
              </el-icon>
              <span class="health-name">{{ h.name }}</span>
              <el-tag :type="healthTagType(h)" size="small" disable-transitions data-test="engine-health-tag">
                {{ healthStateLabel(h) }}
              </el-tag>
              <span class="health-counts">{{ t('appauto.engine.healthCounts', { online: h.onlineExecutors, ready: h.readyExecutors }) }}</span>
            </div>
            <ul v-if="h.blockingItems.length" class="health-items" data-test="engine-health-blocking">
              <li v-for="(item, i) in h.blockingItems" :key="i">{{ item }}</li>
            </ul>
          </div>
        </div>
        <p class="caliber-note health-caliber" data-test="engine-health-caliber">{{ t('appauto.engine.healthCaliber') }}</p>
      </div>

      <p class="caliber-note" data-test="engine-center-caliber">{{ t('appauto.engine.caliberNote') }}</p>

      <TableSkeleton v-if="loading" :cols="6" data-test="engine-center-skeleton" />
      <div v-else-if="!engines.length" class="empty-box" data-test="engine-center-empty">
        <EmptyState :title="t('appauto.engine.emptyTitle')" :hint="t('appauto.engine.emptyHint')" />
      </div>
      <div v-else class="engine-grid" data-test="engine-center-grid">
        <el-card v-for="e in engines" :key="e.code" shadow="hover" class="engine-card" :data-test="`engine-card-${e.code}`">
          <template #header>
            <div class="card-head">
              <span class="engine-name">{{ e.name }}</span>
              <el-tag v-if="e.status !== 1" type="info" size="small" disable-transitions>{{ t('appauto.engine.disabledTag') }}</el-tag>
              <el-tag v-else-if="onlineExecutorCount > 0" type="success" size="small" disable-transitions data-test="engine-ready-tag">
                {{ t('appauto.engine.readyTag') }}
              </el-tag>
              <el-tag v-else-if="onlineExecutorCount === 0" type="warning" size="small" disable-transitions data-test="engine-nolink-tag">
                {{ t('appauto.engine.noOnlineExecutorTag') }}
              </el-tag>
            </div>
          </template>
          <div class="card-body">
            <div class="field-line">
              <span class="field-key">{{ t('appauto.engine.colCode') }}</span><span>{{ e.code }}</span>
            </div>
            <div class="field-line">
              <span class="field-key">{{ t('appauto.engine.colPlatforms') }}</span>
              <span>
                <el-tag v-for="p in e.platformCodes" :key="p" size="small" class="chip" disable-transitions>{{ platformName(p) }}</el-tag>
                <span v-if="!e.platformCodes.length">-</span>
              </span>
            </div>
            <div class="field-line">
              <span class="field-key">{{ t('appauto.engine.colDeviceKinds') }}</span>
              <span>{{ (e.deviceKindCodes || []).join(' · ') || '-' }}</span>
            </div>
            <div class="field-line">
              <span class="field-key">{{ t('appauto.engine.colRuntime') }}</span>
              <span>{{ runtimeText(e) }}</span>
            </div>
            <div class="field-line">
              <span class="field-key">{{ t('appauto.engine.colPriority') }}</span>
              <span>{{ e.defaultPriority }}</span>
            </div>
            <div class="field-line">
              <span class="field-key">{{ t('appauto.engine.colLicense') }}</span>
              <span>{{ e.license || '-' }}</span>
            </div>
            <div class="field-line caps-line">
              <span class="field-key">{{ t('appauto.engine.colCapabilities') }}</span>
              <span class="caps-wrap">
                <el-tag v-for="c in enabledCaps(e)" :key="c" size="small" type="success" effect="plain" class="chip" disable-transitions>{{ c }}</el-tag>
                <el-tooltip v-if="unsupportedCaps(e).length" :content="unsupportedCaps(e).join(' · ')" placement="top">
                  <el-tag size="small" type="info" effect="plain" class="chip" disable-transitions>
                    {{ t('appauto.engine.unsupportedCaps', { count: unsupportedCaps(e).length }) }}
                  </el-tag>
                </el-tooltip>
              </span>
            </div>
            <div v-if="e.driverRequirements && e.driverRequirements.length" class="field-block" data-test="engine-driver-reqs">
              <div class="field-key">{{ t('appauto.engine.colDriverReqs') }}</div>
              <ol class="req-list">
                <li v-for="(r, i) in e.driverRequirements" :key="i">{{ r }}</li>
              </ol>
            </div>
            <div class="card-foot">
              <ThirdPartyLinkCell v-if="e.installDocUrl" :url="e.installDocUrl"
                :label="t('appauto.engine.installDoc')" data-test="engine-install-doc-link" />
              <el-button
                v-if="canManage"
                link
                type="primary"
                data-test="engine-manage-btn"
                @click="openEngineDrawer(e)"
              >
                {{ t('appauto.engine.manage') }}
              </el-button>
            </div>
          </div>
        </el-card>
      </div>

      
      <h3 class="section-title">{{ t('appauto.engine.platformSection') }}</h3>
      <TableSkeleton v-if="loading" :cols="colCfg.visibleColumns.value.length + 1" />
      <el-table v-else :data="platforms" border row-key="code" :size="colCfg.state.density" class="data-table" data-test="engine-platform-table">
        <template #empty>
          <EmptyState :title="t('appauto.engine.platformEmpty')" />
        </template>
        <el-table-column
          v-for="col in colCfg.visibleColumns.value"
          :key="col.key"
          :prop="col.key"
          :fixed="col.fixed || false"
          :label="t(col.labelKey)"
          :width="col.width ?? undefined"
          :min-width="col.width ? undefined : ENGINE_PLATFORM_FLEX_MIN[col.key]"
          :show-overflow-tooltip="ENGINE_PLATFORM_TOOLTIP.has(col.key)"
          :data-test="`engine-col-${col.key}`"
        >
          <template #default="{ row }">
            <template v-if="col.key === 'deviceKinds'">{{ (row.deviceKindCodes || []).join(' · ') || '-' }}</template>
            <template v-else-if="col.key === 'carriers'">
              <el-tag v-for="c in row.carrierTypes" :key="c" size="small" class="chip" disable-transitions>{{ c }}</el-tag>
            </template>
            <template v-else-if="col.key === 'note'">{{ row.note || '-' }}</template>
            <template v-else-if="col.key === 'status'">
              <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small" disable-transitions>
                {{ row.status === 1 ? t('appauto.engine.statusOn') : t('appauto.engine.statusOff') }}
              </el-tag>
            </template>
            <template v-else>{{ row[col.key] }}</template>
          </template>
        </el-table-column>
        <el-table-column v-if="canPlatformManage" :label="t('common.action')" width="100" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" data-test="platform-manage-btn" @click="openPlatformDrawer(row)">
              {{ t('appauto.engine.manage') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <p class="caliber-note">{{ t('appauto.engine.platformCaliber') }}</p>

      
      <el-drawer v-model="engineDrawerVisible" :title="t('appauto.engine.engineDrawerTitle', { name: editingEngine?.name || '' })" size="480px" data-test="engine-manage-drawer">
        <el-form :model="engineForm" label-width="110px">
          <el-form-item :label="t('appauto.engine.formName')" required>
            <el-input v-model="engineForm.name" data-test="engine-form-name" maxlength="100" />
          </el-form-item>
          <el-form-item :label="t('appauto.engine.formPriority')">
            <el-input-number v-model="engineForm.defaultPriority" :min="0" data-test="engine-form-priority" />
            <div class="form-note">{{ t('appauto.engine.priorityNote') }}</div>
          </el-form-item>
          <el-form-item :label="t('appauto.engine.formInstallDoc')">
            <el-input v-model="engineForm.installDocUrl" data-test="engine-form-doc" maxlength="300" placeholder="https://…" />
          </el-form-item>
          <el-form-item :label="t('appauto.engine.formStatus')">
            <el-switch
              v-model="engineForm.status"
              :active-value="1"
              :inactive-value="0"
              :active-text="t('appauto.engine.statusOn')"
              :inactive-text="t('appauto.engine.statusOff')"
              data-test="engine-form-status"
            />
            <div class="form-note">{{ t('appauto.engine.statusNote') }}</div>
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button data-test="engine-drawer-cancel" @click="engineDrawerVisible = false">{{ t('common.cancel') }}</el-button>
          <el-button type="primary" :loading="saving" data-test="engine-drawer-save" @click="saveEngine">{{ t('common.save') }}</el-button>
        </template>
      </el-drawer>

      
      <el-drawer v-model="platformDrawerVisible" :title="t('appauto.engine.platformDrawerTitle', { name: editingPlatform?.name || '' })" size="480px" data-test="platform-manage-drawer">
        <el-form :model="platformForm" label-width="110px">
          <el-form-item :label="t('appauto.engine.formNote')">
            <el-input v-model="platformForm.note" type="textarea" :rows="5" maxlength="2000" show-word-limit data-test="platform-form-note" :placeholder="t('appauto.engine.notePlaceholder')" />
            <div class="form-note">{{ t('appauto.engine.noteFieldHint') }}</div>
          </el-form-item>
          <el-form-item :label="t('appauto.engine.formStatus')">
            <el-switch
              v-model="platformForm.status"
              :active-value="1"
              :inactive-value="0"
              :active-text="t('appauto.engine.statusOn')"
              :inactive-text="t('appauto.engine.statusOff')"
              data-test="platform-form-status"
            />
            <div class="form-note">{{ t('appauto.engine.platformStatusNote') }}</div>
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button data-test="platform-drawer-cancel" @click="platformDrawerVisible = false">{{ t('common.cancel') }}</el-button>
          <el-button type="primary" :loading="saving" data-test="platform-drawer-save" @click="savePlatform">{{ t('common.save') }}</el-button>
        </template>
      </el-drawer>

   
      <el-drawer v-model="supportDrawerVisible" :title="t('appauto.engine.supportDrawerTitle')" size="720px" data-test="engine-support-drawer">
        <TableSkeleton v-if="supportLoading" :cols="5" data-test="engine-support-skeleton" />
        <div v-else-if="!supportRows.length" class="health-empty" data-test="engine-support-empty">
          {{ t('appauto.engine.supportEmpty') }}
        </div>
        <div v-else class="support-list">
          <div v-for="s in supportRows" :key="s.engineCode ?? '__unknown__'" class="support-item" :data-test="`engine-support-row-${s.engineCode ?? 'unknown'}`">
            <div class="support-head">
              <span class="health-name">{{ s.engineName }}</span>
              <el-tag :type="verdictTagType(s.versionState)" size="small" disable-transitions data-test="engine-support-version-tag">
                {{ t('appauto.engine.supportVersionDim') }} · {{ verdictLabel(s.versionState) }}
              </el-tag>
              <el-tag :type="verdictTagType(s.healthState)" size="small" disable-transitions data-test="engine-support-health-tag">
                {{ t('appauto.engine.supportHealthDim') }} · {{ verdictLabel(s.healthState) }}
              </el-tag>
              <el-tag v-if="!s.enabled" type="info" size="small" disable-transitions>{{ t('appauto.engine.disabledTag') }}</el-tag>
            </div>
            <p class="support-conclusion" data-test="engine-support-conclusion">{{ s.conclusion }}</p>
            <p v-if="s.versionReason" class="support-reason" data-test="engine-support-version-reason">{{ s.versionReason }}</p>
            <p class="support-metrics" data-test="engine-support-metrics">
              {{ t('appauto.engine.supportSamples', { samples: s.healthSamples, rate: (s.healthPassRate * 100).toFixed(2) }) }}
            </p>
            <el-table
              v-if="s.healthGroups.length"
              :data="s.healthGroups"
              size="small"
              border
              row-key="platform"
              class="support-groups"
              data-test="engine-support-groups"
            >
              <el-table-column :label="t('appauto.engine.supportColPlatform')" min-width="110">
                <template #default="{ row }">{{ row.platform || t('appauto.engine.supportUnknownPlatform') }}</template>
              </el-table-column>
              <el-table-column :label="t('appauto.engine.supportColSamples')" prop="samples" width="90" />
              <el-table-column :label="t('appauto.engine.supportColPassRate')" width="100">
                <template #default="{ row }">{{ (row.passRate * 100).toFixed(2) }}%</template>
              </el-table-column>
              <el-table-column :label="t('appauto.engine.supportColErrorDist')" min-width="170">
                <template #default="{ row }">
                  <el-tag v-for="(v, k) in row.errorTypeDist" :key="k" size="small" type="danger" effect="plain" class="chip" disable-transitions>{{ k }} {{ v }}</el-tag>
                  <span v-if="!Object.keys(row.errorTypeDist || {}).length">-</span>
                </template>
              </el-table-column>
              <el-table-column :label="t('appauto.engine.supportColAiDist')" min-width="150">
                <template #default="{ row }">
                  <el-tag v-for="(v, k) in row.aiFailureDist" :key="k" size="small" type="warning" effect="plain" class="chip" disable-transitions>{{ k }} {{ v }}</el-tag>
                  <span v-if="!Object.keys(row.aiFailureDist || {}).length">-</span>
                </template>
              </el-table-column>
            </el-table>
            <p class="caliber-note support-caliber" data-test="engine-support-caliber">{{ s.caliberNote }}</p>
          </div>
        </div>
      </el-drawer>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'
import ColumnSettings from '@/components/ColumnSettings.vue'
import { ElMessage } from 'element-plus'
import { CircleCloseFilled, DataAnalysis, FirstAidKit, QuestionFilled, Refresh, SuccessFilled, WarningFilled } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import ThirdPartyLinkCell from '@/components/ThirdPartyLinkCell.vue'
import { openHelp } from '@/composables/useHelpDrawer'
import { useAuthStore } from '@/stores/auth'
import { bizErrorMsg } from '@/utils/bizError'
import { verdictTagType } from '@/utils/verdict'
import { listExecutors } from '@/api/executor'
import { listEngineSupport, type AppautoEngineSupport } from '@/api/appautoSupport'
import {
  listAppautoEngines,
  listAppautoEngineHealth,
  enabledCapabilityKeys,
  disabledCapabilityKeys,
  listAppautoPlatforms,
  updateAppautoEngine,
  updateAppautoPlatform,
  type AppautoEngine,
  type AppautoEngineHealth,
  type AppautoPlatform,
  type EngineManagePayload,
  type PlatformManagePayload,
} from '@/api/appauto'

const { t } = useI18n()
const auth = useAuthStore()

const canManage = computed(() => auth.hasPerm('perm:appauto:engine:manage'))
const canPlatformManage = computed(() => auth.hasPerm('perm:appauto:platform:manage'))

const loading = ref(false)
const saving = ref(false)
const includeDisabled = ref(false)

const loadSeq = ref(0)
const engines = ref<AppautoEngine[]>([])
const platforms = ref<AppautoPlatform[]>([])

// Column-configuration wiring (N-3 repayment, shared useColumnConfig piece):
// the platform-registry data columns flow through the visibleColumns loop so
// show/hide, order, pinning and density persist per user under
// tp-colcfg-engine-platform; the locked action column stays outside the loop.
// Widths/min-widths mirror the frozen layout; the registry is a hand-provisioned
// full set (see the sort-exempt declaration on line 1), so no sortable rides in.
const ENGINE_PLATFORM_COLUMNS: ColumnDef[] = [
  { key: 'code', labelKey: 'appauto.engine.colPlatformCode', width: 140 },
  { key: 'name', labelKey: 'appauto.engine.colName', width: null },
  { key: 'deviceKinds', labelKey: 'appauto.engine.colDeviceKinds', width: null },
  { key: 'carriers', labelKey: 'appauto.engine.colCarriers', width: null },
  { key: 'note', labelKey: 'appauto.engine.colNote', width: null },
  { key: 'status', labelKey: 'appauto.engine.colStatus', width: 100 },
]
/** min-width fallbacks for the flexible (width-null) columns. */
const ENGINE_PLATFORM_FLEX_MIN: Record<string, number> = { name: 150, deviceKinds: 160, carriers: 180, note: 200 }
/** Text columns whose long values clip into a tooltip (frozen show-overflow set). */
const ENGINE_PLATFORM_TOOLTIP = new Set(['name', 'note'])
const colCfg = useColumnConfig('engine-platform', ENGINE_PLATFORM_COLUMNS)
/** Popover rows (column state merged with labelKey for rendering). */
const settingsRows = computed(() =>
  colCfg.state.columns.map((c) => ({ ...c, labelKey: ENGINE_PLATFORM_COLUMNS.find((d) => d.key === c.key)!.labelKey })),
)

const onlineExecutorCount = ref(0)

const healthVisible = ref(false)
const healthLoading = ref(false)
const healthRows = ref<AppautoEngineHealth[]>([])

const healthSeq = ref(0)

const healthAiOff = computed(() => healthRows.value.every((h) => !h.aiDoctorEnabled))
const healthSummaryText = computed(() => t('appauto.engine.healthSummary', {
  ready: healthRows.value.filter((h) => h.enabled && !h.blockingItems.length).length,
  blocked: healthRows.value.filter((h) => h.enabled && h.blockingItems.length > 0).length,
  disabled: healthRows.value.filter((h) => !h.enabled).length,
}))


function healthState(h: AppautoEngineHealth): 'ready' | 'blocked' | 'disabled' {
  if (!h.enabled) return 'disabled'
  return h.blockingItems.length ? 'blocked' : 'ready'
}
function healthTagType(h: AppautoEngineHealth): 'success' | 'warning' | 'info' {
  const s = healthState(h)
  return s === 'ready' ? 'success' : s === 'blocked' ? 'warning' : 'info'
}
function healthStateLabel(h: AppautoEngineHealth): string {
  const s = healthState(h)
  return s === 'ready'
    ? t('appauto.engine.healthStateReady')
    : s === 'blocked'
      ? t('appauto.engine.healthStateBlocked')
      : t('appauto.engine.healthStateDisabled')
}

async function loadHealth() {
  const seq = ++healthSeq.value
  healthLoading.value = true
  try {
    const rows = await listAppautoEngineHealth(includeDisabled.value)
    if (seq !== healthSeq.value) return
    healthRows.value = rows
  } catch (e) {
    if (seq !== healthSeq.value) return
    ElMessage.error(bizErrorMsg(e, t('appauto.engine.healthLoadFailed')))
    healthVisible.value = false
  } finally {
    if (seq === healthSeq.value) healthLoading.value = false
  }
}

function toggleHealth() {
  healthVisible.value = !healthVisible.value
  if (healthVisible.value) void loadHealth()
}

// : the support-degree check rides the global header context — the
// page-level picker was its only copy of the project id (red line 6).
const { projectId, followTick } = useProjectPageContext()
const supportDrawerVisible = ref(false)
const supportLoading = ref(false)
const supportSeq = ref(0)
const supportRows = ref<AppautoEngineSupport[]>([])


function verdictLabel(state: string): string {
  const map: Record<string, string> = {
    GREEN: t('appauto.engine.verdictGreen'),
    YELLOW: t('appauto.engine.verdictYellow'),
    RED: t('appauto.engine.verdictRed'),
    UNKNOWN: t('appauto.engine.verdictUnknown'),
  }
  return map[state] ?? state
}

async function loadSupport() {
  if (!projectId.value) return
  const seq = ++supportSeq.value
  supportLoading.value = true
  try {
    const rows = await listEngineSupport(projectId.value)
    if (seq !== supportSeq.value) return
    supportRows.value = rows
  } catch (e) {
    if (seq !== supportSeq.value) return
    ElMessage.error(bizErrorMsg(e, t('appauto.engine.supportLoadFailed')))
  } finally {
    if (seq === supportSeq.value) supportLoading.value = false
  }
}

function openSupport() {
  supportDrawerVisible.value = true
  void loadSupport()
}

const engineDrawerVisible = ref(false)
const editingEngine = ref<AppautoEngine | null>(null)
const engineForm = ref<EngineManagePayload>({ name: '', defaultPriority: 0, installDocUrl: '', status: 1 })

const platformDrawerVisible = ref(false)
const editingPlatform = ref<AppautoPlatform | null>(null)
const platformForm = ref<PlatformManagePayload>({ note: '', status: 1 })


function platformName(code: string): string {
  return platforms.value.find((p) => p.code === code)?.name || code
}

function runtimeText(e: AppautoEngine): string {
  const req = Object.entries(e.runtimeVersionReq || {}).map(([k, v]) => `${k} ${v}`).join('，')
  return [e.runtimeType, req].filter(Boolean).join(' · ') || '-'
}

function enabledCaps(e: AppautoEngine): string[] {
  return enabledCapabilityKeys(e)
}

function unsupportedCaps(e: AppautoEngine): string[] {
  return disabledCapabilityKeys(e)
}

async function load() {
  const seq = ++loadSeq.value
  loading.value = true
  try {
    const [es, ps] = await Promise.all([
      listAppautoEngines(includeDisabled.value),
      listAppautoPlatforms(includeDisabled.value),
    ])
    if (seq !== loadSeq.value) return
    engines.value = [...es].sort((a, b) => a.defaultPriority - b.defaultPriority)
    platforms.value = ps
  } catch (e) {
    if (seq !== loadSeq.value) return
    ElMessage.error(bizErrorMsg(e, t('appauto.engine.loadFailed')))
  } finally {
    if (seq === loadSeq.value) loading.value = false
  }
  try {
    const execs = await listExecutors()
    onlineExecutorCount.value = execs.filter((x) => x.status === 1).length
  } catch {
    onlineExecutorCount.value = -1
  }
  if (healthVisible.value) void loadHealth()
}

function openEngineDrawer(row: AppautoEngine) {
  editingEngine.value = row
  engineForm.value = {
    name: row.name,
    defaultPriority: row.defaultPriority,
    installDocUrl: row.installDocUrl ?? '',
    status: row.status,
  }
  engineDrawerVisible.value = true
}

async function saveEngine() {
  if (!editingEngine.value) return
  if (!engineForm.value.name.trim()) {
    ElMessage.warning(t('appauto.engine.nameRequired'))
    return
  }
  saving.value = true
  try {
    await updateAppautoEngine(editingEngine.value.code, { ...engineForm.value, name: engineForm.value.name.trim() })
    ElMessage.success(t('appauto.engine.engineSaved'))
    engineDrawerVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('appauto.engine.saveFailed')))
  } finally {
    saving.value = false
  }
}

function openPlatformDrawer(row: AppautoPlatform) {
  editingPlatform.value = row
  platformForm.value = { note: row.note ?? '', status: row.status }
  platformDrawerVisible.value = true
}

async function savePlatform() {
  if (!editingPlatform.value) return
  saving.value = true
  try {
    await updateAppautoPlatform(editingPlatform.value.code, platformForm.value)
    ElMessage.success(t('appauto.engine.platformSaved'))
    platformDrawerVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('appauto.engine.saveFailed')))
  } finally {
    saving.value = false
  }
}

onMounted(load)

// : a header context switch reloads from the new segment (in-page
// switches reload through their own controls, so followTick only fires
// on context-driven changes and never double-loads).
watch(followTick, () => {
  // the support drawer is a per-project snapshot: a header switch hides it
  // and the next open reloads against the new segment (load happens on open)
  supportDrawerVisible.value = false
})

</script>

<style scoped>
.empty-box {
  padding: 24px 0;
}
.caliber-note {
  margin: 0 0 16px;
  font-size: 12px;
  color: var(--tp-text-secondary);
  line-height: 1.6;
}
.section-title {
  margin: 28px 0 12px;
  font-size: 16px;
}
.engine-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(360px, 1fr));
  gap: 16px;
}
.engine-card :deep(.el-card__header) {
  padding: 10px 16px;
}
.engine-card :deep(.el-card__body) {
  padding: 12px 16px;
}
.card-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.engine-name {
  font-weight: 600;
  font-size: 16px;
}
.field-line {
  display: flex;
  gap: 8px;
  margin: 4px 0;
  font-size: 13px;
  line-height: 1.6;
}
.field-key {
  flex: 0 0 86px;
  color: var(--tp-text-secondary);
}
.caps-line {
  align-items: flex-start;
}
.caps-wrap {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.chip {
  margin: 0 4px 4px 0;
}
.field-block {
  margin: 8px 0;
  font-size: 13px;
}
.req-list {
  margin: 4px 0 0;
  padding-left: 18px;
  color: var(--el-text-color-regular);
  line-height: 1.6;
}
.card-foot {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px solid var(--el-border-color-lighter);
}
.card-foot a {
  font-size: 13px;
  color: var(--el-color-primary);
}
.form-note {
  font-size: 12px;
  color: var(--tp-text-secondary);
  line-height: 1.6;
}
.health-panel {
  margin: 0 0 16px;
  padding: 14px 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-fill-color-lighter);
}
.health-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin: 0 0 10px;
}
.health-title {
  margin: 0;
  font-size: 15px;
}
.health-summary {
  font-size: 12px;
  color: var(--tp-text-secondary);
}
.health-empty {
  margin: 10px 0;
  font-size: 13px;
  color: var(--tp-text-secondary);
}
.health-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 10px;
}
.health-item {
  padding: 8px 10px;
  border-radius: 6px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
}
.health-item-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.health-icon {
  display: inline-flex;
}
.health-icon.ready {
  color: var(--el-color-success);
}
.health-icon.blocked {
  color: var(--el-color-warning);
}
.health-icon.disabled {
  color: var(--el-color-info);
}
.health-name {
  font-weight: 600;
  font-size: 14px;
}
.health-counts {
  font-size: 12px;
  color: var(--tp-text-secondary);
}
.health-items {
  margin: 6px 0 0;
  padding-left: 20px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--el-text-color-regular);
}
.health-caliber {
  margin: 10px 0 0;
}
.support-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.support-item {
  padding: 10px 12px;
  border-radius: 6px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
}
.support-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.support-conclusion {
  margin: 8px 0 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-primary);
}
.support-reason {
  margin: 4px 0 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-regular);
}
.support-metrics {
  margin: 4px 0 8px;
  font-size: 12px;
  color: var(--tp-text-secondary);
}
.support-caliber {
  margin: 8px 0 0;
}
</style>
