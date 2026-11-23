<!--
  Read-only ownership echo (T396b, ownership-echo iron rule 1).
  What it does: renders the owning project name (labelled, with a switch-the-header
  hint) inside a create/write dialog so the user always sees who the record belongs to.
  Why: the AK batch moved project selection to the header (global context), which made
  dialog-side ownership invisible - the root cause behind the space-create report.
  Contract: the value is single-sourced from the projectContext store (primaryName);
  this component never hosts a selector (rule 1: no second channel) and shows a plain
  "pick a project in the header" hint when the context is empty (rule 2 wording).
  Boundary: display only - callers still read projectId from useProjectPageContext for
  their payload; nothing here authorizes anything (context = filter state, not credentials).
-->
<template>
  <div class="owner-echo" data-test="owner-echo">
    <span class="owner-echo__label">{{ t('components.ownerEcho.label') }}</span>
    <span v-if="ctx.primaryName" class="owner-echo__value mono" data-test="owner-echo-value">
      {{ ctx.primaryName }}
    </span>
    <span v-if="ctx.primaryName" class="owner-echo__hint">{{ t('components.ownerEcho.switchHint') }}</span>
    <span v-else class="owner-echo__none" data-test="owner-echo-none">{{ t('components.ownerEcho.pickInHeader') }}</span>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { useProjectContextStore } from '@/stores/projectContext'

const { t } = useI18n()
const ctx = useProjectContextStore()
</script>

<style scoped>
.owner-echo {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--tp-text-secondary, #98a2b3);
}
.owner-echo__label {
  flex-shrink: 0;
}
.owner-echo__value {
  color: var(--tp-text-primary, #e6edf3);
  font-weight: 500;
  /* long project names must shrink with ellipsis instead of overflowing the
     dialog (vision rubric dim4: scale-safety of the echo line at worst names) */
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.owner-echo__hint {
  flex-shrink: 0;
}
.owner-echo__none {
  color: var(--el-color-warning, #e6a23c);
}
</style>
