package com.sixbean.testpike.admin.cost.app.service.impl;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.cost.api.dto.BreakOverrideInput;
import com.sixbean.testpike.admin.cost.api.dto.BreakOverrideView;
import com.sixbean.testpike.admin.cost.api.dto.BudgetInput;
import com.sixbean.testpike.admin.cost.api.dto.BudgetView;
import com.sixbean.testpike.admin.cost.api.dto.CostOverviewView;
import com.sixbean.testpike.admin.cost.app.service.CostAdminService;
import com.sixbean.testpike.admin.cost.infrastructure.remote.client.CoreCostClient;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.stereotype.Service;


@Service
public class CostAdminServiceImpl implements CostAdminService {

    private final CoreCostClient coreCostClient;
    private final com.sixbean.testpike.admin.aichat.infrastructure.remote.client.CoreAiChatClient chatClient;
    private final ProjectAccessGuard guard;

    public CostAdminServiceImpl(CoreCostClient coreCostClient,
                                com.sixbean.testpike.admin.aichat.infrastructure.remote.client.CoreAiChatClient chatClient,
                                ProjectAccessGuard guard) {
        this.coreCostClient = coreCostClient;
        this.chatClient = chatClient;
        this.guard = guard;
    }

    @Override
    public CostOverviewView overview(CurrentUser user, String month, Long projectId) {
        if (projectId == null) {
            if (!guard.isAdmin(user)) {
                throw new ServiceException("全局成本聚合仅系统管理员可查看", BizErrorCode.FORBIDDEN);
            }
        } else {
            guard.assertCanManage(user, projectId);
        }
        return coreCostClient.overview(month, projectId);
    }

    @Override
    public com.sixbean.testpike.admin.aichat.api.dto.ChatSessionUsagePageMirrorView sessionUsage(
            CurrentUser user, String month, Long projectId, int page, int pageSize) {
        // Permission gate mirrors overview (the same E3 posture, not a new spectrum):
        // global = ADMIN only, a named segment = its manager. Core re-validates the
        // month shape and paging bounds fail-closed; this layer keeps zero judgments.
        if (projectId == null) {
            if (!guard.isAdmin(user)) {
                throw new ServiceException("全局会话归因仅系统管理员可查看", BizErrorCode.FORBIDDEN);
            }
        } else {
            guard.assertCanManage(user, projectId);
        }
        return chatClient.usageAttribution(month, projectId, page, pageSize);
    }

    @Override
    public BudgetView getBudget(CurrentUser user, long projectId) {
        guard.assertCanManage(user, projectId);
        return coreCostClient.getBudget(projectId);
    }

    @Override
    public BudgetView putBudget(CurrentUser user, long projectId, BudgetInput input) {
        guard.assertCanManage(user, projectId);
        return coreCostClient.putBudget(user.userId(), projectId, input);
    }

    @Override
    public BreakOverrideView breakOverride(CurrentUser user, long projectId, BreakOverrideInput input) {
        if (!guard.isAdmin(user)) {
            throw new ServiceException("熔断临时恢复仅系统管理员可操作", BizErrorCode.FORBIDDEN);
        }
        return coreCostClient.breakOverride(user.userId(), projectId, input);
    }
}
