package com.sixbean.testpike.admin.mcp.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.mcp.api.dto.KnowledgeSyncRoundView;
import com.sixbean.testpike.admin.mcp.api.dto.KnowledgeSyncUpsertRequest;
import com.sixbean.testpike.admin.mcp.api.dto.KnowledgeSyncView;
import com.sixbean.testpike.admin.mcp.app.component.McpAccessGuard;
import com.sixbean.testpike.admin.mcp.infrastructure.remote.client.McpKnowledgeSyncClient;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
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
 * Knowledge sync subscription ledger face inside an MCP server detail
 * (nested under the server the drawer is opened from; no new menu leaf).
 * <p>Controller thin: the permission gate reuses the MCP management key
 * (ADMIN only, same guard as McpServerController, zero new keys), every
 * business guard lives single-sourced in the core service. The register leg
 * binds the nested path scope into the payload so a body can never register
 * a subscription against a server other than the one being viewed.
 * <p>The run-now leg keeps the core contract of answering 200 with a typed
 * round outcome (status/error/newCount/skippedCount) for expected failures
 * such as a DOWN probe idle; the bridge adds no exception translation.
 */
@RestController
@RequestMapping("/api/v1/mcp-servers/{serverId}/knowledge-syncs")
public class McpKnowledgeSyncController {

    private final McpKnowledgeSyncClient syncClient;
    private final McpAccessGuard mcpGuard;

    public McpKnowledgeSyncController(McpKnowledgeSyncClient syncClient, McpAccessGuard mcpGuard) {
        this.syncClient = syncClient;
        this.mcpGuard = mcpGuard;
    }

    /** Ledger list for this server (optional project filter, paged). */
    @RequirePermission("perm:knowledge:mcp-manage")
    @GetMapping
    public Response<PageView<KnowledgeSyncView>> list(@PathVariable long serverId,
                                                      @RequestParam(required = false) Long projectId,
                                                      @RequestParam(defaultValue = "1") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        assertAdmin();
        return Response.builderSuccess(syncClient.list(serverId, projectId, page, size));
    }

    /** One subscription with its bookkeeping columns. */
    @RequirePermission("perm:knowledge:mcp-manage")
    @GetMapping("/{id}")
    public Response<KnowledgeSyncView> detail(@PathVariable long serverId, @PathVariable long id) {
        assertAdmin();
        return Response.builderSuccess(syncClient.detail(id));
    }

    /** Register a subscription under this server (guards in core; auto_activate defaults off). */
    @RequirePermission("perm:knowledge:mcp-manage")
    @PostMapping
    public Response<KnowledgeSyncView> create(@PathVariable long serverId,
                                              @RequestBody KnowledgeSyncUpsertRequest request) {
        assertAdmin();
        return Response.builderSuccess(syncClient.save(request.withServerScope(serverId)));
    }

    /** Replace the editable columns under the same core guards. */
    @RequirePermission("perm:knowledge:mcp-manage")
    @PutMapping("/{id}")
    public Response<KnowledgeSyncView> update(@PathVariable long serverId, @PathVariable long id,
                                              @RequestBody KnowledgeSyncUpsertRequest request) {
        assertAdmin();
        return Response.builderSuccess(syncClient.update(id, request));
    }

    /** Remove the subscription (history rounds stay in the entry ledger). */
    @RequirePermission("perm:knowledge:mcp-manage")
    @DeleteMapping("/{id}")
    public Response<Void> remove(@PathVariable long serverId, @PathVariable long id) {
        assertAdmin();
        syncClient.delete(id);
        return Response.builderSuccess(null);
    }

    /** Pause: excluded from the due scan until resumed. */
    @RequirePermission("perm:knowledge:mcp-manage")
    @PostMapping("/{id}/pause")
    public Response<Void> pause(@PathVariable long serverId, @PathVariable long id) {
        assertAdmin();
        syncClient.pause(id);
        return Response.builderSuccess(null);
    }

    /** Resume: restarts the consecutive-failure path from zero (ledger contract). */
    @RequirePermission("perm:knowledge:mcp-manage")
    @PostMapping("/{id}/resume")
    public Response<Void> resume(@PathVariable long serverId, @PathVariable long id) {
        assertAdmin();
        syncClient.resume(id);
        return Response.builderSuccess(null);
    }

    /** Manual trigger: one immediate round through the core DOWN gate (always 200, typed outcome). */
    @RequirePermission("perm:knowledge:mcp-manage")
    @PostMapping("/{id}/run-now")
    public Response<KnowledgeSyncRoundView> runNow(@PathVariable long serverId, @PathVariable long id) {
        assertAdmin();
        return Response.builderSuccess(syncClient.runNow(id));
    }

    private void assertAdmin() {
        CurrentUser user = UserContext.current();
        mcpGuard.assertCanManage(user);
    }
}
