package com.sixbean.testpike.core.suites.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.suites.api.dto.SuiteCreateInput;
import com.sixbean.testpike.core.suites.api.dto.SuiteInfo;
import com.sixbean.testpike.core.suites.api.dto.SuiteQuery;
import com.sixbean.testpike.core.suites.api.dto.SuiteRunResult;
import com.sixbean.testpike.core.suites.api.dto.SuiteUpdateInput;
import com.sixbean.testpike.core.suites.domain.service.RegressionSuiteService;
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
@RequestMapping("/internal/suites")
public class SuiteInternalController {

    private final RegressionSuiteService suiteService;

    public SuiteInternalController(RegressionSuiteService suiteService) {
        this.suiteService = suiteService;
    }

    
    @PostMapping
    public Response<SuiteInfo> create(@RequestBody SuiteCreateInput input) {
        return Response.builderSuccess(
                suiteService.create(OperatorContext.currentOperatorId(), input));
    }

    
    @PutMapping("/{id}")
    public Response<SuiteInfo> update(@PathVariable long id, @RequestBody SuiteUpdateInput input) {
        return Response.builderSuccess(
                suiteService.update(OperatorContext.currentOperatorId(), id, input));
    }

    
    @GetMapping("/{id}")
    public Response<SuiteInfo> get(@PathVariable long id) {
        return Response.builderSuccess(suiteService.get(id));
    }

    /** Suite list (H1 project isolation, name search, status filter, paging, whitelisted sorting). */
    @GetMapping
    public Response<PageResult<SuiteInfo>> list(@RequestParam long projectId,
                                                @RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) Integer status,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "20") int size,
                                                @RequestParam(required = false) String sortBy,
                                                @RequestParam(required = false) String sortDir) {
        return Response.builderSuccess(
                suiteService.list(new SuiteQuery(projectId, keyword, status, page, size, sortBy, sortDir)));
    }

    
    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable long id) {
        suiteService.delete(OperatorContext.currentOperatorId(), id);
        return Response.builderSuccess(null);
    }

 
    @PostMapping("/{id}/run")
    public Response<SuiteRunResult> run(@PathVariable long id) {
        return Response.builderSuccess(
                suiteService.run(OperatorContext.currentOperatorId(), id));
    }
}
