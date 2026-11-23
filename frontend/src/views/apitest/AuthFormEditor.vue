<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  AUTH_TYPES, AUTH_PLACEMENTS, validateAuth,
  sessionStateAuthTemplate, isConsumableSessionState, type ConsumableSessionState,
} from '@/utils/authForm'
import { listAuthPresets, type AuthPresetItem } from '@/api/apiTest'
import { listSessionStates } from '@/api/sessionState'

/**
 * slice-C2b form mirror: per-request auth editor (bearer / basic / header /
 * api-key / signature). The vocabulary and every verdict string come from
 * utils/authForm (TS mirror of the core AuthFormContract, both-ends fixture
 * shared/auth_form_cases.json) — this component adds zero rules.
 * v-model = one JSON text written straight into the core authJson jsonb column
 * ({} = no auth, honest empty state; never a fake default row).
 * Secret slots only accept credential references (@cred/ path or {{CRED:key}}
 * placeholder) — plaintext secrets are refused here and by the save gate, so
 * they can never land in stored rows or run snapshots.
 * Signature presets come from GET /apitest/requests/auth-presets (K8 config
 * rows, hot-updated); picking one prefills the assembly rows with template
 * text only — the user still has to point {{CRED:...}} keys at registered
 * credentials.
 */

const props = defineProps<{ auth: string; projectId?: number | null }>()
const emit = defineEmits<{ (e: 'update:auth', v: string): void }>()
const { t } = useI18n()

/** One signature-assembly row in form state (all text; shape built at emit time). */
interface AssemblyRow {
  into: string
  name: string
  value: string
}

function emptyRow(): AssemblyRow {
  return { into: 'header', name: '', value: '' }
}

const authOn = ref(false)
const authType = ref('bearer')
const token = ref('')
const username = ref('')
const password = ref('')
const headerName = ref('')
const headerValue = ref('')
const keyName = ref('')
const keyValue = ref('')
const placement = ref('header')
const assemblies = reactive<AssemblyRow[]>([])
const parseFailed = ref(false)
const presets = ref<AuthPresetItem[]>([])
const sessionStates = ref<ConsumableSessionState[]>([])

function textOf(v: unknown): string {
  if (v === null || v === undefined) return ''
  return typeof v === 'string' ? v : String(v)
}

function parseJson(text: string): Record<string, unknown> | null {
  if (!text || text.trim() === '' || text.trim() === '{}') return null
  try {
    const parsed: unknown = JSON.parse(text)
    if (parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)) {
      return parsed as Record<string, unknown>
    }
    return null
  } catch {
    parseFailed.value = true
    return null
  }
}

/** External JSON text -> form state (empty/{} = section off). */
function loadFrom(): void {
  parseFailed.value = false
  const a = parseJson(props.auth)
  authOn.value = a !== null
  if (!a) return
  const rawType = textOf(a.type).trim().toLowerCase()
  authType.value = (AUTH_TYPES as readonly string[]).includes(rawType) ? rawType : ''
  token.value = textOf(a.token)
  username.value = textOf(a.username)
  password.value = textOf(a.password)
  headerName.value = textOf(a.name)
  headerValue.value = textOf(a.value)
  keyName.value = textOf(a.name)
  keyValue.value = textOf(a.value)
  placement.value = (AUTH_PLACEMENTS as readonly string[]).includes(textOf(a.placement).trim().toLowerCase())
    ? textOf(a.placement).trim().toLowerCase()
    : 'header'
  assemblies.splice(0, assemblies.length)
  if (Array.isArray(a.assemblies)) {
    for (const raw of a.assemblies) {
      const row = (raw ?? {}) as Record<string, unknown>
      assemblies.push({ into: textOf(row.into) || 'header', name: textOf(row.name), value: textOf(row.value) })
    }
  }
}
watch(() => props.auth, loadFrom, { immediate: true })

/** Form state -> contract object (shape normalized here, validated downstream). */
function buildAuth(): Record<string, unknown> {
  if (!authOn.value) return {}
  const out: Record<string, unknown> = { type: authType.value }
  switch (authType.value) {
    case 'bearer':
      out.token = token.value.trim()
      break
    case 'basic':
      out.username = username.value.trim()
      out.password = password.value.trim()
      break
    case 'header':
      out.name = headerName.value.trim()
      out.value = headerValue.value.trim()
      break
    case 'api-key':
      out.name = keyName.value.trim()
      out.value = keyValue.value.trim()
      out.placement = placement.value
      break
    case 'signature':
      out.assemblies = assemblies.map((r) => ({ into: r.into, name: r.name.trim(), value: r.value.trim() }))
      break
  }
  return out
}

function emitAuth(): void {
  emit('update:auth', JSON.stringify(buildAuth()))
}
watch([authOn, authType, token, username, password, headerName, headerValue, keyName, keyValue, placement], emitAuth)
watch(assemblies, emitAuth, { deep: true })

/** Preset dropdown: picking one replaces the assembly rows with its template text. */
function applyPreset(presetKey: string): void {
  const preset = presets.value.find((p) => p.presetKey === presetKey)
  if (!preset) return
  assemblies.splice(0, assemblies.length)
  for (const row of preset.assemblies) {
    assemblies.push({ into: row.into, name: row.name, value: row.value })
  }
}

function addAssemblyRow(): void {
  assemblies.push(emptyRow())
}

function removeAssemblyRow(index: number): void {
  assemblies.splice(index, 1)
}

// Presets are catalogue data (no project ownership — templates carry references
// only). A failed load degrades honestly: the dropdown stays empty and manual
// rows still work; we never fake a preset list.
listAuthPresets()
  .then((rows) => { presets.value = rows })
  .catch(() => { presets.value = [] })

// Login-state picker : rows come from the project ledger
// through the admin bridge, narrowed to shapes with an API-auth lane and a
// credential reference. A failed or empty load degrades honestly — the
// picker is absent and the manual form never depends on it.
watch(
  () => props.projectId,
  (pid) => {
    if (pid === null || pid === undefined) {
      sessionStates.value = []
      return
    }
    listSessionStates(pid)
      .then((rows) => { sessionStates.value = rows.filter(isConsumableSessionState) })
      .catch(() => { sessionStates.value = [] })
  },
  { immediate: true },
)

/** Pick a login state: the shared builder decides the auth shape, then the
 *  form slots carry {{CRED:...}} references only — never a secret value. */
function applySessionTemplate(stateName: string): void {
  const state = sessionStates.value.find((s) => s.stateName === stateName)
  const tpl = state ? sessionStateAuthTemplate(state) : null
  if (!tpl) return
  authOn.value = true
  authType.value = String(tpl.type)
  if (authType.value === 'bearer') {
    token.value = String(tpl.token)
  } else if (authType.value === 'header') {
    headerName.value = String(tpl.name)
    headerValue.value = String(tpl.value)
  }
}

/** Save pre-gate (page save calls this): first plain-language verdict, null = ok. */
function validateAll(): string | null {
  return validateAuth(buildAuth())
}

defineExpose({ validateAll })
</script>

<template>
  <div class="auth-form-editor" data-test="auth-form-editor">
    <div v-if="parseFailed" class="afe-parse-warn" data-test="auth-parse-failed">
      {{ t('apitest.authParseFailed') }}
    </div>

    <el-checkbox v-model="authOn" data-test="auth-enable">{{ t('apitest.authEnable') }}</el-checkbox>

    <div v-if="sessionStates.length" class="afe-line" data-test="session-state-picker-row">
      <label for="af-session-ref">{{ t('apitest.sessionRef') }}</label>
      <el-select id="af-session-ref" data-test="session-state-picker" class="afe-type"
        :placeholder="t('apitest.sessionRefPh')" filterable clearable
        @update:model-value="(v: unknown) => applySessionTemplate(String(v ?? ''))">
        <el-option v-for="s in sessionStates" :key="s.stateName" :value="s.stateName"
          :label="s.expiresAt ? `${s.stateName} · ${s.kind} · ${String(s.expiresAt).slice(0, 10)}` : `${s.stateName} · ${s.kind}`" />
      </el-select>
    </div>

    <div v-if="authOn" class="afe-body">
      <div class="afe-line">
        <label for="af-type">{{ t('apitest.authType') }}</label>
        <el-select id="af-type" v-model="authType" data-test="auth-type-select" class="afe-type">
          <el-option v-for="ty in AUTH_TYPES" :key="ty" :value="ty" :label="ty" />
        </el-select>
      </div>

      <div v-if="authType === 'bearer'" class="afe-line">
        <label for="af-token">{{ t('apitest.authToken') }}</label>
        <el-input id="af-token" v-model="token" data-test="auth-token"
          :placeholder="t('apitest.authCredPh')" />
      </div>

      <template v-if="authType === 'basic'">
        <div class="afe-line">
          <label for="af-username">{{ t('apitest.authUsername') }}</label>
          <el-input id="af-username" v-model="username" data-test="auth-username"
            autocomplete="username" placeholder="app_service" />
        </div>
        <div class="afe-line">
          <label for="af-password">{{ t('apitest.authPassword') }}</label>
          <el-input id="af-password" v-model="password" data-test="auth-password" type="password"
            autocomplete="new-password" :placeholder="t('apitest.authCredPh')" />
        </div>
      </template>

      <template v-if="authType === 'header'">
        <div class="afe-line">
          <label for="af-header-name">{{ t('apitest.authHeaderName') }}</label>
          <el-input id="af-header-name" v-model="headerName" data-test="auth-header-name"
            placeholder="X-API-KEY" />
        </div>
        <div class="afe-line">
          <label for="af-header-value">{{ t('apitest.authHeaderValue') }}</label>
          <el-input id="af-header-value" v-model="headerValue" data-test="auth-header-value"
            :placeholder="t('apitest.authCredPh')" />
        </div>
      </template>

      <template v-if="authType === 'api-key'">
        <div class="afe-line">
          <label for="af-key-name">{{ t('apitest.authKeyName') }}</label>
          <el-input id="af-key-name" v-model="keyName" data-test="auth-key-name"
            placeholder="apiKey" />
        </div>
        <div class="afe-line">
          <label for="af-key-value">{{ t('apitest.authKeyValue') }}</label>
          <el-input id="af-key-value" v-model="keyValue" data-test="auth-key-value"
            :placeholder="t('apitest.authCredPh')" />
        </div>
        <div class="afe-line">
          <label for="af-placement">{{ t('apitest.authPlacement') }}</label>
          <el-select id="af-placement" v-model="placement" data-test="auth-placement" class="afe-type">
            <el-option v-for="pl in AUTH_PLACEMENTS" :key="pl" :value="pl" :label="pl" />
          </el-select>
        </div>
      </template>

      <template v-if="authType === 'signature'">
        <div class="afe-line">
          <label for="af-preset">{{ t('apitest.authPreset') }}</label>
          <el-select id="af-preset" data-test="auth-preset-select" class="afe-type"
            :placeholder="t('apitest.authPresetPh')" filterable clearable
            @update:model-value="(v: unknown) => applyPreset(String(v ?? ''))">
            <el-option v-for="p in presets" :key="p.presetKey" :value="p.presetKey" :label="p.label" />
          </el-select>
        </div>
        <div class="afe-hint" data-test="auth-preset-hint">{{ t('apitest.authPresetHint') }}</div>
        <div class="afe-assemblies" data-test="auth-assemblies">
          <div v-for="(row, i) in assemblies" :key="i" class="afe-asm-row">
            <el-select v-model="row.into" class="afe-type" :name="`af-into-${i}`"
              :aria-label="t('apitest.authInto')">
              <el-option v-for="pl in AUTH_PLACEMENTS" :key="pl" :value="pl" :label="pl" />
            </el-select>
            <el-input v-model="row.name" :name="`af-name-${i}`" data-test="auth-asm-name"
              :aria-label="t('apitest.authAsmName')" placeholder="X-Sign" />
            <el-input v-model="row.value" :name="`af-value-${i}`" data-test="auth-asm-value"
              :aria-label="t('apitest.authAsmValue')" :placeholder="t('apitest.authAsmValuePh')" />
            <el-button size="small" text type="danger" :aria-label="t('apitest.deleteRow')"
              @click="removeAssemblyRow(i)">
              {{ t('apitest.authRemoveRow') }}
            </el-button>
          </div>
          <el-button size="small" plain data-test="auth-asm-add" @click="addAssemblyRow">
            {{ t('apitest.authAddRow') }}
          </el-button>
        </div>
      </template>
    </div>
    <div v-else class="afe-hint">{{ t('apitest.authNoneHint') }}</div>
  </div>
</template>

<style scoped>
.auth-form-editor {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.afe-body {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 8px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
}
.afe-line {
  display: flex;
  align-items: center;
  gap: 8px;
}
.afe-line > label {
  flex: 0 0 96px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.afe-type {
  width: 180px;
}
.afe-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.afe-parse-warn {
  font-size: 12px;
  color: var(--el-color-warning);
}
.afe-asm-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.afe-asm-row .el-input {
  flex: 1;
}
</style>
