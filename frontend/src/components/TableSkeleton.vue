
<template>
  <!-- Reveal gate : nothing is painted until `loading` has persisted
 past the theme's named delay, so fast responses show zero skeleton
 frames. The gate lives here — the single shared component — never in
 consuming pages. -->
  <div v-if="shown" class="table-skeleton" data-test="table-skeleton" aria-busy="true">
    <el-skeleton animated>
      <template #template>
        <div class="ts-head">
          <span v-for="(w, i) in colWidths.slice(0, cols)" :key="'h' + i" class="ts-cell" :style="{ width: w }" />
        </div>
        <div v-for="r in rows" :key="r" class="ts-row">
          <span v-for="(w, i) in colWidths.slice(0, cols)" :key="'c' + r + i" class="ts-cell">
            <el-skeleton-item variant="text" :style="{ width: w, height: '14px' }" />
          </span>
        </div>
      </template>
    </el-skeleton>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from "vue";

withDefaults(
  defineProps<{
    
    rows?: number;
    cols?: number;
    
    colWidths?: string[];
  }>(),
  {
    rows: 8,
    cols: 6,
    colWidths: () => ["16%", "22%", "10%", "12%", "14%", "12%", "14%", "16%"],
  }
);

/** Named hot key (single source = theme.scss :root); the fallback constant
 *  only mirrors the shipped default for the case the token is missing. */
const REVEAL_DELAY_KEY = "--ds-skeleton-reveal-delay-ms";
const SHIPPED_REVEAL_DELAY_MS = 250;

const shown = ref(false);
let revealTimer: ReturnType<typeof setTimeout> | undefined;

function resolveRevealDelayMs(): number {
  const raw = getComputedStyle(document.documentElement).getPropertyValue(REVEAL_DELAY_KEY);
  const n = Number(raw);
  return Number.isFinite(n) && n >= 0 ? n : SHIPPED_REVEAL_DELAY_MS;
}

onMounted(() => {
  const delay = resolveRevealDelayMs();
  if (delay === 0) {
    shown.value = true;
    return;
  }
  revealTimer = setTimeout(() => {
    shown.value = true;
    revealTimer = undefined;
  }, delay);
});

onBeforeUnmount(() => {
  if (revealTimer !== undefined) clearTimeout(revealTimer);
});
</script>

<style scoped>
.table-skeleton {
  padding: var(--ds-space-2, 8px) 0;
}
.ts-head {
  display: flex;
  gap: var(--ds-space-3, 12px);
  padding: 8px 12px;
  border-bottom: 1px solid var(--ds-border, #e8eaef);
}
.ts-head .ts-cell {
  height: 12px;
  border-radius: 4px;
  background: var(--el-fill-color-dark, #ebedf0);
}
.ts-row {
  display: flex;
  gap: var(--ds-space-3, 12px);
  align-items: center;
  padding: 16px 12px; 
  border-bottom: 1px solid var(--ds-border, #e8eaef);
}
.ts-cell {
  flex: none;
}
</style>
