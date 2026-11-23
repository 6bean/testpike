<!-- R63 shared error display container (single source; page-level patches forbidden). The reason line always renders; the next-step line renders only when the message carries a fix pointer — never an empty shell. An optional one-click action button completes the guidance entry. -->
<template>
  <div v-if="reason" class="error-notice" data-test="error-notice" role="alert">
    <el-icon class="error-notice__icon"><CircleCloseFilled /></el-icon>
    <div class="error-notice__body">
      <p class="error-notice__reason" data-test="error-notice-reason">{{ reason }}</p>
      <p v-if="guidance" class="error-notice__next" data-test="error-notice-next-step">
        <strong>{{ t('components.nextStep') }}：</strong>{{ guidance }}
      </p>
      <div v-if="$slots.action || actionText" class="error-notice__actions">
        <slot name="action">
          <el-button type="primary" plain size="small" data-test="error-notice-action" @click="emit('action')">
            {{ actionText }}
          </el-button>
        </slot>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { CircleCloseFilled } from '@element-plus/icons-vue'
import { parseErrorGuidance } from '../utils/errorGuidance'

const props = defineProps<{
  /** Raw error text (backend msg or i18n sentence); split into reason + next-step internally. */
  message: string
  /** Optional action button label for a one-click fix; omit it and the button does not render. */
  actionText?: string
}>()
const emit = defineEmits<{ (e: 'action'): void }>()
const { t } = useI18n()

const parts = computed(() => parseErrorGuidance(props.message ?? ''))
const reason = computed(() => parts.value.reason)
const guidance = computed(() => parts.value.guidance)
</script>

<style scoped>
.error-notice {
  display: flex;
  align-items: flex-start;
  gap: var(--ds-space-2, 8px);
  padding: var(--ds-space-3, 12px) var(--ds-space-4, 16px);
  border-radius: var(--ds-radius-2, 8px);
  background: var(--ds-danger-bg, rgba(245, 108, 108, 0.08));
  border: 1px solid var(--ds-danger-border, rgba(245, 108, 108, 0.35));
}
.error-notice__icon {
  color: var(--ds-danger, #f56c6c);
  margin-top: 2px;
}
.error-notice__body {
  flex: 1;
  min-width: 0;
}
.error-notice__reason {
  margin: 0;
  font-size: 13px;
  color: var(--ds-text-1, #303133);
}
.error-notice__next {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--ds-text-2, #4b5563);
}
.error-notice__actions {
  margin-top: var(--ds-space-2, 8px);
}
</style>
