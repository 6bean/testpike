<template>
  <el-select
    :model-value="selectValue"
    filterable
    class="mock-space-picker"
    data-test="mock-space-select"
    :placeholder="t('mockPicker.placeholder')"
    :loading="loading"
    @change="onChange"
  >
    <el-option
      :value="UNBOUND"
      :label="t('mockPicker.unbound')"
      data-test="mock-space-option-unbound"
    />
    <el-option
      v-for="s in spaces"
      :key="s.id"
      :value="s.id"
      :label="s.name"
      :disabled="!bindable(s)"
      :data-test="`mock-space-option-${s.id}`"
      :data-rule-count="s.ruleCount == null ? '' : String(s.ruleCount)"
      :data-thumbnail="thumbnail(s)"
      :data-bindable="String(bindable(s))"
    >
      <div class="space-option">
        <span class="space-name">{{ s.name }}<template v-if="s.alias">（{{ s.alias }}）</template></span>
        <span class="space-thumb" data-test="space-thumbnail">{{ thumbnail(s) }}</span>
        <el-tag v-if="!bindable(s)" size="small" type="warning" disable-transitions>{{ unbindableReason(s) }}</el-tag>
      </div>
    </el-option>
  </el-select>
</template>

<script setup lang="ts">
// Declarative mock-binding selector for the case/suite editors : one
// dropdown, one row per space, thumbnails straight off the list projection
// (rule inventory + hit rate) so the operator sees the space's shape before
// binding it. Draft/retired spaces stay visible but unbindable — the same
// legality the core MockSpaceBindingGate enforces on save, taught up front
// instead of as a post-submit error. Emitting null means "no mock": the run
// goes to the real environment (the parent maps null -> 0 on save).
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { listSpaces, type MockSpaceItem } from '@/api/mock'

const props = defineProps<{ projectId: number; modelValue: number | null }>()
const emit = defineEmits<{ 'update:modelValue': [number | null] }>()

const { t } = useI18n()

/** Sentinel option value for "not bound"; the wire contract uses null. */
const UNBOUND = 0

const spaces = ref<MockSpaceItem[]>([])
const loading = ref(false)

const selectValue = computed(() => props.modelValue ?? UNBOUND)

function onChange(v: number) {
  emit('update:modelValue', v === UNBOUND ? null : v)
}

async function load(): Promise<void> {
  if (!props.projectId) {
    spaces.value = []
    return
  }
  loading.value = true
  try {
    spaces.value = await listSpaces(props.projectId)
  } finally {
    loading.value = false
  }
}

watch(() => props.projectId, () => void load(), { immediate: true })

/** Only a published space can be bound (status 1; mirrors the core gate). */
function bindable(s: MockSpaceItem): boolean {
  return s.status === 1
}

/** One-line honest thumbnail: "rules N · hit rate x%" or the no-sample wording. */
function thumbnail(s: MockSpaceItem): string {
  const rules = `${t('mockPicker.rules')} ${s.ruleCount == null ? '-' : s.ruleCount}`
  const rate = s.hitRate == null
    ? t('mockPicker.noTraffic')
    : `${t('mockPicker.hitRate')} ${Math.round(s.hitRate * 100)}%`
  return `${rules} · ${rate}`
}

function unbindableReason(s: MockSpaceItem): string {
  return s.status === 0 ? t('mockPicker.draftUnbindable') : t('mockPicker.retiredUnbindable')
}

defineExpose({ reload: load })
</script>

<style scoped>
.space-option {
  display: flex;
  align-items: center;
  gap: 8px;
}
.space-thumb {
  color: var(--tp-text-secondary, #8a94a6);
  font-size: 12px;
}
</style>
