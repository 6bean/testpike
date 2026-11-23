package com.sixbean.testpike.core.requirement.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.requirement.api.dto.TraceChain;
import com.sixbean.testpike.core.requirement.domain.service.TraceChainService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;


@RestController
public class TraceChainInternalController {

    private final TraceChainService traceChainService;

    public TraceChainInternalController(TraceChainService traceChainService) {
        this.traceChainService = traceChainService;
    }

    
    @GetMapping("/internal/requirements/{id}/trace-chain")
    public Response<TraceChain> requirementChain(@PathVariable long id) {
        return Response.builderSuccess(traceChainService.traceChainOfRequirement(id));
    }

    
    @GetMapping("/internal/cases/{caseId}/trace-chain")
    public Response<TraceChain> caseChain(@PathVariable long caseId) {
        return Response.builderSuccess(traceChainService.traceChainOfCase(caseId));
    }
}
