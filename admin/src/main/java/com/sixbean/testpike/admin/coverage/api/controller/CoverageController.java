package com.sixbean.testpike.admin.coverage.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.coverage.api.dto.CoverageMatrixView;
import com.sixbean.testpike.admin.coverage.infrastructure.remote.client.CoreCoverageClient;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/v1/projects")
public class CoverageController {

    private final CoreCoverageClient coreCoverageClient;
    private final ProjectAccessGuard guard;

    public CoverageController(CoreCoverageClient coreCoverageClient, ProjectAccessGuard guard) {
        this.coreCoverageClient = coreCoverageClient;
        this.guard = guard;
    }

    
    @GetMapping("/{projectId}/coverage")
    @RequirePermission("perm:batch:coverage-matrix")
    public Response<CoverageMatrixView> matrix(@PathVariable long projectId,
                                               @RequestParam(required = false) String chapter,
                                               @RequestParam(required = false) Integer priority,
                                               @RequestParam(required = false) String endType) {
        CurrentUser user = UserContext.current();
        guard.assertCanViewCoverage(user, projectId);
        return Response.builderSuccess(coreCoverageClient.matrix(projectId, chapter, priority, endType));
    }
}
