<!-- tp-sort-exempt: app packages are per-project uploaded artifacts held in the tens; the server returns the whole registry ordered by id DESC. -->
<template>
  <div class="page-root app-package-page" data-test="app-package-page">
    <div class="page-card">
      <!-- stage-2 (R21 bands): refresh is a view-state action — it rides
 the filter-bar trailing slot instead of the header band. FB-3 same-band
 merge (10-22 ruling): no row-level batch bar and zero condition
 controls, so the page-level variant action (create) rides the same
 trailing slot ahead of the refresh (variants first, view-state last);
 the emptied page-header row is removed as a whole shell (R23 - the
 page name rides the global top bar). The create entry keeps its
 ownership gate: tooltip-wrapped, disabled while no project context. -->
      <div class="filter-bar" data-test="package-filter-bar">
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
                data-test="package-create-btn"
                @click="openCreate"
              >
                {{ t('appauto.appPackage.create') }}
              </el-button>
            </span>
          </el-tooltip>
          <!-- R25 mode declaration (scope-aware single control, TB group): this page carries no
               tabs and exactly one table, so the page-level column-settings popover equals the
               table scope; show/hide, order, pinning and density persist per user via the piece. -->
          <ColumnSettings
            :columns="settingsRows"
            :density="colCfg.state.density"
            @toggle="colCfg.toggle"
            @move="colCfg.move"
            @density="colCfg.setDensity"
            @reset="colCfg.reset"
            @pin="colCfg.toggleFixed"
          />
          <el-button :icon="Refresh" :loading="loading" data-test="package-refresh" @click="load">
            {{ t('appauto.appPackage.refresh') }}
          </el-button>
        </div>
      </div>

      
      <el-alert type="info" show-icon :closable="false" class="usage-alert" data-test="package-usage-alert">
        <template #default>{{ t('appauto.appPackage.usageHint') }}</template>
      </el-alert>

      <div v-if="!loading && !projectId" class="empty-box" data-test="package-empty-no-project">
        <EmptyState
          :title="t('appauto.appPackage.noProjectTitle')"
          :hint="t('appauto.appPackage.noProjectHint')"
        />
      </div>

      <TableSkeleton v-else-if="loading" :cols="colCfg.visibleColumns.value.length + 1" data-test="package-skeleton" />
      <el-table v-else :data="rows" border row-key="id" :size="colCfg.state.density" class="data-table" data-test="package-table">
        <template #empty>
          <EmptyState :title="t('appauto.appPackage.emptyTitle')" :hint="t('appauto.appPackage.emptyHint')" />
        </template>
        <el-table-column
          v-for="col in colCfg.visibleColumns.value"
          :key="col.key"
          :prop="col.key"
          :fixed="col.fixed || false"
          :label="t(col.labelKey)"
          :width="col.width ?? undefined"
          :min-width="col.width ? undefined : APP_PACKAGE_FLEX_MIN[col.key]"
          :show-overflow-tooltip="APP_PACKAGE_TOOLTIP.has(col.key)"
          :data-test="`package-col-${col.key}`"
        >
          <template #default="{ row }">
            <template v-if="col.key === 'name'">
              <span data-test="package-name">{{ row.name }}</span>
            </template>
            <template v-else-if="col.key === 'packageName'">
              <span class="pkg-key" data-test="package-package-name">{{ row.packageName }}</span>
            </template>
            <template v-else-if="col.key === 'version'">
              <span data-test="package-version">{{ row.version || t('appauto.appPackage.versionUnset') }}</span>
            </template>
            <template v-else-if="col.key === 'platform'">{{ row.platformCode || '-' }}</template>
            <template v-else-if="col.key === 'artifact'">
              <template v-if="row.artifactKey">
                <el-tag size="small" type="success" data-test="package-artifact-uploaded">
                  {{ t('appauto.appPackage.artifactUploaded') }}
                </el-tag>
                <span class="artifact-meta" data-test="package-artifact-size">{{ fmtSize(row.artifactSizeBytes) }}</span>
                <span class="artifact-meta pkg-key" data-test="package-artifact-sha" :title="row.artifactSha256 || ''">
                  {{ (row.artifactSha256 || '').slice(0, 12) }}
                </span>
              </template>
              <el-tag v-else size="small" type="info" data-test="package-artifact-missing">
                {{ t('appauto.appPackage.artifactMissing') }}
              </el-tag>
            </template>
            <template v-else-if="col.key === 'source'">
              <span data-test="package-source">{{
                row.source === 'CI' ? t('appauto.appPackage.sourceCi') : t('appauto.appPackage.sourceManual')
              }}</span>
            </template>
            <template v-else-if="col.key === 'permRef'">{{ row.permissionRuleRef || '-' }}</template>
            <template v-else-if="col.key === 'updated'"><TimeCell :iso="row.updatedAt" /></template>
          </template>
        </el-table-column>
        <el-table-column :label="t('appauto.appPackage.colActions')" width="190" fixed="right">
          <template #default="{ row }">
            <el-button size="small" link :icon="Upload" data-test="package-upload-btn" @click="openUpload(row)">
              {{ t('appauto.appPackage.uploadAction') }}
            </el-button>
            <el-button
              size="small"
              link
              type="danger"
              :icon="Delete"
              data-test="package-delete-btn"
              @click="remove(row)"
            >
              {{ t('common.delete') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <p class="caliber-note">{{ t('appauto.appPackage.caliber') }}</p>

      
      <el-dialog
        v-model="createVisible"
        draggable
        :title="t('appauto.appPackage.createTitle')"
        width="520px"
        data-test="package-create-dialog"
      >
        <!-- ownership echo (iron rule 1): read-only, single-sourced from the
 header project context - the record lands on the visible segment -->
        <ProjectOwnerEcho />
        <el-form :model="form" label-position="top">
          <el-form-item :label="t('appauto.appPackage.formName')" required>
            <el-input
              v-model="form.name"
              maxlength="128"
              :placeholder="t('appauto.appPackage.formNamePlaceholder')"
              data-test="package-form-name"
            />
          </el-form-item>
          <el-form-item :label="t('appauto.appPackage.formPackageName')" required>
            <el-input
              v-model="form.packageName"
              maxlength="256"
              placeholder="com.example.app"
              data-test="package-form-package-name"
            />
            <div class="form-hint">{{ t('appauto.appPackage.formPackageNameHint') }}</div>
          </el-form-item>
          <el-form-item :label="t('appauto.appPackage.formVersion')">
            <el-input
              v-model="form.version"
              maxlength="64"
              :placeholder="t('appauto.appPackage.formVersionPlaceholder')"
              data-test="package-form-version"
            />
            <div class="form-hint">{{ t('appauto.appPackage.formVersionHint') }}</div>
          </el-form-item>
          <el-form-item :label="t('appauto.appPackage.formPlatform')">
            <el-select
              v-model="form.platformCode"
              clearable
              :placeholder="t('appauto.appPackage.formPlatformPlaceholder')"
              style="width: 100%"
              data-test="package-form-platform"
              filterable
            >
              <el-option v-for="p in platforms" :key="p.code" :label="p.name ? `${p.name}（${p.code}）` : p.code" :value="p.code" />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('appauto.appPackage.formPermRef')">
            <el-input
              v-model="form.permissionRuleRef"
              maxlength="128"
              :placeholder="t('appauto.appPackage.formPermRefPlaceholder')"
              data-test="package-form-perm-ref"
            />
            <div class="form-hint">{{ t('appauto.appPackage.formPermRefHint') }}</div>
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button data-test="package-create-cancel" @click="createVisible = false">{{ t('common.cancel') }}</el-button>
          <el-button type="primary" :loading="saving" data-test="package-create-save" @click="save">
            {{ t('common.save') }}
          </el-button>
        </template>
      </el-dialog>

      
      <el-dialog
        v-model="uploadVisible"
        draggable
        :title="t('appauto.appPackage.uploadTitle')"
        width="480px"
        data-test="package-upload-dialog"
      >
        <p class="upload-target" data-test="package-upload-target">
          {{ uploadTarget?.name }} · {{ uploadTarget?.packageName }}
          <template v-if="uploadTarget?.version">· {{ uploadTarget.version }}</template>
        </p>
        <input
          ref="fileInputRef"
          type="file"
          class="file-input"
          data-test="package-file-input"
          accept=".apk,.hap,.ipa,.zip,application/vnd.android.package-archive,application/octet-stream"
          @change="onFilePicked"
        />
        <div class="file-picker-row">
          <el-button :icon="Document" data-test="package-file-pick" @click="fileInputRef?.click()">
            {{ t('appauto.appPackage.uploadPick') }}
          </el-button>
          <span class="file-name" data-test="package-file-name">{{ pickedFile ? pickedFile.name : t('appauto.appPackage.uploadNoFile') }}</span>
        </div>
        <el-progress
          v-if="uploading || progress > 0"
          :percentage="progress"
          :status="progress === 100 && !uploading ? 'success' : undefined"
          data-test="package-upload-progress"
        />
        <p class="form-hint">{{ t('appauto.appPackage.uploadHint') }}</p>
        <template #footer>
          <el-button data-test="package-upload-cancel" :disabled="uploading" @click="uploadVisible = false">
            {{ t('common.cancel') }}
          </el-button>
          <el-button
            type="primary"
            :loading="uploading"
            :disabled="!pickedFile"
            data-test="package-upload-start"
            @click="doUpload"
          >
            {{ t('appauto.appPackage.uploadStart') }}
          </el-button>
        </template>
      </el-dialog>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'
import ColumnSettings from '@/components/ColumnSettings.vue'
import ProjectOwnerEcho from '@/components/ProjectOwnerEcho.vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Plus, Upload, Delete, Document } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import TimeCell from '@/components/TimeCell.vue'
import { bizErrorMsg } from '@/utils/bizError'
import {
  listAppPackages,
  createAppPackage,
  uploadAppPackage,
  deleteAppPackage,
  listAppautoPlatforms,
  type AppautoApp,
  type AppautoPlatform,
} from '@/api/appauto'

const { t } = useI18n()

// : the project segment rides the global header context (the page-level
// selector is gone); an empty context keeps the honest "pick a project"
// empty state, deep links and header switches both land through the composable.
const { projectId, followTick } = useProjectPageContext()
const loading = ref(false)
const saving = ref(false)
const rows = ref<AppautoApp[]>([])
const platforms = ref<AppautoPlatform[]>([])

// Column-configuration wiring (N-3 repayment, shared useColumnConfig piece):
// data columns flow through the visibleColumns loop so show/hide, order, pinning
// and density persist per user under tp-colcfg-app-package; the locked action
// column stays outside the loop. Widths/min-widths mirror the frozen layout.
const APP_PACKAGE_COLUMNS: ColumnDef[] = [
  { key: 'name', labelKey: 'appauto.appPackage.colName', width: null },
  { key: 'packageName', labelKey: 'appauto.appPackage.colPackageName', width: null },
  { key: 'version', labelKey: 'appauto.appPackage.colVersion', width: 130 },
  { key: 'platform', labelKey: 'appauto.appPackage.colPlatform', width: 110 },
  { key: 'artifact', labelKey: 'appauto.appPackage.colArtifact', width: null },
  { key: 'source', labelKey: 'appauto.appPackage.colSource', width: 100 },
  { key: 'permRef', labelKey: 'appauto.appPackage.colPermRef', width: null },
  { key: 'updated', labelKey: 'appauto.appPackage.colUpdated', width: 160 },
]
/** min-width fallbacks for the flexible (width-null) columns. */
const APP_PACKAGE_FLEX_MIN: Record<string, number> = { name: 140, packageName: 170, artifact: 220, permRef: 140 }
/** Text columns whose long values clip into a tooltip (frozen show-overflow set). */
const APP_PACKAGE_TOOLTIP = new Set(['name', 'packageName', 'version', 'platform', 'permRef'])
const colCfg = useColumnConfig('app-package', APP_PACKAGE_COLUMNS)
/** Popover rows (column state merged with labelKey for rendering). */
const settingsRows = computed(() =>
  colCfg.state.columns.map((c) => ({ ...c, labelKey: APP_PACKAGE_COLUMNS.find((d) => d.key === c.key)!.labelKey })),
)

const createVisible = ref(false)
const form = ref({ name: '', packageName: '', version: '', platformCode: '', permissionRuleRef: '' })

const uploadVisible = ref(false)
const uploadTarget = ref<AppautoApp | null>(null)
const fileInputRef = ref<HTMLInputElement>()
const pickedFile = ref<File | null>(null)
const uploading = ref(false)
const progress = ref(0)

function fmtSize(bytes?: number | null): string {
  if (bytes === null || bytes === undefined || bytes < 0) return '-'
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB']
  let v = bytes
  let i = -1
  do {
    v /= 1024
    i += 1
  } while (v >= 1024 && i < units.length - 1)
  return `${v.toFixed(1)} ${units[i]}`
}

async function load() {
  if (!projectId.value) {
    rows.value = []
    return
  }
  loading.value = true
  try {
    rows.value = await listAppPackages(projectId.value)
    if (!platforms.value.length) platforms.value = await listAppautoPlatforms()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('appauto.appPackage.loadFailed')))
    rows.value = []
  } finally {
    loading.value = false
  }
}

function openCreate() {
  form.value = { name: '', packageName: '', version: '', platformCode: '', permissionRuleRef: '' }
  createVisible.value = true
}

async function save() {
  if (!projectId.value) return
  if (!form.value.name.trim() || !form.value.packageName.trim()) {
    ElMessage.warning(t('appauto.appPackage.formRequired'))
    return
  }
  saving.value = true
  try {
    await createAppPackage({
      projectId: projectId.value,
      name: form.value.name.trim(),
      packageName: form.value.packageName.trim(),
      version: form.value.version.trim() || null,
      platformCode: form.value.platformCode || null,
      permissionRuleRef: form.value.permissionRuleRef.trim() || null,
    })
    ElMessage.success(t('appauto.appPackage.created'))
    createVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('appauto.appPackage.saveFailed')))
  } finally {
    saving.value = false
  }
}

function openUpload(row: AppautoApp) {
  uploadTarget.value = row
  pickedFile.value = null
  progress.value = 0
  uploadVisible.value = true
}

function onFilePicked(e: Event) {
  pickedFile.value = (e.target as HTMLInputElement).files?.[0] ?? null
  progress.value = 0
}

async function doUpload() {
  if (!uploadTarget.value || !pickedFile.value) {
    ElMessage.warning(t('appauto.appPackage.uploadNoFile'))
    return
  }
  uploading.value = true
  progress.value = 0
  try {
    await uploadAppPackage(uploadTarget.value.id, pickedFile.value, (p) => {
      progress.value = p
    })
    ElMessage.success(t('appauto.appPackage.uploadDone'))
    uploadVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('appauto.appPackage.uploadFailed')))
  } finally {
    uploading.value = false
  }
}

async function remove(row: AppautoApp) {
  try {
    await ElMessageBox.confirm(
      t('appauto.appPackage.deleteConfirm', { name: row.name }),
      t('appauto.appPackage.deleteTitle'),
      { type: 'warning', confirmButtonText: t('common.delete'), cancelButtonText: t('common.cancel') },
    )
  } catch {
    return
  }
  try {
    await deleteAppPackage(row.id)
    ElMessage.success(t('appauto.appPackage.deleted'))
    await load()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('appauto.appPackage.deleteFailed')))
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
.app-package-page {
  padding: 16px;
}
.usage-alert {
  margin-bottom: 12px;
}
.empty-box {
  padding: 32px 0;
}
.pkg-key {
  font-family: var(--font-mono, monospace);
}
.artifact-meta {
  margin-left: 8px;
  font-size: 12px;
  opacity: 0.8;
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
.file-input {
  display: none;
}
.file-picker-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 10px 0;
}
.file-name {
  font-size: 13px;
  word-break: break-all;
}
.upload-target {
  margin: 0 0 6px;
  font-size: 13px;
  opacity: 0.85;
}
</style>
