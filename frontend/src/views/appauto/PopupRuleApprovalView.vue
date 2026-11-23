<!-- tp-sort-exempt: popup rules are hand-authored per app package (tens of rows) served in full. -->
<template>
  <div class="page-root popup-rule-page" data-test="popup-rule-page">
    <div class="page-card">
      <!-- R25 mode declaration (scope-aware single control, TB group): this page
           carries no tabs and exactly one page-surface table (the approval list
           below), so the page-level column-settings popover equals that scope:
           show/hide, order, pinning and density persist per user through the
           shared useColumnConfig piece.
           FB/R21 same-band merge: no row-level batch bar and one screening
           control (within the themed 4-control threshold), so the learn variant
           action rides the filter-bar trailing slot ahead of the settings
           control and the emptied header band is gone. -->
      <div class="filter-bar" data-test="popup-rule-filter-bar">
        <el-select
          v-model="statusFilter"
          clearable
          :placeholder="t('appauto.popupRules.statusAll')"
          style="width: 130px"
          data-test="popup-rule-status-select"
          data-se1-exempt="popup rule status is a fixed 3-value enum, never grows with remote data"
          @change="load"
        >
          <el-option :label="t('appauto.popupRules.statusPending')" :value="1" />
          <el-option :label="t('appauto.popupRules.statusStored')" :value="2" />
          <el-option :label="t('appauto.popupRules.statusRejected')" :value="3" />
        </el-select>
        <div class="filter-bar-actions">
          <el-tooltip :content="t('components.ownerEcho.pickInHeader')" placement="bottom" :disabled="!!projectId">
            <span>
              <el-button
                type="primary"
                :icon="MagicStick"
                :loading="learning"
                :disabled="!projectId"
                data-test="popup-rule-learn-btn"
                @click="openLearnDialog"
              >
                {{ t('appauto.popupRules.learn') }}
              </el-button>
            </span>
          </el-tooltip>
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
          <el-button :icon="Refresh" :loading="loading" data-test="popup-rule-refresh" @click="load">
            {{ t('appauto.popupRules.refresh') }}
          </el-button>
        </div>
      </div>

      
      <el-alert
        v-if="gateOffVisible"
        type="warning"
        show-icon
        :closable="true"
        class="gate-off-alert"
        data-test="popup-rule-gate-off-alert"
      >
        <template #title>{{ t('appauto.popupRules.gateOffTitle') }}</template>
        <template #default>
          <div class="gate-off-body">
            <p>{{ t('appauto.popupRules.gateOffHint') }}</p>
            <el-button link type="primary" :icon="Setting" data-test="popup-rule-goto-configs" @click="gotoConfigs">
              {{ t('appauto.popupRules.gotoConfigs') }}
            </el-button>
          </div>
        </template>
      </el-alert>

      <div v-if="!loading && !projectId" class="empty-box" data-test="popup-rule-empty-no-project">
        <EmptyState
          :title="t('appauto.popupRules.noProjectTitle')"
          :hint="t('appauto.popupRules.noProjectHint')"
        />
      </div>

      <template v-else>
        <TableSkeleton v-if="loading" :cols="colCfg.visibleColumns.value.length + 2" data-test="popup-rule-skeleton" />
        <el-table v-else :data="rows" border row-key="id" :size="colCfg.state.density" class="data-table" data-test="popup-rule-table">
          <template #empty>
            <EmptyState
              :title="t('appauto.popupRules.emptyTitle')"
              :hint="t('appauto.popupRules.emptyHint')"
            />
          </template>
          <el-table-column type="expand">
            <template #default="{ row }">
              <pre class="rule-json mono" data-test="popup-rule-json">{{ formatRuleJson(row.rule) }}</pre>
            </template>
          </el-table-column>
          <el-table-column
            v-for="col in colCfg.visibleColumns.value"
            :key="col.key"
            :prop="col.key"
            :fixed="col.fixed || false"
            :label="t(col.labelKey)"
            :width="col.width ?? undefined"
            :min-width="col.width ? undefined : POPUP_RULE_FLEX_MIN[col.key]"
            :show-overflow-tooltip="POPUP_RULE_TOOLTIP.has(col.key)"
            :data-test="`popup-rule-col-${col.key}`"
          >
            <template #default="{ row }">
              <template v-if="col.key === 'title'">
                <span data-test="popup-rule-title">{{ row.title || t('appauto.popupRules.titleFallback') }}</span>
              </template>
              <template v-else-if="col.key === 'sourceTask'">
                <span data-test="popup-rule-source-task">{{ row.sourceTaskId ?? '-' }}</span>
              </template>
              <template v-else-if="col.key === 'from'">
                <span data-test="popup-rule-from">{{ row.source === 'AI' ? t('appauto.popupRules.fromAi') : t('appauto.popupRules.fromManual') }}</span>
              </template>
              <template v-else-if="col.key === 'status'">
                <el-tag :type="popupRuleStatusTag(row.status)" disable-transitions data-test="popup-rule-status-tag">
                  {{ t(RULE_STATUS_LABEL_KEY[row.status] ?? 'appauto.popupRules.statusPending') }}
                </el-tag>
              </template>
              <template v-else-if="col.key === 'createdAt'">
                <TimeCell :iso="row.createdAt" />
              </template>
            </template>
          </el-table-column>
          <el-table-column :label="t('appauto.popupRules.colActions')" width="170" fixed="right">
            <template #default="{ row }">
              <template v-if="row.status === 1">
                <el-button
                  link
                  type="primary"
                  :loading="busyId === row.id"
                  data-test="popup-rule-confirm-btn"
                  @click="confirmRule(row)"
                >
                  {{ t('appauto.popupRules.confirm') }}
                </el-button>
                <el-button
                  link
                  type="warning"
                  :loading="busyId === row.id"
                  data-test="popup-rule-reject-btn"
                  @click="rejectRule(row)"
                >
                  {{ t('appauto.popupRules.reject') }}
                </el-button>
              </template>
              <span v-else class="handled-hint">{{ t('appauto.popupRules.handled') }}</span>
            </template>
          </el-table-column>
        </el-table>

        <p class="caliber-note">{{ t('appauto.popupRules.caliber') }}</p>
      </template>

      
      <el-dialog
        v-model="learnDialogVisible"
        draggable
        :title="t('appauto.popupRules.learnDialogTitle')"
        width="560px"
        data-test="popup-rule-learn-dialog"
      >
        <!-- ownership echo (iron rule 1): the learned rule set is scoped to the
             header-context project; read-only, single-sourced -->
        <ProjectOwnerEcho />
        <p class="learn-dialog-hint">{{ t('appauto.popupRules.learnDialogHint') }}</p>
        <el-select
          v-model="learnTaskId"
          filterable
          :loading="tasksLoading"
          :placeholder="t('appauto.popupRules.selectFailedTask')"
          class="learn-task-select"
          data-test="popup-rule-learn-task-select"
        >
          <el-option
            v-for="tk in failedTasks"
            :key="tk.id"
            :label="taskLabel(tk)"
            :value="tk.id"
          />
        </el-select>
        <template #footer>
          <el-button data-test="popup-rule-learn-cancel" @click="learnDialogVisible = false">
            {{ t('common.cancel') }}
          </el-button>
          <el-button
            type="primary"
            :loading="learning"
            :disabled="!learnTaskId"
            data-test="popup-rule-learn-submit"
            @click="submitLearn"
          >
            {{ t('appauto.popupRules.learnSubmit') }}
          </el-button>
        </template>
      </el-dialog>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MagicStick, Refresh, Setting } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import ProjectOwnerEcho from '@/components/ProjectOwnerEcho.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import TimeCell from '@/components/TimeCell.vue'
import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'
import ColumnSettings from '@/components/ColumnSettings.vue'
import { BizError } from '@/api/request'
import { bizErrorMsg } from '@/utils/bizError'
import {
  RULE_STATUS_LABEL_KEY,
  formatRuleJson,
  popupRuleStatusTag,
} from '@/utils/popupRule'
import {
  confirmPopupRule,
  learnPopupRule,
  listPopupRules,
  rejectPopupRule,
  type AppautoPopupRule,
} from '@/api/appauto'
import { listExecTasks, type ExecTask } from '@/api/executor'

const { t } = useI18n()
const router = useRouter()

// : the project segment rides the global header context (the page-level
// selector is gone); an empty context keeps the honest "pick a project"
// empty state, deep links and header switches both land through the composable.
const { projectId, followTick } = useProjectPageContext()

// Column-configuration wiring (N-3 repayment, shared useColumnConfig piece):
// the five data columns flow through the visibleColumns loop so show/hide,
// order, pinning and density persist per user under viewKey 'popup-rule';
// the expand slot and the locked action column stay outside the loop
// (the skeleton adds two fixed slots).
const POPUP_RULE_COLUMNS: ColumnDef[] = [
  { key: 'title', labelKey: 'appauto.popupRules.colTitle', width: null },
  { key: 'sourceTask', labelKey: 'appauto.popupRules.colSourceTask', width: 120 },
  { key: 'from', labelKey: 'appauto.popupRules.colFrom', width: 100 },
  { key: 'status', labelKey: 'appauto.popupRules.colStatus', width: 110 },
  { key: 'createdAt', labelKey: 'appauto.popupRules.colCreatedAt', width: 160 },
]
// the title column is the only flexible one; tooltip ellipsis rides it too
const POPUP_RULE_FLEX_MIN: Record<string, number> = { title: 180 }
const POPUP_RULE_TOOLTIP = new Set(['title'])
const colCfg = useColumnConfig('popup-rule', POPUP_RULE_COLUMNS)
const settingsRows = computed(() =>
  colCfg.state.columns.map((c) => ({ ...c, labelKey: POPUP_RULE_COLUMNS.find((d) => d.key === c.key)!.labelKey })),
)
const statusFilter = ref<number | '' | undefined>(undefined)
const loading = ref(false)
const learning = ref(false)
const busyId = ref<number | null>(null)
const rows = ref<AppautoPopupRule[]>([])
const gateOffVisible = ref(false)

const learnDialogVisible = ref(false)
const learnTaskId = ref<number | null>(null)
const failedTasks = ref<ExecTask[]>([])
const tasksLoading = ref(false)

function gotoConfigs(): void {
  void router.push('/configs')
}

async function load() {
  gateOffVisible.value = false
  if (!projectId.value) {
    rows.value = []
    return
  }
  loading.value = true
  try {
    const status = statusFilter.value ? statusFilter.value : undefined
    rows.value = await listPopupRules({ projectId: projectId.value, status })
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('appauto.popupRules.loadFailed')))
    rows.value = []
  } finally {
    loading.value = false
  }
}



function taskLabel(tk: ExecTask): string {
  const time = (tk.finishedAt || tk.createdAt || '').replace('T', ' ').slice(0, 16)
  return `#${tk.id} · ${tk.errorDetail ? tk.errorDetail.slice(0, 40) : t('appauto.popupRules.taskNoDetail')}${time ? ` · ${time}` : ''}`
}

async function openLearnDialog() {
  learnTaskId.value = null
  learnDialogVisible.value = true
  if (!projectId.value) return
  tasksLoading.value = true
  try {
    const page = await listExecTasks({ projectId: projectId.value, page: 1, size: 200 })
    failedTasks.value = (page.list || []).filter((tk) => tk.status === 4 || tk.status === 5)
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('appauto.popupRules.tasksLoadFailed')))
    failedTasks.value = []
  } finally {
    tasksLoading.value = false
  }
}

async function submitLearn() {
  if (!projectId.value || !learnTaskId.value) return
  learning.value = true
  try {
    const draftId = await learnPopupRule({ taskId: learnTaskId.value, projectId: projectId.value })
    ElMessage.success(t('appauto.popupRules.learnDone', { id: draftId }))
    learnDialogVisible.value = false
    statusFilter.value = 1
    await load()
  } catch (e) {
    const code = e instanceof BizError ? String(e.code) : ''
    if (code === '403' || code === '40301') {
      gateOffVisible.value = true
      learnDialogVisible.value = false
    } else {
      ElMessage.error(bizErrorMsg(e, t('appauto.popupRules.learnFailed')))
    }
  } finally {
    learning.value = false
  }
}

async function confirmRule(row: AppautoPopupRule): Promise<void> {
  try {
    await ElMessageBox.confirm(
      t('appauto.popupRules.confirmHint', { title: row.title || t('appauto.popupRules.titleFallback') }),
      t('appauto.popupRules.confirmTitle'),
      { type: 'warning', confirmButtonText: t('common.confirm'), cancelButtonText: t('common.cancel') },
    )
  } catch {
    return
  }
  busyId.value = row.id
  try {
    await confirmPopupRule(row.id)
    ElMessage.success(t('appauto.popupRules.confirmed'))
    await load()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('appauto.popupRules.actionFailed')))
    await load()
  } finally {
    busyId.value = null
  }
}

async function rejectRule(row: AppautoPopupRule): Promise<void> {
  try {
    await ElMessageBox.confirm(
      t('appauto.popupRules.rejectHint', { title: row.title || t('appauto.popupRules.titleFallback') }),
      t('appauto.popupRules.rejectTitle'),
      { type: 'warning', confirmButtonText: t('common.confirm'), cancelButtonText: t('common.cancel') },
    )
  } catch {
    return
  }
  busyId.value = row.id
  try {
    await rejectPopupRule(row.id)
    ElMessage.success(t('appauto.popupRules.rejected'))
    await load()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('appauto.popupRules.actionFailed')))
    await load()
  } finally {
    busyId.value = null
  }
}

// : a header context switch rewrites the project segment — reset in-page
// screening where the removed selector used to and reload from scratch.
watch(followTick, () => {
  rows.value = []
  void load()
})

onMounted(() => {
  void load()
})

</script>

<style scoped lang="scss">
.popup-rule-page {
  .gate-off-alert {
    margin-bottom: 12px;

    .gate-off-body {
      display: flex;
      align-items: center;
      gap: 8px;

      p {
        margin: 0;
        font-size: 12px;
      }
    }
  }

  .rule-json {
    margin: 0;
    padding: 8px 12px;
    font-size: 12px;
    background: var(--tp-bg-elevated);
    border: 1px solid var(--tp-border);
    border-radius: 6px;
    white-space: pre-wrap;
    word-break: break-all;
  }

  .learn-dialog-hint {
    margin: 0 0 10px;
    font-size: 12px;
    color: var(--tp-text-secondary);
  }

  .learn-task-select {
    width: 100%;
  }

  .handled-hint {
    font-size: 12px;
    color: var(--tp-text-secondary);
  }

  .caliber-note {
    margin-top: 10px;
    font-size: 12px;
    color: var(--tp-text-secondary);
  }
}
</style>
