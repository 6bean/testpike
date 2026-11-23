package com.sixbean.testpike.admin.aichat.app.service.impl;

import com.sixbean.testpike.admin.aichat.api.dto.EvolutionSignalsMirrorView;
import com.sixbean.testpike.admin.aichat.app.service.EvolutionSignalsAdminService;
import com.sixbean.testpike.admin.aichat.infrastructure.remote.client.CoreAiChatClient;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.stereotype.Service;

/**
 * Permission gate + pass-through for the self-evolution observation rollup.
 * <p>Zero-judgment principle (same as the cost bridge): month-shape and segment
 * semantics are validated fail-closed in core — this class only decides who
 * may look. The posture mirrors the cost overview gate (global = ADMIN, a
 * named segment = its manager) deliberately: a second, slightly different
 * spectrum for "platform-wide AI read-outs" would drift the moment either
 * door's rule changes.
 */
@Service
public class EvolutionSignalsAdminServiceImpl implements EvolutionSignalsAdminService {

    private final CoreAiChatClient coreClient;
    private final ProjectAccessGuard guard;

    public EvolutionSignalsAdminServiceImpl(CoreAiChatClient coreClient, ProjectAccessGuard guard) {
        this.coreClient = coreClient;
        this.guard = guard;
    }

    @Override
    public EvolutionSignalsMirrorView signals(CurrentUser user, String month, Long projectId) {
        if (projectId == null) {
            // Platform-wide rollup crosses projects — system administrators only.
            if (!guard.isAdmin(user)) {
                throw new ServiceException("全局自进化观察信号仅系统管理员可查看", BizErrorCode.FORBIDDEN);
            }
        } else {
            guard.assertCanManage(user, projectId);
        }
        return coreClient.evolutionSignals(month, projectId);
    }
}
