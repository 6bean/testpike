<template>
  <el-tooltip v-if="url" :content="tipText" placement="top">
    <a
      :href="url"
      target="_blank"
      rel="noopener noreferrer"
      :data-test="dataTest"
      class="tp-third-link"
    >
      <el-icon v-if="iconOnly" :size="14"><TopRight /></el-icon>
      <span v-else class="tp-third-link-text">{{ label }}</span>
      <el-icon v-if="!iconOnly" :size="12" class="tp-third-link-icon"><TopRight /></el-icon>
    </a>
  </el-tooltip>
  <span v-else class="tp-third-link-empty" :data-test="dataTest ? `${dataTest}-empty` : undefined">—</span>
</template>

<script setup lang="ts">
// Single-source renderer for third-party official-site quick links .
// A ledger row or engine card either has a verified homepage URL (open in a new
// tab with noopener/noreferrer) or honestly shows a dash — never a dead link.
// Reused by the upgrade-center panorama table (icon form) and the engine center
// install-doc link (text form) so the anchor markup lives in exactly one place.
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { TopRight } from '@element-plus/icons-vue'

const props = defineProps<{
  /** Homepage URL resolved by the core single source; null/empty = grey dash */
  url?: string | null
  /** Tooltip / link text; falls back to the generic "open homepage" label */
  label?: string
  /** Icon-only form for dense table cells; otherwise text + trailing icon */
  iconOnly?: boolean
  /** Stable test anchor forwarded onto the anchor element */
  dataTest?: string
}>()

const { t } = useI18n()

const tipText = computed(() => props.label || t('common.openHomepage'))
</script>

<style scoped>
.tp-third-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--tp-link-color, var(--el-color-primary));
  text-decoration: none;
}
.tp-third-link:hover {
  text-decoration: underline;
}
.tp-third-link-icon {
  opacity: 0.75;
}
.tp-third-link-empty {
  color: var(--tp-text-secondary, var(--el-text-color-secondary));
}
</style>
