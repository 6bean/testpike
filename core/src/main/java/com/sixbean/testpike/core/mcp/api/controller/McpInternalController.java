package com.sixbean.testpike.core.mcp.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.mcp.api.dto.McpAssemblyServer;
import com.sixbean.testpike.core.mcp.api.dto.McpInvocationInput;
import com.sixbean.testpike.core.mcp.api.dto.McpProbeResult;
import com.sixbean.testpike.core.mcp.api.dto.McpServerCreateInput;
import com.sixbean.testpike.core.mcp.api.dto.McpServerInfo;
import com.sixbean.testpike.core.mcp.api.dto.McpServerQuery;
import com.sixbean.testpike.core.mcp.api.dto.McpServerUpdateInput;
import com.sixbean.testpike.core.mcp.api.dto.McpToolSelectionInput;
import com.sixbean.testpike.core.mcp.domain.service.McpConsumptionService;
import com.sixbean.testpike.core.mcp.domain.service.McpConnectivityService;
import com.sixbean.testpike.core.mcp.domain.service.McpServerService;
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
@RequestMapping("/internal/mcp-servers")
public class McpInternalController {

    private final McpServerService mcpServerService;
    private final McpConnectivityService mcpConnectivityService;
    private final McpConsumptionService mcpConsumptionService;

    public McpInternalController(McpServerService mcpServerService,
                                 McpConnectivityService mcpConnectivityService,
                                 McpConsumptionService mcpConsumptionService) {
        this.mcpServerService = mcpServerService;
        this.mcpConnectivityService = mcpConnectivityService;
        this.mcpConsumptionService = mcpConsumptionService;
    }

    
    @GetMapping
    public Response<PageResult<McpServerInfo>> list(@RequestParam(defaultValue = "1") int page,
                                                    @RequestParam(defaultValue = "20") int size,
                                                    @RequestParam(required = false) String keyword,
                                                    @RequestParam(required = false) Integer enabled,
                                                    @RequestParam(required = false) Integer health,
                                                    @RequestParam(required = false) String sortBy,
                                                    @RequestParam(required = false) String sortDir) {
        return Response.builderSuccess(mcpServerService.list(
                new McpServerQuery(page, size, keyword, enabled, health, sortBy, sortDir)));
    }

    
    @PostMapping
    public Response<McpServerInfo> create(@RequestBody McpServerCreateInput input) {
        return Response.builderSuccess(
                mcpServerService.create(OperatorContext.currentOperatorId(), input));
    }

    
    @GetMapping("/{id}")
    public Response<McpServerInfo> get(@PathVariable long id) {
        return Response.builderSuccess(mcpServerService.get(id));
    }

    
    @PutMapping("/{id}")
    public Response<McpServerInfo> update(@PathVariable long id,
                                          @RequestBody McpServerUpdateInput input) {
        return Response.builderSuccess(
                mcpServerService.update(OperatorContext.currentOperatorId(), id, input));
    }

    
    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable long id) {
        mcpServerService.delete(OperatorContext.currentOperatorId(), id);
        return Response.builderSuccess(null);
    }

    
    @PutMapping("/{id}/toggle")
    public Response<McpServerInfo> toggle(@PathVariable long id, @RequestParam int enabled) {
        return Response.builderSuccess(
                mcpServerService.toggle(OperatorContext.currentOperatorId(), id, enabled));
    }

    
    @PutMapping("/{id}/tools")
    public Response<McpServerInfo> updateTools(@PathVariable long id,
                                               @RequestBody McpToolSelectionInput input) {
        return Response.builderSuccess(
                mcpServerService.updateTools(OperatorContext.currentOperatorId(), id, input));
    }

    
    @PostMapping("/{id}/test")
    public Response<McpProbeResult> test(@PathVariable long id) {
        return Response.builderSuccess(
                mcpConnectivityService.test(OperatorContext.currentOperatorId(), id));
    }

    
    @GetMapping("/assembly")
    public Response<java.util.List<McpAssemblyServer>> assembly(
            @RequestParam(required = false) Long projectId) {
        return Response.builderSuccess(mcpConsumptionService.getAssemblyServers(projectId));
    }

    
    @PostMapping("/invocations")
    public Response<Integer> recordInvocations(
            @RequestBody java.util.List<McpInvocationInput> invocations) {
        return Response.builderSuccess(mcpConsumptionService.recordInvocations(invocations));
    }
}
