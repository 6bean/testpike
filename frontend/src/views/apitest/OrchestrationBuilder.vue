<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  PRECONDITION_STATUS_OPS,
  PRECONDITION_VAR_OPS,
  validateLoopRow,
  validatePreconditionRow,
} from '@/utils/orchestrationForm'

/**
 * form mirror: per-request precondition + loop editor.
 * The vocabulary and every verdict string come from utils/orchestrationForm
 * (TS mirror of the core ApiOrchestrationContract, both-ends fixture
 * shared/orchestration_form_cases.json) — this component adds zero rules.
 * v-model = two JSON texts written straight into the core jsonb columns
 * ({ preconditionJson, loopJson } on RequestSaveRequest; absent/{} = the
 * request runs straight-line). Executor semantics surfaced in captions:
 * an unsatisfied precondition = honest skip (zero packets sent, status 4
 * in the run ledger), never a fake pass.
 */

const props = defineProps<{ precondition: string; loop: string }>()
const emit = defineEmits<{
  (e: 'update:precondition', v: string): void
  (e: 'update:loop', v: string): void
}>()
const { t } = useI18n()

/** One condition row in form state (all text; number coercion at build time). */
interface RowModel {
  field: 'status' | 'var'
  op: string
  name: string
  expectText: string
  matchType: string
  pattern: string
}

function emptyRow(): RowModel {
  return { field: 'status', op: 'eq', name: '', expectText: '', matchType: '', pattern: '' }
}

const preOn = ref(false)
const pre = reactive<RowModel>(emptyRow())
const loopOn = ref(false)
const loopMode = ref<'count' | 'until'>('count')
const times = ref('3')
const maxTimes = ref('10')
const delayMs = ref('0')
const until = reactive<RowModel>(emptyRow())
const parseFailed = ref(false)

function textOf(v: unknown): string {
  if (v === null || v === undefined) return ''
  return typeof v === 'string' ? v : String(v)
}

function loadRow(src: unknown, target: RowModel): void {
  const row = (src ?? {}) as Record<string, unknown>
  target.field = row.field === 'var' ? 'var' : 'status'
  target.op = textOf(row.op) || 'eq'
  target.name = textOf(row.name)
  const expect = row.expect
  target.expectText = Array.isArray(expect) ? expect.join(',') : textOf(expect)
  target.matchType = textOf(row.matchType)
  target.pattern = textOf(row.pattern)
}

function parseJson(text: string): Record<string, unknown> | null {
  if (!text || text.trim() === '' || text.trim() === '{}') return null
  try {
    const parsed: unknown = JSON.parse(text)
    return parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)
      ? parsed as Record<string, unknown>
      : null
  } catch {
    parseFailed.value = true
    return null
  }
}

/** External JSON texts -> form state (empty/{} = both sections off). */
function loadFrom(): void {
  parseFailed.value = false
  const p = parseJson(props.precondition)
  preOn.value = p !== null
  if (p) loadRow(p, pre)
  const l = parseJson(props.loop)
  loopOn.value = l !== null
  if (l) {
    loopMode.value = l.mode === 'until' ? 'until' : 'count'
    times.value = textOf(l.times) || '3'
    maxTimes.value = textOf(l.maxTimes) || '10'
    delayMs.value = textOf(l.delayMs) || '0'
    loadRow(l.until ?? {}, until)
  }
}
watch(() => [props.precondition, props.loop], loadFrom, { immediate: true })

/** Form state -> contract row object (shape normalized here, validated downstream). */
function buildRow(row: RowModel): Record<string, unknown> {
  const out: Record<string, unknown> = { field: row.field, op: row.op }
  if (row.field === 'var') {
    out.name = row.name.trim()
    if (row.op === 'matches') {
      if (row.matchType) out.matchType = row.matchType
      if (row.pattern.trim()) out.pattern = row.pattern.trim()
    }
  }
  if (row.op !== 'exists' && row.op !== 'absent') {
    if (row.op === 'in') {
      out.expect = row.expectText.split(',').map((s) => Number.parseInt(s.trim(), 10)).filter((n) => !Number.isNaN(n))
    } else if (row.field === 'status') {
      out.expect = Number.parseInt(row.expectText.trim(), 10)
    } else {
      out.expect = row.expectText
    }
  }
  return out
}

function buildPrecondition(): Record<string, unknown> {
  return preOn.value ? buildRow(pre) : {}
}

function buildLoop(): Record<string, unknown> {
  if (!loopOn.value) return {}
  const out: Record<string, unknown> = { mode: loopMode.value }
  const delay = Number.parseInt(delayMs.value.trim() || '0', 10)
  if (!Number.isNaN(delay) && delay !== 0) out.delayMs = delay
  if (loopMode.value === 'count') {
    out.times = Number.parseInt(times.value.trim(), 10)
  } else {
    out.maxTimes = Number.parseInt(maxTimes.value.trim(), 10)
    out.until = buildRow(until)
  }
  return out
}

/** Plain integer for count loops only when parseable (bad text stays bad for the validator). */
function emitBoth(): void {
  emit('update:precondition', JSON.stringify(buildPrecondition()))
  emit('update:loop', JSON.stringify(buildLoop()))
}
watch([preOn, loopOn, loopMode, times, maxTimes, delayMs], emitBoth)
watch([pre, until], emitBoth, { deep: true })

function opsFor(row: RowModel): string[] {
  return row.field === 'status' ? [...PRECONDITION_STATUS_OPS] : [...PRECONDITION_VAR_OPS]
}

function onFieldChange(row: RowModel): void {
  // Keep a still-legal op after switching fields; otherwise land on eq.
  if (!opsFor(row).includes(row.op)) row.op = 'eq'
}

/** Save pre-gate (page save calls this): first plain-language verdict, null = ok. */
function validateAll(): string | null {
  const preError = validatePreconditionRow(buildPrecondition())
  if (preError) return preError
  return validateLoopRow(buildLoop())
}

defineExpose({ validateAll })
</script>

<template>
  <div class="orchestration-builder" data-test="orchestration-builder">
    <div v-if="parseFailed" class="ob-parse-warn" data-test="orchestration-parse-failed">
      {{ t('apitest.orchestrationParseFailed') }}
    </div>

    <div class="ob-section" data-test="orchestration-pre">
      <el-checkbox v-model="preOn" data-test="orchestration-pre-on">{{ t('apitest.preconditionEnable') }}</el-checkbox>
      <div v-if="preOn" class="ob-line">
        <label for="ob-pre-field">{{ t('apitest.preconditionField') }}</label>
        <el-select id="ob-pre-field" v-model="pre.field" data-test="orchestration-pre-field" class="ob-field" @update:model-value="onFieldChange(pre)">
          <el-option value="status" :label="t('apitest.preconditionFieldStatus')" />
          <el-option value="var" :label="t('apitest.preconditionFieldVar')" />
        </el-select>
        <label for="ob-pre-op">{{ t('apitest.preconditionOp') }}</label>
        <el-select id="ob-pre-op" v-model="pre.op" data-test="orchestration-pre-op" filterable class="ob-op">
          <el-option v-for="op in opsFor(pre)" :key="op" :value="op" :label="op" />
        </el-select>
        <template v-if="pre.field === 'var'">
          <label for="ob-pre-name">{{ t('apitest.preconditionName') }}</label>
          <el-input id="ob-pre-name" v-model="pre.name" data-test="orchestration-pre-name" class="ob-field" placeholder="token / order.state" />
        </template>
        <template v-if="pre.op !== 'exists' && pre.op !== 'absent'">
          <label for="ob-pre-expect">{{ t('apitest.preconditionExpect') }}</label>
          <el-input
            id="ob-pre-expect"
            v-model="pre.expectText"
            data-test="orchestration-pre-expect"
            class="ob-field"
            :placeholder="pre.op === 'in' ? '200,201' : (pre.field === 'status' ? '200' : 'PAID')"
          />
        </template>
        <template v-if="pre.field === 'var' && pre.op === 'matches'">
          <label for="ob-pre-mt">{{ t('apitest.preconditionMatchType') }}</label>
          <el-select id="ob-pre-mt" v-model="pre.matchType" data-test="orchestration-pre-matchtype" class="ob-op">
            <el-option value="" :label="t('apitest.preconditionMatchTypeDefault')" />
            <el-option value="contains" label="contains" />
            <el-option value="exact" label="exact" />
            <el-option value="regex" label="regex" />
          </el-select>
          <label v-if="pre.matchType === 'regex'" for="ob-pre-pattern">{{ t('apitest.preconditionPattern') }}</label>
          <el-input v-if="pre.matchType === 'regex'" id="ob-pre-pattern" v-model="pre.pattern" data-test="orchestration-pre-pattern" class="ob-field" placeholder="^ok$" />
        </template>
      </div>
      <div v-if="preOn" class="ob-hint">{{ t('apitest.preconditionHint') }}</div>
    </div>

    <div class="ob-section" data-test="orchestration-loop">
      <el-checkbox v-model="loopOn" data-test="orchestration-loop-on">{{ t('apitest.loopEnable') }}</el-checkbox>
      <div v-if="loopOn" class="ob-line">
        <label for="ob-loop-mode">{{ t('apitest.loopMode') }}</label>
        <el-select id="ob-loop-mode" v-model="loopMode" data-test="orchestration-loop-mode" class="ob-field">
          <el-option value="count" :label="t('apitest.loopModeCount')" />
          <el-option value="until" :label="t('apitest.loopModeUntil')" />
        </el-select>
        <template v-if="loopMode === 'count'">
          <label for="ob-loop-times">{{ t('apitest.loopTimes') }}</label>
          <el-input id="ob-loop-times" v-model="times" data-test="orchestration-loop-times" class="ob-op" />
        </template>
        <template v-else>
          <label for="ob-loop-maxtimes">{{ t('apitest.loopMaxTimes') }}</label>
          <el-input id="ob-loop-maxtimes" v-model="maxTimes" data-test="orchestration-loop-maxtimes" class="ob-op" />
          <span class="ob-hint-inline">{{ t('apitest.loopMaxTimesHint') }}</span>
        </template>
        <label for="ob-loop-delay">{{ t('apitest.loopDelay') }}</label>
        <el-input id="ob-loop-delay" v-model="delayMs" data-test="orchestration-loop-delay" class="ob-op" />
      </div>
      <div v-if="loopOn && loopMode === 'until'" class="ob-line" data-test="orchestration-until-row">
        <span class="ob-caption">{{ t('apitest.loopUntil') }}</span>
        <label for="ob-until-field">{{ t('apitest.preconditionField') }}</label>
        <el-select id="ob-until-field" v-model="until.field" data-test="orchestration-until-field" class="ob-field" @update:model-value="onFieldChange(until)">
          <el-option value="status" :label="t('apitest.preconditionFieldStatus')" />
          <el-option value="var" :label="t('apitest.preconditionFieldVar')" />
        </el-select>
        <label for="ob-until-op">{{ t('apitest.preconditionOp') }}</label>
        <el-select id="ob-until-op" v-model="until.op" data-test="orchestration-until-op" filterable class="ob-op">
          <el-option v-for="op in opsFor(until)" :key="op" :value="op" :label="op" />
        </el-select>
        <template v-if="until.field === 'var'">
          <label for="ob-until-name">{{ t('apitest.preconditionName') }}</label>
          <el-input id="ob-until-name" v-model="until.name" data-test="orchestration-until-name" class="ob-field" placeholder="ready" />
        </template>
        <template v-if="until.op !== 'exists' && until.op !== 'absent'">
          <label for="ob-until-expect">{{ t('apitest.preconditionExpect') }}</label>
          <el-input id="ob-until-expect" v-model="until.expectText" data-test="orchestration-until-expect" class="ob-field" placeholder="200" />
        </template>
      </div>
      <div v-if="loopOn" class="ob-hint">{{ t('apitest.loopHint') }}</div>
    </div>
  </div>
</template>

<style scoped>
.orchestration-builder {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.ob-section {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
}

.ob-line {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.ob-line label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.ob-field {
  width: 170px;
}

.ob-op {
  width: 110px;
}

.ob-caption {
  font-size: 12px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.ob-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.ob-hint-inline {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.ob-parse-warn {
  font-size: 12px;
  color: var(--el-color-warning);
}
</style>
