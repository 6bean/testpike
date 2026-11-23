package com.sixbean.testpike.core.script.domain.service;

import com.sixbean.testpike.core.script.api.dto.Flowchart;
import com.sixbean.testpike.core.script.api.dto.ScriptInfo;
import com.sixbean.testpike.core.script.api.dto.ScriptSaveInput;
import com.sixbean.testpike.core.script.api.dto.ScriptVersionDiff;
import com.sixbean.testpike.core.script.api.dto.ScriptVersionInfo;

import java.util.List;


public interface ScriptService {

    
    ScriptInfo save(ScriptSaveInput input);

    
    ScriptInfo saveRepaired(long caseId, String schemaVersion, String actionJson);

    
    ScriptInfo getByCaseId(long caseId);

    
    void markPending(long caseId);

    
    List<ScriptVersionInfo> listVersions(long caseId);

    
    ScriptVersionDiff diffVersion(long caseId, int version, Integer baseVersion);

    
    Flowchart flowchart(long caseId);

    
    Flowchart previewFlowchart(long caseId, String actionJson);
}
