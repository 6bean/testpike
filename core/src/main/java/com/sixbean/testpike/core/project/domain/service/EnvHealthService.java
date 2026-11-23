package com.sixbean.testpike.core.project.domain.service;

import com.sixbean.testpike.core.project.api.dto.EnvHealthScanResult;
import com.sixbean.testpike.core.project.api.dto.EnvProbeOutcome;

import java.util.Map;


public interface EnvHealthService {

    
    EnvHealthScanResult scanAll();

    
    EnvProbeOutcome scanOne(long envId);

    
    Map<String, Object> getHealth(long envId);

    
    String envHealthWarning(long envId);

    
    int scanCertExpiry();
}
