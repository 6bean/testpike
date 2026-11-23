<template>
  <!--
 Global AI chat dock shell ( stage 2b, kernel-extracted at stage 3;
 resize + click-outside per adjudication §sixteen rule 6 / D2).
 Right-side drawer mounted once in MainLayout so it opens over any page
 carrying that page's context (§seven). This file is mount-only: the whole
 conversation engine lives in ChatPanel (shared with the standalone page —
 one kernel, zero second copy of the flow).

 Width = personal preference keyed per user (tp_chat_dock_width bucket in
 useChatDockWidth) — clamp [320,640], grab strip drag, double-click reset
 to 420, keyboard ←/→ 16px steps (X3: a drag is never the only path).
 Clicking outside dock AND bubble collapses the dock; collapse ≠ interrupt
 (the drawer keeps its content mounted: the panel streams on server-side
 and reopening resumes through the Last-Event-ID channel).
 -->
  <el-drawer
    v-model="open"
    :size="String(width)"
    :with-header="false"
    class="ai-chat-drawer"
    :class="{ 'is-resident': dockResident }"
    data-test="ai-chat-dock"
    :modal="false"
    modal-penetrable
  >
    <div class="ai-chat-dock-shell">
      <!-- Hover/focus micro copy on the grab strip ( carried item, fixed
 on the touched face): the drag affordance plus the double-click
 reset spelled out on hover. Native title (NOT el-tooltip: measured —
 a teleported popper over the strip steals the next drag's pointer
 events and the width E2E spectrum goes red). Same locale key as the
 aria-label: one concept, one word, no second copy. -->
      <div
        class="ai-chat-dock-resize"
        data-test="chat-dock-resize"
        role="separator"
        aria-orientation="vertical"
        :aria-label="t('layout.chatDock.resizeAria')"
        :title="t('layout.chatDock.resizeAria')"
        :aria-valuenow="width"
        :aria-valuemin="DOCK_MIN_WIDTH"
        :aria-valuemax="DOCK_MAX_WIDTH"
        tabindex="0"
        @pointerdown="onResizePointerDown"
        @dblclick="resetWidth"
        @keydown.left.prevent="stepWidth(-DOCK_RESIZE_STEP)"
        @keydown.right.prevent="stepWidth(DOCK_RESIZE_STEP)"
      ></div>
      <ChatPanel
        :source="hasBadge ? 3 : 1"
        :badge-execution-id="badgeId"
        :selection-refs="selectionRefs"
        :ask-question="askReq"
        :replay-ask="replayReq"
        closable
        @close="close"
      />
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import {
  chatDockState,
  closeChatDock,
  consumeBadgeRequest,
  consumeChatAsk,
  consumeSelectionAsk,
  consumeReplayAsk,
  setChatDockPin,
  type ChatAskRequest,
  type SelectionAskRef,
  type ReplayAskRequest,
} from '@/composables/useChatDock'
import {
  DOCK_COMPACT_BREAKPOINT,
  readDockPin,
  writeDockPin,
} from '@/composables/useChatDockPin'
import { dockOverlayOffsetPx, dockResident as dockResidentJudge } from '@/composables/chatDockLayout'
import {
  DOCK_DEFAULT_WIDTH,
  DOCK_MAX_WIDTH,
  DOCK_MIN_WIDTH,
  DOCK_RESIZE_STEP,
  clampDockWidth,
  readDockWidth,
  writeDockWidth,
} from '@/composables/useChatDockWidth'
import ChatPanel from '@/components/ChatPanel.vue'

const { t } = useI18n()
const authStore = useAuthStore()

const open = computed({
  get: () => chatDockState.visible,
  set: (v: boolean) => {
    if (!v) closeChatDock()
  },
})

function close() {
  closeChatDock()
}

/**
 * Badge deep link : a failure badge parks its execution pointer
 * and opens the dock; whichever way the dock got here (closed then opened, or
 * already open), the request is consumed exactly once and handed down to the
 * panel as `badge-execution-id`. Source 3 marks the session row as badge-born.
 */
const badgeId = ref<number | null>(null)
const hasBadge = computed(() => badgeId.value != null)

/**
 * Selection deep link : a list page parked its checked rows and
 * opened the dock; consumed exactly once here and handed down as the panel's
 * `selection-refs`, which folds them into the composer chips. Each request
 * installs a fresh array, so the panel's watch fires per ask (repeat asks of
 * the same rows still dedupe on the chip key — nothing doubles).
 */
const selectionRefs = ref<SelectionAskRef[]>([])

/**
 * Palette ask deep link : the ⌘K dialog parked its typed question
 * and opened the dock; consumed exactly once here and handed down as the
 * panel's `ask-question`, which prefills the composer. Each request installs a
 * fresh object, so repeat asks re-trigger the panel watch even with the same
 * wording.
 */
const askReq = ref<ChatAskRequest | null>(null)

watch(
  () => chatDockState.chatAsk,
  (request) => {
    if (request) askReq.value = consumeChatAsk()
  },
  { immediate: true },
)

watch(
  () => chatDockState.selectionAsk,
  (request) => {
    if (request) selectionRefs.value = consumeSelectionAsk() ?? []
  },
  { immediate: true },
)

watch(
  () => chatDockState.badgeRequest,
  (request) => {
    if (request) badgeId.value = consumeBadgeRequest()?.executionId ?? null
  },
  { immediate: true },
)

/**
 * Replay ask deep link : a batch-detail capsule parked its
 * execution-task pointer. It rides the SAME badge pointer channel (the
 * server-side replay timeline is gated by the diagnosis segment check on that
 * id), plus a fresh replayAsk object so the panel swaps the prefilled wording
 * to the replay question. Repeat capsules re-trigger the panel watch even with
 * the same task id.
 */
const replayReq = ref<ReplayAskRequest | null>(null)

watch(
  () => chatDockState.replayAsk,
  (request) => {
    if (!request) return
    const taskId = consumeReplayAsk()?.taskId ?? null
    if (taskId == null) return
    badgeId.value = taskId
    replayReq.value = { taskId }
  },
  { immediate: true },
)

/* Dock width = personal preference, per-user localStorage (ruling: never server). */
const width = ref(DOCK_DEFAULT_WIDTH)

function bucket() {
  return authStore.user?.userId
}

onMounted(() => {
  width.value = readDockWidth(localStorage, bucket())
  // Pin = personal preference, restored across reloads (D5); only an
  // explicit stored '1' pins, anything else keeps the light dock (D1).
  if (readDockPin(localStorage, bucket())) setChatDockPin(true)
  compactQuery = window.matchMedia(`(max-width: ${DOCK_COMPACT_BREAKPOINT - 1}px)`)
  syncCompactViewport()
  compactQuery.addEventListener('change', syncCompactViewport)
})

// Persist flips live (both doors — the header toggle and any future one —
// land on the same setter, so one watch covers every path; no second writer).
watch(
  () => chatDockState.pinned,
  (v) => {
    writeDockPin(localStorage, bucket(), v)
  },
)

// Drag persists live (release keeps the last clamped value; the same clamp
// function covers keyboard steps and the double-click reset — one fold point).
watch(width, (w) => {
  writeDockWidth(localStorage, bucket(), w)
})

function resetWidth() {
  writeDockWidth(localStorage, bucket(), null)
  width.value = DOCK_DEFAULT_WIDTH
}

function stepWidth(delta: number) {
  width.value = clampDockWidth(width.value + delta)
}

/* Grab strip: pointer drag horizontally resizes; clamped live each move. */
let resizeStartX = 0
let resizeStartWidth = 0

function onResizePointerDown(e: PointerEvent) {
  resizeStartX = e.clientX
  resizeStartWidth = width.value
  window.addEventListener('pointermove', onResizePointerMove)
  window.addEventListener('pointerup', onResizePointerUp)
}

function onResizePointerMove(e: PointerEvent) {
  // Dock hangs on the right edge: dragging left grows it, right shrinks it.
  width.value = clampDockWidth(resizeStartWidth + (resizeStartX - e.clientX))
}

function onResizePointerUp() {
  window.removeEventListener('pointermove', onResizePointerMove)
  window.removeEventListener('pointerup', onResizePointerUp)
  // Release keeps the clamped value already persisted through the watch.
  writeDockWidth(localStorage, bucket(), width.value)
}

/**
 * Click-outside collapse (D2): a pointerdown that lands neither inside the
 * dock body nor on the floating bubble closes the dock. Element-plus overlays
 * (message boxes) and poppers (tooltips/dropdowns teleported to the body) are
 * treated as "inside" so confirming a dialog never collapses the thread.
 * Collapse never interrupts the stream — the panel instance stays mounted.
 */
function onDocumentPointerDown(e: PointerEvent) {
  const target = e.target as HTMLElement | null
  if (!target) return
  if (target.closest('.ai-chat-drawer')) return
  if (target.closest('.ai-chat-fab')) return
  if (target.closest('.el-overlay')) return
  if (target.closest('.el-popper')) return
  // Resident dock : while pinned on a wide viewport
  // an outside click keeps the dock exactly where it is. The compact overlay
  // form deliberately ignores the pin so touch users can still tap past it.
  // Un-pinned, the collapse path above is byte-identical to the R33 default.
  // segment 3 folded the predicate into the dockResident computed (chatDockLayout
  // judge) — the one source the root class and the overlay offset read too.
  if (dockResident.value) return
  closeChatDock()
}

/**
 * Esc keyboard equivalence to close : same reachability as the
 * X button in every form — pinned or floating, wide or compact. A modal
 * dialog, message box or popper holding the keypress swallows the stroke
 * first, so the dock never collapses behind a layer mid-answer.
 */
function onDocumentKeydown(e: KeyboardEvent) {
  if (e.key !== 'Escape') return
  const target = e.target as HTMLElement | null
  if (target?.closest('.el-dialog, .el-message-box, .el-popper')) return
  closeChatDock()
}

/* Compact viewport mirror (single sourced from DOCK_COMPACT_BREAKPOINT). */
const compactViewport = ref(false)
let compactQuery: MediaQueryList | null = null

function syncCompactViewport() {
  compactViewport.value = compactQuery?.matches ?? false
}

/**
 * Resident form : pinned on a wide viewport. One
 * predicate for the click-outside guard, the drawer class, the document root
 * class and the overlay offset — chatDockLayout is the single judge source
 * (R40: nobody re-spells `pinned && !compact`).
 */
const dockResident = computed(() =>
  dockResidentJudge({ pinned: chatDockState.pinned, compact: compactViewport.value }),
)

watch(
  dockResident,
  (resident) => {
    document.documentElement.classList.toggle('dock-resident', resident)
  },
  { immediate: true },
)

watch(open, (v) => {
  if (v) {
    document.addEventListener('pointerdown', onDocumentPointerDown, true)
    document.addEventListener('keydown', onDocumentKeydown, true)
  } else {
    document.removeEventListener('pointerdown', onDocumentPointerDown, true)
    document.removeEventListener('keydown', onDocumentKeydown, true)
  }
})

/**
 * Layout reservation : the dock is
 * a non-modal overlay, so nothing about it makes the page behind unreachable —
 * and while the pinned-object panel is expanded its sticky body covers the
 * right edge of the table under it, which is exactly the "focusable control
 * hidden behind a fixed layer" defect the experience gate reports. The shell
 * therefore reserves the live dock width on its right edge whenever the dock is
 * open: the drawer keeps floating, but over its own reserved gutter, and every
 * page control stays visible. One writer (the dock owns the number), one token
 * (--tp-chat-dock-reserve, theme.scss), pages never read the dock.
 */
const DOCK_RESERVE_VAR = '--tp-chat-dock-reserve'
/* Resident squeeze token : the offset viewport-boxed layers
 (modal dialogs) must stop by, published through the single judge — zero
 outside the resident form, so the light/compact shapes stay byte-identical. */
const DOCK_OFFSET_VAR = '--tp-chat-dock-overlay-offset'

function applyDockReserve() {
  const px = open.value ? `${width.value}px` : '0px'
  document.documentElement.style.setProperty(DOCK_RESERVE_VAR, px)
  document.documentElement.style.setProperty(
    DOCK_OFFSET_VAR,
    dockOverlayOffsetPx(dockResident.value, open.value ? width.value : 0),
  )
}

watch([open, width, dockResident], applyDockReserve)
onMounted(applyDockReserve)

onUnmounted(() => {
  document.removeEventListener('pointerdown', onDocumentPointerDown, true)
  document.removeEventListener('keydown', onDocumentKeydown, true)
  compactQuery?.removeEventListener('change', syncCompactViewport)
  window.removeEventListener('pointermove', onResizePointerMove)
  window.removeEventListener('pointerup', onResizePointerUp)
  document.documentElement.style.removeProperty(DOCK_RESERVE_VAR)
  document.documentElement.style.removeProperty(DOCK_OFFSET_VAR)
  document.documentElement.classList.remove('dock-resident')
})
</script>

<style scoped>
/* Shell stretches the drawer body so the grab strip can sit on its left edge. */
.ai-chat-dock-shell {
  position: relative;
  display: flex;
  height: 100%;
  width: 100%;
}
.ai-chat-dock-shell > :deep(.chat-panel),
.ai-chat-dock-shell > * {
  flex: 1;
  min-width: 0;
}
/* 4px grab strip, hover/keyboard-focus changes colour to hint draggability
   (§sixteen rule 6). touch-action none = pointer drag works on touch too. */
.ai-chat-dock-resize {
  flex: none;
  width: 4px;
  cursor: col-resize;
  touch-action: none;
  background: transparent;
  transition: background-color 0.15s ease;
}
.ai-chat-dock-resize:hover,
.ai-chat-dock-resize:focus-visible {
  background: var(--tp-primary, #6366f1);
}
</style>

<style>
/* Drawer body padding zeroed so the grab strip truly hugs the left edge
   (global scope: the drawer teleports to the body, outside this file's
   scoped selectors). Width transitions off while dragging stay snappy. */
.ai-chat-drawer .el-drawer__body {
  padding: 0;
}
</style>
