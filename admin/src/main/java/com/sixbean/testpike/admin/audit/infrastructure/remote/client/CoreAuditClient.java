package com.sixbean.testpike.admin.audit.infrastructure.remote.client;

import com.sixbean.testpike.admin.audit.api.dto.AccessDeniedAuditRequest;
import com.sixbean.testpike.admin.audit.api.dto.AuditLogView;
import com.sixbean.testpike.admin.project.api.dto.CursorView;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.common.clientobject.ChainVerifyResult;


public interface CoreAuditClient {

    
    void writeAccessDenied(AccessDeniedAuditRequest request);

    
    void writeScriptRegenOverwrite(Long operatorId, long projectId, long caseId,
                                   long scriptVersion, long taskId);

    /**
 * The optional sort pair (sortBy/sortDir) forwards to the core column
 * whitelist; null/blank keeps the historical newest-first order. */
    PageView<AuditLogView> query(Long operatorId, Long projectId, String objectType,
                                 String objectId, String action, int page, int size,
                                 String sortBy, String sortDir);

    
    CursorView<AuditLogView> queryCursor(Long operatorId, Long projectId, String objectType,
                                         String objectId, String action, Long afterId, int size);

    
    boolean verifyChain();

    
    ChainVerifyResult verifyChainStructured();

    
    ChainVerifyResult chainVerifyTask(long taskId);
}
