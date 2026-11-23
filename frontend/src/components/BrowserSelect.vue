<template>
  <el-select
    :model-value="modelValue"
    clearable
    style="width: 100%"
    :data-test="dataTest"
    :placeholder="t('batch.browserPlaceholder')"
    @update:model-value="onSelect"
  >
    <el-option v-for="k in options" :key="k" :label="kernelLabel(k)" :value="k" />
  </el-select>
</template>

<script setup lang="ts">
import { computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'


const props = defineProps<{
  modelValue: string | null
  
  envBrowsers: string | null
  dataTest?: string
}>()

const emit = defineEmits<{ (e: 'update:modelValue', v: string | null): void }>()

const { t } = useI18n()


const KERNELS = ['chromium', 'firefox', 'webkit']

const options = computed<string[]>(() => {
  if (props.envBrowsers == null) return KERNELS
  const parsed = props.envBrowsers
    .split(',')
    .map((s) => s.trim().toLowerCase())
    .filter((s) => s.length > 0)
  return parsed.length > 0 ? [...new Set(parsed)] : ['chromium']
})

function kernelLabel(k: string): string {
  return t(`dict.browser.${k}`)
}

function onSelect(v: string | null): void {
  emit('update:modelValue', v || null)
}

watch(options, (list) => {
  if (props.modelValue && !list.includes(props.modelValue)) {
    emit('update:modelValue', null)
  }
})
</script>
