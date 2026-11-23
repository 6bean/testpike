package com.sixbean.testpike.admin.cases.api.dto;


public record CaseVersionChangeView(String field, String label, Object before, Object after) {
}
