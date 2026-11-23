package com.sixbean.testpike.core.defects.api.dto;


public record DefectLibraryInfo(long id, long projectId, Long caseId, Long planId, String title,
                                String description, int severity, int status, Long assigneeId,
                                String assigneeName, long reporterId, String reporterName,
                                String stepsJson, String externalKey, String closedReason,
                                int source, int jiraSyncStatus, String jiraSyncError,
                                int version, String createdAt, String updatedAt) {
}
