package com.sixbean.testpike.core.cost.domain.service;

import com.sixbean.testpike.core.cost.api.dto.BreakOverrideInput;
import com.sixbean.testpike.core.cost.api.dto.BreakOverrideView;
import com.sixbean.testpike.core.cost.api.dto.BudgetInput;
import com.sixbean.testpike.core.cost.api.dto.BudgetView;
import com.sixbean.testpike.core.cost.api.dto.CostOverviewView;
import com.sixbean.testpike.core.cost.api.dto.CostWaterView;


public interface CostService {

    
    CostOverviewView overview(String month, Long projectId);

    
    BudgetView getBudget(long projectId);

 
    BudgetView putBudget(Long operatorId, long projectId, BudgetInput input);

    
    CostWaterView waterLevel(long projectId);

    
    BreakOverrideView breakOverride(Long operatorId, long projectId, BreakOverrideInput input);

    
    int clearExpiredOverrides();
}
