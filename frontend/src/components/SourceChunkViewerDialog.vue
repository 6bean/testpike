<!--
 Source chunk viewer .

 The single surface that shows one source chunk verbatim: it is opened from the
 extraction preview dialog (jump-back-to-source for a preview item or a coverage
 gap anchor) and from the requirement management list (view-original for an
 adopted point). Both doors read the same server answer through the same door
 (GET /ai/documents/{id}/chunks/{chunkId}), so the same point always shows the
 same text - a second renderer here would be a second source of truth.

 The component owns the fetch and the walk-to-neighbour state; callers only pass
 which chunk to open plus their own ledger sentence (the extraction dialog has a
 coverage reason to show, the requirement list has none).
-->
<template>
  <el-dialog
    :model-value="modelValue"
    append-to-body
    align-center
    width="720px"
    :title="titleText"
    data-test="extract-source-dialog"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-loading="loading" class="source-body">
      <!-- Caller's own reason row, shown verbatim (no invented wording). Absent
           for doors without a ledger reason, never a fabricated explanation. -->
      <el-alert
        v-if="gapReason"
        type="warning"
        :closable="false"
        show-icon
        class="source-reason-alert"
        :title="gapReasonTitle || t('document.extractGapReasonTitle')"
        :description="gapReason"
        data-test="extract-source-reason-card"
      />
      <!-- Fallback answer : when the source chunk cannot
 be read (no chunk anchor recorded, or the chunk row went away after
 a document re-parse) the viewer still answers with the verbatim
 excerpt stored at adoption time. The note names the cause and the
 entry that can rebuild a real anchor. -->
      <template v-if="showFallback">
        <el-alert
          type="info"
          :closable="false"
          show-icon
          class="source-reason-alert"
          data-test="extract-source-fallback-note"
          :description="t('document.extractSourceFallbackNote')"
        />
        <pre v-if="hasFallback" class="source-content" data-test="extract-source-fallback-evidence">{{ fallbackEvidence }}</pre>
      </template>
      <p v-if="chunk" class="source-meta" data-test="extract-source-position">
        {{ t('document.extractSourcePosition', { index: chunk.positionIndex, chars: chunk.contentChars }) }}
      </p>
      <p v-if="chunk && chunk.chapter" class="source-meta" data-test="extract-source-chapter">
        {{ t('document.extractSourceChapter', { chapter: chunk.chapter }) }}
      </p>
      <pre v-if="chunk" class="source-content" data-test="extract-source-content">{{ chunk.content }}</pre>
      <!-- Context area: the closest preceding neighbours (already trimmed to the
           server context budget) in a collapsible block, so a thin chunk reads
           with its surrounding original text instead of as a sliver. Each
           neighbour row rides this same door, so walking upstream is a real path. -->
      <el-collapse
        v-if="chunk && chunk.preceding.length > 0"
        v-model="contextOpen"
        class="source-context-collapse"
        data-test="extract-source-context"
      >
        <el-collapse-item :title="t('document.extractContextToggle', { count: chunk.preceding.length })" name="ctx">
          <div
            v-for="nb in chunk.preceding"
            :key="nb.id"
            class="source-neighbor"
            data-test="extract-source-neighbor"
          >
            <div class="source-neighbor-head">
              <span class="source-neighbor-meta">
                {{ t('document.extractSourceChapter', { chapter: nb.chapter || t('document.extractContextNoChapter') }) }} · #{{ nb.seq }}
              </span>
              <el-button
                link
                type="primary"
                size="small"
                :data-test="`extract-source-neighbor-${nb.id}`"
                @click="openChunk(nb.id)"
              >{{ t('document.extractContextNeighborBtn') }}</el-button>
            </div>
            <pre class="source-neighbor-content">{{ nb.content }}</pre>
          </div>
          <div class="source-current-mark" data-test="extract-source-current-mark">
            {{ t('document.extractContextCurrentMark', { index: chunk.positionIndex }) }}
          </div>
        </el-collapse-item>
      </el-collapse>
    </div>
    <template #footer>
      <el-button
        v-if="canSupplement && chunk"
        type="primary"
        :loading="supplementing"
        data-test="extract-supplement-btn"
        @click="emit('supplement')"
      >{{ t('document.extractSupplement') }}</el-button>
      <el-button data-test="extract-source-close-btn" @click="emit('update:modelValue', false)">
        {{ t('document.close') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { bizErrorMsg } from '@/utils/bizError'
import { getDocumentChunk, type DocumentChunk } from '@/api/document'

const props = withDefaults(
  defineProps<{
    /** Whether the viewer is open (v-model on the caller side). */
    modelValue: boolean
    /** Owning document of the chunk to show; null = nothing to read. */
    documentId: number | null
    /** Chunk row to open; changing it re-reads through the same door. */
    chunkId: number | null
    /** Caller-supplied ledger sentence for this chunk ('' = show no reason card). */
    gapReason?: string
    /** Reason card title, falls back to the shared extraction wording. */
    gapReasonTitle?: string
    /** Directed-supplement button only for doors that can write (extraction dialog). */
    canSupplement?: boolean
    /** In-flight flag of the caller's submit, kept separate from the outer dialog. */
    supplementing?: boolean
    /** Recorded verbatim excerpt for the fallback face ('' = nothing stored). */
    fallbackEvidence?: string
    /** Owning object named in the door title ('' = title carries only the chunk seq). */
    subject?: string
  }>(),
  { gapReason: '', gapReasonTitle: '', canSupplement: false, supplementing: false, fallbackEvidence: '', subject: '' },
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'update:chunkId', value: number): void
  (e: 'supplement'): void
}>()

const { t } = useI18n()

const chunk = ref<DocumentChunk | null>(null)
const loading = ref(false)
/** The chunk read failed (stale/foreign id); with an excerpt stored the door
 *  answers through the fallback face instead of closing. */
const failed = ref(false)
/** Neighbours read expanded by default: the surrounding text is the point. */
const contextOpen = ref<string[]>(['ctx'])

const hasFallback = computed(() => Boolean(props.fallbackEvidence && props.fallbackEvidence.trim()))

const showFallback = computed(() =>
  !loading.value &&
  !chunk.value &&
  (failed.value || props.chunkId == null),
)

const titleText = computed(() => {
  if (chunk.value)
    return props.subject
      ? t('document.extractSourceTitleWithSubject', { subject: props.subject, seq: chunk.value.seq })
      : t('document.extractSourceTitle', { seq: chunk.value.seq })
  if (showFallback.value) return t('document.extractSourceFallbackTitle')
  return t('document.extractSourceLoading')
})

/** Read one chunk through the single door. A stale or foreign id comes back
 *  as a 200 answer with found=false and no text (the server treats it as an
 *  expected state, not an error): the dialog stays open on the fallback face
 *  and answers with the recorded excerpt, or with the note alone when the
 *  door holds no excerpt. */
async function loadChunk(id: number): Promise<void> {
  loading.value = true
  chunk.value = null
  failed.value = false
  if (props.documentId == null) {
    failed.value = true
    loading.value = false
    return
  }
  try {
    const view = await getDocumentChunk(props.documentId, id)
    if (view && view.found === false) {
      // Expected fallback state: no toast, no close — the note face below
      // names the cause and the entry that can rebuild a real anchor.
      failed.value = true
    } else {
      chunk.value = view
    }
  } catch (e: unknown) {
    ElMessage.error(bizErrorMsg(e, t('document.extractSourceLoadFail')))
    if (hasFallback.value) {
      failed.value = true
    } else {
      emit('update:modelValue', false)
    }
  } finally {
    loading.value = false
  }
}

/** Neighbour walk: the caller keeps the anchor so its own reason row follows. */
function openChunk(id: number): void {
  emit('update:chunkId', id)
}

watch(
  () => [props.modelValue, props.documentId, props.chunkId] as const,
  ([open, , chunkId]) => {
    if (open && chunkId != null) void loadChunk(chunkId)
    if (!open) {
      chunk.value = null
      failed.value = false
    }
  },
  { immediate: true },
)
</script>

<style scoped lang="scss">
/* Theme tokens only; the focus ring stays on the global :focus-visible (R29) -
   no component-local outline patches. */
.source-body {
  min-height: 80px;
}
.source-reason-alert {
  margin-bottom: 10px;
}
.source-context-collapse {
  margin-top: 10px;
}
.source-neighbor {
  padding: 8px 0;
  border-bottom: 1px dashed var(--el-border-color-lighter);
}
.source-neighbor-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.source-neighbor-meta {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.source-neighbor-content {
  margin: 6px 0 0;
  padding: 8px 12px;
  max-height: 24vh;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
  font-size: 13px;
  line-height: 1.6;
  background: var(--el-fill-color-lighter);
}
.source-current-mark {
  margin-top: 8px;
  padding: 6px 12px;
  font-size: 13px;
  color: var(--el-color-warning);
  background: var(--el-color-warning-light-9);
}
.source-meta {
  margin: 0 0 8px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.source-content {
  margin: 0;
  padding: 12px 16px;
  max-height: 55vh;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
  font-size: 14px;
  line-height: 1.7;
  background: var(--el-fill-color-light);
  border-radius: 6px;
}
</style>
