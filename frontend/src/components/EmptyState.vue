
<template>
  <div class="empty-state" data-test="empty-state">
    <el-icon class="empty-state__icon" :size="44">
      <component :is="iconComp" />
    </el-icon>
    <p class="empty-state__title">{{ title }}</p>
    <p v-if="hint" class="empty-state__hint">{{ hint }}</p>
    <div class="empty-state__actions">
      <el-button v-if="actionText" type="primary" plain :icon="actionIcon" :disabled="actionDisabled" data-test="empty-action" @click="emit('action')">
        {{ actionText }}
      </el-button>
      <el-button v-if="filtered" data-test="empty-clear" @click="emit('clear-filter')">{{ clearText || t('components.clearFilter') }}</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import { Document, Search } from "@element-plus/icons-vue";

const props = withDefaults(
  defineProps<{
    title: string;
    hint?: string;
    actionText?: string;
    actionIcon?: any;
    
    filtered?: boolean;
    
    actionDisabled?: boolean;
    clearText?: string;
    icon?: any;
  }>(),
  { filtered: false, actionDisabled: false }
);
const emit = defineEmits<{ (e: "action"): void; (e: "clear-filter"): void }>();
const { t } = useI18n();

const iconComp = computed(() => props.icon ?? (props.filtered ? Search : Document));
</script>

<style scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--ds-space-2, 8px);
  padding: 48px var(--ds-space-4, 16px);
}
.empty-state__icon {
  color: var(--ds-text-3, #6b7280);
  margin-bottom: var(--ds-space-2, 8px);
}
.empty-state__title {
  margin: 0;
  font-size: 14px;
  color: var(--ds-text-2, #4b5563);
}
.empty-state__hint {
  margin: 0;
  font-size: 12px;
  color: var(--ds-text-3, #6b7280);
}
.empty-state__actions {
  display: flex;
  gap: var(--ds-space-3, 12px);
  margin-top: var(--ds-space-3, 12px);
}
</style>
