
<template>
  <el-dialog
    draggable
    :model-value="visible"
    :title="caseTitle ? `${t('stepTree.dialogTitle')} · ${caseTitle}` : t('stepTree.dialogTitle')"
    class="tp-dialog--xl"
    destroy-on-close
    append-to-body
    :before-close="onDialogClose"
    data-test="step-tree-dialog"
    @update:model-value="(v: boolean) => emit('update:visible', v)"
    @open="initFromScript"
  >
    <div class="ste-wrap">
 
      <DraftRestoreBar :draft-key="scriptDraftKey" :active="visible" @restore="onScriptDraftRestore" />
 
      <el-tabs v-model="activeTab" class="ste-tabs" data-test="step-tree-tabs" :before-leave="onTabLeave">
        <el-tab-pane :label="t('stepTree.tabTree')" name="tree">
      
      <div class="ste-toolbar">
        <el-dropdown trigger="click" data-test="step-tree-add" @command="onAddCommand">
          <el-button
            size="small"
            type="primary"
            plain
          >
            <el-icon><Plus /></el-icon>{{ t('stepTree.addStep') }}
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item
                v-for="spec in candidateVocab"
                :key="spec.action"
                :command="spec.action"
                :data-test="`step-tree-add-${spec.action}`"
              >
                {{ t(spec.label) }}（{{ spec.action }}）
                <el-tag v-if="spec.manualOnly" size="small" type="warning" class="ste-menu-tag">{{ t('stepTree.manualOnly') }}</el-tag>
                <el-tag v-if="spec.container" size="small" type="info" class="ste-menu-tag">{{ t('stepTree.container') }}</el-tag>
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
 
        <el-button size="small" plain data-test="step-tree-insert-loop" @click="onInsertLoop">{{ t('stepTree.insertLoop') }}</el-button>
        <el-button size="small" plain data-test="step-tree-insert-if" @click="onInsertIf">{{ t('stepTree.insertIf') }}</el-button>
 
        <el-button size="small" plain data-test="step-tree-insert-code" @click="onInsertCode">{{ t('stepTree.insertCode') }}</el-button>
 
        <el-button
          v-if="isAppTarget"
          size="small"
          type="primary"
          plain
          :disabled="!appautoEngine"
          data-test="step-tree-nl-open"
          @click="openNlDialog"
        >
          <el-icon><MagicStick /></el-icon>{{ t('stepTree.nl.btn') }}
        </el-button>
        <span v-if="isAppTarget && !appautoEngine" class="ste-dirty" data-test="step-tree-nl-no-engine-tip">
          {{ t('stepTree.nl.noEngineTip') }}
        </span>
 
        <el-button
          v-if="isAppTarget"
          size="small"
          type="primary"
          plain
          :disabled="!appautoEngine"
          data-test="step-tree-record-open"
          @click="openRecordDialog"
        >
          <el-icon><VideoCamera /></el-icon>{{ t('stepTree.record.btn') }}
        </el-button>
        <el-button size="small" :disabled="!canUndo" data-test="step-tree-undo" @click="undo">{{ t('stepTree.undo') }}</el-button>
        <span v-if="guard.isDirty()" class="ste-dirty" data-test="step-tree-dirty">{{ t('common.unsavedTitle') }}</span>
        <span v-else class="ste-clean">{{ t('stepTree.inSync') }}</span>
      </div>

 
      <div v-if="usageItems.length" class="ste-usage" data-test="step-tree-usage">
        <span
          v-for="item in usageItems"
          :key="item.key"
          class="ste-usage-item"
          :class="`ste-usage-${item.level}`"
          :data-test="`step-tree-usage-${item.key}`"
        >
          {{ item.label }} {{ item.used }}/{{ item.cap }}
        </span>
      </div>

      <div class="ste-body">
        
        <div class="ste-tree" data-test="step-tree-list">
          <div class="ste-drop" data-test="step-tree-drop-root" @dragover.prevent="hoverSlot = 'root'" @drop.prevent="onSlotDrop('root', 0, 'steps')">
            <span v-if="hoverSlot === 'root'" class="ste-drop-line" />
          </div>
          <template v-for="row in visibleRows" :key="`${epoch}-${row.key}`">
            <div
              v-if="row.kind === 'slot'"
              class="ste-drop"
              :style="indentStyle(row.depth)"
              :data-test="`step-tree-drop-${row.zoneKey}`"
              @dragover.prevent="hoverSlot = row.zoneKey"
              @drop.prevent="onSlotDrop(row.zoneKey || 'root', row.slotIndex || 0, row.branch || 'steps')"
            >
              <span v-if="hoverSlot === row.zoneKey" class="ste-drop-line" />
            </div>
            <div
              v-else-if="row.node"
              class="ste-row"
              :class="{ 'is-selected': row.node.id === selectedId, 'is-stale': staleIds.has(row.node.id) }"
              :style="indentStyle(row.depth)"
              draggable="true"
              tabindex="0"
              :data-test="`step-tree-row-${row.node.id}`"
              :aria-label="t('stepTree.rowAria', { label: vocLabel(row.label) })"
              @click="selectedId = row.node.id"
              @dragstart="onDragStart($event, row.node.id)"
              @dragend="onDragEnd"
              @dragover.prevent="hoverSlot = `before:${row.node.id}`"
              @dragleave="hoverSlot === `before:${row.node.id}` && (hoverSlot = '')"
              @drop.prevent="onBeforeDrop(row.node.id)"
              @keydown.enter.prevent="selectedId = row.node.id"
              @keydown.delete.prevent="removeNode(row.node.id)"
            >
              <el-icon v-if="row.hasKids" class="ste-caret" @click.stop="toggleFold(row.node.id)">
                <ArrowDown v-if="!folded.has(row.node.id)" />
                <ArrowRight v-else />
              </el-icon>
              <span v-else class="ste-caret-pad" aria-hidden="true" />
              <span class="ste-seq">{{ row.seq }}</span>
              <span class="ste-action">{{ vocLabel(row.label) }}</span>
              <el-tag v-if="row.loopVar" size="small" type="primary" class="ste-chip" :data-test="`step-tree-loopvar-${row.node.id}`">
                {{ interpText(row.loopVar) }}
              </el-tag>
              <el-tag v-if="row.manualOnly" size="small" type="warning" class="ste-chip">{{ t('stepTree.manualOnly') }}</el-tag>
              <span v-if="staleIds.has(row.node.id)" class="ste-stale" :data-test="`step-tree-stale-${row.node.id}`">
                {{ t('stepTree.staleRefPrefix') }}{{ staleIds.get(row.node.id) }}
              </span>
              <el-button
                class="ste-del"
                size="small"
                text
                :icon="Delete"
                :aria-label="t('stepTree.deleteStepAria', { label: vocLabel(row.label) })"
                :data-test="`step-tree-del-${row.node.id}`"
                @click.stop="removeNode(row.node.id)"
              />
            </div>
          </template>
          <div v-if="!steps.length" class="ste-empty" data-test="step-tree-empty">
            {{ t('stepTree.emptyStepsHint') }}
          </div>
        </div>

        
        <div class="ste-form" data-test="step-tree-form">
          <template v-if="selected && selectedSpec">
            <div class="ste-form-title">
              {{ t(selectedSpec.label) }}
              <code class="ste-form-action">{{ selected.step.action }}</code>
            </div>
            <!-- Contract provenance (e.g. "04 §2.1") stays in code/config for
                 traceability only: on-screen copy must carry operational info,
                 not internal document pointers. -->
            <el-form label-position="top" class="ste-fields">
              <el-form-item
                v-for="field in selectedFields"
                :key="field.key"
                :label="t(field.label)"
                :data-test="`step-tree-field-${field.key}`"
              >
                <el-input
                  v-if="field.type === 'text' || field.type === 'stringList' || field.type === 'json' || field.type === 'condition'"
                  v-model="formModel[field.key]"
                  :type="field.type === 'text' ? 'text' : 'textarea'"
                  :rows="field.type === 'text' ? 1 : 5"
                  :placeholder="field.hint ? t(field.hint) : ''"
                  @input="commitField(field)"
                />
                <el-input
                  v-else-if="field.type === 'code'"
                  v-model="formModel[field.key]"
                  type="textarea"
                  :rows="8"
                  class="ste-code"
                  data-test="step-tree-code-area"
                  :placeholder="field.hint ? t(field.hint) : ''"
                  @input="commitField(field)"
                />
                <el-input-number
                  v-else-if="field.type === 'number'"
                  v-model="formModel[field.key]"
                  :min="field.min"
                  :max="field.max"
                  :controls="false"
                  class="ste-number"
                  @change="commitField(field)"
                />
                <el-switch v-else-if="field.type === 'bool'" v-model="formModel[field.key]" @change="commitField(field)" />
                <el-select
                  v-else-if="field.type === 'enum'"
                  v-model="formModel[field.key]"
                  clearable
                  :placeholder="field.hint ? t(field.hint) : t('stepTree.pickFallback')"
                  filterable
                  :data-test="`step-tree-enum-${field.key}`"
                  @change="commitField(field)"
                >
                  <el-option v-for="opt in field.enum || []" :key="opt" :label="opt" :value="opt" />
                </el-select>
                <div v-else-if="field.type === 'locator'" class="ste-locator">
                  <el-input
                    v-for="sub in LOCATOR_STR_FIELDS"
                    :key="sub.key"
                    v-model="formLocator[field.key]![sub.key]"
                    :placeholder="t(sub.label)"
                    :data-test="`step-tree-locator-${field.key}-${sub.key}`"
                    @input="commitField(field)"
                  />
                  <el-input
                    v-model="formChainText[field.key]"
                    type="textarea"
                    :rows="2"
                    :placeholder="t('stepTree.chainPlaceholder')"
                    :data-test="`step-tree-locator-${field.key}-chain`"
                    @input="commitField(field)"
                  />
                </div>
                <div v-if="fieldErrors[field.key]" class="ste-field-error" :data-test="`step-tree-error-${field.key}`">
                  {{ fieldErrors[field.key] }}
                </div>
                <div v-else-if="field.hint" class="ste-field-hint">{{ t(field.hint) }}</div>
 
                <div v-if="field.type === 'text' || field.type === 'condition'" class="ste-refs">
                  <button
                    v-for="cand in refChips"
                    :key="`${cand.kind}-${cand.name}`"
                    type="button"
                    class="ste-ref-chip"
                    :data-test="`step-tree-ref-${field.key}-${cand.name}`"
                    @click="insertRef(field, cand)"
                  >
                    {{ refChipLabel(cand) }}
                  </button>
 
                  
                  <div v-if="poolRefCascaded.length" class="ste-pool-ref" data-test="step-tree-pool-ref">
                    <el-cascader
                      v-model="poolRefPick"
                      class="ste-pool-ref-cascader"
                      size="small"
                      :options="poolRefCascaded"
                      :props="POOL_REF_CASCADER_PROPS"
                      :placeholder="t('stepTree.poolRefPlaceholder')"
                      :show-all-levels="false"
                      @change="onPoolRefChange(field)"
                    />
                  </div>
                  <span v-else-if="poolRefResult.unnamed.length" class="ste-pool-ref-empty" data-test="step-tree-pool-ref-empty">
                    {{ t('stepTree.poolUnnamedHint', { n: poolRefResult.unnamed.length }) }}
                  </span>
 
                  <div class="ste-gen-ref" data-test="step-tree-gen-ref">
                    <el-select
                      v-model="genRefPick"
                      class="ste-gen-ref-select"
                      size="small"
                      filterable
                      :loading="genCatalogLoading"
                      :placeholder="t('stepTree.genRefPlaceholder')"
                      @visible-change="onGenRefDropdownOpen"
                      @change="onGenRefChange(field)"
                    >
                      <el-option-group v-for="g in genRefGroups" :key="g.label" :label="g.label">
                        <el-option v-for="o in g.options" :key="o.name" :value="o.token" :label="o.name" :data-test="`step-tree-gen-opt-${o.name}`">
                          <div class="ste-gen-ref-opt">
                            <span class="ste-gen-ref-opt-name">{{ o.name }}</span>
                            <span class="ste-gen-ref-opt-desc">{{ o.description }}</span>
                            <span v-if="o.example" class="ste-gen-ref-opt-sample">{{ t('stepTree.genRefSample', { sample: samplePreview(o.example) }) }}</span>
                          </div>
                        </el-option>
                      </el-option-group>
                    </el-select>
                  </div>
                  <!-- Shared-asset overlay : picks a published-head
 catalog row and inserts its content, then pins the version row to this case.
 Catalog single source = admin /shared-assets (core resolves the version chain,
 no client-side chain re-resolve); lazy load on first dropdown open. -->
                  <div v-if="projectId" class="ste-asset-ref" data-test="step-tree-asset-ref">
                    <el-select
                      v-model="assetRefPick"
                      class="ste-asset-ref-select"
                      size="small"
                      filterable
                      :loading="assetCatalogLoading"
                      :placeholder="t('stepTree.assetRefPlaceholder')"
                      @visible-change="onAssetRefDropdownOpen"
                      @change="onAssetRefChange(field)"
                    >
                      <el-option-group v-for="g in assetRefGroups" :key="g.kind" :label="t(`stepTree.assetKind.${g.kind}`)">
                        <el-option v-for="o in g.options" :key="o.assetRowId" :value="String(o.assetRowId)" :label="o.name" :data-test="`step-tree-asset-opt-${o.assetRowId}`">
                          <div class="ste-asset-ref-opt">
                            <span class="ste-asset-ref-opt-name">{{ o.name }}</span>
                            <span class="ste-asset-ref-opt-owner">{{ t('stepTree.assetRefOwnerVersion', { owner: o.ownerProjectName, version: o.assetVersion }) }}</span>
                          </div>
                        </el-option>
                      </el-option-group>
                    </el-select>
                  </div>
                </div>
              </el-form-item>
            </el-form>
          </template>
          <div v-else class="ste-form-empty" data-test="step-tree-form-empty">
            {{ t('stepTree.formEmptyHint') }}
          </div>
        </div>
      </div>
        </el-tab-pane>

 
        <el-tab-pane :label="t('stepTree.tabSource')" name="source">
          <div class="ste-source">
            <div class="ste-source-toolbar">
              <el-button size="small" plain data-test="step-tree-format" @click="onFormat">{{ t('stepTree.format') }}</el-button>
              <span v-if="guard.isDirty()" class="ste-dirty" data-test="step-tree-source-dirty">{{ t('common.unsavedTitle') }}</span>
            </div>
            <el-input
              v-model="sourceView"
              type="textarea"
              :rows="16"
              spellcheck="false"
              :placeholder="t('stepTree.sourcePlaceholder')"
              data-test="step-tree-source-text"
              @input="onSourceInput"
            />
            <div v-if="sourceErrors.length" class="ste-source-errors" data-test="step-tree-source-errors">
              <div v-for="(err, i) in sourceErrors" :key="i" class="ste-source-error">
                {{ t('stepTree.sourceErrPos', { line: err.line, column: err.column }) }}{{ err.reason }}
              </div>
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>

 
      <div class="ste-preview">
        <div class="ste-preview-head">
          <el-button size="small" plain :loading="previewLoading" data-test="step-tree-preview-btn" @click="onPreview">
            <el-icon><Refresh /></el-icon>{{ t('stepTree.previewBtn') }}
          </el-button>
          <span v-if="previewInfo" class="ste-preview-info" data-test="step-tree-preview-info">{{ previewInfo }}</span>
        </div>
        <div v-if="previewError" class="ste-preview-error" data-test="step-tree-preview-error">{{ previewError }}</div>
        <div v-if="previewSvg" class="ste-preview-canvas" data-test="step-tree-preview-svg" v-html="previewSvg" />
      </div>
    </div>

 
    <el-dialog
      v-model="diffVisible"
      :title="t('stepTree.diffTitle')"
      width="720px"
      draggable
      append-to-body
      data-test="step-tree-diff-dialog"
    >
      <DataTablePage v-if="diffChanges.length" :data="diffChanges" size="small" border data-test="step-tree-diff-table">
        <el-table-column prop="label" :label="t('stepTree.colField')" width="120" />
        <el-table-column :label="t('stepTree.colBefore')">
          <template #default="{ row }">
            <div class="version-diff-cell" data-test="step-tree-diff-before">{{ renderDiffValue(row.before) }}</div>
          </template>
        </el-table-column>
        <el-table-column :label="t('stepTree.colAfter')">
          <template #default="{ row }">
            <div class="version-diff-cell version-diff-after" data-test="step-tree-diff-after">{{ renderDiffValue(row.after) }}</div>
          </template>
        </el-table-column>
      </DataTablePage>
      <el-empty v-else :description="t('stepTree.diffEmpty')" data-test="step-tree-diff-empty" />
      <template #footer>
        <el-button size="small" data-test="step-tree-diff-cancel" @click="diffVisible = false">{{ t('common.keepEditing') }}</el-button>
        <el-button size="small" type="primary" :disabled="!diffChanges.length" :loading="saving" data-test="step-tree-diff-confirm" @click="confirmSave">
          {{ t('stepTree.diffConfirm') }}
        </el-button>
      </template>
    </el-dialog>

 
    <el-dialog
      v-model="nlDialogVisible"
      draggable
      append-to-body
      :title="t('stepTree.nl.dialogTitle')"
      width="640px"
      data-test="step-tree-nl-dialog"
    >
      <div class="ste-nl">
        <el-input
          v-model="nlDescription"
          type="textarea"
          :rows="3"
          maxlength="500"
          show-word-limit
          :placeholder="t('stepTree.nl.descPlaceholder')"
          data-test="step-tree-nl-desc"
        />
        <div class="ste-nl-actions">
          <el-button
            size="small"
            type="primary"
            :loading="nlGenerating"
            :disabled="!nlDescription.trim()"
            data-test="step-tree-nl-generate"
            @click="onNlGenerate"
          >
            {{ t('stepTree.nl.generate') }}
          </el-button>
          <span class="ste-nl-engine" data-test="step-tree-nl-engine">{{ t('stepTree.nl.engineHint', { engine: appautoEngine || '' }) }}</span>
        </div>
        
        <el-alert
          v-if="nlGateOffVisible"
          type="warning"
          show-icon
          :closable="false"
          class="ste-nl-gate-off"
          data-test="step-tree-nl-gate-off-alert"
        >
          <template #title>{{ t('stepTree.nl.gateOffTitle') }}</template>
          <div class="ste-nl-gate-off-body">
            <span>{{ t('stepTree.nl.gateOffHint') }}</span>
            <el-button size="small" :icon="Setting" data-test="step-tree-nl-gate-off-settings-btn" @click="gotoNlConfigs">
              {{ t('stepTree.nl.gotoConfigs') }}
            </el-button>
          </div>
        </el-alert>
        <div v-if="nlDraftSteps.length" class="ste-nl-draft" data-test="step-tree-nl-draft">
          <div class="ste-nl-draft-head">
            <el-checkbox
              :model-value="nlAllChecked"
              :indeterminate="nlCheckedCount > 0 && nlCheckedCount < nlDraftSteps.length"
              data-test="step-tree-nl-check-all"
              @update:model-value="onNlCheckAll"
            >
              {{ t('stepTree.nl.checkAll') }}
            </el-checkbox>
            <span class="ste-nl-draft-count" data-test="step-tree-nl-count">
              {{ t('stepTree.nl.checkedCount', { n: nlCheckedCount, total: nlDraftSteps.length }) }}
            </span>
          </div>
          <div
            v-for="(st, idx) in nlDraftSteps"
            :key="idx"
            class="ste-nl-step"
            data-test="step-tree-nl-step-row"
          >
            <el-checkbox
              :model-value="nlChecked[idx]"
              :aria-label="`${t('stepTree.nl.pickAria', { n: idx + 1 })}: ${nlStepLabel(st)}`"
              @update:model-value="(v: boolean) => (nlChecked[idx] = v)"
            />
            <span class="ste-nl-step-no">{{ idx + 1 }}</span>
            <span class="ste-nl-step-label" data-test="step-tree-nl-step-label">{{ nlStepLabel(st) }}</span>
            <span class="ste-nl-step-code">{{ String(st.action ?? '') }}</span>
            <pre class="ste-nl-step-json">{{ nlStepJson(st) }}</pre>
          </div>
        </div>
        <div v-else-if="nlGeneratedOnce" class="ste-nl-empty" data-test="step-tree-nl-empty">
          {{ t('stepTree.nl.emptyDraft') }}
        </div>
      </div>
      <template #footer>
        <div class="ste-footer">
          <span v-if="nlErrorMsg" class="ste-save-error" data-test="step-tree-nl-error">{{ nlErrorMsg }}</span>
          <div class="ste-footer-actions">
            <el-button size="small" data-test="step-tree-nl-cancel" @click="nlDialogVisible = false">{{ t('common.cancel') }}</el-button>
            <el-button
              size="small"
              type="primary"
              :disabled="nlCheckedCount === 0"
              data-test="step-tree-nl-merge"
              @click="onNlMerge"
            >
              {{ t('stepTree.nl.merge', { n: nlCheckedCount }) }}
            </el-button>
          </div>
        </div>
      </template>
    </el-dialog>

 
    <el-dialog
      v-model="recordDialogVisible"
      draggable
      append-to-body
      :title="t('stepTree.record.dialogTitle')"
      width="720px"
      data-test="step-tree-record-dialog"
    >
      <div class="ste-record">
        <div class="ste-record-task">
          <span class="ste-record-task-label" data-test="step-tree-record-task-label">{{ t('stepTree.record.taskLabel') }}</span>
          <el-input-number
            v-model="recordTaskId"
            :min="1"
            :controls="false"
            :placeholder="t('stepTree.record.taskPlaceholder')"
            class="ste-record-task-input"
            data-test="step-tree-record-task"
          />
          <el-button
            size="small"
            type="primary"
            plain
            :loading="recordLoading"
            :disabled="!recordTaskId"
            data-test="step-tree-record-load"
            @click="onRecordLoadEvents"
          >
            {{ t('stepTree.record.loadEvents') }}
          </el-button>
        </div>
        <div v-if="recordEvents" class="ste-record-events" data-test="step-tree-record-events">
          {{ t('stepTree.record.eventsSummary', { n: recordEvents.events.length, level: recordEvents.level }) }}<template v-if="recordEvents.dropped > 0">{{ t('stepTree.record.eventsDropped', { n: recordEvents.dropped }) }}</template>
        </div>
        <div v-else-if="recordLoadedOnce" class="ste-record-empty" data-test="step-tree-record-empty-events">
          {{ t('stepTree.record.emptyEvents') }}
        </div>
        <el-input
          v-if="recordEvents"
          v-model="recordTitle"
          size="small"
          maxlength="100"
          :placeholder="t('stepTree.record.titlePlaceholder')"
          data-test="step-tree-record-title"
        />
        <div v-if="recordEvents" class="ste-record-actions">
          <el-button
            size="small"
            type="primary"
            :loading="recordGenerating"
            data-test="step-tree-record-generate"
            @click="onRecordGenerate"
          >
            {{ t('stepTree.record.generate') }}
          </el-button>
        </div>
        
        <el-alert
          v-if="recordGateOffVisible"
          type="warning"
          show-icon
          :closable="false"
          class="ste-record-gate-off"
          data-test="step-tree-record-gate-off-alert"
        >
          <template #title>{{ t('stepTree.record.gateOffTitle') }}</template>
          <div class="ste-record-gate-off-body">
            <span>{{ t('stepTree.record.gateOffHint') }}</span>
            <el-button size="small" :icon="Setting" data-test="step-tree-record-gate-off-settings-btn" @click="gotoNlConfigs">
              {{ t('stepTree.nl.gotoConfigs') }}
            </el-button>
          </div>
        </el-alert>
        <div v-if="recordDraft" class="ste-record-draft" data-test="step-tree-record-draft">
          <div class="ste-record-draft-head">
            <el-checkbox
              :model-value="recordAllChecked"
              :indeterminate="recordCheckedCount > 0 && recordCheckedCount < recordDraftSteps.length"
              data-test="step-tree-record-check-all"
              @update:model-value="(v: boolean) => onRecordCheckAll(v)"
            >
              {{ t('stepTree.record.checkAll') }}
            </el-checkbox>
            <span class="ste-record-draft-count" data-test="step-tree-record-count">
              {{ t('stepTree.record.checkedCount', { n: recordCheckedCount, total: recordDraftSteps.length }) }}
            </span>
          </div>
          <div
            v-for="st in recordDraftSteps"
            :key="st.stepNo"
            class="ste-record-step"
            data-test="step-tree-record-step-row"
          >
            <el-checkbox
              :model-value="st.checked"
              :aria-label="`${t('stepTree.record.pickAria', { n: st.stepNo })}: ${recordStepLabel(st)}`"
              @update:model-value="(v: boolean) => (st.checked = v)"
            />
            <span class="ste-record-step-no">{{ st.stepNo }}</span>
            <span class="ste-record-step-label" data-test="step-tree-record-step-label">{{ recordStepLabel(st) }}</span>
            <span class="ste-record-step-code">{{ st.action }}</span>
            <pre class="ste-record-step-json">{{ recordStepJson(st) }}</pre>
          </div>
        </div>
        <div v-if="recordDrafts.length" class="ste-record-list">
          <div class="ste-record-list-head" data-test="step-tree-record-list-head">{{ t('stepTree.record.draftHead') }}</div>
          <div
            v-for="row in recordDrafts"
            :key="row.id"
            class="ste-record-list-row"
            data-test="step-tree-record-draft-row"
          >
            <span class="ste-record-list-title">{{ row.title || t('stepTree.record.draftCount', { n: recordItemsCount(row) }) }}</span>
            <span class="ste-record-list-meta">{{ t('stepTree.record.draftCount', { n: recordItemsCount(row) }) }} · {{ t('stepTree.record.draftByTask', { id: row.sourceTaskId ?? '-' }) }} · {{ t('stepTree.record.draftTime', { time: row.createdAt ?? '-' }) }}</span>
            <el-button size="small" data-test="step-tree-record-preview" @click="previewRecordDraft(row)">
              {{ row.id === recordDraft?.id ? t('stepTree.record.previewCurrent') : t('stepTree.record.preview') }}
            </el-button>
          </div>
        </div>
      </div>
      <template #footer>
        <div class="ste-footer">
          <span v-if="recordErrorMsg" class="ste-save-error" data-test="step-tree-record-error">{{ recordErrorMsg }}</span>
          <div class="ste-footer-actions">
            <el-popconfirm
              v-if="recordDraft"
              :title="t('stepTree.record.rejectBtn') + '?'"
              @confirm="onRecordReject"
            >
              <template #reference>
                <el-button size="small" data-test="step-tree-record-reject">{{ t('stepTree.record.rejectBtn') }}</el-button>
              </template>
            </el-popconfirm>
            <el-button size="small" data-test="step-tree-record-cancel" @click="recordDialogVisible = false">{{ t('common.cancel') }}</el-button>
            <el-button
              size="small"
              type="primary"
              :disabled="recordCheckedCount === 0"
              :loading="recordConfirming"
              data-test="step-tree-record-confirm"
              @click="onRecordConfirm"
            >
              {{ t('stepTree.record.confirmBtn', { n: recordCheckedCount }) }}
            </el-button>
          </div>
        </div>
      </template>
    </el-dialog>

    <template #footer>
      <div class="ste-footer">
        <span v-if="saveError" class="ste-save-error" data-test="step-tree-save-error">{{ saveError }}</span>
        <div class="ste-footer-actions">
          <el-button size="small" data-test="step-tree-cancel" @click="onCancel">{{ t('common.cancel') }}</el-button>
          <el-button
            size="small"
            type="primary"
            plain
            data-test="step-tree-save"
            :loading="saving"
            :disabled="!guard.isDirty()"
            @click="onSave"
          >
            {{ t('stepTree.saveBtn') }}
          </el-button>
        </div>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">

import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useAppStore } from '@/stores/app'
import { useAuthStore } from '@/stores/auth'
import { ElMessage } from 'element-plus'
import { ArrowDown, ArrowRight, Delete, MagicStick, Plus, Refresh, Setting, VideoCamera } from '@element-plus/icons-vue'
import { actionSpecOf, containerBranches, vocabForTarget, type VocabField } from '@/config/actionVocab'
import { crossLayerDrop } from '@/composables/useStepDragSort'
import { useDraftGuard } from '@/composables/useDraftGuard'
import { applyAutoDraftData, autoDraftKey, clearAutoDraft, useAutoDraft, type AutoDraftRecord } from '@/composables/useAutoDraft'
import DraftRestoreBar from '@/components/DraftRestoreBar.vue'
import DataTablePage from '@/components/DataTablePage.vue'
import { listConfigs } from '@/api/config'
import { BizError } from '@/api/request'
import { generateNlStep, generateRecordFlow, listRecordDrafts, findRecordDraft, confirmRecordDraft, rejectRecordDraft, type RecordDraftView } from '@/api/appauto'
import { getExecTask, parseRecordEvents, type RecordEventsEvidence } from '@/api/executor'
import { bizErrorMsg } from '@/utils/bizError'
import { isMobileFamilyTarget } from '@/utils/automationTarget'
import { previewCaseFlowchart, saveCaseScript, type CaseScript, type CaseVersionChangeItem } from '@/api/case'
import { checkCondition } from '@/utils/conditionPrecheck'
import { renderMermaidSvg } from '@/utils/mermaidRender'
import { confirmStaleReload } from '@/utils/staleConflict'
import { collectVisibleVars, loopVarOf, referenceCandidates, type ScopePathEntry, type ScopeStep } from '@/utils/stepScope'
import { buildPoolRefOptions, dataRefToken, type PoolRefItem } from '@/utils/dataRef'
import { buildGenGroups, type GenOptionGroup } from '@/utils/genRef'
import { getGenkitCatalog, listPoolItems, type GenFunctionGroup } from '@/api/dataFactory'
import {
  buildAssetGroups,
  type AssetOption,
  type AssetOptionGroup,
  type AssetRowLike,
} from '@/utils/assetRef'
import { listSharedAssets, pinSharedAsset } from '@/api/sharedAsset'
import { makeCodeShell, makeIfShell, makeLoopShell } from '@/utils/stepShellFactory'
import { applyFieldEdit, LOCATOR_FIELDS, toControlValue, type LocatorModel } from '@/utils/stepFormField'
import { buildStepNodes, flattenStepNodes, newStepOf, nodePathOf, scanStaleRefs, type StepTreeNode } from '@/utils/stepTreeModel'
import {
  countScriptLimits,
  diffScriptFields,
  parseScriptSourceForModeSwitch,
  prettifyScriptSource,
  type SourceIssue,
} from '@/utils/scriptSource'

const props = withDefaults(
  defineProps<{
    visible: boolean
    caseId: number
    script: CaseScript | null
    caseTitle?: string
 
    projectId?: number
    
    automationTarget?: string
    
    appautoEngine?: string | null
  }>(),
  { caseTitle: '', projectId: 0, automationTarget: 'web', appautoEngine: null },
)

const emit = defineEmits<{
  (e: 'update:visible', v: boolean): void
  
  (e: 'saved', script: CaseScript): void
  
  (e: 'reload-requested'): void
}>()

const appStore = useAppStore()
const authStore = useAuthStore()
const router = useRouter()
const { t } = useI18n()


const LOCATOR_STR_FIELDS = LOCATOR_FIELDS.filter((f) => f.key !== 'chain')


const candidateVocab = computed(() => vocabForTarget(props.automationTarget))


function vocLabel(key: string | undefined): string {
  if (!key) return ''
  return key.startsWith('vocab.') ? t(key) : key
}

const activeTab = ref<'tree' | 'source'>('tree')
const sourceView = ref('')
const sourceErrors = ref<SourceIssue[]>([])
const baselineDoc = ref<Record<string, unknown>>({})


const draftState = reactive({ stepsJson: '[]', sourceText: '' })
const guard = useDraftGuard(draftState, () => initFromScript())


const scriptDraftKey = autoDraftKey('script-editor', 'edit', props.caseId)
const scriptAutoDraft = useAutoDraft(scriptDraftKey, draftState, {
  enabled: () => props.visible && guard.isDirty(),
  snapshot: () => ({
    stepsJson: draftState.stepsJson,
    sourceText: draftState.sourceText,
    sourceView: sourceView.value,
    activeTab: activeTab.value,
  }),
})
onBeforeUnmount(scriptAutoDraft.stop)


function onScriptDraftRestore(rec: AutoDraftRecord): void {
  const applied = applyAutoDraftData(draftState as unknown as Record<string, unknown>, rec.data)
  if (applied) {
    try {
      const parsed = JSON.parse(draftState.stepsJson) as unknown
      if (Array.isArray(parsed)) {
        steps.value = parsed as ScopeStep[]
        rebuild()
      }
    } catch {
    }
  }
  if (rec.data.activeTab === 'source' && typeof rec.data.sourceView === 'string' && rec.data.sourceView) {
    sourceView.value = rec.data.sourceView
    activeTab.value = 'source'
  }
  ElMessage[applied ? 'success' : 'warning'](
    applied ? t('stepTree.draftRestored') : t('stepTree.draftUnrecoverable'),
  )
}


function onDialogClose(done: () => void): void {
  guard.beforeClose(() => {
    clearAutoDraft(scriptDraftKey)
    done()
  })
}


function normSource(text: string): string | null {
  try {
    return JSON.stringify(JSON.parse(text))
  } catch {
    return null
  }
}


function refillSourceView() {
  const pretty = prettifyScriptSource(draftDoc())
  sourceView.value = pretty.ok ? pretty.text : draftDoc()
  sourceErrors.value = []
}

function onTabLeave(to: string | number) {
  if (to === 'source') {
    refillSourceView()
    return true
  }
  const res = parseScriptSourceForModeSwitch(sourceView.value)
  if (!res.ok) {
    sourceErrors.value = res.errors
    ElMessage.warning(t('stepTree.sourceIssues', { n: res.errors.length }))
    return false
  }
  if (JSON.stringify(res.steps) !== JSON.stringify(steps.value)) pushUndo()
  steps.value = res.steps
  rebuild()
  draftState.sourceText = normSource(draftDoc()) ?? ''
  return true
}


function onSourceInput() {
  const norm = normSource(sourceView.value)
  if (norm !== null) {
    draftState.sourceText = norm
    sourceErrors.value = []
  } else {
    draftState.sourceText = sourceView.value
  }
}

function onFormat() {
  const res = prettifyScriptSource(sourceView.value)
  if (!res.ok) {
    const parsed = parseScriptSourceForModeSwitch(sourceView.value)
    sourceErrors.value = parsed.ok ? [] : parsed.errors
    ElMessage.warning(t('stepTree.formatFailed'))
    return
  }
  sourceView.value = res.text
  onSourceInput()
}

const caps = ref<{ steps: number; loop: number; depth: number } | null>(null)

async function loadUsageCaps() {
  if (caps.value || !authStore.hasPerm('perm:system:system-config')) return
  try {
    const GROUP_SYSTEM = '\u7cfb\u7edf'
    const rows = await listConfigs(GROUP_SYSTEM)
    const pick = (key: string): number | undefined => {
      const n = Number(rows.find((r) => r.configKey === key)?.configValue)
      return Number.isFinite(n) && n > 0 ? n : undefined
    }
    const s = pick('ai.script.max-steps')
    const l = pick('ai.script.max-loop')
    const d = pick('ai.script.max-nest-depth')
    if (s != null && l != null && d != null) caps.value = { steps: s, loop: l, depth: d }
  } catch {
  }
}

interface UsageItem {
  key: string
  label: string
  used: number
  cap: number
  level: 'ok' | 'warning' | 'danger'
}

const usageItems = computed<UsageItem[]>(() => {
  if (!caps.value) return []
  const c = countScriptLimits(steps.value)
  const mk = (key: string, label: string, used: number, cap: number): UsageItem => ({
    key,
    label,
    used,
    cap,
    level: used >= cap ? 'danger' : used >= cap * 0.8 ? 'warning' : 'ok',
  })
  return [
    mk('steps', t('stepTree.capSteps'), c.nodes, caps.value.steps),
    mk('loop', t('stepTree.capLoop'), c.loops, caps.value.loop),
    mk('depth', t('stepTree.capDepth'), c.maxDepth, caps.value.depth),
  ]
})

const sourceDoc = ref<Record<string, unknown>>({})
const steps = ref<ScopeStep[]>([])
const tree = ref<StepTreeNode[]>([])
const undoStack = ref<string[]>([])
const epoch = ref(0)
const selectedId = ref('')
const folded = ref<Set<string>>(new Set())
const hoverSlot = ref('')
const draggingId = ref('')

const canUndo = computed(() => undoStack.value.length > 0)


function rebuild() {
  tree.value = buildStepNodes(steps.value)
  const alive = new Set<string>()
  const collect = (nodes: StepTreeNode[]) => nodes.forEach((n) => { alive.add(n.id); if (n.children) collect(n.children) })
  collect(tree.value)
  folded.value = new Set([...folded.value].filter((id) => alive.has(id)))
  epoch.value += 1
  draftState.stepsJson = JSON.stringify(steps.value)
}


function pushUndo() {
  undoStack.value = [...undoStack.value, JSON.stringify(steps.value)].slice(-50)
}


function chainOf(id: string): StepTreeNode[] {
  const segs = nodePathOf(id)
  const chain: StepTreeNode[] = []
  let list: StepTreeNode[] = tree.value
  for (const seg of segs) {
    const node: StepTreeNode | undefined = list[seg]
    if (!node) return []
    chain.push(node)
    list = node.children ?? []
  }
  return chain
}

function nodeById(id: string): StepTreeNode | null {
  const chain = chainOf(id)
  return chain.length && chain[chain.length - 1].id === id ? chain[chain.length - 1] : null
}


function listForNode(node: StepTreeNode): ScopeStep[] {
  const chain = chainOf(node.id)
  const parent = chain.length >= 2 ? chain[chain.length - 2] : null
  if (!parent) return steps.value
  const key = node.branch
  if (!Array.isArray(parent.step[key])) parent.step[key] = []
  return parent.step[key] as ScopeStep[]
}


function visibleVarsOf(id: string): string[] {
  const chain = chainOf(id)
  if (!chain.length || chain[chain.length - 1].id !== id) return []
  const entries: ScopePathEntry[] = []
  for (let i = 0; i < chain.length - 1; i++) {
    entries.push({ index: chain[i].fieldIndex, branch: chain[i + 1].branch })
  }
  return collectVisibleVars(steps.value, entries)
}

function initFromScript() {
  const raw = props.script?.actionJson
  let doc: Record<string, unknown> = {}
  if (raw) {
    try {
      doc = JSON.parse(raw) as Record<string, unknown>
    } catch {
      doc = {}
    }
  }
  sourceDoc.value = doc
  baselineDoc.value = JSON.parse(JSON.stringify(doc)) as Record<string, unknown>
  steps.value = Array.isArray(doc.steps) ? (JSON.parse(JSON.stringify(doc.steps)) as ScopeStep[]) : []
  undoStack.value = []
  selectedId.value = ''
  folded.value = new Set()
  hoverSlot.value = ''
  draggingId.value = ''
  fieldErrors.value = {}
  previewSvg.value = ''
  previewError.value = ''
  previewInfo.value = ''
  saveError.value = ''
  activeTab.value = 'tree'
  sourceErrors.value = []
  sourceView.value = ''
  rebuild()
  draftState.sourceText = normSource(draftDoc()) ?? ''
  guard.snapshot()
  void loadUsageCaps()
}
watch(
  () => props.script,
  () => {
    if (props.visible) initFromScript()
  },
)

interface SteRow {
  kind: 'step' | 'slot'
  key: string
  depth: number
  node?: StepTreeNode
  label?: string
  seq?: number
  loopVar?: string | null
  manualOnly?: boolean
  hasKids?: boolean
  
  zoneKey: string
  slotIndex: number
  branch: string
}

const visibleRows = computed<SteRow[]>(() => {
  const out: SteRow[] = []
  let seq = 0
  const walk = (node: StepTreeNode, depth: number) => {
    const spec = actionSpecOf(node.step.action)
    seq += 1
    const branches = containerBranches(node.step.action)
    out.push({
      kind: 'step',
      key: node.id,
      depth,
      node,
      label: spec?.label ?? node.step.action,
      seq,
      loopVar: loopVarOf(node.step),
      manualOnly: spec?.manualOnly,
      hasKids: branches.length > 0,
      zoneKey: '',
      slotIndex: 0,
      branch: 'steps',
    })
    if (!branches.length || folded.value.has(node.id)) return
    for (const b of branches) {
      const kids = (node.children ?? []).filter((k) => (b === 'elseSteps' ? k.branch === 'elseSteps' : k.branch !== 'elseSteps'))
      kids.forEach((kid) => walk(kid, depth + 1))
      out.push({
        kind: 'slot',
        key: `slot:${node.id}:${b}:${kids.length}`,
        depth: depth + 1,
        zoneKey: `slot:${node.id}.${b}`,
        slotIndex: kids.length,
        branch: b,
      })
    }
  }
  tree.value.forEach((n) => walk(n, 0))
  out.push({ kind: 'slot', key: 'slot:root:steps', depth: 0, zoneKey: 'root', slotIndex: steps.value.length, branch: 'steps' })
  return out
})


function indentStyle(depth: number): Record<string, string> {
  return { marginLeft: `${Math.min(depth, 6) * 16}px` }
}

function toggleFold(id: string) {
  const next = new Set(folded.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  folded.value = next
}

const selected = computed(() => (selectedId.value ? nodeById(selectedId.value) : null))
const selectedSpec = computed(() => (selected.value ? actionSpecOf(selected.value.step.action) : undefined))

const selectedFields = computed<VocabField[]>(() => (selectedSpec.value?.fields ?? []).filter((f) => f.type !== 'steps'))

const formModel = ref<Record<string, any>>({})
const formLocator = ref<Record<string, Record<string, any>>>({})
const formChainText = ref<Record<string, string>>({})
const fieldErrors = ref<Record<string, string>>({})

watch(selected, (sel) => {
  formModel.value = {}
  formLocator.value = {}
  formChainText.value = {}
  fieldErrors.value = {}
  if (!sel) return
  for (const f of actionSpecOf(sel.step.action)?.fields ?? []) {
    if (f.type === 'steps') continue
    if (f.type === 'locator') {
      const model = toControlValue(sel.step, f) as LocatorModel
      const { chain: chainArr, ...rest } = model
      formLocator.value[f.key] = rest as unknown as Record<string, any>
      formChainText.value[f.key] = chainArr.join('\n')
    } else if (f.type === 'stringList') {
      formModel.value[f.key] = (toControlValue(sel.step, f) as string[]).join('\n')
    } else {
      formModel.value[f.key] = toControlValue(sel.step, f)
    }
  }
})


function commitField(field: VocabField) {
  const sel = selected.value
  if (!sel) return
  let value: unknown
  if (field.type === 'locator') {
    value = { ...formLocator.value[field.key], chain: String(formChainText.value[field.key] || '').split('\n') }
  } else if (field.type === 'stringList') {
    value = String(formModel.value[field.key] ?? '').split('\n')
  } else {
    value = formModel.value[field.key]
  }
  const res = applyFieldEdit(sel.step, field, value)
  if (!res.ok) {
    fieldErrors.value[field.key] = res.error || t('stepTree.fieldInvalid')
    return
  }
  fieldErrors.value[field.key] = ''
  const list = listForNode(sel)
  if (sel.fieldIndex >= 0 && sel.fieldIndex < list.length) list[sel.fieldIndex] = res.step
  if (field.type === 'condition') {
    const cond = checkCondition(String(res.step.condition ?? ''), visibleVarsOf(sel.id))
    if (!cond.ok) fieldErrors.value[field.key] = cond.error || ''
  }
  rebuild()
}


function interpText(name: string, suffix = ''): string {
  return '{'.repeat(2) + name + suffix + '}'.repeat(2)
}

const refChips = computed(() => (selectedId.value ? referenceCandidates(visibleVarsOf(selectedId.value)) : []))

function refChipLabel(cand: { kind: 'var' | 'namespace'; name: string }): string {
  return cand.kind === 'var' ? interpText(cand.name) : interpText(cand.name + ':', '…')
}
function insertRef(field: VocabField, cand: { kind: 'var' | 'namespace'; name: string }) {
  const cur = String(formModel.value[field.key] ?? '')
  formModel.value[field.key] = cur + (cand.kind === 'var' ? `{{${cand.name}}}` : `{{${cand.name}:`)
  commitField(field)
}

const poolRefItems = ref<PoolRefItem[]>([])

async function loadPoolRefItems(): Promise<void> {
  if (!props.projectId) {
    poolRefItems.value = []
    return
  }
  try {
    const res = await listPoolItems({ projectId: props.projectId, page: 1, size: 200 })
    poolRefItems.value = res.list
  } catch {
    poolRefItems.value = []
  }
}
watch(() => [props.visible, props.projectId] as const, ([vis]) => {
  if (vis) void loadPoolRefItems()
}, { immediate: true })
const poolRefResult = computed(() => buildPoolRefOptions(poolRefItems.value))
const poolRefOptions = computed(() => poolRefResult.value.options)

function samplePreview(v: string): string {
  return v.length > 24 ? `${v.slice(0, 24)}…` : v
}
const poolRefCascaded = computed(() => {
  const byEnv = new Map<number, { envName: string; keys: { label: string; value: string }[] }>()
  for (const o of poolRefOptions.value) {
    let g = byEnv.get(o.envId)
    if (!g) {
      g = { envName: o.envName, keys: [] }
      byEnv.set(o.envId, g)
    }
    g.keys.push({ label: `${o.key}${t('stepTree.poolLeafSample', { sample: samplePreview(o.sample) })}`, value: `${o.envId}::${o.key}` })
  }
  return [...byEnv.entries()].map(([envId, g]) => ({ value: `env-${envId}`, label: g.envName, children: g.keys }))
})

const POOL_REF_CASCADER_PROPS = { emitPath: false } as const
const poolRefPick = ref('')

function onPoolRefChange(field: VocabField) {
  const picked = poolRefPick.value
  poolRefPick.value = ''
  if (!picked.includes('::')) return
  const key = picked.split('::').slice(1).join('::')
  const cur = String(formModel.value[field.key] ?? '')
  formModel.value[field.key] = cur + dataRefToken(key)
  commitField(field)
}

const genCatalog = ref<GenFunctionGroup[]>([])
const genCatalogLoading = ref(false)
const genCatalogLoaded = ref(false)
const genRefPick = ref('')

async function ensureGenCatalog(): Promise<void> {
  if (genCatalogLoaded.value || genCatalogLoading.value) return
  genCatalogLoading.value = true
  try {
    genCatalog.value = await getGenkitCatalog()
    genCatalogLoaded.value = true
  } catch {
    genCatalog.value = []
  } finally {
    genCatalogLoading.value = false
  }
}
function onGenRefDropdownOpen(visible: boolean): void {
  if (visible) void ensureGenCatalog()
}
const genRefGroups = computed<GenOptionGroup[]>(() => buildGenGroups(genCatalog.value))

function onGenRefChange(field: VocabField) {
  const token = genRefPick.value
  genRefPick.value = ''
  if (!token) return
  const cur = String(formModel.value[field.key] ?? '')
  formModel.value[field.key] = cur + token
  commitField(field)
}

// ---------- Shared-asset overlay : catalog heads -> step/text inserts + case pin ----------
const assetCatalog = ref<AssetRowLike[]>([])
const assetCatalogLoading = ref(false)
const assetCatalogLoaded = ref(false)
const assetRefPick = ref('')

async function ensureAssetCatalog(): Promise<void> {
  if (!props.projectId) return
  if (assetCatalogLoaded.value || assetCatalogLoading.value) return
  assetCatalogLoading.value = true
  try {
    const res = await listSharedAssets({ size: 200 })
    assetCatalog.value = res.list ?? []
    assetCatalogLoaded.value = true
  } catch {
    // catalog load failure = empty dropdown, never blocks the editor main flow
    assetCatalog.value = []
  } finally {
    assetCatalogLoading.value = false
  }
}
function onAssetRefDropdownOpen(visible: boolean): void {
  if (visible) void ensureAssetCatalog()
}
const assetRefGroups = computed<AssetOptionGroup[]>(() => buildAssetGroups(assetCatalog.value))

function findAssetOption(assetRowId: string): AssetOption | undefined {
  for (const group of assetRefGroups.value) {
    const hit = group.options.find((o) => String(o.assetRowId) === assetRowId)
    if (hit) return hit
  }
  return undefined
}

/**
 * Pick a catalog row -> insert its content (steps through the shared
 * insertStepsAtSelection kernel, text appended to the current field) and
 * register the case-level pin. Invalid payload inserts nothing and says so;
 * a pin failure keeps the insert and surfaces the server message (never swallowed).
 */
async function onAssetRefChange(field: VocabField): Promise<void> {
  const picked = assetRefPick.value
  assetRefPick.value = ''
  if (!picked) return
  const option = findAssetOption(picked)
  if (!option) return
  if (option.insert.mode === 'invalid') {
    ElMessage.warning(t('stepTree.assetRefInvalid'))
    return
  }
  if (option.insert.mode === 'steps') {
    pushUndo()
    insertStepsAtSelection(option.insert.steps)
  } else {
    const cur = String(formModel.value[field.key] ?? '')
    formModel.value[field.key] = cur + option.insert.text
    commitField(field)
  }
  try {
    await pinSharedAsset({
      projectId: props.projectId as number,
      assetRowId: option.assetRowId,
      referrerKind: 'case',
      referrerId: props.caseId,
    })
  } catch (e) {
    ElMessage.warning(bizErrorMsg(e, t('stepTree.assetRefPinFailed')))
  }
}

const staleIds = computed<Map<string, string>>(() => {
  const map = new Map<string, string>()
  for (const hit of scanStaleRefs(steps.value)) {
    if (!map.has(hit.nodeId)) map.set(hit.nodeId, hit.ref)
  }
  return map
})


function insertStepsAtSelection(skeletons: ScopeStep[]) {
  if (!skeletons.length) return
  const sel = selected.value
  if (sel) {
    if (containerBranches(sel.step.action).length) {
      if (!Array.isArray(sel.step.steps)) sel.step.steps = []
      ;(sel.step.steps as ScopeStep[]).push(...skeletons)
    } else {
      const list = listForNode(sel)
      list.splice(Math.min(sel.fieldIndex + 1, list.length), 0, ...skeletons)
    }
  } else {
    steps.value.push(...skeletons)
  }
  rebuild()
}
function insertStepAtSelection(skeleton: ScopeStep) {
  insertStepsAtSelection([skeleton])
}


function onAddCommand(action: string) {
  pushUndo()
  insertStepAtSelection(newStepOf(action))
}


function onInsertLoop() {
  const sel = selected.value
  const visible = sel ? [...visibleVarsOf(sel.id)] : []
  if (sel && containerBranches(sel.step.action).length) {
    const own = loopVarOf(sel.step)
    if (own && !visible.includes(own)) visible.push(own)
  }
  const shell = makeLoopShell(visible)
  if (!shell.ok || !shell.step) {
    ElMessage.error(shell.message ?? t('stepTree.loopShellFailed'))
    return
  }
  pushUndo()
  insertStepAtSelection(shell.step)
}


function onInsertIf() {
  pushUndo()
  insertStepAtSelection(makeIfShell())
}


function onInsertCode() {
  pushUndo()
  insertStepAtSelection(makeCodeShell())
}

const nlDialogVisible = ref(false)
const nlDescription = ref('')
const nlGenerating = ref(false)
const nlGateOffVisible = ref(false)
const nlErrorMsg = ref('')
const nlGeneratedOnce = ref(false)
const nlDraftSteps = ref<ScopeStep[]>([])

const nlChecked = ref<boolean[]>([])


const isAppTarget = computed(() => isMobileFamilyTarget(props.automationTarget))
const nlCheckedCount = computed(() => nlChecked.value.filter(Boolean).length)
const nlAllChecked = computed(() => nlDraftSteps.value.length > 0 && nlCheckedCount.value === nlDraftSteps.value.length)

function openNlDialog(): void {
  nlDraftSteps.value = []
  nlChecked.value = []
  nlGeneratedOnce.value = false
  nlGateOffVisible.value = false
  nlErrorMsg.value = ''
  nlDialogVisible.value = true
}

function onNlCheckAll(v: boolean): void {
  nlChecked.value = nlDraftSteps.value.map(() => v)
}


function nlStepLabel(step: ScopeStep): string {
  const spec = actionSpecOf(String(step.action ?? ''))
  return spec ? vocLabel(spec.label) : String(step.action ?? '')
}


function nlStepJson(step: ScopeStep): string {
  const rest = Object.entries(step).filter(([k]) => k !== 'action')
  return rest.length ? rest.map(([k, v]) => `${k}=${typeof v === 'object' ? JSON.stringify(v) : String(v)}`).join('  ') : ''
}

async function onNlGenerate(): Promise<void> {
  const desc = nlDescription.value.trim()
  if (!desc || !props.projectId || !props.appautoEngine) return
  nlGenerating.value = true
  nlGateOffVisible.value = false
  nlErrorMsg.value = ''
  try {
    const view = await generateNlStep(props.projectId, props.appautoEngine, desc)
    nlDraftSteps.value = (view.steps || []) as ScopeStep[]
    nlChecked.value = nlDraftSteps.value.map(() => true)
    nlGeneratedOnce.value = true
  } catch (e) {
    nlDraftSteps.value = []
    nlChecked.value = []
    nlGeneratedOnce.value = true
    const code = e instanceof BizError ? String(e.code) : ''
    if (code === '40301') {
      nlGateOffVisible.value = true
    } else {
      nlErrorMsg.value = bizErrorMsg(e, t('stepTree.nl.generateFailed'))
    }
  } finally {
    nlGenerating.value = false
  }
}


function onNlMerge(): void {
  const picked = nlDraftSteps.value.filter((_, i) => nlChecked.value[i])
  if (!picked.length) return
  pushUndo()
  insertStepsAtSelection(picked)
  nlDialogVisible.value = false
  ElMessage.success(t('stepTree.nl.merged', { n: picked.length }))
}

function gotoNlConfigs(): void {
  void router.push('/configs')
}

const recordDialogVisible = ref(false)
const recordTaskId = ref<number | undefined>(undefined)
const recordLoading = ref(false)
const recordLoadedOnce = ref(false)
const recordEvents = ref<RecordEventsEvidence | null>(null)
const recordTitle = ref('')
const recordGenerating = ref(false)
const recordGateOffVisible = ref(false)
const recordErrorMsg = ref('')
const recordDraft = ref<RecordDraftView | null>(null)
const recordDrafts = ref<RecordDraftView[]>([])
const recordConfirming = ref(false)

interface RecordDraftStep {
  stepNo: number
  action: string
  checked: boolean
  raw: Record<string, unknown>
}
const recordDraftSteps = ref<RecordDraftStep[]>([])

const recordCheckedCount = computed(() => recordDraftSteps.value.filter((s) => s.checked).length)
const recordAllChecked = computed(
  () => recordDraftSteps.value.length > 0 && recordCheckedCount.value === recordDraftSteps.value.length,
)


function parseDraftItems(items: string | null | undefined): Record<string, unknown>[] {
  if (!items) return []
  try {
    const arr = JSON.parse(items) as unknown
    return Array.isArray(arr) ? (arr.filter((x) => !!x && typeof x === 'object') as Record<string, unknown>[]) : []
  } catch {
    return []
  }
}

function recordItemsCount(row: RecordDraftView): number {
  return parseDraftItems(row.items).length
}

async function loadRecordDrafts(): Promise<void> {
  if (!props.projectId || !props.caseId) return
  try {
    recordDrafts.value = await listRecordDrafts({ projectId: props.projectId, caseId: props.caseId, status: 1 })
  } catch {
    recordDrafts.value = []
  }
}

function openRecordDialog(): void {
  recordEvents.value = null
  recordLoadedOnce.value = false
  recordGateOffVisible.value = false
  recordErrorMsg.value = ''
  recordDraft.value = null
  recordDraftSteps.value = []
  recordTitle.value = ''
  recordDialogVisible.value = true
  void loadRecordDrafts()
}

async function onRecordLoadEvents(): Promise<void> {
  if (!recordTaskId.value) return
  recordLoading.value = true
  recordErrorMsg.value = ''
  recordGateOffVisible.value = false
  recordDraft.value = null
  recordDraftSteps.value = []
  try {
    const task = await getExecTask(recordTaskId.value)
    const parsed = parseRecordEvents(task)
    recordEvents.value = parsed && parsed.events.length ? parsed : null
    recordLoadedOnce.value = true
  } catch (e) {
    recordEvents.value = null
    recordLoadedOnce.value = true
    recordErrorMsg.value = bizErrorMsg(e, t('stepTree.record.loadFailed'))
  } finally {
    recordLoading.value = false
  }
}

async function onRecordGenerate(): Promise<void> {
  const ev = recordEvents.value
  if (!ev || !props.projectId || !recordTaskId.value || !props.appautoEngine) return
  recordGenerating.value = true
  recordGateOffVisible.value = false
  recordErrorMsg.value = ''
  try {
    const view = await generateRecordFlow({
      projectId: props.projectId,
      taskId: recordTaskId.value,
      engineCode: props.appautoEngine,
      eventSource: ev.level,
      title: recordTitle.value.trim() || undefined,
      events: ev.events,
    })
    await loadRecordDrafts()
    const row = recordDrafts.value.find((r) => r.id === view.draftId) ?? (await findRecordDraft(view.draftId))
    previewRecordDraft(row)
    ElMessage.success(t('stepTree.record.generated', { n: view.stepCount }))
  } catch (e) {
    const code = e instanceof BizError ? String(e.code) : ''
    if (code === '40301') {
      recordGateOffVisible.value = true
    } else {
      recordErrorMsg.value = bizErrorMsg(e, t('stepTree.record.generateFailed'))
    }
  } finally {
    recordGenerating.value = false
  }
}


function previewRecordDraft(row: RecordDraftView): void {
  recordDraft.value = row
  recordDraftSteps.value = parseDraftItems(row.items).map((raw, i) => ({
    stepNo: typeof raw.stepNo === 'number' ? raw.stepNo : i + 1,
    action: typeof raw.action === 'string' ? raw.action : '',
    checked: true,
    raw,
  }))
}


function recordStepLabel(st: RecordDraftStep): string {
  const spec = actionSpecOf(st.action)
  return spec ? vocLabel(spec.label) : st.action
}


function recordStepJson(st: RecordDraftStep): string {
  const rest = Object.entries(st.raw).filter(([k]) => k !== 'stepNo' && k !== 'action')
  return rest.length ? rest.map(([k, v]) => `${k}=${typeof v === 'object' ? JSON.stringify(v) : String(v)}`).join('  ') : ''
}

function onRecordCheckAll(v: boolean): void {
  recordDraftSteps.value = recordDraftSteps.value.map((s) => ({ ...s, checked: v }))
}

async function onRecordConfirm(): Promise<void> {
  const draft = recordDraft.value
  if (!draft || recordCheckedCount.value === 0) return
  recordConfirming.value = true
  recordErrorMsg.value = ''
  try {
    const checkedStepNos = recordDraftSteps.value.filter((s) => s.checked).map((s) => s.stepNo)
    const saved = await confirmRecordDraft(draft.id, checkedStepNos, props.script?.version)
    ElMessage.success(
      guard.isDirty()
        ? t('stepTree.record.confirmedDirty', { version: saved.scriptVersion ?? 0 })
        : t('stepTree.record.confirmed', { version: saved.scriptVersion ?? 0 }),
    )
    emit('reload-requested')
    recordDraft.value = null
    recordDraftSteps.value = []
    await loadRecordDrafts()
  } catch (e) {
    const code = e instanceof BizError ? String(e.code) : ''
    if (code === '40301') {
      recordGateOffVisible.value = true
    } else if (code === '40901' || code === '409') {
      recordErrorMsg.value = `${bizErrorMsg(e, t('stepTree.record.staleConflict'))}（${t('stepTree.record.staleConflict')}）`
      await loadRecordDrafts()
    } else {
      recordErrorMsg.value = bizErrorMsg(e, t('stepTree.record.confirmFailed'))
    }
  } finally {
    recordConfirming.value = false
  }
}

async function onRecordReject(): Promise<void> {
  const draft = recordDraft.value
  if (!draft) return
  recordErrorMsg.value = ''
  try {
    await rejectRecordDraft(draft.id)
    ElMessage.success(t('stepTree.record.rejected'))
    recordDraft.value = null
    recordDraftSteps.value = []
    await loadRecordDrafts()
  } catch (e) {
    const code = e instanceof BizError ? String(e.code) : ''
    if (code === '40301') {
      recordGateOffVisible.value = true
    } else {
      recordErrorMsg.value = bizErrorMsg(e, t('stepTree.record.rejectFailed'))
    }
  }
}

function removeNode(id: string) {
  const node = nodeById(id)
  if (!node) return
  pushUndo()
  const list = listForNode(node)
  if (node.fieldIndex >= 0 && node.fieldIndex < list.length) list.splice(node.fieldIndex, 1)
  if (selectedId.value === id) selectedId.value = ''
  rebuild()
}

function undo() {
  const prev = undoStack.value[undoStack.value.length - 1]
  if (prev == null) return
  undoStack.value = undoStack.value.slice(0, -1)
  try {
    steps.value = JSON.parse(prev) as ScopeStep[]
    rebuild()
  } catch {
    ElMessage.error(t('stepTree.undoCorrupt'))
  }
}

function onDragStart(e: DragEvent, id: string) {
  draggingId.value = id
  if (e.dataTransfer) e.dataTransfer.effectAllowed = 'move'
}
function onDragEnd() {
  draggingId.value = ''
  hoverSlot.value = ''
}


function slotTarget(zoneKey: string, slotIndex: number, branch: string): { toPath: number[]; insertAt: number } | null {
  if (zoneKey === 'root') return { toPath: [], insertAt: slotIndex }
  const m = /^slot:(n\.[\d.]+)\.(steps|elseSteps)$/.exec(zoneKey)
  if (!m) return null
  const toPath = nodePathOf(m[1])
  if (!toPath.length) return null
  const container = nodeById(m[1])
  if (!container) return null
  if (branch === 'elseSteps') {
    const thenLen = (container.children ?? []).filter((k) => k.branch !== 'elseSteps').length
    return { toPath, insertAt: thenLen + slotIndex }
  }
  return { toPath, insertAt: slotIndex }
}


function moveNode(fromId: string, toPath: number[], toIndex: number, branch: string) {
  const fromPath = nodePathOf(fromId)
  if (!fromPath.length) return
  pushUndo()
  const next = crossLayerDrop(tree.value, fromPath, toPath, toIndex)
  if (!next) {
    undoStack.value = undoStack.value.slice(0, -1)
    ElMessage.warning(t('stepTree.moveToSelfForbidden'))
    return
  }
  const walk = (nodes: StepTreeNode[]): StepTreeNode | null => {
    for (const n of nodes) {
      if (n.id === fromId) return n
      if (n.children) {
        const hit = walk(n.children)
        if (hit) return hit
      }
    }
    return null
  }
  const moved = walk(next)
  if (moved) moved.branch = branch
  if (branch === 'elseSteps') {
    const container = toPath.length ? (() => {
      let list: StepTreeNode[] = next
      let node: StepTreeNode | undefined
      for (const idx of toPath) { node = list[idx]; list = node?.children ?? [] }
      return node
    })() : null
    if (container?.children) {
      const firstElse = container.children.findIndex((k) => k.branch === 'elseSteps')
      if (container.elseStart == null && firstElse >= 0) {
        container.elseStart = firstElse
        container.step.elseSteps = Array.isArray(container.step.elseSteps) ? container.step.elseSteps : []
      }
    }
  }
  steps.value = flattenStepNodes(next)
  rebuild()
}

function onSlotDrop(zoneKey: string, slotIndex: number, branch: string) {
  const from = draggingId.value
  hoverSlot.value = ''
  draggingId.value = ''
  if (!from) return
  const target = slotTarget(zoneKey, slotIndex, branch)
  if (!target) return
  moveNode(from, target.toPath, target.insertAt, branch)
}


function onBeforeDrop(targetId: string) {
  hoverSlot.value = ''
  const from = draggingId.value
  draggingId.value = ''
  if (!from || from === targetId) return
  const chain = chainOf(targetId)
  const target = chain[chain.length - 1]
  if (!target || target.id !== targetId) return
  const parent = chain.length >= 2 ? chain[chain.length - 2] : null
  const insertAt = parent
    ? (parent.children ?? []).findIndex((k) => k.id === targetId)
    : tree.value.findIndex((k) => k.id === targetId)
  if (insertAt < 0) return
  moveNode(from, nodePathOf(targetId).slice(0, -1), insertAt, target.branch)
}

const previewLoading = ref(false)
const previewSvg = ref('')
const previewError = ref('')
const previewInfo = ref('')
let previewSeq = 0


function draftDoc(): string {
  return JSON.stringify({ ...sourceDoc.value, steps: steps.value })
}

async function onPreview() {
  previewLoading.value = true
  previewError.value = ''
  const seq = ++previewSeq
  try {
    const res = await previewCaseFlowchart(props.caseId, draftDoc())
    if (seq !== previewSeq) return
    previewInfo.value = t('stepTree.previewCount', { n: res.stepCount })
    try {
      const svg = await renderMermaidSvg(`ste-preview-${seq}`, res.mermaid, appStore.theme === 'dark' ? 'dark' : 'default')
      if (seq !== previewSeq) return
      previewSvg.value = svg
    } catch {
      previewSvg.value = ''
      previewError.value = t('stepTree.previewRenderFailed')
    }
  } catch (e) {
    if (seq !== previewSeq) return
    previewError.value = (e as Error).message || t('stepTree.previewFailed')
    previewSvg.value = ''
    previewInfo.value = ''
  } finally {
    if (seq === previewSeq) previewLoading.value = false
  }
}

const saving = ref(false)
const saveError = ref('')
const diffVisible = ref(false)
const diffChanges = ref<CaseVersionChangeItem[]>([])


function renderDiffValue(v: unknown): string {
  if (v === null || v === undefined || v === '') return t('stepTree.emptyValue')
  return typeof v === 'string' ? v : JSON.stringify(v)
}


function onSave() {
  if (activeTab.value === 'source') {
    const res = parseScriptSourceForModeSwitch(sourceView.value)
    if (!res.ok) {
      sourceErrors.value = res.errors
      ElMessage.warning(t('stepTree.sourceErrBlockedSave'))
      return
    }
    steps.value = res.steps
    rebuild()
  }
  let draft: Record<string, unknown>
  try {
    draft = JSON.parse(draftDoc()) as Record<string, unknown>
  } catch {
    saveError.value = t('stepTree.draftSerializeFailed')
    return
  }
  diffChanges.value = diffScriptFields(baselineDoc.value, draft)
  saveError.value = ''
  diffVisible.value = true
}


async function confirmSave() {
  if (!props.script) return
  saving.value = true
  saveError.value = ''
  try {
    const updated = await saveCaseScript(
      props.caseId,
      { schemaVersion: props.script.schemaVersion, actionJson: draftDoc() },
      props.script.version,
    )
    const savedDoc = JSON.parse(draftDoc()) as Record<string, unknown>
    sourceDoc.value = savedDoc
    baselineDoc.value = JSON.parse(JSON.stringify(savedDoc)) as Record<string, unknown>
    draftState.stepsJson = JSON.stringify(steps.value)
    draftState.sourceText = normSource(draftDoc()) ?? ''
    guard.snapshot()
    clearAutoDraft(scriptDraftKey)
    diffVisible.value = false
    ElMessage.success(t('stepTree.savedToast'))
    emit('saved', updated)
    emit('update:visible', false)
  } catch (err) {
    diffVisible.value = false
    const handled = await confirmStaleReload(err, t('stepTree.staleConflictMsg'), async () => {
      emit('reload-requested')
    })
    if (!handled) saveError.value = (err as Error)?.message || t('stepTree.saveFailed')
  } finally {
    saving.value = false
  }
}


function onCancel() {
  onDialogClose(() => emit('update:visible', false))
}
</script>

<style scoped>
.ste-wrap {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.ste-tabs {
  --ste-tabs-gap: 8px;
}
.ste-source {
  display: flex;
  flex-direction: column;
  gap: var(--ste-tabs-gap);
}
.ste-source-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
}
.ste-source :deep(textarea) {
  font-family: var(--el-font-family-mono, monospace);
  font-size: 12px;
}
.ste-source-errors {
  border: 1px solid var(--el-color-danger);
  border-radius: 4px;
  padding: 6px 8px;
}
.ste-source-error {
  color: var(--el-color-danger);
  font-size: 12px;
  line-height: 1.6;
}
.ste-usage {
  display: flex;
  gap: 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.ste-usage-warning {
  color: var(--el-color-warning);
}
.ste-usage-danger {
  color: var(--el-color-danger);
  font-weight: 600;
}
.version-diff-cell {
  font-size: 12px;
  word-break: break-all;
  white-space: pre-wrap;
}
.version-diff-after {
  color: var(--el-color-primary);
}
.ste-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
}
.ste-dirty {
  color: var(--el-color-warning);
  font-size: 12px;
}
.ste-clean {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.ste-menu-tag {
  margin-left: 4px;
}
.ste-body {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}
.ste-tree {
  flex: 1;
  min-width: 0;
  max-height: 360px;
  overflow-y: auto;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  padding: 4px;
}
.ste-row {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 6px;
  border: 1px solid transparent;
  border-radius: 4px;
  font-size: 13px;
  cursor: pointer;
}
.ste-row:hover {
  background: var(--el-fill-color-light);
}
.ste-row.is-selected {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}
.ste-row.is-stale {
  border-color: var(--el-color-danger);
}
.ste-caret {
  width: 16px;
  flex-shrink: 0;
  color: var(--el-text-color-secondary);
}
.ste-caret-pad {
  width: 16px;
  flex-shrink: 0;
}
.ste-seq {
  flex-shrink: 0;
  width: 20px;
  text-align: right;
  color: var(--el-text-color-secondary);
}
.ste-action {
  font-weight: 500;
}
.ste-chip {
  flex-shrink: 0;
}
.ste-stale {
  color: var(--el-color-danger);
  font-size: 12px;
}
.ste-del {
  margin-left: auto;
  flex-shrink: 0;
}
.ste-empty {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  padding: 8px;
}
.ste-drop {
  height: 10px;
  position: relative;
}
.ste-drop-line {
  position: absolute;
  left: 0;
  right: 0;
  top: 4px;
  height: 2px;
  border-radius: 2px;
  background: var(--el-color-primary);
}
.ste-form {
  width: 320px;
  flex-shrink: 0;
  max-height: 400px;
  overflow-y: auto;
  border-left: 1px solid var(--el-border-color-lighter);
  padding-left: 12px;
}
.ste-form-title {
  font-size: 14px;
  font-weight: 600;
}
.ste-form-action {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-left: 4px;
}
.ste-form-empty {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.ste-fields :deep(.el-form-item) {
  margin-bottom: 12px;
}
.ste-fields :deep(.el-form-item__label) {
  padding-bottom: 2px;
}
.ste-number {
  width: 120px;
}
.ste-locator {
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 100%;
}
.ste-field-error {
  color: var(--el-color-danger);
  font-size: 12px;
  width: 100%;
}
.ste-field-hint {
  color: var(--el-text-color-secondary);
  font-size: 11px;
  width: 100%;
}
.ste-refs {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  width: 100%;
  margin-top: 4px;
}
.ste-ref-chip {
  border: 1px solid var(--el-border-color);
  border-radius: 4px;
  background: var(--el-fill-color-blank);
  color: var(--el-text-color-regular);
  font-size: 12px;
  padding: 0 4px;
  cursor: pointer;
}
.ste-pool-ref-cascader {
  max-width: 240px;
}
.ste-pool-ref-empty {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.ste-gen-ref-select {
  max-width: 240px;
}
.ste-gen-ref-opt {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.ste-gen-ref-opt-name {
  font-weight: 600;
}
.ste-gen-ref-opt-desc,
.ste-gen-ref-opt-sample {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.ste-asset-ref-select {
  max-width: 240px;
}
.ste-asset-ref-opt {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.ste-asset-ref-opt-name {
  font-weight: 600;
}
.ste-asset-ref-opt-owner {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.ste-preview {
  border-top: 1px solid var(--el-border-color-lighter);
  padding-top: 8px;
}
.ste-preview-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.ste-preview-info {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.ste-preview-error {
  color: var(--el-color-danger);
  font-size: 12px;
  margin-top: 4px;
}
.ste-preview-canvas {
  margin-top: 8px;
  max-height: 240px;
  overflow: auto;
}
.ste-footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
}
.ste-save-error {
  color: var(--el-color-danger);
  font-size: 12px;
  margin-right: 8px;
}
.ste-footer-actions {
  display: flex;
  gap: 8px;
}

.ste-nl {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.ste-record {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.ste-record-task {
  display: flex;
  align-items: center;
  gap: 10px;
}
.ste-record-task-label {
  font-size: 13px;
  color: var(--el-text-color-regular);
}
.ste-record-task-input {
  width: 220px;
}
.ste-record-events {
  font-size: 13px;
  color: var(--el-text-color-regular);
}
.ste-record-empty {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.ste-record-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.ste-record-gate-off-body {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 4px;
}
.ste-record-draft {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 8px 10px;
  max-height: 300px;
  overflow: auto;
}
.ste-record-draft-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 6px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.ste-record-draft-count {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.ste-record-step {
  display: grid;
  grid-template-columns: auto auto 1fr auto;
  gap: 4px 8px;
  align-items: center;
  padding: 6px 2px;
  border-bottom: 1px dashed var(--el-border-color-lighter);
}
.ste-record-step:last-child {
  border-bottom: none;
}
.ste-record-step-no {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  min-width: 18px;
}
.ste-record-step-label {
  font-size: 13px;
}
.ste-record-step-code {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.ste-record-step-json {
  grid-column: 2 / -1;
  margin: 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  white-space: pre-wrap;
  word-break: break-all;
}
.ste-record-list {
  border-top: 1px solid var(--el-border-color-lighter);
  padding-top: 8px;
  max-height: 200px;
  overflow: auto;
}
.ste-record-list-head {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 4px;
}
.ste-record-list-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 4px 2px;
}
.ste-record-list-title {
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 200px;
}
.ste-record-list-meta {
  flex: 1;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.ste-nl-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.ste-nl-engine {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.ste-nl-gate-off-body {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 4px;
}
.ste-nl-draft {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 8px 10px;
  max-height: 320px;
  overflow: auto;
}
.ste-nl-draft-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 6px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.ste-nl-draft-count {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.ste-nl-step {
  display: grid;
  grid-template-columns: auto auto 1fr auto;
  gap: 4px 8px;
  align-items: center;
  padding: 6px 2px;
  border-bottom: 1px dashed var(--el-border-color-lighter);
}
.ste-nl-step:last-child {
  border-bottom: none;
}
.ste-nl-step-no {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  min-width: 18px;
}
.ste-nl-step-label {
  font-weight: 500;
}
.ste-nl-step-code {
  font-family: var(--el-font-family-mono, monospace);
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.ste-nl-step-json {
  grid-column: 2 / -1;
  margin: 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  white-space: pre-wrap;
  word-break: break-all;
}
.ste-nl-empty {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
