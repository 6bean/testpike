<!-- tp-owner-echo-exempt: tools are global assets — the dialog-side
     ProjectSelect encodes the "optional owner" contract (projectId null =
     system-level tool shared across projects), not a second-channel project
     picker, so the ownership-echo iron rule (rules 1/2) does not apply here.
     Ruling = T396b package-1 inventory entries #15. -->
<template>
  <div class="page-root tool-list-page" data-test="tool-list-page">
    <div class="page-card">
      <!-- AN-5 the tool-market leaf merged into this page as a
 tab (old /tool-market path redirects here keeping ?tab=market; the
 tab state rides the URL so deep links/refresh/back resolve the same
 view, BatchListView precedent). -->
      <el-tabs v-model="pageTab" data-test="tool-page-tabs" class="tool-page-tabs">
        <el-tab-pane name="manage">
          <template #label><span data-test="tool-tab-manage">{{ t('tool.pageTabManage') }}</span></template>
        </el-tab-pane>
        <el-tab-pane name="market">
          <template #label><span data-test="tool-tab-market">{{ t('tool.pageTabMarket') }}</span></template>
        </el-tab-pane>
      </el-tabs>

      <!-- Market tab reuses ToolMarketView as-is (embedded: its own frame is
           swapped off so the host card stays the single border). -->
      <ToolMarketView embedded v-if="pageTab === 'market'" />

      <template v-else>
      <!-- N-3 repayment + R21 same-band merge (FB group, revision 3): the manage
           band has no row-level batch bar and exactly 4 condition controls (at the
           threshold), so the create variant action rides the .filter-bar-actions
           trailing slot together with the shared column-settings control (variant
           first, then settings) and the emptied page-header row is removed as a
           whole shell. The page name rides the global top bar per R23. Query
           submit stays inline at the end of the condition flow; data-test anchors
           unchanged. -->

      <!-- IA fix: screening controls live in the shared filter-bar row;
 the project segment rides the global header context. -->
      <div class="filter-bar" data-test="toollistview-filter-bar">
        <el-input
          v-model="query.keyword"
          :placeholder="t('tool.searchKw')"
          clearable
          style="width: 220px"
          data-test="tool-keyword"
          @keyup.enter="reload"
          @clear="reload"
        />
        <el-select
          v-model="query.type"
  :placeholder="t('tool.type')"
          clearable
          style="width: 130px"
          popper-class="tp-tool-type-popper"
          data-test="tool-type-select"
          @change="reload"
        >
          <el-option v-for="opt in TOOL_TYPES" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
        <el-select
          v-model="query.status"
  :placeholder="t('tool.status')"
          clearable
          style="width: 130px"
          popper-class="tp-tool-status-popper"
          data-test="tool-status-select"
          @change="reload"
        >
          <el-option v-for="opt in TOOL_STATUSES" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
        <el-input
          v-model="query.category"
  :placeholder="t('tool.category')"
          clearable
          style="width: 140px"
          data-test="tool-category"
          @keyup.enter="reload"
          @clear="reload"
        />
        <!-- (R21 revision 2): the query submit belongs inline at the
 end of the filter group (eye-hand continuity ≤ 1 click), not pushed
 to the trailing slot. Filters already reload on change/clear/enter;
 the button stays as the explicit path E2E clicks. data-test anchor
 rides unchanged (nine E2E files click it). -->
        <el-button data-test="tool-search" @click="reload">
          <el-icon><Search /></el-icon>{{ t('tool.query') }}
        </el-button>
        <!-- R25 mode declaration (TB group, honest absence / mode 2): this
             trailing slot lives inside the manage-only section below the tabs,
             so the settings control unmounts while the market view shows — no
             global-looking control ever acts on an invisible table.
             N-3: column visibility/order/pin/density persist per user through
             the shared useColumnConfig piece (viewKey 'tool'). -->
        <div class="filter-bar-actions" data-test="tool-filter-actions">
          <el-button type="primary" :icon="Plus" data-test="tool-create-btn" @click="openCreate">{{ t('tool.create') }}</el-button>
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

      <TableSkeleton v-if="loading" :cols="colCfg.visibleColumns.value.length + 1" data-test="toollistview-skeleton" />
      <el-table border v-else :data="rows" :size="colCfg.state.density" row-key="id"  data-test="tool-table" @sort-change="onSortChange">
        <!-- N-3 wiring: every data column renders through the shared
             column-config loop (hide/order/pin take effect, density rides
             :size); the locked action column stays outside the loop. Header
             sort is server-side and single-sourced: SORT_KEY_MAP (already the
             request whitelist) decides the per-column sortable flag. -->
        <el-table-column
          v-for="col in colCfg.visibleColumns.value"
          :key="col.key"
          :prop="col.key"
          :fixed="col.fixed || false"
          :label="t(col.labelKey)"
          :width="col.width ?? undefined"
          :min-width="col.width ? undefined : TOOL_FLEX_MIN[col.key]"
          :align="col.key === 'currentVersion' ? 'center' : undefined"
          :sortable="SORT_KEY_MAP[col.key] ? 'custom' : false"
          :data-test="`tool-col-${col.key}`"
        >
          <template #default="{ row }">
            <template v-if="col.key === 'name'">
              <span class="tool-name">{{ row.name }}</span>
              <el-tag v-if="row.projectId == null" size="small" type="warning" style="margin-left: 6px">
                {{ t('tool.systemLevel') }}
              </el-tag>
              
              <el-tag
                v-if="row.type === 2 && isWriteSchema(row.inputSchemaJson)"
                size="small"
                type="danger"
                style="margin-left: 6px"
                data-test="tool-write-tag"
              >
                {{ t('tool.writeTag') }}
              </el-tag>
              <div class="tool-desc" data-test="tool-desc">{{ row.description || row.bodyPreview || '—' }}</div>
            </template>
            <template v-else-if="col.key === 'category'">{{ row.category || '—' }}</template>
            <template v-else-if="col.key === 'execKind'">{{ row.execKindLabel || '—' }}</template>
            <template v-else-if="col.key === 'status'">
              <el-tag :type="statusTag(row.status)" size="small" effect="light" data-test="tool-status-tag">
                {{ row.statusLabel }}
              </el-tag>
              <div v-if="row.status === 4 && row.rejectReason" class="reject-reason" data-test="tool-reject-reason">
                {{ row.rejectReason }}
              </div>
            </template>
            <template v-else-if="col.key === 'currentVersion'">v{{ row.currentVersion }}</template>
            <template v-else>{{ row[col.key] }}</template>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.action')" width="150" fixed="right">
          <template #default="{ row }">
            <RowActions :actions="rowActions(row)" :max="2" @command="(k: string) => onRowCmd(k, row)" />
          </template>
        </el-table-column>
        <template #empty>
          <EmptyState :title="t('tool.emptyTitle')" :hint="t('tool.emptyHint')" />
        </template>
      </el-table>

      <div class="pagination-bar">
        <el-pagination
          v-if="total > 0"
          v-model:current-page="query.page"
          :page-size="query.size"
          :total="total"
          layout="total, prev, pager, next"
          data-test="tool-pagination"
          @current-change="load"
        />
      </div>
      </template>

      
      <el-dialog
        v-model="formVisible"
        :title="editingId ? t('tool.editTitle') : t('tool.create')"
        width="640px"
        destroy-on-close
        data-test="tool-form-dialog"
      >
        <el-form ref="formRef" :model="form" :rules="formRules" label-width="110px" scroll-to-error>
          <el-form-item :label="t('common.name')" prop="name">
            <el-input v-model="form.name" :placeholder="t('tool.namePh')" maxlength="128" data-test="tool-form-name" />
          </el-form-item>
          <el-form-item v-if="!editingId" :label="t('tool.type')" prop="type">
            <el-select v-model="form.type" style="width: 100%" popper-class="tp-tool-form-type-popper" data-test="tool-form-type">
              <el-option v-for="opt in TOOL_TYPES" :key="opt.value" :label="opt.label" :value="opt.value" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="!editingId" :label="t('tool.projectLabel')">
            <ProjectSelect
              v-model="form.projectId"
              :placeholder="t('tool.projectPh')"
              width="100%"
              data-test="tool-form-project"
            />
          </el-form-item>
          <el-form-item :label="t('tool.category')">
            <el-input v-model="form.category" :placeholder="t('tool.categoryPh')" maxlength="64" data-test="tool-form-category" />
          </el-form-item>
          <el-form-item :label="t('tool.desc')">
            <el-input v-model="form.description" type="textarea" :rows="2" maxlength="500" data-test="tool-form-desc" />
          </el-form-item>
          <el-form-item v-if="form.type === 1" :label="t('tool.bodyLabel')">
            <el-input
              v-model="form.body"
              type="textarea"
              :rows="5"
              :placeholder="t('tool.bodyPh')"
              data-test="tool-form-body"
            />
          </el-form-item>
          <el-form-item :label="t('tool.inputSchema')">
            <el-input
              v-model="form.inputSchemaJson"
              type="textarea"
              :rows="3"
              :placeholder="t('tool.inputSchemaPh')"
              data-test="tool-form-input-schema"
            />
          </el-form-item>
          <el-form-item :label="t('tool.outputSchema')">
            <el-input v-model="form.outputSchemaJson" type="textarea" :rows="3" :placeholder="t('tool.outputSchemaPh')" data-test="tool-form-output-schema" />
          </el-form-item>
          <template v-if="form.type === 2">
            <el-form-item :label="t('tool.execKind')">
              <el-select
                v-model="form.execKind"
                :placeholder="t('tool.execKindPh')"
                clearable
                style="width: 100%"
                popper-class="tp-tool-form-execkind-popper"
                data-test="tool-form-exec-kind"
              >
                <el-option v-for="opt in EXEC_KINDS" :key="opt.value" :label="opt.label" :value="opt.value" />
              </el-select>
            </el-form-item>
   
            <el-form-item v-if="form.execKind === 4" :label="t('tool.fnLabel')">
              <el-select
                v-model="selectedFnName"
                :placeholder="t('tool.fnPh')"
                :loading="genkitCatalogLoading"
                clearable
                filterable
                style="width: 100%"
                popper-class="tp-tool-form-genkit-fn-popper"
                data-test="tool-form-genkit-fn"
                @visible-change="onGenkitSelectOpen"
                @change="applyGenkitFunction"
              >
                <el-option-group v-for="g in genkitCatalog" :key="g.group" :label="g.group">
                  <el-option v-for="fn in g.functions" :key="fn.name" :label="fn.name" :value="fn.name">
                    <span>{{ fn.name }}</span>
                    <span class="genkit-opt-desc">{{ fn.description }}</span>
                  </el-option>
                </el-option-group>
              </el-select>
              <div v-if="selectedFnHint" class="genkit-fn-hint" data-test="tool-form-genkit-hint">{{ selectedFnHint }}</div>
            </el-form-item>
            <el-form-item :label="t('tool.execConfig')">
              <el-input
                v-model="form.execSpecJson"
                type="textarea"
                :rows="3"
                :placeholder="t('tool.execSpecPh')"
                data-test="tool-form-exec-spec"
              />
            </el-form-item>
            <el-form-item :label="t('tool.timeoutLabel')">
              <el-input-number v-model="form.timeoutMs" :min="1000" :max="300000" :step="1000" data-test="tool-form-timeout" />
            </el-form-item>
          </template>
          <el-form-item v-if="!editingId" :label="t('tool.submitNowLabel')">
            <el-switch v-model="form.submitNow" data-test="tool-form-submit-now" />
          </el-form-item>
          <FormSideEffectHint
            v-if="editingId"
            :text="t('tool.editHint')"
            test-id="tool-form-edit-hint"
          />
        </el-form>
        <template #footer>
          <el-button @click="formVisible = false">{{ t('common.cancel') }}</el-button>
          <el-button type="primary" :loading="saving" data-test="tool-form-submit" @click="submitForm">
            {{ editingId ? t('common.save') : t('tool.formCreate') }}
          </el-button>
        </template>
      </el-dialog>

   
      <el-dialog
        v-model="invVisible"
        :title="t('tool.invTitle', { name: invToolName })"
        width="860px"
        destroy-on-close
        data-test="tool-inv-dialog"
      >
        <div class="inv-toolbar">
          <el-date-picker
            v-model="invStart"
            type="datetime"
            :placeholder="t('tool.invStartPh')"
            value-format="YYYY-MM-DDTHH:mm:ss"
            clearable
            style="width: 220px"
            data-test="tool-inv-start"
          />
          <el-date-picker
            v-model="invEnd"
            type="datetime"
            :placeholder="t('tool.invEndPh')"
            value-format="YYYY-MM-DDTHH:mm:ss"
            clearable
            style="width: 220px"
            data-test="tool-inv-end"
          />
          <el-button :loading="invLoading" data-test="tool-inv-query" @click="queryInvocations">{{ t('tool.query') }}</el-button>
          <span class="text-secondary inv-hint">{{ t('tool.invHint') }}</span>
        </div>
        <el-table v-loading="invLoading" :data="invRows" row-key="id" border data-test="tool-inv-table">
          <el-table-column prop="createdAt" :label="t('tool.colTime')" width="170" />
          <el-table-column :label="t('tool.colCaller')" width="110">
            <template #default="{ row }">
              <el-tag size="small" :data-test="`inv-caller-${row.id}`">{{ callerLabel(row.caller) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('tool.colResult')" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.success === 1 ? 'success' : 'danger'">{{ row.success === 1 ? t('tool.success') : t('tool.failed') }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('tool.colDuration')" width="100" align="right">
            <template #default="{ row }">{{ row.durationMs != null ? `${row.durationMs} ms` : '—' }}</template>
          </el-table-column>
          <el-table-column :label="t('tool.colError')" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">{{ row.error || '—' }}</template>
          </el-table-column>
          <template #empty>
            <EmptyState :title="t('tool.invEmptyTitle')" :hint="t('tool.invEmptyHint')" />
          </template>
        </el-table>
        <div class="pagination-bar">
          <el-pagination
            v-if="invTotal > 0"
            v-model:current-page="invQuery.page"
            :page-size="invQuery.size"
            :total="invTotal"
            layout="total, prev, pager, next"
            data-test="tool-inv-pagination"
            @current-change="queryInvocations"
          />
        </div>
        <template #footer>
          <el-button @click="invVisible = false">{{ t('common.close') }}</el-button>
        </template>
      </el-dialog>

   
      <el-dialog
        v-model="runVisible"
        :title="t('tool.testRunTitle', { name: runToolName })"
        width="720px"
        destroy-on-close
        data-test="tool-run-dialog"
      >
        <el-form-item :label="t('tool.sampleInput')" class="run-input-item">
          <el-input
            v-model="runInput"
            type="textarea"
            :rows="4"
            :placeholder="t('tool.sampleInputPh')"
            data-test="tool-run-input"
          />
        </el-form-item>
        <div v-if="runResult" class="run-result" data-test="tool-run-result">
          <div class="run-result-head">
            <el-tag :type="runResult.success ? 'success' : 'danger'" size="small" data-test="tool-run-success-tag">
              {{ runResult.success ? t('tool.execOk') : t('tool.execNg') }}
            </el-tag>
            <span class="text-secondary">{{ t('tool.elapsed', { ms: runResult.durationMs }) }}</span>
            <span v-if="runResult.errorCategory" class="text-secondary" data-test="tool-run-category">
              {{ t('tool.categoryLabel', { label: RUN_CATEGORY_LABELS[runResult.errorCategory] || runResult.errorCategory }) }}
            </span>
          </div>
          <pre class="run-output" data-test="tool-run-output">{{ runResult.success ? (runResult.output || t('tool.emptyResponse')) : (runResult.errorMessage || t('tool.unknownError')) }}</pre>
        </div>
        <template #footer>
          <el-button @click="runVisible = false">{{ t('common.close') }}</el-button>
          <el-button type="primary" :loading="running" data-test="tool-run-exec" @click="execTestRun">{{ t('tool.startTestRun') }}</el-button>
        </template>
      </el-dialog>

   
      <el-dialog
        v-model="invokeVisible"
        :title="t('tool.invokeTitle', { name: invokeToolName })"
        width="720px"
        destroy-on-close
        data-test="tool-invoke-dialog"
      >
        <el-form-item :label="t('tool.inputLabel')" class="run-input-item">
          <el-input
            v-model="invokeInput"
            type="textarea"
            :rows="4"
            :placeholder="t('tool.inputPh')"
            data-test="tool-invoke-input"
          />
        </el-form-item>
        <div v-if="invokeResult" class="run-result" data-test="tool-invoke-result">
          <div class="run-result-head">
            <el-tag :type="invokeResult.success ? 'success' : 'danger'" size="small" data-test="tool-invoke-success-tag">
              {{ invokeResult.success ? t('tool.execOk') : t('tool.execNg') }}
            </el-tag>
            <span class="text-secondary">{{ t('tool.elapsed', { ms: invokeResult.durationMs }) }}</span>
            <span v-if="invokeResult.errorCategory" class="text-secondary" data-test="tool-invoke-category">
              {{ t('tool.categoryLabel', { label: RUN_CATEGORY_LABELS[invokeResult.errorCategory] || invokeResult.errorCategory }) }}
            </span>
          </div>
          <pre class="run-output" data-test="tool-invoke-output">{{ invokeResult.success ? (invokeResult.output || t('tool.emptyResponse')) : (invokeResult.errorMessage || t('tool.unknownError')) }}</pre>
        </div>
        <template #footer>
          <el-button @click="invokeVisible = false">{{ t('common.close') }}</el-button>
          <el-button type="primary" :loading="invoking" data-test="tool-invoke-exec" @click="execInvoke">{{ t('tool.startInvoke') }}</el-button>
        </template>
      </el-dialog>

   
      <el-dialog
        v-model="confirmVisible"
        :title="t('tool.confirmTitle')"
        width="520px"
        :close-on-click-modal="false"
        destroy-on-close
        data-test="tool-confirm-dialog"
      >
        <div class="confirm-body" data-test="tool-confirm-body">
          {{ t('tool.confirmBefore', { name: confirmToolName }) }}<strong>{{ t('tool.writeSemantic') }}</strong>{{ t('tool.confirmAfter') }}
          <div class="confirm-ticket" data-test="tool-confirm-countdown">
            {{ t('tool.ticketIssued') }}{{ confirmSeconds > 0 ? t('tool.ticketValid', { seconds: confirmSeconds }) : t('tool.ticketExpired') }}{{ t('tool.ticketOnce') }}
          </div>
          <div class="text-secondary confirm-input-echo">{{ t('tool.inputEcho', { echo: confirmInputEcho }) }}</div>
        </div>
        <template #footer>
          <el-button data-test="tool-confirm-cancel" @click="cancelConfirm">{{ t('common.cancel') }}</el-button>
          <el-button type="danger" :loading="confirming" :disabled="confirmSeconds <= 0" data-test="tool-confirm-exec" @click="confirmInvoke">
            {{ t('tool.confirmExec') }}
          </el-button>
        </template>
      </el-dialog>

   
      <el-drawer
        v-model="detailVisible"
        :title="t('tool.detailTitle', { name: detailName })"
        size="640px"
        destroy-on-close
        data-test="tool-detail-drawer"
      >
        <el-tabs v-model="detailTab" data-test="tool-detail-tabs">
          <el-tab-pane :label="t('tool.tabOverview')" name="overview">
            <div v-loading="detailLoading">
              <el-descriptions v-if="detail" :column="2" border data-test="tool-detail-desc">
                <el-descriptions-item :label="t('common.name')">{{ detail.name }}</el-descriptions-item>
                <el-descriptions-item :label="t('tool.type')">{{ detail.typeLabel }}</el-descriptions-item>
                <el-descriptions-item :label="t('tool.ownership')">{{ detail.projectId == null ? t('tool.systemLevel') : t('tool.projectRef', { id: detail.projectId }) }}</el-descriptions-item>
                <el-descriptions-item :label="t('tool.category')">{{ detail.category || '—' }}</el-descriptions-item>
                <el-descriptions-item :label="t('common.status')">
                  <el-tag :type="statusTag(detail.status)" size="small">{{ detail.statusLabel }}</el-tag>
                </el-descriptions-item>
                <el-descriptions-item :label="t('tool.currentVersion')">v{{ detail.currentVersion }}</el-descriptions-item>
                <el-descriptions-item :label="t('tool.desc')" :span="2">{{ detail.description || '—' }}</el-descriptions-item>
                <el-descriptions-item :label="t('tool.tags')" :span="2">{{ detail.tagsJson || '—' }}</el-descriptions-item>
                <el-descriptions-item :label="t('common.createdAt')">{{ detail.createdAt }}</el-descriptions-item>
                <el-descriptions-item :label="t('common.updatedAt')">{{ detail.updatedAt }}</el-descriptions-item>
              </el-descriptions>
              <div v-if="detail?.status === 4 && detail.rejectReason" class="reject-reason" data-test="tool-detail-reject">
                {{ t('tool.rejectReasonEcho', { reason: detail.rejectReason }) }}
              </div>
              <div class="detail-section">
                <div class="detail-section-head">
                  <span class="detail-section-title">{{ detail && detail.type === 1 ? t('tool.bodyLabel') : t('tool.bodySectionContract') }}</span>
                  <el-button link type="primary" data-test="tool-detail-download" @click="doDownload(detailId, null)">{{ t('tool.download') }}</el-button>
                </div>
                <MarkdownPreview :source="detail?.body" data-test="tool-detail-body" />
              </div>
              <div class="detail-section" data-test="tool-detail-schema">
                <div class="detail-section-title">{{ t('tool.inputSchema') }}</div>
                <pre class="detail-json">{{ prettyJson(detail?.inputSchemaJson) }}</pre>
              </div>
              <div class="detail-section" data-test="tool-detail-output-schema">
                <div class="detail-section-title">{{ t('tool.outputSchema') }}</div>
                <pre class="detail-json">{{ prettyJson(detail?.outputSchemaJson) }}</pre>
              </div>
              <template v-if="detail && detail.type === 2">
                <div class="detail-section" data-test="tool-detail-exec">
                  <div class="detail-section-title">{{ t('tool.execConfigTitle', { kind: detail.execKindLabel || '—', ms: detail.timeoutMs }) }}</div>
                  <pre class="detail-json">{{ prettyJson(detail.execSpecJson) }}</pre>
                </div>
              </template>
            </div>
          </el-tab-pane>

          <el-tab-pane :label="t('tool.tabVersions')" name="versions">
            <!-- R25 tab-sync note: the column visibility settings target the
                 manage ledger only; this dialog sub-table shares the page
                 density so the settings control never acts on an invisible
                 decoupled table (S-9 :size binding). -->
            <el-table v-loading="verLoading" :data="verRows" :size="colCfg.state.density" row-key="version" border data-test="tool-version-table">
              <el-table-column :label="t('tool.version')" width="70" align="center">
                <template #default="{ row }">v{{ row.version }}</template>
              </el-table-column>
              <el-table-column :label="t('tool.snapshotStatus')" width="100">
                <template #default="{ row }">
                  <el-tag size="small">{{ row.statusLabel }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column :label="t('tool.changeReason')" width="110">
                <template #default="{ row }">{{ VERSION_REASON_LABELS[row.reason as string] || row.reason || '—' }}</template>
              </el-table-column>
              <el-table-column prop="createdAt" :label="t('tool.colTime')" width="170" />
              <el-table-column :label="t('common.action')" min-width="140">
                <template #default="{ row }">
                  <el-button link type="primary" :data-test="`tool-ver-view-${row.version}`" @click="viewVersion(row.version)">{{ t('tool.view') }}</el-button>
                  <el-button link type="warning" :data-test="`tool-ver-rollback-${row.version}`" @click="confirmRollback(row.version)">{{ t('tool.rollback') }}</el-button>
                  <el-button link type="primary" :data-test="`tool-ver-download-${row.version}`" @click="doDownload(detailId, row.version)">{{ t('tool.download') }}</el-button>
                </template>
              </el-table-column>
              <template #empty>
                <EmptyState :title="t('tool.verEmptyTitle')" :hint="t('tool.verEmptyHint')" />
              </template>
            </el-table>
            <div class="pagination-bar">
              <el-pagination
                v-if="verTotal > 0"
                v-model:current-page="verQuery.page"
                :page-size="verQuery.size"
                :total="verTotal"
                layout="total, prev, pager, next"
                data-test="tool-version-pagination"
                @current-change="loadVersions"
              />
            </div>
            <div v-if="verDetail" class="ver-detail" data-test="tool-version-detail">
              <div class="ver-detail-head">
                <span class="tool-name">{{ t('tool.snapshotHead', { ver: verDetail.version, status: verDetail.statusLabel, reason: VERSION_REASON_LABELS[verDetail.reason || ''] || verDetail.reason || '—' }) }}</span>
                <span class="text-secondary">{{ verDetail.createdAt }}</span>
              </div>
              <pre class="detail-json ver-body" data-test="tool-version-body">{{ verDetail.body || t('tool.noBody') }}</pre>
              <div class="text-secondary ver-fm">{{ t('tool.inputSchemaEcho', { schema: prettyJson(verDetail.inputSchemaJson) }) }}</div>
            </div>
          </el-tab-pane>
        </el-tabs>
      </el-drawer>
    </div>
  </div>
</template>

<script setup lang="ts">

import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { Plus, Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import RowActions from '@/components/RowActions.vue'
import ProjectSelect from '@/components/ProjectSelect.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import ColumnSettings from '@/components/ColumnSettings.vue'
import FormSideEffectHint from '@/components/FormSideEffectHint.vue'
import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'
import MarkdownPreview from '@/components/MarkdownPreview.vue'
import ToolMarketView from './ToolMarketView.vue'
import { checkJsonText } from '@/utils/jsonForm'
import { prettyJson } from '@/utils/jsonPretty'
import { approvalStatusTag } from '@/utils/approvalTag'
import { bizErrorMsg } from '@/utils/bizError'
import { BizError } from '@/api/request'
import { getGenkitCatalog, type GenFunctionGroup, type GenFunctionItem } from '@/api/dataFactory'
import {
  approveTool,
  createTool,
  deleteTool,
  downloadTool,
  getToolDetail,
  getToolVersionDetail,
  invokeTool,
  isWriteSchema,
  issueConfirmToken,
  listToolInvocations,
  listToolVersions,
  listTools,
  rejectTool,
  retireTool,
  rollbackTool,
  submitTool,
  testRunTool,
  updateTool,
  type ToolDetailItem,
  type ToolInvocationItem,
  type ToolItem,
  type ToolTestRunResult,
  type ToolVersionDetailItem,
  type ToolVersionInfoItem,
} from '@/api/tool'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

// ---------- AN-5 page tab (manage / market), URL-query synced ----------
/** Query value wins on entry; an unknown key falls back to the manage tab. */
function tabFromQuery(): 'manage' | 'market' {
  return route.query.tab === 'market' ? 'market' : 'manage'
}
const pageTab = ref<'manage' | 'market'>(tabFromQuery())

// Switching tabs writes the query back (replace = no history spam), so any tab
// state is shareable/refresh-stable per the AN tab-in-URL rule.
watch(pageTab, (tab) => {
  void router.replace({ query: { ...route.query, tab: tab === 'manage' ? undefined : tab } })
})
// In-app navigations into this page carrying ?tab=market (redirect from
// /tool-market, ⌘K deep link) must move the visible tab, not just the URL.
// The page is not keep-alive cached, but the route stays mounted when only the
// query changes, so setup never re-runs — the watcher is mandatory.
watch(() => route.query.tab, () => {
  pageTab.value = tabFromQuery()
})
// AK/: the list segment follows the global header project context; an empty
// context honestly means the system-wide view (backend: null projectId = no
// project filter, system-level tools included).
const { projectId, followTick } = useProjectPageContext()


const TOOL_TYPES = computed(() => [
  { value: 1, label: t('tool.typeDoc') },
  { value: 2, label: t('tool.typeExec') },
])

const TOOL_STATUSES = computed(() => [
  { value: 1, label: t('tool.stDraft') },
  { value: 2, label: t('tool.stPending') },
  { value: 3, label: t('tool.stPublished') },
  { value: 4, label: t('tool.stRejected') },
  { value: 5, label: t('tool.stRetired') },
])

const EXEC_KINDS = computed(() => [
  { value: 1, label: t('tool.ekHttp') },
  { value: 2, label: t('tool.ekExecutor') },
  { value: 3, label: t('tool.ekMcp') },
  { value: 4, label: t('tool.ekGenkit') },
])

const loading = ref(false)
const rows = ref<ToolItem[]>([])
const total = ref(0)
const query = reactive({
  page: 1,
  size: 20,
  keyword: '',
  type: null as number | null,
  status: null as number | null,
  category: '',
  sortBy: '',
  sortDir: '',
})

/** Column prop -> core SORT_COLUMNS whitelist key (only mapped columns render sortable). */
const SORT_KEY_MAP: Record<string, string> = {
  name: 'name',
  typeLabel: 'type',
  category: 'category',
  status: 'status',
  updatedAt: 'updated_at',
}

/** N-3 (R25 scope note): data columns of the manage ledger, driven through the
 * shared useColumnConfig piece — hide/order/pin/density persist per user under
 * the 'tool' viewKey; the locked action column stays outside the config. */
const TOOL_COLUMNS: ColumnDef[] = [
  { key: 'name', labelKey: 'tool.colTool', width: null },
  { key: 'typeLabel', labelKey: 'tool.type', width: 100 },
  { key: 'category', labelKey: 'tool.category', width: 120 },
  { key: 'execKind', labelKey: 'tool.execKind', width: 140 },
  { key: 'status', labelKey: 'common.status', width: 110 },
  { key: 'currentVersion', labelKey: 'tool.version', width: 80 },
  { key: 'updatedAt', labelKey: 'common.updatedAt', width: 170 },
]
/** Flexible columns fall back to min-width when no fixed width is set. */
const TOOL_FLEX_MIN: Record<string, number> = { name: 220 }
const colCfg = useColumnConfig('tool', TOOL_COLUMNS)
/** Popover rows (column state merged with labelKey for rendering). */
const settingsRows = computed(() =>
  colCfg.state.columns.map((c) => ({ ...c, labelKey: TOOL_COLUMNS.find((d) => d.key === c.key)!.labelKey })),
)

// Approval-chain tag color lifted to utils/approvalTag (shared with the SKILL
// domain and the project-detail view doors — no second mapping per page).
const statusTag = approvalStatusTag

async function load(): Promise<void> {
  loading.value = true
  try {
    const page = await listTools({
      page: query.page,
      size: query.size,
      keyword: query.keyword || undefined,
      type: query.type ?? undefined,
      status: query.status ?? undefined,
      category: query.category || undefined,
      projectId: projectId.value ?? undefined,
      sortBy: query.sortBy || undefined,
      sortDir: (query.sortDir || undefined) as 'asc' | 'desc' | undefined,
    })
    rows.value = page.list || []
    total.value = page.total || 0
  } catch (e) {
    ElMessage.error((e as Error).message || t('tool.loadListFailed'))
  } finally {
    loading.value = false
  }
}

function reload(): void {
  query.page = 1
  void load()
}

/** Header sort: forward the whitelisted key to the server (ORDER BY in SQL); cancel = backend default order. */
function onSortChange({ prop, order }: { prop: string; order: string | null }): void {
  if (order === null) {
    query.sortBy = ''
    query.sortDir = ''
    query.page = 1
    void load()
    return
  }
  const key = SORT_KEY_MAP[prop]
  if (!key) {
    void load()
    return
  }
  query.sortBy = key
  query.sortDir = order === 'ascending' ? 'asc' : 'desc'
  query.page = 1
  void load()
}

function rowActions(row: ToolItem) {
  const acts: { key: string; label: string; type?: 'primary' | 'warning' | 'danger' | 'info' | 'success'; confirm?: string }[] = []
  acts.push({ key: 'detail', label: t('tool.view') })
  acts.push({ key: 'download', label: t('tool.download') })
  if (row.status === 1 || row.status === 4 || row.status === 3) {
    acts.push({ key: 'edit', label: t('common.edit') })
  }
  if (row.status === 1 || row.status === 4) {
    acts.push({ key: 'submit', label: t('tool.submitApproval'), confirm: t('tool.confirmSubmit', { name: row.name }) })
  }
  if (row.status === 2) {
    acts.push({ key: 'approve', label: t('tool.approve'), type: 'success', confirm: t('tool.confirmApprove', { name: row.name }) })
    acts.push({ key: 'reject', label: t('tool.reject'), type: 'danger' })
  }
  if (row.status === 3) {
    acts.push({ key: 'retire', label: t('tool.retire'), type: 'warning', confirm: t('tool.confirmRetire', { name: row.name }) })
  }
  if (row.status !== 3) {
    acts.push({ key: 'remove', label: t('common.delete'), type: 'danger', confirm: t('tool.confirmRemove', { name: row.name }) })
  }
  if (auth.isAdmin) {
    acts.push({ key: 'invocations', label: t('tool.invocations') })
    if (row.type === 2) {
      acts.push({ key: 'testRun', label: t('tool.testRun') })
      acts.push({ key: 'invoke', label: t('tool.run') })
    }
  }
  return acts
}

async function onRowCmd(key: string, row: ToolItem): Promise<void> {
  try {
    if (key === 'detail') {
      openDetail(row)
      return
    }
    if (key === 'download') {
      await doDownload(row.id, null)
      return
    }
    if (key === 'edit') {
      await openEdit(row)
      return
    }
    if (key === 'invocations') {
      openInvocations(row)
      return
    }
    if (key === 'testRun') {
      openTestRun(row)
      return
    }
    if (key === 'invoke') {
      openInvoke(row)
      return
    }
    if (key === 'submit') await submitTool(row.id, row.version)
    else if (key === 'approve') await approveTool(row.id, row.version)
    else if (key === 'reject') {
      const r = await ElMessageBox.prompt(t('tool.rejectPrompt', { name: row.name }), t('tool.rejectTitle'), {
        inputValidator: (v: string) => (v && v.trim() ? true : t('tool.rejectReasonRequired')),
        confirmButtonText: t('tool.confirmReject'),
        cancelButtonText: t('common.cancel'),
        type: 'warning',
      })
      await rejectTool(row.id, row.version, r.value.trim())
    } else if (key === 'retire') await retireTool(row.id, row.version)
    else if (key === 'remove') await deleteTool(row.id, row.version)
    ElMessage.success(t('common.success'))
    await load()
  } catch (e) {
    if (e === 'cancel' || e === 'close') return
    ElMessage.error((e as Error).message || t('common.failed'))
  }
}

const formVisible = ref(false)
const saving = ref(false)
const formRef = ref<FormInstance>()
const editingId = ref<number | null>(null)
const editingVersion = ref(0)
const form = reactive({
  name: '',
  type: 1,
  projectId: null as number | null,
  category: '',
  description: '',
  body: '',
  inputSchemaJson: '',
  outputSchemaJson: '',
  execKind: null as number | null,
  execSpecJson: '',
  timeoutMs: 30000,
  submitNow: false,
})

const formRules = computed<FormRules>(() => ({
  name: [{ required: true, message: t('tool.nameRequired'), trigger: 'blur' }],
  type: [{ required: true, message: t('tool.typeRequired'), trigger: 'change' }],
}))

const genkitCatalog = ref<GenFunctionGroup[]>([])
const genkitCatalogLoading = ref(false)
const genkitCatalogLoaded = ref(false)
const selectedFnName = ref<string | null>(null)

function findGenFunction(name: string | null): GenFunctionItem | null {
  if (!name) return null
  for (const g of genkitCatalog.value) {
    const hit = g.functions.find((f) => f.name === name)
    if (hit) return hit
  }
  return null
}

async function ensureGenkitCatalog(): Promise<void> {
  if (genkitCatalogLoaded.value || genkitCatalogLoading.value) return
  genkitCatalogLoading.value = true
  try {
    genkitCatalog.value = await getGenkitCatalog()
    genkitCatalogLoaded.value = true
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('tool.fnLoadFailed')))
  } finally {
    genkitCatalogLoading.value = false
  }
}

function onGenkitSelectOpen(visible: boolean): void {
  if (visible) void ensureGenkitCatalog()
}


const selectedFnHint = computed(() => {
  const fn = findGenFunction(selectedFnName.value)
  if (!fn) return ''
  const params = fn.params && fn.params.length ? fn.params.join('、') : t('tool.fnNoParams')
  return t('tool.fnHint', { params, example: fn.example })
})


function applyGenkitFunction(name: string | null): void {
  if (!name) return
  let spec: Record<string, unknown> = { function: name }
  const text = form.execSpecJson.trim()
  if (text) {
    try {
      const parsed = JSON.parse(text) as unknown
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
        spec = { ...(parsed as Record<string, unknown>), function: name }
      }
    } catch {
    }
  }
  form.execSpecJson = JSON.stringify(spec)
}


function syncGenkitSelectionFromSpec(): void {
  selectedFnName.value = null
  if (form.execKind !== 4) return
  try {
    const parsed = JSON.parse(form.execSpecJson || '') as unknown
    if (parsed && typeof parsed === 'object' && typeof (parsed as Record<string, unknown>).function === 'string') {
      selectedFnName.value = (parsed as Record<string, unknown>).function as string
      void ensureGenkitCatalog()
    }
  } catch {
  }
}


function parseIfText(text: string, label: string): string | null {
  const r = checkJsonText(text)
  if (!r.ok) {
    ElMessage.warning(t('tool.jsonInvalid', { label }))
    return undefined as unknown as null
  }
  return r.value
}

function openCreate(): void {
  editingId.value = null
  form.name = ''
  form.type = 1
  // Form-slot prefill : the
  // create dialog's project field starts at the header context; switching it
  // to "system level" (null) stays an explicit authoring decision, not a
  // second screening selector.
  form.projectId = projectId.value
  form.category = ''
  form.description = ''
  form.body = ''
  form.inputSchemaJson = ''
  form.outputSchemaJson = ''
  form.execKind = null
  form.execSpecJson = ''
  form.timeoutMs = 30000
  form.submitNow = false
  selectedFnName.value = null
  formVisible.value = true
}

async function openEdit(row: ToolItem): Promise<void> {
  editingId.value = row.id
  editingVersion.value = row.version
  form.name = row.name
  form.type = row.type
  form.projectId = row.projectId
  form.category = row.category || ''
  form.description = row.description || ''
  form.inputSchemaJson = row.inputSchemaJson || ''
  form.outputSchemaJson = row.outputSchemaJson || ''
  form.execKind = row.execKind
  form.execSpecJson = row.execSpecJson || ''
  form.timeoutMs = row.timeoutMs
  syncGenkitSelectionFromSpec()
  try {
    const detail = await getToolDetail(row.id)
    form.body = detail.body || ''
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('tool.loadBodyFailed')))
    return
  }
  formVisible.value = true
}

async function submitForm(): Promise<void> {
  await formRef.value?.validate()
  const inputSchema = parseIfText(form.inputSchemaJson, t('tool.inputSchema'))
  if (inputSchema === undefined) return
  const outputSchema = parseIfText(form.outputSchemaJson, t('tool.outputSchema'))
  if (outputSchema === undefined) return
  let execSpec: string | null = null
  if (form.type === 2) {
    execSpec = parseIfText(form.execSpecJson, t('tool.execConfig'))
    if (execSpec === undefined) return
  }
  saving.value = true
  try {
    if (editingId.value) {
      await updateTool(editingId.value, {
        expectedVersion: editingVersion.value,
        name: form.name.trim(),
        category: form.category.trim() || null,
        description: form.description.trim() || null,
        body: form.body.trim() ? form.body : null,
        inputSchemaJson: inputSchema,
        outputSchemaJson: outputSchema,
        execKind: form.type === 2 ? form.execKind : null,
        execSpecJson: execSpec,
        timeoutMs: form.type === 2 ? form.timeoutMs : null,
      })
      ElMessage.success(t('tool.savedRepublish'))
    } else {
      await createTool({
        projectId: form.projectId,
        name: form.name.trim(),
        type: form.type,
        category: form.category.trim() || null,
        description: form.description.trim() || null,
        body: form.body || null,
        inputSchemaJson: inputSchema,
        outputSchemaJson: outputSchema,
        execKind: form.type === 2 ? form.execKind : null,
        execSpecJson: execSpec,
        timeoutMs: form.type === 2 ? form.timeoutMs : null,
        submitNow: form.submitNow,
      })
      ElMessage.success(form.submitNow ? t('tool.createdSubmitted') : t('tool.createdDraft'))
    }
    formVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message || t('tool.saveFailed'))
  } finally {
    saving.value = false
  }
}

const invVisible = ref(false)
const invLoading = ref(false)
const invToolId = ref<number | null>(null)
const invToolName = ref('')
const invRows = ref<ToolInvocationItem[]>([])
const invTotal = ref(0)
const invStart = ref<string>('')
const invEnd = ref<string>('')
const invQuery = reactive({ page: 1, size: 20 })


function callerLabel(caller: string): string {
  if (caller === 'mcp') return 'MCP'
  if (caller === 'ai') return t('tool.callerAi')
  return t('tool.callerUser')
}

function openInvocations(row: ToolItem): void {
  invToolId.value = row.id
  invToolName.value = row.name
  invStart.value = ''
  invEnd.value = ''
  invQuery.page = 1
  invVisible.value = true
  void queryInvocations()
}

async function queryInvocations(): Promise<void> {
  if (invToolId.value == null) return
  invLoading.value = true
  try {
    const page = await listToolInvocations(invToolId.value, {
      page: invQuery.page,
      size: invQuery.size,
      start: invStart.value || undefined,
      end: invEnd.value || undefined,
    })
    invRows.value = page.list || []
    invTotal.value = page.total || 0
  } catch (e) {
    ElMessage.error((e as Error).message || t('tool.loadInvFailed'))
  } finally {
    invLoading.value = false
  }
}

const runVisible = ref(false)
const running = ref(false)
const runToolId = ref<number | null>(null)
const runToolName = ref('')
const runInput = ref('')
const runResult = ref<ToolTestRunResult | null>(null)


const RUN_CATEGORY_LABELS = computed<Record<string, string>>(() => ({
  timeout: t('tool.catTimeout'),
  network: t('tool.catNetwork'),
  http: t('tool.catHttp'),
  format: t('tool.catFormat'),
  auth: t('tool.catAuth'),
  protocol: t('tool.catProtocol'),
  tool: t('tool.catTool'),
}))

function openTestRun(row: ToolItem): void {
  runToolId.value = row.id
  runToolName.value = row.name
  runInput.value = ''
  runResult.value = null
  runVisible.value = true
}

async function execTestRun(): Promise<void> {
  if (runToolId.value == null) return
  running.value = true
  try {
    runResult.value = await testRunTool(runToolId.value, runInput.value.trim() || undefined)
  } catch (e) {
    ElMessage.error((e as Error).message || t('tool.testRunFailed'))
  } finally {
    running.value = false
  }
}

const invokeVisible = ref(false)
const invoking = ref(false)
const invokeToolId = ref<number | null>(null)
const invokeToolName = ref('')
const invokeInput = ref('')
const invokeResult = ref<ToolTestRunResult | null>(null)

const confirmVisible = ref(false)
const confirming = ref(false)
const confirmToolName = ref('')
const confirmTicket = ref('')
const confirmSeconds = ref(0)
const confirmInputEcho = ref('')
let confirmTimer: ReturnType<typeof setInterval> | null = null

function openInvoke(row: ToolItem): void {
  invokeToolId.value = row.id
  invokeToolName.value = row.name
  confirmToolName.value = row.name
  invokeInput.value = ''
  invokeResult.value = null
  invokeVisible.value = true
}

function invokePayload(confirmToken?: string | null) {
  return { input: invokeInput.value.trim() || null, confirmToken: confirmToken || null }
}

async function execInvoke(): Promise<void> {
  if (invokeToolId.value == null) return
  invoking.value = true
  try {
    invokeResult.value = await invokeTool(invokeToolId.value, invokePayload())
  } catch (e) {
    if (e instanceof BizError && e.code === '40001') {
      try {
        const ticket = await issueConfirmToken(invokeToolId.value)
        startConfirm(ticket.token, ticket.expiresInSeconds)
        return
      } catch (e2) {
        ElMessage.error(bizErrorMsg(e2, t('tool.ticketReqFailed')))
        return
      }
    }
    ElMessage.error(bizErrorMsg(e, t('tool.invokeReqFailed')))
  } finally {
    invoking.value = false
  }
}

function startConfirm(token: string, seconds: number): void {
  confirmTicket.value = token
  confirmSeconds.value = seconds
  confirmInputEcho.value = invokeInput.value.trim() || t('tool.emptyInput')
  confirmVisible.value = true
  if (confirmTimer) clearInterval(confirmTimer)
  confirmTimer = setInterval(() => {
    if (confirmSeconds.value > 0) confirmSeconds.value -= 1
  }, 1000)
}

function closeConfirm(): void {
  confirmVisible.value = false
  confirmTicket.value = ''
  if (confirmTimer) {
    clearInterval(confirmTimer)
    confirmTimer = null
  }
}

function cancelConfirm(): void {
  closeConfirm()
}

async function confirmInvoke(): Promise<void> {
  if (invokeToolId.value == null || !confirmTicket.value) return
  confirming.value = true
  try {
    invokeResult.value = await invokeTool(invokeToolId.value, invokePayload(confirmTicket.value))
    closeConfirm()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('tool.ticketInvokeFailed')))
    closeConfirm()
  } finally {
    confirming.value = false
  }
}

const detailVisible = ref(false)
const detailLoading = ref(false)
const detailTab = ref<'overview' | 'versions'>('overview')
const detailId = ref<number>(0)
const detailName = ref('')
const detail = ref<ToolDetailItem | null>(null)
const verRows = ref<ToolVersionInfoItem[]>([])
const verTotal = ref(0)
const verLoading = ref(false)
const verQuery = reactive({ page: 1, size: 20 })
const verDetail = ref<ToolVersionDetailItem | null>(null)


const VERSION_REASON_LABELS = computed<Record<string, string>>(() => ({
  edit: t('tool.vrEdit'),
  'rollback-pre': t('tool.vrRollbackPre'),
  retire: t('tool.vrRetire'),
}))

/** JSON pretty-print (shared util utils/jsonPretty, same source as the project-detail doors). */

function openDetail(row: ToolItem): void {
  detailId.value = row.id
  detailName.value = row.name
  detail.value = null
  verDetail.value = null
  detailTab.value = 'overview'
  detailVisible.value = true
  detailLoading.value = true
  getToolDetail(row.id)
    .then((d) => {
      detail.value = d
    })
    .catch((e: unknown) => {
      ElMessage.error(bizErrorMsg(e, t('tool.loadDetailFailed')))
      detailVisible.value = false
    })
    .finally(() => {
      detailLoading.value = false
    })
  void loadVersions()
}


async function loadVersions(): Promise<void> {
  verLoading.value = true
  try {
    const page = await listToolVersions(detailId.value, verQuery.page, verQuery.size)
    verRows.value = page.list || []
    verTotal.value = page.total || 0
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('tool.loadVersionsFailed')))
  } finally {
    verLoading.value = false
  }
}

async function viewVersion(version: number): Promise<void> {
  try {
    verDetail.value = await getToolVersionDetail(detailId.value, version)
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('tool.snapshotLoadFailed', { ver: version })))
  }
}


async function doDownload(id: number, version: number | null): Promise<void> {
  try {
    await downloadTool(id, version)
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('tool.downloadFailed')))
  }
}


async function confirmRollback(version: number): Promise<void> {
  try {
    await ElMessageBox.confirm(
      t('tool.rollbackConfirmMsg', { ver: version }),
      t('tool.rollbackTitle'),
      { confirmButtonText: t('tool.rollbackConfirmBtn'), cancelButtonText: t('common.cancel'), type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await rollbackTool(detailId.value, { version, expectedVersion: detail.value?.version ?? 0 })
    ElMessage.success(t('tool.rollbackDone', { ver: version }))
    const d = await getToolDetail(detailId.value)
    detail.value = d
    verDetail.value = null
    await Promise.all([loadVersions(), load()])
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('tool.rollbackFailed')))
  }
}

onUnmounted(() => {
  if (confirmTimer) clearInterval(confirmTimer)
})

onMounted(() => {
  void load()
})

// : a header context switch rewrites the project segment — follow the new
// primaryId and reload from page 1 (in-page screening changes never bump
// followTick, so no double load).
watch(followTick, () => {
  reload()
})
</script>

<style scoped>
.tool-name {
  font-weight: 600;
}
.genkit-opt-desc {
  float: right;
  margin-left: 24px;
  color: var(--tp-text-secondary);
  font-size: 12px;
}
.genkit-fn-hint {
  margin-top: 4px;
  color: var(--tp-text-secondary);
  font-size: 12px;
  line-height: 1.5;
}
.tool-desc {
  color: var(--tp-text-secondary);
  font-size: 12px;
  margin-top: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.reject-reason {
  color: var(--el-color-danger);
  font-size: 12px;
  margin-top: 2px;
}
.inv-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}
.inv-hint {
  font-size: 12px;
}
.run-input-item {
  margin-bottom: 8px;
}
.run-result {
  margin-top: 4px;
}
.run-result-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}
.run-output {
  max-height: 320px;
  overflow: auto;
  padding: 10px 12px;
  border: 1px solid var(--tp-border-color);
  border-radius: 6px;
  background: var(--tp-fill-color-light, rgba(127, 127, 127, 0.06));
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0;
}
.text-secondary {
  color: var(--tp-text-secondary);
}
</style>
