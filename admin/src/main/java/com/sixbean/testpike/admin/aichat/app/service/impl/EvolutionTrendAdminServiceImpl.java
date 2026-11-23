package com.sixbean.testpike.admin.aichat.app.service.impl;

import com.sixbean.testpike.admin.aichat.api.dto.EvolutionTrendMirrorView;
import com.sixbean.testpike.admin.aichat.app.service.EvolutionTrendAdminService;
import com.sixbean.testpike.admin.aichat.infrastructure.remote.client.CoreAiChatClient;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.stereotype.Service;

/**
 * Permission gate + pass-through for the evolution trend report (AF-i phase 4).
 * <p>Zero-judgment principle (same as the signals bridge it pairs with): the
 * month shape, the window completeness and the ineffective mark are decided
 * fail-closed in core — this class only decides who may look. The stance is
 * the proposal-run door's own sentence family: cards live in a project's
 * evolution session, so a missing segment is refused 40001 with a sentence
 * naming what to pick (top bar or projectId), and a named segment is its
 * manager — one stance shared by the run and its report, no read/write split.
 */
@Service
public class EvolutionTrendAdminServiceImpl implements EvolutionTrendAdminService {

    private final CoreAiChatClient coreClient;
    private final ProjectAccessGuard guard;

    public EvolutionTrendAdminServiceImpl(CoreAiChatClient coreClient, ProjectAccessGuard guard) {
        this.coreClient = coreClient;
        this.guard = guard;
    }

    @Override
    public EvolutionTrendMirrorView trend(CurrentUser user, String month, Long projectId) {
        if (projectId == null) {
            // The verdict rows are cards of a project's evolution session — a
            // platform-wide report has nothing to anchor to. Same sentence
            // family as the proposal-run door (no second stance per read/write).
            throw new ServiceException("自进化趋势报告需要选定项目：请先在顶栏选择项目，或请求里带上 projectId",
                    BizErrorCode.VALIDATION_ERROR);
        }
        guard.assertCanManage(user, projectId);
        return coreClient.evolutionTrend(month, projectId);
    }
}
