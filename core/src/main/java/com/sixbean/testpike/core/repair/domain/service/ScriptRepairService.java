package com.sixbean.testpike.core.repair.domain.service;

import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.repair.api.dto.ScriptRepairItem;
import com.sixbean.testpike.core.repair.api.dto.ScriptRepairRecordInput;
import com.sixbean.testpike.core.repair.api.dto.TrialRepairTaskInput;


public interface ScriptRepairService {

    
    ScriptRepairItem record(ScriptRepairRecordInput input);

    
    long trialTask(TrialRepairTaskInput input);

    
    long countRoundsWithin24h(long caseId);

    
    PageResult<ScriptRepairItem> list(Long projectId, Long caseId, int page, int size);
}
