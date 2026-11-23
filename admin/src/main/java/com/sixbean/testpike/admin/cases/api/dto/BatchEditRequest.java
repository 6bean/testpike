package com.sixbean.testpike.admin.cases.api.dto;

import java.util.List;


public record BatchEditRequest(Long projectId, List<Long> caseIds,
                               Integer phase, Integer priority, List<Long> tagIds) {
}
