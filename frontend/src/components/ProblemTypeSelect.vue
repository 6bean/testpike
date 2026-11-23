<template>
  <div class="problem-type-select" data-test="problem-type-select">
    <div class="pts-label" data-test="problem-type-label">{{ t('case.problemTypesTitle') }}</div>
    <el-checkbox-group v-model="selected">
      <el-checkbox
        v-for="opt in OPTIONS"
        :key="opt"
        :value="opt"
        :data-test="`problem-type-${opt}`"
      >
        {{ t(`case.feedbackType.${opt}`) }}
      </el-checkbox>
    </el-checkbox-group>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

const props = defineProps<{ modelValue: string[] }>()
const emit = defineEmits<{ 'update:modelValue': [string[]] }>()

const { t } = useI18n()


const OPTIONS = [
  'step_inaccurate', 'expected_wrong', 'selector_issue',
  'logic_missing', 'wording', 'locate_error',
] as const

const selected = computed({
  get: () => props.modelValue,
  set: (v: string[]) => emit('update:modelValue', v),
})
</script>

<style scoped>
.problem-type-select {
  margin-bottom: 12px;
}
.pts-label {
  font-size: 13px;
  color: var(--el-text-color-regular);
  margin-bottom: 4px;
}
</style>
