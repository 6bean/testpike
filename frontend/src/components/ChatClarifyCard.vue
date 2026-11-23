<script setup lang="ts">
/**
 * Clarification question card (AX batch , dialog face of the
 * clarify frame).
 *
 * The built-in chat.clarify tool suspended the run on a question the model
 * genuinely could not answer; this card is the one door to un-suspend it:
 * one press per option, the panel composes the pressed option into the
 * user's own next message through the regular send door (same posture as
 * the plan card's confirm — no auto-continue channel exists anywhere else,
 * the write-chain confirmation gate stays a human click). A second press is
 * physically impossible: the first seals the card into its receipt posture.
 *
 * Native buttons on purpose (precedent: ChatPlanCard): every option is its
 * own button label, every disabled posture carries a stated reason, so the
 * Issues panel stays a quiet monitor.
 */
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ClarifyCard } from '@/composables/chatDockReducer'

const props = defineProps<{ card: ClarifyCard; running: boolean }>()
const emit = defineEmits<{ (e: 'answer', option: string): void }>()
const { t } = useI18n()

/** Local working state: the journaled frame never mutates — the user's
 *  pressed option IS their next message. */
const answered = ref('')

const canAnswer = computed(() => !props.running && answered.value === '')
/** Why the doors are shut, in the user's words (vision precedent: a disabled
 *  control without a stated reason reads as broken). Empty when usable. */
const disabledReason = computed(() => {
  if (answered.value !== '') return t('layout.chatDock.clarify.disabledSealed', { option: answered.value })
  if (props.running) return t('layout.chatDock.clarify.disabledRunning')
  return ''
})

function answer(option: string) {
  if (!canAnswer.value) return
  answered.value = option
  emit('answer', option)
}
</script>

<template>
  <section class="chat-clarify-card" :aria-label="t('layout.chatDock.clarify.title')" data-test="chat-clarify-card">
    <div class="chat-clarify-head">
      <span class="chat-clarify-title">{{ t('layout.chatDock.clarify.title') }}</span>
      <span v-if="card.missingSlot" class="chat-clarify-slot">{{ t('layout.chatDock.clarify.slotHint', { slot: card.missingSlot }) }}</span>
    </div>
    <p class="chat-clarify-question" data-test="chat-clarify-question">{{ card.question }}</p>
    <div class="chat-clarify-options">
      <button
        v-for="(o, i) in card.options"
        :key="i"
        type="button"
        class="chat-clarify-option"
        :data-test="`chat-clarify-option-${i}`"
        :disabled="!canAnswer"
        :title="disabledReason || undefined"
        @click="answer(o)"
      >{{ o }}</button>
    </div>
    <p v-if="answered === ''" class="chat-clarify-hint">{{ t('layout.chatDock.clarify.hint') }}</p>
    <p v-else class="chat-clarify-receipt" data-test="chat-clarify-receipt">
      {{ t('layout.chatDock.clarify.receipt', { option: answered }) }}
    </p>
  </section>
</template>

<style scoped>
.chat-clarify-card {
  margin: 8px 0;
  padding: 10px 12px;
  border: 1px solid var(--el-color-primary-light-7);
  border-radius: 8px;
  background: var(--el-fill-color-light);
}
.chat-clarify-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 4px;
}
.chat-clarify-title {
  font-weight: 600;
}
.chat-clarify-slot {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.chat-clarify-question {
  margin: 0 0 8px;
}
.chat-clarify-options {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.chat-clarify-option {
  padding: 4px 14px;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  background: var(--el-bg-color);
  color: var(--el-text-color-primary);
  cursor: pointer;
}
.chat-clarify-option:hover:not(:disabled) {
  border-color: var(--el-color-primary);
  color: var(--el-color-primary);
}
/* Disabled must READ as disabled (vision precedent, plan card): swap the
   fill to the neutral posture instead of fading an otherwise crisp control. */
.chat-clarify-option:disabled {
  background: var(--el-fill-color-dark);
  color: var(--el-text-color-disabled);
  cursor: not-allowed;
}
.chat-clarify-hint {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.chat-clarify-receipt {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--el-color-success);
}
</style>
