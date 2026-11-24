<script setup lang="ts">
/**
 * Shared case-detail door.
 *
 * Opens the real CaseDetailView page component in an embedded posture
 * (single source: the routed detail page itself, not a second read-only
 * copy). Consumers - e.g. clicking a case number inside the requirement
 * link-cases dialog - set `caseId` and `modelValue`. destroy-on-close plus
 * the :key remount guarantees each opened case re-reads its data, because
 * the detail page resolves its id once at mount.
 */
import { computed } from 'vue'
import CaseDetailView from '@/views/case/CaseDetailView.vue'

const props = defineProps<{
  modelValue: boolean
  caseId: number | null
}>()
const emit = defineEmits<{ 'update:modelValue': [boolean] }>()

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})
</script>

<template>
  <el-dialog
    v-model="visible"
    class="tp-dialog--lg"
    align-center
    destroy-on-close
    append-to-body
    data-test="case-detail-door"
  >
    <CaseDetailView v-if="caseId !== null" :key="caseId" embedded :case-id="caseId" />
  </el-dialog>
</template>
