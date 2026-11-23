package com.sixbean.testpike.admin.aichat.app.service.impl;

import com.sixbean.testpike.admin.aichat.api.dto.EvolutionProposalsMirrorView;
import com.sixbean.testpike.admin.aichat.api.dto.EvolutionRollbackMirrorView;
import com.sixbean.testpike.admin.aichat.app.service.EvolutionProposalAdminService;
import com.sixbean.testpike.admin.aichat.infrastructure.remote.client.CoreAiChatClient;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.stereotype.Service;

/**
 * Permission gate + pass-through for the manual self-evolution proposal run.
 * <p>Zero-judgment principle (same door family as the observation rollup):
 * a project's manager (or ADMIN through the shared guard) may fire a run for
 * that segment; anything else is refused before core is touched, because the
 * run writes pending cards. The operator id is pinned from the login context
 * — the caller's body/query never chooses who owns the session (AF-g
 * horizontal line). A missing project is refused here with a sentence naming
 * what to pick, instead of letting core's refusal read like a server fault.
 */
@Service
public class EvolutionProposalAdminServiceImpl implements EvolutionProposalAdminService {

    private final CoreAiChatClient coreClient;
    private final ProjectAccessGuard guard;

    public EvolutionProposalAdminServiceImpl(CoreAiChatClient coreClient, ProjectAccessGuard guard) {
        this.coreClient = coreClient;
        this.guard = guard;
    }

    @Override
    public EvolutionProposalsMirrorView generate(CurrentUser user, String month, Long projectId) {
        if (projectId == null) {
            throw new ServiceException("自进化提案需要选定项目：请先在顶栏选择项目，或请求里带上 projectId",
                    BizErrorCode.VALIDATION_ERROR);
        }
        guard.assertCanManage(user, projectId);
        return coreClient.generateEvolutionProposals(month, projectId, user.userId());
    }

    /**
     * Rollback pass-through (AF-i phase 3): operator pinned from the login
     * context, zero judgment added — card kind, applied state, ownership,
     * once-only mark and family version chain are core's single-sourced guards
     * (re-checking membership here would be a second spectrum, and the card
     * may legitimately live on a project the clicker no longer manages but
     * still owns the session of).
     */
    @Override
    public EvolutionRollbackMirrorView rollback(CurrentUser user, long draftId) {
        return coreClient.rollbackEvolutionProposal(draftId, user.userId());
    }
}
