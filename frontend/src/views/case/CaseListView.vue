<template>
  <div class="page-root" data-test="case-page">
    <div class="page-card">
      <!-- AN-5 the regression-suites leaf merged into this page
 as a tab (old /suites path redirects here keeping ?tab=suites; the
 tab state rides the URL so deep links/refresh/back resolve the same
 view, BatchListView precedent). -->
      <el-tabs v-model="pageTab" data-test="case-page-tabs" class="case-page-tabs">
        <el-tab-pane name="cases">
          <template #label><span data-test="case-tab-cases">{{ t('case.pageTabCases') }}</span></template>
        </el-tab-pane>
        <el-tab-pane name="suites">
          <template #label><span data-test="case-tab-suites">{{ t('case.pageTabSuites') }}</span></template>
        </el-tab-pane>
      </el-tabs>

      <!-- Suites tab reuses SuiteListView as-is (embedded: its own frame is
           swapped off so the host card stays the single border). -->
      <SuiteListView embedded v-if="pageTab === 'suites'" />

      <template v-else>
      <!-- R21 bands: the header band keeps page-level variant actions and the
           page's business entries (create / import / quick-review mode /
           recycle bin / auto-assign / mock jump). View-state actions (export,
           column settings) ride the filter-bar trailing slot below. The old
           .toolbar wrapper was a scanner blind spot for the FB-1 gate. -->
      <div class="page-header">
        <div class="header-actions">
          <el-tooltip :content="t('case.quickReviewHint')" placement="bottom">
            <el-button
              :type="quick.quickMode.value ? 'warning' : 'default'"
              :plain="!quick.quickMode.value"
              :icon="quick.quickMode.value ? 'SwitchButton' : 'Operation'"
              data-test="quick-review-toggle"
              @click="quick.toggleQuickMode()"
            >
              {{ quick.quickMode.value ? t('case.exitQuickReview') : t('case.quickReview') }}
            </el-button>
          </el-tooltip>
          <el-button :icon="Upload" :disabled="!projectId" data-test="case-import" @click="importVisible = true">
            {{ t('importDialog.import') }}
          </el-button>
 
          <el-button v-if="projectId && canAssign" :icon="Delete" data-test="case-recycle-open" @click="recycleVisible = true">
            {{ t('case.recycleBin') }}
          </el-button>
          
          <el-button
            v-if="projectId && canAssign && unassignedCount > 0"
            :icon="UserFilled"
            type="primary"
            plain
            size="small"
            data-test="case-auto-assign"
            @click="autoAssignVisible = true"
          >
            {{ t('case.autoAssign') }}
          </el-button>
          <!-- §12a seamless jump (item 3 send side): one step to the mock
               documentation with the project context pre-filtered. The route
               gate perm:mock:read mirrors here so no door leads to a 403. -->
          <el-button v-if="projectId && auth.hasPerm('perm:mock:read')" data-test="case-view-in-mock" @click="jumpToMockDocs">
            {{ t('case.viewInMock') }}
          </el-button>
          <el-button type="primary" :icon="Plus" data-test="case-create" @click="openCreate">
            {{ t('case.create') }}
          </el-button>
        </div>
      </div>

      
      <div v-if="quick.quickMode.value" class="quick-hint" data-test="quick-hint">
        <el-icon color="var(--tp-warning)"><Operation /></el-icon>
        <span>{{ t('case.quickReviewHint') }}</span>
        <el-tag v-if="quick.activeRow.value" type="warning" size="small" data-test="quick-active">
          {{ quick.activeRow.value.caseNo }}
        </el-tag>
      </div>

      
      <div class="filter-bar">
        <el-select v-model="filters.status" :placeholder="t('case.statusAll')" clearable style="width: 130px" data-test="case-filter-status" @change="load(1)">
          <el-option v-for="(v, k) in CASE_STATUS" :key="k" :label="v.label" :value="Number(k)" />
        </el-select>
        <el-select v-model="filters.priority" :placeholder="t('case.priorityAll')" clearable style="width: 120px" data-test="case-filter-priority" @change="load(1)">
          <el-option v-for="i in [0, 1, 2]" :key="i" :label="PRIORITY_LABEL[i]" :value="i" />
        </el-select>
        <el-select v-model="filters.confidence" :placeholder="t('case.confidenceAll')" clearable style="width: 120px" data-test="case-filter-confidence" @change="load(1)">
          <el-option v-for="i in [1, 2, 3]" :key="i" :label="CONFIDENCE_LABEL[i]" :value="i" />
        </el-select>
        <el-select v-model="filters.phase" :placeholder="t('case.phaseAll')" clearable style="width: 120px" data-test="case-filter-phase" @change="load(1)">
          <el-option v-for="i in [0, 1, 2, 3, 4]" :key="i" :label="PHASE_LABEL[i]" :value="i" />
        </el-select>
        <!-- Review-queue quick filters: provenance channel and evidence
             grade. Options iterate the api single source; the query submit
             button stays inline at the end of the condition flow. -->
        <el-select v-model="filters.sourceFacet" :placeholder="t('case.createChannelAll')" clearable style="width: 140px" data-test="case-filter-facet" @change="onProvenanceFilterChange">
          <el-option v-for="v in CASE_FACET_VALUES" :key="v" :label="FACET_LABEL[v]" :value="v" />
        </el-select>
        <el-select v-model="filters.evidenceGrade" :placeholder="t('case.evidenceAll')" clearable style="width: 140px" data-test="case-filter-evidence" @change="onProvenanceFilterChange">
          <el-option v-for="g in EVIDENCE_GRADE_VALUES" :key="g" :label="EVIDENCE_GRADE_LABEL[g]" :value="g" />
        </el-select>
        <el-select
          v-model="filters.tagIds"
          :placeholder="t('case.tagFilter')"
          clearable
          multiple
          filterable
          collapse-tags
          style="width: 160px"
          data-test="case-filter-tag"
          @change="load(1)"
        >
          <el-option v-for="tag in projectTags" :key="tag.id" :label="tag.name" :value="tag.id" />
        </el-select>
        <el-select v-model="filters.assigneeId" :placeholder="t('case.assigneeFilter')" clearable filterable style="width: 140px" data-test="case-filter-assignee" @change="load(1)">
          <el-option :value="0" :label="t('case.assigneeUnassigned')" />
          <el-option v-if="myUserId" :value="myUserId" :label="t('case.assigneeMe')" />
          <el-option v-for="m in members" :key="m.userId" :value="m.userId" :label="m.nickName || m.username" />
        </el-select>
        <el-input
          v-model="filters.keyword"
          :placeholder="t('case.keywordPlaceholder')"
          clearable
          style="width: 260px"
          :prefix-icon="Search"
          data-test="case-filter-keyword"
          @keyup.enter="load(1)"
          @clear="load(1)"
        />
 
        <el-checkbox
          v-if="auth.hasPerm('perm:feedback:excellent-mark')"
          v-model="filters.excellentOnly"
          data-test="case-filter-excellent"
          @change="load(1)"
        >{{ t('case.excellentFilter') }}</el-checkbox>
        <el-button :icon="Search" data-test="case-search" @click="load(1)">{{ t('case.search') }}</el-button>
        <el-button :icon="Refresh" data-test="case-reset" @click="onReset">{{ t('case.reset') }}</el-button>
        <!-- R21 trailing slot: view-state actions live here (export downloads the
             current selection/filter set, column settings tunes the table view).
             Query submit (search/reset) stays inline at the end of the filter
             group above — the slot never carries a submit button (FB-2). -->
        <div class="filter-bar-actions">
          <!-- P2 bulk export (selection / filter modes; permission = project-visible
               and non read-only, backend 403 is authoritative; above 2000 rows the
               request goes to the async export task queue reusing report download). -->
          <el-dropdown v-if="projectId" trigger="click" data-test="case-export-dropdown" @command="onExport">
            <el-button :icon="Download" :loading="exporting" data-test="case-export-btn">
              {{ t('case.export') }}<el-icon class="el-icon--right"><ArrowDown /></el-icon>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="ids" :disabled="selection.length === 0" data-test="case-export-ids">
                  {{ t('case.exportIds', { n: selection.length }) }}
                </el-dropdown-item>
                <el-dropdown-item command="filter" data-test="case-export-filter">{{ t('case.exportFilter') }}</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <ColumnSettings
            :columns="settingsRows"
            :density="colCfg.state.density"
            @toggle="colCfg.toggle"
            @move="colCfg.move"
            @density="colCfg.setDensity"
            @reset="colCfg.reset"
            @pin="colCfg.toggleFixed"
          />
        </div>
      </div>

      
      <div v-if="!loading && projects.length === 0" class="empty-box" data-test="case-empty-no-project">
        <EmptyState :title="t('case.emptyNoProject')" :action-text="t('case.emptyGoProject')" @action="router.push('/projects')" />
      </div>

      <template v-else>
        <!-- Flaky restore proposals (read channel of the re-probe ledger): isolated
             cases that already passed the required green runs. Confirmation here is the
             only write door and it reuses PUT /cases/{id}/flaky - no second write path.
             Collapsed by default so a large proposal set never pushes the table down. -->
        <div
          v-if="flakyProposals.length > 0"
          data-test="flaky-proposal-bar"
          style="display: flex; flex-direction: column; gap: 6px; padding: 8px 12px; margin-bottom: 12px; border-radius: 6px; background: var(--tp-bg-hover); border: 1px dashed var(--tp-success); font-size: 13px"
        >
          <div style="display: flex; align-items: center; gap: 8px; flex-wrap: wrap">
            <el-icon color="var(--tp-success)"><CircleCheck /></el-icon>
            <span data-test="flaky-proposal-summary">{{ t('case.flakyProposalSummary', { n: flakyProposals.length }) }}</span>
            <el-button
              v-if="flakyProposals.length > 1"
              link
              size="small"
              data-test="flaky-proposal-toggle"
              @click="flakyProposalsExpanded = !flakyProposalsExpanded"
            >
              {{ flakyProposalsExpanded ? t('case.flakyProposalCollapse') : t('case.flakyProposalExpand', { n: flakyProposals.length }) }}
            </el-button>
            <el-button link size="small" :icon="Refresh" data-test="flaky-proposal-refresh" @click="void loadFlakyProposals()">
              {{ t('case.flakyProposalRefresh') }}
            </el-button>
          </div>
          <ul
            v-show="flakyProposals.length === 1 || flakyProposalsExpanded"
            data-test="flaky-proposal-list"
            style="margin: 0; padding-left: 0; list-style: none; max-height: 260px; overflow-y: auto; display: flex; flex-direction: column; gap: 6px"
          >
            <li
              v-for="p in flakyProposals"
              :key="p.caseId"
              data-test="flaky-proposal-item"
              style="display: flex; align-items: center; gap: 8px; flex-wrap: wrap"
            >
              <el-tag type="success" size="small" data-test="case-flaky-restore-badge">{{ t('case.flakyProposalConfirm') }}</el-tag>
              <span class="mono" :data-test="`flaky-proposal-case-no-${p.caseId}`">{{ p.caseNo }}</span>
              <span :title="p.caseTitle" style="max-width: 420px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap">{{ p.caseTitle }}</span>
              <el-tag size="small" type="info">{{ t('case.flakyProposalStreak', { streak: p.greenStreak, threshold: p.greenThreshold }) }}</el-tag>
              <el-button
                type="success"
                size="small"
                :loading="restoringCaseId === p.caseId"
                :data-test="`flaky-proposal-confirm-${p.caseId}`"
                @click="void confirmFlakyRestore(p)"
              >
                {{ t('case.flakyProposalConfirm') }}
              </el-button>
            </li>
          </ul>
        </div>

        
        <div v-if="selection.length > 0" class="batch-bar" data-test="case-batch-bar">
          <span class="text-secondary">{{ t('case.selectedCount', { n: selection.length }) }}</span>
          <el-button v-if="canAssign" type="success" size="small" data-test="case-batch-pass" @click="openBatchReview('pass')">
            {{ t('case.batchPass') }}
          </el-button>
          <el-button v-if="canAssign" type="danger" size="small" data-test="case-batch-reject" @click="openBatchReview('reject')">
            {{ t('case.batchReject') }}
          </el-button>
          <el-button v-if="canAssign" type="primary" plain size="small" :icon="UserFilled" data-test="case-batch-assign" @click="openBatchAssign">
            {{ t('case.batchAssign') }}
          </el-button>
          <el-button size="small" :icon="EditPen" data-test="case-batch-edit" @click="openBatchEdit">
            {{ t('case.batchEdit') }}
          </el-button>
          <!-- Selection "Ask AI" : the checked rows park into the
 shared dock singleton and land as reference chips. The set rides
 the existing E3 chip channel — core keeps the single validation
 point (kind, cap, project segment), the client copies no rule. -->
          <el-button size="small" :icon="ChatLineSquare" data-test="case-ask-ai" @click="askAiSelection">
            {{ t('layout.chatDock.askAi') }}
          </el-button>
          <el-button size="small" @click="clearSelection">{{ t('common.cancel') }}</el-button>
        </div>

        <TableSkeleton v-if="loading" :cols="4" data-test="caselistview-skeleton" />
        <el-table
          ref="caseTableApi"
          :data="vsRenderData"
          row-key="id"
          border
          :size="colCfg.state.density"
          data-test="case-table"
          :style="{ '--tp-table-pin-overlay': `${CASE_ACTION_COL_WIDTH}px` }"
          :default-sort="{ prop: 'updatedAt', order: 'descending' }"
          :span-method="vsSpanMethod"
          :row-style="vsRowStyle"
          :row-class-name="vsRowClass"
          @sort-change="onSortChange"
          @header-dragend="onHeaderDragEnd"
          @row-click="quick.onRowClick"
          :expand-row-keys="expandedKeys"
          @expand-change="onExpandChange"
          @selection-change="onSelectionChange"
         v-else>
          <el-table-column type="selection" width="42" :selectable="(row: CaseItem) => row.status === 1 || row.status === 2" />
          <el-table-column type="expand" width="44">
            <template #default="{ row }">
              <div class="case-expand" :data-test="`case-expand-${row.id}`">
                <div v-if="row.precondition" class="expand-line">
                  <span class="expand-label">{{ t('case.precondition') }}：</span>{{ row.precondition }}
                </div>
                <div v-if="row.expectedResult" class="expand-line">
                  <span class="expand-label">{{ t('case.expectedResult') }}：</span>{{ row.expectedResult }}
                </div>
                <div v-if="row.steps && row.steps.length" class="expand-line">
                  <span class="expand-label">{{ t('case.steps') }}：</span>
                  <ol class="expand-steps">
                    <li v-for="s in row.steps" :key="s.stepNo">
                      {{ s.stepNo }}. {{ s.description }}<span v-if="s.isAssert === 1" class="text-secondary">（{{ t('components.assertTag') }}）</span>
                    </li>
                  </ol>
                </div>
                <div class="expand-actions">
                  <el-button size="small" type="success" :disabled="row.status === 3" data-test="review-pass" @click.stop="doReview(row, 'pass')">
                    <el-icon><CircleCheck /></el-icon>{{ t('case.approve') }}
                  </el-button>
                  <el-button size="small" type="warning" :disabled="row.status === 3 || row.status === 4" data-test="review-need-change" @click.stop="doReview(row, 'need_change')">
                    <el-icon><EditPen /></el-icon>{{ t('case.needChange') }}
                  </el-button>
                  <el-button size="small" type="danger" :disabled="row.status === 3 || row.status === 5" data-test="review-reject" @click.stop="doReview(row, 'reject')">
                    <el-icon><CloseBold /></el-icon>{{ t('case.reject') }}
                  </el-button>
                  <el-button v-if="canAssign" size="small" type="primary" plain data-test="case-assign" @click.stop="openAssign(row)">
                    <el-icon><UserFilled /></el-icon>{{ t('case.assign') }}
                  </el-button>
                </div>
              </div>
            </template>
          </el-table-column>
 
          <el-table-column
            v-for="col in colCfg.visibleColumns.value"
            :key="col.key"
            :prop="col.key === 'tags' || col.key === 'assignee' ? undefined : col.key"
            :fixed="col.fixed || false"
            :label="t(col.labelKey)"
            :width="col.width ?? undefined"
            :min-width="col.width ? undefined : CASE_FLEX_MIN[col.key]"
            :sortable="isColSortable(col.key) ? 'custom' : false"
            :show-overflow-tooltip="OVERFLOW_TIP_COLS.includes(col.key)"
            :data-test="`case-col-${col.key}`"
          >
            <template #default="{ row }">
              <span v-if="col.key === 'caseNo'" class="mono" :data-test="`case-no-${row.id}`">{{ row.caseNo }}</span>
              <template v-else-if="col.key === 'title'">
                <span class="case-title">{{ row.title }}</span>
                
                <el-tooltip v-if="row.flaky === 1" :content="row.flakyReason || t('case.flakyIsolatedReason')" placement="top">
                  <el-tag type="warning" size="small" data-test="case-flaky-badge" style="margin-left: 6px">
                    {{ t('case.flakyBadge') }}
                  </el-tag>
                </el-tooltip>
 
                <el-tooltip v-if="row.excellent === 1" :content="t('case.excellentBadgeTip')" placement="top">
                  <el-icon :size="15" color="var(--tp-warning)" :data-test="`case-excellent-badge-${row.id}`" style="margin-left: 6px; vertical-align: -2px">
                    <StarFilled />
                  </el-icon>
                </el-tooltip>
              </template>
              <el-tag v-else-if="col.key === 'priority'" :type="PRIORITY_TYPE[row.priority] ?? 'info'" size="small">{{ PRIORITY_LABEL[row.priority] }}</el-tag>
              <el-tag v-else-if="col.key === 'confidence'" :type="CONFIDENCE_TYPE[row.confidence] ?? 'info'" size="small">
                {{ CONFIDENCE_LABEL[row.confidence] }}
              </el-tag>
              <template v-else-if="col.key === 'status'">
                <el-tag :type="CASE_STATUS[row.status]?.type ?? 'info'" size="small">{{ CASE_STATUS[row.status]?.label ?? row.status }}</el-tag>
 
                <el-tooltip v-if="row.offlineLinked" :content="t('case.offlineLinkedTip')" placement="top">
                  <el-tag type="info" size="small" data-test="case-offline-hint" style="margin-left: 6px">
                    {{ t('case.offlineLinkedBadge') }}
                  </el-tag>
                </el-tooltip>
              </template>
              <template v-else-if="col.key === 'phase'">
                <el-tag v-if="row.phase" :type="PHASE_TYPE[row.phase]" size="small">{{ PHASE_LABEL[row.phase] }}</el-tag>
                <span v-else class="text-secondary">-</span>
              </template>
              <template v-else-if="col.key === 'tags'">
                <el-tag v-for="tag in (row.tags || [])" :key="tag.id" size="small" type="info" class="case-tag" :data-test="`case-tag-${row.id}`">
                  {{ tag.name }}
                </el-tag>
                <span v-if="!(row.tags || []).length" class="text-secondary">-</span>
              </template>
              <span v-else-if="col.key === 'endType'">{{ END_TYPE_LABEL[row.endType] ?? row.endType }}</span>
              <template v-else-if="col.key === 'assignee'">
                <span v-if="row.assigneeId">{{ assigneeName(row.assigneeId) }}</span>
                <el-tag v-else type="info" size="small">{{ t('case.unassigned') }}</el-tag>
              </template>
              <template v-else-if="col.key === 'source'">
                <span>{{ SOURCE_LABEL[row.source] ?? row.source }}</span>
                <!-- Badges read the two server-computed fields and share the
                     weak-evidence test with the red row rule (one judge). -->
                <el-tag v-if="row.sourceFacet" size="small" :type="FACET_TYPE[row.sourceFacet] ?? 'info'" data-test="case-facet-badge" style="margin-left: 6px">
                  {{ FACET_LABEL[row.sourceFacet] ?? row.sourceFacet }}
                </el-tag>
                <el-tooltip v-if="row.evidenceGrade !== undefined && row.evidenceGrade !== null" placement="top"
                  :content="isWeakEvidence(row) ? t('case.weakEvidenceTip') : t('case.attestedEvidenceTip')">
                  <el-tag size="small" :type="EVIDENCE_GRADE_TYPE[row.evidenceGrade] ?? 'info'" data-test="case-evidence-badge" style="margin-left: 6px">
                    {{ EVIDENCE_GRADE_LABEL[row.evidenceGrade] }}
                  </el-tag>
                </el-tooltip>
              </template>
              <TimeCell v-else-if="col.key === 'updatedAt'" :iso="row.updatedAt" />
            </template>
          </el-table-column>
          <el-table-column :label="t('case.action')" :width="CASE_ACTION_COL_WIDTH" fixed="right">
            <template #default="{ row }">
              <FavoriteButton entityType="case" :entity-id="row.id" size="small" @change="favSet.apply" />
              <RowActions :actions="caseRowActions(row)" :max="3" @command="(k: string) => onCaseRowCmd(k, row)" />
            </template>
          </el-table-column>
        </el-table>

        <div v-if="!loading && list.length === 0" class="empty-box" data-test="case-empty">
          <EmptyState
            v-if="hasActiveFilter"
            :filtered="true"
            :title="emptyCopy.title"
            :hint="emptyCopy.hint"
            :clear-text="t('components.clearFilter')"
            @clear-filter="clearFilters"
          />
          <EmptyState
            v-else
            :title="emptyCopy.title"
            :hint="emptyCopy.hint || t('case.emptyGuide')"
            :action-text="t('case.create')"
            @action="openCreate"
          />
        </div>

        <div v-if="total > 0" class="pagination-bar">
          <el-pagination v-if="total > 0"
            v-model:current-page="page"
            :page-size="size"
            :page-sizes="[10, 20, 50, 100, 1000]"
            :total="total"
            layout="total, sizes, prev, pager, next"
            data-test="case-pagination"
            @size-change="onSizeChange"
            @current-change="load()"
          />
        </div>
      </template>
      </template>
    </div>

    
    <el-dialog draggable
      v-model="createVisible"
      :title="editingId ? t('case.editTitle') : t('case.createTitle')"
      width="640px"
      :before-close="caseDraftGuard.beforeClose"
      data-test="case-create-dialog"
    >
      <DraftRestoreBar
        :draft-key="currentDraftKey()"
        :active="createVisible"
        @restore="onAutoDraftRestore"
      />
      <el-alert
        v-if="refPlans.length > 0"
        type="warning"
        :closable="false"
        show-icon
        class="ref-hint"
        data-test="case-ref-hint"
        :title="t('case.refHint', { names: refPlans.map((n) => `「${n}」`).join('、') })"
      />
      <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-width="100px" scroll-to-error>
        <el-form-item :label="t('case.title')" prop="title">
          <el-input v-model="createForm.title" maxlength="300" show-word-limit data-test="case-create-title" />
        </el-form-item>
        <el-form-item :label="t('case.precondition')">
          <el-input v-model="createForm.precondition" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item :label="t('case.expectedResult')">
          <el-input v-model="createForm.expectedResult" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item :label="t('case.priority')">
          <!-- id="" prevents the form label for= from targeting the non-labelable radiogroup div -->
          <el-radio-group id="" v-model="createForm.priority">
            <el-radio-button v-for="i in [0, 1, 2]" :key="i" :value="i">{{ PRIORITY_LABEL[i] }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="t('case.confidence')">
          <!-- id="" prevents the form label for= from targeting the non-labelable radiogroup div -->
          <el-radio-group id="" v-model="createForm.confidence">
            <el-radio-button v-for="i in [1, 2, 3]" :key="i" :value="i">{{ CONFIDENCE_LABEL[i] }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="t('case.endType')">
          <el-select v-model="createForm.endType" style="width: 160px">
            <el-option v-for="(label, key) in END_TYPE_LABEL" :key="key" :label="label" :value="key" />
          </el-select>
        </el-form-item>
 
        <el-form-item v-if="createForm.endType === 'mobile' || createForm.endType === 'pos'" :label="t('appauto.picker.targetLabel')" data-test="case-create-automation-target">
          <!-- id="" prevents the form label for= from targeting the non-labelable radiogroup div -->
          <el-radio-group id="" v-model="createForm.automationTarget" @change="onCreateTargetChange">
            <el-radio-button value="app">{{ t('appauto.picker.targetApp') }}</el-radio-button>
            <el-radio-button value="mp">{{ t('appauto.picker.targetMp') }}</el-radio-button>
            <el-radio-button value="harmony">{{ t('appauto.picker.targetHarmony') }}</el-radio-button>
          </el-radio-group>
          <span class="form-tip">{{ t('appauto.picker.targetTip') }}</span>
        </el-form-item>
        <el-form-item v-if="createForm.endType === 'mobile' || createForm.endType === 'pos'" :label="t('appauto.picker.engineLabel')">
          <AppautoEnginePicker v-model="createForm.appautoEngine" :target="createForm.automationTarget" />
        </el-form-item>
        <el-form-item v-if="createForm.automationTarget === 'mp'" :label="t('appauto.picker.profileLabel')" data-test="case-create-env-profile">
          <el-select v-model="createForm.appautoEnvProfileId" clearable filterable :placeholder="t('appauto.picker.profilePlaceholder')" style="width: 240px">
            <el-option v-for="p in createAppautoProfiles" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
          <span class="form-tip">{{ t('appauto.picker.profileTip') }}</span>
        </el-form-item>
        <el-form-item :label="t('case.phase')">
          <el-select v-model="createForm.phase" clearable :placeholder="t('case.phasePlaceholder')" style="width: 160px" data-test="case-create-phase">
            <el-option v-for="i in [1, 2, 3, 4]" :key="i" :label="PHASE_LABEL[i]" :value="i" />
          </el-select>
          <span class="form-tip">{{ t('case.phaseTip') }}</span>
        </el-form-item>
 
        <el-form-item :label="t('case.tags')">
          <el-select
            v-model="createForm.tagIds"
            multiple
            filterable
            allow-create
            default-first-option
            :reserve-keyword="false"
            style="width: 100%"
            data-test="case-create-tags"
            @change="onFormTagsChange"
          >
            <el-option v-for="tag in projectTags" :key="tag.id" :label="tag.name" :value="tag.id" />
          </el-select>
          <div class="form-tip">{{ t('case.tagFormHint') }}</div>
        </el-form-item>
        <el-form-item :label="t('case.assignFormLabel')">
          <el-select v-model="createForm.assigneeId" clearable filterable :placeholder="t('case.assignFormPlaceholder')" style="width: 100%" data-test="case-create-assignee">
            <el-option v-for="m in assignableMembers" :key="m.userId" :label="m.nickName || m.username" :value="m.userId">
              <span>{{ m.nickName || m.username }}</span>
              <span class="text-secondary role-float">{{ m.roleLabel }}</span>
            </el-option>
          </el-select>
          <div v-if="!assignableMembers.length" class="text-secondary hint-sm">
            {{ t('case.noAssignableTester') }}
          </div>
        </el-form-item>
        <el-form-item :label="t('case.steps')">
          <div class="steps-editor">
            <div v-for="(step, idx) in createForm.steps" :key="idx" class="step-row">
              <span class="step-idx">{{ idx + 1 }}</span>
              <el-input v-model="step.description" :placeholder="t('case.stepAction')" style="width: 320px" />
              <!-- id="" prevents the form label for= from targeting the non-labelable radiogroup div -->
              <el-radio-group id="" v-model="step.isAssert">
                <el-radio-button :value="0">{{ t('case.stepAction') }}</el-radio-button>
                <el-radio-button :value="1">{{ t('components.assertTag') }}</el-radio-button>
              </el-radio-group>
              <el-button link type="danger" :icon="Delete" @click="removeStep(idx)" />
            </div>
            <el-button size="small" :icon="Plus" data-test="case-add-step" @click="addStep">{{ t('case.createStep') }}</el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="creating" data-test="case-create-submit" @click="onCreate">{{ editingId ? t('common.save') : t('common.confirm') }}</el-button>
      </template>
    </el-dialog>

    
    <el-dialog draggable v-model="reasonVisible" :title="reasonAction === 'need_change' ? t('case.needChangeTitle') : t('case.rejectTitle')" width="480px" destroy-on-close data-test="reason-dialog">
      <div v-if="batchReviewTargets.length" class="assign-targets text-secondary" data-test="batch-review-targets">
        {{ t('case.selectedCount', { n: batchReviewTargets.length }) }}：{{ batchReviewTargets.map((r) => r.caseNo).join('、') }}
      </div>
      <el-input
        v-model="reasonText"
        type="textarea"
        :rows="3"
        :placeholder="reasonAction === 'need_change' ? t('case.needChangePlaceholder') : t('case.rejectPlaceholder')"
        maxlength="500"
        data-test="reason-input"
      />
      <ProblemTypeSelect v-model="reasonTypes" />
      <template #footer>
        <el-button @click="reasonVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="reviewing" data-test="reason-submit" @click="submitReason">{{ t('common.confirm') }}</el-button>
      </template>
    </el-dialog>

    
    <el-dialog draggable v-model="assignVisible" :title="t('case.assignDialogTitle')" width="480px" destroy-on-close data-test="assign-dialog">
      <div v-if="assignTargetRows.length" class="assign-targets text-secondary">
        {{ t('case.selectedCount', { n: assignTargetRows.length }) }}：{{ assignTargetRows.map((r) => r.caseNo).join('、') }}
      </div>
      <el-form label-width="90px">
        <el-form-item :label="t('case.assignTarget')">
          <!-- id="" prevents the form label for= from targeting the non-labelable radiogroup div -->
          <el-radio-group id="" v-model="assignMode" data-test="assign-mode">
            <el-radio value="member">{{ t('case.assignToMember') }}</el-radio>
            <el-radio value="auto" :disabled="assignTargetRows.length === 1">{{ t('case.autoBalance') }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="assignMode === 'member'" :label="t('case.assignToMember')">
          <el-select v-model="assigneeTarget" filterable :placeholder="t('case.assignToMemberPlaceholder')" style="width: 100%" data-test="assign-member-select" popper-class="tp-assign-popper">
            <el-option v-for="m in assignableMembers" :key="m.userId" :label="m.nickName || m.username" :value="m.userId">
              <span>{{ m.nickName || m.username }}</span>
              <span class="text-secondary role-float">{{ m.roleLabel }}</span>
            </el-option>
          </el-select>
          <div v-if="!assignableMembers.length" class="text-secondary hint-sm">
            {{ t('case.noAssignableTester') }}
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="assignVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="assigning" data-test="assign-submit" @click="submitAssign">{{ t('common.confirm') }}</el-button>
      </template>
    </el-dialog>

 
    <el-dialog draggable v-model="batchEditVisible" :title="t('case.batchEdit')" width="480px" destroy-on-close data-test="batch-edit-dialog">
      <div v-if="selection.length" class="text-secondary hint-block">
        {{ t('case.selectedCount', { n: selection.length }) }}：{{ selection.slice(0, 5).map((r) => r.caseNo).join('、') }}<template v-if="selection.length > 5">{{ t('case.selectedMore', { n: selection.length }) }}</template>
      </div>
      <el-form label-width="90px">
        <el-form-item :label="t('case.phaseLabel')">
          <el-select v-model="batchEditPhase" clearable :placeholder="t('case.phaseKeep')" style="width: 100%" data-test="batch-edit-phase">
            <el-option v-for="i in [1, 2, 3, 4]" :key="i" :label="PHASE_LABEL[i]" :value="i" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('case.priorityLabel')">
          <el-select v-model="batchEditPriority" clearable :placeholder="t('case.priorityKeep')" style="width: 100%" data-test="batch-edit-priority">
            <el-option v-for="i in [0, 1, 2]" :key="i" :label="PRIORITY_LABEL[i]" :value="i" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('tag.name')">
          <el-select v-model="batchEditTagIds" multiple collapse-tags filterable :placeholder="t('case.tagKeepHint')" style="width: 100%" data-test="batch-edit-tags">
            <el-option v-for="tag in projectTags" :key="tag.id" :label="tag.name" :value="tag.id" />
          </el-select>
        </el-form-item>
        <div class="text-secondary hint-sm">
          {{ t('case.batchEditClearHint') }}
        </div>
      </el-form>
      <template #footer>
        <el-button @click="batchEditVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="batchEditing" data-test="batch-edit-submit" @click="submitBatchEdit">{{ t('common.confirm') }}</el-button>
      </template>
    </el-dialog>

 
    <el-dialog draggable v-model="tagVisible" :title="t('case.tagDialogTitle')" width="480px" destroy-on-close data-test="case-tag-dialog">
      <div v-if="tagTarget" class="text-secondary hint-block">
        {{ t('case.caseNo') }}：{{ tagTarget.caseNo }} · {{ t('case.tagPickHint') }}
      </div>
      <el-select v-model="tagSelected" multiple collapse-tags filterable :placeholder="t('tag.namePlaceholder')" style="width: 100%" data-test="case-tag-select">
        <el-option v-for="tag in projectTags" :key="tag.id" :label="tag.name" :value="tag.id" />
      </el-select>
      <template #footer>
        <el-button @click="tagVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="tagging" data-test="case-tag-submit" @click="submitTag">{{ t('common.confirm') }}</el-button>
      </template>
    </el-dialog>

 
    <ImportDialog v-model="importVisible" :project-id="projectId" import-type="case" @imported="onImported" />

 
    <CaseRecycleDrawer v-model="recycleVisible" :project-id="projectId" @changed="load()" />
    <AutoAssignDialog v-model="autoAssignVisible" :project-id="projectId" @done="onAutoAssignDone" />

 
    <SimilarityHintDialog ref="similarityDialogRef" />
  </div>
</template>

<script setup lang="ts">
import { computed, onActivated, onBeforeUnmount, onDeactivated, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { ArrowDown, ChatLineSquare, CircleCheck, CloseBold, Delete, Download, EditPen, Plus, Refresh, Search, StarFilled, Upload, UserFilled } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { hasQueryScope, lensEmptyCopy, listProjectScope, programIdParam, watchProgramLens } from '@/composables/useProgramLens'
import { requestSelectionAsk } from '@/composables/useChatDock'
import { useQuickReview } from '@/composables/useQuickReview'
import { useBatchReview } from '@/composables/useBatchReview'
import { useDraftGuard } from '@/composables/useDraftGuard'
import { applyAutoDraftData, autoDraftKey, clearAutoDraft, useAutoDraft, type AutoDraftRecord } from '@/composables/useAutoDraft'
import DraftRestoreBar from '@/components/DraftRestoreBar.vue'
import AppautoEnginePicker from '@/components/AppautoEnginePicker.vue'
import { listAppautoEnvProfiles, type AppautoEnvProfile } from '@/api/appauto'
import SimilarityHintDialog from '@/components/SimilarityHintDialog.vue'
import { buildSimilarityQueryText, useSimilarityHint } from '@/composables/useSimilarityHint'
import { useKeepAliveList, SCROLL_PREFIX } from '@/composables/useKeepAliveList'
import { useVirtualRows } from '@/composables/useVirtualRows'
import { getProjectMembers, type ProjectMember } from '@/api/project'
import { listTags, createTag, setCaseTags } from '@/api/tags'
import { bizErrorMsg } from '@/utils/bizError'
import ImportDialog from '@/components/ImportDialog.vue'
import ProblemTypeSelect from '@/components/ProblemTypeSelect.vue'
import ColumnSettings from '@/components/ColumnSettings.vue'
import CaseRecycleDrawer from './CaseRecycleDrawer.vue'
import SuiteListView from '@/views/suite/SuiteListView.vue'
import AutoAssignDialog from './AutoAssignDialog.vue'
import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'
import {
  CASE_STATUS, CONFIDENCE_LABEL, CONFIDENCE_TYPE, END_TYPE_LABEL, PHASE_LABEL, PHASE_TYPE,
  PRIORITY_LABEL, PRIORITY_TYPE, REVIEW_ACTION_LABEL, SOURCE_LABEL,
  CASE_FACET_VALUES, EVIDENCE_GRADE_VALUES, FACET_LABEL, FACET_TYPE,
  EVIDENCE_GRADE_LABEL, EVIDENCE_GRADE_TYPE, isWeakEvidence,
  assignCase, batchAssign, batchEdit, checkCaseReferences, createCase, deleteCase, exportCases, listCases,
  listFlakyRestoreProposals,
  reviewCase, setCaseExcellent, setCaseFlaky, updateCase,
  type CaseItem, type CaseStep, type FlakyRestoreProposal, type ReviewAction,
} from '@/api/case'
import { checkCaseDelete } from '@/api/deletecheck'
import { guardedDelete } from '@/utils/deleteGuard'
import { resolveProjectFromQuery } from '@/utils/projectQuery'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import { downloadBase64Xlsx } from '@/utils/exportDownload'
import FavoriteButton from '@/components/FavoriteButton.vue'
import { useFavoriteSet } from '@/composables/useFavoriteSet'
import { useObjectSync } from '@/composables/useObjectSync'

// : a landed case write silently re-reads THIS page as-is — same
// filters, same page number (iron rule 2: no jump, no toast; a landed object
// falling outside the filter set simply leaves the rows, nothing else moves).
useObjectSync({
  // follow-up: a rolled-back case is a landed case change too.
  watch: ['CASE_WRITE', 'SCRIPT_REPAIR', 'CASE_ROLLBACK'],
  onSync: () => void load(),
})

defineOptions({ name: 'CaseList' })
const importVisible = ref(false)

const recycleVisible = ref(false)


function onImported(): void {
  void load(1)
}
const { t } = useI18n()
// Fixed action column width in px. Single source for both the column itself
// and the --tp-table-pin-overlay declaration (theme rule: focus scroll
// reveals controls beside the sticky overlay, WCAG 2.4.11).
const CASE_ACTION_COL_WIDTH = 240
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
// AN-5 suites tab host state. Query wins on entry; an unknown
// key falls back to the cases tab. Switching writes the query back through
// replace (no history spam); the query watcher moves the visible tab when an
// external navigation (/suites redirect, ⌘K deep link) lands here, and the
// onActivated re-read covers keep-alive revival (this page sits in the
// MainLayout CACHED_LIST_VIEWS whitelist — setup never re-runs there).
function tabFromQuery(): 'cases' | 'suites' {
  return route.query.tab === 'suites' ? 'suites' : 'cases'
}
const pageTab = ref<'cases' | 'suites'>(tabFromQuery())
watch(pageTab, (tab) => {
  void router.replace({ query: { ...route.query, tab: tab === 'cases' ? undefined : tab } })
})
watch(() => route.query.tab, () => {
  pageTab.value = tabFromQuery()
})

const projects = computed(() => auth.projects)

const loading = ref(false)
const creating = ref(false)

const similarityHint = useSimilarityHint()
const similarityDialogRef = similarityHint.dialogRef
const reviewing = ref(false)
const list = ref<CaseItem[]>([])
const total = ref(0)
const page = ref(1)

const CASE_SIZE_KEY = 'tp-cases-page-size'
const size = ref(Number(localStorage.getItem(CASE_SIZE_KEY)) || 10)

const { projectId, followTick } = useProjectPageContext()
// MP4-b header program lens (R30 set-dimension projection): the page
// reads the single source and NEVER owns a second program picker; the param
// and flip-watch fragments are single sourced in useProgramLens (R40).
const members = ref<ProjectMember[]>([])
const expandedKeys = ref<number[]>([])

// Flaky restore proposals : the
// re-probe ledger already verified the green streak, so this bar only shows pending
// items and one accept action. Fetched on entry and re-fetched when the project segment
// changes (header single source, R30); the refresh button covers releases triggered
// from the row menu, where the ledger row leaves state 1 server-side.
const flakyProposals = ref<FlakyRestoreProposal[]>([])
const flakyProposalsExpanded = ref(false)
const restoringCaseId = ref<number | null>(null)

async function loadFlakyProposals(): Promise<void> {
  if (!projectId.value) {
    flakyProposals.value = []
    return
  }
  try {
    flakyProposals.value = await listFlakyRestoreProposals(projectId.value)
  } catch (e) {
    flakyProposals.value = []
    ElMessage.error(bizErrorMsg(e, t('case.flakyProposalLoadFailed')))
  }
}

watch(projectId, () => void loadFlakyProposals(), { immediate: true })

/** Accept restore: the single write door stays PUT /cases/{id}/flaky (isolated=false). */
async function confirmFlakyRestore(p: FlakyRestoreProposal): Promise<void> {
  restoringCaseId.value = p.caseId
  try {
    await setCaseFlaky(p.caseId, false)
    ElMessage.success(t('case.flakyReleaseOk', { caseNo: p.caseNo }))
    await loadFlakyProposals()
    await load()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('case.flakyFail')))
  } finally {
    restoringCaseId.value = null
  }
}


const favSet = useFavoriteSet('case')

const filters = reactive<{ status?: number; priority?: number; confidence?: number; keyword: string; assigneeId?: number; phase?: number; tagIds?: number[]; excellentOnly?: boolean; sourceFacet?: string; evidenceGrade?: number }>({
  status: undefined,
  priority: undefined,
  confidence: undefined,
  keyword: '',
  assigneeId: undefined,
  phase: undefined,
  sourceFacet: undefined,
  evidenceGrade: undefined,
  tagIds: undefined,
  excellentOnly: undefined,
})


const projectTags = ref<Array<{ id: number; name: string; type: number }>>([])


const tagVisible = ref(false)
const tagTarget = ref<CaseItem | null>(null)
const tagSelected = ref<number[]>([])
const tagging = ref(false)

function openTagDialog(row: CaseItem): void {
  tagTarget.value = row
  tagSelected.value = (row.tags || []).map((x) => x.id)
  tagVisible.value = true
}

async function submitTag(): Promise<void> {
  if (!tagTarget.value) return
  tagging.value = true
  try {
    await setCaseTags(tagTarget.value.id, tagTarget.value.projectId, tagSelected.value)
    ElMessage.success(t('case.tagOk'))
    tagVisible.value = false
    await load()
  } catch (e: any) {
    ElMessage.error(bizErrorMsg(e, t('case.tagFail')))
  } finally {
    tagging.value = false
  }
}

async function loadTags(): Promise<void> {
  if (!projectId.value) {
    projectTags.value = []
    return
  }
  try {
    projectTags.value = (await listTags(projectId.value)) || []
  } catch {
    projectTags.value = []
  }
}

const batchEditVisible = ref(false)
const batchEditing = ref(false)
const batchEditPhase = ref<number | null>(null)
const batchEditPriority = ref<number | null>(null)
const batchEditTagIds = ref<number[]>([])

function openBatchEdit(): void {
  if (!selection.value.length) {
    ElMessage.warning(t('case.noSelection'))
    return
  }
  batchEditPhase.value = null
  batchEditPriority.value = null
  batchEditTagIds.value = []
  batchEditVisible.value = true
}

async function submitBatchEdit(): Promise<void> {
  if (!projectId.value) return
  const ids = selection.value.map((r) => r.id)
  if (!ids.length) return
  if (batchEditPhase.value === null && batchEditPriority.value === null && batchEditTagIds.value.length === 0) {
    ElMessage.warning(t('case.batchEditNeedOne'))
    return
  }
  batchEditing.value = true
  try {
    const result = await batchEdit({
      projectId: projectId.value,
      caseIds: ids,
      phase: batchEditPhase.value,
      priority: batchEditPriority.value,
      tagIds: batchEditTagIds.value.length ? batchEditTagIds.value : [],
    })
    const ok = result.succeeded?.length ?? 0
    const fails = result.failures ?? []
    if (fails.length) {
      const detail = fails.map((f) => `#${f.caseId}：${f.reason}`).join('<br/>')
      ElMessageBox.alert(
        `${t('case.batchEditResult', { ok, fail: fails.length })}<br/>${detail}`,
        t('case.batchEditTitle'),
        { dangerouslyUseHTMLString: true, confirmButtonText: t('common.confirm') },
      )
    } else {
      ElMessage.success(t('case.batchEditOk', { n: ok }))
    }
    batchEditVisible.value = false
    selection.value = []
    await load()
  } catch (e: any) {
    ElMessage.error(bizErrorMsg(e, t('case.batchEditFail')))
  } finally {
    batchEditing.value = false
  }
}

const quick = useQuickReview(() => list.value, {
  onPass: (row) => void doReview(row, 'pass'),
  onNeedChange: (row) => openReasonDialog(row, 'need_change'),
  onReject: (row) => openReasonDialog(row, 'reject'),
  onBatch: () => openBatchReview('pass'),
})

function rowClassName({ row }: { row: CaseItem }): string {
  const base = quick.rowClassName({ row })
  if (row.confidence === 3) {
    // Evidence grade joins the same red rule in-component (BH review-queue note):
    // grade < 2 (no evidence / off-site self-report) escalates the row for
    // priority human review; grade 2/3 already carry attestation, so only the
    // legacy low-confidence tint applies. The old criterion is never relaxed.
    // Threshold single-sourced in the api module (shared with the row badge).
    const escalated = isWeakEvidence(row)
    const cls = escalated ? 'low-confidence-row low-evidence-row' : 'low-confidence-row'
    return base ? `${base} ${cls}` : cls
  }
  return base
}


const VIRTUAL_SIZE = 1000
const vsEnabled = computed(() => size.value >= VIRTUAL_SIZE)
const vs = useVirtualRows(list, {
  enabled: vsEnabled,
  scroller: () => document.querySelector<HTMLElement>('.layout-main'),
  tableEl: () => document.querySelector<HTMLElement>('[data-test="case-table"] .el-scrollbar__view table'),
  columnCount: computed(() => colCfg.visibleColumns.value.length + 3),
  storageKey: () => SCROLL_PREFIX + '/cases',
})
const vsRenderData = vs.renderData
const vsSpanMethod = vs.spanMethod
const vsRowStyle = vs.rowStyle
const vsRowClass = vs.rowClassName(rowClassName)

const caseTableApi = ref<{ doLayout?: () => void } | null>(null)

const selection = ref<CaseItem[]>([])
const assignVisible = ref(false)
const assigning = ref(false)
const assignMode = ref<'member' | 'auto'>('member')
const assigneeTarget = ref<number | undefined>(undefined)
const assignTargetRows = ref<CaseItem[]>([])


const canAssign = computed(() => {
  if (auth.isAdmin) return true
  const p = auth.projects.find((x) => x.projectId === projectId.value)
  return p?.role === 2
})

const autoAssignVisible = ref(false)
const unassignedCount = ref(0)

/** §12a item 3 send side: jump to the mock docs page carrying the project pre-filter. */
function jumpToMockDocs(): void {
  if (!projectId.value) return
  void router.push({ path: '/mock/docs', query: { projectId: projectId.value, source: 'case-flip' } })
}

async function loadUnassignedCount(): Promise<void> {
  if (!projectId.value) {
    unassignedCount.value = 0
    return
  }
  try {
    const r = await listCases({
      projectId: projectId.value, status: 1, assigneeId: 0, page: 1, size: 1,
      // Same page, one lens scope: the count probe must see the segment the
      // list shows, or the auto-assign entry counts rows the lens just hid.
      programId: programIdParam(),
    })
    unassignedCount.value = r.total || 0
  } catch {
    unassignedCount.value = 0
  }
}

function onAutoAssignDone(): void {
  void load(1)
  void loadUnassignedCount()
}

const myUserId = computed(() => auth.user?.userId ?? 0)


const assignableMembers = computed(() =>
  members.value.filter((m) => {
    const roles = m.roles || []
    return roles.includes('tester') || roles.includes('test_lead')
  }),
)

function onSelectionChange(rows: CaseItem[]): void {
  selection.value = rows
}

function clearSelection(): void {
  selection.value = []
}

// ---------- Selection "Ask AI" : set rides the dock singleton ----------
/**
 * Selection "Ask AI": park the checked rows as (case, id, title) chips and
 * open the dock over the current page. No cap copy here — the core door
 * refuses an over-limit set naming both numbers; the user prunes chips in
 * the composer before asking, and nothing sends on its own.
 */
function askAiSelection(): void {
  requestSelectionAsk(
    selection.value.map((r) => ({ kind: 'case' as const, id: r.id, title: r.title })),
    route.path,
  )
}

const exporting = ref(false)


async function onExport(mode: 'ids' | 'filter'): Promise<void> {
  if (!projectId.value || exporting.value) return
  if (mode === 'ids' && selection.value.length === 0) {
    ElMessage.warning(t('case.exportNoSelection'))
    return
  }
  exporting.value = true
  try {
    const result = await exportCases(mode === 'ids'
      ? { projectId: projectId.value, ids: selection.value.map((r) => r.id) }
      : {
          projectId: projectId.value,
          status: filters.status,
          keyword: filters.keyword || undefined,
          priority: filters.priority,
          confidence: filters.confidence,
          assigneeId: filters.assigneeId,
          phase: filters.phase,
          tagIds: filters.tagIds,
          sourceFacet: filters.sourceFacet,
          evidenceGrade: filters.evidenceGrade,
        })
    if (result.async) {
      ElMessage.success(t('case.exportAsyncStarted', { n: result.total }))
    } else if (result.content) {
      downloadBase64Xlsx(result.content, result.fileName || t('case.exportFileName'))
      ElMessage.success(result.removed > 0
        ? t('case.exportDoneRemoved', { total: result.total, removed: result.removed })
        : t('case.exportDone', { n: result.total }))
    }
  } catch (e: any) {
    ElMessage.error(bizErrorMsg(e, t('case.exportFailed')))
  } finally {
    exporting.value = false
  }
}

function openAssign(row: CaseItem): void {
  assignTargetRows.value = [row]
  assignMode.value = 'member'
  assigneeTarget.value = undefined
  assignVisible.value = true
}

function openBatchAssign(): void {
  if (!selection.value.length) {
    ElMessage.warning(t('case.noSelection'))
    return
  }
  assignTargetRows.value = [...selection.value]
  assignMode.value = 'member'
  assigneeTarget.value = undefined
  assignVisible.value = true
}

async function submitAssign(): Promise<void> {
  if (!projectId.value) return
  const targetIds = assignTargetRows.value.map((r) => r.id)
  if (!targetIds.length) return
  let payload: { caseIds: number[]; assigneeId?: number }
  if (assignMode.value === 'member') {
    if (!assigneeTarget.value) {
      ElMessage.warning(t('case.assigneeRequired'))
      return
    }
    payload = { caseIds: targetIds, assigneeId: assigneeTarget.value }
  } else {
    payload = { caseIds: targetIds }
  }
  assigning.value = true
  try {
    if (targetIds.length === 1 && assignTargetRows.value[0]) {
      const row = assignTargetRows.value[0]
      await assignCase(row.id, payload.assigneeId!)
      ElMessage.success(`${t('case.assignSuccess')}：${row.caseNo}`)
    } else {
      const result = await batchAssign({ projectId: projectId.value, caseIds: payload.caseIds, assigneeId: payload.assigneeId })
      const assigned = result.assigned?.length ?? 0
      const skipped = result.skipped ?? []
      ElMessage.success(
        skipped.length
          ? `${t('case.assignSuccess')}${t('case.assignedCount', { n: assigned })}${t('case.assignSkippedSuffix', { n: skipped.length, reasons: skipped.map((s) => s.reason).join('；') })}`
          : `${t('case.assignSuccess')}${t('case.assignedCount', { n: assigned })}`,
      )
    }
    assignVisible.value = false
    selection.value = []
    await load()
  } catch (e) {
    ElMessage.error(t('case.assignFail', { msg: (e as Error).message || t('components.networkError') }))
  } finally {
    assigning.value = false
  }
}

const batchReviewActions = useBatchReview(t)
const batchReviewTargets = ref<CaseItem[]>([])

function openBatchReview(action: ReviewAction): void {
  if (!selection.value.length) {
    ElMessage.warning(t('case.noSelection'))
    return
  }
  const targets = selection.value
  if (action === 'pass') {
    void (async () => {
      if (await batchReviewActions.confirmBatchPass(targets)) {
        await submitBatchReview(action, '')
      }
    })()
  } else {
    batchReviewTargets.value = [...targets]
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
  if (pendingReview) {
    await submitReview(pendingReview.row, pendingReview.action, reasonText.value.trim(), reasonTypes.value)
  } else if (batchReviewTargets.value.length) {
    await submitBatchReview(reasonAction.value, reasonText.value.trim(), reasonTypes.value)
  }
}

async function submitBatchReview(action: ReviewAction, remark: string, feedbackTypes?: string[]): Promise<void> {
  const targets = batchReviewTargets.value.length ? batchReviewTargets.value : selection.value
  if (!targets.length) return
  reviewing.value = true
  try {
    const result = await batchReviewActions.submitBatch(targets, action, remark, feedbackTypes)
    if (result) {
      reasonVisible.value = false
      batchReviewTargets.value = []
      pendingReview = null
      selection.value = []
      await load()
    }
  } finally {
    reviewing.value = false
  }
}

function assigneeName(userId: number): string {
  const m = members.value.find((x) => x.userId === userId)
  return m ? (m.nickName || m.username || t('case.userFallback', { id: userId })) : t('case.userFallback', { id: userId })
}

async function load(p?: number): Promise<void> {
  // Deep-linked program view (lens on, project cleared) queries the
  // program-wide anchor; anchor resolution single-sourced (R40).
  const scope = listProjectScope(projectId.value)
  if (scope == null) return
  if (p) page.value = p
  loading.value = true
  try {
    const result = await listCases({
      projectId: scope,
      status: filters.status,
      priority: filters.priority,
      confidence: filters.confidence,
      keyword: filters.keyword || undefined,
      assigneeId: filters.assigneeId,
      phase: filters.phase,
      tagIds: filters.tagIds,
      excellentOnly: filters.excellentOnly || undefined,
      sourceFacet: filters.sourceFacet,
      evidenceGrade: filters.evidenceGrade,
      sortBy: sortBy.value,
      sortDir: sortDir.value,
      page: page.value,
      size: size.value,
      // Lens on → programId conjuncts the explicit projectId server-side;
      // lens off → the key is dropped entirely (legacy zero drift).
      programId: programIdParam(),
    })
    list.value = result.list || []
    total.value = result.total || 0
    void loadUnassignedCount()
    quick.afterLoad()
    if (vsEnabled.value) {
      if (p) {
        vs.resetWindow()
      } else {
        vs.onScroll(false)
        vs.refreshLayout(caseTableApi.value)
      }
    }
  } catch (e) {
    ElMessage.error(t('case.loadFailed', { msg: (e as Error).message || t('components.networkError') }))
  } finally {
    loading.value = false
  }
}


function onSizeChange(s: number): void {
  size.value = s
  localStorage.setItem(CASE_SIZE_KEY, String(s))
  vs.resetWindow()
  void load(1)
}

watch(page, () => {
  if (vsEnabled.value) vs.resetWindow()
})

async function loadMembers(): Promise<void> {
  if (!projectId.value) return
  try {
    members.value = await getProjectMembers(projectId.value)
  } catch {
    members.value = []
  }
}



const SORT_KEY_MAP: Record<string, string> = {
  caseNo: 'case_no',
  priority: 'priority',
  confidence: 'confidence',
  createdAt: 'created_at',
  updatedAt: 'updated_at',
}


const CASE_COLUMNS: ColumnDef[] = [
  { key: 'caseNo', labelKey: 'case.caseNo', width: 170 },
  { key: 'title', labelKey: 'case.targetTitle', width: null },
  { key: 'priority', labelKey: 'case.priority', width: 110 },
  { key: 'confidence', labelKey: 'case.confidence', width: 110 },
  { key: 'status', labelKey: 'case.status', width: 100 },
  { key: 'phase', labelKey: 'case.phase', width: 92 },
  { key: 'tags', labelKey: 'case.tags', width: null },
  { key: 'endType', labelKey: 'case.endType', width: 90 },
  { key: 'assignee', labelKey: 'case.assignee', width: 110 },
  { key: 'source', labelKey: 'case.source', width: 100 },
  { key: 'updatedAt', labelKey: 'case.updatedAt', width: 160 },
]
/** Text columns that can realistically truncate get the overflow tooltip so
 * the full value stays reachable on hover; tag-rendered columns never do. */
const OVERFLOW_TIP_COLS = ['caseNo', 'title', 'source', 'assignee', 'updatedAt']


const CASE_FLEX_MIN: Record<string, number> = { title: 220, tags: 140 }

const colCfg = useColumnConfig('cases', CASE_COLUMNS)


const settingsRows = computed(() =>
  colCfg.state.columns.map((c) => ({ ...c, labelKey: CASE_COLUMNS.find((d) => d.key === c.key)!.labelKey })),
)


function isColSortable(key: string): boolean {
  return key in SORT_KEY_MAP
}


function onHeaderDragEnd(newWidth: number, _old: number, column: { property?: string }): void {
  if (column?.property) colCfg.updateWidth(column.property, newWidth)
}

function onSortChange({ prop, order }: { prop: string; order: string | null }): void {
  const key = SORT_KEY_MAP[prop]
  if (!key) {
    ElMessage.warning(t('review.sortUnsupported'))
    void load()
    return
  }
  sortBy.value = key
  sortDir.value = order === 'ascending' ? 'asc' : 'desc'
  void load(1)
}

const sortBy = ref('updated_at')
const sortDir = ref('desc')

/**
 * Provenance quick filters ride the URL so a review-queue link survives
 * refresh/back/share. Values outside the server vocabulary are dropped rather
 * than sent — the server decides what a channel or grade is.
 */
function applyProvenanceQuery(): boolean {
  const facet = typeof route.query.sourceFacet === 'string' && (CASE_FACET_VALUES as readonly string[]).includes(route.query.sourceFacet)
    ? route.query.sourceFacet
    : undefined
  const raw = route.query.evidenceGrade
  const parsed = typeof raw === 'string' && raw !== '' ? Number(raw) : Number.NaN
  const grade = (EVIDENCE_GRADE_VALUES as readonly number[]).includes(parsed) ? parsed : undefined
  const changed = facet !== filters.sourceFacet || grade !== filters.evidenceGrade
  filters.sourceFacet = facet
  filters.evidenceGrade = grade
  return changed
}

function syncProvenanceQuery(): void {
  void router.replace({ query: { ...route.query, sourceFacet: filters.sourceFacet ?? undefined, evidenceGrade: filters.evidenceGrade === undefined ? undefined : String(filters.evidenceGrade) } })
}

/** Quick-filter change: page state first, then the list reloads on page 1. */
function onProvenanceFilterChange(): void {
  syncProvenanceQuery()
  void load(1)
}

function onReset(): void {
  filters.status = undefined
  filters.priority = undefined
  filters.confidence = undefined
  filters.keyword = ''
  filters.assigneeId = undefined
  filters.phase = undefined
  filters.tagIds = []
  filters.excellentOnly = undefined
  filters.sourceFacet = undefined
  filters.evidenceGrade = undefined
  sortBy.value = 'updated_at'
  sortDir.value = 'desc'
  void load(1)
}


const hasActiveFilter = computed(() =>
  filters.status !== undefined || filters.priority !== undefined ||
  filters.confidence !== undefined || filters.phase !== undefined ||
  filters.assigneeId !== undefined || (filters.tagIds?.length ?? 0) > 0 ||
  filters.sourceFacet !== undefined || filters.evidenceGrade !== undefined ||
  filters.excellentOnly === true ||
  filters.keyword.trim() !== '')
// Empty-state copy : three branches single-sourced in useProgramLens.
const emptyCopy = computed(() => lensEmptyCopy({
  translate: t,
  titleKey: 'case.lensEmptyTitle',
  emptyKey: hasActiveFilter.value ? 'case.emptyFiltered' : 'case.empty',
  filtered: hasActiveFilter.value,
}))
const clearFilters = onReset

function onExpandChange(_row: CaseItem, expandedRows: CaseItem[]): void {
  expandedKeys.value = expandedRows.map((r) => r.id)
}

async function doReview(row: CaseItem, action: ReviewAction): Promise<void> {
  if (action === 'pass') {
    try {
      await ElMessageBox.confirm(
        t('case.approveConfirmMsg', { caseNo: row.caseNo }),
        t('case.approveConfirmTitle'),
        { confirmButtonText: t('case.approve'), cancelButtonText: t('common.cancel'), type: 'warning' },
      )
    } catch {
      return
    }
  } else {
    openReasonDialog(row, action)
    return
  }
  await submitReview(row, action, '')
}

function openReasonDialog(row: CaseItem, action: ReviewAction): void {
  pendingReview = { row, action }
  reasonText.value = ''
  reasonTypes.value = []
  reasonVisible.value = true
}

const reasonVisible = ref(false)
const reasonText = ref('')
const reasonTypes = ref<string[]>([])
const reasonAction = ref<ReviewAction>('need_change')
let pendingReview: { row: CaseItem; action: ReviewAction } | null = null

async function submitReview(row: CaseItem, action: ReviewAction, remark: string, feedbackTypes?: string[]): Promise<void> {
  reviewing.value = true
  try {
    await reviewCase(row.id, { action, version: row.version, remark: remark || undefined, feedbackTypes }, `ui-${row.id}-${Date.now()}`)
    ElMessage.success(t('case.reviewSuccess', { caseNo: row.caseNo, action: REVIEW_ACTION_LABEL[action] }))
    reasonVisible.value = false
    pendingReview = null
    await load()
  } catch (e) {
    const msg = (e as Error).message || ''
    if (msg.includes('409') || msg.includes('\u7248\u672c') || msg.includes('\u72b6\u6001')) {
      ElMessage.warning(t('case.reviewFail409'))
      await load()
    } else {
      ElMessage.error(t('case.reviewFail', { msg }))
    }
  } finally {
    reviewing.value = false
  }
}

const createVisible = ref(false)
const editingId = ref<number | null>(null)
const editingVersion = ref<number>(0)

const refPlans = ref<string[]>([])
const createFormRef = ref<FormInstance>()
const createForm = reactive<{
  title: string
  precondition: string
  expectedResult: string
  priority: number
  confidence: number
  endType: string
  phase?: number
 
  automationTarget: string
 
  appautoEngine: string | null
  
  appautoEnvProfileId: number | null
 
  tagIds: Array<number | string>
 
  assigneeId?: number
  steps: CaseStep[]
}>({ title: '', precondition: '', expectedResult: '', priority: 1, confidence: 2, endType: 'web', phase: undefined, tagIds: [], assigneeId: undefined, steps: [], automationTarget: 'app', appautoEngine: null, appautoEnvProfileId: null })

const createRules: FormRules = {
  title: [{ required: true, message: t('case.titleRequired'), trigger: 'blur' }],
}

const createAppautoProfiles = ref<AppautoEnvProfile[]>([])
function onCreateTargetChange(): void {
  createForm.appautoEngine = null
  if (createForm.automationTarget === 'mp') void loadCreateEnvProfiles()
}
async function loadCreateEnvProfiles(): Promise<void> {
  if (!projectId.value) { createAppautoProfiles.value = []; return }
  try {
    createAppautoProfiles.value = await listAppautoEnvProfiles(projectId.value)
  } catch {
    createAppautoProfiles.value = []
  }
}
watch(() => createForm.endType, (et) => {
  if (et !== 'mobile' && et !== 'pos') {
    createForm.automationTarget = 'app'
    createForm.appautoEngine = null
    createForm.appautoEnvProfileId = null
  } else if (createForm.automationTarget === 'mp') {
    void loadCreateEnvProfiles()
  }
})

function resetCreateForm(): void {
  clearAutoDraft(currentDraftKey())
  createForm.title = ''
  createForm.precondition = ''
  createForm.expectedResult = ''
  createForm.priority = 1
  createForm.confidence = 2
  createForm.endType = 'web'
  createForm.phase = undefined
  createForm.automationTarget = 'app'
  createForm.appautoEngine = null
  createForm.appautoEnvProfileId = null
  createAppautoProfiles.value = []
  createForm.tagIds = []
  createForm.assigneeId = undefined
  createForm.steps = []
}


const caseDraftGuard = useDraftGuard(createForm, () => {
  resetCreateForm()
  editingId.value = null
})


function currentDraftKey(): string {
  return editingId.value !== null
    ? autoDraftKey('case', 'edit', editingId.value)
    : autoDraftKey('case', 'create', projectId.value ?? 0)
}
const caseAutoDraft = useAutoDraft(currentDraftKey, createForm as unknown as Record<string, unknown>, {
  enabled: () => createVisible.value && caseDraftGuard.isDirty(),
})
onBeforeUnmount(caseAutoDraft.stop)

function onAutoDraftRestore(rec: AutoDraftRecord): void {
  const applied = applyAutoDraftData(createForm as unknown as Record<string, unknown>, rec.data)
  caseDraftGuard.snapshot()
  ElMessage[applied ? 'success' : 'warning'](t(applied ? 'case.draftRestored' : 'case.draftRestoreEmpty'))
}

function openCreate(): void {
  if (!projectId.value) {
    ElMessage.warning(t('case.emptyNoProject'))
    return
  }
  if (editingId.value !== null) caseDraftGuard.discard()
  if (createForm.steps.length === 0) addStep()
  caseDraftGuard.snapshot()
  createVisible.value = true
}


function openEdit(row: CaseItem): void {
  if (!projectId.value) {
    ElMessage.warning(t('case.emptyNoProject'))
    return
  }
  editingId.value = row.id
  editingVersion.value = row.version
  refPlans.value = []
  checkCaseReferences(row.id).then((names) => { refPlans.value = names || [] }).catch(() => { refPlans.value = [] })
  createForm.title = row.title
  createForm.precondition = row.precondition || ''
  createForm.expectedResult = row.expectedResult || ''
  createForm.priority = row.priority
  createForm.confidence = row.confidence
  createForm.endType = row.endType
  createForm.phase = row.phase ?? undefined
  createForm.tagIds = (row.tags || []).map((x) => x.id)
  createForm.assigneeId = row.assigneeId ?? undefined
  createForm.steps = row.steps?.length
    ? row.steps.map((s) => ({ stepNo: s.stepNo, description: s.description, isAssert: s.isAssert ?? 0 }))
    : [{ stepNo: 1, description: '', isAssert: 0 }]
  createVisible.value = true
  caseDraftGuard.snapshot()
}

function caseRowActions(row: CaseItem) {
  return [
    { key: "detail", label: t("case.detail") },
    { key: "edit", label: t("case.edit") },
    { key: "tag", label: t("tag.manage") },
    row.flaky !== 1
      ? { key: "flaky-isolate", label: t("case.flakyIsolate"), type: "warning" as const }
      : { key: "flaky-release", label: t("case.flakyRelease"), type: "success" as const },
    ...(row.excellent !== 1
      ? [{ key: "excellent-mark", label: t("case.excellentMark"), type: "warning" as const, perm: "perm:feedback:excellent-mark" }]
      : [{ key: "excellent-unmark", label: t("case.excellentUnmark"), type: "success" as const, perm: "perm:feedback:excellent-mark" }]),
    { key: "delete", label: t("common.delete"), type: "danger" as const },
  ]
}
function onCaseRowCmd(key: string, row: CaseItem): void {
  if (key === "detail") router.push(`/cases/${row.id}`)
  else if (key === "edit") openEdit(row)
  else if (key === "tag") openTagDialog(row)
  else if (key === "flaky-isolate") void handleSetFlaky(row, true)
  else if (key === "flaky-release") void handleSetFlaky(row, false)
  else if (key === "excellent-mark") void handleSetExcellent(row, true)
  else if (key === "excellent-unmark") void handleSetExcellent(row, false)
  else if (key === "delete") void handleDelete(row)
}


async function handleSetFlaky(row: CaseItem, isolated: boolean): Promise<void> {
  try {
    await setCaseFlaky(row.id, isolated)
    ElMessage.success(isolated
      ? t('case.flakyIsolateOk', { caseNo: row.caseNo })
      : t('case.flakyReleaseOk', { caseNo: row.caseNo }))
    await load()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('case.flakyFail')))
  }
}


async function handleSetExcellent(row: CaseItem, excellent: boolean): Promise<void> {
  try {
    await setCaseExcellent(row.id, excellent, row.version)
    ElMessage.success(excellent
      ? t('case.excellentMarkOk', { caseNo: row.caseNo })
      : t('case.excellentUnmarkOk', { caseNo: row.caseNo }))
    await load()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('case.excellentFail')))
  }
}


async function handleDelete(row: CaseItem): Promise<void> {
  const ok = await guardedDelete({
    check: () => checkCaseDelete(row.id),
    doDelete: () => deleteCase(row.id),
    entityName: t('case.entityName', { title: row.title }),
    forceHint: t('case.deleteForceHint'),
    forceText: t('case.confirmDelete'),
  })
  if (ok) {
    ElMessage.success(t('case.deleteSuccess'))
    await load()
  }
}

function addStep(): void {
  createForm.steps.push({ stepNo: createForm.steps.length + 1, description: '', isAssert: 0 })
}


async function onFormTagsChange(): Promise<void> {
  if (!projectId.value) return
  const tags = createForm.tagIds
  for (let i = 0; i < tags.length; i++) {
    const v = tags[i]
    if (typeof v === 'number') continue
    const name = String(v).trim()
    if (!name) {
      tags.splice(i, 1)
      i--
      continue
    }
    try {
      const created = await createTag({ projectId: projectId.value, name, type: 4 })
      tags[i] = created.id
      if (!projectTags.value.some((x) => x.id === created.id)) {
        projectTags.value.push(created)
      }
    } catch (e) {
      tags.splice(i, 1)
      i--
      ElMessage.error(bizErrorMsg(e, t('tag.createFail')))
    }
  }
}

function removeStep(idx: number): void {
  createForm.steps.splice(idx, 1)
  createForm.steps.forEach((s, i) => { s.stepNo = i + 1 })
}

async function onCreate(): Promise<void> {
  if (!createFormRef.value || !projectId.value) return
  const valid = await createFormRef.value.validate().catch(() => false)
  if (!valid) return
  const simGateOk = await similarityHint.gate(
    projectId.value,
    buildSimilarityQueryText({
      title: createForm.title,
      expectedResult: createForm.expectedResult,
      steps: createForm.steps,
    }),
    editingId.value,
  )
  if (!simGateOk) return
  creating.value = true
  try {
    const steps = createForm.steps
      .filter((s) => s.description.trim())
      .map((s, i) => ({ stepNo: i + 1, description: s.description.trim(), isAssert: s.isAssert ?? 0 }))
    if (editingId.value) {
      await updateCase(editingId.value, {
        title: createForm.title.trim(),
        precondition: createForm.precondition.trim() || undefined,
        expectedResult: createForm.expectedResult.trim() || undefined,
        priority: createForm.priority,
        confidence: createForm.confidence,
        endType: createForm.endType,
        phase: createForm.phase,
        steps: steps.length ? steps : undefined,
        tagIds: createForm.tagIds as number[],
        assigneeId: createForm.assigneeId ?? 0,
        version: editingVersion.value,
      })
      ElMessage.success(t('case.editSuccess'))
      caseDraftGuard.discard()
      createVisible.value = false
      await load(1)
      return
    }
    const result = await createCase({
      projectId: projectId.value,
      title: createForm.title.trim(),
      precondition: createForm.precondition.trim() || undefined,
      expectedResult: createForm.expectedResult.trim() || undefined,
      priority: createForm.priority,
      confidence: createForm.confidence,
      endType: createForm.endType,
      phase: createForm.phase,
      ...(createForm.endType === 'mobile' || createForm.endType === 'pos'
        ? {
            automationTarget: createForm.automationTarget,
            appautoEngine: createForm.appautoEngine ?? undefined,
            appautoEnvProfileId: createForm.appautoEnvProfileId ?? undefined,
          }
        : {}),
      steps: steps.length ? steps : undefined,
      tagIds: createForm.tagIds as number[],
      assigneeId: createForm.assigneeId || undefined,
    })
    ElMessage.success(t('case.createSuccess', { caseNo: result.caseNo }))
    caseDraftGuard.discard()
    createVisible.value = false
    await load(1)
  } catch (e) {
    ElMessage.error((e as Error).message || t('case.createFailed'))
  } finally {
    creating.value = false
  }
}


function applyKeywordQuery(): boolean {
  const kw = typeof route.query.keyword === 'string' ? route.query.keyword : ''
  if (kw && kw !== filters.keyword) {
    filters.keyword = kw
    page.value = 1
    return true
  }
  return false
}


function applyProjectQuery(): boolean {
  const qp = resolveProjectFromQuery(route, auth.projects.map((p) => p.projectId))
  if (qp && qp !== projectId.value) {
    projectId.value = qp
    page.value = 1
    return true
  }
  return false
}

onMounted(() => {
  applyKeywordQuery()
  applyProvenanceQuery()
  // Landing for the case-detail handoff: the recycle door is offered only for a real
  // delete record, so arriving with the flag must actually open the drawer.
  if (route.query.recycle === '1') recycleVisible.value = true
  if (projectId.value) {
    void loadTags()
    void loadMembers()
    void favSet.reload()
  }
  // Deep-linked lens landing carries no project segment; fetch anyway once the
  // header lens resolves the program-wide anchor (single-source gate, R40).
  if (hasQueryScope(projectId.value)) {
    void load().then(() => vs.restoreScroll())
  }
})

watch(followTick, () => {
  if (!hasQueryScope(projectId.value)) return
  page.value = 1
  expandedKeys.value = []
  if (projectId.value) {
    void loadTags()
    void loadMembers()
  }
  void load(1)
})

// Header lens flip (pick / switch / detach) → refetch the visible segment from
// page 1, same posture as the project-header followTick reload above. The
// guard resolves through the shared scope predicate: a lens pick with the
// project segment cleared still refetches the program-wide anchor.
watchProgramLens(() => void load(1), () => hasQueryScope(projectId.value))

useKeepAliveList(async () => {
  applyProjectQuery()
  applyKeywordQuery()
  if (!hasQueryScope(projectId.value)) return
  if (projectId.value) {
    await Promise.all([loadTags(), loadMembers(), favSet.reload(), load()])
  } else {
    await load()
  }
})
// keep-alive revival never re-runs setup and the query watcher
// misses undefined -> undefined, so the URL re-read inside onActivated is
// mandatory (bare /cases = cases tab; BatchListView precedent).
onActivated(() => {
  pageTab.value = tabFromQuery()
  window.addEventListener('keydown', quick.onKeydown)
  vs.restoreScroll()
})
onDeactivated(() => {
  window.removeEventListener('keydown', quick.onKeydown)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', quick.onKeydown)
})
</script>

<style scoped lang="scss">
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
  flex-wrap: wrap;
  gap: 12px;
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.toolbar-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.filter-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 12px;

  .el-input {
    margin-left: 6px;
    padding-left: 14px;
    border-left: 1px solid var(--tp-border);
  }
}

/* .batch-bar now defined once globally in src/styles/theme.scss (shared with
   the requirement list); the per-page scoped copy lived here as a duplicate. */

.assign-targets {
  margin-bottom: 12px;
  font-size: 13px;
  line-height: 1.6;
  max-height: 80px;
  overflow-y: auto;
}

.quick-hint {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  margin-bottom: 12px;
  border-radius: 6px;
  background: var(--tp-bg-hover);
  border: 1px dashed var(--tp-warning);
  color: var(--tp-text);
  font-size: 13px;
}

.case-title {
  font-weight: 500;
}

.mono {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 13px;
}

.case-expand {
  padding: 4px 12px 10px 56px;
  font-size: 13px;

  .expand-line {
    margin-bottom: 6px;
    line-height: 1.6;
  }

  .expand-label {
    color: var(--tp-primary);
    font-weight: 500;
  }

  .expand-steps {
    margin: 4px 0 0 18px;
    padding: 0;
  }

  .expand-actions {
    margin-top: 8px;
    display: flex;
    gap: 8px;
  }
}

.steps-editor {
  width: 100%;

  .step-row {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 8px;

    .step-idx {
      width: 20px;
      text-align: center;
      color: var(--tp-text-secondary);
      font-weight: 600;
    }
  }
}


.empty-box {
  padding-top: 10px;
}


.role-float { float: right; font-size: 12px; }
.hint-sm { font-size: 12px; line-height: 1.6; }
.hint-block { margin-bottom: 12px; font-size: 13px; line-height: 1.6; }
</style>
