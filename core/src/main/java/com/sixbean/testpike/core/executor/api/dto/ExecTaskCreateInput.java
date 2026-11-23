package com.sixbean.testpike.core.executor.api.dto;


public record ExecTaskCreateInput(long projectId, long caseId, String schemaVersion,
                                  String actionJson, Long envId, Long batchId,
                                  String envBaseUrlOverride, String browser) {

    
    public ExecTaskCreateInput(long projectId, long caseId, String schemaVersion,
                               String actionJson, Long envId, Long batchId) {
        this(projectId, caseId, schemaVersion, actionJson, envId, batchId, null, null);
    }

    
    public ExecTaskCreateInput(long projectId, long caseId, String schemaVersion,
                               String actionJson, Long envId, Long batchId,
                               String envBaseUrlOverride) {
        this(projectId, caseId, schemaVersion, actionJson, envId, batchId, envBaseUrlOverride, null);
    }

    
    public ExecTaskCreateInput(long projectId, long caseId, String schemaVersion,
                               String actionJson, Long envId) {
        this(projectId, caseId, schemaVersion, actionJson, envId, null, null);
    }

    
    public String effectiveBaseUrl(String envBaseUrl) {
        return envBaseUrlOverride == null || envBaseUrlOverride.isBlank()
                ? envBaseUrl : envBaseUrlOverride;
    }
}
