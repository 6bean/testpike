<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import ProjectOwnerEcho from '@/components/ProjectOwnerEcho.vue'
import {
  createRunSet,
  previewRunSet,
  type RunSetItem,
  type RunSetPreviewStep,
} from '@/api/apiTest'
import { getProjectEnvs, type ProjectEnv } from '@/api/project'
import { bizErrorMsg } from '@/utils/bizError'

/**
 * Whole-collection launch dialog .
 * One screen = order preview card + launch form. The preview is pure rendering
 * of core's read model (seqNo order, pool keys written by each hop, dependency
 * arrows with the honest forward-reference flag) — no second {{VAR}} parser
 * here (single-source red line).
 * Interruption modes mirror core ApiRunSetPlanSupport.MODES: the values are
 * offered as a form, every stop/skip judgement stays in core.
 * Env is required by this form even though core keeps a no-env ledger honest
 * at pending: there is no later fill-in door yet, so launching without an env
 * would park rows nobody can wake (dead state banned).
 */

const props = defineProps<{
  modelValue: boolean
  projectId: number | null
  collectionId: number | null
  collectionName: string
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'launched', runSet: RunSetItem): void
}>()

const { t } = useI18n()

const MODE_OPTIONS = [
  { value: 'STOP_ON_ASSERT_FAIL', labelKey: 'apitest.runSet.modeStop' },
  { value: 'CONTINUE_AND_SUMMARY', labelKey: 'apitest.runSet.modeContinue' },
  { value: 'CHAIN_DEP_ONLY', labelKey: 'apitest.runSet.modeChainDep' },
] as const

const loading = ref(false)
const launching = ref(false)
const previewError = ref('')
const preview = ref<RunSetPreviewStep[]>([])
const previewFilter = ref('')
const envs = ref<ProjectEnv[]>([])
const pickedEnvId = ref<number | null>(null)
const pickedMode = ref<string>('STOP_ON_ASSERT_FAIL')
const setName = ref('')

/** seqNo lookup for dependency arrows (producer request → its order number). */
const seqOf = computed(() => {
  const map = new Map<number, number>()
  preview.value.forEach((p) => map.set(p.requestId, p.seqNo))
  return map
})

/** Worst-case scale: 50–200 hops stay searchable inside the fixed-height card. */
const filteredPreview = computed(() => {
  const q = previewFilter.value.trim().toLowerCase()
  if (!q) return preview.value
  return preview.value.filter((p) =>
    `${p.seqNo} ${p.name} ${p.method} ${p.urlPath}`.toLowerCase().includes(q))
})

/** Any forward reference (producer sits behind the consumer) → plain-language banner. */
const forwardRefRows = computed(() =>
  preview.value.filter((p) => p.deps.some((d) => !d.producerEarlier)))

async function loadAll(): Promise<void> {
  if (props.collectionId === null) return
  loading.value = true
  previewError.value = ''
  try {
    preview.value = await previewRunSet(props.collectionId)
  } catch (e) {
    previewError.value = bizErrorMsg(e, t('apitest.runSet.previewLoadFail'))
    preview.value = []
  } finally {
    loading.value = false
  }
  if (props.projectId !== null) {
    try {
      envs.value = await getProjectEnvs(props.projectId)
    } catch (e) {
      ElMessage.error(bizErrorMsg(e, t('apitest.runSet.envLoadFail')))
    }
  }
}

watch(() => props.modelValue, (open) => {
  if (open) {
    setName.value = ''
    previewFilter.value = ''
    pickedEnvId.value = null
    pickedMode.value = 'STOP_ON_ASSERT_FAIL'
    void loadAll()
  }
})

async function launch(): Promise<void> {
  if (props.collectionId === null || pickedEnvId.value === null) return
  launching.value = true
  try {
    const created = await createRunSet({
      collectionId: props.collectionId,
      envId: pickedEnvId.value,
      name: setName.value.trim(),
      interruptionMode: pickedMode.value,
    })
    ElMessage.success(t('apitest.runSet.launchOk'))
    emit('update:modelValue', false)
    emit('launched', created)
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('apitest.runSet.launchFail')))
  } finally {
    launching.value = false
  }
}
</script>

<template>
  <el-dialog
    draggable
    :model-value="props.modelValue"
    :title="t('apitest.runSet.dialogTitle', { name: props.collectionName })"
    width="720px"
    data-test="apitest-runset-dialog"
    @update:modelValue="(v: boolean) => emit('update:modelValue', v)"
  >
    <ProjectOwnerEcho />

    <el-alert
      v-if="forwardRefRows.length > 0"
      class="rs-forward-alert"
      type="warning"
      :closable="false"
      show-icon
      data-test="apitest-runset-forward-alert"
      :title="t('apitest.runSet.forwardRefHint', { count: forwardRefRows.length })"
    />

    <div class="rs-preview-head">
      <span class="rs-caption">{{ t('apitest.runSet.orderCaption') }}</span>
      <el-input
        v-model="previewFilter"
        :placeholder="t('apitest.runSet.orderSearchPh')"
        size="small"
        clearable
        style="width: 220px"
        data-test="apitest-runset-order-search"
      />
    </div>
    <div v-loading="loading" class="rs-preview-body" data-test="apitest-runset-preview-card">
      <p v-if="previewError" class="rs-error">{{ previewError }}</p>
      <p v-else-if="preview.length === 0 && !loading" class="rs-empty">{{ t('apitest.runSet.previewEmpty') }}</p>
      <div v-for="step in filteredPreview" :key="step.requestId" class="rs-row" data-test="apitest-runset-preview-row">
        <span class="rs-seq">#{{ step.seqNo }}</span>
        <span class="rs-method mono">{{ step.method }}</span>
        <span class="rs-name">{{ step.name }}</span>
        <span class="rs-path mono">{{ step.urlPath }}</span>
        <span v-if="step.deps.length > 0" class="rs-deps">
          <el-tag
            v-for="dep in step.deps"
            :key="`${step.requestId}-${dep.key}-${dep.producerRequestId}`"
            size="small"
            :type="dep.producerEarlier ? 'info' : 'warning'"
            data-test="apitest-runset-dep-tag"
          >
            {{ dep.producerEarlier
              ? t('apitest.runSet.depFrom', { key: dep.key, seq: seqOf.get(dep.producerRequestId) ?? '?' })
              : t('apitest.runSet.depForward', { key: dep.key, seq: seqOf.get(dep.producerRequestId) ?? '?' }) }}
          </el-tag>
        </span>
        <span v-if="step.extracts.length > 0" class="rs-extracts">
          <el-tag
            v-for="k in step.extracts"
            :key="`${step.requestId}-x-${k}`"
            size="small"
            effect="plain"
            data-test="apitest-runset-extract-tag"
          >{{ t('apitest.runSet.extract', { key: k }) }}</el-tag>
        </span>
      </div>
      <p v-if="!loading && previewFilter && filteredPreview.length === 0 && preview.length > 0" class="rs-empty">
        {{ t('apitest.runSet.orderNoMatch') }}
      </p>
    </div>

    <div class="rs-form">
      <div class="rs-field">
        <label for="apitest-runset-env">{{ t('apitest.runSet.envLabel') }}</label>
        <el-select
          id="apitest-runset-env"
          v-model="pickedEnvId"
          filterable
          :placeholder="t('apitest.runSet.envPh')"
          style="width: 260px"
          data-test="apitest-runset-env-select"
        >
          <el-option v-for="env in envs" :key="env.id" :value="env.id" :label="env.name" />
        </el-select>
      </div>
      <div class="rs-field">
        <!-- A bare <label> without for= or a nested labelable control is an
             orphaned label and Chromium reports it as a form-label association
             issue. The radio group is a div (not labelable), so the caption is a
             plain span wired via aria-labelledby — the accessible group pattern. -->
        <span id="apitest-runset-mode-label" class="rs-caption">{{ t('apitest.runSet.modeLabel') }}</span>
        <el-radio-group v-model="pickedMode" aria-labelledby="apitest-runset-mode-label" data-test="apitest-runset-mode-group">
          <el-radio v-for="m in MODE_OPTIONS" :key="m.value" :value="m.value">
            {{ t(m.labelKey) }}
          </el-radio>
        </el-radio-group>
      </div>
      <div class="rs-field">
        <label for="apitest-runset-name">{{ t('apitest.runSet.nameLabel') }}</label>
        <el-input
          id="apitest-runset-name"
          v-model="setName"
          :placeholder="t('apitest.runSet.namePh', { name: props.collectionName })"
          style="width: 260px"
          data-test="apitest-runset-name-input"
        />
      </div>
    </div>

    <template #footer>
      <el-button data-test="apitest-runset-cancel-btn" @click="emit('update:modelValue', false)">
        {{ t('common.cancel') }}
      </el-button>
      <el-button
        type="primary"
        data-test="apitest-runset-launch-btn"
        :disabled="pickedEnvId === null || preview.length === 0"
        :loading="launching"
        @click="launch"
      >
        {{ t('apitest.runSet.launchBtn') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.rs-forward-alert {
  margin-bottom: 10px;
}

.rs-preview-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 6px;
}

.rs-caption {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.rs-preview-body {
  max-height: 280px;
  overflow-y: auto;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 6px 10px;
  margin-bottom: 12px;
}

.rs-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
  border-bottom: 1px dashed var(--el-border-color-lighter);
  font-size: 13px;
  min-width: 0;
}

.rs-row:last-child {
  border-bottom: none;
}

.rs-seq {
  color: var(--el-text-color-secondary);
  flex-shrink: 0;
}

.rs-method {
  flex-shrink: 0;
  width: 56px;
}

.rs-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}

.rs-path,
.mono {
  font-family: var(--tp-font-mono, monospace);
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.rs-deps,
.rs-extracts {
  display: inline-flex;
  gap: 4px;
  flex-wrap: wrap;
}

.rs-error {
  color: var(--el-color-danger);
  font-size: 13px;
}

.rs-empty {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.rs-form {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.rs-field {
  display: flex;
  align-items: center;
  gap: 10px;
}

.rs-field > label,
.rs-field > .rs-caption {
  width: 96px;
  flex-shrink: 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
</style>
