package com.sixbean.testpike.admin.tags.api.dto;


public record TagView(long id, long projectId, String name, int type, long caseCount) {
}
