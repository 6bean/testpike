package com.sixbean.testpike.admin.audit.infrastructure.remote.client;

import com.sixbean.testpike.admin.audit.api.dto.AccessDeniedAuditRequest;
import com.sixbean.testpike.admin.audit.api.dto.AuditLogView;
import com.sixbean.testpike.admin.project.api.dto.CursorView;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.common.clientobject.ChainVerifyResult;

import java.util.ArrayList;
import java.util.List;


public class StubCoreAuditClient implements CoreAuditClient {

    
    public final List<AccessDeniedAuditRequest> denials = new ArrayList<>();

    
    public boolean fail;

    @Override
    public void writeAccessDenied(AccessDeniedAuditRequest request) {
        if (fail) {
            throw new IllegalStateException("core 审计端点不可达");
        }
        denials.add(request);
    }

    @Override
    public void writeScriptRegenOverwrite(Long operatorId, long projectId, long caseId,
                                          long scriptVersion, long taskId) {
        if (fail) {
            throw new IllegalStateException("core 审计端点不可达");
        }
        denials.add(new AccessDeniedAuditRequest(operatorId, projectId, "script",
                String.valueOf(caseId), "script_regen_overwrite",
                java.util.Map.of("scriptVersion", scriptVersion),
                java.util.Map.of("taskId", taskId)));
    }

    @Override
    public PageView<AuditLogView> query(Long operatorId, Long projectId, String objectType,
                                        String objectId, String action, int page, int size,
                                        String sortBy, String sortDir) {
        throw new UnsupportedOperationException("守卫单测不触发");
    }

    @Override
    public CursorView<AuditLogView> queryCursor(Long operatorId, Long projectId, String objectType,
                                                String objectId, String action, Long afterId, int size) {
        throw new UnsupportedOperationException("守卫单测不触发");
    }

    @Override
    public boolean verifyChain() {
        throw new UnsupportedOperationException("守卫单测不触发");
    }

    @Override
    public ChainVerifyResult verifyChainStructured() {
        throw new UnsupportedOperationException("守卫单测不触发");
    }

    @Override
    public ChainVerifyResult chainVerifyTask(long taskId) {
        throw new UnsupportedOperationException("守卫单测不触发");
    }
}
