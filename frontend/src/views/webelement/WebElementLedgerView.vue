<!-- Web element ledger page (WE batch S0 . Default view = page-folded
 roll-up (one row per canonical page key with lifecycle counts); expanding a
 page lists its elements with the ordered anchors behind the edit dialog.
 State machine and CAS checks live server-side (core webelement service) —
 this page mirrors, never re-implements. Reference counts stay a labelled
 placeholder until S1 wires the case-to-element relation (no fake numbers).
 Worst-scale caliber: folded pages keep the outer table per-page small; the
 inner table is scoped to one page group at a time. Filter state mirrors to
 the URL so refresh/back/share restore the same view (R22 F group). -->
<template>
  <div class="page-root web-element-ledger-page" data-test="web-element-ledger-page">
    <div class="page-card">
      <div class="filter-bar" data-test="ledger-filter-bar">
        <el-input
          v-model="keyword"
          class="cond-keyword"
          clearable
          :placeholder="t('webElement.searchPlaceholder')"
          data-test="ledger-keyword"
          @keyup.enter="applyFilters"
          @clear="applyFilters"
        />
        <el-select
          v-model="stateFilter"
          class="cond-state"
          clearable
          :placeholder="t('webElement.stateFilterPlaceholder')"
          data-test="ledger-state-filter"
          @change="applyFilters"
        >
          <el-option
            v-for="s in WEB_ELEMENT_STATES"
            :key="s"
            :label="stateLabel(s)"
            :value="s"
          />
        </el-select>
        <el-button type="primary" :icon="Search" :loading="loading" data-test="ledger-query-btn" @click="applyFilters">
          {{ t('webElement.query') }}
        </el-button>
        <div class="filter-bar-actions">
          <el-tooltip :content="t('components.ownerEcho.pickInHeader')" placement="bottom" :disabled="!!projectId">
            <span>
              <el-button data-test="ledger-create-btn" type="primary" :icon="Plus"
                :disabled="!projectId" @click="openCreate">
                {{ t('webElement.create') }}
              </el-button>
            </span>
          </el-tooltip>
          
          <el-tooltip :content="t('components.ownerEcho.pickInHeader')" placement="bottom" :disabled="!!projectId">
            <span>
              <el-button data-test="ledger-survey-btn" :icon="Compass" :disabled="!projectId"
                @click="openSurvey">
                {{ t('webElement.survey.button') }}
              </el-button>
            </span>
          </el-tooltip>
          <!-- : live badge on the same cursor channel the tail window
 follows — running until the terminal frame collapses it -->
          <el-tag v-if="surveyTailRunning" size="small" type="warning" effect="light"
            data-test="ledger-survey-live" class="ledger-survey-live">
            {{ t('webElement.survey.tailRunning') }}
          </el-tag>
          <el-tag v-if="siteSurveyLive" size="small" type="warning" effect="light"
            data-test="ledger-site-live" class="ledger-survey-live">
            {{ t('siteProfile.siteLive') }}
          </el-tag>
          
          <el-tooltip :content="t('components.ownerEcho.pickInHeader')" placement="bottom" :disabled="!!projectId">
            <span>
              <el-button data-test="ledger-census-ledger-btn" :icon="Tickets" :disabled="!projectId"
                :loading="censusPosting === 'ledger'" @click="runCensusPosting('ledger')">
                {{ t('webElement.census.ledgerBtn') }}
              </el-button>
            </span>
          </el-tooltip>
          <el-tooltip :content="t('components.ownerEcho.pickInHeader')" placement="bottom" :disabled="!!projectId">
            <span>
              <el-button data-test="ledger-census-script-btn" :icon="Switch" :disabled="!projectId"
                :loading="censusPosting === 'script'" @click="runCensusPosting('script')">
                {{ t('webElement.census.scriptBtn') }}
              </el-button>
            </span>
          </el-tooltip>
          <!-- Site profile drawer entry: the management door for whole-site
               survey profiles, kept in the shared trailing action slot -->
          <el-tooltip :content="t('components.ownerEcho.pickInHeader')" placement="bottom" :disabled="!!projectId">
            <span>
              <el-button data-test="ledger-profile-btn" :icon="Collection" :disabled="!projectId"
                @click="profileDrawerVisible = true">
                {{ t('siteProfile.entry') }}
              </el-button>
            </span>
          </el-tooltip>
          <el-button :icon="Refresh" :loading="loading" data-test="ledger-refresh-btn" @click="load">
            {{ t('webElement.refresh') }}
          </el-button>
          <!-- R25 mode declaration (scope-aware single control, TB group): no tabs
               and exactly one page-level table, so the settings popover equals the
               table scope; show/hide, order, pinning and density persist per user
               through the shared useColumnConfig piece. -->
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

      <el-alert type="info" show-icon :closable="false" class="usage-alert" data-test="ledger-usage-alert">
        <template #default>{{ t('webElement.usageHint') }}</template>
      </el-alert>

      <!-- WE-S7 health board: storm cards (one per storming page, the two
           lists side by side, never pooled), the self-heal rate card (rate
           and evidence count read together per §8.4) and the batch
           trial-run suggestion card. A failed read hides the board — it
           never fabricates readings. -->
      <div
        v-if="!loading && projectId && (stormCards.length > 0 || healSummary)"
        class="ledger-board"
        data-test="ledger-health-board"
      >
        <el-card
          v-for="card in stormCards"
          :key="card.pageKey"
          shadow="never"
          class="ledger-board-card"
          data-test="ledger-storm-card"
        >
          <template #header>
            <span class="board-card-title">{{ t('webElement.board.stormTitle') }} · {{ card.pageKey }}</span>
          </template>
          <div class="board-list-row">
            <span class="board-list-label">{{ t('webElement.board.stormStale') }}</span>
            <el-tag v-for="entry in card.stale" :key="`stale-${entry.id}`" type="danger" size="small" effect="plain">
              {{ entry.name }}
            </el-tag>
            <span v-if="card.stale.length === 0" class="board-empty">-</span>
          </div>
          <div class="board-list-row">
            <span class="board-list-label">{{ t('webElement.board.stormBreaking') }}</span>
            <el-tag v-for="entry in card.breaking" :key="`break-${entry.id}`" type="warning" size="small" effect="plain">
              {{ entry.name }}
            </el-tag>
            <span v-if="card.breaking.length === 0" class="board-empty">-</span>
          </div>
        </el-card>
        <el-card shadow="never" class="ledger-board-card" data-test="ledger-heal-rate-card">
          <template #header>
            <span class="board-card-title">{{ t('webElement.board.healTitle') }}</span>
          </template>
          <div class="board-metrics">
            <span class="board-list-label">{{ t('webElement.board.healRateLabel') }}</span>
            <strong data-test="ledger-heal-rate">{{ healSummary ? formatHealRate(healSummary.healRate) : '-' }}</strong>
            <span class="board-list-label">{{ t('webElement.board.healEvidenceLabel') }}</span>
            <strong data-test="ledger-heal-evidence-count">{{ healSummary ? healSummary.evidenceRowCount : '-' }}</strong>
          </div>
          <p class="board-hint">{{ t('webElement.board.healHint') }}</p>
        </el-card>
        <el-card
          v-if="trialRows.length > 0"
          shadow="never"
          class="ledger-board-card"
          data-test="ledger-trial-suggest-card"
        >
          <template #header>
            <span class="board-card-title">{{ t('webElement.board.trialTitle') }}</span>
          </template>
          <div class="board-trial-list">
            <span v-for="row in trialRows" :key="`trial-${row.pageKey}-${row.elementId}`" class="board-trial-item">
              {{ row.elementName }}
              <el-tag v-if="row.stale" size="small" type="danger" effect="plain">{{ t('webElement.board.stormStale') }}</el-tag>
              <el-tag v-if="row.breaking" size="small" type="warning" effect="plain">{{ t('webElement.healthAboutToBreak') }}</el-tag>
            </span>
          </div>
          <p class="board-hint">{{ t('webElement.board.trialHint') }}</p>
        </el-card>
      </div>

      <div v-if="!loading && !projectId" class="empty-box" data-test="ledger-empty-no-project">
        <EmptyState
          :title="t('webElement.noProjectTitle')"
          :hint="t('webElement.noProjectHint')"
        />
      </div>

      <TableSkeleton v-else-if="loading" :rows="5" />

      <div v-else-if="visibleGroups.length === 0" class="empty-box" data-test="ledger-empty">
        <EmptyState
          :title="t('webElement.emptyTitle')"
          :hint="t('webElement.emptyHint')"
          :action-label="projectId ? t('webElement.create') : ''"
          @action="openCreate"
        />
      </div>

      <el-table
        v-else
        border
        :data="visibleGroups"
        :size="colCfg.state.density"
        row-key="pageKey"
        class="pages-table"
        data-test="ledger-pages-table"
        @sort-change="onSortChange"
      >
        <el-table-column type="expand">
          <template #default="{ row }">
            
            <div v-if="pendingCandidatesOf(row.pageKey) > 0" class="candidate-head"
              data-test="ledger-candidate-head">
              <span class="candidate-head-text">
                {{ t('webElement.survey.batchLine', { n: pendingCandidatesOf(row.pageKey) }) }}
              </span>
              <el-button link type="success" :loading="approving"
                data-test="ledger-approve-all" @click="approveAll(row.pageKey)">
                {{ t('webElement.survey.batchConfirm') }}
              </el-button>
            </div>
            <el-table
              border
              :data="row.elements"
              class="elements-table"
              :data-test="`ledger-elements-${row.pageKey}`"
            >
              <el-table-column prop="name" :label="t('webElement.col.name')" min-width="160" />
              <el-table-column :label="t('webElement.col.state')" width="110">
                <template #default="{ row: el }">
                  <el-tag :type="stateTagType(el.state)" size="small">{{ stateLabel(el.state) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column :label="t('webElement.col.health')" width="130">
                <template #default="{ row: el }">
                  <span v-if="!healthByElement.get(el.id)" data-test="ledger-health-dash">-</span>
                  <template v-else>
                    <el-tag :type="healthTagType(healthByElement.get(el.id)!.badge)" size="small"
                      data-test="ledger-health-badge">
                      {{ healthText(healthByElement.get(el.id)) }}
                    </el-tag>
                    <el-tag v-if="healthByElement.get(el.id)!.aboutToBreak" type="danger" effect="plain"
                      size="small" data-test="ledger-health-breaking">
                      {{ t('webElement.healthAboutToBreak') }}
                    </el-tag>
                  </template>
                </template>
              </el-table-column>
              <el-table-column :label="t('webElement.col.confidence')" width="110">
                <template #default="{ row: el }">{{ el.confidence.toFixed(2) }}</template>
              </el-table-column>
              <el-table-column :label="t('webElement.col.hits')" width="110">
                <template #default="{ row: el }">{{ el.hitCount }} / {{ el.missCount }}</template>
              </el-table-column>
              <el-table-column :label="t('webElement.col.anchors')" width="110">
                <template #default="{ row: el }">{{ el.anchors.length }}</template>
              </el-table-column>
              <el-table-column :label="t('webElement.col.updated')" width="170">
                <template #default="{ row: el }"><TimeCell :value="el.updatedAt" /></template>
              </el-table-column>
              <el-table-column :label="t('webElement.col.actions')" width="320" fixed="right">
                <template #default="{ row: el }">
                  <el-button
                    link
                    type="primary"
                    :icon="EditPen"
                    data-test="ledger-edit-anchors"
                    @click="openAnchors(el)"
                  >
                    {{ t('webElement.action.editAnchors') }}
                  </el-button>
                  <!-- Door covers every origin the SERVER transition table
                       allows for ->CONFIRMED (candidate and stale): a stale
                       row that survived re-survey is re-confirmed through
                       this same button. Refusal stays server-side. -->
                  <el-button
                    v-if="el.state === 0 || el.state === 2"
                    link
                    type="success"
                    data-test="ledger-confirm"
                    @click="moveState(el, 1)"
                  >
                    {{ t('webElement.action.confirm') }}
                  </el-button>
                  <el-button
                    v-if="el.state === 1"
                    link
                    type="warning"
                    data-test="ledger-mark-stale"
                    @click="moveState(el, 2)"
                  >
                    {{ t('webElement.action.markStale') }}
                  </el-button>
                  <el-button
                    v-if="el.state !== 3"
                    link
                    type="danger"
                    data-test="ledger-retire"
                    @click="retire(el)"
                  >
                    {{ t('webElement.action.retire') }}
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </template>
        </el-table-column>
        <el-table-column
          v-for="col in colCfg.visibleColumns.value"
          :key="col.key"
          :prop="col.key"
          :fixed="col.fixed || false"
          :label="t(col.labelKey)"
          :width="col.width ?? undefined"
          :min-width="col.width ? undefined : LEDGER_FLEX_MIN[col.key] ?? 120"
          :sortable="SORTABLE_KEYS.includes(col.key) ? 'custom' : false"
          :show-overflow-tooltip="col.key === 'pageKey'"
        >
          <template #default="{ row }">
            <template v-if="col.key === 'total'">{{ row.total }}</template>
            <el-tag v-else-if="col.key === 'candidateCount'" size="small" type="info">{{ row.candidateCount }}</el-tag>
            <el-tag v-else-if="col.key === 'confirmedCount'" size="small" type="success">{{ row.confirmedCount }}</el-tag>
            <el-tag v-else-if="col.key === 'staleCount'" size="small" type="warning">{{ row.staleCount }}</el-tag>
            <el-tag v-else-if="col.key === 'retiredCount'" size="small">{{ row.retiredCount }}</el-tag>
            <template v-else>{{ row[col.key] }}</template>
          </template>
        </el-table-column>
      </el-table>

      <!-- segment c3 (R62): survey tail window — auto-opened on dispatch,
 constant height comes from the shared drawer (260px list cap),
 scroll-up pauses the follow, the terminal frame collapses it -->
      <TaskTailDrawer
        v-if="surveyTailOpen"
        :events="surveyTailEvents"
        :live="surveyTailRunning"
        :stalled="surveyTailStalled"
        :interrupted="surveyTailInterrupted"
        :heartbeat-seconds="tailHeartbeatSeconds"
        :first-frame-ms="tailFirstFrameMs"
        class="ledger-survey-tail"
        data-test="ledger-survey-tail"
      />

      <el-dialog
        v-model="createVisible"
        :title="t('webElement.createTitle')"
        class="tp-dialog--lg"
        data-test="ledger-create-dialog"
      >
        <ProjectOwnerEcho />
        <el-form label-position="top" class="create-form">
          <el-form-item :label="t('webElement.form.name')" required>
            <el-input v-model="createForm.name" maxlength="120" data-test="ledger-form-name" />
          </el-form-item>
          <el-form-item :label="t('webElement.form.pageUrl')" required>
            <el-input
              v-model="createForm.pageUrl"
              placeholder="https://example.com/orders/detail"
              data-test="ledger-form-url"
            />
            <div class="form-hint">{{ t('webElement.form.pageUrlHint') }}</div>
          </el-form-item>
          <el-form-item :label="t('webElement.form.anchors')">
            <WebElementAnchorEditor v-model="createForm.anchors" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button data-test="ledger-create-cancel" @click="createVisible = false">
            {{ t('webElement.cancel') }}
          </el-button>
          <el-button type="primary" :loading="saving" data-test="ledger-create-submit" @click="submitCreate">
            {{ t('webElement.create') }}
          </el-button>
        </template>
      </el-dialog>

      <el-dialog
        v-model="surveyVisible"
        :title="t('webElement.survey.title')"
        class="tp-dialog--lg"
        data-test="ledger-survey-dialog"
      >
        <ProjectOwnerEcho />
        <el-form label-position="top" class="create-form">
          <el-form-item :label="t('webElement.survey.url')" required>
            <el-input
              v-model="surveyForm.startUrl"
              :placeholder="t('webElement.survey.urlPlaceholder')"
              data-test="ledger-survey-url"
            />
            <div class="form-hint">{{ t('webElement.survey.urlHint') }}</div>
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button data-test="ledger-survey-cancel" @click="surveyVisible = false">
            {{ t('webElement.cancel') }}
          </el-button>
          <el-button type="primary" :loading="surveying" data-test="ledger-survey-submit" @click="submitSurvey">
            {{ t('webElement.survey.submit') }}
          </el-button>
        </template>
      </el-dialog>

      
      <el-dialog
        v-model="censusVisible"
        :title="censusKind === 'script' ? t('webElement.census.scriptTitle') : t('webElement.census.ledgerTitle')"
        class="tp-dialog--md"
        data-test="ledger-census-dialog"
      >
        <ProjectOwnerEcho />
        <el-descriptions :column="1" border size="small" data-test="ledger-census-counts">
          <el-descriptions-item :label="t('webElement.census.entries')">
            {{ censusResult?.entries ?? 0 }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('webElement.census.posted')">
            {{ censusResult?.posted ?? 0 }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('webElement.census.alreadyPending')">
            {{ censusResult?.alreadyPending ?? 0 }}
          </el-descriptions-item>
          <el-descriptions-item v-if="censusKind === 'ledger'" :label="t('webElement.census.alreadyLedgered')">
            {{ censusResult?.alreadyLedgered ?? 0 }}
          </el-descriptions-item>
          <template v-else>
            <el-descriptions-item :label="t('webElement.census.scriptsScanned')">
              {{ censusResult?.scriptsScanned ?? 0 }}
            </el-descriptions-item>
            <el-descriptions-item :label="t('webElement.census.skippedNotLedgered')">
              {{ censusResult?.skippedNotLedgered ?? 0 }}
            </el-descriptions-item>
          </template>
        </el-descriptions>
        <div class="form-hint">{{ censusKind === 'script' ? t('webElement.census.scriptGateHint') : t('webElement.census.gateHint') }}</div>
        <template #footer>
          <el-button type="primary" data-test="ledger-census-close" @click="censusVisible = false">
            {{ t('webElement.census.closeBtn') }}
          </el-button>
        </template>
      </el-dialog>

      <el-dialog
        v-model="anchorsVisible"
        :title="t('webElement.anchorsTitle')"
        :class="anchorsSparse ? 'tp-dialog--md' : 'tp-dialog--lg'"
        data-test="ledger-anchors-dialog"
      >
        <div class="anchors-body" :class="{ 'is-dense': !anchorsSparse }">
        <!-- MCP tail package 1: referencing-case list over the anchor ledger.
             Bounded page, no second index, no drawer-side counters; the server
             guards membership by the element's owning project. -->
        <div class="refs-list" data-test="ledger-refs-list">
          <div class="refs-list-title">{{ t('webElement.refs.title') }}</div>
          <el-table
            v-if="refs.rows.length"
            :data="refs.rows"
            size="small"
            border
            data-test="ledger-refs-table"
          >
            <el-table-column :label="t('webElement.refs.colCaseNo')" prop="caseNo" width="120" />
            <el-table-column :label="t('webElement.refs.colCaseTitle')" prop="caseTitle" min-width="180" />
            <el-table-column :label="t('webElement.refs.colVersion')" width="72">
              <template #default="{ row }">v{{ row.caseVersion }}</template>
            </el-table-column>
            <el-table-column :label="t('webElement.refs.colStep')" prop="stepNo" width="64" />
            <el-table-column :label="t('webElement.refs.colDesc')" prop="stepDescription" min-width="200" />
          </el-table>
          <div v-else class="refs-list-empty" data-test="ledger-refs-empty">
            {{ refs.failed ? t('webElement.refs.loadFailed') : t('webElement.refs.empty') }}
          </div>
          <el-pagination
            v-if="refs.total > refs.pageSize"
            class="pagination-bar"
            layout="prev, pager, next"
            size="small"
            :current-page="refs.page"
            :page-size="refs.pageSize"
            :total="refs.total"
            data-test="ledger-refs-pager"
            @current-change="loadReferencingCases"
          />
        </div>
        <div class="anchors-head">
          <el-input
            v-if="anchorTarget"
            v-model="renameDraft"
            maxlength="120"
            class="anchors-rename-input"
            data-test="ledger-rename-input"
            @keyup.enter="submitRename"
          />
          <el-button
            v-if="anchorTarget"
            size="small"
            :disabled="!renameDirty"
            :loading="renaming"
            data-test="ledger-rename-submit"
            @click="submitRename"
          >
            {{ t('webElement.action.rename') }}
          </el-button>
          <el-tag v-if="anchorTarget" :type="stateTagType(anchorTarget.state)" size="small">
            {{ stateLabel(anchorTarget.state) }}
          </el-tag>
        </div>
        <WebElementAnchorEditor v-if="anchorTarget" v-model="anchorsDraft" />
        
        <div class="heal-recent" data-test="ledger-heal-recent">
          <div class="heal-recent-title">{{ t('webElement.heal.recentTitle') }}</div>
          <el-table
            v-if="healRecent.rows.length"
            :data="healRecent.rows"
            size="small"
            border
            data-test="ledger-heal-recent-table"
          >
            <el-table-column :label="t('webElement.heal.colTime')" width="150">
              <template #default="{ row }">{{ fmtHealTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column :label="t('webElement.heal.colStep')" prop="stepNo" width="70" />
            <el-table-column :label="t('webElement.heal.colDiff')" min-width="220">
              <template #default="{ row }">
                <span class="heal-anchor-cell">{{ healAnchorDiffText(row) }}</span>
              </template>
            </el-table-column>
          </el-table>
          <div v-else class="heal-recent-empty" data-test="ledger-heal-recent-empty">
            {{ healRecent.failed ? t('webElement.heal.loadFailed') : t('webElement.heal.empty') }}
          </div>
          <el-pagination
            v-if="healRecent.total > healRecent.pageSize"
            class="pagination-bar"
            layout="prev, pager, next"
            size="small"
            :current-page="healRecent.page"
            :page-size="healRecent.pageSize"
            :total="healRecent.total"
            data-test="ledger-heal-recent-pager"
            @current-change="loadHealRecent"
          />
        </div>
        </div>
        <template #footer>
          <el-button data-test="ledger-anchors-cancel" @click="anchorsVisible = false">
            {{ t('webElement.cancel') }}
          </el-button>
          <el-button type="primary" :loading="saving" data-test="ledger-anchors-submit" @click="submitAnchors">
            {{ t('webElement.action.saveAnchors') }}
          </el-button>
        </template>
      </el-dialog>

      <!-- Site profile drawer: read + CRUD door for survey profiles; the
 project segment follows the header context through the prop -->
      <SiteProfileDrawer v-model="profileDrawerVisible" :project-id="projectId" :initial-run-id="surveyRunFromUrl" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useProjectPageContext } from '@/composables/useProjectPageContext'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Search, EditPen, Compass, Tickets, Switch, Collection } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import TimeCell from '@/components/TimeCell.vue'
import ProjectOwnerEcho from '@/components/ProjectOwnerEcho.vue'
import ColumnSettings from '@/components/ColumnSettings.vue'
import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'
import WebElementAnchorEditor from './WebElementAnchorEditor.vue'
import TaskTailDrawer from '@/components/TaskTailDrawer.vue'
import SiteProfileDrawer from './SiteProfileDrawer.vue'
import { listSiteSurveyRuns } from '@/api/siteProfiles'
import { useTestRunTail } from '@/composables/useTestRunTail'
import { loadTailWindowConfig, surveyTailCap, tailFirstFrameMs, tailHeartbeatSeconds, tailTerminalDwellMs } from '@/composables/tailWindowConfig'
import { bizErrorMsg } from '@/utils/bizError'
import {
  webElementPages,
  webElementHealth,
  webElementCreate,
  webElementRename,
  webElementUpdateAnchors,
  webElementChangeState,
  webElementHealEvidenceByElement,
  webElementReferencingCasesByElement,
  webElementSurvey,
  fetchSurveyProcessEvents,
  isSurveyTerminalRow,
  webElementCensusLedgerDrafts,
  webElementCensusScriptRefDrafts,
  webElementStorms,
  WEB_ELEMENT_STATES,
  type CensusPostingResult,
  type WebElementAnchor,
  type RevisionStormRow,
  type StepHealEvidenceRow,
  type ReferencingCaseRow,
  type WebElementHealthRow,
  type WebElementLedgerRow,
  type WebElementPageGroup,
} from '@/api/webElements'
import { formatHealRate, healAnchorDiffText } from '@/utils/webElementHealReport'
import {
  buildStormCards,
  buildTrialSuggestions,
  healthTagType,
  summarizeSliceHeal,
  type SliceHealSummary,
} from '@/utils/webElementLedgerBoard'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const { projectId, followTick } = useProjectPageContext()

const loading = ref(false)
const saving = ref(false)
const groups = ref<WebElementPageGroup[]>([])
/* Site profile drawer visibility (whole-site survey profiles management door) */
const profileDrawerVisible = ref(false)
/* R67 revisit door: ?surveyRun=<id> deep-links straight into one record's
   replay, and a running whole-site survey keeps a badge on this page.
   Closing the drawer strips the parameter — the URL stays the single source
   for what is open. */
const surveyRunFromUrl = computed<number | null>(() =>
  route.query.surveyRun === undefined || route.query.surveyRun === ''
    ? null : Number(route.query.surveyRun))
const siteSurveyLive = ref(false)
async function checkSiteSurveyLive() {
  if (!projectId.value) {
    siteSurveyLive.value = false
    return
  }
  try {
    const rs = await listSiteSurveyRuns(projectId.value, 20)
    siteSurveyLive.value = rs.some((r) => r.status === 'running')
  } catch {
    siteSurveyLive.value = false
  }
}
void checkSiteSurveyLive()
watch(surveyRunFromUrl, (v) => {
  if (v !== null) profileDrawerVisible.value = true
}, { immediate: true })
watch(profileDrawerVisible, (v) => {
  if (v) return
  if (route.query.surveyRun !== undefined) {
    const q = { ...route.query }
    delete q.surveyRun
    router.replace({ query: q })
  }
  void checkSiteSurveyLive()
})
/* WE-S7 health overlay keyed by elementId. A failed read leaves the map
   empty and the column shows the honest dash — never a fake green. */
const healthByElement = ref<Map<number, WebElementHealthRow>>(new Map())

/* WE-S7 board readings. Storm rows share the health read-door posture: a
   failed read hides the cards, the board never fabricates. The heal probe
   walks the bounded per-element evidence pages (§8.4 batch caliber — no
   project-level heal door exists yet; the cap keeps this auxiliary read
   off the critical path). */
const HEAL_PROBE_CAP = 8
const HEAL_PROBE_PAGE_SIZE = 20
const storms = ref<RevisionStormRow[]>([])
const healPages = ref<Map<number, StepHealEvidenceRow[]>>(new Map())
const nameById = computed(() => {
  const map = new Map<number, string>()
  for (const group of groups.value) {
    for (const el of group.elements) map.set(el.id, el.name)
  }
  return map
})
/* Two lists side by side per storming page, never pooled into one count. */
const stormCards = computed(() => buildStormCards(storms.value, nameById.value))
const trialRows = computed(() => buildTrialSuggestions(stormCards.value))
const healSummary = computed<SliceHealSummary | null>(() =>
  healPages.value.size > 0 ? summarizeSliceHeal(healPages.value) : null,
)

/* Badge → el-tag color now lives in utils/webElementLedgerBoard (single
   source, contract-pinned code table); the server owns the green/yellow/
   red judgement — this page only maps it. */

function healthText(row: WebElementHealthRow | undefined): string {
  return row ? t(`webElement.healthBadge.${row.badge}`) : '-'
}
const keyword = ref(String(route.query.kw ?? ''))
const stateFilter = ref<number | undefined>(
  route.query.state === undefined ? undefined : Number(route.query.state),
)

/** Page-level column configuration (shared piece, per-user persistence). */
const LEDGER_COLUMNS: ColumnDef[] = [
  { key: 'pageKey', labelKey: 'webElement.col.pageKey', width: null },
  { key: 'total', labelKey: 'webElement.col.total', width: 90 },
  { key: 'candidateCount', labelKey: 'webElement.col.candidate', width: 90 },
  { key: 'confirmedCount', labelKey: 'webElement.col.confirmed', width: 90 },
  { key: 'staleCount', labelKey: 'webElement.col.stale', width: 90 },
  { key: 'retiredCount', labelKey: 'webElement.col.retired', width: 90 },
]
/** Flexible columns fall back to min-width when no explicit width is set. */
const LEDGER_FLEX_MIN: Record<string, number> = { pageKey: 260 }
const colCfg = useColumnConfig('web-element-ledger', LEDGER_COLUMNS)
/** Popover rows (column state merged with labelKey for rendering). */
const settingsRows = computed(() =>
  colCfg.state.columns.map((c) => ({ ...c, labelKey: LEDGER_COLUMNS.find((d) => d.key === c.key)!.labelKey })),
)

/** Columns the header sort may drive. The roll-up endpoint returns the whole
 * project segment at once (no paging), so sorting the fully-loaded set locally
 * orders the entire window — same effect as a server-side ORDER BY, and it
 * never sorts "only the rows on screen". */
const SORTABLE_KEYS = ['pageKey', 'total', 'candidateCount', 'confirmedCount', 'staleCount', 'retiredCount']
const sortState = ref<{ prop: string; order: string | null }>({ prop: '', order: null })
function onSortChange({ prop, order }: { prop: string; order: string | null }): void {
  sortState.value = { prop: SORTABLE_KEYS.includes(prop) ? prop : '', order }
}

const STATE_LABELS: Record<number, string> = {
  0: 'webElement.state.candidate',
  1: 'webElement.state.confirmed',
  2: 'webElement.state.stale',
  3: 'webElement.state.retired',
}
function stateLabel(s: number) {
  return t(STATE_LABELS[s] ?? 'webElement.state.candidate')
}
function stateTagType(s: number) {
  return s === 1 ? 'success' : s === 2 ? 'warning' : s === 3 ? 'info' : 'info'
}

const visibleGroups = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  const st = stateFilter.value
  const filtered = groups.value
    .map((g) => ({
      ...g,
      elements: g.elements.filter(
        (el) =>
          (st === undefined || st === null || el.state === st) &&
          (kw === '' || el.name.toLowerCase().includes(kw) || g.pageKey.toLowerCase().includes(kw)),
      ),
    }))
    .filter((g) => g.elements.length > 0)
  const { prop, order } = sortState.value
  if (!prop || !order) return filtered
  const dir = order === 'ascending' ? 1 : -1
  return filtered.slice().sort((a, b) => {
    const va = a[prop as keyof typeof a]
    const vb = b[prop as keyof typeof b]
    if (typeof va === 'number' && typeof vb === 'number') return (va - vb) * dir
    return String(va ?? '').localeCompare(String(vb ?? '')) * dir
  })
})

function applyFilters() {
  router.replace({
    query: {
      ...route.query,
      kw: keyword.value.trim() || undefined,
      state: stateFilter.value === undefined ? undefined : String(stateFilter.value),
    },
  })
}

async function load() {
  if (!projectId.value) {
    groups.value = []
    return
  }
  loading.value = true
  try {
    groups.value = await webElementPages(projectId.value)
    // Read-door failure degrades the column to dashes, the ledger itself
    // stays usable (no error toast for an auxiliary overlay read).
    try {
      const healthRows = await webElementHealth(projectId.value)
      healthByElement.value = new Map(healthRows.map((h) => [h.elementId, h]))
    } catch {
      healthByElement.value = new Map()
    }
    // Board reads degrade independently: no storms + no heal pages = the
    // whole board row is simply absent (never a card full of fake zeros).
    try {
      storms.value = await webElementStorms(projectId.value)
    } catch {
      storms.value = []
    }
    const probeIds = groups.value
      .flatMap((g) => g.elements.map((e) => e.id))
      .slice(0, HEAL_PROBE_CAP)
    const pages = new Map<number, StepHealEvidenceRow[]>()
    await Promise.all(
      probeIds.map(async (id) => {
        try {
          const page = await webElementHealEvidenceByElement(id, 1, HEAL_PROBE_PAGE_SIZE)
          pages.set(id, Array.isArray(page.items) ? page.items : [])
        } catch {
          pages.set(id, [])
        }
      }),
    )
    healPages.value = pages
  } catch (err) {
    ElMessage.error(bizErrorMsg(err, t('webElement.msg.loadFailed')))
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch(followTick, load)

const createVisible = ref(false)
const createForm = reactive<{ name: string; pageUrl: string; anchors: WebElementAnchor[] }>({
  name: '',
  pageUrl: '',
  anchors: [],
})
function openCreate() {
  createForm.name = ''
  createForm.pageUrl = ''
  createForm.anchors = []
  createVisible.value = true
}
async function submitCreate() {
  if (!projectId.value) return
  if (!createForm.name.trim() || !createForm.pageUrl.trim()) {
    ElMessage.warning(t('webElement.form.required'))
    return
  }
  saving.value = true
  try {
    await webElementCreate({
      projectId: projectId.value,
      name: createForm.name.trim(),
      pageUrl: createForm.pageUrl.trim(),
      anchors: createForm.anchors,
    })
    ElMessage.success(t('webElement.msg.created'))
    createVisible.value = false
    await load()
  } catch (err) {
    ElMessage.error(bizErrorMsg(err, t('webElement.msg.saveFailed')))
  } finally {
    saving.value = false
  }
}

const anchorsVisible = ref(false)
const anchorTarget = ref<WebElementLedgerRow | null>(null)
const anchorsDraft = ref<WebElementAnchor[]>([])
// Rename door : the name in this dialog header is the
// editable truth — script references address elements by name, so fixing a
// mis-named row happens here instead of re-creating the element.
const renameDraft = ref('')
const renaming = ref(false)
const renameDirty = computed(() => {
  const target = anchorTarget.value
  const next = renameDraft.value.trim()
  return !!target && next.length > 0 && next !== target.name
})
function openAnchors(el: WebElementLedgerRow) {
  anchorTarget.value = el
  anchorsDraft.value = el.anchors.map((a) => ({ ...a }))
  renameDraft.value = el.name
  anchorsVisible.value = true
  void loadHealRecent(1)
  void loadReferencingCases(1)
}

// ---------- Referencing-case read (MCP tail package 1): bounded page over
// the anchor ledger, the drawer stores no counters of its own ----------
const refs = reactive<{
  rows: ReferencingCaseRow[]
  total: number
  page: number
  pageSize: number
  failed: boolean
}>({ rows: [], total: 0, page: 1, pageSize: 10, failed: false })
async function loadReferencingCases(page: number) {
  const target = anchorTarget.value
  if (!target) return
  try {
    const data = await webElementReferencingCasesByElement(target.id, page, refs.pageSize)
    refs.rows = data.items
    refs.total = data.total
    refs.page = data.page
    refs.failed = false
  } catch {
    // Read failure never blocks anchor editing: honest empty state with a
    // retry path (R63), never a fake "no references" answer.
    refs.rows = []
    refs.total = 0
    refs.failed = true
  }
}

const healRecent = reactive<{
  rows: StepHealEvidenceRow[]
  total: number
  page: number
  pageSize: number
  failed: boolean
}>({ rows: [], total: 0, page: 1, pageSize: 10, failed: false })
async function loadHealRecent(page: number) {
  const target = anchorTarget.value
  if (!target) return
  try {
    const data = await webElementHealEvidenceByElement(target.id, page, healRecent.pageSize)
    healRecent.rows = data.items
    healRecent.total = data.total
    healRecent.page = data.page
    healRecent.failed = false
  } catch {
    healRecent.rows = []
    healRecent.total = 0
    healRecent.failed = true
  }
}
function fmtHealTime(v?: string | null): string {
  return v ? v.replace('T', ' ').slice(0, 19) : '-'
}
// Shape guard (R group R1): the anchors dialog picks its width tier by content
// density. With both bounded sections empty the 1080 tier renders a flat wide
// strip (measured aspect 2.69 > 1.8), so the sparse face drops to the 720 tier
// (≈1.80, back in range) while the dense face keeps the large tier and pins a
// floor height instead of leaving dead width. Tiers stay theme-single-source.
const anchorsSparse = computed(() => refs.rows.length === 0 && healRecent.rows.length === 0)
async function submitAnchors() {
  const target = anchorTarget.value
  if (!target) return
  saving.value = true
  try {
    await webElementUpdateAnchors(target.id, anchorsDraft.value, target.revision)
    ElMessage.success(t('webElement.msg.anchorsSaved'))
    anchorsVisible.value = false
    await load()
  } catch (err) {
    ElMessage.error(bizErrorMsg(err, t('webElement.msg.saveFailed')))
  } finally {
    saving.value = false
  }
}

async function submitRename() {
  const target = anchorTarget.value
  const next = renameDraft.value.trim()
  if (!target) return
  if (!next) {
    ElMessage.warning(t('webElement.msg.renameBlank'))
    return
  }
  renaming.value = true
  try {
    const updated = await webElementRename(target.id, next, target.revision)
    anchorTarget.value = updated
    renameDraft.value = updated.name
    ElMessage.success(t('webElement.msg.renamed'))
    await load()
  } catch (err) {
    ElMessage.error(bizErrorMsg(err, t('webElement.msg.saveFailed')))
  } finally {
    renaming.value = false
  }
}

async function moveState(el: WebElementLedgerRow, targetState: number) {
  try {
    await webElementChangeState(el.id, targetState, el.revision)
    ElMessage.success(t('webElement.msg.stateMoved', { name: el.name }))
    await load()
  } catch (err) {
    ElMessage.error(bizErrorMsg(err, t('webElement.msg.stateFailed')))
  }
}

async function retire(el: WebElementLedgerRow) {
  try {
    await ElMessageBox.confirm(
      t('webElement.retireConfirm', { name: el.name }),
      t('webElement.action.retire'),
      { type: 'warning' },
    )
  } catch {
    return
  }
  await moveState(el, 3)
}

// segment c3 (R62): one cursor channel for the survey tail — the shared
// useTestRunTail base driven in asynchronous mode through the admin read
// door. Dispatch opens the tail window itself; the terminal frame collapses
// it and the ledger silently re-reads (no manual-refresh instruction).
// segment d adds the sliding-window doors: memory tail-cap trim (hot key
// element.survey.tail-cap) + heartbeat/interrupted signals for the drawer.
void loadTailWindowConfig()
const surveyTailBase = useTestRunTail(
  (runToken, cursor) => fetchSurveyProcessEvents(Number(runToken), cursor),
  {
    terminal: isSurveyTerminalRow, emptyGracePolls: 600, maxPolls: 2400, closeOnTerminal: true,
    terminalDwellMs: () => tailTerminalDwellMs.value,
    cap: () => surveyTailCap.value,
    heartbeatMs: () => tailHeartbeatSeconds.value * 1000,
  },
)
const {
  events: surveyTailEvents, running: surveyTailRunning,
  drawerOpen: surveyTailOpen, stalled: surveyTailStalled, interrupted: surveyTailInterrupted,
  openRun, beginPending, cancelPending,
} = surveyTailBase
const surveyVisible = ref(false)
const surveying = ref(false)
const surveyForm = reactive({ startUrl: '' })
function openSurvey() {
  surveyForm.startUrl = ''
  surveyVisible.value = true
}
async function submitSurvey() {
  if (!projectId.value) return
  const url = surveyForm.startUrl.trim()
  if (!url) {
    ElMessage.warning(t('webElement.survey.emptyUrl'))
    return
  }
  surveying.value = true
  // r4 fix (R62): open the tail window BEFORE the dispatch round trip.
  // The r4 red measured the first server frame at 3378ms — every millisecond
  // of that was user-visible nothing, because the drawer and the badge were
  // gated on the POST ack. Pending mode shows the badge plus the drawer's
  // honest waiting frame instantly (first-frame degrade text past the
  // budget); the acknowledged session id continues the same cursor stream.
  beginPending()
  surveyVisible.value = false
  try {
    const ack = await webElementSurvey({ projectId: projectId.value, startUrl: url })
    ElMessage.success(t('webElement.survey.dispatched', { id: ack.sessionId }))
    void openRun({ runToken: String(ack.sessionId) }).then(() => load())
  } catch (err) {
    cancelPending()
    ElMessage.error(bizErrorMsg(err, t('webElement.msg.saveFailed')))
  } finally {
    surveying.value = false
  }
}

type CensusKind = 'ledger' | 'script'
const censusPosting = ref<CensusKind | null>(null)
const censusVisible = ref(false)
const censusKind = ref<CensusKind>('ledger')
const censusResult = ref<CensusPostingResult | null>(null)
/** One synchronous counting round: the door answers the idempotency account,
 *  the page shows it verbatim — nothing is rewritten here, cards await the
 *  draft-card human gate like every other write path. */
async function runCensusPosting(kind: CensusKind) {
  if (!projectId.value || censusPosting.value) return
  censusPosting.value = kind
  try {
    const result = kind === 'ledger'
      ? await webElementCensusLedgerDrafts(projectId.value)
      : await webElementCensusScriptRefDrafts(projectId.value)
    censusKind.value = kind
    censusResult.value = result
    censusVisible.value = true
  } catch (err) {
    ElMessage.error(bizErrorMsg(err, t('webElement.census.failed')))
  } finally {
    censusPosting.value = null
  }
}

/** Unfiltered candidate count of one folded page (batch line must not shrink
 *  under the current state filter — the approve set is the whole page). */
function pendingCandidatesOf(pageKey: string): number {
  const group = groups.value.find((g) => g.pageKey === pageKey)
  return group ? group.elements.filter((el) => el.state === 0).length : 0
}

const approving = ref(false)
/** One-click approve for the whole page: each row rides the existing CAS
 *  state door (server-side transition table + element-ledger audit event per
 *  row) — no second write path, failures are counted, never swallowed. */
async function approveAll(pageKey: string) {
  const group = groups.value.find((g) => g.pageKey === pageKey)
  const targets = group ? group.elements.filter((el) => el.state === 0) : []
  if (targets.length === 0) {
    ElMessage.info(t('webElement.survey.batchNothing'))
    return
  }
  approving.value = true
  let ok = 0
  let fail = 0
  for (const el of targets) {
    try {
      await webElementChangeState(el.id, 1, el.revision)
      ok += 1
    } catch {
      fail += 1
    }
  }
  try {
    await load()
  } catch {
    // reload failure keeps the honest per-row counts already reported
  }
  ElMessage[fail > 0 ? 'warning' : 'success'](
    fail > 0
      ? t('webElement.survey.batchPartial', { ok, fail })
      : t('webElement.survey.batchDone', { ok }),
  )
  approving.value = false
}

void WEB_ELEMENT_STATES
</script>

<style scoped>
.web-element-ledger-page .usage-alert {
  margin-bottom: 12px;
}
.pages-table {
  margin-top: 4px;
}
.elements-table {
  margin: 0 12px;
}
.candidate-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 4px 12px 0;
  font-size: 13px;
}
.candidate-head-text {
  color: var(--el-text-color-secondary);
}
.empty-box {
  padding: 24px 0;
}
.create-form .form-hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}
.anchors-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 8px 0 12px;
  font-weight: 600;
}
.anchors-rename-input {
  max-width: 320px;
  font-weight: 400;
}
/* Dense face floor (R1): header+body+footer at this floor clears ~640px, so
   the 1080 width lands at aspect ≈1.7 inside the 1.2–1.8 target band. The
   sparse face must NOT take the floor (R3: short content never stretches into
   dead space) — that is why the gate rides the same anchorsSparse switch. */
.anchors-body.is-dense {
  min-height: 480px;
}
.refs-list {
  margin-bottom: 8px;

  .refs-list-title {
    font-size: 13px;
    font-weight: 600;
    margin-bottom: 6px;
  }

  .refs-list-empty {
    font-size: 12px;
    color: var(--el-text-color-secondary);
    padding: 6px 0;
  }
}
.heal-recent {
  margin-top: 12px;

  .heal-recent-title {
    font-size: 13px;
    font-weight: 600;
    margin-bottom: 6px;
  }

  .heal-recent-empty {
    font-size: 12px;
    color: var(--el-text-color-secondary);
    padding: 6px 0;
  }

  .heal-anchor-cell {
    font-family: var(--tp-font-mono, monospace);
    word-break: break-all;
  }
}
.ledger-board {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 12px;
}
.ledger-board-card {
  flex: 1 1 280px;
  max-width: 420px;
}
.board-card-title {
  font-size: 13px;
  font-weight: 600;
}
.board-list-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}
.board-list-label {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.board-empty {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.board-metrics {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px;
}
.board-trial-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.board-trial-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
}
.board-hint {
  margin: 8px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}
</style>
