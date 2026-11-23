package com.sixbean.testpike.admin.defects.api.dto;


public record DefectTransitionRequest(Integer toStatus, String closedReason, Integer version) {
}
