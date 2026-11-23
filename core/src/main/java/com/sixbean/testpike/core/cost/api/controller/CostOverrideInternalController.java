package com.sixbean.testpike.core.cost.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.cost.api.dto.BreakOverrideInput;
import com.sixbean.testpike.core.cost.api.dto.BreakOverrideView;
import com.sixbean.testpike.core.cost.domain.service.CostService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/internal/cost")
public class CostOverrideInternalController {

    private final CostService costService;

    public CostOverrideInternalController(CostService costService) {
        this.costService = costService;
    }

    
    @PostMapping("/break-override/{projectId}")
    public Response<BreakOverrideView> breakOverride(@PathVariable long projectId,
                                                     @RequestBody BreakOverrideInput input) {
        return Response.builderSuccess(
                costService.breakOverride(OperatorContext.currentOperatorId(), projectId, input));
    }

    
    @PostMapping("/override-expire/run")
    public Response<Integer> runOverrideExpireScan() {
        return Response.builderSuccess(costService.clearExpiredOverrides());
    }
}
