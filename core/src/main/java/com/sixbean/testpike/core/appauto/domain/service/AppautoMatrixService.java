package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.core.appauto.api.dto.AppautoMatrixDispatchResult;
import com.sixbean.testpike.core.appauto.api.dto.AppautoMatrixFanoutInput;
import com.sixbean.testpike.core.appauto.api.dto.AppautoMatrixModelRow;
import com.sixbean.testpike.core.appauto.api.dto.AppautoMatrixPrecheckView;

import java.util.List;


public interface AppautoMatrixService {

    
    AppautoMatrixPrecheckView precheck(AppautoMatrixFanoutInput input);

    
    AppautoMatrixDispatchResult fanout(long operatorId, AppautoMatrixFanoutInput input);

    
    List<AppautoMatrixModelRow> modelReport(long projectId, long caseId);
}
