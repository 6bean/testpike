<template>
  <!-- Shared option set for every document-category select (filter bar, upload
       dialog, inline table cell). Kept in one component because the table cell
       is narrow: the option name stays short for the closed select and table
       display, while the effect sentence rides on a second line visible only
       in the open dropdown. -->
  <el-option v-for="opt in DOC_CATEGORIES" :key="opt.value" :label="opt.label" :value="opt.value">
    <div class="doc-cat-option">
      <span>{{ opt.label }}</span>
      <span v-if="opt.description" class="doc-cat-option-desc">{{ opt.description }}</span>
    </div>
  </el-option>
</template>

<script setup lang="ts">
import { DOC_CATEGORIES } from '@/api/document'
</script>

<style scoped>
.doc-cat-option {
  display: flex;
  flex-direction: column;
  line-height: 1.4;
  padding: 2px 0;
}
.doc-cat-option-desc {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  white-space: normal;
}
</style>

<style>
/* Element Plus pins option rows to a fixed 34px with overflow:hidden, while
   the two-line shape defined above is ~40px tall: the effect sentence got
   clipped row by row (measured 6px loss, worst on the last option against the
   panel edge). Rows must fit the content this component owns. Keyed to
   .doc-cat-option so every document-category picker (filter bar, upload
   dialog, inline table cell) inherits the same row geometry from one place —
   no per-page popper patches. */
.el-select-dropdown__item:has(.doc-cat-option) {
  height: auto;
  min-height: 34px;
  line-height: normal;
  padding-top: 7px;
  padding-bottom: 7px;
}
</style>
