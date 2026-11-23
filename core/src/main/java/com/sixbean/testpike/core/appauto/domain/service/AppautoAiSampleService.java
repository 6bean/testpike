package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.core.appauto.api.dto.AppautoAiSampleCacheHitRequest;
import com.sixbean.testpike.core.appauto.api.dto.AppautoAiSampleInput;
import com.sixbean.testpike.core.appauto.api.dto.AppautoAiSampleRecordRequest;

import java.util.List;
import java.util.Map;


public interface AppautoAiSampleService {

    
    long recordSample(AppautoAiSampleInput input);

    
    Map<String, Object> findCacheHit(long caseId, Long deviceId, int stepNo, String anchor);

    
    List<Map<String, Object>> listSamples(long projectId, long caseId, int limit);

    
    Map<String, Object> hitRateStats(long projectId, long caseId);

    
    long recordFromExecutor(AppautoAiSampleRecordRequest request);

    
    Map<String, Object> cacheHitForExecutor(AppautoAiSampleCacheHitRequest request);
}
