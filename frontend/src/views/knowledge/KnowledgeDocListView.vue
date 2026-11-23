<template>
  <div class="page-root" data-test="knowledge-page">
    <div class="page-card">
      <div class="toolbar">
        <div class="toolbar-right">
          <el-button
            type="warning"
            :loading="rebuilding"
            data-test="kb-rebuild-open"
            @click="onRebuild"
          >
            <el-icon><Refresh /></el-icon>{{ t('knowledge.rebuildBtn') }}
          </el-button>
        </div>
      </div>

      <div class="filter-bar">
        <el-select
          v-model="query.sourceType"
          :placeholder="t('knowledge.sourceTypeLabel')"
          clearable
          style="width: 150px"
          popper-class="tp-kb-source-popper"
          data-test="kb-source-select" filterable
          @change="reload"
        >
          <el-option v-for="opt in SOURCE_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
        <el-select
          v-model="query.status"
          :placeholder="t('common.status')"
          clearable
          style="width: 130px"
          popper-class="tp-kb-status-popper"
          data-test="kb-status-select" filterable
          @change="reload"
        >
          <el-option v-for="opt in STATUS_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
        <el-button data-test="kb-search" @click="reload">
          <el-icon><Search /></el-icon>{{ t('knowledge.query') }}
        </el-button>
        <!-- Column show/hide, order, pinning, density and reset ride the shared
             useColumnConfig piece; the control sits in the filter-bar trailing
             slot (view-state actions never live in a header, R21). -->
        <div class="filter-bar-actions">
          <ColumnSettings
            :columns="settingsRows"
            :density="colCfg.state.density"
            @toggle="colCfg.toggle"
            @move="colCfg.move"
            @density="colCfg.setDensity"
            @reset="colCfg.reset"
            @pin="colCfg.toggleFixed"
          />
        </div>
      </div>

      <TableSkeleton v-if="loading" :cols="7" data-test="knowledgedoclistview-skeleton" />
      <!-- Main ledger table: header sort is server-side — every
 sortable column below carries a SORT_KEY_MAP key and the ORDER BY happens
 in core SQL over the whole filtered window. The chunk and hit tables inside
 the detail drawer talk to other endpoints, so they stay outside this sort
 contract ( exemption note). -->
      <DataTablePage
        v-else
        :data="rows"
        data-test="kb-table"
        row-key="id"
        :size="colCfg.state.density"
        :column-config="colCfg"
        @sort-change="onSortChange"
      >
        <el-table-column
          v-for="col in colCfg.visibleColumns.value"
          :key="col.key"
          :prop="col.key === 'snippet' ? undefined : col.key"
          :fixed="col.fixed || false"
          :label="t(col.labelKey)"
          :width="col.width ?? undefined"
          :min-width="col.width ? undefined : KB_FLEX_MIN[col.key]"
          :sortable="KB_SORTABLE.includes(col.key) ? 'custom' : false"
          :align="col.key === 'status' ? 'center' : undefined"
          :data-test="`kb-col-${col.key}`"
        >
          <template #default="{ row }">
            <el-button
              v-if="col.key === 'title'"
              link
              type="primary"
              :data-test="`kb-detail-open-${row.id}`"
              @click="openDetail(row.id)"
            >
              {{ row.title }}
            </el-button>
            <el-tag v-else-if="col.key === 'sourceType'" size="small" :type="row.sourceType === 'TEST_CASE' ? 'success' : 'info'" data-test="kb-source-tag">
              {{ sourceLabel(row.sourceType) }}
            </el-tag>
            <span v-else-if="col.key === 'projectId'">{{ row.projectId === 0 ? t('knowledge.global') : row.projectId }}</span>
            <span v-else-if="col.key === 'snippet'" class="kb-content" data-test="kb-content">{{ snippet(row.content) }}</span>
            <el-tag v-else-if="col.key === 'status'" size="small" :type="row.status === 'ACTIVE' ? 'success' : 'danger'" data-test="kb-status-tag">
              {{ statusLabel(row.status) }}
            </el-tag>
            <span v-else>{{ row[col.key] }}</span>
          </template>
        </el-table-column>

        <template #empty>
          <EmptyState :title="t('knowledge.emptyTitle')" />
        </template>
      </DataTablePage>

      <div class="pagination-bar">
        <el-pagination
          v-if="total > 0"
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          data-test="kb-pager"
          @current-change="load"
          @size-change="load(1)"
        />
      </div>
    </div>

    
    <el-drawer
      v-model="detailVisible"
      :title="t('knowledge.detailTitle', { title: detail?.title ?? '' })"
      size="680px"
      destroy-on-close
      data-test="kb-detail-drawer"
    >
      <div v-loading="detailLoading">
        <template v-if="detail">
          <el-descriptions :column="2" border size="small" data-test="kb-detail-meta">
            <el-descriptions-item :label="t('knowledge.metaLedgerId')">{{ detail.id }}</el-descriptions-item>
            <el-descriptions-item :label="t('knowledge.colProject')">{{ detail.projectId === 0 ? t('knowledge.global') : detail.projectId }}</el-descriptions-item>
            <el-descriptions-item :label="t('knowledge.sourceTypeLabel')">{{ sourceLabel(detail.sourceType) }}</el-descriptions-item>
            <el-descriptions-item :label="t('knowledge.metaPackId')">{{ detail.packId === 0 ? '—' : detail.packId }}</el-descriptions-item>
            <el-descriptions-item :label="t('common.status')">
              <el-tag size="small" :type="detail.status === 'ACTIVE' ? 'success' : 'danger'">{{ statusLabel(detail.status) }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item :label="t('knowledge.metaHitCount')">{{ detail.hitCount }} {{ t('knowledge.unitTimes') }}</el-descriptions-item>
            <el-descriptions-item :label="t('common.createdAt')">{{ detail.createdAt }}</el-descriptions-item>
            <el-descriptions-item :label="t('common.updatedAt')">{{ detail.updatedAt }}</el-descriptions-item>
            <el-descriptions-item :label="t('knowledge.colSource')" :span="2">
              <el-button
                v-if="jumpTarget(detail)"
                link
                type="primary"
                data-test="kb-detail-source-jump"
                @click="jumpToSource(detail)"
              >
                {{ jumpTarget(detail) === 'case' ? t('knowledge.jumpCase') : t('knowledge.jumpSourceObj') }}（ID {{ detail.sourceRef }}）
              </el-button>
              <span v-else class="text-secondary" data-test="kb-detail-source-noflash">
                {{ detail.sourceRef && detail.sourceRef !== 0 ? t('knowledge.noJumpWithRef', { ref: detail.sourceRef }) : t('knowledge.noJumpAuto') }}
              </span>
            </el-descriptions-item>
          </el-descriptions>

          <h4 class="kb-detail-section">{{ t('knowledge.fullTextSection') }}</h4>
          <pre class="kb-detail-content" data-test="kb-detail-content">{{ detail.content }}</pre>

          <h4 class="kb-detail-section">{{ t('knowledge.chunkSection', { count: detail.chunks.length }) }}</h4>
          <el-collapse v-if="detail.chunks.length" v-model="activeChunks" data-test="kb-detail-chunks">
            <el-collapse-item
              v-for="chunk in detail.chunks"
              :key="chunk.chunkId"
              :name="chunk.chunkId"
              :data-test="`kb-chunk-reveal-${chunk.chunkId}`"
            >
              <template #title>
                <span class="kb-chunk-head" :data-test="`kb-chunk-head-${chunk.chunkIndex}`">
                  {{ t('knowledge.chunkHead') }}{{ chunk.chunkIndex }}
                  <span class="kb-chunk-lang">{{ metaText(chunk, 'lang') }}</span>
                </span>
              </template>
              <pre class="kb-chunk-body" :data-test="`kb-chunk-body-${chunk.chunkIndex}`">{{ chunk.content }}</pre>
              <el-descriptions :column="2" size="small" border class="kb-chunk-meta" :data-test="`kb-chunk-meta-${chunk.chunkIndex}`">
                <el-descriptions-item v-for="key in CHUNK_META_KEYS" :key="key" :label="key">
                  {{ metaText(chunk, key) || '—' }}
                </el-descriptions-item>
              </el-descriptions>
            </el-collapse-item>
          </el-collapse>
          <EmptyState v-else :title="t('knowledge.chunksEmpty')" />

          <h4 class="kb-detail-section">{{ t('knowledge.hitsSection', { count: hits.length }) }}</h4>
          <el-table :data="hits" size="small" border data-test="kb-hits-table">
            <el-table-column :label="t('knowledge.colTask')" min-width="180">
              <template #default="{ row }">
                <span>{{ row.taskTitle || t('knowledge.taskCleared') }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="scene" :label="t('knowledge.colScene')" width="150" />
            <el-table-column :label="t('knowledge.colHitChunks')" width="110">
              <template #default="{ row }">{{ row.chunkIds.length }} {{ t('knowledge.unitChunk') }}</template>
            </el-table-column>
            <el-table-column prop="createdAt" :label="t('knowledge.colTime')" width="170" />
            <template #empty>
              <EmptyState :title="t('knowledge.hitsEmpty')" />
            </template>
          </el-table>
        </template>
      </div>
    </el-drawer>

    
    <el-dialog
      draggable
      v-model="resultVisible"
      :title="t('knowledge.rebuildResultTitle')"
      width="560px"
      destroy-on-close
      data-test="kb-rebuild-result-dialog"
    >
      <el-descriptions v-if="result" :column="1" border>
        <el-descriptions-item :label="t('knowledge.rebuildRepaired')">{{ result.repairedDocIds.length }}</el-descriptions-item>
        <el-descriptions-item :label="t('knowledge.rebuildOrphan')">{{ result.orphanDeletedDocIds.length }}</el-descriptions-item>
        <el-descriptions-item :label="t('knowledge.rebuildLedgerRows')">{{ result.ledgerActiveRows }}</el-descriptions-item>
        <el-descriptions-item :label="t('knowledge.rebuildVectorRows')">{{ result.vectorRowsAfter }}</el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button data-test="kb-rebuild-close" @click="resultVisible = false">{{ t('common.close') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">

import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import { flashRevealElement } from '@/composables/sourceAnchorJump'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import ColumnSettings from '@/components/ColumnSettings.vue'
import DataTablePage from '@/components/DataTablePage.vue'
import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'
import {
  KNOWLEDGE_SOURCE_KEYS,
  KNOWLEDGE_STATUS_KEYS,
  getKnowledgeDoc,
  listKnowledgeDocHits,
  listKnowledgeDocs,
  rebuildKnowledge,
  type KnowledgeChunkItemDetail,
  type KnowledgeDocDetail,
  type KnowledgeDocItem,
  type KnowledgeHitItem,
  type KnowledgeRebuildResult,
} from '@/api/knowledge'

const { t } = useI18n()
const router = useRouter()
const route = useRoute()
// R30: project filter follows the header global context (cleared header =
// all-projects view). In-page picker retired.
const { projectId: kbProjectId } = useProjectPageContext()
watch(kbProjectId, () => void reload())

const loading = ref(false)
const rows = ref<KnowledgeDocItem[]>([])
const total = ref(0)
const query = reactive<{
  sourceType: string
  status: string
  page: number
  size: number
  sortBy: string
  sortDir: string
}>({
  sourceType: '',
  status: '',
  page: 1,
  size: 20,
  sortBy: '',
  sortDir: '',
})

const rebuilding = ref(false)
const resultVisible = ref(false)
const result = ref<KnowledgeRebuildResult | null>(null)

// Column prop -> core knowledge SORT_COLUMNS whitelist key (single source for
// the sortable decision and the request payload; . Sorting
// happens server-side over the whole filtered window, not the loaded pageful.
// The snippet column is an 80-char view over long text — honestly unsorted.
const SORT_KEY_MAP: Record<string, string> = {
  id: 'id',
  title: 'title',
  sourceType: 'source_type',
  projectId: 'project_id',
  status: 'status',
  updatedAt: 'updated_at',
}

/** Header sort: forward the whitelisted key to core; clearing a sort falls
 * back to the historical id DESC default order; page resets to 1. */
function onSortChange({ prop, order }: { prop: string; order: string | null }): void {
  query.page = 1
  if (order === null) {
    query.sortBy = ''
    query.sortDir = ''
  } else {
    const key = SORT_KEY_MAP[prop]
    if (key) {
      query.sortBy = key
      query.sortDir = order === 'ascending' ? 'asc' : 'desc'
    }
  }
  void load()
}

const SOURCE_TYPE_VALUES = Object.keys(KNOWLEDGE_SOURCE_KEYS)
const STATUS_VALUES = Object.keys(KNOWLEDGE_STATUS_KEYS)
/** Column defs of the knowledge ledger table (locked selection/row-action
 *  columns stay out of the user-configurable set; N-3 repayment). */
const KB_COLUMNS: ColumnDef[] = [
  { key: 'id', labelKey: 'knowledge.colId', width: 70 },
  { key: 'title', labelKey: 'knowledge.colTitle', width: null },
  { key: 'sourceType', labelKey: 'knowledge.colSource', width: 120 },
  { key: 'projectId', labelKey: 'knowledge.colProject', width: 120 },
  { key: 'snippet', labelKey: 'knowledge.colSnippet', width: null },
  { key: 'status', labelKey: 'common.status', width: 100 },
  { key: 'updatedAt', labelKey: 'common.updatedAt', width: 170 },
]
/** Flexible columns fall back to min-width when no explicit width is set. */
const KB_FLEX_MIN: Record<string, number> = { title: 220, snippet: 240 }
/** Header-sortable columns: same set as the server-side SORT_KEY_MAP whitelist. */
const KB_SORTABLE = ['id', 'title', 'sourceType', 'projectId', 'status', 'updatedAt']
const colCfg = useColumnConfig('knowledge', KB_COLUMNS)
/** Popover rows (column state merged with labelKey for rendering). */
const settingsRows = computed(() =>
  colCfg.state.columns.map((c) => ({ ...c, labelKey: KB_COLUMNS.find((d) => d.key === c.key)!.labelKey })),
)

const SOURCE_OPTIONS = computed(() => SOURCE_TYPE_VALUES.map((value) => ({ value, label: t(KNOWLEDGE_SOURCE_KEYS[value]) })))
const STATUS_OPTIONS = computed(() => STATUS_VALUES.map((value) => ({ value, label: t(KNOWLEDGE_STATUS_KEYS[value]) })))

function sourceLabel(v: string): string {
  const key = KNOWLEDGE_SOURCE_KEYS[v]
  return key ? t(key) : v
}


function statusLabel(v: string): string {
  const key = KNOWLEDGE_STATUS_KEYS[v]
  return key ? t(key) : v
}



const CHUNK_META_KEYS = [
  'doc_id', 'case_id', 'source_type', 'project_id', 'title',
  'chunk_index', 'lang', 'status', 'pack_id', 'excellent',
] as const

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<KnowledgeDocDetail | null>(null)
const hits = ref<KnowledgeHitItem[]>([])
// Paragraph-anchor state (behaviour-kernel round-2 item 3): the collapse keeps
// its own open set so a deep link can expand exactly the anchored chunk; the
// pending pointer survives until the detail rows have actually landed.
const activeChunks = ref<string[]>([])
const pendingChunkId = ref<string | null>(null)


async function openDetail(id: number): Promise<void> {
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null
  hits.value = []
  try {
    const [doc, hitRows] = await Promise.all([getKnowledgeDoc(id), listKnowledgeDocHits(id)])
    detail.value = doc
    hits.value = hitRows
  } catch (e) {
    ElMessage.error((e as Error).message || t('knowledge.detailLoadFailed'))
  } finally {
    detailLoading.value = false
  }
}


function jumpTarget(d: KnowledgeDocDetail): 'case' | null {
  return d.sourceType === 'TEST_CASE' && d.sourceRef !== null && d.sourceRef > 0 ? 'case' : null
}

function jumpToSource(d: KnowledgeDocDetail): void {
  if (jumpTarget(d) === 'case') router.push(`/cases/${d.sourceRef}`)
}


function metaText(chunk: KnowledgeChunkItemDetail, key: string): string {
  const v = chunk.metadata?.[key]
  if (v === undefined || v === null) return ''
  if (typeof v === 'boolean') return v ? t('knowledge.yes') : t('knowledge.no')
  return String(v)
}


function snippet(content: string): string {
  const flat = content.replace(/\s+/g, ' ')
  return flat.length > 80 ? `${flat.slice(0, 80)}…` : flat
}

async function load(toPage?: number): Promise<void> {
  if (toPage) query.page = toPage
  loading.value = true
  try {
    const pageData = await listKnowledgeDocs({
      projectId: kbProjectId.value,
      sourceType: query.sourceType,
      status: query.status,
      page: query.page,
      size: query.size,
      sortBy: query.sortBy || undefined,
      sortDir: query.sortDir || undefined,
    })
    rows.value = pageData.list || []
    total.value = Number(pageData.total) || 0
  } catch (e) {
    ElMessage.error((e as Error).message || t('knowledge.listLoadFailed'))
  } finally {
    loading.value = false
  }
}

function reload(): void {
  query.page = 1
  load()
}


async function onRebuild(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      t('knowledge.rebuildConfirmText'),
      t('knowledge.rebuildConfirmTitle'),
      { type: 'warning', confirmButtonText: t('knowledge.rebuildBtn'), cancelButtonText: t('common.cancel') },
    )
  } catch {
    return
  }
  rebuilding.value = true
  try {
    result.value = await rebuildKnowledge()
    resultVisible.value = true
    load()
  } catch (e) {
    ElMessage.error((e as Error).message || t('knowledge.rebuildFailed'))
  } finally {
    rebuilding.value = false
  }
}

onMounted(() => {
  void load()
  // cite-card deep link: ?docId opens that document's drawer on arrival
  // (the ledger door re-validates ownership; an unknown id lands on the
  // drawer's own honest error line). Page state rides the URL (R22), so the
  // drawer closing clears the pointer and a refresh never re-opens stale state.
  const deep = Number(route.query.docId)
  if (Number.isFinite(deep) && deep > 0) {
    const pointer = route.query.chunkId
    pendingChunkId.value = typeof pointer === 'string' && pointer !== '' ? pointer : null
    void openDetail(deep).then(revealPendingChunk)
  }
})

/**
 * Expand + center + flash the anchored chunk once the drawer carries it. A
 * chunkId that is not a row of this document is dropped honestly (the drawer
 * already shows the document; inventing a highlight on a wrong row would be
 * worse than none).
 */
function revealPendingChunk(): void {
  const chunkId = pendingChunkId.value
  pendingChunkId.value = null
  if (!chunkId || !detail.value) return
  if (!detail.value.chunks.some(c => String(c.chunkId) === chunkId)) return
  activeChunks.value = [chunkId]
  void nextTick(() => flashRevealElement(
    document.querySelector(`[data-test="kb-chunk-reveal-${chunkId}"]`), 'kb-chunk-flash'))
}

watch(detailVisible, (visible) => {
  if (!visible && (route.query.docId !== undefined || route.query.chunkId !== undefined)) {
    const rest = { ...route.query }
    delete rest.docId
    delete rest.chunkId
    void router.replace({ path: route.path, query: rest })
  }
})
</script>

<style scoped>
/* Paragraph-anchor reveal flash (timing owned by sourceAnchorJump.flashRevealElement,
   the page only paints the flash so all anchor families stay visually alike). */
.kb-chunk-flash {
  animation: kb-chunk-flash 1.6s ease;
}
@keyframes kb-chunk-flash {
  0%, 55% {
    background: var(--el-color-primary-light-8, rgba(64, 158, 255, 0.22));
  }
  100% {
    background: transparent;
  }
}
.kb-title {
  font-weight: 600;
}
.kb-content {
  color: var(--tp-text-secondary);
  font-size: 12px;
}
.kb-detail-section {
  margin: 18px 0 8px;
  font-size: 14px;
}
.kb-detail-content {
  margin: 0;
  padding: 10px 12px;
  background: var(--tp-bg-elevated, rgba(125, 125, 125, 0.08));
  border: 1px solid var(--tp-border-color, var(--el-border-color));
  border-radius: 6px;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 13px;
  line-height: 1.7;
  max-height: 300px;
  overflow: auto;
}
.kb-chunk-head {
  font-weight: 600;
}
.kb-chunk-lang {
  margin-left: 8px;
  font-size: 12px;
  color: var(--tp-text-secondary);
}
.kb-chunk-body {
  margin: 0 0 10px;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 12px;
  line-height: 1.6;
  max-height: 260px;
  overflow: auto;
}
.kb-chunk-meta {
  margin-bottom: 6px;
}
</style>
