package com.sixbean.testpike.core.perf.domain.service;

import com.sixbean.testpike.core.perf.api.dto.PerfWhitelistInput;

import java.util.List;
import java.util.Map;


public interface PerfWhitelistService {

    
    long create(long projectId, PerfWhitelistInput input);

    
    void delete(long projectId, long id);

    
    List<Map<String, Object>> list(long projectId);

    
    Map<String, Object> findByEnv(long projectId, long envId);
}
