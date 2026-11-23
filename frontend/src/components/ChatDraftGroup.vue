<template>
  <!--
 Group draft acceptance : one answer can mint
 several draft cards in the same frame ledger. This container folds them
 into ONE door without loosening it — the human still clicks twice (open
 reads a line-by-line receipt. The individual card doors underneath stay
 exactly as they were: the group never auto-applies anything (red line
 10c: a zero-click pass-through is not implemented anywhere in this file).
 Colliding proposals are pulled out by the core (status=4) and named in
 the receipt with both versions and the regenerate exit; their cards keep
 the existing stale refusal shape when clicked alone.
 Worst-case scale (S-group): a group is the drafts of one answer (bounded
 by the frame ledger), rendered as one checkbox row + one fold per item —
 no pagination is invented for a handful of proposals, and the receipt
 never dumps raw JSON.
 -->
  <div class="draft-group" data-test="chat-draft-group">
    <div class="group-head">
      <el-checkbox
        v-model="allChecked"
        :indeterminate="partiallyChecked"
        :disabled="locked"
        data-test="draft-group-all"
      >
        {{ t('layout.chatDock.draft.groupTitle', { n: cards.length }) }}
      </el-checkbox>
      <span class="group-count" data-test="draft-group-count">
        {{ t('layout.chatDock.draft.groupChecked', { n: selectedIds.length, total: cards.length }) }}
      </span>
    </div>
    <!-- Live ledger anchors : re-read from tp_linkage_impact
 on every open, so the group discloses the impact state at the
 moment of the click, never a snapshot copied out of the payload.
 Display-only block: it rides no ticket and fires no apply — the
 acceptance door is the single write channel (red line 10c). A
 failed read collapses to one honest line instead of an empty
 silence; the door itself keeps working (disclosure is a nicety,
 the door is the function). -->
    <div v-if="anchorRows.length > 0 || impactFailure" class="group-impacts" data-test="draft-group-impacts">
      <p v-if="anchorRows.length > 0" class="group-impact-summary" data-test="draft-group-impact-summary">
        {{ t('layout.chatDock.draft.groupImpactSummary', { n: anchorRows.length, pending: pendingCount }) }}
      </p>
      <p v-if="impactFailure" class="group-impact-failure" data-test="draft-group-impact-failure">
        {{ impactFailure }}
      </p>
    </div>
    <div v-for="d in cards" :key="d.draftId" class="group-item" data-test="draft-group-item">
      <el-checkbox
        v-model="checked[d.draftId]"
        :disabled="locked"
        class="group-pick"
        data-test="draft-group-pick"
      >
        {{ t('layout.chatDock.draft.groupPick', { name: itemLabel(d) }) }}
      </el-checkbox>
      <ChatDraftCard :card="d" />
      <p v-if="anchorFor(d.draftId)" class="group-impact-row" data-test="draft-group-impact-row">
        <template v-if="anchorFor(d.draftId)!.impact.consumedAt">
          {{ t('layout.chatDock.draft.groupImpactConsumed', {
            id: anchorFor(d.draftId)!.impact.downstreamId,
            to: typeLabel(anchorFor(d.draftId)!.impact.downstreamObjectType),
          }) }}
        </template>
        <template v-else>
          {{ t('layout.chatDock.draft.groupImpactPending', {
            from: typeLabel(anchorFor(d.draftId)!.impact.upstreamObjectType),
            id: anchorFor(d.draftId)!.impact.upstreamObjectId,
            v: anchorFor(d.draftId)!.impact.upstreamVersion ?? 0,
            to: typeLabel(anchorFor(d.draftId)!.impact.downstreamObjectType),
            targetId: anchorFor(d.draftId)!.impact.downstreamId,
          }) }}
        </template>
      </p>
    </div>
    <div class="group-ops">
      <template v-if="stage === 'applied'">
        <p class="group-receipt-sum" data-test="draft-group-receipt-summary">
          {{ t('layout.chatDock.draft.groupReceiptSummary',
            { landed: landedCount, excluded: receipt.length - landedCount }) }}
        </p>
        <ul class="group-receipt" data-test="draft-group-receipt">
          <li v-for="i in receipt" :key="i.draftId" class="group-receipt-line" :data-outcome="i.outcome">
            <span class="group-receipt-name">{{ i.objectName }}</span>
            <span class="group-receipt-text">{{ receiptText(i) }}</span>
          </li>
        </ul>
      </template>
      <template v-else-if="stage === 'ticketed'">
        <el-button type="primary" size="small" data-test="draft-group-apply"
          :disabled="applying" @click="apply">
          {{ t('layout.chatDock.draft.groupApply', { n: selectedIds.length }) }}
        </el-button>
        <span class="group-ttl" data-test="draft-group-ttl">
          {{ t('layout.chatDock.draft.ticketLeft', { seconds: ticketSeconds }) }}
        </span>
      </template>
      <template v-else>
        <el-button type="primary" size="small" data-test="draft-group-confirm"
          :disabled="confirmBlocked" :loading="loadingTicket" @click="openTicket">
          {{ t('layout.chatDock.draft.groupAccept', { n: selectedIds.length }) }}
        </el-button>
        <span class="group-hint">{{ blockedHint || t('layout.chatDock.draft.groupHint') }}</span>
      </template>
      <!-- The group door answers with the server's own sentence (the core
           already names the object, the ticket state and the consequence —
           a second client-side refusal wording would break one-concept-one-word). -->
      <p v-if="failureLine" class="group-error" data-test="draft-group-error">{{ failureLine }}</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { BizError } from '@/api/request'
import {
  applyGroupDrafts,
  applyGroupScopeDrafts,
  requestGroupDraftConfirmTicket,
  requestGroupScopeConfirmTicket,
  type DraftGroupReceiptItem,
} from '@/api/aiChat'
import { getLinkageImpactsByDrafts, type LinkageDraftImpact } from '@/api/linkage'
import ChatDraftCard from '@/components/ChatDraftCard.vue'
import type { RepairDraftCard } from '@/composables/chatDockReducer'

const props = withDefaults(defineProps<{
  /** DB id of the assistant message the group belongs to; null while the
   *  live stream has not reported the done frame yet (the door honestly
   *  waits instead of guessing an id). */
  messageId: number | null
  cards: RepairDraftCard[]
  /** Viewer's project segment from the header context (R30 single switch);
 * null = no segment, the anchor read stays silent instead of guessing. */
  projectId: number | null
  /**
 * System-origin group coordinates : when set, this
 * door walks the scope-shaped pair of endpoints instead of the answer-shaped
 * one — the pinned panel names a group by (project, object kind) because its
 * cards were minted by the linkage bus, not by an assistant answer. Only the
 * two endpoint calls differ: the ticked subset, the one-shot ticket, the
 * countdown and the line-by-line receipt below are shared by both shapes, so
 * there is exactly one group door in the product.
 */
  scope?: { projectId: number; objectType: string } | null
}>(), { scope: null })

const emit = defineEmits<{
  /** The door answered with a receipt (both shapes) — the caller re-reads its
   *  own list instead of editing it here. */
  (e: 'applied', items: DraftGroupReceiptItem[]): void
}>()

const { t } = useI18n()

/**
 * they do not accept, matching the single-card default door posture.
 * The group can mount while the answer is still streaming (the second card
 * already folds the door), so late-arriving cards must join as selected too
 * — a one-shot snapshot at mount left them undefined, which read as
 * "selected" in the count but rendered unchecked and swallowed the first
 * untick click (unticking kept the count at n/n). */
const checked = ref<Record<number, boolean>>({})
watch(
  () => props.cards.map((c) => c.draftId),
  (ids) => {
    if (ids.every((id) => id in checked.value)) return
    const next: Record<number, boolean> = { ...checked.value }
    for (const id of ids) {
      if (!(id in next)) next[id] = true
    }
    checked.value = next
  },
  { immediate: true },
)
const selectedIds = computed(() =>
  props.cards.filter((c) => checked.value[c.draftId] !== false).map((c) => c.draftId),
)
const allChecked = computed({
  get: () => selectedIds.value.length === props.cards.length,
  set: (value: boolean) => {
    const next: Record<number, boolean> = {}
    for (const c of props.cards) next[c.draftId] = value
    checked.value = next
  },
})
const partiallyChecked = computed(
  () => selectedIds.value.length > 0 && selectedIds.value.length < props.cards.length,
)

/**
 * Live ledger anchors : the impact rows behind this group's
 * draft cards, re-read fresh from the server every time the card set or
 * the viewer segment changes. Display-only — nothing here can write: the
 * acceptance door (ticket + apply) stays the single write channel.
 */
const impactAnchors = ref<LinkageDraftImpact[]>([])
const impactFailure = ref('')
const draftIdsKey = computed(() => props.cards.map((c) => c.draftId).join(','))
const anchorRows = computed(() => impactAnchors.value)
const pendingCount = computed(
  () => impactAnchors.value.filter((a) => !a.impact.consumedAt).length,
)
const typeLabels: Record<string, string> = {
  case: t('layout.chatDock.draft.type.case'),
  requirement: t('layout.chatDock.draft.type.requirement'),
  api_spec: t('layout.chatDock.draft.type.api_spec'),
  script: t('layout.chatDock.draft.type.script'),
}
/** Unknown bus tokens fall back to the raw token (honest echo, no invented label). */
function typeLabel(objectType: string): string {
  return typeLabels[objectType] ?? objectType
}
function anchorFor(draftId: number): LinkageDraftImpact | undefined {
  return impactAnchors.value.find((a) => a.draftId === draftId)
}
let impactSeq = 0
async function loadImpacts() {
  const seq = ++impactSeq
  impactFailure.value = ''
  if (props.projectId == null) {
    impactAnchors.value = []
    return
  }
  const ids = props.cards.map((c) => c.draftId)
  if (ids.length === 0) {
    impactAnchors.value = []
    return
  }
  try {
    const rows = await getLinkageImpactsByDrafts(ids, props.projectId)
    if (seq === impactSeq) impactAnchors.value = rows
  } catch {
    if (seq === impactSeq) {
      impactAnchors.value = []
      impactFailure.value = t('layout.chatDock.draft.groupImpactUnavailable')
    }
  }
}
watch(
  () => `${draftIdsKey.value}|${props.projectId ?? ''}`,
  () => void loadImpacts(),
  { immediate: true },
)

const stage = ref<'idle' | 'ticketed' | 'applied'>('idle')
const loadingTicket = ref(false)
const applying = ref(false)
const ticketSeconds = ref(0)
const receipt = ref<DraftGroupReceiptItem[]>([])
const landedCount = computed(() => receipt.value.filter((i) => i.outcome === 'landed').length)
const locked = computed(() => stage.value === 'applied')
/** One-shot ticket held between the two clicks (never re-guessed client-side). */
let ticket = ''
let ticker: ReturnType<typeof setInterval> | null = null

/** Which door this group walks: the scope-shaped pair when the caller named
 * the group by its segment coordinates, otherwise the answer-shaped one keyed on
 * the message id. Both shapes share the ticket, the subset and the receipt. */
const doorUnavailable = computed(() => props.scope === null && props.messageId === null)

const confirmBlocked = computed(
  () => doorUnavailable.value || selectedIds.value.length === 0 || loadingTicket.value,
)
const blockedHint = computed(() => {
  if (doorUnavailable.value) return t('layout.chatDock.draft.groupGenerating')
  if (selectedIds.value.length === 0) return t('layout.chatDock.draft.groupNone')
  return ''
})

const failureLine = ref('')

function itemLabel(card: RepairDraftCard): string {
  if (card.targetName) return card.targetName
  if (card.caseId > 0) return `#${card.caseId}`
  if (card.requirementId > 0) return `#${card.requirementId}`
  return card.summary || card.objectType
}

/**
 * Receipt wording: a landed row names its new version (R22 — never a bare
 * "done"); a pulled-out row rides the server's own sentence, which already
 * names the object, both versions and the regenerate exit.
 */
function receiptText(item: DraftGroupReceiptItem): string {
  if (item.outcome !== 'landed') return item.detail
  return item.appliedVersion === null || item.appliedVersion === undefined
    ? t('layout.chatDock.draft.groupReceiptLandedPlain')
    : t('layout.chatDock.draft.groupReceiptLanded', { version: item.appliedVersion })
}

/** First click: one ticket over the whole group (TTL echo like a card). */
async function openTicket() {
  if (loadingTicket.value || doorUnavailable.value || selectedIds.value.length === 0) return
  loadingTicket.value = true
  failureLine.value = ''
  try {
    const granted = props.scope !== null
      ? await requestGroupScopeConfirmTicket(props.scope.projectId, props.scope.objectType)
      : await requestGroupDraftConfirmTicket(props.messageId as number)
    ticket = granted.token
    ticketSeconds.value = granted.expiresInSeconds
    stage.value = 'ticketed'
    ticker = setInterval(() => {
      if (ticketSeconds.value > 0) {
        ticketSeconds.value -= 1
        return
      }
      stopTicker()
      ticket = ''
      stage.value = 'idle'
      // Honest expiry line: the ticket is gone, the drafts are not.
      failureLine.value = t('layout.chatDock.draft.fail.ticketExpired')
    }, 1000)
  } catch (err) {
    failureLine.value = err instanceof BizError && err.message
      ? err.message
      : t('layout.chatDock.draft.fail.generic')
  } finally {
    loadingTicket.value = false
  }
}

/** Second click: apply the ticked subset in one core-side transaction. */
async function apply() {
  if (applying.value || ticket === '' || doorUnavailable.value) return
  applying.value = true
  failureLine.value = ''
  try {
    const answered = props.scope !== null
      ? await applyGroupScopeDrafts(
          props.scope.projectId, props.scope.objectType, ticket, selectedIds.value)
      : await applyGroupDrafts(props.messageId as number, ticket, selectedIds.value)
    receipt.value = answered.items
    ticket = ''
    stopTicker()
    ticketSeconds.value = 0
    stage.value = 'applied'
    // The caller owns its own list: landed rows leave a pending read, so the
    // pinned panel re-reads instead of patching rows here (one truth per door).
    emit('applied', answered.items)
  } catch (err) {
    // A refused apply never burns the ticket (the core says so in its own
    // sentence); stay ticketed so the human can fix the selection and retry.
    failureLine.value = err instanceof BizError && err.message
      ? err.message
      : t('layout.chatDock.draft.fail.generic')
    if (err instanceof BizError && err.code === '40001') {
      stage.value = 'idle'
      ticket = ''
      stopTicker()
      ticketSeconds.value = 0
    }
  } finally {
    applying.value = false
  }
}

function stopTicker() {
  if (ticker) clearInterval(ticker)
  ticker = null
}

onBeforeUnmount(stopTicker)
</script>

<style scoped>
.draft-group {
  /* the group wraps the same-card rhythm: one 8px outer fold on the 4px grid */
  margin-top: 8px;
  padding: 8px 10px;
  border: 1px solid var(--tp-border, rgba(148, 163, 184, 0.2));
  border-radius: 8px;
  background: var(--tp-surface-2, rgba(30, 41, 59, 0.35));
}
.group-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
}
.group-count {
  font-size: 12px;
  color: var(--tp-text-secondary, #98a2b3);
}
.group-item {
  /* one checkbox line above its card fold — the tick row stays reachable
     without hunting inside the card (vision: separate zones, not nested) */
  margin-top: 4px;
  padding-left: 4px;
}
.group-pick {
  font-size: 12px;
}
.group-ops {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px;
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px solid var(--tp-border-on-tint, #6d86a6);
}
.group-ttl,
.group-hint {
  font-size: 12px;
  color: var(--tp-text-secondary, #98a2b3);
}
.group-impacts {
  width: 100%;
  margin: 0 0 4px;
}
.group-impact-summary,
.group-impact-row,
.group-impact-failure {
  margin: 2px 0;
  font-size: 12px;
  color: var(--tp-text-secondary, #98a2b3);
}
.group-impact-failure {
  color: var(--tp-text-warning, #d9a03f);
}
.group-receipt {
  width: 100%;
  margin: 0;
  padding: 0;
  list-style: none;
  font-size: 12px;
}
.group-receipt-line {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 6px;
  padding: 2px 0;
}
.group-receipt-name {
  font-weight: 600;
  min-width: 0;
  overflow-wrap: anywhere;
}
.group-receipt-line[data-outcome='landed'] .group-receipt-text {
  color: var(--el-color-success, #67c23a);
}
.group-receipt-line[data-outcome='stale-excluded'] .group-receipt-text {
  color: var(--el-color-warning, #e6a23c);
}
.group-receipt-line[data-outcome='not-ticked'] .group-receipt-text {
  color: var(--tp-text-secondary, #98a2b3);
}
.group-receipt-text {
  min-width: 0;
  overflow-wrap: anywhere;
}
.group-receipt-sum {
  width: 100%;
  margin: 0;
  font-size: 12px;
  color: var(--tp-text-secondary, #98a2b3);
}
.group-error {
  width: 100%;
  margin: 0;
  font-size: 12px;
  color: var(--el-color-danger, #f56c6c);
}
</style>
