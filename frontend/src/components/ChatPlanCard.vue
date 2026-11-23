<script setup lang="ts">
/**
 * Multi-step plan card .
 *
 * The built-in plan.propose tool executed NOTHING server-side; this card is
 * the user's one door to start the chain: tick the steps to run, reorder
 * them with the move buttons, then confirm. Confirming only emits the
 * chosen order — the panel composes the user's own next question through
 * the regular send door (no auto-run channel, the write-chain confirmation
 * gate stays a human click). A re-confirmation is physically impossible:
 * the first press seals the card into its receipt posture.
 *
 * Native controls on purpose (same posture as the flow-guide chip row):
 * every checkbox carries a real label association and every icon button
 * carries a spoken aria-label, so the Issues panel stays a quiet monitor.
 */
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { PlanCard } from '@/composables/chatDockReducer'

const props = defineProps<{ plan: PlanCard; running: boolean }>()
const emit = defineEmits<{ (e: 'confirm', titles: string[]): void }>()
const { t } = useI18n()

interface PlanRow {
  key: number
  title: string
  checked: boolean
}

/** Local working copy: order and ticks are display state, the journaled
 *  frame never mutates — the user's confirmation IS the new question. */
const rows = ref<PlanRow[]>(
  props.plan.steps.map((s) => ({ key: s.index, title: s.title, checked: true })),
)
const confirmed = ref(false)

const checkedCount = computed(() => rows.value.filter((r) => r.checked).length)
const canConfirm = computed(
  () => !props.running && !confirmed.value && checkedCount.value > 0,
)
/** Why the door is shut, in the user's words (vision round: a disabled
 *  button without a stated reason reads as a broken one). Empty when the
 *  button is usable so no tooltip shadows an enabled control. */
const disabledReason = computed(() => {
  if (confirmed.value) return t('layout.chatDock.plan.disabledSealed')
  if (props.running) return t('layout.chatDock.plan.disabledRunning')
  if (checkedCount.value === 0) return t('layout.chatDock.plan.disabledEmpty')
  return ''
})

function move(index: number, delta: number) {
  const target = index + delta
  if (confirmed.value || target < 0 || target >= rows.value.length) return
  const next = [...rows.value]
  const [row] = next.splice(index, 1)
  next.splice(target, 0, row)
  rows.value = next
}

function confirm() {
  if (!canConfirm.value) return
  confirmed.value = true
  emit('confirm', rows.value.filter((r) => r.checked).map((r) => r.title))
}
</script>

<template>
  <section class="chat-plan-card" :aria-label="t('layout.chatDock.plan.title')" data-test="chat-plan-card">
    <div class="chat-plan-head">
      <span class="chat-plan-title">{{ t('layout.chatDock.plan.title') }}</span>
      <span class="chat-plan-count">{{ t('layout.chatDock.plan.count', { n: rows.length }) }}</span>
    </div>
    <ol class="chat-plan-steps">
      <li v-for="(r, i) in rows" :key="r.key" class="chat-plan-step">
        <input
          :id="`chat-plan-ck-${r.key}`"
          v-model="r.checked"
          type="checkbox"
          class="chat-plan-check"
          :disabled="confirmed"
        />
        <label class="chat-plan-label" :for="`chat-plan-ck-${r.key}`">
          <span class="chat-plan-no">{{ i + 1 }}</span>{{ r.title }}
        </label>
        <span class="chat-plan-move">
          <button
            type="button"
            class="chat-plan-move-btn"
            :aria-label="t('layout.chatDock.plan.up')"
            :disabled="i === 0 || confirmed"
            :data-test="`chat-plan-up-${i}`"
            @click="move(i, -1)"
          >↑</button>
          <button
            type="button"
            class="chat-plan-move-btn"
            :aria-label="t('layout.chatDock.plan.down')"
            :disabled="i === rows.length - 1 || confirmed"
            :data-test="`chat-plan-down-${i}`"
            @click="move(i, 1)"
          >↓</button>
        </span>
      </li>
    </ol>
    <p v-if="!confirmed" class="chat-plan-hint">{{ t('layout.chatDock.plan.hint') }}</p>
    <button
      type="button"
      class="chat-plan-confirm"
      data-test="chat-plan-confirm"
      :disabled="!canConfirm"
      :title="disabledReason || undefined"
      @click="confirm"
    >{{ t('layout.chatDock.plan.confirm') }}</button>
    <p v-if="confirmed" class="chat-plan-receipt" data-test="chat-plan-receipt">
      {{ t('layout.chatDock.plan.receipt', { n: checkedCount }) }}
    </p>
  </section>
</template>

<style scoped>
.chat-plan-card {
  margin: 8px 0;
  padding: 10px 12px;
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  background: var(--el-fill-color-light);
}
.chat-plan-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 6px;
}
.chat-plan-title {
  font-weight: 600;
}
.chat-plan-count {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.chat-plan-steps {
  margin: 0;
  padding: 0;
  list-style: none;
}
.chat-plan-step {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 2px 0;
}
.chat-plan-check {
  flex: none;
}
.chat-plan-label {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: baseline;
  gap: 6px;
  cursor: pointer;
}
.chat-plan-no {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.chat-plan-move {
  display: flex;
  gap: 4px;
  flex: none;
}
.chat-plan-move-btn {
  padding: 0 6px;
  border: 1px solid var(--el-border-color);
  border-radius: 4px;
  background: transparent;
  color: var(--el-text-color-regular);
  cursor: pointer;
}
.chat-plan-move-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
.chat-plan-hint {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.chat-plan-confirm {
  margin-top: 8px;
  padding: 4px 14px;
  border: none;
  border-radius: 6px;
  background: var(--el-color-primary);
  color: #fff;
  cursor: pointer;
}
/* Disabled must READ as disabled: on a primary-blue button an opacity wash
   still scans as "bright blue, clickable" (vision rounds 1+2 both flagged
   it). Swap the fill to the neutral posture instead of fading it. */
.chat-plan-confirm:disabled {
  background: var(--el-fill-color-dark);
  color: var(--el-text-color-disabled);
  cursor: not-allowed;
}
.chat-plan-receipt {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--el-color-success);
}
</style>
