package com.sixbean.testpike.admin.plans.infrastructure.remote.client;

import com.sixbean.testpike.admin.plans.api.dto.CompletionCheckView;
import com.sixbean.testpike.admin.plans.api.dto.GenerateBatchResultView;
import com.sixbean.testpike.admin.plans.api.dto.PlanAssignRequest;
import com.sixbean.testpike.admin.plans.api.dto.PlanCaseView;
import com.sixbean.testpike.admin.plans.api.dto.PlanCreateRequest;
import com.sixbean.testpike.admin.plans.api.dto.PlanDeviationView;
import com.sixbean.testpike.admin.plans.api.dto.PlanDeviationRequest;
import com.sixbean.testpike.admin.plans.api.dto.PlanProgressView;
import com.sixbean.testpike.admin.plans.api.dto.PlanSummaryRow;
import com.sixbean.testpike.admin.plans.api.dto.PlanUpdateRequest;
import com.sixbean.testpike.admin.plans.api.dto.PlanView;
import com.sixbean.testpike.admin.project.api.dto.PageView;

import java.util.List;


public interface CorePlanClient {

    
    PlanView create(PlanCreateRequest request);

    
    PlanView update(long id, PlanUpdateRequest request);

    
    PlanView get(long id);

    /** Plan list (project isolation + filters + paging + whitelisted sorting). */
    PageView<PlanView> list(long projectId, Integer status, String keyword, int page, int size,
                             String sortBy, String sortDir);

    
    List<PlanCaseView> listCases(long planId, Integer status);

    
    int assign(long planId, PlanAssignRequest request);

    
    PlanView publish(long planId);

    
    GenerateBatchResultView generateBatches(long planId);

    
    PlanProgressView progress(long planId);

    
    List<PlanSummaryRow> progressSummary(long projectId);

    
    CompletionCheckView completionCheck(long planId);

    
    void cancel(long planId);

    
    void delete(long planId);

    
    void delete(long planId, boolean force);

    
    PlanDeviationView registerDeviation(long planId, PlanDeviationRequest request);

    
    List<PlanDeviationView> listDeviations(long planId);

    
    void validateCaseAllowed(long planId, long caseId);
}
