package com.sixbean.testpike.admin.spec.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.spec.api.dto.ApiSpecView;
import com.sixbean.testpike.admin.spec.api.dto.SpecSubscriptionSaveRequest;
import com.sixbean.testpike.admin.spec.api.dto.SpecSubscriptionView;
import com.sixbean.testpike.admin.spec.api.dto.SpecUrlFetchRequest;
import com.sixbean.testpike.admin.spec.infrastructure.remote.client.CoreApiSpecClient;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;


@RestController
@RequestMapping("/api/v1/api-specs")
public class ApiSpecController {

    private final CoreApiSpecClient coreApiSpecClient;
    private final ProjectAccessGuard guard;

    public ApiSpecController(CoreApiSpecClient coreApiSpecClient, ProjectAccessGuard guard) {
        this.coreApiSpecClient = coreApiSpecClient;
        this.guard = guard;
    }

    
    @PostMapping
    public Response<ApiSpecView> upload(@RequestParam long projectId,
                                        @RequestParam String name,
                                        @RequestPart("file") MultipartFile file) throws IOException {
        CurrentUser user = UserContext.current();
        guard.assertCanWriteRequirement(user, projectId);
        return Response.builderSuccess(coreApiSpecClient.upload(projectId, name, file.getBytes()));
    }

 
    @PostMapping("/fetch-url")
    public Response<ApiSpecView> fetchUrl(@RequestBody SpecUrlFetchRequest request) {
        CurrentUser user = UserContext.current();
        guard.assertCanWriteRequirement(user, request.projectId());
        return Response.builderSuccess(coreApiSpecClient.fetchFromUrl(
                request.projectId(), request.name(), request.url()));
    }

    
    @GetMapping
    public Response<PageView<ApiSpecView>> list(@RequestParam long projectId,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        CurrentUser user = UserContext.current();
        guard.assertCanView(user, projectId);
        return Response.builderSuccess(coreApiSpecClient.list(projectId, page, size));
    }

    
    @GetMapping("/history")
    public Response<List<ApiSpecView>> history(@RequestParam long projectId,
                                               @RequestParam String name) {
        CurrentUser user = UserContext.current();
        guard.assertCanView(user, projectId);
        return Response.builderSuccess(coreApiSpecClient.history(projectId, name));
    }

    
    @GetMapping("/{id}")
    public Response<ApiSpecView> get(@PathVariable long id) {
        CurrentUser user = UserContext.current();
        ApiSpecView spec = coreApiSpecClient.get(id);
        guard.assertCanView(user, spec.projectId());
        return Response.builderSuccess(spec);
    }


    
    @PostMapping("/subscriptions")
    public Response<SpecSubscriptionView> createSubscription(
            @RequestBody SpecSubscriptionSaveRequest request) {
        CurrentUser user = UserContext.current();
        guard.assertCanWriteRequirement(user, request.projectId());
        return Response.builderSuccess(coreApiSpecClient.createSubscription(
                request.projectId(), request.name(), request.url(), request.checkIntervalMinutes(),
                request.sourceKind(), request.providerProjectId(), request.providerSpecName()));
    }

    
    @GetMapping("/subscriptions")
    public Response<PageView<SpecSubscriptionView>> listSubscriptions(
            @RequestParam long projectId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        CurrentUser user = UserContext.current();
        guard.assertCanView(user, projectId);
        return Response.builderSuccess(coreApiSpecClient.listSubscriptions(projectId, page, size));
    }

    
    @PutMapping("/subscriptions/{id}")
    public Response<SpecSubscriptionView> updateSubscription(
            @PathVariable long id, @RequestBody SpecSubscriptionSaveRequest request) {
        CurrentUser user = UserContext.current();
        SpecSubscriptionView current = coreApiSpecClient.getSubscription(id);
        guard.assertCanWriteRequirement(user, current.projectId());
        return Response.builderSuccess(coreApiSpecClient.updateSubscription(
                id, request.name(), request.url(), request.checkIntervalMinutes(), request.enabled(),
                request.sourceKind(), request.providerProjectId(), request.providerSpecName()));
    }

    
    @DeleteMapping("/subscriptions/{id}")
    public Response<Void> deleteSubscription(@PathVariable long id) {
        CurrentUser user = UserContext.current();
        SpecSubscriptionView current = coreApiSpecClient.getSubscription(id);
        guard.assertCanWriteRequirement(user, current.projectId());
        coreApiSpecClient.deleteSubscription(id);
        return Response.builderSuccess(null);
    }
}
