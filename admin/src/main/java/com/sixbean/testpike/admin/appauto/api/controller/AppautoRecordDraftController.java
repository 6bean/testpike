package com.sixbean.testpike.admin.appauto.api.controller;

import com.sixbean.testpike.admin.appauto.api.dto.AppautoRecordDraftView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoRecordFlowView;
import com.sixbean.testpike.admin.appauto.api.dto.RecordDraftConfirmRequest;
import com.sixbean.testpike.admin.appauto.api.dto.RecordFlowGenerateRequest;
import com.sixbean.testpike.admin.appauto.infrastructure.remote.client.CoreAppautoClient;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
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
@RequestMapping("/api/v1/appauto")
public class AppautoRecordDraftController {

    private final CoreAppautoClient coreAppautoClient;
    private final ProjectAccessGuard guard;

    public AppautoRecordDraftController(CoreAppautoClient coreAppautoClient,
                                        ProjectAccessGuard guard) {
        this.coreAppautoClient = coreAppautoClient;
        this.guard = guard;
    }

    
    @PostMapping("/record-flow")
    @RequirePermission("perm:appauto:record")
    public Response<AppautoRecordFlowView> generate(@RequestBody RecordFlowGenerateRequest req) {
        guard.assertCanView(UserContext.current(), req.projectId());
        return Response.builderSuccess(coreAppautoClient.generateRecordFlow(
                req.taskId(), req.engineCode(), req.eventSource(), req.title(), req.events()));
    }

    
    @GetMapping("/record-drafts")
    @RequirePermission("perm:project:project-view")
    public Response<List<AppautoRecordDraftView>> list(@RequestParam long projectId,
                                                       @RequestParam long caseId,
                                                       @RequestParam(required = false)
                                                       Integer status,
                                                       @RequestParam(defaultValue = "100")
                                                       int limit) {
        guard.assertCanView(UserContext.current(), projectId);
        List<AppautoRecordDraftView> rows = coreAppautoClient.listRecordDrafts(caseId, status, limit);
        return Response.builderSuccess(rows.stream()
                .filter(row -> Long.valueOf(projectId).equals(row.projectId()))
                .toList());
    }

    
    @GetMapping("/record-drafts/{id}")
    @RequirePermission("perm:project:project-view")
    public Response<AppautoRecordDraftView> get(@PathVariable long id) {
        AppautoRecordDraftView row = coreAppautoClient.findRecordDraft(id);
        guard.assertCanView(UserContext.current(), row.projectId());
        return Response.builderSuccess(row);
    }

    
    @PostMapping("/record-drafts/{id}/confirm")
    @RequirePermission("perm:script:script-edit")
    public Response<AppautoRecordDraftView> confirm(@PathVariable long id,
                                                    @RequestBody RecordDraftConfirmRequest req) {
        AppautoRecordDraftView row = coreAppautoClient.findRecordDraft(id);
        guard.assertCanManage(UserContext.current(), row.projectId());
        guard.assertCanWriteCase(UserContext.current(), row.projectId());
        return Response.builderSuccess(coreAppautoClient.confirmRecordDraft(
                id, req.checkedStepNos(), req.expectedScriptVersion()));
    }

    
    @PostMapping("/record-drafts/{id}/reject")
    @RequirePermission("perm:script:script-edit")
    public Response<Void> reject(@PathVariable long id) {
        AppautoRecordDraftView row = coreAppautoClient.findRecordDraft(id);
        guard.assertCanWriteCase(UserContext.current(), row.projectId());
        coreAppautoClient.rejectRecordDraft(id);
        return Response.builderSuccess(null);
    }
}
