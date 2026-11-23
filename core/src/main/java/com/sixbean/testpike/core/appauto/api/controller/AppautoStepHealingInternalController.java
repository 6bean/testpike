package com.sixbean.testpike.core.appauto.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.appauto.api.dto.AppautoStepSuggestionInput;
import com.sixbean.testpike.core.appauto.api.dto.AppautoStepSuggestionView;
import com.sixbean.testpike.core.appauto.domain.service.AppautoStepHealingService;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("/internal/appauto/step-suggestions")
public class AppautoStepHealingInternalController {

    private final AppautoStepHealingService healingService;

    public AppautoStepHealingInternalController(AppautoStepHealingService healingService) {
        this.healingService = healingService;
    }

    
    @PostMapping("/record")
    public Response<Map<String, Object>> record(@RequestBody AppautoStepSuggestionInput input) {
        long id = healingService.record(input);
        return Response.builderSuccess(Map.of("suggestionId", id));
    }

    
    @GetMapping
    public Response<List<AppautoStepSuggestionView>> list(@RequestParam long projectId,
                                                           @RequestParam long caseId,
                                                           @RequestParam(required = false)
                                                           Integer status,
                                                           @RequestParam(defaultValue = "100")
                                                           int limit) {
        return Response.builderSuccess(healingService.list(projectId, caseId, status, limit));
    }

    
    @GetMapping("/{id}")
    public Response<AppautoStepSuggestionView> find(@PathVariable long id) {
        return Response.builderSuccess(healingService.find(id));
    }

    
    @PostMapping("/{id}/adopt")
    public Response<AppautoStepSuggestionView> adopt(@PathVariable long id,
                                                      @RequestParam(required = false) Integer
                                                      expectedScriptVersion) {
        return Response.builderSuccess(
                healingService.adopt(OperatorContext.currentOperatorId(), id,
                        expectedScriptVersion));
    }

    
    @PostMapping("/{id}/reject")
    public Response<Void> reject(@PathVariable long id) {
        healingService.reject(OperatorContext.currentOperatorId(), id);
        return Response.builderSuccess(null);
    }
}
