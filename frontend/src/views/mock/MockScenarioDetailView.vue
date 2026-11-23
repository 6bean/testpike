<template>
  <div class="page-root mock-scenario-detail-page" data-test="mock-scenario-detail-page">
    <div class="page-card">
      <div class="page-header">
        <div>
          <h2 class="page-title">
            {{ scenarioName }}
            <el-tag v-if="graph && graph.dangling.length" size="small" type="warning" data-test="mock-scenario-dangling-tag">
              {{ t('mockScenarioDetail.danglingTag', { n: graph.dangling.length }) }}
            </el-tag>
          </h2>
          <p class="page-subtitle" data-test="mock-scenario-detail-subtitle">
            {{ t('mockScenarioDetail.subtitle', { project: ctx.primaryName || '—', space: spaceName || '—' }) }}
          </p>
        </div>
        <div class="header-actions">
          <!-- deep-link discipline: the return door rides the space/project query
 so the list lands back on the same segment it left -->
          <el-button :icon="ArrowLeft" data-test="mock-scenario-detail-back" @click="backToList">
            {{ t('mockScenarioDetail.back') }}
          </el-button>
        </div>
      </div>

      <TableSkeleton v-if="loading" :cols="3" data-test="mock-scenario-detail-skeleton" />
      <div v-else-if="!spaceId" class="empty-block" data-test="mock-scenario-detail-no-space">
        <EmptyState :title="t('mockScenarioDetail.noSpace')" :hint="t('mockScenarioDetail.noSpaceHint')" />
      </div>
      <div v-else-if="!found" class="empty-block" data-test="mock-scenario-detail-missing">
        <EmptyState :title="t('mockScenarioDetail.missing')" :hint="t('mockScenarioDetail.missingHint')" />
      </div>

      <template v-else>
        <!-- the heavy asset: exactly one graph on this screen, laid out by the
             shared module (every surface renders the same picture from the same
             math — no forked copy) -->
        <div class="graph-card" data-test="mock-scenario-detail-graph-card">
          <div v-if="!graph" class="curl-error" data-test="mock-scenario-detail-unreadable">
            {{ t('mockScenarioDetail.unreadable', { reason: readReason ?? 'unknown' }) }}
          </div>
          <svg
            v-else
            class="scenario-graph"
            :width="layout.width"
            :height="layout.height"
            data-test="mock-scenario-detail-graph"
            role="img"
            :aria-label="scenarioName"
          >
            <defs>
              <marker id="tp-arrow-detail" viewBox="0 0 8 8" refX="7" refY="4" markerWidth="7" markerHeight="7" orient="auto">
                <path d="M0,0 L8,4 L0,8 z" class="arrow-fill" />
              </marker>
            </defs>
            <path
              v-for="(e, i) in layout.edges"
              :key="`e${i}`"
              :data-edge="e.dangling ? 'dangling' : 'solid'"
              :d="e.d"
              marker-end="url(#tp-arrow-detail)"
              :class="e.dangling ? 'edge-dangling' : 'edge-solid'"
            />
            <template v-for="(g, i) in layout.ghosts" :key="`g${i}`">
              <circle :cx="g.x" :cy="g.y" r="8" class="ghost-node" />
              <text :x="g.x + 12" :y="g.y + 4" class="ghost-label">{{ g.label }}</text>
            </template>
            <g
              v-for="n in layout.nodes"
              :key="n.name"
              :data-node="n.name"
              :data-initial="n.initial ? 'true' : 'false'"
              class="state-node"
            >
              <circle :cx="n.x" :cy="n.y" r="22" :class="{ 'node-initial': n.initial }" />
              <circle v-if="n.initial" :cx="n.x" :cy="n.y" r="17" class="node-inner-ring" />
              <text :x="n.x" :y="n.y + 4" class="node-label">{{ n.name }}</text>
            </g>
          </svg>
        </div>

        <el-descriptions :column="3" border class="meta-line" data-test="mock-scenario-detail-meta">
          <el-descriptions-item :label="t('mockScenarioDetail.metaLane')">{{ laneText(graph?.scope ?? '') }}</el-descriptions-item>
          <el-descriptions-item :label="t('mockScenarioDetail.metaInitial')">{{ graph?.initial ?? '—' }}</el-descriptions-item>
          <el-descriptions-item :label="t('mockScenarioDetail.metaUpdated')">{{ timeText(updatedAt) }}</el-descriptions-item>
        </el-descriptions>

        <div v-if="graph && graph.dangling.length" class="state-note" data-test="mock-scenario-detail-dangling-note">
          {{ t('mockScenarioDetail.danglingNote') }}：{{ danglingText }}
        </div>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * AG batch : the addressable detail page of one state machine — the single
 * place the graph (this surface's heavy asset) is drawn, one per screen. The
 * layout math is imported from the shared module so no two surfaces can ever
 * render the same machine differently.
 *
 * Data door honesty: the read surface has no single-row door, so the page
 * fetches the owning space's declarations and locates :id among them — the id
 * comes from the route, the space (and project, for the space name) from the
 * query the list rode in on. A machine that no longer exists under that space
 * is reported as missing, never rendered from a stale guess.
 */
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { ArrowLeft } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import { useProjectContextStore } from '@/stores/projectContext'
import { bizErrorMsg } from '@/utils/bizError'
import { laneSpectrumLabel, readScenarioGraph, type ScenarioGraph, type ScenarioReadFailure } from '@/utils/mockScenarioGraph'
import { buildLayout, EMPTY_LAYOUT, type ScenarioLayout } from '@/utils/mockScenarioGraphLayout'
import { listScenarios, listSpaces } from '@/api/mock'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const ctx = useProjectContextStore()

const scenarioId = Number(route.params.id)

function queryId(key: string): number | null {
  const v = route.query[key]
  const raw = Array.isArray(v) ? v[0] : v
  const n = Number(raw)
  return Number.isFinite(n) && n > 0 ? n : null
}

const spaceId = computed(() => queryId('spaceId'))
const projectIdQ = computed(() => queryId('projectId'))

const loading = ref(false)
const found = ref(false)
const scenarioName = ref(t('mockScenarioDetail.fallbackTitle'))
const updatedAt = ref<string | null>(null)
const spaceName = ref('')
const graph = ref<ScenarioGraph | null>(null)
const readReason = ref<ScenarioReadFailure | null>(null)
const layout = ref<ScenarioLayout>(EMPTY_LAYOUT)

/** Lane label in words; unknown spectrum values pass through raw (never blank). */
function laneText(scope: string): string {
  const key = laneSpectrumLabel(scope)
  return key ? t(key) : scope || '—'
}

function timeText(v: string | null): string {
  return v ? v.replace('T', ' ').slice(0, 19) : '—'
}

const danglingText = computed(() =>
  (graph.value?.dangling ?? []).map((e) => `${e.from}→${e.to}`).join(' '),
)

async function load(): Promise<void> {
  if (!spaceId.value || !Number.isFinite(scenarioId)) {
    found.value = false
    return
  }
  loading.value = true
  try {
    const rows = await listScenarios(spaceId.value)
    const row = rows.find((r) => r.id === scenarioId)
    if (!row) {
      found.value = false
      graph.value = null
      layout.value = EMPTY_LAYOUT
      return
    }
    found.value = true
    scenarioName.value = row.name
    updatedAt.value = row.updatedAt
    const read = readScenarioGraph(row.definitionJson)
    if (read.ok) {
      graph.value = read.graph
      readReason.value = null
      layout.value = buildLayout(read.graph)
    } else {
      graph.value = null
      readReason.value = read.reason
      layout.value = EMPTY_LAYOUT
    }
    // Human-readable space context: resolved from the project segment the deep
    // link carried; an unknown project (or no key) honestly keeps the dash.
    if (projectIdQ.value) {
      const spaces = await listSpaces(projectIdQ.value).catch(() => [])
      spaceName.value = spaces.find((s) => s.id === spaceId.value)?.name ?? ''
    }
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('mockScenarioDetail.loadFailed')))
    found.value = false
  } finally {
    loading.value = false
  }
}

/** Back to the workbench scenarios tab with the context riding (no dead ends; AN-4 merge). */
function backToList(): void {
  void router.push({
    path: `/mock/spaces/${spaceId.value}`, query: { tab: 'scenarios', projectId: String(projectIdQ.value ?? '') },
  })
}

void load()
</script>

<style scoped>
.mock-scenario-detail-page { padding: 16px 20px; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; flex-wrap: wrap; margin-bottom: 12px; }
.page-title { margin: 0; font-size: 18px; }
.page-subtitle { margin: 4px 0 0; font-size: 12px; color: var(--el-text-color-secondary); }
.header-actions { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.empty-block { padding: 24px 0; }
.muted { color: var(--el-text-color-secondary); }
.curl-error { color: var(--el-color-danger); font-size: 12px; }
.graph-card { border: 1px solid var(--el-border-color-lighter); border-radius: 8px; padding: 16px; overflow: auto; }
.scenario-graph { display: block; overflow: visible; }
.meta-line { margin-top: 12px; }
.state-note { padding: 8px 12px; border-radius: 6px; margin-top: 12px; font-size: 13px; background: var(--el-color-warning-light-9); color: var(--el-text-color-primary); }
.state-node circle { fill: var(--el-bg-color); stroke: var(--el-color-primary); stroke-width: 1.5; }
.state-node circle.node-initial { stroke-width: 2.5; }
.node-inner-ring { fill: none; stroke: var(--el-color-primary-light-7); stroke-width: 1.5; }
.node-label { font-size: 11px; fill: var(--el-text-color-primary); text-anchor: middle; }
.edge-solid { stroke: var(--el-color-primary); stroke-width: 1.5; fill: none; }
.edge-dangling { stroke: var(--el-color-warning); stroke-width: 1.5; fill: none; stroke-dasharray: 4 3; }
.ghost-node { fill: none; stroke: var(--el-color-warning); stroke-width: 1.5; stroke-dasharray: 3 2; }
.ghost-label { font-size: 11px; fill: var(--el-color-warning); }
.arrow-fill { fill: var(--el-color-primary); }
</style>
