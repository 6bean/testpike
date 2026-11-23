package com.sixbean.testpike.core.apitest.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.apitest.api.dto.ApiRequestInfo;
import com.sixbean.testpike.core.apitest.api.dto.FlowTemplateInfo;
import com.sixbean.testpike.core.apitest.api.dto.FlowTemplatePageView;
import com.sixbean.testpike.core.apitest.api.dto.FlowTemplateSaveInput;
import com.sixbean.testpike.core.apitest.domain.service.FlowTemplateService;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Internal endpoints for flow templates ; core service layer,
 * consumed only by the admin bridge — executor traffic never reaches this port).
 * <p>Operator id arrives via X-Operator-Id (audit closure, same posture as the
 * run-set controller). Project membership guards live admin-side; existence,
 * shape and save-gate validation stay inside the service (single source) —
 * this controller only receives, delegates, wraps.
 */
@RestController
@RequestMapping("/internal/apitest/flow-templates")
public class FlowTemplateInternalController {

    private final FlowTemplateService flowTemplateService;

    public FlowTemplateInternalController(FlowTemplateService flowTemplateService) {
        this.flowTemplateService = flowTemplateService;
    }

    /** Freeze a collection's members into a new template (empty collection 400). */
    @PostMapping
    public Response<FlowTemplateInfo> save(@RequestBody FlowTemplateSaveInput input) {
        return Response.builderSuccess(
                flowTemplateService.save(OperatorContext.currentOperatorId(), input));
    }

    /** One paged window of the project's templates, newest first (S-2 door). */
    @GetMapping
    public Response<FlowTemplatePageView> list(@RequestParam long projectId,
                                               @RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "20") int pageSize) {
        return Response.builderSuccess(flowTemplateService.list(projectId, page, pageSize));
    }

    /** Template detail (404 when missing). */
    @GetMapping("/{id}")
    public Response<FlowTemplateInfo> get(@PathVariable long id) {
        return Response.builderSuccess(flowTemplateService.get(id));
    }

    /** Instantiate the snapshot into a target collection (created rows returned). */
    @PostMapping("/{id}/instantiate")
    public Response<List<ApiRequestInfo>> instantiate(@PathVariable long id,
                                                      @RequestBody Map<String, Long> body) {
        Long targetCollectionId = body == null ? null : body.get("targetCollectionId");
        return Response.builderSuccess(flowTemplateService.instantiate(
                OperatorContext.currentOperatorId(), id,
                targetCollectionId == null ? -1L : targetCollectionId));
    }

    /** Delete a template (404 when missing). */
    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable long id) {
        flowTemplateService.delete(OperatorContext.currentOperatorId(), id);
        return Response.builderSuccess(null);
    }
}
