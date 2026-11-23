package com.sixbean.testpike.admin.cost.infrastructure.remote.client;

import com.sixbean.testpike.admin.cost.api.dto.BreakOverrideInput;
import com.sixbean.testpike.admin.cost.api.dto.BreakOverrideView;
import com.sixbean.testpike.admin.cost.api.dto.BudgetInput;
import com.sixbean.testpike.admin.cost.api.dto.BudgetView;
import com.sixbean.testpike.admin.cost.api.dto.CostOverviewView;


public interface CoreCostClient {

    
    CostOverviewView overview(String month, Long projectId);

    
    BudgetView getBudget(long projectId);

    
    BudgetView putBudget(long operatorId, long projectId, BudgetInput input);

    
    BreakOverrideView breakOverride(long operatorId, long projectId, BreakOverrideInput input);
}
