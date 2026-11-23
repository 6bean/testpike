package com.sixbean.testpike.admin.defects.api.dto;


public record DefectView(long id, long caseId, String defectKey, String defectStatus,
                         int status, int version, long createdBy, String createdAt) {
}
