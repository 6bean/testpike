<template>
 
  <!-- Tab-scoped mode 1 (TB group): when the active pane is a fixed view the
       host passes disabled + a pointed tooltip; the popover stays suppressed
       so nothing opens onto an inert panel (no invisible no-op). The tooltip
       wraps OUTSIDE the popover (element-root discipline: the popover click
       directive must bind the button itself; nesting the reverse order makes
       the reference a component root and breaks the trigger). Default props
       keep the legacy standalone usage behavior byte-for-byte identical. -->
  <el-tooltip :content="disabledTooltip || t('column.fixedViewUnsupported')" placement="bottom" :disabled="!disabled">
    <span>
      <el-popover placement="bottom-end" :width="300" trigger="click" :disabled="disabled">
        <template #reference>
          <el-button :icon="Setting" :disabled="disabled" data-test="column-settings-btn">
            {{ t('column.settings') }}
          </el-button>
        </template>
    <div data-test="column-settings-pop">
      <div class="cs-header">
        <span class="cs-title">{{ t('column.title') }}</span>
        <el-button link type="primary" size="small" data-test="column-settings-reset" @click="emit('reset')">
          {{ t('column.reset') }}
        </el-button>
      </div>
      <div class="cs-density">
        <span>{{ t('column.density') }}</span>
        <el-radio-group :model-value="density" size="small" @change="(v: string) => emit('density', v as TableDensity)">
          <el-radio-button value="default" :disabled="densityDisabled">{{ t('column.densityDefault') }}</el-radio-button>
          <el-radio-button value="small" :disabled="densityDisabled">{{ t('column.densityCompact') }}</el-radio-button>
        </el-radio-group>
      </div>
      <el-scrollbar max-height="320px">
        <div v-for="(col, idx) in columns" :key="col.key" class="cs-row" :data-test="`col-cfg-row-${col.key}`">
          <el-checkbox
            :model-value="col.visible"
            :data-test="`col-cfg-${col.key}`"
            @change="emit('toggle', col.key)"
          >
            {{ t(col.labelKey) }}
          </el-checkbox>
          <span class="cs-move">
            <el-button
              link
              :type="col.fixed === 'left' ? 'primary' : 'default'"
              :icon="Lock"
              size="small"
              :title="t('column.pin')"
              :data-test="`col-cfg-${col.key}-pin`"
              @click="emit('pin', col.key)"
            />
            <el-button
              link
              :icon="Top"
              :disabled="idx === 0"
              size="small"
              :data-test="`col-cfg-${col.key}-up`"
              @click="emit('move', col.key, true)"
            />
            <el-button
              link
              :icon="Bottom"
              :disabled="idx === columns.length - 1"
              size="small"
              :data-test="`col-cfg-${col.key}-down`"
              @click="emit('move', col.key, false)"
            />
          </span>
        </div>
      </el-scrollbar>
    </div>
      </el-popover>
    </span>
  </el-tooltip>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { Bottom, Lock, Setting, Top } from '@element-plus/icons-vue'
import type { TableDensity } from '@/composables/useColumnConfig'




export interface ColumnSettingRow {
  key: string
  visible: boolean
  fixed: 'left' | null
  labelKey: string
}

defineProps<{
  columns: ColumnSettingRow[]
  density: TableDensity
  /** Fixed-view pane: the button opens nothing and points the way (tab-sync mode 1). */
  disabled?: boolean
  /** Why this pane has no column settings (i18n text; falls back to the generic reason). */
  disabledTooltip?: string
  /** Density switches locked (the visible table cannot react to density). */
  densityDisabled?: boolean
}>()

const emit = defineEmits<{
  toggle: [key: string]
  move: [key: string, top: boolean]
  density: [value: TableDensity]
  reset: []
  pin: [key: string]
}>()

const { t } = useI18n()
</script>

<style scoped>
.cs-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}
.cs-title {
  font-weight: 600;
}
.cs-density {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.cs-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 2px 0;
}
.cs-move {
  display: inline-flex;
}
</style>
