<template>
  <div class="page-root">
    <div class="back-bar">
      <el-button link type="primary" @click="goBack">
        <el-icon><ArrowLeft /></el-icon>{{ t('common.back') }}
      </el-button>
      <span class="detail-title">{{ project?.name || t('project.title') }}</span>
      <el-tag v-if="project?.code" size="small" type="info">{{ project.code }}</el-tag>
      <FavoriteButton entityType="project" :entity-id="projectId" />
    </div>

    <el-tabs v-model="activeTab" class="detail-tabs">
      
      <el-tab-pane :label="t('project.envs')" name="envs">
        <div class="page-card">
          <div class="tab-toolbar">
            <span class="text-secondary">{{ t('project.envsHint') }}</span>
            <el-button type="primary" size="small" data-test="env-add" @click="openEnvDialog()">
              <el-icon><Plus /></el-icon>{{ t('project.addEnv') }}
            </el-button>
          </div>
          <TableSkeleton v-if="envLoading" :cols="8" data-test="projectdetailview-skeleton" />
          <el-table border :data="envs" data-test="env-table" v-else>
            <el-table-column prop="name" :label="t('project.envName')" min-width="170">
              <template #default="{ row }">
                <span data-test="env-name-cell">{{ row.name }}</span>
 
                <el-tag
                  v-if="healthMap[row.id]?.tlsMode === 'insecure'"
                  size="small"
                  type="danger"
                  class="cert-insecure-badge"
                  data-test="env-cert-badge"
                >{{ t('project.certBadgeInsecure') }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column :label="t('project.envType')" width="110">
              <template #default="{ row }">
                <el-tag size="small" :type="envTypeTag(row.envType)">
                  {{ envTypeLabel(row.envType) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="baseUrl" :label="t('project.envBaseUrl')" min-width="220" show-overflow-tooltip />
            <el-table-column prop="endTypes" :label="t('project.envEndTypes')" width="110">
              <template #default="{ row }">{{ row.endTypes || 'web' }}</template>
            </el-table-column>
            <el-table-column prop="browsers" :label="t('project.envBrowsers')" width="150">
              <template #default="{ row }">{{ row.browsers || 'chromium' }}</template>
            </el-table-column>
            <el-table-column :label="t('project.envHealthStatus')" width="190">
              <template #default="{ row }">
                <span :data-test="'env-health-' + row.id">
                  <el-tooltip v-if="row.healthStatus === 2" :content="row.healthMsg || t('project.healthProbeDefaultMsg')" placement="top">
                    <el-tag size="small" type="danger" data-test="env-health-unhealthy">
                      {{ t('project.healthUnhealthy') }}<span v-if="row.healthCheckedAt">{{ t('project.healthCheckedSuffix', { ago: timeAgo(row.healthCheckedAt) }) }}</span>
                    </el-tag>
                  </el-tooltip>
                  <el-tag v-else-if="row.healthStatus === 1" size="small" type="success" data-test="env-health-healthy">
                    {{ t('project.healthHealthy') }}<span v-if="row.healthCheckedAt">{{ t('project.healthCheckedSuffix', { ago: timeAgo(row.healthCheckedAt) }) }}</span>
                  </el-tag>
                  <el-tag v-else size="small" type="info" data-test="env-health-unknown">{{ t('project.healthUnknown') }}</el-tag>
                </span>
                <el-button link type="primary" :loading="probingEnvId === row.id" data-test="env-health-probe" @click="onProbeHealth(row)">
                  {{ t('project.healthProbe') }}
                </el-button>
 
                <div class="cert-card-line" data-test="env-cert-card">
                  <template v-if="healthMap[row.id]?.certCheckedAt">
                    <el-tooltip
                      :content="t('project.certTooltip', { issuer: healthMap[row.id]?.certIssuer || t('project.certIssuerUnknown'), notAfter: healthMap[row.id]?.certNotAfter || '-', checkedAt: healthMap[row.id]?.certCheckedAt || '-' })"
                      placement="top"
                    >
                      <span class="cert-lock">
                        <el-icon :size="14" :color="healthMap[row.id]?.certChainComplete ? 'var(--el-color-success)' : 'var(--el-color-warning)'" data-test="env-cert-lock">
                          <Lock v-if="healthMap[row.id]?.certChainComplete" /><WarningFilled v-else />
                        </el-icon>
                        <span class="cert-text" :class="healthMap[row.id]?.certChainComplete ? 'cert-text-ok' : 'cert-text-warn'" data-test="env-cert-expiry">
                          {{ healthMap[row.id]?.certNotAfter ? t('project.certUntil', { date: healthMap[row.id]?.certNotAfter }) : t('project.certNoExpiry') }}
                        </span>
                      </span>
                    </el-tooltip>
                  </template>
                  <span v-else class="cert-text cert-text-unknown" data-test="env-cert-unknown">
                    <el-icon :size="14"><Unlock /></el-icon>{{ t('project.certNotProbed') }}
                  </span>
                </div>
              </template>
            </el-table-column>
            <el-table-column :label="t('project.deployVersion')" width="150">
              <template #default="{ row }">
                <template v-if="deployMap[row.id]?.deployedVersion">
                  <span data-test="env-deploy-version">{{ deployMap[row.id].deployedVersion }}</span>
                  <div class="text-secondary deploy-sub">
                    <span v-if="deployMap[row.id].deployedByName">{{ deployMap[row.id].deployedByName }} · </span>
                    <span v-if="deployMap[row.id].deployedAt">{{ deployMap[row.id].deployedAt }}</span>
                  </div>
                </template>
                <span v-else class="text-secondary" data-test="env-deploy-none">{{ t('project.deployUnrecorded') }}</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('common.action')" width="230" fixed="right">
              <template #default="{ row }">
                
                <RowActions
                  :actions="[
                    { key: 'deploy', label: t('project.deployRecord') },
                    { key: 'edit', label: t('common.edit') },
                    { key: 'params', label: t('project.envParams') },
                    { key: 'exempt', label: t('project.exemptRowAction') },
                    { key: 'delete', label: t('common.delete'), type: 'danger' },
                  ]"
                  :max="3"
                  @command="(k: string) => onEnvRowCmd(k, row)"
                />
              </template>
            </el-table-column>
        
            <template #empty>
              <el-empty :description="t('project.envs') + t('project.envsEmptySuffix')" :image-size="72">
                <el-button type="primary" plain data-test="env-empty-create" @click="openEnvDialog()">
                  <el-icon><Plus /></el-icon>{{ t('project.addEnv') }}
                </el-button>
              </el-empty>
            </template>
  </el-table>

        </div>
      </el-tab-pane>

      
      <el-tab-pane :label="t('project.members')" name="members">
        <div class="page-card">
          <div class="tab-toolbar">
            <span class="text-secondary">{{ t('project.membersHint') }}</span>
            <el-button type="primary" size="small" data-test="member-add" @click="openMemberDialog()">
              <el-icon><Plus /></el-icon>{{ t('project.addMember') }}
            </el-button>
          </div>
          <TableSkeleton v-if="memberLoading" :cols="4" data-test="projectdetailview-skeleton" />
          <el-table border :data="members" data-test="member-table" v-else>
            <el-table-column prop="nickName" :label="t('project.memberUser')" min-width="140">
              <template #default="{ row }">{{ row.nickName || row.username }}</template>
            </el-table-column>
            <el-table-column prop="username" :label="t('project.memberAccount')" width="140" />
 
            <el-table-column prop="roleLabel" :label="t('project.memberRole')" width="140">
              <template #default="{ row }">
                <el-tag size="small" :type="roleTag(row.role)" data-test="member-role-tag">{{ row.roleLabel || row.role }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column :label="t('common.action')" width="210" fixed="right">
              <template #default="{ row }">
 
                <template v-if="canManageMembers">
                  <el-button
                    v-if="!isLeaderRow(row)"
                    link
                    type="primary"
                    data-test="member-set-owner"
                    @click="onSetOwner(row)"
                  >{{ t('project.memberSetOwner') }}</el-button>
                  <el-tooltip
                    v-else
                    :content="ownerCount <= 1 ? t('project.memberDemoteNeedOtherOwner') : ''"
                    :disabled="ownerCount > 1"
                  >
                    <span>
                      <el-button
                        link
                        type="warning"
                        :disabled="ownerCount <= 1"
                        data-test="member-demote-owner"
                        @click="onDemoteOwner(row)"
                      >{{ t('project.memberDemoteOwner') }}</el-button>
                    </span>
                  </el-tooltip>
                </template>
                <el-button v-if="canManageMembers" link type="danger" @click="onDeleteMember(row)">{{ t('common.delete') }}</el-button>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty :description="t('project.membersEmpty')" :image-size="72">
                <el-button type="primary" plain data-test="member-empty-add" @click="openMemberDialog()">
                  <el-icon><Plus /></el-icon>{{ t('project.addMember') }}
                </el-button>
              </el-empty>
            </template>
          </el-table>
          
        </div>
      </el-tab-pane>

      
      <el-tab-pane :label="t('project.llmTab')" name="llm">
        <div class="page-card">
          <div class="tab-toolbar">
            <span class="text-secondary">{{ t('project.llmHint') }}</span>
            <el-button
              type="primary"
              size="small"
              :loading="llmSaving"
              data-test="llm-config-save"
              @click="onLlmSave"
            >
              {{ t('project.llmSave') }}
            </el-button>
          </div>
          <el-form label-width="110px" class="llm-form">
            <el-form-item :label="t('project.llmCurrentModel')">
              <el-select
                v-model="llmConfigId"
                :placeholder="t('project.llmSelectPlaceholder')"
                clearable
                style="width: 420px"
                data-test="llm-config-select"
                filterable
              >
                <el-option
                  v-for="o in llmOptions"
                  :key="o.id"
                  :label="`${o.name}（${o.provider} / ${o.modelName}）`"
                  :value="o.id"
                />
              </el-select>
              <span v-if="llmConfigName" class="llm-current" data-test="llm-config-name">
                {{ t('project.llmUsing', { name: llmConfigName }) }}
              </span>
              <span v-else class="llm-current muted">{{ t('project.llmNotSelected') }}</span>
            </el-form-item>
            <el-form-item :label="t('project.descLabel')">
              <span class="text-secondary">{{ t('project.llmDescTip') }}</span>
            </el-form-item>
          </el-form>
          <el-empty
            v-if="!llmLoading && llmOptions.length === 0"
            :description="t('project.llmEmptyList')"
            :image-size="80"
          />
        </div>
      </el-tab-pane>

 
      <el-tab-pane :label="t('project.skillTab')" name="skills">
        <div class="page-card">
          <div class="tab-toolbar">
            <span class="text-secondary">
              {{ t('project.skillHint') }}
            </span>
            <div class="skill-toolbar-right">
              <el-select
                v-if="canManageMembers"
                v-model="skillAddId"
                :placeholder="t('project.skillAddPlaceholder')"
                filterable
                remote
                clearable
                :remote-method="onSkillSearch"
                :loading="skillOptionsLoading"
                style="width: 320px"
                popper-class="tp-skill-add-popper"
                data-test="skill-add-select"
                @change="onSkillAdd"
              >
                <el-option
                  v-for="o in skillOptions"
                  :key="o.id"
                  :label="`${o.name}（${o.typeLabel}）`"
                  :value="o.id"
                />
              </el-select>
              <el-tooltip v-if="canManageMembers" :content="t('project.dirtyGuardHint')" :disabled="skillDirty" placement="top">
                <el-button
                  type="primary"
                  size="small"
                  :loading="skillSaving"
                  :disabled="!skillDirty"
                  class="skill-save-btn"
                  data-test="skill-bind-save"
                  @click="onSkillSave"
                >
                  {{ t('project.skillSave') }}
                </el-button>
              </el-tooltip>
            </div>
          </div>
          <TableSkeleton v-if="skillLoading" :cols="6" data-test="projectdetailview-skeleton" />
          <el-table border v-else :data="skillBindings" row-key="skillId" data-test="skill-bind-table">
            <el-table-column :label="t('project.skillCol')" min-width="220">
              <template #default="{ row }">
                <span class="skill-bind-name">{{ row.name }}</span>
                <el-tag size="small" class="skill-bind-type">{{ row.typeLabel }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column :label="t('project.skillRoleCol')" width="150">
              <template #default="{ row }">
                <el-select
                  v-model="row.role"
                  :disabled="!canManageMembers"
                  style="width: 120px"
                  :popper-class="`tp-skill-role-popper-${row.skillId}`"
                  :data-test="`skill-role-${row.skillId}`"
                >
                  <el-option v-for="opt in PROJECT_SKILL_ROLES" :key="opt.value" :label="opt.label" :value="opt.value" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column :label="t('project.skillPriorityCol')" width="140">
              <template #default="{ row }">
                <el-input-number
                  v-model="row.priority"
                  :min="0"
                  :max="9999"
                  :step="1"
                  :disabled="!canManageMembers"
                  controls-position="right"
                  style="width: 110px"
                  :data-test="`skill-priority-${row.skillId}`"
                />
              </template>
            </el-table-column>
            <el-table-column :label="t('project.enabledCol')" width="90" align="center">
              <template #default="{ row }">
                <el-switch
                  v-model="row.enabled"
                  :active-value="1"
                  :inactive-value="0"
                  :disabled="!canManageMembers"
                  :data-test="`skill-enabled-${row.skillId}`"
                />
              </template>
            </el-table-column>
            <el-table-column :label="t('project.skillBoundAtCol')" width="170">
              <template #default="{ row }">
                <span v-if="row.boundAt">{{ row.boundAt }}</span>
                <el-tag v-else size="small" type="warning">{{ t('project.colUnsaved') }}</el-tag>
              </template>
            </el-table-column>
            <!-- View stays visible for all members (readers should see what is bound); unbind is leader-only -->
            <el-table-column :label="t('common.action')" width="120" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" :data-test="`skill-view-${row.skillId}`" @click="openSkillDetail(row)">
                  {{ t('project.viewBtn') }}
                </el-button>
                <el-button v-if="canManageMembers" link type="danger" :data-test="`skill-unbind-${row.skillId}`" @click="onSkillUnbind(row.skillId)">
                  {{ t('project.skillUnbind') }}
                </el-button>
              </template>
            </el-table-column>
          </el-table>
          <EmptyState
            v-if="!skillLoading && skillBindings.length === 0"
            :title="canManageMembers ? t('project.skillEmptyMgmt') : t('project.skillEmptyReadonly')"
          />
        </div>
      </el-tab-pane>

 
      <el-tab-pane :label="t('project.toolTab')" name="tools">
        <div class="page-card">
          <div class="tab-toolbar">
            <span class="text-secondary">
              {{ t('project.toolHint') }}
            </span>
            <div class="skill-toolbar-right">
              <el-select
                v-if="canManageMembers"
                v-model="toolAddId"
                :placeholder="t('project.toolAddPlaceholder')"
                filterable
                remote
                clearable
                :remote-method="onToolSearch"
                :loading="toolOptionsLoading"
                style="width: 320px"
                popper-class="tp-tool-add-popper"
                data-test="tool-add-select"
                @change="onToolAdd"
              >
                <el-option
                  v-for="o in toolOptions"
                  :key="o.id"
                  :label="`${o.name}（${o.typeLabel}）`"
                  :value="o.id"
                />
              </el-select>
              <el-tooltip v-if="canManageMembers" :content="t('project.dirtyGuardHint')" :disabled="toolDirty" placement="top">
                <el-button
                  type="primary"
                  size="small"
                  :loading="toolSaving"
                  :disabled="!toolDirty"
                  class="skill-save-btn"
                  data-test="tool-bind-save"
                  @click="onToolSave"
                >
                  {{ t('project.toolSave') }}
                </el-button>
              </el-tooltip>
            </div>
          </div>
          <TableSkeleton v-if="toolLoading" :cols="5" data-test="projectdetailview-skeleton" />
          <el-table border v-else :data="toolBindings" row-key="toolId" data-test="tool-bind-table">
            <el-table-column :label="t('project.toolCol')" min-width="220">
              <template #default="{ row }">
                <span class="skill-bind-name">{{ row.name }}</span>
                <el-tag size="small" class="skill-bind-type">{{ row.typeLabel }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column :label="t('project.enabledCol')" width="90" align="center">
              <template #default="{ row }">
                <el-switch
                  v-model="row.enabled"
                  :active-value="1"
                  :inactive-value="0"
                  :disabled="!canManageMembers"
                  :data-test="`tool-enabled-${row.toolId}`"
                />
              </template>
            </el-table-column>
            <el-table-column :label="t('project.toolEnabledAtCol')" width="170">
              <template #default="{ row }">
                <span v-if="row.boundAt">{{ row.boundAt }}</span>
                <el-tag v-else size="small" type="warning">{{ t('project.colUnsaved') }}</el-tag>
              </template>
            </el-table-column>
            <!-- Same posture as the SKILL tab: view for all members, unbind leader-only -->
            <el-table-column :label="t('common.action')" width="120" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" :data-test="`tool-view-${row.toolId}`" @click="openToolDetail(row)">
                  {{ t('project.viewBtn') }}
                </el-button>
                <el-button v-if="canManageMembers" link type="danger" :data-test="`tool-unbind-${row.toolId}`" @click="onToolUnbind(row.toolId)">
                  {{ t('project.skillUnbind') }}
                </el-button>
              </template>
            </el-table-column>
          </el-table>
          <EmptyState
            v-if="!toolLoading && toolBindings.length === 0"
            :title="canManageMembers ? t('project.toolEmptyMgmt') : t('project.toolEmptyReadonly')"
          />
        </div>
      </el-tab-pane>

 
      <el-tab-pane :label="t('project.pluginTab')" name="plugins">
        <div class="page-card">
          <div class="tab-toolbar">
            <span class="text-secondary">
              {{ t('project.pluginHint') }}
            </span>
            <div class="skill-toolbar-right">
              <el-button
                v-if="canManageMembers && auth.hasPerm('perm:project:plugin-framework')"
                type="primary"
                size="small"
                data-test="plugin-apply-open"
                @click="openPluginApply"
              >
                {{ t('project.pluginApplyBtn') }}
              </el-button>
              <span v-else-if="canManageMembers" class="text-secondary" data-test="plugin-apply-hint">
                {{ t('project.pluginApplyNoPermHint') }}
              </span>
            </div>
          </div>
          <TableSkeleton v-if="pluginLoading" :cols="4" data-test="projectdetailview-plugin-skeleton" />
          <el-table border v-else :data="appliedPlugins" row-key="pluginId" data-test="plugin-applied-table">
            <el-table-column :label="t('project.pluginCol')" min-width="200">
              <template #default="{ row }">
                <span class="skill-bind-name">{{ row.pluginName }}</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('project.pluginStateCol')" width="100" align="center">
              <template #default="{ row }">
                <el-tag :type="row.enabled ? 'success' : 'info'" size="small" :data-test="`plugin-state-${row.pluginId}`">
                  {{ row.enabled ? t('project.pluginEnabled') : t('project.pluginDisabled') }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column :label="t('project.pluginAppliedAtCol')" width="170">
              <template #default="{ row }">{{ row.appliedAt || '—' }}</template>
            </el-table-column>
            <el-table-column :label="t('project.pluginSnapshotCol')" min-width="160">
              <template #default="{ row }">
                <span class="plugin-snapshot" :title="snapshotText(row)">
                  {{ snapshotText(row) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column v-if="canManageMembers" :label="t('common.action')" width="90" fixed="right">
              <template #default="{ row }">
                <el-button
                  link
                  type="danger"
                  :data-test="`plugin-uninstall-${row.pluginId}`"
                  @click="onPluginUninstall(row)"
                >
                  {{ t('project.pluginUninstall') }}
                </el-button>
              </template>
            </el-table-column>
          </el-table>
          <EmptyState
            v-if="!pluginLoading && appliedPlugins.length === 0"
            :title="canManageMembers ? t('project.pluginEmptyMgmt') : t('project.pluginEmptyReadonly')"
          />
        </div>
      </el-tab-pane>

 
      <el-tab-pane :label="t('project.infoTab')" name="info">
        <div class="page-card">
          <el-descriptions :column="2" border data-test="project-info-descriptions">
            <el-descriptions-item :label="t('project.infoJiraKey')">
              <span data-test="pi-jira-key">{{ project?.jiraProjectId || t('project.infoNotSet') }}</span>
            </el-descriptions-item>
            <el-descriptions-item :label="t('project.infoStorageMode')">
              <span data-test="pi-storage-mode">{{ storageModeLabel }}</span>
            </el-descriptions-item>
            <el-descriptions-item :label="t('project.infoTestOwner')">
              <span data-test="pi-test-owner">{{ ownerLabel(project?.testOwnerId) }}</span>
            </el-descriptions-item>
            <el-descriptions-item :label="t('project.infoDevOwner')">
              <span data-test="pi-dev-owner">{{ ownerLabel(project?.devOwnerId) }}</span>
            </el-descriptions-item>
            <el-descriptions-item :label="t('project.infoScriptPath')">
              <span data-test="pi-script-path">{{ project?.scriptStoragePath || t('project.infoSystemDefault') }}</span>
            </el-descriptions-item>
            <el-descriptions-item :label="t('project.infoRecordingPath')">
              <span data-test="pi-recording-path">{{ project?.recordingStoragePath || t('project.infoSystemDefault') }}</span>
            </el-descriptions-item>
            <el-descriptions-item :label="t('project.descLabel')" :span="2">
              <span class="text-secondary">{{ project?.description || '—' }}</span>
            </el-descriptions-item>
          </el-descriptions>
        </div>
      </el-tab-pane>
    </el-tabs>

    
    <el-dialog v-model="envDialogVisible" :title="envEditing ? t('project.editEnv') : t('project.addEnv')" width="480px" destroy-on-close data-test="env-dialog">
      <el-form ref="envFormRef" :model="envForm" :rules="envRules" label-width="90px" scroll-to-error>
        <el-form-item :label="t('project.envName')" prop="name">
          <el-input v-model="envForm.name" :placeholder="t('project.envNamePlaceholder')" data-test="env-name-input" />
        </el-form-item>
        <el-form-item :label="t('project.envType')" prop="envType">
          <el-select v-model="envForm.envType" :placeholder="t('project.envTypeSelectPlaceholder')" style="width: 100%" data-test="env-type-select">
            <el-option :label="t('project.envOptDev')" :value="1" />
            <el-option :label="t('project.envOptTest')" :value="2" />
            <el-option :label="t('project.envOptPre')" :value="3" />
            <el-option :label="t('project.envOptProd')" :value="4" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('project.envBaseUrl')" prop="baseUrl">
          <el-input v-model="envForm.baseUrl" :placeholder="t('project.envBaseUrlPlaceholder')" data-test="env-url-input" />
        </el-form-item>
        <el-form-item :label="t('project.envEndTypes')" prop="endTypes">
          <el-input v-model="envForm.endTypes" :placeholder="t('project.envEndTypesPlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('project.envBrowsers')" prop="browsers">
          <el-input v-model="envForm.browsers" :placeholder="t('project.envBrowsersPlaceholder')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="envDialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="envSaving" data-test="env-submit" @click="onEnvSave">{{ t('common.confirm') }}</el-button>
      </template>
    </el-dialog>

 
    <el-dialog
      v-model="paramsDialogVisible"
      :title="t('project.envParamsTitle', { name: paramsTarget?.name || '' })"
      width="640px"
      destroy-on-close
      data-test="env-params-dialog"
    >
      <div v-loading="paramsLoading">
        <el-alert type="info" :closable="false" class="params-hint" data-test="env-params-hint">
          {{ t('project.envParamsHint') }}
        </el-alert>
        <DataTablePage v-if="paramRows.length" :data="paramRows" size="small" data-test="env-params-table">
          <el-table-column :label="t('project.envParamKey')" min-width="170">
            <template #default="{ row }">
              <el-input v-model="row.key" :placeholder="t('project.envParamKeyPlaceholder')" data-test="env-params-key" />
            </template>
          </el-table-column>
          <el-table-column :label="t('project.envParamValue')" min-width="200">
            <template #default="{ row }">
              
              <el-input
                v-if="!row.encrypted || row.value !== MASKED_VALUE"
                v-model="row.value"
                :placeholder="t('project.envParamValuePlaceholder')"
                data-test="env-params-value"
              />
              <div v-else class="masked-cell">
                <span class="masked-text" data-test="env-params-masked">{{ row.value }}</span>
                <el-button link type="primary" data-test="env-params-reveal-edit" @click="onOverwriteEncrypted(row)">
                  {{ t('project.envParamOverwrite') }}
                </el-button>
              </div>
            </template>
          </el-table-column>
          <el-table-column :label="t('project.envParamStored')" width="90">
            <template #default="{ row }">
              <el-tag v-if="row.encrypted" size="small" type="warning" data-test="env-params-encrypted-tag">{{ t('project.envParamEncrypted') }}</el-tag>
              <el-tag v-else size="small" type="info">{{ t('project.envParamPlain') }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('common.action')" width="70">
            <template #default="{ $index }">
              <el-button link type="danger" data-test="env-params-remove" @click="paramRows.splice($index, 1)">
                {{ t('common.delete') }}
              </el-button>
            </template>
          </el-table-column>
        </DataTablePage>
        <div v-else class="text-secondary params-empty" data-test="env-params-empty">
          {{ t('project.envParamsEmpty') }}
        </div>
        <el-button class="params-add" size="small" data-test="env-params-add" @click="paramRows.push({ key: '', value: '', encrypted: false })">
          <el-icon><Plus /></el-icon>{{ t('project.envParamAdd') }}
        </el-button>
      </div>
      <template #footer>
        <el-button @click="paramsDialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button
          type="primary"
          :loading="paramsSaving"
          :disabled="!canManageMembers"
          data-test="env-params-save"
          @click="onEnvParamsSave"
        >
          {{ t('common.confirm') }}
        </el-button>
      </template>
    </el-dialog>

    
    <el-dialog v-model="deployDialogVisible" :title="t('project.deployRecordTitle')" width="640px" destroy-on-close data-test="deploy-dialog">
      <div class="deploy-env-name">
        {{ deployEnvName }}
        <el-tag v-if="deployMap[deployTarget?.id ?? -1]?.deployedVersion" size="small" type="info" data-test="deploy-current-version">
          {{ t('project.deployCurrent', { version: deployMap[deployTarget?.id ?? -1]?.deployedVersion }) }}
        </el-tag>
        <el-tag v-else size="small" type="warning">{{ t('project.deployUnrecorded') }}</el-tag>
      </div>
      <el-form ref="deployFormRef" :model="deployForm" :rules="deployRules" label-width="90px" scroll-to-error>
        <el-form-item :label="t('project.deployVersion')" prop="version">
          <el-input v-model="deployForm.version" :placeholder="t('project.deployVersionPlaceholder')" data-test="deploy-version-input" />
        </el-form-item>
      </el-form>
      <div v-if="deployBatches.length" class="deploy-batch-block">
        <div class="deploy-batch-title">{{ t('project.deployBatchRef') }}</div>
        <DataTablePage :data="deployBatches" size="small" data-test="deploy-batch-table">
          <el-table-column prop="batchNo" :label="t('project.deployBatchNo')" min-width="150" show-overflow-tooltip />
          <el-table-column prop="batchName" :label="t('project.deployBatchName')" min-width="140" show-overflow-tooltip />
          <el-table-column prop="envVersionSnapshot" :label="t('project.deployBatchVersion')" width="150" />
          <el-table-column :label="t('common.action')" width="170">
            <template #default="{ row }">
              <el-tag v-if="row.matched" size="small" type="success" data-test="deploy-batch-matched">
                {{ t('project.deployMatched') }}
              </el-tag>
              <el-tag v-else size="small" type="warning" data-test="deploy-batch-unmatched">
                {{ t('project.deployUnmatched') }}
              </el-tag>
            </template>
          </el-table-column>
        </DataTablePage>
      </div>
      <template #footer>
        <el-button @click="deployDialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="deploySaving" data-test="deploy-submit" @click="onDeploySave">
          {{ t('common.confirm') }}
        </el-button>
      </template>
    </el-dialog>

    
    <el-dialog v-model="memberDialogVisible" :title="t('project.addMember')" width="480px" destroy-on-close data-test="member-dialog">
      <el-form ref="memberFormRef" :model="memberForm" :rules="memberRules" label-width="90px" scroll-to-error>
        <el-form-item :label="t('project.memberUser')" prop="userId">
          <el-select
            v-model="memberForm.userId"
            :placeholder="t('project.memberUserSelectPlaceholder')"
            style="width: 100%"
            popper-class="tp-member-user-popper"
            data-test="member-user-select"
            filterable
            remote
            :remote-method="onMemberUserSearch"
            :loading="userSearching"
          >
            <el-option v-for="u in userOptions" :key="u.id" :label="`${u.displayName || u.username} (${u.username})`" :value="u.id" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('project.memberRole')" prop="role">
          <el-select v-model="memberForm.role" style="width: 100%" popper-class="tp-member-role-popper" data-test="member-role-select">
            <el-option :label="t('project.memberOptMember')" :value="1" />
            <el-option :label="t('project.memberOptOwner')" :value="2" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="memberDialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="memberSaving" data-test="member-submit" @click="onMemberSave">{{ t('common.confirm') }}</el-button>
      </template>
    </el-dialog>

    
    <el-dialog
      v-model="pluginApplyVisible"
      :title="t('project.pluginApplyBtn')"
      width="640px"
      destroy-on-close
      data-test="plugin-apply-dialog"
    >
      <el-form label-width="80px">
        <el-form-item :label="t('project.pluginCol')">
          <el-select
            v-model="pluginApplyId"
            :placeholder="t('project.pluginApplySelectPlaceholder')"
            filterable
            clearable
            :loading="pluginOptionsLoading"
            style="width: 100%"
            popper-class="tp-plugin-apply-popper"
            data-test="plugin-apply-select"
          >
            <el-option
              v-for="o in pluginOptions"
              :key="o.id"
              :label="`${o.name}（${o.industry || t('project.pluginIndustryGeneral')} · v${o.version}）`"
              :value="o.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="pluginApplyPreview" :label="t('project.pluginDescLabel')">
          <span class="text-secondary">{{ pluginDesc(pluginApplyPreview) }}</span>
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="pluginApplyConfirm" data-test="plugin-apply-confirm">
            {{ t('project.pluginApplyConfirmText') }}
          </el-checkbox>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pluginApplyVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="pluginApplying" data-test="plugin-apply-submit" @click="onPluginApply">
          {{ t('project.pluginApplySubmit') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- SKILL detail view drawer (row action on the SKILL tab): a read-only mirror
         of the market asset, shaped after the tool/skill list pages' detail drawer
         baseline (640px drawer + descriptions + Markdown body), plus this project's
         binding section (inject role / priority / enable / bound at).
         Read doors reuse the existing /skills/{id} + /skills/{id}/versions/{ver}
         (H1 and functional permission decided single-source in the backend; refusal
         wording passes straight through). Body = active-version snapshot (full text;
         the list view only carries a truncated preview). Zero decisions, zero writes. -->
    <el-drawer
      v-model="skillDetailVisible"
      :title="t('project.skillDetailTitle', { name: skillDetailName })"
      size="640px"
      destroy-on-close
      data-test="skill-detail-drawer"
    >
      <div v-loading="skillDetailLoading">
        <el-descriptions v-if="skillDetailMeta" :column="2" border data-test="skill-detail-desc">
          <el-descriptions-item :label="t('common.name')">{{ skillDetailMeta.name }}</el-descriptions-item>
          <el-descriptions-item :label="t('skill.type')">{{ skillDetailMeta.typeLabel }}</el-descriptions-item>
          <el-descriptions-item :label="t('common.status')">
            <el-tag :type="approvalStatusTag(skillDetailMeta.status)" size="small">{{ skillDetailMeta.statusLabel }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item :label="t('skill.colEffectiveVer')">v{{ skillDetailMeta.currentVersion }}</el-descriptions-item>
          <el-descriptions-item :label="t('tool.ownership')">
            {{ skillDetailMeta.projectId == null ? t('skill.systemLevel') : t('tool.projectRef', { id: skillDetailMeta.projectId }) }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('common.updatedAt')">{{ skillDetailMeta.updatedAt }}</el-descriptions-item>
        </el-descriptions>
        <div class="detail-section" data-test="skill-detail-binding">
          <div class="detail-section-title">{{ t('project.bindSectionTitle') }}</div>
          <el-descriptions v-if="skillDetailRow" :column="2" border>
            <el-descriptions-item :label="t('project.skillRoleCol')">{{ skillRoleLabel(skillDetailRow.role) }}</el-descriptions-item>
            <el-descriptions-item :label="t('project.skillPriorityCol')">{{ skillDetailRow.priority }}</el-descriptions-item>
            <el-descriptions-item :label="t('project.enabledCol')">
              <el-tag size="small" :type="skillDetailRow.enabled === 1 ? 'success' : 'info'">
                {{ skillDetailRow.enabled === 1 ? t('project.pluginEnabled') : t('project.pluginDisabled') }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item :label="t('project.skillBoundAtCol')">
              <span v-if="skillDetailRow.boundAt">{{ skillDetailRow.boundAt }}</span>
              <el-tag v-else size="small" type="warning">{{ t('project.colUnsaved') }}</el-tag>
            </el-descriptions-item>
          </el-descriptions>
        </div>
        <div v-if="skillDetailMeta?.frontmatterJson" class="detail-section" data-test="skill-detail-frontmatter">
          <div class="detail-section-title">{{ t('skill.frontmatterLabel') }}</div>
          <pre class="detail-json">{{ prettyJson(skillDetailMeta.frontmatterJson) }}</pre>
        </div>
        <div class="detail-section" data-test="skill-detail-body-section">
          <div class="detail-section-title">{{ t('skill.bodyLabel') }}</div>
          <MarkdownPreview
            v-if="skillDetailSnapshot?.body || skillDetailMeta?.bodyPreview"
            :source="skillDetailSnapshot?.body || skillDetailMeta?.bodyPreview || ''"
            data-test="skill-detail-body"
          />
          <div v-else class="text-secondary" data-test="skill-detail-body-empty">{{ t('project.detailBodyEmpty') }}</div>
        </div>
      </div>
    </el-drawer>

    <!-- Tool detail view drawer (row action on the tool tab): same posture as the
         SKILL door — full market asset details via /tools/{id}/detail (body/schema/
         execSpec, member-readable under H1) plus this project's enablement section.
         Executable tools show the execution config section; document tools carry
         none by contract. -->
    <el-drawer
      v-model="toolDetailVisible"
      :title="t('project.toolDetailTitle', { name: toolDetailName })"
      size="640px"
      destroy-on-close
      data-test="tool-detail-drawer"
    >
      <div v-loading="toolDetailLoading">
        <el-descriptions v-if="toolDetail" :column="2" border data-test="tool-detail-desc">
          <el-descriptions-item :label="t('common.name')">{{ toolDetail.name }}</el-descriptions-item>
          <el-descriptions-item :label="t('tool.type')">{{ toolDetail.typeLabel }}</el-descriptions-item>
          <el-descriptions-item :label="t('tool.category')">{{ toolDetail.category || '—' }}</el-descriptions-item>
          <el-descriptions-item :label="t('common.status')">
            <el-tag :type="approvalStatusTag(toolDetail.status)" size="small">{{ toolDetail.statusLabel }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item :label="t('tool.ownership')">
            {{ toolDetail.projectId == null ? t('tool.systemLevel') : t('tool.projectRef', { id: toolDetail.projectId }) }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('tool.currentVersion')">v{{ toolDetail.currentVersion }}</el-descriptions-item>
          <el-descriptions-item :label="t('tool.desc')" :span="2">{{ toolDetail.description || '—' }}</el-descriptions-item>
          <el-descriptions-item :label="t('tool.tags')" :span="2">{{ prettyJson(toolDetail.tagsJson) }}</el-descriptions-item>
        </el-descriptions>
        <div class="detail-section" data-test="tool-detail-binding">
          <div class="detail-section-title">{{ t('project.bindSectionTitle') }}</div>
          <el-descriptions v-if="toolDetailRow" :column="2" border>
            <el-descriptions-item :label="t('project.enabledCol')">
              <el-tag size="small" :type="toolDetailRow.enabled === 1 ? 'success' : 'info'">
                {{ toolDetailRow.enabled === 1 ? t('project.pluginEnabled') : t('project.pluginDisabled') }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item :label="t('project.toolEnabledAtCol')">
              <span v-if="toolDetailRow.boundAt">{{ toolDetailRow.boundAt }}</span>
              <el-tag v-else size="small" type="warning">{{ t('project.colUnsaved') }}</el-tag>
            </el-descriptions-item>
          </el-descriptions>
        </div>
        <div class="detail-section" data-test="tool-detail-body-section">
          <div class="detail-section-title">{{ toolDetail && toolDetail.type === 1 ? t('tool.bodyLabel') : t('tool.bodySectionContract') }}</div>
          <MarkdownPreview v-if="toolDetail?.body" :source="toolDetail.body" data-test="tool-detail-body" />
          <div v-else class="text-secondary" data-test="tool-detail-body-empty">{{ t('tool.noBody') }}</div>
        </div>
        <div class="detail-section" data-test="tool-detail-schema">
          <div class="detail-section-title">{{ t('tool.inputSchema') }}</div>
          <pre class="detail-json">{{ prettyJson(toolDetail?.inputSchemaJson) }}</pre>
        </div>
        <div class="detail-section" data-test="tool-detail-output-schema">
          <div class="detail-section-title">{{ t('tool.outputSchema') }}</div>
          <pre class="detail-json">{{ prettyJson(toolDetail?.outputSchemaJson) }}</pre>
        </div>
        <div v-if="toolDetail && toolDetail.type === 2" class="detail-section" data-test="tool-detail-exec">
          <div class="detail-section-title">
            {{ t('tool.execConfigTitle', { kind: toolDetail.execKindLabel || '—', ms: toolDetail.timeoutMs }) }}
          </div>
          <pre class="detail-json">{{ prettyJson(toolDetail.execSpecJson) }}</pre>
        </div>
      </div>
    </el-drawer>

    <LoginExemptDialog
      v-model="exemptVisible"
      :env-id="exemptEnvId"
      :env-name="exemptEnvName"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useGoBack } from '@/composables/useGoBack'
import { setBreadcrumbTitle } from '@/composables/useBreadcrumb'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { BizError } from '@/api/request'
import * as projectApi from '@/api/project'
import { envTypeLabel, envTypeTag, ENV_PARAM_MASKED_VALUE, ENV_PARAM_KEY_PATTERN } from '@/api/project'
import { checkEnvDelete } from '@/api/deletecheck'
import { guardedDelete } from '@/utils/deleteGuard'
import type { Project, ProjectEnv, ProjectMember, UserOption, EnvHealth } from '@/api/project'
import { getProjectLlmConfig, updateProjectLlmConfig, type LlmOption } from '@/api/llmConfig'
import {
  getProjectSkills,
  marketList,
  saveProjectSkills,
  type ProjectSkillItem,
  type SkillMarketItem,
} from '@/api/skill'
import { getSkill, getSkillVersion, type SkillAdminItem, type SkillVersionDetail } from '@/api/skillAdmin'
import {
  getProjectTools,
  saveProjectTools,
  getToolDetail,
  type ProjectToolItem,
  type ToolDetailItem,
} from '@/api/tool'
import { marketList as toolMarketList, type ToolMarketItem } from '@/api/toolMarket'
import { approvalStatusTag } from '@/utils/approvalTag'
import { prettyJson } from '@/utils/jsonPretty'
import MarkdownPreview from '@/components/MarkdownPreview.vue'
import {
  pluginApply,
  pluginList,
  pluginUninstall,
  projectAppliedPlugins,
  type AppliedPluginItem,
  type PluginItem,
} from '@/api/plugin'
import { getEnvDeployInfo, recordEnvDeploy, type EnvDeployInfo } from '@/api/project'
import { timeAgo } from '@/utils/timeAgo'
import { confirmStaleReload } from '@/utils/staleConflict'
import FavoriteButton from '@/components/FavoriteButton.vue'
import LoginExemptDialog from './LoginExemptDialog.vue'
import { recordVisit } from '@/composables/useRecentVisits'

const { t } = useI18n()
const route = useRoute()

const goBack = useGoBack('/projects')
const projectId = computed(() => Number(route.params.id))

const project = ref<Project | null>(null)
const activeTab = ref('envs')

const envs = ref<ProjectEnv[]>([])
const envLoading = ref(false)
const envDialogVisible = ref(false)
const envSaving = ref(false)
const envEditing = ref<ProjectEnv | null>(null)
const envFormRef = ref<FormInstance>()
const envForm = reactive({ name: '', envType: 1 as number, baseUrl: '', endTypes: '', browsers: '' })
const envRules: FormRules = {
  name: [{ required: true, message: t('project.envNameRequired'), trigger: 'blur' }],
  envType: [{ required: true, message: t('project.envTypeRequired'), trigger: 'change' }],
}

const paramsDialogVisible = ref(false)
const paramsLoading = ref(false)
const paramsSaving = ref(false)
const paramsTarget = ref<ProjectEnv | null>(null)

interface EnvParamRow {
  key: string
  value: string
  encrypted: boolean
}
const paramRows = ref<EnvParamRow[]>([])

const deployMap = ref<Record<number, EnvDeployInfo>>({})
const probingEnvId = ref<number | null>(null)
const healthMap = ref<Record<number, EnvHealth>>({})
const deployDialogVisible = ref(false)
const deploySaving = ref(false)
const deployTarget = ref<ProjectEnv | null>(null)
const deployEnvName = ref('')
const deployBatches = ref<EnvDeployInfo['batches']>([])
const deployFormRef = ref<FormInstance>()
const deployForm = reactive({ version: '' })
const deployRules: FormRules = {
  version: [{ required: true, message: t('project.deployVersionRequired'), trigger: 'blur' }],
}

const members = ref<ProjectMember[]>([])
const memberLoading = ref(false)
const memberDialogVisible = ref(false)
const memberSaving = ref(false)
const userOptions = ref<UserOption[]>([])
const userSearching = ref(false)
const memberFormRef = ref<FormInstance>()
const memberForm = reactive({ userId: undefined as number | undefined, role: 1 })
const memberRules: FormRules = {
  userId: [{ required: true, message: t('project.memberUserRequired'), trigger: 'change' }],
  role: [{ required: true, message: t('project.memberRoleRequired'), trigger: 'change' }],
}

const auth = useAuthStore()
const myProjectRole = computed(() => {
  if (auth.isAdmin) return 2
  const brief = auth.projects.find((p) => p.projectId === projectId.value)
  return brief?.role ?? 0
})
const canManageMembers = computed(() => myProjectRole.value >= 2)
const isLeaderRow = (row: ProjectMember) => String(row.role) === '2' || row.role === 'TEST_LEADER'

const ownerCount = computed(() => members.value.filter(isLeaderRow).length)

const STORAGE_MODE_LABELS = computed<Record<number, string>>(() => ({ 1: t('project.infoSystemDefault'), 2: t('project.storageModeLocal'), 3: t('project.storageModeCloud') }))
const storageModeLabel = computed(
  () => STORAGE_MODE_LABELS.value[project.value?.storageMode ?? 1] ?? t('project.infoSystemDefault'),
)
function ownerLabel(userId?: number | null): string {
  if (userId == null) return t('project.infoNotSet')
  const row = members.value.find((m) => m.userId === userId)
  if (!row) return t('project.ownerNotMember', { id: userId })
  return row.nickName ? `${row.nickName}（${row.username}）` : row.username
}

async function onSetOwner(row: ProjectMember): Promise<void> {
  const ok = await ElMessageBox.confirm(
    t('project.memberSetOwnerConfirm', { name: row.nickName || row.username }),
    t('common.warning'),
    { confirmButtonText: t('common.confirm'), cancelButtonText: t('common.cancel'), type: 'warning' },
  ).catch(() => false)
  if (!ok) return
  try {
    await projectApi.updateMemberRole(projectId.value, row.userId, 2)
    ElMessage.success(t('project.memberSetOwnerSuccess', { name: row.nickName || row.username }))
    await loadMembers()
  } catch (err: any) {
    if (!(err instanceof BizError)) throw err
  }
}

async function onDemoteOwner(row: ProjectMember): Promise<void> {
  const ok = await ElMessageBox.confirm(
    t('project.memberDemoteOwnerConfirm', { name: row.nickName || row.username }),
    t('common.warning'),
    { confirmButtonText: t('common.confirm'), cancelButtonText: t('common.cancel'), type: 'warning' },
  ).catch(() => false)
  if (!ok) return
  try {
    await projectApi.updateMemberRole(projectId.value, row.userId, 1)
    ElMessage.success(t('project.memberDemoteOwnerSuccess', { name: row.nickName || row.username }))
    await loadMembers()
  } catch (err: any) {
    if (!(err instanceof BizError)) throw err
  }
}

const llmLoading = ref(false)
const llmSaving = ref(false)
const llmConfigId = ref<number | null>(null)
const llmConfigName = ref<string | null>(null)
const llmOptions = ref<LlmOption[]>([])

async function loadLlmConfig(): Promise<void> {
  llmLoading.value = true
  try {
    const view = await getProjectLlmConfig(projectId.value)
    llmConfigId.value = view.llmConfigId
    llmConfigName.value = view.llmConfigName
    llmOptions.value = view.options || []
  } catch (err: any) {
    if (err instanceof BizError) {
      llmOptions.value = []
    } else {
      throw err
    }
  } finally {
    llmLoading.value = false
  }
}

async function onLlmSave(): Promise<void> {
  llmSaving.value = true
  try {
    const view = await updateProjectLlmConfig(projectId.value, llmConfigId.value)
    llmConfigId.value = view.llmConfigId
    llmConfigName.value = view.llmConfigName
    llmOptions.value = view.options || []
    ElMessage.success(llmConfigId.value ? t('project.llmSaveSuccess') : t('project.llmResetDefaultSuccess'))
  } catch (err: any) {
    ElMessage.error(err?.msg || err?.message || t('project.saveFailed'))
  } finally {
    llmSaving.value = false
  }
}



const PROJECT_SKILL_ROLES = computed(() => [
  { value: 1, label: t('project.skillRoleScript') },
  { value: 2, label: t('project.skillRoleTest') },
  { value: 3, label: t('project.skillRoleGeneral') },
])

/** Read-only role label for the detail drawer (unknown codes render '—', never guessed). */
function skillRoleLabel(role: number): string {
  return PROJECT_SKILL_ROLES.value.find((r) => r.value === role)?.label || '—'
}

const skillLoading = ref(false)
const skillSaving = ref(false)
const skillBindings = ref<ProjectSkillItem[]>([])
/** Baseline signature of the persisted set (refreshed on load/save). Save is only
 * meaningful when the current set differs — a no-change save would rewrite row
 * timestamps and claim "saved" without any fact changing (misleading the user). */
const skillBaselineSig = ref('')
/** Canonical set signature (skillId+role+priority+enabled, order-insensitive). */
function skillSignature(rows: ProjectSkillItem[]): string {
  return rows.map((r) => `${r.skillId}:${r.role}:${r.priority}:${r.enabled}`).sort().join('|')
}
const skillDirty = computed(() => skillSignature(skillBindings.value) !== skillBaselineSig.value)

const skillVersion = ref<number>(0)
const skillAddId = ref<number | null>(null)
const skillOptions = ref<SkillMarketItem[]>([])
const skillOptionsLoading = ref(false)

async function loadSkillConfig(): Promise<void> {
  if (!canManageMembers.value) {
    skillBindings.value = []
    skillBaselineSig.value = ''
    skillVersion.value = 0
    skillLoading.value = false
    return
  }
  skillLoading.value = true
  try {
    const view = await getProjectSkills(projectId.value)
    skillBindings.value = view.list || []
    skillBaselineSig.value = skillSignature(skillBindings.value)
    skillVersion.value = view.version
    // Remote selects only fire their method on typing, so the first dropdown open
    // would show "no data" even when the market has published skills. Preload the
    // default page after bindings land so the add door is usable without typing.
    onSkillSearch('')
  } catch (err: any) {
    skillBindings.value = []
    skillBaselineSig.value = ''
    skillVersion.value = 0
    ElMessage.error(err?.msg || err?.message || t('project.skillLoadFail'))
  } finally {
    skillLoading.value = false
  }
}


async function onSkillSearch(keyword: string): Promise<void> {
  skillOptionsLoading.value = true
  try {
    const page = await marketList({ page: 1, size: 20, keyword: keyword || undefined })
    const bound = new Set(skillBindings.value.map((b) => b.skillId))
    skillOptions.value = (page.list || []).filter((s) => !bound.has(s.id))
  } catch {
    skillOptions.value = []
  } finally {
    skillOptionsLoading.value = false
  }
}

function onSkillAdd(id: number | null): void {
  if (id == null) return
  const picked = skillOptions.value.find((s) => s.id === id)
  if (picked && !skillBindings.value.some((b) => b.skillId === id)) {
    skillBindings.value.push({
      skillId: picked.id,
      role: 3,
      priority: 0,
      enabled: 1,
      boundAt: null,
      name: picked.name,
      typeLabel: picked.typeLabel,
    })
  }
  skillAddId.value = null
  skillOptions.value = skillOptions.value.filter((s) => s.id !== id)
}

function onSkillUnbind(skillId: number): void {
  skillBindings.value = skillBindings.value.filter((b) => b.skillId !== skillId)
}

async function onSkillSave(): Promise<void> {
  skillSaving.value = true
  try {
    const view = await saveProjectSkills(
      projectId.value,
      skillBindings.value.map((b) => ({ skillId: b.skillId, role: b.role, priority: b.priority, enabled: b.enabled })),
      skillVersion.value,
    )
    skillBindings.value = view.list || []
    skillBaselineSig.value = skillSignature(skillBindings.value)
    skillVersion.value = view.version
    ElMessage.success(t('project.skillSaveSuccess'))
  } catch (err: any) {
    if (await confirmStaleReload(err, t('project.skillStale'), loadSkillConfig)) {
      return
    }
    ElMessage.error(err?.msg || err?.message || t('project.saveFailed'))
  } finally {
    skillSaving.value = false
  }
}

// ---- SKILL/tool row-action detail drawers (read-only mirror, zero decisions/writes) ----
// Data source = existing market detail doors (/skills/{id}+/{id}/versions/{ver},
// /tools/{id}/detail). H1 and functional permissions stay single-source in the
// backend; this layer must not copy any ownership/publish verdict.
// Body prefers the active-version snapshot (full text); on snapshot failure it
// falls back to the list view's truncated preview — visible truncation beats an
// empty section, and the placeholder only renders when both are missing.

const skillDetailVisible = ref(false)
const skillDetailLoading = ref(false)
const skillDetailName = ref('')
const skillDetailMeta = ref<SkillAdminItem | null>(null)
const skillDetailSnapshot = ref<SkillVersionDetail | null>(null)
const skillDetailRow = ref<ProjectSkillItem | null>(null)

async function openSkillDetail(row: ProjectSkillItem): Promise<void> {
  skillDetailRow.value = row
  skillDetailName.value = row.name || ''
  skillDetailMeta.value = null
  skillDetailSnapshot.value = null
  skillDetailVisible.value = true
  skillDetailLoading.value = true
  try {
    const meta = await getSkill(row.skillId)
    skillDetailMeta.value = meta
    skillDetailName.value = meta.name
    // Full body lives in the active-version snapshot; preview fallback stays honest
    // (truncated text beats an empty section when the snapshot door fails).
    try {
      skillDetailSnapshot.value = await getSkillVersion(meta.id, meta.currentVersion)
    } catch {
      skillDetailSnapshot.value = null
    }
  } catch (err: any) {
    ElMessage.error(err?.msg || err?.message || t('project.detailLoadFail'))
  } finally {
    skillDetailLoading.value = false
  }
}

const toolDetailVisible = ref(false)
const toolDetailLoading = ref(false)
const toolDetailName = ref('')
const toolDetail = ref<ToolDetailItem | null>(null)
const toolDetailRow = ref<ProjectToolItem | null>(null)

async function openToolDetail(row: ProjectToolItem): Promise<void> {
  toolDetailRow.value = row
  toolDetailName.value = row.name || ''
  toolDetail.value = null
  toolDetailVisible.value = true
  toolDetailLoading.value = true
  try {
    toolDetail.value = await getToolDetail(row.toolId)
    toolDetailName.value = toolDetail.value.name
  } catch (err: any) {
    ElMessage.error(err?.msg || err?.message || t('project.detailLoadFail'))
  } finally {
    toolDetailLoading.value = false
  }
}


const toolLoading = ref(false)
const toolSaving = ref(false)
const toolBindings = ref<ProjectToolItem[]>([])
/** Baseline signature of the persisted enablement set (skill tab same posture):
 * the save door only opens when something actually changed. */
const toolBaselineSig = ref('')
/** Canonical set signature (toolId+enabled, order-insensitive). */
function toolSignature(rows: ProjectToolItem[]): string {
  return rows.map((r) => `${r.toolId}:${r.enabled}`).sort().join('|')
}
const toolDirty = computed(() => toolSignature(toolBindings.value) !== toolBaselineSig.value)

const toolVersion = ref<number>(0)
const toolAddId = ref<number | null>(null)
const toolOptions = ref<ToolMarketItem[]>([])
const toolOptionsLoading = ref(false)

async function loadToolConfig(): Promise<void> {
  if (!canManageMembers.value) {
    toolBindings.value = []
    toolBaselineSig.value = ''
    toolVersion.value = 0
    toolLoading.value = false
    return
  }
  toolLoading.value = true
  try {
    const view = await getProjectTools(projectId.value)
    toolBindings.value = view.list || []
    toolBaselineSig.value = toolSignature(toolBindings.value)
    toolVersion.value = view.version
    // Same remote-select posture as the SKILL door: preload the default market
    // page so opening the dropdown without typing already shows candidates.
    onToolSearch('')
  } catch (err: any) {
    toolBindings.value = []
    toolBaselineSig.value = ''
    toolVersion.value = 0
    ElMessage.error(err?.msg || err?.message || t('project.toolLoadFail'))
  } finally {
    toolLoading.value = false
  }
}


async function onToolSearch(keyword: string): Promise<void> {
  toolOptionsLoading.value = true
  try {
    const page = await toolMarketList({ page: 1, size: 20, keyword: keyword || undefined })
    const bound = new Set(toolBindings.value.map((b) => b.toolId))
    toolOptions.value = (page.list || []).filter((s) => !bound.has(s.id))
  } catch {
    toolOptions.value = []
  } finally {
    toolOptionsLoading.value = false
  }
}

function onToolAdd(id: number | null): void {
  if (id == null) return
  const picked = toolOptions.value.find((s) => s.id === id)
  if (picked && !toolBindings.value.some((b) => b.toolId === id)) {
    toolBindings.value.push({
      toolId: picked.id,
      enabled: 1,
      boundAt: null,
      name: picked.name,
      type: picked.type,
      typeLabel: picked.typeLabel,
    })
  }
  toolAddId.value = null
  toolOptions.value = toolOptions.value.filter((s) => s.id !== id)
}

function onToolUnbind(toolId: number): void {
  toolBindings.value = toolBindings.value.filter((b) => b.toolId !== toolId)
}

async function onToolSave(): Promise<void> {
  toolSaving.value = true
  try {
    const view = await saveProjectTools(
      projectId.value,
      toolBindings.value.map((b) => ({ toolId: b.toolId, enabled: b.enabled })),
      toolVersion.value,
    )
    toolBindings.value = view.list || []
    toolBaselineSig.value = toolSignature(toolBindings.value)
    toolVersion.value = view.version
    ElMessage.success(t('project.toolSaveSuccess'))
  } catch (err: any) {
    if (await confirmStaleReload(err, t('project.toolStale'), loadToolConfig)) {
      return
    }
    ElMessage.error(err?.msg || err?.message || t('project.saveFailed'))
  } finally {
    toolSaving.value = false
  }
}

const pluginLoading = ref(false)
const appliedPlugins = ref<AppliedPluginItem[]>([])

async function loadAppliedPlugins(): Promise<void> {
  pluginLoading.value = true
  try {
    appliedPlugins.value = (await projectAppliedPlugins(projectId.value)) || []
  } catch (e) {
    appliedPlugins.value = []
    ElMessage.error((e as Error).message || t('project.pluginAppliedLoadFail'))
  } finally {
    pluginLoading.value = false
  }
}


function snapshotText(row: AppliedPluginItem): string {
  const snap = row.paramSnapshot
  if (!snap || Object.keys(snap).length === 0) return '—'
  try {
    return JSON.stringify(snap)
  } catch {
    return '—'
  }
}

function pluginDesc(p: PluginItem): string {
  const desc = p.manifest?.description
  return typeof desc === 'string' && desc ? desc : '—'
}

const pluginApplyVisible = ref(false)
const pluginApplying = ref(false)
const pluginApplyId = ref<number | null>(null)
const pluginApplyConfirm = ref(false)
const pluginOptionsLoading = ref(false)
const pluginOptions = ref<PluginItem[]>([])
const pluginApplyPreview = computed(() => pluginOptions.value.find((o) => o.id === pluginApplyId.value) ?? null)

async function openPluginApply(): Promise<void> {
  pluginApplyId.value = null
  pluginApplyConfirm.value = false
  pluginApplyVisible.value = true
  pluginOptionsLoading.value = true
  try {
    const all = (await pluginList({ enabled: true })) || []
    const applied = new Set(appliedPlugins.value.map((a) => a.pluginId))
    pluginOptions.value = all.filter((p) => !applied.has(p.id))
  } catch (e) {
    pluginOptions.value = []
    ElMessage.error((e as Error).message || t('project.pluginListLoadFail'))
  } finally {
    pluginOptionsLoading.value = false
  }
}

async function onPluginApply(): Promise<void> {
  if (pluginApplyId.value == null) {
    ElMessage.warning(t('project.pluginPickFirst'))
    return
  }
  pluginApplying.value = true
  try {
    const view = await pluginApply(projectId.value, pluginApplyId.value, pluginApplyConfirm.value)
    for (const note of view.notes || []) {
      ElMessage.warning(note)
    }
    ElMessage.success(t('project.pluginApplySuccess', { name: view.pluginName, n: view.mountedSkills?.length ?? 0 }))
    pluginApplyVisible.value = false
    await loadAppliedPlugins()
  } catch (e) {
    ElMessage.error((e as Error).message || t('project.pluginApplyFail'))
  } finally {
    pluginApplying.value = false
  }
}

async function onPluginUninstall(row: AppliedPluginItem): Promise<void> {
  try {
    await ElMessageBox.confirm(
      t('project.pluginUninstallConfirm', { name: row.pluginName }),
      t('project.pluginUninstallTitle'),
      { type: 'warning', confirmButtonText: t('project.pluginUninstall'), cancelButtonText: t('common.cancel') },
    )
  } catch {
    return
  }
  try {
    await pluginUninstall(projectId.value, row.pluginId)
    ElMessage.success(t('project.pluginUninstallSuccess', { name: row.pluginName }))
    await loadAppliedPlugins()
  } catch (e) {
    ElMessage.error((e as Error).message || t('project.pluginUninstallFail'))
  }
}

function roleTag(role: number | string): 'primary' | 'warning' | 'info' {
  const r = typeof role === 'string' ? role : String(role)
  if (r === '2' || r === 'TEST_LEADER') return 'warning'
  if (r === '1' || r === 'TESTER') return 'primary'
  return 'info'
}

async function loadProject(): Promise<void> {
  project.value = await projectApi.getProject(projectId.value)
  setBreadcrumbTitle('ProjectDetail', project.value?.name ?? null)
  if (project.value) recordVisit('project', projectId.value, project.value.name)
}

async function loadEnvs(): Promise<void> {
  envLoading.value = true
  try {
    envs.value = (await projectApi.getProjectEnvs(projectId.value)) || []
    await Promise.all([loadDeployInfos(), loadCertHealth()])
  } finally {
    envLoading.value = false
  }
}


async function loadCertHealth(): Promise<void> {
  const map: Record<number, EnvHealth> = {}
  await Promise.all(
    envs.value.map(async (env) => {
      try {
        const hv = await projectApi.getEnvHealth(projectId.value, env.id)
        if (hv) map[env.id] = hv
      } catch {
      }
    }),
  )
  healthMap.value = map
}


async function loadDeployInfos(): Promise<void> {
  const map: Record<number, EnvDeployInfo> = {}
  await Promise.all(
    envs.value.map(async (env) => {
      try {
        map[env.id] = await getEnvDeployInfo(env.id)
      } catch {
      }
    }),
  )
  deployMap.value = map
}


async function onProbeHealth(row: ProjectEnv): Promise<void> {
  probingEnvId.value = row.id
  try {
    const outcome = await projectApi.probeEnvHealth(projectId.value, row.id)
    if (outcome.healthy) {
      ElMessage.success(t('project.healthProbeSuccess', { name: row.name }))
    } else {
      ElMessage.warning(t('project.healthProbeWarn', { name: row.name, msg: outcome.msg || t('project.healthProbeNoReason') }))
    }
    await loadEnvs()
  } catch (e: unknown) {
    ElMessage.error(e instanceof BizError ? e.message : t('project.healthProbeFail'))
  } finally {
    probingEnvId.value = null
  }
}

// Login exemption (AV5): one dialog per environment row, three server doors only.
const exemptVisible = ref(false)
const exemptEnvId = ref<number | null>(null)
const exemptEnvName = ref('')

function onEnvRowCmd(key: string, row: ProjectEnv): void {
  if (key === "deploy") openDeployDialog(row)
  else if (key === "edit") openEnvDialog(row)
  else if (key === "params") openEnvParams(row)
  else if (key === "exempt") {
    exemptEnvId.value = row.id
    exemptEnvName.value = row.name
    exemptVisible.value = true
  } else if (key === "delete") void onDeleteEnv(row)
}

async function loadMembers(): Promise<void> {
  memberLoading.value = true
  try {
    members.value = (await projectApi.getProjectMembers(projectId.value)) || []
  } finally {
    memberLoading.value = false
  }
}

function openEnvDialog(row?: ProjectEnv): void {
  envEditing.value = row || null
  envForm.name = row?.name || ''
  envForm.envType = row?.envType || 1
  envForm.baseUrl = row?.baseUrl || ''
  envForm.endTypes = row?.endTypes || ''
  envForm.browsers = row?.browsers || ''
  envDialogVisible.value = true
}



const MASKED_VALUE = ENV_PARAM_MASKED_VALUE


async function openEnvParams(row: ProjectEnv): Promise<void> {
  paramsTarget.value = row
  paramRows.value = []
  paramsDialogVisible.value = true
  paramsLoading.value = true
  try {
    const items = await projectApi.listEnvParams(projectId.value, row.id)
    paramRows.value = (items || []).map((p) => ({ key: p.key, value: p.value, encrypted: p.encrypted }))
  } catch (err: any) {
    if (!(err instanceof BizError)) throw err
    paramsDialogVisible.value = false
  } finally {
    paramsLoading.value = false
  }
}


function onOverwriteEncrypted(row: EnvParamRow): void {
  row.value = ''
  row.encrypted = false
}


async function onEnvParamsSave(): Promise<void> {
  const invalidKeys = paramRows.value
    .map((r) => r.key.trim())
    .filter((k) => k && (!ENV_PARAM_KEY_PATTERN.test(k) || k.length > 64))
  if (invalidKeys.length) {
    ElMessage.warning(t('project.envParamKeyInvalid', { keys: invalidKeys.join(t('project.listSeparator')) }))
    return
  }
  const blankKey = paramRows.value.some((r) => !r.key.trim())
  if (blankKey) {
    ElMessage.warning(t('project.envParamKeyMissing'))
    return
  }
  const params: Record<string, string> = {}
  for (const row of paramRows.value) {
    const key = row.key.trim()
    const value = row.value.trim()
    if (params[key] !== undefined) {
      ElMessage.warning(t('project.envParamKeyDup', { key }))
      return
    }
    if (!value) {
      ElMessage.warning(t('project.envParamValueEmpty', { key }))
      return
    }
    params[key] = value
  }
  if (!paramsTarget.value) return
  paramsSaving.value = true
  try {
    const items = await projectApi.saveEnvParams(projectId.value, paramsTarget.value.id, params)
    paramRows.value = (items || []).map((p) => ({ key: p.key, value: p.value, encrypted: p.encrypted }))
    ElMessage.success(t('project.envParamsSaveSuccess'))
  } catch (err: any) {
    if (!(err instanceof BizError)) throw err
  } finally {
    paramsSaving.value = false
  }
}


function openDeployDialog(row: ProjectEnv): void {
  deployTarget.value = row
  deployEnvName.value = row.name
  const info = deployMap.value[row.id]
  deployForm.version = info?.deployedVersion || ''
  deployBatches.value = info?.batches || []
  deployDialogVisible.value = true
}


async function onDeploySave(): Promise<void> {
  if (!deployFormRef.value || !deployTarget.value) return
  const valid = await deployFormRef.value.validate().catch(() => false)
  if (!valid) return
  deploySaving.value = true
  try {
    const info = await recordEnvDeploy(deployTarget.value.id, deployForm.version.trim())
    deployMap.value = { ...deployMap.value, [deployTarget.value.id]: info }
    deployBatches.value = info.batches || []
    ElMessage.success(t('project.deploySuccess'))
  } catch (e) {
    const msg = e instanceof BizError ? e.message : t('project.deploySaveFail')
    ElMessage.error(msg)
  } finally {
    deploySaving.value = false
  }
}

async function onEnvSave(): Promise<void> {
  if (!envFormRef.value) return
  if (envForm.envType === 4 && !envForm.baseUrl.trim()) {
    ElMessage.warning(t('project.prodBaseUrlRequired'))
    return
  }
  const valid = await envFormRef.value.validate().catch(() => false)
  if (!valid) return
  envSaving.value = true
  try {
    const payload: projectApi.EnvPayload = {
      name: envForm.name,
      envType: envForm.envType,
      baseUrl: envForm.baseUrl || undefined,
      endTypes: envForm.endTypes || undefined,
      browsers: envForm.browsers || undefined,
    }
    if (envEditing.value) {
      const fresh = (await projectApi.getProjectEnvs(projectId.value)).find((e) => e.id === envEditing.value!.id)
      payload.configJson = fresh?.configJson ?? undefined
      await projectApi.updateProjectEnv(projectId.value, envEditing.value.id, payload)
      ElMessage.success(t('project.envUpdateSuccess'))
    } else {
      await projectApi.createProjectEnv(projectId.value, payload)
      ElMessage.success(t('project.envCreateSuccess'))
    }
    envDialogVisible.value = false
    await loadEnvs()
  } catch (e) {
    const msg = e instanceof BizError ? e.message : t('project.saveFailRetry')
    ElMessage.error(msg)
  } finally {
    envSaving.value = false
  }
}

async function onDeleteEnv(row: ProjectEnv): Promise<void> {
  const ok = await guardedDelete({
    check: () => checkEnvDelete(row.id),
    doDelete: (force) => projectApi.deleteProjectEnv(projectId.value, row.id, force),
    entityName: t('project.envEntityName', { name: row.name }),
    forceHint: t('project.envDeleteForceHint'),
  })
  if (ok) {
    ElMessage.success(t('project.envDeleteSuccess'))
    await loadEnvs()
  }
}

function openMemberDialog(): void {
  memberForm.userId = undefined
  memberForm.role = 1
  userOptions.value = []
  memberDialogVisible.value = true
  userSearching.value = true
  projectApi
    .getUsers({ page: 1, size: 100 })
    .then((result) => {
      userOptions.value = result.list || []
    })
    .finally(() => {
      userSearching.value = false
    })
}

function onMemberUserSearch(keyword: string): void {
  userSearching.value = true
  projectApi
    .getUsers({ page: 1, size: 100, keyword: keyword.trim() || undefined })
    .then((result) => {
      userOptions.value = result.list || []
    })
    .finally(() => {
      userSearching.value = false
    })
}

async function onMemberSave(): Promise<void> {
  if (!memberFormRef.value) return
  const valid = await memberFormRef.value.validate().catch(() => false)
  if (!valid) return
  memberSaving.value = true
  try {
    await projectApi.addProjectMember(projectId.value, { userId: memberForm.userId!, role: memberForm.role })
    ElMessage.success(t('project.memberAddSuccess'))
    memberDialogVisible.value = false
    await loadMembers()
  } finally {
    memberSaving.value = false
  }
}

async function onDeleteMember(row: ProjectMember): Promise<void> {
  await ElMessageBox.confirm(t('project.memberDeleteConfirm', { name: row.nickName || row.username }), t('common.warning'), {
    confirmButtonText: t('common.delete'),
    cancelButtonText: t('common.cancel'),
    type: 'warning',
  })
  await projectApi.deleteProjectMember(projectId.value, row.userId)
  ElMessage.success(t('project.memberDeleteSuccess'))
  await loadMembers()
}

onMounted(async () => {
  await loadProject()
  await Promise.all([loadEnvs(), loadMembers(), loadLlmConfig(), loadSkillConfig(), loadToolConfig(), loadAppliedPlugins()])
})

watch(
  () => route.params.id,
  async () => {
    project.value = null
    envs.value = []
    members.value = []
    deployMap.value = {}
    skillBindings.value = []
    skillBaselineSig.value = ''
    skillVersion.value = 0
    toolBindings.value = []
    toolBaselineSig.value = ''
    toolVersion.value = 0
    appliedPlugins.value = []
    await loadProject()
    await Promise.all([loadEnvs(), loadMembers(), loadLlmConfig(), loadSkillConfig(), loadToolConfig(), loadAppliedPlugins()])
  },
)

onBeforeUnmount(() => {
  setBreadcrumbTitle('ProjectDetail', null)
})
</script>

<style scoped lang="scss">
.back-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

/* Detail drawer sections (read-only view doors on the SKILL/tool tabs).
   Class names follow the tool/skill list pages' existing markup; those pages
   never actually styled them, so the definitions live here as page-scoped
   until a second consumer warrants lifting them to the global theme. */
.detail-section {
  margin-top: 16px;
}
.detail-section-title {
  font-size: 14px;
  font-weight: 500;
  color: var(--tp-text-primary);
  margin-bottom: 8px;
}
.detail-json {
  margin: 0;
  padding: 10px 12px;
  border: 1px solid var(--tp-border-color, #e8eaef);
  border-radius: 8px;
  background: var(--tp-fill-color-light, rgba(127, 127, 127, 0.06));
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 320px;
  overflow-y: auto;
}

.detail-title {
  font-size: 16px;
  font-weight: 600;
}

.detail-tabs :deep(.el-tabs__header) {
  margin-bottom: 12px;
}

.tab-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
  gap: 12px;
  flex-wrap: wrap;
}
.skill-toolbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
}
.skill-bind-name {
  font-weight: 600;
}
.skill-bind-type {
  margin-left: 6px;
}

.llm-form {
}

.llm-current {
  margin-left: 12px;
  font-size: 13px;
  color: var(--el-color-primary);
}

.muted {
  color: var(--el-text-color-placeholder);
}

.deploy-sub {
  font-size: 12px;
  line-height: 1.5;
  margin-top: 2px;
}

.cert-card-line {
  display: flex;
  align-items: center;
  margin-top: 2px;
}
.cert-lock {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  cursor: default;
}
.cert-text {
  font-size: 12px;
  line-height: 1.5;
  margin-left: 4px;
}
.cert-text-ok {
  color: var(--el-color-success);
}
.cert-text-warn {
  color: var(--el-color-warning);
}
.cert-text-unknown {
  color: var(--el-text-color-secondary);
}
.cert-insecure-badge {
  margin-left: 8px;
}

.deploy-env-name {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  font-weight: 600;
}

.deploy-batch-block {
  margin-top: 12px;
}

.deploy-batch-title {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 8px;
  color: var(--el-text-color-regular);
}


.params-hint {
  margin-bottom: 12px;
}

.params-empty {
  padding: 16px 0;
  font-size: 13px;
}

.params-add {
  margin-top: 10px;
}

.masked-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}

.masked-text {
  font-family: var(--el-font-family-mono, monospace);
  letter-spacing: 2px;
  color: var(--el-text-color-regular);
}



</style>
