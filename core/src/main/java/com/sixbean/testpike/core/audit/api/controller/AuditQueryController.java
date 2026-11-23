package com.sixbean.testpike.core.audit.api.controller;

import com.sixbean.testpike.common.clientobject.ChainVerifyResult;
import com.sixbean.testpike.core.audit.api.dto.AuditLogInput;
import com.sixbean.testpike.core.audit.domain.service.AuditChainService;
import com.sixbean.testpike.core.audit.domain.service.AuditLogService;
import com.sixbean.testpike.core.audit.infrastructure.dataobject.AuditLog;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.common.clientobject.CursorPage;
import com.sixbean.testpike.core.common.clientobject.PageResult;


import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/internal/audit-logs")
public class AuditQueryController {

    private final AuditLogService auditLogService;
    private final AuditChainService auditChainService;

    public AuditQueryController(AuditLogService auditLogService, AuditChainService auditChainService) {
        this.auditLogService = auditLogService;
        this.auditChainService = auditChainService;
    }

    @GetMapping
    public Response<PageResult<AuditLog>> query(
            @RequestParam(required = false) Long operatorId,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String objectType,
            @RequestParam(required = false) String objectId,
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir) {
        return Response.builderSuccess(auditLogService.query(
                operatorId, projectId, objectType, objectId, action, page, size, sortBy, sortDir));
    }

    
    @GetMapping("/cursor")
    public Response<CursorPage<AuditLog>> queryCursor(
            @RequestParam(required = false) Long operatorId,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String objectType,
            @RequestParam(required = false) String objectId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Long afterId,
            @RequestParam(defaultValue = "20") int size) {
        return Response.builderSuccess(auditLogService.queryCursor(
                operatorId, projectId, objectType, objectId, action, afterId, size));
    }

    
    @PostMapping
    public Response<Void> record(@RequestBody AuditLogInput input,
                                 @RequestHeader(value = "X-Operator-Id", required = false) Long operatorId) {
        auditLogService.record(operatorId == null ? input
                : new AuditLogInput(operatorId, input.projectId(), input.objectType(),
                        input.objectId(), input.action(), input.before(), input.after()));
        return Response.builderSuccess(null);
    }

    
    @GetMapping("/chain-verify")
    public Response<Boolean> verifyChain() {
        return Response.builderSuccess(auditLogService.verifyChain());
    }

    
    @PostMapping("/chain-verify")
    public Response<ChainVerifyResult> verifyChainStructured() {
        Long operatorId = OperatorContext.currentOperatorId();
        return Response.builderSuccess(auditChainService.verify(operatorId == null ? 0L : operatorId));
    }

    
    @GetMapping("/chain-verify/{taskId}")
    public Response<ChainVerifyResult> chainVerifyTask(@PathVariable long taskId) {
        return Response.builderSuccess(auditChainService.taskResult(taskId));
    }
}
