package com.sixbean.testpike.core.visual.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.visual.api.dto.VisualBaselineInfo;
import com.sixbean.testpike.core.visual.domain.service.VisualBaselineService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/internal/visual-baselines")
public class VisualBaselineInternalController {

    private final VisualBaselineService visualBaselineService;

    public VisualBaselineInternalController(VisualBaselineService visualBaselineService) {
        this.visualBaselineService = visualBaselineService;
    }

    
    @GetMapping
    public Response<List<VisualBaselineInfo>> listByCase(@RequestParam long projectId,
                                                         @RequestParam long caseId) {
        return Response.builderSuccess(visualBaselineService.listByCase(projectId, caseId));
    }

    
    @GetMapping("/{id}")
    public Response<VisualBaselineInfo> getById(@PathVariable long id) {
        return Response.builderSuccess(visualBaselineService.getById(id));
    }

    
    @PostMapping("/{id}/confirm")
    public Response<VisualBaselineInfo> confirm(@PathVariable long id) {
        return Response.builderSuccess(
                visualBaselineService.confirm(id, OperatorContext.currentOperatorId()));
    }

    
    @PostMapping("/{id}/reject")
    public Response<Void> reject(@PathVariable long id) {
        visualBaselineService.reject(id, OperatorContext.currentOperatorId());
        return Response.builderSuccess(null);
    }

    
    @GetMapping("/{id}/view-url")
    public Response<String> viewUrl(@PathVariable long id) {
        return Response.builderSuccess(visualBaselineService.viewUrl(id));
    }
}
