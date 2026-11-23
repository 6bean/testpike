<script setup lang="ts">

import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { listStepEvidence, parseStepsTimeline, type ExecStepEvidence, type ExecTask } from '@/api/executor'
import {
  buildFailedStepEvidence,
  normalizeEvidenceRows,
  parsePerfSampleRows,
  triptychHasAny,
  type EvidencePane,
  type FailedStepEvidence,
  type PerfSampleRow,
} from '@/utils/stepEvidence'
import PerfTimelinePane from '@/components/PerfTimelinePane.vue'
import { flashRevealElement } from '@/composables/sourceAnchorJump'

const props = defineProps<{ task: ExecTask | null }>()
const { t } = useI18n()

const SLOT_LABEL_KEY: Record<EvidencePane['slot'], string> = {
  screenshot: 'exec.triptychShot',
  viewTree: 'exec.triptychTree',
  logTail: 'exec.triptychLog',
  record: 'exec.triptychRecord',
  netTimeline: 'exec.triptychNet',
  perfTimeline: 'exec.triptychPerf',
}

const evidenceRows = ref<ExecStepEvidence[]>([])
const loading = ref(false)


const entries = computed<FailedStepEvidence[]>(() =>
  buildFailedStepEvidence(parseStepsTimeline(props.task), evidenceRows.value))
const visible = computed(() => evidenceRows.value.length > 0 && entries.value.length > 0)

function paneOf(entry: FailedStepEvidence, slot: EvidencePane['slot']): EvidencePane {
  return entry.panes.find((p) => p.slot === slot) ?? entry.panes[0]
}

const paneText = reactive<Record<string, { state: 'idle' | 'loading' | 'error'; text: string }>>({})
const expanded = reactive<Record<string, boolean>>({})

function togglePane(url: string): void {
  const next = !expanded[url]
  expanded[url] = next
  if (next && !paneText[url]) void fetchPaneText(url)
}

async function fetchPaneText(url: string): Promise<void> {
  paneText[url] = { state: 'loading', text: '' }
  try {
    const resp = await fetch(url)
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`)
    paneText[url] = { state: 'idle', text: await resp.text() }
  } catch {
    paneText[url] = { state: 'error', text: t('exec.triptychLoadFail') }
  }
}

const perfCharts = reactive<Record<string, PerfSampleRow[]>>({})

async function togglePerfChart(url: string): Promise<void> {
  if (url in perfCharts) {
    delete perfCharts[url]
    return
  }
  try {
    const resp = await fetch(url)
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`)
    perfCharts[url] = parsePerfSampleRows(await resp.text())
  } catch {
    perfCharts[url] = []
  }
}

async function loadEvidence(taskId: number): Promise<void> {
  loading.value = true
  try {
    evidenceRows.value = normalizeEvidenceRows(await listStepEvidence(taskId))
  } catch {
    evidenceRows.value = []
  } finally {
    loading.value = false
  }
}

const failedStepCount = computed(() =>
  parseStepsTimeline(props.task).filter((s) => typeof s.status === 'string' && s.status !== 'ok').length)

watch(
  () => props.task?.id,
  (id) => {
    evidenceRows.value = []
    for (const k of Object.keys(paneText)) delete paneText[k]
    for (const k of Object.keys(expanded)) delete expanded[k]
    for (const k of Object.keys(perfCharts)) delete perfCharts[k]
    if (id != null && failedStepCount.value > 0) void loadEvidence(id)
  },
  { immediate: true },
)

/** Host-facing execution anchor: reveal one failed step's evidence block
 *  (center + flash through the shared door). Returns false when the panel
 *  holds no block for that step — the host then answers honestly. */
function revealStep(stepNo: number): boolean {
  const el = document.querySelector(`[data-test="triptych-step-${stepNo}"]`)
  flashRevealElement(el, 'triptych-step-flash')
  return el !== null
}

defineExpose({ revealStep })
</script>

<template>
  <div v-if="visible" class="triptych-block" data-test="exec-triptych">
    <h4 class="block-title">{{ t('exec.triptychTitle') }}</h4>
    <div
      v-for="e in entries"
      :key="`triptych-${e.stepNo}`"
      class="triptych-step"
      :data-test="`triptych-step-${e.stepNo}`"
    >
      <h5 class="section-title">
        {{ t('exec.triptychStep', { stepNo: e.stepNo, action: e.action || '-' }) }}
      </h5>
      <div v-if="!triptychHasAny(e)" class="triptych-none" data-test="triptych-none">
        {{ t('exec.triptychNone') }}
      </div>
      <div v-else class="triptych-panes">
        <div
          v-for="p in e.panes"
          :key="p.slot"
          class="triptych-pane"
          :class="{ 'pane-absent': !p.present }"
          :data-test="`triptych-pane-${p.slot}`"
        >
          <div class="pane-label">
            <span>{{ t(SLOT_LABEL_KEY[p.slot]) }}</span>
            <span v-if="!p.present" class="pane-absent-note">{{ t('exec.triptychAbsent', { name: t(SLOT_LABEL_KEY[p.slot]) }) }}</span>
          </div>
          <template v-if="p.present">
            <el-image
              v-if="p.slot === 'screenshot'"
              :src="p.url!"
              :preview-src-list="[p.url!]"
              fit="contain"
              class="pane-shot-img"
              :alt="t('exec.triptychShot')"
            />
            <video
              v-else-if="p.slot === 'record'"
              :src="p.url!"
              controls
              preload="none"
              class="pane-record-video"
              data-test="triptych-record-video"
            />
            <template v-else-if="p.slot === 'perfTimeline'">
              <el-button link type="primary" size="small" data-test="triptych-perf-toggle" @click="togglePerfChart(p.url!)">
                {{ p.url! in perfCharts ? t('exec.triptychCollapse') : t('exec.perfViewCurve') }}
              </el-button>
              <PerfTimelinePane
                v-if="p.url! in perfCharts"
                :samples="perfCharts[p.url!]"
              />
            </template>
            <template v-else>
              <el-button link type="primary" size="small" :data-test="`triptych-text-toggle-${p.slot}`" @click="togglePane(p.url!)">
                {{ expanded[p.url!] ? t('exec.triptychCollapse') : t('exec.triptychViewText') }}
              </el-button>
              <div v-if="expanded[p.url!]" class="pane-text-wrap">
                <div v-if="paneText[p.url!]?.state === 'loading'" class="pane-text-loading">{{ t('exec.triptychLoading') }}</div>
                <div v-else-if="paneText[p.url!]?.state === 'error'" class="pane-text-error" data-test="triptych-text-error">
                  {{ paneText[p.url!]?.text }}
                </div>
                <pre v-else class="pane-text mono" data-test="triptych-pane-text">{{ paneText[p.url!]?.text }}</pre>
              </div>
            </template>
          </template>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.triptych-block {
  margin-top: 12px;

  .block-title {
    margin: 0 0 8px;
    font-size: 13px;
  }

  .triptych-step {
    margin-bottom: 12px;
    border-radius: 6px;

    &.triptych-step-flash {
      box-shadow: 0 0 0 2px var(--tp-primary, #409eff);
    }

    .section-title {
      margin: 0 0 6px;
      font-size: 12px;
      color: var(--tp-text-secondary);
    }
  }

  .triptych-none {
    color: var(--tp-text-secondary);
    font-size: 12px;
  }

  .triptych-panes {
    display: flex;
    gap: 12px;
    flex-wrap: wrap;
    align-items: flex-start;
  }

  .triptych-pane {
    flex: 1 1 220px;
    min-width: 220px;

    &.pane-absent {
      opacity: 0.55;
    }

    .pane-label {
      font-size: 12px;
      color: var(--tp-text-secondary);
      margin-bottom: 4px;
      display: flex;
      gap: 6px;
      align-items: center;

      .pane-absent-note {
        color: var(--tp-warning);
      }
    }

    .pane-shot-img {
      width: 140px;
      height: 240px;
      border: 1px solid var(--tp-border);
      border-radius: 6px;
      background: var(--tp-bg-elevated);
    }

    .pane-record-video {
      width: 220px;
      max-width: 100%;
      border: 1px solid var(--tp-border);
      border-radius: 6px;
      background: var(--tp-bg-elevated);
    }

    .pane-text {
      max-height: 260px;
      overflow: auto;
      margin: 4px 0 0;
      padding: 8px;
      font-size: 12px;
      background: var(--tp-bg-elevated);
      border: 1px solid var(--tp-border);
      border-radius: 6px;
      white-space: pre-wrap;
      word-break: break-all;
    }

    .pane-text-loading,
    .pane-text-error {
      font-size: 12px;
      color: var(--tp-text-secondary);
    }

    .pane-text-error {
      color: var(--tp-danger);
    }
  }
}
</style>
