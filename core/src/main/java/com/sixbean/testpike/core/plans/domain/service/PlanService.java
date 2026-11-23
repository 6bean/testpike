package com.sixbean.testpike.core.plans.domain.service;

import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.plans.api.dto.CompletionCheckView;
import com.sixbean.testpike.core.plans.api.dto.GenerateBatchResult;
import com.sixbean.testpike.core.plans.api.dto.PlanAssignInput;
import com.sixbean.testpike.core.plans.api.dto.PlanCaseInfo;
import com.sixbean.testpike.core.plans.api.dto.PlanCreateInput;
import com.sixbean.testpike.core.plans.api.dto.PlanDeviationInfo;
import com.sixbean.testpike.core.plans.api.dto.PlanInfo;
import com.sixbean.testpike.core.plans.api.dto.PlanProgressView;
import com.sixbean.testpike.core.plans.api.dto.PlanQuery;
import com.sixbean.testpike.core.plans.api.dto.PlanSummaryRow;
import com.sixbean.testpike.core.plans.api.dto.PlanUpdateInput;

import java.util.List;


public interface PlanService {

    
    PlanInfo create(long operatorId, PlanCreateInput input);

 
    PlanInfo update(long operatorId, long id, PlanUpdateInput input);

    
    PlanInfo get(long id);

    
    PageResult<PlanInfo> list(PlanQuery query);

    
    List<PlanCaseInfo> listCases(long planId, Integer status);

    
    int assignCases(long operatorId, long planId, PlanAssignInput input);

    
    PlanInfo publish(long operatorId, long id);

    
    GenerateBatchResult generateBatches(long operatorId, long id);

    
    PlanProgressView progress(long id);

 
    List<PlanSummaryRow> progressSummary(long projectId);

    
    CompletionCheckView completionCheck(long operatorId, long id);

 
    void cancel(long operatorId, long id);

    
    void delete(long operatorId, long id);

    
    void delete(long operatorId, long id, boolean force);

    
    PlanDeviationInfo registerDeviation(long operatorId, long planId, long caseId,
                                        int deviationType, String reason);

    
    List<PlanDeviationInfo> listDeviations(long planId);

    
    void validateCaseAllowed(long planId, long caseId);
}
