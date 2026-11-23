package com.sixbean.testpike.admin.llmconfig.api.controller;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.llmconfig.api.dto.GoldenDriftPageView;
import com.sixbean.testpike.admin.llmconfig.api.dto.LlmConfigSaveRequest;
import com.sixbean.testpike.admin.llmconfig.api.dto.LlmConfigView;
import com.sixbean.testpike.admin.llmconfig.api.dto.LlmOption;
import com.sixbean.testpike.admin.llmconfig.api.dto.ProjectLlmConfigSaveBody;
import com.sixbean.testpike.admin.llmconfig.api.dto.ProjectLlmConfigView;
import com.sixbean.testpike.admin.llmconfig.infrastructure.remote.client.LlmConfigClient;
import com.sixbean.testpike.admin.llmconfig.infrastructure.remote.client.ProjectLlmConfigRef;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
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

import java.util.List;


@RestController
@RequestMapping("/api/v1")
public class LlmConfigController {

    private final LlmConfigClient llmConfigClient;
    private final ProjectAccessGuard guard;

    public LlmConfigController(LlmConfigClient llmConfigClient, ProjectAccessGuard guard) {
        this.llmConfigClient = llmConfigClient;
        this.guard = guard;
    }

 
    @GetMapping("/llm-configs")
    @RequirePermission("perm:system:llm-config")
    public Response<List<LlmConfigView>> list(
            @RequestParam(value = "modelType", required = false) Integer modelType) {
        return Response.builderSuccess(llmConfigClient.list(modelType));
    }

    
    @PostMapping("/llm-configs")
    @RequirePermission("perm:system:llm-config")
    public Response<LlmConfigView> create(@RequestBody LlmConfigSaveRequest request) {
        return Response.builderSuccess(llmConfigClient.create(request));
    }

    
    @PutMapping("/llm-configs/{id}")
    @RequirePermission("perm:system:llm-config")
    public Response<LlmConfigView> update(@PathVariable long id, @RequestBody LlmConfigSaveRequest request) {
        return Response.builderSuccess(llmConfigClient.update(id, request));
    }

    
    @DeleteMapping("/llm-configs/{id}")
    @RequirePermission("perm:system:llm-config")
    public Response<Void> delete(@PathVariable long id) {
        llmConfigClient.delete(id);
        return Response.builderSuccess(null);
    }

 
    @GetMapping("/projects/{id}/llm-config")
    public Response<ProjectLlmConfigView> getProjectConfig(@PathVariable long id) {
        guard.assertCanView(UserContext.current(), id);
        ProjectLlmConfigRef ref = llmConfigClient.getProjectConfig(id);
        List<LlmOption> options = llmConfigClient.listEnabled().stream()
                .map(c -> new LlmOption(c.id(), c.name(), c.provider(), c.modelName(), c.baseUrl()))
                .toList();
        return Response.builderSuccess(new ProjectLlmConfigView(
                ref == null ? null : ref.llmConfigId(),
                ref == null ? null : ref.llmConfigName(), options));
    }

    
    @PutMapping("/projects/{id}/llm-config")
    public Response<ProjectLlmConfigView> updateProjectConfig(@PathVariable long id,
                                                              @RequestBody ProjectLlmConfigSaveBody body) {
        guard.assertCanManage(UserContext.current(), id);
        ProjectLlmConfigRef ref = llmConfigClient.updateProjectConfig(id, body.llmConfigId());
        List<LlmOption> options = llmConfigClient.listEnabled().stream()
                .map(c -> new LlmOption(c.id(), c.name(), c.provider(), c.modelName(), c.baseUrl()))
                .toList();
        return Response.builderSuccess(new ProjectLlmConfigView(
                ref == null ? null : ref.llmConfigId(),
                ref == null ? null : ref.llmConfigName(), options));
    }

    /**
 * Route-axis golden replay ledger : newest-first model-switch
 * replays with the drift verdict. This read serves the global quality ledger,
 * not project data — there is no project segment to align, so ProjectAccessGuard
 * does not apply here (stated as-is; permission gate stays llm:config).
 */
    @GetMapping("/llm-configs/route-replays")
    @RequirePermission("perm:system:llm-config")
    public Response<GoldenDriftPageView> routeReplays(
            @RequestParam(value = "domain", required = false) String domain,
            @RequestParam(value = "controlRef", required = false) String controlRef,
            @RequestParam(value = "candidateRef", required = false) String candidateRef,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "50") int pageSize) {
        return Response.builderSuccess(
                llmConfigClient.routeReplays(domain, controlRef, candidateRef, page, pageSize));
    }
}
