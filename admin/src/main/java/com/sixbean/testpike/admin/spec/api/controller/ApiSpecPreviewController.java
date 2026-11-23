package com.sixbean.testpike.admin.spec.api.controller;

import com.sixbean.testpike.admin.ai.infrastructure.remote.client.CoreDocumentClient;
import com.sixbean.testpike.admin.ai.api.dto.DocumentInfoView;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.spec.api.dto.ApiSpecPreviewView;
import com.sixbean.testpike.admin.spec.api.dto.SpecExtractDispatchRequest;
import com.sixbean.testpike.admin.spec.api.dto.SpecExtractEstimateView;
import com.sixbean.testpike.admin.spec.api.dto.SpecPreviewApplyRequest;
import com.sixbean.testpike.admin.spec.api.dto.SpecPreviewApplyView;
import com.sixbean.testpike.admin.spec.infrastructure.remote.client.CoreApiSpecClient;
import com.sixbean.testpike.admin.spec.infrastructure.remote.client.CoreSpecPreviewClient;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/v1/api-spec-previews")
public class ApiSpecPreviewController {

    private final CoreSpecPreviewClient coreSpecPreviewClient;
    private final CoreApiSpecClient coreApiSpecClient;
    private final CoreDocumentClient coreDocumentClient;
    private final ProjectAccessGuard guard;

    public ApiSpecPreviewController(CoreSpecPreviewClient coreSpecPreviewClient,
                                    CoreApiSpecClient coreApiSpecClient,
                                    CoreDocumentClient coreDocumentClient,
                                    ProjectAccessGuard guard) {
        this.coreSpecPreviewClient = coreSpecPreviewClient;
        this.coreApiSpecClient = coreApiSpecClient;
        this.coreDocumentClient = coreDocumentClient;
        this.guard = guard;
    }

    
    @PostMapping("/generate/{specId}")
    public Response<ApiSpecPreviewView> generate(@PathVariable long specId) {
        CurrentUser user = UserContext.current();
        guard.assertCanWriteRequirement(user, coreApiSpecClient.get(specId).projectId());
        return Response.builderSuccess(coreSpecPreviewClient.generate(specId));
    }

    
    @GetMapping("/{id}")
    public Response<ApiSpecPreviewView> get(@PathVariable long id) {
        CurrentUser user = UserContext.current();
        ApiSpecPreviewView preview = coreSpecPreviewClient.get(id);
        guard.assertCanView(user, preview.projectId());
        return Response.builderSuccess(preview);
    }

    
    @PostMapping("/{id}/apply")
    public Response<SpecPreviewApplyView> apply(@PathVariable long id,
                                                @RequestBody SpecPreviewApplyRequest request) {
        CurrentUser user = UserContext.current();
        ApiSpecPreviewView preview = coreSpecPreviewClient.get(id);
        guard.assertCanWriteRequirement(user, preview.projectId());
        return Response.builderSuccess(
                coreSpecPreviewClient.apply(id, request.selectedKeys()));
    }

    
    @PostMapping("/extract-from-document")
    public Response<SpecExtractEstimateView> extractFromDocument(
            @RequestBody SpecExtractDispatchRequest request) {
        CurrentUser user = UserContext.current();
        DocumentInfoView doc = coreDocumentClient.get(request.documentId());
        guard.assertCanWriteRequirement(user, doc.projectId());
        return Response.builderSuccess(coreSpecPreviewClient.dispatchExtract(request));
    }

    
    @GetMapping("/by-document/{documentId}")
    public Response<ApiSpecPreviewView> latestByDocument(@PathVariable long documentId) {
        CurrentUser user = UserContext.current();
        guard.assertCanView(user, coreDocumentClient.get(documentId).projectId());
        return Response.builderSuccess(coreSpecPreviewClient.latestByDocument(documentId));
    }
}
