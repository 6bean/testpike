<template>
  <!--
    Composer display setting for the AI chat kernel (one piece, mounted on both
    entry surfaces): the dock carries it in its "AI Chat" title band, the
    standalone page (whose name rides the global header, so it has no in-page
    title row) carries it at the end of the composer row. Same popover content,
    same shared preference state — a switch taken on either surface holds on
    both (personal preference in a per-user localStorage bucket, the same
    posture as the dock width; never a server-side config key).
  -->
  <el-popover placement="bottom-end" :width="240" trigger="click">
    <template #reference>
      <el-icon class="chat-icon" data-test="chat-dock-settings" :aria-label="t('layout.chatDock.settings')" @click.stop><Setting /></el-icon>
    </template>
    <div data-test="chat-settings-pop">
      <div class="chat-setting-row">
        <el-switch v-model="showSendButton" data-test="chat-send-switch" @change="setShowSendButton" />
        <span>{{ t('layout.chatDock.displaySendButton') }}</span>
      </div>
      <div class="chat-setting-hint">{{ t('layout.chatDock.sendButtonHint') }}</div>
    </div>
  </el-popover>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { Setting } from '@element-plus/icons-vue'
import { useChatComposerPrefs } from '@/composables/useChatComposerPrefs'

const { t } = useI18n()
const { showSendButton, setShowSendButton } = useChatComposerPrefs()
</script>

<style scoped>
.chat-setting-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.chat-setting-hint {
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
