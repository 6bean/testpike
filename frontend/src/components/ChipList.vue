<template>
  <div v-if="items && items.length" class="chip-list">
    <el-tag v-for="item in items" :key="item" size="small" effect="plain">{{ item }}</el-tag>
  </div>
  <span v-else class="chip-empty">{{ emptyText }}</span>
</template>

<script setup lang="ts">

defineProps<{ items?: string[] | null; emptyText: string }>()
</script>

<style scoped>
.chip-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.chip-empty {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
