package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.core.appauto.api.dto.AppautoStepSuggestionInput;
import com.sixbean.testpike.core.appauto.api.dto.AppautoStepSuggestionView;

import java.util.List;


public interface AppautoStepHealingService {

    
    long record(AppautoStepSuggestionInput input);

    
    List<AppautoStepSuggestionView> list(long projectId, long caseId, Integer status, int limit);

    
    AppautoStepSuggestionView find(long suggestionId);

    
    AppautoStepSuggestionView adopt(Long operatorId, long suggestionId, Integer expectedScriptVersion);

    
    void reject(Long operatorId, long suggestionId);
}
