package com.sixbean.testpike.admin.tags.api.dto;

import java.util.List;


public record SetCaseTagsRequest(long projectId, List<Long> tagIds) {
}
