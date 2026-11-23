<script setup lang="ts">
/**
 * probeConfig contract = TargetSaveRequest javadoc: HTTP/ONLINE={url,expect?}; LOGIN
 * has two accepted forms (core LoginProbe) — credential replay={url,credential=@cred/…}
 * and login-state direct read={mode=state-read,stateName,checkUrl}; DB={connectionId};
 * CERT={host,port}. HTTP/ONLINE can also be prefilled from an API-test asset.
 */
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import ProjectOwnerEcho from '@/components/ProjectOwnerEcho.vue'
import { bizErrorMsg } from '@/utils/bizError'
import {
  getProjectEnvs,
  listEnvConnections,
  listEnvParams,
  type EnvConnectionItem,
  type ProjectEnv,
} from '@/api/project'
import { listCollections, listRequests, type ApiCollectionItem, type ApiRequestItem } from '@/api/apiTest'
import { createMonitorTarget, type MonitorTargetSavePayload } from '@/api/monitor'
import { listSessionStates, type LoginStateRow } from '@/api/sessionState'
import { isConsumableSessionState } from '@/utils/authForm'
import {
  LOGIN_REPLAY_MODE,
  LOGIN_STATE_READ_MODE,
  buildLoginProbeConfig,
  isLoginStateReadMode,
  loginProbeGate,
  type LoginProbeFormState,
} from '@/utils/loginProbeForm'

const props = defineProps<{ projectId: number | null }>()
const emit = defineEmits<{ (e: 'created'): void }>()

const { t } = useI18n()

const visible = ref(false)
const step = ref(0)
const submitting = ref(false)

const form = reactive({
  projectId: null as number | null,
  envId: null as number | null,
  name: '',
  targetType: 'HTTP',
  url: '',
  expectBody: '',
  credKey: '',
  probeMode: LOGIN_REPLAY_MODE,
  stateName: '',
  checkUrl: '',
  connectionId: null as number | null,
  host: '',
  port: null as number | null,
  intervalSeconds: 60,
  enabled: true,
})


const MIN_INTERVAL_HINT_SECONDS = 60

const TYPE_OPTIONS = ['HTTP', 'ONLINE', 'LOGIN', 'DB', 'CERT'] as const

const envs = ref<ProjectEnv[]>([])
const connections = ref<EnvConnectionItem[]>([])
const credKeys = ref<string[]>([])
const loginStates = ref<LoginStateRow[]>([])
const collections = ref<ApiCollectionItem[]>([])
const requests = ref<ApiRequestItem[]>([])
const refCollectionId = ref<number | null>(null)
const refRequestId = ref<number | null>(null)

watch(visible, (open) => {
  if (open) resetForm()
})

function resetForm() {
  step.value = 0
  submitting.value = false
  refCollectionId.value = null
  refRequestId.value = null
  requests.value = []
  loginStates.value = []
  Object.assign(form, {
    projectId: props.projectId,
    envId: null,
    name: '',
    targetType: 'HTTP',
    url: '',
    expectBody: '',
    credKey: '',
    probeMode: LOGIN_REPLAY_MODE,
    stateName: '',
    checkUrl: '',
    connectionId: null,
    host: '',
    port: null,
    intervalSeconds: 60,
    enabled: true,
  })
}

function openWizard() {
  visible.value = true
}
defineExpose({ openWizard })

async function loadEnvs() {
  if (!form.projectId) {
    envs.value = []
    return
  }
  try {
    envs.value = await getProjectEnvs(form.projectId)
  } catch (e) {
    void bizErrorMsg(e, t('monitor.loadFail'))
  }
}

watch(() => form.projectId, (pid) => {
  form.envId = null
  connections.value = []
  credKeys.value = []
  loginStates.value = []
  collections.value = []
  if (!pid) return
  void loadEnvs()
  void loadConnections()
  void loadCollections()
})

watch(() => form.targetType, (tt) => {
  if (tt === 'LOGIN' && form.projectId && form.envId) void loadCredKeys()
  if (tt === 'LOGIN' && form.projectId) void loadLoginStates()
})

/** Each probe form brings in its own field set, so its option list loads on demand. */
watch(() => form.probeMode, (mode) => {
  if (mode === LOGIN_STATE_READ_MODE && form.projectId) void loadLoginStates()
})

watch(() => form.envId, () => {
  if (form.targetType === 'LOGIN') void loadCredKeys()
})

async function loadConnections() {
  if (!form.projectId) return
  try {
    const all = await listEnvConnections(form.projectId)
    connections.value = all.filter((c) => !form.envId || c.envId === form.envId)
  } catch (e) {
    void bizErrorMsg(e, t('monitor.loadFail'))
  }
}

async function loadCredKeys() {
  if (!form.projectId || !form.envId) {
    credKeys.value = []
    return
  }
  try {
    credKeys.value = (await listEnvParams(form.projectId, form.envId)).map((p) => p.key)
  } catch (e) {
    void bizErrorMsg(e, t('monitor.loadFail'))
  }
}

/** Ledger rows for the direct-read form: metadata only, the secret never passes through here. */
async function loadLoginStates() {
  if (!form.projectId) {
    loginStates.value = []
    return
  }
  try {
    loginStates.value = await listSessionStates(form.projectId, true)
  } catch (e) {
    void bizErrorMsg(e, t('monitor.loadFail'))
  }
}

/** Selectable rows = those the server can answer a request header for (verdict shared with authForm). */
const stateOptions = computed(() => loginStates.value.filter(isConsumableSessionState))

function loginProbeState(): LoginProbeFormState {
  return {
    probeMode: form.probeMode,
    url: form.url,
    credKey: form.credKey,
    stateName: form.stateName,
    checkUrl: form.checkUrl,
  }
}

async function loadCollections() {
  if (!form.projectId) return
  try {
    collections.value = await listCollections(form.projectId)
  } catch (e) {
    void bizErrorMsg(e, t('monitor.loadFail'))
  }
}

async function onRefCollection(id: number | null) {
  requests.value = []
  refRequestId.value = null
  if (!id) return
  try {
    requests.value = await listRequests(id)
  } catch (e) {
    void bizErrorMsg(e, t('monitor.loadFail'))
  }
}


function onRefRequest(id: number | null) {
  if (!id) return
  const req = requests.value.find((r) => r.id === id)
  if (!req) return
  const env = envs.value.find((e) => e.id === form.envId)
  const base = (env?.baseUrl ?? '').replace(/\/+$/, '')
  form.url = base + (req.urlPath.startsWith('/') ? req.urlPath : `/${req.urlPath}`)
  form.expectBody = extractBodyContains(req.assertionsJson)
}

function extractBodyContains(assertionsJson: string): string {
  try {
    const rows = JSON.parse(assertionsJson || '[]') as Array<Record<string, unknown>>
    const hit = rows.find((r) => r.path === 'body' && (r.op === 'contains' || r.type === 'body'))
    return typeof hit?.expect === 'string' ? hit.expect : ''
  } catch {
    return ''
  }
}


function stepBlockReason(s: number): string {
  if (s === 0) {
    if (!form.projectId) return t('monitor.wizard.needProject')
    if (!form.envId) return t('monitor.wizard.needEnv')
    if (!form.name.trim()) return t('monitor.wizard.needName')
  }
  if (s === 1) {
    if (form.targetType === 'HTTP' || form.targetType === 'ONLINE') {
      if (!form.url.trim()) return t('monitor.wizard.needUrl')
    }
    if (form.targetType === 'LOGIN') {
      const blockKey = loginGate.value.blockKey
      if (blockKey) return t(blockKey)
    }
    if (form.targetType === 'DB' && !form.connectionId) return t('monitor.wizard.needConn')
    if (form.targetType === 'CERT' && (!form.host.trim() || !form.port)) return t('monitor.wizard.needHostPort')
  }
  return ''
}

/** Single source of the LOGIN required-field verdict (asterisks and the guard both read it). */
const loginGate = computed(() => loginProbeGate(loginProbeState()))


const reqFields = computed(() => ({
  env: true,
  name: true,
  url: form.targetType === 'HTTP' || form.targetType === 'ONLINE' || loginGate.value.required.url,
  cred: loginGate.value.required.cred,
  stateName: loginGate.value.required.stateName,
  checkUrl: loginGate.value.required.checkUrl,
  conn: form.targetType === 'DB',
  host: form.targetType === 'CERT',
  port: form.targetType === 'CERT',
}))


function firstBlockedStep(): number {
  for (let s = 0; s <= 1; s++) {
    if (stepBlockReason(s)) return s
  }
  return -1
}

function buildPayload(): MonitorTargetSavePayload {
  let probeConfig: Record<string, unknown>
  switch (form.targetType) {
    case 'LOGIN':
      probeConfig = buildLoginProbeConfig(loginProbeState())
      break
    case 'DB':
      probeConfig = { connectionId: form.connectionId }
      break
    case 'CERT':
      probeConfig = { host: form.host.trim(), port: form.port }
      break
    default:
      probeConfig = form.expectBody.trim()
        ? { url: form.url.trim(), expect: { body: form.expectBody.trim() } }
        : { url: form.url.trim() }
  }
  return {
    projectId: form.projectId!,
    envId: form.envId!,
    name: form.name.trim(),
    targetType: form.targetType,
    probeConfig,
    checkIntervalSeconds: form.intervalSeconds,
    enabled: form.enabled,
  }
}

function nextStep() {
  const msg = stepBlockReason(step.value)
  if (msg) {
    ElMessage.warning(msg)
    return
  }
  step.value += 1
}

function cancelWizard() {
  visible.value = false
}

async function submit() {
  const blocked = firstBlockedStep()
  if (blocked >= 0) {
    step.value = blocked
    ElMessage.warning(stepBlockReason(blocked))
    return
  }
  submitting.value = true
  try {
    await createMonitorTarget(buildPayload())
    ElMessage.success(t('monitor.wizard.createdOk'))
    visible.value = false
    emit('created')
  } catch (e) {
    void bizErrorMsg(e, t('monitor.wizard.createFail'))
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="t('monitor.wizard.title')"
    width="640px"
    draggable
    destroy-on-close
    data-test="monitor-wizard"
  >
    <el-steps :active="step" finish="success" align-center class="wizard__steps">
      <el-step :title="t('monitor.wizard.step1')" />
      <el-step :title="t('monitor.wizard.step2')" />
      <el-step :title="t('monitor.wizard.step3')" />
      <el-step :title="t('monitor.wizard.step4')" />
    </el-steps>

    
    <div v-show="step === 0" class="wizard__body" data-test="wizard-step-belong">
      <!-- Every caption below is a real association (label for=/id pair, or
           aria-labelledby for non-labelable widgets): a bare <label> is an
           orphaned label and Chromium reports it in the Issues panel. -->
      <!-- R30: the wizard never asks for the project a second time — ownership
           echoes the header global context (G-group rule 1: no second channel). -->
      <ProjectOwnerEcho />
      <div class="wizard__field">
        <label for="tw-env-select">{{ t('monitor.wizard.env') }}<span v-if="reqFields.env" class="wizard__req" :title="t('monitor.wizard.reqTip')" aria-hidden="true">*</span></label>
        <el-select id="tw-env-select" v-model="form.envId" filterable :placeholder="t('monitor.wizard.envPh')" data-test="wizard-env" class="wizard__control">
          <el-option v-for="e in envs" :key="e.id" :label="e.name" :value="e.id" />
        </el-select>
      </div>
      <div class="wizard__field">
        <label for="tw-name-input">{{ t('monitor.wizard.name') }}<span v-if="reqFields.name" class="wizard__req" :title="t('monitor.wizard.reqTip')" aria-hidden="true">*</span></label>
        <el-input id="tw-name-input" v-model="form.name" :placeholder="t('monitor.wizard.namePh')" maxlength="64" data-test="wizard-name" show-word-limit />
      </div>
    </div>

    
    <div v-show="step === 1" class="wizard__body" data-test="wizard-step-probe">
      <div class="wizard__field">
        <span id="tw-type-label" class="wizard__caption">{{ t('monitor.wizard.type') }}</span>
        <el-radio-group v-model="form.targetType" aria-labelledby="tw-type-label" data-test="wizard-type">
          <el-radio-button v-for="tt in TYPE_OPTIONS" :key="tt" :value="tt">{{ tt }}</el-radio-button>
        </el-radio-group>
        <p class="wizard__type-desc">{{ t(`monitor.wizard.typeDesc.${form.targetType}`) }}</p>
      </div>

      <template v-if="form.targetType === 'HTTP' || form.targetType === 'ONLINE'">
        <div class="wizard__field">
          <span id="tw-ref-asset-label" class="wizard__caption">{{ t('monitor.wizard.refAsset') }}</span>
          <div class="wizard__ref-row" role="group" aria-labelledby="tw-ref-asset-label">
            <el-select v-model="refCollectionId" clearable filterable :placeholder="t('monitor.wizard.refCollectionPh')" data-test="wizard-ref-collection" @change="onRefCollection">
              <el-option v-for="c in collections" :key="c.id" :label="c.name" :value="c.id" />
            </el-select>
            <el-select v-model="refRequestId" clearable filterable :placeholder="t('monitor.wizard.refRequestPh')" data-test="wizard-ref-request" @change="onRefRequest">
              <el-option v-for="r in requests" :key="r.id" :label="`${r.method} ${r.name}`" :value="r.id" />
            </el-select>
          </div>
          <p class="wizard__hint">{{ t('monitor.wizard.refHint') }}</p>
        </div>
        <div class="wizard__field">
          <label for="tw-url-input">{{ t('monitor.wizard.url') }}<span v-if="reqFields.url" class="wizard__req" :title="t('monitor.wizard.reqTip')" aria-hidden="true">*</span></label>
          <el-input id="tw-url-input" v-model="form.url" placeholder="https://…/health" data-test="wizard-url" />
        </div>
        <div class="wizard__field">
          <label for="tw-expect-body-input">{{ t('monitor.wizard.expectBody') }}</label>
          <el-input id="tw-expect-body-input" v-model="form.expectBody" :placeholder="t('monitor.wizard.expectBodyPh')" data-test="wizard-expect-body" />
          <p class="wizard__hint">{{ t('monitor.wizard.expectBodyHint') }}</p>
        </div>
      </template>

      <template v-else-if="form.targetType === 'LOGIN'">
        <div class="wizard__field">
          <span id="tw-probe-mode-label" class="wizard__caption">{{ t('monitor.wizard.probeMode') }}</span>
          <el-radio-group v-model="form.probeMode" aria-labelledby="tw-probe-mode-label" data-test="wizard-probe-mode">
            <el-radio-button :value="LOGIN_REPLAY_MODE">{{ t('monitor.wizard.modeReplay') }}</el-radio-button>
            <el-radio-button :value="LOGIN_STATE_READ_MODE">{{ t('monitor.wizard.modeStateRead') }}</el-radio-button>
          </el-radio-group>
          <p class="wizard__hint">{{ t('monitor.wizard.modeHint') }}</p>
        </div>

        <template v-if="isLoginStateReadMode(form.probeMode)">
          <div class="wizard__field">
            <label for="tw-state-name-select">{{ t('monitor.wizard.stateName') }}<span v-if="reqFields.stateName" class="wizard__req" :title="t('monitor.wizard.reqTip')" aria-hidden="true">*</span></label>
            <el-select id="tw-state-name-select" v-model="form.stateName" filterable :placeholder="t('monitor.wizard.stateNamePh')" data-test="wizard-state-name" class="wizard__control">
              <el-option v-for="s in stateOptions" :key="s.stateName" :label="s.stateName" :value="s.stateName" />
            </el-select>
            <p class="wizard__hint">{{ t('monitor.wizard.stateNameHint') }}</p>
          </div>
          <div class="wizard__field">
            <label for="tw-check-url-input">{{ t('monitor.wizard.checkUrl') }}<span v-if="reqFields.checkUrl" class="wizard__req" :title="t('monitor.wizard.reqTip')" aria-hidden="true">*</span></label>
            <el-input id="tw-check-url-input" v-model="form.checkUrl" :placeholder="t('monitor.wizard.checkUrlPh')" data-test="wizard-check-url" />
            <p class="wizard__hint">{{ t('monitor.wizard.checkUrlHint') }}</p>
          </div>
        </template>
        <template v-else>
          <div class="wizard__field">
            <label for="tw-login-url-input">{{ t('monitor.wizard.url') }}<span v-if="reqFields.url" class="wizard__req" :title="t('monitor.wizard.reqTip')" aria-hidden="true">*</span></label>
            <el-input id="tw-login-url-input" v-model="form.url" placeholder="https://…/login" data-test="wizard-url" />
          </div>
          <div class="wizard__field">
            <label for="tw-cred-select">{{ t('monitor.wizard.credential') }}<span v-if="reqFields.cred" class="wizard__req" :title="t('monitor.wizard.reqTip')" aria-hidden="true">*</span></label>
            <el-select id="tw-cred-select" v-model="form.credKey" filterable allow-create :placeholder="t('monitor.wizard.credPh')" data-test="wizard-cred" class="wizard__control">
              <el-option v-for="k in credKeys" :key="k" :label="`@cred/${k}`" :value="k" />
            </el-select>
            <p class="wizard__hint">{{ t('monitor.wizard.credHint') }}</p>
          </div>
        </template>
      </template>

      <template v-else-if="form.targetType === 'DB'">
        <div class="wizard__field">
          <label for="tw-conn-select">{{ t('monitor.wizard.connection') }}<span v-if="reqFields.conn" class="wizard__req" :title="t('monitor.wizard.reqTip')" aria-hidden="true">*</span></label>
          <el-select id="tw-conn-select" v-model="form.connectionId" filterable :placeholder="t('monitor.wizard.connPh')" data-test="wizard-conn" class="wizard__control">
            <el-option v-for="c in connections" :key="c.id" :label="`${c.name}（${c.dialect}）`" :value="c.id" />
          </el-select>
          <p class="wizard__hint">{{ t('monitor.wizard.connHint') }}</p>
        </div>
      </template>

      <template v-else>
        <div class="wizard__field wizard__field--pair">
          <div>
            <label for="tw-host-input">{{ t('monitor.wizard.host') }}<span v-if="reqFields.host" class="wizard__req" :title="t('monitor.wizard.reqTip')" aria-hidden="true">*</span></label>
            <el-input id="tw-host-input" v-model="form.host" placeholder="api.example.com" data-test="wizard-host" />
          </div>
          <div>
            <label for="tw-port-input">{{ t('monitor.wizard.port') }}<span v-if="reqFields.port" class="wizard__req" :title="t('monitor.wizard.reqTip')" aria-hidden="true">*</span></label>
            <el-input-number id="tw-port-input" v-model="form.port" :min="1" :max="65535" controls-position="right" data-test="wizard-port" class="wizard__control" />
          </div>
        </div>
        <p class="wizard__hint">{{ t('monitor.wizard.certHint') }}</p>
      </template>
    </div>

    
    <div v-show="step === 2" class="wizard__body" data-test="wizard-step-schedule">
      <div class="wizard__field">
        <label for="tw-interval-input">{{ t('monitor.wizard.interval') }}</label>
        <el-input-number id="tw-interval-input" v-model="form.intervalSeconds" :min="MIN_INTERVAL_HINT_SECONDS" :step="60" controls-position="right" data-test="wizard-interval" />
        <span class="wizard__unit">{{ t('monitor.wizard.seconds') }}</span>
        <p class="wizard__hint">{{ t('monitor.wizard.intervalHint') }}</p>
      </div>
      <div class="wizard__field">
        <span id="tw-enabled-label" class="wizard__caption">{{ t('monitor.wizard.enabled') }}</span>
        <el-switch v-model="form.enabled" aria-labelledby="tw-enabled-label" data-test="wizard-enabled" />
        <p class="wizard__hint">{{ t('monitor.wizard.enabledHint') }}</p>
      </div>
    </div>

    
    <div v-show="step === 3" class="wizard__body" data-test="wizard-step-confirm">
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item :label="t('monitor.wizard.project')">{{ form.projectId ? `#${form.projectId}` : '—' }}</el-descriptions-item>
        <el-descriptions-item :label="t('monitor.wizard.env')">{{ envs.find((e) => e.id === form.envId)?.name ?? '—' }}</el-descriptions-item>
        <el-descriptions-item :label="t('monitor.wizard.name')">{{ form.name }}</el-descriptions-item>
        <el-descriptions-item :label="t('monitor.wizard.type')">{{ form.targetType }}</el-descriptions-item>
        <el-descriptions-item :label="t('monitor.wizard.probeConfig')">{{ JSON.stringify(buildPayload().probeConfig) }}</el-descriptions-item>
        <el-descriptions-item :label="t('monitor.wizard.interval')">{{ form.intervalSeconds }}s</el-descriptions-item>
        <el-descriptions-item :label="t('monitor.wizard.enabled')">{{ form.enabled ? t('monitor.wizard.enabledOn') : t('monitor.wizard.enabledOff') }}</el-descriptions-item>
      </el-descriptions>
      <p class="wizard__hint">{{ t('monitor.wizard.confirmHint') }}</p>
    </div>

    <template #footer>
      <el-button data-test="wizard-cancel" @click="cancelWizard">{{ t('monitor.wizard.cancel') }}</el-button>
      <el-button v-if="step > 0" @click="step -= 1">{{ t('monitor.wizard.prev') }}</el-button>
      <el-button v-if="step < 3" type="primary" data-test="wizard-next" @click="nextStep">{{ t('monitor.wizard.next') }}</el-button>
      <el-button v-if="step === 3" type="primary" :loading="submitting" data-test="wizard-submit" @click="submit">{{ t('monitor.wizard.submit') }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.wizard__steps { margin-bottom: 20px; }
.wizard__body { min-height: 220px; }
.wizard__field { margin-bottom: 16px; }
.wizard__field > label, .wizard__caption { display: block; font-size: 13px; color: var(--el-text-color-secondary); margin-bottom: 6px; }
.wizard__req { color: var(--el-color-danger); margin-left: 2px; font-weight: 600; }
.wizard__control { width: 100%; }
.wizard__field--pair { display: grid; grid-template-columns: 2fr 1fr; gap: 12px; }
.wizard__ref-row { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.wizard__hint { margin: 6px 0 0; font-size: 12px; color: var(--el-text-color-secondary); }
.wizard__type-desc { margin: 8px 0 0; font-size: 12px; color: var(--el-text-color-secondary); }
.wizard__unit { margin-left: 8px; font-size: 13px; color: var(--el-text-color-secondary); }
</style>
