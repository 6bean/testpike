package com.sixbean.testpike.admin.ai.api.controller;

import com.sixbean.testpike.admin.ai.api.dto.DocChangeAnalysisView;
import com.sixbean.testpike.admin.ai.api.dto.DocumentInfoView;
import com.sixbean.testpike.admin.ai.api.dto.HandleChangeRequest;
import com.sixbean.testpike.admin.ai.api.dto.ReanalyzeRequest;
import com.sixbean.testpike.admin.ai.api.dto.RevisionDraftPregenView;
import com.sixbean.testpike.admin.ai.infrastructure.remote.client.CoreDocChangeAnalysisClient;
import com.sixbean.testpike.admin.ai.infrastructure.remote.client.CoreDocumentClient;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/v1/doc-change-analyses")
public class DocChangeAnalysisController {

    private final CoreDocChangeAnalysisClient analysisClient;
    private final CoreDocumentClient coreDocumentClient;
    private final ProjectAccessGuard guard;

    public DocChangeAnalysisController(CoreDocChangeAnalysisClient analysisClient,
                                       CoreDocumentClient coreDocumentClient,
                                       ProjectAccessGuard guard) {
        this.analysisClient = analysisClient;
        this.coreDocumentClient = coreDocumentClient;
        this.guard = guard;
    }

    
    @PostMapping("/reanalyze")
    public Response<DocChangeAnalysisView> reanalyze(@RequestBody ReanalyzeRequest request) {
        CurrentUser user = UserContext.current();
        DocumentInfoView doc = coreDocumentClient.get(request.newDocumentId());
        guard.assertCanWriteRequirement(user, doc.projectId());
        return Response.builderSuccess(analysisClient.analyze(request.newDocumentId()));
    }

    
    @GetMapping
    public Response<PageView<DocChangeAnalysisView>> list(@RequestParam long projectId,
                                                          @RequestParam(required = false) Integer status,
                                                          @RequestParam(defaultValue = "1") int page,
                                                          @RequestParam(defaultValue = "20") int size) {
        CurrentUser user = UserContext.current();
        guard.assertCanView(user, projectId);
        return Response.builderSuccess(analysisClient.list(projectId, status, page, size));
    }

    
    @PostMapping("/{id}/handle")
    public Response<DocChangeAnalysisView> handle(@PathVariable long id,
                                                  @RequestBody(required = false) HandleChangeRequest request) {
        CurrentUser user = UserContext.current();
        guard.assertCanWriteRequirement(user, projectIdOf(id));
        return Response.builderSuccess(analysisClient.handle(id,
                request == null ? null : request.note()));
    }

    /**
 * Opening the disposition view pre-generates anchored revision-draft cards
 * . Write gate = the row's own project, exactly like
 * handle — the pass never writes a requirement itself, the confirmation
 * door stays human one-click (red line 10c).
 */
    @PostMapping("/{id}/pregen-drafts")
    public Response<RevisionDraftPregenView> pregenDrafts(@PathVariable long id) {
        CurrentUser user = UserContext.current();
        guard.assertCanWriteRequirement(user, projectIdOf(id));
        return Response.builderSuccess(analysisClient.pregenDrafts(id));
    }

    
    private long projectIdOf(long id) {
        return analysisClient.get(id).projectId();
    }
}
