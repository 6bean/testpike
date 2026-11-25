<!-- tp-title-dedup-exempt: the left-column card heading is a section title of
 the two-column workbench (neither equal nor containment against the top
 bar label — S-7 compliant), kept per the recon ledger note for
 AiGenerateView. The *pageTitle render is the section's own name, not the
 page-name channel. -->
<template>
  <div class="page-root ai-gen-root">
    <el-row :gutter="16" class="ai-gen-row">
      
      <el-col :xs="24" :lg="14">
        <div class="page-card" data-test="ai-gen-form">
          <div class="section-title">
            <el-icon class="title-icon"><MagicStick /></el-icon>{{ t('aiGenerate.pageTitle') }}
          </div>
          <el-radio-group v-model="mode" class="ai-gen-mode" data-test="ai-gen-mode" @change="onModeChange">
            <el-radio-button value="single">{{ t('aiGenerate.modeSingle') }}</el-radio-button>
            <el-radio-button value="batch">{{ t('aiGenerate.modeBatch') }}</el-radio-button>
          </el-radio-group>

          <!-- Case-gap hint (server-computed): the chips stay absent while the
               advice switch is off — a refusal is swallowed, no error toast. -->
          <div v-if="gapCount > 0" class="field-hint case-gap-chip" data-test="ai-gen-gap-chip">
            <el-icon><InfoFilled /></el-icon>
            <span>{{ t('aiGenerate.gapChip', { n: gapCount }) }}</span>
            <el-button
              type="primary"
              plain
              size="small"
              data-test="ai-gen-gap-fill"
              @click="applyGapPrefill"
            >{{ t('aiGenerate.gapFillBtn') }}</el-button>
          </div>

          <el-form v-if="mode === 'single'" label-width="88px" class="ai-gen-form ai-gen-form--single">
            <!-- Ownership echo (rule 1): header context is the single source —
                 no in-page project selector on this wizard anymore. -->
            <el-form-item :label="t('aiGenerate.projectLabel')">
              <ProjectOwnerEcho />
            </el-form-item>
            <el-form-item :label="t('aiGenerate.reqDescLabel')" required>
              <el-input
                v-model="form.requirement"
                type="textarea"
                :rows="7"
                maxlength="5000"
                show-word-limit
                :placeholder="t('aiGenerate.reqDescPlaceholder')"
                data-test="ai-gen-requirement"
              />
            </el-form-item>
            <el-form-item :label="t('aiGenerate.reqPointLabel')">
              <el-select
                v-model="form.requirementId"
                clearable
                filterable
                :placeholder="t('aiGenerate.reqPointPlaceholder')"
                style="width: 100%"
                data-test="ai-gen-requirement-select"
              >
                <el-option
                  v-for="r in requirements"
                  :key="r.id"
                  :label="`${r.title}${r.acceptanceCriteria ? t('aiGenerate.withAc') : ''}`"
                  :value="r.id"
                />
              </el-select>
              <div v-if="requirements.length === 0" class="field-hint">{{ t('aiGenerate.noReqHint') }}</div>
            </el-form-item>
          </el-form>

 
          <el-form v-else label-width="88px" class="ai-gen-form ai-gen-form--batch" data-test="ai-gen-batch-form">
            <el-form-item :label="t('aiGenerate.projectLabel')">
              <ProjectOwnerEcho />
            </el-form-item>
            <el-form-item :label="t('aiGenerate.reqListLabel')" required>
              <div class="batch-rows" data-test="ai-gen-batch-rows">
                <div class="batch-rows-head">
                  <span class="batch-count" data-test="ai-gen-batch-count">
                    {{ t('aiGenerate.rowCount', { filled: filledBatchCount, max: maxBatchRows }) }}
                  </span>
                  <el-button
                    plain
                    size="small"
                    :disabled="batchRows.length >= maxBatchRows"
                    data-test="ai-gen-batch-add"
                    @click="addBatchRow"
                  >
                    <el-icon><Plus /></el-icon>{{ t('aiGenerate.addRowBtn') }}
                  </el-button>
                </div>
                <div class="batch-rows-scroll">
                  <div v-for="(row, idx) in batchRows" :key="idx" class="batch-row">
                    <span class="batch-row-no">{{ idx + 1 }}</span>
                    <el-input
                      v-model="row.text"
                      type="textarea"
                      :rows="2"
                      maxlength="5000"
                      :placeholder="t('aiGenerate.batchRowPlaceholder')"
                      class="batch-row-input"
                      :data-test="`ai-gen-batch-input-${idx}`"
                    />
                    <el-tag
                      v-if="row.requirementId !== undefined"
                      type="info"
                      size="small"
                      class="batch-row-link-tag"
                      :data-test="`ai-gen-batch-link-${idx}`"
                    >
                      {{ t('aiGenerate.rowLinkedReq', { id: row.requirementId }) }}
                    </el-tag>
                    <el-tooltip
                      v-if="batchRows.length > 1"
                      :content="t('aiGenerate.removeRowAria', { n: idx + 1 })"
                      placement="top"
                    >
                      <el-button
                        link
                        type="danger"
                        :aria-label="t('aiGenerate.removeRowAria', { n: idx + 1 })"
                        data-test="ai-gen-batch-remove"
                        @click="removeBatchRow(idx)"
                      >
                        <el-icon><Delete /></el-icon>
                      </el-button>
                    </el-tooltip>
                  </div>
                </div>
              </div>
            </el-form-item>
          </el-form>

          <div v-if="mode === 'single'" class="budget-tip" data-test="ai-gen-budget-tip">
            <el-icon><InfoFilled /></el-icon>
            <div class="budget-tip-body">
              <div v-if="autoEnumerate === 1" class="budget-tip-title" data-test="ai-gen-single-auto-hint">
                {{ t('aiGenerate.autoEnumHintOnSingle') }}
              </div>
              <div class="budget-tip-title">{{ t('aiGenerate.budgetTipTitle') }}</div>
              <ul>
                <li>{{ t('aiGenerate.budgetLi1a') }}<strong>{{ t('aiGenerate.budgetLi1b') }}</strong>{{ t('aiGenerate.budgetLi1c') }}</li>
                <li>{{ t('aiGenerate.budgetLi2') }}</li>
                <li>{{ t('aiGenerate.budgetLi3') }}</li>
              </ul>
            </div>
          </div>

          <div v-if="mode === 'single'" class="form-actions">
            <el-tooltip
              :content="t('components.ownerEcho.pickInHeader')"
              placement="bottom"
              :disabled="!!projectId"
            >
              <span>
                <el-button
                  type="primary"
                  size="large"
                  :loading="submitting"
                  :disabled="!projectId || !form.requirement.trim()"
                  data-test="ai-gen-submit"
                  @click="submit"
                >
                  <el-icon v-if="!submitting"><Cpu /></el-icon>{{ t('aiGenerate.startBtn') }}
                </el-button>
              </span>
            </el-tooltip>
          </div>
          <div v-else class="form-actions">
            <div class="batch-submit-tip">
              <el-icon><InfoFilled /></el-icon>
              {{ t('aiGenerate.batchSubmitTip', { max: maxBatchRows }) }}
            </div>
            <!-- Default full-enumeration discoverability line: states what a plain submit
                 does with the scenario kinds, and where the toggle lives when off. -->
            <div v-if="filledBatchCount > 0 && !previewActive" class="batch-auto-enum-hint" data-test="ai-gen-auto-enum-hint">
              <el-icon><InfoFilled /></el-icon>
              <span v-if="previewRunning" data-test="ai-gen-auto-enum-running">{{ t('aiGenerate.autoEnumRunning') }}</span>
              <span v-else>{{ autoEnumerate === 1 ? t('aiGenerate.autoEnumHintOn') : t('aiGenerate.autoEnumHintOff') }}</span>
            </div>
            <el-button
              :loading="previewRunning"
              :disabled="!projectId || filledBatchCount === 0"
              data-test="ai-gen-preview-start"
              @click="startScenarioPreview"
            >
              {{ t('aiGenerate.previewStartBtn') }}
            </el-button>
            <el-button
              type="primary"
              size="large"
              :loading="batchSubmitting"
              :disabled="!projectId || filledBatchCount === 0 || (previewActive && expandedPreviewItems.length === 0)"
              data-test="ai-gen-batch-submit"
              @click="submitBatch"
            >
              <el-icon v-if="!batchSubmitting"><Cpu /></el-icon>{{ t('aiGenerate.batchSubmitBtn') }}
            </el-button>
          </div>

          <!-- Circle-selection matrix: scenarios ticked per preview row (all selected by default, rows collapsed for large batches) -->
          <div v-if="previewActive" class="scenario-panel" data-test="ai-gen-scenario-panel">
            <div class="scenario-panel-head">
              <span class="scenario-panel-title" data-test="ai-gen-scenario-count">
                {{ t('aiGenerate.previewPanelTitle', { selected: scenarioSelectedCount, total: scenarioTotalCount }) }}
              </span>
              <span class="scenario-panel-actions">
                <el-button link type="primary" data-test="ai-gen-scenario-all" @click="setAllSelection(true)">
                  {{ t('aiGenerate.previewSelectAll') }}
                </el-button>
                <el-button link data-test="ai-gen-scenario-none" @click="setAllSelection(false)">
                  {{ t('aiGenerate.previewClearAll') }}
                </el-button>
              </span>
            </div>
            <div class="scenario-scroll">
            <el-collapse>
              <el-collapse-item
                v-for="pr in previewRows"
                :key="pr.id"
                :name="pr.id"
                :data-test="`ai-gen-preview-row-${pr.seq}`"
              >
                <template #title>
                  <div class="scenario-row-title">
                    <span class="scenario-row-text" :title="pr.requirementText">{{ pr.requirementText }}</span>
                    <el-tag
                      v-if="!pr.expansionEnabled || pr.scenarios.length === 0"
                      type="info"
                      size="small"
                    >
                      {{ t('aiGenerate.previewLegacyRowTag') }}
                    </el-tag>
                    <el-tag v-else-if="!pr.converged" type="warning" size="small" :data-test="`ai-gen-preview-unconverged-${pr.seq}`">
                      {{ t('aiGenerate.previewNotConverged') }}
                    </el-tag>
                    <span class="scenario-row-count">
                      {{ t('aiGenerate.previewRowCount', { selected: rowSelectedCount(pr), total: pr.scenarios.length }) }}
                    </span>
                  </div>
                </template>
                <div v-if="!pr.converged && (pr.residualClaims ?? []).length > 0" class="scenario-residual" data-test="ai-gen-preview-residual">
                  {{ t('aiGenerate.previewResidualLead') }}
                  <ul>
                    <li v-for="(claim, ci) in (pr.residualClaims ?? [])" :key="ci">{{ claim }}</li>
                  </ul>
                </div>
                <div class="scenario-row-toolbar">
                  <el-button link size="small" @click="setRowSelection(pr, true)">{{ t('aiGenerate.previewSelectAll') }}</el-button>
                  <el-button link size="small" @click="setRowSelection(pr, false)">{{ t('aiGenerate.previewClearAll') }}</el-button>
                </div>
                <el-checkbox
                  v-for="(sc, si) in pr.scenarios"
                  :key="si"
                  class="scenario-check"
                  :model-value="selectedKeys.has(scenarioSelectionKey(pr.id, si))"
                  :data-test="`ai-gen-scenario-${pr.seq}-${si}`"
                  @change="(v: unknown) => toggleScenario(pr.id, si, Boolean(v))"
                >
                  <span class="scenario-kind">{{ kindWord(sc.kind) }}</span>
                  <span class="scenario-title">{{ sc.title }}</span>
                  <span v-if="sc.anchor" class="scenario-anchor">{{ t('aiGenerate.previewBasisPrefix') }}{{ sc.anchor }}</span>
                </el-checkbox>
              </el-collapse-item>
            </el-collapse>
            </div>
          </div>
        </div>
      </el-col>

      
      <el-col :xs="24" :lg="10">
        <div class="page-card ai-gen-right-card" :class="mode === 'single' ? 'is-single' : 'is-batch'" :data-test="mode === 'single' ? 'ai-gen-task-panel' : 'ai-gen-batch-panel'">
          <div class="batch-head-row" data-test="ai-gen-batch-head-row">
            <div class="section-title">{{ mode === 'single' ? t('aiGenerate.sectionTaskProgress') : t('aiGenerate.sectionBatchProgress') }}</div>
            <!-- 11-20 detail layout pass: the settled-state overview rides the SAME line as
                 the section title (it was its own band above the progress card) — one
                 band less of chrome height, the queue gets the freed rows. Anchors
                 (ai-gen-batch-progress-count / cancelled-count) move with it. -->
            <div v-if="mode === 'batch' && batch" class="batch-progress-head">
              <span class="batch-progress-count" data-test="ai-gen-batch-progress-count">
                {{ t('aiGenerate.doneProgress', { done: batch.done + batch.failed + batch.cancelled, total: batch.total }) }}
              </span>
              <span class="batch-progress-sub">
                {{ t('aiGenerate.subProgress', { ok: batch.done, fail: batch.failed, pending: batch.pending + batch.running }) }}
                <span v-if="batch.cancelled > 0" class="batch-cancelled-count" data-test="ai-gen-batch-cancelled-count">{{ t('aiGenerate.cancelledCount', { n: batch.cancelled }) }}</span>
              </span>
            </div>
            <!-- Stalled marker: progress re-reads keep failing (an expired
                 sign-in being the proven cause) and the numbers above are
                 frozen; say so with the repair entry instead of waiting
                 silently. -->
            <span v-if="mode === 'batch' && batchReadStalled" class="batch-poll-stalled" data-test="ai-gen-poll-stalled">
              {{ t('aiGenerate.pollStalledTip', { n: batchPollFailures }) }}
            </span>
          </div>
          <template v-if="mode === 'single'">
          <template v-if="task">
            <div class="task-card" :class="`task-${task.status}`">
              <div class="task-header">
                <el-tag :type="AI_TASK_STATUS[task.status]?.type ?? 'info'" data-test="ai-gen-task-status">
                  {{ AI_TASK_STATUS[task.status]?.label ?? task.status }}
                </el-tag>
                <el-tag v-if="task.taskType === 2" type="info">{{ t('aiGenerate.tagScript') }}</el-tag>
                <el-tag v-else type="info">{{ t('aiGenerate.tagCase') }}</el-tag>
                <span class="task-id">{{ t('aiGenerate.taskNo', { id: task.id }) }}</span>
              </div>

              <div class="task-body">
                
                <template v-if="task.status === 1">
                  <div class="task-pending">
                    <el-icon class="spin-icon" :size="30"><Loading /></el-icon>
                    <div class="task-text">{{ t('aiGenerate.pendingText') }}</div>
                    <div class="task-sub">{{ t('aiGenerate.pendingSub') }}</div>
                  </div>
                </template>

                
                <template v-else-if="task.status === 2">
                  <div class="task-running">
                    <el-icon class="spin-icon" :size="30"><Loading /></el-icon>
                    <div class="task-text">{{ t('aiGenerate.runningText') }}</div>
                    <el-progress :percentage="66" :indeterminate="true" :duration="2" :show-text="false" class="task-progress" />
                    <div class="task-sub">{{ t('aiGenerate.runningSub') }}</div>
                    <el-button
                      type="danger"
                      plain
                      size="small"
                      class="task-stop"
                      :loading="stoppingTask"
                      data-test="ai-gen-stop-task"
                      @click="stopCurrentTask"
                    >{{ t('aiGenerate.stopTaskBtn') }}</el-button>
                  </div>
                </template>

                <!-- Stopped by a human: settled state, own branch (cancel door) -->
                <template v-else-if="task.status === 5">
                  <div class="task-cancelled">
                    <el-icon :size="30" color="#94a3b8"><CircleCloseFilled /></el-icon>
                    <div class="task-text">{{ t('aiGenerate.stoppedText') }}</div>
                    <!-- 11-20 report: a stopped single task got stuck with no door back —
                         same restart door as failed retry (backend now accepts cancelled). -->
                    <el-button
                      type="primary"
                      plain
                      :loading="retrying"
                      data-test="ai-gen-restart"
                      @click="retryTask"
                    >
                      <el-icon><RefreshRight /></el-icon>{{ t('aiGenerate.restartShort') }}
                    </el-button>
                  </div>
                </template>

                
                <template v-else-if="task.status === 3">
                  <div class="task-done">
                    <el-icon :size="34" color="#34d399"><CircleCheckFilled /></el-icon>
                    <div class="task-text">{{ t('aiGenerate.doneText') }}</div>
                    <div v-if="task.caseId && caseProbe !== 'blocked'" class="task-link">
                      <el-button
                        type="primary"
                        link
                        :loading="caseProbe === 'checking'"
                        data-test="ai-gen-open-case"
                        @click="router.push({ path: `/cases/${task.caseId}`, query: { projectId: String(task.projectId ?? '') } })"
                      >
                        {{ t('aiGenerate.openCaseBtn') }}
                      </el-button>
 
                      <el-button
                        v-if="fromMatrixLink"
                        type="primary"
                        link
                        data-test="ai-gen-back-matrix"
                        @click="router.push({ path: '/coverage', query: { projectId: String(task.projectId) } })"
                      >
                        {{ t('aiGenerate.backMatrixBtn') }}
                      </el-button>
                    </div>
                    <div v-else-if="task.caseId" class="task-missing" data-test="ai-gen-case-unreachable">
                      <div class="task-missing-msg">{{ caseProbeMessage || t('aiGenerate.caseUnavailable') }}</div>
                      <el-button
                        type="primary"
                        plain
                        data-test="ai-gen-goto-library"
                        @click="router.push(caseLibraryRoute(task.projectId ?? ''))"
                      >
                        {{ t('caseDetail.gotoCaseLibrary') }}
                      </el-button>
                    </div>
                    <div class="task-sub">
                      {{ t('aiGenerate.taskNo', { id: task.id }) }} · {{ task.retryCnt > 0 ? t('aiGenerate.afterRetries', { n: task.retryCnt }) : t('aiGenerate.firstPass') }}
                    </div>
                  </div>
                </template>

                
                <template v-else>
                  <div class="task-failed">
                    <el-icon :size="34" color="#f87171"><CircleCloseFilled /></el-icon>
                    <div class="task-text">{{ t('aiGenerate.failText') }}</div>
                    <div class="task-error" data-test="ai-gen-task-error">{{ task.errorMsg || t('aiGenerate.unknownError') }}</div>
                    <div class="task-sub">
                      {{ task.errorCode ? t('aiGenerate.errCodePrefix', { code: task.errorCode }) : '' }}{{ t('aiGenerate.retriedTimes', { n: task.retryCnt }) }}
                    </div>
                    <el-button
                      type="primary" plain
                      :loading="retrying"
                      data-test="ai-gen-retry"
                      @click="retryTask"
                    >
                      <el-icon><RefreshRight /></el-icon>{{ t('aiGenerate.retryBtn') }}
                    </el-button>
                  </div>
                </template>
              </div>
            </div>
          </template>
          <el-empty v-else :description="t('aiGenerate.emptySingleDesc')" :image-size="80">
            <template v-if="projectId && form.requirement.trim()">
              <div class="task-sub" data-test="ai-gen-empty-auto-hint">
                {{ autoEnumerate === 1 ? t('aiGenerate.autoEnumHintOnSingle') : t('aiGenerate.autoEnumHintOffSingle') }}
              </div>
              <el-button type="primary" plain @click="submit">
                {{ t('aiGenerate.submitNowBtn') }}
              </el-button>
            </template>
          </el-empty>
          </template>

          
          <template v-else>
            <template v-if="batch">
              <div class="batch-progress-card">
                
                <el-progress
                  :percentage="batchProgressPercent"
                  :status="batchProgressStatus"
                  class="batch-progress-bar"
                />
                <!-- Server-assembled waiting notice (reason / backlog / recovery
                     estimate); rendered verbatim, no local copy of the sentence. -->
                <div
                  v-if="batch.queueNotice"
                  class="batch-queue-notice"
                  data-test="ai-gen-queue-notice"
                  role="status"
                >
                  <el-icon><Loading /></el-icon>
                  <span>{{ batch.queueNotice }}</span>
                </div>
                <div v-if="batch.failed > 0 && batch.done === 0" class="batch-unavailable" data-test="ai-gen-batch-unavailable">
                  <el-icon><WarningFilled /></el-icon>
                  {{ t('aiGenerate.unavailableTip') }}
                </div>
                <div v-else-if="batch.failed > 0" class="batch-partial-fail">
                  <el-icon><WarningFilled /></el-icon>
                  {{ t('aiGenerate.partialFailTip') }}
                </div>
                <div class="batch-item-list" data-test="ai-gen-batch-items">
                  <div v-for="(item, idx) in batch.items" :key="item.taskId" class="batch-item" :data-test="`ai-gen-batch-item-${idx}`">
                    <span class="batch-item-no">{{ idx + 1 }}</span>
                    <div class="batch-item-main">
                      <div class="batch-item-title" :title="item.requirementText">
                        {{ item.requirementText }}
                      </div>
                      <div class="batch-item-status">
                        <el-tag v-if="item.status === 1" size="small" type="info">{{ t('aiGenerate.stQueued') }}</el-tag>
                        <el-tag v-else-if="item.status === 2" size="small" type="warning">{{ t('aiGenerate.stRunning') }}</el-tag>
                        <el-tag v-else-if="item.status === 3" size="small" type="success">{{ t('aiGenerate.stDone') }}</el-tag>
                        <el-tag v-else-if="item.status === 5" size="small" type="info">{{ t('aiGenerate.stCancelled') }}</el-tag>
                        <el-tag v-else size="small" type="danger">{{ t('aiGenerate.stFailed') }}</el-tag>
                        <span v-if="item.status === 4 && item.errorMsg" class="batch-item-error" :title="item.errorMsg">
                          {{ item.errorMsg }}
                        </span>
                      </div>
                    </div>
                    <el-button
                      v-if="item.status === 3 && item.caseId"
                      type="primary"
                      link
                      size="small"
                      :data-test="`ai-gen-batch-open-${idx}`"
                      @click="router.push(`/cases/${item.caseId}`)"
                    >
                      {{ t('aiGenerate.openCaseShort') }}
                    </el-button>
                    <el-button
                      v-else-if="item.status === 4"
                      link
                      size="small"
                      :loading="retryingIds.includes(item.taskId)"
                      data-test="ai-gen-batch-retry-one"
                      @click="retryBatchItem(item.taskId)"
                    >
                      {{ t('aiGenerate.retryShort') }}
                    </el-button>
                    <el-button
                      v-else-if="item.status === 2"
                      link
                      size="small"
                      type="danger"
                      :loading="stoppingIds.includes(item.taskId)"
                      data-test="ai-gen-batch-stop-one"
                      @click="stopBatchItem(item.taskId)"
                    >
                      {{ t('aiGenerate.stopShort') }}
                    </el-button>
                    <el-button
                      v-else-if="item.status === 5"
                      link
                      size="small"
                      type="primary"
                      :loading="retryingIds.includes(item.taskId)"
                      data-test="ai-gen-batch-restart-one"
                      @click="retryBatchItem(item.taskId)"
                    >
                      {{ t('aiGenerate.restartShort') }}
                    </el-button>
                  </div>
                </div>
                <!-- P4: one aggregated tail of every sub-task's ledger on
 the shared drawer (same component as extraction / steps). -->
                <TaskTailDrawer
                  v-if="batch"
                  :events="batchTailEvents"
                  :live="batchTailLive"
                  :expired="batchTailExpired"
                  retention-param="ai.stream.event-retention-days"
                  :can-stop="batchTailLive"
                  class="batch-tail-drawer"
                  @stop="stopRunningItems"
                />
                <div v-if="batch.failed > 0" class="batch-retry-failed">
                  <el-button
                    type="primary"
                    plain
                    :loading="retryFailedLoading"
                    data-test="ai-gen-batch-retry-failed"
                    @click="retryFailedItems"
                  >
                    <el-icon><RefreshRight /></el-icon>{{ t('aiGenerate.retryFailedBtn', { n: batch.failed }) }}
                  </el-button>
                  <span class="batch-retry-hint">{{ t('aiGenerate.retryFailedHint') }}</span>
                </div>
              </div>
            </template>
            <el-empty v-else :description="t('aiGenerate.emptyBatchDesc')" :image-size="80">
              <el-button
                v-if="projectId && filledBatchCount > 0"
                type="primary"
                plain
                @click="submitBatch"
              >
                {{ t('aiGenerate.modeBatch') }}
              </el-button>
            </el-empty>
          </template>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { bizErrorMsg } from '@/utils/bizError'
import {
  generateCases,
  retryAiTask,
  cancelAiTask,
  estimateBatchGenerate,
  batchGenerateCases,
  getAiBatchLimit,
  getLatestGeneration,
  runScenarioPreview,
  listScenarioPreviews,
  AI_TASK_STATUS,
} from '@/api/ai'
import { listRequirements, listUncoveredRequirements, type RequirementItem } from '@/api/requirement'
import { getCase } from '@/api/case'
import { caseLibraryRoute } from '@/utils/caseLoadFailure'
import { buildBatchPayload, parseRequirementIdsParam, rowsFromGapItems, type BatchRow, type AiBatchItemPayload } from '@/utils/aiBatchLinkage'
import {
  DEFAULT_SCENARIO_LABELS,
  buildPreviewRunPlan,
  enumeratePreviewPlan,
  defaultSelectedKeys,
  expandSelectedItems,
  newPreviewBatchId,
  requirementLinksByRowId,
  scenarioSelectionKey,
  type ScenarioPreviewRow,
} from '@/utils/aiScenarioSelection'
import { useAiTaskPolling } from '@/composables/useAiTaskPolling'
import { useAiBatchPolling } from '@/composables/useAiBatchPolling'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import ProjectOwnerEcho from '@/components/ProjectOwnerEcho.vue'
import TaskTailDrawer from '@/components/TaskTailDrawer.vue'
import { useGenBatchEventTail } from '@/composables/useGenBatchEventTail'

const { t } = useI18n()

const route = useRoute()
const router = useRouter()
// Header context is the single ownership source (T396b): URL priority chain
// (?projectId deep link from the coverage matrix / doc suggestions) → primaryId.
// The legacy in-page project selector retired — a create wizard must not keep a
// second channel around the global context (ownership-echo iron rule 1).
const { projectId } = useProjectPageContext()

const form = ref<{ requirement: string; requirementId?: number }>({
  requirement: '',
  requirementId: undefined,
})
const requirements = ref<RequirementItem[]>([])
/** Cross-page deep links carry a wanted requirement point (A3 loop) — prefill it once
 *  after the first requirement list lands; later context switches must not re-apply it. */
const prefillRequirementId = Number(route.query.requirementId)
let requirementPrefillDone = false
const submitting = ref(false)
const retrying = ref(false)


const fromMatrixLink = computed(() => {
  const rid = Number(route.query.requirementId)
  return Number.isFinite(rid) && rid > 0
})

const taskIdRef = ref<number | null>(null)
const { task, startPolling } = useAiTaskPolling(taskIdRef)

const taskFinished = computed(() => task.value !== null && (task.value.status === 3 || task.value.status === 4))

/** Whether the case id carried by a finished task still opens. A cleaned sentinel used to
 *  produce a direct button that bounced the user into a 404 with a recycle-bin instruction;
 *  the door is now offered only after the existing case read confirms the row (no new channel). */
const caseProbe = ref<'idle' | 'checking' | 'openable' | 'blocked'>('idle')
const caseProbeMessage = ref<string | null>(null)

async function probeGeneratedCase(id: number): Promise<void> {
  caseProbe.value = 'checking'
  try {
    await getCase(id)
    caseProbe.value = 'openable'
    caseProbeMessage.value = null
  } catch (e) {
    caseProbe.value = 'blocked'
    caseProbeMessage.value = (e as Error).message || null
  }
}

watch(
  () => [task.value?.status, task.value?.caseId] as const,
  ([status, caseId]) => {
    if (status === 3 && typeof caseId === 'number' && caseId > 0) void probeGeneratedCase(caseId)
    else if (caseProbe.value !== 'idle') caseProbe.value = 'idle'
  },
)


const maxBatchRows = ref(500)
const mode = ref<'single' | 'batch'>('single')
const batchRows = ref<BatchRow[]>([{ text: '' }])
const batchSubmitting = ref(false)
const batchIdRef = ref<number | null>(null)
const { batch, pollFailures: batchPollFailures, readStalled: batchReadStalled, startPolling: startBatchPolling } = useAiBatchPolling(batchIdRef)
// Aggregated live tail of the batch's sub-task ledgers .
const { events: batchTailEvents, live: batchTailLive, expired: batchTailExpired, assessExpiry: assessBatchTailExpiry } = useGenBatchEventTail(batch)
// R67 revisit door: once the batch settles, the still-in-flight window read
// gets a beat to land; an empty ledger with frames behind it swaps the
// drawer to the retention pointer instead of a blank box.
watch(batchTailLive, (isLive) => {
  if (isLive) return
  window.setTimeout(() => void assessBatchTailExpiry(), 1500)
}, { immediate: true })

const retryingIds = ref<number[]>([])
const retryFailedLoading = ref(false)


const filledBatchCount = computed(
  () => batchRows.value.filter((r) => r.text.trim().length > 0).length,
)

// ---------- one-click case-gap handoff ----------
/** Count of this project's requirements with no covering live case, computed
 *  server-side (same coverage wording as the matrix — single source). The list
 *  page feeds the batch entry; the human gates after submit stay untouched. */
const GAP_FETCH_SIZE = 200
const gapCount = ref(0)
const gapItems = ref<RequirementItem[]>([])

async function loadCaseGap(): Promise<void> {
  gapCount.value = 0
  gapItems.value = []
  if (!projectId.value) return
  try {
    const res = await listUncoveredRequirements(projectId.value, 1, GAP_FETCH_SIZE)
    gapItems.value = res.list || []
    gapCount.value = res.total ?? gapItems.value.length
  } catch {
    gapCount.value = 0
    gapItems.value = []
  }
}

/** Fill the existing batch-creation rows with the gap requirements (title plus
 *  acceptance criteria when present — the same text channel as manual rows).
 *  User-typed rows are replaced only after an explicit confirm. */
async function applyGapPrefill(): Promise<void> {
  if (gapItems.value.length === 0) return
  if (filledBatchCount.value > 0) {
    try {
      await ElMessageBox.confirm(
        t('aiGenerate.gapReplaceTip', { n: filledBatchCount.value }),
        t('aiGenerate.gapReplaceTitle'),
        { type: 'warning' },
      )
    } catch {
      return
    }
  }
  const capped = gapItems.value.slice(0, maxBatchRows.value)
  batchRows.value = rowsFromGapItems(capped, t('aiGenerate.gapAcPrefix'))
  mode.value = 'batch'
  ElMessage.success(t('aiGenerate.gapFilled', { n: capped.length }))
}

/** Progress percent: settled = done + failed + cancelled over total (stopped items are settled, not running). */
const batchProgressPercent = computed(() => {
  const b = batch.value
  if (!b || b.total === 0) return 0
  return Math.round(((b.done + b.failed + b.cancelled) / b.total) * 100)
})

const batchProgressStatus = computed(() => {
  const b = batch.value
  if (!b) return undefined
  if (b.failed > 0 && b.done === 0) return 'exception'
  if (b.done + b.failed + b.cancelled >= b.total) return 'success'
  return undefined
})


function onModeChange(): void {
  // View-only switch; taskIdRef / batchIdRef keep their server handles.
}


function batchIsLive(b: { pending: number; running: number } | null): boolean {
  return !!b && b.pending + b.running > 0
}


function taskIsLive(t: { status: number } | null): boolean {
  return !!t && (t.status === 1 || t.status === 2)
}

/**
 * Re-attach the generation queue after the user leaves and comes back:
 * one GET /ai/generation/latest per project segment, then hand the ids to the
 * same pollers the submit path uses. Live rows pick the visible mode; when
 * everything already settled, the newer ledger row wins so the panel shows
 * an honest last state instead of the blank start page. Recovery failure
 * lands as the empty panel with the network error surfaced by the request
 * layer — never a locally cached ghost.
 */
async function recoverGeneration(): Promise<void> {
  const pid = projectId.value
  if (!pid) return
  try {
    const latest = await getLatestGeneration(pid)
    const b = latest.batch
    const t = latest.task
    if (b) batchIdRef.value = b.id
    if (t) taskIdRef.value = t.id
    if (batchIsLive(b)) mode.value = 'batch'
    else if (taskIsLive(t)) mode.value = 'single'
    else if (b && t) mode.value = (b.createdAt ?? '') >= (t.createdAt ?? '') ? 'batch' : 'single'
    else if (b) mode.value = 'batch'
    if (b) await startBatchPolling()
    if (t) await startPolling()
  } catch {
    // The request layer already shows the failure; the panel stays honestly empty.
  }
}


watch([taskIdRef, batchIdRef], ([t, b]) => {
  const q = { ...route.query } as Record<string, string | string[] | undefined>
  if (t) q.task = String(t)
  else delete q.task
  if (b) q.batchId = String(b)
  else delete q.batchId
  void router.replace({ query: q })
})

function addBatchRow(): void {
  if (batchRows.value.length >= maxBatchRows.value) {
    ElMessage.warning(t('aiGenerate.rowLimitWarn', { max: maxBatchRows.value }))
    return
  }
  batchRows.value.push({ text: '' })
}

function removeBatchRow(idx: number): void {
  batchRows.value.splice(idx, 1)
  if (batchRows.value.length === 0) {
    batchRows.value.push({ text: '' })
  }
}

// ---------- stage0 scenario preview circle-selection (enumerate draft scenarios first, generate one task per kept scenario) ----------
/** true while the enumeration POSTs run line by line (synchronous chain, the spinner is the progress) */
const previewRunning = ref(false)
/** Batch default-full-enumeration flag (K8 key echoed by /ai/batch/limit; shipped default on). */
const autoEnumerate = ref(1)
/** preview group id of this round (client-side millisecond key, see the aiScenarioSelection module note) */
const previewBatchId = ref<number | null>(null)
/** preview rows read back: the server list is the single source of the matrix */
const previewRows = ref<ScenarioPreviewRow[]>([])
/** ticked scenario keys (default = everything selected, the operator only trims) */
const selectedKeys = ref<Set<string>>(new Set())
/** filled (non-blank) rows in fill order: shared basis for the run plan and the link re-attach */
const filledRows = computed(() => batchRows.value.filter((r) => r.text.trim().length > 0))
const previewActive = computed(() => previewRows.value.length > 0)
const previewLinks = computed(() => requirementLinksByRowId(filledRows.value, previewRows.value))
/** expanded submit items (N->N: one task per ticked scenario; rows with no tick contribute nothing) */
const expandedPreviewItems = computed<AiBatchItemPayload[]>(() =>
  previewActive.value
    ? expandSelectedItems(previewRows.value, selectedKeys.value, DEFAULT_SCENARIO_LABELS, previewLinks.value)
    : [],
)
const scenarioTotalCount = computed(() =>
  previewRows.value.reduce((n, r) => n + r.scenarios.length, 0),
)
const scenarioSelectedCount = computed(() => {
  let n = 0
  for (const r of previewRows.value) {
    r.scenarios.forEach((_, idx) => {
      if (selectedKeys.value.has(scenarioSelectionKey(r.id, idx))) n += 1
    })
  }
  return n
})

/** Row priority: the requirement view carries no priority column, so every line
 *  runs at the P1 middle — never invent a P0 to amplify the enumeration */
function priorityOf(_row: BatchRow): number {
  return 1
}

/**
 * Run the stage0 preview: enumerate row by row, read the group back, start fully
 * selected. Returns the honest outcome so the default submit path can branch:
 * expanded = matrix on screen (fully selected), off = enumeration switch disabled
 * (legacy whole-row shape is the documented behavior), aborted = the enumeration
 * door answered nothing and rows failed (never masquerade, never silently legacy).
 */
async function startScenarioPreview(): Promise<'expanded' | 'off' | 'aborted'> {
  if (!projectId.value) {
    ElMessage.warning(t('aiGenerate.needProject'))
    return 'aborted'
  }
  const batchId = newPreviewBatchId()
  const plan = buildPreviewRunPlan(filledRows.value, {
    batchId,
    projectId: projectId.value,
    priorityOf,
  })
  if (plan.length === 0) {
    ElMessage.warning(t('aiGenerate.needOneReq'))
    return 'aborted'
  }
  previewRunning.value = true
  try {
    const outcome = await enumeratePreviewPlan(plan, runScenarioPreview)
    const rows = (await listScenarioPreviews(batchId)) as ScenarioPreviewRow[]
    if (rows.length === 0) {
      previewBatchId.value = null
      previewRows.value = []
      selectedKeys.value = new Set()
      if (outcome.failures.length > 0) {
        // nothing survived the ledger and rows failed: report plainly,
        // never masquerade as the switch-off shape
        ElMessage.error(t('aiGenerate.previewAllFailed', { n: outcome.failures.length, total: plan.length }))
        return 'aborted'
      }
      // enumeration switch is off: say so plainly and fall back to whole-row generation
      ElMessage.info(t('aiGenerate.previewSwitchOffTip'))
      return 'off'
    }
    previewBatchId.value = batchId
    previewRows.value = rows
    selectedKeys.value = defaultSelectedKeys(rows)
    if (outcome.failures.length > 0) {
      // partial enumeration: the panel still activates on the rows that
      // landed, and the failed count is stated plainly — never swallowed
      ElMessage.warning(t('aiGenerate.previewPartial', {
        done: outcome.completed,
        failed: outcome.failures.length,
        total: plan.length,
      }))
    } else {
      ElMessage.success(t('aiGenerate.previewDone', { n: rows.length }))
    }
    return 'expanded'
  } finally {
    previewRunning.value = false
  }
}

function toggleScenario(rowId: number, idx: number, checked: boolean): void {
  const next = new Set(selectedKeys.value)
  const key = scenarioSelectionKey(rowId, idx)
  if (checked) {
    next.add(key)
  } else {
    next.delete(key)
  }
  selectedKeys.value = next
}

/** keep or drop a whole row in at most two clicks */
function setRowSelection(row: ScenarioPreviewRow, checked: boolean): void {
  const next = new Set(selectedKeys.value)
  row.scenarios.forEach((_, idx) => {
    const key = scenarioSelectionKey(row.id, idx)
    if (checked) {
      next.add(key)
    } else {
      next.delete(key)
    }
  })
  selectedKeys.value = next
}

/** select all / clear across the matrix */
function setAllSelection(checked: boolean): void {
  selectedKeys.value = checked ? defaultSelectedKeys(previewRows.value) : new Set()
}

/** ticked count of one row (the collapsed header still reports the real number) */
function rowSelectedCount(row: ScenarioPreviewRow): number {
  let n = 0
  row.scenarios.forEach((_, idx) => {
    if (selectedKeys.value.has(scenarioSelectionKey(row.id, idx))) n += 1
  })
  return n
}

/** scenario kind word: single vocabulary = DEFAULT_SCENARIO_LABELS, no second table here */
function kindWord(kind: string): string {
  return DEFAULT_SCENARIO_LABELS.kindWords[kind] ?? kind
}

/** editing a source row invalidates the matrix at once (better the whole-row shape than a stale match) */
watch(
  () => batchRows.value.map((r) => r.text).join('\n'),
  () => {
    if (previewActive.value) {
      previewBatchId.value = null
      previewRows.value = []
      selectedKeys.value = new Set()
    }
  },
)


async function submitBatch(): Promise<void> {
  if (!projectId.value) {
    ElMessage.warning(t('aiGenerate.needProject'))
    return
  }
  const payload = buildBatchPayload(projectId.value, batchRows.value)
  if (payload.items.length === 0) {
    ElMessage.warning(t('aiGenerate.needOneReq'))
    return
  }
  if (previewActive.value && expandedPreviewItems.value.length === 0) {
    ElMessage.warning(t('aiGenerate.previewNoneSelected'))
    return
  }
  batchSubmitting.value = true
  try {
    // Default-full shape: a submit that never ran the circle-selection first
    // auto-enumerates the five scenario kinds and keeps everything selected,
    // so one requirement point becomes several cases by default. Key off =
    // the legacy whole-row submit; a dead enumeration door keeps the honest
    // promise of the existing failure copy — the submit continues row by row,
    // stated plainly inside the cost confirmation, never silently.
    let autoExpanded = false
    let autoEnumFailed = false
    if (!previewActive.value && autoEnumerate.value === 1) {
      const status = await startScenarioPreview()
      autoExpanded = status === 'expanded'
      autoEnumFailed = status === 'aborted'
    }
    // 1. cost estimate (pure calculation): the preview group adds the scenarios-x-rounds multipliers
    const est = await estimateBatchGenerate(
      previewActive.value ? { ...payload, previewBatchId: previewBatchId.value } : payload,
    )
    const multiplierLine =
      est.scenarioCount != null && est.deliberationRounds != null
        ? t('aiGenerate.confirmScenarioLine', { scenarios: est.scenarioCount, rounds: est.deliberationRounds })
        : ''
    // 2. the real submit payload is computed BEFORE the dialog: the cost
    //    confirmation must promise exactly the number of tasks that will run
    //    (server-side estimate.count carries raw rows, while a preview-active
    //    submit sends the expanded scenario items — reading est.count here
    //    promised fewer cases than were actually generated). Circle-selected
    //    submit sends the expanded items without previewBatchId (real task
    //    count, no double multiply). Key off = raw rows, legacy shape intact.
    const finalPayload = previewActive.value
      ? {
          projectId: projectId.value,
          requirements: expandedPreviewItems.value.map((item) => item.text),
          items: expandedPreviewItems.value,
        }
      : payload
    // 3. confirm dialog: count + estimated tokens + cost (multiplier line when previewing)
    await ElMessageBox.confirm(
      t('aiGenerate.confirmBodyA', {
        count: previewActive.value ? finalPayload.items.length : est.count,
        max: est.maxCount,
      }) +
        t('aiGenerate.confirmBodyB', { tokens: est.estimatedTokens.toLocaleString(), cost: est.estimatedCostYuan.toFixed(4) }) +
        multiplierLine +
        (autoExpanded ? t('aiGenerate.confirmAutoLine') : '') +
        (autoEnumFailed ? t('aiGenerate.confirmAutoFailedLine') : '') +
        t('aiGenerate.confirmBodyC'),
      t('aiGenerate.confirmTitle'),
      {
        confirmButtonText: t('aiGenerate.confirmOk'),
        cancelButtonText: t('aiGenerate.confirmCancel'),
        type: 'warning',
        dangerouslyUseHTMLString: true,
        customClass: 'batch-confirm-dialog',
      },
    )
    // 4. create after confirmation (idempotency key = projectId + requirement
    //    digests, so a double click can never burn the cost twice)
    const key = `batch:${projectId.value}:${finalPayload.requirements.map((r) => r.slice(0, 8)).join('|')}`
    const res = await batchGenerateCases(finalPayload, key)
    batchIdRef.value = res.batchId
    await startBatchPolling()
    ElMessage.success(t('aiGenerate.batchSubmitted', { id: res.batchId, n: finalPayload.items.length }))
  } catch (err) {
    if (err instanceof Error && err.message === 'cancel') {
      return
    }
    throw err
  } finally {
    batchSubmitting.value = false
  }
}

/** Retry one failed item (only that task goes back to pending and re-queues). */
async function retryBatchItem(taskId: number): Promise<void> {
  if (retryingIds.value.includes(taskId)) return
  retryingIds.value = [...retryingIds.value, taskId]
  try {
    await retryAiTask(taskId)
    ElMessage.success(t('aiGenerate.itemRetried'))
    await startBatchPolling()
  } finally {
    retryingIds.value = retryingIds.value.filter((id) => id !== taskId)
  }
}

const stoppingTask = ref(false)
const stoppingIds = ref<number[]>([])

/** Stop the single live task behind the task card (cancel door; no second confirm —
 *  stopping writes no case data). The poller settles on status 5 after the call. */
async function stopCurrentTask(): Promise<void> {
  if (!task.value || stoppingTask.value) return
  stoppingTask.value = true
  try {
    await cancelAiTask(task.value.id)
    ElMessage.success(t('aiGenerate.stopOk'))
    await startPolling()
  } catch (e: unknown) {
    ElMessage.error(bizErrorMsg(e, t('aiGenerate.stopFail')))
  } finally {
    stoppingTask.value = false
  }
}

/** Stop one running item inside the batch list (per-row door, one click). */
async function stopBatchItem(taskId: number): Promise<void> {
  if (stoppingIds.value.includes(taskId)) return
  stoppingIds.value = [...stoppingIds.value, taskId]
  try {
    await cancelAiTask(taskId)
    ElMessage.success(t('aiGenerate.stopOk'))
    await startBatchPolling()
  } catch (e: unknown) {
    ElMessage.error(bizErrorMsg(e, t('aiGenerate.stopFail')))
  } finally {
    stoppingIds.value = stoppingIds.value.filter((id) => id !== taskId)
  }
}

/** Batch drawer stop door: one confirm names how many running items will stop, then
 *  each row is cancelled through the single-source door; partial failures are counted
 *  honestly instead of pretending everything stopped. */
async function stopRunningItems(): Promise<void> {
  const b = batch.value
  if (!b) return
  const runningIds = b.items.filter((it) => it.status === 2).map((it) => it.taskId)
  if (runningIds.length === 0) return
  try {
    await ElMessageBox.confirm(
      t('aiGenerate.stopBatchConfirmText', { n: runningIds.length }),
      t('aiGenerate.stopBatchConfirmTitle'),
      { type: 'warning' },
    )
  } catch {
    return
  }
  let stopped = 0
  let failed = 0
  for (const id of runningIds) {
    try {
      await cancelAiTask(id)
      stopped += 1
    } catch {
      failed += 1
    }
  }
  if (failed === 0) ElMessage.success(t('aiGenerate.stopOk'))
  else ElMessage.error(t('aiGenerate.stopPartialFail', { ok: stopped, fail: failed }))
  await startBatchPolling()
}


async function retryFailedItems(): Promise<void> {
  const b = batch.value
  if (!b || b.failed === 0) return
  retryFailedLoading.value = true
  try {
    const failedIds = b.items.filter((it) => it.status === 4).map((it) => it.taskId)
    for (const taskId of failedIds) {
      await retryAiTask(taskId)
    }
    ElMessage.success(t('aiGenerate.failedRetried', { n: failedIds.length }))
    await startBatchPolling()
  } finally {
    retryFailedLoading.value = false
  }
}


/** Load the active requirement points of the current context project (A3 prefill applies once). */
async function loadRequirements(): Promise<void> {
  form.value.requirementId = undefined
  requirements.value = []
  if (!projectId.value) return
  try {
    const res = await listRequirements({ projectId: projectId.value, status: 1, size: 100 })
    requirements.value = res.list || []
    if (!requirementPrefillDone && Number.isFinite(prefillRequirementId) && prefillRequirementId > 0) {
      requirementPrefillDone = true
      if (requirements.value.some((r) => r.id === prefillRequirementId)) {
        form.value.requirementId = prefillRequirementId
      }
    }
  } catch {
    requirements.value = []
  }
}

// Context switches (header change or deep-link landing) reload the requirement list.
// : an old project's handles must not linger under a new context — the panel
// re-attaches to the new segment right away (handle-less recovery only; deep-link
// handles are applied in onMounted per explicit user intent).
watch(projectId, () => {
  void loadRequirements()
  void loadCaseGap()
  if (!route.query.batchId) {
    batchIdRef.value = null
    batch.value = null
  }
  if (!route.query.task) {
    taskIdRef.value = null
    task.value = null
  }
  void recoverGeneration()
})

/**
 * Single-line submit (idempotency key stays projectId + requirement digest).
 * With the default-enumeration switch on (shipped), a
 * single requirement rides the SAME door as the batch path: the text lands as
 * one batch row, the batch submit auto-enumerates the five scenario kinds and
 * goes out fully selected — one requirement point becomes several cases by
 * default. The legacy one-task-one-case shape stays only behind the switch off
 * (documented escape hatch, zero drift).
 */
async function submit(): Promise<void> {
  if (!projectId.value || !form.value.requirement.trim()) {
    ElMessage.warning(t('aiGenerate.needProjectAndReq'))
    return
  }
  if (autoEnumerate.value === 1) {
    const text = form.value.requirement.trim()
    const linked = form.value.requirementId ? Number(form.value.requirementId) : undefined
    batchRows.value = linked ? [{ text, requirementId: linked }] : [{ text }]
    mode.value = 'batch'
    await submitBatch()
    return
  }
  submitting.value = true
  try {
    const key = `gen:${projectId.value}:${form.value.requirement.trim().slice(0, 64)}`
    const res = await generateCases(
      {
        projectId: projectId.value,
        requirement: form.value.requirement.trim(),
        requirementId: form.value.requirementId,
      },
      key,
    )
    taskIdRef.value = res.taskId
    await startPolling()
    ElMessage.success(t('aiGenerate.taskSubmitted', { id: res.taskId }))
  } finally {
    submitting.value = false
  }
}


async function retryTask(): Promise<void> {
  if (!task.value) return
  retrying.value = true
  try {
    await retryAiTask(task.value.id)
    ElMessage.success(t('aiGenerate.taskRetried'))
    taskIdRef.value = task.value.id
    task.value = null
    await startPolling()
  } finally {
    retrying.value = false
  }
}

onMounted(async () => {
  getAiBatchLimit()
    .then((r) => {
      if (r && Number.isFinite(r.maxCount) && r.maxCount > 0) maxBatchRows.value = r.maxCount
      // Absent echo (older core) keeps the shipped default of on.
      if (r && r.autoEnumerate === 0) autoEnumerate.value = 0
    })
    .catch(() => undefined)
  const qTask = route.query.task
  if (qTask) {
    const id = Number(qTask)
    if (Number.isFinite(id) && id > 0) {
      taskIdRef.value = id
      await startPolling()
    }
  }
  const qBatch = Number(route.query.batchId)
  if (Number.isFinite(qBatch) && qBatch > 0) {
    batchIdRef.value = qBatch
    mode.value = 'batch'
    await startBatchPolling()
  }
  // A3 loop (coverage-matrix deep link): the composable already resolved ?projectId into the
  // header context (rule 1 of the priority chain); the requirement text still
  // lands on this page. The requirement-point prefill applies in loadRequirements
  // once its list arrives (the list fetch cannot race a bare watch here).
  const qRequirement = route.query.requirement
  if (qRequirement) {
    form.value.requirement = String(qRequirement)
  }
  // Initial pull for whatever projectId the context resolved at setup time
  // (watch(projectId) only covers later switches).
  await loadRequirements()
  void loadCaseGap()
  if (!route.query.task && !route.query.batchId) {
    await recoverGeneration()
  }
  // Deep-link hand-off (?requirementIds=1,2,3 from the requirement list or the
  // extraction confirm): the named points land as batch rows through the same
  // text channel as the gap fill. Rows gone from this project segment are
  // reported by honest count — never silently faked into the batch.
  const handoffIds = parseRequirementIdsParam(route.query.requirementIds)
  if (handoffIds.length > 0 && projectId.value) {
    try {
      const res = await listRequirements({ projectId: projectId.value, ids: handoffIds, size: handoffIds.length })
      const landed = res.list || []
      if (landed.length > 0) {
        batchRows.value = rowsFromGapItems(landed, t('aiGenerate.gapAcPrefix'))
        mode.value = 'batch'
        if (landed.length < handoffIds.length) {
          ElMessage.warning(t('aiGenerate.reqIdsMissing', { n: handoffIds.length - landed.length }))
        } else {
          ElMessage.success(t('aiGenerate.reqIdsFilled', { n: landed.length }))
        }
      } else {
        ElMessage.warning(t('aiGenerate.reqIdsMissing', { n: handoffIds.length }))
      }
    } catch {
      ElMessage.warning(t('aiGenerate.reqIdsLoadFail'))
    }
  }
})
</script>

<style scoped lang="scss">
/* height contract (11-20 R65 adoption): the page rides the theme fill
 block (root stretches to the working area, bottom gutter stays the 12px
 single source) instead of its own vh calc — the token formula left a 36px
 band below the fold and re-implemented what the theme now owns. Each
 region still flows inside its own scroll; the old fixed 440/320px caps
 stay retired. */
.ai-gen-root {
  min-height: 480px;
}

.ai-gen-row {
  flex: 1 1 0;
  min-height: 0;

  > .el-col {
    height: 100%;
    min-height: 0;
    display: flex;
    flex-direction: column;
  }
}

.ai-gen-root .page-card {
  height: 100%;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

/* One line for the section title AND the batch outcome summary (11-20 detail layout pass): the summary used to be its own band above the progress
   card — merged here to buy back vertical space for the queue. */
.batch-head-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 4px 16px;
  margin-bottom: 16px; /* keeps the band spacing the old title margin gave */

  .section-title {
    margin-bottom: 0;
  }
}

/* Left card (batch): the rows region takes all remaining height and scrolls
   inside; everything else (mode tabs, submit row) stays fixed. */
.ai-gen-form--batch {
  flex: 1 1 55%;
  min-height: 0;
  display: flex;
  flex-direction: column;

  > .el-form-item:last-child {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
    margin-bottom: 0;
  }
}

.ai-gen-form--batch > .el-form-item:last-child :deep(.el-form-item__content) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

/* Left card (single): the whole form scrolls when the viewport band is short. */
.ai-gen-form--single {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.section-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  font-weight: 600;
  margin-bottom: 16px;

  .title-icon {
    color: var(--tp-primary);
  }
}

.field-hint {
  font-size: 12px;
  color: var(--tp-text-secondary);
  margin-top: 4px;
}

.budget-tip {
  display: flex;
  gap: 10px;
  padding: 12px 14px;
  border-radius: 10px;
  background: var(--tp-primary-weak);
  border: 1px solid var(--tp-border);
  color: var(--tp-text-secondary);
  margin-bottom: 16px;

  > .el-icon {
    margin-top: 2px;
    color: var(--tp-primary);
    flex-shrink: 0;
  }

  .budget-tip-title {
    font-weight: 600;
    color: var(--tp-text-primary);
    margin-bottom: 6px;
  }

  ul {
    margin: 0;
    padding-left: 18px;
    line-height: 1.8;
    font-size: 13px;
  }
}

.form-actions {
  display: flex;
  justify-content: flex-end;
}

.task-card {
  border-radius: 10px;
  border: 1px solid var(--tp-border);
  overflow: hidden;

  &.task-3 {
    border-color: rgba(52, 211, 153, 0.45);
  }

  &.task-4 {
    border-color: rgba(248, 113, 113, 0.45);
  }
}

.task-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 14px;
  border-bottom: 1px solid var(--tp-border);
  background: var(--tp-bg-header);

  .task-id {
    margin-left: auto;
    font-size: 12px;
    color: var(--tp-text-secondary);
    font-family: ui-monospace, monospace;
  }
}

.task-body {
  padding: 24px 14px;
  display: flex;
  justify-content: center;
}

.task-pending,
.task-running,
.task-done,
.task-failed {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  text-align: center;
  width: 100%;
}

.spin-icon {
  color: var(--tp-primary);
  animation: spin 1.2s linear infinite;
}

@keyframes spin {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}

.task-text {
  font-size: 16px;
  font-weight: 600;
}

.task-sub {
  font-size: 12px;
  color: var(--tp-text-secondary);
  line-height: 1.6;
}

.task-progress {
  width: 100%;
  max-width: 280px;
  margin-top: 4px;
}

.task-error {
  font-size: 13px;
  color: var(--tp-danger);
  background: rgba(248, 113, 113, 0.1);
  border-radius: 8px;
  padding: 8px 12px;
  max-width: 100%;
  word-break: break-all;
  line-height: 1.6;
}

.task-link {
  margin-top: 4px;
}


.ai-gen-mode {
  margin-bottom: 16px;
}

.batch-rows {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 8px;
  flex: 1;
  min-height: 0;
}

/* Persistent header row: the row counter stays visible while the list
   scrolls, so large batches (up to the K8 limit) remain navigable. */
.batch-rows-head {
  display: flex;
  align-items: center;
  gap: 12px;
}

.batch-rows-scroll {
  display: flex;
  flex-direction: column;
  gap: 8px;
  /* : fills the card's remaining height (was a 440px page cap) and
 flows inside — the card itself never grows past the content band. */
  flex: 1;
  min-height: 160px;
  overflow-y: auto;
  padding-right: 6px;
}

.batch-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.batch-row-no {
  flex-shrink: 0;
  width: 22px;
  height: 22px;
  margin-top: 6px;
  border-radius: 50%;
  background: var(--tp-primary-weak);
  color: var(--tp-primary);
  font-size: 13px;
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.batch-row-link-tag {
  /* the carried requirement-point link badge: fixed width, never squeezes the editor */
  flex-shrink: 0;
}

.batch-row-input {
  flex: 1;

  /* Taller line box: rows prefilled with title + acceptance-criteria
     lines read as two distinct lines instead of a dense block. */
  :deep(.el-textarea__inner) {
    line-height: 1.7;
  }
}

.batch-row .el-button {
  margin-top: 6px;
}

.batch-count {
  font-size: 12px;
  color: var(--tp-text-secondary);
}

/* Case-gap callout: separated from the title above and from the form below
   by its own surface, padding and margin — no longer a bare hint line. */
.case-gap-chip {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
  padding: 8px 12px;
  border: 1px solid var(--tp-border);
  border-radius: 8px;
  background: var(--tp-primary-weak);
}

.batch-submit-tip {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--tp-text-secondary);
  margin-right: 12px;
}

.batch-auto-enum-hint {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--tp-text-secondary);
}

.batch-submit-tip .el-icon {
  color: var(--tp-primary);
}
.scenario-panel {
  margin-top: 12px;
  padding: 12px;
  border: 1px solid var(--tp-border);
  border-radius: 8px;
  background: var(--tp-bg-elevated, transparent);
  /* : shares the left band with the rows region and flows inside — the
 circle-selection matrix of a 500-row batch must never grow the page. */
  flex: 1 1 45%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.scenario-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding-right: 4px;
}

.batch-tail-drawer {
  flex-shrink: 0;
}

.ai-gen-right-card.is-single {
  overflow-y: auto;
}
.scenario-panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}
.scenario-panel-title {
  font-size: 13px;
  color: var(--tp-text-primary);
}
.scenario-row-title {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  flex: 1;
}
.scenario-row-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}
.scenario-row-count {
  margin-left: auto;
  font-size: 12px;
  color: var(--tp-text-secondary);
  flex-shrink: 0;
}
.scenario-row-toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 6px;
}
.scenario-check {
  display: flex;
  align-items: baseline;
  margin-bottom: 6px;
  min-width: 0;
}
.scenario-check :deep(.el-checkbox__label) {
  display: inline-flex;
  align-items: baseline;
  gap: 6px;
  flex-wrap: wrap;
  white-space: normal;
  line-height: 1.5;
}
.scenario-kind {
  color: var(--tp-primary);
  font-size: 12px;
  flex-shrink: 0;
}
.scenario-anchor {
  color: var(--tp-text-secondary);
  font-size: 12px;
}
.scenario-residual {
  margin: 0 0 8px;
  padding: 8px;
  border-radius: 6px;
  background: var(--tp-warning-soft, rgba(230, 162, 60, 0.12));
  font-size: 12px;
  color: var(--tp-text-primary);
}
.scenario-residual ul {
  margin: 4px 0 0;
  padding-left: 18px;
}

.batch-progress-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  /* : the progress card fills the right column's band; the queue list
 flows inside (see .batch-item-list) instead of a 320px page cap. */
  flex: 1;
  min-height: 0;
}

.batch-progress-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
  flex-wrap: wrap;
}

.batch-progress-count {
  font-size: 16px;
  font-weight: 600;
}

.batch-progress-sub {
  font-size: 12px;
  color: var(--tp-text-secondary);
}

.batch-unavailable,
.batch-partial-fail {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  padding: 8px 12px;
  border-radius: 8px;
  line-height: 1.6;
}

.batch-unavailable {
  color: var(--tp-danger);
  background: rgba(248, 113, 113, 0.1);
}

.batch-partial-fail {
  color: var(--tp-warning);
  background: rgba(245, 158, 11, 0.1);
}

/* Waiting notice strip: neutral info tone, the sentence itself comes from
   the server so this only carries layout. */
.batch-queue-notice {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  font-size: 13px;
  padding: 8px 12px;
  border-radius: 8px;
  line-height: 1.6;
  color: var(--tp-text-secondary);
  background: rgba(96, 165, 250, 0.1);
}

.batch-item-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  /* : was a 320px cap — the queue is the page's most valuable surface on
 tall screens, it now grows to the band and scrolls internally. */
  flex: 1;
  min-height: 160px;
  overflow-y: auto;
  padding-right: 4px;
}

.batch-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid var(--tp-border);
  border-radius: 8px;
  background: var(--tp-bg-header);
}

.batch-item-no {
  flex-shrink: 0;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: var(--tp-primary-weak);
  color: var(--tp-primary);
  font-size: 11px;
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.batch-item-main {
  flex: 1;
  min-width: 0;
}

.batch-item-title {
  font-size: 13px;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.batch-item-status {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 2px;
}

.batch-item-error {
  font-size: 12px;
  color: var(--tp-danger);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 180px;
}

.batch-retry-failed {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-top: 4px;
}

.batch-retry-hint {
  font-size: 12px;
  color: var(--tp-text-secondary);
}

.task-cancelled {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 8px 0;
}

.task-stop {
  margin-top: 10px;
}

.batch-cancelled-count {
  margin-left: 8px;
  font-size: 12px;
  color: var(--tp-text-secondary);
}
</style>
