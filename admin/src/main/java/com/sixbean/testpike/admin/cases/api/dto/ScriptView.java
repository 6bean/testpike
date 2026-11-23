package com.sixbean.testpike.admin.cases.api.dto;


public record ScriptView(long id, long caseId, String schemaVersion, String actionJson,
                         int status, int generatedBy, Integer lastTrialResult,
                         int version, String createdAt, String updatedAt) {
}
