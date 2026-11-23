<template>
  <!--
 Context waterline bar : answers the user's "I just
 said that, why did the AI forget" with the session's own ledger
 numbers instead of a guess. Every number rides in from the server
 door (fetchContextWaterline reads the sealed ledger + K8 config),
 the panel never re-sums tokens or re-counts folds — the meter width
 is a display-only mapping of the two server numbers. An unlimited
 budget reads honestly as "no cap" and never renders an exhausted
 state. Expanding the folded turns re-reads the originals from the
 ALREADY LOADED thread (retrieval anchor; the rule for this segment is
 no second retrieval chain).
 -->
  <div v-if="waterline" class="chat-waterline" data-test="chat-waterline">
    <div class="chat-waterline-meter">
      <span
        v-if="waterline.budgetUnlimited"
        class="chat-waterline-text"
        data-test="chat-waterline-unlimited"
      >{{ t('layout.chatDock.waterline.unlimited') }}</span>
      <template v-else>
        <el-progress
          class="chat-waterline-bar"
          :percentage="meterWidth"
          :stroke-width="6"
          :show-text="false"
          :status="meterWidth >= 90 ? 'warning' : undefined"
        />
        <span class="chat-waterline-text" data-test="chat-waterline-budget">
          {{ t('layout.chatDock.waterline.budget', {
            used: fmtTokens(waterline.promptTokensUsed),
            budget: fmtTokens(waterline.budgetTokens),
          }) }}
        </span>
      </template>
    </div>

    <div v-if="foldedCount > 0" class="chat-waterline-fold" data-test="chat-waterline-fold">
      <button
        type="button"
        class="chat-waterline-toggle"
        :aria-expanded="foldOpen ? 'true' : 'false'"
        data-test="chat-waterline-toggle"
        @click="foldOpen = !foldOpen"
      >
        <span>{{ t('layout.chatDock.waterline.folded', {
          folded: foldedCount,
          pinned: waterline.pinnedFactsCount,
          keep: waterline.keepRecentTurns,
        }) }}</span>
        <span class="chat-waterline-caret">{{ foldOpen ? t('layout.chatDock.waterline.hide') : t('layout.chatDock.waterline.reveal') }}</span>
      </button>
      <!-- Retrieval anchor: the folded originals already sit in the loaded
           thread, so the disclosure lists them from the parent's texts. -->
      <p v-if="foldOpen && preview.length" class="chat-waterline-preview-count" data-test="chat-waterline-preview-count">
        {{ t('layout.chatDock.waterline.previewCount', { n: preview.length }) }}
        <span v-if="preview.length < foldedCount" class="chat-waterline-preview-more">{{ t('layout.chatDock.waterline.previewMore', {
          shown: preview.length,
          total: foldedCount,
        }) }}</span>
      </p>
      <ul v-if="foldOpen && preview.length" class="chat-waterline-preview" data-test="chat-waterline-preview">
        <li v-for="(p, i) in preview" :key="i" class="chat-waterline-preview-item">{{ p }}</li>
      </ul>
      <p v-else-if="foldOpen" class="chat-waterline-preview-empty" data-test="chat-waterline-preview-empty">
        {{ t('layout.chatDock.waterline.previewEmpty') }}
      </p>
    </div>
    <div v-else class="chat-waterline-plain" data-test="chat-waterline-plain">
      {{ t('layout.chatDock.waterline.pinned', { pinned: waterline.pinnedFactsCount }) }}
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { fetchContextWaterline, type ChatContextWaterlineView } from '@/api/aiChat'

const props = defineProps<{
  sessionId: number | null
  /** Sealed-ledger tick from the panel (row count that only grows on seals).
 * Any change re-reads the door so the bar never shows a stale first-fetch
 * snapshot while the conversation keeps sealing turns . */
  refreshToken?: number
  /** Oldest-first user texts of the loaded thread (retrieval anchor feed). */
  userTurnTexts?: string[]
}>()

const { t } = useI18n()
const waterline = ref<ChatContextWaterlineView | null>(null)
const foldOpen = ref(false)
/** Latest-wins guard: refreshes fire per sealed turn, so overlapping reads
 *  can resolve out of order — an early (pre-seal, used=0) response landing
 *  last would freeze the bar on a stale snapshot (E2E-proven with fast
 *  replies). Only the newest request may write the ref. */
let waterlineReqSeq = 0

async function reloadWaterline(id: number | null) {
  if (id == null) return
  const seq = ++waterlineReqSeq
  try {
    const view = await fetchContextWaterline(id)
    if (seq === waterlineReqSeq) waterline.value = view
  } catch {
    // Honest absence: a failed read hides the bar instead of blocking
    // the dialog or rendering a guessed number. A superseded failure must
    // not blank a newer good snapshot either.
    if (seq === waterlineReqSeq) waterline.value = null
  }
}

watch(
  () => props.sessionId,
  (id) => {
    foldOpen.value = false
    waterline.value = null
    // Invalidate any in-flight read of the previous session (or of the
    // null session) so it can never paint a bar that belongs elsewhere.
    waterlineReqSeq++
    void reloadWaterline(id)
  },
  { immediate: true },
)

// Same session, newer seals → refetch without clearing the bar (no flicker).
watch(
  () => props.refreshToken,
  () => {
    if (props.sessionId != null) void reloadWaterline(props.sessionId)
  },
)

/** Display-only meter width from the two server numbers (no re-computation
 *  of the ledger — sums never happen here). */
const meterWidth = computed(() => {
  const w = waterline.value
  if (!w || w.budgetUnlimited || w.budgetTokens <= 0) return 0
  return Math.min(100, Math.round((w.promptTokensUsed / w.budgetTokens) * 100))
})

const foldedCount = computed(() => Number(waterline.value?.foldedTurns ?? 0))

/** Cap the disclosure at 10 texts so the bar can never blow up the page
 */
const preview = computed(() =>
  (props.userTurnTexts ?? []).slice(0, Math.min(foldedCount.value, 10)),
)

/** Token figure formatting (vision review: "4618/200.0k" read as noisy —
 *  plain digits below 10k get thousands separators, thousands units drop a
 *  bare ".0" so a round 200000 budget reads "200k", not "200.0k"). */
function fmtTokens(n: number): string {
  if (n >= 10000) {
    const k = n / 1000
    return `${Number.isInteger(k) ? String(k) : k.toFixed(1)}k`
  }
  return n.toLocaleString('en-US')
}
</script>

<style scoped>
.chat-waterline {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
  padding: 8px 16px 16px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}
.chat-waterline-meter {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 160px;
}
.chat-waterline-bar {
  width: 120px;
}
.chat-waterline-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: none;
  background: none;
  padding: 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  cursor: pointer;
}
.chat-waterline-toggle:hover {
  color: var(--el-color-primary);
}
.chat-waterline-caret {
  color: var(--el-color-primary);
}
.chat-waterline-fold {
  width: 100%;
}
.chat-waterline-preview-count {
  margin: 4px 0 0;
}
.chat-waterline-preview-more {
  margin-left: 6px;
}
.chat-waterline-preview {
  margin: 2px 0 0;
  padding-left: 18px;
  list-style: disc;
  max-height: 180px;
  overflow-y: auto;
}
.chat-waterline-preview-item {
  white-space: pre-wrap;
  word-break: break-word;
  margin-bottom: 4px;
}
.chat-waterline-preview-empty {
  flex-basis: 100%;
  margin: 4px 0 0;
}
</style>
