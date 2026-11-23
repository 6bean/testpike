package com.sixbean.testpike.core.visual.domain.service;

import com.sixbean.testpike.core.visual.api.dto.VisualBaselineCandidateInput;
import com.sixbean.testpike.core.visual.api.dto.VisualBaselineInfo;


public interface VisualBaselineService {

    
    VisualBaselineInfo recordCandidate(VisualBaselineCandidateInput input);

    
    VisualBaselineInfo confirm(long id, Long userId);

    
    void reject(long id, Long userId);

    
    VisualBaselineInfo getById(long id);

    
    VisualBaselineInfo findConfirmed(long projectId, String stepKey);

    
    java.util.List<VisualBaselineInfo> listByStep(long projectId, String stepKey);

    
    java.util.List<VisualBaselineInfo> listByCase(long projectId, long caseId);

    
    String viewUrl(long id);

    
    byte[] readBaselineBytes(String baselineKey);

    
    String grantBaselineViewUrl(String baselineKey);
}
