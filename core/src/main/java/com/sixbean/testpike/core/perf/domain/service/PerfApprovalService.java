package com.sixbean.testpike.core.perf.domain.service;

import com.sixbean.testpike.core.perf.api.dto.PerfApprovalInput;

import java.util.List;
import java.util.Map;


public interface PerfApprovalService {

    
    long apply(long projectId, long applicantId, PerfApprovalInput input);

    
    void approve(long id, long approverId);

    
    void reject(long id, long approverId, String rejectReason);

    
    List<Map<String, Object>> list(long projectId);
}
