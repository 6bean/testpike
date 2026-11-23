<script setup lang="ts">
/**
 * Compact goal-progress card .
 *
 * The card is a persistent state surface of the session, not a message: one
 * card (the newest) tells the whole story at the header — done/total count,
 * a progress strip, the current step and failed/pending badges — while the
 * step detail stays folded by default. Worst scale (a 50-step ledger) then
 * costs a single row until the user asks for the list.
 *
 * The server's open-tail sentence is intentionally NOT rendered any more
 * : the bottom paragraph duplicated the header count, and its
 * that with automatic continuation. The reducer still folds `sentence`
 * because the reload contract uses it as the card's door.
 *
 * Native controls (same posture as the plan card): the toggle is a real
 * button carrying aria-expanded/aria-controls, the strip is a role
 * progressbar with spoken values, every state chip rides a state word.
 */
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { GoalProgress, GoalStepState } from '@/composables/chatDockReducer'

const props = defineProps<{ goal: GoalProgress; running: boolean }>()
const { t } = useI18n()

const collapsed = ref(true)
const glyph: Record<GoalStepState, string> = {
  pending: '○',
  done: '✓',
  skipped: '⊘',
  blocked: '✕',
}

const steps = computed(() => props.goal.steps)
const total = computed(() => steps.value.length)
const doneCount = computed(
  () => steps.value.filter((s) => s.state === 'done').length,
)
const blockedCount = computed(
  () => steps.value.filter((s) => s.state === 'blocked').length,
)
const pendingCount = computed(
  () => steps.value.filter((s) => s.state === 'pending').length,
)
/** First step the ledger has not closed = what the run works on now. */
const currentIndex = computed(() =>
  steps.value.findIndex((s) => s.state === 'pending' || s.state === 'blocked'),
)
const currentTitle = computed(() =>
  currentIndex.value >= 0 ? steps.value[currentIndex.value].title : '',
)
/**
 * Display state of one row: the wire vocabulary is the ledger's
 * only ever rides the first open row while a turn is streaming. A blocked
 * row keeps its failure wording even under the overlay — the card must
 * never paint a failed step as in progress.
 */
function displayState(state: GoalStepState, index: number): GoalStepState | 'running' {
  if (props.running && state === 'pending' && index === currentIndex.value) {
    return 'running'
  }
  return state
}

function stateWord(state: GoalStepState | 'running'): string {
  return t(`layout.chatDock.goal.state.${state}`)
}
</script>

<template>
  <section class="chat-goal-card" :aria-label="t('layout.chatDock.goal.title')" data-test="chat-goal-card">
    <button
      type="button"
      class="chat-goal-toggle"
      data-test="chat-goal-toggle"
      :aria-expanded="!collapsed"
      aria-controls="chat-goal-body"
      @click="collapsed = !collapsed"
    >
      <span class="chat-goal-title">{{ t('layout.chatDock.goal.title') }}</span>
      <span class="chat-goal-count" data-test="chat-goal-count">
        {{ t('layout.chatDock.goal.count', { done: doneCount, total }) }}
      </span>
      <!-- State badges form one group behind a divider :
 the count answers "how far", the group answers "what is left and
 what broke" — reading them as a single undivided run of chips was
 the grouping defect. The divider only appears with a badge. -->
      <span v-if="pendingCount > 0 || blockedCount > 0" class="chat-goal-badges">
        <span v-if="pendingCount > 0" class="chat-goal-badge chat-goal-badge-pending" data-test="chat-goal-badge-pending">
          {{ t('layout.chatDock.goal.badgePending', { n: pendingCount }) }}
        </span>
        <span v-if="blockedCount > 0" class="chat-goal-badge chat-goal-badge-blocked" data-test="chat-goal-badge-blocked">
          {{ t('layout.chatDock.goal.badgeBlocked', { n: blockedCount }) }}
        </span>
      </span>
      <span class="chat-goal-chevron" aria-hidden="true">{{ collapsed ? '▸' : '▾' }}</span>
    </button>
    <div class="chat-goal-headline">
      <div
        class="chat-goal-progress"
        data-test="chat-goal-progress"
        role="progressbar"
        :aria-valuenow="doneCount"
        aria-valuemin="0"
        :aria-valuemax="total"
        :aria-label="t('layout.chatDock.goal.count', { done: doneCount, total })"
      >
        <span class="chat-goal-progress-fill" :style="{ width: total ? `${(doneCount / total) * 100}%` : '0%' }" />
      </div>
      <span v-if="currentTitle" class="chat-goal-current" data-test="chat-goal-current">
        {{ t('layout.chatDock.goal.current', { step: currentTitle }) }}
      </span>
      <!-- Cost transparency : the consumed figure is the
 server's session ledger folded at the one folding point — this
 component never re-computes a cost number. No figure (old
 server, or the door honestly could not read it) = no chip,
 never a fabricated zero. Remaining steps ride the SAME
 openCount the fold pinned. -->
      <span
        v-if="goal.consumedTokens !== null"
        class="chat-goal-cost"
        data-test="chat-goal-cost"
      >
        {{ t('layout.chatDock.goal.cost', { tokens: goal.consumedTokens }) }}
      </span>
      <span
        v-if="goal.openCount > 0"
        class="chat-goal-remaining"
        data-test="chat-goal-remaining"
      >
        {{ t('layout.chatDock.goal.remaining', { n: goal.openCount }) }}
      </span>
    </div>
    <ol v-show="!collapsed" id="chat-goal-body" class="chat-goal-steps">
      <li
        v-for="(s, i) in steps"
        :key="i"
        class="chat-goal-step"
        :class="`is-${displayState(s.state, i)}`"
        :data-test="`chat-goal-step-${i}`"
      >
        <span class="chat-goal-glyph" :class="`is-${s.state}`" aria-hidden="true">{{ glyph[s.state] }}</span>
        <span class="chat-goal-step-title">{{ s.title }}</span>
        <span class="chat-goal-state" :class="`is-${displayState(s.state, i)}`" data-test="chat-goal-state">
          {{ stateWord(displayState(s.state, i)) }}
        </span>
      </li>
    </ol>
  </section>
</template>

<style scoped>
.chat-goal-card {
  /* Facing the composer band below and the answer bubble above keeps the
     theme band gap (single source, never a page-level number). */
  margin: var(--tp-band-gap, 16px) 0;
  padding: 8px 12px;
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  background: var(--el-fill-color-light);
}
.chat-goal-toggle {
  display: flex;
  /* Centered (not baseline) so the chevron glyph and the 18px chips sit on
     the same optical line instead of drifting ~2px under the text baseline. */
  align-items: center;
  gap: 8px;
  width: 100%;
  min-height: 24px;
  padding: 0;
  border: none;
  background: transparent;
  cursor: pointer;
  text-align: left;
}
.chat-goal-title {
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.chat-goal-count {
  font-size: 12px;
  line-height: 18px;
  color: var(--el-text-color-secondary);
}
.chat-goal-badges {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  margin-left: 4px;
  padding-left: 12px;
  border-left: 1px solid var(--el-border-color-lighter);
}
.chat-goal-badge {
  padding: 0 8px;
  border-radius: 999px;
  font-size: 12px;
  line-height: 18px;
  white-space: nowrap;
}
.chat-goal-badge-pending {
  color: var(--el-color-info);
  background: var(--el-color-info-light-9);
}
.chat-goal-badge-blocked {
  color: var(--el-color-danger);
  background: var(--el-color-danger-light-9);
}
.chat-goal-chevron {
  margin-left: auto;
  font-size: 12px;
  line-height: 18px;
  color: var(--el-text-color-secondary);
}
.chat-goal-headline {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 10px;
}
.chat-goal-progress {
  flex: 1;
  min-width: 0;
  height: 6px;
  border-radius: 999px;
  background: var(--el-border-color-lighter);
  overflow: hidden;
}
.chat-goal-progress-fill {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: var(--el-color-primary);
  transition: width 0.2s ease;
}
.chat-goal-current {
  flex: 0 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  line-height: 16px;
  color: var(--el-text-color-primary);
}
/* Cost chips sit at the headline's end as secondary information —
   muted tone, no wrap; the figures come from the folded card data. */
.chat-goal-cost,
.chat-goal-remaining {
  flex: 0 0 auto;
  white-space: nowrap;
  font-size: 12px;
  line-height: 16px;
  color: var(--el-text-color-secondary);
}
.chat-goal-steps {
  margin: 10px 0 0;
  /* Head block and detail block keep an explicit divider plus breathing
 room : background colour alone read as one run. */
  padding: 8px 0 0;
  border-top: 1px solid var(--el-border-color-lighter);
  list-style: none;
  /* Worst-scale containment ( vision re-check, kept through the
 rebuild): a 50-step ledger scrolls inside the card instead of pushing
 the composer below the fold. */
  max-height: min(50vh, 480px);
  overflow-y: auto;
  overscroll-behavior: contain;
}
.chat-goal-step {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 2px 0;
}
.chat-goal-glyph {
  flex: none;
  width: 16px;
  text-align: center;
}
.chat-goal-glyph.is-done {
  color: var(--el-color-success);
}
.chat-goal-glyph.is-pending {
  color: var(--el-text-color-secondary);
}
.chat-goal-glyph.is-skipped {
  color: var(--el-text-color-disabled);
}
.chat-goal-glyph.is-blocked {
  color: var(--el-color-danger);
}
.chat-goal-step-title {
  flex: 1;
  min-width: 0;
}
.chat-goal-step.is-done .chat-goal-step-title {
  color: var(--el-text-color-secondary);
}
.chat-goal-state {
  flex: none;
  padding: 0 8px;
  border-radius: 999px;
  font-size: 12px;
  line-height: 18px;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color);
}
.chat-goal-state.is-done {
  color: var(--el-color-success);
  background: var(--el-color-success-light-9);
}
.chat-goal-state.is-running {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}
.chat-goal-state.is-skipped {
  color: var(--el-text-color-disabled);
}
.chat-goal-state.is-blocked {
  color: var(--el-color-danger);
  background: var(--el-color-danger-light-9);
}
</style>
