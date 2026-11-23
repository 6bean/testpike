<template>
  <el-dialog
    draggable
    :model-value="visible"
    :title="title"
    width="640px"
    destroy-on-close
    :close-on-click-modal="false"
    data-test="file-upload-dialog"
    @update:model-value="visible = $event"
  >
    <div class="file-upload-body">
      
      <slot name="extra" />

      
      <div class="file-upload-drop-wrap" data-test="file-upload-drop">
        <el-upload
          drag
          :auto-upload="false"
          :accept="accept"
          :show-file-list="false"
          :on-change="onFileChange"
        >
          <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
          <div class="el-upload__text">{{ t('uploadDialog.dropHint') }}</div>
          <template #tip>
            <div class="el-upload__tip">
              <slot name="tip">{{ tip }}</slot>
            </div>
          </template>
        </el-upload>
      </div>

      
      <el-alert v-if="error" type="error" :closable="false" class="file-upload-error" data-test="file-upload-error">
        {{ error }}
      </el-alert>

      
      <div v-if="uploading" class="file-upload-progress-row" data-test="file-upload-progress">
        <el-progress :percentage="progress" :stroke-width="10" />
        <span class="text-secondary">{{ t('uploadDialog.uploading') }} {{ progress }}%</span>
      </div>
    </div>

    <template #footer>
      <el-button @click="visible = false">{{ t('common.cancel') }}</el-button>
      <el-button
        type="primary"
        :loading="uploading"
        :disabled="!file"
        data-test="file-upload-submit"
        @click="submit"
      >{{ submitText || t('components.uploadSubmit') }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">

import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, type UploadFile, type UploadFiles } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'

type ProgressFn = (percent: number) => void

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    title: string
    
    accept: string
    
    maxSizeMb?: number
    
    tip?: string
    submitText?: string
    
    uploadFn: (file: File, onProgress: ProgressFn) => Promise<unknown>
  }>(),
  { maxSizeMb: 50, tip: '' },
)

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  
  (e: 'uploaded', res: unknown): void
}>()

const { t } = useI18n()

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})

const file = ref<File | null>(null)
const uploading = ref(false)
const progress = ref(0)
const error = ref('')

watch(visible, (v) => {
  if (!v) {
    file.value = null
    uploading.value = false
    progress.value = 0
    error.value = ''
  }
})


function rejectedByFormat(f: File): boolean {
  const lower = f.name.toLowerCase()
  return !props.accept
    .split(',')
    .map((s) => s.trim().toLowerCase())
    .filter(Boolean)
    .some((ext) => lower.endsWith(ext))
}

function onFileChange(uploadFile: UploadFile, uploadFiles: UploadFiles): void {
  if (uploadFiles.length > 1) {
    uploadFiles.splice(0, uploadFiles.length - 1)
  }
  const f = uploadFile.raw ?? null
  error.value = ''
  file.value = null
  if (!f) return
  if (rejectedByFormat(f)) {
    const dot = f.name.lastIndexOf('.')
    const ext = dot >= 0 ? f.name.slice(dot).toLowerCase() : f.name
    error.value = t('uploadDialog.typeRejected', { ext })
    return
  }
  if (f.size > props.maxSizeMb * 1024 * 1024) {
    error.value = t('uploadDialog.sizeRejected', { mb: props.maxSizeMb })
    return
  }
  file.value = f
}

async function submit(): Promise<void> {
  if (!file.value) return
  uploading.value = true
  progress.value = 0
  error.value = ''
  try {
    const res = await props.uploadFn(file.value, (p) => { progress.value = p })
    visible.value = false
    emit('uploaded', res)
  } catch (e) {
    const msg = (e as Error).message || t('uploadDialog.failUnknown')
    error.value = msg
    ElMessage.error(msg)
  } finally {
    uploading.value = false
  }
}
</script>

<style scoped>
/* Vertical rhythm inside the upload dialog is theme-token driven, not per-page:
   the extra-form row, the drop zone, the inline error and the progress row are
   adjacent blocks, so the shared band gap token carries every gap between them
   (previously the first two sat flush at 0px and each lower block patched its
   own margin-top). Single source = --tp-band-gap in theme.scss. */
.file-upload-body {
  display: flex;
  flex-direction: column;
  gap: var(--tp-band-gap);
}
.file-upload-drop-wrap {
  width: 100%;
}
/* Bottom rhythm: the Element Plus defaults (40px dragger
   padding, UA-driven tip margin) left a loose zone between the drop rect and
   the footer buttons on every consumer page. Pin the vertical padding tight
   and fix the hint margin so the format text sits directly above the action
   area. Display-only change in the shared component = single source. */
:deep(.el-upload-dragger) {
  padding: 24px 10px;
}
:deep(.el-dialog__footer) {
  padding-bottom: 20px;
}
:deep(.el-upload__tip) {
  margin-top: 8px;
  margin-bottom: 0;
}
.file-upload-progress-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.text-secondary {
  color: var(--tp-text-secondary);
  font-size: 12px;
  white-space: nowrap;
}
</style>
