<template>
  
  <el-tooltip :content="t('layout.langSwitchTip')" placement="bottom">
    <el-dropdown trigger="click" data-test="lang-switch" @command="onSelect">
      <el-icon class="lang-switch-icon" :size="18"><Operation /></el-icon>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item command="zh-CN" :disabled="locale === 'zh-CN'" data-test="lang-option-zh">
            {{ t('layout.langZh') }}
          </el-dropdown-item>
          <el-dropdown-item command="en-US" :disabled="locale === 'en-US'" data-test="lang-option-en">
            {{ t('layout.langEn') }}
          </el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </el-tooltip>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import useLocale from '@/composables/useLocale'
import type { TpLocale } from '@/utils/i18n'

const { t } = useI18n()
const { locale, switchTo } = useLocale()

function onSelect(command: string): void {
  switchTo(command as TpLocale)
}
</script>

<style scoped lang="scss">
.lang-switch-icon {
  cursor: pointer;
  color: var(--el-text-color-secondary);

  &:hover {
    color: var(--el-color-primary);
  }
}
</style>
