<template>
 
  <el-dialog
    v-model="visible"
    :show-close="false"
    width="640px"
    top="10vh"
    class="global-search-dialog"
    data-test="global-search-dialog"
    @keydown.down.prevent="moveCursor(1)"
    @keydown.up.prevent="moveCursor(-1)"
    @keydown.enter.prevent="openCurrent()"
  >
    <template #header>
      <div class="gs-input-row">
        <el-icon :size="16"><Search /></el-icon>
        <input
          ref="inputEl"
          v-model="keyword"
          id="global-search-field"
          name="global_search"
          autocomplete="off"
          :aria-label="t('components.searchPlaceholder')"
          class="gs-input"
          data-test="global-search-input"
          :placeholder="t('components.searchPlaceholder')"
          @input="onInput"
        />
        <span class="gs-kbd">{{ t('components.searchKeysHint') }}</span>
      </div>
    </template>

    <div v-loading="loading" class="gs-body" data-test="global-search-body">
      <!-- P6: one pinned ask row whenever the input is not blank —
 natural language that finds nothing still has a one-click door. -->
      <div
        v-if="askVisible"
        class="gs-row gs-ask-row"
        :class="{ active: cursor === 0 }"
        data-test="global-search-ask-ai"
        @click="openAsk"
        @mouseenter="cursor = 0"
      >
        <span class="gs-row-title">{{ t('components.askAiQuery') }}</span>
        <span class="gs-row-sub">{{ keyword.trim() }}</span>
      </div>
      <template v-if="flatResults.length > 0">
        <div v-for="group in groupedResults" :key="group.label" class="gs-group">
          <div class="gs-group-title">{{ group.label }}</div>
          <div
            v-for="row in group.rows"
            :key="row.key"
            class="gs-row"
            :class="{ active: cursor === row.flatIndex + askOffset }"
            :data-test="`global-search-row-${row.category}`"
            @click="openRow(row)"
            @mouseenter="cursor = row.flatIndex + askOffset"
          >
            <span class="gs-row-title" v-html="highlight(row.title)" />
            <span v-if="row.subtitle" class="gs-row-sub">{{ row.subtitle }}</span>
          </div>
        </div>
      </template>
      <el-empty v-else-if="!loading && searched" :description="t('components.searchNoMatch')" :image-size="60" data-test="global-search-empty" />
      <div v-else-if="!loading" class="gs-hint">{{ t('components.searchIdleHint') }}</div>
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { Search } from '@element-plus/icons-vue'
import { globalSearch, type SearchItem, type SearchResults } from '@/api/search'
import { searchMenu, type MenuSearchEntry } from '@/config/menuSearch'
import { requestChatAsk } from '@/composables/useChatDock'
import { useAuthStore } from '@/stores/auth'



type SearchCategory = 'menu' | 'cases' | 'requirements' | 'defects' | 'projects'

interface FlatRow {
  key: string
  category: SearchCategory
  id: number
  projectId: number
  title: string
  subtitle: string | null
  
  path: string | null
  /** Host tab carried by a synonym hit ( R24 zero-loss: the
 * regression-suite word opens /cases?tab=suites instead of the default). */
  tab?: string
  flatIndex: number
}

const router = useRouter()
const { t } = useI18n()
const authStore = useAuthStore()
const visible = defineModel<boolean>('visible', { default: false })

const keyword = ref('')
const loading = ref(false)
const searched = ref(false)
const results = ref<SearchResults>({ cases: [], requirements: [], defects: [], projects: [] })
const cursor = ref(0)
const inputEl = ref<HTMLInputElement>()

let debounceTimer: ReturnType<typeof setTimeout> | null = null

/**
 * Palette ask entry : the pinned "Ask AI" row rides slot 0 of the
 * cursor whenever the input is non-blank; ↑↓/Enter cover it for free because
 * the cursor math below counts it as one row. Clicking it parks the typed
 * question in the shared dock singleton (no second chat surface — §seven
 * posture, same one-shot channel family as the badge and selection asks) and
 * the panel prefills the composer — nothing auto-sends.
 */
const askVisible = computed(() => keyword.value.trim() !== '')
/** Rows sit behind the ask slot: cursor index − askOffset = flat row index. */
const askOffset = computed(() => (askVisible.value ? 1 : 0))

function openAsk() {
  const question = keyword.value.trim()
  if (!question) return
  visible.value = false
  requestChatAsk(question, router.currentRoute.value.path)
}

const CATEGORY_LABELS: Record<SearchCategory, string> = {
  menu: 'components.catMenuPages',
  cases: 'components.catCases',
  requirements: 'components.catRequirements',
  defects: 'components.catDefects',
  projects: 'components.catProjects',
}


const menuHits = computed<MenuSearchEntry[]>(() =>
  searchMenu(keyword.value, (key) => t(key), (code) => authStore.hasPerm(code)),
)


function menuCrumb(entry: MenuSearchEntry): string {
  return entry.groupTitleKeys.map((k) => t(k)).join(' › ')
}

const flatResults = computed<FlatRow[]>(() => {
  const rows: FlatRow[] = []
  const pushMenu = (items: MenuSearchEntry[]) => {
    for (const it of items) {
      rows.push({
        key: `menu-${it.path}`,
        category: 'menu',
        id: 0,
        projectId: 0,
        title: t(it.titleKey),
        subtitle: menuCrumb(it),
        path: it.path,
        tab: it.tab,
        flatIndex: rows.length,
      })
    }
  }
  const push = (category: SearchCategory, items: SearchItem[]) => {
    for (const it of items) {
      rows.push({
        key: `${category}-${it.id}`,
        category,
        id: it.id,
        projectId: it.projectId,
        title: it.title ?? '',
        subtitle: it.subtitle,
        path: null,
        flatIndex: rows.length,
      })
    }
  }
  pushMenu(menuHits.value)
  push('cases', results.value.cases)
  push('requirements', results.value.requirements)
  push('defects', results.value.defects)
  push('projects', results.value.projects)
  return rows
})

const groupedResults = computed(() => {
  const order: SearchCategory[] = ['menu', 'cases', 'requirements', 'defects', 'projects']
  return order
    .map((category) => ({
      label: t(CATEGORY_LABELS[category]),
      rows: flatResults.value.filter((r) => r.category === category),
    }))
    .filter((g) => g.rows.length > 0)
})

/**
 * Default cursor band: when the result band is non-empty the highlight rides
 * the first result row so Enter after typing opens the top hit; the pinned
 * ask row keeps the default only in the zero-result posture. ↑/↓ still reach
 * every slot including ask (slot 0 is unchanged for movement math).
 */
function defaultCursor(): number {
  return flatResults.value.length > 0 ? askOffset.value : 0
}

function onInput() {
  // Menu source recomputes locally (zero network); reset the highlight to the
  // default band so stale cursor positions never drift onto wrong rows.
  cursor.value = defaultCursor()
  if (debounceTimer) clearTimeout(debounceTimer)
  debounceTimer = setTimeout(() => void doSearch(), 300)
}

async function doSearch() {
  loading.value = true
  try {
    results.value = await globalSearch(keyword.value)
    searched.value = true
    cursor.value = defaultCursor()
  } catch {
    results.value = { cases: [], requirements: [], defects: [], projects: [] }
  } finally {
    loading.value = false
  }
}

function moveCursor(step: number) {
  const n = flatResults.value.length + askOffset.value
  if (n === 0) return
  cursor.value = (cursor.value + step + n) % n
}


function highlight(title: string): string {
  const safe = title.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  const kw = keyword.value.trim()
  if (!kw) return safe
  const esc = kw.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return safe.replace(new RegExp(esc, 'gi'), (m) => `<mark class="gs-mark">${m}</mark>`)
}

function targetOf(row: FlatRow): string {
  // Menu rows keep their route path (zero path-change rule) and append the
  // target tab of a synonym hit (R24 zero-loss: a retired leaf's word must
  // land on the host tab that replaced the page, not the host default).
  if (row.category === 'menu') {
    const base = row.path ?? '/dashboard'
    return row.tab ? `${base}?tab=${encodeURIComponent(row.tab)}` : base
  }
  switch (row.category) {
    case 'cases':
      return `/cases/${row.id}`
    case 'projects':
      return `/projects/${row.id}`
    case 'requirements':
      return `/requirements?projectId=${row.projectId}&keyword=${encodeURIComponent(row.title)}`
    default:
      return `/defects?projectId=${row.projectId}&keyword=${encodeURIComponent(row.title)}`
  }
}

function openRow(row: FlatRow) {
  visible.value = false
  void router.push(targetOf(row))
}

function openCurrent() {
  // Cursor slot 0 is the pinned ask row when the input is non-blank; the rest
  // of the band shifts behind it by askOffset (same math as moveCursor).
  if (askVisible.value && cursor.value === 0) {
    openAsk()
    return
  }
  const row = flatResults.value[cursor.value - askOffset.value]
  if (row) openRow(row)
  else if (keyword.value.trim()) void doSearch()
}

function onGlobalKeydown(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && (e.key === 'k' || e.key === 'K')) {
    e.preventDefault()
    visible.value = !visible.value
  }
}

watch(visible, (open) => {
  if (open) {
    cursor.value = defaultCursor()
    void nextTick(() => inputEl.value?.focus())
  }
})

onMounted(() => window.addEventListener('keydown', onGlobalKeydown))
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onGlobalKeydown)
  if (debounceTimer) clearTimeout(debounceTimer)
})
</script>

<style scoped>
.gs-input-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.gs-input {
  flex: 1;
  border: none;
  outline: none;
  background: transparent;
  font-size: 16px;
  color: var(--el-text-color-primary);
}
.gs-kbd {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  white-space: nowrap;
}
.gs-body {
  max-height: 52vh;
  overflow-y: auto;
  min-height: 120px;
}
.gs-group-title {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  padding: 8px 8px 4px;
}
.gs-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 10px;
  border-radius: 6px;
  cursor: pointer;
}
.gs-row.active {
  background: var(--el-fill-color-hover, var(--el-fill-color-light));
}
/* Pinned ask row: one accent border marks the ask band without a second
   visual language (same row anatomy as result rows, R21 posture). */
.gs-ask-row {
  margin-bottom: 4px;
  border: 1px dashed var(--el-color-primary-light-5);
}
.gs-row-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.gs-row-sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  flex-shrink: 0;
}
:deep(.gs-mark) {
  background: var(--el-color-warning-light-5, #ffe58f);
  color: inherit;
  padding: 0 1px;
  border-radius: 2px;
}
.gs-hint {
  text-align: center;
  color: var(--el-text-color-secondary);
  padding: 40px 0;
  font-size: 13px;
}
</style>
