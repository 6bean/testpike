<script setup lang="ts">
// Single-source side-effect disclosure line rendered inside a form's action area.
// Used by the SKILL and tool editors so the "what saving will trigger" note
// appears before the user submits, with identical layout on both pages.
defineProps<{
  text: string
  testId: string
}>()
</script>

<template>
  <el-form-item label=" ">
    <span class="text-secondary form-side-effect-hint" :data-test="testId">{{ text }}</span>
  </el-form-item>
</template>

<style scoped>
.form-side-effect-hint {
  font-size: 12px;
}
.text-secondary {
  color: var(--tp-text-secondary);
}
</style>
