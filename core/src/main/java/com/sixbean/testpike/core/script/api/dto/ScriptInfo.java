package com.sixbean.testpike.core.script.api.dto;


public record ScriptInfo(long id, long caseId, String schemaVersion, String actionJson,
                         int status, int generatedBy, Integer lastTrialResult,
                         int version, String createdAt, String updatedAt) {
}
