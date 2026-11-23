package com.sixbean.testpike.admin.repair.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.cases.app.component.CaseProjectResolver;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.repair.api.dto.ScriptRepairView;
import com.sixbean.testpike.admin.repair.infrastructure.remote.client.ScriptRepairClient;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/v1/script-repairs")
public class ScriptRepairController {

    private final ScriptRepairClient scriptRepairClient;
    private final ProjectAccessGuard guard;
    private final CaseProjectResolver caseProjectResolver;

    public ScriptRepairController(ScriptRepairClient scriptRepairClient,
                                  ProjectAccessGuard guard,
                                  CaseProjectResolver caseProjectResolver) {
        this.scriptRepairClient = scriptRepairClient;
        this.guard = guard;
        this.caseProjectResolver = caseProjectResolver;
    }

    
    @GetMapping
    public Response<PageView<ScriptRepairView>> list(@RequestParam(required = false) Long projectId,
                                                     @RequestParam(required = false) Long caseId,
                                                     @RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        CurrentUser user = UserContext.current();
        Long effectiveProjectId = projectId;
        if (effectiveProjectId == null && caseId != null) {
            effectiveProjectId = caseProjectResolver.resolveProjectId(caseId);
        }
        if (effectiveProjectId == null) {
            if (!guard.isSystemManager(user)) {
                throw new ServiceException("查看跨项目修复记录需系统管理员权限；"
                        + "项目内记录请选择项目或从用例详情进入", BizErrorCode.FORBIDDEN);
            }
        } else {
            guard.assertCanView(user, effectiveProjectId);
        }
        return Response.builderSuccess(
                scriptRepairClient.list(effectiveProjectId, caseId, page, size));
    }
}
