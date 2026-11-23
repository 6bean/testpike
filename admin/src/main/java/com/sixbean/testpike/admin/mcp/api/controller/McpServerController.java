package com.sixbean.testpike.admin.mcp.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.mcp.api.dto.McpProbeResultView;
import com.sixbean.testpike.admin.mcp.api.dto.McpServerCreateRequest;
import com.sixbean.testpike.admin.mcp.api.dto.McpServerUpdateRequest;
import com.sixbean.testpike.admin.mcp.api.dto.McpServerView;
import com.sixbean.testpike.admin.mcp.api.dto.McpToolSelectionRequest;
import com.sixbean.testpike.admin.mcp.app.component.McpAccessGuard;
import com.sixbean.testpike.admin.mcp.infrastructure.remote.client.McpServerClient;
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


@RestController
@RequestMapping("/api/v1/mcp-servers")
public class McpServerController {

    private final McpServerClient mcpServerClient;
    private final McpAccessGuard mcpGuard;

    public McpServerController(McpServerClient mcpServerClient, McpAccessGuard mcpGuard) {
        this.mcpServerClient = mcpServerClient;
        this.mcpGuard = mcpGuard;
    }

    
    @RequirePermission("perm:knowledge:mcp-manage")
    @GetMapping
    public Response<PageView<McpServerView>> list(@RequestParam(required = false) String keyword,
                                                  @RequestParam(required = false) Integer enabled,
                                                  @RequestParam(required = false) Integer health,
                                                  @RequestParam(required = false) String sortBy,
                                                  @RequestParam(required = false) String sortDir,
                                                  @RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        assertAdmin();
        return Response.builderSuccess(
                mcpServerClient.list(keyword, enabled, health, sortBy, sortDir, page, size));
    }

 
    @RequirePermission("perm:knowledge:mcp-manage")
    @PostMapping
    public Response<McpServerView> create(@RequestBody McpServerCreateRequest request) {
        assertAdmin();
        return Response.builderSuccess(mcpServerClient.create(request));
    }

 
    @RequirePermission("perm:knowledge:mcp-manage")
    @GetMapping("/{id}")
    public Response<McpServerView> get(@PathVariable long id) {
        assertAdmin();
        return Response.builderSuccess(mcpServerClient.get(id));
    }

    
    @RequirePermission("perm:knowledge:mcp-manage")
    @PutMapping("/{id}")
    public Response<McpServerView> update(@PathVariable long id,
                                          @RequestBody McpServerUpdateRequest request) {
        assertAdmin();
        return Response.builderSuccess(mcpServerClient.update(id, request));
    }

 
    @RequirePermission("perm:knowledge:mcp-manage")
    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable long id) {
        assertAdmin();
        mcpServerClient.delete(id);
        return Response.builderSuccess(null);
    }

    
    @RequirePermission("perm:knowledge:mcp-manage")
    @PutMapping("/{id}/toggle")
    public Response<McpServerView> toggle(@PathVariable long id, @RequestParam int enabled) {
        assertAdmin();
        return Response.builderSuccess(mcpServerClient.toggle(id, enabled));
    }

 
    @RequirePermission("perm:knowledge:mcp-manage")
    @PutMapping("/{id}/tools")
    public Response<McpServerView> updateTools(@PathVariable long id,
                                               @RequestBody McpToolSelectionRequest request) {
        assertAdmin();
        return Response.builderSuccess(mcpServerClient.updateTools(id, request));
    }

 
    @RequirePermission("perm:knowledge:mcp-manage")
    @PostMapping("/{id}/test")
    public Response<McpProbeResultView> test(@PathVariable long id) {
        assertAdmin();
        return Response.builderSuccess(mcpServerClient.test(id));
    }

    private void assertAdmin() {
        CurrentUser user = UserContext.current();
        mcpGuard.assertCanManage(user);
    }
}
