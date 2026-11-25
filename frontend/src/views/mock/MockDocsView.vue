<!--
  tp-owner-echo-exempt: reason = the structured create entry is hidden while the
  header context is empty (v-if="canWrite && spaceId" + the page-level
  "mock-docs-no-project" empty block covers iron rule 2 by hiding, per the
  scanner-recognised hidden-entry form); the create drawer itself never hosts a
  project selector — space+project ride on the single-source header context.
-->
<template>
  <div class="page-root mock-docs-page" data-test="mock-docs-page">
    <div :class="embedded ? 'mock-docs-embedded' : 'page-card'">
      <!-- R23 stage-2: the title shell row is gone — the page name rides the
 global topbar and the retired title-side "?" now lives on the topbar
 help dot (data-test=header-help-btn); the descriptive subtitle is
 dropped with the shell (its content lives in the help chapter).
 IA law inherited: screening (space picker) + refresh live in
 the filter-bar row; the page write actions moved to the leading
 slot of the same row (no second selector in any action slot). -->

      <div v-if="projectId && (spaces.length || !loadingSpaces)" class="filter-bar" data-test="mock-docs-filter-bar">
        <!-- R23 stage-2: empty title shell removed (page name rides the global
             topbar, "?" rides the topbar help dot). The structured create
             primary action + AI entries moved into the filter-bar leading slot
             (R23 law 5: actions left, screening center, view-state trailing).
             The V240 edit round: cards carry edit / disable / delete doors — the
             core save chain is the single gate for all three, and the
             confirmation dialog shows the rule's 7-day traffic impact before a
             live edit or a delete goes through. -->
        <el-button
          v-if="canWrite && spaceId"
          type="primary"
          data-test="mock-docs-new-structured"
          @click="openCreateDrawer"
        >
          {{ t('mockDocs.structuredCreate') }}
        </el-button>
        <!-- round 4b: AI drafting entries (buttons only — screening vs
 doing separation keeps selectors out of the action slot). Filing
 needs write perm; the job list stays reachable read-only. -->
        <el-button v-if="canWrite && spaceId" data-test="mock-docs-ai-draft" @click="openAiDialog">
          {{ t('mockAi.draftEntry') }}
        </el-button>
        <el-button v-if="spaceId" data-test="mock-docs-ai-jobs" @click="openAiJobs">
          {{ t('mockAi.jobsEntry') }}
        </el-button>
        <!-- Bulk data-enrichment door: rides the same leading-slot family as
             the drafting entries — one action button plus one dialog, no new
             page. The job type names itself on screen as the plain words for
             "AI data enrichment". -->
        <el-button v-if="canWrite && spaceId" data-test="mock-docs-ai-bulk" @click="openBulkDialog">
          {{ t('mockAi.bulkEntry') }}
        </el-button>
        <el-select
          v-if="!embedded"
                    v-model="pickedSpace"
          :placeholder="t('mockDocs.selectSpace')"
          filterable
          style="width: 220px"
          data-test="mock-docs-space-select"
          :disabled="!spaces.length"
          @change="syncSpaceToUrl()"
        >
          <el-option v-for="s in spaces" :key="s.id" :label="mockSpaceLabel(s)" :value="s.id" />
        </el-select>
        <div class="filter-bar-actions" data-test="mock-docs-filter-actions">
          <!-- One filled primary per band: the structured-create door owns the
               primary slot, so the import door reads as a secondary outline
               button (the two used to compete for the same visual weight). -->
          <el-button
            v-if="canWrite && spaceId !== null"
            data-test="mock-docs-import-contract"
            @click="stubDirectOpen = true"
          >
            {{ t('mockWorkbench.importContractAction') }}
          </el-button>
          <el-button :icon="Refresh" :loading="loading" data-test="mock-docs-refresh" @click="loadStubs">
            {{ t('mockDocs.refresh') }}
          </el-button>
        </div>
      </div>

      <div v-if="!projectId" class="empty-block" data-test="mock-docs-no-project">
        <EmptyState :title="t('mockDocs.noProject')" />
      </div>
      <div v-else-if="!loadingSpaces && !spaces.length" class="empty-block" data-test="mock-docs-no-space">
        <EmptyState :title="t('mockDocs.noSpace')" :hint="t('mockDocs.noSpaceHint')" />
      </div>

      <template v-else-if="spaceId">
        <!-- §12a item 6: cross-group jump shows the origin chain with a
             filter-preserving back door (query ride kept on return). -->
        <div v-if="cameFromLabel && originTarget" class="origin-bar" data-test="mock-docs-origin-bar">
          <span class="muted">{{ t('mockDocs.originNote', { from: cameFromLabel }) }}</span>
          <el-button link type="primary" data-test="mock-docs-origin-back" @click="backToOrigin">
            {{ t('mockDocs.originBack') }}
          </el-button>
        </div>
        <!-- Serving state honesty: only a published space answers traffic. -->
        <div v-if="selectedSpace && selectedSpace.status !== 1" class="state-note" data-test="mock-docs-not-published">
          {{ t('mockDocs.notPublishedNote', { status: statusLabel(selectedSpace.status) }) }}
        </div>

        <!-- Paste-a-curl fast create (F1 path-2). The parser only pre-fills;
             legality is decided by the core save gate, whose plain-word refusal
             (40001) is shown untouched. -->
        <div v-if="canWrite" class="curl-box" data-test="mock-docs-curl-box">
          <div class="curl-title">{{ t('mockDocs.curlTitle') }}</div>
          <div class="curl-hint">{{ t('mockDocs.curlHint') }}</div>
          <el-input
            v-model="curlText"
            type="textarea"
            :rows="4"
            :placeholder="t('mockDocs.curlPlaceholder')"
            data-test="mock-docs-curl-input"
          />
          <div v-if="parseError" class="curl-error" data-test="mock-docs-curl-error">{{ parseError }}</div>
          <div v-else-if="preview" class="curl-preview" data-test="mock-docs-curl-preview">
            <span class="mono method-tag" :class="`m-${preview.method.toLowerCase()}`">{{ preview.method }}</span>
            <span class="mono">{{ preview.pathPattern }}</span>
            <span v-if="matcherCount" class="muted">· {{ t('mockDocs.previewMatchers', { n: matcherCount }) }}</span>
            <span class="muted">· {{ responseSummary(preview.responseJson) }}</span>
          </div>
          <el-button
            type="primary"
            :disabled="!preview"
            :loading="creating"
            data-test="mock-docs-curl-create"
            @click="createFromCurl"
          >
            {{ t('mockDocs.curlCreate') }}
          </el-button>
        </div>

        <TableSkeleton v-if="loading && !stubs.length" :cols="4" data-test="mock-docs-skeleton" />
        <div v-else-if="!stubs.length" class="empty-block" data-test="mock-docs-empty">
          <EmptyState :title="t('mockDocs.emptyStubs')" :hint="t('mockDocs.emptyStubsHint')" />
        </div>

        <!-- display-scale iron law: at worst-case sizes a flat card wall is
 unreadable, so the stub area gets an instant search (method/path/tag
 over the loaded window) and grouping by resource path with groups
 collapsed by default (header carries the counts). Zero extra server
 requests — screening happens over what the space already served. -->
        <template v-else>
          <el-input
            v-model="stubKeyword"
            :placeholder="t('mockDocs.stubSearchPh')"
            :prefix-icon="Search"
            clearable
            style="width: 260px; margin-bottom: 12px"
            data-test="mock-docs-stub-search"
          />
          <div v-if="!stubGroups.length" class="empty-block" data-test="mock-docs-stub-no-match">
            <EmptyState :title="t('mockDocs.stubNoMatch')" />
          </div>
          <div v-for="g in stubGroups" :key="g.key" class="stub-group" data-test="mock-docs-stub-group">
            <button
              type="button"
              class="stub-group-head"
              :aria-expanded="expandedGroups[g.key] ? 'true' : 'false'"
              :data-test="`mock-docs-stub-group-head-${g.key}`"
              @click="toggleGroup(g.key)"
            >
              <el-icon class="group-caret" :size="13"><component :is="expandedGroups[g.key] ? CaretBottom : CaretRight" /></el-icon>
              <span class="mono group-path">/{{ g.key }}</span>
              <span class="muted">{{ t('mockDocs.stubGroupCount', { n: g.rows.length }) }}</span>
              <el-tag v-if="g.disabledCount" size="small" type="warning">{{ t('mockDocs.stubGroupDisabled', { n: g.disabledCount }) }}</el-tag>
            </button>
            <div v-if="expandedGroups[g.key]" class="doc-cards">
              <div v-for="row in g.rows" :key="row.id" class="doc-card" data-test="mock-docs-card">
                <div class="card-head">
                  <span class="mono method-tag" :class="`m-${row.method.toLowerCase()}`">{{ row.method }}</span>
                  <span class="mono path-text">{{ row.pathPattern }}</span>
                  <el-tag size="small" type="info">{{ pathMatchLabel(row.pathMatchType) }}</el-tag>
                  <el-tag v-if="row.enabled === false" size="small" type="warning">{{ t('mockDocs.disabledTag') }}</el-tag>
                  <!-- §12a origin badge: where the rule was born + one-step return
                       door (manual rules have no door — nothing invented). -->
                  <el-tag
                    v-if="sourceLabel(row.source)"
                    size="small"
                    :class="['source-tag', `src-${row.source}`]"
                    data-test="mock-docs-source-tag"
                  >
                    {{ sourceLabel(row.source) }}
                  </el-tag>
                  <!-- AI-enriched badge: enrichment is an attribute, not a
                       birth fact — the origin badge above keeps its own value. -->
                  <el-tooltip v-if="row.enrichedBy" :content="t('mockDocs.enrichedTip')" placement="top">
                    <el-tag size="small" type="success" class="enriched-tag" data-test="mock-docs-enriched-tag">
                      {{ t('mockDocs.enrichedTag') }}
                    </el-tag>
                  </el-tooltip>
                  <el-button
                    v-if="jumpTargetForSource({ source: row.source, sourceRef: row.sourceRef, projectId, spaceId })"
                    link
                    type="primary"
                    class="source-jump"
                    data-test="mock-docs-source-jump"
                    :aria-label="t('mockDocs.sourceJumpAria', { name: sourceLabel(row.source) })"
                    @click="jumpToSource(row)"
                  >
                    {{ t('mockDocs.sourceJump') }}
                  </el-button>
                  <span class="grow"></span>
                  <span class="muted prio">{{ t('mockDocs.priorityTag', { p: row.priority ?? '—' }) }}</span>
                  <template v-if="canWrite">
                    <el-button link type="primary" data-test="mock-docs-card-edit" @click="openEditDrawer(row)">
                      {{ t('mockDocs.actEdit') }}
                    </el-button>
                    <el-button link type="primary" data-test="mock-docs-card-toggle" @click="toggleEnabled(row)">
                      {{ row.enabled === false ? t('mockDocs.actEnable') : t('mockDocs.actDisable') }}
                    </el-button>
                    <el-button link type="danger" data-test="mock-docs-card-delete" @click="confirmDelete(row)">
                      {{ t('mockDocs.actDelete') }}
                    </el-button>
                  </template>
                </div>
                <div class="card-body">
                  <div class="card-label">{{ t('mockDocs.matcherLabel') }}</div>
                  <div v-if="matcherSummary(row.matchersJson)" class="mono matcher-text" data-test="mock-docs-card-matchers">
                    {{ matcherSummary(row.matchersJson) }}
                  </div>
                  <div v-else class="muted">{{ t('mockDocs.matcherNone') }}</div>
                  <div class="card-label">{{ t('mockDocs.responseLabel') }}</div>
                  <div class="mono matcher-text" data-test="mock-docs-card-response">{{ responseSummary(row.responseJson) }}</div>
                  <!-- fault visibility: a stub that carries a fault in its
 builder payload must say so where the rule is read — the
 chip names the type + probability through the shared label
 util (one dictionary, never a second copy). -->
                  <div v-if="faultChip(row.responseJson)" class="fault-chip" data-test="mock-docs-card-fault">
                    {{ t('mockFault.chipPrefix') }} · {{ faultChip(row.responseJson) }}
                  </div>
                  <div class="card-label">{{ t('mockDocs.urlLabel') }}</div>
                  <div class="url-line">
                    <span class="mono">{{ stubUrl(row) }}</span>
                    <el-button link type="primary" data-test="mock-docs-copy-curl" @click="copyCurl(row)">
                      {{ t('mockDocs.actCopyCurl') }}
                    </el-button>
                    <el-button
                      v-if="row.method === 'GET' && spacePublished"
                      link
                      type="primary"
                      data-test="mock-docs-try"
                      @click="tryIt(row)"
                    >
                      {{ t('mockDocs.actTry') }}
                    </el-button>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </template>
        <!-- IA: the inline full-graph wall is retired. Scenarios keep only
 a summary bar here (counts computed over the loaded declarations)
 and jump to the standalone /mock/scenarios surface with the space
 context riding. The graph — a heavy asset — renders one per screen
 on the detail page, never per row on a list. -->
        <div class="scenario-box" data-test="mock-docs-scenario-box">
          <div class="scenario-head">
            <div>
              <div class="curl-title">{{ t('mockDocs.scenarioTitle') }}</div>
              <div class="curl-hint">{{ t('mockDocs.scenarioSummaryHint') }}</div>
            </div>
            <span class="grow"></span>
            <span class="muted scenario-counts" data-test="mock-docs-scenario-summary">
              <span>{{ t('mockDocs.scenarioCount', { n: scenarios.length }) }}</span>
              <span v-if="scenarioHealth.healthy" class="ok-part">{{ t('mockDocs.scenarioHealthy', { n: scenarioHealth.healthy }) }}</span>
              <span v-if="scenarioHealth.needsFix" class="warn-part" data-test="mock-docs-scenario-needsfix">
                {{ t('mockDocs.scenarioNeedsFix', { n: scenarioHealth.needsFix }) }}
              </span>
            </span>
            <el-button link type="primary" data-test="mock-docs-scenario-list" @click="gotoScenarioList">
              {{ t('mockDocs.scenarioListJump') }}
            </el-button>
          </div>
          <div v-if="!scenarios.length" class="empty-block" data-test="mock-docs-scenario-empty">
            <EmptyState :title="t('mockDocs.scenarioEmpty')" />
          </div>
        </div>

        <!-- / V240: structured create/edit drawer. V240 follow-up
 lifted the form into the shared MockStubEditorDrawer component (the
 endpoint inventory page consumes the SAME editor — two entry doors,
 zero fork). Opened through the editorRef below. -->
        <MockStubEditorDrawer
          ref="editorRef"
          :space-id="spaceId"
          :space-published="spacePublished"
          @saved="onEditorSaved"
        />

        <!-- AI drafting surface. Filing dialog — the job is
 dispatched immediately and the pinned cost quote shows on the task
 card afterwards (nothing burns before submit). AI drafts never enter
 the rule source directly: the tick-to-confirm drawer below is the
 only ingress, provenance forced nl-ai by the service. -->
        <!-- Bulk data-enrichment dialog: pick one parsed contract version of
             this project, the job is filed with a fixed plain-words prompt —
             the endpoint list itself is the requirement. Submission opens the
             process tail window before the ack returns (dispatch-first form),
             so the first server frame never lands on an empty screen. -->
        <el-dialog
          v-model="bulkDialogVisible"
          :title="t('mockAi.bulkDialogTitle')"
          width="520px"
          data-test="mock-ai-bulk-dialog"
        >
          <ProjectOwnerEcho />
          <el-form label-position="top">
            <el-form-item :label="t('mockAi.bulkFSpec')" data-test="mock-ai-bulk-spec-wrap">
              <el-select
                v-model="bulkSpecId"
                filterable
                :loading="bulkSpecLoading"
                :placeholder="t('mockAi.bulkSpecPh')"
                style="width: 100%"
                data-test="mock-ai-bulk-spec"
              >
                <el-option
                  v-for="s in bulkSpecRows"
                  :key="s.id"
                  :label="`${s.name} · v${s.specVersion}`"
                  :value="s.id"
                />
              </el-select>
            </el-form-item>
            <div class="ai-hint" data-test="mock-ai-bulk-hint">{{ t('mockAi.bulkHint') }}</div>
          </el-form>
          <template #footer>
            <el-button type="primary" :loading="bulkFiling" data-test="mock-ai-bulk-file" @click="fileBulkEnrich">
              {{ t('mockAi.bulkSubmit') }}
            </el-button>
          </template>
        </el-dialog>

        <el-dialog
          v-model="aiDialogVisible"
          :title="t('mockAi.dialogTitle')"
          width="520px"
          data-test="mock-ai-dialog"
        >
          <ProjectOwnerEcho />
          <el-form label-position="top">
            <el-form-item :label="t('mockAi.fJobType')" data-test="mock-ai-f-type-wrap">
              <el-select v-model="aiForm.jobType" filterable style="width: 100%" data-test="mock-ai-f-type">
                <el-option v-for="jt in AI_JOB_TYPES" :key="jt" :label="t(`mockAi.type.${jt}`)" :value="jt" />
              </el-select>
            </el-form-item>
            <el-form-item :label="t('mockAi.fPrompt')" data-test="mock-ai-f-prompt-wrap">
              <el-input
                v-model="aiForm.prompt"
                type="textarea"
                :rows="5"
                maxlength="4000"
                :placeholder="t('mockAi.promptPh')"
                data-test="mock-ai-f-prompt"
              />
            </el-form-item>
            <div class="ai-hint" data-test="mock-ai-file-hint">{{ t('mockAi.fileThenQuoteHint') }}</div>
          </el-form>
          <template #footer>
            <el-button type="primary" :loading="aiFiling" data-test="mock-ai-file" @click="fileAiJob">
              {{ t('mockAi.fileJob') }}
            </el-button>
          </template>
        </el-dialog>

        <!-- Task drawer: job cards with status, pinned estimate, quote/view/ignore
             verbs. Read-only audiences can open it too (estimates burn nothing). -->
        <el-drawer
          v-model="aiJobsVisible"
          :title="t('mockAi.jobsTitle')"
          size="560px"
          data-test="mock-ai-jobs-drawer"
        >
          <div v-if="aiJobsFollowing" class="muted ai-jobs-following" data-test="mock-ai-jobs-following">
            {{ t('mockAi.jobsFollowing') }}
          </div>
          <div v-if="!aiJobs.length && !aiJobsLoading" class="empty-block" data-test="mock-ai-jobs-empty">
            <EmptyState :title="t('mockAi.jobsEmpty')" :hint="t('mockAi.jobsEmptyHint')" />
          </div>
          <div v-for="job in aiJobs" :key="job.id" class="ai-job-card" data-test="mock-ai-job-card">
            <div class="card-head">
              <el-tag size="small" :type="aiJobStatusTag(job.status)">{{ t(`mockAi.status.${job.status}`) }}</el-tag>
              <span class="path-text">{{ t(`mockAi.type.${job.jobType}`) }}</span>
              <span class="grow" />
              <span class="muted mono">#{{ job.id }}</span>
            </div>
            <p class="ai-job-prompt">{{ promptOf(job) }}</p>
            <div v-if="estimateOf(job)" class="ai-estimate" data-test="mock-ai-estimate-line">
              {{ estimateOf(job) }}
            </div>
            <div class="ai-job-actions">
              <el-button link type="primary" :loading="aiEstimating === job.id" @click="quoteAiJob(job)">
                {{ t('mockAi.actEstimate') }}
              </el-button>
              <el-button
                v-if="job.status === 'preview' || job.status === 'consumed'"
                link
                type="primary"
                @click="openAiDrafts(job)"
              >
                {{ t('mockAi.actViewDrafts') }}
              </el-button>
              <el-button
                v-if="canWrite && (job.status === 'queued' || job.status === 'running' || job.status === 'preview')"
                link
                type="danger"
                @click="ignoreAiJobQuiet(job)"
              >
                {{ t('mockAi.actIgnore') }}
              </el-button>
              <!-- R62/R67 revisit door: opens this job's process tail — live
                   runs keep following, settled runs replay the cursor window
                   from zero (no dropped frames, no bulk refresh). -->
              <el-button link type="primary" data-test="mock-ai-job-tail" @click="openJobTail(job)">
                {{ t('mockAi.actProcessDetail') }}
              </el-button>
            </div>
          </div>
        </el-drawer>

        <!-- Job process tail drawer: shared windowed component (constant
             height, up-scroll pauses the follow, terminal frame collapses
             it). R67 expiry door: when history proves frames existed while
             the cursor window came back empty, the component itself shows
             the retention pointer naming the hot-reload parameter key. -->
        <TaskTailDrawer
          v-if="jobTailOpen"
          :events="jobTailEvents"
          :live="jobTailRunning"
          :stalled="jobTailStalled"
          :interrupted="jobTailInterrupted"
          :expired="jobTailExpired"
          retention-param="mock.spec-import.history-frame-cap"
          :heartbeat-seconds="tailHeartbeatSeconds"
          :first-frame-ms="tailFirstFrameMs"
          data-test="mock-ai-job-tail-drawer"
        />

        <!-- Confirmation drawer: keyword search + tick cards paged at 20, the
             tick keeps the ORIGINAL row index so confirm lands exactly what was
             shown; consumed jobs stay readable (re-view what landed) but the
             checkboxes are disabled. Empty tick selection stops locally. -->
        <el-drawer
          v-model="aiDraftVisible"
          :title="t('mockAi.drawerTitle')"
          size="640px"
          data-test="mock-ai-drawer"
        >
          <p v-if="aiDraftJob" class="muted" data-test="mock-ai-drawer-note">
            {{ t('mockAi.drawerJobNote', { id: aiDraftJob.id, type: t(`mockAi.type.${aiDraftJob.jobType}`) }) }}
          </p>
          <el-input
            v-model="aiDraftKeyword"
            clearable
            :placeholder="t('mockAi.draftSearchPh')"
            data-test="mock-ai-draft-search"
            style="margin-bottom: 12px"
          />
          <div v-if="!filteredDrafts.length" class="empty-block" data-test="mock-ai-draft-empty">
            <EmptyState :title="aiDrafts.length ? t('mockAi.draftNoMatch') : t('mockAi.drawerEmpty')" />
          </div>
          <el-checkbox-group v-model="aiTicked">
            <div v-for="row in pagedDrafts" :key="row.index" class="ai-draft-card" data-test="mock-ai-draft-card">
              <el-checkbox :value="row.index" :disabled="aiDraftReadOnly">
                <div class="card-head">
                  <span class="method-tag" :class="`m-${row.item.method.toLowerCase()}`">{{ row.item.method }}</span>
                  <span class="path-text mono">{{ row.item.path }}</span>
                  <span class="muted">[{{ pathMatchLabel(row.item.pathMatchType) }}]</span>
                </div>
                <div class="ai-draft-meta">
                  <span>{{ row.item.name }}</span>
                  <span v-if="row.item.scenarioName" class="muted">· {{ row.item.scenarioName }}</span>
                  <span v-if="row.item.faultJson" class="fault-chip">{{ draftFaultChip(row.item.faultJson) }}</span>
                </div>
              </el-checkbox>
            </div>
          </el-checkbox-group>
          <div class="pagination-bar">
            <el-pagination
              v-if="filteredDrafts.length > AI_DRAFT_PAGE_SIZE"
              v-model:current-page="aiDraftPage"
              :page-size="AI_DRAFT_PAGE_SIZE"
              :total="filteredDrafts.length"
              layout="prev, pager, next"
              data-test="mock-ai-draft-pager"
            />
          </div>
          <template #footer>
            <span class="ai-selected-count" data-test="mock-ai-selected-count">
              {{ t('mockAi.selectedCount', { n: aiTicked.length }) }}
            </span>
            <el-button
              type="primary"
              :loading="aiConfirming"
              :disabled="aiDraftReadOnly"
              data-test="mock-ai-drawer-confirm"
              @click="confirmAiDrafts"
            >
              {{ t('mockAi.actConfirm') }}
            </el-button>
          </template>
        </el-drawer>
      </template>
    </div>
    <MockStubDirectWizard
      v-model="stubDirectOpen"
      :project-id="projectId"
      :preset-space-id="spaceId"
      :preset-space-name="activeSpace ? mockSpaceLabel(activeSpace) : ''"
    />
  </div>
</template>
<script setup lang="ts">
/**
 * AG batch : Mock-as-documentation — the read-only doc page a
 * space renders automatically (design F12). Every endpoint card shows the
 * current stub behaviour as plain words: method/path with its match-type badge,
 * matcher summary, response preview, and a copyable URL / curl example with a
 * browser "try it" door for GET on published spaces. The page is hand-drawn in
 * the design system — deliberately no iframe of an external doc UI (W4 red line).
 *
 * Fast create: paste one curl (optionally with its HTTP response block) and the
 * parser pre-fills a stub; legality is judged once at the core save gate, whose
 * plain-word refusal is shown untouched — the page never claims validity itself.
 */
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import TaskTailDrawer from '@/components/TaskTailDrawer.vue'
import { useTestRunTail } from '@/composables/useTestRunTail'
import { tailFirstFrameMs, tailHeartbeatSeconds, tailTerminalDwellMs } from '@/composables/tailWindowConfig'
import { listApiSpecs, type ApiSpecView } from '@/api/apiSpec'
import { listAiJobProcessEvents, isMockEnrichTerminalRow } from '@/api/mock'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CaretBottom, CaretRight, Refresh, Search } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import MockStubEditorDrawer from '@/components/MockStubEditorDrawer.vue'
// Shared contract-direct wizard (single component, two mount points: the
// API-test page and the space workbench docs tab — no second copy).
import MockStubDirectWizard from '@/components/mock/MockStubDirectWizard.vue'
// AK single-source posture : page-level project selector removed — the
// project rides on the global header context via useProjectPageContext.
import ProjectOwnerEcho from '@/components/ProjectOwnerEcho.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import { openHelp } from '@/composables/useHelpDrawer'
import { useListAutoFollow } from '@/composables/useListAutoFollow'
import { listAutoFollowMs, loadTailWindowConfig } from '@/composables/tailWindowConfig'
import { useMockSpaceUrlPick } from '@/composables/useMockSpaceUrlPick'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import { bizErrorMsg } from '@/utils/bizError'
import { faultChipText } from '@/utils/mockFaultLabels'
import { parseCurlDraft, type CurlParsedDraft } from '@/utils/mockCurlParse'
import { jumpTargetForSource } from '@/utils/mockJump'
// PATH_MATCH_TYPES / HTTP_METHODS moved with the editor into MockStubEditorDrawer
import { mockEndpointCurl, mockEndpointUrl } from '@/utils/mockServingUrl'
import { readScenarioGraph } from '@/utils/mockScenarioGraph'
import { useAuthStore } from '@/stores/auth'
import {
  confirmAiJob,
  createAiJob,
  createStub,
  deleteStub,
  estimateAiJob,
  getAiJob,
  getServingUrl,
  ignoreAiJob,
  isMockAiJobPending,
  listAiJobDrafts,
  listAiJobs,
  listScenarios,
  listSpaces,
  listStubs,
  recentStubHits,
  updateStub,
  type MockAiJobDraftItem,
  type MockAiJobEstimate,
  type MockAiJobItem,
  type MockScenarioItem,
  type MockSpaceItem,
  type MockServingUrl,
  type MockStubCreatePayload,
  type MockStubItem,
} from '@/api/mock'

const { t } = useI18n()
const route = useRoute()
// AN-4 embedded contract: the space workbench hosts this page as a tab
// and injects the picked space id (props default off — standalone mounting
// stays possible while retired paths still redirect).
const props = withDefaults(defineProps<{ embedded?: boolean; spaceId?: number | null }>(), { embedded: false, spaceId: null })
const embedded = computed(() => props.embedded)
const router = useRouter()
const auth = useAuthStore()
// AK single-source posture: shared composable owns the project state.
const { projectId } = useProjectPageContext()

const canWrite = computed(() => auth.hasPerm('perm:mock:write'))

const spaces = ref<MockSpaceItem[]>([])
const loadingSpaces = ref(false)
const pickedSpace = ref<number | null>(null)
// §12a URL pre-filter wiring (shared with the traffic inspector — one impl).
const { pickFromQuery, syncSpaceToUrl } = useMockSpaceUrlPick(pickedSpace, projectId)
const stubs = ref<MockStubItem[]>([])
const loading = ref(false)
const servingUrl = ref<MockServingUrl | null>(null)

const curlText = ref('')
const creating = ref(false)

// : host-injected space id wins over the local pick (redirect-era fallback kept).
const spaceId = computed(() => props.spaceId ?? pickedSpace.value)
const selectedSpace = computed(() => spaces.value.find((s) => s.id === pickedSpace.value) ?? null)
// Host-injected space (workbench tab) or the local pick — the wizard's
// destination echo reads the same source the rule list is scoped by.
const activeSpace = computed(() => spaces.value.find((s) => s.id === spaceId.value) ?? null)
const stubDirectOpen = ref(false)
const spacePublished = computed(() => selectedSpace.value?.status === 1)

const parseResult = computed(() => (curlText.value.trim() ? parseCurlDraft(curlText.value) : null))
const preview = computed<CurlParsedDraft | null>(() => (parseResult.value?.ok ? parseResult.value.draft : null))
const parseError = computed(() => (parseResult.value && !parseResult.value.ok ? t('mockDocs.curlUnparsed') : ''))
const matcherCount = computed(() => {
  if (!preview.value?.matchersJson) return 0
  try {
    return JSON.parse(preview.value.matchersJson).length
  } catch {
    return 0
  }
})

function statusLabel(status: number | null): string {
  if (status === 1) return t('mockDocs.statusPublished')
  if (status === 2) return t('mockDocs.statusRetired')
  return t('mockDocs.statusDraft')
}

function pathMatchLabel(type: string): string {
  // The wire spectrum is uppercase (EXACT/PREFIX...), locale keys lowercase —
  // normalize here so every caller reads one Chinese label, never a raw key.
  const key = `mockDocs.pathType.${type.toLowerCase()}`
  const label = t(key)
  // unknown spectrum value (a future server-side type): show it raw, never blank
  return label === key ? type : label
}

/** Origin badge text — null for an unknown/missing source (no badge invented). */
function sourceLabel(source: string | null): string | null {
  if (!source) return null
  const key = `mockDocs.source.${source}`
  const label = t(key)
  return label === key ? source : label
}

/** One-step return to the page the rule was born from (§12a item 2). */
function jumpToSource(row: MockStubItem): void {
  const target = jumpTargetForSource({ source: row.source, sourceRef: row.sourceRef, projectId: projectId.value, spaceId: spaceId.value })
  if (target) void router.push({ path: target.path, query: target.query })
}

/** Where this visit came from (query key set by the sending page). */
const cameFrom = computed<string | null>(() => {
  const v = route.query.source
  const raw = Array.isArray(v) ? v[0] : v
  return typeof raw === 'string' && raw.length > 0 ? raw : null
})
const cameFromLabel = computed(() => (cameFrom.value ? sourceLabel(cameFrom.value) : null))
// The bar only shows when a real return door exists (an unknown sender has none).
const originTarget = computed(() =>
  jumpTargetForSource({ source: cameFrom.value, sourceRef: null, projectId: projectId.value, spaceId: spaceId.value }))

/** Back to the sender, filters intact — the ride key is dropped on the way back. */
function backToOrigin(): void {
  const target = originTarget.value
  if (!target) return
  const { source: _dropped, ...rest } = route.query
  void router.push({ path: target.path, query: rest })
}

async function loadSpaces(): Promise<void> {
  servingUrl.value = await getServingUrl().catch(() => null)
  if (!projectId.value) {
    spaces.value = []
    pickedSpace.value = null
    return
  }
  loadingSpaces.value = true
  try {
    spaces.value = await listSpaces(projectId.value)
    // §12a pre-fill: a spaceId riding the URL lands only if it is in this
    // project's loaded list (honest consumption — never a guessed id).
    const fromQuery = pickFromQuery(spaces.value.map((s) => s.id))
    if (fromQuery != null) pickedSpace.value = fromQuery
    else if (!spaces.value.some((s) => s.id === pickedSpace.value)) {
      pickedSpace.value = spaces.value[0]?.id ?? null
    }
    void syncSpaceToUrl()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('mockDocs.loadSpacesFailed')))
    spaces.value = []
  } finally {
    loadingSpaces.value = false
  }
}

// Receipt deep-link consumer: the wizard's "try the first one" action lands
// here carrying ?tryStub=<id>. It runs right after the rule list arrives,
// strips the query key first (a later refresh must not auto-open a browser
// tab again) and names every unmet guard out loud — a dead-end link is a
// defect, so all three refusals carry a reason plus the way forward.
async function consumeTryStub(): Promise<void> {
  const v = route.query.tryStub
  const raw = Array.isArray(v) ? v[0] : v
  if (!raw) return
  const { tryStub: _consumed, ...rest } = route.query
  void router.replace({ query: rest })
  const row = stubs.value.find((s) => String(s.id) === raw)
  if (!row) {
    ElMessage.info(t('mockDocs.tryRowMissing'))
    return
  }
  if (row.method.toUpperCase() !== 'GET') {
    ElMessage.info(t('mockDocs.tryNotGet', { method: row.method }))
    return
  }
  const host = spaces.value.find((s) => s.id === spaceId.value)
  if (host && host.status !== 1) {
    ElMessage.info(t('mockDocs.tryNeedPublish'))
    return
  }
  tryIt(row)
}

async function loadStubs(): Promise<void> {
  if (!spaceId.value) {
    stubs.value = []
    return
  }
  loading.value = true
  try {
    stubs.value = await listStubs(spaceId.value)
    void consumeTryStub()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('mockDocs.loadStubsFailed')))
    stubs.value = []
  } finally {
    loading.value = false
  }
}

// Declared before the immediate watch below: the watch fires during setup,
// and loadScenarios touches this ref on its empty-space early return — a
// later declaration would sit in the temporal dead zone at that instant.
const scenarios = ref<MockScenarioItem[]>([])

watch(projectId, () => {
  pickedSpace.value = null
  void loadSpaces()
})
watch(spaceId, () => {
  void loadStubs()
  void loadScenarios()
}, {
  // The workbench tab hosts this view with a constant spaceId prop: without
  // an immediate first run the watch never fires and the rules never load.
  immediate: true,
})

/** Matcher rows condensed into one plain line — parsing failures stay visible, never silent. */
function matcherSummary(json: string | null): string {
  if (!json) return ''
  try {
    const rows = JSON.parse(json) as Array<Record<string, unknown>>
    if (!Array.isArray(rows) || !rows.length) return ''
    return rows
      .map((r) => {
        if (r.kind === 'body') return `body(${r.bodyType})=${short(String(r.value ?? r.path ?? ''))}`
        return `${r.kind} ${r.key} ${r.matchType ?? 'exact'} ${short(String(r.value ?? ''))}`
      })
      .join(' · ')
  } catch {
    return json.slice(0, 200)
  }
}

function short(v: string): string {
  const one = v.replace(/\s+/g, ' ').trim()
  return one.length > 40 ? `${one.slice(0, 40)}…` : one
}

function responseSummary(json: string | null): string {
  if (!json) return t('mockDocs.responseEmpty')
  try {
    const resp = JSON.parse(json) as Record<string, unknown>
    const body = typeof resp.body === 'string' ? short(resp.body) : ''
    return `→ ${resp.status ?? 200} ${resp.bodyMode === 'template' ? t('mockDocs.responseTemplate') : t('mockDocs.responseStatic')}${body ? ` ${body}` : ''}`
  } catch {
    return short(json)
  }
}

/** Fault chip text for one builder payload via the shared label util (null =
 *  no fault — absence is never invented). */
function faultChip(json: string | null): string | null {
  return faultChipText(json, (key) => t(key))
}

/** Space route = serving root + '/' + routing handle (never an invented host);
 *  composition lives in the shared util — the live-endpoints page renders the
 *  identical shape from there (reuse >=2, one owner). */
function stubUrl(row: MockStubItem): string {
  return mockEndpointUrl(servingUrl.value, selectedSpace.value?.spaceKey, row.pathPattern)
}

async function copyText(text: string, okKey: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(t(okKey))
  } catch {
    ElMessage.warning(t('mockDocs.copyDenied'))
  }
}

function copyCurl(row: MockStubItem): void {
  const url = stubUrl(row)
  if (!url) {
    ElMessage.warning(t('mockDocs.servingUnavailable'))
    void loadSpaces()
    return
  }
  void copyText(mockEndpointCurl(row.method, url), 'mockDocs.curlCopied')
}

/** "Try it" (F12): browser straight to the traffic plane — GET only, published only. */
function tryIt(row: MockStubItem): void {
  const url = stubUrl(row)
  if (!url) {
    ElMessage.warning(t('mockDocs.servingUnavailable'))
    void loadSpaces()
    return
  }
  window.open(url, '_blank', 'noopener')
}

async function createFromCurl(): Promise<void> {
  if (!spaceId.value || !preview.value) return
  creating.value = true
  try {
    // capture the path BEFORE clearing the textarea: preview is a computed
    // over curlText, and reading it after the clear dereferences null — the
    // resulting TypeError was swallowed by the catch below and silently
    // skipped the refresh (this exact bug kept t359b red after the reveal
    // helper landed: created row invisible, page read as "lost my work")
    const createdPath = preview.value.pathPattern
    await createStub(spaceId.value, preview.value)
    ElMessage.success(t('mockDocs.createdFromCurl'))
    curlText.value = ''
    revealGroup(createdPath)
    await loadStubs()
  } catch (e) {
    // core save-gate refusals (illegal matcher row, bad path shape, banned env)
    // arrive as plain words — show them untouched.
    ElMessage.error(bizErrorMsg(e, t('mockDocs.createFailed')))
  } finally {
    creating.value = false
  }
}

// ---- : scenario summary bar (the graph surface lives on /mock/scenarios)
// Honest boundary: the read door here is still the space's full declaration
// list (counts only — nothing renders inline), while the standalone scenario
// surface consumes the bounded paged door. Grouping/search below screen the
// already-served stub window client-side: zero extra requests per keystroke.
// (The `scenarios` ref itself is declared above the immediate watch — TDZ.)
const scenarioHealth = computed(() => {
  let healthy = 0
  let needsFix = 0
  for (const row of scenarios.value) {
    const read = readScenarioGraph(row.definitionJson)
    if (read.ok && !read.graph.dangling.length) healthy++
    else needsFix++
  }
  return { healthy, needsFix }
})

/** Jump to the space workbench scenarios tab carrying the current context . */
function gotoScenarioList(): void {
  void router.push({
    path: `/mock/spaces/${spaceId.value}`, query: { tab: 'scenarios', projectId: String(projectId.value ?? '') },
  })
}

async function loadScenarios(): Promise<void> {
  if (!spaceId.value) {
    scenarios.value = []
    return
  }
  try {
    scenarios.value = await listScenarios(spaceId.value)
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('mockDocs.scenarioLoadFailed')))
    scenarios.value = []
  }
}

// ---- : stub screening (instant search + resource-path grouping) --------
const stubKeyword = ref('')
/** Collapse state per group key — groups default to CLOSED (display-scale law). */
const expandedGroups = ref<Record<string, boolean>>({})
/** V240: auto-expand on load only for genuinely small spaces (both caps). */
const AUTO_EXPAND_MAX_RULES = 8
const AUTO_EXPAND_MAX_GROUPS = 5

// V240 follow-up ( half-done seam, root cause of the t359b/t359c E2E
// reds): "groups default collapsed" is the WORST-CASE display law, not a
// small-space punishment — a freshly created or jumped-to rule that is
// invisible reads as "the page lost my work". Small-space courtesy (few
// rules AND few groups) auto-expands on every list refresh; at scale the
// collapsed default stands (t363b's 33-rule fixture keeps its negative
// anchor). This lives in the same block as the collapse state on purpose:
// hoisting it into loadStubs (earlier attempt) read the consts before their
// declaration — same-name consts hoist as TDZ, the throw was swallowed by
// the catch and the block silently died.
watch(stubs, (rows) => {
  const groups = new Set(rows.map(resourceKeyOf))
  if (rows.length <= AUTO_EXPAND_MAX_RULES && groups.size <= AUTO_EXPAND_MAX_GROUPS) {
    groups.forEach((k) => { expandedGroups.value[k] = true })
  }
})

function toggleGroup(key: string): void {
  expandedGroups.value[key] = !expandedGroups.value[key]
}

/** V240 follow-up: a rule that was just created / edited / toggled must be
 *  VISIBLE. Groups open collapsed by default (display-scale law), but a
 *  write always lands on a row the operator just touched — revealing that
 *  one group is the honest middle ground between the collapsed wall and
 *  dumping everything. (This is the half-done seam that turned the t359b
 *  paste-a-curl E2E red: the created rule landed invisibly collapsed.) */
function revealGroup(pathPattern: string): void {
  const seg = pathPattern.split('/').filter((x) => x.length > 0)[0] ?? 'root'
  expandedGroups.value[seg] = true
}

/** First path segment = the resource a rule hangs under ("users" of /users/{id}). */
function resourceKeyOf(row: MockStubItem): string {
  const seg = row.pathPattern.split('/').filter((s) => s.length > 0)[0] ?? ''
  return seg || 'root'
}

const stubGroups = computed(() => {
  const k = stubKeyword.value.trim().toLowerCase()
  const matched = k
    ? stubs.value.filter(
        (r) =>
          r.method.toLowerCase().includes(k) ||
          r.pathPattern.toLowerCase().includes(k) ||
          r.name.toLowerCase().includes(k),
      )
    : stubs.value
  const byKey = new Map<string, MockStubItem[]>()
  for (const row of matched) {
    const key = resourceKeyOf(row)
    const arr = byKey.get(key)
    if (arr) arr.push(row)
    else byKey.set(key, [row])
  }
  return Array.from(byKey.entries())
    .sort((a, b) => a[0].localeCompare(b[0]))
    .map(([key, rows]) => ({
      key,
      rows,
      disabledCount: rows.filter((r) => r.enabled === false).length,
    }))
})

// ---- / V240: structured create/edit drawer — lifted into the
// shared MockStubEditorDrawer component (the endpoint inventory page consumes
// the same editor; two doors, zero fork). The page keeps only the mode state
// and the save refresh wiring.
const editorRef = ref<InstanceType<typeof MockStubEditorDrawer> | null>(null)

function openCreateDrawer(): void {
  editorRef.value?.open(null)
}

function openEditDrawer(row: MockStubItem): void {
  editorRef.value?.open(row)
}

async function onEditorSaved(mode: 'create' | 'update', path: string): Promise<void> {
  // create lands a fresh row; update (and a refused stale-echo save, handled
  // inside the drawer via the conflict emit) both need the list re-read so
  // the next open carries the fresh version echo. V240 follow-up: also reveal
  // the group the saved rule lives in — a collapsed write reads as nothing
  // happening (the exact seam that turned t359b red).
  void mode
  revealGroup(path)
  await loadStubs()
}

// ---- V240 edit segment: disable/enable switch + delete with impact preview ---
/** One-click serving switch — the full payload of the row is echoed through
 *  the update door with enabled flipped (core owns the merge semantics; the
 *  page sends what it read, nothing invented). */
async function toggleEnabled(row: MockStubItem): Promise<void> {
  const nextEnabled = row.enabled === false
  try {
    await updateStub(row.id, {
      name: row.name,
      priority: row.priority,
      method: row.method,
      pathMatchType: row.pathMatchType,
      pathPattern: row.pathPattern,
      matchersJson: row.matchersJson,
      responseJson: row.responseJson,
      scenarioName: row.scenarioName,
      requiredState: row.requiredState,
      newState: row.newState,
      enabled: nextEnabled,
      expectedVersion: row.version ?? 1,
    })
    ElMessage.success(nextEnabled ? t('mockDocs.enabledOk') : t('mockDocs.disabledOk'))
    revealGroup(row.pathPattern)
    await loadStubs()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('mockDocs.updateFailed')))
    await loadStubs()
  }
}

/** Delete = the loud door. The prompt first reads the rule's 7-day impact so
 *  the operator decides against traffic truth, not against a bare yes/no; a
 *  live rule with hits gets the stronger warning wording. */
async function confirmDelete(row: MockStubItem): Promise<void> {
  let hits: number | null = null
  try {
    hits = await recentStubHits(row.id)
  } catch {
    hits = null
  }
  const live = spacePublished.value && row.enabled !== false
  const detail = hits == null
    ? (live ? t('mockDocs.deleteConfirmLiveUnknown') : t('mockDocs.deleteConfirmQuiet'))
    : (live && hits > 0
        ? t('mockDocs.deleteConfirmLive', { hits })
        : t('mockDocs.deleteConfirmQuietHits', { hits }))
  try {
    await ElMessageBox.confirm(detail, t('mockDocs.deleteConfirmTitle', { name: row.name }), {
      type: live ? 'warning' : 'info',
      confirmButtonText: t('mockDocs.deleteConfirmOk'),
      cancelButtonText: t('common.cancel'),
    })
  } catch {
    return // dismissed
  }
  try {
    await deleteStub(row.id)
    ElMessage.success(t('mockDocs.deletedOk'))
    await loadStubs()
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('mockDocs.deleteFailed')))
  }
}

// ---- AI drafting jobs (file → quote → read back → tick-confirm)
// The whole surface rides the seven admin /mock/jobs doors; this page never
// talks to ai-service and never invents provenance (core forces nl-ai +
// job:{id} on confirm). AI drafts stay out of the rule source until a human
// ticks them — the confirmation drawer is the only ingress by design.
const AI_JOB_TYPES = ['nl-stub', 'edge-scenarios', 'spec-sync'] as const
const AI_DRAFT_PAGE_SIZE = 20

const aiDialogVisible = ref(false)
const aiJobsVisible = ref(false)
const aiDraftVisible = ref(false)
const aiForm = reactive({ jobType: 'nl-stub' as string, prompt: '' })
const aiFiling = ref(false)
const aiJobs = ref<MockAiJobItem[]>([])
const aiJobsLoading = ref(false)
const aiEstimating = ref<number | null>(null)
/** Quotes fetched this session, keyed by job id (the card also renders the
 *  creation-time pinned snapshot from budgetSnapshotJson — quotes never
 *  re-price a filed job). */
const aiQuotes = reactive<Record<number, MockAiJobEstimate>>({})
const aiDraftJob = ref<MockAiJobItem | null>(null)
const aiDrafts = ref<MockAiJobDraftItem[]>([])
const aiDraftKeyword = ref('')
const aiDraftPage = ref(1)
const aiTicked = ref<number[]>([])
const aiConfirming = ref(false)

function openAiDialog(): void {
  if (!spaceId.value) return
  aiForm.jobType = 'nl-stub'
  aiForm.prompt = ''
  aiDialogVisible.value = true
}

/** File a drafting job. Empty prompt stops locally — no server round-trip,
 *  no generic required-field error after the fact (iron rule: plain words). */
async function fileAiJob(): Promise<void> {
  if (!spaceId.value) return
  if (!aiForm.prompt.trim()) {
    ElMessage.warning(t('mockAi.needsPrompt'))
    return
  }
  aiFiling.value = true
  try {
    const job = await createAiJob({
      spaceId: spaceId.value,
      jobType: aiForm.jobType,
      prompt: aiForm.prompt.trim(),
    })
    aiDialogVisible.value = false
    aiJobs.value = [job, ...aiJobs.value]
    aiJobsVisible.value = true
    aiJobsFollow.start()
    ElMessage.success(t('mockAi.filed'))
  } catch (e) {
    // switch-gate refusals come back with the exact config key in plain
    // words from core — shown untouched, never re-worded here.
    ElMessage.error(bizErrorMsg(e, t('mockAi.fileFailed')))
  } finally {
    aiFiling.value = false
  }
}

function openAiJobs(): void {
  if (!spaceId.value) return
  aiJobsVisible.value = true
  void loadAiJobs().then(() => aiJobsFollow.start())
}

// ---- bulk data-enrichment filing + one shared job-process tail ------------
// The tail rides the platform cursor-window runner over the admin read door
// GET /mock/jobs/{id}/process-events — same decoder, same windowing rules as
// every other process tail, no second window implementation here.
const bulkDialogVisible = ref(false)
const bulkSpecId = ref<number | null>(null)
const bulkSpecRows = ref<ApiSpecView[]>([])
const bulkSpecLoading = ref(false)
const bulkFiling = ref(false)
/** One-shot guard so the revisit deep link fires once per job id. */
let revisitedJobId = -1

async function openBulkDialog(): Promise<void> {
  if (!spaceId.value || !projectId.value) return
  bulkSpecId.value = null
  bulkDialogVisible.value = true
  bulkSpecLoading.value = true
  try {
    const page = await listApiSpecs({ projectId: projectId.value, page: 1, size: 50 })
    bulkSpecRows.value = page.list
  } catch (e) {
    // The cap/size story is server-side; a read failure only names itself.
    ElMessage.error(bizErrorMsg(e, t('mockAi.jobsLoadFailed')))
  } finally {
    bulkSpecLoading.value = false
  }
}

/** File the bulk enrichment job. The tail window opens BEFORE the ack
 *  (dispatch-first form) so the first server frame never lands on an empty
 *  screen; a refusal closes it again. The fixed plain-words prompt states
 *  what the job does — the endpoint list itself is the requirement. */
async function fileBulkEnrich(): Promise<void> {
  if (!spaceId.value) return
  if (bulkSpecId.value === null) {
    ElMessage.warning(t('mockAi.bulkNeedsSpec'))
    return
  }
  bulkFiling.value = true
  beginPending()
  try {
    const job = await createAiJob({
      spaceId: spaceId.value,
      jobType: 'spec-enrich',
      prompt: t('mockAi.bulkPrompt', { id: bulkSpecId.value }),
      specId: bulkSpecId.value,
    })
    bulkDialogVisible.value = false
    aiJobs.value = [job, ...aiJobs.value]
    // The tail window is the only surface a filed process shows itself on
    // (R62 process-visibility door): auto-opening the jobs drawer here would
    // modal-occlude the tail the run just opened. The card list re-reads
    // silently every time the panel is opened, so no refresh step is lost.
    aiJobsFollow.start()
    ElMessage.success(t('mockAi.bulkFiled'))
    void openTailRun({ runToken: String(job.id) })
  } catch (e) {
    // switch-gate and cap refusals come back from core with the exact key /
    // real numbers in plain words — shown untouched (R63 three-part door).
    cancelPending()
    ElMessage.error(bizErrorMsg(e, t('mockAi.fileFailed')))
  } finally {
    bulkFiling.value = false
  }
}

/** Terminal frames ride the single-source judgement exported with the read
 *  door (view and wiring contract share it — no second copy). */
const enrichTail = useTestRunTail(
  (runToken, cursor) => listAiJobProcessEvents(Number(runToken), cursor),
  {
    terminal: isMockEnrichTerminalRow,
    emptyGracePolls: 600,
    maxPolls: 2400,
    closeOnTerminal: true,
    terminalDwellMs: () => tailTerminalDwellMs.value,
    heartbeatMs: () => tailHeartbeatSeconds.value * 1000,
  },
)
const {
  events: jobTailEvents, running: jobTailRunning, drawerOpen: jobTailOpen,
  stalled: jobTailStalled, interrupted: jobTailInterrupted, expired: jobTailExpired,
  openRun: openTailRun, beginPending, cancelPending,
} = enrichTail

/** Open one job's process tail: live runs keep following where they are,
 *  settled runs replay the whole cursor window from zero — never a dead
 *  button, never a manual refresh instruction. */
function openJobTail(job: MockAiJobItem): void {
  aiJobsVisible.value = false
  void openTailRun({ runToken: String(job.id) }).then(() => {
    // R67 expiry verdict (single-source formula, no per-page re-derivation):
    // a sealed enrichment run always lands at least its terminal frame, so
    // history proves frames existed. When the cursor window then comes back
    // empty, the sweeper retired them — the drawer swaps to the retention
    // pointer instead of a dead empty box.
    if (job.status !== 'queued' && job.status !== 'running') {
      enrichTail.noteReplayExpiry(1)
    }
  })
}

// R67 revisit deep link (?job=<id>): shared links and history entries land
// on the run's process tail itself, not on an unfiltered list. Once the
// space snapshot is ready, the target job card's door opens automatically.
watch(
  () => [route.query.job, spaceId.value] as const,
  ([jid, sid]) => {
    if (!jid || !sid) return
    const target = Number(jid)
    if (Number.isNaN(target) || revisitedJobId === target) return
    revisitedJobId = target
    void loadAiJobs({ silent: true }).then(() => {
      const job = aiJobs.value.find((j) => j.id === target)
      if (job) openJobTail(job)
    })
  },
  { immediate: true },
)

/** Silent beats (auto-follow re-reads) keep the previous snapshot on screen:
 *  no skeleton flicker, no error toast storm when one poll fails. Explicit
 *  opens still show the loading state and surface failures. */
async function loadAiJobs(opts: { silent?: boolean } = {}): Promise<void> {
  if (!spaceId.value) return
  if (!opts.silent) aiJobsLoading.value = true
  try {
    aiJobs.value = await listAiJobs(spaceId.value)
  } catch (e) {
    if (!opts.silent) ElMessage.error(bizErrorMsg(e, t('mockAi.jobsLoadFailed')))
  } finally {
    if (!opts.silent) aiJobsLoading.value = false
  }
}

// R62 ①③ (D-face): while any card sits queued/generating the drawer re-reads
// the list on the hot-updated beat (ui.list.auto-follow-ms), so statuses move
// to preview/failed on their own; the beat self-stops on panel close or when
// every card reaches a settled state — no manual refresh anywhere.
void loadTailWindowConfig()
const aiJobsFollow = useListAutoFollow<MockAiJobItem>({
  active: () => aiJobsVisible.value,
  rows: () => aiJobs.value,
  isPending: isMockAiJobPending,
  reload: () => loadAiJobs({ silent: true }),
  intervalMs: () => listAutoFollowMs.value,
})
const aiJobsFollowing = aiJobsFollow.following

/** The filed requirement text, re-read from the job's input snapshot. */
function promptOf(job: MockAiJobItem): string {
  if (!job.inputJson) return ''
  try {
    const parsed = JSON.parse(job.inputJson) as { prompt?: string }
    return parsed.prompt || job.inputJson
  } catch {
    return job.inputJson
  }
}

function parseBudget(job: MockAiJobItem): MockAiJobEstimateFields | null {
  if (!job.budgetSnapshotJson) return null
  try {
    const b = JSON.parse(job.budgetSnapshotJson) as Record<string, unknown>
    if (typeof b.estimatedInputTokens !== 'number' || typeof b.outputQuotaTokens !== 'number') return null
    return {
      estimatedInputTokens: b.estimatedInputTokens,
      outputQuotaTokens: b.outputQuotaTokens,
      estimatedTotalTokens: typeof b.estimatedTotalTokens === 'number'
        ? b.estimatedTotalTokens
        : b.estimatedInputTokens + b.outputQuotaTokens,
    }
  } catch {
    return null
  }
}

type MockAiJobEstimateFields = Pick<
  MockAiJobEstimate,
  'estimatedInputTokens' | 'outputQuotaTokens' | 'estimatedTotalTokens'
>

/** Estimate line on the card: pinned creation-time snapshot first (it is the
 *  price the dispatch was actually filed at); a live quote click refreshes
 *  the view for jobs without a pin. Absence renders nothing — never a fake 0. */
function estimateOf(job: MockAiJobItem): string | null {
  const live = aiQuotes[job.id]
  const pinned = parseBudget(job) ?? (live
    ? {
        estimatedInputTokens: live.estimatedInputTokens,
        outputQuotaTokens: live.outputQuotaTokens,
        estimatedTotalTokens: live.estimatedTotalTokens,
      }
    : null)
  if (!pinned) return null
  return t('mockAi.estimateLine', {
    input: pinned.estimatedInputTokens,
    quota: pinned.outputQuotaTokens,
    total: pinned.estimatedTotalTokens,
  })
}

/** El-tag hue per status (unknown tokens from a newer server stay info —
 *  the page never blanks a state it does not know). */
function aiJobStatusTag(status: string): 'success' | 'warning' | 'danger' | 'info' | 'primary' {
  switch (status) {
    case 'consumed': return 'success'
    case 'preview': return 'warning'
    case 'failed': return 'danger'
    case 'running': return 'primary'
    default: return 'info'
  }
}

async function quoteAiJob(job: MockAiJobItem): Promise<void> {
  aiEstimating.value = job.id
  try {
    aiQuotes[job.id] = await estimateAiJob(job.id)
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('mockAi.estimateFailed')))
  } finally {
    aiEstimating.value = null
  }
}

async function ignoreAiJobQuiet(job: MockAiJobItem): Promise<void> {
  try {
    await ignoreAiJob(job.id)
    ElMessage.success(t('mockAi.ignored'))
    await loadAiJobs()
  } catch (e) {
    // terminal-state refusals ("history stays sealed") arrive in plain words
    ElMessage.error(bizErrorMsg(e, t('mockAi.ignoreFailed')))
  }
}

async function openAiDrafts(job: MockAiJobItem): Promise<void> {
  aiDraftJob.value = job
  aiDrafts.value = []
  aiTicked.value = []
  aiDraftKeyword.value = ''
  aiDraftPage.value = 1
  aiDraftVisible.value = true
  try {
    aiDrafts.value = await listAiJobDrafts(job.id)
  } catch (e) {
    // no-preview / vanished-bundle refusals are core's single point; the
    // drawer stays open on its empty state carrying that wording.
    ElMessage.error(bizErrorMsg(e, t('mockAi.draftLoadFailed')))
  }
}

/** Keyword filter keeping the ORIGINAL row index — ticks address rows the
 *  user actually saw, so paging/searching can never confirm the wrong item. */
const filteredDrafts = computed(() => {
  const kw = aiDraftKeyword.value.trim().toLowerCase()
  return aiDrafts.value
    .map((item, index) => ({ item, index }))
    .filter(({ item }) =>
      !kw ||
      item.method.toLowerCase().includes(kw) ||
      item.path.toLowerCase().includes(kw) ||
      item.name.toLowerCase().includes(kw))
})

const pagedDrafts = computed(() => {
  const start = (aiDraftPage.value - 1) * AI_DRAFT_PAGE_SIZE
  return filteredDrafts.value.slice(start, start + AI_DRAFT_PAGE_SIZE)
})

// keyword changes must not strand the pager on an out-of-range page (declare
// after the refs — TDZ).
watch(aiDraftKeyword, () => { aiDraftPage.value = 1 })

/** consumed = re-view what landed (read-only); confirm is preview-status only
 *  and core refuses anything else, but the button gates locally too. */
const aiDraftReadOnly = computed(() => aiDraftJob.value?.status !== 'preview')

/** One drafted item → the stub payload the core save gate accepts. The fault
 *  the model drafted (kept verbatim in the bundle) folds into responseJson as
 *  the builder document's `fault` key — same shape the structured form writes,
 *  so confirmation lands rules indistinguishable from hand-drafted ones. */
function draftToPayload(row: MockAiJobDraftItem): MockStubCreatePayload {
  let response: Record<string, unknown> = {}
  try {
    response = JSON.parse(row.responseJson || '{}') as Record<string, unknown>
  } catch {
    response = {}
  }
  if (row.faultJson) {
    try {
      response.fault = JSON.parse(row.faultJson) as Record<string, unknown>
    } catch {
      // unparseable fault text stays out rather than half-merged — the core
      // validator would reject the whole rule anyway; the verbatim bundle row
      // remains readable for re-filing.
    }
  }
  return {
    name: row.name,
    method: row.method,
    pathMatchType: row.pathMatchType,
    pathPattern: row.path,
    responseJson: JSON.stringify(response),
    scenarioName: row.scenarioName,
  }
}

/** Fault chip for a bare fault document (draft items carry the fault object
 *  itself, not the whole response doc the stub cards parse). */
function draftFaultChip(faultJson: string): string | null {
  try {
    const fault = JSON.parse(faultJson) as Record<string, unknown>
    return faultChipText(JSON.stringify({ fault }), (key) => t(key))
  } catch {
    return null
  }
}

/** Confirm = the human gate. Empty tick selection stops locally (never a
 *  round-trip that core would refuse with a generic wording). */
async function confirmAiDrafts(): Promise<void> {
  const job = aiDraftJob.value
  if (!job || aiTicked.value.length === 0) {
    ElMessage.warning(t('mockAi.needsSelection'))
    return
  }
  aiConfirming.value = true
  try {
    const rows = aiTicked.value.map((i) => draftToPayload(aiDrafts.value[i]!))
    const landed = await confirmAiJob(job.id, rows)
    ElMessage.success(t('mockAi.confirmed', { n: landed.length }))
    aiDraftVisible.value = false
    await Promise.all([loadAiJobs(), loadStubs()])
  } catch (e) {
    ElMessage.error(bizErrorMsg(e, t('mockAi.confirmFailed')))
  } finally {
    aiConfirming.value = false
  }
}

// mock-domain ledger: the docs page renders the current space's
// stub rules (the documented traffic contract), so a landed create/edit of a
// rule re-reads the stub list in place; a landed space only needs the space
// picker refreshed. Both stay silent — no toast/confirm/jump (iron rules 2-3).
import { useObjectSync } from '@/composables/useObjectSync'
import { mockSpaceLabel } from '@/utils/mockSpaceLabel'

useObjectSync({
  watch: ['MOCK_STUB_WRITE', 'MOCK_SPACE_WRITE'],
  onSync: (event) => {
    if (event.objectType === 'MOCK_STUB_WRITE') void loadStubs()
    else void loadSpaces()
  },
})

void loadSpaces()
void loadScenarios()
</script>

<style scoped>
.mock-docs-page { padding: 16px 20px; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; flex-wrap: wrap; margin-bottom: 12px; }
.page-title { margin: 0; font-size: 18px; }
.page-subtitle { margin: 4px 0 0; font-size: 12px; color: var(--el-text-color-secondary); }
.header-actions { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.mono { font-family: var(--el-font-family-mono, monospace); font-size: 12px; }
.muted { color: var(--el-text-color-secondary); }
.ai-jobs-following { display: block; margin-bottom: 12px; font-size: 12px; }
/* The stub-area empty state sits between two content bands inside the
   workbench docs tab, so its full-page frame became a dead whitespace wall
   (267px band for 123px of content, 2026-11-25 forensics). Tighten the
   vertical part to the registered 8px step within this page scope only —
   the shared component keeps its roomy default for standalone use. */
.empty-block { padding: 0; }
.empty-block :deep(.empty-state) { padding-block: var(--ds-space-2, 8px); }
.origin-bar { display: flex; align-items: center; gap: 8px; padding: 6px 12px; border-radius: 6px; margin-bottom: 12px; font-size: 13px; background: var(--el-color-primary-light-9); }
.source-tag { font-size: 11px; }
.source-jump { font-size: 12px; margin-left: 2px; }
.state-note { padding: 8px 12px; border-radius: 6px; margin-bottom: 12px; font-size: 13px; background: var(--el-color-warning-light-9); color: var(--el-text-color-primary); }
.curl-box { padding: 12px; border: 1px solid var(--el-border-color-lighter); border-radius: 8px; margin-bottom: 12px; display: flex; flex-direction: column; gap: 8px; }
.curl-title { font-weight: 600; font-size: 13px; }
.curl-hint { font-size: 12px; color: var(--el-text-color-secondary); }
.curl-error { color: var(--el-color-danger); font-size: 12px; }
.curl-preview { display: flex; align-items: center; gap: 8px; font-size: 12px; flex-wrap: wrap; }
.fault-chip {
  display: inline-flex;
  align-items: center;
  margin: 4px 0;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 12px;
  background: var(--el-color-warning-light-9);
  color: var(--el-color-warning-dark-2);
}
.doc-cards { display: flex; flex-direction: column; gap: 10px; }
.doc-card { border: 1px solid var(--el-border-color-lighter); border-radius: 8px; padding: 12px 14px; }
.card-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.path-text { font-size: 13px; font-weight: 600; }
.grow { flex: 1; }
.prio { font-size: 12px; }
.card-body { margin-top: 8px; display: flex; flex-direction: column; gap: 4px; }
.card-label { font-size: 12px; color: var(--el-text-color-secondary); margin-top: 4px; }
.matcher-text { font-size: 12px; word-break: break-all; }
.url-line { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.method-tag { padding: 2px 8px; border-radius: 4px; background: var(--el-color-info-light-8); font-weight: 600; }
.method-tag.m-get { background: var(--el-color-success-light-8); color: var(--el-color-success); }
.method-tag.m-post { background: var(--el-color-warning-light-8); color: var(--el-color-warning); }
.method-tag.m-put { background: var(--el-color-primary-light-8); color: var(--el-color-primary); }
.method-tag.m-delete { background: var(--el-color-danger-light-8); color: var(--el-color-danger); }
.scenario-box { margin-top: 16px; padding: 12px; border: 1px solid var(--el-border-color-lighter); border-radius: 8px; }
.scenario-head { display: flex; align-items: flex-start; gap: 8px; margin-bottom: 8px; }
.scenario-counts { display: inline-flex; gap: 10px; font-size: 12px; }

.scenario-counts span + span::before { content: '·'; margin-right: 10px; color: var(--el-border-color); }
.scenario-counts .ok-part { color: var(--el-color-success); }
.scenario-counts .warn-part { color: var(--el-color-warning); }
.stub-group { margin-bottom: 12px; }
.stub-group-head { display: flex; align-items: center; gap: 8px; width: 100%; padding: 8px 12px; border: 1px solid var(--el-border-color-lighter); border-radius: 8px; background: var(--el-fill-color-lighter); cursor: pointer; text-align: left; }
.group-path { font-weight: 600; }
.doc-cards { margin-top: 8px; padding-left: 12px; }
/* two-column rows inside the create drawer keep the aspect
 comfortable (ratio iron law) instead of a single tall stack. */
.form-row { display: flex; gap: 12px; }
.form-row > .el-form-item { flex: 1; min-width: 0; }
.fault-divider { height: 1px; background: var(--el-border-color-lighter); margin: 4px 0 16px; }
/* V240: the live-consequence line at the top of the edit drawer — soft
   background emphasis (AD UI ruling: no harsh bars), warning hue because it
   speaks about traffic that is calling this rule right now. */
.live-impact { padding: 8px 12px; border-radius: 6px; margin-bottom: 12px; font-size: 12px; background: var(--el-color-warning-light-9); color: var(--el-text-color-primary); }
/* AI drafting surface — job cards, estimate line, tick cards. */
.ai-hint { font-size: 12px; color: var(--el-text-color-secondary); }
.ai-job-card { border: 1px solid var(--el-border-color-lighter); border-radius: 8px; padding: 12px 14px; margin-bottom: 12px; }
.ai-job-prompt { margin: 8px 0 4px; font-size: 12px; display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; word-break: break-all; }
.ai-job-actions { display: flex; gap: 10px; margin-top: 6px; }
.ai-estimate { font-size: 12px; color: var(--el-text-color-secondary); background: var(--el-fill-color-lighter); border-radius: 6px; padding: 6px 10px; margin-top: 6px; }
.ai-draft-card { border: 1px solid var(--el-border-color-lighter); border-radius: 8px; padding: 8px 12px; margin-bottom: 8px; }
.ai-draft-meta { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: 12px; color: var(--el-text-color-secondary); margin-top: 2px; }
.ai-selected-count { margin-right: 12px; font-size: 12px; color: var(--el-text-color-secondary); }
</style>
