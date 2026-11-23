<script setup lang="ts">

import { onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { getProjects } from '@/api/project'


export interface ProjectOption {
  projectId: number
  projectName: string
  projectCode: string
}

const props = withDefaults(
  defineProps<{
    modelValue?: number | number[] | null
    
    placeholder?: string
    width?: string
    dataTest?: string
    disabled?: boolean
    
    autoSelectFirst?: boolean
    
    multiple?: boolean
    
    options?: ProjectOption[] | null
    /** Native id of the inner input, so an external <label for=...> can bind (wrapper div is not labelable) */
    inputId?: string
  }>(),
  { modelValue: null, placeholder: '', width: '220px', dataTest: '', disabled: false, autoSelectFirst: false, multiple: false, options: null, inputId: undefined },
)

const emit = defineEmits<{
  (e: 'update:modelValue', v: number | number[] | null): void
  (e: 'change', v: number | number[] | null): void
}>()

const { t } = useI18n()
const options = ref<ProjectOption[]>(props.options ? [...props.options] : [])
const loading = ref(false)
let timer: ReturnType<typeof setTimeout> | null = null


watch(() => props.options, (next) => {
  if (next) options.value = [...next]
})


function normalizeMultiple(v: unknown): number[] {
  if (!Array.isArray(v)) return []
  return v.filter((x): x is number => typeof x === 'number' && Number.isInteger(x) && x > 0)
}


function boundValue(): number | number[] | null {
  if (props.multiple) return normalizeMultiple(props.modelValue)
  return props.modelValue ?? null
}


async function load(keyword = ''): Promise<void> {
  if (props.options) {
    const kw = keyword.trim().toLowerCase()
    options.value = kw
      ? props.options.filter(
          (o) => o.projectName.toLowerCase().includes(kw) || o.projectCode.toLowerCase().includes(kw),
        )
      : [...props.options]
    return
  }
  const showLoading = keyword !== '' || options.value.length === 0
  if (showLoading) loading.value = true
  try {
    const page = await getProjects({ page: 1, size: 100, status: 1, keyword: keyword || undefined })
    options.value = (page.list || []).map((p) => ({
      projectId: p.id,
      projectName: p.name,
      projectCode: p.code,
    }))
    if (props.autoSelectFirst && !props.multiple && !props.modelValue && options.value.length > 0) {
      const first = options.value[0].projectId
      emit('update:modelValue', first)
      emit('change', first)
    }
  } finally {
    if (showLoading) loading.value = false
  }
}


function onRemote(keyword: string): void {
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => void load(keyword), 300)
}

function onUpdate(v: number | number[] | null): void {
  emit('update:modelValue', props.multiple ? normalizeMultiple(v) : v)
}

function onChange(v: number | number[] | null): void {
  emit('change', props.multiple ? normalizeMultiple(v) : v)
}

onMounted(() => void load())


defineExpose({ load })
</script>

<template>
  <el-select
    :model-value="boundValue()"
    filterable
    remote
    :remote-method="onRemote"
    :loading="loading"
    clearable
    :multiple="multiple"
    :collapse-tags="multiple"
    :collapse-tags-tooltip="multiple"
    :max-collapse-tags="multiple ? 1 : undefined"
    :placeholder="placeholder || t('common.selectProjectPlaceholder')"
    :style="{ width }"
    :disabled="disabled"
    :data-test="dataTest"
    :id="inputId"
    @update:model-value="onUpdate"
    @change="onChange"
  >
    <el-option v-for="p in options" :key="p.projectId" :label="p.projectName" :value="p.projectId">
      <!-- flex layout with ellipsis on both segments: a long project name or
           code never overflows the option row (vision audit finding) -->
      <span class="tp-opt-name">{{ p.projectName }}</span>
      <span class="tp-opt-code">{{ p.projectCode }}</span>
    </el-option>
    <template #empty>
      <el-empty :image-size="40" :description="t('common.noData')" />
    </template>
  </el-select>
</template>

<style scoped>
/* Option row truncation (vision-audit fix): the name truncates with an
   ellipsis at a bounded width, the code stays pinned right. Pure span-level
   rules — the dropdown popper is teleported to <body>, so :deep selectors
   cannot reach the option <li> from a scoped component. */
.tp-opt-name {
  display: inline-block;
  max-width: 62%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: middle;
}

.tp-opt-code {
  float: right;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}


:deep(.el-select__input:focus),
:deep(.el-select__inner:focus),
:deep(input:focus) {
  outline: none;
  box-shadow: none;
  border-color: transparent;
}
</style>
