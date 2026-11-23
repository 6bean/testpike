package com.sixbean.testpike.core.datafactory.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.datafactory.api.dto.DataFactoryCreateInput;
import com.sixbean.testpike.core.datafactory.api.dto.DataFactoryInfo;
import com.sixbean.testpike.core.datafactory.api.dto.DataFactoryQuery;
import com.sixbean.testpike.core.datafactory.api.dto.DataFactoryRunInfo;
import com.sixbean.testpike.core.datafactory.api.dto.DataFactoryRunResult;
import com.sixbean.testpike.core.datafactory.api.dto.DataFactoryUpdateInput;
import com.sixbean.testpike.core.datafactory.api.dto.DataPoolItemInfo;
import com.sixbean.testpike.core.datafactory.api.dto.GenFunctionGroupInfo;
import com.sixbean.testpike.core.datafactory.domain.service.DataFactoryService;
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
@RequestMapping("/internal/data-factories")
public class DataFactoryInternalController {

    private final DataFactoryService dataFactoryService;

    public DataFactoryInternalController(DataFactoryService dataFactoryService) {
        this.dataFactoryService = dataFactoryService;
    }

 
    @PostMapping
    public Response<DataFactoryInfo> create(@RequestBody DataFactoryCreateInput input) {
        return Response.builderSuccess(
                dataFactoryService.create(OperatorContext.currentOperatorId(), input));
    }

    
    @GetMapping
    public Response<PageResult<DataFactoryInfo>> list(@RequestParam long projectId,
                                                      @RequestParam(required = false) String keyword,
                                                      @RequestParam(required = false) Integer type,
                                                      @RequestParam(defaultValue = "1") int page,
                                                      @RequestParam(defaultValue = "20") int size,
                                                      @RequestParam(required = false) String sortBy,
                                                      @RequestParam(required = false) String sortDir) {
        Long operator = OperatorContext.currentOperatorId();
        // Internal read doors (ai-service resource-pool segment) may arrive
        // without the header: fold to system operator 0 exactly like the
        // occupy/release doors below — never unbox a null into the long slot.
        return Response.builderSuccess(dataFactoryService.list(operator == null ? 0L : operator,
                new DataFactoryQuery(projectId, keyword, type, page, size, sortBy, sortDir)));
    }

 
    @GetMapping("/genkit-catalog")
    public Response<List<GenFunctionGroupInfo>> genkitCatalog() {
        return Response.builderSuccess(dataFactoryService.genkitCatalog());
    }

    
    @GetMapping("/{id}")
    public Response<DataFactoryInfo> get(@PathVariable long id) {
        return Response.builderSuccess(
                dataFactoryService.get(OperatorContext.currentOperatorId(), id));
    }

    
    @PutMapping("/{id}")
    public Response<Void> update(@PathVariable long id, @RequestBody DataFactoryUpdateInput input) {
        dataFactoryService.update(OperatorContext.currentOperatorId(), id, input);
        return Response.builderSuccess(null);
    }

    
    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable long id) {
        dataFactoryService.delete(OperatorContext.currentOperatorId(), id);
        return Response.builderSuccess(null);
    }

 
    @PostMapping("/{id}/run")
    public Response<DataFactoryRunResult> run(@PathVariable long id) {
        return Response.builderSuccess(
                dataFactoryService.run(OperatorContext.currentOperatorId(), id));
    }

    
    @GetMapping("/{id}/runs")
    public Response<PageResult<DataFactoryRunInfo>> runs(@PathVariable long id,
                                                         @RequestParam long projectId,
                                                         @RequestParam(defaultValue = "1") int page,
                                                         @RequestParam(defaultValue = "20") int size) {
        return Response.builderSuccess(dataFactoryService.runs(OperatorContext.currentOperatorId(),
                projectId, id, page, size));
    }

    
    @GetMapping("/{id}/recent-runs")
    public Response<List<DataFactoryRunInfo>> recentRuns(@PathVariable long id,
                                                         @RequestParam long projectId,
                                                         @RequestParam(defaultValue = "10") int limit) {
        return Response.builderSuccess(dataFactoryService.recentRuns(
                OperatorContext.currentOperatorId(), projectId, id, limit));
    }


    
    @GetMapping("/pool-items")
    public Response<PageResult<DataPoolItemInfo>> listPoolItems(@RequestParam long projectId,
                                                                @RequestParam(required = false) Long envId,
                                                                @RequestParam(required = false) String type,
                                                                @RequestParam(required = false) Integer status,
                                                                @RequestParam(defaultValue = "1") int page,
                                                                @RequestParam(defaultValue = "20") int size) {
        return Response.builderSuccess(dataFactoryService.listPoolItems(
                OperatorContext.currentOperatorId(), projectId, envId, type, status, page, size));
    }

    
    @PutMapping("/pool-items/{id}/status")
    public Response<Void> updatePoolItemStatus(@PathVariable long id,
                                               @RequestParam long projectId,
                                               @RequestParam int status) {
        dataFactoryService.updatePoolItemStatus(OperatorContext.currentOperatorId(), projectId, id, status);
        return Response.builderSuccess(null);
    }

    
    @DeleteMapping("/pool-items/{id}")
    public Response<Void> deletePoolItem(@PathVariable long id, @RequestParam long projectId) {
        dataFactoryService.deletePoolItem(OperatorContext.currentOperatorId(), projectId, id);
        return Response.builderSuccess(null);
    }


    
    @PostMapping("/pool-items/{id}/occupy")
    public Response<Void> occupyPoolItem(@PathVariable long id,
                                         @RequestParam long projectId,
                                         @RequestParam long lockedBy) {
        Long operatorId = OperatorContext.currentOperatorId();
        dataFactoryService.occupy(operatorId == null ? 0L : operatorId, projectId, id, lockedBy);
        return Response.builderSuccess(null);
    }

    
    @PostMapping("/pool-items/{id}/release")
    public Response<Void> releasePoolItem(@PathVariable long id, @RequestParam long projectId) {
        Long operatorId = OperatorContext.currentOperatorId();
        dataFactoryService.release(operatorId == null ? 0L : operatorId, projectId, id);
        return Response.builderSuccess(null);
    }
}
