<template>
  <div class="schema-panel" data-test="spec-schema-panel">
    <p v-if="!endpoint" class="muted">{{ t('apiInventory.compareSideEmpty') }}</p>
    <template v-else>
      <!-- Parameters are the richest on-disk field data (core parser keeps the
           full skeleton). Request/response schemas come through as coarse type
           or $ref names (verified by the stage-1 L3 probe), so those render as
           a one-line value; an inline JSON schema (forward-compatible) unfolds
           into field rows; unparsable JSON degrades to a folded raw block. -->
      <div class="field-group" data-test="spec-schema-param-group">
        <h5 class="group-label">{{ t('apiInventory.compareGroupParams') }}</h5>
        <table v-if="endpoint.parameters && endpoint.parameters.length" class="field-table" data-test="spec-schema-field-table">
          <thead>
            <tr>
              <th>{{ t('apiInventory.compareField') }}</th>
              <th>{{ t('apiInventory.compareFieldLocation') }}</th>
              <th>{{ t('apiInventory.compareFieldType') }}</th>
              <th>{{ t('apiInventory.compareFieldRequired') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="p in endpoint.parameters" :key="p.location + ':' + p.name" data-test="spec-schema-field-row">
              <td class="mono">{{ p.name }}</td>
              <td class="mono muted">{{ p.location }}</td>
              <td class="mono muted">{{ p.type }}</td>
              <td>
                <span v-if="p.required" class="req-yes">{{ t('apiInventory.compareRequiredYes') }}</span>
                <span v-else class="muted">{{ t('apiInventory.compareRequiredNo') }}</span>
              </td>
            </tr>
          </tbody>
        </table>
        <p v-else class="muted">{{ t('apiInventory.compareNoParams') }}</p>
      </div>

      <div v-for="g in schemaGroups" :key="g.label" class="field-group" data-test="spec-schema-schema-group">
        <h5 class="group-label">{{ g.label }}</h5>
        <template v-if="g.parsed && g.parsed.ok">
          <table class="field-table" data-test="spec-schema-field-table">
            <tbody>
              <tr v-for="f in g.parsed.fields" :key="f.name" data-test="spec-schema-field-row">
                <td class="mono">{{ f.name }}</td>
                <td class="mono muted">{{ f.type }}</td>
                <td>
                  <span v-if="f.required" class="req-yes">{{ t('apiInventory.compareRequiredYes') }}</span>
                  <span v-else class="muted">{{ t('apiInventory.compareRequiredNo') }}</span>
                </td>
              </tr>
            </tbody>
          </table>
        </template>
        <el-collapse v-else-if="g.parsed && !g.parsed.ok" class="raw-fold" data-test="spec-schema-raw-fold">
          <el-collapse-item :title="t('apiInventory.compareRawUnparsable')">
            <pre class="mono raw-text">{{ g.parsed.raw }}</pre>
          </el-collapse-item>
        </el-collapse>
        <p v-else-if="g.value" class="mono schema-value" data-test="spec-schema-coarse-value">{{ g.value }}</p>
        <p v-else class="muted">{{ t('apiInventory.compareNoSchema') }}</p>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
/**
 * Schema panel of one endpoint snapshot for the version-compare drawer.
 * Real data shape (core OpenApiSpecParser, verified by the stage-1
 * L3 probe): parameters travel as a full skeleton, while request/response
 * schemas are stored as coarse type names or $ref names ('object', 'Pet', ...)
 * — never inline JSON today. Rendering rules: parameter rows as a table;
 * coarse schema names as a single value line; a genuine JSON object (should
 * the parser ever store inline schemas) unfolds to field rows via the shared
 * specCompare reader; unparsable JSON folds into raw text, labeled honestly.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ApiEndpointView } from '@/api/apiSpec'
import { parseSchemaFields, type SchemaParseResult } from '@/utils/specCompare'

const props = defineProps<{ endpoint: ApiEndpointView | null }>()
const { t } = useI18n()

interface SchemaGroup {
  label: string
  /** coarse type/$ref name shown as one line (today's real storage shape) */
  value: string | null
  parsed: SchemaParseResult | null
}

function toGroup(label: string, schema: string | null | undefined): SchemaGroup {
  const trimmed = (schema ?? '').trim()
  if (!trimmed) return { label, value: null, parsed: null }
  if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) return { label, value: trimmed, parsed: null }
  return { label, value: null, parsed: parseSchemaFields(trimmed) }
}

const schemaGroups = computed<SchemaGroup[]>(() => {
  const ep = props.endpoint
  if (!ep) return []
  const out: SchemaGroup[] = [
    toGroup(t('apiInventory.compareGroupRequest', { ct: ep.requestContentType || '-' }), ep.requestSchema),
  ]
  for (const [code, schema] of Object.entries(ep.responseSchemas || {})) {
    out.push(toGroup(t('apiInventory.compareGroupResponse', { code }), schema))
  }
  return out
})
</script>

<style scoped>
.schema-panel { display: flex; flex-direction: column; gap: 10px; }
.field-group { border: 1px solid var(--el-border-color-lighter); border-radius: var(--tp-radius-sm, 6px); padding: 8px 10px; }
.group-label { margin: 0 0 6px; font-size: 12px; color: var(--el-text-color-secondary); font-weight: 600; }
.field-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.field-table th { text-align: left; font-weight: 600; color: var(--el-text-color-secondary); padding: 2px 8px 4px 0; }
.field-table td { padding: 2px 8px 2px 0; border-top: 1px solid var(--el-border-color-lighter); }
.req-yes { color: var(--tp-danger, var(--el-color-danger)); }
.muted { color: var(--el-text-color-secondary); font-size: 12px; }
.mono { font-family: var(--el-font-family-mono, monospace); }
.schema-value { margin: 0; font-size: 12px; }
.raw-fold { margin-top: 2px; }
.raw-text { white-space: pre-wrap; word-break: break-all; font-size: 12px; margin: 0; }
</style>
