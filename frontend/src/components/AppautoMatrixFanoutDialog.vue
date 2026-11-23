<template>
  <el-dialog
    :model-value="modelValue"
    :title="t('appauto.matrix.dialogTitle')"
    width="880px"
    draggable
    destroy-on-close
    data-test="matrix-fanout-dialog"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="matrix-body">
      
      <div class="matrix-row">
        <span class="matrix-field-key">{{ t('appauto.matrix.selectEnv') }}</span>
        <el-select
          v-model="envId"
          :loading="envLoading"
          filterable
          style="width: 240px"
          data-test="matrix-env-select"
        >
          <el-option v-for="e in envs" :key="e.id" :label="`${e.name}（${envTypeLabel(e.envType)}）`" :value="e.id" />
        </el-select>
        <span class="matrix-field-key">{{ t('appauto.matrix.batchName') }}</span>
        <el-input
          v-model="batchName"
          clearable
          style="width: 240px"
          :placeholder="t('appauto.matrix.batchNamePlaceholder')"
          data-test="matrix-batch-name"
        />
      </div>

      
      <div class="matrix-row">
        <span class="matrix-col-title">{{ t('appauto.matrix.comboArea') }}</span>
        <el-button
          data-test="matrix-sample-btn"
          :disabled="!comboRows.length"
          :loading="sampling"
          @click="runSample"
        >{{ t('appauto.matrix.sampleBtn') }}</el-button>
      </div>
      <div v-if="sampleError" class="matrix-error" data-test="matrix-sample-error">{{ sampleError }}</div>
      <div v-else-if="sampleView" class="matrix-sample-zone" data-test="matrix-sample-result">
        <el-alert type="info" :closable="false" show-icon data-test="matrix-sample-applied">
          {{ t('appauto.matrix.sampleApplied', { n: sampleAppliedCount }) }}
        </el-alert>
        <div class="matrix-sample-lines">
          <div v-if="sampleView.summary" data-test="matrix-sample-summary">{{ sampleView.summary }}</div>
          <div data-test="matrix-sample-coverage">{{ sampleView.coverageMessage }}</div>
          <div data-test="matrix-sample-cost">{{ sampleView.costMessage }}</div>
        </div>
      </div>
      <div v-if="comboRows.length" class="matrix-combo-list" data-test="matrix-combo-list">
        <div
          v-for="row in comboRows"
          :key="row.key"
          class="matrix-combo-row"
          :class="{ checked: row.selected }"
          :data-test="`matrix-combo-${row.key}`"
          @click="row.selected = !row.selected"
        >
          <el-checkbox :model-value="row.selected" data-test="matrix-combo-check" />
          <span class="matrix-combo-cell mono">{{ row.engineCode }}</span>
          <span class="matrix-combo-cell">{{ row.platformCode }}</span>
          <span class="matrix-combo-cell">{{ row.deviceKindCode }}</span>
          <span class="matrix-combo-env" @click.stop>
            <el-select
              v-model="row.envProfileId"
              clearable
              filterable
              size="small"
              style="width: 200px"
              :placeholder="t('appauto.matrix.envProfileOptional')"
              :data-test="`matrix-combo-env-profile-${row.key}`"
            >
              <el-option v-for="p in envProfiles" :key="p.id" :label="`${p.name}（${p.profileKind}）`" :value="p.id" />
            </el-select>
          </span>
        </div>
      </div>
      <div v-else class="matrix-no-combo" data-test="matrix-no-combo">
        {{ t('appauto.matrix.noCombosHint') }}
      </div>

      
      <div class="matrix-actions">
        <el-button
          data-test="matrix-precheck-btn"
          :disabled="selectedCount === 0 || !envId"
          :loading="prechecking"
          @click="runPrecheck"
        >{{ t('appauto.matrix.precheckBtn') }}</el-button>
        <span v-if="selectedCount" class="matrix-selected-info" data-test="matrix-selected-count">
          {{ t('appauto.matrix.selectedCount', { n: selectedCount }) }}
        </span>
      </div>
      <div v-if="precheckError" class="matrix-error" data-test="matrix-precheck-error">{{ precheckError }}</div>
      <div v-if="precheck && !precheckError" class="matrix-precheck-zone">
        <el-alert type="info" :closable="false" show-icon data-test="matrix-precheck-message">
          {{ precheck.message }}
        </el-alert>
        <div class="matrix-precheck-grid" data-test="matrix-precheck-grid">
          <span>{{ t('appauto.matrix.fanoutTasks') }}：<b data-test="matrix-precheck-tasks">{{ precheck.fanoutTasks }}</b></span>
          <span>{{ t('appauto.matrix.minutesPerTask') }}：<b>{{ precheck.minutesPerTask }}</b></span>
          <span>{{ t('appauto.matrix.aheadPending') }}：<b>{{ precheck.aheadPendingTasks }}</b></span>
          <span>{{ t('appauto.matrix.idleCapacity') }}：<b>{{ precheck.idleCapacity }}</b></span>
          <span>{{ t('appauto.matrix.estQueue') }}：<b>{{ precheck.estQueueMinutes }}</b></span>
          <span>{{ t('appauto.matrix.startAt') }}：<b data-test="matrix-precheck-start-at">{{ precheck.estimatedStartAt }}</b></span>
          <span>{{ t('appauto.matrix.maxAllowed') }}：<b>{{ precheck.fanoutMaxAllowed }}</b></span>
        </div>
        <el-alert
          v-if="precheck.budgetHighWater"
          type="warning"
          :closable="false"
          show-icon
          class="matrix-budget-alert"
          data-test="matrix-budget-high-alert"
        >{{ t('appauto.matrix.budgetHighHint') }}</el-alert>
      </div>

      
      <div v-if="dispatchError" class="matrix-error" data-test="matrix-dispatch-error">{{ dispatchError }}</div>
      <div v-if="dispatchResult" class="matrix-dispatch-zone" data-test="matrix-dispatch-result">
        <el-alert type="success" :closable="false" show-icon>
          {{ dispatchResult.message }}
          <el-button link type="primary" data-test="matrix-goto-batch" @click="gotoBatch">
            {{ t('appauto.matrix.gotoBatch', { no: dispatchResult.batchNo }) }}
          </el-button>
        </el-alert>
      </div>
    </div>

    <template #footer>
      <el-button data-test="matrix-cancel-btn" @click="emit('update:modelValue', false)">
        {{ t('appauto.matrix.closeBtn') }}
      </el-button>
      <el-tooltip
        v-if="!canRun"
        :content="t('appauto.matrix.permHint')"
        placement="top"
      >
        <span>
          <el-button type="primary" disabled data-test="matrix-dispatch-btn">{{ t('appauto.matrix.dispatchBtn') }}</el-button>
        </span>
      </el-tooltip>
      <el-button
        v-else
        type="primary"
        data-test="matrix-dispatch-btn"
        :disabled="!precheck || dispatching"
        :loading="dispatching"
        @click="dispatch"
      >{{ t('appauto.matrix.dispatchBtn') }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">

import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import {
  fanoutMatrix,
  listAppautoEngines,
  listAppautoEnvProfiles,
  listAppautoPlatforms,
  matrixSample,
  precheckMatrix,
  type AppautoEngine,
  type AppautoEnvProfile,
  type MatrixDispatchResult,
  type MatrixFanoutPayload,
  type MatrixPrecheckView,
  type MatrixSampleView,
} from '@/api/appauto'
import { envTypeLabel, getProjectEnvs, type ProjectEnv } from '@/api/project'
import { bizErrorMsg } from '@/utils/bizError'
import { useAuthStore } from '@/stores/auth'

const props = defineProps<{
  modelValue: boolean
  projectId: number
  caseId: number
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
}>()

const { t } = useI18n()
const router = useRouter()
const auth = useAuthStore()

const canRun = computed(() => auth.hasPerm('perm:appauto:matrix:run'))

interface ComboRow {
  key: string
  engineCode: string
  platformCode: string
  deviceKindCode: string
  envProfileId: number | null
  selected: boolean
}

const envs = ref<ProjectEnv[]>([])
const envLoading = ref(false)
const envId = ref<number | null>(null)
const batchName = ref('')
const comboRows = reactive<ComboRow[]>([])
const envProfiles = ref<AppautoEnvProfile[]>([])
const prechecking = ref(false)
const precheck = ref<MatrixPrecheckView | null>(null)
const precheckError = ref('')
const dispatching = ref(false)
const dispatchResult = ref<MatrixDispatchResult | null>(null)
const dispatchError = ref('')
const sampling = ref(false)
const sampleView = ref<MatrixSampleView | null>(null)
const sampleError = ref('')
const sampleAppliedCount = ref(0)

const selectedCount = computed(() => comboRows.filter((r) => r.selected).length)

watch(
  () => props.modelValue,
  async (open) => {
    if (!open) return
    precheck.value = null
    precheckError.value = ''
    dispatchResult.value = null
    dispatchError.value = ''
    sampleView.value = null
    sampleError.value = ''
    sampleAppliedCount.value = 0
    comboRows.length = 0
    await loadWizardData()
  },
)

async function loadWizardData(): Promise<void> {
  envLoading.value = true
  try {
    const [engineList, platformList, projectEnvs, profiles] = await Promise.all([
      listAppautoEngines(false),
      listAppautoPlatforms(false),
      getProjectEnvs(props.projectId),
      listAppautoEnvProfiles(props.projectId),
    ])
    envs.value = projectEnvs
    envProfiles.value = profiles
    buildComboRows(engineList, platformList)
    if (!envId.value && envs.value.length) envId.value = envs.value[0].id
  } catch (e) {
    precheckError.value = bizErrorMsg(e, t('appauto.matrix.loadFail'))
  } finally {
    envLoading.value = false
  }
}


function buildComboRows(engineList: AppautoEngine[], platformList: { code: string; deviceKindCodes: string[] }[]): void {
  const platformById = new Map(platformList.map((p) => [p.code, p.deviceKindCodes || []]))
  for (const engine of engineList) {
    if (engine.status !== 1) continue
    for (const platformCode of engine.platformCodes || []) {
      const kinds = platformById.get(platformCode)
      if (!kinds) continue
      for (const kind of kinds) {
        if (!(engine.deviceKindCodes || []).includes(kind)) continue
        comboRows.push({
          key: `${engine.code}-${platformCode}-${kind}`,
          engineCode: engine.code,
          platformCode,
          deviceKindCode: kind,
          envProfileId: null,
          selected: false,
        })
      }
    }
  }
}

function buildPayload(): MatrixFanoutPayload {
  return {
    projectId: props.projectId,
    caseId: props.caseId,
    name: batchName.value.trim() || null,
    envId: envId.value as number,
    combos: comboRows
      .filter((r) => r.selected)
      .map((r) => ({
        engineCode: r.engineCode,
        platformCode: r.platformCode,
        deviceKindCode: r.deviceKindCode,
        envProfileId: r.envProfileId,
      })),
  }
}


async function runSample(): Promise<void> {
  sampling.value = true
  sampleView.value = null
  sampleError.value = ''
  try {
    const view = await matrixSample({ projectId: props.projectId, caseId: props.caseId })
    sampleView.value = view
    const targets = new Set(
      (view.recommendations || []).map((c) => `${c.engineCode}-${c.platformCode}-${c.deviceKindCode}`),
    )
    let applied = 0
    for (const row of comboRows) {
      if (targets.has(row.key)) {
        row.selected = true
        applied += 1
      }
    }
    sampleAppliedCount.value = applied
  } catch (e) {
    sampleError.value = bizErrorMsg(e, t('appauto.matrix.sampleFail'))
  } finally {
    sampling.value = false
  }
}

async function runPrecheck(): Promise<void> {
  prechecking.value = true
  precheck.value = null
  precheckError.value = ''
  try {
    precheck.value = await precheckMatrix(buildPayload())
  } catch (e) {
    precheckError.value = bizErrorMsg(e, t('appauto.matrix.precheckFail'))
  } finally {
    prechecking.value = false
  }
}

watch([selectedCount, envId], () => {
  precheck.value = null
  precheckError.value = ''
})

async function dispatch(): Promise<void> {
  dispatching.value = true
  dispatchError.value = ''
  try {
    dispatchResult.value = await fanoutMatrix(buildPayload())
  } catch (e) {
    dispatchResult.value = null
    dispatchError.value = bizErrorMsg(e, t('appauto.matrix.dispatchFail'))
  } finally {
    dispatching.value = false
  }
}

function gotoBatch(): void {
  if (!dispatchResult.value) return
  router.push(`/batches/${dispatchResult.value.batchId}`)
  emit('update:modelValue', false)
}
</script>

<style scoped>
.matrix-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.matrix-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.matrix-field-key {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.matrix-col-title {
  font-weight: 600;
  font-size: 13px;
}
.matrix-combo-list {
  max-height: 260px;
  overflow: auto;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
}
.matrix-combo-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 6px 10px;
  cursor: pointer;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.matrix-combo-row:last-child {
  border-bottom: none;
}
.matrix-combo-row.checked {
  background: var(--el-color-primary-light-9);
}
.matrix-combo-cell {
  min-width: 120px;
  font-size: 13px;
}
.matrix-combo-cell.mono {
  font-family: var(--el-font-family-mono, monospace);
}
.matrix-no-combo {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.matrix-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.matrix-selected-info {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.matrix-error {
  color: var(--el-color-danger);
  font-size: 13px;
  word-break: break-all;
}
.matrix-precheck-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 18px;
  font-size: 13px;
  padding: 6px 2px;
}
.matrix-budget-alert {
  margin-top: 4px;
}
.matrix-sample-zone {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.matrix-sample-lines {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  display: flex;
  flex-direction: column;
  gap: 2px;
}
</style>
