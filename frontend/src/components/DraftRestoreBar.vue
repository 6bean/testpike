<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { peekAutoDraft, clearAutoDraft, type AutoDraftRecord } from '@/composables/useAutoDraft'


const props = defineProps<{
  
  draftKey: string
  
  active: boolean
}>()

const emit = defineEmits<{ restore: [record: AutoDraftRecord] }>()

const { t } = useI18n()
const draft = ref<AutoDraftRecord | null>(null)

watch(
  () => [props.draftKey, props.active] as const,
  ([key, active]) => {
    draft.value = active ? peekAutoDraft(key) : null
  },
  { immediate: true },
)

function onRestore(): void {
  if (!draft.value) return
  emit('restore', draft.value)
  clearAutoDraft(props.draftKey)
  draft.value = null
}

function onDiscard(): void {
  clearAutoDraft(props.draftKey)
  draft.value = null
}


function formatSavedAt(iso: string): string {
  const d = new Date(iso)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}
</script>

<template>
  <el-alert
    v-if="draft"
    type="warning"
    :closable="false"
    show-icon
    class="draft-restore-bar"
    data-test="draft-restore-bar"
  >
    <template #title>
      <span data-test="draft-restore-text">
        {{ t('case.draftDetected', { time: formatSavedAt(draft.savedAt) }) }}
        <el-tag v-if="draft.truncated" size="small" type="info" data-test="draft-truncated-tag">
          {{ t('case.draftTruncated') }}
        </el-tag>
      </span>
      <el-button size="small" type="primary" link data-test="draft-restore-btn" @click="onRestore">
        {{ t('case.draftRestore') }}
      </el-button>
      <el-button size="small" link data-test="draft-discard-btn" @click="onDiscard">
        {{ t('case.draftDiscard') }}
      </el-button>
    </template>
  </el-alert>
</template>

<style scoped>
.draft-restore-bar {
  margin-bottom: 12px;
}
.draft-restore-bar :deep(.el-alert__title) {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
</style>
