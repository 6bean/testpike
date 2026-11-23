package com.sixbean.testpike.admin.defects.api.dto;


public record DefectLinkRequest(long caseId, String defectKey, String defectStatus) {
}
