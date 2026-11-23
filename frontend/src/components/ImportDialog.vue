<template>
  <el-dialog draggable
    v-model="visible"
    :title="importType === 'case' ? t('importDialog.caseTitle') : t('importDialog.reqTitle')"
    width="640px"
    destroy-on-close
    :close-on-click-modal="false"
    data-test="import-dialog"
  >
    
    <div class="import-tip">
      <el-icon color="var(--tp-primary)"><InfoFilled /></el-icon>
      <span>{{ t('importDialog.tip') }}</span>
      <el-button link type="primary" :loading="downloading" data-test="import-template-download" @click="downloadTemplate">
        {{ t('importDialog.downloadTemplate') }}
      </el-button>
    </div>

    
    <el-upload
      drag
      :auto-upload="false"
      :accept="'.xlsx'"
      :show-file-list="false"
      :on-change="onFileChange"
      :on-remove="onFileRemove"
      data-test="import-upload"
    >
      <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
      <div class="el-upload__text">{{ t('importDialog.dropHint') }}</div>
      <template #tip>
        <div class="el-upload__tip">{{ t('importDialog.fileHint') }}</div>
      </template>
    </el-upload>
    <div v-if="file" class="import-file-echo" data-test="import-file-echo">
      <el-icon><Document /></el-icon>
      <span class="mono">{{ file.name }}</span>
    </div>

    
    <div v-if="importType === 'case'" class="import-status-row">
      <span class="label">{{ t('importDialog.targetStatus') }}：</span>
      <el-radio-group v-model="targetStatus" data-test="import-target-status">
        <el-radio-button :value="1">{{ t('importDialog.statusReview') }}</el-radio-button>
        <el-radio-button :value="3">{{ t('importDialog.statusPassed') }}</el-radio-button>
      </el-radio-group>
    </div>

    
    <div v-if="uploading" class="import-progress-row" data-test="import-progress">
      <el-progress :percentage="progress" :stroke-width="10" />
      <span class="text-secondary">{{ t('importDialog.uploading') }} {{ progress }}%</span>
    </div>

    
    <el-alert v-if="error" type="error" :closable="false" class="import-error" data-test="import-error">
      {{ error }}
    </el-alert>

    
    <el-alert
      v-if="result && result.failCount > 0 && importType === 'case'"
      type="warning"
      :closable="false"
      class="import-error"
      data-test="import-fix-hint"
    >
      {{ t('importDialog.fixHint') }}
    </el-alert>

    
    <div v-if="result" class="import-result" data-test="import-result">
      <div class="result-summary">
        <el-tag type="success" data-test="import-success-count">
          {{ t('importDialog.successCount', { n: result.successCount }) }}
        </el-tag>
        <el-tag v-if="result.failCount > 0" type="danger" data-test="import-fail-count">
          {{ t('importDialog.failCount', { n: result.failCount }) }}
        </el-tag>
        <el-tag v-else type="success" data-test="import-all-ok">
          {{ t('importDialog.allOk') }}
        </el-tag>
      </div>
      <div v-if="failedItems.length" class="fail-list" data-test="import-fail-list">
        <div class="fail-title">{{ t('importDialog.failDetail') }}</div>
        <div v-for="item in failedItems" :key="item.rowNumber" class="fail-item" data-test="import-fail-item">
          <span class="mono fail-row">{{ t('importDialog.row', { n: item.rowNumber }) }}</span>
          <span class="fail-reason">{{ item.reason || t('importDialog.unknownReason') }}</span>
        </div>
      </div>
    </div>

    <template #footer>
      <el-button @click="visible = false">{{ t('common.cancel') }}</el-button>
      <el-button
        v-if="result && result.failCount > 0 && importType === 'case'"
        type="warning"
        :loading="uploading"
        data-test="import-retry"
        @click="doRetry"
      >
        {{ t('importDialog.retryFailed') }}
      </el-button>
      <el-button
        v-else
        type="primary"
        :loading="uploading"
        :disabled="!file"
        data-test="import-submit"
        @click="doImport"
      >
        {{ t('importDialog.startImport') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, type UploadFile, type UploadFiles } from 'element-plus'
import { Document, InfoFilled, UploadFilled } from '@element-plus/icons-vue'
import {
  downloadImportTemplate, importCases, importRequirements, retryFailedCases,
  type ImportItem, type ImportResult,
} from '@/api/import'

const { t } = useI18n()

const props = defineProps<{
  modelValue: boolean
  projectId: number | null
  
  importType: 'case' | 'requirement'
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  
  (e: 'imported'): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})

const file = ref<File | null>(null)
const targetStatus = ref(1)
const uploading = ref(false)
const progress = ref(0)
const result = ref<ImportResult | null>(null)
const error = ref('')
const downloading = ref(false)

const failedItems = computed<ImportItem[]>(() => (result.value?.items ?? []).filter((i) => !i.success))

watch(visible, (v) => {
  if (!v) {
    file.value = null
    uploading.value = false
    progress.value = 0
    result.value = null
    error.value = ''
  }
})

function onFileChange(uploadFile: UploadFile, uploadFiles: UploadFiles): void {
  if (uploadFiles.length > 1) {
    uploadFiles.splice(0, uploadFiles.length - 1)
  }
  file.value = uploadFile.raw ?? null
  error.value = ''
}

function onFileRemove(): void {
  file.value = null
  result.value = null
  error.value = ''
}

async function downloadTemplate(): Promise<void> {
  downloading.value = true
  try {
    await downloadImportTemplate(props.importType === 'case' ? 'cases' : 'requirements')
    ElMessage.success(t('importDialog.templateDownloaded'))
  } catch (e) {
    ElMessage.error(t('importDialog.templateFail', { msg: (e as Error).message || t('components.networkError') }))
  } finally {
    downloading.value = false
  }
}

async function doImport(): Promise<void> {
  if (!file.value) {
    ElMessage.warning(t('importDialog.fileRequired'))
    return
  }
  if (!props.projectId) {
    ElMessage.warning(t('importDialog.projectRequired'))
    return
  }
  uploading.value = true
  progress.value = 0
  error.value = ''
  try {
    const res = props.importType === 'case'
      ? await importCases(props.projectId, file.value, targetStatus.value, (p) => { progress.value = p })
      : await importRequirements(props.projectId, file.value, (p) => { progress.value = p })
    result.value = res
    if (res.failCount === 0) {
      ElMessage.success(t('importDialog.importSuccess', { n: res.successCount }))
      emit('imported')
      visible.value = false
    } else {
      ElMessage.warning(t('importDialog.importPartial', { ok: res.successCount, fail: res.failCount }))
    }
  } catch (e) {
    error.value = (e as Error).message || t('importDialog.importFailUnknown')
  } finally {
    uploading.value = false
  }
}

async function doRetry(): Promise<void> {
  if (!file.value || !result.value) return
  const retryRows = failedItems.value.map((i) => i.rowNumber)
  if (!retryRows.length || !props.projectId) return
  uploading.value = true
  progress.value = 0
  error.value = ''
  try {
    const res = await retryFailedCases(props.projectId, file.value, retryRows, targetStatus.value, (p) => { progress.value = p })
    result.value = res
    if (res.failCount === 0) {
      ElMessage.success(t('importDialog.retrySuccess', { n: res.successCount }))
      emit('imported')
      visible.value = false
    } else {
      ElMessage.warning(t('importDialog.importPartial', { ok: res.successCount, fail: res.failCount }))
    }
  } catch (e) {
    error.value = (e as Error).message || t('importDialog.importFailUnknown')
  } finally {
    uploading.value = false
  }
}
</script>

<style scoped>
.import-tip {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 12px;
  font-size: 13px;
  color: var(--tp-text-secondary);
}
.import-status-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
  font-size: 13px;
}
.import-status-row .label {
  color: var(--tp-text-secondary);
}
.import-progress-row {
  margin-top: 12px;
}
.import-progress-row .text-secondary {
  font-size: 12px;
}
.import-error {
  margin-top: 12px;
}
.import-result {
  margin-top: 12px;
  border: 1px solid var(--tp-border);
  border-radius: 6px;
  padding: 10px 12px;
}
.result-summary {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.fail-list {
  margin-top: 10px;
  max-height: 200px;
  overflow-y: auto;
}
.fail-title {
  font-size: 13px;
  color: var(--tp-text-secondary);
  margin-bottom: 6px;
}
.fail-item {
  display: flex;
  gap: 8px;
  font-size: 13px;
  padding: 4px 0;
  border-bottom: 1px dashed var(--tp-border);
}
.fail-row {
  flex-shrink: 0;
  color: var(--tp-danger);
}
.fail-reason {
  color: var(--tp-text);
}

.import-file-echo {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
  padding: 6px 10px;
  border: 1px solid var(--tp-border-color, #e8eaef);
  border-radius: 8px;
  font-size: 13px;
  color: var(--tp-text-primary);
  background: var(--tp-fill-color-light, rgba(127, 127, 127, 0.06));
}
</style>
