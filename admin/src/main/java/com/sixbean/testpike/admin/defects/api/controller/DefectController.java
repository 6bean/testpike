package com.sixbean.testpike.admin.defects.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.cases.app.component.CaseProjectResolver;
import com.sixbean.testpike.admin.defects.api.dto.DefectLinkRequest;
import com.sixbean.testpike.admin.defects.api.dto.DefectStatusRequest;
import com.sixbean.testpike.admin.defects.api.dto.DefectView;
import com.sixbean.testpike.admin.defects.infrastructure.remote.client.CoreDefectClient;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.common.clientobject.Response;
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
@RequestMapping("/api/v1/defects")
public class DefectController {

    private final CoreDefectClient coreDefectClient;
    private final CaseProjectResolver caseProjectResolver;
    private final ProjectAccessGuard guard;

    public DefectController(CoreDefectClient coreDefectClient, CaseProjectResolver caseProjectResolver,
                            ProjectAccessGuard guard) {
        this.coreDefectClient = coreDefectClient;
        this.caseProjectResolver = caseProjectResolver;
        this.guard = guard;
    }

    
    @PostMapping
    public Response<DefectView> link(@RequestBody DefectLinkRequest request) {
        CurrentUser user = UserContext.current();
        guard.assertCanWriteCase(user, caseProjectResolver.resolveProjectId(request.caseId()));
        return Response.builderSuccess(coreDefectClient.link(request));
    }

 
    @PutMapping("/{id}/status")
    public Response<DefectView> updateStatus(@PathVariable long id, @RequestBody DefectStatusRequest request) {
        CurrentUser user = UserContext.current();
        DefectView defect = coreDefectClient.get(id);
        guard.assertCanBackfillDefectStatus(user, caseProjectResolver.resolveProjectId(defect.caseId()),
                request.defectStatus());
        return Response.builderSuccess(coreDefectClient.updateStatus(id, request));
    }

    
    @GetMapping
    public Response<List<DefectView>> listByCase(@RequestParam long caseId) {
        CurrentUser user = UserContext.current();
        guard.assertCanView(user, caseProjectResolver.resolveProjectId(caseId));
        return Response.builderSuccess(coreDefectClient.listByCase(caseId));
    }

    
    @GetMapping("/{id}")
    public Response<DefectView> get(@PathVariable long id) {
        CurrentUser user = UserContext.current();
        DefectView defect = coreDefectClient.get(id);
        guard.assertCanView(user, caseProjectResolver.resolveProjectId(defect.caseId()));
        return Response.builderSuccess(defect);
    }
}
