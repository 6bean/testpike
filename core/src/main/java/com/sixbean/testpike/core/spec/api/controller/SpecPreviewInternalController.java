package com.sixbean.testpike.core.spec.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.spec.api.dto.ApiSpecPreviewInfo;
import com.sixbean.testpike.core.spec.api.dto.SpecExtractDispatchRequest;
import com.sixbean.testpike.core.spec.api.dto.SpecExtractEstimate;
import com.sixbean.testpike.core.spec.api.dto.SpecPreviewApplyRequest;
import com.sixbean.testpike.core.spec.api.dto.SpecPreviewApplyResult;
import com.sixbean.testpike.core.spec.api.dto.SpecDraftWritebackRequest;
import com.sixbean.testpike.core.spec.domain.service.ApiSpecPreviewService;
import com.sixbean.testpike.core.spec.domain.service.SpecExtractDispatchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/internal/api-spec-previews")
public class SpecPreviewInternalController {

    private final ApiSpecPreviewService apiSpecPreviewService;
    private final SpecExtractDispatchService specExtractDispatchService;

    public SpecPreviewInternalController(ApiSpecPreviewService apiSpecPreviewService,
                                         SpecExtractDispatchService specExtractDispatchService) {
        this.apiSpecPreviewService = apiSpecPreviewService;
        this.specExtractDispatchService = specExtractDispatchService;
    }

    
    @PostMapping("/generate/{specId}")
    public Response<ApiSpecPreviewInfo> generate(@PathVariable long specId) {
        return Response.builderSuccess(
                apiSpecPreviewService.generate(OperatorContext.currentOperatorId(), specId));
    }

    
    @GetMapping("/{id}")
    public Response<ApiSpecPreviewInfo> get(@PathVariable long id) {
        return Response.builderSuccess(apiSpecPreviewService.get(id));
    }

    
    @GetMapping("/by-document/{documentId}")
    public Response<ApiSpecPreviewInfo> latestByDocument(@PathVariable long documentId) {
        return Response.builderSuccess(apiSpecPreviewService.latestByDocument(documentId));
    }

    
    @PostMapping("/{id}/apply")
    public Response<SpecPreviewApplyResult> apply(@PathVariable long id,
                                                  @RequestBody SpecPreviewApplyRequest request) {
        return Response.builderSuccess(apiSpecPreviewService.apply(
                OperatorContext.currentOperatorId(), id, request.selectedKeys()));
    }

    
    @PostMapping("/from-drafts/{documentId}")
    public Response<ApiSpecPreviewInfo> writeBackDrafts(@PathVariable long documentId,
                                                        @RequestBody SpecDraftWritebackRequest request) {
        return Response.builderSuccess(apiSpecPreviewService.generateFromDrafts(
                OperatorContext.currentOperatorId(), documentId, request.items()));
    }

    
    @PostMapping("/extract-from-document")
    public Response<SpecExtractEstimate> dispatchExtract(
            @RequestBody SpecExtractDispatchRequest request) {
        return Response.builderSuccess(specExtractDispatchService.dispatch(
                OperatorContext.currentOperatorId(), request.documentId(), request.modelHint()));
    }
}
