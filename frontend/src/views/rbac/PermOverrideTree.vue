<template>
  <div class="perm-tree-wrap" data-test="perm-tree">
    <div class="perm-tree-toolbar">
      <el-input
        v-model="keyword"
        data-test="perm-search"
        :placeholder="t('rbac.permSearchPlaceholder')"
        :prefix-icon="Search"
        clearable
      />
      <span class="perm-override-count" data-test="perm-override-count">
        {{ t('rbac.permOverrideCount', { n: overrideCount }) }}
      </span>
    </div>
    <div class="perm-tree-scroll">
      <el-empty v-if="filteredGroups.length === 0" :description="t('common.emptyNoData')" />
      <section v-for="g in filteredGroups" :key="g.domain" class="perm-domain">
        <header class="perm-domain-header">
          <el-checkbox
            :model-value="domainChecked(g)"
            :indeterminate="domainIndeterminate(g)"
            :data-test="`perm-domain-all-${g.domain}`"
            @change="(v: boolean) => toggleDomain(g, v)"
          >
            <span class="perm-domain-label">{{ g.domainLabel }}</span>
          </el-checkbox>
          <span class="perm-domain-count">{{ g.items.length }}</span>
        </header>
        <div v-for="item in g.items" :key="item.key" class="perm-item">
          <div class="perm-item-text">
            <span class="perm-item-label">{{ item.label }}</span>
            <code class="perm-item-key">{{ item.key }}</code>
          </div>
          <el-radio-group
            :model-value="effectOf(item.key)"
            size="small"
            :data-test="`perm-effect-${item.key}`"
            @change="(v: number) => setEffect(item.key, v)"
          >
            <el-radio-button :value="PERM_EFFECT.INHERIT">{{ t('rbac.permInherit') }}</el-radio-button>
            <el-radio-button :value="PERM_EFFECT.ALLOW">{{ t('rbac.permAllow') }}</el-radio-button>
            <el-radio-button :value="PERM_EFFECT.DENY">{{ t('rbac.permDeny') }}</el-radio-button>
          </el-radio-group>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, triggerRef } from 'vue'
import { useI18n } from 'vue-i18n'
import { Search } from '@element-plus/icons-vue'
import { PERM_EFFECT } from '@/api/rbac'
import type { PermGroupView } from '@/api/rbac'

const props = defineProps<{ groups: PermGroupView[]; modelValue: Record<string, number> }>()
const emit = defineEmits<{ 'update:modelValue': [value: Record<string, number>] }>()

const { t } = useI18n()
const keyword = ref('')


const effects = ref<Record<string, number>>({ ...props.modelValue })

function emitEffects(): void {
  emit('update:modelValue', { ...effects.value })
}

function setEffect(key: string, effect: number): void {
  if (effect === PERM_EFFECT.INHERIT) {
    delete effects.value[key]
  } else {
    effects.value[key] = effect
  }
  triggerRef(effects)
  emitEffects()
}

function effectOf(key: string): number {
  return effects.value[key] ?? PERM_EFFECT.INHERIT
}

const overrideCount = computed(() => Object.keys(effects.value).length)

const filteredGroups = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return props.groups
  const groups: PermGroupView[] = []
  for (const g of props.groups) {
    if (g.domainLabel.toLowerCase().includes(kw)) {
      groups.push(g)
      continue
    }
    const items = g.items.filter(
      (i) => i.label.toLowerCase().includes(kw) || i.key.toLowerCase().includes(kw),
    )
    if (items.length > 0) {
      groups.push({ ...g, items })
    }
  }
  return groups
})

function domainChecked(g: PermGroupView): boolean {
  return g.items.length > 0 && g.items.every((i) => effectOf(i.key) === PERM_EFFECT.ALLOW)
}

function domainIndeterminate(g: PermGroupView): boolean {
  const n = g.items.filter((i) => effectOf(i.key) !== PERM_EFFECT.INHERIT).length
  return n > 0 && !domainChecked(g)
}


function toggleDomain(g: PermGroupView, checked: boolean): void {
  for (const item of g.items) {
    setEffect(item.key, checked ? PERM_EFFECT.ALLOW : PERM_EFFECT.INHERIT)
  }
}
</script>

<style scoped lang="scss">
.perm-tree-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
}

.perm-override-count {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--tp-text-secondary, #8b9bb4);
}

.perm-tree-scroll {
  max-height: 480px;
  overflow-y: auto;
  padding-right: 4px;
}

.perm-domain {
  margin-bottom: 16px;

  .perm-domain-header {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 6px 8px;
    background: var(--tp-bg-elevated, rgba(56, 189, 248, 0.06));
    border-radius: 6px;
    margin-bottom: 4px;

    .perm-domain-label {
      font-weight: 600;
      font-size: 13px;
    }

    .perm-domain-count {
      font-size: 12px;
      color: var(--tp-text-secondary, #8b9bb4);
    }
  }
}

.perm-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 4px 8px 4px 28px;
  border-radius: 4px;

  &:hover {
    background: var(--tp-bg-hover, rgba(139, 155, 180, 0.08));
  }

  .perm-item-text {
    display: flex;
    align-items: baseline;
    gap: 8px;
    min-width: 0;

    .perm-item-label {
      font-size: 13px;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    .perm-item-key {
      font-family: 'JetBrains Mono', Consolas, monospace;
      font-size: 11px;
      color: var(--tp-text-secondary, #8b9bb4);
      white-space: nowrap;
    }
  }
}
</style>
