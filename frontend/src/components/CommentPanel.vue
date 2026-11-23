<template>
  <section v-if="canUse" class="comment-panel" data-test="comment-panel">
    <header class="comment-panel-head">
      <span class="comment-panel-title">{{ t('comment.title') }}</span>
      <el-button link size="small" data-test="comment-refresh" :loading="loading" @click="load">
        {{ t('comment.refresh') }}
      </el-button>
    </header>

    <p v-if="hostId == null" class="comment-empty" data-test="comment-empty">{{ t('comment.pickHost') }}</p>

    <template v-else>
      <div class="comment-list" data-test="comment-list">
        <p v-if="!comments.length" class="comment-empty" data-test="comment-empty">{{ t('comment.none') }}</p>
        <article v-for="c in comments" :key="c.id" class="comment-item" data-test="comment-item">
          <div class="comment-meta">
            <span class="comment-author">{{ c.source === 2 ? t('comment.systemAuthor') : (c.authorName || '-') }}</span>
            <el-tag v-if="c.source === 2" size="small" type="info">{{ t('comment.systemTag') }}</el-tag>
            <span class="comment-time">{{ c.createdAt }}</span>
          </div>
          <p class="comment-body">{{ c.content }}</p>
          <div class="comment-foot">
            <el-button link size="small" data-test="comment-attach-toggle" @click="toggleAttachments(c.id)">
              {{ t('comment.attachments') }}
            </el-button>
          </div>
          <ul v-if="openAttachments[c.id]" class="comment-attach-list">
            <li v-if="!attachMap[c.id]?.length">{{ t('comment.noAttachments') }}</li>
            <li v-for="a in attachMap[c.id]" :key="a.id" data-test="comment-attachment-row">
              <span class="attach-name">{{ a.fileName }}</span>
              <span class="attach-size">{{ formatSize(a.sizeBytes) }}</span>
              <el-button link size="small" data-test="comment-download" @click="downloadFile(a.id)">
                {{ t('comment.download') }}
              </el-button>
            </li>
          </ul>
        </article>
      </div>

      <div class="comment-editor">
        <el-input
          v-model="content"
          type="textarea"
          :rows="3"
          maxlength="2000"
          show-word-limit
          :placeholder="t('comment.placeholder')"
          data-test="comment-input"
        />
        <div class="comment-editor-bar">
          <el-select
            v-model="mentionIds"
            multiple filterable remote reserve-keyword
            :remote-method="searchUsers" :loading="userLoading"
            :placeholder="t('comment.mention')" class="comment-mention"
            data-test="comment-mention"
          >
            <el-option v-for="u in userOptions" :key="u.id" :label="u.displayName || u.username" :value="u.id" />
          </el-select>
          <el-button link size="small" data-test="comment-attach-pick" @click="pickFiles">
            {{ t('comment.addImage') }}
          </el-button>
          <el-button
            type="primary" size="small"
            :disabled="!canSubmit" :loading="submitting"
            data-test="comment-submit" @click="submit"
          >
            {{ t('comment.submit') }}
          </el-button>
        </div>
        <ul v-if="pendingFiles.length" class="comment-pending-list" data-test="comment-pending">
          <li v-for="(f, i) in pendingFiles" :key="i">{{ f.name }}（{{ formatSize(f.size) }}）
            <el-button link size="small" @click="pendingFiles.splice(i, 1)">×</el-button>
          </li>
        </ul>
        <input ref="fileInput" type="file" accept=".png,.jpg,.jpeg,.gif,.webp" multiple hidden @change="onFileChosen" />
      </div>
    </template>
  </section>
</template>

<script setup lang="ts">

import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import {
  createComment,
  downloadAttachment,
  listAttachments,
  listComments,
  markRead,
  uploadAttachment,
  type CommentHostType,
  type CommentItem,
  type CommentAttachmentItem,
} from '@/api/comment'
import { getUsers, type UserOption } from '@/api/project'

const props = defineProps<{ hostType: CommentHostType; hostId: number | null }>()
const { t } = useI18n()
const auth = useAuthStore()

const canUse = computed(() => auth.hasPerm('perm:comment:use'))

const loading = ref(false)
const submitting = ref(false)
const comments = ref<CommentItem[]>([])
const content = ref('')
const mentionIds = ref<number[]>([])
const pendingFiles = ref<File[]>([])
const attachMap = reactive<Record<number, CommentAttachmentItem[]>>({})
const openAttachments = reactive<Record<number, boolean>>({})
const userOptions = ref<UserOption[]>([])
const userLoading = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)

const canSubmit = computed(() => !!props.hostId && !!content.value.trim())

async function load() {
  if (props.hostId == null || !canUse.value) return
  loading.value = true
  try {
    comments.value = await listComments(props.hostType, props.hostId)
    await markRead(props.hostType, props.hostId)
  } catch (e) {
    ElMessage.error((e as Error)?.message || t('comment.loadFailed'))
  } finally {
    loading.value = false
  }
}

async function searchUsers(keyword: string) {
  if (!keyword) return
  userLoading.value = true
  try {
    userOptions.value = (await getUsers({ keyword, page: 1, size: 20 })).list
  } catch {
    userOptions.value = []
  } finally {
    userLoading.value = false
  }
}

function pickFiles() {
  fileInput.value?.click()
}

function onFileChosen(ev: Event) {
  const input = ev.target as HTMLInputElement
  attachPendingFiles([...(input.files || [])])
  input.value = ''
}


function attachPendingFiles(files: File[]) {
  const extOk = ['.png', '.jpg', '.jpeg', '.gif', '.webp']
  for (const f of files) {
    const lower = f.name.toLowerCase()
    if (!extOk.some((x) => lower.endsWith(x))) {
      ElMessage.warning(t('comment.typeRejected', { name: f.name }))
      continue
    }
    if (f.size > 10 * 1024 * 1024) {
      ElMessage.warning(t('comment.sizeRejected', { name: f.name }))
      continue
    }
    pendingFiles.value.push(f)
  }
}

async function submit() {
  if (!canSubmit.value) return
  await submitWithAttachments()
}


async function submitWithAttachments() {
  if (props.hostId == null || !content.value.trim()) return
  submitting.value = true
  try {
    const { id } = await createComment({
      hostType: props.hostType,
      hostId: props.hostId,
      content: content.value.trim(),
      mentions: mentionIds.value.length ? mentionIds.value : undefined,
    })
    for (const f of pendingFiles.value) {
      await uploadAttachment(id, f)
    }
    content.value = ''
    mentionIds.value = []
    pendingFiles.value = []
    ElMessage.success(t('comment.posted'))
    await load()
  } catch (e) {
    ElMessage.error((e as Error)?.message || t('comment.postFailed'))
  } finally {
    submitting.value = false
  }
}

async function toggleAttachments(commentId: number) {
  openAttachments[commentId] = !openAttachments[commentId]
  if (openAttachments[commentId] && !attachMap[commentId]) {
    try {
      attachMap[commentId] = await listAttachments(commentId)
    } catch {
      attachMap[commentId] = []
    }
  }
}

async function downloadFile(attachmentId: number) {
  try {
    const grant = await downloadAttachment(attachmentId)
    window.open(grant.url, '_blank')
  } catch (e) {
    ElMessage.error((e as Error)?.message || t('comment.downloadFailed'))
  }
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

watch(() => [props.hostType, props.hostId], load, { immediate: true })

defineExpose({ attachPendingFiles, submitWithAttachments, downloadFile, load })
</script>

<style scoped>
.comment-panel { display: flex; flex-direction: column; gap: 8px; }
.comment-panel-head { display: flex; align-items: center; justify-content: space-between; }
.comment-panel-title { font-weight: 600; }
.comment-empty { color: var(--el-text-color-secondary); font-size: 13px; margin: 4px 0; }
.comment-list { display: flex; flex-direction: column; gap: 8px; max-height: 320px; overflow-y: auto; }
.comment-item { border: 1px solid var(--el-border-color-lighter); border-radius: 6px; padding: 8px 10px; }
.comment-meta { display: flex; align-items: center; gap: 8px; font-size: 12px; color: var(--el-text-color-secondary); }
.comment-author { font-weight: 600; color: var(--el-text-color-primary); }
.comment-body { white-space: pre-wrap; margin: 6px 0; font-size: 13px; word-break: break-all; }
.comment-foot { display: flex; justify-content: flex-end; }
.comment-attach-list { margin: 0; padding-left: 16px; font-size: 12px; }
.attach-name { margin-right: 8px; }
.attach-size { margin-right: 8px; color: var(--el-text-color-secondary); }
.comment-editor-bar { display: flex; align-items: center; gap: 8px; margin-top: 6px; }
.comment-mention { flex: 1; min-width: 160px; }
.comment-pending-list { margin: 4px 0 0; padding-left: 18px; font-size: 12px; }
</style>
