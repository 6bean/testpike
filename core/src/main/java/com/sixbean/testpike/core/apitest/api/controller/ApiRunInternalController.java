package com.sixbean.testpike.core.apitest.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.apitest.api.dto.ApiRunInfo;
import com.sixbean.testpike.core.apitest.api.dto.ResponseCompareView;
import com.sixbean.testpike.core.apitest.api.dto.RunCreateInput;
import com.sixbean.testpike.core.apitest.api.dto.RunResultInput;
import com.sixbean.testpike.core.apitest.domain.service.ApiRunService;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
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
@RequestMapping("/internal/apitest/runs")
public class ApiRunInternalController {

    private final ApiRunService apiRunService;

    public ApiRunInternalController(ApiRunService apiRunService) {
        this.apiRunService = apiRunService;
    }

    
    @PostMapping
    public Response<ApiRunInfo> create(@RequestBody RunCreateInput input) {
        return Response.builderSuccess(
                apiRunService.create(OperatorContext.currentOperatorId(), input));
    }

    
    @GetMapping("/{id}")
    public Response<ApiRunInfo> get(@PathVariable long id) {
        return Response.builderSuccess(apiRunService.get(id));
    }

    
    @GetMapping
    public Response<List<ApiRunInfo>> listByRequest(@RequestParam long requestId) {
        return Response.builderSuccess(apiRunService.listByRequest(requestId));
    }

    /** Compare two runs' recorded response bodies (404 when either side is gone; 400 when no snapshot). */
    @GetMapping("/compare")
    public Response<ResponseCompareView> compare(@RequestParam long left, @RequestParam long right) {
        return Response.builderSuccess(apiRunService.compare(left, right));
    }

    
    @PutMapping("/{id}/result")
    public Response<ApiRunInfo> recordResult(@PathVariable long id,
                                             @RequestBody RunResultInput input) {
        return Response.builderSuccess(apiRunService.recordResult(id,
                input.status() == null ? -1 : input.status(), input.durationMs(),
                input.assertDetailJson(), input.artifactRef(), input.errorMsg()));
    }

    
    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable long id) {
        apiRunService.delete(OperatorContext.currentOperatorId(), id);
        return Response.builderSuccess(null);
    }

    
    @DeleteMapping
    public Response<Void> clearUnpromoted(@RequestParam long requestId) {
        apiRunService.clearUnpromoted(requestId);
        return Response.builderSuccess(null);
    }
}
