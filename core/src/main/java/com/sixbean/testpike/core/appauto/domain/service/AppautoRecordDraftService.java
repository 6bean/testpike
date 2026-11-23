package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.core.appauto.api.dto.AppautoRecordDraftInput;
import com.sixbean.testpike.core.appauto.api.dto.AppautoRecordDraftView;

import java.util.List;


public interface AppautoRecordDraftService {

    
    java.util.Set<String> EVENT_SOURCES = java.util.Set.of("native", "scrcpy");

    
    long record(AppautoRecordDraftInput input);

    
    List<AppautoRecordDraftView> list(long caseId, Integer status, int limit);

    
    AppautoRecordDraftView find(long draftId);

    
    AppautoRecordDraftView confirm(Long operatorId, long draftId, List<Integer> checkedStepNos,
                                   Integer expectedScriptVersion);

    
    void reject(Long operatorId, long draftId);
}
