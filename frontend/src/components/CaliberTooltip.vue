<template>
 
  
  <el-tooltip v-if="hover" :content="hover" placement="top" :show-after="150">
    <span class="caliber-hoverable" data-test="caliber-hover" :data-caliber="code"><slot /></span>
  </el-tooltip>
  <span v-else class="caliber-plain" data-test="caliber-hover" :data-caliber="code"><slot /></span>
</template>

<script setup lang="ts">

import { computed, onMounted, shallowRef } from 'vue'
import { ElTooltip } from 'element-plus'
import { getCalibers, type CaliberView } from '@/api/report'

const props = defineProps<{ code: string; overrideText?: string }>()

const calibers = shallowRef<CaliberView[]>([])
onMounted(async () => {
  try {
    calibers.value = await getCalibers()
  } catch {
  }
})

const hover = computed(
  () => props.overrideText || calibers.value.find((c) => c.code === props.code)?.hoverText || '',
)
</script>

<style scoped>
.caliber-hoverable {
  cursor: help;
  border-bottom: 1px dotted var(--el-text-color-secondary);
}
</style>
