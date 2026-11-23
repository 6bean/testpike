package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.core.appauto.api.dto.AppautoMatrixSampleRequest;
import com.sixbean.testpike.core.appauto.api.dto.AppautoMatrixSampleView;


public interface AppautoMatrixSampleService {

    
    AppautoMatrixSampleView recommend(AppautoMatrixSampleRequest request);
}
