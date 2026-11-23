<!-- Shared ordered-anchor editor (WE batch S0 . One implementation
 consumed by both the create form and the anchor-edit dialog (R40: no
 second copy of the reorder/strength logic). Order is meaningful: the
 runtime tries anchors top-down, so up/down controls are first-class. -->
<template>
  <div class="anchor-editor" data-test="anchor-editor">
    <div v-if="rows.length === 0" class="anchor-empty">
      {{ t('webElement.anchorEditor.empty') }}
    </div>
    <div v-for="(row, idx) in rows" :key="row.key" class="anchor-row">
      <el-select
        v-model="row.type"
        class="a-type"
        :placeholder="t('webElement.anchorEditor.type')"
        data-test="anchor-type"
      >
        <el-option v-for="at in WEB_ELEMENT_ANCHOR_TYPES" :key="at" :label="at" :value="at" />
      </el-select>
      <el-input
        v-model="row.value"
        class="a-value"
        :placeholder="t('webElement.anchorEditor.value')"
        data-test="anchor-value"
      />
      <el-input-number
        v-model="row.strength"
        class="a-strength"
        :min="0"
        :max="1"
        :step="0.1"
        :precision="2"
        :controls-position="'right'"
        :placeholder="t('webElement.anchorEditor.strength')"
        data-test="anchor-strength"
      />
      <el-input
        v-model="row.note"
        class="a-note"
        :placeholder="t('webElement.anchorEditor.note')"
        maxlength="200"
        data-test="anchor-note"
      />
      <div class="a-actions">
        <el-button
          :icon="Top"
          :disabled="idx === 0"
          circle
          size="small"
          :title="t('webElement.anchorEditor.moveUp')"
          data-test="anchor-up"
          @click="move(idx, -1)"
        />
        <el-button
          :icon="Bottom"
          :disabled="idx === rows.length - 1"
          circle
          size="small"
          :title="t('webElement.anchorEditor.moveDown')"
          data-test="anchor-down"
          @click="move(idx, 1)"
        />
        <el-button
          :icon="Delete"
          circle
          size="small"
          type="danger"
          :title="t('webElement.anchorEditor.remove')"
          data-test="anchor-remove"
          @click="rows.splice(idx, 1)"
        />
      </div>
    </div>
    <el-button
      :icon="Plus"
      class="anchor-add"
      data-test="anchor-add"
      @click="append()"
    >
      {{ t('webElement.anchorEditor.add') }}
    </el-button>
  </div>
</template>

<script setup lang="ts">
import { reactive, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { Plus, Delete, Top, Bottom } from '@element-plus/icons-vue'
import { WEB_ELEMENT_ANCHOR_TYPES, type WebElementAnchor } from '@/api/webElements'

const props = defineProps<{ modelValue: WebElementAnchor[] }>()
const emit = defineEmits<{ 'update:modelValue': [WebElementAnchor[]] }>()

const { t } = useI18n()

interface AnchorRow extends WebElementAnchor {
  key: number
}

let seq = 0
const rows = reactive<AnchorRow[]>([])

function hydrate(list: WebElementAnchor[]) {
  rows.splice(0, rows.length, ...list.map((a) => ({ ...a, key: (seq += 1) })))
}
hydrate(props.modelValue ?? [])

watch(
  rows,
  () => {
    emit(
      'update:modelValue',
      rows.map(({ type, value, strength, note }) => ({ type, value, strength, note })),
    )
  },
  { deep: true },
)

function append() {
  rows.push({ key: (seq += 1), type: 'css', value: '', strength: 0.8, note: '' })
}

function move(idx: number, delta: number) {
  const to = idx + delta
  if (to < 0 || to >= rows.length) return
  const [row] = rows.splice(idx, 1)
  rows.splice(to, 0, row)
}

defineExpose({ rows })
</script>

<style scoped>
.anchor-editor {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.anchor-empty {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.anchor-row {
  display: grid;
  grid-template-columns: 120px minmax(0, 1fr) 120px minmax(0, 1fr) auto;
  gap: 8px;
  align-items: center;
}
.a-actions {
  display: flex;
  gap: 4px;
}
.anchor-add {
  align-self: flex-start;
}
</style>
