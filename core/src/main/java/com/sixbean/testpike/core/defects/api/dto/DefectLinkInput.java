package com.sixbean.testpike.core.defects.api.dto;


public record DefectLinkInput(long caseId, String defectKey, String defectStatus) {
}
