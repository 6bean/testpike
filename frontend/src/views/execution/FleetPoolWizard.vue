<!-- Add-pool wizard (T294d : three steps — pick or create a pool
 through the single tag channel (a pool is a type=7 tag, created via
 api/tags createTag, never a second POST /tags), issue one-time
 enrollment tokens through the admin bridge, then land the user back on
 a refreshed page. The token plaintext lives only in this component's
 state and is wiped on close; it never reaches storage or the URL
 (credential red line: one-time + TTL + fingerprint-bound, digest-only
 storage server-side). -->
<template>
  <el-dialog
    v-model="visible"
    draggable
    :title="t('pool.wizardTitle')"
    :class="step === 1 ? 'tp-dialog--lg' : 'tp-dialog--md'"
    append-to-body
    data-test="pool-wizard-dialog"
    @closed="scrub"
  >
    <el-steps :active="step" simple class="wizard-steps">
      <el-step :title="t('pool.wizardStepPool')" data-test="pool-wizard-step-pool" />
      <el-step :title="t('pool.wizardStepEnroll')" data-test="pool-wizard-step-enroll" />
      <el-step :title="t('pool.wizardStepDone')" data-test="pool-wizard-step-done" />
    </el-steps>

    <!-- Step 1: pick an existing pool or create one through the tag channel. -->
    <div v-if="step === 0" class="wizard-body" data-test="pool-wizard-pane-pool">
      <el-radio-group v-model="choice" class="wizard-choice">
        <el-radio value="existing">{{ t('pool.wizardPoolChoiceExisting') }}</el-radio>
        <el-radio value="create">{{ t('pool.wizardPoolChoiceCreate') }}</el-radio>
      </el-radio-group>
      <el-select
        v-if="choice === 'existing'"
        v-model="selectedPoolId"
        class="wizard-field"
        filterable
        data-test="pool-wizard-pool-select"
        :placeholder="t('pool.wizardPoolSelectPlaceholder')"
      >
        <el-option
          v-for="p in pools"
          :key="p.poolId"
          :label="p.poolName"
          :value="p.poolId"
        />
      </el-select>
      <el-input
        v-else
        v-model="newPoolName"
        class="wizard-field"
        data-test="pool-wizard-pool-name"
        :placeholder="t('pool.wizardPoolNamePlaceholder')"
        maxlength="64"
      />
    </div>

    <!-- Step 2: enrollment tokens. Plaintext shown exactly once with a copy
         action; the ledger table only carries the plaintext-free view fields. -->
    <div v-else-if="step === 1" class="wizard-body" data-test="pool-wizard-pane-enroll">
      <div class="wizard-issue-row">
        <el-button
          type="primary"
          :loading="issuing"
          data-test="pool-wizard-issue"
          @click="issue"
        >
          {{ t('pool.wizardEnrollIssue') }}
        </el-button>
      </div>
      <div v-if="issuedPlaintext" class="wizard-token-block" data-test="pool-wizard-token">
        <div class="wizard-token-label">{{ t('pool.wizardEnrollCommand') }}</div>
        <div class="wizard-token-value">{{ issuedPlaintext }}</div>
        <div class="wizard-token-note">{{ t('pool.wizardTokenOnce') }}</div>
        <el-button size="small" data-test="pool-wizard-token-copy" @click="copyToken">
          {{ t('pool.wizardTokenCopy') }}
        </el-button>
      </div>
      <!-- Access command block: pick the deployment form, the page hands
           over the exact fleet-script invocation. Key names mirror
           deploy/fleet/fleet-keys.env.example (parity pinned by the
           fleetPoolWizard contract reading that file); the page assembles
           text only — installation logic stays in the repo's scripts. -->
      <div class="wizard-command" data-test="pool-wizard-command">
        <div class="wizard-command-label">{{ t('pool.wizardCommandLabel') }}</div>
        <div class="wizard-form-label">{{ t('pool.wizardFormLabel') }}</div>
        <el-radio-group v-model="form" class="wizard-form-choice" data-test="pool-wizard-form-group">
          <el-radio value="bare" data-test="pool-wizard-form-bare">{{ t('pool.wizardFormBare') }}</el-radio>
          <el-radio value="k8s" data-test="pool-wizard-form-k8s">{{ t('pool.wizardFormK8s') }}</el-radio>
        </el-radio-group>
        <pre class="wizard-command-value">{{ accessCommand }}</pre>
        <div class="wizard-command-hint">
          {{ form === 'bare' ? t('pool.wizardFormBareHint') : t('pool.wizardFormK8sHint') }}
        </div>
        <div class="wizard-command-note">{{ t('pool.wizardCommandHint') }}</div>
        <el-button size="small" data-test="pool-wizard-command-copy" @click="copyCommand">
          {{ t('pool.wizardCommandCopy') }}
        </el-button>
      </div>
      <el-table
        v-if="ledger.length"
        :data="ledger"
        border
        size="small"
        max-height="260"
        class="wizard-ledger"
        data-test="pool-wizard-ledger"
      >
        <el-table-column :label="t('pool.wizardColRole')" prop="role" width="90" />
        <el-table-column :label="t('pool.wizardColUses')" width="90">
          <template #default="{ row }">{{ row.usedCount }} / {{ row.maxUses }}</template>
        </el-table-column>
        <el-table-column :label="t('pool.wizardColStatus')" width="100">
          <template #default="{ row }">
            <el-tag size="small" disable-transitions :type="statusTone(row.status)">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('pool.wizardColExpire')" width="150">
          <template #default="{ row }"><TimeCell :iso="row.expiresAt" /></template>
        </el-table-column>
        <el-table-column :label="t('pool.wizardColFingerprint')" prop="boundFingerprint" min-width="120" show-overflow-tooltip />
        <el-table-column :label="t('pool.wizardColAction')" width="130">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 0"
              size="small"
              text
              type="danger"
              :data-test="`pool-wizard-revoke-${row.id}`"
              @click="revoke(row.id)"
            >
              {{ t('pool.wizardRevoke') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- Step 3: honest landing guidance; the pool list reloads via landed. -->
    <div v-else class="wizard-body" data-test="pool-wizard-pane-done">
      <p class="wizard-done-hint" data-test="pool-wizard-done-hint">{{ t('pool.wizardDoneHint') }}</p>
    </div>

    <template #footer>
      <div class="wizard-footer">
        <el-button v-if="step === 1" data-test="pool-wizard-skip" @click="finish">
          {{ t('pool.wizardEnrollSkip') }}
        </el-button>
        <el-button v-if="step < 2" type="primary" :loading="creating" data-test="pool-wizard-next" @click="next">
          {{ step === 0 ? t('pool.wizardNextFromPool') : t('pool.wizardNextFromEnroll') }}
        </el-button>
        <el-button v-else type="primary" data-test="pool-wizard-finish" @click="finish">
          {{ t('pool.wizardFinishClose') }}
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { createTag } from '@/api/tags'
import {
  listFleetEnrollTokens,
  issueFleetEnrollToken,
  revokeFleetEnrollToken,
  type FleetEnrollToken,
} from '@/api/cluster'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import TimeCell from '@/components/TimeCell.vue'

interface PoolOption {
  poolId: number
  poolName: string
}

const props = defineProps<{ pools: PoolOption[] }>()
const emit = defineEmits<{ (e: 'landed'): void }>()
const visible = defineModel<boolean>({ required: true })

const { t } = useI18n()
const { projectId } = useProjectPageContext()

const step = ref(0)
const choice = ref<'existing' | 'create'>('existing')
const selectedPoolId = ref<number | null>(null)
const newPoolName = ref('')
const poolLabel = ref('')
const creating = ref(false)
const issuing = ref(false)
const ledger = ref<FleetEnrollToken[]>([])
/** Deployment form for the access command (bare node vs Kubernetes pool). */
const form = ref<'bare' | 'k8s'>('bare')
/** One-time plaintext kept in component state only; wiped by scrub(). */
const issuedPlaintext = ref('')

watch(visible, (open) => {
  if (open) {
    step.value = 0
    choice.value = 'existing'
    selectedPoolId.value = null
    newPoolName.value = ''
    poolLabel.value = ''
    ledger.value = []
    form.value = 'bare'
  }
})

/** Closing (by any path) removes the plaintext from memory. */
function scrub(): void {
  issuedPlaintext.value = ''
}

async function next(): Promise<void> {
  if (step.value === 0) {
    if (choice.value === 'existing') {
      if (selectedPoolId.value == null) {
        ElMessage.warning(t('pool.wizardPoolNameRequired'))
        return
      }
      poolLabel.value = props.pools.find((p) => p.poolId === selectedPoolId.value)?.poolName ?? ''
      step.value = 1
      await loadLedger()
      return
    }
    const name = newPoolName.value.trim()
    if (!name) {
      ElMessage.warning(t('pool.wizardPoolNameRequired'))
      return
    }
    if (projectId.value == null) {
      // R63: the fix path is named, not a generic failure.
      ElMessage.warning(t('pool.wizardNeedProject'))
      return
    }
    creating.value = true
    try {
      // Single write channel for pools: a pool is a type=7 tag (R40 — no
      // second POST /tags path may appear beside this call).
      const created = await createTag({ projectId: projectId.value, name, type: 7 })
      poolLabel.value = created.name
      step.value = 1
      await loadLedger()
    } finally {
      creating.value = false
    }
    return
  }
  step.value = 2
}

async function loadLedger(): Promise<void> {
  try {
    ledger.value = await listFleetEnrollTokens()
  } catch {
    ledger.value = []
  }
}

async function issue(): Promise<void> {
  issuing.value = true
  try {
    const answer = await issueFleetEnrollToken({ role: 'executor', maxUses: 1, remark: poolLabel.value || undefined })
    issuedPlaintext.value = answer.token
    await loadLedger()
  } finally {
    issuing.value = false
  }
}

async function revoke(id: number): Promise<void> {
  await revokeFleetEnrollToken(id)
  // Silent re-read after the action — the user needs no manual refresh.
  await loadLedger()
}

async function copyToken(): Promise<void> {
  try {
    await navigator.clipboard.writeText(issuedPlaintext.value)
    ElMessage.success(t('pool.wizardTokenCopied'))
  } catch {
    ElMessage.warning(t('pool.wizardTokenOnce'))
  }
}

/** The fleet-script invocation for the picked form. Key names mirror
 *  deploy/fleet/fleet-keys.env.example (contract-pinned parity); the bare
 *  command interpolates the just-issued plaintext from component state and
 *  keeps a placeholder before issuance so an empty secret never renders.
 *  The k8s form needs no token — pooled executors register through the
 *  pool facts the chart ships. */
const accessCommand = computed(() => {
  const base = window.location.origin
  if (form.value === 'bare') {
    const token = issuedPlaintext.value || '<EXECUTOR_NODE_ENROLL_TOKEN>'
    return `EXECUTOR_NODE_BASE_URL=${base} \\\nEXECUTOR_NODE_ENROLL_TOKEN=${token} \\\n    ./deploy/fleet/bare/install-executor-node.sh`
  }
  return `EXECUTOR_POOL_API_URL=${base} \\\n    ./deploy/fleet/k8s/install-executor-pool.sh`
})

async function copyCommand(): Promise<void> {
  try {
    await navigator.clipboard.writeText(accessCommand.value)
    ElMessage.success(t('pool.wizardCommandCopied'))
  } catch {
    // Clipboard refusal still gives the manual-copy exit (R63), no silence.
    ElMessage.warning(t('pool.wizardCommandCopyFailed'))
  }
}

function finish(): void {
  emit('landed')
  visible.value = false
}

/** Status → colour is a display mapping over the server's own enum value
 *  (core keeps the transition authority; the wizard never re-decides). */
const STATUS_LABEL_KEYS: Record<number, string> = {
  0: 'pool.wizardStatusPending',
  1: 'pool.wizardStatusRedeemed',
  2: 'pool.wizardStatusExpired',
  3: 'pool.wizardStatusRevoked',
}
const STATUS_TONES: Record<number, 'warning' | 'success' | 'info' | 'danger'> = {
  0: 'warning',
  1: 'success',
  2: 'info',
  3: 'danger',
}
function statusLabel(status: number): string {
  return t(STATUS_LABEL_KEYS[status] ?? 'pool.wizardStatusExpired')
}
function statusTone(status: number): string {
  return STATUS_TONES[status] ?? 'info'
}
</script>

<style scoped>
.wizard-steps {
  margin-bottom: 16px;
}
.wizard-body {
  min-height: 200px;
}
.wizard-choice {
  display: flex;
  gap: 24px;
  margin-bottom: 12px;
}
.wizard-field {
  width: 100%;
}
.wizard-issue-row {
  margin-bottom: 12px;
}
.wizard-token-block {
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  padding: 12px;
  margin-bottom: 12px;
}
.wizard-token-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 4px;
}
.wizard-token-value {
  font-family: var(--el-font-family-mono, monospace);
  word-break: break-all;
  margin-bottom: 8px;
}
.wizard-token-note {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 8px;
}
.wizard-command {
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  padding: 12px;
  margin-bottom: 12px;
}
.wizard-command-label {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 6px;
}
.wizard-form-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 4px;
}
.wizard-form-choice {
  display: flex;
  gap: 24px;
  margin-bottom: 8px;
}
.wizard-command-value {
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  padding: 10px 12px;
  font-family: var(--el-font-family-mono, monospace);
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0 0 8px;
}
.wizard-command-hint,
.wizard-command-note {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 8px;
}
.wizard-ledger {
  width: 100%;
}
.wizard-done-hint {
  color: var(--el-text-color-regular);
}
.wizard-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
