<!--
 Table baseline: border is on by default — Element Plus only
 activates header column-width dragging with border, so the shell enables it
 for all consumers. Pass :column-config (the useColumnConfig return value,
 the single shared piece — no second implementation) and dragged widths are
 written back and persisted automatically; sort-change is re-emitted so
 pages can wire server-side sorting at one point.
 Usage: <DataTablePage :data="rows" :column-config="colCfg" v-loading="loading"
 @selection-change="onSel" @sort-change="onSort"> <el-table-column .../> </DataTablePage>
-->
<template>
  <el-table
    ref="innerRef"
    v-bind="tableAttrs"
    @sort-change="onInnerSortChange"
    @header-dragend="onInnerHeaderDragEnd"
  >
    <slot />
    <template v-if="$slots.append" #append><slot name="append" /></template>
    <template #empty>
      <slot name="empty">
        <!-- Empty-flash suppression (site standard 2026-11-02, user order): while the
             first page load is in flight, never paint the "no data" copy — show the
             shared TableSkeleton inside the body area instead; the real EmptyState
             only renders once loading settled on zero rows. -->
        <TableSkeleton v-if="loading" :rows="3" class="dtp-initial-skeleton" data-test="dtp-initial-skeleton" />
        <EmptyState v-else :title="titleText" :hint="hintText" />
      </slot>
    </template>
  </el-table>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, useAttrs } from 'vue'
import { useI18n } from 'vue-i18n'
import type { TableInstance } from 'element-plus'

defineOptions({ inheritAttrs: false })

/**
 * Minimal contract of the column-config object this shell writes back to.
 * Matches the return shape of useColumnConfig (updateWidth is the only
 * member the shell needs; kept structural so pages pass the shared piece
 * without a second wrapper).
 */
interface ColumnConfigLike {
  updateWidth: (key: string, width: number) => void
}

const props = defineProps<{
  /** Loading flag: show "loading…" instead of "no data" before the first response. */
  loading?: boolean
  /** Override the default "no data" reason sentence. */
  emptyTitle?: string
  /** Way-out hint (optional, e.g. "click New Batch at the top-right to add one"). */
  emptyHint?: string
  /** Column-config object (useColumnConfig return value): when provided, dragged widths write back and persist. */
  columnConfig?: ColumnConfigLike | null
}>()

// sort-change/header-dragend are declared so the shell controls their dispatch:
// without the declaration they would sit in $attrs and reach el-table directly;
// with it, pages listening via @sort-change still fire through emit below.
const emit = defineEmits<{
  'sort-change': [payload: { column: unknown; prop: string; order: string | null }]
  'header-dragend': [newWidth: number, oldWidth: number, column: unknown]
}>()

const { t } = useI18n()
const attrs = useAttrs()
const innerRef = ref<TableInstance | null>(null)

// border defaults to true so every consumer gets header-drag activation for
// free; an explicit :border="false" from a page still wins (narrow-screen or
// read-only dialog tables opt out via the attr, not via scoped CSS forks).
const tableAttrs = computed(() => ({ border: true, ...attrs }))

function onInnerSortChange(payload: { column: unknown; prop: string; order: string | null }): void {
  emit('sort-change', payload)
}

/**
 * recurrence guard (dev-only): measure rendered sortable
 * header cells once the table has mounted. A header taller than the
 * single-line budget (~26px: 14px font + padding) means the label wrapped
 * to two lines because the column is narrower than
 * label width + 24px sort icon + 24px cell padding — the exact defect class
 * the site-wide E2E header gate catches. Warning in the console at
 * development time keeps new pages from re-introducing it. Same 26px budget
 * as frontend/tests/e2e/test_t409_header_gate.py (measurement authority).
 */
const SINGLE_LINE_HEADER_MAX_PX = 26

onMounted(async () => {
  if (!import.meta.env.DEV) return
  await nextTick()
  const root = (innerRef.value as unknown as { $el?: HTMLElement } | null)?.$el
  if (!(root instanceof HTMLElement)) return
  root.querySelectorAll('th.is-sortable').forEach((th) => {
    const cell = th.querySelector('.cell')
    if (!cell) return
    const rect = cell.getBoundingClientRect()
    if (rect.width === 0) return // not laid out yet (hidden tab/dialog)
    const h = Math.round(rect.height)
    if (h > SINGLE_LINE_HEADER_MAX_PX) {
      const label = (cell.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 24)
      console.warn(
        `[DataTablePage] sortable header "${label}" renders ${h}px tall at ` +
        `width ${Math.round(th.getBoundingClientRect().width)}px (single-line ` +
        `budget ${SINGLE_LINE_HEADER_MAX_PX}px): raise the column width to ` +
        `label + 24px sort icon + 24px padding, or drop sortable.`,
      )
    }
  })
})
function onInnerHeaderDragEnd(newWidth: number, oldWidth: number, column: unknown): void {
  const prop = (column as { property?: string } | null | undefined)?.property
  if (prop && props.columnConfig) props.columnConfig.updateWidth(prop, newWidth)
  emit('header-dragend', newWidth, oldWidth, column)
}

const titleText = computed(() => props.emptyTitle ?? t('common.noData'))
const hintText = computed(() => (props.loading ? t('common.loading') : props.emptyHint))

function clearSelection(): void { innerRef.value?.clearSelection() }
function toggleRowSelection(row: unknown, selected?: boolean): void { innerRef.value?.toggleRowSelection(row as never, selected) }
function toggleAllSelection(): void { innerRef.value?.toggleAllSelection() }
function setCurrentRow(row: unknown): void { innerRef.value?.setCurrentRow(row as never) }
function doLayout(): void { innerRef.value?.doLayout() }

defineExpose({ clearSelection, toggleRowSelection, toggleAllSelection, setCurrentRow, doLayout, columnConfig: computed(() => props.columnConfig ?? null) })
</script>
