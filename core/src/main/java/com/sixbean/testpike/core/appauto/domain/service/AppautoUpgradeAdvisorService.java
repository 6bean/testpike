package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.core.appauto.api.dto.AppautoUpgradeAdviceRequest;
import com.sixbean.testpike.core.appauto.api.dto.AppautoUpgradeAdviceView;


public interface AppautoUpgradeAdvisorService {

    
    AppautoUpgradeAdviceView advise(AppautoUpgradeAdviceRequest request);
}
