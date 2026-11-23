package com.sixbean.testpike.admin.tool.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.admin.tool.api.dto.ToolConfirmTicketView;
import com.sixbean.testpike.admin.tool.api.dto.ToolCreateRequest;
import com.sixbean.testpike.admin.tool.api.dto.ToolDetailView;
import com.sixbean.testpike.admin.tool.api.dto.ToolInvocationView;
import com.sixbean.testpike.admin.tool.api.dto.ToolInvokeRequest;
import com.sixbean.testpike.admin.tool.api.dto.ToolRejectRequest;
import com.sixbean.testpike.admin.tool.api.dto.ToolRollbackRequest;
import com.sixbean.testpike.admin.tool.api.dto.ToolTestRunInput;
import com.sixbean.testpike.admin.tool.api.dto.ToolTestRunResult;
import com.sixbean.testpike.admin.tool.api.dto.ToolUpdateRequest;
import com.sixbean.testpike.admin.tool.api.dto.ToolVersionDetailView;
import com.sixbean.testpike.admin.tool.api.dto.ToolVersionInfoView;
import com.sixbean.testpike.admin.tool.api.dto.ToolView;
import com.sixbean.testpike.admin.tool.app.component.ToolAccessGuard;
import com.sixbean.testpike.admin.tool.infrastructure.remote.client.ToolClient;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.http.ResponseEntity;
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
@RequestMapping("/api/v1/tools")
public class ToolController {

    private final ToolClient toolClient;
    private final ToolAccessGuard toolGuard;
    private final ProjectAccessGuard projectGuard;

    public ToolController(ToolClient toolClient, ToolAccessGuard toolGuard,
                          ProjectAccessGuard projectGuard) {
        this.toolClient = toolClient;
        this.toolGuard = toolGuard;
        this.projectGuard = projectGuard;
    }

    
    @GetMapping
    public Response<PageView<ToolView>> list(@RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) Integer type,
                                             @RequestParam(required = false) Integer status,
                                             @RequestParam(required = false) Long projectId,
                                             @RequestParam(required = false) String category,
                                             @RequestParam(required = false) Long tagId,
                                             @RequestParam(required = false) String sortBy,
                                             @RequestParam(required = false) String sortDir,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        CurrentUser user = UserContext.current();
        if (projectId != null) {
            projectGuard.assertCanView(user, projectId);
        }
        return Response.builderSuccess(toolClient.list(keyword, type, status, projectId,
                category, tagId, sortBy, sortDir, page, size));
    }

    
    @PostMapping
    public Response<ToolView> create(@RequestBody ToolCreateRequest request) {
        toolGuard.assertCanManage(UserContext.current(), request.projectId());
        return Response.builderSuccess(toolClient.create(request));
    }

    
    @GetMapping("/{id}")
    public Response<ToolView> get(@PathVariable long id) {
        CurrentUser user = UserContext.current();
        ToolView tool = toolClient.get(id);
        if (tool.projectId() != null) {
            projectGuard.assertCanView(user, tool.projectId());
        }
        return Response.builderSuccess(tool);
    }

    
    @PutMapping("/{id}")
    public Response<ToolView> update(@PathVariable long id, @RequestBody ToolUpdateRequest request) {
        ToolView tool = toolClient.get(id);
        toolGuard.assertCanManage(UserContext.current(), tool.projectId());
        return Response.builderSuccess(toolClient.update(id, request));
    }

    
    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable long id, @RequestParam int expectedVersion) {
        ToolView tool = toolClient.get(id);
        toolGuard.assertCanManage(UserContext.current(), tool.projectId());
        toolClient.delete(id, expectedVersion);
        return Response.builderSuccess(null);
    }

    
    @PostMapping("/{id}/submit")
    public Response<ToolView> submit(@PathVariable long id, @RequestParam int expectedVersion) {
        ToolView tool = toolClient.get(id);
        toolGuard.assertCanManage(UserContext.current(), tool.projectId());
        return Response.builderSuccess(toolClient.submit(id, expectedVersion));
    }

    
    @PostMapping("/{id}/approve")
    public Response<ToolView> approve(@PathVariable long id, @RequestParam int expectedVersion) {
        ToolView tool = toolClient.get(id);
        toolGuard.assertCanManage(UserContext.current(), tool.projectId());
        return Response.builderSuccess(toolClient.approve(id, expectedVersion));
    }

    
    @PostMapping("/{id}/reject")
    public Response<ToolView> reject(@PathVariable long id, @RequestBody ToolRejectRequest request) {
        ToolView tool = toolClient.get(id);
        toolGuard.assertCanManage(UserContext.current(), tool.projectId());
        return Response.builderSuccess(toolClient.reject(id, request));
    }

    
    @RequirePermission("perm:tool:tool-market")
    @GetMapping("/{id}/invocations")
    public Response<PageView<ToolInvocationView>> invocations(
            @PathVariable long id,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        toolGuard.assertAdmin(UserContext.current());
        return Response.builderSuccess(toolClient.invocations(id, start, end, page, size));
    }

    
    @PostMapping("/{id}/retire")
    public Response<ToolView> retire(@PathVariable long id, @RequestParam int expectedVersion) {
        ToolView tool = toolClient.get(id);
        toolGuard.assertCanManage(UserContext.current(), tool.projectId());
        return Response.builderSuccess(toolClient.retire(id, expectedVersion));
    }

    
    @RequirePermission("perm:tool:tool-market")
    @PostMapping("/{id}/test-run")
    public Response<ToolTestRunResult> testRun(@PathVariable long id,
                                               @RequestBody(required = false) ToolTestRunInput input) {
        toolGuard.assertAdmin(UserContext.current(), "工具试跑");
        return Response.builderSuccess(toolClient.testRun(id, input));
    }

    
    @RequirePermission("perm:tool:tool-market")
    @PostMapping("/{id}/confirm-token")
    public Response<ToolConfirmTicketView> confirmToken(@PathVariable long id) {
        toolGuard.assertAdmin(UserContext.current(), "写确认票签发");
        return Response.builderSuccess(toolClient.confirmTicket(id));
    }

    
    @RequirePermission("perm:tool:tool-market")
    @PostMapping("/{id}/invoke")
    public Response<ToolTestRunResult> invoke(@PathVariable long id,
                                              @RequestBody(required = false) ToolInvokeRequest request) {
        toolGuard.assertAdmin(UserContext.current(), "工具执行");
        return Response.builderSuccess(toolClient.invoke(id, request));
    }


    
    @GetMapping("/{id}/detail")
    public Response<ToolDetailView> detail(@PathVariable long id) {
        assertCanViewTool(id);
        return Response.builderSuccess(toolClient.detail(id));
    }

    
    @GetMapping("/{id}/versions")
    public Response<PageView<ToolVersionInfoView>> versions(@PathVariable long id,
                                                            @RequestParam(defaultValue = "1") int page,
                                                            @RequestParam(defaultValue = "20") int size) {
        assertCanViewTool(id);
        return Response.builderSuccess(toolClient.versions(id, page, size));
    }

    
    @GetMapping("/{id}/versions/{version}")
    public Response<ToolVersionDetailView> versionDetail(@PathVariable long id,
                                                         @PathVariable int version) {
        assertCanViewTool(id);
        return Response.builderSuccess(toolClient.versionDetail(id, version));
    }

    
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable long id,
                                          @RequestParam(required = false) Integer version) {
        assertCanViewTool(id);
        return toolClient.download(id, version);
    }

    
    @PostMapping("/{id}/rollback")
    public Response<ToolView> rollback(@PathVariable long id,
                                       @RequestBody ToolRollbackRequest request) {
        ToolView tool = toolClient.get(id);
        toolGuard.assertCanManage(UserContext.current(), tool.projectId());
        return Response.builderSuccess(toolClient.rollback(id, request));
    }

    
    private void assertCanViewTool(long id) {
        ToolView tool = toolClient.get(id);
        if (tool.projectId() != null) {
            projectGuard.assertCanView(UserContext.current(), tool.projectId());
        }
    }
}
