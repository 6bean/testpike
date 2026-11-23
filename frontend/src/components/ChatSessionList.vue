<template>
  <!--
 Shared session library : ONE piece mounted by both
 surfaces — the standalone page's left column and the dock's whole-body
 list view. Paging, keyword search and date grouping all ride the server
 doors ; the browser never accumulates the whole ledger. Worst-case
 scale (S group, 500 sessions): rows window through useWindowedList, the
 same engine the conversation thread uses — DOM stays one window deep.
 The project segment is the global header context (R30 single source): no
 second selector here; without a segment the panel answers the honest hint.
 Archived rows are queried with an explicit status — the core default
 face returns active rows only (pinned by the L3 door contract).
 No in-page title row (R23): the dock header / top header carry the name.
 -->
  <div class="session-list" data-test="chat-session-list">
    <div class="session-toolbar">
      <el-button type="primary" data-test="session-new" :aria-label="t('layout.chatDock.sessionList.newChat')" @click="emit('create')">
        <el-icon><Plus /></el-icon>
        <span>{{ t('layout.chatDock.sessionList.newChat') }}</span>
      </el-button>
      <el-input
        v-model="keyword"
        data-test="session-search"
        :placeholder="t('layout.chatDock.sessionList.search')"
        clearable
        :prefix-icon="Search"
        class="session-search"
      />
      <el-button
        text
        class="session-view-toggle"
        data-test="session-view-toggle"
        @click="toggleArchived"
      >{{ t(archived ? 'layout.chatDock.sessionList.viewArchived' : 'layout.chatDock.sessionList.viewActive') }}</el-button>
    </div>

    <el-empty
      v-if="!loading && rows.length === 0"
      :description="emptyText"
      :image-size="64"
      data-test="session-empty"
    >
      <!-- Empty-ledger onboarding : the hint names the door
 and the door sits under it — ONE activation from a fresh segment to a
 fresh thread. Rides the shared create door (host owns the single
 newConversation owner); the archived face and the missing-slice
 face show the hint only — no dead button pointing at a creation
 the current context cannot serve. -->
      <el-button
        v-if="!archived && ctx.primaryId != null"
        type="primary"
        data-test="session-empty-new"
        @click="emit('create')"
      >{{ t('layout.chatDock.sessionList.newChat') }}</el-button>
    </el-empty>

    <div v-show="rows.length > 0" ref="scrollerEl" class="session-scroller" data-test="session-scroller" @scroll="list.sync()">
      <div v-if="padTop > 0" class="session-pad" :style="{ height: `${padTop}px` }" />
      <template v-for="item in visibleRows" :key="itemKey(item)">
        <button
          v-if="item.kind === 'group'"
          class="session-group-head"
          data-test="session-group-head"
          :aria-expanded="!collapsed.has(item.key)"
          @click="toggleGroup(item.key)"
        >
          <el-icon class="session-group-caret" :class="{ 'is-collapsed': collapsed.has(item.key) }"><ArrowDown /></el-icon>
          <span class="session-group-label">{{ item.label }}</span>
          <span class="session-group-count">{{ item.count }}</span>
        </button>
        <div
          v-else-if="!collapsed.has(groupKeyOf(item.session))"
          class="session-row"
          :class="{ 'is-current': item.session.id === currentId }"
          data-test="session-row"
          data-windowed-row
          role="button"
          tabindex="0"
          @click="emit('open', item.session.id)"
          @keydown.enter.prevent="emit('open', item.session.id)"
        >
          <template v-if="editingId === item.session.id">
            <el-input
              v-model="editingTitle"
              size="small"
              data-test="session-rename-input"
              @keyup.enter="commitRename(item.session)"
              @blur="commitRename(item.session)"
            />
          </template>
          <template v-else>
            <span class="session-title" :title="item.session.title">{{ item.session.title || t('layout.chatDock.sessionList.untitled') }}</span>
            <span class="session-meta">
              <TimeCell :iso="item.session.updatedAt" />
              <span class="session-depth">{{ t('layout.chatDock.sessionList.messages', { n: item.session.messageCount }) }}</span>
              <el-tooltip :content="t('layout.chatDock.sessionList.rename')" placement="top">
                <el-icon class="session-op" :aria-label="t('layout.chatDock.sessionList.rename')" @click.stop="startRename(item.session)"><EditPen /></el-icon>
              </el-tooltip>
              <el-tooltip v-if="!archived" :content="t('layout.chatDock.sessionList.archive')" placement="top">
                <el-icon class="session-op" :aria-label="t('layout.chatDock.sessionList.archive')" @click.stop="archive(item.session)"><FolderDelete /></el-icon>
              </el-tooltip>
            </span>
          </template>
        </div>
      </template>
      <div v-if="padBottom > 0" class="session-pad" :style="{ height: `${padBottom}px` }" />
      <el-button
        v-if="rows.length < total"
        text
        class="session-more"
        data-test="session-load-more"
        :loading="loading"
        @click="loadPage(pageRef + 1)"
      >{{ t('layout.chatDock.sessionList.loadMore', { n: total - rows.length }) }}</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, EditPen, FolderDelete, Plus, Search } from '@element-plus/icons-vue'
import TimeCell from '@/components/TimeCell.vue'
import { useProjectContextStore } from '@/stores/projectContext'
import { useWindowedList } from '@/composables/useWindowedList'
import {
  archiveChatSession,
  getChatSessionGroupDays,
  listChatSessions,
  renameChatSession,
  type ChatSessionListItem,
} from '@/api/aiChat'

/**
 * Row shapes of the library (server contract pinned in api/aiChat).
 * `currentId` only decorates the open thread — selection itself is the
 * host's single state (ChatPanel owns sessionId, zero second copy).
 */
const props = defineProps<{ currentId?: number | null }>()
const emit = defineEmits<{
  /** Open one owned session (host parks the current thread and loads it). */
  open: [sessionId: number]
  /** New conversation: the host seals the current thread's pointer. */
  create: []
}>()

const { t } = useI18n()
const ctx = useProjectContextStore()

/** Statuses mirrored from the ledger row (active face needs no parameter). */
const ARCHIVED = 1

/** Accumulated-page shape: the list grows by server pages, never re-fetches
 *  the whole ledger on every keystroke (pageSize is the window it loads). */
const PAGE_SIZE = 50

const rows = ref<ChatSessionListItem[]>([])
const total = ref(0)
const pageRef = ref(0)
const keyword = ref('')
const archived = ref(false)
const loading = ref(false)
const scrollerEl = ref<HTMLElement | null>(null)
const editingId = ref<number | null>(null)
const editingTitle = ref('')

/** Group buckets = hot key read through the admin door (parsed server-side).
 *  This array is only the unreachable-door floor, NOT the normal path. */
const buckets = ref<number[]>([])
const FALLBACK_BUCKETS = [1, 7, 30]

const currentId = computed(() => props.currentId ?? null)

/**
 * Date grouping is derived, never stored: rows arrive newest-first from the
 * server, and each row falls into the first bucket window it matches. Today
 * and yesterday are calendar days; the bucket numbers label themselves
 * (「last {n} days」), so a hot-key change reshapes the groups with zero code.
 */
function groupKeyOf(s: ChatSessionListItem): string {
  const ageMs = Date.now() - new Date(s.updatedAt).getTime()
  const dayMs = 86_400_000
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const sinceMidnight = Date.now() - today.getTime()
  if (ageMs <= sinceMidnight) return 'today'
  if (ageMs <= sinceMidnight + dayMs) return 'yesterday'
  for (const b of buckets.value) {
    if (b >= 2 && ageMs <= b * dayMs) return `last-${b}`
  }
  return 'earlier'
}

function groupLabel(key: string): string {
  if (key === 'today') return t('layout.chatDock.sessionList.groupToday')
  if (key === 'yesterday') return t('layout.chatDock.sessionList.groupYesterday')
  if (key === 'earlier') return t('layout.chatDock.sessionList.groupEarlier')
  return t('layout.chatDock.sessionList.groupLastDays', { n: Number(key.slice('last-'.length)) })
}

/** Group order: fixed calendar heads first, bucket windows ascending, tail last. */
function groupOrder(key: string): number {
  if (key === 'today') return -2
  if (key === 'yesterday') return -1
  if (key === 'earlier') return Number.MAX_SAFE_INTEGER
  return Number(key.slice('last-'.length))
}

/**
 * Flat window source: group heads + rows interleaved (heads are collapsible
 * buttons, rows hide when their group is folded). The windower sees one
 * uniform list, so DOM depth stays bounded even with hundreds of groups.
 */
const flat = computed(() => {
  const out: Array<
    | { kind: 'group'; key: string; label: string; count: number }
    | { kind: 'row'; session: ChatSessionListItem }
  > = []
  const heads: { key: string; count: number }[] = []
  const byKey = new Map<string, ChatSessionListItem[]>()
  for (const s of rows.value) {
    const k = groupKeyOf(s)
    if (!byKey.has(k)) {
      byKey.set(k, [])
      heads.push({ key: k, count: 0 })
    }
    byKey.get(k)!.push(s)
    heads.find((h) => h.key === k)!.count += 1
  }
  heads.sort((a, b) => groupOrder(a.key) - groupOrder(b.key))
  for (const h of heads) {
    out.push({ kind: 'group', key: h.key, label: groupLabel(h.key), count: h.count })
    for (const s of byKey.get(h.key)!) out.push({ kind: 'row', session: s })
  }
  return out
})

const list = useWindowedList(flat, { scroller: () => scrollerEl.value, rowPitch: 44, buffer: 6 })
const visibleRows = list.visible
const padTop = list.padTop
const padBottom = list.padBottom

/** Groups folded by hand or by the default (recent windows stay open). */
const collapsed = ref(new Set<string>())
function defaultCollapsed(): Set<string> {
  const keys = new Set(flat.value.filter((i) => i.kind === 'group').map((i) => (i as { key: string }).key))
  const openUntil = buckets.value.find((b) => b >= 2) ?? 7
  const next = new Set<string>()
  for (const k of keys) {
    if (k === 'earlier' || (k.startsWith('last-') && Number(k.slice(5)) > openUntil)) next.add(k)
  }
  return next
}
function toggleGroup(key: string) {
  const next = new Set(collapsed.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  collapsed.value = next
}

function itemKey(item: (typeof flat.value)[number]): string {
  return item.kind === 'group' ? `g:${item.key}` : `r:${item.session.id}`
}

const emptyText = computed(() =>
  ctx.primaryId == null
    ? t('layout.chatDock.noProjectTip')
    : t(archived.value ? 'layout.chatDock.sessionList.emptyArchived' : 'layout.chatDock.sessionList.empty'),
)

/** One page through the server door (segment = header context, R30). */
async function loadPage(page: number) {
  if (ctx.primaryId == null) {
    rows.value = []
    total.value = 0
    return
  }
  loading.value = true
  try {
    const res = await listChatSessions({
      projectIds: [ctx.primaryId],
      keyword: keyword.value.trim() || undefined,
      page,
      pageSize: PAGE_SIZE,
      ...(archived.value ? { status: ARCHIVED } : {}),
    })
    rows.value = page === 1 ? res.list : [...rows.value, ...res.list]
    total.value = res.total
    pageRef.value = page
    collapsed.value = defaultCollapsed()
    void list.measurePitch()
    list.sync()
  } catch (e) {
    // Server sentence shown as-is (single-source wording, never re-invented).
    ElMessage.error((e as Error)?.message || t('layout.chatDock.sessionList.loadFailed'))
  } finally {
    loading.value = false
  }
}

/** Search is a server query, not a client filter over an accumulation. */
let searchTimer: ReturnType<typeof setTimeout> | null = null
watch(keyword, () => {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => void loadPage(1), 300)
})

function toggleArchived() {
  archived.value = !archived.value
  void loadPage(1)
}

function startRename(s: ChatSessionListItem) {
  editingId.value = s.id
  editingTitle.value = s.title
}

/** Inline commit: blank titles are refused (the rename door rejects server-side). */
async function commitRename(s: ChatSessionListItem) {
  if (editingId.value !== s.id) return
  editingId.value = null
  const next = editingTitle.value.trim()
  if (next === '' || next === s.title) {
    if (next === '') ElMessage.warning(t('layout.chatDock.sessionList.renameEmpty'))
    return
  }
  try {
    await renameChatSession(s.id, next)
    s.title = next
    ElMessage.success(t('layout.chatDock.sessionList.renamed', { title: next }))
  } catch (e) {
    ElMessage.error((e as Error)?.message || t('layout.chatDock.sessionList.renameFailed'))
  }
}

/** Archive names its object and the consequence (R22 confirmation rule). */
async function archive(s: ChatSessionListItem) {
  try {
    await ElMessageBox.confirm(
      t('layout.chatDock.sessionList.archiveAsk', { title: s.title }),
      t('layout.chatDock.sessionList.archive'),
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await archiveChatSession(s.id)
    ElMessage.success(t('layout.chatDock.sessionList.archived', { title: s.title }))
    void loadPage(1)
  } catch (e) {
    ElMessage.error((e as Error)?.message || t('layout.chatDock.sessionList.archiveFailed'))
  }
}

async function loadBuckets() {
  try {
    const res = await getChatSessionGroupDays()
    if (res?.buckets?.length) buckets.value = res.buckets
  } catch {
    buckets.value = FALLBACK_BUCKETS
  }
}

watch(() => ctx.primaryId, () => void loadPage(1))

onMounted(async () => {
  await loadBuckets()
  if (!buckets.value.length) buckets.value = FALLBACK_BUCKETS
  void loadPage(1)
})

/** Hosts refresh the ledger after opening/archiving elsewhere (one door). */
defineExpose({ reload: () => loadPage(1) })
</script>

<style scoped>
.session-list {
  display: flex;
  flex-direction: column;
  min-height: 0;
  height: 100%;
  gap: 8px;
}
.session-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.session-search {
  flex: 1;
  min-width: 140px;
}
.session-view-toggle {
  flex-shrink: 0;
}
.session-scroller {
  flex: 1;
  overflow-y: auto;
  min-height: 0;
}
.session-group-head {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  min-height: 28px;
  padding: 2px 8px;
  border: none;
  background: none;
  cursor: pointer;
  color: var(--tp-text-secondary);
  font-size: 12px;
  text-align: left;
}
.session-group-caret {
  transition: transform 0.15s ease;
}
.session-group-caret.is-collapsed {
  transform: rotate(-90deg);
}
.session-group-count {
  margin-left: auto;
  font-variant-numeric: tabular-nums;
}
.session-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 40px;
  padding: 4px 8px;
  border-radius: 6px;
  cursor: pointer;
}
.session-row:hover {
  background: var(--tp-bg-hover, rgba(125, 125, 125, 0.08));
}
.session-row.is-current {
  background: var(--el-color-primary-light-9);
}
.session-title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.session-meta {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
  font-size: 12px;
  color: var(--tp-text-secondary);
}
.session-depth {
  font-variant-numeric: tabular-nums;
}
.session-op {
  cursor: pointer;
  padding: 4px;
  border-radius: 4px;
}
.session-op:hover {
  color: var(--el-color-primary);
}
.session-more {
  width: 100%;
  justify-content: center;
}
</style>
