<!-- tp-sort-exempt: LLM provider configs are hand-provisioned (a handful), served in full. -->
<template>
  <div class="page-root" data-test="llm-config-page">
    <div class="page-card">
      <div class="toolbar">
        <div class="toolbar-right">
          <el-select
            v-model="typeFilter"
            style="width: 140px; margin-right: 8px"
            data-test="llm-type-filter"
            @change="load"
          >
            <el-option :label="t('llmConfig.typeAll')" :value="0" />
            <el-option :label="t('llmConfig.typeGen')" :value="1" />
            <el-option label="Embedding" :value="2" />
          </el-select>
          <el-button type="primary" data-test="llm-create" @click="openCreate">
            <el-icon><Plus /></el-icon>{{ t('llmConfig.create') }}
          </el-button>
          
          <el-button data-test="golden-ledger-open" @click="openLedger">
            <el-icon><Tickets /></el-icon>{{ t('llmConfig.goldenLedger') }}
          </el-button>
          <!-- R25 mode declaration (scope-aware single control, TB group): this page carries no
               tabs and exactly one table, so the page-level column-settings popover equals the
               table scope; show/hide, order, pinning and density persist per user via the piece. -->
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

      <TableSkeleton v-if="loading" :cols="colCfg.visibleColumns.value.length + 1" data-test="llmconfiglistview-skeleton" />
      <!-- Config table: rows are hand-provisioned and served in full (tp-sort-exempt), so the
           loop never claims a sort header. Data columns render through the shared column-config
           loop so show/hide, order and pinning take effect; the action column stays locked
           outside. -->
      <el-table border :data="list" :size="colCfg.state.density" data-test="llm-table" row-key="id" v-else>
        <el-table-column
          v-for="col in colCfg.visibleColumns.value"
          :key="col.key"
          :prop="col.key"
          :fixed="col.fixed || false"
          :label="t(col.labelKey)"
          :width="col.width ?? undefined"
          :min-width="col.width ? undefined : LLM_FLEX_MIN[col.key]"
          :align="col.key === 'promptLimitTokens' ? 'right' : undefined"
          :show-overflow-tooltip="col.key === 'baseUrl' || col.key === 'description' || undefined"
          :data-test="`llm-col-${col.key}`"
        >
          <template #default="{ row }">
            <template v-if="col.key === 'name'">
              <span class="model-name">{{ row.name }}</span>
              <el-tag v-if="row.isDefault === 1" size="small" type="success" style="margin-left: 6px">{{ row.modelType === 2 ? t('llmConfig.tagEmbedDefault') : t('llmConfig.tagSysDefault') }}</el-tag>
            </template>
            <template v-else-if="col.key === 'modelType'">
              <el-tag :type="row.modelType === 2 ? 'warning' : 'primary'" size="small" effect="plain">
                {{ row.modelType === 2 ? 'Embedding' : t('llmConfig.typeGenShort') }}
              </el-tag>
            </template>
            <template v-else-if="col.key === 'apiKeyMasked'">
              <span v-if="row.apiKeyMasked" class="masked" data-test="llm-key-masked">******</span>
              <span v-else class="muted">—</span>
            </template>
            <template v-else-if="col.key === 'status'">
              <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
                {{ row.status === 1 ? t('llmConfig.statusOn') : t('llmConfig.statusOff') }}
              </el-tag>
            </template>
            <template v-else-if="col.key === 'promptLimitTokens'">{{ row.promptLimitTokens?.toLocaleString() ?? '—' }}</template>
            <template v-else>{{ row[col.key] }}</template>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.action')" width="230" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" data-test="llm-edit" @click="openEdit(row)">{{ t('common.edit') }}</el-button>
            <el-button link type="danger" data-test="llm-delete" @click="onDelete(row)">{{ t('common.delete') }}</el-button>
            
            <el-button link type="primary" data-test="llm-adopt-ledger" @click="adoptRouteFilter(row.name)">{{ t('llmConfig.adoptLedger') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      <EmptyState v-if="!loading && list.length === 0" :title="t('llmConfig.empty')" :action-text="t('llmConfig.create')" @action="openCreate" />
    </div>

    
    <el-dialog draggable
      v-model="dialogVisible"
      :title="editing ? t('llmConfig.editTitle') : t('llmConfig.create')"
      width="640px"
      destroy-on-close
      data-test="llm-dialog"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px" scroll-to-error>
        <el-form-item :label="t('llmConfig.formModelType')" prop="modelType">
          <!-- id="" prevents the form label for= from targeting the non-labelable radiogroup div -->
          <el-radio-group id="" v-model="form.modelType" data-test="llm-type-radio">
            <el-radio :value="1">{{ t('llmConfig.typeGen') }}</el-radio>
            <el-radio :value="2">Embedding</el-radio>
          </el-radio-group>
          <span class="hint-text">{{ t('llmConfig.hintModelType') }}</span>
        </el-form-item>
        <el-form-item v-if="form.modelType === 2" :label="t('llmConfig.formEmbedDim')" prop="embeddingDimension">
          <el-input-number
            v-model="form.embeddingDimension"
            :min="1"
            :max="16000"
            controls-position="right"
            style="width: 220px"
            data-test="llm-dim-input"
          />
          <span class="hint-text">{{ t('llmConfig.hintEmbedDim') }}</span>
        </el-form-item>
        <el-form-item :label="t('llmConfig.formName')" prop="name">
          <el-input v-model="form.name" :placeholder="t('llmConfig.namePh')" data-test="llm-name-input" />
        </el-form-item>
        <el-form-item :label="t('llmConfig.colProvider')" prop="provider">
          <el-select v-model="form.provider" style="width: 100%" data-test="llm-provider-input">
            <el-option v-for="p in PROVIDERS" :key="p" :label="p" :value="p" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('llmConfig.colModelName')" prop="modelName">
          <el-input v-model="form.modelName" :placeholder="t('llmConfig.modelNamePh')" data-test="llm-model-input" />
        </el-form-item>
        <el-form-item :label="t('llmConfig.colApiKey')">
          <el-input
            v-model="form.apiKey"
            type="password"
            show-password
            :placeholder="editing ? t('llmConfig.keyPhEditing') : t('llmConfig.keyPhCreate')"
            data-test="llm-key-input"
          />
        </el-form-item>
        <el-form-item :label="t('llmConfig.colBaseUrl')">
          <el-input v-model="form.baseUrl" :placeholder="t('llmConfig.baseUrlPh')" data-test="llm-baseurl-input" />
        </el-form-item>
        <el-form-item :label="t('llmConfig.colPromptLimit')" prop="promptLimitTokens">
          <el-input-number
            v-model="form.promptLimitTokens"
            :min="1024"
            :max="10000000"
            :step="1024"
            controls-position="right"
            style="width: 220px"
            data-test="llm-prompt-limit-input"
          />
          <span class="hint-text">{{ t('llmConfig.hintPromptLimit') }}</span>
        </el-form-item>
        <el-form-item :label="t('llmConfig.colStatus')">
          <el-switch
            v-model="form.status"
            :active-value="1"
            :inactive-value="0"
            :active-text="t('llmConfig.statusOn')"
            :inactive-text="t('llmConfig.statusOff')"
            data-test="llm-status-switch"
          />
        </el-form-item>
        <el-form-item :label="t('llmConfig.tagSysDefault')">
          <el-switch
            v-model="form.isDefault"
            :active-value="1"
            :inactive-value="0"
            :active-text="t('llmConfig.onTextDefault')"
            :inactive-text="t('llmConfig.offTextDefault')"
            data-test="llm-default-switch"
          />
        </el-form-item>
        <el-form-item :label="t('llmConfig.colDesc')">
          <el-input v-model="form.description" type="textarea" :rows="2" data-test="llm-desc-input" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" data-test="llm-save" @click="onSave">{{ t('common.confirm') }}</el-button>
      </template>
    </el-dialog>

    
    <el-dialog draggable
      v-model="ledgerVisible"
      :title="t('llmConfig.goldenLedger')"
      width="980px"
      destroy-on-close
      data-test="golden-ledger-dialog"
    >
      <div class="ledger-filter" data-test="golden-ledger-filter">
        <el-input
          v-model="ledgerDomain"
          :placeholder="t('llmConfig.ledgerDomainPh')"
          clearable
          style="width: 220px"
          data-test="golden-ledger-domain-input"
          @keyup.enter="searchLedger"
        />
        <el-input
          v-model="ledgerRouteName"
          :placeholder="t('llmConfig.ledgerRoutePh')"
          clearable
          style="width: 260px"
          data-test="golden-ledger-route-input"
          @keyup.enter="searchLedger"
        />
        <el-button type="primary" data-test="golden-ledger-search" @click="searchLedger">{{ t('llmConfig.ledgerQuery') }}</el-button>
      </div>
      <el-table border :data="ledgerRows" v-loading="ledgerLoading" :size="colCfg.state.density" data-test="golden-ledger-table" row-key="id">
        <el-table-column prop="comparisonKind" :label="t('llmConfig.ledgerColAxis')" width="90" />
        <el-table-column :label="t('llmConfig.ledgerColSwitch')" min-width="200">
          <template #default="{ row }">{{ row.controlRef || '—' }} → {{ row.candidateRef || '—' }}</template>
        </el-table-column>
        <el-table-column prop="packName" :label="t('llmConfig.ledgerColPack')" min-width="150" show-overflow-tooltip />
        <el-table-column :label="t('llmConfig.ledgerColSamples')" width="90" align="right">
          <template #default="{ row }">{{ row.sampleTotal }}</template>
        </el-table-column>
        <el-table-column :label="t('llmConfig.ledgerColDrift')" width="110" align="right">
          <template #default="{ row }">{{ Number(row.driftPercentPp).toFixed(2) }} / {{ row.thresholdPp }}</template>
        </el-table-column>
        <el-table-column :label="t('llmConfig.ledgerColVerdict')" width="110">
          <template #default="{ row }">
            <el-tag :type="row.passed ? 'success' : 'danger'" size="small">
              {{ row.passed ? t('llmConfig.ledgerPass') : t('llmConfig.ledgerBlock') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="note" :label="t('llmConfig.ledgerColNote')" min-width="140" show-overflow-tooltip />
        <el-table-column :label="t('llmConfig.ledgerColTime')" width="160">
          <template #default="{ row }">{{ formatLedgerTime(row.createdAt) }}</template>
        </el-table-column>
      </el-table>
      <EmptyState
        v-if="!ledgerLoading && ledgerRows.length === 0"
        :title="t('llmConfig.ledgerEmptyTitle')"
        :hint="t('llmConfig.ledgerEmptyHint')"
      />
      <div v-if="ledgerTotal > 0" class="pagination-bar">
        <el-pagination
          layout="total, prev, pager, next"
          :total="ledgerTotal"
          :current-page="ledgerPage"
          :page-size="LEDGER_PAGE_SIZE"
          @current-change="onLedgerPage"
        />
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { Plus, Tickets } from '@element-plus/icons-vue'
import {
  createLlmConfig,
  deleteLlmConfig,
  listLlmConfigs,
  listRouteReplays,
  updateLlmConfig,
  type GoldenDriftVerdict,
  type LlmConfigSavePayload,
  type LlmConfigView,
} from '@/api/llmConfig'
import EmptyState from '@/components/EmptyState.vue'
import ColumnSettings from '@/components/ColumnSettings.vue'
import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'

const { t } = useI18n()

const PROVIDERS = ['openai', 'deepseek', 'qwen', 'dashscope-compatible', 'custom']

/** Configurable data columns of the config table (the locked action column stays outside). */
const LLM_COLUMNS: ColumnDef[] = [
  { key: 'name', labelKey: 'llmConfig.colModel', width: null },
  { key: 'modelType', labelKey: 'llmConfig.colType', width: 110 },
  { key: 'provider', labelKey: 'llmConfig.colProvider', width: 110 },
  { key: 'modelName', labelKey: 'llmConfig.colModelName', width: null },
  { key: 'apiKeyMasked', labelKey: 'llmConfig.colApiKey', width: 110 },
  { key: 'baseUrl', labelKey: 'llmConfig.colBaseUrl', width: null },
  { key: 'status', labelKey: 'llmConfig.colStatus', width: 90 },
  { key: 'description', labelKey: 'llmConfig.colDesc', width: null },
  { key: 'promptLimitTokens', labelKey: 'llmConfig.colPromptLimit', width: 110 },
]
/** Flexible columns fall back to min-width when no explicit width is set. */
const LLM_FLEX_MIN: Record<string, number> = { name: 180, modelName: 140, baseUrl: 200, description: 160 }
const colCfg = useColumnConfig('llm', LLM_COLUMNS)
/** Popover rows (column state merged with labelKey for rendering). */
const settingsRows = computed(() =>
  colCfg.state.columns.map((c) => ({ ...c, labelKey: LLM_COLUMNS.find((d) => d.key === c.key)!.labelKey })),
)
const loading = ref(false)
const saving = ref(false)
const list = ref<LlmConfigView[]>([])
const dialogVisible = ref(false)
const editing = ref<LlmConfigView | null>(null)
const formRef = ref<FormInstance>()

const typeFilter = ref(0)


const KEY_EMPTY = ''

const form = reactive<LlmConfigSavePayload>({
  name: '',
  provider: 'deepseek',
  modelName: '',
  apiKey: KEY_EMPTY,
  baseUrl: '',
  status: 1,
  isDefault: 0,
  description: '',
  promptLimitTokens: 1_048_576,
  modelType: 1,
  embeddingDimension: null,
})

function dimRule(_rule: unknown, value: number | null | undefined, callback: (e?: Error) => void) {
  if (form.modelType === 2 && (value == null || value < 1 || value > 16000)) {
    callback(new Error(t('llmConfig.dimRuleMsg')))
  } else {
    callback()
  }
}
function baseUrlRule(_rule: unknown, value: string | null | undefined, callback: (e?: Error) => void) {
  if (form.modelType === 2 && !value) {
    callback(new Error(t('llmConfig.baseUrlRuleMsg')))
  } else {
    callback()
  }
}

const rules = computed(() => ({
  name: [{ required: true, message: t('llmConfig.rulesName'), trigger: 'blur' }],
  provider: [{ required: true, message: t('llmConfig.rulesProvider'), trigger: 'change' }],
  modelName: [{ required: true, message: t('llmConfig.rulesModelName'), trigger: 'blur' }],
  embeddingDimension: [{ validator: dimRule, trigger: 'blur' }],
  baseUrl: [{ validator: baseUrlRule, trigger: 'blur' }],
}))

async function load() {
  loading.value = true
  try {
    list.value = await listLlmConfigs(typeFilter.value === 0 ? null : typeFilter.value)
  } finally {
    loading.value = false
  }
}

function resetForm() {
  Object.assign(form, {
    name: '',
    provider: 'deepseek',
    modelName: '',
    apiKey: KEY_EMPTY,
    baseUrl: '',
    status: 1,
    isDefault: 0,
    description: '',
    promptLimitTokens: 1_048_576,
    modelType: 1,
    embeddingDimension: null,
  })
}

function openCreate() {
  editing.value = null
  resetForm()
  dialogVisible.value = true
}

function openEdit(row: LlmConfigView) {
  editing.value = row
  resetForm()
  Object.assign(form, {
    name: row.name,
    provider: row.provider,
    modelName: row.modelName,
    apiKey: KEY_EMPTY,
    baseUrl: row.baseUrl || '',
    status: row.status,
    isDefault: row.isDefault,
    description: row.description || '',
    promptLimitTokens: row.promptLimitTokens ?? 1_048_576,
    modelType: row.modelType ?? 1,
    embeddingDimension: row.embeddingDimension ?? null,
  })
  dialogVisible.value = true
}

async function onSave() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    const keyPayload = form.apiKey || null
    const payload: LlmConfigSavePayload = {
      ...form,
      apiKey: keyPayload,
      baseUrl: form.baseUrl || null,
      description: form.description || null,
      embeddingDimension: form.modelType === 2 ? form.embeddingDimension ?? null : null,
    }
    if (editing.value) {
      await updateLlmConfig(editing.value.id, payload)
      ElMessage.success(t('llmConfig.updated'))
    } else {
      await createLlmConfig(payload)
      ElMessage.success(t('llmConfig.created'))
    }
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}


const ledgerVisible = ref(false)
const ledgerLoading = ref(false)
const ledgerRows = ref<GoldenDriftVerdict[]>([])
const ledgerTotal = ref(0)
const ledgerPage = ref(1)
const ledgerRouteName = ref('')
const ledgerDomain = ref('')
const LEDGER_PAGE_SIZE = 20

function formatLedgerTime(raw: string): string {
  return (raw || '').replace('T', ' ').slice(0, 19)
}

async function loadLedger() {
  ledgerLoading.value = true
  try {
    const view = await listRouteReplays({
      domain: ledgerDomain.value.trim() || null,
      candidateRef: ledgerRouteName.value.trim() || null,
      page: ledgerPage.value,
      pageSize: LEDGER_PAGE_SIZE,
    })
    ledgerRows.value = view.rows
    ledgerTotal.value = view.total
  } catch (err: any) {
    ElMessage.error(err?.msg || err?.message || t('llmConfig.ledgerLoadFailed'))
  } finally {
    ledgerLoading.value = false
  }
}

function openLedger() {
  ledgerVisible.value = true
  ledgerDomain.value = ''
  ledgerRouteName.value = ''
  ledgerPage.value = 1
  void loadLedger()
}


function adoptRouteFilter(routeName: string) {
  ledgerVisible.value = true
  ledgerRouteName.value = routeName
  ledgerPage.value = 1
  void loadLedger()
}

function searchLedger() {
  ledgerPage.value = 1
  void loadLedger()
}

function onLedgerPage(page: number) {
  ledgerPage.value = page
  void loadLedger()
}

async function onDelete(row: LlmConfigView) {
  try {
    await ElMessageBox.confirm(t('llmConfig.deleteConfirm', { name: row.name }), t('llmConfig.deleteTitle'), {
      type: 'warning',
      confirmButtonText: t('common.delete'),
      cancelButtonText: t('common.cancel'),
    })
  } catch {
    return
  }
  try {
    await deleteLlmConfig(row.id)
    ElMessage.success(t('llmConfig.deleted'))
    await load()
  } catch (err: any) {
    ElMessage.error(err?.msg || err?.message || t('llmConfig.deleteFailed'))
  }
}

onMounted(load)
</script>

<style scoped>
.model-name {
  font-weight: 600;
}
.masked {
  color: var(--el-color-warning);
  letter-spacing: 2px;
}
.muted {
  color: var(--el-text-color-placeholder);
}

.hint-text {
  margin-left: 8px;
  color: var(--tp-text-secondary);   
  font-size: 12px;
}
.ledger-filter {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}
</style>
