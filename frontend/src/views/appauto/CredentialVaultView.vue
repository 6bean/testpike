<!-- tp-sort-exempt: vault entries are operator-provisioned secrets (tens per project) rendered as a full resident list, not a paging ledger. -->
<template>
  <div class="page-root credential-vault-page" data-test="credential-vault-page">
    <div class="page-card">
      <!-- IA fix: screening controls live in the shared filter-bar row;
 the project segment rides the global header context. FB-3 same-band
 merge (10-22 ruling): no row-level batch bar and one condition
 control (<= threshold), so the page-level variant action (create)
 rides the .filter-bar-actions trailing slot ahead of the refresh
 (variants first, view-state last); the emptied page-header row is
 removed as a whole shell (R23 - the page name rides the global top
 bar). The create entry keeps its ownership gate: tooltip-wrapped,
 disabled while no project context. -->
      <div class="filter-bar" data-test="credential-filter-bar">
        <el-select
          v-model="enabledFilter"
          clearable
          :placeholder="t('appauto.credential.statusAll')"
          style="width: 130px"
          data-test="credential-status-select"
          @change="load"
        >
          <el-option :label="t('dict.appauto.credential.enabled')" :value="true" />
          <el-option :label="t('dict.appauto.credential.disabled')" :value="false" />
        </el-select>
        <div class="filter-bar-actions">
          <el-tooltip
            :content="t('components.ownerEcho.pickInHeader')"
            placement="bottom"
            :disabled="!!projectId"
          >
            <span>
              <el-button
                type="primary"
                :icon="Plus"
                :disabled="!projectId"
                data-test="credential-create-btn"
                @click="openCreate"
              >
                {{ t('appauto.credential.create') }}
              </el-button>
            </span>
          </el-tooltip>
          <!-- R25 mode declaration (scope-aware single control, TB group): the page has no
               tabs and exactly one page-surface table (the vault list below), so this
               page-level column-settings control equals the vault scope: show/hide, order,
               pinning and density persist per user through the shared useColumnConfig piece. -->
          <ColumnSettings
            :columns="settingsRows"
            :density="colCfg.state.density"
            @toggle="colCfg.toggle"
            @move="colCfg.move"
            @density="colCfg.setDensity"
            @reset="colCfg.reset"
            @pin="colCfg.toggleFixed"
          />
          <!-- refresh stays a view-state action in the same trailing slot, behind settings -->
          <el-button :icon="Refresh" :loading="loading" data-test="credential-refresh" @click="load">
            {{ t('appauto.credential.refresh') }}
          </el-button>
        </div>
      </div>

   
      <el-alert
        type="info"
        show-icon
        :closable="false"
        class="usage-alert"
        data-test="credential-usage-alert"
      >
        <template #default>
          <span>{{ t('appauto.credential.usageHintPrefix') }}</span>
          <code class="usage-code">{{ usageCodeText }}</code>
          <span>{{ t('appauto.credential.usageHintSuffix') }}</span>
        </template>
      </el-alert>

      <div v-if="!loading && !projectId" class="empty-box" data-test="credential-empty-no-project">
        <EmptyState
          :title="t('appauto.credential.noProjectTitle')"
          :hint="t('appauto.credential.noProjectHint')"
        />
      </div>

      <TableSkeleton v-else-if="loading" :cols="colCfg.visibleColumns.value.length + 1" data-test="credential-skeleton" />
      <el-table v-else :data="rows" border row-key="id" :size="colCfg.state.density" class="data-table" data-test="credential-table">
        <template #empty>
          <EmptyState
            :filtered="enabledFilter !== ''"
            :title="enabledFilter !== '' ? t('common.emptyFilteredResult') : t('appauto.credential.emptyTitle')"
            :hint="enabledFilter !== '' ? t('common.emptyFilteredHint') : t('appauto.credential.emptyHint')"
          />
        </template>
        <el-table-column
          v-for="col in colCfg.visibleColumns.value"
          :key="col.key"
          :prop="col.key"
          :fixed="col.fixed || false"
          :label="t(col.labelKey)"
          :width="col.width ?? undefined"
          :min-width="col.width ? undefined : CREDENTIAL_FLEX_MIN[col.key]"
          :show-overflow-tooltip="CREDENTIAL_TOOLTIP.has(col.key)"
          :data-test="`credential-col-${col.key}`"
        >
          <template #default="{ row }">
            <template v-if="col.key === 'name'">
              <span data-test="credential-name">{{ row.name }}</span>
            </template>
            <template v-else-if="col.key === 'credKey'">
              <span class="cred-key" data-test="credential-key">{{ row.credKey }}</span>
            </template>
            <template v-else-if="col.key === 'secret'">
              <!-- never echoed: only the masked string produced by core renders, no plaintext surface -->
              <span data-test="credential-mask">{{ row.secretMask }}</span>
            </template>
            <template v-else-if="col.key === 'status'">
              <el-switch
                :model-value="row.status === 1"
                :loading="busyId === row.id"
                data-test="credential-enabled-switch"
                @change="toggleEnabled(row, $event as boolean)"
              />
            </template>
            <template v-else-if="col.key === 'testGrade'">
              <el-tag
                v-if="row.testGrade"
                size="small"
                :type="gradeTagType(row.testGrade)"
                data-test="credential-grade-badge"
              >{{ GRADE_LABEL_KEY[row.testGrade] ? t('appauto.credential.' + GRADE_LABEL_KEY[row.testGrade]) : row.testGrade }}</el-tag>
              <span v-else class="grade-none">{{ t('appauto.credential.gradeNeverTested') }}</span>
            </template>
            <template v-else-if="col.key === 'updated'">
              <TimeCell :iso="row.updatedAt" />
            </template>
            <template v-else>{{ row.remark || '-' }}</template>
          </template>
        </el-table-column>
        <el-table-column :label="t('appauto.credential.colActions')" width="310" fixed="right">
          <template #default="{ row }">
            <el-button size="small" link :icon="Edit" data-test="credential-edit-btn" @click="openEdit(row)">
              {{ t('common.edit') }}
            </el-button>
            <el-button size="small" link type="primary" data-test="credential-test-btn" @click="openTest(row)">
              {{ t('appauto.credential.testNow') }}
            </el-button>
            <el-button size="small" link type="primary" data-test="credential-history-btn" @click="openHistory(row)">
              {{ t('appauto.credential.history') }}
            </el-button>
            <el-button size="small" link type="danger" :icon="Delete" data-test="credential-delete-btn" @click="remove(row)">
              {{ t('common.delete') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <p class="caliber-note">{{ t('appauto.credential.caliber') }}</p>

      
      <el-dialog
        v-model="testVisible"
        :title="t('appauto.credential.testTitle', { name: testRow?.name || '' })"
        width="640px"
        append-to-body
        data-test="credential-test-dialog"
      >
        <p class="test-hint" data-test="credential-test-hint">{{ t('appauto.credential.testHint') }}</p>
        <el-form label-position="top" class="test-form">
          <el-form-item :label="t('appauto.credential.testUrl')">
            <el-input v-model="testForm.url" :placeholder="t('appauto.credential.testUrlPlaceholder')" data-test="credential-test-url" clearable />
          </el-form-item>
          <el-form-item :label="t('appauto.credential.testMethod')">
            <el-select v-model="testForm.method" data-test="credential-test-method" style="width: 160px">
              <el-option label="GET" value="GET" />
              <el-option label="POST" value="POST" />
              <el-option label="PUT" value="PUT" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="testForm.method !== 'GET'" :label="t('appauto.credential.testBody')">
            <el-input
              v-model="testForm.bodyTemplate"
              type="textarea"
              :rows="4"
              data-test="credential-test-body"
              :placeholder="t('appauto.credential.testBodyPlaceholder')"
            />
          </el-form-item>
          <!-- Request-header rows: repeatable name/value pairs. Values may carry the vault placeholder, resolved server-side right before dispatch, so even GET requests send the credential. -->
          <el-form-item :label="t('appauto.credential.testHeaders')">
            <div class="test-header-rows" data-test="credential-test-headers">
              <div v-for="(h, i) in testForm.headers" :key="i" class="test-header-row">
                <el-input v-model="h.name" :placeholder="t('appauto.credential.testHeaderName')" data-test="credential-test-header-name" />
                <el-input v-model="h.value" :placeholder="t('appauto.credential.testHeaderValue')" data-test="credential-test-header-value" />
                <el-button
                  link
                  :icon="Delete"
                  :aria-label="t('appauto.credential.testHeaderRemove', { index: i + 1 })"
                  :data-test="`credential-test-header-remove-${i + 1}`"
                  @click="testForm.headers.splice(i, 1)"
                />
              </div>
              <div class="test-header-actions">
                <el-button size="small" :icon="Plus" data-test="credential-test-header-add" @click="addHeaderRow">
                  {{ t('appauto.credential.testHeaderAdd') }}
                </el-button>
                <el-dropdown trigger="click" data-test="credential-test-prefill" @command="prefillCredentialHeader">
                  <el-button size="small" type="primary" plain>
                    {{ t('appauto.credential.testPrefill') }}
                  </el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item command="bearer">{{ t('appauto.credential.testPrefillBearer') }}</el-dropdown-item>
                      <el-dropdown-item command="cookie">{{ t('appauto.credential.testPrefillCookie') }}</el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </div>
            </div>
          </el-form-item>
        </el-form>
        <div class="test-run-line">
          <el-button type="primary" :loading="testRunning" data-test="credential-test-run" @click="runTest">
            {{ t('appauto.credential.testRun') }}
          </el-button>
          <el-tag v-if="testGradeText" :type="testGradeTag" data-test="credential-test-grade">{{ testGradeText }}</el-tag>
        </div>
        <p v-if="testOutcomeNote" class="form-hint" data-test="credential-test-note">{{ testOutcomeNote }}</p>
        <TaskTailDrawer v-if="testTailOpen" :events="testEvents" :live="testRunning" :doors="testDoors" />
        <template #footer>
          <el-button data-test="credential-test-close" @click="testVisible = false">{{ t('common.close') }}</el-button>
        </template>
      </el-dialog>

      <!-- R67 revisit door : list this credential's historical test runs and
           replay any run's tail through the SHARED base (no second chain). -->
      <RunHistoryDialog
        v-model="histVisible"
        :load-runs="loadHistRuns"
        :fetch-frames="fetchHistFrames"
        :doors="histDoors"
        retention-param="ai.stream.event-retention-days"
      />

      
      <el-dialog
        v-model="dialogVisible"
        :title="editing ? t('appauto.credential.editTitle') : t('appauto.credential.createTitle')"
        width="520px"
        data-test="credential-dialog"
      >
        <!-- ownership echo (iron rule 1): read-only, single-sourced from the
 header project context - the record lands on the visible segment -->
        <ProjectOwnerEcho />
        <el-form :model="form" label-position="top">
          <el-form-item :label="t('appauto.credential.formName')" required>
            <el-input
              v-model="form.name"
              maxlength="128"
              :placeholder="t('appauto.credential.formNamePlaceholder')"
              data-test="credential-form-name"
            />
          </el-form-item>
          <el-form-item :label="t('appauto.credential.formKey')" required>
            <el-input
              v-model="form.credKey"
              :disabled="!!editing"
              maxlength="64"
              placeholder="app_account"
              data-test="credential-form-key"
            />
            <div class="form-hint">{{ t('appauto.credential.formKeyHint') }}</div>
          </el-form-item>
          <el-form-item :label="t('appauto.credential.formSecret')" :required="!editing">
            <el-input
              v-model="form.secret"
              type="password"
              show-password
              autocomplete="new-password"
              :placeholder="editing ? t('appauto.credential.formSecretKeepHint') : t('appauto.credential.formSecretPlaceholder')"
              data-test="credential-form-secret"
            />
            <div v-if="editing" class="form-hint">{{ t('appauto.credential.formSecretEditHint') }}</div>
          </el-form-item>
          <el-form-item :label="t('appauto.credential.formRemark')">
            <el-input
              v-model="form.remark"
              type="textarea"
              :rows="2"
              maxlength="512"
              :placeholder="t('appauto.credential.formRemarkPlaceholder')"
              data-test="credential-form-remark"
            />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button data-test="credential-dialog-cancel" @click="dialogVisible = false">
            {{ t('common.cancel') }}
          </el-button>
          <el-button type="primary" :loading="saving" data-test="credential-dialog-save" @click="save">
            {{ t('common.save') }}
          </el-button>
        </template>
      </el-dialog>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import ProjectOwnerEcho from '@/components/ProjectOwnerEcho.vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Plus, Edit, Delete } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import TimeCell from '@/components/TimeCell.vue'
import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'
import ColumnSettings from '@/components/ColumnSettings.vue'
import { bizErrorMsg } from '@/utils/bizError'
import {
  listCredentials,
  createCredential,
  updateCredential,
  setCredentialEnabled,
  deleteCredential,
  type AppautoCredential,
  testCredential,
  listCredentialTestEvents,
} from '@/api/appauto'
import TaskTailDrawer from '@/components/TaskTailDrawer.vue'
import RunHistoryDialog from '@/components/RunHistoryDialog.vue'
import { credentialTrailDoors, listCredentialTestRuns } from '@/api/taskEvents'
import { useTestRunTail } from '@/composables/useTestRunTail'

const { t } = useI18n()

const usageCodeText = computed(() => t('appauto.credential.usageCode'))

// Column-configuration wiring (N-3 repayment, shared useColumnConfig piece):
// the six data columns flow through the visibleColumns loop so show/hide,
// order, pinning and density persist per user under viewKey 'credential-vault';
// the locked action column stays outside the loop (skeleton adds one slot).
const CREDENTIAL_COLUMNS: ColumnDef[] = [
  { key: 'name', labelKey: 'appauto.credential.colName', width: null },
  { key: 'credKey', labelKey: 'appauto.credential.colKey', width: null },
  { key: 'secret', labelKey: 'appauto.credential.colSecret', width: 120 },
  { key: 'status', labelKey: 'appauto.credential.colStatus', width: 110 },
  // Evidence-grade badge column for the last test run; reads the same persisted value as the dialog verdict row.
  { key: 'testGrade', labelKey: 'appauto.credential.testGradeLabel', width: 150 },
  { key: 'remark', labelKey: 'appauto.credential.colRemark', width: null },
  { key: 'updated', labelKey: 'appauto.credential.colUpdated', width: 160 },
]
// flexible columns keep their original min-widths; tooltip ellipsis rides the two prose columns
const CREDENTIAL_FLEX_MIN: Record<string, number> = { name: 150, credKey: 150, remark: 170 }
const CREDENTIAL_TOOLTIP = new Set(['name', 'remark'])
const colCfg = useColumnConfig('credential-vault', CREDENTIAL_COLUMNS)
const settingsRows = computed(() =>
  colCfg.state.columns.map((c) => ({ ...c, labelKey: CREDENTIAL_COLUMNS.find((d) => d.key === c.key)!.labelKey })),
)

// : the project segment rides the global header context (the page-level
// selector is gone); an empty context keeps the honest "pick a project"
// empty state, deep links and header switches both land through the composable.
const { projectId, followTick } = useProjectPageContext()
const enabledFilter = ref<'' | boolean>('')
const loading = ref(false)
const saving = ref(false)
const busyId = ref<number | null>(null)
const rows = ref<AppautoCredential[]>([])
const dialogVisible = ref(false)
const editing = ref<AppautoCredential | null>(null)
const form = ref({ name: '', credKey: '', secret: '', remark: '' })

async function load() {
  if (!projectId.value) {
    rows.value = []
    return
  }
  loading.value = true
  try {
    const enabledOnly = enabledFilter.value === '' ? undefined : enabledFilter.value
    rows.value = await listCredentials(projectId.value, enabledOnly)
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('appauto.credential.loadFailed')))
    rows.value = []
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  form.value = { name: '', credKey: '', secret: '', remark: '' }
  dialogVisible.value = true
}

function openEdit(row: AppautoCredential) {
  editing.value = row
  form.value = { name: row.name, credKey: row.credKey, secret: row.secretMask, remark: row.remark || '' }
  dialogVisible.value = true
}

async function save() {
  if (!projectId.value) return
  if (!form.value.name.trim() || !form.value.credKey.trim()) {
    ElMessage.warning(t('appauto.credential.formRequired'))
    return
  }
  if (!editing.value && !form.value.secret) {
    ElMessage.warning(t('appauto.credential.secretRequired'))
    return
  }
  saving.value = true
  try {
    const payload = {
      projectId: projectId.value,
      name: form.value.name.trim(),
      credKey: form.value.credKey.trim(),
      secret: form.value.secret,
      remark: form.value.remark.trim() || null,
    }
    if (editing.value) {
      await updateCredential(editing.value.id, payload)
      ElMessage.success(t('appauto.credential.updated'))
    } else {
      await createCredential(payload)
      ElMessage.success(t('appauto.credential.created'))
    }
    dialogVisible.value = false
    form.value = { name: '', credKey: '', secret: '', remark: '' }
    await load()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('appauto.credential.saveFailed')))
  } finally {
    saving.value = false
  }
}

async function toggleEnabled(row: AppautoCredential, enabled: boolean) {
  busyId.value = row.id
  try {
    await setCredentialEnabled(row.id, enabled)
    ElMessage.success(enabled ? t('appauto.credential.enabledDone') : t('appauto.credential.disabledDone'))
    await load()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('appauto.credential.toggleFailed')))
    await load()
  } finally {
    busyId.value = null
  }
}

const testVisible = ref(false)
const testRow = ref<AppautoCredential | null>(null)
const testForm = reactive({
  url: '', method: 'POST', bodyTemplate: '{"username":"demo","password":"{{CRED:key}}"}',
  // Request-header rows: values support the vault placeholder; the server injects secrets just before dispatch.
  headers: [] as { name: string; value: string }[],
})
const testOutcome = ref<{ grade?: string | null; gradeHuman?: string | null; note?: string | null } | null>(null)
const GRADE_LABEL_KEY: Record<string, string> = {
  VERIFIED: 'gradeVERIFIED', UNVERIFIED: 'gradeUNVERIFIED', EXPIRED: 'gradeEXPIRED', UNKNOWN: 'gradeUNKNOWN',
}
type GradeTag = 'success' | 'danger' | 'warning' | 'info'
function gradeTagType(grade?: string | null): GradeTag {
  if (grade === 'VERIFIED') return 'success'
  if (grade === 'EXPIRED') return 'danger'
  if (grade === 'UNKNOWN') return 'warning'
  return 'info'
}
const testGradeText = computed(() => {
  const g = testOutcome.value?.grade
  if (!g) return ''
  return testOutcome.value?.gradeHuman || (GRADE_LABEL_KEY[g] ? t(`appauto.credential.${GRADE_LABEL_KEY[g]}`) : g)
})
const testGradeTag = computed<GradeTag>(() => gradeTagType(testOutcome.value?.grade))
const testOutcomeNote = computed(() => testOutcome.value?.note || '')

function addHeaderRow() {
  testForm.headers.push({ name: '', value: '' })
}

// One-click "test with this credential" prefills the reference header row for the credential's kind; the user may still edit it, and the real value is only injected server-side.
function prefillCredentialHeader(shape: 'bearer' | 'cookie') {
  const key = testRow.value?.credKey
  if (!key) return
  testForm.headers = shape === 'bearer'
    ? [{ name: 'Authorization', value: `Bearer {{CRED:${key}}}` }]
    : [{ name: 'Cookie', value: `{{CRED:${key}}}` }]
}

const {
  events: testEvents, running: testRunning, drawerOpen: testTailOpen, openRun: testOpenRun,
} = useTestRunTail(async (runToken, cursor) =>
  (await listCredentialTestEvents(testRow.value!.id, runToken, cursor)).map((row) => ({
    cursor: row.cursor, level: row.level, summary: row.summary, detail: row.detail, landedAt: row.landedAt,
  })))
/** gated read doors over the credential-test trail */
const testDoors = computed(() => (testRow.value ? credentialTrailDoors(testRow.value.id) : undefined))

// ---------- R67 revisit door : run history + replay over the shared base ----------
const histVisible = ref(false)
const histRow = ref<AppautoCredential | null>(null)
const histDoors = computed(() => (histRow.value ? credentialTrailDoors(histRow.value.id) : undefined))
function openHistory(row: AppautoCredential) {
  histRow.value = row
  histVisible.value = true
}
async function loadHistRuns() {
  if (!histRow.value) return []
  return listCredentialTestRuns(histRow.value.id)
}
async function fetchHistFrames(runToken: string, cursor: number) {
  if (!histRow.value) return []
  const rows = await listCredentialTestEvents(histRow.value.id, runToken, cursor)
  return rows.map((row) => ({
    cursor: row.cursor, level: row.level, summary: row.summary, detail: row.detail, landedAt: row.landedAt,
  }))
}

function openTest(row: AppautoCredential) {
  testRow.value = row
  testForm.url = ''
  testForm.method = 'POST'
  testForm.bodyTemplate = '{"username":"demo","password":"{{CRED:' + row.credKey + '}}"}'
  testForm.headers = []
  testOutcome.value = null
  testEvents.value = []
  testTailOpen.value = false
  testVisible.value = true
}

async function runTest() {
  if (!testRow.value) {
    return
  }
  try {
    const headers = testForm.headers.filter((h) => h.name.trim() && h.value.trim())
    const outcome = await testCredential(testRow.value.id, {
      url: testForm.url, method: testForm.method, bodyTemplate: testForm.bodyTemplate, headers,
    })
    testOutcome.value = outcome
    await testOpenRun(outcome)
    // The list verdict badge reads the persisted evidence grade; the list re-reads silently after the run.
    await load()
  } catch (error) {
    ElMessage.error(bizErrorMsg(error, t('appauto.credential.testFailed')))
  }
}

async function remove(row: AppautoCredential) {
  try {
    await ElMessageBox.confirm(
      t('appauto.credential.deleteConfirm', { name: row.name }),
      t('appauto.credential.deleteTitle'),
      { type: 'warning', confirmButtonText: t('common.delete'), cancelButtonText: t('common.cancel') },
    )
  } catch {
    return
  }
  busyId.value = row.id
  try {
    await deleteCredential(row.id)
    ElMessage.success(t('appauto.credential.deleted'))
    await load()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('appauto.credential.deleteFailed')))
  } finally {
    busyId.value = null
  }
}

// : a header context switch rewrites the project segment — reset in-page
// screening where the removed selector used to and reload from scratch.
watch(followTick, () => {
  void load()
})

onMounted(() => {
  void load()
})

</script>

<style scoped>
.credential-vault-page {
  padding: 16px;
}
.usage-alert {
  margin-bottom: 12px;
}
.usage-code {
  margin: 0 4px;
  padding: 4px 8px;
  border-radius: 4px;
  font-family: var(--font-mono, monospace);
}
.empty-box {
  padding: 32px 0;
}
.cred-key {
  font-family: var(--font-mono, monospace);
}
.caliber-note {
  margin-top: 10px;
  font-size: 12px;
  opacity: 0.75;
}
.form-hint {
  font-size: 12px;
  opacity: 0.75;
  margin-top: 4px;
}
/* Test-door reference surface: header rows stack full-width, actions share one line */
.test-header-rows {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}
.test-header-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.test-header-row .el-input:first-child {
  flex: 0 0 200px;
}
.test-header-row .el-input {
  flex: 1;
}
.test-header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.test-run-line {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 4px;
}
.grade-none {
  opacity: 0.6;
  font-size: 12px;
}
</style>
