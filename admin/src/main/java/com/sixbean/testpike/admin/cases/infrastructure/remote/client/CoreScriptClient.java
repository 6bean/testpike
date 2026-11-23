package com.sixbean.testpike.admin.cases.infrastructure.remote.client;

import com.sixbean.testpike.admin.cases.api.dto.FlowchartView;
import com.sixbean.testpike.admin.cases.api.dto.ScriptView;
import com.sixbean.testpike.admin.cases.api.dto.ScriptVersionDiffView;
import com.sixbean.testpike.admin.cases.api.dto.ScriptVersionView;

import java.util.List;


public interface CoreScriptClient {

    
    ScriptView getScript(long caseId);

    
    long regenerateScript(long caseId, long projectId, long operatorId);

 
    List<ScriptVersionView> listVersions(long caseId);

    
    ScriptVersionDiffView diffVersion(long caseId, int version, Integer baseVersion);

 
    FlowchartView flowchart(long caseId);

    
    ScriptView saveScript(long caseId, String schemaVersion, String actionJson,
                          int generatedBy, Long operatorId, Integer expectedVersion);

    
    FlowchartView previewFlowchart(long caseId, String actionJson);
}
