package com.sixbean.testpike.admin.cases.api.dto;

import java.util.List;


public record AutoAssignRequest(long projectId, List<Long> caseIds, boolean preview) {
}
