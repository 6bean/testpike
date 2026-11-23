package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.core.appauto.api.dto.AppautoEngineAvailabilityInfo;

import java.util.List;


public interface AppautoAvailabilityService {

    
    List<AppautoEngineAvailabilityInfo> availability(boolean includeDisabled);
}
