package com.sixbean.testpike.core.script.api.dto;


public record ScriptSaveInput(long caseId, String schemaVersion, String actionJson,
                              int generatedBy, Long operatorId, Integer expectedVersion) {

    
    public ScriptSaveInput(long caseId, String schemaVersion, String actionJson, int generatedBy) {
        this(caseId, schemaVersion, actionJson, generatedBy, null, null);
    }

    
    public ScriptSaveInput(long caseId, String schemaVersion, String actionJson,
                           int generatedBy, Long operatorId) {
        this(caseId, schemaVersion, actionJson, generatedBy, operatorId, null);
    }
}
