
<template>
  <div class="row-actions" data-test="row-actions">
    <el-button
      v-for="a in visible"
      :key="a.key"
      link
      :type="a.type ?? 'primary'"
      :disabled="a.disabled"
      :data-test="`ra-${a.key}`"
      @click="fire(a)"
    >
      {{ a.label }}
    </el-button>
    <el-dropdown v-if="overflow.length" trigger="click" @command="onCmd">
      <el-button link type="primary" data-test="ra-more">
        {{ moreLabel || t('common.more') }}
        <el-icon class="row-actions__caret"><ArrowDown /></el-icon>
      </el-button>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item
            v-for="a in overflow"
            :key="a.key"
            :command="a.key"
            :disabled="a.disabled"
            :data-test="`ra-${a.key}`"
            :style="a.type === 'danger' ? 'color: var(--el-color-danger)' : undefined"
          >
            {{ a.label }}
          </el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import { ArrowDown } from "@element-plus/icons-vue";
import { ElMessageBox } from "element-plus";
import { useAuthStore } from "@/stores/auth";

export interface RowAction {
  key: string;
  label: string;
  
  type?: "primary" | "warning" | "danger" | "info" | "success";
  
  confirm?: string;
  
  disabled?: boolean;
  
  perm?: string;
}

const props = withDefaults(
  defineProps<{
    actions: RowAction[];
    
    max?: number;
    moreLabel?: string;
  }>(),
  { max: 3 }
);
const emit = defineEmits<{ (e: "command", key: string): void }>();
const auth = useAuthStore();
const { t } = useI18n();


const allowed = computed(() => props.actions.filter((a) => !a.perm || auth.hasPerm(a.perm)));

const visible = computed(() => allowed.value.slice(0, props.max));
const overflow = computed(() => allowed.value.slice(props.max));

function fire(a: RowAction): void {
  if (a.confirm) {
    ElMessageBox.confirm(a.confirm, t('components.confirmActionTitle'), { type: 'warning', confirmButtonText: t('common.confirm'), cancelButtonText: t('common.cancel') })
      .then(() => emit("command", a.key))
      .catch(() => undefined);
  } else {
    emit("command", a.key);
  }
}
function onCmd(key: string): void {
  const a = props.actions.find((x) => x.key === key);
  if (a) fire(a);
}
</script>

<style scoped>
.row-actions {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  vertical-align: middle;
}
/* Element Plus carries a global `.el-button + .el-button { margin-left: 12px }`.
   Inside this flex row that margin stacks onto the 4px gap for adjacent link
   buttons (16px) while a button followed by the "more" dropdown keeps the
   plain 4px gap, so identical actions landed at different x positions row by
   row (vision audit: delete sat ~8px further right on some rows). The flex
   gap is the single spacing channel here — zero the inherited margin once,
   in the shared component, for every page. */
.row-actions .el-button + .el-button {
  margin-left: 0;
}
.row-actions__caret {
  margin-left: 2px;
}
</style>
