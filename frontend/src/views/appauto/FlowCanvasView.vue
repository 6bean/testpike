<!-- UI Flow canvas view (W5 app-automation phase two, design segment B3): read-only
 projection of a case's step tree onto the shared mermaid flowchart. The
 clicked-node to step mapping consumes ONLY the server-emitted nodes pairs
 (core assembler owns the traversal — R40 no second derivation); jumps and
 captures hand over to the surfaces that already own those actions. -->
<template>
  <div class="page-root flow-canvas-page" data-test="flow-canvas-page">
    <div class="page-card">
      <!-- R21 bands: one condition control (case picker) so page actions merge into
           the filter-bar trailing slot (FB-3 same-band merge); the query button stays
           inline at the end of the condition flow (R21 second amendment). No
           page-level create/import here — this surface is a read-only projection. -->
      <div class="filter-bar" data-test="flow-canvas-filter-bar">
        <el-select
          v-model="caseId"
          class="cond-case"
          filterable
          remote
          clearable
          reserve-keyword
          :remote-method="searchCases"
          :loading="casesLoading"
          :placeholder="t('appauto.flowCanvas.casePlaceholder')"
          data-test="flow-canvas-case-select"
          @change="onCaseChange"
        >
          <el-option
            v-for="c in caseOptions"
            :key="c.id"
            :label="`${c.caseNo} ${c.title}`"
            :value="c.id"
          />
        </el-select>
        <el-button
          type="primary"
          :icon="Search"
          :loading="loading"
          data-test="flow-canvas-query-btn"
          @click="loadFlowchart"
        >
          {{ t('appauto.flowCanvas.query') }}
        </el-button>
        <div class="filter-bar-actions">
          <el-button :icon="Refresh" data-test="flow-canvas-refresh-btn" @click="loadFlowchart">
            {{ t('appauto.flowCanvas.refresh') }}
          </el-button>
        </div>
      </div>

      <div v-if="loading" class="canvas-skeleton" data-test="flow-canvas-loading">
        <el-skeleton :rows="6" animated />
      </div>

      <div v-else-if="errorText" class="empty-box" data-test="flow-canvas-error">
        <el-result icon="error" :sub-title="errorText" />
        <div>{{ t('appauto.flowCanvas.loadFailed') }}</div>
      </div>

      <div v-else-if="!caseId" class="empty-box" data-test="flow-canvas-empty">
        <EmptyState
          :title="t('appauto.flowCanvas.emptyTitle')"
          :description="t('appauto.flowCanvas.emptyHint')"
          :hint="t('appauto.flowCanvas.emptyAction')"
        />
      </div>

      <div v-else class="canvas-body">
        <div class="canvas-stage">
          <div class="canvas-zoom" data-test="flow-canvas-zoom">
            <el-button-group>
              <el-button size="small" :icon="ZoomIn" :title="t('appauto.flowCanvas.zoomIn')" @click="zoom(0.2)" />
              <el-button size="small" :icon="ZoomOut" :title="t('appauto.flowCanvas.zoomOut')" @click="zoom(-0.2)" />
              <el-button size="small" :title="t('appauto.flowCanvas.zoomReset')" @click="resetZoom">
                {{ Math.round(scale * 100) }}%
              </el-button>
            </el-button-group>
          </div>
          <!-- Mermaid SVG target (v-html injection like the review-flow chart; on
               render failure the raw source travels back, never a blank screen). -->
          <div
            v-show="svgText"
            class="flow-canvas-scroll"
            data-test="flow-canvas-svg"
            @click="onCanvasClick"
          >
            <div class="flow-canvas-scaled" :style="{ transform: `scale(${scale})` }" v-html="svgText" />
          </div>
          <pre v-if="!svgText && flowchart" class="script-json" data-test="flow-canvas-source">{{ flowchart.mermaid }}</pre>
        </div>

        <aside class="step-panel">
          <div v-if="selectedStep != null" class="step-card" data-test="flow-canvas-step-card">
            <h3 class="step-title">{{ t('appauto.flowCanvas.stepLabel', { n: selectedStep }) }}</h3>
            <div class="step-actions">
              <el-button type="primary" data-test="flow-canvas-jump-btn" @click="jumpToCaseSteps">
                {{ t('appauto.flowCanvas.jump') }}
              </el-button>
              <el-tooltip :content="t('appauto.flowCanvas.captureHint')" placement="bottom">
                <el-button data-test="flow-canvas-capture-btn" @click="goCapture">
                  {{ t('appauto.flowCanvas.capture') }}
                </el-button>
              </el-tooltip>
            </div>
          </div>
          <div v-else class="step-card step-card-idle" data-test="flow-canvas-step-card">
            <div class="step-hint">{{ t('appauto.flowCanvas.emptyHint') }}</div>
          </div>
          <div class="step-summary" data-test="flow-canvas-step-summary">
            {{ t('caseDetail.countStep', { n: flowchart?.stepCount ?? 0 }) }}
          </div>
        </aside>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
// Page state rides the URL (?caseId=) so refresh/back/share restore the same
// view (R22 F group). The project segment rides the global header context (R30):
// deep links win, otherwise the header primary project is consumed; switching
// the header drops the picked case because options are project-scoped.
import { onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import { useAppStore } from '@/stores/app'
import { ElMessage } from 'element-plus'
import { Refresh, Search, ZoomIn, ZoomOut } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import { bizErrorMsg } from '@/utils/bizError'
import { renderMermaidSvg } from '@/utils/mermaidRender'
import {
  clickedNodeIdFromElementId,
  nodeIdForStepIndex,
  stepIndexForNode,
} from '@/utils/flowCanvasProjection'
import { getCaseFlowchart, listCases, type CaseFlowchart, type CaseItem } from '@/api/case'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const appStore = useAppStore()
const { projectId, followTick } = useProjectPageContext()

const caseId = ref<number | null>(null)
const caseOptions = ref<CaseItem[]>([])
const casesLoading = ref(false)
const loading = ref(false)
const errorText = ref('')
const flowchart = ref<CaseFlowchart | null>(null)
const svgText = ref('')
const selectedStep = ref<number | null>(null)
const scale = ref(1)
let renderGen = 0

// Worst-case scale: the picker serves the first 50 matches of the remote search
// and narrows by keyword (SE ladder), the chart surface scrolls + zooms.
async function searchCases(keyword: string) {
  if (!projectId.value) return
  casesLoading.value = true
  try {
    const page = await listCases({ projectId: projectId.value, keyword: keyword || undefined, page: 1, size: 50 })
    caseOptions.value = page.list ?? []
  } catch (e) {
    caseOptions.value = []
    ElMessage.warning(bizErrorMsg(e, t('appauto.flowCanvas.loadFailed')))
  } finally {
    casesLoading.value = false
  }
}

async function loadFlowchart() {
  if (!caseId.value) return
  const gen = ++renderGen
  loading.value = true
  errorText.value = ''
  selectedStep.value = null
  svgText.value = ''
  try {
    const fc = await getCaseFlowchart(caseId.value)
    if (gen !== renderGen) return
    flowchart.value = fc
    try {
      const svg = await renderMermaidSvg(
        `flow-canvas-${gen}`,
        fc.mermaid,
        appStore.theme === 'dark' ? 'dark' : 'default',
      )
      if (gen !== renderGen) return
      svgText.value = svg
    } catch {
      // Render failure keeps the mermaid source visible (pre block), never blank.
      if (gen === renderGen) svgText.value = ''
    }
  } catch (e) {
    errorText.value = bizErrorMsg(e, t('appauto.flowCanvas.loadFailed'))
  } finally {
    if (gen === renderGen) loading.value = false
  }
}

function onCaseChange() {
  router.replace({ query: { ...route.query, caseId: caseId.value ? String(caseId.value) : undefined } })
  loadFlowchart()
}

function onCanvasClick(event: MouseEvent) {
  if (!flowchart.value) return
  // Mermaid 11 node groups carry ids like `<renderId>-flowchart-<nodeId>-<n>`:
  // the walk anchors the node group itself, the parser trims prefix/counter.
  const target = (event.target as Element | null)?.closest?.('g.node')
  const elId = target?.getAttribute('id') ?? null
  const nodeId = clickedNodeIdFromElementId(elId)
  const step = stepIndexForNode(flowchart.value.nodes, nodeId)
  if (step == null) {
    selectedStep.value = null
    return
  }
  selectedStep.value = step
  highlightNode(nodeIdForStepIndex(flowchart.value.nodes, step))
}

// Selection feedback rides the chart itself: the matched node group gets a class;
// misses (helper nodes never emitted upstream) simply clear the card.
function highlightNode(nodeId: string | null) {
  document.querySelectorAll('.flow-canvas-scaled [data-canvas-active]').forEach((el) => {
    el.removeAttribute('data-canvas-active')
  })
  if (!nodeId) return
  // Match through the same parser the click path uses: rendered ids embed the
  // counter, so exact-id lookup would miss on mermaid 11 output.
  const hit = Array.from(document.querySelectorAll('.flow-canvas-scaled g.node'))
    .find((el) => clickedNodeIdFromElementId(el.getAttribute('id')) === nodeId)
  hit?.setAttribute('data-canvas-active', '1')
}

function zoom(delta: number) {
  scale.value = Math.min(2.5, Math.max(0.5, Math.round((scale.value + delta) * 10) / 10))
}

function resetZoom() {
  scale.value = 1
}

function jumpToCaseSteps() {
  if (caseId.value) router.push(`/cases/${caseId.value}`)
}

// Template image capture is owned by the element library surface (live device
// frame region selection, P1 delivery) — the canvas hands over, never re-implements.
function goCapture() {
  router.push('/appauto/elements')
}

onMounted(() => {
  const q = Number(route.query.caseId)
  if (Number.isFinite(q) && q > 0) caseId.value = q
  void searchCases('')
  loadFlowchart()
})

// Header project switch invalidates the picked case (options are project-scoped).
watch(projectId, () => {
  caseId.value = null
  flowchart.value = null
  svgText.value = ''
  selectedStep.value = null
  void searchCases('')
})
watch(followTick, () => void searchCases(''))
watch(() => appStore.theme, () => {
  if (caseId.value) loadFlowchart()
})
</script>

<style scoped>
.flow-canvas-page .filter-bar {
  display: flex;
  align-items: center;
  gap: var(--band-gap, 12px);
}
.cond-case {
  width: 320px;
}
.canvas-skeleton,
.empty-box {
  padding: 24px;
}
.canvas-body {
  display: flex;
  gap: 16px;
  align-items: stretch;
  min-height: 420px;
}
.canvas-stage {
  position: relative;
  flex: 1;
  min-width: 0;
}
.canvas-zoom {
  position: absolute;
  right: 12px;
  top: 12px;
  z-index: 2;
}
.flow-canvas-scroll {
  overflow: auto;
  max-height: 70vh;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 12px;
  cursor: pointer;
}
.flow-canvas-scaled {
  /* Centre the diagram column in the stage: the mermaid flowchart is a tall
 narrow strip; glued to the left edge it leaves a dead band before the
 step panel (vision scoring , dim-3 ratio comfort). */
  width: fit-content;
  margin-inline: auto;
  transform-origin: top left;
}
.flow-canvas-scaled :deep([data-canvas-active]) {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 2px;
  border-radius: 4px;
}
.step-panel {
  width: 300px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.step-card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 16px;
}
.step-title {
  margin: 0 0 12px;
  font-size: 15px;
}
.step-actions {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.step-hint {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.step-summary {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  margin-top: auto;
}
</style>
