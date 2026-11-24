<template>
  <div class="page-root">
    <div class="page-card" data-test="case-detail-root">
      
      <div class="detail-header">
        <el-button v-if="!embedded" link type="primary" :icon="ArrowLeft" data-test="detail-back" @click="goBack">{{ t('common.back') }}</el-button>
        <div v-if="detail" class="header-main">
          <div class="header-title-row">
            <h2 class="page-title" style="margin: 0" data-test="detail-title">{{ detail.title }}</h2>
            <span class="mono detail-no" data-test="detail-case-no">{{ detail.caseNo }}</span>
            <FavoriteButton entityType="case" :entity-id="caseId" />
 
            <ExcellentStarButton
              :entity-id="caseId"
              :case-no="detail.caseNo"
              :excellent="detail.excellent ?? 0"
              :version="detail.version"
              :status="detail.status"
              @changed="load()"
            />
          </div>
          <div class="header-tags">
            <el-tag :type="CASE_STATUS[detail.status]?.type ?? 'info'" size="small" data-test="detail-status">
              {{ CASE_STATUS[detail.status]?.label ?? detail.status }}
            </el-tag>
            <el-tag :type="PRIORITY_TYPE[detail.priority] ?? 'info'" size="small" data-test="detail-priority">
              {{ PRIORITY_LABEL[detail.priority] }}
            </el-tag>
            <el-tag :type="CONFIDENCE_TYPE[detail.confidence] ?? 'info'" size="small" data-test="detail-confidence">
              {{ CONFIDENCE_LABEL[detail.confidence] }}
            </el-tag>
            <el-tag v-if="detail.phase" :type="PHASE_TYPE[detail.phase] ?? 'info'" size="small" data-test="detail-phase">
              {{ PHASE_LABEL[detail.phase] }}
            </el-tag>
            <el-tag v-else type="info" size="small" data-test="detail-phase">{{ t('caseDetail.phaseNone') }}</el-tag>
            <el-tag type="info" size="small" data-test="detail-version">v{{ detail.version }}</el-tag>
            <!-- lineage (R-10): a promoted case names its sandbox origin and the
 badge jumps back to the exact source request (deep link by requestId). -->
            <el-tag
              v-if="sandboxOriginId !== null"
              type="success"
              size="small"
              data-test="case-promoted-origin"
              style="cursor: pointer"
              @click="jumpToSandboxOrigin"
            >
              {{ t('caseDetail.sandboxOrigin', { id: sandboxOriginId }) }}
            </el-tag>
          </div>
        </div>
      </div>

      <!-- Handoff card for a case id that cannot be opened: reason (server sentence, names
           the id), plus the one click that actually recovers it. The recycle door appears only
           when the server found a delete record, so an empty recycle bin is never advertised. -->
      <div v-if="!detail && !loading && loadFailure" class="history-empty detail-load-failure" data-test="case-load-error">
        <div class="detail-load-failure-title" data-test="case-load-failure-title">{{ t('caseDetail.loadFailureTitle') }}</div>
        <div class="detail-load-failure-msg" data-test="case-load-failure-msg">{{ loadFailure }}</div>
        <div class="detail-load-failure-actions" style="margin-top: 12px">
          <el-button type="primary" data-test="case-failure-goto-library" @click="goCaseLibrary(false)">
            {{ t('caseDetail.gotoCaseLibrary') }}
          </el-button>
          <el-button v-if="showRecycleDoor" plain data-test="case-failure-open-recycle" @click="goCaseLibrary(true)">
            {{ t('caseDetail.openRecycleBin') }}
          </el-button>
        </div>
      </div>

      <template v-if="detail">
        
        <template v-if="!editMode">
          <el-descriptions :column="2" border class="detail-desc">
            <el-descriptions-item :label="t('case.endType')">
              {{ END_TYPE_LABEL[detail.endType] ?? detail.endType }}
            </el-descriptions-item>
 
            <el-descriptions-item :label="t('appauto.picker.targetLabel')">
              <span data-test="detail-automation-target">{{ automationTargetText }}</span>
            </el-descriptions-item>
            <el-descriptions-item :label="t('case.phase')">
              {{ detail.phase ? PHASE_LABEL[detail.phase] : PHASE_LABEL[0] }}
            </el-descriptions-item>
            <el-descriptions-item :label="t('case.source')">
              {{ SOURCE_LABEL[detail.source] ?? detail.source }}
            </el-descriptions-item>
            <el-descriptions-item :label="t('case.assignee')">
              {{ assigneeText }}
            </el-descriptions-item>
            <el-descriptions-item :label="t('caseDetail.createdBy')">
              {{ creatorText }}
            </el-descriptions-item>
            <el-descriptions-item :label="t('common.createdAt')">
              {{ formatTime(detail.createdAt) }}
            </el-descriptions-item>
            <el-descriptions-item :label="t('common.updatedAt')">
              {{ formatTime(detail.updatedAt) }}
            </el-descriptions-item>
          </el-descriptions>

          
          <div class="detail-block" data-test="detail-precondition">
            <div class="detail-block-label">{{ t('case.precondition') }}</div>
            <div class="detail-block-body">{{ detail.precondition || '—' }}</div>
          </div>
          <div class="detail-block" data-test="detail-expected">
            <div class="detail-block-label">{{ t('case.expectedResult') }}</div>
            <div class="detail-block-body">{{ detail.expectedResult || '—' }}</div>
          </div>

          
          <h3 class="section-title">{{ t('case.steps') }}</h3>
          <DataTablePage :data="detail.steps || []" size="small" border data-test="detail-steps">
            <el-table-column type="index" :label="t('case.stepSeq')" width="60" />
            <el-table-column prop="description" :label="t('case.stepAction')" min-width="320" show-overflow-tooltip />
            <el-table-column :label="t('caseDetail.stepType')" width="100">
              <template #default="{ row }">
                <el-tag :type="row.isAssert === 1 ? 'success' : 'primary'" size="small">
                  {{ row.isAssert === 1 ? t('caseDetail.assert') : t('case.stepAction') }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column
              :label="t('case.stepEvidence')"
              min-width="220"
              show-overflow-tooltip
              data-test="detail-step-evidence"
            >
              <template #default="{ row }">
                <span v-if="stepAnchorText(row.anchor)" data-test="detail-step-evidence-text">
                  {{ stepAnchorText(row.anchor) }}
                </span>
                <span v-else>—</span>
              </template>
            </el-table-column>
          </DataTablePage>

          
          <div class="script-block" data-test="detail-script">
            <h3 class="section-title">
              {{ t('caseDetail.scriptSection') }}
              <el-tag v-if="script" size="small" type="info">schema {{ script.schemaVersion }}</el-tag>
 
              <el-tag v-if="script && script.status === 2" size="small" type="warning" data-test="detail-script-pending">
                {{ t('caseDetail.pendingUpdate') }}
              </el-tag>
              <el-tag v-else-if="script" size="small" type="success">{{ t('caseDetail.generated') }}</el-tag>
            </h3>
            <!-- Tail drawer for the running script-generation task :
 same shared component as the extraction face, fed by the task
 door cursor window + SSE. Hidden until a task is bound. -->
            <TaskTailDrawer
              v-if="scriptTaskId != null"
              :events="scriptTailEvents"
              :live="scriptTaskLive"
              :can-stop="scriptTaskLive"
              @stop="stopScriptTask"
            />
            <!-- segment c: gap-declaration chips (soft signal row above the
 actions; "ignore" hides this task's chips, a fresh generation
 task brings a new window). -->
            <ResourceGapChips
              v-if="scriptTaskId != null"
              :events="scriptTailEvents"
              :dismissed="gapsDismissed"
              @regen="regenScript"
              @dismiss="dismissGaps"
            />
            <template v-if="scriptLoading">
              <el-skeleton :rows="3" animated />
            </template>
            <template v-else-if="script">
              <div class="script-actions">
                <el-button type="primary" plain size="small" :loading="!!scriptTask" data-test="detail-script-regen" @click="regenScript">
                  <el-icon><RefreshRight /></el-icon>{{ t('caseDetail.regenScript') }}
                </el-button>
 
                <el-button
                  v-if="canClone"
                  type="primary"
                  plain
                  size="small"
                  data-test="detail-script-tree-edit"
                  @click="stepTreeVisible = true"
                >
                  <el-icon><Operation /></el-icon>{{ t('caseDetail.treeEdit') }}
                </el-button>
                <el-tooltip
                  :content="script.status === 2 ? t('caseDetail.regenTip') : t('caseDetail.trialTip')"
                  placement="top"
                >
                  <el-button
                    type="success"
                    plain
                    size="small"
                    :disabled="script.status === 2"
                    :loading="trialSubmitting"
                    data-test="detail-trial-run"
                    @click="startTrialRun()"
                  >
                    <el-icon><VideoPlay /></el-icon>{{ t('caseDetail.trialRun') }}
                  </el-button>
                </el-tooltip>
                <el-tooltip
                  :content="t('caseDetail.quickTip')"
                  placement="top"
                >
                  <el-button
                    type="primary"
                    plain
                    size="small"
                    :disabled="script.status === 2"
                    :loading="quickSubmitting"
                    data-test="detail-quick-execute"
                    @click="startQuickExecute()"
                  >
                    <el-icon><Promotion /></el-icon>{{ t('exec.quickExecute') }}
                  </el-button>
                </el-tooltip>
 
                <el-button
                  v-if="isAppautoCase"
                  type="warning"
                  plain
                  size="small"
                  :disabled="script.status === 2"
                  data-test="detail-matrix-fanout"
                  @click="matrixFanoutVisible = true"
                >
                  <el-icon><Histogram /></el-icon>{{ t('appauto.matrix.entryBtn') }}
                </el-button>
                <span v-if="scriptTask" class="script-task-info" data-test="detail-script-task">
                  <el-tag :type="AI_TASK_STATUS[scriptTask.status]?.type ?? 'info'" size="small">
                    {{ AI_TASK_STATUS[scriptTask.status]?.label ?? scriptTask.status }}
                  </el-tag>
                  <span class="script-task-sub">{{ t('caseDetail.taskRef', { id: scriptTask.id }) }}（{{ scriptTask.errorMsg || t('caseDetail.autoRefreshAfterDone') }}）</span>
                </span>
              </div>
              <pre class="script-json" data-test="detail-script-json">{{ prettyScript }}</pre>

 
              <div class="version-block" data-test="script-version-block">
                <h3 class="section-title sub-title">
                  {{ t('caseDetail.scriptVersions') }}
                  <el-tag v-if="scriptVersionList.length > 0" size="small" type="info" data-test="script-version-count">
                    {{ t('caseDetail.countVer', { n: scriptVersionList.length }) }}
                  </el-tag>
                </h3>
                <DataTablePage
                  v-if="scriptVersionList.length"
                  v-loading="scriptVersionLoading"
                  :data="scriptVersionList"
                  size="small"
                  border
                  row-key="id"
                  data-test="script-version-table"
                 :loading="scriptVersionLoading">
                  <el-table-column :label="t('caseDetail.colVersion')" width="80">
                    <template #default="{ row }">
                      <span data-test="script-version-no">v{{ row.scriptVersion }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column :label="t('caseDetail.colTime')" min-width="150">
                    <template #default="{ row }">
                      <TimeCell data-test="script-version-time" :iso="row.createdAt" />
                    </template>
                  </el-table-column>
                  <el-table-column :label="t('caseDetail.colReason')" min-width="120">
                    <template #default="{ row }">
                      <el-tag size="small" :type="row.reason === SCRIPT_REASON_ROLLBACK ? 'warning' : 'primary'" data-test="script-version-reason">
                        {{ row.reason || t('caseDetail.reasonScriptSave') }}
                      </el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column :label="t('caseDetail.colCaseVer')" width="110">
                    <template #default="{ row }">
                      <span data-test="script-version-case-anchor">{{ t('caseDetail.caseVerPrefix') }} v{{ row.caseVersion }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column :label="t('common.action')" width="110">
                    <template #default="{ row, $index }">
                      <el-button
                        link
                        type="primary"
                        size="small"
                        :disabled="row.corrupted || $index >= scriptVersionList.length - 1"
                        :data-test="`script-version-diff-${row.scriptVersion}`"
                        @click="openScriptVersionDiff(row, scriptVersionList[$index + 1])"
                      >
                        {{ t('caseDetail.viewDiff') }}
                      </el-button>
                    </template>
                  </el-table-column>
                </DataTablePage>
                <div v-else-if="!scriptVersionLoading" class="history-empty" data-test="script-version-empty">
                  <el-icon :size="16"><Clock /></el-icon>
                  <span>{{ t('caseDetail.scriptVerEmpty') }}</span>
                </div>
              </div>
            </template>
            <div v-else class="script-empty">
              <el-icon :size="20"><Document /></el-icon>
              <span>{{ t('caseDetail.noScriptHint') }}</span>
              <div class="script-empty-actions">
                <el-button type="primary" plain size="small" :loading="!!scriptTask" data-test="detail-script-regen" @click="regenScript">
                  <el-icon><MagicStick /></el-icon>{{ t('caseDetail.genScript') }}
                </el-button>
                <el-tooltip :content="t('caseDetail.trialNeedScript')" placement="top">
                  <el-button size="small" data-test="detail-trial-run" @click="trialRunNoScript">
                    <el-icon><VideoPlay /></el-icon>{{ t('caseDetail.trialRun') }}
                  </el-button>
                </el-tooltip>
                <el-tooltip :content="t('caseDetail.quickNeedScript')" placement="top">
                  <el-button size="small" data-test="detail-quick-execute" @click="trialRunNoScript">
                    <el-icon><Promotion /></el-icon>{{ t('exec.quickExecute') }}
                  </el-button>
                </el-tooltip>
              </div>
            </div>

 
            <div class="version-block" data-test="flowchart-block">
              <h3 class="section-title sub-title">
                {{ t('caseDetail.flowchart') }}
                <el-tag v-if="flowchart" size="small" type="info" data-test="flowchart-step-count">
                  {{ t('caseDetail.countStep', { n: flowchart.stepCount }) }}
                </el-tag>
              </h3>
              <div v-if="flowchartLoading" class="history-empty" data-test="flowchart-loading">
                <el-skeleton :rows="2" animated />
              </div>
              <div v-else-if="flowchartError" class="history-empty" data-test="flowchart-error">
                <el-icon :size="16"><WarningFilled /></el-icon>
                <span>{{ flowchartError }}</span>
              </div>
 
              <div v-else class="flowchart-body">
                <div class="flowchart-main">
                  
                  <div v-show="flowchartSvg" class="flowchart-canvas" data-test="flowchart-svg" v-html="flowchartSvg" />
                  <pre v-if="!flowchartSvg" class="script-json" data-test="flowchart-source">{{ flowchart?.mermaid }}</pre>
                </div>
                <StepReorderPanel
                  v-if="canClone && detail"
                  ref="reorderPanelRef"
                  :steps="detail.steps"
                  :saving="reorderSaving"
                  :project-id="detail.projectId"
                  @save="saveReorderedSteps"
                />
              </div>
            </div>
          </div>

 
          <el-alert
            v-if="lockBlockedMsg"
            type="warning"
            :closable="false"
            show-icon
            class="review-lock-banner"
            data-test="review-lock-banner"
          >
            <template #title>{{ lockBlockedMsg }}</template>
            <el-button
              size="small"
              type="primary"
              plain
              :loading="lockAcquiring"
              data-test="review-lock-retry"
              @click="enterEdit"
            >{{ t('caseDetail.lockRetry') }}</el-button>
          </el-alert>

          
          <div class="detail-actions">
            <el-button :icon="Edit" :disabled="detail.status === 6 || !!lockBlockedMsg" data-test="detail-edit" @click="enterEdit">{{ t('caseDetail.edit') }}</el-button>
            <el-tooltip :content="t('caseDetail.cloneTip')" placement="top">
              <el-button
                v-if="canClone"
                :icon="DocumentCopy"
                :loading="cloneSubmitting"
                data-test="detail-clone"
                @click="openCloneDialog"
              >{{ t('caseDetail.clone') }}</el-button>
            </el-tooltip>
            <el-button
              type="success"
              :disabled="detail.status === 3 || detail.status === 6 || detail.status === 9 || !!lockBlockedMsg"
              data-test="detail-review-pass"
              @click="doReview('pass')"
            >
              <el-icon><CircleCheck /></el-icon>{{ t('case.approve') }}
            </el-button>
            <el-button
              type="warning"
              :disabled="detail.status === 3 || detail.status === 4 || detail.status === 6 || detail.status === 9 || !!lockBlockedMsg"
              data-test="detail-review-need-change"
              @click="doReview('need_change')"
            >
              <el-icon><EditPen /></el-icon>{{ t('case.needChange') }}
            </el-button>
            <el-button
              type="danger"
              :disabled="detail.status === 3 || detail.status === 5 || detail.status === 6 || detail.status === 9 || !!lockBlockedMsg"
              data-test="detail-review-reject"
              @click="doReview('reject')"
            >
              <el-icon><CloseBold /></el-icon>{{ t('case.reject') }}
            </el-button>
          </div>

 
          <div class="defect-block" data-test="case-defect-block">
            <h3 class="section-title">
              {{ t('caseDetail.defectSection') }}
              <el-tag v-if="defects.length" size="small" type="info" data-test="case-defect-count">
                {{ t('caseDetail.countItem', { n: defects.length }) }}
              </el-tag>
            </h3>

            
            <div v-if="closedDefect" class="rerun-hint-card" data-test="rerun-hint-card">
              <el-icon :size="18" color="var(--tp-warning)"><WarningFilled /></el-icon>
              <div class="rerun-hint-body">
                <div class="rerun-hint-title" data-test="rerun-hint-text">
                  {{ t('caseDetail.rerunHintTitle', { key: closedDefect.defectKey }) }}
                </div>
                <div class="rerun-hint-sub">{{ t('caseDetail.rerunHintSub') }}</div>
              </div>
              <el-button
                type="warning"
                size="small"
                :loading="rerunning"
                :disabled="detail.status === 6"
                data-test="rerun-case-btn"
                @click="doRerun"
              >
                <el-icon><VideoPlay /></el-icon>{{ t('caseDetail.rerunCase') }}
              </el-button>
            </div>

            <div class="defect-toolbar">
              <el-button
                type="primary"
                plain
                size="small"
                :disabled="detail.status === 6"
                data-test="case-defect-link-btn"
                @click="openLinkDialog"
              >
                <el-icon><Link /></el-icon>{{ t('caseDetail.linkDefect') }}
              </el-button>
            </div>

 
            <div v-if="builtinSummary && builtinSummary.total > 0" class="builtin-defect-summary" data-test="builtin-defect-summary">
              <el-icon :size="16" color="var(--tp-warning)"><WarningFilled /></el-icon>
              <span>{{ t('caseDetail.builtinSummary', { n: builtinSummary.total }) }}</span>
              <el-tag v-if="builtinSummary.openCount > 0" type="danger" size="small" data-test="builtin-defect-open-tag">
                {{ t('caseDetail.builtinOpen', { n: builtinSummary.openCount }) }}
              </el-tag>
              <el-tag v-else type="success" size="small">{{ t('caseDetail.allClosed') }}</el-tag>
            </div>

            <DataTablePage v-if="defects.length" v-loading="defectsLoading" :data="defects" size="small" border data-test="case-defect-table" :loading="defectsLoading">
              <el-table-column prop="defectKey" :label="t('caseDetail.colDefectKey')" width="150">
                <template #default="{ row }">
                  <span class="mono" data-test="defect-key-cell">{{ row.defectKey }}</span>
                </template>
              </el-table-column>
              <el-table-column :label="t('caseDetail.colDefectStatus')" width="150">
                <template #default="{ row }">
                  <el-select
                    :model-value="row.defectStatus"
                    size="small"
                    :disabled="row.status === 2 || detail.status === 6"
                    data-test="defect-status-select"
                    @change="(v: string) => changeDefectStatus(row, v)"
                  >
                    <el-option
                      v-for="(s, key) in DEFECT_STATUS_LABEL"
                      :key="key"
                      :label="s.label"
                      :value="key"
                      :disabled="key === 'closed' && !canCloseDefect"
                    />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column :label="t('caseDetail.colCloseStatus')" width="110">
                <template #default="{ row }">
                  <el-tag :type="DEFECT_CLOSE_LABEL[row.status]?.type ?? 'info'" size="small" data-test="defect-closed-badge">
                    {{ DEFECT_CLOSE_LABEL[row.status]?.label ?? row.status }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="createdAt" :label="t('caseDetail.colLinkedAt')" min-width="160">
            <template #default="{ row }"><TimeCell :iso="row.createdAt" /></template>
          </el-table-column>
            </DataTablePage>
            <div v-else class="defect-empty" data-test="case-defect-empty">
              <el-icon :size="16"><Link /></el-icon>
              <span>{{ t('caseDetail.defectEmpty') }}</span>
            </div>
          </div>

 
          <div class="req-block" data-test="case-req-block">
            <h3 class="section-title">
              {{ t('caseDetail.reqSection') }}
              <el-tag v-if="linkedRequirements.length" size="small" type="info" data-test="case-req-count">
                {{ t('caseDetail.countReq', { n: linkedRequirements.length }) }}
              </el-tag>
            </h3>
 
            <el-alert
              v-if="linkedRequirements.some((r) => r.status === 3)"
              type="info"
              :closable="false"
              show-icon
              data-test="case-offline-hint"
              style="margin-bottom: 8px"
              :title="t('caseDetail.offlineHintTitle')"
              :description="t('caseDetail.offlineHintDesc')"
            />
            <DataTablePage v-if="linkedRequirements.length" :data="linkedRequirements" size="small" border data-test="case-req-table" class="req-row-link" @row-click="openRequirementLedger">
              <el-table-column prop="title" :label="t('caseDetail.colReqItem')" min-width="240" show-overflow-tooltip>
                <template #default="{ row }">
                  <span>{{ row.title }}</span>
                  <el-tag v-if="showReqAutoLinked" size="small" type="info" style="margin-left: 6px" data-test="req-provenance-auto">
                    {{ t('caseDetail.reqAutoLinked') }}
                  </el-tag>
                </template>
              </el-table-column>
              
              <el-table-column :label="t('caseDetail.colReqNo')" width="110">
                <template #default="{ row }">#{{ row.id }}</template>
              </el-table-column>
              <el-table-column prop="chapter" :label="t('caseDetail.colChapter')" width="90">
                <template #default="{ row }">{{ row.chapter || '—' }}</template>
              </el-table-column>
              <el-table-column :label="t('common.status')" width="110">
                <template #default="{ row }">
 
                  <el-tag :type="REQUIREMENT_STATUS[row.status]?.tag ?? 'info'" size="small" :data-test="`req-status-${row.id}`">
                    {{ REQUIREMENT_STATUS[row.status]?.label ?? row.status }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column :label="t('common.action')" width="110">
                <template #default="{ row }">
                  <el-button
                    link
                    type="danger"
                    size="small"
                    :disabled="!canUnlinkRequirement"
                    :data-test="`req-unlink-${row.id}`"
                    @click.stop="unlinkRequirement(row)"
                  >
                    {{ t('caseDetail.unlinkReq') }}
                  </el-button>
                </template>
              </el-table-column>
            </DataTablePage>
            <div v-else class="req-empty" data-test="case-req-empty">
              <el-icon :size="16"><Document /></el-icon>
              <span>{{ t('caseDetail.reqEmpty') }}</span>
            </div>
          </div>

 
          <div class="trace-block" data-test="case-trace-block">
            <h3 class="section-title">{{ t('caseDetail.traceChain') }}</h3>
            <TraceChainPanel :chain="traceChain" :loading="traceLoading" />
          </div>

 
          <div class="history-block" data-test="repair-block">
            <h3 class="section-title">
              {{ t('caseDetail.repairPanel') }}
              <el-tag v-if="repairTotal > 0" size="small" type="info" data-test="repair-count">
                {{ t('caseDetail.countRepair', { n: repairTotal }) }}
              </el-tag>
            </h3>
            <DataTablePage
              v-if="repairList.length"
              v-loading="repairLoading"
              :data="repairList"
              size="small"
              border
              row-key="id"
              data-test="repair-table"
             :loading="repairLoading">
              <el-table-column :label="t('caseDetail.colTime')" min-width="150">
                <template #default="{ row }">
                  <TimeCell data-test="repair-row" :iso="row.createdAt" />
                </template>
              </el-table-column>
              <el-table-column :label="t('caseDetail.repairColRound')" width="90">
                <template #default="{ row }">
                  <span data-test="repair-round">{{ t('caseDetail.repairRoundN', { n: row.round }) }}</span>
                </template>
              </el-table-column>
              <el-table-column :label="t('caseDetail.repairColProposal')" min-width="220">
                <template #default="{ row }">
                  <span class="mono" data-test="repair-proposal">{{ row.proposalSummary || '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column :label="t('caseDetail.repairColOutcome')" width="150">
                <template #default="{ row }">
                  <el-tag :type="row.outcome === 1 ? 'success' : row.outcome === 2 ? 'danger' : 'info'" size="small" data-test="repair-outcome">
                    {{ row.outcomeLabel }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column :label="t('caseDetail.repairColFailNote')" min-width="200">
                <template #default="{ row }">
                  <span class="history-error">{{ row.failNote || '—' }}</span>
                </template>
              </el-table-column>
            </DataTablePage>
            <div v-else-if="!repairLoading" class="history-empty" data-test="repair-empty">
              <el-icon :size="16"><MagicStick /></el-icon>
              <span>{{ t('caseDetail.repairEmpty') }}</span>
            </div>
            <div v-if="repairTotal > 10" class="pagination-bar">
              <el-pagination
                v-model:current-page="repairPage"
                :page-size="10"
                :total="repairTotal"
                layout="prev, pager, next"
                small
                data-test="repair-pager"
                @current-change="loadRepairRecords"
              />
            </div>
          </div>

 
          <div v-if="baselineList.length || baselineLoading" class="history-block" data-test="baseline-block">
            <h3 class="section-title">
              {{ t('caseDetail.baselinePanel') }}
              <el-tag v-if="pendingBaselineCount > 0" size="small" type="warning" data-test="baseline-pending-count">
                {{ t('caseDetail.baselinePendingCount', { n: pendingBaselineCount }) }}
              </el-tag>
            </h3>
            <DataTablePage
              v-if="baselineList.length"
              v-loading="baselineLoading"
              :data="baselineList"
              size="small"
              border
              row-key="id"
              data-test="baseline-table"
             :loading="baselineLoading">
              <el-table-column :label="t('caseDetail.baselineColShot')" width="110">
                <template #default="{ row }">
                  <el-image
                    v-if="baselineViewUrls[row.id]"
                    :src="baselineViewUrls[row.id]"
                    :preview-src-list="[baselineViewUrls[row.id]]"
                    preview-teleported
                    fit="cover"
                    class="history-shot"
                    :data-test="`baseline-shot-${row.id}`"
                  />
                  <span v-else class="history-muted">—</span>
                </template>
              </el-table-column>
              <el-table-column :label="t('caseDetail.baselineColStepKey')" min-width="180">
                <template #default="{ row }">
                  <span class="mono" data-test="baseline-step-key">{{ row.stepKey }}</span>
                </template>
              </el-table-column>
              <el-table-column :label="t('caseDetail.baselineColStatus')" width="110">
                <template #default="{ row }">
                  <el-tag :type="row.status === 1 ? 'warning' : row.status === 2 ? 'success' : 'info'" size="small" data-test="baseline-status">
                    {{ row.statusLabel }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column :label="t('caseDetail.baselineColTolerance')" width="100">
                <template #default="{ row }">
                  <span data-test="baseline-tolerance">{{ row.diffTolerance != null ? `${(row.diffTolerance * 100).toFixed(1)}%` : '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column :label="t('caseDetail.colTime')" min-width="150">
                <template #default="{ row }">
                  <TimeCell :iso="row.createdAt" />
                </template>
              </el-table-column>
              <el-table-column v-if="canVisualAssert" :label="t('caseDetail.baselineColAction')" width="150" fixed="right">
                <template #default="{ row }">
                  <template v-if="row.status === 1">
                    <el-button
                      link
                      type="primary"
                      size="small"
                      :loading="baselineBusy[row.id]"
                      data-test="baseline-confirm-btn"
                      @click="confirmBaseline(row)"
                    >{{ t('caseDetail.baselineConfirm') }}</el-button>
                    <el-button
                      link
                      type="danger"
                      size="small"
                      :loading="baselineBusy[row.id]"
                      data-test="baseline-reject-btn"
                      @click="rejectBaseline(row)"
                    >{{ t('caseDetail.baselineReject') }}</el-button>
                  </template>
                  <span v-else class="history-muted">—</span>
                </template>
              </el-table-column>
            </DataTablePage>
            <div v-else-if="baselineLoading" class="history-empty" data-test="baseline-loading-ph">
              <el-icon :size="16"><Picture /></el-icon>
              <span>{{ t('caseDetail.baselineLoading') }}</span>
            </div>
          </div>

 
          <div class="history-block" data-test="case-history-block">
            <h3 class="section-title">
              {{ t('caseDetail.execHistory') }}
              <el-tag v-if="historyTotal > 0" size="small" type="info" data-test="case-history-count">
                {{ t('caseDetail.countRun', { n: historyTotal }) }}
              </el-tag>
            </h3>
 
            <div v-if="historyList.length > 1" class="history-compare-bar">
              <el-button
                link
                type="primary"
                size="small"
                :disabled="historyCompareRows.length < 2"
                data-test="history-compare-btn"
                @click="openHistoryCompare"
              >
                {{ t('exec.historyCompare') }}<template v-if="historyCompareRows.length">（{{ historyCompareRows.length }}）</template>
              </el-button>
              <span v-if="historyCompareRows.length < 2" class="history-compare-hint">
                {{ t('exec.historyCompareNeedTwo', { max: HISTORY_COMPARE_MAX }) }}
              </span>
            </div>
            <DataTablePage
              v-if="historyList.length"
              ref="historyTableRef"
              v-loading="historyLoading"
              :data="historyList"
              size="small"
              border
              row-key="id"
              data-test="case-history-table"
              @selection-change="onHistorySelectionChange"
             :loading="historyLoading">
              <el-table-column type="selection" width="42" :selectable="() => true" />
              <el-table-column :label="t('caseDetail.colFinishedAt')" min-width="150">
                <template #default="{ row }">
                  <TimeCell data-test="history-time" :iso="row.finishedAt || row.startedAt" />
                </template>
              </el-table-column>
              <el-table-column :label="t('caseDetail.colSource')" width="110">
                <template #default="{ row }">
                  <el-tag
                    v-if="row.batchName"
                    size="small"
                    type="info"
                    data-test="history-source"
                  >
                    {{ row.batchName }}
                  </el-tag>
                  <el-tag v-else size="small" type="primary" data-test="history-source">{{ t('caseDetail.trialRun') }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column :label="t('caseDetail.colEnvVersion')" min-width="170">
                <template #default="{ row }">
                  <span class="mono" data-test="history-env">{{ row.envVersion || '-' }}</span>
                </template>
              </el-table-column>
              <el-table-column :label="t('caseDetail.colResult')" width="90">
                <template #default="{ row }">
                  <el-tag :type="EXEC_TASK_STATUS[row.status]?.type ?? 'info'" size="small" data-test="history-status">
                    {{ EXEC_TASK_STATUS[row.status]?.label ?? row.status }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column :label="t('caseDetail.colDuration')" width="90">
                <template #default="{ row }">
                  <span>{{ row.durationMs != null ? `${(row.durationMs / 1000).toFixed(1)}s` : '-' }}</span>
                </template>
              </el-table-column>
              <el-table-column :label="t('caseDetail.colFailReason')" min-width="200">
                <template #default="{ row }">
                  <el-tag v-if="row.aiFailureClass" :type="AI_FAILURE_CLASS[row.aiFailureClass]?.type ?? 'info'" size="small" data-test="history-ai-class" class="history-ai-class">
                    {{ AI_FAILURE_CLASS[row.aiFailureClass]?.label ?? row.aiFailureClass }}
                  </el-tag>
                  <span v-if="row.errorDetail" class="history-error" data-test="history-error">{{ row.errorDetail }}</span>
                  <span v-else class="history-muted">—</span>
                </template>
              </el-table-column>
              <el-table-column :label="t('caseDetail.colScreenshot')" width="90">
                <template #default="{ row }">
                  <el-image
                    v-if="row.screenshots.length"
                    :src="row.screenshots[0].url"
                    :preview-src-list="shotUrls(row)"
                    preview-teleported
                    fit="cover"
                    class="history-shot"
                    data-test="history-screenshot"
                  />
                  <span v-else class="history-muted">—</span>
                </template>
              </el-table-column>
 
              <el-table-column :label="t('caseDetail.colEngine')" width="150" fixed="right">
                <template #default="{ row }">
                  <template v-if="row.engineCode">
                    <span class="mono" data-test="history-engine">{{ row.engineCode }}</span>
                    <el-button
                      link
                      type="primary"
                      size="small"
                      :data-test="`engine-rerun-open-${row.id}`"
                      @click="openEngineRerun(row)"
                    >{{ t('caseDetail.engineRerun') }}</el-button>
                  </template>
                  <span v-else class="history-muted">—</span>
                </template>
              </el-table-column>
            </DataTablePage>
            <div v-else-if="!historyLoading" class="history-empty" data-test="case-history-empty">
              <el-icon :size="16"><Clock /></el-icon>
              <span>{{ t('caseDetail.historyEmpty') }}</span>
            </div>
            <div v-if="historyTotal > 10" class="pagination-bar">
              <el-pagination v-if="historyTotal > 0"
                v-model:current-page="historyPage"
                :page-size="10"
                :total="historyTotal"
                layout="prev, pager, next"
                small
                data-test="history-pager"
                @current-change="loadHistory"
          />
            </div>
          </div>

 
          <div class="version-block" data-test="case-version-block">
            <h3 class="section-title">
              {{ t('caseDetail.versionHistory') }}
              <el-tag v-if="versionList.length > 0" size="small" type="info" data-test="case-version-count">
                {{ t('caseDetail.countVer', { n: versionList.length }) }}
              </el-tag>
            </h3>
            <DataTablePage
              v-if="versionList.length"
              v-loading="versionLoading"
              :data="versionList"
              size="small"
              border
              row-key="id"
              data-test="case-version-table"
             :loading="versionLoading">
              <el-table-column :label="t('caseDetail.colVersion')" width="80">
                <template #default="{ row }">
                  <span data-test="version-no">v{{ row.version }}</span>
                </template>
              </el-table-column>
              <el-table-column :label="t('caseDetail.colTime')" min-width="150">
                <template #default="{ row }">
                  <TimeCell data-test="version-time" :iso="row.createdAt" />
                </template>
              </el-table-column>
              <el-table-column :label="t('caseDetail.colOperator')" min-width="110">
                <template #default="{ row }">
                  <span data-test="version-operator">{{ row.operatorName || '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column :label="t('caseDetail.colType')" width="110">
                <template #default="{ row }">
                  <el-tag size="small" :type="VERSION_CHANGE_TYPE[row.changeType]?.type ?? 'info'" data-test="version-type">
                    {{ VERSION_CHANGE_TYPE[row.changeType]?.label ?? row.changeType }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column :label="t('caseDetail.colChangedFields')" width="100">
                <template #default="{ row }">
                  <span data-test="version-changed">{{ row.corrupted ? '—' : (row.changedFieldCount ?? 0) }}</span>
                </template>
              </el-table-column>
              <el-table-column :label="t('common.action')" width="180">
                <template #default="{ row, $index }">
                  <el-button
                    link
                    type="primary"
                    size="small"
                    :disabled="row.corrupted || $index >= versionList.length - 1"
                    :data-test="`version-diff-${row.version}`"
                    @click="openVersionDiff(row, versionList[$index + 1])"
                  >
                    {{ t('caseDetail.viewDiff') }}
                  </el-button>
                  <el-popconfirm
                    :title="t('caseDetail.rollbackPopTitle')"
                    width="280"
                    :confirm-button-text="t('caseDetail.rollbackConfirmBtn')"
                    :cancel-button-text="t('common.cancel')"
                    @confirm="doRollback(row)"
                  >
                    <template #reference>
                      <el-button
                        link
                        type="warning"
                        size="small"
                        :disabled="row.corrupted || row.version === latestVersion"
                        :data-test="`version-rollback-${row.version}`"
                      >
                        {{ t('caseDetail.rollbackToVersion') }}
                      </el-button>
                    </template>
                  </el-popconfirm>
                </template>
              </el-table-column>
            </DataTablePage>
            <div v-else-if="!versionLoading" class="history-empty" data-test="case-version-empty">
              <el-icon :size="16"><Clock /></el-icon>
              <span>{{ t('caseDetail.versionEmpty') }}</span>
            </div>
          </div>
        </template>

 
        <el-form v-else ref="editFormRef" :model="editForm" :rules="editRules" label-width="100px" class="edit-form" scroll-to-error>
 
          <el-alert
            v-if="lockHeld"
            type="info"
            :closable="false"
            show-icon
            class="review-lock-holding"
            data-test="review-lock-holding"
            :title="t('caseDetail.lockHolding')"
          />
          <DraftRestoreBar
            :draft-key="editDraftKey"
            :active="editMode"
            @restore="onEditDraftRestore"
          />
          <el-form-item :label="t('case.title')" prop="title">
            <el-input v-model="editForm.title" maxlength="300" show-word-limit data-test="detail-edit-title" />
          </el-form-item>
          <el-form-item :label="t('case.precondition')">
            <el-input v-model="editForm.precondition" type="textarea" :rows="2" />
          </el-form-item>
          <el-form-item :label="t('case.expectedResult')">
            <el-input v-model="editForm.expectedResult" type="textarea" :rows="2" data-test="detail-edit-expected" />
          </el-form-item>
          <!-- for="" opts out of label[for] association: el-radio-group's root
               is a DIV, and Blink requires a labelable target for label[for]
               (FormLabelForMatchesNonExistingIdError otherwise, pinned by the
               remount probe). Empty for= switches Element Plus to its group
               pattern — the label renders as a div and the radiogroup gets
               aria-labelledby, which is the accessible shape. -->
          <el-form-item :label="t('case.priority')" for="">
            <!-- id="" prevents the form label for= from targeting the non-labelable radiogroup div -->
            <el-radio-group id="" v-model="editForm.priority">
              <el-radio-button v-for="i in [0, 1, 2]" :key="i" :value="i">{{ PRIORITY_LABEL[i] }}</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item :label="t('case.confidence')" for="">
            <!-- id="" prevents the form label for= from targeting the non-labelable radiogroup div -->
            <el-radio-group id="" v-model="editForm.confidence">
              <el-radio-button v-for="i in [1, 2, 3]" :key="i" :value="i">{{ CONFIDENCE_LABEL[i] }}</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item :label="t('case.endType')">
            <el-select v-model="editForm.endType" style="width: 160px">
              <el-option v-for="(label, key) in END_TYPE_LABEL" :key="key" :label="label" :value="key" />
            </el-select>
          </el-form-item>
 
          <el-form-item :label="t('appauto.picker.targetLabel')" for="">
            <!-- id="" prevents the form label for= from targeting the non-labelable radiogroup div -->
            <el-radio-group id="" v-model="editForm.automationTarget" data-test="detail-edit-automation-target" @change="onAutomationTargetChange">
              <el-radio-button value="web">{{ t('appauto.picker.targetWeb') }}</el-radio-button>
              <el-radio-button value="app">{{ t('appauto.picker.targetApp') }}</el-radio-button>
              <el-radio-button value="mp">{{ t('appauto.picker.targetMp') }}</el-radio-button>
              <el-radio-button value="harmony">{{ t('appauto.picker.targetHarmony') }}</el-radio-button>
            </el-radio-group>
            <span class="form-tip">{{ t('appauto.picker.targetTip') }}</span>
          </el-form-item>
          <template v-if="isMobileFamilyTarget(editForm.automationTarget)">
            <el-form-item :label="t('appauto.picker.engineLabel')" data-test="detail-edit-appauto-engine-item">
              <div class="engine-picker-wrap">
                <AppautoEnginePicker v-model="editForm.appautoEngine" :target="editForm.automationTarget" />
              </div>
            </el-form-item>
            <el-form-item :label="t('appauto.picker.profileLabel')">
              <el-select
                v-model="editForm.appautoEnvProfileId"
                clearable
                :placeholder="t('appauto.picker.profilePlaceholder')"
                style="width: 280px"
                data-test="detail-edit-appauto-profile" filterable
              >
                <el-option v-for="p in appautoProfiles" :key="p.id" :label="p.name" :value="p.id" />
              </el-select>
              <span class="form-tip">{{ t('appauto.picker.profileTip') }}</span>
            </el-form-item>
          </template>
          <el-form-item :label="t('case.phase')">
            <el-select v-model="editForm.phase" clearable :placeholder="t('case.phasePlaceholder')" style="width: 160px" data-test="detail-edit-phase">
              <el-option v-for="i in [1, 2, 3, 4]" :key="i" :label="PHASE_LABEL[i]" :value="i" />
            </el-select>
            <span class="form-tip">{{ t('case.phaseTip') }}</span>
          </el-form-item>
          <!-- declarative mock binding: one dropdown, live rule/traffic
 thumbnails, draft&retired spaces taught as unbindable up front. -->
          <el-form-item :label="t('case.mockSpaceLabel')" data-test="detail-edit-mock-space-item">
            <MockSpacePicker v-model="editForm.mockSpaceId" :project-id="detail.projectId" />
            <span class="form-tip">{{ t('case.mockSpaceTip') }}</span>
          </el-form-item>
          <el-form-item :label="t('case.steps')">
            <div class="steps-editor" data-test="detail-edit-steps">
              <div v-for="(step, idx) in editForm.steps" :key="idx" class="step-row"
                :class="{ 'is-moved': movedEditIdx === idx }"
                data-test="detail-edit-step-row"
                @dragover.prevent="onEditStepDragOver(idx)"
                @drop.prevent="onEditStepDragEnd"
                @dragend="onEditStepDragEnd"
              >
 
                <span class="step-grip" draggable="true" :aria-label="t('caseDetail.dragStepAria', { n: idx + 1 })" data-test="detail-edit-step-grip" @dragstart="onEditStepDragStart(idx)">
                  <el-icon><Rank /></el-icon>
                </span>
                <span class="step-idx">{{ idx + 1 }}</span>
                <el-input v-model="step.description" :placeholder="t('case.stepAction')" style="width: 360px" />
                <!-- id="" prevents the form label for= from targeting the non-labelable radiogroup div -->
                <el-radio-group id="" v-model="step.isAssert">
                  <el-radio-button :value="0">{{ t('case.stepAction') }}</el-radio-button>
                  <el-radio-button :value="1">{{ t('caseDetail.assert') }}</el-radio-button>
                </el-radio-group>
                <el-button link type="danger" :icon="Delete" @click="removeStep(idx)" />
              </div>
              <el-button size="small" :icon="Plus" data-test="detail-edit-add-step" @click="addStep">
                {{ t('caseDetail.addStep') }}
              </el-button>
            </div>
          </el-form-item>
          <div class="edit-actions">
            <el-button @click="exitEdit">{{ t('common.cancel') }}</el-button>
            <el-button type="primary" :loading="saving" data-test="detail-save-draft" @click="saveDraft">
              <el-icon><DocumentChecked /></el-icon>{{ t('caseDetail.saveDraft') }}
            </el-button>
          </div>
        </el-form>
      </template>

      <el-skeleton v-else-if="loading" :rows="6" animated data-test="detail-loading" />
    </div>

 
    <el-dialog v-model="versionDiffVisible" :title="versionDiffTitle" width="800px" destroy-on-close data-test="version-diff-dialog">
      <el-alert
        v-if="versionDiff?.truncated"
        type="warning"
        :closable="false"
        show-icon
        :title="t('caseDetail.diffTruncated')"
        data-test="version-diff-truncated"
        style="margin-bottom: 12px"
      />
      <DataTablePage v-if="versionDiff && versionDiff.changes.length" :data="versionDiff.changes" size="small" border data-test="version-diff-table">
        <el-table-column prop="label" :label="t('caseDetail.colField')" width="110" />
        <el-table-column :label="t('caseDetail.colBefore')" min-width="240">
          <template #default="{ row }">
            <div class="version-diff-cell" data-test="diff-before">{{ renderDiffValue(row.before, row.field) }}</div>
          </template>
        </el-table-column>
        <el-table-column :label="t('caseDetail.colAfter')" min-width="240">
          <template #default="{ row }">
            <div class="version-diff-cell version-diff-after" data-test="diff-after">{{ renderDiffValue(row.after, row.field) }}</div>
          </template>
        </el-table-column>
      </DataTablePage>
      <el-empty v-else-if="versionDiff" :description="t('caseDetail.noVersionDiff')" :image-size="60" />
    </el-dialog>

 
    <el-dialog v-model="scriptVersionDiffVisible" :title="scriptVersionDiffTitle" width="800px" destroy-on-close data-test="script-version-diff-dialog">
      <el-alert
        v-if="scriptVersionDiff?.corrupted"
        type="error"
        :closable="false"
        show-icon
        :title="scriptVersionDiff.corruptedMessage || t('caseDetail.snapshotCorrupted')"
        data-test="script-version-diff-corrupted"
        style="margin-bottom: 12px"
      />
      <DataTablePage v-else-if="scriptVersionDiff && scriptVersionDiff.changes.length" :data="scriptVersionDiff.changes" size="small" border data-test="script-version-diff-table">
        <el-table-column prop="label" :label="t('caseDetail.colField')" width="110" />
        <el-table-column :label="t('caseDetail.colBefore')" min-width="240">
          <template #default="{ row }">
            <div class="version-diff-cell" data-test="script-diff-before">{{ renderScriptDiffValue(row.before) }}</div>
          </template>
        </el-table-column>
        <el-table-column :label="t('caseDetail.colAfter')" min-width="240">
          <template #default="{ row }">
            <div class="version-diff-cell version-diff-after" data-test="script-diff-after">{{ renderScriptDiffValue(row.after) }}</div>
          </template>
        </el-table-column>
      </DataTablePage>
      <el-empty v-else-if="scriptVersionDiff" :description="t('caseDetail.noScriptDiff')" :image-size="60" />
    </el-dialog>

 
    <SimilarityHintDialog ref="similarityDialogRef" />

    
    <el-dialog
      v-model="reasonVisible"
      :title="reasonAction === 'need_change' ? t('case.needChangeTitle') : t('case.rejectTitle')"
      width="480px"
      destroy-on-close
      data-test="detail-reason-dialog"
    >
      <el-input
        v-model="reasonText"
        type="textarea"
        :rows="3"
        :placeholder="reasonAction === 'need_change' ? t('case.needChangePlaceholder') : t('case.rejectPlaceholder')"
        maxlength="500"
        data-test="detail-reason-input"
      />
      <ProblemTypeSelect v-model="reasonTypes" />
      <template #footer>
        <el-button @click="reasonVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="reviewing" data-test="detail-reason-submit" @click="submitReason">
          {{ t('common.confirm') }}
        </el-button>
      </template>
    </el-dialog>

 
    <el-dialog v-model="linkVisible" :title="t('caseDetail.linkDefect')" width="480px" destroy-on-close data-test="defect-link-dialog">
      <el-form label-width="90px">
        <el-form-item :label="t('caseDetail.colDefectKey')" required>
          <el-input
            v-model="linkForm.defectKey"
            :placeholder="t('caseDetail.defectKeyPh')"
            maxlength="50"
            data-test="defect-key-input"
            @keyup.enter="submitLink"
          />
        </el-form-item>
        <el-form-item :label="t('caseDetail.initStatus')">
          <el-select v-model="linkForm.defectStatus" style="width: 100%" data-test="defect-link-status-select">
            <el-option :label="t('caseDetail.statusOpen')" value="open" />
            <el-option :label="t('caseDetail.statusInProgress')" value="in_progress" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="linkVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="linking" data-test="defect-link-submit" @click="submitLink">
          {{ t('caseDetail.linkBtn') }}
        </el-button>
      </template>
    </el-dialog>

 
    <el-dialog
      v-model="envSelectVisible"
      :title="t(envSelectMode === 'quick' ? 'exec.envSelectTitleQuick' : 'exec.envSelectTitle')"
      width="640px"
      destroy-on-close
      data-test="env-select-dialog"
    >
      <el-select
        v-model="selectedEnvId"
        :placeholder="t('exec.envPlaceholder')"
        style="width: 100%"
        :loading="envLoading"
        data-test="env-select" filterable
      >
        <el-option
          v-for="env in envs"
          :key="env.id"
          :value="env.id"
          :label="env.name"
          :disabled="env.envType === 4"
        >
          <div class="env-option">
            <span>{{ env.name }}</span>
            <el-tag size="small" :type="envTypeTag(env.envType)">{{ envTypeLabel(env.envType) }}</el-tag>
            <span v-if="env.versionSnapshot" class="env-option-version">{{ t('caseDetail.versionWord') }} {{ env.versionSnapshot }}</span>
            <span v-if="env.envType === 4" class="env-prod-warn">{{ t('exec.envProdDisabled') }}</span>
          </div>
        </el-option>
      </el-select>
      <p class="env-select-hint" data-test="env-select-hint">{{ t('exec.envSelectHint') }}</p>
 
      <el-form v-if="envSelectMode === 'quick'" label-width="72px" style="margin-top: 8px">
        <el-form-item :label="t('batch.browserLabel')">
          <BrowserSelect v-model="quickBrowser" :env-browsers="selectedEnvBrowsers" data-test="quick-browser" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="envSelectVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="trialSubmitting || quickSubmitting" data-test="env-select-confirm" @click="submitEnvSelect">
          {{ t(envSelectMode === 'quick' ? 'exec.envStartQuick' : 'exec.envStart') }}
        </el-button>
      </template>
    </el-dialog>

 
    <el-dialog
      v-model="quickResultVisible"
      :title="t('exec.quickResultTitle')"
      width="640px"
      destroy-on-close
      data-test="quick-result-dialog"
    >
      <p class="quick-message" data-test="quick-result-message">{{ quickResult?.message || t('exec.quickSubmitOk') }}</p>
      <el-descriptions v-if="quickResult" :column="2" border class="quick-meta">
        <el-descriptions-item :label="t('batch.status')">{{ quickResult.statusLabel }}</el-descriptions-item>
        <el-descriptions-item :label="t('batch.queuePos')">
          {{ quickResult.queuePos ? t('batch.queueNth', { n: quickResult.queuePos }) : '-' }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('batch.estimatedStartAt')">{{ quickResult.estimatedStartAt || '-' }}</el-descriptions-item>
        <el-descriptions-item :label="t('batch.skipCount')">
          {{ quickResult.skipCases.length ? t('batch.skipN', { n: quickResult.skipCases.length }) : t('batch.none') }}
        </el-descriptions-item>
      </el-descriptions>
      <DataTablePage v-if="quickResult && quickResult.skipCases.length > 0" :data="quickResult.skipCases" size="small"
        class="quick-skip-table" data-test="quick-skip-table">
        <el-table-column prop="caseNo" :label="t('batch.caseNo')" width="130" />
        <el-table-column prop="title" :label="t('batch.caseTitle')" min-width="200" show-overflow-tooltip />
        <el-table-column prop="reason" :label="t('batch.skipReason')" min-width="180" show-overflow-tooltip />
      </DataTablePage>
      <template #footer>
        <el-button data-test="quick-result-close" @click="quickResultVisible = false">{{ t('common.close') }}</el-button>
        <el-button type="primary" data-test="quick-result-goto" @click="gotoQuickBatch">
          {{ t('exec.quickGotoBatch') }}
        </el-button>
      </template>
    </el-dialog>

 
    <el-dialog
      v-model="cloneVisible"
      :title="t('caseDetail.cloneTitle')"
      width="640px"
      destroy-on-close
      data-test="clone-dialog"
    >
      <p class="clone-hint" data-test="clone-hint">{{ t('caseDetail.cloneHint') }}</p>
      <el-radio-group v-model="cloneTargetMode" class="clone-target-mode">
        <el-radio value="same">{{ t('caseDetail.cloneTargetSame', { project: sourceProjectName }) }}</el-radio>
        <el-radio value="other">{{ t('caseDetail.cloneTargetOther') }}</el-radio>
      </el-radio-group>
      <div v-if="cloneTargetMode === 'other'" class="clone-target-select">
        <ProjectSelect
          v-model="cloneTargetProjectId"
          :width="'100%'"
          :data-test="'clone-target-project'"
          :placeholder="t('caseDetail.cloneTargetPlaceholder')"
        />
        <p class="clone-target-note">{{ t('caseDetail.cloneTargetNote') }}</p>
      </div>
      <template #footer>
        <el-button data-test="clone-cancel" @click="cloneVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="cloneSubmitting" data-test="clone-confirm" @click="submitClone">
          {{ t('caseDetail.cloneConfirm') }}
        </el-button>
      </template>
    </el-dialog>

 
    <el-dialog
      v-model="historyCompareVisible"
      :title="t('exec.historyCompareTitle')"
      width="900px"
      data-test="history-compare-dialog"
    >
      <DataTablePage :data="historyCompareMetrics" size="small" border>
        <el-table-column prop="label" :label="t('exec.historyCompareResult')" width="110" fixed />
        <el-table-column
          v-for="row in historyCompareRows"
          :key="row.id"
          :label="t('caseDetail.taskRef', { id: row.id })"
          min-width="200"
        >
          <template #default="{ row: metric }">
            <template v-if="metric.key === 'result'">
              <el-tag :type="EXEC_TASK_STATUS[row.status]?.type ?? 'info'" size="small">
                {{ EXEC_TASK_STATUS[row.status]?.label ?? row.status }}
              </el-tag>
            </template>
            <template v-else-if="metric.key === 'engine'">
              <span v-if="row.engineCode" class="mono" data-test="compare-engine">{{ row.engineCode }}</span>
              <span v-else class="history-muted">—</span>
            </template>
            <template v-else-if="metric.key === 'shots'">
              <div v-if="row.screenshots.length" class="compare-shots">
                <el-image
                  v-for="s in row.screenshots"
                  :key="s.objectKey"
                  :src="s.url"
                  :preview-src-list="row.screenshots.map((x) => x.url)"
                  preview-teleported
                  fit="cover"
                  class="compare-shot"
                  :alt="s.objectKey.split('/').pop() || ''"
                />
              </div>
              <span v-else class="history-muted">—</span>
            </template>
            <span v-else class="compare-cell">{{ metric.values[row.id] || '—' }}</span>
          </template>
        </el-table-column>
      </DataTablePage>
      <template #footer>
        <el-button data-test="history-compare-close" @click="historyCompareVisible = false">{{ t('exec.close') }}</el-button>
      </template>
    </el-dialog>

 
    <AppautoEngineRerunDialog
      v-if="engineRerunRow && detail"
      v-model="engineRerunVisible"
      :project-id="detail.projectId"
      :case-id="caseId"
      :task-id="engineRerunRow.id"
      :source-engine-code="engineRerunRow.engineCode"
      @done="onEngineRerunDone"
    />

 
    <AppautoMatrixFanoutDialog
      v-if="detail"
      v-model="matrixFanoutVisible"
      :project-id="detail.projectId"
      :case-id="caseId"
    />

    
    <el-dialog
      v-model="trialVisible"
      :title="t('exec.taskDetail')"
      width="640px"
      destroy-on-close
      data-test="trial-result-dialog"
    >
      <ExecTaskResultView :task="trialTask" />
      <template #footer>
        <div class="trial-dialog-footer">
          <span v-if="trialTask" class="trial-hint" data-test="trial-hint">
            <template v-if="trialTask.status === 1">{{ t('exec.trialRunPending') }}</template>
            <template v-else-if="trialTask.status === 2">{{ t('exec.trialRunRunning') }}</template>
            <template v-else-if="trialTask.status === 3">
              {{ t('exec.trialRunDone', { status: t('exec.statusSuccess'), ms: trialTask.durationMs ?? '-' }) }}
            </template>
            <template v-else>
              {{ t('exec.trialRunFail', { status: EXEC_TASK_STATUS[trialTask.status]?.label ?? trialTask.status, detail: trialTask.errorDetail || '' }) }}
            </template>
          </span>
          <el-button
            v-if="trialTask && [3, 4, 5, 6].includes(trialTask.status)"
            type="success"
            plain
            size="small"
            data-test="trial-run-again"
            @click="reRunTrial"
          >
            <el-icon><VideoPlay /></el-icon>{{ t('exec.runningAgain') }}
          </el-button>
          <el-button data-test="trial-dialog-close" @click="trialVisible = false">{{ t('exec.close') }}</el-button>
        </div>
      </template>
    </el-dialog>

 
    <StepTree
      v-model:visible="stepTreeVisible"
      :case-id="caseId"
      :script="script"
      :case-title="detail?.title || ''"
      :project-id="detail?.projectId || 0"
      :automation-target="detail?.automationTarget || 'web'"
      :appauto-engine="detail?.appautoEngine || null"
      @saved="loadScript"
      @reload-requested="loadScript"
    />
    <CommentPanel host-type="case" :host-id="caseId" />
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { isAutoLinkedRequirement, requirementLedgerDeepLink } from '@/utils/caseRequirementEcho'
import { caseLibraryRoute, classifyCaseLoadFailure, offersRecycleBinAction } from '@/utils/caseLoadFailure'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules, type TableInstance as ElTableInstance } from 'element-plus'
import {
  ArrowLeft, CircleCheck, Clock, CloseBold, Delete, Document, DocumentChecked, DocumentCopy, Edit, Histogram, Link, MagicStick, Operation, Picture, Plus,
  Promotion, Rank, RefreshRight, VideoPlay, WarningFilled,
} from '@element-plus/icons-vue'
import {
  CASE_STATUS, CONFIDENCE_LABEL, CONFIDENCE_TYPE, END_TYPE_LABEL, PHASE_LABEL, PHASE_TYPE,
  PRIORITY_LABEL, PRIORITY_TYPE, REVIEW_ACTION_LABEL, SOURCE_LABEL, diffCaseVersion, getCase,
  acquireReviewLock, heartbeatReviewLock, releaseReviewLock,
  getCaseScript, listCaseVersions, listScriptVersions, diffScriptVersion, quickExecuteCase, regenerateScript, reviewCase, rollbackCaseVersion, updateCase, cloneCase,
  getCaseFlowchart, type CaseFlowchart,
  type CaseItem, type CaseScript, type CaseStep, type CaseVersionDiffResult,
  type CaseVersionItem, type ReviewAction, type UpdateCasePayload,
  type ScriptVersionDiffResult, type ScriptVersionItem,
} from '@/api/case'
import { bizErrorMsg } from '@/utils/bizError'
import { formatStepAnchor } from '@/utils/stepAnchorDisplay'
import { isMobileFamilyTarget } from '@/utils/automationTarget'
import { submitRegenWithOverwriteGuard } from '@/utils/scriptRegenConfirm'
import TaskTailDrawer from '@/components/TaskTailDrawer.vue'
import ResourceGapChips from '@/components/ResourceGapChips.vue'
import { openTaskProcessEventTail, mergeEvents, type ExtractionEvent, type TailHandle } from '@/api/taskEvents'
import type { BatchStartResult } from '@/api/batch'
import {
  DEFECT_CLOSE_LABEL, DEFECT_STATUS_LABEL, linkDefect, listDefectsByCase, rerunHintCase,
  updateDefectStatus, type DefectView,
} from '@/api/defect'
import { AI_TASK_STATUS, cancelAiTask } from '@/api/ai'
import { listCaseRequirements, REQUIREMENT_STATUS, unlinkCaseRequirement, type RequirementItem } from '@/api/requirement'
import { AI_FAILURE_CLASS, EXEC_TASK_STATUS, getCaseExecHistory, getExecTask, trialRunCase,
  type BatchCaseRerunResult, type CaseExecHistoryItem, type ExecTask } from '@/api/executor'
import { listScriptRepairs, type ScriptRepairView } from '@/api/scriptRepair'
import { listAppautoEnvProfiles, type AppautoEnvProfile } from '@/api/appauto'
import AppautoEnginePicker from '@/components/AppautoEnginePicker.vue'
import AppautoEngineRerunDialog from '@/components/AppautoEngineRerunDialog.vue'
import AppautoMatrixFanoutDialog from '@/components/AppautoMatrixFanoutDialog.vue'
import {
  confirmVisualBaseline,
  getVisualBaselineViewUrl,
  listVisualBaselines,
  rejectVisualBaseline,
  type VisualBaselineView,
} from '@/api/visualBaseline'
import { envTypeLabel, envTypeTag, getProjectEnvs, getProjectMembers, type ProjectEnv, type ProjectMember } from '@/api/project'
import { useAuthStore } from '@/stores/auth'
import { useAppStore } from '@/stores/app'
import { useAiTaskPolling } from '@/composables/useAiTaskPolling'
import { useGoBack } from '@/composables/useGoBack'
import { useDraftGuard } from '@/composables/useDraftGuard'
import { applyAutoDraftData, autoDraftKey, clearAutoDraft, useAutoDraft, type AutoDraftRecord } from '@/composables/useAutoDraft'
import DraftRestoreBar from '@/components/DraftRestoreBar.vue'
import MockSpacePicker from '@/components/MockSpacePicker.vue'
import BrowserSelect from '@/components/BrowserSelect.vue'
import SimilarityHintDialog from '@/components/SimilarityHintDialog.vue'
import ProblemTypeSelect from '@/components/ProblemTypeSelect.vue'
import { buildSimilarityQueryText, useSimilarityHint } from '@/composables/useSimilarityHint'
import { setBreadcrumbTitle } from '@/composables/useBreadcrumb'
import ExecTaskResultView from '@/components/ExecTaskResultView.vue'
import ProjectSelect from '@/components/ProjectSelect.vue'
import TraceChainPanel from '@/components/TraceChainPanel.vue'
import CommentPanel from '@/components/CommentPanel.vue'
import FavoriteButton from '@/components/FavoriteButton.vue'
import ExcellentStarButton from './ExcellentStarButton.vue'
import StepReorderPanel from './StepReorderPanel.vue'
import StepTree from '@/components/StepTree.vue'
import { moveInPlace } from '@/composables/useStepDragSort'
import { recordVisit } from '@/composables/useRecentVisits'
import { getCaseTraceChain, type TraceChainResult } from '@/api/trace'
import { fieldSnapshots, mergeUntouchedFields, useObjectSync } from '@/composables/useObjectSync'

// ---------- : instant sync of this case after a chat draft lands ----------
// Iron rules 2/3: view state silently re-reads the landed values, an open
// editor swaps only the fields the user has not typed (their draft survives,
// the version baseline advances into the existing column-merge path). No
// toast, no confirm, no navigation anywhere in this chain; SCRIPT_REPAIR
// refreshes the script block on the targeted-read posture.
let landingSyncing = false
async function syncLandedCase(eventType: string): Promise<void> {
  if (landingSyncing) return
  landingSyncing = true
  try {
    const fresh = await getCase(caseId)
    if (editMode.value) {
      // Iron rule 3 per field: a field the user has not typed since the
      // prefill/restore stamp swaps to the landed value, a typed field keeps
      // their draft. mergeUntouchedFields advances the stamp for every swapped
      // field, so back-to-back landings keep converging (the previous
      // whole-form guard skipped the merge whenever ANY field was dirty —
      // the E2E edit-state spectrum proved that contradicted rule 3).
      if (editFieldBaseline) {
        mergeUntouchedFields(editForm as unknown as Record<string, unknown>, editFieldBaseline, {
          title: fresh.title,
          precondition: fresh.precondition ?? '',
          expectedResult: fresh.expectedResult ?? '',
          priority: fresh.priority,
          confidence: fresh.confidence,
          endType: fresh.endType,
          phase: fresh.phase ?? undefined,
          automationTarget: fresh.automationTarget || 'web',
          appautoEngine: fresh.appautoEngine ?? null,
          appautoEnvProfileId: fresh.appautoEnvProfileId ?? null,
          mockSpaceId: fresh.mockSpaceId ?? null,
          steps: (fresh.steps || []).map((s) => ({ stepNo: s.stepNo, description: s.description, isAssert: s.isAssert ?? 0 })),
        })
      }
    }
    detail.value = fresh
    if (eventType === 'SCRIPT_REPAIR') await loadScript()
  } catch {
    // Silent per iron rule 2: the next landing or a manual reload converges.
  } finally {
    landingSyncing = false
  }
}
useObjectSync({
  // follow-up: rollback re-points the case version, same re-read.
  watch: ['CASE_WRITE', 'SCRIPT_REPAIR', 'CASE_ROLLBACK'],
  anchor: (e) => e.objectId === caseId,
  onSync: (e) => void syncLandedCase(e.objectType),
})

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const appStore = useAppStore()

// Embedding props (shared case-detail door): the link-cases dialog on the
// requirement page mounts this same page inside a dialog instead of a second
// read-only detail implementation. `embedded` drops the routed-page chrome
// (the back button) and the id comes from the door instead of the route.
const props = withDefaults(defineProps<{
  embedded?: boolean
  caseId?: number | string | null
}>(), { embedded: false, caseId: null })


const SCRIPT_REASON_ROLLBACK = '\u7528\u4f8b\u56de\u6eda\u91cd\u6302'
const caseId = Number(props.embedded ? props.caseId : route.params.id)
const loading = ref(true)
const saving = ref(false)

const similarityHint = useSimilarityHint()
const similarityDialogRef = similarityHint.dialogRef
const reviewing = ref(false)
const detail = ref<CaseItem | null>(null)
const members = ref<ProjectMember[]>([])

/** Step evidence anchor text for the steps table: the canonical payload shape is
 *  owned by the core anchor normalizer, this page only reads it. An unreadable or
 *  absent anchor renders as an honest empty cell (no invented trace source). */
function stepAnchorText(anchor?: string | null): string | null {
  return formatStepAnchor(anchor, t)
}

/** lineage: sandbox-promoted cases carry sourceRef "apitest:{requestId}"
 * written by core PromotionService — parse it honestly, anything malformed
 * simply hides the badge (no fabricated jump). */
const sandboxOriginId = computed<number | null>(() => {
  const refStr = detail.value?.sourceRef ?? ''
  const m = /^apitest:(\d+)$/.exec(refStr)
  return m ? Number(m[1]) : null
})

/** Origin jump: open API-test with the exact promoted request pre-selected
 *  (query deep link; the page relocates the collection to host it). */
function jumpToSandboxOrigin(): void {
  if (sandboxOriginId.value === null || detail.value === null) return
  void router.push({
    path: '/api-test',
    query: { projectId: String(detail.value.projectId), requestId: String(sandboxOriginId.value) },
  })
}

const defects = ref<DefectView[]>([])
const defectsLoading = ref(false)
const linkVisible = ref(false)
const linking = ref(false)
const rerunning = ref(false)
const linkForm = reactive({ defectKey: '', defectStatus: 'open' })


const builtinSummary = ref<{ caseId: number; total: number; openCount: number } | null>(null)


const closedDefect = computed(() => defects.value.find((d) => d.status === 2) ?? null)


const canCloseDefect = computed(() => {
  if (auth.isAdmin) return true
  const me = members.value.find((m) => m.userId === auth.user?.userId)
  return me?.role !== undefined && Number(me.role) === 2
})

const linkedRequirements = ref<RequirementItem[]>([])
const showReqAutoLinked = computed(() => isAutoLinkedRequirement(detail.value?.source))
/** Requirement ledger deep link rides the existing ids filter channel and
 * carries the case's own project so the landing segment is deterministic. */
function openRequirementLedger(row?: RequirementItem): void {
  const link = requirementLedgerDeepLink(Number(row?.id), detail.value?.projectId ?? null)
  if (link) void router.push(link)
}


const canUnlinkRequirement = computed(
  () => auth.user?.roles?.some((r) => r.toUpperCase() === 'DEVELOPER') !== true,
)

async function loadLinkedRequirements(): Promise<void> {
  try {
    linkedRequirements.value = await listCaseRequirements(caseId)
  } catch {
    linkedRequirements.value = []
  }
}


async function unlinkRequirement(row: RequirementItem): Promise<void> {
  try {
    await ElMessageBox.confirm(
      t('caseDetail.unlinkConfirmMsg', { title: row.title }),
      t('caseDetail.unlinkReq'),
      { type: 'warning', confirmButtonText: t('caseDetail.unlinkConfirmBtn'), cancelButtonText: t('caseDetail.unlinkThinkAgain') },
    )
  } catch {
    return
  }
  try {
    await unlinkCaseRequirement(caseId, row.id)
    ElMessage.success(t('caseDetail.unlinkDone'))
    await loadLinkedRequirements()
    void loadTraceChain()
  } catch {
    await loadLinkedRequirements()
  }
}

async function loadDefects(): Promise<void> {
  defectsLoading.value = true
  try {
    defects.value = await listDefectsByCase(caseId)
  } catch {
    defects.value = []
  } finally {
    defectsLoading.value = false
  }
}

async function loadBuiltinSummary(): Promise<void> {
  try {
    const { summaryByCase } = await import('@/api/defectLibrary')
    builtinSummary.value = await summaryByCase(caseId)
  } catch {
    builtinSummary.value = null
  }
}

function openLinkDialog(): void {
  linkForm.defectKey = ''
  linkForm.defectStatus = 'open'
  linkVisible.value = true
}

async function submitLink(): Promise<void> {
  const key = linkForm.defectKey.trim()
  if (!key) {
    ElMessage.warning(t('caseDetail.defectKeyRequired'))
    return
  }
  linking.value = true
  try {
    await linkDefect({ caseId, defectKey: key, defectStatus: linkForm.defectStatus })
    ElMessage.success(t('caseDetail.linkedToast', { key }))
    linkVisible.value = false
    await loadDefects()
  } catch (e) {
    ElMessage.error((e as Error).message || t('caseDetail.linkFailed'))
  } finally {
    linking.value = false
  }
}


async function changeDefectStatus(row: DefectView, status: string): Promise<void> {
  try {
    await updateDefectStatus(row.id, { defectStatus: status, version: row.version })
    ElMessage.success(
      status === 'closed'
        ? t('caseDetail.closedToast', { key: row.defectKey })
        : t('caseDetail.statusUpdatedToast', { key: row.defectKey, label: DEFECT_STATUS_LABEL[status]?.label ?? status }),
    )
    await loadDefects()
  } catch (e) {
    handleConflict(e, t('caseDetail.backfillFailed'))
  }
}


async function doRerun(): Promise<void> {
  rerunning.value = true
  try {
    await rerunHintCase(caseId)
    ElMessage.success(t('caseDetail.rerunStarted'))
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message || t('caseDetail.rerunFailed'))
  } finally {
    rerunning.value = false
  }
}

const editMode = ref(false)
const editFormRef = ref<FormInstance>()
const editForm = reactive<{
  title: string
  precondition: string
  expectedResult: string
  priority: number
  confidence: number
  endType: string
  phase?: number
  steps: CaseStep[]
 
  automationTarget: string
 
  appautoEngine: string | null
  
  appautoEnvProfileId: number | null
  /** Declared mock binding : picker value — null = no mock / untouched baseline */
  mockSpaceId: number | null
}>({ title: '', precondition: '', expectedResult: '', priority: 1, confidence: 2, endType: 'web', phase: undefined, steps: [], automationTarget: 'web', appautoEngine: null, appautoEnvProfileId: null, mockSpaceId: null })

const appautoProfiles = ref<AppautoEnvProfile[]>([])
async function loadAppautoProfiles(): Promise<void> {
  if (!detail.value) return
  try {
    appautoProfiles.value = await listAppautoEnvProfiles(detail.value.projectId)
  } catch {
    appautoProfiles.value = []
  }
}

function onAutomationTargetChange(): void {
  editForm.appautoEngine = null
  if (isMobileFamilyTarget(editForm.automationTarget)) void loadAppautoProfiles()
}

const automationTargetText = computed(() => {
  const d = detail.value
  if (!d) return ''
  const target = d.automationTarget || 'web'
  const label = target === 'app' ? t('appauto.picker.targetApp')
    : target === 'mp' ? t('appauto.picker.targetMp')
      : target === 'harmony' ? t('appauto.picker.targetHarmony')
        // Promoted sandbox cases carry api — label them honestly instead of
        // falling through to the web branch.
        : target === 'api' ? t('appauto.picker.targetApi') : t('appauto.picker.targetWeb')
  if (!d.automationTarget) return label
  const parts = [label]
  if (isMobileFamilyTarget(d.automationTarget)) {
    parts.push(d.appautoEngine || `${t('appauto.picker.engineLabel')}：${t('appauto.picker.autoOption')}`)
  } else if (d.appautoEngine) {
    parts.push(d.appautoEngine)
  }
  if (d.appautoEnvProfileId) {
    const p = appautoProfiles.value.find((x) => x.id === d.appautoEnvProfileId)
    parts.push(p ? `${t('appauto.picker.profileLabel')}：${p.name}` : `${t('appauto.picker.profileLabel')} #${d.appautoEnvProfileId}`)
  }
  return parts.join(' · ')
})

const editRules: FormRules = {
  title: [{ required: true, message: t('case.titleRequired'), trigger: 'blur' }],
}


const REVIEW_HEARTBEAT_MS = 60_000
const lockHeld = ref(false)
const lockBlockedMsg = ref('')
const lockAcquiring = ref(false)
let lockHeartbeatTimer: ReturnType<typeof setInterval> | null = null

function stopLockHeartbeat(): void {
  if (lockHeartbeatTimer !== null) {
    clearInterval(lockHeartbeatTimer)
    lockHeartbeatTimer = null
  }
}

function startLockHeartbeat(): void {
  stopLockHeartbeat()
  lockHeartbeatTimer = setInterval(async () => {
    try {
      await heartbeatReviewLock(caseId)
    } catch (e) {
      const err = e as { code?: string }
      if (String(err?.code) === '409' || String(err?.code) === '40904') {
        stopLockHeartbeat()
        lockHeld.value = false
        editMode.value = false
        lockBlockedMsg.value = bizErrorMsg(e, t('caseDetail.lockExpired'))
        ElMessage.warning(lockBlockedMsg.value)
      }
    }
  }, REVIEW_HEARTBEAT_MS)
}


function dropReviewLock(): void {
  stopLockHeartbeat()
  if (lockHeld.value) {
    lockHeld.value = false
    void releaseReviewLock(caseId).catch(() => {  })
  }
}


const editDraftKey = autoDraftKey('case', 'edit', caseId)
const editDraftBaseline = useDraftGuard(editForm as unknown as Record<string, unknown>, () => {  })
/** Per-field stamp taken at enterEdit prefill and auto-draft restore (
 * landing merge: a field still equal to its stamp was not user-typed). */
let editFieldBaseline: Record<string, string> | null = null
const editAutoDraft = useAutoDraft(editDraftKey, editForm as unknown as Record<string, unknown>, {
  enabled: () => editMode.value && editDraftBaseline.isDirty(),
})
onBeforeUnmount(editAutoDraft.stop)

function onEditDraftRestore(rec: AutoDraftRecord): void {
  const applied = applyAutoDraftData(editForm as unknown as Record<string, unknown>, rec.data)
  editDraftBaseline.snapshot()
  // Restored content becomes the new prefill stamp: field-level protection
  // in the BG landing merge still keys off per-field equality with it.
  editFieldBaseline = fieldSnapshots(editForm)
  ElMessage[applied ? 'success' : 'warning'](t(applied ? 'case.draftRestored' : 'case.draftRestoreEmpty'))
}

const assigneeText = computed(() => {
  const d = detail.value
  if (!d || !d.assigneeId) return t('case.unassigned')
  const m = members.value.find((x) => x.userId === d.assigneeId)
  return m ? (m.nickName || m.username || t('caseDetail.userRef', { id: d.assigneeId })) : t('caseDetail.userRef', { id: d.assigneeId })
})

const creatorText = computed(() => {
  const d = detail.value
  if (!d || !d.createdBy) return '-'
  const m = members.value.find((x) => x.userId === d.createdBy)
  return m ? (m.nickName || m.username || t('caseDetail.userRef', { id: d.createdBy })) : t('caseDetail.userRef', { id: d.createdBy })
})

function formatTime(v?: string | null): string {
  if (!v) return '-'
  return v.replace('T', ' ').slice(0, 16)
}

const traceChain = ref<TraceChainResult | null>(null)
const traceLoading = ref(false)

async function loadTraceChain(): Promise<void> {
  traceLoading.value = true
  try {
    traceChain.value = await getCaseTraceChain(caseId)
  } catch {
    traceChain.value = null
  } finally {
    traceLoading.value = false
  }
}

/** Dead-link handoff: the typed rejection from the server stays on screen instead of
 *  being flashed in a toast, because the guidance is only useful while it can be read. */
const loadFailure = ref<string | null>(null)
const failureKind = computed(() => classifyCaseLoadFailure(loadFailure.value))
const showRecycleDoor = computed(() => offersRecycleBinAction(failureKind.value))

function goCaseLibrary(recycle: boolean): void {
  const slice = route.query.projectId
  void router.replace(caseLibraryRoute(Array.isArray(slice) ? (slice[0] ?? '') : (slice ?? ''), recycle))
}

async function load(): Promise<void> {
  loading.value = true
  try {
    detail.value = await getCase(caseId)
    loadFailure.value = null
    setBreadcrumbTitle('CaseDetail', detail.value.title)
    recordVisit('case', caseId, detail.value.title)
    try {
      members.value = await getProjectMembers(detail.value.projectId)
    } catch {
      members.value = []
    }
    void loadHistory()
    void loadVersions()
    void loadVisualBaselines()
    void loadTraceChain()
    void loadLinkedRequirements()
    if (isMobileFamilyTarget(detail.value.automationTarget)) void loadAppautoProfiles()
  } catch (e) {
    loadFailure.value = (e as Error).message || t('caseDetail.loadFailed')
  } finally {
    loading.value = false
  }
}

const historyList = ref<CaseExecHistoryItem[]>([])
const historyLoading = ref(false)
const historyTotal = ref(0)
const historyPage = ref(1)


async function loadHistory(): Promise<void> {
  if (!detail.value) return
  historyLoading.value = true
  try {
    const res = await getCaseExecHistory(caseId, detail.value.projectId, historyPage.value, 10)
    historyList.value = res.list
    historyTotal.value = res.total
  } catch {
    historyList.value = []
    historyTotal.value = 0
  } finally {
    historyLoading.value = false
  }
}


function shotUrls(row: CaseExecHistoryItem): string[] {
  return row.screenshots.map((s) => s.url)
}

const VERSION_CHANGE_TYPE = computed<Record<number, { label: string; type: 'primary' | 'success' | 'info' | 'warning' }>>(() => ({
  1: { label: t('caseDetail.vtEdit'), type: 'primary' },
  2: { label: t('caseDetail.vtReview'), type: 'success' },
  3: { label: t('caseDetail.vtDraft'), type: 'info' },
  4: { label: t('caseDetail.vtRollback'), type: 'warning' },
}))
const versionList = ref<CaseVersionItem[]>([])
const versionLoading = ref(false)
const versionDiffVisible = ref(false)
const versionDiff = ref<CaseVersionDiffResult | null>(null)
const versionDiffTitle = ref(t('caseDetail.versionDiffPlain'))


const latestVersion = computed(() => versionList.value[0]?.version ?? 0)

async function loadVersions(): Promise<void> {
  versionLoading.value = true
  try {
    versionList.value = await listCaseVersions(caseId)
  } catch {
    versionList.value = []
  } finally {
    versionLoading.value = false
  }
}


async function openVersionDiff(row: CaseVersionItem, base: CaseVersionItem | undefined): Promise<void> {
  if (!base) return
  try {
    versionDiff.value = await diffCaseVersion(caseId, row.version, base.version)
    versionDiffTitle.value = t('caseDetail.versionDiffTitle', { from: base.version, to: row.version })
    versionDiffVisible.value = true
  } catch (e) {
    ElMessage.error((e as Error).message || t('caseDetail.versionDiffLoadFailed'))
  }
}


function renderDiffValue(v: unknown, field?: string): string {
  if (v == null || v === '') return t('caseDetail.emptyValue')
  if (typeof v === 'number' && field) {
    if (field === 'status') return CASE_STATUS[v]?.label ?? String(v)
    if (field === 'priority') return PRIORITY_LABEL[v] ?? String(v)
    if (field === 'confidence') return CONFIDENCE_LABEL[v] ?? String(v)
  }
  if (Array.isArray(v)) {
    return v.map((s) => `${(s as { stepNo?: number }).stepNo ?? ''}. ${(s as { description?: string }).description ?? ''}`).join('\n')
  }
  return typeof v === 'object' ? JSON.stringify(v) : String(v)
}


async function doRollback(row: CaseVersionItem): Promise<void> {
  if (!detail.value) return
  try {
    const res = await rollbackCaseVersion(caseId, row.version, detail.value.version)
    ElMessage.success(t('caseDetail.rollbackDone', { ver: row.version, newVer: res.newVersion }))
    await load()
    void loadScript()
  } catch (e) {
    handleConflict(e, t('caseDetail.rollbackFailed'))
  }
}

const repairList = ref<ScriptRepairView[]>([])
const repairLoading = ref(false)
const repairTotal = ref(0)
const repairPage = ref(1)

async function loadRepairRecords(): Promise<void> {
  repairLoading.value = true
  try {
    const res = await listScriptRepairs({ caseId, page: repairPage.value, size: 10 })
    repairList.value = res.list
    repairTotal.value = res.total
  } catch {
    repairList.value = []
    repairTotal.value = 0
  } finally {
    repairLoading.value = false
  }
}

const baselineList = ref<VisualBaselineView[]>([])
const baselineLoading = ref(false)

const baselineViewUrls = ref<Record<number, string>>({})
const baselineBusy = ref<Record<number, boolean>>({})
const canVisualAssert = computed(() => auth.hasPerm('perm:script:visual-assert'))

const pendingBaselineCount = computed(() => baselineList.value.filter((b) => b.status === 1).length)

async function loadVisualBaselines(): Promise<void> {
  if (!detail.value) return
  baselineLoading.value = true
  try {
    baselineList.value = await listVisualBaselines({ projectId: detail.value.projectId, caseId })
    baselineViewUrls.value = {}
    void refreshBaselineUrls()
  } catch {
    baselineList.value = []
  } finally {
    baselineLoading.value = false
  }
}

async function refreshBaselineUrls(): Promise<void> {
  const rows = [...baselineList.value]
  const map: Record<number, string> = {}
  for (const row of rows) {
    try {
      map[row.id] = await getVisualBaselineViewUrl(row.id)
    } catch {
      
    }
  }
  baselineViewUrls.value = map
}


async function confirmBaseline(row: VisualBaselineView): Promise<void> {
  try {
    await ElMessageBox.confirm(
      t('caseDetail.baselineConfirmTip', { key: row.stepKey }),
      t('caseDetail.baselineConfirmTitle'),
      { type: 'warning' },
    )
  } catch {
    return 
  }
  baselineBusy.value[row.id] = true
  try {
    await confirmVisualBaseline(row.id)
    ElMessage.success(t('caseDetail.baselineConfirmed'))
    await loadVisualBaselines()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('caseDetail.baselineConfirmFailed')))
  } finally {
    delete baselineBusy.value[row.id]
  }
}


async function rejectBaseline(row: VisualBaselineView): Promise<void> {
  try {
    await ElMessageBox.confirm(
      t('caseDetail.baselineRejectTip'),
      t('caseDetail.baselineRejectTitle'),
      { type: 'warning' },
    )
  } catch {
    return
  }
  baselineBusy.value[row.id] = true
  try {
    await rejectVisualBaseline(row.id)
    ElMessage.success(t('caseDetail.baselineRejected'))
    await loadVisualBaselines()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('caseDetail.baselineRejectFailed')))
  } finally {
    delete baselineBusy.value[row.id]
  }
}

const scriptVersionList = ref<ScriptVersionItem[]>([])
const scriptVersionLoading = ref(false)
const scriptVersionDiffVisible = ref(false)
const scriptVersionDiff = ref<ScriptVersionDiffResult | null>(null)
const scriptVersionDiffTitle = ref(t('caseDetail.scriptDiffPlain'))

async function loadScriptVersions(): Promise<void> {
  scriptVersionLoading.value = true
  try {
    scriptVersionList.value = await listScriptVersions(caseId)
  } catch {
    scriptVersionList.value = []
  } finally {
    scriptVersionLoading.value = false
  }
}


async function openScriptVersionDiff(row: ScriptVersionItem, base: ScriptVersionItem | undefined): Promise<void> {
  if (!base) return
  try {
    scriptVersionDiff.value = await diffScriptVersion(caseId, row.scriptVersion, base.scriptVersion)
    scriptVersionDiffTitle.value = t('caseDetail.scriptDiffTitle', { from: base.scriptVersion, to: row.scriptVersion })
    scriptVersionDiffVisible.value = true
  } catch (e) {
    ElMessage.error((e as Error).message || t('caseDetail.scriptDiffLoadFailed'))
  }
}


function renderScriptDiffValue(v: unknown): string {
  if (v == null || v === '') return t('caseDetail.emptyValue')
  if (Array.isArray(v)) {
    return v
      .map((s, i) => {
        const step = s as { action?: string; target?: unknown; value?: unknown }
        const parts = [`${i + 1}. ${step.action ?? t('caseDetail.unknownAction')}`]
        if (step.target !== undefined) parts.push(`${t('caseDetail.diffTarget')}=${JSON.stringify(step.target)}`)
        if (step.value !== undefined) parts.push(`${t('caseDetail.diffValue')}=${JSON.stringify(step.value)}`)
        return parts.join(' ')
      })
      .join('\n')
  }
  if (typeof v === 'object') return JSON.stringify(v, null, 2)
  return String(v)
}

const flowchart = ref<CaseFlowchart | null>(null)
const flowchartSvg = ref('')
const flowchartLoading = ref(false)
const flowchartError = ref('')

let mermaidApi: typeof import('mermaid').default | null = null
let flowchartRenderSeq = 0

async function ensureMermaid(): Promise<typeof import('mermaid').default> {
  if (!mermaidApi) {
    const mod = await import('mermaid')
    mermaidApi = mod.default
    mermaidApi.initialize({
      startOnLoad: false,
      securityLevel: 'strict',
      theme: appStore.theme === 'dark' ? 'dark' : 'default',
    })
  }
  return mermaidApi
}

async function loadFlowchart(): Promise<void> {
  const seq = ++flowchartRenderSeq
  flowchartLoading.value = true
  flowchartError.value = ''
  try {
    const data = await getCaseFlowchart(caseId)
    if (seq !== flowchartRenderSeq) return
    flowchart.value = data
    flowchartSvg.value = ''
    try {
      const mmd = await ensureMermaid()
      const { svg } = await mmd.render(`tp-flowchart-${caseId}-${seq}`, data.mermaid)
      if (seq === flowchartRenderSeq) flowchartSvg.value = svg
    } catch {
      flowchartSvg.value = ''
    }
  } catch (e: unknown) {
    if (seq !== flowchartRenderSeq) return
    flowchart.value = null
    flowchartError.value = bizErrorMsg(e, t('caseDetail.flowchartLoadFailed'))
  } finally {
    if (seq === flowchartRenderSeq) flowchartLoading.value = false
  }
}

watch(() => appStore.theme, () => {
  mermaidApi = null
  if (flowchart.value) void loadFlowchart()
})

const reorderPanelRef = ref<InstanceType<typeof StepReorderPanel> | null>(null)
const reorderSaving = ref(false)

async function saveReorderedSteps(reordered: CaseStep[]): Promise<void> {
  const d = detail.value
  if (!d) return
  reorderSaving.value = true
  try {
    await updateCase(caseId, {
      steps: reordered.map((s, i) => ({ stepNo: i + 1, description: s.description, isAssert: s.isAssert ?? 0 })),
      version: d.version,
    })
    ElMessage.success(t('caseDetail.reorderSaved'))
    await load()
    await loadScript()
  } catch (e) {
    reorderPanelRef.value?.discard()
    handleConflict(e, t('caseDetail.reorderFailed'))
  } finally {
    reorderSaving.value = false
  }
}

const HISTORY_COMPARE_MAX = 5
const historyTableRef = ref<ElTableInstance | null>(null)
const historyCompareRows = ref<CaseExecHistoryItem[]>([])
const historyCompareVisible = ref(false)

function onHistorySelectionChange(rows: CaseExecHistoryItem[]): void {
  if (rows.length > HISTORY_COMPARE_MAX) {
    historyCompareRows.value = rows.slice(-HISTORY_COMPARE_MAX)
    historyTableRef.value?.clearSelection()
    historyCompareRows.value.forEach((r) => historyTableRef.value?.toggleRowSelection(r, true))
    ElMessage.warning(t('exec.historyCompareNeedTwo', { max: HISTORY_COMPARE_MAX }))
    return
  }
  historyCompareRows.value = rows
}


const historyCompareMetrics = computed(() => {
  const byId = (fn: (r: CaseExecHistoryItem) => string): Record<number, string> =>
    Object.fromEntries(historyCompareRows.value.map((r) => [r.id, fn(r)]))
  return [
    { key: 'source', label: t('exec.historyCompareSource'), values: byId((r) => r.batchName || t('caseDetail.trialRun')) },
    { key: 'time', label: t('exec.historyCompareTime'), values: byId((r) => formatTime(r.finishedAt || r.startedAt)) },
    { key: 'env', label: t('exec.historyCompareEnv'), values: byId((r) => r.envVersion || '') },
    { key: 'result', label: t('exec.historyCompareResult'), values: {} as Record<number, string> },
    { key: 'engine', label: t('caseDetail.colEngine'), values: byId((r) => r.engineCode || '') },
    { key: 'duration', label: t('exec.historyCompareDuration'), values: byId((r) => (r.durationMs != null ? `${(r.durationMs / 1000).toFixed(1)}s` : '')) },
    { key: 'error', label: t('exec.historyCompareError'), values: byId((r) => r.errorDetail || '') },
    { key: 'shots', label: t('exec.historyCompareShots'), values: {} as Record<number, string> },
  ]
})

function openHistoryCompare(): void {
  if (historyCompareRows.value.length < 2) {
    ElMessage.warning(t('exec.historyCompareNeedTwo', { max: HISTORY_COMPARE_MAX }))
    return
  }
  historyCompareVisible.value = true
}

const engineRerunVisible = ref(false)
const engineRerunRow = ref<CaseExecHistoryItem | null>(null)

const matrixFanoutVisible = ref(false)
const isAppautoCase = computed(() => isMobileFamilyTarget(detail.value?.automationTarget))

function openEngineRerun(row: CaseExecHistoryItem): void {
  engineRerunRow.value = row
  engineRerunVisible.value = true
}

function onEngineRerunDone(res: BatchCaseRerunResult): void {
  ElMessage.success(t('caseDetail.engineRerunDone', { msg: res.message || String(res.taskId) }))
  void loadHistory()
}


const goBack = useGoBack('/cases')

async function enterEdit(): Promise<void> {
  const d = detail.value
  if (!d) return
  lockAcquiring.value = true
  try {
    await acquireReviewLock(caseId)
  } catch (e) {
    const err = e as { code?: string }
    if (String(err?.code) === '409' || String(err?.code) === '40904') {
      lockBlockedMsg.value = bizErrorMsg(e, t('caseDetail.lockOccupiedFallback'))
      return
    }
    ElMessage.error(bizErrorMsg(e, t('caseDetail.enterEditFailed')))
    return
  } finally {
    lockAcquiring.value = false
  }
  lockBlockedMsg.value = ''
  lockHeld.value = true
  startLockHeartbeat()
  editForm.title = d.title
  editForm.precondition = d.precondition || ''
  editForm.expectedResult = d.expectedResult || ''
  editForm.priority = d.priority
  editForm.confidence = d.confidence
  editForm.endType = d.endType
  editForm.phase = d.phase ?? undefined
  editForm.automationTarget = d.automationTarget || 'web'
  editForm.appautoEngine = d.appautoEngine || null
  editForm.appautoEnvProfileId = d.appautoEnvProfileId ?? null
  editForm.mockSpaceId = d.mockSpaceId ?? null
  if (isMobileFamilyTarget(editForm.automationTarget)) void loadAppautoProfiles()
  editForm.steps = (d.steps || []).map((s) => ({ stepNo: s.stepNo, description: s.description, isAssert: s.isAssert ?? 0 }))
  editMode.value = true
  editDraftBaseline.snapshot()
  editFieldBaseline = fieldSnapshots(editForm)
}

function exitEdit(): void {
  editMode.value = false
  movedEditIdx.value = -1
  editDragIdx.value = -1
  dropReviewLock()
  clearAutoDraft(editDraftKey)
}

function addStep(): void {
  editForm.steps.push({ stepNo: editForm.steps.length + 1, description: '', isAssert: 0 })
}

function removeStep(idx: number): void {
  editForm.steps.splice(idx, 1)
  editForm.steps.forEach((s, i) => { s.stepNo = i + 1 })
  movedEditIdx.value = -1
}

const editDragIdx = ref(-1)

const movedEditIdx = ref(-1)

function onEditStepDragStart(idx: number): void {
  editDragIdx.value = idx
  movedEditIdx.value = idx
}

function onEditStepDragOver(idx: number): void {
  if (editDragIdx.value < 0 || editDragIdx.value === idx) return
  moveInPlace(editForm.steps, editDragIdx.value, idx)
  editForm.steps.forEach((s, i) => { s.stepNo = i + 1 })
  editDragIdx.value = idx
  movedEditIdx.value = idx
}

function onEditStepDragEnd(): void {
  editDragIdx.value = -1
  movedEditIdx.value = -1
}


function buildAutomationTargetPayload(
  serverTarget: string | null | undefined,
  formTarget: string,
  formEngine: string | null,
  formProfileId: number | null,
): Pick<UpdateCasePayload, 'automationTarget' | 'appautoEngine' | 'appautoEnvProfileId'> {
  const server = serverTarget || 'web'
  if (formTarget === 'web') {
    return server === 'web' ? {}
      : { automationTarget: 'web', appautoEngine: undefined, appautoEnvProfileId: undefined }
  }
  if (formTarget === server) {
    const d = detail.value
    const engineSame = (d?.appautoEngine || null) === formEngine
    const profileSame = (d?.appautoEnvProfileId ?? null) === formProfileId
    if (engineSame && profileSame) return {}
    return {
      automationTarget: formTarget,
      appautoEngine: formEngine || '',
      appautoEnvProfileId: formProfileId ?? 0,
    }
  }
  return {
    automationTarget: formTarget,
    appautoEngine: formEngine || '',
    appautoEnvProfileId: formProfileId ?? 0,
  }
}

async function saveDraft(): Promise<void> {
  if (!editFormRef.value || !detail.value) return
  const valid = await editFormRef.value.validate().catch(() => false)
  if (!valid) return
  const simGateOk = await similarityHint.gate(
    detail.value.projectId,
    buildSimilarityQueryText({
      title: editForm.title,
      expectedResult: editForm.expectedResult,
      steps: editForm.steps,
    }),
    caseId,
  )
  if (!simGateOk) return
  saving.value = true
  try {
    const steps = editForm.steps
      .filter((s) => s.description.trim())
      .map((s, i) => ({ stepNo: i + 1, description: s.description.trim(), isAssert: s.isAssert ?? 0 }))
    const targetPayload = buildAutomationTargetPayload(
      detail.value.automationTarget, editForm.automationTarget,
      editForm.appautoEngine, editForm.appautoEnvProfileId,
    )
    // Mock binding three-state: untouched -> null (no update); switched to
    // "no mock" -> 0 (unbind sentinel); a space id -> (re)bind.
    const mockSpaceChanged = editForm.mockSpaceId !== (detail.value.mockSpaceId ?? null)
    await updateCase(caseId, {
      title: editForm.title.trim(),
      precondition: editForm.precondition.trim() || undefined,
      expectedResult: editForm.expectedResult.trim() || undefined,
      priority: editForm.priority,
      confidence: editForm.confidence,
      endType: editForm.endType,
      phase: editForm.phase,
      ...targetPayload,
      mockSpaceId: mockSpaceChanged ? editForm.mockSpaceId ?? 0 : null,
      steps: steps.length ? steps : undefined,
      version: detail.value.version,
    })
    ElMessage.success(t('caseDetail.draftSaved'))
    clearAutoDraft(editDraftKey)
    editMode.value = false
    dropReviewLock()
    await load()
    await loadScript()
  } catch (e) {
    handleConflict(e, t('caseDetail.draftConflict'))
  } finally {
    saving.value = false
  }
}

const reasonVisible = ref(false)
const reasonText = ref('')
const reasonTypes = ref<string[]>([])
const reasonAction = ref<ReviewAction>('need_change')

async function doReview(action: ReviewAction): Promise<void> {
  if (!detail.value) return
  if (action === 'pass') {
    try {
      await ElMessageBox.confirm(
        t('case.approveConfirmMsg', { caseNo: detail.value.caseNo }),
        t('case.approveConfirmTitle'),
        { confirmButtonText: t('case.approve'), cancelButtonText: t('common.cancel'), type: 'warning' },
      )
    } catch {
      return
    }
    await submitReview(action, '')
  } else {
    reasonAction.value = action
    reasonText.value = ''
    reasonTypes.value = []
    reasonVisible.value = true
  }
}

async function submitReason(): Promise<void> {
  if (!reasonText.value.trim()) {
    ElMessage.warning(t('case.remarkRequired'))
    return
  }
  if (!reasonTypes.value.length) {
    ElMessage.warning(t('case.problemTypesRequired'))
    return
  }
  await submitReview(reasonAction.value, reasonText.value.trim(), reasonTypes.value)
}

async function submitReview(action: ReviewAction, remark: string, feedbackTypes?: string[]): Promise<void> {
  const d = detail.value
  if (!d) return
  reviewing.value = true
  try {
    await reviewCase(caseId, { action, version: d.version, remark: remark || undefined, feedbackTypes }, `ui-${caseId}-${Date.now()}`)
    ElMessage.success(t('case.reviewSuccess', { caseNo: d.caseNo, action: REVIEW_ACTION_LABEL[action] }))
    reasonVisible.value = false
    await load()
  } catch (e) {
    handleConflict(e, t('case.reviewFail409'))
  } finally {
    reviewing.value = false
  }
}


function handleConflict(e: unknown, fallback: string): void {
  const msg = (e as Error).message || ''
  if (msg.includes('409') || msg.includes('\u7248\u672c') || msg.includes('\u72b6\u6001')) {
    ElMessage.warning(msg || fallback)
    void load()
  } else {
    ElMessage.error(msg || t('common.failed'))
  }
}

const script = ref<CaseScript | null>(null)
const scriptLoading = ref(true)

const stepTreeVisible = ref(false)
const scriptTaskId = ref<number | null>(null)
const { task: scriptTask, startPolling: startScriptPolling } = useAiTaskPolling(scriptTaskId)
// segment c: "ignore" is scoped to the generation task whose events were
// shown; a freshly submitted task re-arms the chip row for its own window.
const gapsDismissedTaskId = ref<number | null>(null)
const gapsDismissed = computed(
  () => scriptTaskId.value != null && gapsDismissedTaskId.value === scriptTaskId.value,
)
function dismissGaps(): void {
  gapsDismissedTaskId.value = scriptTaskId.value
}

// S4 segment D: the script-generation run tails the same shared drawer over
// the task door (GET /ai/tasks/{id}/process-events). Rows stay on screen after
// the run settles so the final state is reviewable; the tail closes at the
// terminal frame or on leaving the page (one stream per open drawer).
const scriptTailEvents = ref<ExtractionEvent[]>([])
let scriptTailHandle: TailHandle | null = null
const scriptTaskLive = computed(() =>
  // Settled = done(3), failed(4) or stopped by a human(5); only then the tail stops being live.
  !!scriptTask.value && scriptTask.value.status !== 3 && scriptTask.value.status !== 4 && scriptTask.value.status !== 5)

const stoppingScript = ref(false)

/** Stop the running script-generation task from the tail door. One click, no second
 *  confirm: stopping writes no case data. The core settles the row as cancelled and the
 *  poller picks the new state up; a refusal surfaces the server's readable reason. */
async function stopScriptTask(): Promise<void> {
  if (scriptTaskId.value == null || stoppingScript.value) return
  stoppingScript.value = true
  try {
    await cancelAiTask(scriptTaskId.value)
    ElMessage.success(t('aiGenerate.stopOk'))
    await startScriptPolling()
  } catch (e: unknown) {
    ElMessage.error(bizErrorMsg(e, t('aiGenerate.stopFail')))
  } finally {
    stoppingScript.value = false
  }
}

function openScriptTail(taskId: number): void {
  closeScriptTail()
  scriptTailEvents.value = []
  scriptTailHandle = openTaskProcessEventTail(taskId, 0, batch => {
    scriptTailEvents.value = mergeEvents(scriptTailEvents.value, batch)
  })
}

function closeScriptTail(): void {
  scriptTailHandle?.close()
  scriptTailHandle = null
}

const prettyScript = computed(() => {
  if (!script.value?.actionJson) return ''
  try {
    return JSON.stringify(JSON.parse(script.value.actionJson), null, 2)
  } catch {
    return script.value.actionJson
  }
})

async function loadScript(): Promise<void> {
  scriptLoading.value = true
  try {
    script.value = await getCaseScript(caseId)
  } catch {
    script.value = null
  } finally {
    scriptLoading.value = false
  }
  void loadScriptVersions()
  void loadRepairRecords()
  void loadFlowchart()
}


async function regenScript(): Promise<void> {
  let taskId: number | null
  try {
    taskId = await submitRegenWithOverwriteGuard(
      (force) => regenerateScript(caseId, force),
      async (message) => {
        await ElMessageBox.confirm(message, t('caseDetail.overwriteTitle'), {
          type: 'warning',
          confirmButtonText: t('caseDetail.overwriteConfirmBtn'),
          cancelButtonText: t('common.cancel'),
        })
      },
    )
  } catch (e) {
    if (e === 'cancel' || e === 'close') return
    ElMessage.error((e as Error).message || t('caseDetail.regenSubmitFailed'))
    return
  }
  if (taskId == null) {
    ElMessage.warning(t('caseDetail.regenNoTaskId'))
    return
  }
  scriptTaskId.value = taskId
  openScriptTail(taskId)
  await startScriptPolling()
  ElMessage.success(t('caseDetail.regenSubmitted', { id: taskId }))
}

watch(scriptTask, (task) => {
  if (task && (task.status === 3 || task.status === 4)) {
    closeScriptTail()
  }
  if (task && task.status === 3) {
    ElMessage.success(t('caseDetail.regenDone'))
    void loadScript()
  } else if (task && task.status === 4) {
    ElMessage.error(task.errorMsg || t('caseDetail.regenFailed'))
  }
})

const trialVisible = ref(false)
const trialTask = ref<ExecTask | null>(null)
const trialSubmitting = ref(false)
const envSelectVisible = ref(false)
const envs = ref<ProjectEnv[]>([])
const envLoading = ref(false)
const selectedEnvId = ref<number | null>(null)
let lastEnvId: number | null = null
let trialTimer: number | null = null
const envSelectMode = ref<'trial' | 'quick'>('trial')
const quickSubmitting = ref(false)
const quickResultVisible = ref(false)
const quickResult = ref<BatchStartResult | null>(null)
const quickBrowser = ref<string | null>(null)
const selectedEnvBrowsers = computed<string | null>(
  () => envs.value.find((e) => e.id === selectedEnvId.value)?.browsers ?? null)


function trialRunNoScript(): void {
  ElMessage.warning(t('exec.noScript'))
}


async function openEnvSelect(mode: 'trial' | 'quick' = 'trial'): Promise<void> {
  const projectId = detail.value?.projectId
  if (!projectId) return
  envLoading.value = true
  try {
    envs.value = await getProjectEnvs(projectId)
    if (envs.value.length === 0) {
      ElMessage.warning(t('exec.noEnv'))
      return
    }
    const usable = envs.value.filter((e) => e.envType !== 4)
    selectedEnvId.value = lastEnvId ?? usable[0]?.id ?? null
    envSelectMode.value = mode
    envSelectVisible.value = true
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('exec.envLoadFail')))
  } finally {
    envLoading.value = false
  }
}


async function startTrialRun(envId?: number): Promise<void> {
  if (trialSubmitting.value) return
  if (envId === undefined || typeof envId !== 'number') {
    await openEnvSelect('trial')
    return
  }
  trialSubmitting.value = true
  try {
    const res = await trialRunCase(caseId, envId)
    lastEnvId = envId
    trialTask.value = null
    trialVisible.value = true
    ElMessage.success(t('exec.trialRunSubmit', { taskId: res.taskId }))
    startTrialPolling(res.taskId)
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('exec.trialRunSubmitFail', { msg: '' })))
  } finally {
    trialSubmitting.value = false
  }
}


async function startQuickExecute(envId?: number): Promise<void> {
  if (quickSubmitting.value) return
  if (envId === undefined || typeof envId !== 'number') {
    await openEnvSelect('quick')
    return
  }
  await submitQuickExecute(envId)
}


async function submitEnvSelect(): Promise<void> {
  if (selectedEnvId.value == null) {
    ElMessage.warning(t('exec.envRequired'))
    return
  }
  const envId = selectedEnvId.value
  envSelectVisible.value = false
  if (envSelectMode.value === 'quick') {
    await submitQuickExecute(envId)
  } else {
    await startTrialRun(envId)
  }
}


async function submitQuickExecute(envId: number): Promise<void> {
  quickSubmitting.value = true
  try {
    const res = await quickExecuteCase(caseId, envId, quickBrowser.value)
    lastEnvId = envId
    quickResult.value = res
    quickResultVisible.value = true
    ElMessage.success(t('exec.quickSubmitOk'))
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('exec.quickSubmitFail')))
  } finally {
    quickSubmitting.value = false
  }
}


function gotoQuickBatch(): void {
  if (quickResult.value) {
    quickResultVisible.value = false
    void router.push(`/batches/${quickResult.value.batchId}`)
  }
}



const canClone = computed(
  () => auth.user?.roles?.some((r) => r.toUpperCase() === 'DEVELOPER') !== true,
)


const sourceProjectName = computed(() => {
  const pid = detail.value?.projectId
  if (!pid) return ''
  const p = auth.projects.find((x) => x.projectId === pid)
  return p?.projectName || p?.projectCode || `#${pid}`
})

const cloneVisible = ref(false)
const cloneSubmitting = ref(false)
const cloneTargetMode = ref<'same' | 'other'>('same')
const cloneTargetProjectId = ref<number | null>(null)


function openCloneDialog(): void {
  cloneTargetMode.value = 'same'
  cloneTargetProjectId.value = null
  cloneVisible.value = true
}


async function submitClone(): Promise<void> {
  if (cloneSubmitting.value) return
  const d = detail.value
  if (!d) return
  let target: number | null = null
  if (cloneTargetMode.value === 'other') {
    if (cloneTargetProjectId.value == null) {
      ElMessage.warning(t('caseDetail.cloneTargetRequired'))
      return
    }
    if (cloneTargetProjectId.value === d.projectId) {
      target = null
    } else {
      target = cloneTargetProjectId.value
    }
  }
  cloneSubmitting.value = true
  try {
    const cloned = await cloneCase(d.id, target)
    cloneVisible.value = false
    ElMessage.success(t('caseDetail.cloneDone', { caseNo: cloned.caseNo }))
    void router.push({
      path: '/cases',
      query: { projectId: cloned.projectId, keyword: cloned.caseNo },
    })
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('caseDetail.cloneFail')))
  } finally {
    cloneSubmitting.value = false
  }
}


function reRunTrial(): void {
  if (lastEnvId != null) {
    void startTrialRun(lastEnvId)
  } else {
    void openEnvSelect()
  }
}


function startTrialPolling(taskId: number): void {
  stopTrialPolling()
  const fetchOnce = async (): Promise<void> => {
    try {
      const task = await getExecTask(taskId)
      trialTask.value = task
      if ([3, 4, 5, 6].includes(task.status)) {
        stopTrialPolling()
        void loadHistory()
        if (task.status === 3) {
          ElMessage.success(t('exec.trialRunDone', { status: t('exec.statusSuccess'), ms: task.durationMs ?? '-' }))
        } else if (task.status === 4 || task.status === 5) {
          ElMessage.error(t('exec.trialRunFail', {
            status: EXEC_TASK_STATUS[task.status]?.label ?? task.status,
            detail: task.errorDetail || '',
          }))
        }
      }
    } catch {
    }
  }
  void fetchOnce()
  trialTimer = window.setInterval(() => {
    const cur = trialTask.value
    if (cur && [3, 4, 5, 6].includes(cur.status)) {
      stopTrialPolling()
      return
    }
    void fetchOnce()
  }, 2000)
}

function stopTrialPolling(): void {
  if (trialTimer !== null) {
    window.clearInterval(trialTimer)
    trialTimer = null
  }
}

onMounted(() => {
  void load()
  void loadScript()
  void loadDefects()
  void loadBuiltinSummary()
})

onBeforeUnmount(() => {
  stopTrialPolling()
  closeScriptTail()
  dropReviewLock()
  setBreadcrumbTitle('CaseDetail', null)
})
</script>

<style scoped lang="scss">
.detail-header {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 16px;

  .header-main {
    flex: 1;
    min-width: 0;
  }

  .header-title-row {
    display: flex;
    align-items: center;
    gap: 12px;
    flex-wrap: wrap;
    margin-bottom: 8px;
  }

  .detail-no {
    color: var(--tp-text-secondary);
    font-size: 14px;
  }

  .header-tags {
    display: flex;
    align-items: center;
    gap: 8px;
    flex-wrap: wrap;
  }
}

.detail-desc {
  margin-bottom: 6px;
}

.detail-block {
  margin-top: 12px;
  padding: 12px 14px;
  border-radius: 8px;
  background: var(--tp-bg-hover);
  border: 1px solid var(--tp-border);

  .detail-block-label {
    font-size: 12px;
    color: var(--tp-primary);
    font-weight: 600;
    margin-bottom: 6px;
  }

  .detail-block-body {
    font-size: 13px;
    line-height: 1.8;
    color: var(--tp-text);
    white-space: pre-wrap;
    word-break: break-word;
  }
}

.section-title {
  margin: 16px 0 10px;
  font-size: 16px;
  color: var(--tp-text);
  display: flex;
  align-items: center;
  gap: 8px;

  &::before {
    content: '';
    display: inline-block;
    width: 4px;
    height: 14px;
    border-radius: 2px;
    background: var(--tp-primary);
  }
}

.review-lock-banner {
  margin-top: 16px;

  :deep(.el-button) {
    margin-top: 8px;
  }
}

.review-lock-holding {
  margin-bottom: 12px;
}

.detail-actions {
  display: flex;
  gap: 10px;
  margin-top: 16px;
  flex-wrap: wrap;
}

.edit-form {
  margin-top: 4px;
}

.steps-editor {
  width: 100%;

  .step-row {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 8px;

    &.is-moved {
      outline: 1px dashed var(--el-color-warning);
      outline-offset: 2px;
    }

    .step-grip {
      display: inline-flex;
      align-items: center;
      color: var(--el-text-color-placeholder);
      cursor: grab;
      flex-shrink: 0;

      &:hover {
        color: var(--el-color-primary);
      }
    }

    .step-idx {
      width: 20px;
      text-align: center;
      color: var(--tp-text-secondary);
      font-weight: 600;
    }
  }
}

.edit-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 6px;
}

.script-block {
  margin-top: 16px;

  .script-actions {
    display: flex;
    align-items: center;
    gap: 10px;
    margin-bottom: 10px;
  }

  .script-task-info {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    font-size: 12px;
    color: var(--tp-text-secondary);
  }

  .script-json {
    background: var(--tp-bg-code, #0f172a);
    color: #7dd3fc;
    border: 1px solid var(--tp-border);
    border-radius: 10px;
    padding: 12px 16px;
    font-size: 12px;
    line-height: 1.6;
    font-family: 'JetBrains Mono', Consolas, monospace;
    overflow-x: auto;
    max-height: 420px;
    overflow-y: auto;
    white-space: pre;
    margin: 0;
  }

  .script-empty {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 16px 14px;
    border: 1px dashed var(--tp-border);
    border-radius: 10px;
    color: var(--tp-text-secondary);
    font-size: 13px;
    flex-wrap: wrap;

    .script-empty-actions {
      display: flex;
      align-items: center;
      gap: 10px;
      margin-left: auto;
    }
  }
}

.trial-dialog-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;

  .trial-hint {
    font-size: 13px;
    color: var(--tp-text-secondary);
    flex: 1;
    text-align: left;
    word-break: break-all;
  }
}


.env-option {
  display: flex;
  align-items: center;
  gap: 8px;

  .env-option-version {
    font-size: 12px;
    color: var(--tp-text-secondary);
  }

  .env-prod-warn {
    font-size: 12px;
    color: var(--el-color-danger);
  }
}

.env-select-hint {
  margin: 10px 0 0;
  font-size: 12px;
  color: var(--tp-text-secondary);
  line-height: 1.6;
}


.clone-hint {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--tp-text-secondary);
  line-height: 1.6;
}

.clone-target-mode {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
}

.clone-target-select {
  margin-top: 10px;
}

.clone-target-note {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--tp-text-secondary);
  line-height: 1.6;
}


.quick-message {
  margin: 0 0 12px;
  font-size: 14px;
  color: var(--tp-text-primary);
  line-height: 1.6;
}

.quick-meta {
  margin-bottom: 12px;
}

.quick-skip-table {
  margin-bottom: 4px;
}

.trace-block {
  margin-top: 16px;
}

.req-block {
  margin-top: 16px;

  // Rows of the linked-requirement table navigate to the requirement
  // ledger, so they carry the standard pointer affordance (vision review
  // follow-up: clickable rows looked inert without it).
  .req-row-link :deep(.el-table__body .el-table__row) {
    cursor: pointer;
  }

  .req-empty {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 12px 16px;
    border: 1px dashed var(--tp-border);
    border-radius: 10px;
    color: var(--tp-text-secondary);
    font-size: 13px;
  }
}

.defect-block {
  margin-top: 16px;

  .defect-toolbar {
    margin-bottom: 10px;
  }

  .builtin-defect-summary {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 10px;
    padding: 8px 12px;
    border-radius: 6px;
    background: var(--el-fill-color-light);
    font-size: 13px;
    color: var(--el-text-color-regular);
  }

  .defect-empty {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 12px 16px;
    border: 1px dashed var(--tp-border);
    border-radius: 10px;
    color: var(--tp-text-secondary);
    font-size: 13px;
  }
}

.history-block {
  margin-top: 16px;

  .history-shot {
    width: 56px;
    height: var(--ds-table-row-height); 
    border-radius: 4px;
    border: 1px solid var(--tp-border);
    cursor: zoom-in;
  }

  .history-error {
    color: var(--el-color-danger);
    font-size: 12px;
    word-break: break-all;
  }

  .history-ai-class {
    margin-right: 6px;
    vertical-align: middle;
  }

  .history-muted {
    color: var(--tp-text-secondary);
  }

  .history-empty {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 12px;
    border: 1px dashed var(--tp-border);
    border-radius: 8px;
    color: var(--tp-text-secondary);
    font-size: 13px;
  }

  .history-pager {
    display: flex;
    justify-content: flex-end;
    margin-top: 10px;
  }

  
  .history-compare-bar {
    display: flex;
    align-items: center;
    gap: 10px;
    margin-bottom: 6px;

    .history-compare-hint {
      font-size: 12px;
      color: var(--tp-text-secondary);
    }
  }
}

.compare-shots {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;

  .compare-shot {
    width: 120px;
    height: 80px;
    border: 1px solid var(--tp-border);
    border-radius: 6px;
  }
}

.compare-cell {
  font-size: 12px;
  word-break: break-all;
}

.rerun-hint-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  margin-bottom: 12px;
  background: var(--tp-bg-warning, #fdf6ec);
  border: 1px solid rgba(230, 162, 60, 0.45);
  border-radius: 10px;

  .rerun-hint-body {
    flex: 1;
    min-width: 0;
  }

  .rerun-hint-title {
    font-size: 13px;
    font-weight: 600;
    color: var(--tp-warning);   
  }

  .rerun-hint-sub {
    font-size: 12px;
    color: var(--tp-text-secondary);
    margin-top: 2px;
  }
}


.version-block {
  margin-top: 16px;
}


.version-block .sub-title {
  margin-top: 10px;
  font-size: 14px;
}



.flowchart-body {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  flex-wrap: wrap;
}
.flowchart-main {
  flex: 1 1 420px;
  min-width: 0;
}
.flowchart-canvas {
  margin-top: 8px;
  padding: 12px;
  overflow-x: auto;
  text-align: center;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-extra-light);
}

.flowchart-canvas :deep(svg) {
  max-width: 100%;
  height: auto;
}

.version-diff-cell {
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 12px;
  line-height: 1.6;
}

.version-diff-after {
  color: var(--el-color-success);
}

.mono {
  font-family: 'JetBrains Mono', Consolas, monospace;
}
</style>
