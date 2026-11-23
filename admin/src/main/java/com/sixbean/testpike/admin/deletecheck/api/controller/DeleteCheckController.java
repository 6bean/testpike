package com.sixbean.testpike.admin.deletecheck.api.controller;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.cases.api.dto.CaseView;
import com.sixbean.testpike.admin.cases.infrastructure.remote.client.CoreCaseClient;
import com.sixbean.testpike.admin.deletecheck.api.dto.DeleteCheckView;
import com.sixbean.testpike.admin.deletecheck.infrastructure.remote.client.CoreDeleteCheckClient;
import com.sixbean.testpike.admin.plans.api.dto.PlanView;
import com.sixbean.testpike.admin.plans.infrastructure.remote.client.CorePlanClient;
import com.sixbean.testpike.admin.project.api.dto.ProjectEnvView;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.project.infrastructure.remote.client.CoreProjectClient;
import com.sixbean.testpike.admin.rbac.infrastructure.remote.client.CoreRoleClient;
import com.sixbean.testpike.admin.tags.api.dto.TagView;
import com.sixbean.testpike.admin.tags.infrastructure.remote.client.CoreTagClient;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/v1/delete-check")
public class DeleteCheckController {

    private final CoreDeleteCheckClient coreDeleteCheckClient;
    private final ProjectAccessGuard guard;
    private final CorePlanClient corePlanClient;
    private final CoreProjectClient coreProjectClient;
    private final CoreCaseClient coreCaseClient;
    private final CoreTagClient coreTagClient;

    public DeleteCheckController(CoreDeleteCheckClient coreDeleteCheckClient, ProjectAccessGuard guard,
                                 CorePlanClient corePlanClient, CoreProjectClient coreProjectClient,
                                 CoreCaseClient coreCaseClient, CoreTagClient coreTagClient) {
        this.coreDeleteCheckClient = coreDeleteCheckClient;
        this.guard = guard;
        this.corePlanClient = corePlanClient;
        this.coreProjectClient = coreProjectClient;
        this.coreCaseClient = coreCaseClient;
        this.coreTagClient = coreTagClient;
    }

    
    @GetMapping("/projects/{id}")
    public Response<DeleteCheckView> checkProject(@PathVariable long id) {
        guard.assertCanManage(UserContext.current(), id);
        return Response.builderSuccess(coreDeleteCheckClient.checkProject(id));
    }

    
    @GetMapping("/envs/{id}")
    public Response<DeleteCheckView> checkEnv(@PathVariable long id) {
        ProjectEnvView env = coreProjectClient.getEnvById(id);
        guard.assertCanManage(UserContext.current(), env.projectId());
        return Response.builderSuccess(coreDeleteCheckClient.checkEnv(id));
    }

    
    @GetMapping("/roles/{id}")
    @RequirePermission("perm:system:role-manage")
    public Response<DeleteCheckView> checkRole(@PathVariable long id) {
        return Response.builderSuccess(coreDeleteCheckClient.checkRole(id));
    }

    
    @GetMapping("/plans/{id}")
    public Response<DeleteCheckView> checkPlan(@PathVariable long id) {
        PlanView plan = corePlanClient.get(id);
        guard.assertCanManage(UserContext.current(), plan.projectId());
        return Response.builderSuccess(coreDeleteCheckClient.checkPlan(id));
    }

    
    @GetMapping("/tags/{id}")
    public Response<DeleteCheckView> checkTag(@PathVariable long id) {
        TagView tag = coreTagClient.getTag(id);
        guard.assertCanWriteCase(UserContext.current(), tag.projectId());
        return Response.builderSuccess(coreDeleteCheckClient.checkTag(id));
    }

    
    @GetMapping("/cases/{id}")
    public Response<DeleteCheckView> checkCase(@PathVariable long id) {
        CaseView view = coreCaseClient.getCase(id);
        guard.assertCanDeleteCase(UserContext.current(), view.projectId());
        return Response.builderSuccess(coreDeleteCheckClient.checkCase(id));
    }
}
