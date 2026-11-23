package com.sixbean.testpike.core.llmconfig.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.llmconfig.api.dto.LlmConfigBrief;
import com.sixbean.testpike.core.llmconfig.api.dto.LlmConfigInfo;
import com.sixbean.testpike.core.llmconfig.api.dto.LlmConfigInput;
import com.sixbean.testpike.core.llmconfig.api.dto.ProjectLlmConfigInfo;
import com.sixbean.testpike.core.llmconfig.api.dto.ProjectLlmConfigInput;
import com.sixbean.testpike.core.llmconfig.domain.service.LlmConfigService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/internal")
public class LlmConfigInternalController {

    private final LlmConfigService llmConfigService;

    public LlmConfigInternalController(LlmConfigService llmConfigService) {
        this.llmConfigService = llmConfigService;
    }

    @GetMapping("/llm-configs")
    public Response<List<LlmConfigInfo>> list(
            @RequestParam(value = "modelType", required = false) Integer modelType) {
        return Response.builderSuccess(llmConfigService.list(modelType));
    }

    
    @GetMapping("/llm-configs/briefs")
    public Response<List<LlmConfigBrief>> listEnabledBriefs(
            @RequestParam(value = "modelType", required = false) Integer modelType) {
        return Response.builderSuccess(llmConfigService.listEnabledBriefs(modelType));
    }

    @GetMapping("/llm-configs/{id}")
    public Response<LlmConfigInfo> get(@PathVariable long id) {
        return Response.builderSuccess(llmConfigService.get(id));
    }

    @PostMapping("/llm-configs")
    public Response<LlmConfigInfo> create(@RequestBody LlmConfigInput input,
                                          @RequestHeader(value = "X-Operator-Id", required = false) Long operatorId) {
        return Response.builderSuccess(llmConfigService.create(resolveOperator(operatorId), input));
    }

    @PutMapping("/llm-configs/{id}")
    public Response<LlmConfigInfo> update(@PathVariable long id, @RequestBody LlmConfigInput input,
                                          @RequestHeader(value = "X-Operator-Id", required = false) Long operatorId) {
        return Response.builderSuccess(llmConfigService.update(resolveOperator(operatorId), id, input));
    }

    @DeleteMapping("/llm-configs/{id}")
    public Response<Void> delete(@PathVariable long id) {
        llmConfigService.delete(id);
        return Response.builderSuccess(null);
    }

 
    @GetMapping("/projects/{projectId}/llm-config")
    public Response<ProjectLlmConfigInfo> getProjectConfig(@PathVariable long projectId) {
        return Response.builderSuccess(llmConfigService.getProjectConfig(projectId));
    }

 
    @PutMapping("/projects/{projectId}/llm-config")
    public Response<ProjectLlmConfigInfo> updateProjectConfig(@PathVariable long projectId,
                                                              @RequestBody ProjectLlmConfigInput input,
                                                              @RequestHeader(value = "X-Operator-Id", required = false) Long operatorId) {
        return Response.builderSuccess(llmConfigService.updateProjectConfig(
                resolveOperator(operatorId), projectId, input.llmConfigId()));
    }

 
    @GetMapping("/projects/{projectId}/llm-config/brief")
    public Response<LlmConfigBrief> resolveProjectBrief(@PathVariable long projectId) {
        return Response.builderSuccess(llmConfigService.resolveProjectBrief(projectId));
    }

 
    @GetMapping("/projects/{projectId}/embedding-config/brief")
    public Response<LlmConfigBrief> resolveProjectEmbeddingBrief(@PathVariable long projectId) {
        return Response.builderSuccess(llmConfigService.resolveProjectEmbeddingBrief(projectId));
    }

    private Long resolveOperator(Long operatorId) {
        if (operatorId != null) {
            return operatorId;
        }
        Long ctx = OperatorContext.currentOperatorId();
        return ctx != null ? ctx : 0L;
    }
}
