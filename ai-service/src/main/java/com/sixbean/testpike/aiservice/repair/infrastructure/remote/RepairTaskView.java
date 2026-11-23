package com.sixbean.testpike.aiservice.repair.infrastructure.remote;


public record RepairTaskView(long projectId, long caseId, int status, String schemaVersion,
                             String actionJson, Integer aiFailureClass, String errorDetail) {
}
