package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.core.appauto.api.dto.AppautoStabilityScanResult;
import com.sixbean.testpike.core.appauto.api.dto.AppautoStabilityView;

import java.util.List;


public interface AppautoStabilityService {

    
    List<AppautoStabilityView> stabilityRows(long projectId, long caseId);

    
    AppautoStabilityScanResult scan();
}
