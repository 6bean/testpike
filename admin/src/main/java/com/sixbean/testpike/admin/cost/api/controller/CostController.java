package com.sixbean.testpike.admin.cost.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.cost.api.dto.BreakOverrideInput;
import com.sixbean.testpike.admin.cost.api.dto.BreakOverrideView;
import com.sixbean.testpike.admin.cost.api.dto.BudgetInput;
import com.sixbean.testpike.admin.cost.api.dto.BudgetView;
import com.sixbean.testpike.admin.cost.api.dto.CostOverviewView;
import com.sixbean.testpike.admin.cost.app.service.CostAdminService;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/v1/cost")
public class CostController {

    private final CostAdminService costAdminService;

    public CostController(CostAdminService costAdminService) {
        this.costAdminService = costAdminService;
    }

    
    @GetMapping("/overview")
    public Response<CostOverviewView> overview(@RequestParam String month,
                                               @RequestParam(required = false) Long projectId) {
        CurrentUser user = UserContext.current();
        return Response.builderSuccess(costAdminService.overview(user, month, projectId));
    }

    /**
 * Paged session-attribution rows for the cost dashboard (E9, AO batch
 * phase 3): which conversations burned tokens this month, with the
 * window sum recomputed from the message ledger next to the lifetime
 * session total so a drift reads on the page. Permission posture is the
 * same gate overview runs — no new spectrum here.
 */
    @GetMapping("/session-usage")
    public Response<com.sixbean.testpike.admin.aichat.api.dto.ChatSessionUsagePageMirrorView> sessionUsage(
            @RequestParam String month,
            @RequestParam(required = false) Long projectId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        CurrentUser user = UserContext.current();
        return Response.builderSuccess(
                costAdminService.sessionUsage(user, month, projectId, page, pageSize));
    }

    
    @GetMapping("/budget/{projectId}")
    public Response<BudgetView> getBudget(@PathVariable long projectId) {
        CurrentUser user = UserContext.current();
        return Response.builderSuccess(costAdminService.getBudget(user, projectId));
    }

    
    @PutMapping("/budget/{projectId}")
    public Response<BudgetView> putBudget(@PathVariable long projectId,
                                          @RequestBody BudgetInput input) {
        CurrentUser user = UserContext.current();
        return Response.builderSuccess(costAdminService.putBudget(user, projectId, input));
    }

 
    @RequirePermission("perm:platform:cost-budget")
    @PostMapping("/break-override/{projectId}")
    public Response<BreakOverrideView> breakOverride(@PathVariable long projectId,
                                                     @RequestBody BreakOverrideInput input) {
        CurrentUser user = UserContext.current();
        return Response.builderSuccess(costAdminService.breakOverride(user, projectId, input));
    }
}
