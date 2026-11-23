package com.sixbean.testpike.core.apitest.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.apitest.api.dto.StepAliasInfo;
import com.sixbean.testpike.core.apitest.api.dto.StepAliasInput;
import com.sixbean.testpike.core.apitest.domain.service.StepAliasService;
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
import java.util.Map;


@RestController
@RequestMapping("/internal/apitest/step-aliases")
public class StepAliasInternalController {

    private final StepAliasService stepAliasService;

    public StepAliasInternalController(StepAliasService stepAliasService) {
        this.stepAliasService = stepAliasService;
    }

    
    @PostMapping
    public Response<StepAliasInfo> create(@RequestBody StepAliasInput input) {
        return Response.builderSuccess(
                stepAliasService.create(OperatorContext.currentOperatorId(), input));
    }

    
    @PutMapping("/{id}")
    public Response<StepAliasInfo> update(@PathVariable long id, @RequestBody Map<String, String> body) {
        return Response.builderSuccess(stepAliasService.update(
                OperatorContext.currentOperatorId(), id,
                body.get("kind"), body.get("target"), body.get("ownerRef")));
    }

    
    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable long id) {
        stepAliasService.delete(OperatorContext.currentOperatorId(), id);
        return Response.builderSuccess(null);
    }

    
    @GetMapping
    public Response<List<StepAliasInfo>> list(@RequestParam long projectId) {
        return Response.builderSuccess(stepAliasService.list(projectId));
    }

    
    @GetMapping("/{id}")
    public Response<StepAliasInfo> find(@PathVariable long id) {
        return Response.builderSuccess(stepAliasService.find(id));
    }
}
