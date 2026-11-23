package com.sixbean.testpike.admin.cases.api.dto;

import java.util.List;


public record BatchAssignRequest(Long projectId, List<Long> caseIds, Long assigneeId) {
}
