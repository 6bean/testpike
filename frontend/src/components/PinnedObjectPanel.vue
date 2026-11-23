<template>
  <!--
 Pinned-object panel (AM batch , the "UX front door" of the
 16 §70 line): a sticky object view inside the chat dock. Chip source is
 the wire triple (kind, id, title) of the conversation reference contract
 — the panel keeps the LAST non-empty chip set, so a send that clears the
 composer never blanks the view mid-thread (reload starts honestly empty,
 R25 mode ② — no invented rows).

 Per object, wired to EXISTING doors only (zero new endpoints in this
 segment):
 - pending un-landed draft count door, GET
 /api/v1/ai/chat/drafts/pending-count — the count is computed
 single-sourced in core);
 - kind 'case' additionally shows the current status badge (getCase),
 the version timeline (listCaseVersions; a chip asks the
 comparison card through the composer prefill channel) and the most
 recent review summary (listCaseReviewLogs), plus the one-click
 submit-review entry pointing at the review workbench (the visible
 door of batch review);
 - every other kind shows title + pending count and nothing else —
 absent capability, absent row (no half-wired affordances).

 Layout posture: R17 unified card background, X5 focus rings stay on the
 global single source (no inner outline overrides here), N-group naming —
 the panel never repeats a page title row (R23).
 -->
  <section
    v-if="pinned.length > 0"
    class="pinned-object-panel"
    data-test="pinned-object-panel"
    :aria-label="t('layout.chatDock.pinnedPanel.title')"
  >
    <button
      type="button"
      class="pinned-object-panel-toggle"
      data-test="pinned-panel-toggle"
      :aria-expanded="!collapsed"
      @click="collapsed = !collapsed"
    >
      <span class="pinned-object-panel-glyph" aria-hidden="true">{{ collapsed ? '▸' : '▾' }}</span>
      {{ t(collapsed ? 'layout.chatDock.pinnedPanel.expand' : 'layout.chatDock.pinnedPanel.collapse', { count: pinned.length }) }}
    </button>
    <ul v-show="!collapsed" class="pinned-object-list" data-test="pinned-object-list">
      <li
        v-for="row in pinned"
        :key="`${row.kind}:${row.id}`"
        class="pinned-object-row"
        data-test="pinned-object-row"
        :data-kind="row.kind"
      >
        <span class="pinned-object-kind">{{ t(kindLabelKey(row.kind)) }}</span>
        <span class="pinned-object-title" :title="row.title">{{ row.title }}</span>
        <!-- disable-transitions: el-tag's zoom animation ends via a
             requestAnimationFrame callback; a page driven under an attached
             CDP session (ux evidence gates) can throttle rAF so the badge
             freezes in the leave frame at opacity 0 — the data would then be
             invisible whenever the panel hydrates mid-animation. A data badge
             must never depend on animation frames to become visible. -->
        <el-tag
          v-if="statusOf(row)"
          size="small"
          disable-transitions
          data-test="pinned-status-badge"
        >{{ statusOf(row) }}</el-tag>
        <el-tag
          v-if="pendingOf(row) > 0"
          size="small"
          type="warning"
          disable-transitions
          data-test="pinned-pending-count"
        >{{ t('layout.chatDock.pinnedPanel.pending', { n: pendingOf(row) }) }}</el-tag>
        <!-- Downstream-impact badge : count comes from the
 ONE pending-impact ledger door, never from a client-side
 arithmetic — disposal zeroing happens when the next server read
 says so, so a badge cannot lie about a still-open consequence.
 A button (not a plain tag) because it is a navigation affordance:
 it reveals the group-card door area below. -->
        <button
          v-if="impactOf(row) > 0"
          type="button"
          class="pinned-impact-badge"
          data-test="pinned-impact-badge"
          :title="t('layout.chatDock.pinnedPanel.impactBadgeHint')"
          @click="revealPendingGroups"
        >{{ t('layout.chatDock.pinnedPanel.impactBadge', { n: impactOf(row) }) }}</button>
        <!-- The submit-review entry stays on the row's PRIMARY line: the
             review summary below has flex-basis:100% and would otherwise
             strand the button alone under the summary, visually detaching
             the action from its object (vision review r8, high item). -->
        <el-button
          v-if="row.kind === 'case'"
          size="small"
          type="primary"
          data-test="pinned-submit-review"
          @click="gotoReview(row)"
        >{{ t('layout.chatDock.pinnedPanel.submitReview') }}</el-button>
        <div
          v-if="versionLabelsOf(row).length > 0"
          class="pinned-object-versions"
          data-test="pinned-version-timeline"
        >
          <span class="pinned-object-versions-label">{{ t('layout.chatDock.pinnedPanel.versions') }}</span>
          <button
            v-for="label in versionLabelsOf(row)"
            :key="label"
            type="button"
            class="pinned-version-chip"
            :data-test="`pinned-version-${label}`"
            @click="askCompare(row, label)"
          >{{ label }}</button>
        </div>
        <p v-if="reviewOf(row)" class="pinned-object-review" data-test="pinned-review-summary">
          {{ t('layout.chatDock.pinnedPanel.reviewLatest') }}：{{ reviewOf(row) }}
        </p>
      </li>
    </ul>
    <!-- Pending disposal : the linkage bus's still
 un-disposed cards for the kinds the pinned rows belong to, walked
 through the SAME group door an answer's cards use. Reads only become
 writes behind the two clicks of that door. -->
    <PinnedPendingGroups
      v-if="!collapsed"
      ref="pendingGroupsRef"
      :project-id="projectId"
      :rows="pinned"
      @answered="onPendingDoorAnswered"
    />
    <p v-if="partialFailure" class="pinned-object-note" data-test="pinned-load-note">
      {{ t('layout.chatDock.pinnedPanel.loadFailed') }}
    </p>
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { RefObjectPayload } from '@/api/aiChat'
import { fetchDraftPendingCount } from '@/api/aiChat'
import { getCase, listCaseReviewLogs, listCaseVersions } from '@/api/case'
import { getLinkagePendingImpacts } from '@/api/linkage'
import { runBounded } from '@/utils/asyncFanout'
import PinnedPendingGroups from '@/components/PinnedPendingGroups.vue'

const props = defineProps<{
  /** Current composer chip set (wire triples) — single truth of references. */
  refs: RefObjectPayload[]
  /** Project segment from the global header context (R30); null disables loads. */
  projectId: number | null
}>()

const emit = defineEmits<{
  /** A version chip asks a comparison sentence through the composer. */
  (e: 'compare', text: string): void
}>()

const { t } = useI18n()
const router = useRouter()

const collapsed = ref(false)

/**
 * Sticky chip set: the LAST non-empty reference list. The composer clears
 * refs right after a send; the panel must keep showing the objects of the
 * newest user turn rather than collapsing to nothing on every ask.
 */
const pinned = ref<RefObjectPayload[]>([])
watch(
  () => props.refs,
  (next) => {
    if (next.length > 0) pinned.value = next.map((r) => ({ ...r }))
  },
  { immediate: true },
)

/** Per-object caches keyed by `kind:id` — numbers/labels are server-sourced. */
const pending = ref<Record<string, number>>({})
/** Pending downstream-impact counts ; every value is a
 * fresh ledger read — disposal answers NEVER decrement or zero this map
 * client-side, the next server read owns the number. */
const impactCounts = ref<Record<string, number>>({})
const statuses = ref<Record<string, string>>({})
const versions = ref<Record<string, string[]>>({})
const reviews = ref<Record<string, string>>({})
const partialFailure = ref(false)

function keyOf(row: RefObjectPayload): string {
  return `${row.kind}:${row.id}`
}

function statusOf(row: RefObjectPayload): string {
  return statuses.value[keyOf(row)] ?? ''
}
function pendingOf(row: RefObjectPayload): number {
  return pending.value[keyOf(row)] ?? 0
}
function impactOf(row: RefObjectPayload): number {
  return impactCounts.value[keyOf(row)] ?? 0
}
function versionLabelsOf(row: RefObjectPayload): string[] {
  return versions.value[keyOf(row)] ?? []
}
function reviewOf(row: RefObjectPayload): string {
  return reviews.value[keyOf(row)] ?? ''
}

/** Chat kind → display label key; unknown kinds never reach the map (the
 *  triple contract enumerates the set — a missing key falls back to kind). */
function kindLabelKey(kind: string): string {
  const known: Record<string, string> = {
    case: 'layout.chatDock.pinnedPanel.kindCase',
    defect: 'layout.chatDock.pinnedPanel.kindDefect',
    batch: 'layout.chatDock.pinnedPanel.kindBatch',
    doc: 'layout.chatDock.pinnedPanel.kindDoc',
    skill: 'layout.chatDock.pinnedPanel.kindSkill',
    tool: 'layout.chatDock.pinnedPanel.kindTool',
  }
  return known[kind] ?? 'layout.chatDock.pinnedPanel.kindCase'
}

/** Read one string field defensively — DO shapes evolve and the panel must
 *  never crash a dock over a renamed label field (honest blank instead). */
function pickString(obj: unknown, keys: string[]): string {
  if (!obj || typeof obj !== 'object') return ''
  const rec = obj as Record<string, unknown>
  for (const k of keys) {
    const v = rec[k]
    if (typeof v === 'string' && v.trim() !== '') return v
  }
  return ''
}

async function loadRow(row: RefObjectPayload): Promise<void> {
  const key = keyOf(row)
  const projectId = props.projectId
  if (projectId == null) return
  let ok = true
  try {
    // door: objectType rides the chat reference kind (case, defect,
    // batch, doc, skill, tool) — the core read door owns the translation to
    // the draft ledger's vocabulary (single source, no client copy). Unknown
    // kinds count 0 honestly.
    pending.value = { ...pending.value, [key]: await fetchDraftPendingCount(projectId, row.kind.toUpperCase(), row.id) }
  } catch {
    ok = false
  }
  // Downstream-impact ledger : the row kind token is the
  // ledger's own vocabulary (lowercase, core-owned). Kinds the bus never
  // registers answer empty — an honest 0, not a second guessing layer.
  try {
    const ledger = await getLinkagePendingImpacts(row.kind, row.id, projectId)
    impactCounts.value = { ...impactCounts.value, [key]: ledger.impacts.length }
  } catch {
    ok = false
  }
  if (row.kind === 'case') {
    try {
      const detail = await getCase(row.id)
      const label = pickString(detail, ['statusLabel', 'statusName'])
      if (label) statuses.value = { ...statuses.value, [key]: label }
    } catch {
      ok = false
    }
    try {
      const rows = await listCaseVersions(row.id)
      // The row already carries a leading "version" label (locale), so a
      // "vN" chip doubles the marker into "version vN" (vision scoring r11,
      // mid item); the bare ordinal keeps the row text "version N".
      versions.value = {
        ...versions.value,
        [key]: rows
          .slice(0, 6)
          .map((v) =>
            (pickString(v, ['versionLabel']) || String((v as unknown as Record<string, unknown>).version ?? '')).replace(/^[vV]/, ''),
          )
          .filter((label) => label !== ''),
      }
    } catch {
      ok = false
    }
    try {
      const logs = await listCaseReviewLogs(row.id)
      const first = logs.length > 0 ? pickString(logs[0], ['remark', 'comment', 'opinion']) : ''
      if (first) reviews.value = { ...reviews.value, [key]: first }
    } catch {
      ok = false
    }
  }
  partialFailure.value = !ok || partialFailure.value
}

/** Reload whenever the pinned set or the project segment changes.
 * Fan-out is capped through the shared bounded runner (worst scale pins 32+
 * rows; an unbounded burst stretches the pending-count tail under the dev
 * proxy and intermittently starves the badge — attribution). */
const DRAFT_READ_FANOUT_LIMIT = 6
function reloadAll(): void {
  partialFailure.value = false
  void runBounded(pinned.value.map((row) => () => loadRow(row)), DRAFT_READ_FANOUT_LIMIT)
}
watch(
  () => [pinned.value.map((r) => keyOf(r)).join('|'), props.projectId] as const,
  () => {
    reloadAll()
  },
  { immediate: true },
)

/**
 * A disposal door answered : badges are NOT zeroed here —
 * one bounded re-read of every ledger door lets the server own the number,
 * so an object still awaiting disposal under another door keeps its badge.
 */
function onPendingDoorAnswered(): void {
  reloadAll()
}

/** Group-card door area, revealed by the impact badge (ONE disposal surface;
 *  the badge scrolls to it instead of growing a second acceptance flow). */
const pendingGroupsRef = ref<InstanceType<typeof PinnedPendingGroups> | null>(null)
function revealPendingGroups(): void {
  collapsed.value = false
  void nextTick(() => {
    const el = pendingGroupsRef.value?.$el as HTMLElement | undefined
    if (el && typeof el.scrollIntoView === 'function') el.scrollIntoView({ block: 'nearest' })
  })
}

function askCompare(row: RefObjectPayload, label: string): void {
  emit('compare', t('layout.chatDock.pinnedPanel.compareAsk', { title: row.title, version: label }))
}

function gotoReview(row: RefObjectPayload): void {
  // One-click entry of the batch review door (workbench consumes the
  // caseId query; the panel never mints a second review flow).
  void router.push({ name: 'ReviewWorkbench', query: { caseId: String(row.id) } })
  ElMessage.success(t('layout.chatDock.pinnedPanel.reviewOpened', { title: row.title }))
}
</script>

<style scoped>
/* Sticky under the waterline so the objects stay in view while the thread
   scrolls (theme tokens only — no per-page colour forks). */
.pinned-object-panel {
  position: sticky;
  top: 0;
  z-index: 2;
  flex: none;
  margin: 0 12px 4px;
  padding: 6px 10px;
  background: var(--tp-bg-elevated, rgba(99, 102, 241, 0.06));
  border: 1px solid var(--tp-border, rgba(148, 163, 184, 0.25));
  border-radius: 8px;
}
.pinned-object-panel-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: none;
  background: transparent;
  cursor: pointer;
  font-size: 12px;
  color: var(--tp-text-secondary, #94a3b8);
  padding: 2px 0;
  min-height: 24px;
}
.pinned-object-panel-glyph {
  font-size: 11px;
}
.pinned-object-list {
  list-style: none;
  /* 8px header separation: the r10 vision row "expanded header sticks to the
     first row" measured 4px — below the panel's own 6px row rhythm; the
     full-score form is a clear band gap between the toggle and the list. */
  margin: 8px 0 0;
  padding: 0 2px 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  /* Scale safety (10-14 display-scale iron law): the panel sits inside a
     392px dock column and a 32-object reference set measured 996px tall —
     beyond the 900px viewport with the tail rows clipped and no way to
     reach them. Cap the list height (same min() posture as the sibling
     goal card) and let it scroll; global scrollbar theme styles apply. */
  max-height: min(50vh, 480px);
  overflow-y: auto;
}
.pinned-object-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}
.pinned-object-kind {
  font-size: 11px;
  color: var(--tp-text-secondary, #94a3b8);
  border: 1px solid var(--tp-border, rgba(148, 163, 184, 0.25));
  border-radius: 4px;
  padding: 0 4px;
}
.pinned-object-title {
  font-size: 12px;
  color: var(--tp-text-primary, #e2e8f0);
  /* Long names on a single line with an ellipsis; the full title rides the
     native tooltip (title= bound in the template). Word-wrapping inside a
     40% column read as a hard cut in the vision review (r8, mid item). */
  flex: 0 1 auto;
  min-width: 0;
  max-width: 40%;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.pinned-object-versions {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
}
.pinned-object-versions-label {
  font-size: 11px;
  color: var(--tp-text-secondary, #94a3b8);
}
.pinned-version-chip {
  border: 1px solid var(--tp-border, rgba(148, 163, 184, 0.25));
  background: transparent;
  color: var(--tp-text-secondary, #94a3b8);
  border-radius: 10px;
  font-size: 11px;
  padding: 1px 7px;
  cursor: pointer;
  min-height: 24px;
  /* Numeric-only version labels (single version marker fix) made bare
     digits that measured 22px wide; WCAG 2.2 target size floor is 24px. */
  min-width: 24px;
}
.pinned-version-chip:hover {
  color: var(--tp-primary, #6366f1);
  border-color: var(--tp-primary, #6366f1);
}
/* Impact badge: a clickable chip on the row's primary line (button for the
   keyboard, ≥24px target per WCAG 2.2; colour rides the theme warning token
   like the pending tag — one alarm vocabulary across the panel). */
.pinned-impact-badge {
  border: 1px solid var(--tp-border-warning, rgba(217, 160, 63, 0.45));
  background: transparent;
  color: var(--tp-text-warning, #d9a03f);
  border-radius: 10px;
  font-size: 11px;
  padding: 1px 8px;
  cursor: pointer;
  min-height: 24px;
}
.pinned-impact-badge:hover {
  border-color: var(--tp-text-warning, #d9a03f);
  text-decoration: underline;
}
.pinned-object-review {
  flex-basis: 100%;
  margin: 0;
  font-size: 11px;
  color: var(--tp-text-secondary, #94a3b8);
  overflow-wrap: anywhere;
}
.pinned-object-note {
  margin: 4px 0 0;
  font-size: 11px;
  color: var(--tp-text-secondary, #94a3b8);
}
</style>
