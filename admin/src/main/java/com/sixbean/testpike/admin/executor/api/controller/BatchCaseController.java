package com.sixbean.testpike.admin.executor.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.executor.api.dto.BatchCaseRerunView;
import com.sixbean.testpike.admin.executor.api.dto.ExecTaskView;
import com.sixbean.testpike.admin.executor.infrastructure.remote.client.CoreExecClient;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/v1/batch-cases")
public class BatchCaseController {

    private final CoreExecClient coreExecClient;
    private final ProjectAccessGuard guard;

    public BatchCaseController(CoreExecClient coreExecClient, ProjectAccessGuard guard) {
        this.coreExecClient = coreExecClient;
        this.guard = guard;
    }

    
    @PostMapping("/{id}/rerun")
    public Response<BatchCaseRerunView> rerun(@PathVariable long id,
            @RequestParam(required = false) String engineCode) {
        CurrentUser user = UserContext.current();
        ExecTaskView task = coreExecClient.getTask(id);
        guard.assertCanWriteCase(user, task.projectId());
        return Response.builderSuccess(coreExecClient.rerunBatchCase(id, user.userId(), engineCode));
    }
}
