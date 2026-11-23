<template>
  <div v-if="isAppTarget" class="appauto-engine-picker" data-test="appauto-engine-picker">
    <el-alert
      v-if="loadError"
      type="error"
      :closable="false"
      show-icon
      :title="loadError"
      data-test="picker-load-error"
    />
    <TableSkeleton v-else-if="loading" :cols="3" data-test="picker-skeleton" />
    <div v-else-if="!cards.length" class="picker-empty" data-test="picker-empty">
      <EmptyState :title="t('appauto.picker.emptyEngines')" :hint="t('appauto.picker.emptyEnginesHint')" />
    </div>
    <div v-else class="picker-grid">
      <div
        class="engine-pick-card engine-pick-auto"
        data-test="picker-auto-option"
        :class="{ 'is-selected': modelValue === null }"
        role="button"
        :aria-pressed="modelValue === null"
        @click="emit('update:modelValue', null)"
      >
        <div class="card-head">
          <span class="dot dot-auto" />
          <span class="engine-name">{{ t('appauto.picker.autoOption') }}</span>
          <el-tag v-if="modelValue === null" size="small" disable-transitions>
            {{ t('appauto.picker.autoSelectedTag') }}
          </el-tag>
        </div>
        <div class="auto-hint">{{ t('appauto.picker.autoHint') }}</div>
      </div>
      <div
        v-for="c in cards"
        :key="c.engine.code"
        class="engine-pick-card"
        :class="{ 'is-selected': modelValue === c.engine.code, 'is-offline': !c.available }"
        :data-test="`picker-card-${c.engine.code}`"
        :data-available="String(c.available)"
        :data-missing-reasons="(c.missingReasons || []).join('；')"
        :data-recommended="String(c.recommended)"
        role="button"
        :aria-pressed="modelValue === c.engine.code"
        @click="emit('update:modelValue', c.engine.code)"
      >
        <div class="card-head">
          <span class="dot" :class="c.available ? 'dot-green' : 'dot-gray'" />
          <span class="engine-name">{{ c.engine.name }}</span>
          <el-tag v-if="c.recommended" size="small" type="success" disable-transitions data-test="picker-recommend-tag">
            {{ t('appauto.picker.recommended') }}
          </el-tag>
          <el-tag v-if="modelValue === c.engine.code" size="small" disable-transitions>
            {{ t('appauto.picker.selectedTag') }}
          </el-tag>
        </div>
        <div class="field-line">
          <span class="field-key">{{ t('appauto.picker.colPlatforms') }}</span>
          <span>
            <el-tag v-for="p in c.engine.platformCodes" :key="p" size="small" effect="plain" class="chip" disable-transitions>{{ p }}</el-tag>
            <span v-if="!c.engine.platformCodes.length">-</span>
          </span>
        </div>
        <div class="field-line">
          <span class="field-key">{{ t('appauto.picker.colDeviceKinds') }}</span>
          <span>{{ (c.engine.deviceKindCodes || []).join(' · ') || '-' }}</span>
        </div>
        <div class="field-line">
          <span class="field-key">{{ t('appauto.picker.colCaps') }}</span>
          <span class="caps-wrap">
            <el-tag v-for="cap in enabledCapabilityKeys(c.engine).slice(0, 4)" :key="cap" size="small" effect="plain" class="chip" disable-transitions>{{ cap }}</el-tag>
            <span v-if="enabledCapabilityKeys(c.engine).length > 4" class="caps-more">+{{ enabledCapabilityKeys(c.engine).length - 4 }}</span>
            <span v-if="!enabledCapabilityKeys(c.engine).length">-</span>
          </span>
        </div>
        <div class="state-line">
          <template v-if="c.available">
            <el-tooltip :content="t('appauto.picker.lightGreenTip', { count: c.onlineCount })" placement="top">
              <span class="avail-text avail-green" data-test="picker-avail-green">{{ t('appauto.picker.lightGreen') }}</span>
            </el-tooltip>
          </template>
          <template v-else>
            <el-tooltip :content="(c.missingReasons || []).join('；') || t('appauto.picker.lightGrayTip')" placement="top">
              <span class="avail-text avail-gray" data-test="picker-avail-gray">{{ t('appauto.picker.lightGray') }}</span>
            </el-tooltip>
          </template>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">

import { computed, ref, watchEffect } from 'vue'
import { useI18n } from 'vue-i18n'
import TableSkeleton from '@/components/TableSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import { isMobileFamilyTarget, wantedPlatformsForTarget } from '@/utils/automationTarget'
import {
  listAppautoEngines, listAppautoEngineAvailability, enabledCapabilityKeys,
  type AppautoEngine,
} from '@/api/appauto'

const props = defineProps<{
 
  modelValue: string | null
  
  target: string
}>()

const emit = defineEmits<{ 'update:modelValue': [code: string | null] }>()

const { t } = useI18n()
const loading = ref(false)
const loadError = ref('')

interface Card {
  engine: AppautoEngine
  available: boolean
  onlineCount: number
  missingReasons: string[]
  recommended: boolean
}
const cards = ref<Card[]>([])
const loadSeq = ref(0)

const isAppTarget = computed(() => isMobileFamilyTarget(props.target))
watchEffect(() => {
  if (isAppTarget.value) void load()
  else cards.value = []
})

async function load(): Promise<void> {
  const seq = ++loadSeq.value
  loading.value = true
  loadError.value = ''
  try {
    const [engines, availability] = await Promise.all([
      listAppautoEngines(false),
      listAppautoEngineAvailability(false),
    ])
    if (seq !== loadSeq.value) return
    const wanted = wantedPlatformsForTarget(props.target)
    const availMap = new Map(availability.map((a) => [a.code, a]))
    const matched = engines
      .filter((e) => (e.platformCodes || []).some((p) => wanted.includes(p)))
      .sort((a, b) => a.defaultPriority - b.defaultPriority)
    const firstReady = matched.find((e) => availMap.get(e.code)?.available)
    cards.value = matched.map((e) => {
      const a = availMap.get(e.code)
      return {
        engine: e,
        available: a?.available === true,
        onlineCount: a?.onlineCount ?? 0,
        missingReasons: a?.missingReasons ?? [],
        recommended: firstReady?.code === e.code,
      }
    })
  } catch (e) {
    if (seq !== loadSeq.value) return
    loadError.value = (e as Error)?.message || t('appauto.picker.loadFailed')
  } finally {
    if (seq === loadSeq.value) loading.value = false
  }
}
</script>

<style scoped>
.picker-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 12px;
}
.engine-pick-card {
  border: 1px solid var(--ds-border, #dcdfe6);
  border-radius: 8px;
  padding: 10px 12px;
  cursor: pointer;
  transition: border-color 0.15s, box-shadow 0.15s;
  background: var(--ds-bg-elevated, #fff);
}
.engine-pick-card:hover {
  border-color: var(--ds-primary, #409eff);
}
.engine-pick-card.is-selected {
  border-color: var(--ds-primary, #409eff);
  box-shadow: 0 0 0 1px var(--ds-primary, #409eff) inset;
}
.engine-pick-card.is-offline .engine-name {
  opacity: 0.72;
}
.card-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex: none;
}
.dot-green { background: var(--ds-success, #67c23a); }
.dot-gray { background: var(--ds-text-placeholder, #a8abb2); }
.dot-auto { background: var(--ds-primary, #409eff); }
.auto-hint { font-size: 12px; color: var(--ds-text-secondary, #909399); line-height: 1.7; }
.engine-name { font-weight: 600; }
.field-line {
  display: flex;
  gap: 6px;
  font-size: 12px;
  line-height: 1.9;
}
.field-key {
  color: var(--ds-text-secondary, #909399);
  flex: none;
}
.chip { margin-right: 4px; }
.caps-wrap { display: inline-flex; align-items: center; flex-wrap: wrap; }
.caps-more { color: var(--ds-text-secondary, #909399); }
.state-line { margin-top: 4px; font-size: 12px; }
.avail-text { cursor: default; }
.avail-green { color: var(--ds-success, #67c23a); }
.avail-gray { color: var(--ds-text-secondary, #909399); }
.picker-empty { padding: 8px 0; }
</style>
