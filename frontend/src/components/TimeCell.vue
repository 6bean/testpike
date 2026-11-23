<template>
  <span class="time-cell" :title="absolute">{{ display }}</span>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { timeAgo } from '@/utils/timeAgo'

const props = defineProps<{ iso?: string | null }>()

const display = computed(() => timeAgo(props.iso))
const absolute = computed(() => (props.iso ? props.iso.replace('T', ' ').slice(0, 19) : '-'))
</script>

<style scoped>
.time-cell {
  font-variant-numeric: tabular-nums;
  color: var(--tp-text-secondary);
}
</style>
