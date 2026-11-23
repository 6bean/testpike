package com.sixbean.testpike.admin.suites.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.suites.api.dto.SuiteCreateRequest;
import com.sixbean.testpike.admin.suites.api.dto.SuiteRunResultView;
import com.sixbean.testpike.admin.suites.api.dto.SuiteUpdateRequest;
import com.sixbean.testpike.admin.suites.api.dto.SuiteView;
import com.sixbean.testpike.admin.suites.infrastructure.remote.client.CoreSuiteClient;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/v1/suites")
public class SuiteController {

    private final CoreSuiteClient coreSuiteClient;
    private final ProjectAccessGuard guard;

    public SuiteController(CoreSuiteClient coreSuiteClient, ProjectAccessGuard guard) {
        this.coreSuiteClient = coreSuiteClient;
        this.guard = guard;
    }

    /** Suite list (member/H1 view path; forwards whitelisted sort params to core). */
    @GetMapping
    public Response<PageView<SuiteView>> list(@RequestParam long projectId,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) Integer status,
                                              @RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "20") int size,
                                              @RequestParam(required = false) String sortBy,
                                              @RequestParam(required = false) String sortDir) {
        CurrentUser user = UserContext.current();
        guard.assertCanView(user, projectId);
        return Response.builderSuccess(coreSuiteClient.list(projectId, keyword, status, page, size, sortBy, sortDir));
    }

    
    @PostMapping
    public Response<SuiteView> create(@RequestBody SuiteCreateRequest request) {
        CurrentUser user = UserContext.current();
        guard.assertCanManage(user, request.projectId());
        return Response.builderSuccess(coreSuiteClient.create(request));
    }

    
    @GetMapping("/{id}")
    public Response<SuiteView> get(@PathVariable long id) {
        CurrentUser user = UserContext.current();
        SuiteView suite = coreSuiteClient.get(id);
        guard.assertCanView(user, suite.projectId());
        return Response.builderSuccess(suite);
    }

    
    @PutMapping("/{id}")
    public Response<SuiteView> update(@PathVariable long id, @RequestBody SuiteUpdateRequest request) {
        CurrentUser user = UserContext.current();
        SuiteView suite = coreSuiteClient.get(id);
        guard.assertCanManage(user, suite.projectId());
        return Response.builderSuccess(coreSuiteClient.update(id, request));
    }

    
    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable long id) {
        CurrentUser user = UserContext.current();
        SuiteView suite = coreSuiteClient.get(id);
        guard.assertCanManage(user, suite.projectId());
        coreSuiteClient.delete(id);
        return Response.builderSuccess(null);
    }

 
    @PostMapping("/{id}/run")
    public Response<SuiteRunResultView> run(@PathVariable long id) {
        CurrentUser user = UserContext.current();
        SuiteView suite = coreSuiteClient.get(id);
        guard.assertCanManage(user, suite.projectId());
        return Response.builderSuccess(coreSuiteClient.run(id));
    }
}
