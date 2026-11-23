<template>
  <el-drawer v-model="visible" size="620px" :title="t('exec.selfCheckTitle')" data-test="self-check-drawer">
    <TableSkeleton v-if="loading" :cols="4" data-test="self-check-skeleton" />
    <el-empty v-else-if="!report" :description="t('exec.selfCheckLoadFail')" data-test="self-check-empty" />
    <template v-else>
      
      <div class="sc-head" data-test="self-check-head">
        <div class="sc-head-main">
          <span class="sc-name">{{ report.name }}</span>
          <el-tag size="small" :type="statusTagType(report.status)">{{ report.statusText || '—' }}</el-tag>
        </div>
        <div class="sc-head-meta">
          <span>{{ t('exec.version') }}：{{ report.version || '—' }}</span>
          <span>{{ t('exec.lastHeartbeat') }}：{{ fmtTime(report.lastHeartbeat) }}</span>
          <span v-if="report.hostOs">{{ t('exec.selfCheckHostOs') }}：{{ report.hostOs }}</span>
        </div>
      </div>

      
      <el-alert
        v-if="!report.capabilitiesReported"
        type="info"
        :closable="false"
        data-test="self-check-not-reported"
        class="sc-banner"
      >{{ t('exec.selfCheckNotReported') }}</el-alert>

      <template v-if="report.capabilitiesReported">
        
        <div class="sc-section" data-test="self-check-tools">
          <h4 class="sc-section-title">{{ t('exec.selfCheckTools') }}</h4>
          <div v-if="toolEntries.length" class="sc-chips">
            <el-tag
              v-for="[name, present] in toolEntries"
              :key="name"
              size="small"
              :type="present ? 'success' : 'danger'"
              :data-test="`self-check-tool-${name}`"
            >{{ name }}</el-tag>
          </div>
          <span v-else class="sc-muted">{{ t('exec.selfCheckNone') }}</span>
          <div class="sc-missing" data-test="self-check-missing">
            <template v-if="(report.missingTools ?? []).length">
              <span class="sc-muted">{{ t('exec.selfCheckMissingTools') }}：</span>
              <el-tag v-for="m in report.missingTools" :key="m" type="danger" size="small" effect="plain">{{ m }}</el-tag>
            </template>
            <span v-else class="sc-ok-line" data-test="self-check-no-missing">{{ t('exec.selfCheckNoMissing') }}</span>
          </div>
        </div>

        
        <div class="sc-grid">
          <div class="sc-section" data-test="self-check-runtimes">
            <h4 class="sc-section-title">{{ t('exec.selfCheckRuntimes') }}</h4>
            <div v-if="runtimeEntries.length" class="sc-chips">
              <el-tag v-for="[name, ver] in runtimeEntries" :key="name" size="small" type="info" effect="plain">
                {{ name }}{{ ver == null ? '' : `：${ver}` }}
              </el-tag>
            </div>
            <span v-else class="sc-muted">{{ t('exec.selfCheckNone') }}</span>
          </div>
          <div class="sc-section" data-test="self-check-engines">
            <h4 class="sc-section-title">{{ t('exec.selfCheckEngines') }}</h4>
            <ChipList :items="report.engines" :empty-text="t('exec.selfCheckNone')" />
          </div>
          <div class="sc-section" data-test="self-check-platforms">
            <h4 class="sc-section-title">{{ t('exec.selfCheckPlatforms') }}</h4>
            <ChipList :items="report.platforms" :empty-text="t('exec.selfCheckNone')" />
          </div>
          <div class="sc-section" data-test="self-check-device-kinds">
            <h4 class="sc-section-title">{{ t('exec.selfCheckDeviceKinds') }}</h4>
            <ChipList :items="report.deviceKinds" :empty-text="t('exec.selfCheckNone')" />
          </div>
        </div>

        
        <div class="sc-section" data-test="self-check-device-count">
          <h4 class="sc-section-title">{{ t('exec.selfCheckDeviceCount') }}</h4>
          <span v-if="report.deviceCount != null" class="sc-device-num">{{ report.deviceCount }}</span>
          <span v-else class="sc-muted">{{ t('exec.selfCheckDeviceUnattributed') }}</span>
        </div>
      </template>

      
      <div class="sc-section" data-test="self-check-components">
        <h4 class="sc-section-title">{{ t('exec.selfCheckComponents') }}</h4>
        <el-table v-if="(report.components ?? []).length" :data="report.components ?? []" size="small" border>
          <el-table-column :label="t('exec.selfCheckColComponent')" prop="componentFamily" min-width="110" />
          <el-table-column :label="t('exec.selfCheckColCurrent')" min-width="110">
            <template #default="{ row }">{{ row.currentVersion || '—' }}</template>
          </el-table-column>
          <el-table-column :label="t('upgrade.colRequirement')" min-width="100">
            <template #default="{ row }">{{ row.requirement || '—' }}</template>
          </el-table-column>
          <el-table-column :label="t('upgrade.colState')" width="110">
            <template #default="{ row }">
              <el-tooltip :content="row.stateReason || '—'" placement="top">
                <el-tag :type="verdictTagType(row.supportState)" size="small" disable-transitions
                  :data-test="`self-check-state-${row.id}`">
                  {{ supportStateLabel(row.supportState, t) }}
                </el-tag>
              </el-tooltip>
            </template>
          </el-table-column>
        </el-table>
        <span v-else class="sc-muted">{{ t('exec.selfCheckComponentsEmpty') }}</span>
      </div>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">

import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { getExecutorSelfCheck, type ExecutorSelfCheckInfo } from '@/api/executor'
import { supportStateLabel, verdictTagType } from '@/utils/verdict'
import TableSkeleton from '@/components/TableSkeleton.vue'
import ChipList from '@/components/ChipList.vue'

const { t } = useI18n()
const visible = defineModel<boolean>({ required: true })
const props = defineProps<{ executorId: number | null }>()

const report = ref<ExecutorSelfCheckInfo | null>(null)
const loading = ref(false)

const toolEntries = computed<[string, boolean][]>(() =>
  Object.entries(report.value?.tools ?? {}).map(([k, v]) => [k, v === true]),
)
const runtimeEntries = computed<[string, unknown][]>(() =>
  Object.entries(report.value?.runtimes ?? {}),
)


function statusTagType(code: number): 'success' | 'warning' | 'danger' | 'info' {
  if (code === 1) return 'success'
  if (code === 2) return 'warning'
  if (code === 3) return 'danger'
  return 'info'
}

function fmtTime(v: string | null): string {
  return v ? v.replace('T', ' ').slice(0, 19) : '—'
}

watch(
  () => [visible.value, props.executorId] as const,
  async ([open, id]) => {
    if (!open || id == null) return
    loading.value = true
    report.value = null
    try {
      report.value = await getExecutorSelfCheck(id)
    } catch {
      report.value = null
    } finally {
      loading.value = false
    }
  },
  { immediate: true },
)
</script>

<style scoped>
.sc-head {
  margin-bottom: 12px;
}
.sc-head-main {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.sc-name {
  font-weight: 600;
  font-size: 16px;
}
.sc-head-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.sc-banner {
  margin-bottom: 12px;
}
.sc-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px 20px;
}
.sc-section {
  margin-bottom: 14px;
}
.sc-section-title {
  margin: 0 0 6px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  font-weight: 600;
}
.sc-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.sc-missing {
  margin-top: 8px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}
.sc-device-num {
  font-size: 20px;
  font-weight: 600;
}
.sc-muted {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.sc-ok-line {
  font-size: 12px;
  color: var(--el-color-success);
}
</style>
