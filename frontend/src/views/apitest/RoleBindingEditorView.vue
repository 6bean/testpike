<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import CaseSelect from '@/components/CaseSelect.vue'
import EmptyState from '@/components/EmptyState.vue'
import { bizErrorMsg } from '@/utils/bizError'
import { getCaseScript, saveCaseScript } from '@/api/case'
import { listStepAliases, type StepAliasItem } from '@/api/apiTest'



const { t } = useI18n()


const RESERVED_NAMES = ['ENV', 'DATA', 'PACK', 'VAR', 'SESSION', 'RUN', 'global']

const ROLE_NAME_RE = /^[A-Za-z0-9_\u4e00-\u9fa5][A-Za-z0-9_\u4e00-\u9fa5-]*$/

interface RoleRow {
  name: string
  binding: string
  poolSize: number
  credential: string
}

// R30: project switch = header global context (wiring piece: URL-first chain
// + follow). watch(projectId) below already drives every load.
const { projectId } = useProjectPageContext()
const caseId = ref<number | null>(null)
const roles = ref<RoleRow[]>([])
const loading = ref(false)
const saving = ref(false)

const scriptVersion = ref<number | null>(null)
const schemaVersion = ref('v2')

let actionRoot: Record<string, unknown> = {}

function newRow(): RoleRow {
  return { name: '', binding: 'per-vu', poolSize: 3, credential: '' }
}

async function loadScript(): Promise<void> {
  scriptVersion.value = null
  roles.value = []
  actionRoot = {}
  if (caseId.value === null) return
  loading.value = true
  try {
    const script = await getCaseScript(caseId.value)
    if (!script) {
      ElMessage.info(t('roleCard.noScriptYet'))
      return
    }
    schemaVersion.value = script.schemaVersion || 'v2'
    scriptVersion.value = script.version
    let parsed: Record<string, unknown>
    try {
      parsed = JSON.parse(script.actionJson) as Record<string, unknown>
    } catch {
      ElMessage.error(t('roleCard.parseFailed'))
      return
    }
    actionRoot = parsed
    const declared = Array.isArray(parsed.roles) ? parsed.roles : []
    roles.value = declared.map((r) => {
      const row = r as { name?: string; binding?: string; credential?: string | null }
      const binding = row.binding ?? 'none'
      const poolMatch = /^pool\((\d+)\)$/.exec(binding)
      return {
        name: row.name ?? '',
        binding: poolMatch ? 'pool' : binding,
        poolSize: poolMatch ? Number(poolMatch[1]) : 3,
        credential: row.credential ?? '',
      }
    })
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('roleCard.loadFail')))
  } finally {
    loading.value = false
  }
}

watch(caseId, () => { void loadScript() })


const firstError = computed<string | null>(() => {
  const seen = new Set<string>()
  for (const row of roles.value) {
    const name = row.name.trim()
    if (!name) return t('roleCard.nameRequired')
    if (!ROLE_NAME_RE.test(name)) return t('roleCard.nameIllegal', { name })
    if (RESERVED_NAMES.includes(name)) return t('roleCard.nameReserved', { name })
    if (seen.has(name)) return t('roleCard.nameDuplicated', { name })
    seen.add(name)
    if (row.binding === 'pool' && (!Number.isInteger(row.poolSize) || row.poolSize < 1 || row.poolSize > 9999)) {
      return t('roleCard.poolRange', { name })
    }
  }
  return null
})

function bindingOf(row: RoleRow): string {
  return row.binding === 'pool' ? `pool(${row.poolSize})` : row.binding
}

async function save(): Promise<void> {
  if (caseId.value === null || scriptVersion.value === null) {
    ElMessage.warning(t('roleCard.pickCaseFirst'))
    return
  }
  const err = firstError.value
  if (err) {
    ElMessage.error(err)
    return
  }
  const payload = {
    ...actionRoot,
    roles: roles.value.map((row) => ({
      name: row.name.trim(),
      binding: bindingOf(row),
      credential: row.credential.trim() === '' ? null : row.credential.trim(),
    })),
  }
  saving.value = true
  try {
    const saved = await saveCaseScript(caseId.value,
      { schemaVersion: schemaVersion.value, actionJson: JSON.stringify(payload) },
      scriptVersion.value)
    scriptVersion.value = saved.version
    ElMessage.success(t('roleCard.saveOk'))
    await loadScript()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('roleCard.saveFail')))
  } finally {
    saving.value = false
  }
}

function addRole(): void {
  roles.value.push(newRow())
}


function refText(name: string): string {
  return '{{' + name + '.token}}'
}

const stepAliases = ref<StepAliasItem[]>([])


function aliasRefText(a: StepAliasItem): string {
  return '{{' + a.aliasName + '.\u5b57\u6bb5}}'
}

async function loadAliases(): Promise<void> {
  if (projectId.value === null) {
    stepAliases.value = []
    return
  }
  try {
    stepAliases.value = await listStepAliases(projectId.value)
  } catch {
  }
}

watch(projectId, () => { void loadAliases() })


const REF_EXAMPLES = [
  { test: 'role-card-ref-role', nameKey: 'refRoleGroup', code: '{{\u4e70\u5bb6.token}}', descKey: 'refRoleDesc' },
  { test: 'role-card-ref-global', nameKey: 'refGlobalGroup', code: '{{global.\u5e93\u5b58}}', descKey: 'refGlobalDesc' },
  { test: 'role-card-ref-run', nameKey: 'refRunGroup', code: '{{RUN.\u8ba2\u5355\u53f7}}', descKey: 'refRunDesc' },
]

function removeRole(index: number): void {
  roles.value.splice(index, 1)
}


async function copyRef(text: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(t('roleCard.copied'))
  } catch {
    ElMessage.info(t('roleCard.copyHint', { text }))
  }
}

onMounted(() => {  })
</script>

<template>
  <!-- tp-page-root-exempt: root carries its own 16px padding (live-measured
       1440x900), content edge gap present without the theme .page-root. -->
  <div class="role-card-page" data-test="role-card-page">
    <div class="tp-page-head">
      <!-- stage-3: the page-name h2 retired (topbar is the single
 source); only the declaration-semantics description line stays. -->
      <p class="tp-page-desc">{{ t('roleCard.pageDesc') }}</p>
    </div>

    <div class="rc-toolbar">
      <CaseSelect
        v-model="caseId"
        :project-id="projectId"
        :placeholder="t('roleCard.casePlaceholder')"
        data-test="role-card-case-select"
        width="320px"
      />
    </div>

    <div class="rc-section" data-test="role-card-ref-group">
      <div class="rc-section-head">
        <span class="rc-section-title">{{ t('roleCard.refTitle') }}</span>
      </div>
      <p class="rc-section-hint">{{ t('roleCard.refHint') }}</p>
      <div class="rc-ref-grid">
        <div v-for="ex in REF_EXAMPLES" :key="ex.test" class="rc-ref-item" :data-test="ex.test">
          <div class="rc-ref-name">{{ t('roleCard.' + ex.nameKey) }}</div>
          <code v-text="ex.code" />
          <p>{{ t('roleCard.' + ex.descKey) }}</p>
          <el-button link type="primary" @click="copyRef(ex.code)">{{ t('roleCard.copy') }}</el-button>
        </div>
      </div>
 
      <div v-if="projectId !== null" class="rc-alias-group" data-test="role-card-alias-group">
        <div class="rc-ref-name">{{ t('roleCard.aliasGroupTitle') }}</div>
        <p class="rc-section-hint">{{ t('roleCard.aliasGroupHint') }}</p>
        <div v-if="stepAliases.length === 0" class="rc-alias-empty" data-test="role-card-alias-empty">
          {{ t('roleCard.aliasEmpty') }}
        </div>
        <div v-for="a in stepAliases" :key="a.id" class="rc-alias-item" :data-test="`role-card-alias-${a.id}`">
          <el-tag size="small" :type="a.kind === 'step' ? 'warning' : 'info'" data-test="alias-kind-tag">
            {{ a.kind === 'step' ? t('roleCard.aliasKindStep') : t('roleCard.aliasKindPath') }}
          </el-tag>
          <code v-text="aliasRefText(a)" />
          <span class="rc-alias-target">{{ t('roleCard.aliasTargetPrefix') }}<code v-text="a.target" /></span>
          <el-button link type="primary" :data-test="`role-card-alias-copy-${a.id}`" @click="copyRef(aliasRefText(a))">
            {{ t('roleCard.copy') }}
          </el-button>
        </div>
      </div>
    </div>

    <div v-if="caseId === null" class="rc-empty">
      <EmptyState :title="t('roleCard.pickCaseFirst')" />
    </div>

    <template v-else>
      <div class="rc-section">
        <div class="rc-section-head">
          <span class="rc-section-title">{{ t('roleCard.rolesTitle') }}</span>
          <el-button data-test="role-card-add-btn" plain type="primary" :disabled="loading" @click="addRole">
            {{ t('roleCard.addRole') }}
          </el-button>
        </div>
        <p class="rc-section-hint">{{ t('roleCard.rolesHint') }}</p>
        <div v-if="roles.length === 0" class="rc-empty">
          <EmptyState :title="t('roleCard.rolesEmpty')" />
        </div>
        <div v-for="(row, i) in roles" :key="i" class="rc-role-card" :data-test="`role-card-row-${i}`">
          <div class="rc-field">
            <label :for="`rc-name-${i}`">{{ t('roleCard.roleName') }}</label>
            <el-input v-model="row.name" :id="`rc-name-${i}`" data-test="role-card-name-input"
              :placeholder="t('roleCard.roleNamePh')" style="width: 160px" />
          </div>
          <div class="rc-field">
            <label :id="`rc-binding-label-${i}`" :for="`rc-binding-${i}`">{{ t('roleCard.binding') }}</label>
            <el-select v-model="row.binding" :id="`rc-binding-${i}`" data-test="role-card-binding-select" style="width: 200px">
              <el-option value="per-vu" :label="t('roleCard.bindingPerVu')" />
              <el-option value="shared" :label="t('roleCard.bindingShared')" />
              <el-option value="pool" :label="t('roleCard.bindingPool')" />
              <el-option value="none" :label="t('roleCard.bindingNone')" />
            </el-select>
          </div>
          <div v-if="row.binding === 'pool'" class="rc-field">
            <label :for="`rc-pool-${i}`">{{ t('roleCard.poolSize') }}</label>
            <el-input-number v-model="row.poolSize" :id="`rc-pool-${i}`" data-test="role-card-pool-size"
              :min="1" :max="9999" :step="1" style="width: 140px" />
          </div>
          <div class="rc-field">
            <label :for="`rc-credential-${i}`">{{ t('roleCard.credential') }}</label>
            <el-input v-model="row.credential" :id="`rc-credential-${i}`" data-test="role-card-credential-input"
              :placeholder="t('roleCard.credentialPh')" style="width: 180px" />
          </div>
          <div class="rc-field rc-field-grow">
            <!-- Caption for a read-only preview (non-labelable widget): a bare
                 <label> here would be an orphaned label in the Issues panel,
                 so it renders as a plain span instead. -->
            <span class="rc-field-caption" :id="`rc-ref-label-${i}`">{{ t('roleCard.refPreview') }}</span>
            <code v-if="row.name.trim()" class="rc-ref" :data-test="`role-card-ref-${i}`"
              v-text="refText(row.name.trim())" />
            <span v-else class="rc-ref-muted">{{ t('roleCard.refPreviewHint') }}</span>
            <el-button v-if="row.name.trim()" link type="primary"
              :data-test="`role-card-copy-${i}`" @click="copyRef(refText(row.name.trim()))">
              {{ t('roleCard.copy') }}
            </el-button>
          </div>
          <el-button link type="danger" :data-test="`role-card-remove-${i}`" @click="removeRole(i)">
            {{ t('roleCard.delete') }}
          </el-button>
        </div>
      </div>

      <div class="rc-actions">
        <el-button type="primary" data-test="role-card-save-btn" :loading="saving"
          :disabled="scriptVersion === null" @click="save">
          {{ t('roleCard.save') }}
        </el-button>
        <span v-if="scriptVersion === null" class="rc-actions-hint">{{ t('roleCard.noScriptYet') }}</span>
      </div>
    </template>
  </div>
</template>

<style scoped>
.role-card-page {
  padding: 16px;
}

.tp-page-head h2 {
  margin: 0 0 4px;
  font-size: 18px;
}

.tp-page-desc {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.rc-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.rc-section {
  margin-bottom: 16px;
}

.rc-section-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 4px;
}

.rc-section-title {
  font-size: 14px;
  font-weight: 600;
}

.rc-section-hint {
  margin: 0 0 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.rc-empty {
  padding: 8px 0;
}

.rc-role-card {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  flex-wrap: wrap;
  padding: 12px;
  margin-bottom: 8px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-fill-color-blank);
}

.rc-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.rc-field label,
.rc-field-caption {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.rc-field-grow {
  flex: 1;
  min-width: 200px;
}

.rc-ref {
  font-size: 13px;
  padding: 2px 6px;
  border-radius: 4px;
  background: var(--el-fill-color-light);
}

.rc-ref-muted {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}

.rc-ref-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 12px;
}

.rc-ref-item {
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}

.rc-ref-item code {
  display: inline-block;
  margin: 4px 0;
  font-size: 13px;
  padding: 2px 6px;
  border-radius: 4px;
  background: var(--el-fill-color-light);
}

.rc-ref-item p {
  margin: 4px 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.rc-ref-name {
  font-size: 13px;
  font-weight: 600;
}

.rc-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.rc-actions-hint {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}


.rc-alias-group {
  margin-top: 12px;
  padding: 10px 12px;
  border: 1px dashed var(--el-border-color);
  border-radius: 8px;
}
.rc-alias-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
  font-size: 13px;
}
.rc-alias-item code {
  font-size: 13px;
  padding: 2px 6px;
  border-radius: 4px;
  background: var(--el-fill-color-light);
}
.rc-alias-target {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.rc-alias-target code {
  font-size: 12px;
  padding: 2px 4px;
  border-radius: 3px;
  background: var(--el-fill-color-light);
}
.rc-alias-empty {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  padding: 4px 0;
}
</style>
