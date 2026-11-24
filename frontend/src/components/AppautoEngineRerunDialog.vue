<template>
  <el-dialog
    :model-value="modelValue"
    :title="t('appauto.rerun.title')"
    width="640px"
    destroy-on-close
    data-test="engine-rerun-dialog"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="rerun-body">
      <div class="rerun-col-title">{{ t('appauto.rerun.engineCol') }}</div>
      <div v-if="engines.length" class="rerun-engine-cards" data-test="rerun-engine-cards">
        <div
          v-for="e in engines"
          :key="e.code"
          class="rerun-engine-card"
          :class="{ active: selected === e.code }"
          :data-test="`rerun-engine-opt-${e.code}`"
          @click="pickEngine(e.code)"
        >
          <span class="rerun-engine-name">{{ e.name }}</span>
          <span class="rerun-engine-code mono">{{ e.code }}</span>
        </div>
      </div>
      <div v-else class="rerun-no-target" data-test="rerun-no-target">{{ t('appauto.rerun.noTargetHint') }}</div>

      <div class="rerun-actions">
        <el-button
          data-test="rerun-precheck-btn"
          :disabled="!selected"
          :loading="prechecking"
          @click="runPrecheck"
        >{{ t('appauto.rerun.precheckBtn') }}</el-button>
      </div>

      <div v-if="precheckError" class="rerun-error" data-test="rerun-precheck-error">{{ precheckError }}</div>

      <div v-if="precheck && !precheckError" class="rerun-verdict-zone">
        <el-alert
          :type="verdictAlertType"
          :closable="false"
          show-icon
          data-test="rerun-verdict"
        >{{ verdictText }}</el-alert>
        <div v-if="precheck.items.length" class="rerun-items" data-test="rerun-items">
          <div class="rerun-items-title">{{ t('appauto.rerun.itemsTitle') }}</div>
          <ul>
            <li v-for="(item, i) in precheck.items" :key="i" class="rerun-item">{{ item }}</li>
          </ul>
        </div>
        <div v-if="precheck.verdict === 'REWRITE'" class="rerun-rewrite-hint" data-test="rerun-rewrite-hint">
          {{ t('appauto.rerun.rewriteHint') }}
        </div>
      </div>

      <div v-if="dispatchError" class="rerun-error" data-test="rerun-dispatch-error">{{ dispatchError }}</div>
    </div>

    <template #footer>
      <el-button data-test="rerun-cancel-btn" @click="emit('update:modelValue', false)">
        {{ t('appauto.rerun.cancelBtn') }}
      </el-button>
      <el-button
        type="primary"
        data-test="rerun-dispatch-btn"
        :disabled="!dispatchAllowed"
        :loading="dispatching"
        @click="dispatch"
      >{{ t('appauto.rerun.dispatchBtn') }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">

import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { listAppautoEngines, precheckPortability, type AppautoPortability } from '@/api/appauto'
import { rerunBatchCase, type BatchCaseRerunResult } from '@/api/executor'

const props = defineProps<{
  modelValue: boolean
  projectId: number
  caseId: number
  taskId: number
  
  sourceEngineCode?: string | null
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'done', result: BatchCaseRerunResult): void
}>()

const { t } = useI18n()

const engines = ref<{ code: string; name: string }[]>([])
const selected = ref<string>('')
const prechecking = ref(false)
const precheck = ref<AppautoPortability | null>(null)
const precheckError = ref<string>('')
const dispatching = ref(false)
const dispatchError = ref<string>('')

watch(
  () => props.modelValue,
  async (open) => {
    if (!open) return
    selected.value = ''
    precheck.value = null
    precheckError.value = ''
    dispatchError.value = ''
    try {
      const all = await listAppautoEngines()
      engines.value = all
        .filter((e) => e.status === 1 && e.code !== props.sourceEngineCode)
        .map((e) => ({ code: e.code, name: e.name }))
    } catch (e) {
      engines.value = []
      precheckError.value = bizMsg(e)
    }
  },
  { immediate: true },
)

function bizMsg(e: unknown): string {
  return (e as Error)?.message || String(e)
}


function pickEngine(code: string): void {
  selected.value = code
  precheck.value = null
  precheckError.value = ''
  dispatchError.value = ''
}

async function runPrecheck(): Promise<void> {
  if (!selected.value) return
  prechecking.value = true
  precheckError.value = ''
  dispatchError.value = ''
  try {
    precheck.value = await precheckPortability(props.projectId, props.caseId, selected.value)
  } catch (e) {
    precheck.value = null
    precheckError.value = bizMsg(e)
  } finally {
    prechecking.value = false
  }
}

const verdictText = computed(() => {
  const v = precheck.value?.verdict
  if (v === 'RUNNABLE') return t('appauto.rerun.verdictRunnable')
  if (v === 'DEGRADED') return t('appauto.rerun.verdictDegraded')
  if (v === 'REWRITE') return t('appauto.rerun.verdictRewrite')
  return ''
})

const verdictAlertType = computed(() => {
  const v = precheck.value?.verdict
  if (v === 'RUNNABLE') return 'success'
  if (v === 'DEGRADED') return 'warning'
  return 'error'
})


const dispatchAllowed = computed(
  () => !!selected.value && !!precheck.value && !precheckError.value && precheck.value.verdict !== 'REWRITE',
)

async function dispatch(): Promise<void> {
  if (!dispatchAllowed.value || !selected.value) return
  dispatching.value = true
  dispatchError.value = ''
  try {
    const res = await rerunBatchCase(props.taskId, selected.value)
    emit('done', res)
    emit('update:modelValue', false)
  } catch (e) {
    dispatchError.value = bizMsg(e)
  } finally {
    dispatching.value = false
  }
}
</script>

<style scoped>
.rerun-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.rerun-col-title {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.rerun-engine-cards {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.rerun-engine-card {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 14px;
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  cursor: pointer;
  transition: border-color 0.15s, background-color 0.15s;
}
.rerun-engine-card:hover {
  border-color: var(--el-color-primary);
}
.rerun-engine-card.active {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}
.rerun-engine-name {
  font-size: 13px;
  font-weight: 600;
}
.rerun-engine-code.mono,
.rerun-engine-card .mono {
  font-family: var(--tp-font-mono, ui-monospace, monospace);
  font-size: 11px;
  color: var(--el-text-color-secondary);
}
.rerun-no-target {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.rerun-actions {
  display: flex;
  gap: 8px;
}
.rerun-verdict-zone {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.rerun-items {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 8px 12px;
}
.rerun-items-title {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 4px;
}
.rerun-items ul {
  margin: 0;
  padding-left: 18px;
}
.rerun-item {
  font-size: 13px;
  line-height: 1.7;
}
.rerun-rewrite-hint {
  font-size: 12px;
  color: var(--el-color-danger);
}
.rerun-error {
  font-size: 13px;
  color: var(--el-color-danger);
  word-break: break-all;
}
</style>
