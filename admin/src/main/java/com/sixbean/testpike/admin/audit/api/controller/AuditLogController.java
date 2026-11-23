package com.sixbean.testpike.admin.audit.api.controller;

import com.sixbean.testpike.admin.audit.api.dto.AuditLogView;
import com.sixbean.testpike.admin.audit.infrastructure.remote.client.CoreAuditClient;
import com.sixbean.testpike.admin.project.api.dto.CursorView;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.ChainVerifyResult;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/v1/audit-logs")
public class AuditLogController {

    private final CoreAuditClient coreAuditClient;

    public AuditLogController(CoreAuditClient coreAuditClient) {
        this.coreAuditClient = coreAuditClient;
    }

    /**
 * The optional sort pair forwards to the core column whitelist (single gate). */
    @GetMapping
    @RequirePermission("perm:detail:audit-view")
    public Response<PageView<AuditLogView>> query(
            @RequestParam(required = false) Long operatorId,
            @RequestParam(required = false) String objectType,
            @RequestParam(required = false) String objectId,
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir) {
        return Response.builderSuccess(coreAuditClient.query(
                operatorId, null, objectType, objectId, action, page, size, sortBy, sortDir));
    }

    
    @GetMapping("/cursor")
    @RequirePermission("perm:detail:audit-view")
    public Response<CursorView<AuditLogView>> queryCursor(
            @RequestParam(required = false) Long operatorId,
            @RequestParam(required = false) String objectType,
            @RequestParam(required = false) String objectId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Long afterId,
            @RequestParam(defaultValue = "20") int size) {
        return Response.builderSuccess(coreAuditClient.queryCursor(
                operatorId, null, objectType, objectId, action, afterId, size));
    }

    
    @GetMapping("/chain-verify")
    @RequirePermission("perm:detail:audit-view")
    public Response<Boolean> verifyChain() {
        return Response.builderSuccess(coreAuditClient.verifyChain());
    }

    
    @PostMapping("/chain-verify")
    @RequirePermission("perm:detail:audit-view")
    public Response<ChainVerifyResult> verifyChainStructured() {
        return Response.builderSuccess(coreAuditClient.verifyChainStructured());
    }

    
    @GetMapping("/chain-verify/task/{taskId}")
    @RequirePermission("perm:detail:audit-view")
    public Response<ChainVerifyResult> chainVerifyTask(@PathVariable long taskId) {
        return Response.builderSuccess(coreAuditClient.chainVerifyTask(taskId));
    }
}
