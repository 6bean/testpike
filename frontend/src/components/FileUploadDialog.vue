<template>
  <el-dialog
    :model-value="visible"
    :title="title"
    width="640px"
    destroy-on-close
    :close-on-click-modal="false"
    data-test="file-upload-dialog"
    @update:model-value="visible = $event"
  >
    <div
      class="file-upload-body file-upload-drop-host"
      :class="{ 'is-dragover': dragActive }"
      data-test="file-upload-drop-host"
      @dragenter.prevent="onHostDragEnter"
      @dragover.prevent="onHostDragOver"
      @dragleave="onHostDragLeave"
      @drop.prevent="onHostDrop"
    >
      <slot name="extra" />

      <!-- Selection echo (R71): picking or dropping a file must be visible in
           the dialog itself — before this fix the only cue was the footer
           button unlocking, which reads as "the dialog does not react". -->
      <div v-if="file" class="file-upload-selected" data-test="file-upload-selected">
        <el-icon class="file-upload-selected-icon"><Document /></el-icon>
        <span class="file-upload-selected-name">{{ file.name }}</span>
        <span class="file-upload-selected-size">{{ formatSize(file.size) }}</span>
        <el-button
          text
          type="primary"
          size="small"
          data-test="file-upload-remove"
          @click="clearFile"
        >{{ t('uploadDialog.removeFile') }}</el-button>
      </div>

      <div class="file-upload-drop-wrap" data-test="file-upload-drop">
        <el-upload
          ref="uploadRef"
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

import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, type UploadFile, type UploadFiles, type UploadInstance } from 'element-plus'
import { Document, UploadFilled } from '@element-plus/icons-vue'

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
const uploadRef = ref<UploadInstance>()
const dragDepth = ref(0)
const dragActive = computed(() => dragDepth.value > 0)

function hasFiles(e: DragEvent): boolean {
  return Array.from(e.dataTransfer?.types ?? []).includes('Files')
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

watch(visible, (v) => {
  if (!v) {
    file.value = null
    uploading.value = false
    progress.value = 0
    error.value = ''
    dragDepth.value = 0
  }
})

// While the dialog is open, never let the browser hijack a file drop
// (opening the file in a tab reads as "the dialog crashed / did nothing").
// Capture phase only calls preventDefault, so the inner el-upload dragger
// still runs its own handlers untouched.
function guardGlobalFileDrag(e: DragEvent): void {
  if (hasFiles(e)) e.preventDefault()
}
watch(visible, (v) => {
  if (v) {
    document.addEventListener('dragover', guardGlobalFileDrag, true)
    document.addEventListener('drop', guardGlobalFileDrag, true)
  } else {
    document.removeEventListener('dragover', guardGlobalFileDrag, true)
    document.removeEventListener('drop', guardGlobalFileDrag, true)
  }
})
onBeforeUnmount(() => {
  document.removeEventListener('dragover', guardGlobalFileDrag, true)
  document.removeEventListener('drop', guardGlobalFileDrag, true)
})

// Whole-dialog drop target (R71): the dashed dragger used to be the only
// droppable rectangle, so dragging into the dialog body did nothing.
function onHostDragEnter(e: DragEvent): void {
  if (hasFiles(e)) dragDepth.value += 1
}
function onHostDragOver(e: DragEvent): void {
  if (hasFiles(e)) e.dataTransfer && (e.dataTransfer.dropEffect = 'copy')
}
function onHostDragLeave(): void {
  dragDepth.value = Math.max(0, dragDepth.value - 1)
}
function onHostDrop(e: DragEvent): void {
  dragDepth.value = 0
  const host = e.currentTarget as HTMLElement | null
  const target = e.target as HTMLElement | null
  // Drops landing on the dragger are already handled by el-upload itself
  // (the event bubbles here too) — do not double-process.
  if (host && target && host.contains(target) && target.closest('.el-upload')) return
  const f = e.dataTransfer?.files?.[0] ?? null
  acceptFile(f)
}

function rejectedByFormat(f: File): boolean {
  const lower = f.name.toLowerCase()
  return !props.accept
    .split(',')
    .map((s) => s.trim().toLowerCase())
    .filter(Boolean)
    .some((ext) => lower.endsWith(ext))
}

function acceptFile(f: File | null): void {
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

function onFileChange(uploadFile: UploadFile, uploadFiles: UploadFiles): void {
  if (uploadFiles.length > 1) {
    uploadFiles.splice(0, uploadFiles.length - 1)
  }
  acceptFile(uploadFile.raw ?? null)
}

function clearFile(): void {
  file.value = null
  error.value = ''
  uploadRef.value?.clearFiles()
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
/* Whole-dialog drop host (R71): a transparent dashed frame sits inside the
   dialog body padding at rest and lights up while a file drag hovers the
   dialog, so the droppable area is discoverable instead of being only the
   inner dragger rectangle. */
.file-upload-drop-host {
  border: 2px dashed transparent;
  border-radius: 8px;
  margin: -10px;
  padding: 10px;
  transition: border-color 0.15s ease, background-color 0.15s ease;
}
.file-upload-drop-host.is-dragover {
  border-color: var(--el-color-primary);
  background-color: var(--el-color-primary-light-9);
}
.file-upload-selected {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  padding: 8px 12px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
}
.file-upload-selected-icon {
  color: var(--el-color-primary);
  flex: none;
}
.file-upload-selected-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 500;
}
.file-upload-selected-size {
  flex: none;
  color: var(--tp-text-secondary);
  font-size: 12px;
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
