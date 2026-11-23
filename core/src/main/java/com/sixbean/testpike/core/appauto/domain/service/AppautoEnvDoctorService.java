package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.core.appauto.api.dto.AppautoEngineHealthInfo;

import java.util.List;


public interface AppautoEnvDoctorService {

    
    List<AppautoEngineHealthInfo> health(boolean includeDisabled);
}
