<!-- tp-sort-exempt: the K8 config catalog is operator-defined keys (dozens) rendered in full. -->
<template>
  <div class="page-root" data-test="config-page">
    <div class="page-card">
      <div class="toolbar">
        <div class="toolbar-right">
          <el-button data-test="config-retention-preview" @click="openRetention">
            <el-icon><Clock /></el-icon>{{ t('config.previewCleanup') }}
          </el-button>
          <el-radio-group v-model="groupFilter" size="default" data-test="config-group-filter" @change="load">
            <el-radio-button :value="''">{{ t('config.groupAll') }}</el-radio-button>
            <el-radio-button v-for="g in GROUP_OPTIONS" :key="g.value" :value="g.value">{{ g.label }}</el-radio-button>
          </el-radio-group>
          <el-button type="primary" data-test="config-create" @click="openCreate">
            <el-icon><Plus /></el-icon>{{ t('config.create') }}
          </el-button>
          <!-- shared column-settings popover (single piece, no fork) -->
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

 
      <div v-if="repairEnabledCfg || repairRoundsCfg" class="repair-gate-card" data-test="repair-gate-card">
        <div class="repair-gate-title">
          <el-icon><MagicStick /></el-icon>
          <span>{{ t('config.repairGateTitle') }}</span>
          <span class="repair-gate-sub">{{ t('config.repairGateSub') }}</span>
        </div>
        <div class="repair-gate-row">
          <span class="repair-gate-label">{{ t('config.repairEnabledLabel') }}</span>
          <el-switch
            :model-value="repairEnabledCfg?.configValue === '1'"
            :disabled="!repairEnabledCfg || gateSaving"
            data-test="repair-gate-enabled"
            @change="onRepairEnabledChange"
          />
          <span class="repair-gate-hint">{{ t('config.repairEnabledHint') }}</span>
        </div>
        <div class="repair-gate-row">
          <span class="repair-gate-label">{{ t('config.repairRoundsLabel') }}</span>
          <el-input-number
            :model-value="Number(repairRoundsCfg?.configValue ?? 3)"
            :min="1"
            :max="10"
            :disabled="!repairRoundsCfg || gateSaving"
            size="small"
            data-test="repair-gate-rounds"
            @change="onRepairRoundsChange"
          />
          <span class="repair-gate-hint">{{ t('config.repairRoundsHint') }}</span>
        </div>
      </div>

 
      <div v-if="styleGateCfg" class="repair-gate-card" data-test="style-gate-card">
        <div class="repair-gate-title">
          <el-icon><MagicStick /></el-icon>
          <span>{{ t('config.styleGateTitle') }}</span>
          <span class="repair-gate-sub">{{ t('config.styleGateSub') }}</span>
        </div>
        <div class="repair-gate-row">
          <span class="repair-gate-label">{{ t('config.styleGateLabel') }}</span>
          <el-switch
            :model-value="styleGateCfg.configValue === '1'"
            :disabled="gateSaving"
            data-test="style-gate-enabled"
            @change="onStyleGateChange"
          />
          <span class="repair-gate-hint">{{ t('config.styleGateHint') }}</span>
        </div>
      </div>

      <!-- R-4 AI mock switch family : the master gate
 ai.mock.enabled ANDed with one sub-switch per AI application point.
 Each switch states what it does / what it costs in tokens / how the
 system degrades when off. Missing key = backend not migrated, the
 card hides instead of faking state (same posture as repair card). -->
      <div v-if="aiMockMasterCfg" class="repair-gate-card" data-test="ai-mock-gate-card">
        <div class="repair-gate-title">
          <el-icon><MagicStick /></el-icon>
          <span>{{ t('config.aiMockGateTitle') }}</span>
          <span class="repair-gate-sub">{{ t('config.aiMockGateSub') }}</span>
        </div>
        <div class="repair-gate-row">
          <span class="repair-gate-label">{{ t('config.aiMockMasterLabel') }}</span>
          <el-switch
            :model-value="aiMockMasterCfg.configValue === '1'"
            :disabled="gateSaving"
            data-test="ai-mock-gate-master"
            @change="(v: boolean) => saveGateCfg(aiMockMasterCfg!, v ? '1' : '0')"
          />
          <div class="gate-hint-list">
            <span>{{ t('config.aiMockMasterDo') }}</span>
            <span>{{ t('config.aiMockMasterToken') }}</span>
            <span>{{ t('config.aiMockMasterOff') }}</span>
          </div>
        </div>
        <div v-for="sub in aiMockSubs" :key="sub.key" class="repair-gate-row">
          <span class="repair-gate-label">{{ t(sub.labelKey) }}</span>
          <el-switch
            :model-value="sub.cfg?.configValue === '1'"
            :disabled="!sub.cfg || aiMockMasterCfg.configValue !== '1' || gateSaving"
            :data-test="sub.testId"
            @change="(v: boolean) => sub.cfg && saveGateCfg(sub.cfg, v ? '1' : '0')"
          />
          <div class="gate-hint-list">
            <span>{{ t(sub.doKey) }}</span>
            <span>{{ t(sub.tokenKey) }}</span>
            <span>{{ t(sub.offKey) }}</span>
          </div>
        </div>
      </div>

      <TableSkeleton v-if="loading" :cols="6" data-test="configlistview-skeleton" />
      <DataTablePage
        :data="list"
        data-test="config-table"
        row-key="id"
        :size="colCfg.state.density"
        :column-config="colCfg"
        v-else
      >
        <template #empty>
          <EmptyState
            :filtered="hasActiveFilter"
            :title="hasActiveFilter ? t('config.emptyGroup') : t('config.empty')"
            :hint="hasActiveFilter ? t('config.emptyGroupHint') : t('config.emptyHint')"
            :action-text="t('config.create')"
            @action="openCreate"
            @clear-filter="showAllConfigs"
          />
        </template>
        <!-- configurable data columns (show/hide, order, width,
 density persist through the shared useColumnConfig piece). -->
        <el-table-column
          v-for="col in colCfg.visibleColumns.value"
          :key="col.key"
          :prop="col.key"
          :fixed="col.fixed || false"
          :label="t(col.labelKey)"
          :width="col.width ?? undefined"
          :min-width="col.width ? undefined : CONFIG_FLEX_MIN[col.key]"
          :show-overflow-tooltip="col.key === 'description'"
          :data-test="`config-col-${col.key}`"
        >
          <template #default="{ row }">
            <code v-if="col.key === 'configKey'" class="config-key">{{ row.configKey }}</code>
            <template v-else-if="col.key === 'value'">
              <span v-if="row.encryptFlag === 1" class="masked" data-test="config-masked">******</span>
              <span v-else data-test="config-plain">{{ row.configValue || '—' }}</span>
              <el-tag v-if="row.encryptFlag === 1" size="small" type="warning" class="sensitive-tag">{{ t('config.sensitive') }}</el-tag>
            </template>
            <span v-else>{{ row[col.key] }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.action')" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" data-test="config-edit" @click="openEdit(row)">{{ t('common.edit') }}</el-button>
            <el-button link type="danger" data-test="config-delete" @click="onDelete(row)">{{ t('common.delete') }}</el-button>
          </template>
        </el-table-column>
      </DataTablePage>
    </div>

    
    <el-dialog draggable
      v-model="dialogVisible"
      :title="editing ? t('config.editTitle') : t('config.create')"
      width="640px"
      destroy-on-close
      data-test="config-dialog"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px" scroll-to-error>
        <el-form-item :label="t('config.colKey')" prop="configKey">
          <el-input
            v-model="form.configKey"
            :placeholder="t('config.keyPh')"
            :disabled="!!editing"
            data-test="config-key-input"
          />
        </el-form-item>
        <el-form-item :label="t('config.colValue')" prop="configValue">
          <el-input
            v-model="form.configValue"
            :type="form.encryptFlag === 1 ? 'password' : 'text'"
            :placeholder="form.encryptFlag === 1 ? t('config.sensitiveValuePh') : t('config.valuePh')"
            show-password
            data-test="config-value-input"
          />
        </el-form-item>
        <el-form-item :label="t('config.colGroup')" prop="groupName">
          <el-select v-model="form.groupName" style="width: 100%" data-test="config-group-input">
            <el-option v-for="g in GROUP_OPTIONS" :key="g.value" :label="g.label" :value="g.value" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('config.valueTypeLabel')" prop="valueType">
          <el-select v-model="form.valueType" style="width: 100%" data-test="config-type-input">
            <el-option v-for="v in VALUE_TYPES" :key="v" :label="v" :value="v" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('config.sensitiveLabel')">
          <el-switch
            v-model="form.encryptFlag"
            :active-value="1"
            :inactive-value="0"
            :active-text="t('config.encryptOn')"
            :inactive-text="t('config.plain')"
            data-test="config-encrypt-switch"
          />
        </el-form-item>
        <el-form-item :label="t('config.colDesc')" prop="description">
          <el-input v-model="form.description" type="textarea" :rows="2" :placeholder="t('config.descPh')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" data-test="config-submit" @click="onSubmit">
          {{ t('common.confirm') }}
        </el-button>
      </template>
    </el-dialog>

    
    <el-dialog draggable v-model="retentionVisible" :title="t('config.retentionTitle')" width="640px" destroy-on-close data-test="retention-dialog">
      <p class="retention-tip">
        {{ t('config.retentionTipA') }}
        {{ t('config.retentionTipB') }}<b>{{ t('config.retentionIrreversible') }}</b>{{ t('config.retentionTipC') }}
      </p>
      <DataTablePage v-loading="retentionLoading" :data="retentionItems" data-test="retention-table" :loading="retentionLoading">
        <el-table-column prop="label" :label="t('config.colDataType')" min-width="110" />
        <el-table-column :label="t('config.colRetention')" width="100">
          <template #default="{ row }">{{ t('config.daysN', { n: row.days }) }}</template>
        </el-table-column>
        <el-table-column :label="t('config.colCutoff')" min-width="180">
          <template #default="{ row }">{{ row.cutoff ? String(row.cutoff).replace('T', ' ').slice(0, 19) : '—' }}</template>
        </el-table-column>
        <el-table-column :label="t('config.colWillCleanup')" width="110" align="right">
          <template #default="{ row }">
            <span :class="{ 'retention-hot': row.count > 0 }" data-test="retention-count">{{ row.count }}</span>
          </template>
        </el-table-column>
      </DataTablePage>
      <template #footer>
        <el-button @click="retentionVisible = false">{{ t('common.close') }}</el-button>
        <el-button data-test="retention-refresh" :loading="retentionLoading" @click="loadRetention">{{ t('config.rePreview') }}</el-button>
        <el-button
          type="danger"
          :loading="retentionRunning"
          :disabled="retentionItems.length === 0 || retentionTotal === 0"
          data-test="retention-run"
          @click="onRetentionRun"
        >
          {{ t('config.runCleanup', { n: retentionTotal }) }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref, computed } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Clock, MagicStick, Plus } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import ColumnSettings from '@/components/ColumnSettings.vue'
import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'
import {
  createConfig,
  deleteConfig,
  listConfigs,
  previewRetentionCleanup,
  runRetentionCleanup,
  updateConfig,
  type ConfigView,
  type RetentionItemView,
} from '@/api/config'

const { t } = useI18n()

// ---------- column configuration (shared useColumnConfig piece) ----------
/** Configurable data columns (the fixed-right action column stays locked out). */
const CONFIG_COLUMNS: ColumnDef[] = [
  { key: 'configKey', labelKey: 'config.colKey', width: null },
  { key: 'value', labelKey: 'config.colValue', width: null },
  { key: 'groupName', labelKey: 'config.colGroup', width: 90 },
  { key: 'valueType', labelKey: 'config.colType', width: 90 },
  { key: 'description', labelKey: 'config.colDesc', width: null },
]
/** Flexible columns fall back to min-width when no explicit width is set. */
const CONFIG_FLEX_MIN: Record<string, number> = { configKey: 220, value: 160, description: 200 }
const colCfg = useColumnConfig('configs', CONFIG_COLUMNS)
/** Popover rows (column state merged with labelKey for rendering). */
const settingsRows = computed(() =>
  colCfg.state.columns.map((c) => ({ ...c, labelKey: CONFIG_COLUMNS.find((d) => d.key === c.key)!.labelKey })),
)

const GROUP_MODEL = '\u6a21\u578b'
const GROUP_AI = 'AI'
const GROUP_SYSTEM = '\u7cfb\u7edf'
const GROUP_SECURITY = '\u5b89\u5168'
const GROUP_NOTIFY = '\u901a\u77e5'
const GROUP_APPAUTO = 'APP\u81ea\u52a8\u5316'
const GROUP_VALUES = [GROUP_MODEL, GROUP_AI, GROUP_SYSTEM, GROUP_SECURITY, GROUP_NOTIFY, GROUP_APPAUTO]
const VALUE_TYPES = ['string', 'int', 'bool', 'json']

const GROUP_OPTIONS = computed(() =>
  [
    { value: GROUP_MODEL, label: t('config.groupModel') },
    { value: GROUP_AI, label: t('config.groupAi') },
    { value: GROUP_SYSTEM, label: t('config.groupSystem') },
    { value: GROUP_SECURITY, label: t('config.groupSecurity') },
    { value: GROUP_NOTIFY, label: t('config.groupNotify') },
    { value: GROUP_APPAUTO, label: t('config.groupAppauto') },
  ])

const list = ref<ConfigView[]>([])
const loading = ref(false)
const saving = ref(false)
const groupFilter = ref('')
const dialogVisible = ref(false)
const editing = ref<ConfigView | null>(null)
const formRef = ref<FormInstance>()

const form = reactive({
  configKey: '',
  configValue: '',
  groupName: GROUP_SYSTEM,
  valueType: 'string',
  encryptFlag: 0,
  description: '',
})

const rules = computed<FormRules>(() => ({
  configKey: [{ required: true, message: t('config.rulesKey'), trigger: 'blur' }],
  configValue: [{ required: true, message: t('config.rulesValue'), trigger: 'blur' }],
  groupName: [{ required: true, message: t('config.rulesGroup'), trigger: 'change' }],
  valueType: [{ required: true, message: t('config.rulesType'), trigger: 'change' }],
}))

async function load() {
  loading.value = true
  try {
    list.value = await listConfigs(groupFilter.value || undefined)
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  Object.assign(form, { configKey: '', configValue: '', groupName: GROUP_SYSTEM, valueType: 'string', encryptFlag: 0, description: '' })
  dialogVisible.value = true
}

function openEdit(row: ConfigView) {
  editing.value = row
  Object.assign(form, {
    configKey: row.configKey,
    configValue: '',
    groupName: row.groupName,
    valueType: row.valueType,
    encryptFlag: row.encryptFlag,
    description: row.description || '',
  })
  dialogVisible.value = true
}

async function onSubmit() {
  await formRef.value?.validate()
  saving.value = true
  try {
    if (editing.value) {
      await updateConfig(editing.value.id, { ...form })
      ElMessage.success(t('config.updated'))
    } else {
      await createConfig({ ...form })
      ElMessage.success(t('config.created'))
    }
    dialogVisible.value = false
    await load()
  } catch (e: any) {
    ElMessage.error(e?.msg || e?.message || t('config.saveFailed'))
  } finally {
    saving.value = false
  }
}

async function onDelete(row: ConfigView) {
  try {
    await ElMessageBox.confirm(
      t('config.deleteConfirm', { key: row.configKey }),
      t('config.deleteTitle'),
      { type: 'warning', confirmButtonText: t('common.delete'), cancelButtonText: t('common.cancel') },
    )
  } catch {
    return
  }
  try {
    await deleteConfig(row.id)
    ElMessage.success(t('config.deleted'))
    await load()
  } catch (e: any) {
    ElMessage.error(e?.msg || e?.message || t('config.deleteFailed'))
  }
}


const retentionVisible = ref(false)
const retentionLoading = ref(false)
const retentionRunning = ref(false)
const retentionItems = ref<RetentionItemView[]>([])
const retentionTotal = computed(() => retentionItems.value.reduce((s, i) => s + i.count, 0))

function openRetention() {
  retentionVisible.value = true
  void loadRetention()
}

async function loadRetention() {
  retentionLoading.value = true
  try {
    retentionItems.value = await previewRetentionCleanup()
  } catch (e: any) {
    ElMessage.error(e?.msg || e?.message || t('config.previewFailed'))
  } finally {
    retentionLoading.value = false
  }
}

async function onRetentionRun() {
  try {
    await ElMessageBox.confirm(
      t('config.cleanupConfirm', { n: retentionTotal.value }),
      t('config.cleanupTitle'),
      { type: 'warning', confirmButtonText: t('config.cleanupConfirmBtn'), cancelButtonText: t('common.cancel') },
    )
  } catch {
    return
  }
  retentionRunning.value = true
  try {
    const removed = await runRetentionCleanup()
    const total = removed.reduce((s, i) => s + i.count, 0)
    ElMessage.success(t('config.cleanupDone', { total }))
    retentionItems.value = removed
  } catch (e: any) {
    ElMessage.error(e?.msg || e?.message || t('config.cleanupFailed'))
  } finally {
    retentionRunning.value = false
  }
}

const REPAIR_KEY_ENABLED = 'ai.repair.enabled'
const REPAIR_KEY_ROUNDS = 'ai.repair.rounds'
const STYLE_KEY_ENABLED = 'script.style-gate.enabled'
const gateSaving = ref(false)
const repairEnabledCfg = computed(() => list.value.find((c) => c.configKey === REPAIR_KEY_ENABLED) ?? null)
const repairRoundsCfg = computed(() => list.value.find((c) => c.configKey === REPAIR_KEY_ROUNDS) ?? null)
const styleGateCfg = computed(() => list.value.find((c) => c.configKey === STYLE_KEY_ENABLED) ?? null)

// R-4 : AI mock switch family — master gate ∧ per-application-point
// sub-switches; sub-switches are disabled while the master is off because the
// backend ANDs them (a lone "on" sub would fake a capability that never runs).
const AIMOCK_KEY_MASTER = 'ai.mock.enabled'
const aiMockMasterCfg = computed(() => list.value.find((c) => c.configKey === AIMOCK_KEY_MASTER) ?? null)
const aiMockSubs = computed(() =>
  [
    { key: 'ai.mock.nl-stub', testId: 'ai-mock-gate-nl-stub', labelKey: 'config.aiMockNlLabel', doKey: 'config.aiMockNlDo', tokenKey: 'config.aiMockNlToken', offKey: 'config.aiMockNlOff' },
    { key: 'ai.mock.edge-scenarios', testId: 'ai-mock-gate-edge', labelKey: 'config.aiMockEdgeLabel', doKey: 'config.aiMockEdgeDo', tokenKey: 'config.aiMockEdgeToken', offKey: 'config.aiMockEdgeOff' },
    { key: 'ai.mock.spec-sync', testId: 'ai-mock-gate-spec-sync', labelKey: 'config.aiMockSpecLabel', doKey: 'config.aiMockSpecDo', tokenKey: 'config.aiMockSpecToken', offKey: 'config.aiMockSpecOff' },
  ].map((s) => ({ ...s, cfg: list.value.find((c) => c.configKey === s.key) ?? undefined })),
)


async function saveGateCfg(cfg: ConfigView, nextValue: string): Promise<boolean> {
  gateSaving.value = true
  try {
    await updateConfig(cfg.id, {
      configKey: cfg.configKey,
      configValue: nextValue,
      groupName: cfg.groupName,
      valueType: cfg.valueType,
      encryptFlag: cfg.encryptFlag,
      description: cfg.description ?? undefined,
    })
    await load()
    ElMessage.success(t('config.repairGateSaved', { key: cfg.configKey, value: nextValue }))
    return true
  } catch (e: any) {
    ElMessage.error(e?.msg || e?.message || t('config.repairGateSaveFailed'))
    return false
  } finally {
    gateSaving.value = false
  }
}

function onRepairEnabledChange(checked: boolean) {
  if (repairEnabledCfg.value) void saveGateCfg(repairEnabledCfg.value, checked ? '1' : '0')
}

function onRepairRoundsChange(val: number | undefined) {
  if (repairRoundsCfg.value && val != null) void saveGateCfg(repairRoundsCfg.value, String(val))
}

function onStyleGateChange(checked: boolean) {
  if (styleGateCfg.value) void saveGateCfg(styleGateCfg.value, checked ? '1' : '0')
}

onMounted(load)

const hasActiveFilter = computed(() => groupFilter.value !== '')
function showAllConfigs(): void { groupFilter.value = ''; load() }
</script>

<style scoped>
.repair-gate-card {
  margin: 0 0 16px;
  padding: 12px 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-fill-color-lighter);
}
.repair-gate-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
  margin-bottom: 10px;
}
.repair-gate-sub {
  font-weight: 400;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.repair-gate-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 6px 0;
}
.repair-gate-label {
  min-width: 170px;
  font-size: 13px;
}
.repair-gate-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.gate-hint-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}
.config-key {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
  color: var(--el-color-primary);
}
.masked {
  font-family: 'JetBrains Mono', Consolas, monospace;
  letter-spacing: 2px;
  color: var(--el-color-warning);
}
.retention-tip {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}
.retention-hot {
  font-weight: 600;
  color: var(--el-color-danger);
}

.sensitive-tag {
  margin-left: 8px;
}
</style>
