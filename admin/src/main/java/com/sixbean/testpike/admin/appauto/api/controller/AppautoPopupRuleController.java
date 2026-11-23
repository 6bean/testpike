package com.sixbean.testpike.admin.appauto.api.controller;

import com.sixbean.testpike.admin.appauto.api.dto.AppautoPopupRuleView;
import com.sixbean.testpike.admin.appauto.api.dto.PopupRuleLearnRequest;
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
public class AppautoPopupRuleController {

    private final CoreAppautoClient coreAppautoClient;
    private final ProjectAccessGuard guard;

    public AppautoPopupRuleController(CoreAppautoClient coreAppautoClient,
                                      ProjectAccessGuard guard) {
        this.coreAppautoClient = coreAppautoClient;
        this.guard = guard;
    }

    
    @PostMapping("/popup-rules/learn")
    @RequirePermission("perm:appauto:popup-rule:run")
    public Response<Long> learn(@RequestBody PopupRuleLearnRequest req) {
        guard.assertCanView(UserContext.current(), req.projectId());
        return Response.builderSuccess(coreAppautoClient.learnPopupRule(req.taskId()));
    }

    
    @GetMapping("/popup-rules")
    @RequirePermission("perm:project:project-view")
    public Response<List<AppautoPopupRuleView>> list(@RequestParam long projectId,
                                                     @RequestParam(required = false)
                                                     Integer status,
                                                     @RequestParam(defaultValue = "100")
                                                     int limit) {
        guard.assertCanView(UserContext.current(), projectId);
        List<AppautoPopupRuleView> rows = coreAppautoClient.listPopupRules(projectId, status, limit);
        return Response.builderSuccess(rows.stream()
                .filter(row -> Long.valueOf(projectId).equals(row.projectId()))
                .toList());
    }

    
    @GetMapping("/popup-rules/{id}")
    @RequirePermission("perm:project:project-view")
    public Response<AppautoPopupRuleView> get(@PathVariable long id) {
        AppautoPopupRuleView row = coreAppautoClient.findPopupRule(id);
        guard.assertCanView(UserContext.current(), row.projectId());
        return Response.builderSuccess(row);
    }

    
    @PostMapping("/popup-rules/{id}/confirm")
    @RequirePermission("perm:appauto:popup-rule:approve")
    public Response<AppautoPopupRuleView> confirm(@PathVariable long id) {
        AppautoPopupRuleView row = coreAppautoClient.findPopupRule(id);
        guard.assertCanManage(UserContext.current(), row.projectId());
        return Response.builderSuccess(coreAppautoClient.confirmPopupRule(id));
    }

    
    @PostMapping("/popup-rules/{id}/reject")
    @RequirePermission("perm:appauto:popup-rule:approve")
    public Response<Void> reject(@PathVariable long id) {
        AppautoPopupRuleView row = coreAppautoClient.findPopupRule(id);
        guard.assertCanManage(UserContext.current(), row.projectId());
        coreAppautoClient.rejectPopupRule(id);
        return Response.builderSuccess(null);
    }
}
