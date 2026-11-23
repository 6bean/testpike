package com.sixbean.testpike.admin.prompt.api.controller;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.prompt.api.dto.BehaviorRolloutView;
import com.sixbean.testpike.admin.prompt.api.dto.PromptTemplateFamilyView;
import com.sixbean.testpike.admin.prompt.api.dto.PromptTemplateSaveRequest;
import com.sixbean.testpike.admin.prompt.api.dto.PromptTemplateView;
import com.sixbean.testpike.admin.prompt.infrastructure.remote.client.PromptTemplateCoreClient;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/api/v1/prompt-templates")
public class PromptTemplateController {

    private final PromptTemplateCoreClient coreClient;

    public PromptTemplateController(PromptTemplateCoreClient coreClient) {
        this.coreClient = coreClient;
    }

    
    @RequirePermission("perm:feedback:model-update")
    @PostMapping
    public Response<PromptTemplateView> save(@RequestBody PromptTemplateSaveRequest request) {
        return Response.builderSuccess(coreClient.save(
                request.withCreatedBy(UserContext.currentUserId())));
    }

    
    @RequirePermission("perm:feedback:model-update")
    @PostMapping("/{sourceId}/rollback")
    public Response<PromptTemplateView> rollback(@PathVariable Long sourceId) {
        return Response.builderSuccess(
                coreClient.rollback(sourceId, UserContext.currentUserId()));
    }

    
    @RequirePermission("perm:feedback:model-update")
    @GetMapping("/{id}")
    public Response<PromptTemplateView> detail(@PathVariable Long id) {
        return Response.builderSuccess(coreClient.detail(id));
    }

    
    @RequirePermission("perm:feedback:model-update")
    @GetMapping("/versions")
    public Response<List<PromptTemplateView>> listVersions(@RequestParam String name,
                                                           @RequestParam String domain) {
        return Response.builderSuccess(coreClient.listVersions(name, domain));
    }

    /**
     * Family enumeration (one row per domain+name pair through its newest version;
     * optional domain filter). The management page's first screen — readers learn
     * what exists before asking a family for versions or content.
     */
    @RequirePermission("perm:feedback:model-update")
    @GetMapping("/families")
    public Response<List<PromptTemplateFamilyView>> listFamilies(
            @RequestParam(required = false) String domain) {
        return Response.builderSuccess(coreClient.listFamilies(domain));
    }

    
    @RequirePermission("perm:feedback:model-update")
    @GetMapping("/latest")
    public Response<PromptTemplateView> latest(@RequestParam String name,
                                               @RequestParam String domain) {
        return Response.builderSuccess(coreClient.latest(name, domain));
    }

    
    @RequirePermission("perm:feedback:model-update")
    @GetMapping("/latest-by-status")
    public Response<PromptTemplateView> latestByStatus(@RequestParam String name,
                                                       @RequestParam String domain,
                                                       @RequestParam int status) {
        return Response.builderSuccess(coreClient.latestByStatus(name, domain, status));
    }

    
    @RequirePermission("perm:feedback:model-update")
    @PostMapping("/{id}/promote")
    public Response<PromptTemplateView> promote(@PathVariable Long id) {
        return Response.builderSuccess(coreClient.promote(id));
    }

    /**
 * Gray-rollout advisory of one template family ( item 7 user-reachable
 * leg): result rates and the rollback suggestion are computed in core; this
 * bridge relays only. Advisory-grade by design — promoting or rolling back
 * stays a human action through the promote/rollback legs above.
 */
    @RequirePermission("perm:feedback:model-update")
    @GetMapping("/rollout-advisory")
    public Response<BehaviorRolloutView> rolloutAdvisory(@RequestParam String domain,
                                                         @RequestParam String name) {
        return Response.builderSuccess(coreClient.rolloutAdvisory(domain, name));
    }
}
