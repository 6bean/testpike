package com.sixbean.testpike.core.apitest.domain.service;

import com.sixbean.testpike.core.apitest.api.dto.ApiRunInfo;
import com.sixbean.testpike.core.apitest.api.dto.ResponseCompareView;
import com.sixbean.testpike.core.apitest.api.dto.RunCreateInput;

import java.util.List;

/**
 * <p>Status 4 = honest skip : an unsatisfied request
 * precondition, or a hop suppressed by the collection interruption mode.
 */
public interface ApiRunService {

    
    ApiRunInfo create(Long operatorId, RunCreateInput input);

    
    ApiRunInfo get(long runId);

    
    List<ApiRunInfo> listByRequest(long requestId);

    
    ApiRunInfo recordResult(long runId, int status, Long durationMs, String assertDetailJson,
                            String artifactRef, String errorMsg);

    
    ApiRunInfo recordResult(long runId, int status, Long durationMs, String assertDetailJson,
                            String artifactRef, String errorMsg, String storedVarsJson);

    
    void delete(Long operatorId, long runId);

    
    void clearUnpromoted(long requestId);

    /**
     * Compare the recorded response bodies of two runs (W-c5 run-history diff).
     * <p>Either side missing → 404; a run without a dispatched exec task or
     * without a step-1 response snapshot → 400 (honest "nothing to compare",
     * never a fabricated empty diff). The read path decides the format
     * tri-state: JSON four-color leaf diff / line-level text diff / binary
     * SHA-256 digest only.
     */
    ResponseCompareView compare(long leftRunId, long rightRunId);
}
