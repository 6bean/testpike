<template>
  <el-dialog
    v-model="visible"
    :title="t('appauto.onboard.title')"
    width="800px"
    draggable
    destroy-on-close
    data-test="onboard-wizard"
  >
    <el-steps :active="step" finish="success" align-center class="obw__steps">
      <el-step :title="t('appauto.onboard.stepPlatform')" />
      <el-step :title="t('appauto.onboard.stepDevice')" />
      <el-step :title="t('appauto.onboard.stepEngine')" />
      <el-step :title="t('appauto.onboard.stepTryRun')" />
    </el-steps>

    
    <div v-show="step === 0" class="obw__body" data-test="onboard-step-platform">
      <p class="obw__lead">{{ t('appauto.onboard.platformLead') }}</p>
      <TableSkeleton v-if="loading" :cols="4" />
      <div v-else-if="!platforms.length" class="obw__empty">
        <EmptyState :title="t('appauto.onboard.platformEmpty')" :hint="t('appauto.onboard.platformEmptyHint')" />
      </div>
      <div v-else class="obw__cards" data-test="onboard-platform-cards">
        <el-card
          v-for="p in platforms"
          :key="p.code"
          shadow="hover"
          class="obw__card"
          :class="{ 'obw__card--picked': pickedPlatform === p.code }"
          :data-test="`onboard-platform-${p.code}`"
          @click="pickedPlatform = p.code"
        >
          <div class="obw__card-head">
            <span class="obw__card-name">{{ p.name }}</span>
            <el-tag v-if="pickedPlatform === p.code" type="success" size="small" disable-transitions>
              {{ t('appauto.onboard.picked') }}
            </el-tag>
          </div>
          <div class="obw__card-sub">{{ (p.deviceKindCodes || []).join(' · ') || '-' }}</div>
          <p v-if="p.note" class="obw__card-note">{{ p.note }}</p>
        </el-card>
      </div>
    </div>

    
    <div v-show="step === 1" class="obw__body" data-test="onboard-step-device">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        class="obw__probe-note"
        data-test="onboard-probe-note"
        :title="t('appauto.onboard.probeNoteTitle')"
        :description="t('appauto.onboard.probeNoteBody')"
      />
      <el-form :model="form" label-width="110px" class="obw__form">
        <el-form-item :label="t('appauto.onboard.formName')" required>
          <el-input v-model="form.name" maxlength="100" data-test="onboard-form-name" />
        </el-form-item>
        <el-form-item :label="t('appauto.onboard.formSerial')" required>
          <el-input v-model="form.serial" maxlength="120" :placeholder="t('appauto.onboard.serialHint')" data-test="onboard-form-serial" />
        </el-form-item>
        <el-form-item :label="t('appauto.onboard.formType')" required>
          <el-select v-model="form.deviceType" class="obw__control" data-test="onboard-form-type">
            <el-option v-for="ty in DEVICE_TYPES" :key="ty" :label="deviceTypeLabel(ty)" :value="ty" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.deviceType === 'ADB'" :label="t('appauto.onboard.formAdbAddress')" required>
          <el-input v-model="form.adbAddress" placeholder="192.168.1.10:5555" data-test="onboard-form-adb" />
        </el-form-item>
      </el-form>
      <p v-if="registeredSerial" class="obw__ok" data-test="onboard-device-ok">
        <el-icon><Select /></el-icon> {{ t('appauto.onboard.deviceRegistered', { serial: registeredSerial }) }}
      </p>
    </div>

    
    <div v-show="step === 2" class="obw__body" data-test="onboard-step-engine">
      <p class="obw__lead">{{ t('appauto.onboard.engineLead', { platform: platformName || '-' }) }}</p>
      <TableSkeleton v-if="loading" :cols="4" />
      <div v-else-if="!engines.length" class="obw__empty">
        <EmptyState :title="t('appauto.onboard.engineEmpty')" :hint="t('appauto.onboard.engineEmptyHint')" />
      </div>
      <div v-else class="obw__cards" data-test="onboard-engine-cards">
        <el-card
          v-for="e in engines"
          :key="e.code"
          shadow="hover"
          class="obw__card"
          :class="{ 'obw__card--picked': pickedEngine === e.code }"
          :data-test="`onboard-engine-${e.code}`"
          @click="compatible(e) && (pickedEngine = e.code)"
        >
          <div class="obw__card-head">
            <span class="obw__card-name">{{ e.name }}</span>
            <el-tag v-if="compatible(e)" type="success" size="small" disable-transitions data-test="onboard-compat-tag">
              {{ t('appauto.onboard.compat') }}
            </el-tag>
            <el-tag v-else type="info" size="small" disable-transitions>
              {{ t('appauto.onboard.incompat') }}
            </el-tag>
          </div>
          <div class="obw__card-sub">{{ t('appauto.onboard.priority', { n: e.defaultPriority }) }}</div>
          <p v-if="!compatible(e)" class="obw__card-note">{{ t('appauto.onboard.incompatReason', { platform: platformName || '-' }) }}</p>
        </el-card>
      </div>
      <p class="obw__hint">{{ t('appauto.onboard.engineCaliber') }}</p>
    </div>

 
    <div v-show="step === 3" class="obw__body" data-test="onboard-step-tryrun">
      <el-result icon="success" :title="t('appauto.onboard.readyTitle')" :sub-title="t('appauto.onboard.readyBody')">
        <template #extra>
          <div class="obw__summary" data-test="onboard-summary">
            <div class="obw__summary-line">{{ t('appauto.onboard.sumPlatform', { name: platformName || '-' }) }}</div>
            <div class="obw__summary-line" data-test="onboard-sum-engine">
              {{ t('appauto.onboard.sumEngine', { name: engineName || '-' }) }}
            </div>
            <div v-if="registeredSerial" class="obw__summary-line" data-test="onboard-sum-device">
              {{ t('appauto.onboard.sumDevice', { serial: registeredSerial }) }}
            </div>
          </div>
          <p class="obw__hint">{{ t('appauto.onboard.tryRunHint') }}</p>
        </template>
      </el-result>
    </div>

    <template #footer>
      <el-button v-if="step > 0" data-test="onboard-prev" @click="step -= 1">{{ t('appauto.onboard.prev') }}</el-button>
      <el-button data-test="onboard-cancel" @click="visible = false">{{ t('common.cancel') }}</el-button>
      <el-button
        v-if="step < 3"
        type="primary"
        :loading="submitting"
        data-test="onboard-next"
        @click="next"
      >{{ t('appauto.onboard.next') }}</el-button>
      <el-button v-else type="primary" data-test="onboard-done" @click="finish">
        {{ t('appauto.onboard.done') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Select } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import { bizErrorMsg } from '@/utils/bizError'
import {
  DEVICE_TYPES,
  listAppautoEngines,
  listAppautoPlatforms,
  registerAppautoDevice,
  type AppautoEngine,
  type AppautoPlatform,
} from '@/api/appauto'

const props = defineProps<{ projectId: number | null }>()
const emit = defineEmits<{ (e: 'finished'): void }>()

const { t } = useI18n()

const visible = ref(false)
const step = ref(0)
const loading = ref(false)
const submitting = ref(false)
const platforms = ref<AppautoPlatform[]>([])
const engines = ref<AppautoEngine[]>([])
const pickedPlatform = ref<string>('')
const pickedEngine = ref<string>('')
const registeredSerial = ref<string>('')

const emptyForm = () => ({ name: '', serial: '', deviceType: 'ADB', adbAddress: '' })
const form = reactive(emptyForm())

const platformName = computed(() => platforms.value.find((p) => p.code === pickedPlatform.value)?.name || '')
const engineName = computed(() => engines.value.find((e) => e.code === pickedEngine.value)?.name || '')

function deviceTypeLabel(ty: string): string {
  if (ty === 'EMULATOR') return t('appauto.onboard.typeEmulator')
  if (ty === 'ADB') return t('appauto.onboard.typeAdb')
  if (ty === 'USB') return t('appauto.onboard.typeUsb')
  return ty
}

function compatible(e: AppautoEngine): boolean {
  return !!pickedPlatform.value && (e.platformCodes || []).includes(pickedPlatform.value)
}

async function loadCatalog() {
  loading.value = true
  try {
    const [ps, es] = await Promise.all([listAppautoPlatforms(), listAppautoEngines()])
    platforms.value = ps.filter((p) => p.status === 1)
    engines.value = [...es.filter((e) => e.status === 1)].sort((a, b) => a.defaultPriority - b.defaultPriority)
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('appauto.onboard.loadFailed')))
  } finally {
    loading.value = false
  }
}

watch(visible, (open) => {
  if (!open) return
  step.value = 0
  pickedPlatform.value = ''
  pickedEngine.value = ''
  registeredSerial.value = ''
  Object.assign(form, emptyForm())
  void loadCatalog()
})

function openWizard() {
  visible.value = true
}
defineExpose({ openWizard })

function finish() {
  visible.value = false
  emit('finished')
}

async function registerDevice(): Promise<boolean> {
  if (!props.projectId) {
    ElMessage.warning(t('appauto.onboard.noProject'))
    return false
  }
  if (!form.name.trim() || !form.serial.trim()) {
    ElMessage.warning(t('appauto.onboard.nameSerialRequired'))
    return false
  }
  if (form.deviceType === 'ADB' && !form.adbAddress.trim()) {
    ElMessage.warning(t('appauto.onboard.adbRequired'))
    return false
  }
  try {
    await registerAppautoDevice({
      projectId: props.projectId,
      name: form.name.trim(),
      serial: form.serial.trim(),
      deviceType: form.deviceType,
      adbAddress: form.deviceType === 'ADB' ? form.adbAddress.trim() : null,
      labels: [],
    })
    registeredSerial.value = form.serial.trim()
    ElMessage.success(t('appauto.onboard.deviceRegisteredToast'))
    return true
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('appauto.onboard.saveFailed')))
    return false
  }
}

async function next() {
  if (step.value === 0 && !pickedPlatform.value) {
    ElMessage.warning(t('appauto.onboard.pickPlatformRequired'))
    return
  }
  if (step.value === 1) {
    if (form.name.trim() || form.serial.trim()) {
      submitting.value = true
      const ok = await registerDevice()
      submitting.value = false
      if (!ok) return
    }
  }
  if (step.value === 2 && !pickedEngine.value) {
    ElMessage.warning(t('appauto.onboard.pickEngineRequired'))
    return
  }
  step.value += 1
}
</script>

<style scoped>
.obw__steps { margin-bottom: 20px; }
.obw__body { min-height: 260px; }
.obw__lead { margin: 0 0 12px; font-size: 13px; color: var(--el-text-color-secondary); }
.obw__cards { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
.obw__card { cursor: pointer; }
.obw__card--picked { border-color: var(--el-color-success); }
.obw__card-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.obw__card-name { font-weight: 600; }
.obw__card-sub { margin-top: 6px; font-size: 12px; color: var(--el-text-color-secondary); }
.obw__card-note { margin: 6px 0 0; font-size: 12px; color: var(--el-text-color-secondary); }
.obw__probe-note { margin-bottom: 16px; }
.obw__form { max-width: 560px; }
.obw__control { width: 100%; }
.obw__ok { display: flex; align-items: center; gap: 6px; margin: 8px 0 0; color: var(--el-color-success); font-size: 13px; }
.obw__hint { margin: 12px 0 0; font-size: 12px; color: var(--el-text-color-secondary); }
.obw__summary-line { font-size: 13px; line-height: 1.8; }
</style>
