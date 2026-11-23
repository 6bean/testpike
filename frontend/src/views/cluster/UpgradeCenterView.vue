<!-- tp-sort-exempt: release manifests accumulate slowly (tens of publishes per year across the six families) and the ledger renders the full registry with server id DESC order. -->
<template>
  <div class="page-root upgrade-center-page" data-test="upgrade-center-page">
    <div class="page-card">
      <div class="page-header">
        <div class="header-actions">
          <el-button :icon="Document" data-test="upgrade-changelog-btn" :disabled="!latest" @click="changelogVisible = true">
            {{ t('upgrade.btnChangelog') }}
          </el-button>
          <el-button :icon="Tickets" data-test="upgrade-guide-btn" :disabled="!latest" @click="openGuide">
            {{ t('upgrade.btnGuide') }}
          </el-button>
          <el-button :icon="FirstAidKit" :loading="healthLoading" data-test="upgrade-health-btn" @click="toggleHealth">
            {{ t('upgrade.btnHealth') }}
          </el-button>
          <el-button :icon="QuestionFilled" data-test="upgrade-help-btn" @click="openHelp('K', t('layout.leafHelpAnchor.upgradeCenter'))">
            {{ t('upgrade.help') }}
          </el-button>
        </div>
      </div>

      <!-- migration batch (R21 bands): refresh is a view-state action — moved out of
 the header band into a filter-bar trailing slot (page has no other
 screening controls, so the slot row carries it alone; anchor kept). -->
      <div class="filter-bar" data-test="upgrade-filter-bar">
        <div class="filter-bar-actions">
          <el-button :icon="Refresh" :loading="loading" data-test="upgrade-refresh" @click="load">
            {{ t('upgrade.refresh') }}
          </el-button>
          <!-- R25 mode 1: one scoped settings piece for the whole tab block;
               it follows activeTab (plans = configurable ledger, the rest are
               honestly disabled fixed views naming their reason). -->
          <ColumnSettings
            :columns="currentPane.settingsRows.value"
            :density="currentPane.state.density"
            :disabled="currentPane.settingsDisabled.value"
            :density-disabled="currentPane.densityDisabled.value"
            :disabled-tooltip="currentPane.disabledReason.value ? t(currentPane.disabledReason.value) : ''"
            @toggle="currentPane.toggle"
            @move="currentPane.move"
            @density="currentPane.setDensity"
            @reset="currentPane.reset"
            @pin="currentPane.toggleFixed"
          />
        </div>
      </div>

   
      <el-tabs v-model="activeTab" data-test="upgrade-tabs" @tab-change="onTabChange">
        <!-- tp-scope-exempt tp-pane-fixed-view: the manifest tab is banners, candidate
             cards and a four-column bounded health check; hiding any column breaks the
             diagnostic semantics, so the scoped control honestly disables here. -->
        <el-tab-pane :label="t('upgrade.tabManifest')" name="manifest">
          
          <div v-if="platformLoaded && platform" class="self-version-banner" data-test="upgrade-self-version-banner">
            <div class="banner-main">
              <span class="banner-label">{{ t('upgrade.selfVersionTitle') }}</span>
              <span class="banner-version" data-test="upgrade-self-running">{{ platform.runningVersion || t('upgrade.selfVersionUnknown') }}</span>
              <el-tag size="small" disable-transitions :type="selfVerdictTagType" data-test="upgrade-self-verdict">
                {{ t(`upgrade.selfVerdict.${platform.verdict}`) }}
              </el-tag>
            </div>
            <div class="banner-meta">
              <span data-test="upgrade-self-latest">
                {{ t('upgrade.selfVersionLatest', { v: platform.latestVersion || t('upgrade.selfVersionUnknown') }) }}
              </span>
              <span v-if="platform.buildTime" data-test="upgrade-self-build-time">
                {{ t('upgrade.selfVersionBuilt', { time: formatTime(platform.buildTime) }) }}
              </span>
              
              <span data-test="upgrade-self-frontend-version">
                {{ t('upgrade.selfFrontendVersion', { v: frontendVersion || t('upgrade.selfVersionUnknown') }) }}
              </span>
              <span v-if="frontendBuildTime" data-test="upgrade-self-frontend-build-time">
                {{ t('upgrade.selfVersionBuilt', { time: formatTime(frontendBuildTime) }) }}
              </span>
            </div>
          </div>

          
          <div v-if="loaded && !latest" class="empty-box" data-test="upgrade-empty-state">
            <EmptyState :title="t('upgrade.emptyTitle')" :hint="t('upgrade.emptyHint')" />
          </div>

          <div v-else-if="latest" class="version-banner" data-test="upgrade-version-banner">
            <div class="banner-main">
              <span class="banner-label">{{ t('upgrade.bannerTitle') }}</span>
              <span class="banner-version" data-test="upgrade-banner-version">{{ latest.version }}</span>
              <el-tag size="small" disable-transitions data-test="upgrade-banner-source">
                {{ latest.source === 'import' ? t('upgrade.sourceImport') : t('upgrade.sourceManual') }}
              </el-tag>
            </div>
            <div class="banner-meta">
              <span data-test="upgrade-banner-range">
                {{ t('upgrade.bannerRange', { range: rangeText }) }}
              </span>
              <span v-if="latest.registeredAt" data-test="upgrade-banner-time">
                {{ t('upgrade.bannerRegistered', { time: formatTime(latest.registeredAt) }) }}
              </span>
            </div>
            <el-alert
              class="banner-note"
              type="info"
              :closable="false"
              show-icon
              data-test="upgrade-banner-migration-note"
              :title="t('upgrade.migrationNote')"
            />
          </div>

          <!-- pending candidate cards (two-state model: detection lands in
 the candidate table, publishing stays a human authority act).
 Halo = faint background tint on the danger-weak token (AD caliber —
 no modal, no side bar); a confirmed card turns into the create-plan
 entry in place . -->
          <div v-for="c in candidates" :key="c.id" class="candidate-card candidate-halo"
            :data-test="`upgrade-candidate-${c.version}`">
            <div class="banner-main">
              <span class="banner-version">{{ c.version }}</span>
              <el-tag size="small" type="warning" disable-transitions data-test="upgrade-candidate-pending-tag">
                {{ t('upgrade.changelogPendingTag') }}
              </el-tag>
              <span class="banner-label">
                {{ t('upgrade.candidateFound', { v: c.version, source: candidateSourceText(c), time: formatTime(c.detectedAt) }) }}
              </span>
            </div>
            <p class="caliber-note" data-test="upgrade-candidate-note">{{ t('upgrade.candidateCardNote') }}</p>
            <div class="candidate-actions">
              <el-button size="small" :data-test="`upgrade-candidate-view-${c.id}`" @click="openConfirm(c)">
                {{ t('upgrade.candidateView') }}
              </el-button>
              <el-button v-if="canConfirmCandidate" size="small" type="primary"
                :data-test="`upgrade-candidate-confirm-${c.id}`" @click="openConfirm(c)">
                {{ t('upgrade.candidateConfirm') }}
              </el-button>
              <el-button v-if="canConfirmCandidate" size="small"
                :data-test="`upgrade-candidate-dismiss-${c.id}`" @click="dismissCandidate(c)">
                {{ t('upgrade.candidateDismiss') }}
              </el-button>
            </div>
          </div>
          <!-- Just-confirmed version: the card turns into the create-plan entry in place
 . -->
          <div v-for="v in justPublishedVersions" :key="v" class="candidate-card" :data-test="`upgrade-published-${v}`">
            <div class="banner-main">
              <span class="banner-version">{{ v }}</span>
              <span class="banner-label">{{ t('upgrade.confirmSuccess', { v }) }}</span>
              <el-button size="small" type="primary" :data-test="`upgrade-candidate-create-plan-${v}`" @click="startPlanFromVersion(v)">
                {{ t('upgrade.btnNewPlan') }}
              </el-button>
            </div>
          </div>

          
          <div v-if="healthVisible" class="health-panel" data-test="upgrade-health-panel">
            <h3 class="section-title">{{ t('upgrade.healthTitle') }}</h3>
            <el-alert
              v-if="!latest"
              type="warning"
              :closable="false"
              show-icon
              data-test="upgrade-health-manifest-missing"
              :title="t('upgrade.healthManifestMissing')"
            />
            <template v-else>
              <TableSkeleton v-if="healthLoading" :cols="4" />
              <el-table border v-else :data="compatRows" size="small" row-key="id" data-test="upgrade-health-table"
                :row-class-name="compatRowClass">
                <el-table-column :label="t('upgrade.colNode')" prop="name" min-width="160" />
                <el-table-column :label="t('upgrade.colVersion')" min-width="120">
                  <template #default="{ row }">{{ row.version || '—' }}</template>
                </el-table-column>
                <el-table-column :label="t('upgrade.colCompat')" width="180">
                  <template #default="{ row }">
                    <el-tag :type="row.compatible ? 'success' : 'danger'" size="small" disable-transitions
                      :data-test="`upgrade-compat-tag-${row.id}`">
                      {{ row.compatible ? t('upgrade.compatOk') : t('upgrade.compatBad') }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column :label="t('upgrade.colReason')" min-width="240">
                  <template #default="{ row }">{{ row.reason || '—' }}</template>
                </el-table-column>
                <template #empty>
                  <span data-test="upgrade-health-empty">{{ t('upgrade.emptyExecutors') }}</span>
                </template>
              </el-table>
            </template>
            <p class="caliber-note" data-test="upgrade-health-caliber">{{ t('upgrade.healthCaliber') }}</p>
          </div>
        </el-tab-pane>

        <el-tab-pane :label="t('upgrade.tabPlans')" name="plans">
          <div class="plan-toolbar">
            <el-steps class="plan-steps" :active="planStep" align-center data-test="upgrade-plan-steps">
              <el-step :title="t('upgrade.planStepScope')" />
              <el-step :title="t('upgrade.planStepPreview')" />
              <el-step :title="t('upgrade.planStepProgress')" />
            </el-steps>
            <el-button v-if="planStep === 2 || createdPlan" data-test="upgrade-plan-new" :disabled="!canRun"
              @click="resetPlanFlow">
              {{ t('upgrade.btnNewPlan') }}
            </el-button>
          </div>

          
          <div v-show="planStep === 0" class="plan-form" data-test="upgrade-plan-form">
            <el-form label-width="140px" :disabled="!canRun">
              <!-- el-radio-group renders a div, which is not a labelable element;
                   left to itself Element Plus auto-emits a <label for> pointing at
                   that div and Chromium flags it as a form-label association issue.
                   An empty `for` opts into the labelled-group mode: the caption
                   renders as a plain div and the item wrapper carries
                   role=group + aria-labelledby — the correct ARIA pattern. -->
              <el-form-item :label="t('upgrade.lbScope')" for="">
                <!-- id="" prevents the form label for= from targeting the non-labelable radiogroup div -->
                <el-radio-group id="" v-model="planForm.scope" data-test="upgrade-plan-scope" @change="onScopeChange">
                  <el-radio value="engine">{{ t('upgrade.scopeEngine') }}</el-radio>
                  <el-radio value="node">{{ t('upgrade.scopeNode') }}</el-radio>
                  <el-radio value="platform">{{ t('upgrade.scopePlatform') }}</el-radio>
                </el-radio-group>
              </el-form-item>
              <el-form-item v-if="planForm.scope === 'engine'" :label="t('upgrade.lbEngines')">
                <el-select v-model="planForm.engineCodes" multiple filterable data-test="upgrade-plan-engines"
                  :placeholder="t('upgrade.enginesHint')">
                  <el-option v-for="e in engineOptions" :key="e.code" :label="`${e.name || e.code}（${e.code}）`"
                    :value="e.code.toUpperCase()" />
                </el-select>
              </el-form-item>
              <el-form-item :label="t('upgrade.lbCanary')">
                <el-select v-model="planForm.canaryNodeIds" multiple clearable filterable data-test="upgrade-plan-canary"
                  :placeholder="t('upgrade.canaryHint')">
                  <el-option v-for="n in nodeOptions" :key="n.id" :label="n.name" :value="String(n.id)" />
                </el-select>
              </el-form-item>
              <el-form-item :label="t('upgrade.lbBatchSize')">
                <el-input-number v-model="planForm.batchSize" :min="1" data-test="upgrade-plan-batch" />
              </el-form-item>
              <el-form-item :label="t('upgrade.lbManifest')">
                <el-select v-model="planForm.manifestVersion" clearable filterable data-test="upgrade-plan-manifest"
                  :placeholder="t('upgrade.manifestHint')">
                  <el-option v-for="m in manifests" :key="m.id" :label="m.version" :value="m.version" />
                </el-select>
              </el-form-item>
              <el-form-item :label="t('upgrade.lbBaseline')">
                <el-input v-model="planForm.baselineRef" data-test="upgrade-plan-baseline"
                  :placeholder="t('upgrade.baselineHint')" />
              </el-form-item>
              <!-- Production declaration lives on the plan row (the server never guesses the
                   environment). Under the strict dual-review switch it is what turns on the
                   window + second-person requirements enforced by core at start. -->
              <el-form-item :label="t('upgrade.lbProduction')" for="">
                <el-checkbox v-model="planForm.productionScope" data-test="upgrade-plan-production">
                  {{ t('upgrade.productionCheck') }}
                </el-checkbox>
              </el-form-item>
              <el-form-item v-if="planForm.productionScope" :label="t('upgrade.lbWindow')">
                <el-input v-model="planForm.maintenanceWindow" data-test="upgrade-plan-window"
                  :placeholder="t('upgrade.maintenanceWindowHint')" />
              </el-form-item>
            </el-form>
            <div class="plan-form-actions">
              <el-button type="primary" :loading="impactLoading" data-test="upgrade-plan-to-preview"
                :disabled="!canRun" @click="toPreviewStep">
                {{ t('upgrade.btnPreviewImpact') }}
              </el-button>
              <!-- The dual-review reviewer must be able to reach the existing-plan list
                 without first creating a new plan; the wizard otherwise only lands on
                 step 3 after a start. Read-only display — action buttons inside the
                 list stay gated by canRun. -->
              <el-button data-test="upgrade-plan-open-list" @click="openPlanList">
                {{ t('upgrade.btnOpenList') }}
              </el-button>
            </div>
          </div>

          
          <div v-show="planStep === 1" class="plan-preview" data-test="upgrade-plan-preview">
            <template v-if="impact">
              <el-alert type="info" :closable="false" show-icon data-test="upgrade-impact-caliber"
                :title="t('upgrade.impactCaliber')" />
              <div class="impact-grid" data-test="upgrade-impact-grid">
                <span data-test="upgrade-impact-nodes">{{ t('upgrade.impactNodes', { n: impact.affectedNodes }) }}</span>
                <span data-test="upgrade-impact-steps">{{ t('upgrade.impactSteps', { n: impact.stepCount }) }}</span>
                <span data-test="upgrade-impact-waves">{{ t('upgrade.impactWaves', { n: impact.waves }) }}</span>
                <span data-test="upgrade-impact-minutes">{{ t('upgrade.impactMinutes', { n: impact.estimatedMinutes }) }}</span>
                <span data-test="upgrade-impact-active">{{ t('upgrade.impactActive', { n: impact.activePlans }) }}</span>
              </div>
              <!-- Artifact reachability pre-check: one HEAD probe per declared artifact of the pinned
                   manifest. Verdicts and wording are single-sourced from core (no second spectrum here). -->
              <div class="precheck-block" data-test="upgrade-precheck-block">
                <p class="caliber-note" data-test="upgrade-precheck-caliber">{{ t('upgrade.precheckCaliber') }}</p>
                <p v-if="!impact.precheck || impact.precheck.length === 0" class="precheck-empty"
                  data-test="upgrade-precheck-empty">{{ t('upgrade.precheckEmpty') }}</p>
                <div v-for="(row, i) in impact.precheck" :key="i" class="precheck-row"
                  :data-test="`upgrade-precheck-row-${row.componentFamily || i}`">
                  <el-tag :type="precheckTagType(row.verdict)" size="small" disable-transitions>
                    {{ precheckVerdictLabel(row.verdict) }}
                  </el-tag>
                  <span class="precheck-family">{{ row.componentFamily }}</span>
                  <span class="precheck-reason">{{ row.reason }}</span>
                </div>
              </div>
              <!-- Platform rehearsal: honest cost statement (fixed text, single-sourced
                   with the core wave semantics) + core-sourced tallies. No client-side
                   verdicts — counts are display aggregation of core rows only. -->
              <div v-if="planForm.scope === 'platform'" class="rehearsal-block" data-test="upgrade-rehearsal-block">
                <p class="caliber-note" data-test="upgrade-rehearsal-restart-note">{{ t('upgrade.rehearsalRestartNote') }}</p>
                <div class="impact-grid">
                  <span data-test="upgrade-rehearsal-out-of-range">{{
                    t('upgrade.rehearsalOutOfRange', { n: outOfRangeExecutorCount }) }}</span>
                  <span data-test="upgrade-rehearsal-running">{{
                    t('upgrade.rehearsalRunning', { n: runningTaskTotal }) }}</span>
                </div>
                <p class="caliber-note" data-test="upgrade-rehearsal-drain-note">{{ t('upgrade.rehearsalDrainNote') }}</p>
              </div>
            </template>
            <template v-if="createdPlan">
              <el-alert class="plan-created" type="success" :closable="false" show-icon
                data-test="upgrade-plan-created" :title="t('upgrade.planCreated')" />
              <!-- Platform wave manifest: buckets createdSteps by core waveSeq ascending
                   (family + machine count + pinned target per wave). Single-wave duration
                   is not returned by core — never computed here; total time is the
                   impact.estimatedMinutes straight through above. -->
              <div v-if="platformWaveGroups.length" class="wave-group-block" data-test="upgrade-wave-groups">
                <h4 class="section-subtitle">{{ t('upgrade.waveListTitle') }}</h4>
                <div v-for="w in platformWaveGroups" :key="w.seq" class="wave-row"
                  :data-test="`upgrade-wave-row-${w.seq}`">
                  <el-tag size="small" disable-transitions type="info">{{ t('upgrade.waveSeqLabel', { n: w.seq }) }}</el-tag>
                  <span class="wave-items">{{
                    w.items.map((i) => `${i.family}→${i.targetVersion || '—'}`).join('、') }}</span>
                </div>
              </div>
              <el-table :data="createdSteps" size="small" border data-test="upgrade-created-steps">
                <el-table-column :label="t('upgrade.colNode')" prop="nodeId" min-width="140" />
                <el-table-column :label="t('upgrade.colKind')" prop="kind" width="90" />
                <el-table-column :label="t('upgrade.colEngine')" min-width="110">
                  <template #default="{ row }">{{ row.engineCode || '—' }}</template>
                </el-table-column>
                <el-table-column :label="t('upgrade.colCur')" min-width="110">
                  <template #default="{ row }">{{ row.currentVersion || '—' }}</template>
                </el-table-column>
                <el-table-column :label="t('upgrade.colTarget')" min-width="110">
                  <template #default="{ row }">{{ row.targetVersion || '—' }}</template>
                </el-table-column>
                <el-table-column :label="t('upgrade.colStatus')" width="110">
                  <template #default="{ row }">{{ stepStatusLabel(row.status) }}</template>
                </el-table-column>
              </el-table>
            </template>
            <div class="plan-form-actions">
              <el-button data-test="upgrade-plan-back" :disabled="submitting" @click="backToScope">
                {{ t('upgrade.btnBack') }}
              </el-button>
              <el-button v-if="!createdPlan" type="primary" :loading="submitting" data-test="upgrade-plan-create"
                :disabled="!canRun" @click="confirmCreate">
                {{ t('upgrade.btnCreatePlan') }}
              </el-button>
              <el-button v-else type="primary" :loading="submitting" data-test="upgrade-plan-start" :disabled="!canRun"
                @click="confirmStart">
                {{ t('upgrade.btnConfirmRun') }}
              </el-button>
            </div>
          </div>

          
          <div v-show="planStep === 2" class="plan-progress" data-test="upgrade-plan-progress">
            <div class="section-head">
              <h3 class="section-title">{{ t('upgrade.planListTitle') }}</h3>
              <span v-if="planFollowing" class="follow-badge" data-test="upgrade-plan-following">{{
                t('upgrade.planFollowing') }}</span>
              <el-button :icon="Refresh" size="small" :loading="plansLoading" data-test="upgrade-plans-refresh"
                @click="loadPlans">
                {{ t('upgrade.refresh') }}
              </el-button>
            </div>
            <el-table :data="plans" border row-key="id" data-test="upgrade-plans-table"
              :size="plansPane.state.density" @row-click="openPlan">
              <!-- R25 mode 1: data columns render through the pane's visible set
                   (show/hide, order, width, density persist via the shared
                   useColumnConfig piece); the action column is locked outside
                   the loop. Cell bodies keep their per-column renderers. -->
              <el-table-column
                v-for="col in plansPane.visibleColumns.value"
                :key="col.key"
                :prop="col.key"
                :label="t(col.labelKey)"
                :fixed="col.fixed || false"
                :width="col.width ?? undefined"
                :min-width="col.width ? undefined : PLANS_FLEX_MIN[col.key]"
                :data-test="`upgrade-plans-col-${col.key}`"
              >
                <template #default="{ row }">
                  <template v-if="col.key === 'scope'">{{ scopeLabel(row.scope) }}</template>
                  <template v-else-if="col.key === 'targetEngineCodes'">{{ parseCodes(row.targetEngineCodes).join(', ') || '—' }}</template>
                  <template v-else-if="col.key === 'status'">
                    <el-tag :type="planStatusType(row.status)" size="small" disable-transitions
                      :data-test="`upgrade-plan-status-${row.id}`">
                      {{ planStatusLabel(row.status) }}
                    </el-tag>
                  </template>
                  <template v-else-if="col.key === 'manifestVersion'">{{ row.manifestVersion || '—' }}</template>
                  <template v-else-if="col.key === 'brakeReason'">
                    <el-tooltip v-if="row.brakeReason" :content="row.brakeReason" placement="top">
                      <span class="brake-text" data-test="upgrade-plan-brake">{{ row.brakeReason }}</span>
                    </el-tooltip>
                    <span v-else>—</span>
                  </template>
                  <template v-else-if="col.key === 'operator'">{{ row.operator || '—' }}</template>
                  <!-- Reviewer is read straight off the row (core persists it; the UI never
                       re-judges whether review is required — that gate lives in core). -->
                  <template v-else-if="col.key === 'reviewedBy'">{{ row.reviewedBy || '—' }}</template>
                  <template v-else-if="col.key === 'createdAt'">{{ formatTime(row.createdAt) }}</template>
                  <span v-else>{{ row[col.key] }}</span>
                </template>
              </el-table-column>
              <el-table-column :label="t('upgrade.colActions')" width="230" fixed="right">
                <template #default="{ row }">
                  <el-button link size="small" :data-test="`upgrade-plan-view-${row.id}`" @click.stop="openPlan(row)">
                    {{ t('upgrade.actView') }}
                  </el-button>
                  <!-- Dual-review consumer (decision E): offered exactly on an unreviewed
                       production-declared PREVIEW plan. Clicking calls POST /{id}/review;
                       core refuses self-review (reviewer = login identity, never a body
                       field) with a plain-language message shown straight through. -->
                  <el-button v-if="canRun && row.status === 'PREVIEW' && row.productionScope && !row.reviewedBy"
                    link type="primary" size="small" :loading="reviewingId === row.id"
                    :data-test="`upgrade-plan-review-${row.id}`" @click.stop="doReview(row)">
                    {{ t('upgrade.actReview') }}
                  </el-button>
                  <el-button v-if="canRun && row.status === 'RUNNING'" link type="warning" size="small"
                    :data-test="`upgrade-plan-pause-${row.id}`" @click.stop="doTransition(pauseUpgradePlan, row, t('upgrade.toastPaused'))">
                    {{ t('upgrade.actPause') }}
                  </el-button>
                  <el-button v-if="canRun && row.status === 'PAUSED'" link type="success" size="small"
                    :data-test="`upgrade-plan-resume-${row.id}`" @click.stop="doTransition(resumeUpgradePlan, row, t('upgrade.toastResumed'))">
                    {{ t('upgrade.actResume') }}
                  </el-button>
                  <el-button v-if="canRun && (row.status === 'RUNNING' || row.status === 'PAUSED')" link type="danger"
                    size="small" :data-test="`upgrade-plan-abort-${row.id}`" @click.stop="confirmAbort(row)">
                    {{ t('upgrade.actAbort') }}
                  </el-button>
                </template>
              </el-table-column>
              <template #empty>
                <span data-test="upgrade-plans-empty">{{ t('upgrade.emptyPlans') }}</span>
              </template>
            </el-table>

            <template v-if="selectedPlan">
              <h3 class="section-title plan-steps-title">
                {{ t('upgrade.planStepsTitle', { id: selectedPlan.id }) }}
              </h3>
              <el-table :data="planSteps" size="small" border row-key="id" data-test="upgrade-plan-steps-table">
                <el-table-column :label="t('upgrade.colNode')" prop="nodeId" min-width="140" />
                <el-table-column :label="t('upgrade.colKind')" prop="kind" width="90" />
                <el-table-column :label="t('upgrade.colEngine')" min-width="110">
                  <template #default="{ row }">{{ row.engineCode || '—' }}</template>
                </el-table-column>
                <el-table-column :label="t('upgrade.colPhase')" width="100">
                  <template #default="{ row }">{{ phaseLabel(row.phase) }}</template>
                </el-table-column>
                <el-table-column :label="t('upgrade.colCur')" min-width="110">
                  <template #default="{ row }">{{ row.currentVersion || '—' }}</template>
                </el-table-column>
                <el-table-column :label="t('upgrade.colTarget')" min-width="110">
                  <template #default="{ row }">{{ row.targetVersion || '—' }}</template>
                </el-table-column>
                <el-table-column :label="t('upgrade.colStatus')" width="110">
                  <template #default="{ row }">
                    <el-tag :type="stepStatusType(row.status)" size="small" disable-transitions>{{ stepStatusLabel(row.status) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column :label="t('upgrade.colCommand')" width="100">
                  <template #default="{ row }">{{ row.commandId ?? '—' }}</template>
                </el-table-column>
                <el-table-column :label="t('upgrade.colEvidence')" min-width="200">
                  <template #default="{ row }">
                    <el-tooltip v-if="evidenceText(row.evidence)" :content="evidenceText(row.evidence)" placement="top">
                      <span>{{ evidenceText(row.evidence) }}</span>
                    </el-tooltip>
                    <span v-else>—</span>
                  </template>
                </el-table-column>
              </el-table>
            </template>
          </div>
        </el-tab-pane>

        <!-- tp-scope-exempt tp-pane-fixed-view: the matrix is a bounded three-colour
             comparison ledger (node x component x support state); every column carries
             comparison semantics, so the scoped control honestly disables here. -->
        <el-tab-pane :label="t('upgrade.tabMatrix')" name="matrix">
          <div class="section-head">
            <h3 class="section-title">{{ t('upgrade.matrixTitle') }}</h3>
            <el-button :icon="Refresh" size="small" :loading="matrixLoading" data-test="upgrade-matrix-refresh"
              @click="loadMatrix">
              {{ t('upgrade.refresh') }}
            </el-button>
          </div>
          <TableSkeleton v-if="matrixLoading" :cols="8" />
          <el-table v-else :data="matrixRows" size="small" border row-key="id" data-test="upgrade-panorama-table">
            <el-table-column :label="t('upgrade.colNode')" prop="nodeId" min-width="140" />
            <el-table-column :label="t('upgrade.colFamily')" prop="componentFamily" min-width="140" />
            <el-table-column :label="t('upgrade.colCur')" min-width="110">
              <template #default="{ row }">{{ row.currentVersion || '—' }}</template>
            </el-table-column>
            <el-table-column :label="t('upgrade.colTarget')" min-width="110">
              <template #default="{ row }">{{ row.targetVersion || '—' }}</template>
            </el-table-column>
            <el-table-column :label="t('upgrade.colRequirement')" min-width="110">
              <template #default="{ row }">{{ row.requirement || '—' }}</template>
            </el-table-column>
            <el-table-column :label="t('upgrade.colState')" width="120">
              <template #default="{ row }">
                <el-tooltip :content="row.stateReason || '—'" placement="top">
                  <el-tag :type="stateType(row.supportState)" size="small" disable-transitions
                    :data-test="`upgrade-matrix-state-${row.id}`">
                    {{ stateLabel(row.supportState) }}
                  </el-tag>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column :label="t('upgrade.colSource')" width="110">
              <template #default="{ row }">{{ row.source || '—' }}</template>
            </el-table-column>
            <el-table-column :label="t('upgrade.colHomepage')" width="64" align="center">
              <template #default="{ row }">
                <ThirdPartyLinkCell :url="row.homepageUrl" :label="row.componentFamily" icon-only
                  :data-test="`upgrade-homepage-${row.componentFamily}`" />
              </template>
            </el-table-column>
            <el-table-column :label="t('upgrade.colReported')" width="170">
              <template #default="{ row }">{{ formatTime(row.reportedAt) }}</template>
            </el-table-column>
            <template #empty>
              <span data-test="upgrade-matrix-empty">{{ t('upgrade.emptyMatrix') }}</span>
            </template>
          </el-table>
          <p class="caliber-note" data-test="upgrade-matrix-caliber">{{ t('upgrade.matrixCaliber') }}</p>
        </el-tab-pane>

   
        <!-- tp-scope-exempt tp-pane-fixed-view: the insight tab shows fixed analysis
             tables (window slots / flaky links) whose columns are the analysis itself;
             so the scoped control honestly disables here. -->
        <el-tab-pane :label="t('upgrade.tabInsight')" name="insight">
          <div class="insight-block" data-test="upgrade-insight-advisor">
            <div class="section-head">
              <h3 class="section-title">{{ t('upgrade.advisorTitle') }}</h3>
              <span class="section-hint">{{ t('upgrade.advisorHint') }}</span>
            </div>
            <div class="insight-form">
              <ProjectSelect
                v-model="adviceProjectId"
                :placeholder="t('upgrade.advisorSelectProject')"
                width="220px"
                data-test="upgrade-advisor-project"
              />
              <el-select v-model="adviceVersion" filterable :placeholder="t('upgrade.advisorVersion')" style="width: 180px" data-test="upgrade-advisor-version">
                <el-option v-for="m in manifests" :key="m.id" :label="m.version" :value="m.version" />
              </el-select>
              <el-button
                v-if="canRunAdvisor"
                type="primary"
                :loading="adviceLoading"
                :disabled="!adviceProjectId || !adviceVersion"
                data-test="upgrade-advisor-run"
                @click="runAdvisor"
              >
                {{ t('upgrade.advisorRun') }}
              </el-button>
            </div>
            <el-alert v-if="adviceError" type="warning" :closable="false" show-icon :title="adviceError" data-test="upgrade-advisor-error" />
            <div v-if="advice" class="advice-body" data-test="upgrade-advice-result">
              <p class="advice-summary">{{ advice.summary }}</p>
              <ul v-if="advice.risks.length" data-test="upgrade-advice-risks">
                <li v-for="(r, i) in advice.risks" :key="i">{{ r }}</li>
              </ul>
              <p v-else class="release-none" data-test="upgrade-advice-no-risks">{{ t('upgrade.advisorNoRisks') }}</p>
              <h4 class="release-sub">{{ t('upgrade.advisorBreaking') }}</h4>
              <ul v-if="advice.breakingChanges.length" data-test="upgrade-advice-breaking">
                <li v-for="(c, i) in advice.breakingChanges" :key="i">{{ c }}</li>
              </ul>
              <p v-else class="release-none">—</p>
              <h4 class="release-sub">{{ t('upgrade.advisorProfile') }}</h4>
              <div class="profile-chips" data-test="upgrade-advisor-profile">
                <el-tag v-for="pk in PROFILE_KEYS" :key="pk" size="small" effect="plain" class="chip" disable-transitions>
                  {{ t(PROFILE_LABEL_KEYS[pk]) }}: {{ profileValue(pk) }}
                </el-tag>
              </div>
              <p class="advice-conclusion" data-test="upgrade-advice-conclusion">{{ advice.conclusion }}</p>
              <p class="caliber-note">{{ t('upgrade.advisorCaliber') }}</p>
            </div>
          </div>

          <div class="insight-block" data-test="upgrade-insight-window">
            <div class="section-head">
              <h3 class="section-title">{{ t('upgrade.windowTitle') }}</h3>
              <el-button :icon="Refresh" size="small" :loading="windowLoading" data-test="upgrade-window-refresh" @click="loadWindow">
                {{ t('upgrade.refresh') }}
              </el-button>
            </div>
            <p class="section-hint">{{ t('upgrade.windowHint') }}</p>
            <el-alert v-if="windowError" type="warning" :closable="false" show-icon :title="windowError" data-test="upgrade-window-error" />
            <el-table v-else :data="windowRows" size="small" border :row-key="(r: AppautoUpgradeWindow) => r.slotStart" data-test="upgrade-window-table">
              <el-table-column :label="t('upgrade.windowColSlot')" min-width="160">
                <template #default="{ row }">{{ formatTime(row.slotStart) }}</template>
              </el-table-column>
              <el-table-column :label="t('upgrade.windowColBusy')" prop="busyBatches" width="110" />
              <el-table-column :label="t('upgrade.windowColRecommend')" width="120">
                <template #default="{ row }">
                  <el-tag v-if="row.recommended" type="success" size="small" disable-transitions data-test="upgrade-window-recommended">
                    {{ t('upgrade.windowRecommended') }}
                  </el-tag>
                  <span v-else>—</span>
                </template>
              </el-table-column>
              <el-table-column :label="t('upgrade.windowColHint')" min-width="220" show-overflow-tooltip>
                <template #default="{ row }">{{ row.canaryHint || '—' }}</template>
              </el-table-column>
              <template #empty>
                <span data-test="upgrade-window-empty">{{ t('upgrade.windowEmpty') }}</span>
              </template>
            </el-table>
          </div>

          <div class="insight-block" data-test="upgrade-insight-flaky">
            <div class="section-head">
              <h3 class="section-title">{{ t('upgrade.flakyTitle') }}</h3>
              <el-button :icon="Refresh" size="small" :loading="flakyLoading" data-test="upgrade-flaky-refresh" @click="loadFlakyLinks">
                {{ t('upgrade.refresh') }}
              </el-button>
            </div>
            <p class="section-hint">{{ t('upgrade.flakyHint') }}</p>
            <el-table v-if="!flakyLoading" :data="flakyRows" size="small" border row-key="executorId" data-test="upgrade-flaky-table">
              <el-table-column :label="t('upgrade.flakyColNode')" prop="executorId" width="110" />
              <el-table-column :label="t('upgrade.flakyColVersion')" prop="targetVersion" width="120" />
              <el-table-column :label="t('upgrade.flakyColAt')" width="170">
                <template #default="{ row }">{{ formatTime(row.upgradedAt) }}</template>
              </el-table-column>
              <el-table-column :label="t('upgrade.flakyColBefore')" width="160">
                <template #default="{ row }">{{ pct(row.beforePassRate) }}（{{ row.beforeSamples }}）</template>
              </el-table-column>
              <el-table-column :label="t('upgrade.flakyColAfter')" width="160">
                <template #default="{ row }">{{ pct(row.afterPassRate) }}（{{ row.afterSamples }}）</template>
              </el-table-column>
              <el-table-column :label="t('upgrade.flakyColDrop')" width="100">
                <template #default="{ row }">{{ row.dropPoints.toFixed(2) }}</template>
              </el-table-column>
              <el-table-column :label="t('upgrade.flakyColAdvice')" min-width="220" show-overflow-tooltip prop="advice" />
              <template #empty>
                <span data-test="upgrade-flaky-empty">{{ t('upgrade.flakyEmpty') }}</span>
              </template>
            </el-table>
            <TableSkeleton v-else :cols="7" />
          </div>
        </el-tab-pane>
      </el-tabs>

      <!-- Changelog drawer: pending candidates first as a gray group ,
 then the full manifest list in version-descending order with
 plain-language breaking changes and the component matrix. -->
      <el-drawer v-model="changelogVisible" size="640px" :title="t('upgrade.drawerChangelog')"
        data-test="upgrade-changelog-drawer">
        <div v-if="candidates.length" class="changelog-pending-group" data-test="upgrade-changelog-pending-group">
          <h4 class="release-sub">{{ t('upgrade.changelogPendingGroup') }}</h4>
          <div v-for="c in candidates" :key="c.id" class="release-item changelog-pending-item"
            :data-test="`upgrade-changelog-candidate-${c.version}`">
            <div class="release-head">
              <span class="release-version">{{ c.version }}</span>
              <el-tag size="small" type="info" disable-transitions data-test="upgrade-changelog-pending-tag">
                {{ t('upgrade.changelogPendingTag') }}
              </el-tag>
              <span class="release-time">{{ formatTime(c.detectedAt) }}</span>
            </div>
            <p class="release-none">{{ candidateSourceText(c) }}</p>
          </div>
        </div>
        <div v-for="m in manifests" :key="m.id" class="release-item" :data-test="`upgrade-release-${m.version}`">
          <div class="release-head">
            <span class="release-version">{{ m.version }}</span>
            <span class="release-time">{{ formatTime(m.registeredAt) }}</span>
          </div>
          <div class="release-body">
            <h4 class="release-sub">{{ t('upgrade.colBreaking') }}</h4>
            <ul v-if="parseArray(m.breakingChanges).length" data-test="upgrade-breaking-list">
              <li v-for="(c, i) in parseArray(m.breakingChanges)" :key="i">{{ c }}</li>
            </ul>
            <p v-else class="release-none">{{ t('upgrade.noBreaking') }}</p>
            <h4 class="release-sub">{{ t('upgrade.colMatrix') }}</h4>
            <el-table border v-if="parseMatrix(m.componentMatrix).length" :data="parseMatrix(m.componentMatrix)" size="small"
              data-test="upgrade-matrix-table">
              <el-table-column :label="t('upgrade.matrixComponent')" prop="component" min-width="140" />
              <el-table-column :label="t('upgrade.matrixSupport')" prop="support" min-width="120" />
              <el-table-column :label="t('upgrade.matrixPinned')" min-width="100">
                <template #default="{ row }">{{ row.pinned || '—' }}</template>
              </el-table-column>
            </el-table>
            <p v-else class="release-none">—</p>
          </div>
        </div>
      </el-drawer>

      
      <el-drawer v-model="guideVisible" size="640px" :title="t('upgrade.drawerGuide')" data-test="upgrade-guide-drawer">
        <template v-if="latest">
          <h3 class="guide-title" data-test="upgrade-guide-version">
            {{ t('upgrade.guideTitle', { v: latest.version }) }}
          </h3>
          <el-alert type="warning" :closable="false" show-icon data-test="upgrade-guide-migration-note"
            :title="t('upgrade.guideMigrationNote')" />
          <h4 class="release-sub">{{ t('upgrade.suggestedPath') }}</h4>
          <p data-test="upgrade-guide-path">{{ latest.suggestedPath || t('upgrade.noPath') }}</p>
          <h4 class="release-sub">{{ t('upgrade.colBreaking') }}</h4>
          <ol data-test="upgrade-guide-breaking">
            <li v-for="(c, i) in parseArray(latest.breakingChanges)" :key="i">{{ c }}</li>
            <li v-if="!parseArray(latest.breakingChanges).length" class="release-none">{{ t('upgrade.noBreaking') }}</li>
          </ol>
          <h4 class="release-sub">{{ t('upgrade.colCompat') }}</h4>
          <p>{{ t('upgrade.bannerRange', { range: rangeText }) }}</p>
          <div class="guide-actions">
            <el-button :icon="Download" data-test="upgrade-guide-export" @click="exportGuide">
              {{ t('upgrade.guideExport') }}
            </el-button>
            <el-button :icon="Printer" data-test="upgrade-guide-print" @click="printGuide">
              {{ t('upgrade.guidePrint') }}
            </el-button>
          </div>
        </template>
      </el-drawer>

      <!-- confirm-release drawer: prefilled from the candidate payload at a
 single point (blanks detection never guesses stay highlighted for a
 human, but are not submit-blocked — core register tolerates empty
 arrays). Impact preview calls the compat-diff-with endpoint and only
 counts core-scored rows; zero local re-scoring (red line 6). A
 skip-version without a path shows a yellow hint, never a hard block. -->
      <el-drawer v-model="confirmVisible" size="640px" :title="t('upgrade.drawerConfirmTitle', { v: confirmTarget?.version || '' })"
        data-test="upgrade-confirm-drawer">
        <template v-if="confirmTarget">
          <el-descriptions :column="1" border size="small" data-test="upgrade-confirm-detected">
            <el-descriptions-item :label="t('upgrade.confirmReleaseName')">
              {{ confirmPayload.release_name || '—' }}
            </el-descriptions-item>
            <el-descriptions-item :label="t('upgrade.confirmDetectedRepo')">
              {{ confirmPayload.detected_repo || '—' }}
            </el-descriptions-item>
            <el-descriptions-item :label="t('upgrade.confirmPublishedAt')">
              {{ confirmPayload.published_at ? formatTime(confirmPayload.published_at) : '—' }}
            </el-descriptions-item>
          </el-descriptions>
          <el-alert v-if="crossVersionHint" class="confirm-cross-warn" type="warning" :closable="false" show-icon
            data-test="upgrade-confirm-cross-warn" :title="crossVersionHint" />
          <el-form class="confirm-form" label-position="top">
            <el-form-item :label="t('upgrade.confirmVersionLabel')">
              <el-input :model-value="confirmTarget.version" readonly data-test="upgrade-confirm-version" />
            </el-form-item>
            <el-form-item :label="t('upgrade.confirmMatrixLabel')">
              <el-input v-model="confirmForm.componentMatrixJson" type="textarea" :rows="3"
                :class="{ 'confirm-field-blank': !confirmForm.componentMatrixJson }"
                data-test="upgrade-confirm-matrix" />
            </el-form-item>
            <el-form-item :label="t('upgrade.confirmBreakingLabel')">
              <el-input v-model="confirmForm.breakingChangesJson" type="textarea" :rows="2"
                data-test="upgrade-confirm-breaking" />
            </el-form-item>
            <el-form-item :label="t('upgrade.confirmMinLabel')">
              <el-input v-model="confirmForm.executorVersionMin"
                :class="{ 'confirm-field-blank': !confirmForm.executorVersionMin }"
                data-test="upgrade-confirm-min" />
            </el-form-item>
            <el-form-item :label="t('upgrade.confirmMaxLabel')">
              <el-input v-model="confirmForm.executorVersionMax"
                :class="{ 'confirm-field-blank': !confirmForm.executorVersionMax }"
                data-test="upgrade-confirm-max" />
            </el-form-item>
            <el-form-item :label="t('upgrade.confirmPathLabel')">
              <el-input v-model="confirmForm.suggestedPath" type="textarea" :rows="2"
                :class="{ 'confirm-field-blank': !confirmForm.suggestedPath }"
                data-test="upgrade-confirm-path" />
            </el-form-item>
            <el-form-item :label="t('upgrade.confirmSourceLabel')">
              <el-input model-value="import" readonly data-test="upgrade-confirm-source" />
            </el-form-item>
            <p v-if="confirmHasBlanks" class="caliber-note confirm-blank-hint" data-test="upgrade-confirm-blank-hint">
              {{ t('upgrade.confirmBlankRequired') }}
            </p>
          </el-form>
          <div class="confirm-impact">
            <el-button size="small" :loading="impactPreviewLoading" data-test="upgrade-confirm-impact-btn"
              @click="runImpactPreview">
              {{ t('upgrade.confirmImpactRun') }}
            </el-button>
            <p v-if="impactPreviewText" class="caliber-note" data-test="upgrade-confirm-impact-result">
              {{ impactPreviewText }}
            </p>
          </div>
          <div class="guide-actions">
            <el-button type="primary" :loading="confirming" data-test="upgrade-confirm-submit" @click="submitConfirm">
              {{ t('upgrade.confirmSubmit') }}
            </el-button>
          </div>
        </template>
      </el-drawer>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Document, Download, Printer, QuestionFilled, Refresh, FirstAidKit, Tickets } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import ProjectSelect from '@/components/ProjectSelect.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import ThirdPartyLinkCell from '@/components/ThirdPartyLinkCell.vue'
import ColumnSettings from '@/components/ColumnSettings.vue'
import { openHelp } from '@/composables/useHelpDrawer'
import { useTabScopedSettings } from '@/composables/tabScopedColumnSettings'
import { useListAutoFollow } from '@/composables/useListAutoFollow'
import { listAutoFollowMs } from '@/composables/tailWindowConfig'
import type { ColumnDef } from '@/composables/useColumnConfig'
import { useAuthStore } from '@/stores/auth'
import { bizErrorMsg } from '@/utils/bizError'
import { supportStateLabel, verdictTagType } from '@/utils/verdict'
import { listAppautoEngines, type AppautoEngine } from '@/api/appauto'
import {
  listUpgradeFlakyLinks,
  listUpgradeWindowSlots,
  runUpgradeAdvice,
  type AppautoUpgradeAdvice,
  type AppautoUpgradeFlakyLink,
  type AppautoUpgradeWindow,
} from '@/api/appautoSupport'
import { listExecutors, type ExecutorInfo } from '@/api/executor'
import {
  abortUpgradePlan,
  confirmReleaseCandidate,
  createUpgradePlan,
  dismissReleaseCandidate,
  getCompatDiff,
  getCompatDiffWith,
  getLatestManifest,
  getPlatformVersion,
  listClusterComponents,
  listReleaseCandidates,
  listReleaseManifests,
  listUpgradePlans,
  listUpgradePlanSteps,
  parseCandidatePayload,
  pauseUpgradePlan,
  previewUpgradeImpact,
  reviewUpgradePlan,
  resumeUpgradePlan,
  startUpgradePlan,
  type CandidatePayload,
  type ClusterComponentRow,
  type ExecutorCompatRow,
  type PlatformVersionInfo,
  type ReleaseCandidateItem,
  type ReleaseManifestItem,
  type UpgradePlanImpact,
  type UpgradePlanRow,
  type UpgradePlanStepRow,
} from '@/api/cluster'

const { t } = useI18n()
const auth = useAuthStore()


interface MatrixRow {
  component: string
  support: string
  pinned: string
}

const activeTab = ref<'manifest' | 'plans' | 'matrix' | 'insight'>('manifest')

// R25 mode 1 (TB group, scope-aware single piece): the page-level column
// settings/density control outfits to whichever tab is active instead of
// silently acting on one fixed table. The plans ledger (upgrade orders) is
// the configurable body; manifest/matrix/insight are bounded comparison and
// analysis surfaces declared as honest fixed views (disabled + named reason).
// Each pane owns its own viewKey — no shared storage key across tables.
const PLANS_COLUMNS: ColumnDef[] = [
  { key: 'id', labelKey: 'upgrade.colPlanId', width: 70 },
  { key: 'scope', labelKey: 'upgrade.colScope', width: 120 },
  { key: 'targetEngineCodes', labelKey: 'upgrade.colTargets', width: null },
  { key: 'status', labelKey: 'upgrade.colStatus', width: 110 },
  { key: 'manifestVersion', labelKey: 'upgrade.colManifest', width: 110 },
  { key: 'brakeReason', labelKey: 'upgrade.colBrake', width: null },
  { key: 'operator', labelKey: 'upgrade.colOperator', width: 110 },
  { key: 'reviewedBy', labelKey: 'upgrade.colReviewed', width: 120 },
  { key: 'createdAt', labelKey: 'upgrade.colCreatedAt', width: 170 },
]
/** Elastic columns without a fixed width (min-width floor, px). */
const PLANS_FLEX_MIN: Record<string, number> = { targetEngineCodes: 150, brakeReason: 180 }
const settingsHost = useTabScopedSettings(activeTab)
const plansPane = settingsHost.settingsFor('plans', 'upgradePlans', PLANS_COLUMNS)
settingsHost.settingsFor('manifest', 'upgradeManifest', null, { disabledReasonKey: 'upgrade.manifestFixedViewHint' })
settingsHost.settingsFor('matrix', 'upgradeMatrix', null, { disabledReasonKey: 'upgrade.matrixFixedViewHint' })
settingsHost.settingsFor('insight', 'upgradeInsight', null, { disabledReasonKey: 'upgrade.insightFixedViewHint' })
const currentPane = settingsHost.currentPane
const canRun = computed(() => auth.hasPerm('perm:cluster:upgrade:run'))
const canRunAdvisor = computed(() => auth.hasPerm('perm:appauto:advisor:run'))

const loading = ref(false)
const loaded = ref(false)
const latest = ref<ReleaseManifestItem | null>(null)
const manifests = ref<ReleaseManifestItem[]>([])
const platform = ref<PlatformVersionInfo | null>(null)
const platformLoaded = ref(false)
const selfVerdictTagType = computed(() => {
  switch (platform.value?.verdict) {
    case 'UP_TO_DATE': return 'success'
    case 'BEHIND': return 'warning'
    case 'RUNNING_AHEAD_UNREGISTERED': return 'danger'
    default: return 'info'
  }
})
const healthVisible = ref(false)
const healthLoading = ref(false)
const compatRows = ref<ExecutorCompatRow[]>([])
const changelogVisible = ref(false)
const guideVisible = ref(false)

// ── candidate confirmation surface (two-state model: detection lands in
// the candidate table, publishing stays a human authority act) ──
// Pending candidates below the latest banner; empty list = page renders
// element-for-element identical to before (progressive enhancement).
const candidates = ref<ReleaseCandidateItem[]>([])
// Versions confirmed during this session: the pending card turns into the
// create-plan entry in place .
const justPublishedVersions = ref<string[]>([])
const canConfirmCandidate = computed(() => auth.hasPerm('perm:cluster:release:candidate:confirm'))
const confirmVisible = ref(false)
const confirmTarget = ref<ReleaseCandidateItem | null>(null)
const confirmPayload = ref<CandidatePayload>(parseCandidatePayload(null))
const confirming = ref(false)
// Drawer form mirrors the admin ReleaseRegisterRequest seven-field shape;
// version is fixed to the candidate version (core hard-rejects mismatch).
const confirmForm = reactive({
  componentMatrixJson: '',
  breakingChangesJson: '',
  executorVersionMin: '',
  executorVersionMax: '',
  suggestedPath: '',
})
const impactPreviewLoading = ref(false)
const impactPreviewText = ref('')
/** Blank authoritative fields get the highlight (detection never guesses — a
 * human must see what is still open; core register tolerates empty arrays so
 * this is a prompt, not a submit gate). */
const confirmHasBlanks = computed(() =>
  !confirmForm.componentMatrixJson.trim() || !confirmForm.executorVersionMin.trim()
  || !confirmForm.executorVersionMax.trim() || !confirmForm.suggestedPath.trim())
/** Skip-version hint (design §6.2: yellow, never a hard block). Major-segment
 * extraction here is hint-surface only — the authoritative compatibility/
 * sequence spectrum stays core-side single source (red line 6). */
const crossVersionHint = computed(() => {
  const c = confirmTarget.value
  if (!c || !latest.value || confirmForm.suggestedPath.trim()) return ''
  const major = (v: string) => Number.parseInt(v.replace(/^v/i, ''), 10)
  const cm = major(c.version)
  const lm = major(latest.value.version)
  if (!Number.isFinite(cm) || !Number.isFinite(lm) || cm <= lm) return ''
  return t('upgrade.confirmCrossWarn', { v: c.version, latest: latest.value.version })
})

function candidateSourceText(c: ReleaseCandidateItem): string {
  switch (c.detectedSource) {
    case 'poll': return t('upgrade.candidateSourcePoll')
    case 'webhook': return t('upgrade.candidateSourceWebhook')
    default: return t('upgrade.candidateSourceImport')
  }
}

function openConfirm(c: ReleaseCandidateItem) {
  confirmTarget.value = c
  confirmPayload.value = parseCandidatePayload(c.payloadJson)
  confirmForm.componentMatrixJson = ''
  confirmForm.breakingChangesJson = confirmPayload.value.release_notes_md
    ? JSON.stringify([confirmPayload.value.release_notes_md.slice(0, 500)]) : ''
  confirmForm.executorVersionMin = ''
  confirmForm.executorVersionMax = ''
  confirmForm.suggestedPath = ''
  impactPreviewText.value = ''
  confirmVisible.value = true
}

/** Impact pre-check via the existing compat-diff-with endpoint (core scores
 * every row; the page only counts out-of-range reds from the response — zero
 * local re-scoring). */
async function runImpactPreview() {
  impactPreviewLoading.value = true
  impactPreviewText.value = ''
  try {
    const rows = await getCompatDiffWith(
      confirmForm.executorVersionMin.trim() || null,
      confirmForm.executorVersionMax.trim() || null)
    const judged = rows.filter((r) => r.compatible !== null)
    if (!judged.length) {
      impactPreviewText.value = t('upgrade.confirmImpactEmpty')
    } else {
      const out = judged.filter((r) => r.compatible === false).length
      impactPreviewText.value = out > 0
        ? t('upgrade.confirmImpactOut', { n: out })
        : t('upgrade.confirmImpactOk')
    }
  } catch (e: unknown) {
    impactPreviewText.value = bizMessage(e) || t('upgrade.confirmImpactFailed')
  } finally {
    impactPreviewLoading.value = false
  }
}

async function submitConfirm() {
  const c = confirmTarget.value
  if (!c || confirming.value) return
  confirming.value = true
  try {
    await confirmReleaseCandidate(c.id, {
      version: c.version,
      componentMatrixJson: confirmForm.componentMatrixJson.trim() || null,
      breakingChangesJson: confirmForm.breakingChangesJson.trim() || null,
      executorVersionMin: confirmForm.executorVersionMin.trim() || null,
      executorVersionMax: confirmForm.executorVersionMax.trim() || null,
      suggestedPath: confirmForm.suggestedPath.trim() || null,
      source: 'import',
    })
    ElMessage.success(t('upgrade.confirmSuccess', { v: c.version }))
    confirmVisible.value = false
    confirmTarget.value = null
    if (!justPublishedVersions.value.includes(c.version)) justPublishedVersions.value.push(c.version)
    // Converge banner/health/cards onto the new authoritative manifest.
    await load()
  } catch (e: unknown) {
    ElMessage.error(bizMessage(e))
  } finally {
    confirming.value = false
  }
}

/** Dismiss keeps a human-worded reason (core hard-rejects blank; the prompt
 * collects it, the blank guard here only saves a pointless round-trip). */
async function dismissCandidate(c: ReleaseCandidateItem) {
  try {
    const res = await ElMessageBox.prompt(
      t('upgrade.dismissPromptHint'),
      t('upgrade.dismissPromptTitle', { v: c.version }),
      { inputType: 'textarea', confirmButtonText: t('upgrade.candidateDismiss'), cancelButtonText: t('common.cancel') })
    const reason = (res.value || '').trim()
    if (!reason) {
      ElMessage.warning(t('upgrade.dismissReasonBlank'))
      return
    }
    await dismissReleaseCandidate(c.id, reason)
    ElMessage.success(t('upgrade.dismissSuccess', { v: c.version }))
    await loadCandidates()
  } catch (e: unknown) {
    // ElMessageBox cancel arrives as 'cancel'/'close' strings — silent by design.
    if (e === 'cancel' || e === 'close') return
    ElMessage.error(bizMessage(e))
  }
}

/** consumption slot: carry the confirmed version into the plan wizard.
 * The flow resets first (a previous plan left at step 2/3 must never hijack
 * this entry), then the released version presets the manifest selector. */
function startPlanFromVersion(version: string) {
  resetPlanFlow()
  planForm.manifestVersion = version
  activeTab.value = 'plans'
  void ensurePlanDeps()
}

/** Candidate pull is independent of the manifest pull: a failure here must
 * not take down the banner area (same non-collateral posture as ). */
async function loadCandidates() {
  try {
    candidates.value = await listReleaseCandidates()
  } catch {
    candidates.value = []
  }
}

const planStep = ref(0)
const planForm = reactive({
  scope: 'engine',
  engineCodes: [] as string[],
  canaryNodeIds: [] as string[],
  batchSize: 1,
  baselineRef: '',
  manifestVersion: '',
  productionScope: false,
  maintenanceWindow: '',
})
const engineOptions = ref<AppautoEngine[]>([])
const nodeOptions = ref<ExecutorInfo[]>([])
const impact = ref<UpgradePlanImpact | null>(null)
const impactLoading = ref(false)
const submitting = ref(false)
/** Row currently carrying an in-flight review request (per-row button loading, not a modal). */
const reviewingId = ref<number | null>(null)
const createdPlan = ref<UpgradePlanRow | null>(null)
const createdSteps = ref<UpgradePlanStepRow[]>([])
const plans = ref<UpgradePlanRow[]>([])
const plansLoading = ref(false)
const selectedPlan = ref<UpgradePlanRow | null>(null)
const planSteps = ref<UpgradePlanStepRow[]>([])

const matrixRows = ref<ClusterComponentRow[]>([])
const matrixLoading = ref(false)
const matrixLoaded = ref(false)
const planDepsLoaded = ref(false)

const adviceProjectId = ref<number | null>(null)
const adviceVersion = ref('')
const adviceLoading = ref(false)
const advice = ref<AppautoUpgradeAdvice | null>(null)
const adviceError = ref('')
const windowRows = ref<AppautoUpgradeWindow[]>([])
const windowLoading = ref(false)
const windowLoaded = ref(false)
const windowError = ref('')
const flakyRows = ref<AppautoUpgradeFlakyLink[]>([])
const flakyLoading = ref(false)
const flakyLoaded = ref(false)

const PROFILE_KEYS = ['scriptCases', 'verbCounts', 'engineUsage', 'aiSampleCases', 'webviewSteps'] as const

const PROFILE_LABEL_KEYS: Record<(typeof PROFILE_KEYS)[number], string> = {
  scriptCases: 'upgrade.profileScriptCases',
  verbCounts: 'upgrade.profileVerbCounts',
  engineUsage: 'upgrade.profileEngineUsage',
  aiSampleCases: 'upgrade.profileAiSampleCases',
  webviewSteps: 'upgrade.profileWebviewSteps',
}

function profileValue(pk: (typeof PROFILE_KEYS)[number]): string {
  const v = advice.value?.profile?.[pk]
  if (v === null || v === undefined) return '—'
  if (typeof v === 'object') {
    const entries = Object.entries(v as Record<string, unknown>)
    return entries.length ? entries.map(([k, n]) => `${k} ${n}`).join('，') : '0'
  }
  return String(v)
}

function pct(rate: number): string {
  return `${(rate * 100).toFixed(2)}%`
}


async function runAdvisor() {
  if (!adviceProjectId.value || !adviceVersion.value) return
  adviceLoading.value = true
  adviceError.value = ''
  advice.value = null
  try {
    advice.value = await runUpgradeAdvice(adviceProjectId.value, adviceVersion.value)
  } catch (e: unknown) {
    adviceError.value = bizMessage(e)
  } finally {
    adviceLoading.value = false
  }
}


async function loadWindow() {
  windowLoading.value = true
  windowError.value = ''
  try {
    windowRows.value = await listUpgradeWindowSlots()
    windowLoaded.value = true
  } catch (e: unknown) {
    windowError.value = bizMessage(e)
  } finally {
    windowLoading.value = false
  }
}

async function loadFlakyLinks() {
  flakyLoading.value = true
  try {
    flakyRows.value = await listUpgradeFlakyLinks()
    flakyLoaded.value = true
  } catch (e: unknown) {
    ElMessage.error(bizMessage(e))
  } finally {
    flakyLoading.value = false
  }
}

const rangeText = computed(() => {
  const unbounded = t('upgrade.versionUnbounded')
  const lo = latest.value?.executorVersionMin || unbounded
  const hi = latest.value?.executorVersionMax || unbounded
  return `${lo} ~ ${hi}`
})

async function load() {
  loading.value = true
  try {
    const [latestRow, all] = await Promise.all([getLatestManifest(), listReleaseManifests()])
    latest.value = latestRow
    manifests.value = all
  } catch (e: unknown) {
    ElMessage.error(bizMessage(e))
  } finally {
    loaded.value = true
    loading.value = false
  }
  // pending candidates ride the same refresh (independent pull, no
  // collateral on failure — empty = page identical to pre- surface).
  await loadCandidates()
  try {
    platform.value = await getPlatformVersion()
  } catch {
    platform.value = null
  } finally {
    platformLoaded.value = true
  }
}

async function toggleHealth() {
  healthVisible.value = !healthVisible.value
  if (!healthVisible.value) return
  healthLoading.value = true
  try {
    await ensureCompatRows()
  } catch (e: unknown) {
    ElMessage.error(bizMessage(e))
  } finally {
    healthLoading.value = false
  }
}

/** Compat rows lazy pull (shared by the health card and the platform rehearsal
 * block — one fetch, core verdicts straight through, no second spectrum). */
async function ensureCompatRows() {
  if (compatRows.value.length) return
  compatRows.value = await getCompatDiff()
}


function compatRowClass({ row }: { row: ExecutorCompatRow }): string {
  return row.compatible === false ? 'compat-row-danger' : ''
}

function openGuide() {
  guideVisible.value = true
}


async function onTabChange(name: string | number) {
  if (name === 'plans') {
    await ensurePlanDeps()
    if (planStep.value === 2) await loadPlans()
  } else if (name === 'matrix' && !matrixLoaded.value) {
    await loadMatrix()
  } else if (name === 'insight') {
    if (!windowLoaded.value) await loadWindow()
    if (!flakyLoaded.value) await loadFlakyLinks()
  }
}


async function ensurePlanDeps() {
  if (planDepsLoaded.value) return
  try {
    const [engines, executors] = await Promise.all([listAppautoEngines(), listExecutors()])
    engineOptions.value = engines.filter((e) => e.status === 1)
    nodeOptions.value = executors
    planDepsLoaded.value = true
  } catch (e: unknown) {
    ElMessage.error(bizMessage(e))
  }
}

/** Clear the engine multi-select whenever scope leaves engine (node/platform payloads must carry no codes — core rejects them in plain words). */
function onScopeChange() {
  if (planForm.scope !== 'engine') {
    planForm.engineCodes = []
  }
  // Platform rehearsal needs the out-of-range executor tally (core-sourced
  // compat rows, lazily pulled once; same spectrum as the health card).
  if (planForm.scope === 'platform') void ensureCompatRows()
}

/** Three-value scope label for the plan list (platform was mislabeled as engine by the old binary expression — fixed here). */
function scopeLabel(scope: string): string {
  if (scope === 'node') return t('upgrade.scopeNode')
  if (scope === 'platform') return t('upgrade.scopePlatform')
  return t('upgrade.scopeEngine')
}

function buildPayload() {
  return {
    scope: planForm.scope,
    engineCodes: planForm.scope === 'engine' ? [...planForm.engineCodes] : [],
    canaryNodeIds: [...planForm.canaryNodeIds],
    batchSize: planForm.batchSize,
    baselineRef: planForm.baselineRef.trim() || null,
    manifestVersion: planForm.manifestVersion || null,
    productionScope: planForm.productionScope,
    maintenanceWindow: planForm.maintenanceWindow.trim() || null,
  }
}

// ── Platform rehearsal tallies (display aggregation only — every verdict and
// number below comes from core-sourced rows; nothing is re-judged here) ──
/** Executors outside the latest manifest range (core compat rows, compatible===false). */
const outOfRangeExecutorCount = computed(
  () => compatRows.value.filter((r) => r.compatible === false).length,
)
/** Tasks currently running on executor nodes (sum of ledger runningCnt — an
 * aggregation for display, paired with the "waves wait for drain by default" note). */
const runningTaskTotal = computed(
  () => nodeOptions.value.reduce((acc, n) => acc + (n.runningCnt || 0), 0),
)
/** Created platform steps grouped by waveSeq ascending (core ordering; the UI
 * only buckets, never recomputes wave timing — estimatedMinutes is passed through). */
const platformWaveGroups = computed(() => {
  const bySeq = new Map<number, { family: string; targetVersion: string | null }[]>()
  for (const s of createdSteps.value) {
    if (s.waveSeq === null || s.waveSeq === undefined) continue
    const bucket = bySeq.get(s.waveSeq) ?? []
    bucket.push({ family: s.componentFamily || s.kind, targetVersion: s.targetVersion })
    bySeq.set(s.waveSeq, bucket)
  }
  return [...bySeq.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([seq, items]) => ({ seq, items }))
})


async function toPreviewStep() {
  impactLoading.value = true
  try {
    impact.value = await previewUpgradeImpact(buildPayload())
    planStep.value = 1
  } catch (e: unknown) {
    ElMessage.error(bizMessage(e))
  } finally {
    impactLoading.value = false
  }
}

function backToScope() {
  if (createdPlan.value) {
    resetPlanFlow()
  } else {
    planStep.value = 0
  }
}

/** Jump straight to the existing-plan list (reviewer entry: review before starting
 * never requires creating a plan first). Loads the list through the same bridge. */
async function openPlanList() {
  planStep.value = 2
  await loadPlans()
}


async function confirmCreate() {
  submitting.value = true
  try {
    const plan = await createUpgradePlan(buildPayload())
    createdPlan.value = plan
    createdSteps.value = await listUpgradePlanSteps(plan.id)
  } catch (e: unknown) {
    ElMessage.error(bizMessage(e))
  } finally {
    submitting.value = false
  }
}

/** Confirm & run = start (PREVIEW→RUNNING CAS; empty canary = server picks the first node).
 * Platform scope adds a second confirmation carrying the restart cost
 * statement — the user must see "core restarts last, ~30-60s dispatch pause"
 * before the waves begin. */
async function confirmStart() {
  if (!createdPlan.value) return
  if (planForm.scope === 'platform') {
    try {
      await ElMessageBox.confirm(
        t('upgrade.platformStartConfirm', { v: createdPlan.value.manifestVersion || '—' }),
        t('upgrade.actStart'),
        {
          type: 'warning',
          confirmButtonText: t('upgrade.btnConfirmRun'),
          cancelButtonText: t('upgrade.btnBack'),
        },
      )
    } catch {
      return
    }
  }
  submitting.value = true
  try {
    await startUpgradePlan(createdPlan.value.id)
    ElMessage.success(t('upgrade.toastStarted'))
    resetPlanFlow()
    planStep.value = 2
    await loadPlans()
    // The fresh snapshot now holds the RUNNING row: hand freshness to the
    // shared beat instead of a page-side timer (R62①).
    planFollow.start()
  } catch (e: unknown) {
    ElMessage.error(bizMessage(e))
  } finally {
    submitting.value = false
  }
}

function resetPlanFlow() {
  planStep.value = 0
  impact.value = null
  createdPlan.value = null
  createdSteps.value = []
  selectedPlan.value = null
  planSteps.value = []
}

/** Single row fetcher shared by the manual read (loadPlans) and the follow
 * beat reload below (R40: one fetch path, two wrappers). Re-reads the open
 * plan's step detail so the detail table stays fresh while following. */
async function fetchPlanRows() {
  plans.value = await listUpgradePlans()
  if (selectedPlan.value) {
    const fresh = plans.value.find((p) => p.id === selectedPlan.value?.id)
    if (fresh) await openPlan(fresh)
  }
}

async function loadPlans() {
  plansLoading.value = true
  try {
    await fetchPlanRows()
  } catch (e: unknown) {
    ElMessage.error(bizMessage(e))
  } finally {
    plansLoading.value = false
  }
}

/**
 * R62 ①③: a RUNNING upgrade plan is a live background process — the core
 * advancer dispatches waves node by node long after the request returned.
 * While the visible list holds one, the shared follow beat (single source,
 * same loop as the export-task/journey panels) re-reads the rows so the
 * statuses settle on screen with no user action; when nothing runs anymore
 * the badge self-retires. PAUSED/PREVIEW rows wait for a human click and
 * are honestly out of scope; the terminal set (COMPLETED/ABORTED/BRAKED) is
 * core's own state machine (UpgradePlanServiceImpl), not a page copy.
 */
const planFollow = useListAutoFollow<UpgradePlanRow>({
  active: () => activeTab.value === 'plans' && planStep.value === 2,
  rows: () => plans.value,
  isPending: (row) => row.status === 'RUNNING',
  reload: async () => {
    try {
      await fetchPlanRows()
    } catch {
      /* beat failure keeps the previous snapshot; next beat retries */
    }
  },
  intervalMs: () => listAutoFollowMs.value,
})
const planFollowing = planFollow.following

async function openPlan(row: UpgradePlanRow) {
  selectedPlan.value = row
  try {
    planSteps.value = await listUpgradePlanSteps(row.id)
  } catch (e: unknown) {
    ElMessage.error(bizMessage(e))
  }
}


async function doTransition(
  fn: (id: number) => Promise<UpgradePlanRow>,
  row: UpgradePlanRow,
  okMsg: string,
) {
  try {
    await fn(row.id)
    ElMessage.success(okMsg)
    await loadPlans()
    // resume flips a row back to RUNNING without any panel (re)mount —
    // re-hand freshness to the beat; start() self-checks pending, so it is
    // a no-op for pause/abort.
    planFollow.start()
  } catch (e: unknown) {
    ElMessage.error(bizMessage(e))
  }
}

/**
 * Confirm the second-person review on a production-declared PREVIEW plan.
 * The reviewer identity is the login context bridged by admin — nothing is
 * sent in the body. Self-review and duplicate review come back from core as
 * plain-language refusals and are shown straight through (the UI keeps no
 * copy of that judgment).
 */
async function doReview(row: UpgradePlanRow) {
  reviewingId.value = row.id
  try {
    const fresh = await reviewUpgradePlan(row.id)
    ElMessage.success(t('upgrade.toastReviewed', { name: fresh.reviewedBy || '—' }))
    await loadPlans()
  } catch (e: unknown) {
    ElMessage.error(bizMessage(e))
  } finally {
    reviewingId.value = null
  }
}


async function confirmAbort(row: UpgradePlanRow) {
  try {
    await ElMessageBox.confirm(t('upgrade.abortConfirm', { id: row.id }), t('upgrade.actAbort'), {
      type: 'warning',
      confirmButtonText: t('upgrade.btnConfirmRun'),
      cancelButtonText: t('upgrade.btnBack'),
    })
  } catch {
    return
  }
  await doTransition(abortUpgradePlan, row, t('upgrade.toastAborted'))
}

async function loadMatrix() {
  matrixLoading.value = true
  try {
    matrixRows.value = await listClusterComponents()
    matrixLoaded.value = true
  } catch (e: unknown) {
    ElMessage.error(bizMessage(e))
  } finally {
    matrixLoading.value = false
  }
}


function stateType(state: string): 'success' | 'warning' | 'danger' | 'info' {
  return verdictTagType(state)
}

function stateLabel(state: string): string {
  return supportStateLabel(state, t)
}

/**
 * Pre-check tag colour: presentation-only mapping over the core verdict enum
 * (reachable = green; every failure shape = red; unknown values = grey, never
 * guessed). The verdict itself is decided server-side by ReleaseArtifactProber.
 */
function precheckTagType(verdict: string): 'success' | 'warning' | 'danger' | 'info' {
  if (verdict === 'reachable') return 'success'
  if (verdict === 'unreachable' || verdict === 'size_mismatch' || verdict === 'host_blocked') return 'danger'
  return 'info'
}

/** Pre-check verdict label (i18n key family; dirty/unknown values pass through untranslated). */
function precheckVerdictLabel(verdict: string): string {
  const known = ['reachable', 'unreachable', 'size_mismatch', 'host_blocked']
  return known.includes(verdict) ? t(`upgrade.precheckVerdict.${verdict}`) : verdict
}

function planStatusType(status: string): 'success' | 'warning' | 'danger' | 'info' | 'primary' {
  const map: Record<string, 'success' | 'warning' | 'danger' | 'info' | 'primary'> = {
    PREVIEW: 'info',
    RUNNING: 'primary',
    PAUSED: 'warning',
    COMPLETED: 'success',
    ABORTED: 'info',
    BRAKED: 'danger',
  }
  return map[status] ?? 'info'
}

function planStatusLabel(status: string): string {
  const map: Record<string, string> = {
    PREVIEW: t('upgrade.statusPreview'),
    RUNNING: t('upgrade.statusRunning'),
    PAUSED: t('upgrade.statusPaused'),
    COMPLETED: t('upgrade.statusCompleted'),
    ABORTED: t('upgrade.statusAborted'),
    BRAKED: t('upgrade.statusBraked'),
  }
  return map[status] ?? status
}

function stepStatusType(status: string): 'success' | 'warning' | 'danger' | 'info' | 'primary' {
  const map: Record<string, 'success' | 'warning' | 'danger' | 'info' | 'primary'> = {
    PENDING: 'info',
    RUNNING: 'primary',
    SUCCESS: 'success',
    FAILED: 'danger',
    SKIPPED: 'warning',
  }
  return map[status] ?? 'info'
}

function stepStatusLabel(status: string): string {
  const map: Record<string, string> = {
    PENDING: t('upgrade.stepPending'),
    RUNNING: t('upgrade.stepRunning'),
    SUCCESS: t('upgrade.stepSuccess'),
    FAILED: t('upgrade.stepFailed'),
    SKIPPED: t('upgrade.stepSkipped'),
  }
  return map[status] ?? status
}


function phaseLabel(phase: string): string {
  const map: Record<string, string> = {
    canary: t('upgrade.phaseCanary'),
    batch: t('upgrade.phaseBatch'),
    rollback: t('upgrade.phaseRollback'),
  }
  return map[phase] ?? phase
}


function parseArray(raw: string | null): string[] {
  if (!raw) return []
  try {
    const v = JSON.parse(raw)
    return Array.isArray(v) ? v.filter((x) => typeof x === 'string' && x.trim()) : []
  } catch {
    return []
  }
}


function parseCodes(raw: string | null): string[] {
  if (!raw) return []
  const arr = parseArray(raw)
  return arr.length ? arr : [raw]
}


function evidenceText(raw: string | null): string {
  if (!raw || raw === '{}') return ''
  try {
    const v = JSON.parse(raw)
    if (typeof v === 'string') return v
    if (Array.isArray(v)) return v.map((x) => String(x)).join('；')
    if (v && typeof v === 'object') {
      return Object.entries(v as Record<string, unknown>)
        .map(([k, val]) => `${k}: ${String(val)}`)
        .join('；')
    }
    return raw
  } catch {
    return raw
  }
}

function parseMatrix(raw: string | null): MatrixRow[] {
  if (!raw) return []
  try {
    const v = JSON.parse(raw)
    if (!Array.isArray(v)) return []
    return v
      .filter((x) => x && typeof x === 'object' && typeof x.component === 'string')
      .map((x) => ({
        component: x.component as string,
        support: typeof x.support === 'string' ? x.support : '',
        pinned: typeof x.pinned === 'string' ? x.pinned : '',
      }))
  } catch {
    return []
  }
}

function guideText(): string {
  if (!latest.value) return ''
  const lines = [
    `# ${t('upgrade.guideTitle', { v: latest.value.version })}`,
    '',
    `> ${t('upgrade.guideMigrationNote')}`,
    '',
    `## ${t('upgrade.suggestedPath')}`,
    latest.value.suggestedPath || t('upgrade.noPath'),
    '',
    `## ${t('upgrade.colBreaking')}`,
    ...parseArray(latest.value.breakingChanges).map((c, i) => `${i + 1}. ${c}`),
    '',
    `## ${t('upgrade.colCompat')}`,
    t('upgrade.bannerRange', { range: rangeText.value }),
  ]
  return lines.join('\n')
}

function exportGuide() {
  const blob = new Blob([guideText()], { type: 'text/markdown;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `upgrade-guide-${latest.value?.version ?? 'draft'}.md`
  a.click()
  URL.revokeObjectURL(a.href)
  ElMessage.success(t('upgrade.guideExported'))
}

function printGuide() {
  window.print()
}

function formatTime(v: string | null | undefined): string {
  return v ? v.replace('T', ' ').slice(0, 19) : '—'
}

const frontendVersion: string = typeof __APP_VERSION__ === 'undefined' ? '' : __APP_VERSION__
const frontendBuildTime: string = typeof __APP_BUILD_TIME__ === 'undefined' ? '' : __APP_BUILD_TIME__


function bizMessage(e: unknown): string {
  return bizErrorMsg(e, t('upgrade.loadFailed'))
}

onMounted(load)
</script>

<style scoped>
.upgrade-center-page {
  padding: 16px;
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.page-title {
  margin: 0;
}
.version-banner,
.self-version-banner {
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  padding: 12px 16px;
  margin-bottom: 12px;
}
.banner-main {
  display: flex;
  align-items: center;
  gap: 10px;
}
.banner-label {
  color: var(--el-text-color-secondary);
}
.banner-version {
  font-size: 20px;
  font-weight: 600;
}
.banner-meta {
  display: flex;
  gap: 16px;
  margin-top: 6px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.banner-note {
  margin-top: 8px;
}
.health-panel {
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  padding: 12px 16px;
  margin-bottom: 12px;
}
.section-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}
.section-title {
  margin: 0 0 8px;
}
.caliber-note {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin: 8px 0 0;
}
.release-item {
  border-bottom: 1px solid var(--el-border-color-lighter);
  padding: 8px 0 12px;
}
.release-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
}
.release-version {
  font-weight: 600;
  font-size: 16px;
}
.release-time {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.release-sub {
  margin: 8px 0 4px;
  font-size: 13px;
}
.release-none {
  color: var(--el-text-color-secondary);
}
.guide-title {
  margin: 0 0 8px;
}
.guide-actions {
  display: flex;
  gap: 8px;
  margin-top: 16px;
}
.empty-box {
  padding: 24px 0;
}
.plan-toolbar {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 16px;
}
.plan-steps {
  flex: 1;
}
.plan-form,
.plan-preview,
.plan-progress {
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  padding: 16px;
}
.plan-form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
}
.impact-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  margin: 12px 0;
  font-size: 14px;
}
.precheck-block {
  margin: 4px 0 12px;
}
.rehearsal-block {
  margin: 4px 0 12px;
  padding: 10px 12px;
  border-radius: 8px;
  /* Halo-style soft emphasis (AD-caliber background wash; no dialog, no stripe) */
  background: var(--el-color-warning-light-9);
}
.wave-group-block {
  margin: 8px 0 12px;
}
.section-subtitle {
  margin: 8px 0;
  font-size: 14px;
}
.wave-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin: 4px 0;
  font-size: 13px;
}
.wave-items {
  color: var(--el-text-color-regular);
}
.precheck-empty {
  margin: 4px 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.precheck-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin: 6px 0;
  font-size: 13px;
}
.precheck-family {
  font-weight: 600;
  white-space: nowrap;
}
.precheck-reason {
  color: var(--el-text-color-regular);
  word-break: break-all;
}
.plan-created {
  margin: 12px 0;
}
.plan-steps-title {
  margin-top: 16px;
}
.brake-text {
  color: var(--el-color-danger);
}
.insight-block {
  margin: 0 0 24px;
}
.insight-form {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}
.section-hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.advice-body {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 12px 16px;
  background: var(--el-fill-color-lighter);
}
.advice-summary {
  margin: 0 0 8px;
  font-weight: 600;
}
.advice-conclusion {
  margin: 8px 0 0;
}
.profile-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.profile-chips .chip {
  margin: 0 4px 4px 0;
}
:deep(.compat-row-danger) {
  --el-table-tr-bg-color: var(--el-color-danger-light-9);
}
/* pending card: background halo on the danger-weak token (AD caliber —
 no modal, no side bar). */
.candidate-card {
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  padding: 12px 16px;
  margin-bottom: 12px;
}
.candidate-halo {
  background: var(--tp-danger-weak);
}
.candidate-actions {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}
/* Required-field highlight: warning ring on blank authoritative fields (a
   prompt, not a submit gate — core register tolerates empty arrays). */
:deep(.confirm-field-blank .el-input__wrapper),
:deep(.confirm-field-blank .el-textarea__inner) {
  box-shadow: 0 0 0 1px var(--el-color-warning) inset;
}
.confirm-cross-warn {
  margin: 10px 0;
}
.confirm-blank-hint {
  color: var(--el-color-warning);
}
.confirm-impact {
  margin-top: 8px;
}
/* Changelog pending group (gray state) */
.changelog-pending-group {
  margin-bottom: 12px;
}
.changelog-pending-item {
  opacity: 0.72;
  background: var(--el-fill-color-light);
  border-radius: 6px;
  padding: 8px 10px;
}
</style>
