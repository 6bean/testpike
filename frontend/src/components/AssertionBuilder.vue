<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { Delete } from '@element-plus/icons-vue'
import { ASSERTION_TYPES, ASSERTION_TYPE_META, EXTRACT_SOURCE_OPTIONS, WS_ASSERTION_TYPES, validateAssertionRow } from '@/utils/assertionForm'
import type { StepAliasItem } from '@/api/apiTest'



const props = withDefaults(defineProps<{ modelValue: string; projectId?: number | null; aliases?: StepAliasItem[]; protocol?: string }>(), {
  protocol: 'http',
})
const emit = defineEmits<{
  (e: 'update:modelValue', v: string): void
  (e: 'registerAlias', payload: { aliasName: string; target: string }): void
}>()
const { t } = useI18n()

/** Transport face (single source = the page's formProtocol via prop; the
 *  crossed grammar mirrors core ApiAssertionFormContract.rowViolation). */
const isWs = computed(() => props.protocol === 'ws')
const isHttp = computed(() => !isWs.value)
/** Type dropdown candidates filtered by transport — never a second copy of
 *  either vocabulary (both lists come from utils/assertionForm). */
const typeOptions = computed<string[]>(() => (isWs.value ? [...WS_ASSERTION_TYPES] : [...ASSERTION_TYPES]))

function isWsType(type: string): boolean {
  return (WS_ASSERTION_TYPES as readonly string[]).includes(type)
}


interface FormRow {
  type: string
  op: string
  path: string
  name: string
  saveAs: string
  expectText: string
  /** ws window budget in ms (text form; blank = executor default 3000, ws.* rows only). */
  withinMs: string
  /** Extraction lane for extract rows (body default = JsonPath; header/cookie read by name).
   *  Vocabulary single source = utils/assertionForm EXTRACT_SOURCE_OPTIONS. */
  source: string
  
  aliasName: string
}

const rows = ref<FormRow[]>([])
const dirty = ref<boolean[]>([])
const parseFailed = ref(false)
let lastEmitted = ''

function emptyRow(): FormRow {
  return { type: '', op: '', path: '', name: '', saveAs: '', expectText: '', withinMs: '', source: 'body', aliasName: '' }
}

/** Saved lane value -> form value: blank / legacy rows sit on the JsonPath lane. */
function laneOf(raw: unknown): string {
  const value = String(raw ?? '').trim().toLowerCase()
  return value === '' ? 'body' : value
}


function registeredAliasOf(saveAs: string): StepAliasItem | null {
  const key = saveAs.trim()
  if (key === '') return null
  return (props.aliases ?? []).find((a) => a.target === key) ?? null
}


function displayNameOf(row: FormRow): string {
  return row.aliasName !== '' ? row.aliasName : (registeredAliasOf(row.saveAs)?.aliasName ?? '')
}


function canRegister(row: FormRow): boolean {
  return row.type === 'extract'
    && row.saveAs.trim() !== ''
    && row.aliasName.trim() !== ''
    && registeredAliasOf(row.saveAs)?.aliasName !== row.aliasName.trim()
}

function toExpectText(v: unknown): string {
  if (v === null || v === undefined) return ''
  return typeof v === 'string' ? v : JSON.stringify(v)
}


function loadFrom(raw: string): void {
  parseFailed.value = false
  try {
    const parsed: unknown = raw && raw.trim() !== '' ? JSON.parse(raw) : []
    if (!Array.isArray(parsed)) {
      parseFailed.value = true
      rows.value = []
      dirty.value = []
      return
    }
    rows.value = parsed
      .filter((r): r is Record<string, unknown> => r !== null && typeof r === 'object')
      .map((r) => ({
        type: String(r.type ?? ''),
        op: String(r.op ?? ''),
        path: String(r.path ?? ''),
        name: String(r.name ?? ''),
        saveAs: String(r.saveAs ?? ''),
        expectText: toExpectText(r.expect),
        withinMs: r.withinMs === null || r.withinMs === undefined ? '' : String(r.withinMs),
        source: laneOf(r.source),
        aliasName: '',
      }))
    dirty.value = rows.value.map(() => true)
  } catch {
    parseFailed.value = true
    rows.value = []
    dirty.value = []
  }
}

watch(() => props.modelValue, (v) => {
  if (v === lastEmitted) return
  loadFrom(v ?? '')
}, { immediate: true })

/** Form row -> WS2 assertion object (the single normalization point: status = int,
 *  range/duration = JSON.parse, everything else stays raw text). The ws face adds
 *  withinMs as an integer and ws.order as a sequence array. */
function buildAssertion(row: FormRow): Record<string, unknown> {
  const out: Record<string, unknown> = { type: row.type }
  const wsFace = isWsType(row.type)
  const meta = ASSERTION_TYPE_META[row.type]
  const ops = meta ? meta.ops : []
  if (ops.length > 0 && row.op) out.op = row.op
  if ((row.type === 'body' || (row.type === 'extract' && row.source === 'body')) && row.path.trim() !== '') out.path = row.path.trim()
  if ((row.type === 'header' || (row.type === 'extract' && row.source !== 'body')) && row.name.trim() !== '') out.name = row.name.trim()
  if (row.type === 'extract' && row.source !== 'body') out.source = row.source
  if (row.type === 'extract' && row.saveAs.trim() !== '') out.saveAs = row.saveAs.trim()
  if (wsFace && row.withinMs.trim() !== '') out.withinMs = Number.parseInt(row.withinMs.trim(), 10)
  const e = row.expectText.trim()
  if (e !== '' && row.type !== 'extract') {
    if (wsFace && row.type === 'ws.order') {
      // ws.order expects a non-empty sequence array; paste-in JSON wins, plain
      // comma lists are accepted as the same shape (executor compares strings).
      try {
        const parsed: unknown = JSON.parse(e)
        out.expect = Array.isArray(parsed) ? parsed : e.split(',').map((s) => s.trim()).filter((s) => s !== '')
      } catch {
        out.expect = e.split(',').map((s) => s.trim()).filter((s) => s !== '')
      }
    } else if (row.type === 'duration' || row.op === 'range') {
      try {
        out.expect = JSON.parse(e)
      } catch {
        out.expect = e
      }
    } else if (row.type === 'status') {
      out.expect = Number.parseInt(e, 10)
    } else {
      out.expect = e
    }
  }
  return out
}

function flush(): void {
  const built = rows.value.map(buildAssertion).filter((r) => Object.keys(r).length > 1)
  lastEmitted = JSON.stringify(built)
  emit('update:modelValue', lastEmitted)
}

/** Inline red text = validateAssertionRow return shown verbatim; untouched empty
 *  rows stay quiet (no error spam on the blank form). The protocol argument is
 *  mandatory — crossed grammar single source (http face = six types, ws face =
 *  three ws.* types, both ends mirrored). */
function rowError(i: number): string | null {
  if (!dirty.value[i]) return null
  return validateAssertionRow(buildAssertion(rows.value[i]), props.protocol)
}

function opsOf(row: FormRow): string[] {
  return ASSERTION_TYPE_META[row.type]?.ops ?? []
}

function markDirty(i: number): void {
  dirty.value[i] = true
}


function onTypeChange(i: number): void {
  const ops = opsOf(rows.value[i])
  rows.value[i].op = ops.length === 1 ? ops[0] : ''
  markDirty(i)
}

/** Lane switch: the row revalidates immediately so a header/cookie lane missing a
 *  name names itself on the edit face instead of at the save gate. */
function onSourceChange(i: number): void {
  markDirty(i)
}

/** Transport flip: the whole row set revalidates against the crossed grammar so
 *  a wrong-face row names itself immediately (no silent pass to the core gate).
 *  Row data stays untouched — flipping back never loses work. */
watch(() => props.protocol, () => {
  for (let i = 0; i < rows.value.length; i += 1) dirty.value[i] = true
})

function addRow(): void {
  rows.value.push(emptyRow())
  dirty.value.push(false)
}

function removeRow(i: number): void {
  rows.value.splice(i, 1)
  dirty.value.splice(i, 1)
  flush()
}


function validateAll(): string | null {
  for (let i = 0; i < rows.value.length; i += 1) dirty.value[i] = true
  const built = rows.value.map(buildAssertion).filter((r) => Object.keys(r).length > 1)
  for (const r of built) {
    const err = validateAssertionRow(r, props.protocol)
    if (err) return err
  }
  for (const row of rows.value) {
    if (Object.keys(buildAssertion(row)).length <= 1) {
      return validateAssertionRow(buildAssertion(row), props.protocol)
    }
  }
  return null
}

defineExpose({ validateAll })

watch(rows, () => flush(), { deep: true })
</script>

<template>
  <div class="assertion-builder">
    <div v-if="parseFailed" class="ab-parse-warn" data-test="assertion-parse-failed">
      {{ t('apitest.parseFailed') }}
    </div>
    <div v-for="(row, i) in rows" :key="i" class="ab-row" :data-test="`assertion-row-${i}`">
      <div class="ab-line">
        <el-select
          v-model="row.type"
          :data-test="`assertion-type-${i}`"
          class="ab-type"
          data-se1-exempt="assertion types are a fixed 6-value (http) / 3-value (ws) enum in utils/assertionForm; never grows with remote data"
          @update:model-value="onTypeChange(i)"
        >
          <el-option
            v-for="ty in typeOptions"
            :key="ty"
            :value="ty"
            :label="ASSERTION_TYPE_META[ty].label"
          />
        </el-select>
        <el-select
          v-if="opsOf(row).length > 0"
          v-model="row.op"
          :data-test="`assertion-op-${i}`"
          class="ab-op"
          data-se1-exempt="operators per assertion type are a fixed enum in utils/assertionForm, at most 4 values; never grows with remote data"
          @update:model-value="markDirty(i)"
        >
          <el-option v-for="op in opsOf(row)" :key="op" :value="op" :label="op" />
        </el-select>
        <el-input
          v-if="isHttp && (row.type === 'body' || (row.type === 'extract' && row.source === 'body'))"
          v-model="row.path"
          :data-test="`assertion-path-${i}`"
          class="ab-field"
          placeholder="$.data.id"
          @blur="markDirty(i)"
        />
        <!-- Extraction lane picker (save-gate face = edit face): body keeps JsonPath,
             header/cookie read the response by name. Fixed 3-value enum, so search
             adds nothing and the exemption is stated instead. -->
        <el-select
          v-if="isHttp && row.type === 'extract'"
          v-model="row.source"
          :data-test="`assertion-source-${i}`"
          class="ab-source"
          data-se1-exempt="extraction lanes are a fixed 3-value enum in utils/assertionForm; never grows with remote data"
          @update:model-value="onSourceChange(i)"
        >
          <el-option
            v-for="lane in EXTRACT_SOURCE_OPTIONS"
            :key="lane.value"
            :value="lane.value"
            :label="lane.label"
          />
        </el-select>
        <el-input
          v-if="isHttp && row.type === 'extract' && row.source !== 'body'"
          v-model="row.name"
          :data-test="`assertion-extract-name-${i}`"
          class="ab-field"
          placeholder="X-Auth-Token"
          @blur="markDirty(i)"
        />
        <el-input
          v-if="isHttp && row.type === 'header'"
          v-model="row.name"
          :data-test="`assertion-name-${i}`"
          class="ab-field"
          placeholder="Content-Type"
          @blur="markDirty(i)"
        />
        <el-input
          v-if="isHttp && row.type === 'extract'"
          v-model="row.saveAs"
          :data-test="`assertion-saveas-${i}`"
          class="ab-field"
          placeholder="TOKEN"
          @blur="markDirty(i)"
        />
        
        <div v-if="isHttp && row.type === 'extract' && projectId != null" class="ab-alias-line">
          <el-input
            v-model="row.aliasName"
            :data-test="`assertion-alias-name-${i}`"
            class="ab-field"
            :placeholder="t('apitest.aliasNamePh')"
          />
          <el-button
            size="small"
            :disabled="!canRegister(row)"
            :data-test="`assertion-alias-register-${i}`"
            @click="emit('registerAlias', { aliasName: row.aliasName.trim(), target: row.saveAs.trim() })"
          >
            {{ t('apitest.aliasRegister') }}
          </el-button>
          <span v-if="registeredAliasOf(row.saveAs)" class="ab-alias-ok" :data-test="`assertion-alias-state-${i}`">
            {{ t('apitest.aliasRegistered', { name: displayNameOf(row) }) }}
          </span>
        </div>
        <!-- ws face: optional window budget (absent = executor default 3000ms) -->
        <el-input
          v-if="isWs && isWsType(row.type)"
          v-model="row.withinMs"
          :data-test="`assertion-withinms-${i}`"
          class="ab-field"
          placeholder="withinMs"
          @blur="markDirty(i)"
        />
        <el-input
          v-if="row.type !== 'extract'"
          v-model="row.expectText"
          :data-test="`assertion-expect-${i}`"
          class="ab-field"
          :placeholder="ASSERTION_TYPE_META[row.type]?.expectHint ?? ''"
          @blur="markDirty(i)"
        />
        <span class="ab-example" :data-test="`assertion-example-${i}`">
          {{ row.type ? ASSERTION_TYPE_META[row.type]?.example ?? '' : '' }}
        </span>
        <el-button
          text
          :icon="Delete"
          :data-test="`assertion-remove-${i}`"
          :aria-label="t('apitest.deleteRow')"
          @click="removeRow(i)"
        />
      </div>
      <div v-if="rowError(i)" class="ab-error" :data-test="`assertion-error-${i}`">
        {{ rowError(i) }}
      </div>
    </div>
    <!-- stage 4: the add-affordance gets an explicit hover identity
 (primary tint + underline) so it no longer reads as static text. -->
    <el-button class="ab-add-btn" data-test="assertion-add-btn" @click="addRow">{{ t('apitest.addRow') }}</el-button>
  </div>
</template>

<style scoped>
.assertion-builder {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.ab-row {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 8px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
}

.ab-alias-line {
  display: flex;
  align-items: center;
  gap: 8px;
}

.ab-alias-ok {
  font-size: 12px;
  color: var(--el-color-success);
}

.ab-line {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.ab-type {
  width: 150px;
}

.ab-op {
  width: 110px;
}

.ab-source {
  width: 150px;
}

.ab-field {
  width: 170px;
}

.ab-example {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.ab-error {
  font-size: 12px;
  color: var(--el-color-danger);
}

.ab-parse-warn {
  font-size: 12px;
  color: var(--el-color-warning);
}

/* Hover identity for the add-row affordance: primary tint + underline,
   staying inside the global Element Plus baseline colors (no per-page hue).
   :deep needed: the label span is rendered by el-button, outside this
   component's scoped attribute reach. */
.ab-add-btn :deep(span) {
  color: var(--el-color-primary);
}

.ab-add-btn:hover {
  color: var(--el-color-primary);
  border-color: var(--el-color-primary);
}

.ab-add-btn:hover :deep(span) {
  text-decoration: underline;
}
</style>
