
<template>
  <ol
    v-if="core.ordered.value.length"
    ref="listEl"
    class="step-drag-list"
    data-test="flow-reorder-list"
    role="list"
    :aria-label="ariaLabel || t('components.dragListAria')"
    @dragover="onEdgeScroll"
  >
    <li
      v-for="(item, idx) in core.ordered.value"
      :key="`${core.dragEpoch.value}-${idx}`"
      class="reorder-item"
      :class="{
        'is-dragging': core.dragIndex.value === idx,
        'is-grabbed': core.keyboardGrab.value === idx,
        'is-moved': core.movedSet.value.has(idx),
      }"
      draggable="true"
      tabindex="0"
      :data-test="`flow-reorder-item-${idx + 1}`"
      :aria-label="t('components.dragItemAria', { n: idx + 1, desc: item.description })"
      :aria-roledescription="t('components.dragRoleDesc')"
      :aria-grabbed="core.dragIndex.value === idx || core.keyboardGrab.value === idx"
      @dragstart="core.beginDrag(idx)"
      @dragover.prevent="core.dragHoverTo(idx)"
      @drop.prevent="core.endDrag()"
      @dragend="core.endDrag()"
      @keydown="onKeydown($event, idx)"
    >
      <el-icon class="reorder-grip"><Rank /></el-icon>
      <span class="reorder-idx">{{ idx + 1 }}</span>
      <span class="reorder-desc" :title="item.description">{{ item.description }}</span>
      <el-tag v-if="item.isAssert === 1" size="small" type="success">{{ t('components.assertTag') }}</el-tag>
      
      <el-button
        v-if="renamable"
        link
        type="primary"
        size="small"
        :data-test="`step-rename-btn-${idx + 1}`"
        :aria-label="t('components.renameStepAria', { n: idx + 1 })"
        @click.stop="emit('rename', idx)"
      >{{ t('components.renameStep') }}</el-button>
    </li>
  </ol>
  <div v-else class="reorder-empty" data-test="flow-reorder-empty">{{ emptyText || t('components.dragListEmpty') }}</div>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { Rank } from '@element-plus/icons-vue'
import type { StepDragSortCore } from '@/composables/useStepDragSort'

const { t } = useI18n()

const props = withDefaults(
  defineProps<{
    
    core: StepDragSortCore
    
    emptyText?: string
    
    ariaLabel?: string
    
    renamable?: boolean
  }>(),
  { renamable: false },
)

const emit = defineEmits<{
  
  (e: 'rename', idx: number): void
}>()

const listEl = ref<HTMLElement | null>(null)


function onKeydown(e: KeyboardEvent, idx: number): void {
  const core = props.core
  if (e.key === ' ') {
    e.preventDefault()
    core.toggleKeyboardGrab(idx)
    return
  }
  if (core.keyboardGrab.value < 0) return
  if (e.key === 'Escape') {
    core.cancelKeyboardGrab()
    return
  }
  if (e.key === 'ArrowUp' || e.key === 'ArrowDown') {
    e.preventDefault()
    core.keyboardMove(e.key === 'ArrowUp' ? -1 : 1)
    nextTick(() => {
      const items = listEl.value?.querySelectorAll<HTMLElement>('.reorder-item')
      const target = items?.[core.keyboardGrab.value]
      target?.focus()
      target?.scrollIntoView({ block: 'nearest' })
    })
  }
}


let scrollRaf = 0
let scrollDir = 0
function onEdgeScroll(e: DragEvent): void {
  const el = listEl.value
  if (!el || props.core.dragIndex.value < 0) return
  const rect = el.getBoundingClientRect()
  const EDGE = 24
  const prev = scrollDir
  if (e.clientY - rect.top < EDGE) scrollDir = -1
  else if (rect.bottom - e.clientY < EDGE) scrollDir = 1
  else scrollDir = 0
  if (scrollDir !== 0 && !scrollRaf) {
    const tick = () => {
      if (!scrollDir || !listEl.value) {
        scrollRaf = 0
        return
      }
      listEl.value.scrollTop += scrollDir * 6
      scrollRaf = requestAnimationFrame(tick)
    }
    scrollRaf = requestAnimationFrame(tick)
  } else if (scrollDir === 0 && scrollRaf) {
    cancelAnimationFrame(scrollRaf)
    scrollRaf = 0
  }
  void prev
}

onBeforeUnmount(() => {
  if (scrollRaf) cancelAnimationFrame(scrollRaf)
})
</script>

<style scoped>
.step-drag-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 360px;
  overflow-y: auto;
}
.reorder-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  background: var(--el-fill-color-blank);
  cursor: grab;
  font-size: 13px;
  transition: border-color 0.15s, opacity 0.15s;
}
.reorder-item:hover {
  border-color: var(--el-color-primary);
}
.reorder-item:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 1px;
}
.reorder-item.is-dragging {
  opacity: 0.5;
}

.reorder-item.is-dragging::before,
.reorder-item.is-grabbed::before {
  content: '';
  position: absolute;
  left: -1px;
  top: -1px;
  bottom: -1px;
  width: 3px;
  border-radius: 2px;
  background: var(--el-color-primary);
}
.reorder-item.is-grabbed {
  border-color: var(--el-color-primary);
  box-shadow: 0 0 0 1px var(--el-color-primary);
}
.reorder-item.is-moved {
  border-color: var(--el-color-warning);
}
.reorder-grip {
  color: var(--el-text-color-placeholder);
  flex-shrink: 0;
}
.reorder-idx {
  flex-shrink: 0;
  width: 18px;
  text-align: right;
  color: var(--el-text-color-secondary);
}
.reorder-desc {
  flex: 1;
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.reorder-empty {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  padding: 8px 0;
}
</style>
