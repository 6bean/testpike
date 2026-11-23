<script setup lang="ts">

import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { listCases } from '@/api/case'


export interface CaseOption {
  id: number
  caseNo: string
  title: string
}

const props = withDefaults(
  defineProps<{
    modelValue?: number | number[] | null
 
    projectId: number | null
    multiple?: boolean
    placeholder?: string
    width?: string
    dataTest?: string
    disabled?: boolean
    pageSize?: number
  }>(),
  { modelValue: null, multiple: false, placeholder: '', width: '100%', dataTest: '', disabled: false, pageSize: 50 },
)

const emit = defineEmits<{
  (e: 'update:modelValue', v: number | number[] | null): void
  (e: 'change', v: number | number[] | null): void
}>()

const { t } = useI18n()
const options = ref<CaseOption[]>([])
const page = ref(1)
const total = ref(0)
const loading = ref(false)
const keyword = ref('')
let timer: ReturnType<typeof setTimeout> | null = null

const hasMore = computed(() => options.value.length < total.value)


async function load(reset: boolean): Promise<void> {
  if (!props.projectId) return
  const showLoading = !reset || keyword.value !== '' || options.value.length === 0
  if (showLoading) loading.value = true
  try {
    const target = reset ? 1 : page.value + 1
    const res = await listCases({
      projectId: props.projectId,
      keyword: keyword.value || undefined,
      page: target,
      size: props.pageSize,
    })
    total.value = Number(res.total || 0)
    const rows: CaseOption[] = (res.list || []).map((c) => ({ id: c.id, caseNo: c.caseNo, title: c.title }))
    options.value = reset ? rows : [...options.value, ...rows]
    page.value = target
  } finally {
    if (showLoading) loading.value = false
  }
}


function onRemote(kw: string): void {
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => {
    keyword.value = kw
    void load(true)
  }, 300)
}

function loadMore(): void {
  void load(false)
}

function onUpdate(v: number | number[] | null): void {
  emit('update:modelValue', v)
}

function onChange(v: number | number[] | null): void {
  emit('change', v)
}

watch(
  () => props.projectId,
  (id, prev) => {
    keyword.value = ''
    options.value = []
    total.value = 0
    page.value = 1
    const selected = props.modelValue
    const hasSelection =
      Array.isArray(selected) ? selected.length > 0 : selected !== null && selected !== undefined && selected !== 0
    if (prev !== undefined && hasSelection) {
      emit('update:modelValue', props.multiple ? [] : null)
    }
    void load(true)
  },
  { immediate: true },
)

defineExpose({ load, options })
</script>

<template>
  <el-select
    :model-value="modelValue"
    :multiple="multiple"
    filterable
    remote
    :remote-method="onRemote"
    :loading="loading"
    clearable
    :placeholder="placeholder || t('common.selectCasePlaceholder')"
    :style="{ width }"
    :disabled="disabled"
    :data-test="dataTest"
    @update:model-value="onUpdate"
    @change="onChange"
  >
    <el-option v-for="c in options" :key="c.id" :label="`${c.caseNo} ${c.title}`" :value="c.id" />
    <template #empty>
      <el-empty :image-size="40" :description="t('common.noData')" />
    </template>
    <template #footer v-if="hasMore">
      <div class="tp-case-loadmore">
        <el-button link type="primary" :loading="loading" data-test="case-select-loadmore" @click="loadMore">
          {{ t('common.loadMore') }}（{{ options.length }}/{{ total }}）
        </el-button>
      </div>
    </template>
  </el-select>
</template>

<style scoped>
.tp-case-loadmore {
  display: flex;
  justify-content: center;
  padding: 4px 0 8px;
  border-top: 1px solid var(--el-border-color-lighter);
}
</style>
