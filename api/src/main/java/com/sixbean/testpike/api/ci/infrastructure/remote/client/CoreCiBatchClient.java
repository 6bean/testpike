package com.sixbean.testpike.api.ci.infrastructure.remote.client;

import com.sixbean.testpike.api.ci.api.dto.CiBadgeView;
import com.sixbean.testpike.api.ci.api.dto.CiCreateFromTemplateInput;
import com.sixbean.testpike.api.ci.api.dto.CiEnvOverrideInput;

import java.util.List;
import java.util.Map;


public interface CoreCiBatchClient {

    
    CiBadgeView badge(String badgeToken);

    
    CiBadgeView badgeByBatchId(long batchId);

    
    Map<String, Object> verdict(long batchId);

    
    Map<String, Object> get(long batchId);

    
    List<Map<String, Object>> listCases(long batchId);

    
    List<Map<String, Object>> listTemplates(long projectId);

    
    Map<String, Object> createFromTemplate(CiCreateFromTemplateInput input, long operatorId);

    
    Map<String, Object> start(long batchId, CiEnvOverrideInput envOverrides, long operatorId);
}
