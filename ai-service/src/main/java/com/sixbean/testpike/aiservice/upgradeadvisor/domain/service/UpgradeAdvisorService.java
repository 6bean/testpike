package com.sixbean.testpike.aiservice.upgradeadvisor.domain.service;

import com.sixbean.testpike.aiservice.upgradeadvisor.api.dto.UpgradeAdvisorRequest;

import java.util.Map;


public interface UpgradeAdvisorService {

    
    Map<String, Object> advise(UpgradeAdvisorRequest request);
}
