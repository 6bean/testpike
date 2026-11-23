<template>
  <el-drawer
    :model-value="modelValue"
    :title="t('plugin.detailTitle', { name: current?.name || plugin?.name || '' })"
    :append-to-body="false"
    size="680px"
    data-test="plugin-detail-drawer"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-loading="loading" class="drawer-body">
      <div class="detail-head">
        <el-tag v-if="current?.source === 1" size="small" type="info">{{ t('plugin.builtinTag') }}</el-tag>
        <el-tag v-else size="small" type="success">{{ t('plugin.uploadTag') }}</el-tag>
        <span class="meta" data-test="plugin-detail-revision">{{ t('plugin.revisionLabel', { r: current?.revision ?? '—' }) }}</span>
        <span class="meta">{{ t('plugin.colVersion') }}: v{{ current?.version || '—' }}</span>
        <el-button
          v-if="current && current.source !== 1"
          type="primary"
          plain
          size="small"
          data-test="plugin-edit-open"
          @click="toggleEdit"
        >
          {{ editing ? t('plugin.cancelEdit') : t('plugin.editBtn') }}
        </el-button>
      </div>

      
      <template v-if="!editing">
        <el-descriptions :column="1" border data-test="plugin-detail-desc">
          <el-descriptions-item :label="t('plugin.fName')">{{ current?.name || '—' }}</el-descriptions-item>
          <el-descriptions-item :label="t('plugin.fIndustry')">{{ current?.industry || '—' }}</el-descriptions-item>
          <el-descriptions-item :label="t('plugin.fVersion')">{{ current?.version || '—' }}</el-descriptions-item>
          <el-descriptions-item :label="t('plugin.fContentPack')">{{ manifestText('content_pack') }}</el-descriptions-item>
          <el-descriptions-item :label="t('plugin.fDefaultSkills')">{{ manifestListText('default_skills') }}</el-descriptions-item>
          <el-descriptions-item :label="t('plugin.fProjectTypes')">{{ manifestListText('project_types') }}</el-descriptions-item>
          <el-descriptions-item :label="t('plugin.fParamTemplate')">
            <pre class="json-block">{{ manifestPrettyJson('param_template') }}</pre>
          </el-descriptions-item>
        </el-descriptions>

        <h4 class="section-title">{{ t('plugin.appliedProjectsTitle') }}</h4>
        <el-table border :data="appliedProjects" size="default" data-test="plugin-applied-projects-table">
          <el-table-column prop="projectName" :label="t('plugin.colProject')" min-width="180" show-overflow-tooltip />
          <el-table-column prop="appliedAt" :label="t('plugin.colAppliedAt')" width="180" />
        </el-table>
        <div v-if="!loading && !appliedProjects.length" class="text-secondary" data-test="plugin-applied-empty">
          {{ t('plugin.appliedProjectsEmpty') }}
        </div>
      </template>

      
      <template v-else>
        <el-alert
          v-if="formErrors.length"
          type="error"
          :closable="false"
          show-icon
          data-test="plugin-edit-errors"
          class="edit-errors"
        >
          <div v-for="(err, i) in formErrors" :key="i">{{ err }}</div>
        </el-alert>
        <el-form label-position="top" data-test="plugin-edit-form">
          <el-form-item :label="t('plugin.fName')">
            <el-input v-model="form.name" data-test="plugin-field-name" />
          </el-form-item>
          <el-form-item :label="t('plugin.fIndustry')">
            <el-input v-model="form.industry" data-test="plugin-field-industry" />
          </el-form-item>
          <el-form-item :label="t('plugin.fVersion')">
            <el-input v-model="form.version" data-test="plugin-field-version" />
          </el-form-item>
          <el-form-item :label="t('plugin.fContentPack')">
            <el-input v-model="form.contentPack" data-test="plugin-field-content-pack" />
          </el-form-item>
          <el-form-item :label="t('plugin.fDefaultSkills')">
            <el-input
              v-model="skillsText"
              type="textarea"
              :rows="3"
              :placeholder="t('plugin.listPh')"
              data-test="plugin-field-default-skills"
            />
          </el-form-item>
          <el-form-item :label="t('plugin.fProjectTypes')">
            <el-input
              v-model="typesText"
              type="textarea"
              :rows="2"
              :placeholder="t('plugin.listPh')"
              data-test="plugin-field-project-types"
            />
          </el-form-item>
          <el-form-item :label="t('plugin.fParamTemplate')">
            <el-input
              v-model="form.paramTemplateJson"
              type="textarea"
              :rows="6"
              data-test="plugin-field-param-template"
            />
          </el-form-item>
        </el-form>
        <div class="edit-actions">
          <el-button type="primary" :loading="saving" data-test="plugin-edit-submit" @click="onSubmit">
            {{ t('plugin.saveBtn') }}
          </el-button>
        </div>
      </template>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">

import { reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import {
  pluginAppliedProjects,
  pluginDetail,
  pluginUpdate,
  type AppliedProjectItem,
  type PluginItem,
} from '@/api/plugin'
import {
  manifestToForm,
  validateDescriptorForm,
  buildSubmitContent,
  type DescriptorForm,
} from '@/utils/pluginDescriptor'

const props = defineProps<{
  modelValue: boolean
  
  plugin: PluginItem | null
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'saved', v: PluginItem): void
}>()

const { t } = useI18n()

const loading = ref(false)
const saving = ref(false)
const editing = ref(false)
const current = ref<PluginItem | null>(null)
const appliedProjects = ref<AppliedProjectItem[]>([])
const formErrors = ref<string[]>([])
const revision = ref(0)

const form = reactive<DescriptorForm>({
  name: '',
  industry: '',
  version: '',
  contentPack: '',
  defaultSkills: [],
  paramTemplateJson: '',
  projectTypes: [],
})
const skillsText = ref('')
const typesText = ref('')

watch(skillsText, (v) => { form.defaultSkills = splitLines(v) })
watch(typesText, (v) => { form.projectTypes = splitLines(v) })

function splitLines(v: string): string[] {
  return v.split('\n').map((x) => x.trim()).filter((x) => x.length > 0)
}

async function onOpen(): Promise<void> {
  editing.value = false
  formErrors.value = []
  current.value = null
  appliedProjects.value = []
  const id = props.plugin?.id
  if (id == null) return
  loading.value = true
  try {
    const [detail, projects] = await Promise.all([pluginDetail(id), pluginAppliedProjects(id)])
    current.value = detail
    revision.value = detail.revision
    appliedProjects.value = projects || []
  } catch (e) {
    ElMessage.error((e as Error).message || t('plugin.detailLoadFailed'))
  } finally {
    loading.value = false
  }
}

watch(
  () => [props.modelValue, props.plugin?.id] as const,
  ([visible, id]) => {
    if (visible && id != null) void onOpen()
  },
  { immediate: true },
)

function manifestValue(key: string): unknown {
  return current.value?.manifest?.[key]
}

function manifestText(key: string): string {
  const v = manifestValue(key)
  if (typeof v === 'string') return v || '—'
  if (v == null) return '—'
  return String(v)
}

function manifestListText(key: string): string {
  const v = manifestValue(key)
  return Array.isArray(v) && v.length ? v.map((x) => String(x)).join(' / ') : '—'
}

function manifestPrettyJson(key: string): string {
  const v = manifestValue(key)
  if (v == null) return '—'
  try {
    return JSON.stringify(v, null, 2)
  } catch {
    return String(v)
  }
}

function toggleEdit(): void {
  if (editing.value) {
    editing.value = false
    formErrors.value = []
    return
  }
  Object.assign(form, manifestToForm(current.value?.manifest || null))
  skillsText.value = form.defaultSkills.join('\n')
  typesText.value = form.projectTypes.join('\n')
  editing.value = true
}

async function onSubmit(): Promise<void> {
  formErrors.value = validateDescriptorForm(form)
  if (formErrors.value.length) return
  if (!current.value) return
  saving.value = true
  try {
    const updated = await pluginUpdate(current.value.id, revision.value, buildSubmitContent(form))
    ElMessage.success(t('plugin.updated', { name: updated.name, version: updated.version }))
    current.value = updated
    revision.value = updated.revision
    editing.value = false
    emit('saved', updated)
  } catch (e) {
    ElMessage.error((e as Error).message || t('plugin.updateFailed'))
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.drawer-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.detail-head {
  display: flex;
  align-items: center;
  gap: 12px;
}
.detail-head .meta {
  color: var(--tp-text-secondary);
  font-size: 12px;
}
.detail-head .el-button {
  margin-left: auto;
}
.section-title {
  margin: 8px 0 0;
  font-size: 14px;
}
.json-block {
  margin: 0;
  font-family: var(--tp-font-mono, ui-monospace, monospace);
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}
.edit-errors {
  margin-bottom: 8px;
}
.edit-actions {
  display: flex;
  justify-content: flex-end;
}
.text-secondary {
  color: var(--tp-text-secondary);
  font-size: 12px;
}
</style>
