package com.sixbean.testpike.admin.cost.app.service;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.cost.api.dto.BreakOverrideInput;
import com.sixbean.testpike.admin.cost.api.dto.BreakOverrideView;
import com.sixbean.testpike.admin.cost.api.dto.BudgetInput;
import com.sixbean.testpike.admin.cost.api.dto.BudgetView;
import com.sixbean.testpike.admin.cost.api.dto.CostOverviewView;


public interface CostAdminService {

    
    CostOverviewView overview(CurrentUser user, String month, Long projectId);

    
    BudgetView getBudget(CurrentUser user, long projectId);

    
    BudgetView putBudget(CurrentUser user, long projectId, BudgetInput input);

    /**
 * Paged session-attribution rows for the cost dashboard (E9, AO batch
 * phase 3). Same permission posture as overview: a null projectId is
 * the global view and only ADMIN may ask; a named segment needs that
 * project's manager (guard single point, no second copy). Window-token
 * recomputation and value refusals stay core-side — this layer only
 * orchestrates.
 */
    com.sixbean.testpike.admin.aichat.api.dto.ChatSessionUsagePageMirrorView sessionUsage(
            CurrentUser user, String month, Long projectId, int page, int pageSize);

 
    BreakOverrideView breakOverride(CurrentUser user, long projectId, BreakOverrideInput input);
}
