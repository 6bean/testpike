package com.sixbean.testpike.core.document.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.document.api.dto.DocChangeAnalysisInfo;
import com.sixbean.testpike.core.document.api.dto.HandleChangeRequest;
import com.sixbean.testpike.core.document.api.dto.ReanalyzeRequest;
import com.sixbean.testpike.core.document.api.dto.RevisionDraftPregen;
import com.sixbean.testpike.core.document.domain.service.DocumentChangeAnalysisService;
import com.sixbean.testpike.core.document.domain.service.DocumentChangeRevisionDraftService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/internal/doc-change-analyses")
public class DocChangeAnalysisInternalController {

    private final DocumentChangeAnalysisService analysisService;
    private final DocumentChangeRevisionDraftService revisionDraftService;

    public DocChangeAnalysisInternalController(DocumentChangeAnalysisService analysisService,
                                               DocumentChangeRevisionDraftService revisionDraftService) {
        this.analysisService = analysisService;
        this.revisionDraftService = revisionDraftService;
    }

    
    @PostMapping("/reanalyze")
    public Response<DocChangeAnalysisInfo> reanalyze(@RequestBody ReanalyzeRequest request) {
        return Response.builderSuccess(
                analysisService.analyze(OperatorContext.currentOperatorId(), request.newDocumentId()));
    }

    
    @GetMapping
    public Response<PageResult<DocChangeAnalysisInfo>> list(@RequestParam long projectId,
                                                            @RequestParam(required = false) Integer status,
                                                            @RequestParam(defaultValue = "1") int page,
                                                            @RequestParam(defaultValue = "20") int size) {
        return Response.builderSuccess(analysisService.list(projectId, status, page, size));
    }

    
    @GetMapping("/{id}")
    public Response<DocChangeAnalysisInfo> get(@PathVariable long id) {
        return Response.builderSuccess(analysisService.get(id));
    }

    
    @PostMapping("/{id}/handle")
    public Response<DocChangeAnalysisInfo> handle(@PathVariable long id,
                                                  @RequestBody(required = false) HandleChangeRequest request) {
        return Response.builderSuccess(analysisService.handle(OperatorContext.currentOperatorId(), id,
                request == null ? null : request.note()));
    }

    /** Pre-generate revision-draft cards when the disposition view opens . */
    @PostMapping("/{id}/pregen-drafts")
    public Response<RevisionDraftPregen> pregenDrafts(@PathVariable long id) {
        return Response.builderSuccess(revisionDraftService.pregenDrafts(
                OperatorContext.currentOperatorId(), id));
    }
}
