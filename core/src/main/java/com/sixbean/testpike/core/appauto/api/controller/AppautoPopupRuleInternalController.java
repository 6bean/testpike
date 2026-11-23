package com.sixbean.testpike.core.appauto.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.appauto.api.dto.AppautoPopupRuleView;
import com.sixbean.testpike.core.appauto.domain.service.AppautoPopupRuleService;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/internal/appauto")
public class AppautoPopupRuleInternalController {

    private final AppautoPopupRuleService popupRuleService;

    public AppautoPopupRuleInternalController(AppautoPopupRuleService popupRuleService) {
        this.popupRuleService = popupRuleService;
    }

    
    @PostMapping("/popup-rules/learn")
    public Response<Long> learn(@RequestParam long taskId) {
        return Response.builderSuccess(popupRuleService.learn(taskId));
    }

    
    @GetMapping("/popup-rules")
    public Response<List<AppautoPopupRuleView>> list(@RequestParam long projectId,
                                                     @RequestParam(required = false)
                                                     Integer status,
                                                     @RequestParam(defaultValue = "100")
                                                     int limit) {
        return Response.builderSuccess(popupRuleService.list(projectId, status, limit));
    }

    
    @GetMapping("/popup-rules/{id}")
    public Response<AppautoPopupRuleView> find(@PathVariable long id) {
        return Response.builderSuccess(popupRuleService.find(id));
    }

    
    @PostMapping("/popup-rules/{id}/confirm")
    public Response<AppautoPopupRuleView> confirm(@PathVariable long id) {
        return Response.builderSuccess(popupRuleService.confirm(
                OperatorContext.currentOperatorId(), id));
    }

    
    @PostMapping("/popup-rules/{id}/reject")
    public Response<Void> reject(@PathVariable long id) {
        popupRuleService.reject(OperatorContext.currentOperatorId(), id);
        return Response.builderSuccess(null);
    }
}
