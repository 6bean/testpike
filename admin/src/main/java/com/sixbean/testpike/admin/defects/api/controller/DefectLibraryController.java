package com.sixbean.testpike.admin.defects.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.cases.app.component.CaseProjectResolver;
import com.sixbean.testpike.admin.defects.api.dto.DefectCreateRequest;
import com.sixbean.testpike.admin.defects.api.dto.DefectExportResultView;
import com.sixbean.testpike.admin.defects.api.dto.DefectLibraryView;
import com.sixbean.testpike.admin.defects.api.dto.DefectSummaryView;
import com.sixbean.testpike.admin.defects.api.dto.DefectTransitionRequest;
import com.sixbean.testpike.admin.defects.api.dto.DefectUpdateRequest;
import com.sixbean.testpike.admin.defects.api.dto.ExportDefectsRequest;
import com.sixbean.testpike.admin.defects.infrastructure.remote.client.CoreDefectLibraryClient;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/v1/defect-library")
public class DefectLibraryController {

    private final CoreDefectLibraryClient coreDefectLibraryClient;
    private final CaseProjectResolver caseProjectResolver;
    private final ProjectAccessGuard guard;

    public DefectLibraryController(CoreDefectLibraryClient coreDefectLibraryClient,
                                   CaseProjectResolver caseProjectResolver, ProjectAccessGuard guard) {
        this.coreDefectLibraryClient = coreDefectLibraryClient;
        this.caseProjectResolver = caseProjectResolver;
        this.guard = guard;
    }

    /** Defect list (filters + paging; member/H1 view path, forwards whitelisted sort params to core). */
    @GetMapping
    @RequirePermission("perm:testops:defect-lib")
    public Response<PageView<DefectLibraryView>> list(@RequestParam long projectId,
                                                      @RequestParam(required = false) Integer status,
                                                      @RequestParam(required = false) Integer severity,
                                                      @RequestParam(required = false) Long assigneeId,
                                                      @RequestParam(required = false) Long caseId,
                                                      @RequestParam(required = false) String keyword,
                                                      @RequestParam(defaultValue = "1") int page,
                                                      @RequestParam(defaultValue = "10") int size,
                                                      @RequestParam(required = false) String sortBy,
                                                      @RequestParam(required = false) String sortDir,
                                                      @RequestParam(defaultValue = "0") long programId) {
        CurrentUser user = UserContext.current();
        // Program lens : with no explicit project, core narrows the list to
        // lens projects ∩ the caller's visible domain (ProgramLensScope single source),
        // so the single-project guard is skipped — an unreachable lens yields an honest
        // empty page, never a leak and never 403 (same contract as the case domain).
        if (projectId != 0L || programId <= 0L) {
            guard.assertCanView(user, projectId);
        }
        return Response.builderSuccess(coreDefectLibraryClient.list(projectId, status, severity,
                assigneeId, caseId, keyword, page, size, sortBy, sortDir, programId));
    }

    
    @PostMapping
    @RequirePermission("perm:testops:defect-lib")
    public Response<DefectLibraryView> create(@RequestBody DefectCreateRequest request) {
        CurrentUser user = UserContext.current();
        guard.assertCanWriteCase(user, request.projectId());
        return Response.builderSuccess(coreDefectLibraryClient.create(request));
    }

    
    @GetMapping("/{id}")
    @RequirePermission("perm:testops:defect-lib")
    public Response<DefectLibraryView> get(@PathVariable long id) {
        CurrentUser user = UserContext.current();
        DefectLibraryView defect = coreDefectLibraryClient.get(id);
        guard.assertCanView(user, defect.projectId());
        return Response.builderSuccess(defect);
    }

    
    @PutMapping("/{id}")
    @RequirePermission("perm:testops:defect-lib")
    public Response<DefectLibraryView> update(@PathVariable long id, @RequestBody DefectUpdateRequest request) {
        CurrentUser user = UserContext.current();
        DefectLibraryView defect = coreDefectLibraryClient.get(id);
        guard.assertCanWriteCase(user, defect.projectId());
        return Response.builderSuccess(coreDefectLibraryClient.update(id, request));
    }

 
    @PostMapping("/{id}/transition")
    @RequirePermission("perm:testops:defect-lib")
    public Response<DefectLibraryView> transition(@PathVariable long id,
                                                  @RequestBody DefectTransitionRequest request) {
        CurrentUser user = UserContext.current();
        DefectLibraryView defect = coreDefectLibraryClient.get(id);
        guard.assertCanTransitionDefect(user, defect, request.toStatus());
        return Response.builderSuccess(coreDefectLibraryClient.transition(id, request));
    }

 
    @GetMapping("/summary")
    @RequirePermission("perm:testops:defect-lib")
    public Response<DefectSummaryView> summaryByCase(@RequestParam long caseId) {
        CurrentUser user = UserContext.current();
        guard.assertCanView(user, caseProjectResolver.resolveProjectId(caseId));
        return Response.builderSuccess(coreDefectLibraryClient.summaryByCase(caseId));
    }

    
    @PostMapping("/export")
    @RequirePermission("perm:ux:case-export")
    public Response<DefectExportResultView> export(@RequestBody ExportDefectsRequest request) {
        guard.assertCanExportDefect(UserContext.current(), request.projectId());
        return Response.builderSuccess(coreDefectLibraryClient.exportDefects(request));
    }
}
