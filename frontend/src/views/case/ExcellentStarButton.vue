<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElTooltip } from 'element-plus'
import { Star, StarFilled } from '@element-plus/icons-vue'
import { setCaseExcellent } from '@/api/case'
import { bizErrorMsg } from '@/utils/bizError'
import { useAuthStore } from '@/stores/auth'

const props = defineProps<{
  entityId: number
  
  caseNo?: string
  
  excellent: number
  
  version: number
  
  status: number
}>()

const emit = defineEmits<{ changed: [] }>()
const { t } = useI18n()
const auth = useAuthStore()

const marked = computed(() => props.excellent === 1)

const visible = computed(() => auth.hasPerm('perm:feedback:excellent-mark'))
const markDisabled = computed(() => !marked.value && props.status !== 3)
const local = ref<boolean | null>(null)
const busy = ref(false)
const show = computed(() => local.value ?? marked.value)

async function toggle(): Promise<void> {
  if (busy.value || markDisabled.value) return
  busy.value = true
  const next = !show.value
  local.value = next
  try {
    await setCaseExcellent(props.entityId, next, props.version)
    ElMessage.success(next
      ? t('case.excellentMarkOk', { caseNo: props.caseNo ?? '' })
      : t('case.excellentUnmarkOk', { caseNo: props.caseNo ?? '' }))
    local.value = null
    emit('changed')
  } catch (e) {
    local.value = null
    ElMessage.error(bizErrorMsg(e, t('case.excellentFail')))
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <el-button
    v-if="visible"
    link
    :loading="busy"
    :disabled="markDisabled"
    class="excellent-btn"
    :class="{ 'excellent-on': show }"
    :data-test="`excellent-star-${entityId}`"
    :title="markDisabled ? t('case.excellentMarkDisabledTip') : (show ? t('case.excellentUnmark') : t('case.excellentMark'))"
    @click.stop="toggle"
  >
    <el-icon :size="18">
      <StarFilled v-if="show" data-test="excellent-star-on" />
      <Star v-else data-test="excellent-star-off" />
    </el-icon>
  </el-button>
</template>

<style scoped lang="scss">
.excellent-btn {
  padding: 2px 4px;

  .el-icon {
    color: var(--tp-text-secondary);
  }

  &.excellent-on .el-icon {
    color: var(--tp-warning);   
  }

  &:hover .el-icon {
    color: var(--tp-warning);
  }
}
</style>
