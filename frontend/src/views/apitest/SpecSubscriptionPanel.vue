<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { bizErrorMsg } from '@/utils/bizError'
import { listProgramProjectIds, listPrograms } from '@/api/program'
import {
  createApiSpecSubscription,
  deleteApiSpecSubscription,
  listApiSpecSubscriptions,
  listApiSpecs,
  updateApiSpecSubscription,
  type SpecSubscriptionView,
} from '@/api/apiSpec'

/**
 * Set-internal source (sourceKind=program_spec): provider candidates are computed from the
 * V340 program lens on the client for option rendering only; the membership fence and the
 * provider-holds-spec check live in the core service single source and pass through as
 * human-language errors (local validation never duplicates those four gates).
 */

const props = defineProps<{ projectId: number | null }>()
const emit = defineEmits<{ (e: 'closed'): void }>()

const { t } = useI18n()
const auth = useAuthStore()

const canWrite = computed(() => {
  if (auth.isAdmin) return true
  const p = auth.projects.find((x) => x.projectId === props.projectId)
  return p?.role === 2
})

const rows = ref<SpecSubscriptionView[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const loading = ref(false)

async function load(): Promise<void> {
  if (props.projectId === null) return
  loading.value = true
  try {
    const r = await listApiSpecSubscriptions({ projectId: props.projectId, page: page.value, size: size.value })
    rows.value = r.list
    total.value = Number(r.total ?? 0)
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('apitest.subscription.loadFail')))
  } finally {
    loading.value = false
  }
}

watch(() => props.projectId, () => { page.value = 1; void load() })
onMounted(() => { void load() })

const formOpen = ref(false)
const editingId = ref<number | null>(null)
const formName = ref('')
const formUrl = ref('')
const formInterval = ref(60)
const saving = ref(false)

const formSourceKind = ref<'url' | 'program_spec'>('url')
const formProviderProjectId = ref<number | null>(null)
const formProviderSpecName = ref('')
// Provider candidates: projects that share at least one ACTIVE program with the
// current project (client-side option set; the authoritative fence is re-checked
// by the core service on every create/update).
const peerProjectIds = ref<number[]>([])
const peerLoading = ref(false)
const providerSpecNames = ref<string[]>([])
const providerSpecLoading = ref(false)

async function loadPeerProjects(): Promise<void> {
  peerProjectIds.value = []
  const pid = props.projectId
  if (pid === null) return
  peerLoading.value = true
  try {
    const programs = (await listPrograms()).filter((p) => p.state === 1)
    const membership = await Promise.all(programs.map((p) => listProgramProjectIds(p.id)))
    const set = new Set<number>()
    programs.forEach((p, i) => {
      const ids = membership[i] ?? []
      if (ids.includes(pid)) ids.forEach((id) => { if (id !== pid) set.add(id) })
    })
    peerProjectIds.value = [...set].sort((a, b) => a - b)
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('apitest.subscription.peerLoadFail')))
  } finally {
    peerLoading.value = false
  }
}

async function loadProviderSpecs(): Promise<void> {
  providerSpecNames.value = []
  const pid = formProviderProjectId.value
  if (pid === null) return
  providerSpecLoading.value = true
  try {
    const r = await listApiSpecs({ projectId: pid, page: 1, size: 200 })
    providerSpecNames.value = r.list.map((x) => x.name)
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('apitest.subscription.providerSpecLoadFail')))
  } finally {
    providerSpecLoading.value = false
  }
}

watch(formProviderProjectId, () => {
  formProviderSpecName.value = ''
  void loadProviderSpecs()
})

/** Provider display: name from my project list, honest id fallback otherwise
 * (provider teams own their projects; the spec name carries the real signal). */
function providerLabel(id: number | null | undefined): string {
  if (id === null || id === undefined) return t('apitest.subscription.providerUnknown')
  const p = auth.projects.find((x) => x.projectId === id)
  return p ? p.projectName : `#${id}`
}

function sourceText(row: SpecSubscriptionView): string {
  if (row.sourceKind === 'program_spec') {
    return t('apitest.subscription.sourceProvider', {
      project: providerLabel(row.providerProjectId),
      spec: row.providerSpecName ?? t('apitest.subscription.providerUnknown'),
    })
  }
  return row.url ?? ''
}

function openCreate(): void {
  editingId.value = null
  formName.value = ''
  formUrl.value = ''
  formInterval.value = 60
  formSourceKind.value = 'url'
  formProviderProjectId.value = null
  formProviderSpecName.value = ''
  providerSpecNames.value = []
  formOpen.value = true
  void loadPeerProjects()
}

function openEdit(row: SpecSubscriptionView): void {
  editingId.value = row.id
  formName.value = row.name
  formUrl.value = row.url ?? ''
  formInterval.value = row.checkIntervalMinutes
  formSourceKind.value = row.sourceKind === 'program_spec' ? 'program_spec' : 'url'
  formProviderProjectId.value = row.providerProjectId ?? null
  formProviderSpecName.value = row.providerSpecName ?? ''
  formOpen.value = true
  void loadPeerProjects()
  if (formSourceKind.value === 'program_spec' && formProviderProjectId.value !== null) {
    void loadProviderSpecs()
  }
}

/** Form-shape gate only (button enable + honest pointer); semantic gates stay in core. */
const canSubmit = computed(() => {
  if (!formName.value.trim()) return false
  if (formSourceKind.value === 'url') return formUrl.value.trim().length > 0
  return formProviderProjectId.value !== null && formProviderSpecName.value.trim().length > 0
})

async function submit(): Promise<void> {
  if (props.projectId === null) return
  saving.value = true
  try {
    const shape = {
      name: formName.value.trim(),
      checkIntervalMinutes: formInterval.value,
      sourceKind: formSourceKind.value,
    }
    const payload = formSourceKind.value === 'url'
      ? { ...shape, url: formUrl.value.trim() }
      : {
          ...shape,
          providerProjectId: formProviderProjectId.value,
          providerSpecName: formProviderSpecName.value.trim(),
        }
    if (editingId.value === null) {
      await createApiSpecSubscription({ ...payload, projectId: props.projectId })
      ElMessage.success(t('apitest.subscription.createOk'))
    } else {
      await updateApiSpecSubscription(editingId.value, payload)
      ElMessage.success(t('apitest.subscription.saveOk'))
    }
    formOpen.value = false
    await load()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('apitest.subscription.saveFail')))
  } finally {
    saving.value = false
  }
}


async function onToggle(row: SpecSubscriptionView, v: boolean): Promise<void> {
  try {
    await updateApiSpecSubscription(row.id, { enabled: v })
    ElMessage.success(v ? t('apitest.subscription.enabled') : t('apitest.subscription.disabled'))
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('apitest.subscription.saveFail')))
  } finally {
    await load()
  }
}

async function remove(row: SpecSubscriptionView): Promise<void> {
  try {
    await ElMessageBox.confirm(t('apitest.subscription.deleteConfirm', { name: row.name }), t('common.delete'), { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteApiSpecSubscription(row.id)
    ElMessage.success(t('apitest.subscription.deleteOk'))
    await load()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('apitest.subscription.deleteFail')))
  }
}

function statusTag(row: SpecSubscriptionView): { type: 'success' | 'warning' | 'danger' | 'info'; label: string } {
  if (!row.lastStatus) return { type: 'info', label: t('apitest.subscription.statusNever') }
  if (row.lastStatus === 'updated') return { type: 'success', label: t('apitest.subscription.statusUpdated') }
  if (row.lastStatus === 'unchanged') return { type: 'info', label: t('apitest.subscription.statusUnchanged') }
  return { type: 'danger', label: t('apitest.subscription.statusFailed') }
}

function close(): void {
  emit('closed')
}
</script>

<template>
  <div class="sub-panel" data-test="spec-subscription-panel">
    <template v-if="!formOpen">
      <div class="sub-toolbar">
        <span class="sub-hint">{{ t('apitest.subscription.hint') }}</span>
        <el-button v-if="canWrite" type="primary" data-test="sub-create-btn" @click="openCreate">
          {{ t('apitest.subscription.create') }}
        </el-button>
      </div>
      <el-table border v-loading="loading" :data="rows" data-test="sub-table" size="small" :empty-text="t('apitest.subscription.empty')">
        <el-table-column prop="name" :label="t('apitest.subscription.colName')" min-width="120" show-overflow-tooltip />
        <el-table-column :label="t('apitest.subscription.colSource')" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.sourceKind === 'program_spec'" data-test="sub-source-provider">{{ sourceText(row) }}</span>
            <span v-else data-test="sub-source-url">{{ sourceText(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('apitest.subscription.colInterval')" width="110">
          <template #default="{ row }">{{ t('apitest.subscription.minutesUnit', { n: row.checkIntervalMinutes }) }}</template>
        </el-table-column>
        <el-table-column :label="t('apitest.subscription.colEnabled')" width="80">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabled"
              :disabled="!canWrite"
              data-test="sub-enabled-switch"
              @update:model-value="(v: boolean) => onToggle(row, v)"
            />
          </template>
        </el-table-column>
        <el-table-column :label="t('apitest.subscription.colLastCheck')" width="170">
          <template #default="{ row }">
            <el-tag :type="statusTag(row).type" size="small" data-test="sub-status-tag">{{ statusTag(row).label }}</el-tag>
            <span v-if="row.lastCheckAt" class="sub-time">{{ row.lastCheckAt }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.action')" width="130">
          <template #default="{ row }">
            <el-button v-if="canWrite" link type="primary" data-test="sub-edit-btn" @click="openEdit(row)">
              {{ t('common.edit') }}
            </el-button>
            <el-button v-if="canWrite" link type="danger" data-test="sub-delete-btn" @click="remove(row)">
              {{ t('common.delete') }}
            </el-button>
            <el-tooltip v-if="row.lastError" :content="row.lastError" placement="top">
              <span class="sub-err" data-test="sub-error-dot">!</span>
            </el-tooltip>
          </template>
        </el-table-column>
      </el-table>
      <div class="pagination-bar">
        <el-pagination
          v-if="total > size"
          v-model:current-page="page"
          :page-size="size"
          :total="total"
          layout="prev, pager, next, total"
          @current-change="load"
        />
      </div>
    </template>

    <el-form v-else label-position="top" class="sub-form" data-test="sub-form">
      <el-form-item :label="t('apitest.subscription.fieldName')">
        <el-input v-model="formName" data-test="sub-name-input" :placeholder="t('apitest.subscription.fieldNamePh')" maxlength="100" />
      </el-form-item>
      <!-- Caption intentionally sits outside the el-form-item label binding:
           Element Plus 2.14 auto-points label[for] at the radio-group
           container DIV when it is the item's only registered control, and a
           DIV is not a labelable element — Chromium's form-association audit
           (DevTools Issues) reports it. The group carries an explicit
           aria-label for its accessible name instead. -->
      <el-form-item data-test="sub-source-kind-item">
        <div class="sub-kind-field">
          <span class="sub-kind-caption">{{ t('apitest.subscription.fieldSourceKind') }}</span>
          <el-radio-group
            v-model="formSourceKind"
            data-test="sub-source-kind-radio"
            :aria-label="t('apitest.subscription.fieldSourceKind')"
          >
            <el-radio value="url">{{ t('apitest.subscription.sourceKindUrl') }}</el-radio>
            <el-radio value="program_spec">{{ t('apitest.subscription.sourceKindProgram') }}</el-radio>
          </el-radio-group>
          <div class="sub-field-hint">{{ formSourceKind === 'url' ? t('apitest.subscription.urlHint') : t('apitest.subscription.programHint') }}</div>
        </div>
      </el-form-item>
      <el-form-item v-if="formSourceKind === 'url'" :label="t('apitest.subscription.fieldUrl')">
        <el-input v-model="formUrl" data-test="sub-url-input" :placeholder="t('apitest.subscription.fieldUrlPh')" />
      </el-form-item>
      <template v-else>
        <el-form-item v-if="peerProjectIds.length > 0 || peerLoading" :label="t('apitest.subscription.fieldProviderProject')">
          <el-select
            v-model="formProviderProjectId"
            data-test="sub-provider-project-select"
            :loading="peerLoading"
            filterable
            :placeholder="t('apitest.subscription.providerProjectPh')"
            style="width: 100%"
          >
            <el-option
              v-for="id in peerProjectIds"
              :key="id"
              :label="providerLabel(id)"
              :value="id"
            />
          </el-select>
        </el-form-item>
        <div v-else class="sub-field-hint" data-test="sub-provider-empty">{{ t('apitest.subscription.providerEmpty') }}</div>
        <el-form-item v-if="formProviderProjectId !== null" :label="t('apitest.subscription.fieldProviderSpec')">
          <el-select
            v-model="formProviderSpecName"
            data-test="sub-provider-spec-select"
            :loading="providerSpecLoading"
            filterable
            :placeholder="t('apitest.subscription.providerSpecPh')"
            :no-data-text="t('apitest.subscription.providerNoSpecs')"
            style="width: 100%"
          >
            <el-option v-for="n in providerSpecNames" :key="n" :label="n" :value="n" />
          </el-select>
        </el-form-item>
      </template>
      <el-form-item :label="t('apitest.subscription.fieldInterval')">
        <el-input-number v-model="formInterval" data-test="sub-interval-input" :min="1" :max="43200" :step="30" />
        <div class="sub-field-hint">{{ t('apitest.subscription.intervalHint') }}</div>
      </el-form-item>
      <div v-if="!canSubmit" class="sub-field-hint" data-test="sub-form-guard">{{ t('apitest.subscription.guardHint') }}</div>
      <div class="sub-form-actions">
        <el-button data-test="sub-form-back-btn" @click="formOpen = false">{{ t('apitest.subscription.backList') }}</el-button>
        <el-button type="primary" :loading="saving" :disabled="!canSubmit" data-test="sub-form-save-btn" @click="submit">
          {{ editingId === null ? t('apitest.subscription.create') : t('common.save') }}
        </el-button>
      </div>
    </el-form>

    <div class="sub-close">
      <el-button data-test="sub-close-btn" @click="close">{{ t('common.close') }}</el-button>
    </div>
  </div>
</template>

<style scoped>
.sub-panel {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.sub-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.sub-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.sub-time {
  margin-left: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.sub-err {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: var(--el-color-danger);
  color: #fff;
  font-size: 11px;
  cursor: help;
}

.sub-form {
  max-width: 560px;
}

/* Mirrors the label-top caption metrics (font/color/line-height/8px gap) of
   the sibling el-form-item labels so the caption-free item stays on-grid. */
.sub-kind-field {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  width: 100%;
}

.sub-kind-caption {
  display: block;
  margin-bottom: 8px;
  line-height: 22px;
  font-size: var(--el-form-label-font-size, 14px);
  color: var(--el-text-color-regular);
}

.sub-field-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}

.sub-form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.sub-close {
  display: flex;
  justify-content: flex-end;
}
</style>
