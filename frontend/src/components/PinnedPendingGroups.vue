<template>
  <!--
 Pending disposal for the pinned objects : the
 linkage bus mints a grouped draft card whenever an upstream change leaves a
 downstream object waiting. This section reads what still waits in the segment:
 ONE enumerated read lists every non-empty system group of this project and
 each card carries its own group token (D4d — the panel holds no kind
 vocabulary and never guesses one). Rows fold through the ONE ledger-row fold
 (chatDockReducer.foldDraftRowToCard) and each group's cards go to the SAME
 group door an assistant answer uses (ChatDraftGroup in scope mode) — the
 panel never grows a second acceptance flow.

 Red line 10c: marking and disclosure are automatic, changing downstream
 content never is. Nothing in this file writes — a write happens only behind
 the door's two clicks (open the one-shot ticket, then apply the ticked
 subset), and after the door answers the list is re-read from the server
 instead of being edited here.

 Worst-case scale (S-group): one enumerated read per segment whatever the kind
 count; the group count is bounded by the closed object-kind set and the
 ageing gate (piece E) holds the pending volume down. An empty group renders
 no row (no half-wired affordance); a failed read is one honest line.
 -->
  <section
    v-if="groups.length > 0 || readFailed"
    class="pinned-pending-groups"
    data-test="pinned-pending-groups"
    :aria-label="t('layout.chatDock.pinnedPanel.pendingGroupsTitle', { n: totalCount })"
  >
    <p v-if="groups.length > 0" class="pending-groups-title" data-test="pinned-pending-groups-title">
      {{ t('layout.chatDock.pinnedPanel.pendingGroupsTitle', { n: totalCount }) }}
    </p>
    <!-- Door identity rides the class + this section's own anchor. Passing a
         data-test here would fall through onto ChatDraftGroup's root and
         REPLACE the component's own data-test="chat-draft-group" (Vue merge
         rule: parent value wins for non-class attributes) — the shared door
         would silently lose the generic anchor every contract uses. -->
    <ChatDraftGroup
      v-for="g in groups"
      :key="`${g.projectId}:${g.objectType}`"
      class="pending-group-door"
      :message-id="null"
      :cards="g.cards"
      :project-id="g.projectId"
      :scope="{ projectId: g.projectId, objectType: g.objectType }"
      @applied="onDoorApplied(`${g.projectId}:${g.objectType}`)"
    />
    <p v-if="readFailed" class="pending-groups-failed" data-test="pinned-pending-groups-failed">
      {{ t('layout.chatDock.pinnedPanel.pendingGroupsFailed') }}
    </p>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { PendingGroupCard, RefObjectPayload } from '@/api/aiChat'
import { fetchPendingGroupCards } from '@/api/aiChat'
import { foldDraftRowToCard, type RepairDraftCard } from '@/composables/chatDockReducer'
import { doorKey, mergePendingDoors } from '@/composables/pendingDoorMerge'
import ChatDraftGroup from '@/components/ChatDraftGroup.vue'

const props = defineProps<{
  /** Viewer's project segment from the header context (R30 single switch);
 * null = no segment, nothing loads and nothing renders. */
  projectId: number | null
  /** Pinned reference rows — their kinds name the groups worth reading. */
  rows: RefObjectPayload[]
}>()

/**
 * Bubbles every door the human answered : the parent panel
 * re-reads its per-object badges from the ledger on this event — disposal
 * zeroing is server-sourced, never patched here.
 */
const emit = defineEmits<{
  (e: 'answered', key: string): void
}>()

const { t } = useI18n()

/** One project + object kind the bus still owes a human decision to. */
interface PendingGroup {
  projectId: number
  objectType: string
  cards: RepairDraftCard[]
}

/** Groups of this segment the bus still owes a human decision to; the read
 * door names their tokens, the panel only rides them (D4d). */
const groups = ref<PendingGroup[]>([])
const readFailed = ref(false)
/**
 * Doors a human answered during this mount. Their receipt lives in the door's
 * own state, so the re-read must keep the SAME keyed instance mounted until
 * the server offers that kind again (pendingDoorMerge). Every add is
 * immediately followed by a reload, which replaces `groups.value` — the count
 * below recomputes through that, so a plain Set is enough here.
 */
const answeredKeys = new Set<string>()
const totalCount = computed(
  () =>
    groups.value.reduce(
      (sum, g) => sum + (answeredKeys.has(doorKey(g)) ? 0 : g.cards.length),
      0,
    ),
)

let readSeq = 0

/** Answered door first, then the honest re-read (order is the fix). */
function onDoorApplied(key: string): void {
  answeredKeys.add(key)
  void reload()
  emit('answered', key)
}

/** Re-read every group from the server (mount, segment or pinned-set change,
 * and after the door answers) — the list is never patched client-side,
 * only answered doors ride on so their receipt stays readable. */
async function reload(): Promise<void> {
  const projectId = props.projectId
  const seq = ++readSeq
  readFailed.value = false
  if (projectId === null) {
    groups.value = []
    return
  }
  let rows: PendingGroupCard[]
  try {
    rows = await fetchPendingGroupCards(projectId)
  } catch {
    if (seq !== readSeq) return
    groups.value = mergePendingDoors([], groups.value, answeredKeys, projectId)
    readFailed.value = true
    return
  }
  // A later reload owns the answer; a stale response must not resurrect it.
  if (seq !== readSeq) return
  const byKind = new Map<string, RepairDraftCard[]>()
  rows.forEach((row) => {
    const card = foldDraftRowToCard({
      draftId: row.draftId,
      objectType: row.objectType,
      payloadJson: row.payloadJson,
      targetId: row.targetId,
      baseVersion: row.baseVersion,
      summary: row.summary,
    })
    if (card === null) return
    const bucket = byKind.get(row.objectType)
    if (bucket) {
      bucket.push(card)
    } else {
      byKind.set(row.objectType, [card])
    }
  })
  const fresh = [...byKind.entries()]
    .sort((a, b) => a[0].localeCompare(b[0]))
    .map(([objectType, cards]) => ({ projectId, objectType, cards }))
  // The server re-offering a kind outranks the hold: the fresh row wins and
  // the answered mark goes away (it no longer owes a receipt).
  freshKeysOf(fresh).forEach((key) => {
    if (answeredKeys.has(key)) answeredKeys.delete(key)
  })
  groups.value = mergePendingDoors(fresh, groups.value, answeredKeys, projectId)
}

function freshKeysOf(list: PendingGroup[]): Set<string> {
  return new Set(list.map(doorKey))
}

watch(
  () => [props.projectId, props.rows.map((row) => `${row.kind}:${row.id}`).join('|')] as const,
  () => {
    void reload()
  },
  { immediate: true },
)
</script>

<style scoped>
/* One band under the pinned rows, on the panel's own rhythm (theme tokens
   only — no per-page colour or spacing forks, R21 band discipline). */
.pinned-pending-groups {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin: 6px 2px 2px;
  padding-top: 6px;
  border-top: 1px solid var(--tp-border, rgba(148, 163, 184, 0.25));
}
.pending-groups-title {
  margin: 0;
  font-size: 11px;
  color: var(--tp-text-secondary, #94a3b8);
}
.pending-groups-failed {
  margin: 0;
  font-size: 11px;
  color: var(--tp-text-warning, #d9a03f);
}
</style>
