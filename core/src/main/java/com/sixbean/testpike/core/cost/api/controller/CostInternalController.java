package com.sixbean.testpike.core.cost.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.cost.api.dto.BudgetInput;
import com.sixbean.testpike.core.cost.api.dto.BudgetView;
import com.sixbean.testpike.core.cost.api.dto.CostOverviewView;
import com.sixbean.testpike.core.cost.domain.service.CostService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/internal/cost")
public class CostInternalController {

    private final CostService costService;

    public CostInternalController(CostService costService) {
        this.costService = costService;
    }

 
    @GetMapping("/overview")
    public Response<CostOverviewView> overview(@RequestParam String month,
                                               @RequestParam(required = false) Long projectId) {
        return Response.builderSuccess(costService.overview(month, projectId));
    }

    
    @GetMapping("/budget/{projectId}")
    public Response<BudgetView> getBudget(@PathVariable long projectId) {
        return Response.builderSuccess(costService.getBudget(projectId));
    }

    
    @PutMapping("/budget/{projectId}")
    public Response<BudgetView> putBudget(@PathVariable long projectId,
                                          @RequestBody BudgetInput input) {
        return Response.builderSuccess(
                costService.putBudget(OperatorContext.currentOperatorId(), projectId, input));
    }
}
