package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.core.appauto.api.dto.AppautoNlStepRequest;
import com.sixbean.testpike.core.appauto.api.dto.AppautoNlStepView;


public interface AppautoNlStepService {

    
    AppautoNlStepView generate(AppautoNlStepRequest request);
}
