package com.sixbean.testpike.core.defects.api.dto;


public record DefectInfo(long id, long caseId, String defectKey, String defectStatus,
                         int status, int version, long createdBy, String createdAt) {
}
