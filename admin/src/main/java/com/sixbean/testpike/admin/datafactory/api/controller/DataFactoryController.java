package com.sixbean.testpike.admin.datafactory.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.datafactory.api.dto.DataFactoryCreateRequest;
import com.sixbean.testpike.admin.datafactory.api.dto.DataFactoryRecordView;
import com.sixbean.testpike.admin.datafactory.api.dto.DataFactoryRunResultView;
import com.sixbean.testpike.admin.datafactory.api.dto.DataFactoryRunView;
import com.sixbean.testpike.admin.datafactory.api.dto.DataFactoryUpdateRequest;
import com.sixbean.testpike.admin.datafactory.api.dto.DataPoolItemView;
import com.sixbean.testpike.admin.datafactory.api.dto.GenFunctionGroupView;
import com.sixbean.testpike.admin.datafactory.infrastructure.remote.client.CoreDataFactoryClient;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
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

import java.util.List;


@RestController
@RequestMapping("/api/v1/data-factories")
public class DataFactoryController {

    private final CoreDataFactoryClient coreDataFactoryClient;
    private final ProjectAccessGuard guard;

    public DataFactoryController(CoreDataFactoryClient coreDataFactoryClient, ProjectAccessGuard guard) {
        this.coreDataFactoryClient = coreDataFactoryClient;
        this.guard = guard;
    }

    
    @GetMapping
    public Response<PageView<DataFactoryRecordView>> list(@RequestParam long projectId,
                                                          @RequestParam(required = false) String keyword,
                                                          @RequestParam(required = false) Integer type,
                                                          @RequestParam(defaultValue = "1") int page,
                                                          @RequestParam(defaultValue = "20") int size,
                                                          @RequestParam(required = false) String sortBy,
                                                          @RequestParam(required = false) String sortDir) {
        CurrentUser user = UserContext.current();
        guard.assertCanView(user, projectId);
        return Response.builderSuccess(
                coreDataFactoryClient.list(projectId, keyword, type, page, size, sortBy, sortDir));
    }

    
    @PostMapping
    public Response<DataFactoryRecordView> create(@RequestBody DataFactoryCreateRequest request) {
        CurrentUser user = UserContext.current();
        guard.assertCanManage(user, request.projectId());
        return Response.builderSuccess(coreDataFactoryClient.create(request));
    }

 
    @GetMapping("/genkit-catalog")
    public Response<List<GenFunctionGroupView>> genkitCatalog() {
        return Response.builderSuccess(coreDataFactoryClient.genkitCatalog());
    }

    
    @GetMapping("/{id}")
    public Response<DataFactoryRecordView> get(@PathVariable long id) {
        CurrentUser user = UserContext.current();
        DataFactoryRecordView factory = coreDataFactoryClient.get(id);
        guard.assertCanView(user, factory.projectId());
        return Response.builderSuccess(factory);
    }

    
    @PutMapping("/{id}")
    public Response<DataFactoryRecordView> update(@PathVariable long id,
                                                  @RequestBody DataFactoryUpdateRequest request) {
        CurrentUser user = UserContext.current();
        DataFactoryRecordView factory = coreDataFactoryClient.get(id);
        guard.assertCanManage(user, factory.projectId());
        coreDataFactoryClient.update(id, request);
        return Response.builderSuccess(coreDataFactoryClient.get(id));
    }

    
    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable long id) {
        CurrentUser user = UserContext.current();
        DataFactoryRecordView factory = coreDataFactoryClient.get(id);
        guard.assertCanManage(user, factory.projectId());
        coreDataFactoryClient.delete(id);
        return Response.builderSuccess(null);
    }

 
    @PostMapping("/{id}/run")
    public Response<DataFactoryRunResultView> run(@PathVariable long id) {
        CurrentUser user = UserContext.current();
        DataFactoryRecordView factory = coreDataFactoryClient.get(id);
        guard.assertCanView(user, factory.projectId());
        return Response.builderSuccess(coreDataFactoryClient.run(id));
    }

    
    @GetMapping("/{id}/runs")
    public Response<PageView<DataFactoryRunView>> runs(@PathVariable long id,
                                                       @RequestParam long projectId,
                                                       @RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        CurrentUser user = UserContext.current();
        guard.assertCanView(user, projectId);
        return Response.builderSuccess(coreDataFactoryClient.runs(id, projectId, page, size));
    }

    
    @GetMapping("/{id}/recent-runs")
    public Response<List<DataFactoryRunView>> recentRuns(@PathVariable long id,
                                                         @RequestParam long projectId,
                                                         @RequestParam(defaultValue = "10") int limit) {
        CurrentUser user = UserContext.current();
        guard.assertCanView(user, projectId);
        return Response.builderSuccess(coreDataFactoryClient.recentRuns(id, projectId, limit));
    }


    
    @GetMapping("/pool-items")
    public Response<PageView<DataPoolItemView>> listPoolItems(@RequestParam long projectId,
                                                              @RequestParam(required = false) Long envId,
                                                              @RequestParam(required = false) String type,
                                                              @RequestParam(required = false) Integer status,
                                                              @RequestParam(defaultValue = "1") int page,
                                                              @RequestParam(defaultValue = "20") int size) {
        CurrentUser user = UserContext.current();
        guard.assertCanView(user, projectId);
        return Response.builderSuccess(coreDataFactoryClient.listPoolItems(projectId, envId, type, status, page, size));
    }

    
    @PutMapping("/pool-items/{id}/status")
    public Response<Void> updatePoolItemStatus(@PathVariable long id,
                                               @RequestParam long projectId,
                                               @RequestParam int status) {
        CurrentUser user = UserContext.current();
        guard.assertCanManage(user, projectId);
        coreDataFactoryClient.updatePoolItemStatus(projectId, id, status);
        return Response.builderSuccess(null);
    }

    
    @DeleteMapping("/pool-items/{id}")
    public Response<Void> deletePoolItem(@PathVariable long id, @RequestParam long projectId) {
        CurrentUser user = UserContext.current();
        guard.assertCanManage(user, projectId);
        coreDataFactoryClient.deletePoolItem(projectId, id);
        return Response.builderSuccess(null);
    }
}
