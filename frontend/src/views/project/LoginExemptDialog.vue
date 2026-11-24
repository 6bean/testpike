<script setup lang="ts">
/**
 * Login exemption dialog for one environment profile (AV5 / face②).
 * Three actions only, mirroring the three admin doors: read the current
 * declaration, declare/overwrite it, record a re-check observation. Verdicts
 * are computed by the core table and echoed back — this page can never mark
 */
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../../stores/auth'
import {
  declareLoginExempt,
  getLoginExempt,
  reviewLoginExempt,
  type LoginExemptView,
} from '../../api/loginExempt'

const visible = defineModel<boolean>({ required: true })
const props = defineProps<{ envId: number | null; envName?: string }>()

const { t } = useI18n()
const auth = useAuthStore()
const canExempt = computed(() => auth.hasPerm('perm:session:exempt'))

const loading = ref(false)
const saving = ref(false)
const reviewing = ref(false)
const view = ref<LoginExemptView | null>(null)

const form = reactive({
  kind: 'none',
  credKey: '',
  accounts: [] as string[],
  reviewMethod: 'trial_login',
})
const accountDraft = ref('')

const review = reactive({ signal: '', note: '' })

const CRED_KEY_PATTERN = /^[A-Za-z_][A-Za-z0-9_]{0,63}$/

const KINDS = ['none', 'master_code', 'whitelist_accounts'] as const
const REVIEW_METHODS = ['trial_login', 'manual'] as const
const SIGNALS = [
  'trial_login_ok',
  'captcha_still_required',
  'master_code_rejected',
  'undetermined',
  'manual_confirmed',
  'manual_rejected',
] as const

const statusLight = computed(() => {
  const v = view.value
  if (!v || !v.declared) return { type: 'info' as const, label: t('project.exemptStatusUndeclared') }
  if (v.reviewStatus === 'passed') return { type: 'success' as const, label: t('project.exemptStatusPassed') }
  if (v.reviewStatus === 'failed') return { type: 'danger' as const, label: t('project.exemptStatusFailed') }
  return { type: 'warning' as const, label: t('project.exemptStatusPending') }
})

const reviewedAtText = computed(() => {
  const raw = view.value?.reviewedAt
  if (!raw) return ''
  return raw.replace('T', ' ').slice(0, 19)
})

function resetFormFrom(v: LoginExemptView) {
  const k: string = v.kind ?? ''
  form.kind = (KINDS as readonly string[]).includes(k) ? k : 'none'
  form.credKey = v.credKey ?? ''
  form.accounts = v.accounts ? [...v.accounts] : []
  const m: string = v.reviewMethod ?? ''
  form.reviewMethod = (REVIEW_METHODS as readonly string[]).includes(m) ? m : 'trial_login'
}

async function load() {
  if (props.envId == null) return
  loading.value = true
  try {
    view.value = await getLoginExempt(props.envId)
    resetFormFrom(view.value)
    review.signal = ''
    review.note = ''
  } catch (e) {
    view.value = null
    ElMessage.error((e as Error).message || t('project.exemptLoadFailed'))
  } finally {
    loading.value = false
  }
}

watch(
  () => [visible.value, props.envId] as const,
  ([open]) => {
    if (open && props.envId != null) load()
  },
)

function addAccount() {
  const v = accountDraft.value.trim()
  if (!v) return
  if (!form.accounts.includes(v)) form.accounts.push(v)
  accountDraft.value = ''
}

function removeAccount(v: string) {
  form.accounts = form.accounts.filter((a) => a !== v)
}

/** Client-side pre-check only; the authoritative rules stay single-sourced in core. */
function declareBlockers(): string | null {
  if (form.kind === 'master_code') {
    const key = form.credKey.trim()
    if (!key) return t('project.exemptCredKeyRequired')
    if (!CRED_KEY_PATTERN.test(key)) return t('project.exemptCredKeyInvalid')
  }
  if (form.kind === 'whitelist_accounts' && form.accounts.length === 0) {
    return t('project.exemptAccountsRequired')
  }
  return null
}

async function onDeclare() {
  if (props.envId == null) return
  const blocker = declareBlockers()
  if (blocker) {
    ElMessage.warning(blocker)
    return
  }
  saving.value = true
  try {
    view.value = await declareLoginExempt(props.envId, {
      kind: form.kind,
      credKey: form.kind === 'master_code' ? form.credKey.trim() : null,
      accounts: form.kind === 'whitelist_accounts' ? form.accounts : null,
      reviewMethod: form.reviewMethod,
    })
    resetFormFrom(view.value)
    ElMessage.success(t('project.exemptDeclaredOk'))
  } catch (e) {
    ElMessage.error((e as Error).message || t('project.exemptDeclareFailed'))
  } finally {
    saving.value = false
  }
}

async function onReview() {
  if (props.envId == null || !review.signal) {
    ElMessage.warning(t('project.exemptSignalRequired'))
    return
  }
  reviewing.value = true
  try {
    view.value = await reviewLoginExempt(props.envId, {
      signal: review.signal,
      note: review.note.trim() || null,
    })
    review.signal = ''
    review.note = ''
    ElMessage.success(t('project.exemptReviewedOk'))
  } catch (e) {
    ElMessage.error((e as Error).message || t('project.exemptReviewFailed'))
  } finally {
    reviewing.value = false
  }
}
</script>

<template>
  <!-- tp-dialog-exempt: 960px is the measured comfortable-band size for this
       dialog (ratio 1.74 across all five states); theme tiers land outside
       the 1.2-1.8 band on both sides (720px -> 1.15, 1080px -> 1.95). -->
  <el-dialog
    v-model="visible"
    width="960px"
    destroy-on-close
    :title="t('project.exemptDialogTitle') + (envName ? '：' + envName : '')"
    data-test="login-exempt-dialog"
  >
    <div v-loading="loading">
      <!-- Current declaration: status light + server wording, never page-made -->
      <div class="exempt-status" data-test="exempt-status">
        <el-tag :type="statusLight.type" size="small" data-test="exempt-status-tag">
          {{ statusLight.label }}
        </el-tag>
        <span v-if="view?.declared" class="text-secondary" data-test="exempt-kind-text">
          {{ t('project.exemptKindLabel') }}{{ t('project.exemptKind.' + view.kind) }}
        </span>
        <span v-if="reviewedAtText" class="text-secondary" data-test="exempt-reviewed-at">
          {{ t('project.exemptReviewedAtLabel') }}{{ reviewedAtText }}
        </span>
      </div>
      <el-alert
        v-if="view?.declared && view.reviewDetail"
        :title="view.reviewDetail"
        :type="view.reviewStatus === 'passed' ? 'success' : view.reviewStatus === 'failed' ? 'error' : 'info'"
        :closable="false"
        class="exempt-detail"
        data-test="exempt-review-detail"
      />

      <!-- Declaration form -->
      <el-form label-position="top" class="exempt-form" @submit.prevent>
        <!-- for="" opts out of the auto label[for] association: el-radio-group's
             root is a DIV and Blink requires a labelable target, so the generated
             for= trips the Chromium FormLabelForMatchesNonExistingIdError audit
             (iron rule 5c). Empty for= switches Element Plus to the group pattern:
             the caption renders as a div and the radiogroup gets aria-labelledby.
             Same shape as the pinned precedent in CaseDetailView. -->
        <el-form-item :label="t('project.exemptKindField')" for="">
          <el-radio-group id="" v-model="form.kind" data-test="exempt-kind-radio">
            <el-radio v-for="k in KINDS" :key="k" :value="k">
              {{ t('project.exemptKind.' + k) }}
            </el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item v-if="form.kind === 'master_code'" :label="t('project.exemptCredKeyField')">
          <el-input
            v-model="form.credKey"
            data-test="exempt-credkey-input"
            :placeholder="t('project.exemptCredKeyPlaceholder')"
          />
          <div class="exempt-hint">{{ t('project.exemptCredKeyHint') }}</div>
        </el-form-item>

        <el-form-item v-if="form.kind === 'whitelist_accounts'" :label="t('project.exemptAccountsField')">
          <div class="exempt-accounts">
            <el-tag
              v-for="a in form.accounts"
              :key="a"
              closable
              size="small"
              data-test="exempt-account-tag"
              @close="removeAccount(a)"
            >{{ a }}</el-tag>
            <el-input
              v-model="accountDraft"
              size="small"
              class="exempt-account-input"
              data-test="exempt-account-input"
              :placeholder="t('project.exemptAccountAddPlaceholder')"
              @keyup.enter="addAccount"
            />
          </div>
        </el-form-item>

        <el-form-item :label="t('project.exemptReviewMethodField')" for="">
          <el-radio-group id="" v-model="form.reviewMethod" data-test="exempt-method-radio">
            <el-radio v-for="m in REVIEW_METHODS" :key="m" :value="m">
              {{ t('project.exemptMethod.' + m) }}
            </el-radio>
          </el-radio-group>
        </el-form-item>

        <div class="exempt-actions">
          <el-tooltip
            v-if="!canExempt"
            :content="t('project.exemptNoPermTip')"
            placement="top"
          >
            <span>
              <el-button type="primary" disabled data-test="exempt-declare-btn">
                {{ t('project.exemptDeclareBtn') }}
              </el-button>
            </span>
          </el-tooltip>
          <el-button
            v-else
            type="primary"
            :loading="saving"
            data-test="exempt-declare-btn"
            @click="onDeclare"
          >
            {{ t('project.exemptDeclareBtn') }}
          </el-button>
        </div>
      </el-form>

      <!-- Re-check: the page only reports observed signals; the verdict comes back from the server -->
      <el-divider />
      <el-form label-position="top" class="exempt-form" @submit.prevent>
        <el-form-item :label="t('project.exemptSignalField')">
          <el-select
            v-model="review.signal"
            class="exempt-signal-select"
            data-test="exempt-signal-select"
            :placeholder="t('project.exemptSignalPlaceholder')"
          >
            <el-option
              v-for="s in SIGNALS"
              :key="s"
              :value="s"
              :label="t('project.exemptSignal.' + s)"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('project.exemptNoteField')">
          <el-input
            v-model="review.note"
            type="textarea"
            :rows="2"
            maxlength="500"
            show-word-limit
            data-test="exempt-note-input"
            :placeholder="t('project.exemptNotePlaceholder')"
          />
        </el-form-item>
        <div class="exempt-actions">
          <el-tooltip
            v-if="!canExempt"
            :content="t('project.exemptNoPermTip')"
            placement="top"
          >
            <span>
              <el-button :disabled="true" data-test="exempt-review-btn">
                {{ t('project.exemptReviewBtn') }}
              </el-button>
            </span>
          </el-tooltip>
          <el-button
            v-else
            :loading="reviewing"
            data-test="exempt-review-btn"
            @click="onReview"
          >
            {{ t('project.exemptReviewBtn') }}
          </el-button>
        </div>
      </el-form>
    </div>
  </el-dialog>
</template>

<style scoped>
.exempt-status {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  /* Block gap follows the single-source band token (R21): the old 8px/12px
     local values measured below the 16px tier in the style-measure face. */
  margin-bottom: var(--tp-band-gap);
}
.exempt-detail {
  margin-bottom: var(--tp-band-gap);
}
.exempt-form {
  display: block;
}
.exempt-accounts {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  width: 100%;
}
.exempt-account-input {
  width: 180px;
}
.exempt-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}
.exempt-actions {
  display: flex;
  justify-content: flex-end;
}
.exempt-signal-select {
  width: 100%;
}
</style>
