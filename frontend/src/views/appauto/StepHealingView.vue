<!-- tp-sort-exempt: the suggestion feed is server-capped (limit 100 per case) and drained to terminal states; not an unbounded ledger. -->
<template>
  <div class="page-root step-healing-page" data-test="step-healing-page">
    <div class="page-card">
      <!-- R25 mode declaration (scope-aware single control, TB group): this page
           carries no tabs and exactly one page-surface table, so the page-level
           column-settings popover equals the table scope: show/hide, order,
           pinning and density persist per user through the shared
           useColumnConfig piece (viewKey step-healing).
           R21 bands (FB group): the header band stays dropped (page name rides
           the global top bar per R23); this page has no page-level variant
           action, so the trailing slot carries the settings control ahead of
           refresh, and both screening controls stay in the condition flow. -->
      <div class="filter-bar" data-test="step-healing-filter-bar">
        <el-select
          v-model="caseId"
          filterable
          clearable
          :loading="casesLoading"
          :placeholder="t('appauto.healing.selectCase')"
          style="width: 240px"
          data-test="step-healing-case-select"
          @change="load"
        >
          <el-option
            v-for="c in caseOptions"
            :key="c.id"
            :label="`${c.caseNo} · ${c.title}`"
            :value="c.id"
          />
        </el-select>
        <el-select
          v-model="statusFilter"
          clearable
          :placeholder="t('appauto.healing.statusAll')"
          style="width: 130px"
          data-test="step-healing-status-select"
          @change="load"
        >
          <el-option :label="t('dict.appauto.suggestion.status.pending')" :value="1" />
          <el-option :label="t('dict.appauto.suggestion.status.adopted')" :value="2" />
          <el-option :label="t('dict.appauto.suggestion.status.rejected')" :value="3" />
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
          <el-button :icon="Refresh" :loading="loading" data-test="step-healing-refresh" @click="load">
            {{ t('appauto.healing.refresh') }}
          </el-button>
        </div>
      </div>

      
      <el-alert
        v-if="gateOffVisible"
        type="warning"
        show-icon
        :closable="true"
        class="gate-off-alert"
        data-test="step-healing-gate-off-alert"
      >
        <template #title>{{ t('appauto.healing.gateOffTitle') }}</template>
        <template #default>
          <div class="gate-off-body">
            <p class="gate-off-hint">{{ t('appauto.healing.gateOffHint') }}</p>
            <el-button size="small" :icon="Setting" data-test="step-healing-gate-off-settings-btn" @click="gotoConfigs">
              {{ t('appauto.healing.goSettings') }}
            </el-button>
          </div>
        </template>
      </el-alert>

      <div v-if="!loading && !projectId" class="empty-box" data-test="step-healing-empty-no-project">
        <EmptyState
          :title="t('appauto.healing.noProjectTitle')"
          :hint="t('appauto.healing.noProjectHint')"
        />
      </div>

      <div v-else-if="!loading && !caseId" class="empty-box" data-test="step-healing-empty-no-case">
        <EmptyState
          :title="t('appauto.healing.noCaseTitle')"
          :hint="t('appauto.healing.noCaseHint')"
        />
      </div>

      <TableSkeleton v-else-if="loading" :cols="colCfg.visibleColumns.value.length + 1" data-test="step-healing-skeleton" />
      <el-table v-else :data="rows" :size="colCfg.state.density" border row-key="id" class="data-table" data-test="step-healing-table">
        <template #empty>
          <EmptyState
            :filtered="!!statusFilter"
            :title="statusFilter ? t('common.emptyFilteredResult') : t('appauto.healing.emptyTitle')"
            :hint="statusFilter ? t('common.emptyFilteredHint') : t('appauto.healing.emptyHint')"
          />
        </template>
        <el-table-column
          v-for="col in colCfg.visibleColumns.value"
          :key="col.key"
          :prop="col.key"
          :fixed="col.fixed || false"
          :label="t(col.labelKey)"
          :width="col.width ?? undefined"
          :min-width="col.width ? undefined : STEP_HEALING_FLEX_MIN[col.key]"
          :show-overflow-tooltip="col.key === 'anchor'"
          :data-test="`step-healing-col-${col.key}`"
        >
          <template #default="{ row }">
            <template v-if="col.key === 'step'">
              <span data-test="step-healing-step-no">{{ row.stepNo }}</span>
            </template>
            <template v-else-if="col.key === 'anchor'">{{ row.anchor || '-' }}</template>
            <template v-else-if="col.key === 'source'">
              <span data-test="step-healing-source-label">{{ SUGGESTION_SOURCE_LABEL[row.source] || row.source }}</span>
            </template>
            <template v-else-if="col.key === 'proposed'">
              <div class="proposed-cell" data-test="step-healing-proposed">
                <div class="proposed-action">{{ row.proposedAction }}</div>
                <div class="proposed-target">{{ row.proposedTarget }}</div>
                <div v-if="row.x !== null && row.x !== undefined" class="proposed-coord">
                  {{ t('appauto.healing.coord', { x: row.x, y: row.y ?? 0 }) }}
                </div>
              </div>
            </template>
            <template v-else-if="col.key === 'reason'">
              <span class="reason-text">{{ row.reason || '-' }}</span>
            </template>
            <template v-else-if="col.key === 'status'">
              <el-tag :type="statusTagType(row.status)" data-test="step-healing-status-tag" disable-transitions>
                {{ SUGGESTION_STATE_LABEL[String(row.status)] || row.status }}
              </el-tag>
            </template>
            <template v-else-if="col.key === 'adoptedVer'">
              {{ row.adoptedScriptVersion ?? '-' }}
            </template>
            <template v-else-if="col.key === 'createdAt'"><TimeCell :iso="row.createdAt" /></template>
          </template>
        </el-table-column>
        <el-table-column :label="t('appauto.healing.colActions')" width="150" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status === 1">
              <el-button
                link
                type="primary"
                :loading="busyId === row.id"
                data-test="step-healing-adopt-btn"
                @click="adopt(row)"
              >
                {{ t('appauto.healing.adopt') }}
              </el-button>
              <el-button
                link
                type="warning"
                :loading="busyId === row.id"
                data-test="step-healing-reject-btn"
                @click="reject(row)"
              >
                {{ t('appauto.healing.reject') }}
              </el-button>
            </template>
            <span v-else class="handled-hint">{{ t('appauto.healing.handled') }}</span>
          </template>
        </el-table-column>
      </el-table>

      <p class="caliber-note">{{ t('appauto.healing.caliber') }}</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Setting } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import TimeCell from '@/components/TimeCell.vue'
import ColumnSettings from '@/components/ColumnSettings.vue'
import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'
import { BizError } from '@/api/request'
import { bizErrorMsg } from '@/utils/bizError'
import {
  SUGGESTION_SOURCE_LABEL,
  SUGGESTION_STATE_LABEL,
  adoptStepSuggestion,
  listStepSuggestions,
  rejectStepSuggestion,
  type AppautoStepSuggestion,
} from '@/api/appauto'
import { getCaseScript, listCases, type CaseItem } from '@/api/case'

const { t } = useI18n()
const router = useRouter()

// Column-configuration wiring (N-3 repayment, shared useColumnConfig piece):
// all eight data columns of the suggestion table flow through the
// visibleColumns loop so show/hide, order, pinning and density persist per
// user under viewKey 'step-healing'; the fixed-right action column is the
// only locked slot outside the loop, so the skeleton mirrors the visible
// data count plus one. The feed is server-capped (limit 100), so no
// per-column sort is declared (tp-sort-exempt header).
const STEP_HEALING_COLUMNS: ColumnDef[] = [
  { key: 'step', labelKey: 'appauto.healing.colStep', width: 90 },
  { key: 'anchor', labelKey: 'appauto.healing.colAnchor', width: null },
  { key: 'source', labelKey: 'appauto.healing.colSource', width: 110 },
  { key: 'proposed', labelKey: 'appauto.healing.colProposed', width: null },
  { key: 'reason', labelKey: 'appauto.healing.colReason', width: null },
  { key: 'status', labelKey: 'appauto.healing.colStatus', width: 100 },
  { key: 'adoptedVer', labelKey: 'appauto.healing.colAdoptedVer', width: 110 },
  { key: 'createdAt', labelKey: 'appauto.healing.colCreatedAt', width: 160 },
]
// anchor/proposed/reason are the flexible columns (long text cells)
const STEP_HEALING_FLEX_MIN: Record<string, number> = { anchor: 170, proposed: 220, reason: 180 }
const colCfg = useColumnConfig('step-healing', STEP_HEALING_COLUMNS)
const settingsRows = computed(() =>
  colCfg.state.columns.map((c) => ({ ...c, labelKey: STEP_HEALING_COLUMNS.find((d) => d.key === c.key)!.labelKey })),
)

// : the project segment rides the global header context (the page-level
// selector is gone); an empty context keeps the honest "pick a project"
// empty state, deep links and header switches both land through the composable.
const { projectId, followTick } = useProjectPageContext()
const caseId = ref<number | null>(null)
const statusFilter = ref<number | '' | undefined>(undefined)
const loading = ref(false)
const casesLoading = ref(false)
const busyId = ref<number | null>(null)
const rows = ref<AppautoStepSuggestion[]>([])
const caseOptions = ref<CaseItem[]>([])
const gateOffVisible = ref(false)

function statusTagType(status: number): 'warning' | 'success' | 'info' {
  if (status === 2) return 'success'
  if (status === 3) return 'info'
  return 'warning'
}

function gotoConfigs(): void {
  void router.push('/configs')
}

async function load() {
  gateOffVisible.value = false
  if (!projectId.value || !caseId.value) {
    rows.value = []
    return
  }
  loading.value = true
  try {
    const status = statusFilter.value ? statusFilter.value : undefined
    rows.value = await listStepSuggestions(projectId.value, caseId.value, status)
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('appauto.healing.loadFailed')))
    rows.value = []
  } finally {
    loading.value = false
  }
}

async function onProjectChange() {
  caseId.value = null
  rows.value = []
  caseOptions.value = []
  if (!projectId.value) return
  casesLoading.value = true
  try {
    const r = await listCases({ projectId: projectId.value, page: 1, size: 200 })
    caseOptions.value = r.list || []
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('appauto.healing.loadFailed')))
  } finally {
    casesLoading.value = false
  }
}


async function currentScriptVersion(targetCaseId: number): Promise<number | undefined> {
  try {
    const script = await getCaseScript(targetCaseId)
    return script ? script.version : undefined
  } catch {
    return undefined
  }
}

async function adopt(row: AppautoStepSuggestion): Promise<void> {
  try {
    await ElMessageBox.confirm(
      t('appauto.healing.adoptConfirm', { step: row.stepNo }),
      t('appauto.healing.adoptConfirmTitle'),
      { type: 'warning', confirmButtonText: t('common.confirm'), cancelButtonText: t('common.cancel') },
    )
  } catch {
    return
  }
  busyId.value = row.id
  try {
    const version = await currentScriptVersion(row.caseId ?? 0)
    await adoptStepSuggestion(row.id, version)
    ElMessage.success(t('appauto.healing.adopted'))
    await load()
  } catch (e) {
    const code = e instanceof BizError ? String(e.code) : ''
    if (code === '40301') {
      gateOffVisible.value = true
    } else if (code === '40901') {
      ElMessage.error(bizErrorMsg(e, t('appauto.healing.loadFailed')))
      await load()
    } else if (code === '40904') {
      ElMessage.warning(bizErrorMsg(e, t('appauto.healing.loadFailed')))
      await load()
    } else {
      ElMessage.error(bizErrorMsg(e, t('appauto.healing.loadFailed')))
    }
  } finally {
    busyId.value = null
  }
}

async function reject(row: AppautoStepSuggestion): Promise<void> {
  try {
    await ElMessageBox.confirm(
      t('appauto.healing.rejectConfirm'),
      t('appauto.healing.rejectConfirmTitle'),
      { type: 'info', confirmButtonText: t('common.confirm'), cancelButtonText: t('common.cancel') },
    )
  } catch {
    return
  }
  busyId.value = row.id
  try {
    await rejectStepSuggestion(row.id)
    ElMessage.success(t('appauto.healing.rejected'))
    await load()
  } catch (e) {
    if (e instanceof BizError && String(e.code) === '40904') {
      ElMessage.warning(bizErrorMsg(e, t('appauto.healing.loadFailed')))
      await load()
    } else {
      ElMessage.error(bizErrorMsg(e, t('appauto.healing.loadFailed')))
    }
  } finally {
    busyId.value = null
  }
}

// : a header context switch reloads from the new segment (in-page
// switches reload through their own controls, so followTick only fires
// on context-driven changes and never double-loads).
watch(followTick, () => {
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
.gate-off-alert {
  margin-bottom: 12px;
}
.gate-off-body {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.gate-off-hint {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
}
.proposed-cell {
  line-height: 1.5;
}
.proposed-action {
  font-weight: 600;
}
.proposed-target {
  font-size: 12px;
  color: var(--tp-text-secondary);
  word-break: break-all;
}
.proposed-coord {
  font-size: 12px;
  color: var(--tp-text-secondary);
}
.reason-text {
  font-size: 12px;
  line-height: 1.5;
  word-break: break-all;
}
.handled-hint {
  color: var(--tp-text-secondary);
  font-size: 12px;
}
.caliber-note {
  margin-top: 12px;
  font-size: 12px;
  color: var(--tp-text-secondary);
  line-height: 1.6;
}
</style>
