package com.sixbean.testpike.core.mcp.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.mcp.api.dto.McpKnowledgeSyncInfo;
import com.sixbean.testpike.core.mcp.api.dto.McpKnowledgeSyncRoundResult;
import com.sixbean.testpike.core.mcp.api.dto.McpKnowledgeSyncSaveRequest;
import com.sixbean.testpike.core.mcp.domain.service.McpKnowledgeSyncService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP knowledge sync subscription ledger internal face (core service layer,
 * consumed by admin over HTTP; the drawer inside an MCP server detail).
 * <p>Controller thin (H1 baseline, same shape as McpInternalController): the
 * permission gate lives on the admin side, every guard, the DOWN probe gate,
 * the ledger write-back and the landing chain are single-sourced in
 * McpKnowledgeSyncService. The operator travels on X-Operator-Id so the
 * audit chain closes through OperatorContext.
 * <p>run-now never throws for an expected failure: a DOWN subscription
 * idles on the existing connectivity probe and answers 200 with the typed
 * round outcome (status/error/newCount/skippedCount), never a 5xx.
 */
@RestController
@RequestMapping("/internal/mcp-knowledge-syncs")
public class McpKnowledgeSyncInternalController {

    private final McpKnowledgeSyncService syncService;

    public McpKnowledgeSyncInternalController(McpKnowledgeSyncService syncService) {
        this.syncService = syncService;
    }

    /** Ledger list (project segment, optional server scope, paged id DESC). */
    @GetMapping
    public Response<PageResult<McpKnowledgeSyncInfo>> list(@RequestParam(required = false) Long projectId,
                                                           @RequestParam(required = false) Long serverId,
                                                           @RequestParam(defaultValue = "1") int page,
                                                           @RequestParam(defaultValue = "20") int size) {
        return Response.builderSuccess(syncService.list(projectId, serverId, page, size));
    }

    /** One subscription with its bookkeeping columns (40001 human message when absent). */
    @GetMapping("/{id}")
    public Response<McpKnowledgeSyncInfo> detail(@PathVariable long id) {
        return Response.builderSuccess(syncService.detail(id));
    }

    /** Register (guards run in the service layer; auto_activate defaults to off). */
    @PostMapping
    public Response<McpKnowledgeSyncInfo> save(@RequestBody McpKnowledgeSyncSaveRequest input) {
        return Response.builderSuccess(syncService.save(OperatorContext.currentOperatorId(), input));
    }

    /** Replace the editable columns under the same guards. */
    @PutMapping("/{id}")
    public Response<McpKnowledgeSyncInfo> update(@PathVariable long id,
                                                 @RequestBody McpKnowledgeSyncSaveRequest input) {
        return Response.builderSuccess(syncService.update(OperatorContext.currentOperatorId(), id, input));
    }

    /** Remove the subscription (history rounds stay in the entry ledger). */
    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable long id) {
        syncService.delete(OperatorContext.currentOperatorId(), id);
        return Response.builderSuccess(null);
    }

    /** Pause: excluded from the due scan until resumed. */
    @PostMapping("/{id}/pause")
    public Response<Void> pause(@PathVariable long id) {
        syncService.pause(OperatorContext.currentOperatorId(), id);
        return Response.builderSuccess(null);
    }

    /** Resume: restarts the consecutive-failure path from zero (ledger contract). */
    @PostMapping("/{id}/resume")
    public Response<Void> resume(@PathVariable long id) {
        syncService.resume(OperatorContext.currentOperatorId(), id);
        return Response.builderSuccess(null);
    }

    /** Manual trigger: one immediate round through the DOWN gate (always 200, typed outcome). */
    @PostMapping("/{id}/run-now")
    public Response<McpKnowledgeSyncRoundResult> runNow(@PathVariable long id) {
        return Response.builderSuccess(syncService.runOnce(OperatorContext.currentOperatorId(), id));
    }
}
