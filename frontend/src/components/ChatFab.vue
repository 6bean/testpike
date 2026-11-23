<template>
  <!--
 Floating AI chat entry bubble .
 Mounted exactly once in MainLayout — never per page. Hidden while the dock
 body is open (no double-channel noise). Pointer-draggable with snap-to-edge,
 double-click/long-press reset; position is a per-user localStorage preference.
 -->
  <div
    v-if="fabVisible && !embedded"
    ref="fabRef"
    class="ai-chat-fab"
    data-test="ai-chat-fab"
    role="button"
    tabindex="0"
    :aria-label="t('layout.chatFab.aria')"
    :title="t('layout.chatFab.tip')"
    :style="positionStyle"
    @pointerdown="onPointerDown"
    @dblclick="resetToDefault"
    @contextmenu.prevent="resetToDefault"
    @keydown.enter.prevent="activate"
    @keydown.space.prevent="activate"
  >
    <el-icon :size="22"><ChatDotRound /></el-icon>
    <!--
 Running halo : a stream still working while the dock is
 collapsed shows a soft pulsing ring — reminder-halo discipline, never a
 harsh bar. Reopening the dock resumes replay (Last-Event-ID channel).
 -->
    <span
      v-if="streaming"
      class="ai-chat-fab-halo"
      data-test="chat-fab-running"
      :aria-label="t('layout.chatFab.runningTip')"
      :title="t('layout.chatFab.runningTip')"
    ></span>
    <!--
 Unread badge : three states from one
 computed — running shows the halo PLUS the number gathered so far,
 idle shows a static red number, quiet (0 and nothing running) shows
 nothing at all. Counts above the cap render "<cap>+" — more is
 announced honestly, never silently truncated.
 -->
    <span
      v-if="badgeText"
      class="ai-chat-fab-badge"
      :class="{ 'is-running': streaming }"
      data-test="chat-fab-badge"
      :aria-label="t('layout.chatFab.unreadTip', { count: badgeText })"
      :title="t('layout.chatFab.unreadTip', { count: badgeText })"
    >{{ badgeText }}</span>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ChatDotRound } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { chatDockState, fabVisible, toggleChatDock, bindChatUnreadStorage, chatUnreadTotal } from '@/composables/useChatDock'
import { badgeLabel, DEFAULT_BADGE_CAP } from '@/composables/chatUnreadBadge'
import { listConfigs } from '@/api/config'
import {
  clampFabPosition,
  defaultFabPosition,
  isFabDrag,
  readFabPosition,
  snapFabToEdge,
  writeFabPosition,
  FAB_SIZE,
  type BlockedBand,
  type FabPoint,
} from '@/composables/useChatFabPosition'

const { t } = useI18n()
const authStore = useAuthStore()
/** Stream working while the dock is collapsed → the bubble carries the halo. */
const streaming = computed(() => chatDockState.streaming)
/**
 * Unread badge : the ledger total rendered with the cap. The cap
 * hot-reads the system config key when the row exists (admin-permission
 * gated fetch, fail-soft); without a row the factory default stands.
 */
const badgeCap = ref(DEFAULT_BADGE_CAP)
const badgeText = computed(() => badgeLabel(chatUnreadTotal.value, badgeCap.value))

async function loadBadgeCap(): Promise<void> {
  if (!authStore.hasPerm('perm:system:system-config')) return
  try {
    // group_name literal = backend contract (tp_sys_config seed single
    // source, same posture as StepTree's usage caps reader); the CJK wire
    // value rides a \uXXXX escape so the component source stays ASCII.
    const GROUP_SYSTEM = '\u7cfb\u7edf'
    const rows = await listConfigs(GROUP_SYSTEM)
    const n = Number(rows.find((r) => r.configKey === 'ai.chat.badge-cap')?.configValue)
    if (Number.isFinite(n) && n > 0) badgeCap.value = n
  } catch {
    // fail-soft: the default cap keeps the badge honest either way.
  }
}
const fabRef = ref<HTMLElement | null>(null)
/** Guard-rail c: never render the bubble inside an embedded (iframe) frame. */
const embedded = ref(window.self !== window.top)
const point = ref<FabPoint>(defaultFabPosition({ width: window.innerWidth, height: window.innerHeight }))

const positionStyle = computed(() => ({ left: `${point.value.left}px`, top: `${point.value.top}px` }))

/** Blocked bands read live: the header strip and the bottom pagination strip. */
function blockedBands(): BlockedBand[] {
  const bands: BlockedBand[] = []
  const header = document.querySelector('.layout-header')
  if (header) {
    const r = header.getBoundingClientRect()
    bands.push({ top: r.top, bottom: r.bottom, left: r.left, right: r.right })
  }
  document.querySelectorAll('.el-pagination').forEach((el) => {
    const r = el.getBoundingClientRect()
    if (r.height > 0) bands.push({ top: r.top, bottom: r.bottom, left: r.left, right: r.right })
  })
  return bands
}

function viewport() {
  return { width: window.innerWidth, height: window.innerHeight }
}

function persist() {
  writeFabPosition(localStorage, authStore.user?.userId, point.value)
}

function resetToDefault() {
  point.value = defaultFabPosition(viewport())
  writeFabPosition(localStorage, authStore.user?.userId, null)
}

function activate() {
  toggleChatDock(window.location.hash || '/')
}

let startX = 0
let startY = 0
let originLeft = 0
let originTop = 0
let dragging = false

function onPointerDown(e: PointerEvent) {
  startX = e.clientX
  startY = e.clientY
  originLeft = point.value.left
  originTop = point.value.top
  dragging = false
  window.addEventListener('pointermove', onPointerMove)
  window.addEventListener('pointerup', onPointerUp)
}

function onPointerMove(e: PointerEvent) {
  const dx = e.clientX - startX
  const dy = e.clientY - startY
  if (!dragging && !isFabDrag(dx, dy)) return
  dragging = true
  e.preventDefault?.()
  point.value = clampFabPosition(originLeft + dx, originTop + dy, viewport(), blockedBands())
}

function onPointerUp(e: PointerEvent) {
  window.removeEventListener('pointermove', onPointerMove)
  window.removeEventListener('pointerup', onPointerUp)
  if (dragging) {
    // release snaps to the nearer edge (12px inset), keep the vertical slot
    const snapped = snapFabToEdge(e.clientX - FAB_SIZE / 2, viewport())
    point.value = clampFabPosition(snapped, point.value.top, viewport(), blockedBands())
    persist()
  } else {
    activate()
  }
}

function onResize() {
  point.value = clampFabPosition(point.value.left, point.value.top, viewport(), blockedBands())
}

onMounted(() => {
  bindChatUnreadStorage(localStorage, String(authStore.user?.userId ?? 'anon'))
  void loadBadgeCap()
  const saved = readFabPosition(localStorage, authStore.user?.userId)
  point.value = clampFabPosition(
    saved?.left ?? defaultFabPosition(viewport()).left,
    saved?.top ?? defaultFabPosition(viewport()).top,
    viewport(),
    blockedBands(),
  )
  window.addEventListener('resize', onResize)
})

onUnmounted(() => {
  window.removeEventListener('resize', onResize)
  window.removeEventListener('pointermove', onPointerMove)
  window.removeEventListener('pointerup', onPointerUp)
})
</script>

<style scoped>
/* z-index single-sourced here per §thirteen (floating layer, above content,
   below element-plus dialogs/overlays which start at 2000). */
.ai-chat-fab {
  position: fixed;
  z-index: 1900;
  width: 48px;
  height: 48px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--tp-primary, #6366f1);
  color: #fff;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.35);
  cursor: grab;
  touch-action: none;
  user-select: none;
}
.ai-chat-fab:active {
  cursor: grabbing;
}
/* Soft pulsing ring = a stream is still working behind the collapsed dock
 . */
.ai-chat-fab-halo {
  position: absolute;
  inset: -4px;
  border-radius: 50%;
  border: 2px solid var(--tp-primary, #6366f1);
  opacity: 0.5;
  pointer-events: none;
  animation: tp-fab-halo 1.6s ease-in-out infinite;
}
@keyframes tp-fab-halo {
  0% {
    transform: scale(0.92);
    opacity: 0.6;
  }
  50% {
    transform: scale(1.06);
    opacity: 0.25;
  }
  100% {
    transform: scale(0.92);
    opacity: 0.6;
  }
}
/* Static red count at the bubble's top-right (idle state); rides the halo
   unchanged while a stream is still running (three-state rule §two.3). */
.ai-chat-fab-badge {
  position: absolute;
  top: -2px;
  right: -2px;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 9px;
  background: var(--tp-danger, #f56c6c);
  color: #fff;
  font-size: 11px;
  font-weight: 600;
  line-height: 18px;
  text-align: center;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.4);
  pointer-events: none;
}
</style>
