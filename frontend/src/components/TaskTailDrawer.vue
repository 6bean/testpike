<template>
  <!-- tail -f drawer shared by the long-task faces : extraction
 now, generation / batch detail consume the same component later. -->
  <div class="task-tail" data-test="task-tail-drawer">
    <div class="task-tail-head">
      <span class="task-tail-title" data-test="task-tail-title">{{ t('document.tailTitle') }}</span>
      <span
        v-for="level in LEVELS"
        :key="level"
        class="task-tail-chip"
        :class="{ 'is-off': !visibleLevels.has(level) }"
        :data-test="`task-tail-chip-${level}`"
        role="button"
        tabindex="0"
        @click="toggleLevel(level)"
        @keydown.enter="toggleLevel(level)"
      >{{ levelLabel(level) }}</span>
      <el-button
        v-if="errorCount > 0"
        link
        type="danger"
        size="small"
        class="task-tail-error-anchor"
        data-test="task-tail-error-anchor"
        @click="jumpToLatestError"
      >{{ t('document.tailErrorAnchor', { count: errorCount }) }}</el-button>
      <span class="task-tail-spacer" />
      <!-- Unread badge : while the follow is paused, frames that
 landed are counted so "back to bottom" carries the news number. -->
      <span v-if="unread > 0" class="task-tail-unread" data-test="task-tail-unread">
        +{{ unread > 99 ? '99+' : unread }}
      </span>
      <!-- Stop door : shown only while the observed task is live and
 only when the host wires an available cancel endpoint. The drawer
 just emits `stop`; the host owns the call and the failure feedback. -->
      <el-button
        v-if="canStop && live"
        size="small"
        type="danger"
        plain
        data-test="task-tail-stop"
        @click="emit('stop')"
      >{{ t('document.tailStop') }}</el-button>
      <span v-if="!following" class="task-tail-paused" data-test="task-tail-paused">
        {{ t('document.tailPaused') }}
      </span>
      <el-button
        size="small"
        :type="following ? 'default' : 'primary'"
        data-test="task-tail-jump-latest"
        @click="resumeFollow"
      >{{ t('document.tailJumpLatest') }}</el-button>
    </div>
    <div
      ref="listEl"
      class="task-tail-list"
      data-test="task-tail-list"
      style="height: 260px"
      @scroll="onScroll"
    >
      <div v-if="expired" class="task-tail-expired" data-test="task-tail-expired">
        {{ t('runHistory.tailExpired', { param: retentionParam ?? '' }) }}
      </div>
      <div v-else-if="shown.length === 0" class="task-tail-empty" :data-test="firstFrameDegrade ? 'task-tail-first-frame' : 'task-tail-empty'">
        {{ firstFrameDegrade ? t('document.tailFirstFrameDegrade') : live ? t('document.tailWaiting') : t('document.tailEmpty') }}
      </div>
      <div
        v-if="padTop > 0"
        class="task-tail-pad"
        data-test="task-tail-pad-top"
        :style="{ height: padTop + 'px' }"
      />
      <div
        v-for="(row, offset) in visibleRows"
        :key="rowKey(row)"
        :ref="el => registerRow(el, rowKey(row))"
        class="task-tail-row"
        data-windowed-row
        :class="[`level-${row.level}`, { 'is-open': isOpen(row, winStart + offset) }]"
        :data-test="`task-tail-row-${winStart + offset}`"
      >
        <div class="task-tail-line">
          <span class="task-tail-time" :title="formatTailTime(row.landedAt)">{{ formatTailTime(row.landedAt) }}</span>
          <span class="task-tail-level" :class="`level-${row.level}`">{{ levelLabel(row.level) }}</span>
          <span class="task-tail-summary">{{ row.summary }}</span>
          <span v-if="row.detail && !isOpen(row, winStart + offset)" class="task-tail-detail" :title="row.detail">{{ formatDetailForDisplay(row.detail) }}</span>
          <el-button
            v-if="row.detail"
            link
            size="small"
            class="trail-toggle"
            :data-test="`trail-toggle-${rowKey(row)}`"
            @click="toggleRow(row, winStart + offset)"
          >{{ isOpen(row, winStart + offset) ? t('document.tailCollapse') : t('document.tailExpand') }}</el-button>
        </div>
        <!-- collapsible full-text block : monospace pre-wrap,
 scrolls INSIDE the block so the windowed list keeps its geometry;
 the live newest row opens by default, history stays collapsed -->
        <div v-if="row.detail && isOpen(row, winStart + offset)" class="task-tail-detail-block" :data-test="`trail-detail-${rowKey(row)}`">
          <template v-for="(line, li) in trailLines(row.detail)" :key="li">
            <div v-if="line.kind === 'masked'" class="trail-line is-masked">
              <span
                v-if="revealedText(row.cursor, line)"
                class="trail-revealed"
                :data-test="`trail-revealed-${line.field}`"
              >{{ revealedText(row.cursor, line) }}</span>
              <span v-else class="trail-line-text">{{ line.text }}</span>
              <el-button
                v-if="doors"
                link
                size="small"
                class="trail-reveal"
                :data-test="`trail-reveal-${line.field}`"
                @click="revealField(row, line)"
              >{{ t('document.tailReveal') }}</el-button>
            </div>
            <div v-else-if="line.kind === 'pointer'" class="trail-line is-pointer">
              <span class="trail-line-text">{{ line.text }}</span>
              <el-button
                v-if="doors && !fullOf(row.cursor)"
                link
                size="small"
                :data-test="`trail-full-${row.cursor}`"
                @click="loadFull(row, 0)"
              >{{ t('document.tailExpandFull') }}</el-button>
            </div>
            <div v-else class="trail-line">{{ line.text }}</div>
          </template>
          <div v-if="fullOf(row.cursor)" class="trail-full" :data-test="`trail-full-text-${row.cursor}`">
            <div class="trail-full-body">{{ fullOf(row.cursor)!.text }}</div>
            <el-button
              v-if="fullOf(row.cursor)!.hasMore"
              link
              size="small"
              :data-test="`trail-full-more-${row.cursor}`"
              @click="loadMore(row)"
            >{{ t('document.tailLoadMore') }}</el-button>
            <span v-else class="trail-full-done">{{ t('document.tailFullDone') }}</span>
          </div>
          <!-- the copy door stays masked-only: it always writes the original
               trail text, never the in-memory revealed plaintext -->
          <el-button
            link
            size="small"
            class="trail-copy"
            :data-test="`trail-copy-${rowKey(row)}`"
            @click="copyMasked(row)"
          >{{ t('document.tailCopy') }}</el-button>
        </div>
      </div>
      <!-- Heartbeat row : silence beyond the threshold still
 proves the stream is alive — an empty window is never silent. -->
      <div v-if="live && stalled && !interrupted" class="task-tail-row is-meta" data-test="task-tail-heartbeat">
        {{ t('document.tailHeartbeat', { seconds: heartbeatSeconds ?? TAIL_HEARTBEAT_DEFAULT_S }) }}
      </div>
      <div v-if="interrupted" class="task-tail-row is-meta level-error" data-test="task-tail-interrupted">
        {{ t('document.tailInterrupted') }}
      </div>
      <div
        v-if="padBottom > 0"
        class="task-tail-pad"
        data-test="task-tail-pad-bottom"
        :style="{ height: padBottom + 'px' }"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * Process-event tail of one AI long task = watch the thinking
 * process live so a human can steer it; segment d, R62). The list keeps a
 * constant-height, windowed DOM: only a segment of rows is rendered, spacers
 * hold the scrollbar honest, and the memory buffer itself is already
 * tail-trimmed by the runner's cap option — the drawer never grows with the
 * frame count ("sliding window is not an unlimited dialog"). The list
 * auto-follows the tail while the user stays at the bottom; scrolling up
 * pauses the follow so reading history is never fought by the stream, and
 * one click (with the unread count) returns to the newest row. Level chips
 * filter thinking / step / error; error rows get a red anchor that jumps to
 * the latest failure.
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch, type ComponentPublicInstance } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import {
  formatDetailForDisplay,
  TRAIL_BODY_SECTION_MARK,
  TRAIL_FULL_POINTER_MARK,
  TRAIL_MASK_MARK,
  TRAIL_REQUEST_SECTION_MARK,
  TRAIL_RESPONSE_SECTION_MARK,
  type ExtractionEvent,
  type TrailDoorApi,
} from '@/api/taskEvents'
import { useWindowedList } from '@/composables/useWindowedList'
import { TAIL_HEARTBEAT_DEFAULT_S } from '@/composables/tailWindowConfig'

const props = defineProps<{
  events: ExtractionEvent[]
  /** true while the observed task is still running (drives the empty hint) */
  live: boolean
  /** host declares a cancel door for this scene; default off = honest absence */
  canStop?: boolean
  /** stream alive but silent beyond the heartbeat threshold (opt-in door) */
  stalled?: boolean
  /** polling ran out WITHOUT a terminal frame (opt-in door) */
  interrupted?: boolean
  /** heartbeat hint interval seconds (hot key ui.tail.heartbeat-seconds) */
  heartbeatSeconds?: number
  /** first-frame wait budget ms (hot key ui.tail.first-frame-ms); leaving it
   *  undefined keeps the legacy waiting text (synchronous doors, zero drift) */
  firstFrameMs?: number
  /**
 * Audited read doors over exchange-trail rows : wired only by
 * the login-state probe and the credential test. Without it the masked
 * lines stay masked and the pointer line stays an honest statement — the
 * reveal / full-text buttons are an absence, never a dead control.
 */
  doors?: TrailDoorApi
  /**
 * R67 retention exit (opt-in, T701d-expiry-1): when the host proves the
 * replayed run had frames while the window came back empty, the drawer
 * itself swaps the list for the three-part pointer (cause names the
 * record, the domain's hot-reload parameter key shows verbatim, the
 * settings page path stays literal — R63). Absent = today's behaviour,
 * zero drift for every live tail.
 */
  expired?: boolean
  /** the domain's retention parameter key, rendered verbatim in the pointer */
  retentionParam?: string
}>()

const emit = defineEmits<{ stop: [] }>()

const { t } = useI18n()

const LEVELS = ['thinking', 'step', 'error'] as const

const listEl = ref<HTMLElement | null>(null)
const following = ref(true)
const unread = ref(0)
const visibleLevels = ref<Set<string>>(new Set(LEVELS))
const rowEls = new Map<string, HTMLElement>()

/** DOM/state identity of one row. Aggregated batch tails merge several task
 *  ledgers where cursors repeat per source (the wire dedupe key is
 *  scope+cursor) — keying the list by bare cursor produced duplicate Vue
 *  keys and the patcher thrashed the DOM on every frame (user report
 *  2026-11-20: the tail pane flickered nonstop). Single-source tails have no
 *  scope, so their keys stay the bare cursor and every existing
 *  trail-toggle/reveal test anchor keeps working. */
function rowKey(row: ExtractionEvent): string {
  return row.scope ? `${row.scope}.${row.cursor}` : String(row.cursor)
}

const shown = computed(() =>
  props.events.filter(row => visibleLevels.value.has(row.level)))

const errorCount = computed(() => props.events.filter(r => r.level === 'error').length)

// ---------- sliding window (R62): DOM rows ≈ visible + small buffers ------
// useWindowedList is the repo-wide single virtualiser (chat / apitest /
// perf consume it already) — consuming it here instead of a second copy.
// rowHeight feeds REAL cumulative heights (measured by the row ResizeObserver
// below): a trail row expands from ~23px to ~217px, and the old uniform-pitch
// spacer treated those rows as 23px both in and out of the window, so every
// expand/collapse crossing the window edge rescaled scrollHeight mid-scroll
// (1067↔1710 observed) and shifted the viewport = the reported occasional
// blank while scrolling up. With exact pads the geometry never rescales.
const rowHeights = new Map<string, number>()
const win = useWindowedList(shown, {
  scroller: () => listEl.value,
  rowPitch: 22,
  buffer: 4,
  rowHeight: (index) => {
    const row = shown.value[index]
    return row ? rowHeights.get(rowKey(row)) ?? null : null
  },
})
const visibleRows = win.visible
const padTop = win.padTop
const padBottom = win.padBottom
const winStart = win.start

// ---------- first-frame degrade door ------------------------------
const openedAt = ref(Date.now())
const nowTick = ref(Date.now())
let firstFrameTimer: ReturnType<typeof setInterval> | null = null
const firstFrameDegrade = computed(() =>
  props.firstFrameMs !== undefined
  && props.live
  && shown.value.length === 0
  && nowTick.value - openedAt.value >= props.firstFrameMs)
onMounted(() => {
  win.sync()
  win.measurePitch()
  if (props.firstFrameMs !== undefined) {
    openedAt.value = Date.now()
    nowTick.value = Date.now()
    firstFrameTimer = setInterval(() => { nowTick.value = Date.now() }, 1000)
  }
})
onBeforeUnmount(() => {
  // paired recycle of the ticking timer (R57): created only with the door on
  if (firstFrameTimer !== null) {
    clearInterval(firstFrameTimer)
    firstFrameTimer = null
  }
  // paired recycle of the row height observers (R57)
  rowRo?.disconnect()
  rowRo = null
  rowHeights.clear()
})

function levelLabel(level: string): string {
  if (level === 'thinking') return t('document.tailLevelThinking')
  if (level === 'error') return t('document.tailLevelError')
  return t('document.tailLevelStep')
}

/** Tail rows show time-of-day HH:MM:SS; the full stamp rides the tooltip. */
function formatTailTime(landedAt: string): string {
  if (!landedAt) return ''
  const idx = landedAt.indexOf('T')
  const clock = idx >= 0 ? landedAt.slice(idx + 1, idx + 9) : landedAt.slice(0, 8)
  return clock
}

function registerRow(el: Element | ComponentPublicInstance | null, key: string): void {
  if (el instanceof HTMLElement) {
    rowEls.set(key, el)
    rowKeyByEl.set(el, key)
    ensureRowObserver()
    rowRo?.observe(el)
  } else {
    const prev = rowEls.get(key)
    if (prev) {
      rowRo?.unobserve(prev)
      rowKeyByEl.delete(prev)
    }
    rowEls.delete(key)
  }
}

/** Row height truth (11-20 blank/jitter fix, second layer): expanded trail
 * rows are ~9x taller than collapsed ones, and the pads must know their REAL
 * heights even after they slide out of the window. A ResizeObserver per row
 * element keeps rowHeights current; removals are unobserved explicitly and
 * the map is pruned against the live source (R57: bounded, evicted, created
 * in the same function as its recycle pair). */
const rowKeyByEl = new WeakMap<HTMLElement, string>()
let rowRo: ResizeObserver | null = null
function ensureRowObserver(): void {
  if (rowRo || typeof ResizeObserver === 'undefined') return
  rowRo = new ResizeObserver((entries) => {
    let changed = false
    for (const entry of entries) {
      const el = entry.target as HTMLElement
      const key = rowKeyByEl.get(el)
      if (!key) continue
      const h = Math.round(el.offsetHeight)
      if (h > 8 && rowHeights.get(key) !== h) {
        rowHeights.set(key, h)
        changed = true
      }
    }
    if (changed) win.bumpGeometry()
  })
}

watch(shown, (rows) => {
  // Prune stale keys after tail-trim/level-filter so the map stays bounded.
  if (rowHeights.size > rows.length * 2 + 16) {
    const live = new Set(rows.map(rowKey))
    for (const k of [...rowHeights.keys()]) if (!live.has(k)) rowHeights.delete(k)
  }
})

function toggleLevel(level: string): void {
  const next = new Set(visibleLevels.value)
  if (next.has(level)) next.delete(level)
  else next.add(level)
  // Never filter everything away: an all-off click resets to all-on.
  visibleLevels.value = next.size === 0 ? new Set(LEVELS) : next
}

function onScroll(): void {
  const el = listEl.value
  if (!el) return
  const distanceToBottom = el.scrollHeight - el.scrollTop - el.clientHeight
  following.value = distanceToBottom < 40
  win.sync()
  win.measurePitch()
}

function scrollToBottom(): void {
  void nextTick(() => {
    const el = listEl.value
    if (el) el.scrollTop = el.scrollHeight
    win.sync()
  })
}

function resumeFollow(): void {
  following.value = true
  unread.value = 0
  scrollToBottom()
}

function jumpToLatestError(): void {
  const lastError = [...props.events].reverse().find(r => r.level === 'error')
  if (!lastError) return
  following.value = false
  const idx = shown.value.findIndex(r => rowKey(r) === rowKey(lastError))
  const el = listEl.value
  if (el && idx >= 0) {
    // windowed rows only exist near the viewport: land the scroll first so
    // the window contains the target, then centre it in the next tick
    el.scrollTop = Math.max(0, win.offsetOf(idx) - Math.floor(el.clientHeight / 3))
    win.sync()
  }
  void nextTick(() => {
    rowEls.get(rowKey(lastError))?.scrollIntoView({ block: 'center' })
  })
}

// ---------- exchange-trail read doors ------------------------
/** One parsed line of a trail block: plain text, a masked header awaiting
 *  reveal, or the honest over-budget pointer awaiting the full-text door. */
interface TrailLine {
  kind: 'line' | 'masked' | 'pointer'
  text: string
  /** `request:<name>` / `response:<name>` — the reveal door's field key */
  field?: string
  /** 0-based occurrence of the same field in this row (multi Set-Cookie) */
  occ?: number
}

const HEADER_LINE = /^([a-z0-9!#$%&'*+\-.^_`|~]+): (.*)$/

/**
 * Split one server-rendered trail (core ProbeExchangeRunner shape) into
 * lines, tagging masked header values with their section-qualified field key
 */
function analyzeTrail(detail: string): TrailLine[] {
  let section: 'request' | 'response' | null = null
  const seenOcc = new Map<string, number>()
  const out: TrailLine[] = []
  for (const raw of detail.split('\n')) {
    if (raw.startsWith(TRAIL_REQUEST_SECTION_MARK)) {
      section = 'request'
      out.push({ kind: 'line', text: raw })
      continue
    }
    if (raw.startsWith(TRAIL_RESPONSE_SECTION_MARK)) {
      section = 'response'
      out.push({ kind: 'line', text: raw })
      continue
    }
    if (raw.startsWith(TRAIL_BODY_SECTION_MARK)) {
      // body content is free text from here on — never treat it as headers
      section = null
      out.push({ kind: raw.includes(TRAIL_FULL_POINTER_MARK) ? 'pointer' : 'line', text: raw })
      continue
    }
    if (raw.includes(TRAIL_FULL_POINTER_MARK)) {
      out.push({ kind: 'pointer', text: raw })
      continue
    }
    const m = section === null ? null : HEADER_LINE.exec(raw)
    if (m && m[2].includes(TRAIL_MASK_MARK)) {
      const field = `${section}:${m[1]}`
      const occ = seenOcc.get(field) ?? 0
      seenOcc.set(field, occ + 1)
      out.push({ kind: 'masked', text: raw, field, occ })
      continue
    }
    out.push({ kind: 'line', text: raw })
  }
  return out
}

const trailLineCache = new Map<string, TrailLine[]>()
function trailLines(detail: string | null): TrailLine[] {
  if (!detail) return []
  let lines = trailLineCache.get(detail)
  if (!lines) {
    lines = analyzeTrail(detail)
    // bounded cache keyed by the trail text (R57): the drawer's own row
    // count already caps memory; evict wholesale once oversized
    if (trailLineCache.size > 400) trailLineCache.clear()
    trailLineCache.set(detail, lines)
  }
  return lines
}

/** user-explicit open/closed choices, keyed by row cursor */
const userToggles = ref<Map<string, boolean>>(new Map())

/** open = user choice; else the live newest row auto-opens ONLY on door-wired
 *  trail hosts (probe / credential) — door-less hosts keep the legacy
 *  single-line detail exactly (vision D1 contract, zero drift) */
function isOpen(row: ExtractionEvent, indexInShown: number): boolean {
  const manual = userToggles.value.get(rowKey(row))
  if (manual !== undefined) return manual
  return props.live && !!props.doors && indexInShown === shown.value.length - 1 && !!row.detail
}

function toggleRow(row: ExtractionEvent, indexInShown: number): void {
  const next = new Map(userToggles.value)
  const probe: ExtractionEvent = { ...row, detail: 'x' }
  next.set(rowKey(row), !isOpen(probe, indexInShown))
  userToggles.value = next
}

/** revealed plaintext, in memory only — never copied, never persisted */
const revealed = ref<Map<string, string[]>>(new Map())
const revealBusy = ref<Set<string>>(new Set())

function revealKey(cursor: number, field: string): string {
  return `${cursor}|${field}`
}

function revealedText(cursor: number, line: TrailLine): string {
  const values = revealed.value.get(revealKey(cursor, line.field ?? ''))
  if (!values || values.length === 0) return ''
  return values[line.occ ?? 0] ?? ''
}

async function revealField(row: ExtractionEvent, line: TrailLine): Promise<void> {
  const doors = props.doors
  const field = line.field
  if (!doors || !field || revealBusy.value.has(revealKey(row.cursor, field))) return
  revealBusy.value = new Set(revealBusy.value).add(revealKey(row.cursor, field))
  try {
    const answer = await doors.reveal(row.cursor, field)
    if (answer?.values?.length) {
      const next = new Map(revealed.value)
      next.set(revealKey(row.cursor, field), answer.values)
      revealed.value = next
    }
  } catch (e) {
    // the server sentence names the cause and the way to fix it (R63)
    ElMessage.error((e as Error)?.message || t('document.tailRevealFailed'))
  } finally {
    const done = new Set(revealBusy.value)
    done.delete(revealKey(row.cursor, field))
    revealBusy.value = done
  }
}

/** loaded full-text pages per row: accumulated text + the next cursor */
const fullBuf = ref<Map<number, { text: string; nextFrom: number; hasMore: boolean }>>(new Map())
/** cursors with a page in flight (same no-op re-entry shape as revealBusy):
 *  without this a double click requests the same cursor twice and the page
 *  text appends twice; the set empties in `finally`, no entry outlives a fetch */
const fullBusy = new Set<number>()

function fullOf(cursor: number): { text: string; nextFrom: number; hasMore: boolean } | undefined {
  return fullBuf.value.get(cursor)
}

async function loadFull(row: ExtractionEvent, from: number): Promise<void> {
  const doors = props.doors
  if (!doors) return
  if (fullBusy.has(row.cursor)) return
  fullBusy.add(row.cursor)
  try {
    const page = await doors.fullText(row.cursor, from)
    const prev = fullBuf.value.get(row.cursor)
    const next = new Map(fullBuf.value)
    next.set(row.cursor, {
      text: (prev?.text ?? '') + page.text,
      nextFrom: page.from + page.text.length,
      hasMore: page.hasMore,
    })
    fullBuf.value = next
  } catch (e) {
    ElMessage.error((e as Error)?.message || t('document.tailRevealFailed'))
  } finally {
    fullBusy.delete(row.cursor)
  }
}

function loadMore(row: ExtractionEvent): void {
  const buf = fullBuf.value.get(row.cursor)
  if (buf) void loadFull(row, buf.nextFrom)
}

/** copy answers with the masked original only — plaintext has no copy door */
function copyMasked(row: ExtractionEvent): void {
  if (!row.detail) return
  void navigator.clipboard?.writeText(row.detail)
}

watch(() => props.events.length, (next, prev) => {
  const delta = next - prev
  if (following.value) {
    scrollToBottom()
  } else {
    if (delta > 0) unread.value += delta
    win.sync()
  }
  win.measurePitch()
})

// level filters rewrite `shown` without touching events.length: re-derive
// the window so the visible segment never points at a stale index range
watch(() => shown.value.length, () => {
  if (following.value) scrollToBottom()
  else win.sync()
})
</script>

<style scoped>
.task-tail {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-blank);
}
.task-tail-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.task-tail-title {
  font-size: 13px;
  font-weight: 600;
}
.task-tail-chip {
  font-size: 12px;
  padding: 1px 8px;
  border-radius: 10px;
  border: 1px solid var(--el-border-color);
  cursor: pointer;
  user-select: none;
}
.task-tail-chip.is-off {
  opacity: 0.4;
  text-decoration: line-through;
}
.task-tail-spacer {
  flex: 1;
}
.task-tail-unread {
  font-size: 12px;
  font-weight: 600;
  color: var(--el-color-primary);
}
.task-tail-paused {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.task-tail-list {
  /* constant geometry (R62): the height lives on the inline style so the
     box measures 260px in every host (E2E boundingBox + component spectra),
     and content growth scrolls inside the window, never out */
  overflow-y: auto;
  padding: 4px 0;
}
.task-tail-expired {
  padding: 10px 12px;
  font-size: 12px;
  line-height: 1.7;
  opacity: 0.85;
}

.task-tail-empty {
  padding: 12px;
  text-align: center;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.task-tail-row {
  display: block;
  font-size: 12px;
  line-height: 1.6;
}
.task-tail-line {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 2px 10px;
}
.task-tail-row.is-meta {
  color: var(--el-text-color-secondary);
  font-style: normal;
}
.task-tail-row.level-error {
  background: var(--el-color-danger-light-9);
}
.task-tail-time {
  flex: none;
  color: var(--el-text-color-secondary);
  font-variant-numeric: tabular-nums;
}
.task-tail-level {
  flex: none;
  min-width: 40px;
  color: var(--el-text-color-secondary);
}
.task-tail-level.level-error {
  color: var(--el-color-danger);
  font-weight: 600;
}
.task-tail-summary {
  flex: 1;
  word-break: break-word;
}
.task-tail-detail {
  flex: none;
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--el-text-color-placeholder);
}
/* collapsible trail block : monospace pre-wrap, the scroll
 lives INSIDE the block so the constant-height window never grows */
.task-tail-detail-block {
  margin: 0 10px 4px 10px;
  padding: 4px 8px;
  max-height: 180px;
  overflow-y: auto;
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}
.trail-line.is-masked {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.trail-line.is-masked .trail-line-text {
  flex: 1;
  min-width: 0;
}
.trail-revealed {
  flex: 1;
  min-width: 0;
  color: var(--el-color-warning);
  font-weight: 600;
}
.trail-full {
  border-top: 1px dashed var(--el-border-color);
  margin-top: 4px;
  padding-top: 4px;
}
.trail-full-done {
  color: var(--el-text-color-secondary);
}
.trail-copy,
.trail-toggle {
  flex: none;
}
</style>
