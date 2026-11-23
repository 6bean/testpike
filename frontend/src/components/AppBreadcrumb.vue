<template>
 
  <el-breadcrumb v-if="items.length" class="app-breadcrumb" data-test="app-breadcrumb" separator="/">
    <el-breadcrumb-item v-for="item in items" :key="item.key">
      <span
        v-if="item.path"
        class="bc-link"
        :data-test="`breadcrumb-item-${item.key}`"
        @click="go(item.path)"
      >{{ item.title }}</span>
      <span v-else :data-test="`breadcrumb-item-${item.key}`">{{ item.title }}</span>
    </el-breadcrumb-item>
  </el-breadcrumb>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import type { BreadcrumbItem } from '@/composables/useBreadcrumb'



defineProps<{ items: BreadcrumbItem[] }>()

const router = useRouter()

function go(path: string | null): void {
  if (path) void router.push(path)
}
</script>

<style scoped>
.app-breadcrumb {
  font-size: 13px;
  line-height: 1.3;
}
.bc-link {
  cursor: pointer;
  color: var(--el-text-color-secondary);
}
.bc-link:hover {
  color: var(--el-color-primary);
}
</style>
