package com.sixbean.testpike.core.defects.api.dto;


public record DefectTransitionInput(Integer toStatus, String closedReason, Integer version) {
}
