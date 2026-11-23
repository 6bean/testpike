<template>
  <el-drawer
    v-model="visible"
    :title="editing ? t('mockDocs.drawerTitleEdit') : t('mockDocs.drawerTitle')"
    size="520px"
    data-test="mock-docs-create-drawer"
  >
    <!-- ownership echo (iron rule 1): read-only, single-sourced from the
         header project context -->
    <ProjectOwnerEcho />
    <!-- V240 honesty line: editing a rule of a PUBLISHED space changes what
         live callers see within one rule-load poll, so the drawer names that
         consequence plus the rule's 7-day serve count (fetched on open;
         absent = still loading / unavailable, never a fake 0). -->
    <div v-if="editing && spacePublished" class="live-impact" data-test="mock-docs-live-impact">
      {{ t('mockDocs.liveImpactHint', { hits: impact === null ? '…' : impact }) }}
    </div>
    <el-form label-position="top" class="stub-form">
      <el-form-item :label="t('mockDocs.fName')" data-test="mock-docs-f-name">
        <el-input v-model="form.name" maxlength="120" data-test="mock-docs-f-name-input" />
      </el-form-item>
      <div class="form-row">
        <el-form-item :label="t('mockDocs.fMethod')" data-test="mock-docs-f-method-wrap">
          <el-select v-model="form.method" data-test="mock-docs-f-method">
            <el-option v-for="m in HTTP_METHODS" :key="m" :label="m" :value="m" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('mockDocs.fPathMatch')" data-test="mock-docs-f-pathmatch-wrap">
          <el-select v-model="form.pathMatchType" data-test="mock-docs-f-pathmatch">
            <el-option v-for="pt in PATH_MATCH_TYPES" :key="pt" :label="t(`mockDocs.pathType.${pt}`)" :value="pt" />
          </el-select>
        </el-form-item>
      </div>
      <el-form-item :label="t('mockDocs.fPath')" data-test="mock-docs-f-path">
        <el-input v-model="form.pathPattern" placeholder="/orders" data-test="mock-docs-f-path-input" />
      </el-form-item>
      <div class="form-row">
        <el-form-item :label="t('mockDocs.fStatus')" data-test="mock-docs-f-status-wrap">
          <el-input v-model="form.status" type="number" min="100" max="599" data-test="mock-docs-f-status" />
        </el-form-item>
        <el-form-item :label="t('mockDocs.fContentType')" data-test="mock-docs-f-contenttype-wrap">
          <el-input v-model="form.contentType" placeholder="application/json" data-test="mock-docs-f-contenttype" />
        </el-form-item>
      </div>
      <el-form-item :label="t('mockDocs.fBody')" data-test="mock-docs-f-body-wrap">
        <el-input v-model="form.body" type="textarea" :rows="4" data-test="mock-docs-f-body" />
      </el-form-item>
      <el-form-item :label="t('mockDocs.faultSwitch')" data-test="mock-docs-f-fault-switch">
        <el-switch v-model="form.faultEnabled" />
      </el-form-item>
      <template v-if="form.faultEnabled">
        <!-- Vision dim1 fix: the fault parameter block is a distinct group,
             separated from the base-response fields by a rule line. -->
        <div class="fault-divider" data-test="mock-docs-f-fault-divider" />
        <el-form-item :label="t('mockDocs.faultTypeLabel')" data-test="mock-docs-f-fault-type-wrap">
          <el-select v-model="form.faultType" data-test="mock-docs-f-fault-type">
            <el-option v-for="ft in FAULT_TYPES" :key="ft" :label="t(`mockFault.${ft}`)" :value="ft" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('mockDocs.fProbability')" data-test="mock-docs-f-probability">
          <el-input v-model="form.faultProbability" type="number" min="0" max="100" />
        </el-form-item>
        <template v-if="form.faultType === 'delay'">
          <div class="form-row">
            <el-form-item :label="t('mockDocs.fMinMs')" data-test="mock-docs-f-minms">
              <el-input v-model="form.faultMinMs" type="number" min="0" />
            </el-form-item>
            <el-form-item :label="t('mockDocs.fMaxMs')" data-test="mock-docs-f-maxms">
              <el-input v-model="form.faultMaxMs" type="number" min="0" />
            </el-form-item>
          </div>
        </template>
        <template v-else-if="form.faultType === 'status'">
          <el-form-item :label="t('mockDocs.fStatusCode')" data-test="mock-docs-f-statuscode">
            <el-input v-model="form.faultStatusCode" type="number" min="500" max="599" />
          </el-form-item>
        </template>
        <template v-else-if="form.faultType === 'dribble'">
          <div class="form-row">
            <el-form-item :label="t('mockDocs.fChunkBytes')" data-test="mock-docs-f-chunkbytes">
              <el-input v-model="form.faultChunkBytes" type="number" min="1" />
            </el-form-item>
            <el-form-item :label="t('mockDocs.fChunkDelayMs')" data-test="mock-docs-f-chunkdelay">
              <el-input v-model="form.faultChunkDelayMs" type="number" min="0" />
            </el-form-item>
          </div>
        </template>
        <div
          v-else-if="form.faultType === 'malformed' && form.contentType.trim() && !form.contentType.includes('json')"
          class="curl-error"
          data-test="mock-docs-f-malformed-warn"
        >
          {{ t('mockDocs.faultMalformedHint') }}
        </div>
      </template>
      <div v-if="formError" class="curl-error" data-test="mock-docs-form-error">{{ formError }}</div>
    </el-form>
    <template #footer>
      <el-button type="primary" :loading="saving" data-test="mock-docs-f-save" @click="submit">
        {{ editing ? t('mockDocs.fSaveEdit') : t('mockDocs.fSave') }}
      </el-button>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
/**
 * Shared stub create/edit drawer (born when edit actions moved onto the
 * endpoint inventory page): ONE editor component consumed by
 * space docs page and the project endpoint inventory page — two entry doors,
 * zero fork (the docs page used to host the form inline; keeping two copies
 * would violate the no-second-copy red line and drift the honesty warnings).
 *
 * Contract:
 * - `row` null = create mode (spaceId required); non-null = edit mode and the
 *   row's full mutable payload round-trips: the form decomposes only the keys
 *   it owns (status/contentType/static body/fault), everything else the
 *   response payload carries (template mode, custom headers, future keys) is
 *   preserved verbatim through responseExtras and merged back on save — an
 *   editor must never silently delete configuration it does not show.
 *   Scenario columns / matchers / provenance ride on from the row untouched
 *   (the builder form does not own them; an edit must not silently unbind).
 * - Legality is decided once at the core save gate (identical validator for
 *   create and update); its plain-word refusal shows untouched. The local
 *   guard is emptiness only (typo stop), never a second verdict.
 * - Optimistic lock: the edit echo is the row version the page last read;
 *   a stale echo comes back as the plain-word 40901 conflict and the parent
 *   reloads the list before retrying.
 * - Honesty line: editing a rule of a PUBLISHED space takes effect on live
 *   callers within one rule-load poll, so the drawer states that plus the
 *   rule's 7-day serve count (read on open; unreadable = '…', never a fake 0).
 */
import { reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import ProjectOwnerEcho from '@/components/ProjectOwnerEcho.vue'
import { bizErrorMsg } from '@/utils/bizError'
import { FAULT_TYPES } from '@/utils/mockFaultLabels'
import { PATH_MATCH_TYPES } from '@/utils/mockRuleSpectrum'
import { HTTP_METHODS } from '@/utils/assertionForm'
import {
  createStub,
  recentStubHits,
  updateStub,
  type MockStubItem,
} from '@/api/mock'

const props = defineProps<{
  /** owning space id (create mode needs it; edit keeps the row's own) */
  spaceId: number | null
  /** owning space published? — drives the live-impact honesty line */
  spacePublished: boolean
}>()

// The row being edited is ARGUMENT-driven through open(), not a prop: the
// parent assigns and opens in one tick, and Vue props are not yet flushed
// at that moment — a prop read inside open() would see the previous row.
const editing = ref<MockStubItem | null>(null)

const emit = defineEmits<{
  /** saved carries the mode PLUS the final path so the parent can reveal the
   *  resource group the rule landed in (groups default collapsed — a write
   *  that stays invisible reads as "nothing happened"). */
  (e: 'saved', mode: 'create' | 'update', path: string): void
  (e: 'conflict'): void
}>()

const { t } = useI18n()
const visible = ref(false)
const saving = ref(false)
const formError = ref('')
const impact = ref<number | null>(null)
const responseExtras = ref<Record<string, unknown>>({})

function emptyForm() {
  return {
    name: '',
    method: 'GET' as string,
    pathMatchType: 'exact' as string,
    pathPattern: '',
    status: '200',
    contentType: '',
    body: '',
    faultEnabled: false,
    faultType: 'delay' as string,
    faultProbability: '',
    faultMinMs: '',
    faultMaxMs: '',
    faultStatusCode: '',
    faultChunkBytes: '',
    faultChunkDelayMs: '',
  }
}
const form = reactive(emptyForm())

/** Open the drawer (parent calls through the ref). Decomposes the row when
 *  editing; an unreadable payload refuses the editor outright — the core gate
 *  would reject the save anyway, so the page says so instead of opening a
 *  half-blind form. */
function open(row: MockStubItem | null = null): void {
  editing.value = row
  Object.assign(form, emptyForm())
  responseExtras.value = {}
  formError.value = ''
  if (row) {
    form.name = row.name
    form.method = row.method
    form.pathMatchType = row.pathMatchType
    form.pathPattern = row.pathPattern
    try {
      const resp = JSON.parse(row.responseJson || '{}') as Record<string, unknown>
      const KNOWN = ['status', 'bodyMode', 'body', 'contentType', 'fault']
      for (const k of Object.keys(resp)) {
        if (!KNOWN.includes(k)) responseExtras.value[k] = resp[k]
      }
      form.status = String(resp.status ?? 200)
      if (resp.bodyMode === 'template') {
        responseExtras.value.bodyMode = resp.bodyMode
      }
      form.body = typeof resp.body === 'string' ? resp.body : JSON.stringify(resp.body ?? '')
      form.contentType = typeof resp.contentType === 'string' ? resp.contentType : ''
      const fault = resp.fault as Record<string, unknown> | undefined
      if (fault && typeof fault.type === 'string') {
        form.faultEnabled = true
        form.faultType = fault.type
        form.faultProbability = fault.probability != null ? String(fault.probability) : ''
        form.faultMinMs = fault.minMs != null ? String(fault.minMs) : ''
        form.faultMaxMs = fault.maxMs != null ? String(fault.maxMs) : ''
        form.faultStatusCode = fault.statusCode != null ? String(fault.statusCode) : ''
        form.faultChunkBytes = fault.chunkBytes != null ? String(fault.chunkBytes) : ''
        form.faultChunkDelayMs = fault.chunkDelayMs != null ? String(fault.chunkDelayMs) : ''
      }
    } catch {
      ElMessage.warning(t('mockDocs.editUnreadableResponse'))
      return
    }
    void loadImpact(row.id)
  } else {
    impact.value = null
  }
  visible.value = true
}

async function loadImpact(stubId: number): Promise<void> {
  impact.value = null
  try {
    impact.value = await recentStubHits(stubId)
  } catch {
    impact.value = null // absence stays visible as '…', never a fake 0
  }
}

/** Whole-number text field → number, blank/invalid → null (absence is never
 *  coerced into a value the server would read as an explicit choice). */
function toIntOrNull(v: string): number | null {
  const trimmed = v.trim()
  if (!trimmed) return null
  const n = Number(trimmed)
  return Number.isFinite(n) ? Math.trunc(n) : null
}

/** Build the fault block from the form. Only the parameters the selected type
 *  owns are emitted — a stray minMs on a disconnect fault would be noise the
 *  core gate has to reject; the drawer sends the narrowest honest shape.
 *  Legality itself stays the core save gate's single point. */
function buildFault(): Record<string, unknown> | null {
  if (!form.faultEnabled) return null
  const fault: Record<string, unknown> = { type: form.faultType }
  const probability = toIntOrNull(form.faultProbability)
  if (probability !== null) fault.probability = probability
  if (form.faultType === 'delay') {
    const minMs = toIntOrNull(form.faultMinMs)
    const maxMs = toIntOrNull(form.faultMaxMs)
    if (minMs !== null) fault.minMs = minMs
    if (maxMs !== null) fault.maxMs = maxMs
  } else if (form.faultType === 'status') {
    const statusCode = toIntOrNull(form.faultStatusCode)
    if (statusCode !== null) fault.statusCode = statusCode
  } else if (form.faultType === 'dribble') {
    const chunkBytes = toIntOrNull(form.faultChunkBytes)
    const chunkDelayMs = toIntOrNull(form.faultChunkDelayMs)
    if (chunkBytes !== null) fault.chunkBytes = chunkBytes
    if (chunkDelayMs !== null) fault.chunkDelayMs = chunkDelayMs
  }
  return fault
}

async function submit(): Promise<void> {
  formError.value = ''
  // Local guard is emptiness only (an obvious typo stop) — every domain rule
  // (verb set, path shape, fault parameter bounds) is judged once at the core
  // save gate, whose plain-word refusal is shown untouched below.
  if (!form.name.trim() || !form.pathPattern.trim()) {
    formError.value = t('mockDocs.formNeedsNamePath')
    return
  }
  const response: Record<string, unknown> = {
    status: toIntOrNull(form.status) ?? 200,
    bodyMode: 'static',
    body: form.body,
  }
  if (form.contentType.trim()) response.contentType = form.contentType.trim()
  const fault = buildFault()
  if (fault) response.fault = fault
  // Keys the builder form does not own ride back in untouched — honest
  // round-trip (only in edit mode; a create starts clean).
  if (editing.value) {
    for (const [k, v] of Object.entries(responseExtras.value)) {
      if (!(k in response)) response[k] = v
    }
  }
  const payload = {
    name: form.name.trim(),
    method: form.method,
    pathMatchType: form.pathMatchType,
    pathPattern: form.pathPattern.trim(),
    responseJson: JSON.stringify(response),
  }
  saving.value = true
  try {
    if (editing.value) {
      const row = editing.value
      await updateStub(row.id, {
        ...payload,
        priority: row.priority,
        matchersJson: row.matchersJson,
        scenarioName: row.scenarioName,
        requiredState: row.requiredState,
        newState: row.newState,
        enabled: row.enabled ?? true,
        expectedVersion: row.version ?? 1,
      })
      ElMessage.success(t('mockDocs.updatedStructured'))
      visible.value = false
      emit('saved', 'update', payload.pathPattern)
    } else {
      if (props.spaceId == null) {
        formError.value = t('mockDocs.noSpace')
        return
      }
      await createStub(props.spaceId, { ...payload, source: 'manual' })
      ElMessage.success(t('mockDocs.createdStructured'))
      visible.value = false
      emit('saved', 'create', payload.pathPattern)
    }
  } catch (e) {
    // version conflicts (40901) are plain-word from core — show them untouched
    // and let the parent reload so the next open carries the fresh version.
    ElMessage.error(bizErrorMsg(e, editing.value ? t('mockDocs.updateFailed') : t('mockDocs.createFailed')))
    if (editing.value) emit('conflict')
  } finally {
    saving.value = false
  }
}

watch(visible, (v) => {
  if (!v) impact.value = null
})

defineExpose({ open })
</script>

<style scoped>
.mono { font-family: var(--el-font-family-mono, monospace); font-size: 12px; }
.curl-error { color: var(--el-color-danger); font-size: 12px; }
/* Middle-consequence line at the top of the edit drawer — soft background
   emphasis (AD UI ruling: no harsh bars), warning hue because it speaks
   about traffic that is calling this rule right now. */
.live-impact { padding: 8px 12px; border-radius: 6px; margin-bottom: 12px; font-size: 12px; background: var(--el-color-warning-light-9); color: var(--el-text-color-primary); }
.form-row { display: flex; gap: 12px; }
.form-row > .el-form-item { flex: 1; min-width: 0; }
.fault-divider { height: 1px; background: var(--el-border-color-lighter); margin: 4px 0 16px; }
</style>
