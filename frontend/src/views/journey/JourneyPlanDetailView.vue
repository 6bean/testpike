<template>
  <div class="page-root" data-test="journey-plan-page">
    <div class="page-card">
      <!-- Detail page: the h2 carries the dynamic entity name (R23 exemption),
           page-level actions (start run) stay in the page header — this page
           has no filter band (R21 tail rule for detail/management pages). -->
      <div class="plan-head">
        <div>
          <h2 class="plan-title" data-test="journey-plan-title">{{ journey?.name || '—' }}</h2>
          <div class="plan-sub" data-test="journey-plan-sub">
            {{ t('journeys.colProgram') }}: {{ programName }} · {{ t('journeys.colOwner') }}: {{ ownerLabel }}
            <el-tag size="small" :type="journey?.status === 1 ? 'success' : 'info'">
              {{ journey?.status === 1 ? t('journeys.statusActive') : t('journeys.statusArchived') }}
            </el-tag>
          </div>
        </div>
        <div class="head-actions">
          <el-tooltip v-if="!canStartRun" :content="t('journeys.startRunDisabledHint')" placement="top">
            <span>
              <el-button type="primary" disabled data-test="journey-start-run-disabled">
                {{ t('journeys.startRun') }}
              </el-button>
            </span>
          </el-tooltip>
          <el-button
            v-else
            type="primary"
            :loading="starting"
            data-test="journey-start-run"
            @click="startRun"
          >{{ t('journeys.startRun') }}</el-button>
        </div>
      </div>

      <TableSkeleton v-if="loading" :cols="5" data-test="journey-plan-skeleton" />
      <template v-else>
        <!-- Append-segment form (16 row: form-style ordered segment table —
             no canvas). Core rejects duplicate seq numbers and names the
             reason; nextSeq keeps the common path one keystroke away. -->
        <h3 class="block-title">{{ t('journeys.addSegmentTitle') }}</h3>
        <el-form label-position="top" class="segment-form" data-test="journey-segment-form" @submit.prevent>
          <div class="segment-form-row">
            <el-form-item :label="t('journeys.colSeq')" class="form-cell form-cell-seq">
              <el-input-number v-model="form.seqNo" :min="1" data-test="journey-form-seq" />
            </el-form-item>
            <el-form-item :label="t('journeys.colProject')" class="form-cell">
              <el-select v-model="form.projectId" filterable :placeholder="t('journeys.projectPlaceholder')" data-test="journey-form-project">
                <el-option v-for="p in memberProjects" :key="p.id" :label="p.name" :value="p.id" />
              </el-select>
            </el-form-item>
            <!-- for="" opts out of the auto label[for] association: el-radio-group's
                 root is a DIV and Blink requires a labelable target, so the generated
                 for= trips the Chromium FormLabelForMatchesNonExistingIdError audit
                 (iron rule 5c). Empty for= switches Element Plus to the group pattern:
                 the caption renders as a div and the radiogroup gets aria-labelledby.
                 Same shape as the pinned precedent in CaseDetailView. -->
            <el-form-item :label="t('journeys.colTargetKind')" class="form-cell" for="">
              <el-radio-group id="" v-model="form.targetKind" data-test="journey-form-kind">
                <el-radio value="asset">{{ t('journeys.targetAsset') }}</el-radio>
                <el-radio value="case">{{ t('journeys.targetCase') }}</el-radio>
              </el-radio-group>
            </el-form-item>
            <el-form-item v-if="form.targetKind === 'asset'" :label="t('journeys.colAsset')" class="form-cell">
              <el-select
                v-model="form.assetRowId"
                filterable
                :placeholder="t('journeys.assetPlaceholder')"
                data-test="journey-form-asset"
              >
                <el-option
                  v-for="a in publishedAssets"
                  :key="a.id"
                  :label="`${a.name} · ${a.ownerProjectName} · v${a.assetVersion}`"
                  :value="a.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item v-else :label="t('journeys.colCase')" class="form-cell">
              <el-input-number
                v-model="form.caseId"
                :min="1"
                :controls="false"
                :placeholder="t('journeys.casePlaceholder')"
                data-test="journey-form-case"
              />
            </el-form-item>
          </div>
          <el-form-item :label="t('journeys.colParams')" class="params-item">
            <el-input
              v-model="form.paramsOverrideJson"
              type="textarea"
              :rows="2"
              :placeholder="t('journeys.paramsPlaceholder')"
              data-test="journey-form-params"
            />
            <div class="form-hint">{{ t('journeys.paramsHint') }}</div>
          </el-form-item>
          <el-button
            type="primary"
            :loading="adding"
            :disabled="!formValid"
            data-test="journey-segment-add"
            @click="addSegment"
          >{{ t('journeys.addSegmentSubmit') }}</el-button>
        </el-form>

        <h3 class="block-title">{{ t('journeys.segmentTableTitle') }}</h3>
        <DataTablePage
          :data="segments"
          row-key="id"
          :empty-title="t('journeys.segmentEmptyTitle')"
          :empty-hint="t('journeys.segmentEmptyHint')"
          data-test="journey-segment-table"
        >
          <el-table-column prop="seqNo" :label="t('journeys.colSeq')" width="80" align="center" />
          <el-table-column :label="t('journeys.colProject')" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">{{ projectName(row.projectId) }}</template>
          </el-table-column>
          <el-table-column :label="t('journeys.colTargetKind')" width="130" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="row.targetKind === 'asset' ? 'warning' : 'primary'">
                {{ row.targetKind === 'asset' ? t('journeys.targetAsset') : t('journeys.targetCase') }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('journeys.colTarget')" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">
              <el-button
                v-if="row.targetKind === 'asset' && row.assetRowId"
                link
                type="primary"
                :data-test="`journey-seg-asset-${row.id}`"
                @click="router.push('/asset-pool')"
              >{{ t('journeys.assetTarget', { id: row.assetRowId }) }}</el-button>
              <el-button
                v-else-if="row.caseId"
                link
                type="primary"
                :data-test="`journey-seg-case-${row.id}`"
                @click="router.push(`/cases/${row.caseId}`)"
              >{{ t('journeys.caseTarget', { id: row.caseId }) }}</el-button>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('journeys.colParams')" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="params-cell">{{ row.paramsOverrideJson || '—' }}</span>
            </template>
          </el-table-column>
        </DataTablePage>

      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * Journey plan editor : the form-style ordered segment
 * table. Segments are appended in order; each row names its owning member
 * project and its target (a published shared-asset version row or a case
 * inside that project). Starting a run hands over to core, which expands
 * every segment into that segment's project batch and lands the user on the
 * run timeline.
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import DataTablePage from '@/components/DataTablePage.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import { useAuthStore } from '@/stores/auth'
import { listProgramProjectIds, listPrograms } from '@/api/program'
import { useProjectNameLookup } from '@/composables/useProjectNameLookup'
import { listSharedAssets, type SharedAssetView } from '@/api/sharedAsset'
import {
  addJourneySegment,
  getJourney,
  startJourneyRun,
  type JourneySegmentView,
  type JourneyView,
} from '@/api/journey'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const journeyId = Number(route.params.id)
const journey = ref<JourneyView | null>(null)
const segments = ref<JourneySegmentView[]>([])
const loading = ref(true)
const starting = ref(false)
const adding = ref(false)

const programs = ref<{ id: number; name: string }[]>([])
const { projectById, projectName, loadProjectNames } = useProjectNameLookup()
const memberProjectIds = ref<number[]>([])
const publishedAssets = ref<SharedAssetView[]>([])

const form = reactive({
  seqNo: 1,
  projectId: 0,
  targetKind: 'asset' as 'asset' | 'case',
  assetRowId: null as number | null,
  caseId: null as number | null,
  paramsOverrideJson: '',
})

const programName = computed(() => programs.value.find((p) => p.id === journey.value?.programId)?.name ?? '—')
const ownerLabel = computed(() => t('journeys.ownerFormat', { id: journey.value?.ownerUserId ?? 0 }))
const canStartRun = computed(() => segments.value.length > 0)

const memberProjects = computed(() =>
  memberProjectIds.value
    .map((id) => ({ id, name: projectById.value[id] ?? t('journeys.projectFormat', { id }) }))
    .filter((p) => p.id > 0),
)

const formValid = computed(() => {
  if (!form.seqNo || !form.projectId) return false
  if (form.targetKind === 'asset') return !!form.assetRowId
  return !!form.caseId
})

async function loadJourney() {
  loading.value = true
  try {
    journey.value = await getJourney(journeyId)
    segments.value = journey.value.segments
    form.seqNo = segments.value.reduce((max, s) => Math.max(max, s.seqNo), 0) + 1
  } finally {
    loading.value = false
  }
}

async function addSegment() {
  if (!formValid.value) return
  adding.value = true
  try {
    await addJourneySegment(journeyId, {
      seqNo: form.seqNo,
      projectId: form.projectId,
      targetKind: form.targetKind,
      assetRowId: form.targetKind === 'asset' ? form.assetRowId : null,
      caseId: form.targetKind === 'case' ? form.caseId : null,
      paramsOverrideJson: form.paramsOverrideJson.trim() || null,
    })
    ElMessage.success(t('journeys.segmentAddedMsg', { seq: form.seqNo }))
    form.assetRowId = null
    form.caseId = null
    form.paramsOverrideJson = ''
    await loadJourney()
  } finally {
    adding.value = false
  }
}

async function startRun() {
  starting.value = true
  try {
    const run = await startJourneyRun(journeyId)
    ElMessage.success(t('journeys.runStartedMsg', { id: run.id }))
    router.push(`/journeys/runs/${run.id}`)
  } finally {
    starting.value = false
  }
}

onMounted(async () => {
  await loadJourney()
  const [progs] = await Promise.all([
    listPrograms(),
    loadProjectNames(),
  ])
  programs.value = progs
  if (journey.value) {
    memberProjectIds.value = await listProgramProjectIds(journey.value.programId)
    if (memberProjectIds.value.length && !memberProjectIds.value.includes(form.projectId)) {
      form.projectId = memberProjectIds.value[0]
    }
  }
  const pool = await listSharedAssets({ page: 1, size: 200 })
  // Only current published heads can be pinned by a plan row (core re-checks; this is the honest option set).
  publishedAssets.value = pool.list.filter((a) => a.status === 1 && a.head)
})
</script>

<style scoped>
.plan-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}
.plan-title {
  margin: 0 0 4px;
  font-size: 18px;
}
.plan-sub {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.block-title {
  margin: 16px 0 8px;
  font-size: 14px;
}
.segment-form-row {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
}
.form-cell {
  min-width: 220px;
}
.form-cell-seq {
  min-width: 140px;
}
.params-item {
  max-width: 720px;
}
.params-cell {
  font-family: var(--el-font-family-mono, monospace);
  font-size: 12px;
}
.form-hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}
</style>
