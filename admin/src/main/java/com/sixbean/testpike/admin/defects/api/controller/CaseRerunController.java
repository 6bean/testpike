package com.sixbean.testpike.admin.defects.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.cases.app.component.CaseProjectResolver;
import com.sixbean.testpike.admin.defects.infrastructure.remote.client.CoreDefectClient;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
public class CaseRerunController {

    private final CoreDefectClient coreDefectClient;
    private final CaseProjectResolver caseProjectResolver;
    private final ProjectAccessGuard guard;

    public CaseRerunController(CoreDefectClient coreDefectClient, CaseProjectResolver caseProjectResolver,
                               ProjectAccessGuard guard) {
        this.coreDefectClient = coreDefectClient;
        this.caseProjectResolver = caseProjectResolver;
        this.guard = guard;
    }

    @PostMapping("/api/v1/cases/{caseId}/rerun-hint")
    public Response<Long> rerunHint(@PathVariable long caseId) {
        CurrentUser user = UserContext.current();
        guard.assertCanWriteCase(user, caseProjectResolver.resolveProjectId(caseId));
        return Response.builderSuccess(coreDefectClient.rerunHint(caseId));
    }
}
