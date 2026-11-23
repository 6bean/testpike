package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.core.appauto.api.dto.AppautoPortabilityInfo;


public interface AppautoPortabilityService {

    
    AppautoPortabilityInfo precheck(long projectId, long caseId, String engineCode);
}
