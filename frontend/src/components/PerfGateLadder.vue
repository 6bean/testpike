<script setup lang="ts">
/**
 * CF-8 explainable safety-gate ladder.
 * Renders every gate door of a perf launch precheck as its own traffic-light row
 * (PASS / FAIL / SKIP), instead of only surfacing the first rejection. A FAIL row
 * shows what is missing, who has the authority to fix it, and the in-app entry —
 * the three-part explanation the operator needs to act immediately.
 * Labels/details/fixBy/entryPath arrive localized from the server (display values,
 * not machine keys); every UI caption in this component comes from locale.
 */
import { useI18n } from 'vue-i18n'
import type { PerfGateStep } from '@/api/perf'

const props = defineProps<{ steps: PerfGateStep[] | null | undefined }>()
const { t } = useI18n()

function stateIcon(state: string): string {
  if (state === 'PASS') return '🟢'
  if (state === 'FAIL') return '🔴'
  return '⚪'
}
</script>

<template>
  <div v-if="props.steps && props.steps.length" class="pgl" data-test="pw-gate-ladder">
    <p class="pgl__title">{{ t('perf.ladder.title') }}</p>
    <ol class="pgl__rows">
      <li
        v-for="s in props.steps"
        :key="s.code"
        class="pgl__row"
        :class="`pgl__row--${s.state.toLowerCase()}`"
        :data-test="`pw-gate-step-${s.code}`"
      >
        <span class="pgl__dot">{{ stateIcon(s.state) }}</span>
        <div class="pgl__body">
          <p class="pgl__label">{{ s.label }}</p>
          <p v-if="s.detail" class="pgl__detail">{{ s.detail }}</p>
          <p v-if="s.state === 'FAIL' && s.fixBy" class="pgl__fix" data-test="pw-gate-fixby">
            {{ t('perf.ladder.fixBy') }}{{ s.fixBy }}
          </p>
          <p v-if="s.state === 'FAIL' && s.entryPath" class="pgl__entry">
            {{ t('perf.ladder.entry') }}{{ s.entryPath }}
          </p>
        </div>
      </li>
    </ol>
  </div>
</template>

<style scoped>
.pgl { margin-top: 10px; }
.pgl__title { margin: 0 0 6px; font-size: 12px; font-weight: 600; color: var(--el-text-color-secondary); }
.pgl__rows { margin: 0; padding: 0; list-style: none; display: flex; flex-direction: column; gap: 6px; }
.pgl__row { display: flex; gap: 8px; align-items: flex-start; padding: 6px 8px; border-radius: 6px; border: 1px solid var(--el-border-color-lighter); font-size: 12px; }
.pgl__row--fail { border-color: var(--el-color-danger); background: var(--el-color-danger-light-9); }
.pgl__row--pass { border-color: var(--el-color-success-light-5); }
.pgl__dot { flex: none; line-height: 18px; }
.pgl__body { min-width: 0; }
.pgl__label { margin: 0; font-weight: 600; }
.pgl__detail { margin: 2px 0 0; color: var(--el-text-color-secondary); word-break: break-all; }
.pgl__fix { margin: 2px 0 0; color: var(--el-text-color-primary); }
.pgl__entry { margin: 2px 0 0; color: var(--el-color-primary); word-break: break-all; }
</style>
