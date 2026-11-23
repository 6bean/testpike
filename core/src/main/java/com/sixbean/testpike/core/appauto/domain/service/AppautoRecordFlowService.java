package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.core.appauto.api.dto.AppautoRecordFlowRequest;
import com.sixbean.testpike.core.appauto.api.dto.AppautoRecordFlowView;


public interface AppautoRecordFlowService {

    
    AppautoRecordFlowView generate(AppautoRecordFlowRequest request);
}
